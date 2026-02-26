package com.leonidaskiachidis.shiftvault

import android.content.Context
import android.net.Uri
import android.widget.Toast
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader

@Serializable
data class BackupData(
    val version: Int = 1,
    val shifts: Map<String, List<Shift>> = emptyMap(),
    val templates: List<Shift> = emptyList()
)

object BackupHelper {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Serializes a BackupData object to a pretty-printed JSON string.
     */
    fun serialize(backupData: BackupData): String {
        return json.encodeToString(backupData)
    }

    /**
     * Deserializes a JSON string to a BackupData object.
     * Returns null if the string is invalid or cannot be parsed.
     */
    fun deserialize(jsonString: String): BackupData? {
        return try {
            json.decodeFromString<BackupData>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Writes the given JSON string to the URI chosen by the user via SAF.
     * Returns true on success, false on failure.
     */
    fun writeToUri(context: Context, uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Reads the full text content from the URI chosen by the user via SAF.
     * Returns null on failure.
     */
    fun readFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Generates a suggested backup file name based on today's date.
     */
    fun generateBackupFileName(): String {
        val today = java.time.LocalDate.now()
        return "shiftvault_backup_$today.json"
    }
}
