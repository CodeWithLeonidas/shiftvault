package com.leonidaskiachidis.shiftvault

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate

val Context.dataStore by preferencesDataStore(name = "shift_vault_prefs")

/**
 * The JSON format of backups and of the pre-Room store. Lenient on purpose: a
 * field a later version adds or drops, or an enum value this build doesn't
 * know, must never make data unreadable.
 */
internal val StoreJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Stored text that no longer decodes. Thrown so it can be set aside, never overwritten. */
private class UnreadableDataException(cause: Throwable) : Exception(cause)

/**
 * The one door to the app's data. Shifts and saved shifts live in Room
 * ([ShiftDatabase]); settings and Job Profiles live in DataStore. Screens only
 * ever see Shift and Map<LocalDate, List<Shift>>, whichever store is behind it.
 */
class ShiftManager(private val context: Context) {

    // ── DataStore keys ──
    // SHIFTS_KEY / TEMPLATES_KEY held all shifts as JSON before Room. They are
    // read once, by the import below, and otherwise left alone as a safety
    // copy. A later version can remove them.
    private val SHIFTS_KEY = stringPreferencesKey("saved_shifts_map")
    private val TEMPLATES_KEY = stringPreferencesKey("custom_shift_templates")
    private val CURRENCY_KEY = stringPreferencesKey("selected_currency")
    private val START_OF_WEEK_KEY = stringPreferencesKey("start_of_week")
    private val THEME_KEY = stringPreferencesKey("theme_mode")
    private val THEME_VARIANT_KEY = stringPreferencesKey("theme_variant") // legacy, unused
    private val LIGHT_THEME_VARIANT_KEY = stringPreferencesKey("light_theme_variant")
    private val DARK_THEME_VARIANT_KEY = stringPreferencesKey("dark_theme_variant")
    private val PROFILES_KEY = stringPreferencesKey("job_profiles")
    private val ACTIVE_PROFILE_KEY = stringPreferencesKey("active_job_profile")

    /** Set when unreadable data was set aside; cleared once the user has been told. */
    private val UNREADABLE_NOTICE_KEY = booleanPreferencesKey("unreadable_data_notice")

    // ══════════════════════════════════════════════════════════════
    //  DATABASE ACCESS
    //
    //  Every read and write goes through db(), which makes sure the one-time
    //  import from DataStore has finished first. Without that gate a screen
    //  could write to the empty database while the import was still running.
    // ══════════════════════════════════════════════════════════════

    private suspend fun db(): ShiftDatabase {
        readyDatabase?.let { return it }
        return readyLock.withLock {
            readyDatabase ?: ShiftDatabase.get(context).also { database ->
                withContext(NonCancellable + Dispatchers.IO) { importFromDataStoreIfNeeded(database) }
                readyDatabase = database
            }
        }
    }

    /** Opens the database and runs the import now, rather than on first use. */
    suspend fun prepareStorage() {
        db()
    }

    /**
     * Copies the shifts that were stored as JSON in DataStore into Room, once.
     *
     * The "done" marker is written in the same transaction as the rows, so an
     * import cut short (app killed, phone off) leaves nothing behind and simply
     * runs again next time — it can never half-import or import twice.
     */
    private suspend fun importFromDataStoreIfNeeded(database: ShiftDatabase) {
        val dao = database.dao()
        if (dao.meta(ShiftDatabase.META_IMPORTED_FROM_DATASTORE) != null) return

        // Anything that doesn't decode is set aside (and the user told) rather
        // than imported as "nothing" — the raw text stays in DataStore.
        var shifts: Map<LocalDate, List<Shift>> = emptyMap()
        var templates: List<Shift> = emptyList()
        context.dataStore.edit { preferences ->
            shifts = readShiftsOrSetAside(preferences)
            templates = readTemplatesOrSetAside(preferences)
        }

        database.withTransaction {
            if (dao.meta(ShiftDatabase.META_IMPORTED_FROM_DATASTORE) != null) return@withTransaction
            dao.clearCalendar()
            dao.clearSaved()
            dao.insertCalendar(shifts.flatMap { (date, list) -> calendarRows(date, list) })
            dao.insertSaved(savedRows(templates))
            dao.putMeta(MetaEntity(ShiftDatabase.META_IMPORTED_FROM_DATASTORE, System.currentTimeMillis().toString()))
        }
    }

