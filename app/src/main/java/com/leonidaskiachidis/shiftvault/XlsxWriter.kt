package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  XlsxWriter — a small, dependency-free .xlsx writer.
 *
 *  An .xlsx file is a zip of XML parts. This writes exactly the parts the
 *  earnings report needs — styled cells, number formats, column widths, row
 *  heights, frozen panes, an autofilter, formulas, data bars and print setup —
 *  and nothing else. Excel, Google Sheets, Numbers and LibreOffice render
 *  these the same whichever library produced them.
 *
 *  It replaces Apache POI, which did the same job but added ~25 MB of code and
 *  schema classes to the APK. POI is still used in unit tests, to open every
 *  file this produces and fail the build if one is malformed.
 *
 *  Strings are written inline (no shared-string table) and formulas without a
 *  cached value; the workbook asks Excel to calculate everything on open.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ══════════════════════════════════════════════════════════════
//  Styles
// ══════════════════════════════════════════════════════════════

enum class XlsxAlign { LEFT, CENTER, RIGHT }

/**
 * Everything about how a cell looks. Colours are 0xRRGGBB. Equal styles share
 * one entry in the file, so building them freely per cell is fine.
 */
data class XlsxStyle(
    val bold: Boolean = false,
    val size: Int = 10,
    val color: Int = 0x000000,
    val fill: Int? = null,
    /** Thin bottom rule in this colour. */
    val bottomBorder: Int? = null,
    /** Thin top rule in this colour. */
    val topBorder: Int? = null,
    /** Excel number format code, e.g. `0.00"h"`. */
    val format: String? = null,
    val align: XlsxAlign = XlsxAlign.LEFT,
    /** Inset from the aligned edge, in Excel's indent steps (~1 character each). */
    val indent: Int = 0
)

// ══════════════════════════════════════════════════════════════
//  Workbook model
// ══════════════════════════════════════════════════════════════

class XlsxWorkbook(private val creator: String = "ShiftVault") {
    private val sheets = mutableListOf<XlsxSheet>()

    /** Sheets appear in the order they are added; the first is the one that opens. */
    fun sheet(name: String): XlsxSheet = XlsxSheet(name).also { sheets += it }

    /**
     * Writes the finished file. Leaves [out] open — the caller owns it, as with
     * the PDF export.
     */
    fun write(out: OutputStream) {
        val styles = StyleTable()
        // Rendering the sheets first registers every style they use.
        val sheetXml = sheets.mapIndexed { i, s -> s.toXml(styles, selected = i == 0) }

        val zip = ZipOutputStream(out)
        fun part(path: String, xml: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        part("[Content_Types].xml", contentTypes())
        part("_rels/.rels", rootRels())
        part("docProps/core.xml", coreProps())
        part("xl/workbook.xml", workbookXml())
        part("xl/_rels/workbook.xml.rels", workbookRels())
        part("xl/styles.xml", styles.toXml())
        sheetXml.forEachIndexed { i, xml -> part("xl/worksheets/sheet${i + 1}.xml", xml) }

        zip.finish()
        zip.flush()
    }

    private fun contentTypes() = buildString {
        append(XML_HEAD)
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        sheets.indices.forEach {
            append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels() = XML_HEAD +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
            "</Relationships>"

    private fun coreProps() = XML_HEAD +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" """ +
            """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            "<dc:creator>${esc(creator)}</dc:creator>" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">${java.time.Instant.now().toString().substringBefore('.')}Z</dcterms:created>""" +
            "</cp:coreProperties>"

    private fun workbookXml() = buildString {
        append(XML_HEAD)
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<bookViews><workbookView activeTab="0"/></bookViews><sheets>""")
        sheets.forEachIndexed { i, s ->
            append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets>")

        // The filter range and the header row repeated on printed pages are
        // "defined names" in the workbook, pointing into each sheet.
        val names = buildString {
            sheets.forEachIndexed { i, s ->
                val q = "'" + s.name.replace("'", "''") + "'"
                s.autoFilter?.let { f ->
                    append("""<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">""")
                    append(esc("$q!${f.absolute()}"))
                    append("</definedName>")
                }
                s.repeatRow?.let { r ->
                    append("""<definedName name="_xlnm.Print_Titles" localSheetId="$i">""")
                    append(esc("$q!\$${r + 1}:\$${r + 1}"))
                    append("</definedName>")
                }
            }
        }
        if (names.isNotEmpty()) append("<definedNames>").append(names).append("</definedNames>")

