package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  ExportHelper — the PDF and Excel reports.
 *
 *  Every hour and every amount comes from PayEngine, so an export can never
 *  disagree with what the app shows on screen. That includes day type,
 *  paid/unpaid breaks, rate multipliers and flat bonuses.
 *
 *  Both reports are laid out rather than dumped:
 *
 *   - The PDF paginates up front, so it can print "Page 2 of 5", repeat the
 *     table header on every page, and never split a month away from its first
 *     row. It draws in Manrope and the app's own palette, tinted by the Job
 *     Profile the report belongs to.
 *   - The workbook is a spreadsheet someone can work in: real date cells, real
 *     currency formats, a frozen filtered header, and a totals row built on
 *     SUBTOTAL so it follows whatever the reader filters down to.
 *
 *  Neither function touches the UI or closes the stream it was handed — both
 *  are safe to call off the main thread, which matters because POI takes a
 *  noticeable moment to spin up.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xddf.usermodel.chart.AxisPosition
import org.apache.poi.xddf.usermodel.chart.BarDirection
import org.apache.poi.xddf.usermodel.chart.ChartTypes
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Who a report is for, and in what currency it is written. */
data class ReportMeta(
    val profileName: String,
    val profileColorValue: Long,
    val currencyCode: String
) {
    val currencySymbol: String
        get() = try {
            java.util.Currency.getInstance(currencyCode).getSymbol(Locale.getDefault())
        } catch (_: Exception) {
            currencyCode
        }
}

object ExportHelper {

    // ══════════════════════════════════════════════════════════
    //  Shared vocabulary
    // ══════════════════════════════════════════════════════════

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    private val monthShortFmt = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
    private val monthYearShortFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    private val fullDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    private fun typeLabel(shift: Shift): String = when (shift.dayType) {
        DayType.WORKED -> "Worked"
        DayType.PAID_LEAVE -> "Paid leave"
        DayType.UNPAID_LEAVE -> "Unpaid leave"
    }

    /** What to show in a single "detail" column: clock times, or the leave type. */
    private fun detailLabel(shift: Shift): String = when {
        shift.dayType != DayType.WORKED -> typeLabel(shift)
        shift.isAllDaySpan -> "All day"
        else -> "${shift.startTime} – ${shift.endTime}"
    }

    private fun multiplierLabel(shift: Shift): String {
        val m = PayEngine.effectiveMultiplier(shift)
        return if (m == 1.0) "–" else "×" + "%.2f".format(m).trimEnd('0').trimEnd('.')
    }

    private fun num(value: Double, decimals: Int = 2): String =
        String.format(Locale.getDefault(), "%,.${decimals}f", value)

    /** A flat, chronological view of the map — the order both reports read in. */
    private fun entriesOf(savedShifts: Map<LocalDate, List<Shift>>): List<Pair<LocalDate, Shift>> =
        savedShifts.keys.sorted().flatMap { date ->
            savedShifts[date].orEmpty().map { date to it.migrated() }
        }

    /** "Jan 2025 – Sep 2026", or the single month when that is all there is. */
    private fun rangeLabel(entries: List<Pair<LocalDate, Shift>>): String {
        if (entries.isEmpty()) return "No shifts recorded"
        val first = entries.first().first
        val last = entries.last().first
        val a = YearMonth.from(first)
        val b = YearMonth.from(last)
        return if (a == b) first.format(monthYearShortFmt)
        else "${first.format(monthYearShortFmt)} – ${last.format(monthYearShortFmt)}"
    }

    /**
     * A file name that says what the file is once it is sitting in a downloads
     * folder next to thirty others.
     */
    fun suggestFileName(profileName: String, extension: String): String {
        val safe = profileName.trim()
            // Any script's letters and digits are kept — "Καφετέρια" must not
            // collapse to an empty name. Only characters that are unsafe or
            // awkward in file names are dropped.
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .replace(' ', '-')
            .take(28)
            .ifBlank { "Report" }
        return "ShiftVault-$safe-${LocalDate.now()}.$extension"
    }

    private class Totals(val entries: List<Pair<LocalDate, Shift>>) {
        val shiftsWorked = entries.count { PayEngine.isWorked(it.second) }
        val hours = entries.sumOf { PayEngine.payableHours(it.second) }
        val earnings = entries.sumOf { PayEngine.earnings(it.second) }
        val bonuses = entries.sumOf {
            if (it.second.dayType == DayType.UNPAID_LEAVE) 0.0 else it.second.bonus
        }
        val averageRate = if (hours > 0.0) earnings / hours else 0.0
    }

    // ══════════════════════════════════════════════════════════
    //  PDF
    // ══════════════════════════════════════════════════════════

    private const val PAGE_W = 595f          // A4 at 72dpi
    private const val PAGE_H = 842f
    private const val MARGIN = 44f
    private const val RIGHT_EDGE = PAGE_W - MARGIN
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val BODY_BOTTOM = PAGE_H - 58f

    private const val TABLE_HEAD_H = 26f
    private const val MONTH_H = 24f
    private const val ENTRY_H = 19f
    private const val SUBTOTAL_H = 22f
    private const val GRAND_H = 38f

    private const val INK = 0xFF262433.toInt()
    private const val MUTED = 0xFF8B8794.toInt()
    private const val OUTLINE = 0xFFE6E1D8.toInt()
    private const val ZEBRA = 0xFFFAF8F4.toInt()
    private const val CARD = 0xFFF7F4EF.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** A column in the shift table. Widths sum to exactly [CONTENT_W]. */
    private class Col(val title: String, val x: Float, val w: Float, val right: Boolean) {
        /** Where text starts (left columns) or ends (right-aligned columns). */
        val textX: Float get() = if (right) x + w - 6f else x + 6f
    }

