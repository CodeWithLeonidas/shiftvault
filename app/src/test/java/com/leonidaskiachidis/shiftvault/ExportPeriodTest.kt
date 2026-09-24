package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExportPeriodTest {

    private val today = LocalDate.of(2026, 9, 24)

    @Test
    fun calendarMonthPay_doesNotListTheSameMonthTwice() {
        val titles = exportPeriodChoices(PayPeriod(), today, shown = null).map { it.title }
        // "This month" equals "This pay period" here, so only the first survives.
        assertEquals(
            listOf("This pay period", "Last pay period", "This year", "All time"),
            titles
        )
    }

    @Test
    fun customPayCycle_listsPayAndCalendarPeriodsSeparately() {
        val pay = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 15)
        val choices = exportPeriodChoices(pay, today, shown = null)
        assertEquals(
            listOf("This pay period", "Last pay period", "This month", "Last month", "This year", "All time"),
            choices.map { it.title }
        )
        assertEquals(DateRange(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 14)), choices[0].range)
        assertNull(choices.last().range)
    }

    @Test
    fun analyticsPeriod_leadsTheList() {
        val shown = ExportPeriod("Shown in Analytics", DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        assertEquals(shown, exportPeriodChoices(PayPeriod(), today, shown).first())
    }

    @Test
    fun request_roundTripsItsRange() {
        val range = DateRange(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 14))
        assertEquals(range, ExportRequest.of(ExportFormat.PDF, range).range)
        assertNull(ExportRequest.of(ExportFormat.EXCEL, null).range)
    }

    @Test
    fun fileName_namesThePeriod() {
        val range = DateRange(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 14))
        assertEquals(
            "ShiftVault-Καφετέρια-2026-09-15_to_2026-10-14.pdf",
            ExportHelper.suggestFileName("Καφετέρια", "pdf", range)
        )
        assertTrue(ExportHelper.suggestFileName("Main job", "xlsx").startsWith("ShiftVault-Main-job-"))
    }
}