        // Formulas carry no cached value, so Excel must work them out on open.
        append("""<calcPr calcId="191029" fullCalcOnLoad="1"/>""")
        append("</workbook>")
    }

    private fun workbookRels() = buildString {
        append(XML_HEAD)
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sheets.indices.forEach {
            append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
        }
        append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }
}

/** A rectangular block of cells, zero-based and inclusive. */
data class XlsxRange(val firstRow: Int, val firstCol: Int, val lastRow: Int, val lastCol: Int) {
    fun ref() = "${cellRef(firstRow, firstCol)}:${cellRef(lastRow, lastCol)}"
    fun absolute() =
        "\$${colName(firstCol)}\$${firstRow + 1}:\$${colName(lastCol)}\$${lastRow + 1}"
}

class XlsxSheet internal constructor(val name: String) {

    internal sealed class Value {
        class Text(val text: String) : Value()
        class Number(val value: Double) : Value()
        class Formula(val formula: String) : Value()
        object Blank : Value()
    }

    internal class Cell(val value: Value, val style: XlsxStyle?)

    inner class Row internal constructor(internal val index: Int) {
        internal val cells = sortedMapOf<Int, Cell>()
        internal var height: Float? = null

        fun height(points: Float) = apply { height = points }

        fun text(col: Int, text: String, style: XlsxStyle? = null) = apply {
            cells[col] = Cell(Value.Text(text), style)
        }

        fun number(col: Int, value: Double, style: XlsxStyle? = null) = apply {
            cells[col] = Cell(Value.Number(value), style)
        }

        /** A real date cell (a serial number), so it sorts and filters as a date. */
        fun date(col: Int, date: LocalDate, style: XlsxStyle) = apply {
            cells[col] = Cell(Value.Number(excelSerial(date)), style)
        }

        /** Formula text without the leading "=", e.g. `SUM(B2:B9)`. */
        fun formula(col: Int, formula: String, style: XlsxStyle? = null) = apply {
            cells[col] = Cell(Value.Formula(formula), style)
        }

        /** An empty cell that still carries a style — a fill or a rule. */
        fun blank(col: Int, style: XlsxStyle) = apply {
            cells[col] = Cell(Value.Blank, style)
        }
    }

    private val rows = sortedMapOf<Int, Row>()
    private val widths = sortedMapOf<Int, Double>()
    private val dataBars = mutableListOf<Pair<XlsxRange, Int>>()

    /** Columns and rows to the left of / above the split stay put while scrolling. */
    var freeze: Pair<Int, Int>? = null
    var autoFilter: XlsxRange? = null
    var showGridLines = true
    var landscape = false
    /** Print scaled to one page wide (and as many tall as needed). */
    var fitToWidth = false
    /** Zero-based row printed at the top of every page. */
    var repeatRow: Int? = null
    var footerLeft: String? = null
    var footerRight: String? = null

    fun row(index: Int): Row = rows.getOrPut(index) { Row(index) }

    /** Width in Excel's character units (the number shown in Excel's column dialog). */
    fun columnWidth(col: Int, characters: Double) {
        widths[col] = characters
    }

    /**
     * Horizontal in-cell bars, measured from zero up to the column's largest
     * value — so 7.5h and 8h look nearly equal, as they are. (Excel's default
     * starts at the smallest value, which turns small differences into big ones.)
     */
    fun dataBar(range: XlsxRange, color: Int) {
        dataBars += range to color
    }

