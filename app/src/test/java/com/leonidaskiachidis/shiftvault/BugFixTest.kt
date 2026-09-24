package com.leonidaskiachidis.shiftvault

/*
 * Pins the behaviour behind the pre-release bug fixes, so none of them can
 * quietly come back: decimal commas, 24-hour shifts, rest-day counting, and
 * refusing backup files whose dates would make the restored store unreadable.
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BugFixTest {

    // ── Decimal input ──

    @Test
    fun parseDecimal_acceptsCommaAndPoint() {
        assertEquals(12.5, parseDecimal("12,5")!!, 0.0)
        assertEquals(12.5, parseDecimal("12.5")!!, 0.0)
        assertEquals(0.5, parseDecimal(" 0,5 ")!!, 0.0)
        assertEquals(12.0, parseDecimal("12,")!!, 0.0)
    }

    @Test
    fun parseDecimal_rejectsGarbageAndNegatives() {
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("abc"))
        assertNull(parseDecimal("-3"))
    }

    @Test
    fun formatDecimalInput_dropsNeedlessZero() {
        assertEquals("12", formatDecimalInput(12.0))
        assertEquals("12.5", formatDecimalInput(12.5))
    }

    // ── 24-hour shifts ──

    @Test
    fun sameStartAndEnd_isTwentyFourHours() {
        val shift = Shift(
            name = "24h", startTime = "08:00", endTime = "08:00",
            colorValue = 0xFF3F7DDE, hourlyRate = 10.0
        )
        assertEquals(24.0, PayEngine.workedHours(shift), 0.0)
        assertEquals(240.0, PayEngine.earnings(shift), 0.0)
    }

    // ── Rest days ──

    private fun worked(date: LocalDate) = date to listOf(
        Shift(name = "Day", startTime = "09:00", endTime = "17:00", colorValue = 0xFF3F7DDE)
    )

    private fun leave(date: LocalDate) = date to listOf(
        Shift(
            name = "Leave", startTime = "00:00", endTime = "23:59", colorValue = 0xFF3F7DDE,
            dayType = DayType.PAID_LEAVE
        )
    )

    @Test
    fun restDays_excludeLeaveAndFutureDays() {
        val month = YearMonth.of(2026, 9)
        val today = LocalDate.of(2026, 9, 10)
        val shifts = mapOf(
            worked(LocalDate.of(2026, 9, 1)),
            worked(LocalDate.of(2026, 9, 2)),
            leave(LocalDate.of(2026, 9, 3)),
            worked(LocalDate.of(2026, 9, 20)) // future: neither rest nor counted as past
        )

        val stats = StatsHelper.calculateMonthlyStats(shifts, month, today)

        // Days 1–10 have happened; 1, 2 worked and 3 on leave → 7 rest days.
        assertEquals(7, stats.restDays)
        assertEquals(stats.restDays, stats.restDates.size)
    }

    @Test
    fun restDays_pastPeriodCountsEveryUnloggedDay() {
        val month = YearMonth.of(2026, 2) // 28 days
        val today = LocalDate.of(2026, 9, 10)
        val shifts = mapOf(worked(LocalDate.of(2026, 2, 5)))

        val stats = StatsHelper.calculateMonthlyStats(shifts, month, today)

        assertEquals(27, stats.restDays)
    }

    // ── Backup validation ──

    @Test
    fun backup_withValidDates_isAccepted() {
        val json = """{"version":1,"shifts":{"2026-09-01":[]}}"""
        assertNotNull(BackupHelper.deserialize(json))
    }

    @Test
    fun backup_withInvalidDateKey_isRefused() {
        val json = """{"version":1,"shifts":{"not-a-date":[]}}"""
        assertNull(BackupHelper.deserialize(json))
    }

    @Test
    fun backup_withUnknownFieldsAndEnumValues_stillDecodes() {
        // A file from a newer version: an extra field, and a day type this
        // build doesn't know. Both must degrade gracefully, not fail.
        val json = """
            {"version":1,"futureField":true,"shifts":{"2026-09-01":[
              {"name":"X","startTime":"09:00","endTime":"17:00","colorValue":1,
               "dayType":"SOMETHING_NEW","extra":"ignored"}
            ]}}
        """.trimIndent()
        val data = BackupHelper.deserialize(json)
        assertNotNull(data)
        assertEquals(DayType.WORKED, data!!.shifts.getValue("2026-09-01").single().dayType)
    }
}
