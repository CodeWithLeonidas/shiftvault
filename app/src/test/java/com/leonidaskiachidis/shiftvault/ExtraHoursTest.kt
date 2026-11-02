package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ExtraHoursTest {

    private fun shift(
        start: String = "09:00",
        end: String = "17:00",
        job: String = "a",
        extra: Boolean = false,
        paid: Boolean = false,
        type: DayType = DayType.WORKED
    ) = Shift(
        name = "S", startTime = start, endTime = end, colorValue = 0,
        jobId = job, isExtra = extra, extraPaid = paid, dayType = type
    )

    private val oct3 = LocalDate.of(2026, 10, 3)
    private val oct10 = LocalDate.of(2026, 10, 10)
    private val sep26 = LocalDate.of(2026, 9, 26)

    @Test
    fun `only worked extra shifts are listed, newest first, by month`() {
        val shifts = mapOf(
            sep26 to listOf(shift(extra = true, paid = true)),
            oct3 to listOf(shift(), shift("18:00", "22:00", extra = true)),
            oct10 to listOf(shift(extra = true), shift(extra = true, type = DayType.PAID_LEAVE))
        )
        val entries = ExtraHours.entries(shifts)
        assertEquals(listOf(oct10, oct3, sep26), entries.map { it.date })
        assertEquals(1, entries[1].index)
        assertEquals(12.0, ExtraHours.pendingHours(entries), 1e-9)
        assertEquals(8.0, ExtraHours.paidHours(entries), 1e-9)

        val months = ExtraHours.byMonth(entries)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.month })
        assertEquals(12.0, months[0].pendingHours, 1e-9)
        assertTrue(months[1].allPaid)
    }

    @Test
    fun `marking one shift paid touches only that shift`() {
        val all = mapOf(oct3 to listOf(shift(job = "b", extra = true), shift(extra = true), shift(extra = true)))
        val after = ExtraHours.setPaid(all, "a", mapOf(oct3 to setOf(1)), paid = true)
        val mine = ProfileScope.daySlice(after, oct3, "a")
        assertEquals(listOf(false, true), mine.map { it.extraPaid })
        assertFalse(ProfileScope.daySlice(after, oct3, "b").single().extraPaid)
    }

    @Test
    fun `extra and paid survive the database mapping`() {
        val s = shift(extra = true, paid = true)
        assertEquals(s, s.toColumns().toShift())
        assertTrue(s.toColumns().isExtra)
    }

    @Test
    fun `old backups are recognised as predating the Extra switch`() {
        val old = BackupHelper.deserialize("""{"version":1,"shifts":{},"templates":[]}""")!!
        assertFalse(old.extraFlagged)
        val now = BackupHelper.deserialize(BackupHelper.serialize(BackupData(extraFlagged = true)))!!
        assertTrue(now.extraFlagged)
    }

    @Test
    fun `a whole month can be marked paid and back`() {
        val all = mapOf(
            oct3 to listOf(shift(extra = true)),
            oct10 to listOf(shift(), shift(extra = true)),
            sep26 to listOf(shift(extra = true))
        )
        val october = ExtraHours.byMonth(ExtraHours.entries(all)).first()
        val paid = ExtraHours.setPaid(all, "a", ExtraHours.targetsOf(october.entries), paid = true)
        assertTrue(ExtraHours.byMonth(ExtraHours.entries(paid)).first().allPaid)
        assertFalse(paid.getValue(sep26).single().extraPaid)
        assertFalse("a normal shift is never marked", paid.getValue(oct10).first().extraPaid)

        val back = ExtraHours.setPaid(paid, "a", ExtraHours.targetsOf(october.entries), paid = false)
        assertEquals(all, back)
    }
}
