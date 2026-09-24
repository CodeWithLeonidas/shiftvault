package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderPlannerTest {

    private val today = LocalDate.of(2026, 9, 24)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today) = day.atTime(h, m)
    private fun shift(start: String, end: String = "17:00", name: String = "Shift") =
        Shift(name = name, startTime = start, endTime = end, colorValue = 0)

    @Test
    fun nextReminder_isLeadBeforeTheNextShift() {
        val shifts = mapOf(today to listOf(shift("14:00")), today.plusDays(1) to listOf(shift("09:00")))
        assertEquals(at(13), ReminderPlanner.nextShiftReminder(shifts, 60, at(10)))
    }

    @Test
    fun shiftInsideLeadWindow_isSkippedForTheFollowingOne() {
        // 13:30 now, shift at 14:00: its reminder (13:00) has passed, so the next is tomorrow's.
        val shifts = mapOf(today to listOf(shift("14:00")), today.plusDays(1) to listOf(shift("09:00")))
        assertEquals(at(8, day = today.plusDays(1)), ReminderPlanner.nextShiftReminder(shifts, 60, at(13, 30)))
    }

    @Test
    fun leaveAndAllDayMarkers_getNoReminder() {
        val shifts = mapOf(
            today to listOf(
                shift("00:00", "23:59", "Marker"),
                shift("09:00").copy(dayType = DayType.PAID_LEAVE)
            )
        )
        assertNull(ReminderPlanner.nextShiftReminder(shifts, 60, at(6)))
    }

    @Test
    fun dueNow_findsShiftsWithinLeadPlusGrace_andSkipsAnnounced() {
        // 13:00 with a 1 h lead and 20 min grace covers starts up to 14:20: B rides
        // along with A; C (14:30) gets its own reminder at 13:30.
        val shifts = mapOf(today to listOf(shift("14:00", name = "A"), shift("14:15", name = "B"), shift("14:30", name = "C")))
        val due = ReminderPlanner.dueNow(shifts, 60, at(13), alreadyAnnouncedUntil = null)
        assertEquals(listOf("A", "B"), due.map { it.shift.name })

        val afterA = ReminderPlanner.dueNow(shifts, 60, at(13), alreadyAnnouncedUntil = at(14))
        assertEquals(listOf("B"), afterA.map { it.shift.name })
    }

    @Test
    fun logReminder_todayIfAhead_elseTomorrow() {
        val time = LocalTime.of(20, 0)
        assertEquals(at(20), ReminderPlanner.nextLogReminder(time, at(19)))
        assertEquals(at(20, day = today.plusDays(1)), ReminderPlanner.nextLogReminder(time, at(20, 30)))
    }

    @Test
    fun hasEntryOn_countsAnythingLogged() {
        assertTrue(ReminderPlanner.hasEntryOn(mapOf(today to listOf(shift("09:00"))), today))
        assertFalse(ReminderPlanner.hasEntryOn(emptyMap(), today))
    }

    @Test
    fun leadLabels() {
        assertEquals(listOf("15 min", "30 min", "1 h", "2 h", "1 h 30 min"),
            listOf(15, 30, 60, 120, 90).map { ReminderSettings.leadLabel(it) })
    }

    @Test
    fun overnightShiftFromYesterday_isNotAnnouncedAgain() {
        // Started at 22:00 yesterday: already in the past, so never "upcoming".
        val shifts = mapOf(today.minusDays(1) to listOf(shift("22:00", "06:00")))
        assertTrue(ReminderPlanner.upcoming(shifts, LocalDateTime.of(today, LocalTime.of(1, 0))).isEmpty())
    }
}
