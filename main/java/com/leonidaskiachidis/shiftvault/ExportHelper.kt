package com.leonidaskiachidis.shiftvault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter

object ExportHelper {

    fun exportShiftsToPdf(context: Context, savedShifts: Map<LocalDate, List<Shift>>) {
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
            // Add Month Divider
            if (date.month != currentMonth) {
                currentMonth = date.month

                // Check if we need a new page before drawing the month header
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
                // Page Overflow Check
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

                // Calculations
                val start = LocalTime.parse(shift.startTime, timeFormatter)
                var end = LocalTime.parse(shift.endTime, timeFormatter)
                if (end.isBefore(start)) end = end.plusHours(24)

                val netMinutes = Duration.between(start, end).toMinutes() - shift.breakMinutes
                val hours = netMinutes / 60.0
                val earnings = hours * shift.hourlyRate

                // Draw Row
                canvas.drawText(date.format(dateFormatter), 50f, yPosition, paint)
                canvas.drawText("${shift.startTime} - ${shift.endTime}", 150f, yPosition, paint)
                canvas.drawText(String.format("%.2f h", hours), 330f, yPosition, paint)
                canvas.drawText(String.format("%.2f", earnings), 450f, yPosition, paint)

                yPosition += 20f
            }
        }

        pdfDocument.finishPage(page)

        // 6. Save the file
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val file = File(directory, "ShiftReport_${System.currentTimeMillis()}.pdf")

        try {
            pdfDocument.writeTo(FileOutputStream(file))
            Toast.makeText(context, "PDF Saved: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error saving PDF", Toast.LENGTH_SHORT).show()
        } finally {
            pdfDocument.close()
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

        // ADD THIS LINE TO RESET:
        paint.isFakeBoldText = false
    }
}