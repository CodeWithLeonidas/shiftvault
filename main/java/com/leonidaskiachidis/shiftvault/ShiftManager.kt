package com.leonidaskiachidis.shiftvault

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.LocalDate

val Context.dataStore by preferencesDataStore(name = "shift_vault_prefs")

class ShiftManager(private val context: Context) {
    private val SHIFTS_KEY = stringPreferencesKey("saved_shifts_map")
    private val TEMPLATES_KEY = stringPreferencesKey("custom_shift_templates")
    private val CURRENCY_KEY = stringPreferencesKey("selected_currency")

    // --- CALENDAR SHIFTS (Updated to List) ---
    suspend fun saveShifts(shifts: Map<LocalDate, List<Shift>>) {
        val jsonString = Json.encodeToString(shifts.mapKeys { it.key.toString() })
        context.dataStore.edit { preferences ->
            preferences[SHIFTS_KEY] = jsonString
        }
    }

    val getShifts: Flow<Map<LocalDate, List<Shift>>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[SHIFTS_KEY] ?: ""
        if (jsonString.isEmpty()) emptyMap()
        else {
            try {
                val rawMap: Map<String, List<Shift>> = Json.decodeFromString(jsonString)
                rawMap.mapKeys { LocalDate.parse(it.key) }
            } catch (e: Exception) { emptyMap() }
        }
    }

    val getCurrency: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[CURRENCY_KEY] ?: "EUR"
        }

    // --- TEMPLATES ---
    suspend fun saveTemplates(templates: List<Shift>) {
        val jsonString = Json.encodeToString(templates)
        context.dataStore.edit { preferences ->
            preferences[TEMPLATES_KEY] = jsonString
        }
    }

    suspend fun saveCurrency(code: String) {
        context.dataStore.edit { preferences ->
            preferences[CURRENCY_KEY] = code
        }
    }

    val getTemplates: Flow<List<Shift>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[TEMPLATES_KEY] ?: ""
        if (jsonString.isEmpty()) emptyList()
        else {
            try { Json.decodeFromString(jsonString) }
            catch (e: Exception) { emptyList() }
        }
    }

    // --- BACKUP & RESTORE ---

    /**
     * Collects current shifts and templates into a BackupData object.
     */
    suspend fun getAllDataForBackup(): BackupData {
        val preferences = context.dataStore.data.first()

        val shifts: Map<String, List<Shift>> = try {
            val shiftsJson = preferences[SHIFTS_KEY] ?: ""
            if (shiftsJson.isEmpty()) emptyMap()
            else Json.decodeFromString(shiftsJson)
        } catch (_: Exception) { emptyMap() }

        val templates: List<Shift> = try {
            val templatesJson = preferences[TEMPLATES_KEY] ?: ""
            if (templatesJson.isEmpty()) emptyList()
            else Json.decodeFromString(templatesJson)
        } catch (_: Exception) { emptyList() }

        return BackupData(
            version = 1,
            shifts = shifts,
            templates = templates
        )
    }

    /**
     * Overwrites all shifts and templates with the data from a BackupData object.
     */
    suspend fun restoreFromBackup(backupData: BackupData) {
        context.dataStore.edit { preferences ->
            val shiftsJson = Json.encodeToString(backupData.shifts)
            preferences[SHIFTS_KEY] = shiftsJson

            val templatesJson = Json.encodeToString(backupData.templates)
            preferences[TEMPLATES_KEY] = templatesJson
        }
    }
}
