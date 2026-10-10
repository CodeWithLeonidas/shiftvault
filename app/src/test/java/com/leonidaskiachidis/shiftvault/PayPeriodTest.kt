package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class PayPeriodTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    // ── Monthly ──

    @Test
    fun calendarMonth_isTheDefault() {
        val r = PayPeriod().periodContaining(d(2026, 9, 24))
        assertEquals(DateRange(d(2026, 9, 1), d(2026, 9, 30)), r)
    }

    @Test
    fun monthlyFromThe15th_runsTo14thOfNextMonth() {
        val p = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 15)
        assertEquals(DateRange(d(2026, 9, 15), d(2026, 10, 14)), p.periodContaining(d(2026, 9, 24)))
        // Before the 15th belongs to the period that started last month.
        assertEquals(DateRange(d(2026, 8, 15), d(2026, 9, 14)), p.periodContaining(d(2026, 9, 3)))
    }

    @Test
    fun monthlyFromThe31st_fallsBackToShortMonthsLastDay() {
        val p = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 31)
        // February has no 31st: the period starts on Feb 28.
        assertEquals(DateRange(d(2026, 2, 28), d(2026, 3, 30)), p.periodContaining(d(2026, 3, 10)))
    }

    // ── Home range (the browsed month) ──

    @Test
    fun homeRange_calendarMonth_isTheBrowsedMonth() {
        val r = PayPeriod().homeRange(java.time.YearMonth.of(2026, 11), today = d(2026, 9, 24))
        assertEquals(DateRange(d(2026, 11, 1), d(2026, 11, 30)), r)
    }

    @Test
    fun homeRange_weeklyAndBiweekly_totalTheBrowsedMonth() {
        val oct = java.time.YearMonth.of(2026, 10)
        val month = DateRange(d(2026, 10, 1), d(2026, 10, 31))
        assertEquals(month, PayPeriod(cycle = PayCycle.WEEKLY).homeRange(oct, d(2026, 9, 24)))
        assertEquals(month, PayPeriod(cycle = PayCycle.BIWEEKLY).homeRange(oct, d(2026, 9, 24)))
    }

    @Test
    fun homeRange_midMonthCycle_currentMonthIsTodaysPeriod_andEachMonthStepsOne() {
        val p = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 15)
        val today = d(2026, 9, 24)
        assertEquals(DateRange(d(2026, 9, 15), d(2026, 10, 14)), p.homeRange(java.time.YearMonth.of(2026, 9), today))
        assertEquals(DateRange(d(2026, 10, 15), d(2026, 11, 14)), p.homeRange(java.time.YearMonth.of(2026, 10), today))
        assertEquals(DateRange(d(2026, 7, 15), d(2026, 8, 14)), p.homeRange(java.time.YearMonth.of(2026, 7), today))
    }

    @Test
    fun homeRange_startOn31st_neverRepeatsAPeriodAcrossShortMonths() {
        val p = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 31)
        val today = d(2026, 1, 30)
        val ranges = (0L..13L).map { p.homeRange(java.time.YearMonth.of(2026, 1).plusMonths(it), today) }
        assertEquals(ranges.size, ranges.distinct().size)
        // Consecutive months hand over without a gap or an overlap.
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.end.plusDays(1), b.start) }
    }

    @Test
    fun monthlyAcrossNewYear() {
        val p = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 20)
        assertEquals(DateRange(d(2026, 12, 20), d(2027, 1, 19)), p.periodContaining(d(2027, 1, 5)))
    }

    // ── Weekly / bi-weekly ──

    @Test
    fun weekly_startsOnChosenDay() {
        val p = PayPeriod(cycle = PayCycle.WEEKLY, weekStart = 3) // Wednesday
        val r = p.periodContaining(d(2026, 9, 24)) // a Thursday
        assertEquals(DateRange(d(2026, 9, 23), d(2026, 9, 29)), r)
    }

    @Test
    fun biweekly_countsFromAnchor_inBothDirections() {
        val p = PayPeriod(cycle = PayCycle.BIWEEKLY, anchor = "2026-09-07")
        assertEquals(DateRange(d(2026, 9, 21), d(2026, 10, 4)), p.periodContaining(d(2026, 9, 24)))
        assertEquals(DateRange(d(2026, 8, 24), d(2026, 9, 6)), p.periodContaining(d(2026, 9, 1)))
    }

    @Test
    fun previousAndNext_areAdjacent() {
        val p = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 15)
        val now = p.periodContaining(d(2026, 9, 24))
        assertEquals(now.start.minusDays(1), p.previous(now).end)
        assertEquals(now.end.plusDays(1), p.next(now).start)
    }

    // ── Labels ──

    @Test
    fun labels_readNaturally() {
        val en = Locale.ENGLISH
        assertEquals("September 2026", DateRange(d(2026, 9, 1), d(2026, 9, 30)).label(en))
        assertEquals("15 Sep – 14 Oct 2026", DateRange(d(2026, 9, 15), d(2026, 10, 14)).label(en))
        assertEquals("20 Dec 2026 – 19 Jan 2027", DateRange(d(2026, 12, 20), d(2027, 1, 19)).label(en))
        assertEquals("21 – 27 Sep", DateRange(d(2026, 9, 21), d(2026, 9, 27)).shortLabel(en))
        assertEquals("September", DateRange(d(2026, 9, 1), d(2026, 9, 30)).shortLabel(en))
    }

    @Test
    fun ordinals() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 31).map { PayPeriod.ordinal(it) })
    }

    // ── Weeks and totals ──

    @Test
    fun week_followsStartOfWeekSetting() {
        assertEquals(d(2026, 9, 21), weekContaining(d(2026, 9, 24), "Monday").start)
        assertEquals(d(2026, 9, 20), weekContaining(d(2026, 9, 24), "Sunday").start)
    }

    @Test
    fun totals_countOnlyDaysInRange() {
        val shift = Shift(name = "Day", startTime = "09:00", endTime = "17:00", colorValue = 0, hourlyRate = 10.0)
        val shifts = mapOf(
            d(2026, 9, 21) to listOf(shift),
            d(2026, 9, 22) to listOf(shift),
            d(2026, 9, 28) to listOf(shift) // next week
        )
        val t = PeriodTotals.of(shifts, weekContaining(d(2026, 9, 24), "Monday"))
        assertEquals(16.0, t.hours, 1e-9)
        assertEquals(160.0, t.earnings, 1e-9)
        assertEquals(2, t.shifts)
    }

    @Test
    fun oldProfileJson_withoutPayPeriod_readsAsCalendarMonth() {
        val old = """[{"id":"default","name":"My Job","colorValue":4282351070}]"""
        val profile = StoreJson.decodeFromString<List<JobProfile>>(old).single()
        assertEquals(PayCycle.MONTHLY, profile.payPeriod.cycle)
        assertTrue(profile.payPeriod.startDay == 1)
    }
}
