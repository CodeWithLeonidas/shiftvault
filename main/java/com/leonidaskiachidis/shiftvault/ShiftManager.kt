package com.leonidaskiachidis.shiftvault

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

val Context.dataStore by preferencesDataStore(name = "shift_vault_prefs")

class ShiftManager(private val context: Context) {
    private val SHIFTS_KEY = stringPreferencesKey("saved_shifts_map")
    private val TEMPLATES_KEY = stringPreferencesKey("custom_shift_templates")

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
                // Decode as Map<String, List<Shift>>
                val rawMap: Map<String, List<Shift>> = Json.decodeFromString(jsonString)
                rawMap.mapKeys { LocalDate.parse(it.key) }
            } catch (e: Exception) { emptyMap() }
        }
    }

    // --- TEMPLATES (NEW) ---
    suspend fun saveTemplates(templates: List<Shift>) {
        val jsonString = Json.encodeToString(templates)
        context.dataStore.edit { preferences ->
            preferences[TEMPLATES_KEY] = jsonString
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
}