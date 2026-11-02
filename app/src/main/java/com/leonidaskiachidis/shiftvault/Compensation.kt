package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Compensation — what a period of work is expected to pay.
 *
 *  PayEngine prices one shift. This prices a *period*, which is where the two
 *  ways of being paid part company:
 *
 *    Hourly / shift-based   every hour earns its shift's rate × multiplier,
 *                           exactly as PayEngine has always worked it out.
 *    Fixed monthly salary   each pay period earns the salary in force for it,
 *                           whatever the hours. Shift rates are not added on
 *                           top — that would pay the same time twice.
 *
 *  Overtime is found the same way in both (a job chooses how), and is then
 *  paid, banked as time off, or only tracked. In fixed mode it is valued at the
 *  job's *reference* hourly rate when there is one; the salary is never divided
 *  by the hours worked to invent a rate. Without one, overtime shows as hours.
 *
 *  Hours worked are always counted from the shifts, separately from money.
 *
 *  Pure date and money maths, no Android — covered by plain JVM tests. Money
 *  comes out in whole cents.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

@Serializable
enum class PayMode(val label: String) {
    HOURLY("Hourly / shifts"),
    FIXED_MONTHLY("Monthly salary")
}

/** Which worked hours count as overtime. */
@Serializable
enum class OvertimeSource(val label: String) {
    /** Shifts switched to Extra in the shift form (the name is kept for stored profiles). */
    MARKED_SHIFTS("Extra shifts"),
    /** Hours past the contracted hours of a week. */
    WEEKLY_LIMIT("Over weekly hours"),
    /** Hours past a set number in one day. */
    DAILY_LIMIT("Over daily hours")
}

/** What overtime earns. */
@Serializable
enum class OvertimeCompensation(val label: String) {
    /** Money: hours × rate × multiplier. */
    PAID("Paid"),
    /** No money; hours × multiplier are banked as time off. */
    TIME_OFF("Time off"),
    /** Counted and shown, with no premium or time worked out. */
    TRACK_ONLY("Track only")
}

/** A monthly salary, in force from the pay period starting on [from] (ISO); null = from the start. */
@Serializable
data class SalaryRate(val from: String? = null, val monthlyCents: Long)

/**
 * How one job pays. Every field has a default, so profiles saved before this
 * existed read back as hourly with marked-shift overtime paid — exactly how
 * the app has always calculated them.
 */
@Serializable
data class PaySetup(
    val mode: PayMode = PayMode.HOURLY,
    /** Fixed mode: the salary over time. Edited by adding a change from a month on. */
    val salaryHistory: List<SalaryRate> = emptyList(),
    /** Fixed mode: what one hour is worth, for valuing overtime. 0 = not set. */
    val referenceHourlyRate: Double = 0.0,
    /** Contracted hours per week; the limit for WEEKLY_LIMIT. 0 = not set. */
    val contractHoursPerWeek: Double = 0.0,
    val overtimeSource: OvertimeSource = OvertimeSource.MARKED_SHIFTS,
    /** The limit for DAILY_LIMIT. 0 = not set. */
    val dailyLimitHours: Double = 0.0,
    /** Multiplier for hours over a weekly or daily limit. Marked shifts carry their own. */
    val overtimeMultiplier: Double = PayMultipliers.OVERTIME,
    val overtimeCompensation: OvertimeCompensation = OvertimeCompensation.PAID
) {
    val isFixed: Boolean get() = mode == PayMode.FIXED_MONTHLY

    /** Oldest first: the open-ended starting salary, then each change by date. */
    val salaries: List<SalaryRate>
        get() = salaryHistory.sortedWith(compareBy(nullsFirst()) { it.from })

    /** The salary in force for a pay period starting on [periodStart]; null if none was set yet. */
    fun salaryFor(periodStart: LocalDate): Long? =
        salaries.lastOrNull { it.from == null || !LocalDate.parse(it.from).isAfter(periodStart) }?.monthlyCents

    val currentSalaryCents: Long? get() = salaries.lastOrNull()?.monthlyCents

    /** Sets the salary from [from] on (null = from the start), replacing a change on that same date. */
    fun withSalary(from: LocalDate?, cents: Long): PaySetup =
        copy(salaryHistory = salaryHistory.filterNot { it.from == from?.toString() } + SalaryRate(from?.toString(), cents))

    /** A rate implied by the contract, offered as a starting point for [referenceHourlyRate]. */
    fun contractHourlyRate(): Double? {
        val salary = currentSalaryCents ?: return null
        if (contractHoursPerWeek <= 0.0) return null
        // A year's pay over a year's contracted hours; never over hours actually worked.
        return salary / 100.0 * 12.0 / (contractHoursPerWeek * 52.0)
    }
}