    private val columns: List<Col> = buildList {
        var x = MARGIN
        fun col(title: String, w: Float, right: Boolean = false) {
            add(Col(title, x, w, right)); x += w
        }
        col("Date", 52f)
        col("Shift", 116f)
        col("Time / type", 90f)
        col("Hours", 46f, right = true)
        col("Rate", 52f, right = true)
        col("Mult.", 36f, right = true)
        col("Bonus", 48f, right = true)
        col("Earnings", 67f, right = true)
    }

    /** One laid-out band of the table. Heights are known before anything is drawn. */
    private sealed class Band {
        abstract val height: Float

        class Month(val label: String, val hours: Double, val earnings: Double) : Band() {
            override val height = MONTH_H
        }

        class Entry(val date: LocalDate, val shift: Shift, val zebra: Boolean) : Band() {
            override val height = ENTRY_H
        }

        class Subtotal(val hours: Double, val earnings: Double) : Band() {
            override val height = SUBTOTAL_H
        }

        class Grand(val shifts: Int, val hours: Double, val earnings: Double) : Band() {
            override val height = GRAND_H
        }
    }

    private class Fonts(context: Context) {
        private val base: Typeface =
            ResourcesCompat.getFont(context, R.font.manrope_variablefont) ?: Typeface.SANS_SERIF

