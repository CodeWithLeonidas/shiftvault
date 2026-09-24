package com.leonidaskiachidis.shiftvault

/*
 * The conversion between Shift and database rows, and the "which days
 * changed" diff that decides what gets rewritten. Both are pure, so they run
 * here on the JVM; the database itself is covered by ShiftDaoTest on a device.
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RoomMappingTest {

    private val full = Shift(
        name = "Night", startTime = "22:00", endTime = "06:00", colorValue = 0xFFE04B4B,
        hourlyRate = 12.5, payMultiplier = 1.5, bonus = 10.0, breakMinutes = 30,
        paidBreak = true, dayType = DayType.WORKED, paidLeaveHours = 8.0,
        location = "Downtown", jobId = "job_a", notes = "Covered for Anna", iconName = "moon"
    )

    @Test
    fun shift_roundTripsThroughRow_unchanged() {
        assertEquals(full, full.toColumns().toShift())
    }

    @Test
    fun legacyRecord_isMigratedBeforeBeingStored() {
        val v1 = full.copy(isDayOff = true, dayOffPaidHours = 6.0, dayOffHourlyRate = 11.0, jobId = null)
        val stored = v1.toColumns()
        assertEquals("PAID_LEAVE", stored.dayType)
        assertEquals(6.0, stored.paidLeaveHours, 0.0)
        assertEquals(11.0, stored.hourlyRate, 0.0)
        assertEquals(JobProfile.DEFAULT_ID, stored.jobId)
    }

    @Test
    fun unknownDayType_readsBackAsWorked() {
        val row = full.toColumns().copy(dayType = "SOMETHING_NEW")
        assertEquals(DayType.WORKED, row.toShift().dayType)
    }

    @Test
    fun calendarRows_keepOrderAndGroupBackByDate() {
        val d1 = LocalDate.of(2026, 9, 1)
        val d2 = LocalDate.of(2026, 9, 2)
        val a = full.copy(name = "A")
        val b = full.copy(name = "B")
        val rows = calendarRows(d1, listOf(a, b)) + calendarRows(d2, listOf(b))

        assertEquals(listOf(0, 1, 0), rows.map { it.position })
        assertEquals(mapOf(d1 to listOf(a, b), d2 to listOf(b)), rows.toShiftMap())
    }

    @Test
    fun savedRows_numberPositionsInListOrder() {
        val rows = savedRows(listOf(full.copy(name = "A"), full.copy(name = "B")))
        assertEquals(listOf("A" to 0, "B" to 1), rows.map { it.shift.name to it.position })
    }

    @Test
    fun changedDays_findsOnlyAddedRemovedOrEditedDays() {
        val same = LocalDate.of(2026, 9, 1)
        val edited = LocalDate.of(2026, 9, 2)
        val removed = LocalDate.of(2026, 9, 3)
        val added = LocalDate.of(2026, 9, 4)
        val old = mapOf(
            same to listOf(full),
            edited to listOf(full),
            removed to listOf(full)
        )
        val new = mapOf(
            same to listOf(full),
            edited to listOf(full.copy(bonus = 99.0)),
            added to listOf(full)
        )
        assertEquals(setOf(edited, removed, added), changedDays(old, new))
    }

    @Test
    fun changedDays_seesReorderingWithinADay() {
        val d = LocalDate.of(2026, 9, 1)
        val a = full.copy(name = "A")
        val b = full.copy(name = "B")
        assertTrue(d in changedDays(mapOf(d to listOf(a, b)), mapOf(d to listOf(b, a))))
    }
}
