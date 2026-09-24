package com.leonidaskiachidis.shiftvault

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate

val Context.dataStore by preferencesDataStore(name = "shift_vault_prefs")

/**
 * The format everything is stored in. Lenient on purpose: a field a later
 * version adds or drops, or an enum value this build doesn't know, must never
 * make the whole store unreadable — that used to decode as "no shifts at all",
 * and the next edit then saved that emptiness over the user's history.
 */
internal val StoreJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Stored text that no longer decodes. Thrown so it can be set aside, never overwritten. */
private class UnreadableDataException(cause: Throwable) : Exception(cause)

class ShiftManager(private val context: Context) {
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

    // --- CALENDAR SHIFTS ---

    /**
     * Applies [transform] to the shifts as they are stored *right now* and saves
     * the result, all inside one DataStore transaction.
     *
     * Screens used to read the map, change it, and save it back later. Two of
     * those overlapping (fast taps in paste mode) meant the second save was
     * built on a stale map and quietly dropped the first. Here the transform
     * always sees the latest committed value, so no update can be lost.
     *
     * Runs NonCancellable: a screen closing mid-save must not abandon the write.
     */
    suspend fun updateShifts(
        transform: (Map<LocalDate, List<Shift>>) -> Map<LocalDate, List<Shift>>
    ) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                val current = readShiftsForWrite(preferences)
                val updated = transform(current).filterValues { it.isNotEmpty() }
                preferences[SHIFTS_KEY] = encodeShiftMap(updated)
            }
        }
    }

    val getShifts: Flow<Map<LocalDate, List<Shift>>> = context.dataStore.data.map { preferences ->
        // Unreadable data shows as empty here, but is never written back as
        // empty: every write path sets it aside first (see readShiftsForWrite).
        try {
            decodeShiftMap(preferences[SHIFTS_KEY])
        } catch (_: UnreadableDataException) {
            emptyMap()
        }
    }

    val getCurrency: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[CURRENCY_KEY] ?: "EUR"
        }

    // --- TEMPLATES ---

    /** Same contract as [updateShifts], for the saved-shift library. */
    suspend fun updateTemplates(transform: (List<Shift>) -> List<Shift>) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                val current = readTemplatesForWrite(preferences)
                preferences[TEMPLATES_KEY] = StoreJson.encodeToString(transform(current))
            }
        }
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

    val getTemplates: Flow<List<Shift>> = context.dataStore.data.map { preferences ->
        try {
            decodeTemplates(preferences[TEMPLATES_KEY])
        } catch (_: UnreadableDataException) {
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  UNREADABLE DATA
    //
    //  If stored shifts or saved shifts ever stop decoding, the raw text is
    //  moved to its own timestamped key — never overwritten, and included in
    //  Android's auto backup — and the app carries on with an empty store. A
    //  later version can read the copy back; nothing is silently lost.
    // ══════════════════════════════════════════════════════════════

    /** Checks both stores once, at start-up, and sets aside anything unreadable. */
    suspend fun setAsideUnreadableData() {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                readShiftsForWrite(preferences)
                readTemplatesForWrite(preferences)
            }
        }
    }

    /** True while there is set-aside data the user hasn't been told about yet. */
    val hasUnreadableDataNotice: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[UNREADABLE_NOTICE_KEY] == true
    }

    suspend fun acknowledgeUnreadableDataNotice() {
        context.dataStore.edit { preferences -> preferences.remove(UNREADABLE_NOTICE_KEY) }
    }

    private fun readShiftsForWrite(preferences: MutablePreferences): Map<LocalDate, List<Shift>> =
        try {
            decodeShiftMap(preferences[SHIFTS_KEY])
        } catch (_: UnreadableDataException) {
            setAside(preferences, SHIFTS_KEY)
            emptyMap()
        }

    private fun readTemplatesForWrite(preferences: MutablePreferences): List<Shift> =
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
    //  nothing stored, so the flow hands back the default profile and the
    //  decoders above stamp every legacy record onto it — the user sees their
    //  data exactly where they left it, now under a name.
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
     */
    suspend fun deleteProfile(id: String) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                val remaining = decodeProfiles(preferences[PROFILES_KEY]).filterNot { it.id == id }
                if (remaining.isEmpty()) return@edit

                preferences[PROFILES_KEY] = StoreJson.encodeToString(remaining)

                val shifts = readShiftsForWrite(preferences)
                    .mapValues { (_, list) -> list.filterNot { it.jobId == id } }
                    .filterValues { it.isNotEmpty() }
                preferences[SHIFTS_KEY] = encodeShiftMap(shifts)

                val templates = readTemplatesForWrite(preferences).filterNot { it.jobId == id }
                preferences[TEMPLATES_KEY] = StoreJson.encodeToString(templates)

                if (preferences[ACTIVE_PROFILE_KEY] == id) {
                    preferences[ACTIVE_PROFILE_KEY] = remaining.first().id
                }
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

        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                val all = readTemplatesForWrite(preferences)
                val existing = all.filter { it.jobId == toId }.map { it.identity() }.toMutableSet()

                val additions = mutableListOf<Shift>()
                for (shift in shifts) {
                    val moved = shift.copy(jobId = toId)
                    if (existing.add(moved.identity())) additions += moved
                }

                result = CopyResult(copied = additions.size, skipped = shifts.size - additions.size)
                if (additions.isNotEmpty()) {
                    preferences[TEMPLATES_KEY] = StoreJson.encodeToString(all + additions)
                }
            }
        }
        return result
    }

    // ── Decoders, shared by the flows and the edit blocks above ──

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

    private fun encodeShiftMap(shifts: Map<LocalDate, List<Shift>>): String =
        StoreJson.encodeToString(shifts.mapKeys { it.key.toString() })

    // --- BACKUP & RESTORE ---

    /**
     * Collects current shifts and templates into a BackupData object.
     */
    suspend fun getAllDataForBackup(): BackupData {
        val preferences = context.dataStore.data.first()

        // Unreadable data is set aside at start-up, so empty here really is empty.
        val shifts: Map<String, List<Shift>> =
            try {
                decodeShiftMap(preferences[SHIFTS_KEY])
            } catch (_: UnreadableDataException) {
                emptyMap()
            }.mapKeys { it.key.toString() }

        val templates: List<Shift> =
            try {
                decodeTemplates(preferences[TEMPLATES_KEY])
            } catch (_: UnreadableDataException) {
                emptyList()
            }

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
     * default one is restored alongside it and the decoders fold that backup's
     * shifts into it — importing an old backup keeps working unchanged.
     */
    suspend fun restoreFromBackup(backupData: BackupData) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[SHIFTS_KEY] = StoreJson.encodeToString(backupData.shifts)
                preferences[TEMPLATES_KEY] = StoreJson.encodeToString(backupData.templates)

                val profiles = backupData.profiles.ifEmpty { listOf(JobProfile.Default) }
                preferences[PROFILES_KEY] = StoreJson.encodeToString(profiles)
                preferences[ACTIVE_PROFILE_KEY] =
                    profiles.firstOrNull { it.id == backupData.activeProfileId }?.id
                        ?: profiles.first().id
            }
        }
    }
}
