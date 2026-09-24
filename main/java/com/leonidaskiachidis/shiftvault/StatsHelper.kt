package com.leonidaskiachidis.shiftvault

import java.time.LocalDate
import java.time.YearMonth

// ── Per-shift entry for the detail bottom sheet ──
data class ShiftBreakdownEntry(
    val date: LocalDate,
    val startTime: String,
    val endTime: String,
    val hours: Double,
    val earnings: Double,
    val breakMinutes: Int,
    val notes: String,
    val colorValue: Long
)

data class MonthlyStats(
    val totalHours: Double,
    val totalShifts: Int,
    val shiftBreakdown: Map<String, Int>,
    // hours per shift-name group
    val shiftBreakdownHours: Map<String, Double>,
    // full per-occurrence entries, keyed by shift name
    val shiftBreakdownEntries: Map<String, List<ShiftBreakdownEntry>>,
    val averageHoursPerShift: Double,
    val estimatedEarnings: Double,
    val restDays: Int,
    /** Unpaid leave records in the period (v1 name kept for call sites). */
    val holidayDays: Int,
    /** Paid leave records in the period (v1 name kept for call sites). */
    val dayOffDays: Int,
    // actual dates for the drill-down sheets
    val holidayDates: List<LocalDate>,
    val dayOffDates: List<LocalDate>,
    val restDates: List<LocalDate>
)

object StatsHelper {

    fun calculateMonthlyStats(
        allShifts: Map<LocalDate, List<Shift>>,
        targetMonth: YearMonth,
        today: LocalDate = LocalDate.now()
    ): MonthlyStats {
        val shiftsInMonthWithDate = allShifts.filter { (date, _) ->
            YearMonth.from(date) == targetMonth
        }

        val allDatesInMonth = (1..targetMonth.lengthOfMonth()).map { targetMonth.atDay(it) }

        return processShiftsToStats(
            shiftsWithDate = shiftsInMonthWithDate,
            allDatesInPeriod = allDatesInMonth,
            today = today
        )
    }

    fun calculateYearlyStats(
        allShifts: Map<LocalDate, List<Shift>>,
        targetYear: java.time.Year,
        today: LocalDate = LocalDate.now()
    ): MonthlyStats {
        val shiftsInYearWithDate = allShifts.filter { (date, _) ->
            date.year == targetYear.value
        }

        val allDatesInYear = (1..targetYear.length())
            .map { LocalDate.ofYearDay(targetYear.value, it) }

        return processShiftsToStats(
            shiftsWithDate = shiftsInYearWithDate,
            allDatesInPeriod = allDatesInYear,
            today = today
        )
    }

    fun calculateRangeStats(
        allShifts: Map<LocalDate, List<Shift>>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate = LocalDate.now()
    ): MonthlyStats {
        val shiftsInRangeWithDate = allShifts.filter { (date, _) ->
            !date.isBefore(start) && !date.isAfter(end)
        }

        val daysInRange = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1
        val allDatesInRange = (0 until daysInRange).map { start.plusDays(it.toLong()) }

        return processShiftsToStats(
            shiftsWithDate = shiftsInRangeWithDate,
            allDatesInPeriod = allDatesInRange,
            today = today
        )
    }

    /**
     * Net worked hours for one shift. Thin wrapper over PayEngine so existing
     * call sites keep working; all the rules live in one place now.
     */
    fun calculateShiftDuration(shift: Shift): Double = PayEngine.workedHours(shift)

    /** Money the shift is worth, including multiplier and bonus. */
    fun calculateShiftEarnings(shift: Shift): Double = PayEngine.earnings(shift)

    fun formatHours(hours: Double): String {
        val wholeHours = hours.toInt()
        val minutes = Math.round((hours - wholeHours) * 60).toInt()
        return if (minutes > 0) "${wholeHours}h ${minutes}m" else "${wholeHours}h"
    }

    private fun processShiftsToStats(
        shiftsWithDate: Map<LocalDate, List<Shift>>,
        allDatesInPeriod: List<LocalDate>,
        today: LocalDate
    ): MonthlyStats {
        // Migrate once, up front — everything below reads the new fields only.
        val byDate: Map<LocalDate, List<Shift>> =
            shiftsWithDate.mapValues { (_, list) -> list.map { it.migrated() } }

        val allShiftsList = byDate.values.flatten()
        val workedShifts = allShiftsList.filter { it.dayType == DayType.WORKED }

        val totalHours = workedShifts.sumOf { PayEngine.workedHours(it) }
        val totalShifts = workedShifts.size

        // Every record contributes, so a worked public holiday finally counts.
        val estimatedEarnings = allShiftsList.sumOf { PayEngine.earnings(it) }

        val breakdown = workedShifts.groupBy { it.name }.mapValues { it.value.size }

        val breakdownHours = workedShifts.groupBy { it.name }.mapValues { (_, shifts) ->
            shifts.sumOf { PayEngine.workedHours(it) }
        }

        val breakdownEntries: Map<String, List<ShiftBreakdownEntry>> =
            byDate.entries
                .flatMap { (date, shifts) ->
                    shifts.filter { it.dayType == DayType.WORKED }.map { shift ->
                        ShiftBreakdownEntry(
                            date = date,
                            startTime = shift.startTime,
                            endTime = shift.endTime,
                            hours = PayEngine.workedHours(shift),
                            earnings = PayEngine.earnings(shift),
                            breakMinutes = shift.breakMinutes,
                            notes = shift.notes,
                            colorValue = shift.colorValue
                        ) to shift.name
                    }
                }
                .groupBy({ it.second }, { it.first })
                .mapValues { (_, entries) -> entries.sortedByDescending { it.date } }

        val avgHours = if (totalShifts > 0) totalHours / totalShifts else 0.0

        // Paid leave (was "day off") and unpaid leave (was "holiday")
        val paidLeaveDates = byDate
            .filter { (_, shifts) -> shifts.any { it.dayType == DayType.PAID_LEAVE } }
            .keys.sorted()

        val unpaidLeaveDates = byDate
            .filter { (_, shifts) -> shifts.any { it.dayType == DayType.UNPAID_LEAVE } }
            .keys.sorted()

        // Rest = nothing logged at all (a leave day is not rest), and only days
        // that have already happened — the rest of the current month isn't
        // "rest" yet, it just hasn't been lived.
        val loggedDates = byDate.keys.toSet()
        val restDates = allDatesInPeriod
            .filter { !it.isAfter(today) && it !in loggedDates }
            .sorted()

        return MonthlyStats(
            totalHours = totalHours,
            totalShifts = totalShifts,
            shiftBreakdown = breakdown,
            shiftBreakdownHours = breakdownHours,
            shiftBreakdownEntries = breakdownEntries,
            averageHoursPerShift = avgHours,
            estimatedEarnings = estimatedEarnings,
            restDays = restDates.size,
            holidayDays = unpaidLeaveDates.size,
            dayOffDays = paidLeaveDates.size,
            holidayDates = unpaidLeaveDates,
            dayOffDates = paidLeaveDates,
            restDates = restDates
        )
    }
}