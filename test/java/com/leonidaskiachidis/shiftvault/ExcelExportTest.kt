package com.leonidaskiachidis.shiftvault

/*
 * The workbook is assembled from a lot of OOXML surface — styles, number
 * formats, a frozen filtered header, SUBTOTAL formulas, data bars and a chart.
 * Any one of those going wrong produces a file Excel refuses to open, which the
 * compiler cannot tell us about. So the test writes a real report and reads it
 * back with POI: if it round-trips, the XML is at least well-formed and the
 * structure is what we meant to write.
 */

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class ExcelExportTest {

    private val meta = ReportMeta(
        profileName = "Restaurant",
        profileColorValue = 0xFF3F7DDE,
        currencyCode = "EUR"
    )

    private fun sampleShifts(): Map<LocalDate, List<Shift>> = mapOf(
        LocalDate.of(2026, 1, 5) to listOf(
            Shift(
                name = "Morning", startTime = "09:00", endTime = "17:00",
                colorValue = 0xFF3F7DDE, hourlyRate = 12.0, breakMinutes = 30,
                jobId = JobProfile.DEFAULT_ID
            )
        ),
        LocalDate.of(2026, 1, 6) to listOf(
            Shift(
                name = "Morning", startTime = "09:00", endTime = "17:00",
                colorValue = 0xFF3F7DDE, hourlyRate = 12.0, breakMinutes = 30,
                jobId = JobProfile.DEFAULT_ID
            )
        ),
        LocalDate.of(2026, 2, 9) to listOf(
            Shift(
                name = "Night", startTime = "22:00", endTime = "06:00",
                colorValue = 0xFFE04B4B, hourlyRate = 15.0, payMultiplier = 1.5,
                bonus = 10.0, jobId = JobProfile.DEFAULT_ID
            ),
            Shift(
                name = "Annual leave", startTime = "00:00", endTime = "23:59",
                colorValue = 0xFF8AAE22, hourlyRate = 12.0,
                dayType = DayType.PAID_LEAVE, paidLeaveHours = 8.0,
                jobId = JobProfile.DEFAULT_ID
            )
        )
    )

    private fun writeAndReopen(shifts: Map<LocalDate, List<Shift>>): XSSFWorkbook {
        val bytes = ByteArrayOutputStream().also {
            ExportHelper.exportShiftsToExcel(shifts, it, meta)
        }.toByteArray()
        assertTrue("workbook should not be empty", bytes.size > 2_000)
        return XSSFWorkbook(ByteArrayInputStream(bytes))
    }

    @Test
    fun `report round-trips and opens on the summary sheet`() {
        writeAndReopen(sampleShifts()).use { wb ->
            assertEquals("Summary", wb.getSheetAt(0).sheetName)
            assertNotNull(wb.getSheet("Shifts"))
        }
    }

    @Test
    fun `shifts sheet carries every entry, a frozen filtered header and live totals`() {
        writeAndReopen(sampleShifts()).use { wb ->
            val sheet = wb.getSheet("Shifts")

            // Header sits at row 3 (title, subtitle, blank, header).
            assertEquals("Date", sheet.getRow(3).getCell(0).stringCellValue)
            assertNotNull("header should be frozen", sheet.paneInformation)
            assertEquals(1, sheet.ctWorksheet.autoFilter?.let { 1 } ?: 0)

            // Four shifts across three days, then the totals row.
            val firstData = 4
            val lastData = firstData + 3
            assertEquals("Morning", sheet.getRow(firstData).getCell(2).stringCellValue)
            assertEquals("Annual leave", sheet.getRow(lastData).getCell(2).stringCellValue)

            val totalRow = sheet.getRow(lastData + 1)
            assertEquals("TOTAL", totalRow.getCell(0).stringCellValue)
            assertEquals(CellType.FORMULA, totalRow.getCell(12).cellType)
            assertEquals(
                "SUBTOTAL(109,M5:M8)",
                totalRow.getCell(12).cellFormula
            )
        }
    }

    @Test
    fun `hours and earnings agree with PayEngine`() {
        val shifts = sampleShifts()
        writeAndReopen(shifts).use { wb ->
            val sheet = wb.getSheet("Shifts")
            val all = shifts.keys.sorted().flatMap { shifts[it]!! }

            all.forEachIndexed { index, shift ->
                val row = sheet.getRow(4 + index)
                assertEquals(
                    "hours for ${shift.name}",
                    PayEngine.payableHours(shift),
                    row.getCell(8).numericCellValue,
                    0.0001
                )
                assertEquals(
                    "earnings for ${shift.name}",
                    PayEngine.earnings(shift),
                    row.getCell(12).numericCellValue,
                    0.0001
                )
            }
        }
    }

    @Test
    fun `dates are written as real dates, not text`() {
        writeAndReopen(sampleShifts()).use { wb ->
            val cell = wb.getSheet("Shifts").getRow(4).getCell(0)
            assertEquals(CellType.NUMERIC, cell.cellType)
            assertEquals(LocalDate.of(2026, 1, 5), cell.localDateTimeCellValue.toLocalDate())
        }
    }

    @Test
    fun `an empty profile still produces a readable report`() {
        writeAndReopen(emptyMap()).use { wb ->
            assertEquals("Summary", wb.getSheetAt(0).sheetName)
            assertNotNull(wb.getSheet("Shifts"))
        }
    }

    @Test
    fun `summary carries a live chart once there is more than one month`() {
        writeAndReopen(sampleShifts()).use { wb ->
            val summary = wb.getSheet("Summary")
            assertEquals(
                "chart should be generated, not swallowed by its runCatching",
                1, summary.createDrawingPatriarch().charts.size
            )
        }
    }
}
