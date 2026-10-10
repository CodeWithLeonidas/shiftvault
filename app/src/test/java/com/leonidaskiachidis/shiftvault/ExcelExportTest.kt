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
    fun `a salaried report totals the salary, not the shifts`() {
        val job = JobProfile(
            id = JobProfile.DEFAULT_ID, name = "Office", colorValue = 0,
            pay = PaySetup(mode = PayMode.FIXED_MONTHLY, salaryHistory = listOf(SalaryRate(null, 130_000)))
        )
        val shifts = sampleShifts()
        val range = DateRange(shifts.keys.min(), shifts.keys.max())
        val pay = Compensation.forRange(job, shifts, range)
        val bytes = ByteArrayOutputStream().also {
            ExportHelper.exportShiftsToExcel(shifts, it, meta.copy(pay = pay))
        }.toByteArray()
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            val summary = wb.getSheet("Summary")
            val labels = (0..summary.lastRowNum).mapNotNull { summary.getRow(it)?.getCell(0)?.stringCellValue }
            assertTrue("Base salary" in labels)
            assertTrue("Total expected" in labels)
            val totalRow = (0..summary.lastRowNum).first { summary.getRow(it)?.getCell(0)?.stringCellValue == "Total expected" }
            assertEquals(pay.expectedCents / 100.0, summary.getRow(totalRow).getCell(1).numericCellValue, 1e-9)
        }
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
    fun `totals calculate to the same figures as PayEngine`() {
        val shifts = sampleShifts()
        writeAndReopen(shifts).use { wb ->
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            val all = shifts.values.flatten()

            val totalRow = wb.getSheet("Shifts").getRow(8)
            assertEquals(
                all.sumOf { PayEngine.earnings(it) },
                evaluator.evaluate(totalRow.getCell(12)).numberValue, 0.0001
            )
            assertEquals(
                all.sumOf { PayEngine.payableHours(it) },
                evaluator.evaluate(totalRow.getCell(8)).numberValue, 0.0001
            )
        }
    }

    @Test
    fun `report is styled - accent header, formats, zebra rows and data bars`() {
        writeAndReopen(sampleShifts()).use { wb ->
            val sheet = wb.getSheet("Shifts")

            val header = sheet.getRow(3).getCell(0).cellStyle
            assertTrue("header text is bold", wb.getFontAt(header.fontIndex).bold)
            assertNotNull("header has the profile colour as fill", header.fillForegroundColorColor)

            val earnings = sheet.getRow(4).getCell(12).cellStyle
            assertEquals("\"€\"#,##0.00", earnings.dataFormatString)
            assertEquals("0.00\"h\"", sheet.getRow(4).getCell(8).cellStyle.dataFormatString)

            // Row 5 is the second data row, so it carries the zebra fill.
            assertNotNull(sheet.getRow(5).getCell(2).cellStyle.fillForegroundColorColor)

            assertEquals(1, sheet.sheetConditionalFormatting.numConditionalFormattings)
            assertTrue("gridlines hidden for a cleaner report", !sheet.isDisplayGridlines)
        }
    }

    /**
     * Not a check — a preview. Writes a realistic report to
     * app/build/reports/sample-earnings-report.xlsx on every test run, so the
     * workbook's design can be opened in Excel without building the app.
     */
    @Test
    fun `write a sample report for previewing the design`() {
        val job = JobProfile.DEFAULT_ID
        fun day(d: LocalDate, vararg s: Shift) = d to s.toList()
        val morning = Shift(
            name = "Πρωινή βάρδια", startTime = "07:00", endTime = "15:00", colorValue = 0xFF3F7DDE,
            hourlyRate = 9.5, breakMinutes = 30, location = "Κεντρικό κατάστημα", jobId = job
        )
        val night = Shift(
            name = "Νυχτερινή", startTime = "23:00", endTime = "07:00", colorValue = 0xFFE04B4B,
            hourlyRate = 9.5, payMultiplier = 1.25, bonus = 5.0, jobId = job
        )
        val sunday = morning.copy(name = "Κυριακή", payMultiplier = 1.75, notes = "Αργία")
        val leave = Shift(
            name = "Κανονική άδεια", startTime = "00:00", endTime = "23:59", colorValue = 0xFF8AAE22,
            hourlyRate = 9.5, dayType = DayType.PAID_LEAVE, paidLeaveHours = 8.0, jobId = job
        )
        val shifts = buildMap {
            var d = LocalDate.of(2026, 8, 3)
            repeat(40) { i ->
                val entry = when {
                    d.dayOfWeek == java.time.DayOfWeek.SUNDAY -> day(d, sunday)
                    d.dayOfWeek == java.time.DayOfWeek.SATURDAY -> null
                    i % 9 == 4 -> day(d, leave)
                    i % 3 == 2 -> day(d, night)
                    else -> day(d, morning)
                }
                entry?.let { put(it.first, it.second) }
                d = d.plusDays(1)
            }
        }
        val out = java.io.File("build/reports/sample-earnings-report.xlsx")
        out.parentFile?.mkdirs()
        out.outputStream().use {
            ExportHelper.exportShiftsToExcel(
                shifts, it, meta.copy(profileName = "Καφετέρια Λιμάνι"),
                accent = 0x3F7DDE // the profile blue, as the app would compute it
            )
        }
        assertTrue(out.length() > 2_000)
    }

    @Test
    fun `awkward text survives - ampersands, markup, Greek and line breaks`() {
        val tricky = mapOf(
            LocalDate.of(2026, 3, 2) to listOf(
                Shift(
                    name = "Καφετέρια & <Bar>", startTime = "09:00", endTime = "17:00",
                    colorValue = 0xFF3F7DDE, hourlyRate = 10.0,
                    notes = "Line one\nLine \"two\"", jobId = JobProfile.DEFAULT_ID
                )
            )
        )
        val trickyMeta = meta.copy(profileName = "R&D <Team>")
        val bytes = ByteArrayOutputStream().also {
            ExportHelper.exportShiftsToExcel(tricky, it, trickyMeta)
        }.toByteArray()

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            val row = wb.getSheet("Shifts").getRow(4)
            assertEquals("Καφετέρια & <Bar>", row.getCell(2).stringCellValue)
            assertEquals("Line one\nLine \"two\"", row.getCell(14).stringCellValue)
        }
    }
}
