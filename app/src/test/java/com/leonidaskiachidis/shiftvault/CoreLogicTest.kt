package com.leonidaskiachidis.shiftvault

/*
 * The money and hours logic every screen and export reads through. A wrong
 * figure here is a wrong payslip check for the user, so the rules are pinned
 * one by one: breaks, overnight spans, leave, multipliers, bonuses, legacy
 * records, profile partitioning and period statistics.
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class CoreLogicTest {

    private fun worked(
        start: String = "09:00",
        end: String = "17:00",
        rate: Double = 10.0,
        breakMinutes: Int = 0,
        paidBreak: Boolean = false,
        multiplier: Double = 1.0,
        bonus: Double = 0.0,
        name: String = "Day",
        jobId: String? = JobProfile.DEFAULT_ID
    ) = Shift(
        name = name, startTime = start, endTime = end, colorValue = 0xFF3F7DDE,
        hourlyRate = rate, breakMinutes = breakMinutes, paidBreak = paidBreak,
        payMultiplier = multiplier, bonus = bonus, jobId = jobId
    )

    // ══════════ PayEngine: hours ══════════

    @Test
    fun unpaidBreak_isDeducted_paidBreak_isNot() {
        assertEquals(7.5, PayEngine.workedHours(worked(breakMinutes = 30)), 1e-9)
        assertEquals(8.0, PayEngine.workedHours(worked(breakMinutes = 30, paidBreak = true)), 1e-9)
    }

    @Test
    fun overnightShift_runsPastMidnight() {
        assertEquals(8.0, PayEngine.workedHours(worked(start = "22:00", end = "06:00")), 1e-9)
    }

    @Test
    fun allDayMarker_isTwentyFourHours() {
        assertEquals(24.0, PayEngine.grossSpanHours(worked(start = "00:00", end = "23:59")), 1e-9)
    }

    @Test
    fun breakLongerThanShift_neverGoesNegative() {
        assertEquals(0.0, PayEngine.workedHours(worked(start = "09:00", end = "10:00", breakMinutes = 90)), 0.0)
    }

    @Test
    fun malformedTimes_countAsZero() {
        assertEquals(0.0, PayEngine.workedHours(worked(start = "nine", end = "17:00")), 0.0)
    }

    // ══════════ PayEngine: money ══════════

    @Test
    fun multiplierAndBonus_applyToWorkedShift() {
        // 8h × 10 × 1.5 + 20
        assertEquals(140.0, PayEngine.earnings(worked(multiplier = 1.5, bonus = 20.0)), 1e-9)
    }

    @Test
    fun zeroMultiplier_fallsBackToNormalRate() {
        assertEquals(1.0, PayEngine.effectiveMultiplier(worked(multiplier = 0.0)), 0.0)
        assertEquals(80.0, PayEngine.earnings(worked(multiplier = 0.0)), 1e-9)
    }

    @Test
    fun negativeBonus_isIgnored() {
        assertEquals(80.0, PayEngine.earnings(worked(bonus = -50.0)), 1e-9)
    }

    @Test
    fun paidLeave_paysCreditedHoursAtPlainRate() {
        val leave = worked(multiplier = 2.0, bonus = 5.0)
            .copy(dayType = DayType.PAID_LEAVE, paidLeaveHours = 6.0)
        assertEquals(6.0, PayEngine.payableHours(leave), 0.0)
        assertEquals(1.0, PayEngine.effectiveMultiplier(leave), 0.0)
        assertEquals(65.0, PayEngine.earnings(leave), 1e-9) // 6 × 10 + 5
        assertFalse(PayEngine.isWorked(leave))
    }

    @Test
    fun unpaidLeave_earnsNothing_evenWithBonus() {
        val unpaid = worked(bonus = 30.0).copy(dayType = DayType.UNPAID_LEAVE)
        assertEquals(0.0, PayEngine.payableHours(unpaid), 0.0)
        assertEquals(0.0, PayEngine.earnings(unpaid), 0.0)
    }

    @Test
    fun effectiveRate_blendsBonusIntoHourly() {
        assertEquals(12.5, PayEngine.effectiveRate(worked(bonus = 20.0)), 1e-9) // 100 / 8
        assertEquals(0.0, PayEngine.effectiveRate(worked().copy(dayType = DayType.UNPAID_LEAVE)), 0.0)
    }

    // ══════════ Legacy (v1) records ══════════

    @Test
    fun legacyHoliday_becomesUnpaidLeave() {
        val v1 = worked().copy(isHoliday = true)
        val m = v1.migrated()
        assertEquals(DayType.UNPAID_LEAVE, m.dayType)
        assertFalse(m.isHoliday)
        assertEquals(0.0, PayEngine.earnings(v1), 0.0)
    }

    @Test
    fun legacyDayOff_becomesPaidLeave_withItsHoursAndRate() {
        val v1 = worked(rate = 10.0).copy(isDayOff = true, dayOffPaidHours = 6.0, dayOffHourlyRate = 11.0)
        val m = v1.migrated()
        assertEquals(DayType.PAID_LEAVE, m.dayType)
        assertEquals(6.0, m.paidLeaveHours, 0.0)
        assertEquals(11.0, m.hourlyRate, 0.0)
        assertFalse(m.isDayOff)
    }

    @Test
    fun legacyDayOff_withoutValues_defaultsToEightHoursAtShiftRate() {
        val m = worked(rate = 10.0).copy(isDayOff = true, dayOffPaidHours = 0.0, dayOffHourlyRate = 0.0).migrated()
        assertEquals(8.0, m.paidLeaveHours, 0.0)
        assertEquals(10.0, m.hourlyRate, 0.0)
    }

    @Test
    fun currentRecord_isReturnedUnchanged() {
        val s = worked()
        assertSame(s, s.migrated())
    }

    @Test
    fun recordWithoutProfile_isStampedOntoDefault() {
        assertEquals(JobProfile.DEFAULT_ID, worked(jobId = null).withProfile().jobId)
        assertEquals("job_x", worked(jobId = "job_x").withProfile().jobId)
    }

    // ══════════ ProfileScope ══════════

    private val day = LocalDate.of(2026, 9, 1)
    private val mine = worked(name = "Mine", jobId = "a")
    private val theirs = worked(name = "Theirs", jobId = "b")

    @Test
    fun shifts_showOnlyThatProfile_andDropEmptyDays() {
        val all = mapOf(
            day to listOf(mine, theirs),
            day.plusDays(1) to listOf(theirs)
        )
        val scoped = ProfileScope.shifts(all, "a")
        assertEquals(mapOf(day to listOf(mine)), scoped)
    }

    @Test
    fun withDaySlice_replacesOnlyThatProfile() {
        val all = mapOf(day to listOf(mine, theirs))
        val replacement = worked(name = "New", jobId = "a")
        val updated = ProfileScope.withDaySlice(all, day, "a", listOf(replacement))
        assertTrue(updated.getValue(day).containsAll(listOf(replacement, theirs)))
        assertFalse(updated.getValue(day).contains(mine))
    }

    @Test
    fun clearingLastShiftOfDay_removesTheDay() {
        val all = mapOf(day to listOf(mine))
        assertFalse(ProfileScope.withDaySlice(all, day, "a", emptyList()).containsKey(day))
    }

    @Test
    fun clearingOneProfile_keepsTheOthersDay() {
        val all = mapOf(day to listOf(mine, theirs))
        assertEquals(listOf(theirs), ProfileScope.withDaySlice(all, day, "a", emptyList()).getValue(day))
    }

    // ══════════ StatsHelper ══════════

    private val today = LocalDate.of(2026, 12, 31)

    private val sample = mapOf(
        LocalDate.of(2026, 9, 1) to listOf(worked(name = "Day", breakMinutes = 30)),          // 7.5h, 75
        LocalDate.of(2026, 9, 2) to listOf(worked(name = "Night", start = "22:00", end = "06:00", multiplier = 1.5)), // 8h, 120
        LocalDate.of(2026, 9, 3) to listOf(worked().copy(dayType = DayType.PAID_LEAVE, paidLeaveHours = 8.0, name = "Leave")), // 80
        LocalDate.of(2026, 10, 1) to listOf(worked(name = "Day"))                              // other month
    )

    @Test
    fun monthlyStats_totalWorkedHoursShiftsAndEarnings() {
        val stats = StatsHelper.calculateMonthlyStats(sample, YearMonth.of(2026, 9), today)
        assertEquals(15.5, stats.totalHours, 1e-9)
        assertEquals(2, stats.totalShifts)
        assertEquals(275.0, stats.estimatedEarnings, 1e-9) // leave pay counts in earnings
        assertEquals(1, stats.dayOffDays)
        assertEquals(mapOf("Day" to 1, "Night" to 1), stats.shiftBreakdown)
        assertEquals(7.75, stats.averageHoursPerShift, 1e-9)
    }

    @Test
    fun yearlyStats_includeEveryMonth() {
        val stats = StatsHelper.calculateYearlyStats(sample, Year.of(2026), today)
        assertEquals(3, stats.totalShifts)
        assertEquals(355.0, stats.estimatedEarnings, 1e-9)
    }

    @Test
    fun rangeStats_areInclusiveAtBothEnds() {
        val stats = StatsHelper.calculateRangeStats(
            sample, LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3), today
        )
        assertEquals(1, stats.totalShifts)
        assertEquals(200.0, stats.estimatedEarnings, 1e-9)
    }

    @Test
    fun breakdownEntries_areNewestFirst() {
        val shifts = mapOf(
            LocalDate.of(2026, 9, 1) to listOf(worked(name = "Day")),
            LocalDate.of(2026, 9, 5) to listOf(worked(name = "Day"))
        )
        val entries = StatsHelper.calculateMonthlyStats(shifts, YearMonth.of(2026, 9), today)
            .shiftBreakdownEntries.getValue("Day")
        assertEquals(LocalDate.of(2026, 9, 5), entries.first().date)
    }
}
