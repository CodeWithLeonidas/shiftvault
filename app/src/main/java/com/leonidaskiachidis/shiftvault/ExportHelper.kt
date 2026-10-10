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
 *  The workbook is written by XlsxWriter, a few kilobytes of our own code,
 *  rather than Apache POI, which added ~25 MB to the APK.
 *
 *  Neither function touches the UI or closes the stream it was handed — both
 *  are safe to call off the main thread.
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
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Who a report is for, and in what currency it is written. */
data class ReportMeta(
    val profileName: String,
    val profileColorValue: Long,
    val currencyCode: String,
    /**
     * The period the report was asked to cover ("15 Sep – 14 Oct 2026"), or
     * null for everything recorded — then the range is read off the data.
     */
    val periodLabel: String? = null,
    /**
     * Set for a salaried job: its pay isn't the sum of its shifts, so the
     * report's money totals come from the same period calculation as the app.
     */
    val pay: PeriodPay? = null
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
    private fun rangeLabel(entries: List<Pair<LocalDate, Shift>>, meta: ReportMeta): String {
        meta.periodLabel?.let { return it }
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
    fun suggestFileName(profileName: String, extension: String, range: DateRange? = null): String {
        val safe = profileName.trim()
            // Any script's letters and digits are kept — "Καφετέρια" must not
            // collapse to an empty name. Only characters that are unsafe or
            // awkward in file names are dropped.
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .replace(' ', '-')
            .take(28)
            .ifBlank { "Report" }
        val span = range?.let { "${it.start}_to_${it.end}" } ?: LocalDate.now().toString()
        return "ShiftVault-$safe-$span.$extension"
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
                        " · ${rangeLabel(entries, meta)}",
                        MARGIN + 14f + nameW, 92f, pSub
                    )

                    canvas.drawLine(MARGIN, 108f, RIGHT_EDGE, 108f, lineOutline)

                    drawKpiCards(
                        canvas, kpiTop, kpiH, totals, symbol,
                        fillCard, lineOutline, pKpiLabel, pKpiValue,
                        pay = meta.pay
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
                            if (meta.periodLabel != null) "No shifts in this period."
                            else "No shifts recorded for ${meta.profileName} yet.",
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
        value: Paint,
        pay: PeriodPay? = null
    ) {
        val cards = if (pay != null) listOf(
            "SHIFTS WORKED" to totals.shiftsWorked.toString(),
            "HOURS WORKED" to "${num(pay.hoursWorked, 1)} h",
            "BASE SALARY" to formatCents(pay.baseCents, symbol),
            "TOTAL EXPECTED" to formatCents(pay.expectedCents, symbol)
        ) else listOf(
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

    /** Height of a table row, in points — a little air around every line. */
    private const val XL_ROW_H = 18f

    /** The report's visual vocabulary, so every table in the workbook matches. */
    private class Styles(symbol: String) {
        val money = "\"$symbol\"#,##0.00"
        val hours = "0.00\"h\""
        val multiplier = "\"×\"0.00"
        val date = "d mmm yyyy"
        val whole = "0"
        val percent = "0.0%"

        /** A table cell: thin outline rule underneath, zebra fill on alternate rows. */
        fun body(
            format: String? = null,
            alt: Boolean = false,
            bold: Boolean = false,
            color: Int = XL_INK,
            align: XlsxAlign = XlsxAlign.LEFT,
            fill: Int? = null,
            topRule: Boolean = false,
            indent: Int = 0
        ) = XlsxStyle(
            bold = bold,
            size = 10,
            color = color,
            fill = fill ?: if (alt) XL_ZEBRA else null,
            bottomBorder = XL_OUTLINE,
            topBorder = if (topRule) XL_INK else null,
            format = format,
            align = align,
            indent = indent
        )

        /** Free-standing text: titles, subtitles, section labels. */
        fun plain(bold: Boolean, size: Int, color: Int) =
            XlsxStyle(bold = bold, size = size, color = color)

        /** Column headings: white on the job profile's colour. */
        fun header(accent: Int) = XlsxStyle(
            bold = true, size = 10, color = XL_WHITE, fill = accent, align = XlsxAlign.CENTER
        )

        /** The closing totals row: card fill with a dark rule above. */
        fun total(format: String? = null, bold: Boolean = true, align: XlsxAlign = XlsxAlign.RIGHT) =
            body(format, bold = bold, align = align, fill = XL_CARD, topRule = true)
    }

    /**
     * Writes the workbook: a Summary sheet (opens first) and the full Shifts
     * table. Built with [XlsxWorkbook], so it adds nothing to the APK beyond a
     * few kilobytes of code.
     */
    fun exportShiftsToExcel(
        savedShifts: Map<LocalDate, List<Shift>>,
        outputStream: OutputStream,
        meta: ReportMeta,
        /**
         * The heading colour (0xRRGGBB). Normally derived from the profile;
         * unit tests pass it directly, because the Android colour maths behind
         * the default is stubbed out on the JVM.
         */
        accent: Int = ShiftColors
            .accent(androidx.compose.ui.graphics.Color(meta.profileColorValue.toInt()), false)
            .toArgb() and 0xFFFFFF
    ) {
        val entries = entriesOf(savedShifts)
        val totals = Totals(entries)
        val styles = Styles(meta.currencySymbol)

        val workbook = XlsxWorkbook()
        // Summary reads better as the first thing the file opens on.
        writeSummarySheet(workbook.sheet("Summary"), entries, totals, styles, accent, meta)
        writeShiftsSheet(workbook.sheet("Shifts"), entries, styles, accent, meta)
        workbook.write(outputStream)
    }

    private fun writeShiftsSheet(
        sheet: XlsxSheet,
        entries: List<Pair<LocalDate, Shift>>,
        s: Styles,
        accent: Int,
        meta: ReportMeta
    ) {
        val columns = listOf(
            "Date" to 11.7, "Day" to 7.0, "Shift" to 20.3, "Type" to 11.7,
            "Start" to 7.8, "End" to 7.8, "Break" to 7.8, "Paid break" to 10.2,
            "Hours" to 9.4, "Rate" to 10.2, "Multiplier" to 10.2, "Bonus" to 10.2,
            "Earnings" to 11.7, "Location" to 18.0, "Note" to 27.3
        )
        columns.forEachIndexed { i, (_, w) -> sheet.columnWidth(i, w) }
        sheet.showGridLines = false

        var r = 0
        sheet.row(r++).height(24f).text(0, "Earnings Report", s.plain(true, 16, XL_INK))
        sheet.row(r++).text(
            0,
            "${meta.profileName} · ${rangeLabel(entries, meta)} · generated ${LocalDate.now().format(fullDateFmt)}",
            s.plain(false, 10, XL_MUTED)
        )
        r++

        val headerRow = r
        sheet.row(r++).height(22f).apply {
            columns.forEachIndexed { i, (title, _) -> text(i, title, s.header(accent)) }
        }
        sheet.freeze = 3 to headerRow + 1

        if (entries.isEmpty()) {
            sheet.row(r).text(
                0,
                if (meta.periodLabel != null) "No shifts in this period." else "No shifts recorded for this job profile.",
                s.plain(false, 10, XL_MUTED)
            )
            return
        }

        val firstData = r
        entries.forEachIndexed { index, (date, shift) ->
            val alt = index % 2 == 1
            val worked = shift.dayType == DayType.WORKED
            sheet.row(r++).height(XL_ROW_H)
                .date(0, date, s.body(s.date, alt))                    // a real date, so it sorts
                .text(
                    1,
                    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()),
                    s.body(alt = alt, color = XL_MUTED)
                )
                .text(2, shift.name, s.body(alt = alt, bold = true))
                .text(3, typeLabel(shift), s.body(alt = alt, color = XL_MUTED))
                .text(4, if (worked) shift.startTime else "–", s.body(alt = alt, align = XlsxAlign.CENTER))
                .text(5, if (worked) shift.endTime else "–", s.body(alt = alt, align = XlsxAlign.CENTER))
                .number(
                    6, if (worked) shift.breakMinutes.toDouble() else 0.0,
                    s.body(s.whole, alt, align = XlsxAlign.RIGHT, color = XL_MUTED)
                )
                .text(
                    7,
                    when {
                        !worked || shift.breakMinutes == 0 -> "–"
                        shift.paidBreak -> "Paid"
                        else -> "Unpaid"
                    },
                    s.body(alt = alt, align = XlsxAlign.CENTER, color = XL_MUTED)
                )
                .number(8, PayEngine.payableHours(shift), s.body(s.hours, alt, align = XlsxAlign.RIGHT))
                .number(9, shift.hourlyRate, s.body(s.money, alt, align = XlsxAlign.RIGHT, color = XL_MUTED))
                .number(
                    10, PayEngine.effectiveMultiplier(shift),
                    s.body(s.multiplier, alt, align = XlsxAlign.RIGHT, color = XL_MUTED)
                )
                .number(
                    11, if (shift.dayType == DayType.UNPAID_LEAVE) 0.0 else shift.bonus,
                    s.body(s.money, alt, align = XlsxAlign.RIGHT, color = XL_MUTED)
                )
                .number(12, PayEngine.earnings(shift), s.body(s.money, alt, bold = true, align = XlsxAlign.RIGHT))
                // Indented so they don't run straight on from the right-aligned
                // earnings figure beside them.
                .text(13, shift.location, s.body(alt = alt, color = XL_MUTED, indent = 1))
                .text(14, shift.notes, s.body(alt = alt, color = XL_MUTED, indent = 1))
        }
        val lastData = r - 1

        // Totals built on SUBTOTAL rather than SUM: filter the table down to one
        // shift name or one month and the totals follow the filter.
        fun subtotal(col: Int) =
            "SUBTOTAL(109,${cellRef(firstData, col)}:${cellRef(lastData, col)})"

        sheet.row(r).height(20f).apply {
            text(0, "TOTAL", s.total(bold = true, align = XlsxAlign.LEFT))
            listOf(1, 2, 3, 4, 5, 6, 7, 9, 10, 13, 14).forEach { blank(it, s.total()) }
            formula(8, subtotal(8), s.total(s.hours))
            formula(11, subtotal(11), s.total(s.money, bold = false))
            formula(12, subtotal(12), s.total(s.money))
        }

        sheet.autoFilter = XlsxRange(headerRow, 0, lastData, columns.size - 1)

        // Data bars turn the hours column into a glanceable profile of the month.
        sheet.dataBar(XlsxRange(firstData, 8, lastData, 8), accent)

        sheet.landscape = true
        sheet.fitToWidth = true
        sheet.repeatRow = headerRow
        sheet.footerLeft = "ShiftVault · ${meta.profileName}"
        sheet.footerRight = "Page &P of &N"
    }

    private fun writeSummarySheet(
        sheet: XlsxSheet,
        entries: List<Pair<LocalDate, Shift>>,
        totals: Totals,
        s: Styles,
        accent: Int,
        meta: ReportMeta
    ) {
        listOf(20.3, 12.5, 12.5, 12.5, 13.3).forEachIndexed { i, w -> sheet.columnWidth(i, w) }
        sheet.showGridLines = false

        var r = 0
        sheet.row(r++).height(28f).text(0, "Earnings Report", s.plain(true, 18, XL_INK))
        sheet.row(r++).height(18f).text(0, "${meta.profileName} · ${rangeLabel(entries, meta)}", s.plain(true, 11, accent))
        sheet.row(r++).text(0, "Generated ${LocalDate.now().format(fullDateFmt)}", s.plain(false, 10, XL_MUTED))
        r++

        fun section(title: String) {
            sheet.row(r++).height(20f).text(0, title, s.plain(true, 11, XL_INK))
        }

        section("AT A GLANCE")
        fun kpi(label: String, value: Double, format: String, emphasise: Boolean = false) {
            val fill = if (emphasise) XL_CARD else null
            sheet.row(r++).height(20f)
                .text(0, label, s.body(fill = fill, bold = emphasise))
                .number(1, value, s.body(format, bold = emphasise, align = XlsxAlign.RIGHT, fill = fill))
        }
        kpi("Shifts worked", totals.shiftsWorked.toDouble(), s.whole)
        val pay = meta.pay
        if (pay != null) {
            // A salary: the totals come from the period, not from the shifts.
            kpi("Hours worked", pay.hoursWorked, s.hours)
            kpi("Base salary", pay.baseCents / 100.0, s.money)
            if (pay.overtimeHours > 0.0) kpi("Overtime hours", pay.overtimeHours, s.hours)
            pay.overtimeCents?.takeIf { it > 0L }?.let { kpi("Overtime pay", it / 100.0, s.money) }
            kpi("Bonuses", pay.extrasCents / 100.0, s.money)
            kpi("Total expected", pay.expectedCents / 100.0, s.money, emphasise = true)
        } else {
            kpi("Paid hours", totals.hours, s.hours)
            kpi("Average effective rate", totals.averageRate, s.money)
            kpi("Bonuses", totals.bonuses, s.money)
            kpi("Total earnings", totals.earnings, s.money, emphasise = true)
        }
        r++

        // ── By month ──
        section("BY MONTH")
        sheet.row(r++).height(20f).apply {
            listOf("Month", "Shifts", "Hours", "Bonuses", "Earnings")
                .forEachIndexed { i, title -> text(i, title, s.header(accent)) }
        }

        val byMonth = entries.groupBy { YearMonth.from(it.first) }.toList().sortedBy { it.first }
        val monthFirst = r
        byMonth.forEachIndexed { index, (month, list) ->
            val alt = index % 2 == 1
            sheet.row(r++).height(XL_ROW_H)
                .text(0, month.atDay(1).format(monthFmt), s.body(alt = alt, bold = true))
                .number(
                    1, list.count { PayEngine.isWorked(it.second) }.toDouble(),
                    s.body(s.whole, alt, align = XlsxAlign.RIGHT)
                )
                .number(2, list.sumOf { PayEngine.payableHours(it.second) }, s.body(s.hours, alt, align = XlsxAlign.RIGHT))
                .number(
                    3,
                    list.sumOf { if (it.second.dayType == DayType.UNPAID_LEAVE) 0.0 else it.second.bonus },
                    s.body(s.money, alt, align = XlsxAlign.RIGHT, color = XL_MUTED)
                )
                .number(
                    4, list.sumOf { PayEngine.earnings(it.second) },
                    s.body(s.money, alt, bold = true, align = XlsxAlign.RIGHT)
                )
        }
        if (byMonth.isNotEmpty()) {
            val monthLast = r - 1
            sheet.row(r++).height(20f).apply {
                text(0, "Total", s.total(bold = true, align = XlsxAlign.LEFT))
                listOf(1 to s.whole, 2 to s.hours, 3 to s.money, 4 to s.money).forEach { (col, fmt) ->
                    formula(col, "SUM(${cellRef(monthFirst, col)}:${cellRef(monthLast, col)})", s.total(fmt))
                }
            }
        }
        r++

        // ── By shift ──
        section("BY SHIFT")
        sheet.row(r++).height(20f).apply {
            listOf("Shift", "Times worked", "Hours", "Earnings", "Share")
                .forEachIndexed { i, title -> text(i, title, s.header(accent)) }
        }

        val byShiftFirst = r
        entries.groupBy { it.second.name }
            .map { (name, list) -> Triple(name, list, list.sumOf { PayEngine.earnings(it.second) }) }
            .sortedByDescending { it.third }
            .forEachIndexed { index, (name, list, earned) ->
                val alt = index % 2 == 1
                sheet.row(r++).height(XL_ROW_H)
                    .text(0, name, s.body(alt = alt, bold = true))
                    .number(1, list.size.toDouble(), s.body(s.whole, alt, align = XlsxAlign.RIGHT))
                    .number(2, list.sumOf { PayEngine.payableHours(it.second) }, s.body(s.hours, alt, align = XlsxAlign.RIGHT))
                    .number(3, earned, s.body(s.money, alt, bold = true, align = XlsxAlign.RIGHT))
                    .number(
                        4, if (totals.earnings > 0.0) earned / totals.earnings else 0.0,
                        s.body(s.percent, alt, align = XlsxAlign.RIGHT, color = XL_MUTED)
                    )
            }
        // The share column doubles as a bar chart, standing in for the old
        // embedded chart.
        if (r > byShiftFirst) sheet.dataBar(XlsxRange(byShiftFirst, 4, r - 1, 4), accent)

        sheet.fitToWidth = true
        sheet.footerLeft = "ShiftVault · ${meta.profileName}"
    }
}
