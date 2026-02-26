package com.leonidaskiachidis.shiftvault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.OutputStream
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter

object ExportHelper {

    fun exportShiftsToPdf(context: Context, savedShifts: Map<LocalDate, List<Shift>>, outputStream: OutputStream) {
        val pdfDocument = PdfDocument()
        val paint = Paint()
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val dateFormatter = DateTimeFormatter.ofPattern("dd MMM")

        // 1. Calculate Totals for the Summary
        var totalShiftsCount = 0
        var totalHoursSum = 0.0
        var totalEarningsSum = 0.0

        savedShifts.values.flatten().forEach { shift ->
            val start = LocalTime.parse(shift.startTime, timeFormatter)
            var end = LocalTime.parse(shift.endTime, timeFormatter)
            if (end.isBefore(start)) end = end.plusHours(24)

            val netMinutes = Duration.between(start, end).toMinutes() - shift.breakMinutes
            val hours = netMinutes / 60.0

            totalShiftsCount++
            totalHoursSum += hours
            totalEarningsSum += (hours * shift.hourlyRate)
        }

        // 2. Initial Page Setup
        var pageCount = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageCount).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas
        var yPosition = 120f

        // 3. Draw Report Header & Summary (Only on Page 1)
        paint.textSize = 24f
        paint.isFakeBoldText = true
        canvas.drawText("ShiftVault Earnings Report", 50f, 50f, paint)

        paint.textSize = 12f
        paint.isFakeBoldText = false
        canvas.drawText("Generated on: ${LocalDate.now()}", 50f, 80f, paint)