    internal fun toXml(styles: StyleTable, selected: Boolean): String = buildString {
        append(XML_HEAD)
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        // Element order below is fixed by the file format.
        if (fitToWidth) append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")

        append("<sheetViews><sheetView workbookViewId=\"0\"")
        if (selected) append(" tabSelected=\"1\"")
        if (!showGridLines) append(" showGridLines=\"0\"")
        append(">")
        freeze?.let { (cols, rowsAbove) ->
            val pane = when {
                cols > 0 && rowsAbove > 0 -> "bottomRight"
                rowsAbove > 0 -> "bottomLeft"
                else -> "topRight"
            }
            append("<pane")
            if (cols > 0) append(" xSplit=\"$cols\"")
            if (rowsAbove > 0) append(" ySplit=\"$rowsAbove\"")
            append(" topLeftCell=\"${cellRef(rowsAbove, cols)}\" activePane=\"$pane\" state=\"frozen\"/>")
            append("<selection pane=\"$pane\"/>")
        }
        append("</sheetView></sheetViews>")
        append("""<sheetFormatPr defaultRowHeight="15"/>""")

        if (widths.isNotEmpty()) {
            append("<cols>")
            widths.forEach { (c, w) ->
                append("""<col min="${c + 1}" max="${c + 1}" width="${fmt(w)}" customWidth="1"/>""")
            }
            append("</cols>")
        }

        append("<sheetData>")
        rows.values.forEach { row ->
            append("<row r=\"${row.index + 1}\"")
            row.height?.let { append(" ht=\"${fmt(it.toDouble())}\" customHeight=\"1\"") }
            append(">")
            row.cells.forEach { (c, cell) ->
                val ref = cellRef(row.index, c)
                val s = cell.style?.let { " s=\"${styles.indexOf(it)}\"" } ?: ""
                when (val v = cell.value) {
                    is Value.Text ->
                        append("<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v.text)}</t></is></c>")
                    is Value.Number ->
                        append("<c r=\"$ref\"$s><v>${fmt(v.value)}</v></c>")
                    is Value.Formula ->
                        append("<c r=\"$ref\"$s><f>${esc(v.formula)}</f></c>")
                    Value.Blank ->
                        append("<c r=\"$ref\"$s/>")
                }
            }
            append("</row>")
        }
        append("</sheetData>")

        autoFilter?.let { append("<autoFilter ref=\"${it.ref()}\"/>") }

        dataBars.forEachIndexed { i, (range, color) ->
            append("<conditionalFormatting sqref=\"${range.ref()}\">")
            append("<cfRule type=\"dataBar\" priority=\"${i + 1}\"><dataBar>")
            append("<cfvo type=\"num\" val=\"0\"/><cfvo type=\"max\"/><color rgb=\"${argb(color)}\"/>")
            append("</dataBar></cfRule></conditionalFormatting>")
        }

        append("""<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.75" header="0.3" footer="0.3"/>""")
        if (landscape || fitToWidth) {
            append("<pageSetup")
            if (landscape) append(" orientation=\"landscape\"")
            if (fitToWidth) append(" fitToWidth=\"1\" fitToHeight=\"0\"")
            append("/>")
        }
        if (footerLeft != null || footerRight != null) {
            // Header/footer text uses "&" as its control character; a literal
            // ampersand in, say, a profile name has to be doubled.
            val left = footerLeft?.let { "&L" + it.replace("&", "&&") } ?: ""
            val right = footerRight?.let { "&R$it" } ?: ""
            append("<headerFooter><oddFooter>${esc(left + right)}</oddFooter></headerFooter>")
        }
        append("</worksheet>")
    }
}

// ══════════════════════════════════════════════════════════════
//  styles.xml
// ══════════════════════════════════════════════════════════════

/** Collects the distinct fonts, fills, borders and formats the sheets use. */
internal class StyleTable {
    private val xfs = linkedMapOf<XlsxStyle, Int>()

    private data class Font(val bold: Boolean, val size: Int, val color: Int)
    private data class Border(val bottom: Int?, val top: Int?)

    private val fonts = linkedMapOf(Font(false, 11, 0x000000) to 0)
    // The first two fills are reserved by the format ("none" and "gray125").
    private val fills = linkedMapOf<Int?, Int>(null to 0)
    private val borders = linkedMapOf(Border(null, null) to 0)
    private val formats = linkedMapOf<String, Int>()

    init {
        xfs[XlsxStyle()] = 0
    }

    fun indexOf(style: XlsxStyle): Int = xfs.getOrPut(style) { xfs.size }

