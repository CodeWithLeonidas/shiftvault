package com.leonidaskiachidis.shiftvault

import android.content.Context
import android.net.Uri
import android.widget.Toast
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Job Profiles were added without bumping [version]: both new fields default,
 * so a v1 file written before them still decodes, and a file written now still
 * opens in an older build (which simply ignores the extra keys).
 */
@Serializable
data class BackupData(
    val version: Int = 1,
    val shifts: Map<String, List<Shift>> = emptyMap(),
    val templates: List<Shift> = emptyList(),
    val profiles: List<JobProfile> = emptyList(),
    val activeProfileId: String = JobProfile.DEFAULT_ID,
    /** True in backups made since shifts carry the Extra switch. */
    val extraFlagged: Boolean = false,
    /**
     * True in backups made since the ledger existed. Older files lack it, and
     * restoring one then leaves the ledger alone instead of emptying it.
     */
    val ledgerIncluded: Boolean = false,
    val earnings: List<Earning> = emptyList(),
    val payments: List<Payment> = emptyList()
)

object BackupHelper {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
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
     *
     * Every date key is checked here too: one bad key would make the restored
     * store unreadable as a whole, so a file like that is refused up front.
     */
    fun deserialize(jsonString: String): BackupData? {
        return try {
            json.decodeFromString<BackupData>(jsonString).also { data ->
                data.shifts.keys.forEach { java.time.LocalDate.parse(it) }
                // Same for the ledger: a bad date would break every balance.
                data.earnings.forEach { it.period }
                data.payments.forEach { it.day }
            }
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
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
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

/**
 * How old the last backup is, in words for the Settings row. Counted in
 * calendar days, so a backup made last night is "yesterday" this morning.
 */
object BackupAge {
    /** After this many days without a backup, Settings starts pointing at it. */
    const val STALE_DAYS = 14L

    private fun daysSince(lastAt: Long, now: Long, zone: java.time.ZoneId): Long {
        val then = java.time.Instant.ofEpochMilli(lastAt).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(then, today).coerceAtLeast(0L)
    }

    fun label(lastAt: Long?, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        if (lastAt == null) return "Never backed up"
        return when (val days = daysSince(lastAt, now, zone)) {
            0L -> "Last backup: today"
            1L -> "Last backup: yesterday"
            else -> "Last backup: $days days ago"
        }
    }

    fun isStale(lastAt: Long?, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean =
        lastAt == null || daysSince(lastAt, now, zone) >= STALE_DAYS
}