    // --- CALENDAR SHIFTS ---

    /**
     * Applies [transform] to the shifts as they are stored *right now* and saves
     * the result, all inside one database transaction.
     *
     * The transform always sees the latest committed data, so overlapping
     * updates (fast taps in paste mode) each land on top of one another instead
     * of the later one overwriting the earlier. Only the days that actually
     * changed are rewritten.
     *
     * Runs NonCancellable: a screen closing mid-save must not abandon the write.
     */
    suspend fun updateShifts(
        transform: (Map<LocalDate, List<Shift>>) -> Map<LocalDate, List<Shift>>
    ) {
        withContext(NonCancellable) {
            val database = db()
            val dao = database.dao()
            database.withTransaction {
                val current = dao.calendar().toShiftMap()
                val updated = transform(current).filterValues { it.isNotEmpty() }
                for (date in changedDays(current, updated)) {
                    dao.deleteDay(date.toString())
                    updated[date]?.let { dao.insertCalendar(calendarRows(date, it)) }
                }
            }
        }
    }

    val getShifts: Flow<Map<LocalDate, List<Shift>>> =
        flow { emitAll(db().dao().observeCalendar()) }
            .map { rows -> rows.toShiftMap() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // --- TEMPLATES ---

    /** Same contract as [updateShifts], for the saved-shift library. */
    suspend fun updateTemplates(transform: (List<Shift>) -> List<Shift>) {
        withContext(NonCancellable) {
            val database = db()
            val dao = database.dao()
            database.withTransaction {
                val current = dao.saved().map { it.shift.toShift() }
                val updated = transform(current)
                if (updated != current) {
                    // The library is short and its order is its identity (edits
                    // are by index), so it is rewritten as a whole.
                    dao.clearSaved()
                    dao.insertSaved(savedRows(updated))
                }
            }
        }
    }

    val getTemplates: Flow<List<Shift>> =
        flow { emitAll(db().dao().observeSaved()) }
            .map { rows -> rows.map { it.shift.toShift() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    // --- CURRENCY ---
    val getCurrency: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[CURRENCY_KEY] ?: "EUR"
        }

    suspend fun saveCurrency(code: String) {
        context.dataStore.edit { preferences ->
            preferences[CURRENCY_KEY] = code
        }
    }

    // --- START OF WEEK ---
    val getStartOfWeek: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[START_OF_WEEK_KEY] ?: "Monday"
        }

    suspend fun saveStartOfWeek(day: String) {
        context.dataStore.edit { preferences ->
            preferences[START_OF_WEEK_KEY] = day
        }
    }

    // --- THEME MODE ---
    val getThemeMode: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[THEME_KEY] ?: "system"  // "dark", "light", or "system"
        }

