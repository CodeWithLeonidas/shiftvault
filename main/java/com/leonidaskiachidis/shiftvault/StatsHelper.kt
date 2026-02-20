package com.leonidaskiachidis.shiftvault

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

data class MonthlyStats(
    val totalHours: Double,
    val totalShifts: Int,
    val shiftBreakdown: Map<String, Int>,
    val averageHoursPerShift: Double
)

object StatsHelper {

    fun calculateMonthlyStats(
        allShifts: Map<LocalDate, List<Shift>>,
        targetMonth: YearMonth
    ): MonthlyStats {
        val shiftsInMonth = allShifts.filter { (date, _) ->
            YearMonth.from(date) == targetMonth
        }.values.flatten()

        val totalHours = shiftsInMonth.sumOf { calculateShiftDuration(it) }
        val totalShifts = shiftsInMonth.size

        val breakdown = shiftsInMonth.groupBy { it.name }
            .mapValues { it.value.size }

        val avgHours = if (totalShifts > 0) totalHours / totalShifts else 0.0

        return MonthlyStats(
            totalHours = totalHours,
            totalShifts = totalShifts,
            shiftBreakdown = breakdown,
            averageHoursPerShift = avgHours
        )
    }

    private fun calculateShiftDuration(shift: Shift): Double {
        val start = LocalTime.parse(shift.startTime)
        val end = LocalTime.parse(shift.endTime)

        val duration = if (end.isAfter(start)) {
            Duration.between(start, end)
        } else {
            // Overnight shift: calculate to midnight + remaining hours
            Duration.between(start, LocalTime.MAX).plusMinutes(1) +
                    Duration.between(LocalTime.MIN, end)
        }

        return duration.toMinutes() / 60.0
    }

    fun formatHours(hours: Double): String {
        val wholeHours = hours.toInt()
        val minutes = ((hours - wholeHours) * 60).toInt()
        return if (minutes > 0) "${wholeHours}h ${minutes}m" else "${wholeHours}h"
    }
}