/** What one period is expected to pay, and the hours behind it. */
data class PeriodPay(
    val range: DateRange,
    val mode: PayMode,
    val compensation: OvertimeCompensation,
    val hoursWorked: Double,
    val shiftsWorked: Int,
    val paidLeaveHours: Double,
    val unpaidLeaveDays: Int,
    /** Fixed: the salary for each pay period ending in the range. Hourly: pay for the regular hours. */
    val baseCents: Long,
    /** Fixed mode only: a pay period in the range has no salary set. */
    val salaryMissing: Boolean,
    val overtimeHours: Double,
    /** Money for overtime; null when it can't be valued (fixed, no reference rate). */
    val overtimeCents: Long?,
    /** Banked under TIME_OFF: overtime hours × multiplier. */
    val timeOffHours: Double,
    /** Shift bonuses — tips, allowances — on top of everything else. */
    val extrasCents: Long,
    /** Overtime money and extras per day, for charts in fixed mode. */
    val extraByDay: Map<LocalDate, Long>
) {
    /** The total expected: base, plus overtime when it's paid in money, plus extras. Never counts an hour twice. */
    val expectedCents: Long get() = baseCents + (overtimeCents ?: 0L) + extrasCents

    /** Overtime worked that has a money value missing only because no reference rate is set. */
    val overtimeUnvalued: Boolean get() = overtimeCents == null && overtimeHours > 0.0
}

/** The calendar's "Week start day" setting as a day of the week. */
fun weekStartDay(setting: String): DayOfWeek =
    if (setting == "Sunday") DayOfWeek.SUNDAY else DayOfWeek.MONDAY

object Compensation {

    private class Piece(
        val date: LocalDate,
        val shift: Shift,
        val hours: Double,
        val overtime: Double,
        val overtimeMultiplier: Double
    )

