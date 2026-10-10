package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Calendar export — shifts as an iCalendar (.ics) file (RFC 5545).
 *
 *  Opens in Google Calendar (web import), Outlook, Samsung Calendar, Apple
 *  Calendar and most others. Times are written as "floating" local times: a
 *  09:00 shift stays a 09:00 shift in whatever time zone the calendar is in,
 *  which is what a rota means.
 *
 *  Pay is deliberately left out — calendars get shared; hours and the job are
 *  enough to plan around.
 *
 *  Pure text building, no Android — covered by plain JVM tests.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

object CalendarExport {

    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val utcFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun write(shifts: Map<LocalDate, List<Shift>>, profileName: String, out: OutputStream) {
        out.write(build(shifts, profileName, Instant.now()).toByteArray(Charsets.UTF_8))
    }

    /** The whole file. [stamp] is when it was made (DTSTAMP), passed in for tests. */
    fun build(shifts: Map<LocalDate, List<Shift>>, profileName: String, stamp: Instant): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//ShiftVault//Shifts//EN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:" + escape("Shifts · $profileName")
        )
        shifts.toSortedMap().forEach { (date, list) ->
            list.forEachIndexed { index, shift -> lines += event(date, index, shift, profileName, stamp) }
        }
        lines += "END:VCALENDAR"
        return lines.joinToString(separator = "\r\n", postfix = "\r\n") { fold(it) }
    }

    private fun event(date: LocalDate, index: Int, raw: Shift, profileName: String, stamp: Instant): List<String> {
        val shift = raw.migrated()
        val timed = PayEngine.isWorked(shift) && !shift.isAllDaySpan
        val start = if (timed) runCatching { LocalTime.parse(shift.startTime) }.getOrNull() else null

        val when_ = if (start != null) {
            val begins = date.atTime(start)
            val minutes = (PayEngine.grossSpanHours(shift) * 60).roundToLong().coerceAtLeast(1L)
            listOf(
                "DTSTART:" + begins.format(dateTimeFmt),
                "DTEND:" + begins.plusMinutes(minutes).format(dateTimeFmt)
            )
        } else {
            // Leave and all-day entries: an all-day event (DTEND is exclusive).
            listOf(
                "DTSTART;VALUE=DATE:" + date.format(dateFmt),
                "DTEND;VALUE=DATE:" + date.plusDays(1).format(dateFmt)
            )
        }

        val details = buildList {
            add(profileName)
            when (shift.dayType) {
                DayType.WORKED -> add(hoursLabel(PayEngine.workedHours(shift)))
                DayType.PAID_LEAVE -> add("Paid leave")
                DayType.UNPAID_LEAVE -> add("Unpaid leave")
            }
            if (shift.notes.isNotBlank()) add(shift.notes.trim())
        }

        return buildList {
            add("BEGIN:VEVENT")
            add("UID:" + uid(date, index, shift))
            add("DTSTAMP:" + utcFmt.format(stamp))
            addAll(when_)
            add("SUMMARY:" + escape(shift.name.ifBlank { "Shift" }))
            if (shift.location.isNotBlank()) add("LOCATION:" + escape(shift.location.trim()))
            add("DESCRIPTION:" + escape(details.joinToString("\n")))
            // Free/busy: a shift is busy time, a day of leave is not.
            add("TRANSP:" + if (PayEngine.isWorked(shift)) "OPAQUE" else "TRANSPARENT")
            add("END:VEVENT")
        }
    }

    /**
     * Stable for the same entry, so importing an updated export again lets
     * calendars that honour UIDs update the event instead of duplicating it.
     */
    private fun uid(date: LocalDate, index: Int, shift: Shift): String {
        val identity = listOf(shift.jobId, shift.name, shift.startTime, shift.endTime, shift.dayType.name)
            .joinToString("|")
        val hash = Integer.toHexString(identity.hashCode())
        return "${date.format(dateFmt)}-$index-$hash@shiftvault"
    }

    /** RFC 5545 TEXT escaping. */
    internal fun escape(text: String): String = buildString {
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            '\r' -> {}
            else -> append(c)
        }
    }

    /**
     * Content lines are at most 75 octets; longer ones continue on lines that
     * start with a space. Counted in UTF-8 bytes, and never inside a character,
     * so Greek or emoji names survive intact.
     */
    internal fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var lineBytes = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (lineBytes + bytes > limit) {
                out.append("\r\n ")
                lineBytes = 0
                limit = 74 // the leading space counts towards the next line's 75
            }
            out.append(line, i, i + chars)
            lineBytes += bytes
            i += chars
        }
        return out.toString()
    }
}