        paint.isFakeBoldText = true
        canvas.drawText("SUMMARY", 400f, 50f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 10f
        canvas.drawText("Total Shifts: $totalShiftsCount", 400f, 70f, paint)
        canvas.drawText(String.format("Total Hours: %.2f h", totalHoursSum), 400f, 85f, paint)
        canvas.drawText(String.format("Total Earnings: %.2f", totalEarningsSum), 400f, 100f, paint)

        // 4. Draw Table Headers (Initial)
        drawTableHeaders(canvas, paint, yPosition)
        yPosition += 25f

        // 5. Draw the actual shift data
        val sortedDates = savedShifts.keys.sorted()
        var currentMonth: Month? = null

        sortedDates.forEach { date ->
            if (date.month != currentMonth) {
                currentMonth = date.month

                if (yPosition > 780f) {
                    pdfDocument.finishPage(page)
                    pageCount++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageCount).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    yPosition = 50f
                    drawTableHeaders(canvas, paint, yPosition)
                    yPosition += 25f
                }

                yPosition += 10f
                paint.isFakeBoldText = true
                paint.color = android.graphics.Color.GRAY
                canvas.drawText("${date.month} ${date.year}", 50f, yPosition, paint)
                yPosition += 5f
                canvas.drawLine(50f, yPosition, 550f, yPosition, paint)
                yPosition += 20f
                paint.isFakeBoldText = false
                paint.color = android.graphics.Color.BLACK
            }

            savedShifts[date]?.forEach { shift ->
                if (yPosition > 800f) {
                    pdfDocument.finishPage(page)
                    pageCount++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageCount).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    yPosition = 50f
                    drawTableHeaders(canvas, paint, yPosition)
                    yPosition += 25f
                }

                val start = LocalTime.parse(shift.startTime, timeFormatter)
                var end = LocalTime.parse(shift.endTime, timeFormatter)
                if (end.isBefore(start)) end = end.plusHours(24)

                val netMinutes = Duration.between(start, end).toMinutes() - shift.breakMinutes
                val hours = netMinutes / 60.0
                val earnings = hours * shift.hourlyRate

                canvas.drawText(date.format(dateFormatter), 50f, yPosition, paint)
                canvas.drawText("${shift.startTime} - ${shift.endTime}", 150f, yPosition, paint)
                canvas.drawText(String.format("%.2f h", hours), 330f, yPosition, paint)
                canvas.drawText(String.format("%.2f", earnings), 450f, yPosition, paint)

                yPosition += 20f
            }
        }

        pdfDocument.finishPage(page)

        try {
            pdfDocument.writeTo(outputStream)
            Toast.makeText(context, "PDF saved successfully!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving PDF", Toast.LENGTH_SHORT).show()
        } finally {
            pdfDocument.close()
            outputStream.close()
        }
    }

    private fun drawTableHeaders(canvas: Canvas, paint: Paint, yPos: Float) {
        paint.isFakeBoldText = true
        paint.textSize = 12f
        canvas.drawText("Date", 50f, yPos, paint)
        canvas.drawText("Shift Time", 150f, yPos, paint)
        canvas.drawText("Hours", 330f, yPos, paint)
        canvas.drawText("Earnings", 450f, yPos, paint)
        canvas.drawLine(50f, yPos + 5f, 550f, yPos + 5f, paint)
        paint.isFakeBoldText = false
    }

    fun exportShiftsToExcel(context: Context, savedShifts: Map<LocalDate, List<Shift>>, outputStream: OutputStream) {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Shift Report")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")

        val headerStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 12
                color = IndexedColors.WHITE.index
            }
            setFont(font)
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            setBorderBottom(BorderStyle.THIN)
            setBorderTop(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
        }

        val titleStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 16
            }
            setFont(font)}

        val summaryLabelStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont().apply { bold = true }
            setFont(font)
        }

        val summaryValueStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val monthStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 11
                color = IndexedColors.DARK_BLUE.index
            }
            setFont(font)
        }

        val currencyStyle = workbook.createCellStyle().apply {
            dataFormat = workbook.createDataFormat().getFormat("0.00")
        }

        var totalShiftsCount = 0
        var totalHoursSum = 0.0
        var totalEarningsSum = 0.0

        savedShifts.values.flatten().forEach { shift ->
            val start = LocalTime.parse(shift.startTime, timeFormatter)
            var end = LocalTime.parse(shift.endTime, timeFormatter)
            if (end.isBefore(start)) end = end.plusHours(24)
            val netMinutes = Duration.between(start, end).toMinutes() - shift.breakMinutes
            val hours = netMinutes / 60.0
            totalShiftsCount++
            totalHoursSum += hours
            totalEarningsSum += (hours * shift.hourlyRate)
        }

        var rowNum = 0

        val titleRow = sheet.createRow(rowNum++)
        titleRow.createCell(0).apply {
            setCellValue("ShiftVault Earnings Report")
            cellStyle = titleStyle
        }
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 3))

        rowNum++

        val dateRow = sheet.createRow(rowNum++)
        dateRow.createCell(0).setCellValue("Generated on: ${LocalDate.now().format(dateFormatter)}")

        rowNum++

        val summaryHeaderRow = sheet.createRow(rowNum++)
        summaryHeaderRow.createCell(0).apply {
            setCellValue("SUMMARY")
            cellStyle = summaryLabelStyle
        }
        val totalShiftsRow = sheet.createRow(rowNum++)
        totalShiftsRow.createCell(0).apply {
            setCellValue("Total Shifts:")
            cellStyle = summaryLabelStyle
        }
        totalShiftsRow.createCell(1).apply {
            setCellValue(totalShiftsCount.toDouble())
            cellStyle = summaryValueStyle
        }

        val totalHoursRow = sheet.createRow(rowNum++)
        totalHoursRow.createCell(0).apply {
            setCellValue("Total Hours:")
            cellStyle = summaryLabelStyle
        }
        totalHoursRow.createCell(1).apply {
            setCellValue(totalHoursSum)
            cellStyle = currencyStyle
        }

        val totalEarningsRow = sheet.createRow(rowNum++)
        totalEarningsRow.createCell(0).apply {
            setCellValue("Total Earnings:")
            cellStyle = summaryLabelStyle
        }
        totalEarningsRow.createCell(1).apply {
            setCellValue(totalEarningsSum)
            cellStyle = currencyStyle
        }

        rowNum += 2

        val headerRow = sheet.createRow(rowNum++)
        listOf("Date", "Start Time", "End Time", "Hours Worked", "Earnings").forEachIndexed { index, header ->
            headerRow.createCell(index).apply {
                setCellValue(header)
                cellStyle = headerStyle
            }
        }

        val sortedDates = savedShifts.keys.sorted()
        var currentMonth: Month? = null

        sortedDates.forEach { date ->
            if (date.month != currentMonth) {
                currentMonth = date.month
                val monthRow = sheet.createRow(rowNum++)
                monthRow.createCell(0).apply {
                    setCellValue("${date.month} ${date.year}")
                    cellStyle = monthStyle
                }
                sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 4))
            }

            savedShifts[date]?.forEach { shift ->
                val start = LocalTime.parse(shift.startTime, timeFormatter)
                var end = LocalTime.parse(shift.endTime, timeFormatter)
                if (end.isBefore(start)) end = end.plusHours(24)
                val netMinutes = Duration.between(start, end).toMinutes() - shift.breakMinutes
                val hours = netMinutes / 60.0
                val earnings = hours * shift.hourlyRate

                val dataRow = sheet.createRow(rowNum++)
                dataRow.createCell(0).setCellValue(date.format(dateFormatter))
                dataRow.createCell(1).setCellValue(shift.startTime)
                dataRow.createCell(2).setCellValue(shift.endTime)
                dataRow.createCell(3).apply {
                    setCellValue(hours)
                    cellStyle = currencyStyle
                }
                dataRow.createCell(4).apply {
                    setCellValue(earnings)
                    cellStyle = currencyStyle
                }
            }
        }

        sheet.setColumnWidth(0, 4000)
        sheet.setColumnWidth(1, 3500)
        sheet.setColumnWidth(2, 3500)
        sheet.setColumnWidth(3, 3500)
        sheet.setColumnWidth(4, 3500)

        try {
            workbook.write(outputStream)
            Toast.makeText(context, "Excel saved successfully!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving Excel: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            workbook.close()
            outputStream.close()
        }
    }
}
