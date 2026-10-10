package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TierOneFeaturesTest {

    private val mon = LocalDate.of(2026, 10, 5) // a Monday

    private fun shift(
        start: String,
        end: String,
        name: String = "Shift",
        type: DayType = DayType.WORKED,
        notes: String = "",
        location: String = ""
    ) = Shift(
        name = name, startTime = start, endTime = end, colorValue = 0,
        dayType = type, notes = notes, location = location, jobId = JobProfile.DEFAULT_ID
    )

    private fun kinds(issues: Map<LocalDate, List<RestIssue>>, day: LocalDate) =
        issues[day].orEmpty().map { it.kind }

    // ══════════ Rest warnings ══════════

    @Test
    fun `a night shift followed by an early start is short rest`() {
        val issues = RestCheck.issues(
            mapOf(
                mon to listOf(shift("22:00", "06:00")),
                mon.plusDays(1) to listOf(shift("14:00", "22:00"))
            )
        )
        assertEquals(listOf(RestIssueKind.SHORT_REST), kinds(issues, mon.plusDays(1)))
        assertTrue(issues.getValue(mon.plusDays(1)).single().message.startsWith("Only 8h off"))
    }

    @Test
    fun `eleven hours off is enough`() {
        val issues = RestCheck.issues(
            mapOf(mon to listOf(shift("09:00", "21:00")), mon.plusDays(1) to listOf(shift("08:00", "16:00")))
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `split shifts on one day are not short rest`() {
        val issues = RestCheck.issues(mapOf(mon to listOf(shift("07:00", "11:00"), shift("17:00", "21:00"))))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `overlapping shifts are flagged on the later one`() {
        val issues = RestCheck.issues(mapOf(mon to listOf(shift("09:00", "17:00"), shift("16:00", "20:00"))))
        assertEquals(listOf(RestIssueKind.OVERLAP), kinds(issues, mon))
    }

    @Test
    fun `leave days take no part`() {
        val issues = RestCheck.issues(
            mapOf(
                mon to listOf(shift("22:00", "06:00")),
                mon.plusDays(1) to listOf(shift("08:00", "16:00", type = DayType.PAID_LEAVE))
            )
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `a seventh day in a row is flagged, the sixth is not`() {
        val week = (0L..6L).associate { mon.plusDays(it) to listOf(shift("09:00", "13:00")) }
        val issues = RestCheck.issues(week)
        assertTrue(kinds(issues, mon.plusDays(5)).isEmpty())
        assertEquals(listOf(RestIssueKind.LONG_STREAK), kinds(issues, mon.plusDays(6)))
        assertTrue(issues.getValue(mon.plusDays(6)).single().message.startsWith("7th"))
    }

    @Test
    fun `a week over 48 hours is flagged once, where it tips over`() {
        // Five 10h days: 50h, crossing 48 on Friday.
        val week = (0L..4L).associate { mon.plusDays(it) to listOf(shift("08:00", "18:00")) }
        val issues = RestCheck.issues(week, DayOfWeek.MONDAY)
        assertEquals(listOf(RestIssueKind.LONG_WEEK), kinds(issues, mon.plusDays(4)))
        assertEquals(1, issues.values.flatten().count { it.kind == RestIssueKind.LONG_WEEK })
    }

    @Test
    fun `week hours follow the chosen week start`() {
        // Sun + Mon–Thu = 50h in a Sunday week, but only 40h in Monday weeks.
        val days = (-1L..3L).associate { mon.plusDays(it) to listOf(shift("08:00", "18:00")) }
        assertTrue(RestCheck.issues(days, DayOfWeek.MONDAY).values.flatten().none { it.kind == RestIssueKind.LONG_WEEK })
        assertEquals(1, RestCheck.issues(days, DayOfWeek.SUNDAY).values.flatten().count { it.kind == RestIssueKind.LONG_WEEK })
    }

    // ══════════ Calendar export ══════════

    private val stamp = Instant.parse("2026-10-09T12:00:00Z")

    @Test
    fun `a worked shift becomes a timed event, overnight ending the next day`() {
        val ics = CalendarExport.build(mapOf(mon to listOf(shift("22:00", "06:00", name = "Night"))), "Bar", stamp)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertTrue(ics.contains("DTSTART:20261005T220000\r\n"))
        assertTrue(ics.contains("DTEND:20261006T060000\r\n"))
        assertTrue(ics.contains("SUMMARY:Night\r\n"))
        assertTrue(ics.contains("DTSTAMP:20261009T120000Z\r\n"))
        assertTrue(ics.contains("TRANSP:OPAQUE"))
    }

    @Test
    fun `leave becomes an all-day event that doesn't block time`() {
        val ics = CalendarExport.build(
            mapOf(mon to listOf(shift("00:00", "23:59", name = "Annual leave", type = DayType.PAID_LEAVE))),
            "Bar", stamp
        )
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261005\r\n"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20261006\r\n"))
        assertTrue(ics.contains("TRANSP:TRANSPARENT"))
    }

    @Test
    fun `text is escaped`() {
        assertEquals("a\\, b\\; c\\\\d\\ne", CalendarExport.escape("a, b; c\\d\ne"))
    }

    @Test
    fun `long lines fold at 75 bytes without splitting a character`() {
        val greek = "SUMMARY:" + "Καφετέρια ".repeat(12)
        val folded = CalendarExport.fold(greek)
        val parts = folded.split("\r\n")
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        assertEquals(greek, parts.first() + parts.drop(1).joinToString("") { it.removePrefix(" ") })
    }

    @Test
    fun `the same entry keeps the same UID across exports`() {
        val shifts = mapOf(mon to listOf(shift("09:00", "17:00")))
        val uid = { s: String -> s.lines().first { it.startsWith("UID:") } }
        assertEquals(
            uid(CalendarExport.build(shifts, "Bar", stamp)),
            uid(CalendarExport.build(shifts, "Bar", stamp.plusSeconds(3600)))
        )
    }

    // ══════════ Goals and take-home ══════════

    private val job = JobProfile(id = "j", name = "Bar", colorValue = 0)

    @Test
    fun `take-home applies the deduction share`() {
        assertEquals(800.0, job.copy(deductionPercent = 20.0).takeHome(1000.0), 1e-9)
        assertEquals(1000.0, job.takeHome(1000.0), 1e-9)
        assertEquals(0.0, job.copy(deductionPercent = 150.0).takeHome(1000.0), 1e-9)
    }

    @Test
    fun `goal progress leads with earnings, falls back to hours`() {
        val totals = PeriodTotals(hours = 30.0, earnings = 600.0, shifts = 4)
        assertNull(GoalProgress.of(job, totals).headline)
        assertEquals(0.5, GoalProgress.of(job.copy(earningsGoal = 1200.0), totals).headline!!, 1e-9)
        assertEquals(0.75, GoalProgress.of(job.copy(hoursGoal = 40.0), totals).headline!!, 1e-9)
        assertEquals(0.5, GoalProgress.of(job.copy(earningsGoal = 1200.0, hoursGoal = 40.0), totals).headline!!, 1e-9)
    }

    @Test
    fun `goals use the shown range when it is a pay period, else the current one`() {
        val today = LocalDate.of(2026, 10, 9)
        val monthly = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 15)
        val shown = monthly.periodContaining(today)
        assertEquals(shown, monthly.goalRange(shown, today))

        val weekly = PayPeriod(cycle = PayCycle.WEEKLY, weekStart = 1)
        val october = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        assertEquals(weekly.periodContaining(today), weekly.goalRange(october, today))
    }

    // ══════════ Backup age ══════════

    private val utc = ZoneOffset.UTC
    private fun millis(date: String) = Instant.parse(date).toEpochMilli()

    @Test
    fun `backup age reads in calendar days`() {
        val now = millis("2026-10-09T08:00:00Z")
        assertEquals("Never backed up", BackupAge.label(null, now, utc))
        assertEquals("Last backup: today", BackupAge.label(millis("2026-10-09T01:00:00Z"), now, utc))
        assertEquals("Last backup: yesterday", BackupAge.label(millis("2026-10-08T23:00:00Z"), now, utc))
        assertEquals("Last backup: 12 days ago", BackupAge.label(millis("2026-09-27T10:00:00Z"), now, utc))
    }

    @Test
    fun `a backup goes stale after two weeks`() {
        val now = millis("2026-10-09T08:00:00Z")
        assertTrue(BackupAge.isStale(null, now, utc))
        assertFalse(BackupAge.isStale(millis("2026-09-26T08:00:00Z"), now, utc))
        assertTrue(BackupAge.isStale(millis("2026-09-25T08:00:00Z"), now, utc))
    }

    // ══════════ Review prompt ══════════

    @Test
    fun `review is asked once, after real use`() {
        val day = 86_400_000L
        val installed = 0L
        assertFalse(ReviewPolicy.shouldAsk(9, installed, null, 10 * day))
        assertFalse(ReviewPolicy.shouldAsk(10, installed, null, 2 * day))
        assertTrue(ReviewPolicy.shouldAsk(10, installed, null, 3 * day))
        assertFalse(ReviewPolicy.shouldAsk(50, installed, askedAt = 5 * day, now = 400 * day))
    }
}