    /**
     * What [range] is expected to pay under [profile]'s pay setup. [shifts] are
     * that job's; [weekStart] is the calendar's first day, used for weekly
     * overtime.
     */
    fun forRange(
        profile: JobProfile,
        shifts: Map<LocalDate, List<Shift>>,
        range: DateRange,
        weekStart: DayOfWeek = DayOfWeek.MONDAY
    ): PeriodPay {
        val setup = profile.pay
        val inRange = range.slice(shifts)
        val entries = inRange.values.flatten()
        val worked = entries.filter { PayEngine.isWorked(it) }

        val pieces = overtimePieces(setup, shifts, range, weekStart)
        val overtimeHours = pieces.sumOf { it.overtime }

        val extrasD = entries.sumOf { if (it.migrated().dayType == DayType.UNPAID_LEAVE) 0.0 else max(0.0, it.bonus) }
        val extrasCents = cents(extrasD)

        var timeOff = 0.0
        val baseCents: Long
        val overtimeCents: Long?
        var salaryMissing = false
        val extraByDay = HashMap<LocalDate, Long>()
        for ((date, list) in inRange) {
            val bonus = list.sumOf { if (it.migrated().dayType == DayType.UNPAID_LEAVE) 0.0 else max(0.0, it.bonus) }
            if (bonus > 0.0) extraByDay[date] = cents(bonus)
        }

        if (setup.isFixed) {
            var base = 0L
            forEachPeriodEndingIn(profile.payPeriod, range) { period ->
                val salary = setup.salaryFor(period.start)
                if (salary == null) salaryMissing = true else base += salary
            }
            baseCents = base
            val rate = setup.referenceHourlyRate
            when (setup.overtimeCompensation) {
                OvertimeCompensation.PAID -> {
                    overtimeCents = if (overtimeHours <= 0.0) 0L
                    else if (rate <= 0.0) null
                    else cents(pieces.sumOf { it.overtime * rate * it.overtimeMultiplier })
                    if (rate > 0.0) {
                        pieces.filter { it.overtime > 0.0 }.groupBy { it.date }.forEach { (date, ps) ->
                            val value = cents(ps.sumOf { it.overtime * rate * it.overtimeMultiplier })
                            extraByDay[date] = (extraByDay[date] ?: 0L) + value
                        }
                    }
                }
                OvertimeCompensation.TIME_OFF -> {
                    overtimeCents = 0L
                    timeOff = pieces.sumOf { it.overtime * it.overtimeMultiplier }
                }
                OvertimeCompensation.TRACK_ONLY -> overtimeCents = 0L
            }
        } else {
            val plainRules = setup.overtimeSource == OvertimeSource.MARKED_SHIFTS &&
                setup.overtimeCompensation == OvertimeCompensation.PAID
            // Every hour at its shift's rate, overtime included — for the
            // default rules this is the very sum the app has always shown.
            val totalD = if (plainRules) entries.sumOf { PayEngine.earnings(it) }
            else hourlyTotal(setup, entries, pieces) + extrasD
            val otD = when (setup.overtimeCompensation) {
                OvertimeCompensation.PAID -> pieces.sumOf { it.overtime * it.shift.migrated().hourlyRate * it.overtimeMultiplier }
                else -> 0.0
            }
            if (setup.overtimeCompensation == OvertimeCompensation.TIME_OFF) {
                timeOff = pieces.sumOf { it.overtime * it.overtimeMultiplier }
            }
            val totalCents = cents(totalD)
            overtimeCents = cents(otD)
            baseCents = totalCents - overtimeCents - extrasCents
        }

        return PeriodPay(
            range = range,
            mode = setup.mode,
            compensation = setup.overtimeCompensation,
            hoursWorked = worked.sumOf { PayEngine.workedHours(it) },
            shiftsWorked = worked.size,
            paidLeaveHours = entries.filter { it.migrated().dayType == DayType.PAID_LEAVE }
                .sumOf { PayEngine.payableHours(it) },
            unpaidLeaveDays = inRange.count { (_, list) -> list.any { it.migrated().dayType == DayType.UNPAID_LEAVE } },
            baseCents = baseCents,
            salaryMissing = salaryMissing,
            overtimeHours = overtimeHours,
            overtimeCents = overtimeCents,
            timeOffHours = timeOff,
            extrasCents = extrasCents,
            extraByDay = extraByDay
        )
    }

    /** [forRange] as the PeriodTotals that Home's goals and strip read. */
    fun totals(
        profile: JobProfile,
        shifts: Map<LocalDate, List<Shift>>,
        range: DateRange,
        weekStart: DayOfWeek = DayOfWeek.MONDAY
    ): PeriodTotals {
        val pay = forRange(profile, shifts, range, weekStart)
        return PeriodTotals(hours = pay.hoursWorked, earnings = pay.expectedCents / 100.0, shifts = pay.shiftsWorked)
    }

