package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  ShiftDatabase — where shifts live.
 *
 *  Calendar shifts and saved shifts are rows in SQLite, one per shift. They
 *  used to be one JSON string each inside DataStore, which meant every tap
 *  re-encoded and rewrote the user's entire history. Now an edit touches only
 *  the rows of the days it changed.
 *
 *  Settings and Job Profiles stay in DataStore: they are small key-value data,
 *  which is what DataStore is for.
 *
 *  Nothing outside ShiftManager talks to this file — screens keep working with
 *  Shift and Map<LocalDate, List<Shift>> exactly as before.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

// ══════════════════════════════════════════════════════════════
//  Tables
// ══════════════════════════════════════════════════════════════

/**
 * The columns every stored shift carries, shared by both tables. Only the
 * current model is stored — legacy v1 flags are folded in by migrated()
 * before a row is ever written, and jobId is always a real profile id.
 */
data class ShiftColumns(
    val name: String,
    val startTime: String,
    val endTime: String,
    val colorValue: Long,
    val hourlyRate: Double,
    val payMultiplier: Double,
    val bonus: Double,
    val breakMinutes: Int,
    val paidBreak: Boolean,
    /** DayType by name, so a value this build doesn't know can fall back safely. */
    val dayType: String,
    val paidLeaveHours: Double,
    val location: String,
    val jobId: String,
    val notes: String,
    val iconName: String?
)

/** A shift on a calendar day. [position] keeps the order they were added in. */
@Entity(
    tableName = "calendar_shifts",
    indices = [Index("date"), Index("jobId")]
)
data class CalendarShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO-8601 ("2026-09-01"), so text order is date order. */
    val date: String,
    val position: Int,
    @Embedded val shift: ShiftColumns
)

/** An entry in the saved-shift library. [position] is its place in the list. */
@Entity(
    tableName = "saved_shifts",
    indices = [Index("jobId")]
)
data class SavedShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val position: Int,
    @Embedded val shift: ShiftColumns
)

/** Small facts about the database itself, e.g. that the DataStore import ran. */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val name: String,
    val value: String
)

// ══════════════════════════════════════════════════════════════
//  Queries
// ══════════════════════════════════════════════════════════════

@Dao
interface ShiftDao {

    // ── Calendar ──

    @Query("SELECT * FROM calendar_shifts ORDER BY date, position")
    fun observeCalendar(): Flow<List<CalendarShiftEntity>>

    @Query("SELECT * FROM calendar_shifts ORDER BY date, position")
    suspend fun calendar(): List<CalendarShiftEntity>

    @Query("DELETE FROM calendar_shifts WHERE date = :date")
    suspend fun deleteDay(date: String)

    @Query("DELETE FROM calendar_shifts WHERE jobId = :jobId")
    suspend fun deleteCalendarForJob(jobId: String)

    @Query("DELETE FROM calendar_shifts")
    suspend fun clearCalendar()

    @Insert
    suspend fun insertCalendar(rows: List<CalendarShiftEntity>)

    // ── Saved shifts ──

    @Query("SELECT * FROM saved_shifts ORDER BY position")
    fun observeSaved(): Flow<List<SavedShiftEntity>>

    @Query("SELECT * FROM saved_shifts ORDER BY position")
    suspend fun saved(): List<SavedShiftEntity>

    @Query("DELETE FROM saved_shifts WHERE jobId = :jobId")
    suspend fun deleteSavedForJob(jobId: String)

    @Query("DELETE FROM saved_shifts")
    suspend fun clearSaved()

    @Insert
    suspend fun insertSaved(rows: List<SavedShiftEntity>)

    // ── Meta ──

    @Query("SELECT value FROM meta WHERE name = :name")
    suspend fun meta(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(row: MetaEntity)
}

// ══════════════════════════════════════════════════════════════
//  Database
// ══════════════════════════════════════════════════════════════

/**
 * Version 1. Any change to the tables above needs a version bump and a
 * Migration — the exported schema in app/schemas is what that is checked
 * against. Never use fallbackToDestructiveMigration here: it deletes the
 * user's shifts.
 */
@Database(
    entities = [CalendarShiftEntity::class, SavedShiftEntity::class, MetaEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ShiftDatabase : RoomDatabase() {
    abstract fun dao(): ShiftDao

    companion object {
        /** Set once the shifts stored in DataStore have been copied in. */
        const val META_IMPORTED_FROM_DATASTORE = "imported_from_datastore"

        @Volatile private var instance: ShiftDatabase? = null

        /** One instance per process; Room is not meant to be opened twice. */
        fun get(context: Context): ShiftDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShiftDatabase::class.java,
                    "shiftvault.db"
                ).build().also { instance = it }
            }
    }
}

// ══════════════════════════════════════════════════════════════
//  Mapping — pure, so it is covered by plain JVM tests
// ══════════════════════════════════════════════════════════════

internal fun Shift.toColumns(): ShiftColumns {
    val s = migrated().withProfile()
    return ShiftColumns(
        name = s.name,
        startTime = s.startTime,
        endTime = s.endTime,
        colorValue = s.colorValue,
        hourlyRate = s.hourlyRate,
        payMultiplier = s.payMultiplier,
        bonus = s.bonus,
        breakMinutes = s.breakMinutes,
        paidBreak = s.paidBreak,
        dayType = s.dayType.name,
        paidLeaveHours = s.paidLeaveHours,
        location = s.location,
        jobId = s.jobId ?: JobProfile.DEFAULT_ID,
        notes = s.notes,
        iconName = s.iconName
    )
}

internal fun ShiftColumns.toShift(): Shift = Shift(
    name = name,
    startTime = startTime,
    endTime = endTime,
    colorValue = colorValue,
    hourlyRate = hourlyRate,
    payMultiplier = payMultiplier,
    bonus = bonus,
    breakMinutes = breakMinutes,
    paidBreak = paidBreak,
    dayType = DayType.entries.firstOrNull { it.name == dayType } ?: DayType.WORKED,
    paidLeaveHours = paidLeaveHours,
    location = location,
    jobId = jobId,
    notes = notes,
    iconName = iconName
)

internal fun calendarRows(date: LocalDate, shifts: List<Shift>): List<CalendarShiftEntity> =
    shifts.mapIndexed { i, s -> CalendarShiftEntity(date = date.toString(), position = i, shift = s.toColumns()) }

internal fun savedRows(shifts: List<Shift>): List<SavedShiftEntity> =
    shifts.mapIndexed { i, s -> SavedShiftEntity(position = i, shift = s.toColumns()) }

/** Rows (already ordered by date, position) back into the map the app works with. */
internal fun List<CalendarShiftEntity>.toShiftMap(): Map<LocalDate, List<Shift>> =
    groupBy({ LocalDate.parse(it.date) }, { it.shift.toShift() })

/** The days whose shifts differ between [old] and [new] — the only ones to rewrite. */
internal fun changedDays(
    old: Map<LocalDate, List<Shift>>,
    new: Map<LocalDate, List<Shift>>
): Set<LocalDate> =
    (old.keys + new.keys).filterTo(HashSet()) { old[it].orEmpty() != new[it].orEmpty() }
