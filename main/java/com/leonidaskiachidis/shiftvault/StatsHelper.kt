package com.leonidaskiachidis.shiftvault

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

data class MonthlyStats(
    val totalHours: Double,
    val totalShifts: Int,
    val shiftBreakdown: Map<String, Int>,
    val averageHoursPerShift: Double,
    val estimatedEarnings: Double,
    val restDays: Int,
    val holidayDays: Int
)

object StatsHelper {

    fun calculateMonthlyStats(
        allShifts: Map<LocalDate, List<Shift>>,
        targetMonth: YearMonth
    ): MonthlyStats {
        val shiftsInMonthWithDate = allShifts.filter { (date, _) ->
            YearMonth.from(date) == targetMonth
        }

        // We count a "worked day" only if it contains at least one non-holiday shift
        val workedDaysCount = shiftsInMonthWithDate.values.count { dayShifts ->
            dayShifts.any { !it.isHoliday }
        }

        return processShiftsToStats(
            shifts = shiftsInMonthWithDate.values.flatten(),
            totalDays = targetMonth.lengthOfMonth(),
            workedDaysCount = workedDaysCount
        )
    }

    private fun calculateShiftDuration(shift: Shift): Double {
        val start = LocalTime.parse(shift.startTime)
        val end = LocalTime.parse(shift.endTime)

        val totalDuration = if (end.isAfter(start)) {
            Duration.between(start, end)
        } else {
            // Overnight shift logic
            Duration.between(start, LocalTime.MAX).plusMinutes(1) +
                    Duration.between(LocalTime.MIN, end)
        }

        // Subtract the break minutes and convert to decimal hours
        val netMinutes = totalDuration.toMinutes() - shift.breakMinutes
        return maxOf(0L, netMinutes) / 60.0
    }

    fun formatHours(hours: Double): String {
        val wholeHours = hours.toInt()
        val minutes = Math.round((hours - wholeHours) * 60).toInt()
        return if (minutes > 0) "${wholeHours}h ${minutes}m" else "${wholeHours}h"
    }

    // 1. YEARLY LOGIC
    fun calculateYearlyStats(
        allShifts: Map<LocalDate, List<Shift>>,
        targetYear: java.time.Year
    ): MonthlyStats {
        val shiftsInYearWithDate = allShifts.filter { (date, _) ->
            date.year == targetYear.value
        }

        val workedDaysCount = shiftsInYearWithDate.values.count { dayShifts ->
            dayShifts.any { !it.isHoliday }
        }

        return processShiftsToStats(
            shifts = shiftsInYearWithDate.values.flatten(),
            totalDays = targetYear.length(),
            workedDaysCount = workedDaysCount
        )
    }

    // 2. CUSTOM RANGE LOGIC
    fun calculateRangeStats(
        allShifts: Map<LocalDate, List<Shift>>,
        start: LocalDate,
        end: LocalDate
    ): MonthlyStats {
        val shiftsInRangeWithDate = allShifts.filter { (date, _) ->
            !date.isBefore(start) && !date.isAfter(end)
        }

        val daysInRange = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1
        val workedDaysCount = shiftsInRangeWithDate.values.count { dayShifts ->
            dayShifts.any { !it.isHoliday }
        }

        return processShiftsToStats(
            shifts = shiftsInRangeWithDate.values.flatten(),
            totalDays = daysInRange,
            workedDaysCount = workedDaysCount
        )
    }

    // 3. PRIVATE REUSABLE PROCESSOR
    private fun processShiftsToStats(shifts: List<Shift>, totalDays: Int, workedDaysCount: Int = 0): MonthlyStats {
        val actualWorkShifts = shifts.filter { !it.isHoliday }
        val holidayCount = shifts.count { it.isHoliday }

        val totalHours = actualWorkShifts.sumOf { calculateShiftDuration(it) }
        val totalShifts = actualWorkShifts.size

        // --- UPDATED EARNINGS LOGIC ---
        val estimatedEarnings = actualWorkShifts.sumOf { shift ->
            calculateShiftDuration(shift) * shift.hourlyRate
        }

        val breakdown = actualWorkShifts.groupBy { it.name }
            .mapValues { it.value.size }

        val avgHours = if (totalShifts > 0) totalHours / totalShifts else 0.0

        return MonthlyStats(
            totalHours = totalHours,
            totalShifts = totalShifts,
            shiftBreakdown = breakdown,
            averageHoursPerShift = avgHours,
            estimatedEarnings = estimatedEarnings,
            restDays = totalDays - workedDaysCount,
            holidayDays = holidayCount
        )
    }
}