    fun toXml(): String {
        // Resolve every style's parts, assigning ids in first-use order.
        data class Xf(val numFmt: Int, val font: Int, val fill: Int, val border: Int, val align: XlsxAlign, val indent: Int)
        val resolved = xfs.keys.map { s ->
            val numFmt = s.format?.let { f -> formats.getOrPut(f) { CUSTOM_FORMAT_BASE + formats.size } } ?: 0
            val font = fonts.getOrPut(Font(s.bold, s.size, s.color)) { fonts.size }
            val fill = if (s.fill == null) 0 else fills.getOrPut(s.fill) { fills.size + 1 }
            val border = borders.getOrPut(Border(s.bottomBorder, s.topBorder)) { borders.size }
            Xf(numFmt, font, fill, border, s.align, s.indent)
        }

        return buildString {
            append(XML_HEAD)
            append("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

            if (formats.isNotEmpty()) {
                append("<numFmts count=\"${formats.size}\">")
                formats.forEach { (code, id) -> append("<numFmt numFmtId=\"$id\" formatCode=\"${esc(code)}\"/>") }
                append("</numFmts>")
            }

            append("<fonts count=\"${fonts.size}\">")
            fonts.keys.forEach { f ->
                append("<font>")
                if (f.bold) append("<b/>")
                append("<sz val=\"${f.size}\"/><color rgb=\"${argb(f.color)}\"/><name val=\"Calibri\"/><family val=\"2\"/>")
                append("</font>")
            }
            append("</fonts>")

            append("<fills count=\"${fills.size + 1}\">")
            append("""<fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""")
            fills.keys.filterNotNull().forEach { c ->
                append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${argb(c)}\"/><bgColor indexed=\"64\"/></patternFill></fill>")
            }
            append("</fills>")

            append("<borders count=\"${borders.size}\">")
            borders.keys.forEach { b ->
                append("<border><left/><right/>")
                append(b.top?.let { "<top style=\"thin\"><color rgb=\"${argb(it)}\"/></top>" } ?: "<top/>")
                append(b.bottom?.let { "<bottom style=\"thin\"><color rgb=\"${argb(it)}\"/></bottom>" } ?: "<bottom/>")
                append("<diagonal/></border>")
            }
            append("</borders>")

            append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")

            append("<cellXfs count=\"${resolved.size}\">")
            resolved.forEach { x ->
                append("<xf numFmtId=\"${x.numFmt}\" fontId=\"${x.font}\" fillId=\"${x.fill}\" borderId=\"${x.border}\" xfId=\"0\"")
                if (x.numFmt != 0) append(" applyNumberFormat=\"1\"")
                if (x.font != 0) append(" applyFont=\"1\"")
                if (x.fill != 0) append(" applyFill=\"1\"")
                if (x.border != 0) append(" applyBorder=\"1\"")
                val h = when (x.align) {
                    XlsxAlign.LEFT -> "left"
                    XlsxAlign.CENTER -> "center"
                    XlsxAlign.RIGHT -> "right"
                }
                val indent = if (x.indent > 0) " indent=\"${x.indent}\"" else ""
                append(" applyAlignment=\"1\"><alignment horizontal=\"$h\" vertical=\"center\"$indent/></xf>")
            }
            append("</cellXfs>")

            append("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
            append("""<dxfs count="0"/><tableStyles count="0"/>""")
            append("</styleSheet>")
        }
    }

    private companion object {
        /** Ids below this are Excel's built-in formats. */
        const val CUSTOM_FORMAT_BASE = 164
    }
}

// ══════════════════════════════════════════════════════════════
//  Helpers
// ══════════════════════════════════════════════════════════════

private const val XML_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

/** 0 → "A", 25 → "Z", 26 → "AA". */
internal fun colName(col: Int): String {
    var n = col + 1
    val sb = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % 26
        sb.append('A' + rem)
        n = (n - 1) / 26
    }
    return sb.reverse().toString()
}

internal fun cellRef(row: Int, col: Int) = "${colName(col)}${row + 1}"

/** Excel counts days from 1899-12-30 (a historical quirk it keeps for compatibility). */
internal fun excelSerial(date: LocalDate): Double = (date.toEpochDay() + 25569).toDouble()

private fun argb(rgb: Int) = "FF%06X".format(rgb and 0xFFFFFF)

/** Numbers as XML wants them: invariant, no exponent for normal values, no ".0" noise. */
private fun fmt(value: Double): String =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString()
    else java.math.BigDecimal(value).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

/** Escapes text for XML and drops the control characters XML 1.0 forbids. */
private fun esc(text: String): String = buildString(text.length) {
    for (ch in text) {
        when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
            else -> append(ch)
        }
    }
}