    /**
     * Hourly pay under rules other than the default: regular hours at the
     * shift's rate × its own multiplier; overtime hours at rate × the
     * overtime multiplier when paid, not paid when banked, and at the plain
     * rate when only tracked. Paid leave at the plain rate, as PayEngine does.
     */
    private fun hourlyTotal(setup: PaySetup, entries: List<Shift>, pieces: List<Piece>): Double {
        // Matched by instance: two identical shifts on one day are still two shifts.
        val overtimeOf = java.util.IdentityHashMap<Shift, Piece>().apply { pieces.forEach { put(it.shift, it) } }
        var total = 0.0
        for (raw in entries) {
            val s = raw.migrated()
            when (s.dayType) {
                DayType.UNPAID_LEAVE -> Unit
                DayType.PAID_LEAVE -> total += PayEngine.payableHours(s) * s.hourlyRate
                DayType.WORKED -> {
                    val piece = overtimeOf[raw]
                    val hours = PayEngine.workedHours(s)
                    val ot = piece?.overtime ?: 0.0
                    val own = PayEngine.effectiveMultiplier(s)
                    total += (hours - ot) * s.hourlyRate * own
                    total += when (setup.overtimeCompensation) {
                        OvertimeCompensation.PAID -> ot * s.hourlyRate * (piece?.overtimeMultiplier ?: own)
                        OvertimeCompensation.TIME_OFF -> 0.0
                        // Counted, and paid like any other hour of that shift.
                        OvertimeCompensation.TRACK_ONLY -> ot * s.hourlyRate * own
                    }
                }
            }
        }
        return total
    }

    /**
     * The overtime in each worked shift dated inside [range]. Limits are
     * counted from the start of the week (or day), even when that lies before
     * the range, so a week split across two pay periods is never counted twice
     * or not at all. Within a day, earlier shifts fill the limit first.
     */
    private fun overtimePieces(
        setup: PaySetup,
        shifts: Map<LocalDate, List<Shift>>,
        range: DateRange,
        weekStart: DayOfWeek
    ): List<Piece> {
        val from = when (setup.overtimeSource) {
            OvertimeSource.WEEKLY_LIMIT -> range.start.with(TemporalAdjusters.previousOrSame(weekStart))
            else -> range.start
        }
        val window = shifts.filterKeys { !it.isBefore(from) && !it.isAfter(range.end) }.toSortedMap()
        val pieces = mutableListOf<Piece>()
        var bucket: LocalDate? = null
        var used = 0.0
        for ((date, list) in window) {
            val ordered = list.filter { PayEngine.isWorked(it) }.sortedBy { it.startTime }
            for (shift in ordered) {
                val hours = PayEngine.workedHours(shift)
                val own = PayEngine.effectiveMultiplier(shift)
                val overtime: Double
                val multiplier: Double
                when (setup.overtimeSource) {
                    OvertimeSource.MARKED_SHIFTS -> {
                        // The Extra switch, not the multiplier, says what is extra;
                        // an extra shift is valued at its own multiplier (1× unless set).
                        overtime = if (shift.isExtra) hours else 0.0
                        multiplier = own
                    }
                    OvertimeSource.WEEKLY_LIMIT, OvertimeSource.DAILY_LIMIT -> {
                        val limit = if (setup.overtimeSource == OvertimeSource.WEEKLY_LIMIT) setup.contractHoursPerWeek
                        else setup.dailyLimitHours
                        val key = if (setup.overtimeSource == OvertimeSource.WEEKLY_LIMIT)
                            date.with(TemporalAdjusters.previousOrSame(weekStart)) else date
                        if (key != bucket) { bucket = key; used = 0.0 }
                        overtime = if (limit <= 0.0) 0.0 else min(hours, max(0.0, used + hours - limit))
                        used += hours
                        // A shift already paid at a premium keeps the higher one.
                        multiplier = max(own, setup.overtimeMultiplier)
                    }
                }
                if (date in range) pieces += Piece(date, shift, hours, overtime, multiplier)
            }
        }
        return pieces
    }

    /** Each pay period whose last day falls in [range]: a payslip belongs where it is due. */
    private inline fun forEachPeriodEndingIn(pay: PayPeriod, range: DateRange, action: (DateRange) -> Unit) {
        var period = pay.periodContaining(range.start)
        while (!period.start.isAfter(range.end)) {
            if (period.end in range) action(period)
            period = pay.next(period)
        }
    }

    private fun cents(amount: Double): Long = (amount * 100.0).roundToLong()
}
