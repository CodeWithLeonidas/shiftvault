package com.leonidaskiachidis.shiftvault

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

/**
 * What actually happened on a day. Kept separate from *how it is paid* — a
 * public holiday can be any of these three depending on the contract, so the
 * day type never implies a rate on its own.
 */
@Serializable
enum class DayType {
    /** Time was worked. Pay comes from hours × rate × multiplier. */
    WORKED,

    /** Away, still paid: annual leave, sick leave, a paid public holiday. */
    PAID_LEAVE,

    /** Away, not paid: unpaid holiday, unpaid absence. */
    UNPAID_LEAVE
}

/** Common pay multipliers, offered as presets in the shift form. */
object PayMultipliers {
    const val NORMAL = 1.0
    const val OVERTIME = 1.5
    const val HOLIDAY = 1.75
    const val DOUBLE = 2.0
}

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Shift(
    val name: String,
    @SuppressLint("UnsafeOptInUsageError") val startTime: String,
    val endTime: String,
    val colorValue: Long, // We store the color as a number for easy saving

    // ── Pay ──
    val hourlyRate: Double = 0.0,
    /** 1.0 normal, 1.5 overtime, 1.75 holiday, 2.0 double — see PayMultipliers. */
    val payMultiplier: Double = 1.0,
    /** Fixed extra on top of hourly pay: tips, travel allowance, shift bonus. */
    val bonus: Double = 0.0,

    // ── Time ──
    val breakMinutes: Int = 0,
    /** When true the break counts as worked time instead of being deducted. */
    val paidBreak: Boolean = false,

    // ── Day classification ──
    val dayType: DayType = DayType.WORKED,
    /** Hours credited when dayType is PAID_LEAVE. */
    val paidLeaveHours: Double = 8.0,

    // ── Context ──
    val location: String = "",
    /**
     * Which Job Profile this belongs to. Null only ever appears in records
     * written before profiles existed; ShiftManager stamps those onto the
     * default profile as they are decoded, so every screen sees a real id.
     */
    val jobId: String? = null,
    val notes: String = "",
    /** A ShiftIconCatalog key; null shows the first letter of [name]. */
    val iconName: String? = null,

    // ── Legacy (v1) — kept so old saved JSON still decodes. Read via
    //    migrated(); never write to these from new code. ──
    val isHoliday: Boolean = false,
    val isDayOff: Boolean = false,
    val dayOffPaidHours: Double = 8.0,
    val dayOffHourlyRate: Double = 0.0
) {
    /**
     * True when this record still carries v1 flags and hasn't been mapped onto
     * dayType yet.
     */
    private val needsMigration: Boolean
        get() = dayType == DayType.WORKED && (isHoliday || isDayOff)

    /**
     * Returns this shift expressed purely in the new fields.
     *
     * v1 semantics being preserved:
     *  - isHoliday earned nothing, so it maps to UNPAID_LEAVE. Historic totals
     *    stay exactly as the user has always seen them.
     *  - isDayOff paid dayOffPaidHours × dayOffHourlyRate, so it maps to
     *    PAID_LEAVE with those two values moved into the new fields.
     */
    fun migrated(): Shift = when {
        !needsMigration -> this

        isHoliday -> copy(
            dayType = DayType.UNPAID_LEAVE,
            isHoliday = false,
            isDayOff = false
        )

        else -> copy(
            dayType = DayType.PAID_LEAVE,
            paidLeaveHours = if (dayOffPaidHours > 0.0) dayOffPaidHours else 8.0,
            hourlyRate = if (dayOffHourlyRate > 0.0) dayOffHourlyRate else hourlyRate,
            isHoliday = false,
            isDayOff = false
        )
    }

    /** All-day markers are stored as a full 00:00–23:59 span. */
    val isAllDaySpan: Boolean
        get() = startTime == "00:00" && endTime == "23:59"
}

/**
 * A job the user works — "Main Job", "Restaurant", "Freelance".
 *
 * Every Shift points at exactly one through [Shift.jobId], so a profile is a
 * real partition of the data and not a view filter: the shifts, the saved
 * shift library, and every figure in Analytics belong to one profile at a time.
 *
 * Keeping the partition inside one flat store (rather than one store per
 * profile) is deliberate — cross-profile work such as "earnings per job" or
 * comparing two jobs only needs a different grouping key, not a second read.
 */
@Serializable
data class JobProfile(
    val id: String,
    val name: String,
    val colorValue: Long,
    /** Prefills the rate on a new shift created while this profile is active. */
    val defaultHourlyRate: Double = 0.0,
    /** Epoch millis; orders the switcher so the list never shuffles. */
    val createdAt: Long = 0L,
    /** How this job's pay periods fall. Defaults to the calendar month. */
    val payPeriod: PayPeriod = PayPeriod(),
    /** A ShiftIconCatalog key; null shows the first letter of [name]. */
    val iconName: String? = null,
    /**
     * Share of gross pay withheld — tax, insurance — as a percentage. Only
     * drives the "≈ take-home" estimate; 0 means no estimate is shown.
     */
    val deductionPercent: Double = 0.0,
    /** Earnings target for one pay period; 0 = no goal. */
    val earningsGoal: Double = 0.0,
    /** Hours target for one pay period; 0 = no goal. */
    val hoursGoal: Double = 0.0,
    /** How this job pays: hourly or a fixed monthly salary, and its overtime rules. */
    val pay: PaySetup = PaySetup()
) {
    /** Gross pay after [deductionPercent], for the take-home estimate. */
    fun takeHome(gross: Double): Double =
        gross * (1.0 - deductionPercent.coerceIn(0.0, 100.0) / 100.0)

    val hasGoal: Boolean get() = earningsGoal > 0.0 || hoursGoal > 0.0


    companion object {
        /**
         * The profile every pre-profiles record is folded into. Shifts written
         * before this feature carry jobId = null and are stamped with this on
         * decode, so an existing user opens the app to all their data intact
         * under one profile.
         */
        const val DEFAULT_ID = "default"

        val Default = JobProfile(
            id = DEFAULT_ID,
            name = "My Job",
            colorValue = 0xFF3F7DDEL, // blue, ShiftColors.Palette[7]
            createdAt = 0L
        )

        fun newId(): String = "job_" + java.util.UUID.randomUUID().toString().take(8)
    }
}

// Add this to your data model file
data class CurrencyInfo(
    val symbol: String,
    val code: String,
    val name: String
)