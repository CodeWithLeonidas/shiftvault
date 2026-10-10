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
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
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
import androidx.room.Transaction
import androidx.room.Update
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

/**
 * Money owed for work (see Ledger.kt). [type] is an EarningType by name;
 * dates are ISO-8601, so text order is date order; amounts are whole cents.
 */
@Entity(tableName = "earnings", indices = [Index("jobId")])
data class EarningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: String,
    val type: String,
    val description: String,
    val amountCents: Long,
    val periodStart: String,
    val periodEnd: String,
    val createdAt: Long,
    /** Added in version 3: hours the amount is for; null when not known. */
    val hours: Double? = null,
    /** Added in version 3: a shortfall the user accepted, closing the earning. */
    @ColumnInfo(defaultValue = "0") val shortfallAccepted: Boolean = false
)

/**
 * Money received towards one earning. No foreign key: deleting an earning
 * deletes its payments explicitly, in the same transaction (LedgerDao).
 */
@Entity(tableName = "payments", indices = [Index("earningId")])
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val earningId: Long,
    val amountCents: Long,
    val date: String,
    val note: String,
    val createdAt: Long,
    /** Added in version 3: hours of work the payment covers; null when not recorded. */
    val hoursPaid: Double? = null
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

@Dao
interface LedgerDao {

    @Query("SELECT * FROM earnings")
    fun observeEarnings(): Flow<List<EarningEntity>>

    @Query("SELECT * FROM payments")
    fun observePayments(): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM earnings")
    suspend fun earnings(): List<EarningEntity>

    @Query("SELECT * FROM payments")
    suspend fun payments(): List<PaymentEntity>

    @Query("SELECT * FROM earnings WHERE id = :id")
    suspend fun earning(id: Long): EarningEntity?

    /** Everything received so far towards one earning. */
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM payments WHERE earningId = :earningId")
    suspend fun paidCents(earningId: Long): Long

    @Insert
    suspend fun insertEarning(row: EarningEntity): Long

    @Update
    suspend fun updateEarning(row: EarningEntity)

    @Insert
    suspend fun insertEarningRows(rows: List<EarningEntity>)

    @Query("UPDATE earnings SET shortfallAccepted = :accepted WHERE id = :id")
    suspend fun setShortfallAccepted(id: Long, accepted: Boolean)

    @Insert
    suspend fun insertPayment(row: PaymentEntity): Long

    @Query("DELETE FROM payments WHERE id = :id")
    suspend fun deletePayment(id: Long)

    @Query("DELETE FROM payments WHERE earningId = :earningId")
    suspend fun deletePaymentsFor(earningId: Long)

    @Query("DELETE FROM earnings WHERE id = :id")
    suspend fun deleteEarningRow(id: Long)

    /** An earning and its payments go together, or not at all. */
    @Transaction
    suspend fun deleteEarning(id: Long) {
        deletePaymentsFor(id)
        deleteEarningRow(id)
    }

    @Query("DELETE FROM payments WHERE earningId IN (SELECT id FROM earnings WHERE jobId = :jobId)")
    suspend fun deletePaymentsForJob(jobId: String)

    @Query("DELETE FROM earnings WHERE jobId = :jobId")
    suspend fun deleteEarningsForJob(jobId: String)

    @Query("DELETE FROM earnings")
    suspend fun clearEarnings()

    @Query("DELETE FROM payments")
    suspend fun clearPayments()

    @Insert
    suspend fun insertEarnings(rows: List<EarningEntity>)

    @Insert
    suspend fun insertPayments(rows: List<PaymentEntity>)
}

// ══════════════════════════════════════════════════════════════
//  Database
// ══════════════════════════════════════════════════════════════

/**
 * Version 2 added the earnings and payments tables, and version 3 added hours
 * to both plus the accepted-shortfall flag — new tables and new columns only,
 * so Room's automatic migrations carry older versions over with every row
 * intact.
 *
 * Any change to the tables above needs a version bump and a migration — the
 * exported schemas in app/schemas are what those are checked against. Never
 * use fallbackToDestructiveMigration here: it deletes the user's data.
 */
@Database(
    entities = [
        CalendarShiftEntity::class, SavedShiftEntity::class, MetaEntity::class,
        EarningEntity::class, PaymentEntity::class
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)]
)
abstract class ShiftDatabase : RoomDatabase() {
    abstract fun dao(): ShiftDao
    abstract fun ledger(): LedgerDao

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

internal fun Earning.toEntity() = EarningEntity(
    id = id, jobId = jobId, type = type.name, description = description.trim(),
    amountCents = amountCents, periodStart = periodStart, periodEnd = periodEnd, createdAt = createdAt,
    hours = hours, shortfallAccepted = shortfallAccepted
)

/** A type this build doesn't know (written by a newer one) reads as "Other work". */
internal fun EarningEntity.toEarning() = Earning(
    id = id, jobId = jobId,
    type = EarningType.entries.firstOrNull { it.name == type } ?: EarningType.OTHER,
    description = description, amountCents = amountCents,
    periodStart = periodStart, periodEnd = periodEnd, createdAt = createdAt,
    hours = hours, shortfallAccepted = shortfallAccepted
)

internal fun Payment.toEntity() = PaymentEntity(
    id = id, earningId = earningId, amountCents = amountCents, date = date, note = note.trim(),
    createdAt = createdAt, hoursPaid = hoursPaid
)

internal fun PaymentEntity.toPayment() = Payment(
    id = id, earningId = earningId, amountCents = amountCents, date = date, note = note,
    createdAt = createdAt, hoursPaid = hoursPaid
)
