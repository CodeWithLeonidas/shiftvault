package com.leonidaskiachidis.shiftvault

import java.time.Duration
import java.time.LocalTime

/**
 * Single source of truth for turning a Shift into hours and money.
 *
 * Everything that shows a duration or an amount goes through here, so the same
 * shift can never be worth one number in Analytics and another in an export.
 *
 * Call sites should pass an already-migrated Shift; the entry points below call
 * migrated() defensively anyway, since it's a no-op on new records.
 */
object PayEngine {

    /** Clock-to-clock hours, before any break is taken off. Handles overnight. */
    fun grossSpanHours(shift: Shift): Double {
        if (shift.isAllDaySpan) return 24.0

        val start = runCatching { LocalTime.parse(shift.startTime) }.getOrNull() ?: return 0.0
        val end = runCatching { LocalTime.parse(shift.endTime) }.getOrNull() ?: return 0.0

        val duration = if (end.isAfter(start)) {
            Duration.between(start, end)
        } else {
            // Overnight shift: run to midnight, then on to the end time
            Duration.between(start, LocalTime.MAX).plusMinutes(1) +
                    Duration.between(LocalTime.MIN, end)
        }
        return maxOf(0L, duration.toMinutes()) / 60.0
    }

    /**
     * Hours actually credited as worked time. An unpaid break is deducted; a
     * paid break is not.
     */
    fun workedHours(shift: Shift): Double {
        val s = shift.migrated()
        val gross = grossSpanHours(s)
        val deduction = if (s.paidBreak) 0.0 else s.breakMinutes / 60.0
        return maxOf(0.0, gross - deduction)
    }

    /**
     * Hours the shift is paid for.
     *  WORKED       — the hours on the clock
     *  PAID_LEAVE   — the credited leave hours, no clock involved
     *  UNPAID_LEAVE — nothing
     */
    fun payableHours(shift: Shift): Double {
        val s = shift.migrated()
        return when (s.dayType) {
            DayType.WORKED -> workedHours(s)
            DayType.PAID_LEAVE -> maxOf(0.0, s.paidLeaveHours)
            DayType.UNPAID_LEAVE -> 0.0
        }
    }

    /**
     * The multiplier that applies to pay. Premiums are a property of *working*
     * an unsociable day, so leave is always paid at plain rate.
     */
    fun effectiveMultiplier(shift: Shift): Double {
        val s = shift.migrated()
        return if (s.dayType == DayType.WORKED) {
            if (s.payMultiplier > 0.0) s.payMultiplier else 1.0
        } else 1.0
    }

    /** Hourly pay for the shift, before the fixed bonus. */
    fun hourlyEarnings(shift: Shift): Double {
        val s = shift.migrated()
        return payableHours(s) * s.hourlyRate * effectiveMultiplier(s)
    }

    /**
     * Everything the shift is worth: hourly pay plus tips / allowance / bonus.
     * Unpaid leave earns nothing, bonus included — a day you weren't paid for
     * shouldn't quietly carry money.
     */
    fun earnings(shift: Shift): Double {
        val s = shift.migrated()
        if (s.dayType == DayType.UNPAID_LEAVE) return 0.0
        return hourlyEarnings(s) + maxOf(0.0, s.bonus)
    }

    /** Whether this record should count towards "days worked". */
    fun isWorked(shift: Shift): Boolean = shift.migrated().dayType == DayType.WORKED

    /** Blended rate actually achieved, useful for "avg rate" read-outs. */
    fun effectiveRate(shift: Shift): Double {
        val hours = payableHours(shift)
        return if (hours > 0.0) earnings(shift) / hours else 0.0
    }
}