        fun of(size: Float, weight: Int, color: Int, align: Paint.Align = Paint.Align.LEFT): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
                p.typeface = base
                p.textSize = size
                p.color = color
                p.textAlign = align
                // Manrope ships as one variable file, so weight is an axis and
                // not a separate face. Synthetic bold keeps the hierarchy
                // readable on the rare device that ignores the axis.
                if (!p.setFontVariationSettings("'wght' $weight")) {
                    p.isFakeBoldText = weight >= 700
                }
            }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float = 1f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
        }

    /** Trims to fit, with an ellipsis, so a long shift name can't run into the next column. */
    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1) {
            end--
            val candidate = text.substring(0, end).trimEnd() + "…"
            if (paint.measureText(candidate) <= maxWidth) return candidate
        }
        return "…"
    }

    /** Shrinks rather than clips — a big total should stay readable, not become "12,3…". */
    private fun drawFitted(
        canvas: Canvas,
        text: String,
        paint: Paint,
        x: Float,
        baseline: Float,
        maxWidth: Float
    ) {
        val original = paint.textSize
        while (paint.textSize > 8f && paint.measureText(text) > maxWidth) {
            paint.textSize = paint.textSize - 0.5f
        }
        canvas.drawText(text, x, baseline, paint)
        paint.textSize = original
    }

    fun exportShiftsToPdf(
        context: Context,
        savedShifts: Map<LocalDate, List<Shift>>,
        outputStream: OutputStream,
        meta: ReportMeta
    ) {
        val f = Fonts(context)
        val entries = entriesOf(savedShifts)
        val totals = Totals(entries)
        val symbol = meta.currencySymbol

        val accent = ShiftColors
            .accent(androidx.compose.ui.graphics.Color(meta.profileColorValue.toInt()), false)
            .toArgb()
        val accentInk = ShiftColors
            .ink(androidx.compose.ui.graphics.Color(meta.profileColorValue.toInt()), false)
            .toArgb()
        val accentWash = (accent and 0x00FFFFFF) or 0x1A000000

        // ── Paints ──
        val pTitle = f.of(23f, 800, INK)
        val pMeta = f.of(9f, 500, MUTED, Paint.Align.RIGHT)
        val pProfile = f.of(10.5f, 700, INK)
        val pSub = f.of(10.5f, 500, MUTED)
        val pKpiLabel = f.of(7.5f, 700, MUTED)
        val pKpiValue = f.of(16f, 800, INK)
        val pSection = f.of(10.5f, 700, INK)
        val pTh = f.of(8f, 700, WHITE)
        val pThRight = f.of(8f, 700, WHITE, Paint.Align.RIGHT)
        val pCell = f.of(8.5f, 500, INK)
        val pCellMuted = f.of(8.5f, 500, MUTED)
        val pCellRight = f.of(8.5f, 600, INK, Paint.Align.RIGHT)
        val pCellRightMuted = f.of(8.5f, 500, MUTED, Paint.Align.RIGHT)
        val pMonth = f.of(9.5f, 700, accentInk)
        val pMonthRight = f.of(8.5f, 600, MUTED, Paint.Align.RIGHT)
        val pTotalLabel = f.of(8.5f, 700, INK)
        val pTotalRight = f.of(8.5f, 700, INK, Paint.Align.RIGHT)
        val pGrand = f.of(10f, 800, WHITE)
        val pGrandRight = f.of(10f, 800, WHITE, Paint.Align.RIGHT)
        val pFooter = f.of(7.5f, 500, MUTED)
        val pFooterRight = f.of(7.5f, 500, MUTED, Paint.Align.RIGHT)
        val pBarLabel = f.of(7f, 600, MUTED, Paint.Align.CENTER)
        val pBarValue = f.of(7f, 700, INK, Paint.Align.CENTER)
        val pEmpty = f.of(11f, 500, MUTED, Paint.Align.CENTER)

        val fillCard = fill(CARD)
        val fillInk = fill(INK)
        val fillZebra = fill(ZEBRA)
        val fillAccent = fill(accent)
        val fillWash = fill(accentWash)
        val lineOutline = stroke(OUTLINE)

        // ── Monthly roll-up, used by both the chart and the table bands ──
        val byMonth: List<Pair<YearMonth, List<Pair<LocalDate, Shift>>>> =
            entries.groupBy { YearMonth.from(it.first) }.toList().sortedBy { it.first }

        val showChart = byMonth.size in 2..18

        // ── Intro block geometry (page 1 only) ──
        val kpiTop = 124f
        val kpiH = 58f
        val chartTop = kpiTop + kpiH + 32f          // 214
        val chartH = 96f
        val introBottom = if (showChart) chartTop + chartH else kpiTop + kpiH
        val firstPageTableTop = introBottom + 24f

        // ── Build the bands ──
        val bands = mutableListOf<Band>()
        var zebra = false
        byMonth.forEach { (month, monthEntries) ->
            val mHours = monthEntries.sumOf { PayEngine.payableHours(it.second) }
            val mEarnings = monthEntries.sumOf { PayEngine.earnings(it.second) }
            bands += Band.Month(month.atDay(1).format(monthFmt), mHours, mEarnings)
            zebra = false
            monthEntries.forEach { (date, shift) ->
                bands += Band.Entry(date, shift, zebra)
                zebra = !zebra
            }
            bands += Band.Subtotal(mHours, mEarnings)
        }
        if (entries.isNotEmpty()) {
            bands += Band.Grand(totals.shiftsWorked, totals.hours, totals.earnings)
        }

        // ── Paginate before drawing, so "Page 2 of 5" can be truthful and a
        //    month heading can never be stranded at the foot of a page ──
        val pages = mutableListOf<List<Band>>()
        var current = mutableListOf<Band>()
        var y = firstPageTableTop + TABLE_HEAD_H
        bands.forEach { band ->
            val needed = band.height + if (band is Band.Month) ENTRY_H else 0f
            if (y + needed > BODY_BOTTOM && current.isNotEmpty()) {
                pages += current
                current = mutableListOf()
                y = MARGIN + TABLE_HEAD_H
            }
            current += band
            y += band.height
        }
        pages += current

        val document = PdfDocument()
        try {
            pages.forEachIndexed { pageIndex, pageBands ->
                val info = PdfDocument.PageInfo
                    .Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageIndex + 1)
                    .create()
                val page = document.startPage(info)
                val canvas = page.canvas

                var top = MARGIN

                if (pageIndex == 0) {
                    // Accent bar — the report is colour-coded by job, the same
                    // way the app is.
                    canvas.drawRect(0f, 0f, PAGE_W, 5f, fillAccent)

                    canvas.drawText("Earnings Report", MARGIN, 72f, pTitle)
                    canvas.drawText(
                        "Generated ${LocalDate.now().format(fullDateFmt)}",
                        RIGHT_EDGE, 72f, pMeta
                    )

                    canvas.drawCircle(MARGIN + 3.5f, 88.5f, 3.5f, fillAccent)
                    canvas.drawText(meta.profileName, MARGIN + 14f, 92f, pProfile)
                    val nameW = pProfile.measureText(meta.profileName)
                    canvas.drawText(
                        " · ${rangeLabel(entries)}",
                        MARGIN + 14f + nameW, 92f, pSub
                    )

                    canvas.drawLine(MARGIN, 108f, RIGHT_EDGE, 108f, lineOutline)

                    drawKpiCards(
                        canvas, kpiTop, kpiH, totals, symbol,
                        fillCard, lineOutline, pKpiLabel, pKpiValue
                    )

                    if (showChart) {
                        canvas.drawText("Paid hours by month", MARGIN, chartTop - 12f, pSection)
                        drawMonthChart(
                            canvas, chartTop, chartH, byMonth,
                            fillAccent, lineOutline, pBarLabel, pBarValue
                        )
                    }

                    if (entries.isEmpty()) {
                        canvas.drawText(
                            "No shifts recorded for ${meta.profileName} yet.",
                            PAGE_W / 2f, firstPageTableTop + 60f, pEmpty
                        )
                    }

                    top = firstPageTableTop
                }

                if (entries.isNotEmpty()) {
                    drawTableHead(canvas, top, fillInk, pTh, pThRight)
                    var rowY = top + TABLE_HEAD_H

                    pageBands.forEach { band ->
                        when (band) {
                            is Band.Month -> {
                                canvas.drawRoundRect(
                                    RectF(MARGIN, rowY, RIGHT_EDGE, rowY + 20f), 4f, 4f, fillWash
                                )
                                canvas.drawText(band.label, MARGIN + 8f, rowY + 13.5f, pMonth)
                                canvas.drawText(
                                    "${num(band.hours)} h  ·  $symbol${num(band.earnings)}",
                                    RIGHT_EDGE - 8f, rowY + 13.5f, pMonthRight
                                )
                            }

                            is Band.Entry -> {
                                if (band.zebra) {
                                    canvas.drawRect(
                                        MARGIN, rowY, RIGHT_EDGE, rowY + ENTRY_H, fillZebra
                                    )
                                }
                                drawEntry(canvas, rowY, band, pCell, pCellMuted, pCellRight, pCellRightMuted, symbol)
                            }

                            is Band.Subtotal -> {
                                canvas.drawLine(MARGIN, rowY + 1f, RIGHT_EDGE, rowY + 1f, lineOutline)
                                canvas.drawText("Month total", columns[0].textX, rowY + 15f, pTotalLabel)
                                canvas.drawText(
                                    num(band.hours), columns[3].textX, rowY + 15f, pTotalRight
                                )
                                canvas.drawText(
                                    "$symbol${num(band.earnings)}",
                                    columns[7].textX, rowY + 15f, pTotalRight
                                )
                            }

                            is Band.Grand -> {
                                canvas.drawRoundRect(
                                    RectF(MARGIN, rowY + 6f, RIGHT_EDGE, rowY + 34f), 6f, 6f, fillInk
                                )
                                canvas.drawText(
                                    "TOTAL · ${band.shifts} shift${if (band.shifts == 1) "" else "s"} worked",
                                    MARGIN + 10f, rowY + 24f, pGrand
                                )
                                canvas.drawText(
                                    "${num(band.hours)} h      $symbol${num(band.earnings)}",
                                    RIGHT_EDGE - 10f, rowY + 24f, pGrandRight
                                )
                            }
                        }
                        rowY += band.height
                    }
                }

                // ── Footer ──
                canvas.drawLine(MARGIN, PAGE_H - 40f, RIGHT_EDGE, PAGE_H - 40f, lineOutline)
                canvas.drawText(
                    "ShiftVault · ${meta.profileName}", MARGIN, PAGE_H - 26f, pFooter
                )
                canvas.drawText(
                    "Page ${pageIndex + 1} of ${pages.size}", RIGHT_EDGE, PAGE_H - 26f, pFooterRight
                )

                document.finishPage(page)
            }

            document.writeTo(outputStream)
        } finally {
            document.close()
        }
    }

    private fun drawKpiCards(
        canvas: Canvas,
        top: Float,
        height: Float,
        totals: Totals,
        symbol: String,
        fillCard: Paint,
        outline: Paint,
        label: Paint,
        value: Paint
    ) {
        val cards = listOf(
            "SHIFTS WORKED" to totals.shiftsWorked.toString(),
            "PAID HOURS" to "${num(totals.hours, 1)} h",
            "AVG RATE" to "$symbol${num(totals.averageRate)}",
            "TOTAL EARNED" to "$symbol${num(totals.earnings)}"
        )
        val gap = 10f
        val w = (CONTENT_W - gap * (cards.size - 1)) / cards.size

        cards.forEachIndexed { index, (title, text) ->
            val x = MARGIN + index * (w + gap)
            val rect = RectF(x, top, x + w, top + height)
            canvas.drawRoundRect(rect, 10f, 10f, fillCard)
            canvas.drawRoundRect(rect, 10f, 10f, outline)
            canvas.drawText(title, x + 13f, top + 20f, label)
            drawFitted(canvas, text, value, x + 13f, top + 44f, w - 26f)
        }
    }

    private fun drawMonthChart(
        canvas: Canvas,
        top: Float,
        height: Float,
        byMonth: List<Pair<YearMonth, List<Pair<LocalDate, Shift>>>>,
        bar: Paint,
        outline: Paint,
        label: Paint,
        valueLabel: Paint
    ) {
        val baseline = top + height - 14f
        val plotH = baseline - top - 12f
        val hours = byMonth.map { (_, list) -> list.sumOf { PayEngine.payableHours(it.second) } }
        val max = hours.maxOrNull() ?: 0.0
        if (max <= 0.0) return

        val slot = CONTENT_W / byMonth.size
        val barW = minOf(30f, slot * 0.52f)
        val showValues = byMonth.size <= 12

        byMonth.forEachIndexed { index, (month, _) ->
            val centre = MARGIN + slot * index + slot / 2f
            val h = (hours[index] / max * plotH).toFloat()
            if (h > 0.5f) {
                canvas.drawRoundRect(
                    RectF(centre - barW / 2f, baseline - h, centre + barW / 2f, baseline),
                    3f, 3f, bar
                )
            }
            canvas.drawText(month.atDay(1).format(monthShortFmt), centre, baseline + 11f, label)
            if (showValues) {
                canvas.drawText(num(hours[index], 0), centre, baseline - h - 4f, valueLabel)
            }
        }

        canvas.drawLine(MARGIN, baseline, RIGHT_EDGE, baseline, outline)
    }

    private fun drawTableHead(canvas: Canvas, top: Float, fillInk: Paint, th: Paint, thRight: Paint) {
        canvas.drawRoundRect(RectF(MARGIN, top, RIGHT_EDGE, top + 22f), 5f, 5f, fillInk)
        columns.forEach { col ->
            canvas.drawText(col.title, col.textX, top + 14.5f, if (col.right) thRight else th)
        }
    }

    private fun drawEntry(
        canvas: Canvas,
        top: Float,
        band: Band.Entry,
        cell: Paint,
        cellMuted: Paint,
        right: Paint,
        rightMuted: Paint,
        symbol: String
    ) {
        val shift = band.shift
        val baseline = top + 12.8f
        val hours = PayEngine.payableHours(shift)
        val earnings = PayEngine.earnings(shift)

        // The month band above already says which month, so the date column
        // only has to disambiguate the day.
        canvas.drawText(band.date.format(dayFmt), columns[0].textX, baseline, cellMuted)

        val nameCol = columns[1]
        val dot = ShiftColors
            .accent(androidx.compose.ui.graphics.Color(shift.colorValue.toInt()), false)
            .toArgb()
        canvas.drawCircle(nameCol.x + 8f, baseline - 3f, 3f, fill(dot))
        canvas.drawText(
            fit(shift.name, cell, nameCol.w - 24f),
            nameCol.x + 16f, baseline, cell
        )

        canvas.drawText(
            fit(detailLabel(shift), cellMuted, columns[2].w - 10f),
            columns[2].textX, baseline, cellMuted
        )
        canvas.drawText(num(hours), columns[3].textX, baseline, right)
        canvas.drawText(
            if (shift.hourlyRate > 0.0) "$symbol${num(shift.hourlyRate)}" else "–",
            columns[4].textX, baseline, rightMuted
        )
        canvas.drawText(multiplierLabel(shift), columns[5].textX, baseline, rightMuted)
        canvas.drawText(
            if (shift.bonus > 0.0 && shift.dayType != DayType.UNPAID_LEAVE)
                "$symbol${num(shift.bonus)}" else "–",
            columns[6].textX, baseline, rightMuted
        )
        canvas.drawText("$symbol${num(earnings)}", columns[7].textX, baseline, right)
    }

    // ══════════════════════════════════════════════════════════
    //  Excel
    // ══════════════════════════════════════════════════════════

    private const val XL_INK = 0x262433
    private const val XL_MUTED = 0x8B8794
    private const val XL_OUTLINE = 0xE6E1D8
    private const val XL_ZEBRA = 0xFAF8F4
    private const val XL_CARD = 0xF7F4EF
    private const val XL_WHITE = 0xFFFFFF

    private fun rgb(hex: Int): XSSFColor = XSSFColor(
        byteArrayOf(
            ((hex shr 16) and 0xFF).toByte(),
            ((hex shr 8) and 0xFF).toByte(),
            (hex and 0xFF).toByte()
        ),
        null
    )

    /**
     * Builds and caches the cell styles. A workbook has a hard ceiling on
     * styles, and the obvious "make one per cell" approach walks straight into
     * it on a long report.
     */
    private class Styles(private val wb: XSSFWorkbook, private val symbol: String) {
        val money: String = "\"$symbol\"#,##0.00"
        val hours = "0.00\"h\""
        val multiplier = "\"×\"0.00"
        val date = "d mmm yyyy"
        val whole = "0"
        val percent = "0.0%"

        private val cache = HashMap<String, XSSFCellStyle>()

        fun body(
            format: String? = null,
            alt: Boolean = false,
            weight: Int = 400,
            color: Int = XL_INK,
            align: HorizontalAlignment = HorizontalAlignment.LEFT,
            size: Int = 10,
            fillColor: Int? = null,
            topBorder: Boolean = false
        ): XSSFCellStyle = cache.getOrPut("$format|$alt|$weight|$color|$align|$size|$fillColor|$topBorder") {
            wb.createCellStyle().apply {
                setFont(wb.createFont().apply {
                    bold = weight >= 700
                    fontHeightInPoints = size.toShort()
                    fontName = "Calibri"
                    setColor(rgb(color))
                })
                if (format != null) dataFormat = wb.createDataFormat().getFormat(format)
                alignment = align
                verticalAlignment = VerticalAlignment.CENTER
                val bg = fillColor ?: if (alt) XL_ZEBRA else null
                if (bg != null) {
                    setFillForegroundColor(rgb(bg))
                    fillPattern = FillPatternType.SOLID_FOREGROUND
                }
                setBorderBottom(BorderStyle.THIN)
                setBottomBorderColor(rgb(XL_OUTLINE))
                if (topBorder) {
                    setBorderTop(BorderStyle.THIN)
                    setTopBorderColor(rgb(XL_INK))
                }
            }
        }

        fun plain(weight: Int, size: Int, color: Int): XSSFCellStyle =
            cache.getOrPut("plain|$weight|$size|$color") {
                wb.createCellStyle().apply {
                    setFont(wb.createFont().apply {
                        bold = weight >= 700
                        fontHeightInPoints = size.toShort()
                        fontName = "Calibri"
                        setColor(rgb(color))
                    })
                    verticalAlignment = VerticalAlignment.CENTER
                }
            }

        fun header(accent: Int): XSSFCellStyle = cache.getOrPut("header|$accent") {
            wb.createCellStyle().apply {
                setFont(wb.createFont().apply {
                    bold = true
                    fontHeightInPoints = 10
                    fontName = "Calibri"
                    setColor(rgb(XL_WHITE))
                })
                setFillForegroundColor(rgb(accent))
                fillPattern = FillPatternType.SOLID_FOREGROUND
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
            }
        }
    }

    fun exportShiftsToExcel(
        savedShifts: Map<LocalDate, List<Shift>>,
        outputStream: OutputStream,
        meta: ReportMeta
    ) {
        val workbook = XSSFWorkbook()
        try {
            val entries = entriesOf(savedShifts)
            val totals = Totals(entries)
            val styles = Styles(workbook, meta.currencySymbol)
            val accent = ShiftColors
                .accent(androidx.compose.ui.graphics.Color(meta.profileColorValue.toInt()), false)
                .toArgb() and 0xFFFFFF

            val shiftsSheet = workbook.createSheet("Shifts")
            val dataRange = writeShiftsSheet(shiftsSheet, entries, styles, accent, meta)

            val summary = workbook.createSheet("Summary")
            writeSummarySheet(summary, entries, totals, styles, accent, meta)

            // Summary reads better as the first thing the file opens on.
            workbook.setSheetOrder("Summary", 0)
            workbook.setActiveSheet(0)

            // Charts are the one piece of the OOXML surface that can misbehave
            // on a device; a failure here must not cost the user the report.
            if (dataRange != null) {
                runCatching { addMonthlyChart(summary, entries, meta) }
            }

            workbook.write(outputStream)
        } finally {
            workbook.close()
        }
    }

    /** @return the first and last data row of the table, or null when empty. */
    private fun writeShiftsSheet(
        sheet: XSSFSheet,
        entries: List<Pair<LocalDate, Shift>>,
        s: Styles,
        accent: Int,
        meta: ReportMeta
    ): Pair<Int, Int>? {
        val columns = listOf(
            "Date" to 3000, "Day" to 1800, "Shift" to 5200, "Type" to 3000,
            "Start" to 2000, "End" to 2000, "Break" to 2000, "Paid break" to 2600,
            "Hours" to 2400, "Rate" to 2600, "Multiplier" to 2600, "Bonus" to 2600,
            "Earnings" to 3000, "Location" to 4600, "Note" to 7000
        )
        columns.forEachIndexed { i, (_, w) -> sheet.setColumnWidth(i, w) }

        var r = 0
        sheet.createRow(r++).createCell(0).apply {
            setCellValue("Earnings Report")
            cellStyle = s.plain(800, 16, XL_INK)
        }
        sheet.createRow(r++).createCell(0).apply {
            setCellValue(
                "${meta.profileName} · ${rangeLabel(entries)} · " +
                        "generated ${LocalDate.now().format(fullDateFmt)}"
            )
            cellStyle = s.plain(400, 10, XL_MUTED)
        }
        r++

        val headerRow = r
        val hr = sheet.createRow(r++)
        hr.heightInPoints = 20f
        columns.forEachIndexed { i, (title, _) ->
            hr.createCell(i).apply {
                setCellValue(title)
                cellStyle = s.header(accent)
            }
        }

        if (entries.isEmpty()) {
            sheet.createRow(r).createCell(0).apply {
                setCellValue("No shifts recorded for this job profile.")
                cellStyle = s.plain(400, 10, XL_MUTED)
            }
            sheet.createFreezePane(3, headerRow + 1)
            return null
        }

        val firstData = r
        entries.forEachIndexed { index, (date, shift) ->
            val alt = index % 2 == 1
            val worked = shift.dayType == DayType.WORKED
            val row = sheet.createRow(r++)

            row.createCell(0).apply {
                setCellValue(date)                       // a real date, so it sorts
                cellStyle = s.body(s.date, alt)
            }
            row.createCell(1).apply {
                setCellValue(
                    date.dayOfWeek.getDisplayName(
                        java.time.format.TextStyle.SHORT, Locale.getDefault()
                    )
                )
                cellStyle = s.body(alt = alt, color = XL_MUTED)
            }
            row.createCell(2).apply {
                setCellValue(shift.name)
                cellStyle = s.body(alt = alt, weight = 700)
            }
            row.createCell(3).apply {
                setCellValue(typeLabel(shift))
                cellStyle = s.body(alt = alt, color = XL_MUTED)
            }
            row.createCell(4).apply {
                setCellValue(if (worked) shift.startTime else "–")
                cellStyle = s.body(alt = alt, align = HorizontalAlignment.CENTER)
            }
            row.createCell(5).apply {
                setCellValue(if (worked) shift.endTime else "–")
                cellStyle = s.body(alt = alt, align = HorizontalAlignment.CENTER)
            }
            row.createCell(6).apply {
                setCellValue(if (worked) shift.breakMinutes.toDouble() else 0.0)
                cellStyle = s.body(s.whole, alt, align = HorizontalAlignment.RIGHT, color = XL_MUTED)
            }
            row.createCell(7).apply {
                setCellValue(
                    when {
                        !worked || shift.breakMinutes == 0 -> "–"
                        shift.paidBreak -> "Paid"
                        else -> "Unpaid"
                    }
                )
                cellStyle = s.body(alt = alt, align = HorizontalAlignment.CENTER, color = XL_MUTED)
            }
            row.createCell(8).apply {
                setCellValue(PayEngine.payableHours(shift))
                cellStyle = s.body(s.hours, alt, align = HorizontalAlignment.RIGHT)
            }
            row.createCell(9).apply {
                setCellValue(shift.hourlyRate)
                cellStyle = s.body(s.money, alt, align = HorizontalAlignment.RIGHT, color = XL_MUTED)
            }
            row.createCell(10).apply {
                setCellValue(PayEngine.effectiveMultiplier(shift))
                cellStyle = s.body(s.multiplier, alt, align = HorizontalAlignment.RIGHT, color = XL_MUTED)
            }
            row.createCell(11).apply {
                setCellValue(if (shift.dayType == DayType.UNPAID_LEAVE) 0.0 else shift.bonus)
                cellStyle = s.body(s.money, alt, align = HorizontalAlignment.RIGHT, color = XL_MUTED)
            }
            row.createCell(12).apply {
                setCellValue(PayEngine.earnings(shift))
                cellStyle = s.body(s.money, alt, weight = 700, align = HorizontalAlignment.RIGHT)
            }
            row.createCell(13).apply {
                setCellValue(shift.location)
                cellStyle = s.body(alt = alt, color = XL_MUTED)
            }
            row.createCell(14).apply {
                setCellValue(shift.notes)
                cellStyle = s.body(alt = alt, color = XL_MUTED)
            }
        }
        val lastData = r - 1

        // Totals built on SUBTOTAL rather than SUM: filter the table down to one
        // shift name or one month and the totals follow the filter.
        val totalRow = sheet.createRow(r)
        totalRow.heightInPoints = 18f
        totalRow.createCell(0).apply {
            setCellValue("TOTAL")
            cellStyle = s.body(weight = 700, fillColor = XL_CARD, topBorder = true)
        }
        listOf(1, 2, 3, 4, 5, 6, 7, 13, 14).forEach { c ->
            totalRow.createCell(c).cellStyle = s.body(fillColor = XL_CARD, topBorder = true)
        }
        fun subtotal(col: Int, format: String, weight: Int = 700) {
            val letter = ('A' + col).toString()
            totalRow.createCell(col).apply {
                cellFormula = "SUBTOTAL(109,$letter${firstData + 1}:$letter${lastData + 1})"
                cellStyle = s.body(
                    format, weight = weight,
                    align = HorizontalAlignment.RIGHT, fillColor = XL_CARD, topBorder = true
                )
            }
        }
        subtotal(8, s.hours)
        totalRow.createCell(9).cellStyle = s.body(fillColor = XL_CARD, topBorder = true)
        totalRow.createCell(10).cellStyle = s.body(fillColor = XL_CARD, topBorder = true)
        subtotal(11, s.money, weight = 400)
        subtotal(12, s.money)

        sheet.createFreezePane(3, headerRow + 1)
        sheet.setAutoFilter(CellRangeAddress(headerRow, lastData, 0, columns.size - 1))

        // Data bars turn the hours column into a glanceable profile of the month.
        runCatching {
            val cf = sheet.sheetConditionalFormatting
            val rule = cf.createConditionalFormattingRule(rgb(accent))
            rule.dataBarFormatting?.isIconOnly = false
            cf.addConditionalFormatting(
                arrayOf(CellRangeAddress(firstData, lastData, 8, 8)), rule
            )
        }

        sheet.printSetup.landscape = true
        sheet.fitToPage = true
        sheet.printSetup.fitWidth = 1
        sheet.printSetup.fitHeight = 0
        sheet.repeatingRows = CellRangeAddress(headerRow, headerRow, -1, -1)
        sheet.footer.right = "Page &P of &N"
        sheet.footer.left = "ShiftVault · ${meta.profileName}"

        return firstData to lastData
    }

    private fun writeSummarySheet(
        sheet: XSSFSheet,
        entries: List<Pair<LocalDate, Shift>>,
        totals: Totals,
        s: Styles,
        accent: Int,
        meta: ReportMeta
    ) {
        listOf(5200, 3200, 3200, 3200, 3400).forEachIndexed { i, w -> sheet.setColumnWidth(i, w) }

        var r = 0
        sheet.createRow(r++).apply { heightInPoints = 24f }.createCell(0).apply {
            setCellValue("Earnings Report")
            cellStyle = s.plain(800, 18, XL_INK)
        }
        sheet.createRow(r++).createCell(0).apply {
            setCellValue("${meta.profileName} · ${rangeLabel(entries)}")
            cellStyle = s.plain(700, 11, accent)
        }
        sheet.createRow(r++).createCell(0).apply {
            setCellValue("Generated ${LocalDate.now().format(fullDateFmt)}")
            cellStyle = s.plain(400, 10, XL_MUTED)
        }
        r++

        fun section(title: String) {
            sheet.createRow(r++).createCell(0).apply {
                setCellValue(title)
                cellStyle = s.plain(700, 11, XL_INK)
            }
        }

        section("AT A GLANCE")
        fun kpi(label: String, value: Double, format: String?, emphasise: Boolean = false) {
            val row = sheet.createRow(r++)
            row.heightInPoints = 18f
            row.createCell(0).apply {
                setCellValue(label)
                cellStyle = s.body(fillColor = if (emphasise) XL_CARD else null)
            }
            row.createCell(1).apply {
                setCellValue(value)
                cellStyle = s.body(
                    format,
                    weight = if (emphasise) 700 else 400,
                    align = HorizontalAlignment.RIGHT,
                    fillColor = if (emphasise) XL_CARD else null
                )
            }
        }
        kpi("Shifts worked", totals.shiftsWorked.toDouble(), s.whole)
        kpi("Paid hours", totals.hours, s.hours)
        kpi("Average effective rate", totals.averageRate, s.money)
        kpi("Bonuses", totals.bonuses, s.money)
        kpi("Total earnings", totals.earnings, s.money, emphasise = true)
        r++

        // ── By month ──
        section("BY MONTH")
        val monthHeader = sheet.createRow(r++)
        monthHeader.heightInPoints = 18f
        listOf("Month", "Shifts", "Hours", "Bonuses", "Earnings").forEachIndexed { i, title ->
            monthHeader.createCell(i).apply {
                setCellValue(title)
                cellStyle = s.header(accent)
            }
        }

        val byMonth = entries.groupBy { YearMonth.from(it.first) }.toList().sortedBy { it.first }
        val monthFirst = r
        byMonth.forEachIndexed { index, (month, list) ->
            val alt = index % 2 == 1
            val row = sheet.createRow(r++)
            row.createCell(0).apply {
                setCellValue(month.atDay(1).format(monthFmt))
                cellStyle = s.body(alt = alt, weight = 700)
            }
            row.createCell(1).apply {
                setCellValue(list.count { PayEngine.isWorked(it.second) }.toDouble())
                cellStyle = s.body(s.whole, alt, align = HorizontalAlignment.RIGHT)
            }
            row.createCell(2).apply {
                setCellValue(list.sumOf { PayEngine.payableHours(it.second) })
                cellStyle = s.body(s.hours, alt, align = HorizontalAlignment.RIGHT)
            }
            row.createCell(3).apply {
                setCellValue(
                    list.sumOf {
                        if (it.second.dayType == DayType.UNPAID_LEAVE) 0.0 else it.second.bonus
                    }
                )
                cellStyle = s.body(s.money, alt, align = HorizontalAlignment.RIGHT, color = XL_MUTED)
            }
            row.createCell(4).apply {
                setCellValue(list.sumOf { PayEngine.earnings(it.second) })
                cellStyle = s.body(s.money, alt, weight = 700, align = HorizontalAlignment.RIGHT)
            }
        }
        if (byMonth.isNotEmpty()) {
            val monthLast = r - 1
            val row = sheet.createRow(r++)
            row.createCell(0).apply {
                setCellValue("Total")
                cellStyle = s.body(weight = 700, fillColor = XL_CARD, topBorder = true)
            }
            listOf(1 to s.whole, 2 to s.hours, 3 to s.money, 4 to s.money).forEach { (col, fmt) ->
                val letter = ('A' + col).toString()
                row.createCell(col).apply {
                    cellFormula = "SUM($letter${monthFirst + 1}:$letter${monthLast + 1})"
                    cellStyle = s.body(
                        fmt, weight = 700,
                        align = HorizontalAlignment.RIGHT, fillColor = XL_CARD, topBorder = true
                    )
                }
            }
        }
        r++

        // ── By shift ──
        section("BY SHIFT")
        val shiftHeader = sheet.createRow(r++)
        shiftHeader.heightInPoints = 18f
        listOf("Shift", "Times worked", "Hours", "Earnings", "Share").forEachIndexed { i, title ->
            shiftHeader.createCell(i).apply {
                setCellValue(title)
                cellStyle = s.header(accent)
            }
        }

        entries.groupBy { it.second.name }
            .map { (name, list) -> Triple(name, list, list.sumOf { PayEngine.earnings(it.second) }) }
            .sortedByDescending { it.third }
            .forEachIndexed { index, (name, list, earned) ->
                val alt = index % 2 == 1
                val row = sheet.createRow(r++)
                row.createCell(0).apply {
                    setCellValue(name)
                    cellStyle = s.body(alt = alt, weight = 700)
                }
                row.createCell(1).apply {
                    setCellValue(list.size.toDouble())
                    cellStyle = s.body(s.whole, alt, align = HorizontalAlignment.RIGHT)
                }
                row.createCell(2).apply {
                    setCellValue(list.sumOf { PayEngine.payableHours(it.second) })
                    cellStyle = s.body(s.hours, alt, align = HorizontalAlignment.RIGHT)
                }
                row.createCell(3).apply {
                    setCellValue(earned)
                    cellStyle = s.body(s.money, alt, weight = 700, align = HorizontalAlignment.RIGHT)
                }
                row.createCell(4).apply {
                    setCellValue(if (totals.earnings > 0.0) earned / totals.earnings else 0.0)
                    cellStyle = s.body(s.percent, alt, align = HorizontalAlignment.RIGHT, color = XL_MUTED)
                }
            }

        sheet.printSetup.fitWidth = 1
        sheet.fitToPage = true
        sheet.footer.left = "ShiftVault · ${meta.profileName}"
    }

    /**
     * A live column chart of earnings per month, plotted from a small block of
     * cells parked off to the right so the chart stays linked to real data
     * rather than being baked in as a picture.
     */
    private fun addMonthlyChart(
        sheet: XSSFSheet,
        entries: List<Pair<LocalDate, Shift>>,
        meta: ReportMeta
    ) {
        val byMonth = entries.groupBy { YearMonth.from(it.first) }.toList().sortedBy { it.first }
        if (byMonth.size < 2) return

        val dataCol = 20     // column U — clear of both the print area and the chart
        byMonth.forEachIndexed { index, (month, list) ->
            val row = sheet.getRow(index) ?: sheet.createRow(index)
            row.createCell(dataCol).setCellValue(month.atDay(1).format(monthYearShortFmt))
            row.createCell(dataCol + 1).setCellValue(list.sumOf { PayEngine.earnings(it.second) })
        }
        sheet.setColumnHidden(dataCol, true)
        sheet.setColumnHidden(dataCol + 1, true)

        val drawing = sheet.createDrawingPatriarch()
        val anchor = drawing.createAnchor(0, 0, 0, 0, 6, 1, 14, 20)
        val chart = drawing.createChart(anchor)
        chart.setTitleText("Earnings by month · ${meta.profileName}")
        chart.setTitleOverlay(false)

        val bottom = chart.createCategoryAxis(AxisPosition.BOTTOM)
        val left = chart.createValueAxis(AxisPosition.LEFT)
        left.crosses = org.apache.poi.xddf.usermodel.chart.AxisCrosses.AUTO_ZERO

        val categories = XDDFDataSourcesFactory.fromStringCellRange(
            sheet, CellRangeAddress(0, byMonth.size - 1, dataCol, dataCol)
        )
        val values = XDDFDataSourcesFactory.fromNumericCellRange(
            sheet, CellRangeAddress(0, byMonth.size - 1, dataCol + 1, dataCol + 1)
        )

        val data = chart.createData(ChartTypes.BAR, bottom, left)
        data.addSeries(categories, values).setTitle("Earnings", null)
        (data as XDDFBarChartData).barDirection = BarDirection.COL
        chart.plot(data)
    }
}