    suspend fun saveThemeMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = mode
        }
    }

    // --- THEME VARIANT (separate for light & dark) ---
    val getLightThemeVariant: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[LIGHT_THEME_VARIANT_KEY] ?: "default"
        }

    val getDarkThemeVariant: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[DARK_THEME_VARIANT_KEY] ?: "default"
        }

    suspend fun saveLightThemeVariant(variant: String) {
        context.dataStore.edit { preferences ->
            preferences[LIGHT_THEME_VARIANT_KEY] = variant
        }
    }

    suspend fun saveDarkThemeVariant(variant: String) {
        context.dataStore.edit { preferences ->
            preferences[DARK_THEME_VARIANT_KEY] = variant
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  UNREADABLE DATA
    //
    //  If the pre-Room JSON ever fails to decode during the import, the raw
    //  text is moved to its own timestamped DataStore key — never overwritten,
    //  and included in Android's auto backup — and the app carries on. A later
    //  version can read the copy back; nothing is silently lost.
    // ══════════════════════════════════════════════════════════════

    /** True while there is set-aside data the user hasn't been told about yet. */
    val hasUnreadableDataNotice: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[UNREADABLE_NOTICE_KEY] == true
    }

    suspend fun acknowledgeUnreadableDataNotice() {
        context.dataStore.edit { preferences -> preferences.remove(UNREADABLE_NOTICE_KEY) }
    }

    private fun readShiftsOrSetAside(preferences: MutablePreferences): Map<LocalDate, List<Shift>> =
        try {
            decodeShiftMap(preferences[SHIFTS_KEY])
        } catch (_: UnreadableDataException) {
            setAside(preferences, SHIFTS_KEY)
            emptyMap()
        }

    private fun readTemplatesOrSetAside(preferences: MutablePreferences): List<Shift> =
        try {
            decodeTemplates(preferences[TEMPLATES_KEY])
        } catch (_: UnreadableDataException) {
            setAside(preferences, TEMPLATES_KEY)
            emptyList()
        }

    private fun setAside(preferences: MutablePreferences, key: Preferences.Key<String>) {
        val raw = preferences[key] ?: return
        val copyKey = stringPreferencesKey("unreadable_${key.name}_${System.currentTimeMillis()}")
        preferences[copyKey] = raw
        preferences.remove(key)
        preferences[UNREADABLE_NOTICE_KEY] = true
    }

    // ══════════════════════════════════════════════════════════════
    //  JOB PROFILES
    //
    //  One profile always exists. An install that predates this feature has
    //  nothing stored, so the flow hands back the default profile and every
    //  legacy record is stamped onto it — the user sees their data exactly
    //  where they left it, now under a name.
    // ══════════════════════════════════════════════════════════════

    val getProfiles: Flow<List<JobProfile>> = context.dataStore.data.map { preferences ->
        decodeProfiles(preferences[PROFILES_KEY])
    }

    /**
     * The id of the profile the whole app is scoped to. Falls back to the first
     * profile whenever the stored id no longer resolves (deleted on another
     * device, restored from an older backup), so the app can never be pinned to
     * a profile that isn't there.
     */
    val getActiveProfileId: Flow<String> = context.dataStore.data.map { preferences ->
        val profiles = decodeProfiles(preferences[PROFILES_KEY])
        val stored = preferences[ACTIVE_PROFILE_KEY]
        if (profiles.any { it.id == stored }) stored!! else profiles.first().id
    }

    suspend fun saveActiveProfileId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[ACTIVE_PROFILE_KEY] = id
        }
    }

    /** Adds the profile, or replaces the one already carrying its id. */
    suspend fun saveProfile(profile: JobProfile) {
        context.dataStore.edit { preferences ->
            val current = decodeProfiles(preferences[PROFILES_KEY]).toMutableList()
            val at = current.indexOfFirst { it.id == profile.id }
            if (at >= 0) current[at] = profile else current.add(profile)
            preferences[PROFILES_KEY] = StoreJson.encodeToString(current)
        }
    }

    /**
     * Removes a profile together with everything filed under it. Deleting the
     * last profile is refused — the app is always scoped to one — and deleting
     * the active one moves the selection to whatever is left.
     *
     * The profile goes first: if the app were stopped between the two steps,
     * what remained would be rows of a profile that no longer exists, which no
     * screen shows — never a profile whose shifts had vanished.
     */
    suspend fun deleteProfile(id: String) {
        withContext(NonCancellable) {
            var deleted = false
            context.dataStore.edit { preferences ->
                val remaining = decodeProfiles(preferences[PROFILES_KEY]).filterNot { it.id == id }
                if (remaining.isEmpty()) return@edit

                preferences[PROFILES_KEY] = StoreJson.encodeToString(remaining)
                if (preferences[ACTIVE_PROFILE_KEY] == id) {
                    preferences[ACTIVE_PROFILE_KEY] = remaining.first().id
                }
                deleted = true
            }
            if (!deleted) return@withContext

            val database = db()
            database.withTransaction {
                database.dao().deleteCalendarForJob(id)
                database.dao().deleteSavedForJob(id)
            }
        }
    }

    /**
     * Files copies of [shifts] under [toId]. The originals are never read back
     * out of their own profile, let alone changed — a copy adds, it never moves.
     *
     * The duplicate guard is kept here as well as in the picker: the picker
     * greys out what is already there, and this makes sure a stale selection
     * still can't land the same shift twice.
     *
     * @return how many were copied, and how many were already there.
     */
    suspend fun copySavedShiftsToProfile(toId: String, shifts: List<Shift>): CopyResult {
        var result = CopyResult(0, 0)
        if (shifts.isEmpty()) return result

        updateTemplates { all ->
            val existing = all.filter { it.jobId == toId }.map { it.identity() }.toMutableSet()
            val additions = mutableListOf<Shift>()
            for (shift in shifts) {
                val moved = shift.copy(jobId = toId)
                if (existing.add(moved.identity())) additions += moved
            }
            result = CopyResult(copied = additions.size, skipped = shifts.size - additions.size)
            all + additions
        }
        return result
    }

    // ── JSON decoders: profiles, and the pre-Room shift store ──

    private fun decodeProfiles(json: String?): List<JobProfile> {
        if (json.isNullOrEmpty()) return listOf(JobProfile.Default)
        return try {
            StoreJson.decodeFromString<List<JobProfile>>(json).ifEmpty { listOf(JobProfile.Default) }
        } catch (_: Exception) { listOf(JobProfile.Default) }
    }

    /**
     * Migrates at the edge: everything downstream sees new fields only, and a
     * real jobId on every record. Throws [UnreadableDataException] rather than
     * returning empty, so no caller can mistake "unreadable" for "none".
     */
    private fun decodeShiftMap(json: String?): Map<LocalDate, List<Shift>> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            StoreJson.decodeFromString<Map<String, List<Shift>>>(json)
                .mapKeys { LocalDate.parse(it.key) }
                .mapValues { (_, list) -> list.map { it.migrated().withProfile() } }
        } catch (e: Exception) {
            throw UnreadableDataException(e)
        }
    }

    private fun decodeTemplates(json: String?): List<Shift> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            StoreJson.decodeFromString<List<Shift>>(json).map { it.migrated().withProfile() }
        } catch (e: Exception) {
            throw UnreadableDataException(e)
        }
    }

    // --- BACKUP & RESTORE ---

    /**
     * Collects current shifts and templates into a BackupData object. The file
     * format is unchanged by the move to Room, so old and new backups open in
     * either version.
     */
    suspend fun getAllDataForBackup(): BackupData {
        val dao = db().dao()
        val shifts = dao.calendar().toShiftMap().mapKeys { it.key.toString() }
        val templates = dao.saved().map { it.shift.toShift() }

        val preferences = context.dataStore.data.first()
        val profiles = decodeProfiles(preferences[PROFILES_KEY])

        return BackupData(
            version = 1,
            shifts = shifts,
            templates = templates,
            profiles = profiles,
            activeProfileId = preferences[ACTIVE_PROFILE_KEY] ?: profiles.first().id
        )
    }

    /**
     * Overwrites all shifts and templates with the data from a BackupData object.
     *
     * A file written before Job Profiles existed carries no profiles, so the
     * default one is restored alongside it and every record without a jobId is
     * folded into it — importing an old backup keeps working unchanged.
     *
     * Date keys were validated when the file was read (BackupHelper), so the
     * parse below cannot fail on a file that reached this point.
     */
    suspend fun restoreFromBackup(backupData: BackupData) {
        withContext(NonCancellable) {
            val database = db()
            val dao = database.dao()
            database.withTransaction {
                dao.clearCalendar()
                dao.clearSaved()
                dao.insertCalendar(
                    backupData.shifts.flatMap { (date, list) -> calendarRows(LocalDate.parse(date), list) }
                )
                dao.insertSaved(savedRows(backupData.templates))
            }

            context.dataStore.edit { preferences ->
                val profiles = backupData.profiles.ifEmpty { listOf(JobProfile.Default) }
                preferences[PROFILES_KEY] = StoreJson.encodeToString(profiles)
                preferences[ACTIVE_PROFILE_KEY] =
                    profiles.firstOrNull { it.id == backupData.activeProfileId }?.id
                        ?: profiles.first().id
            }
        }
    }

    private companion object {
        /** Shared by every ShiftManager instance: the import runs once per process. */
        val readyLock = Mutex()
        @Volatile var readyDatabase: ShiftDatabase? = null
    }
}
