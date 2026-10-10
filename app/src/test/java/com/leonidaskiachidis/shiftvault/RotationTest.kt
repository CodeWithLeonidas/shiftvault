package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RotationTest {

    private val start = LocalDate.of(2026, 10, 1)
    private val day = Shift(name = "Day", startTime = "07:00", endTime = "19:00", colorValue = 1)
    private val night = Shift(name = "Night", startTime = "19:00", endTime = "07:00", colorValue = 2)
    private val fourFour = RotationPattern.Presets.first { it.name == "4 on / 4 off" }

    @Test
    fun presets_areWellFormed() {
        RotationPattern.Presets.forEach {
            assertTrue(it.name, it.length in 1..RotationPattern.MAX_LENGTH)
            assertTrue(it.name, it.code.all { c -> c in "0123" })
            assertTrue(it.name, it.description.isNotBlank())
        }
        assertEquals(14, RotationPattern.Presets.first { it.name.startsWith("2-2-3") }.length)
        assertEquals(28, RotationPattern.Presets.first { it.name == "DuPont" }.length)
    }

    @Test
    fun slotOn_repeatsAndWorksBeforeStart() {
        assertEquals(RotationSlot.A, RotationFill.slotOn(fourFour, start, start))
        assertEquals(RotationSlot.OFF, RotationFill.slotOn(fourFour, start, start.plusDays(4)))
        assertEquals(RotationSlot.A, RotationFill.slotOn(fourFour, start, start.plusDays(8)))
        assertEquals(RotationSlot.OFF, RotationFill.slotOn(fourFour, start, start.minusDays(1)))
    }

    @Test
    fun fill_fourOnFourOff_over16Days() {
        val result = RotationFill.apply(
            emptyMap(), "job", fourFour, mapOf(RotationSlot.A to day),
            start, start.plusDays(15), RotationConflict.SKIP
        )
        assertEquals(8, result.size)
        assertTrue(start in result && start.plusDays(3) in result)
        assertFalse(start.plusDays(4) in result)
        assertEquals("job", result.getValue(start).single().jobId)
    }

    @Test
    fun fill_usesSlotB_forNights() {
        val daysNights = RotationPattern.Presets.first { it.name.startsWith("2 days") }
        val result = RotationFill.apply(
            emptyMap(), "job", daysNights, mapOf(RotationSlot.A to day, RotationSlot.B to night),
            start, start.plusDays(7), RotationConflict.SKIP
        )
        assertEquals("Day", result.getValue(start).single().name)
        assertEquals("Night", result.getValue(start.plusDays(2)).single().name)
        assertEquals(4, result.size)
    }

    @Test
    fun unassignedSlot_isTreatedAsOff() {
        val daysNights = RotationPattern.Presets.first { it.name.startsWith("2 days") }
        val result = RotationFill.apply(
            emptyMap(), "job", daysNights, mapOf(RotationSlot.A to day),
            start, start.plusDays(7), RotationConflict.SKIP
        )
        assertEquals(2, result.size)
    }

    @Test
    fun conflicts_skipReplaceOrAdd() {
        val existing = mapOf(start to listOf(night.copy(jobId = "job")))
        fun fill(c: RotationConflict) = RotationFill.apply(
            existing, "job", fourFour, mapOf(RotationSlot.A to day), start, start, c
        ).getValue(start).map { it.name }

        assertEquals(listOf("Night"), fill(RotationConflict.SKIP))
        assertEquals(listOf("Day"), fill(RotationConflict.REPLACE))
        assertEquals(listOf("Night", "Day"), fill(RotationConflict.ADD))
    }

    @Test
    fun otherJobs_areNeverTouched() {
        val existing = mapOf(start to listOf(night.copy(jobId = "other")))
        val result = RotationFill.apply(
            existing, "job", fourFour, mapOf(RotationSlot.A to day), start, start, RotationConflict.REPLACE
        )
        assertEquals(setOf("other", "job"), result.getValue(start).map { it.jobId }.toSet())
    }

    @Test
    fun preview_countsWhatApplyWouldDo() {
        val existing = mapOf(start to listOf(night.copy(jobId = "job")))
        val p = RotationFill.preview(
            existing, "job", fourFour, mapOf(RotationSlot.A to day),
            start, start.plusDays(7), RotationConflict.SKIP
        )
        assertEquals(RotationPreview(shiftsAdded = 3, daysSkipped = 1, daysReplaced = 0), p)
    }

    @Test
    fun fill_usesSlotC_forTheThirdShift() {
        val evening = Shift(name = "Evening", startTime = "15:00", endTime = "23:00", colorValue = 3)
        val threeShift = RotationPattern.Presets.first { it.code == "1122330000" }
        assertEquals(listOf(RotationSlot.A, RotationSlot.B, RotationSlot.C), threeShift.slotsUsed)
        val result = RotationFill.apply(
            emptyMap(), "job", threeShift,
            mapOf(RotationSlot.A to day, RotationSlot.B to evening, RotationSlot.C to night),
            start, start.plusDays(9), RotationConflict.SKIP
        )
        assertEquals(listOf("Day", "Day", "Evening", "Evening", "Night", "Night"),
            (0L..5L).map { result.getValue(start.plusDays(it)).single().name })
        assertEquals(6, result.size)
    }

    @Test
    fun range_isCapped() {
        val everyDay = RotationPattern("Every day", "1")
        val result = RotationFill.apply(
            emptyMap(), "job", everyDay, mapOf(RotationSlot.A to day),
            start, start.plusYears(10), RotationConflict.SKIP
        )
        assertEquals(RotationFill.MAX_DAYS, result.size)
    }
}
