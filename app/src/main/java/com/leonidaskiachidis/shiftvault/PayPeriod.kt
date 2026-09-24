package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Pay periods — the stretch of days one payslip covers.
 *
 *  Each Job Profile has its own, because different employers pay on different
 *  cycles. Everything that answers "what will I be paid?" reads through here:
 *  the Home totals, the Analytics pay-period view and the export periods.
 *
 *  Pure date maths, no Android — covered by plain JVM tests.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Serializable
enum class PayCycle {
    /** Once a month, starting on [PayPeriod.startDay] (1 = the calendar month). */
    MONTHLY,

    /** Every week, starting on [PayPeriod.weekStart]. */
    WEEKLY,

    /** Every two weeks, counted from [PayPeriod.anchor]. */
    BIWEEKLY
}

/** An inclusive run of days. */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    /**
     * "September 2026" for a calendar month, otherwise "15 Sep – 14 Oct 2026".
     * The year is shown once, at the end, unless the range crosses a year.
     */
    fun label(locale: Locale = Locale.getDefault()): String {
        val ym = YearMonth.from(start)
        if (start == ym.atDay(1) && end == ym.atEndOfMonth()) {
            return start.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
        }
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        return if (start.year == end.year) "${start.format(dayMonth)} – ${end.format(full)}"
        else "${start.format(full)} – ${end.format(full)}"
    }

    /** Like [label] but without any year — for the tight Home tiles. */
    fun shortLabel(locale: Locale = Locale.getDefault()): String {
        val ym = YearMonth.from(start)
        if (start == ym.atDay(1) && end == ym.atEndOfMonth()) {
            return start.format(DateTimeFormatter.ofPattern("MMMM", locale))
        }
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        return if (start.month == end.month) "${start.dayOfMonth} – ${end.format(dayMonth)}"
        else "${start.format(dayMonth)} – ${end.format(dayMonth)}"
    }

    /** Only the days in this range, from a date-keyed map. */
    fun <T> slice(map: Map<LocalDate, T>): Map<LocalDate, T> = map.filterKeys { it in this }
}

/**
 * How a job's pay periods fall. Stored on the JobProfile; every field has a
 * default, so profiles saved before this existed read back as calendar months.
 */
@Serializable
data class PayPeriod(
    val cycle: PayCycle = PayCycle.MONTHLY,
    /** MONTHLY: the day a period starts on. 29–31 fall back to the month's last day. */
    val startDay: Int = 1,
    /** WEEKLY: ISO day of week (1 = Monday … 7 = Sunday) a period starts on. */
    val weekStart: Int = 1,
    /** BIWEEKLY: an ISO date on which some period started; the rest follow every 14 days. */
    val anchor: String = "2026-01-05"
) {
    /** The pay period [date] falls in. */
    fun periodContaining(date: LocalDate): DateRange = when (cycle) {
        PayCycle.MONTHLY -> {
            val thisStart = monthStart(YearMonth.from(date))
            if (!date.isBefore(thisStart)) {
                DateRange(thisStart, monthStart(YearMonth.from(date).plusMonths(1)).minusDays(1))
            } else {
                DateRange(monthStart(YearMonth.from(date).minusMonths(1)), thisStart.minusDays(1))
            }
        }

        PayCycle.WEEKLY -> {
            val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(weekStart.coerceIn(1, 7))))
            DateRange(start, start.plusDays(6))
        }

        PayCycle.BIWEEKLY -> {
            val base = runCatching { LocalDate.parse(anchor) }.getOrDefault(LocalDate.of(2026, 1, 5))
            val periods = Math.floorDiv(ChronoUnit.DAYS.between(base, date), 14L)
            val start = base.plusDays(periods * 14)
            DateRange(start, start.plusDays(13))
        }
    }

    fun previous(range: DateRange): DateRange = periodContaining(range.start.minusDays(1))

    fun next(range: DateRange): DateRange = periodContaining(range.end.plusDays(1))

    /** "Monthly · from the 15th", "Weekly · from Monday", "Every 2 weeks". */
    fun describe(locale: Locale = Locale.getDefault()): String = when (cycle) {
        PayCycle.MONTHLY ->
            if (startDay <= 1) "Calendar month" else "Monthly · from the ${ordinal(startDay)}"
        PayCycle.WEEKLY ->
            "Weekly · from " + DayOfWeek.of(weekStart.coerceIn(1, 7))
                .getDisplayName(java.time.format.TextStyle.FULL, locale)
        PayCycle.BIWEEKLY -> "Every 2 weeks"
    }

    private fun monthStart(ym: YearMonth): LocalDate = ym.atDay(startDay.coerceIn(1, ym.lengthOfMonth()))

    companion object {
        /** 1st, 2nd, 3rd, 4th … 21st, 22nd, 23rd, 31st. */
        fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
            return "$n$suffix"
        }
    }
}

/** The calendar week containing [date], by the app's "week starts on" setting. */
fun weekContaining(date: LocalDate, startOfWeek: String): DateRange {
    val first = if (startOfWeek == "Sunday") DayOfWeek.SUNDAY else DayOfWeek.MONDAY
    val start = date.with(TemporalAdjusters.previousOrSame(first))
    return DateRange(start, start.plusDays(6))
}

/** Worked hours and total earnings for a set of days — what the Home tiles show. */
data class PeriodTotals(val hours: Double, val earnings: Double, val shifts: Int) {
    companion object {
        fun of(shifts: Map<LocalDate, List<Shift>>, range: DateRange): PeriodTotals {
            val inRange = range.slice(shifts).values.flatten()
            val worked = inRange.filter { PayEngine.isWorked(it) }
            return PeriodTotals(
                hours = worked.sumOf { PayEngine.workedHours(it) },
                earnings = inRange.sumOf { PayEngine.earnings(it) },
                shifts = worked.size
            )
        }
    }
}
