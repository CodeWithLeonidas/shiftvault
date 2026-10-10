package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Rest warnings — schedules that wear people down.
 *
 *  Flags, per day, the patterns working-time rules (the EU directive among
 *  them) warn about: too little rest between two working days, shifts that
 *  overlap, a seventh day in a row, and a week over 48 hours.
 *
 *  Every job counts: rest is about the person, and a short turnaround between
 *  two employers is still a short turnaround. Leave and all-day entries have
 *  no clock time, so only worked, timed shifts take part.
 *
 *  Pure date maths, no Android — covered by plain JVM tests.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

enum class RestIssueKind { SHORT_REST, OVERLAP, LONG_STREAK, LONG_WEEK }

/** One warning, filed under the [date] of the shift it is about. */
data class RestIssue(val date: LocalDate, val kind: RestIssueKind, val message: String)

object RestCheck {
    /** Minimum rest between two working days (EU Working Time Directive). */
    const val MIN_REST_HOURS = 11L
    /** Working days in a row before a day off is overdue. */
    const val MAX_STREAK_DAYS = 6
    /** Weekly hours ceiling (EU directive average). */
    const val MAX_WEEK_HOURS = 48.0

    private val dayTime = DateTimeFormatter.ofPattern("EEE HH:mm")

    private data class Span(val date: LocalDate, val start: LocalDateTime, val end: LocalDateTime)

    /** Every issue in [shifts], keyed by the date it should be shown on. */
    fun issues(
        shifts: Map<LocalDate, List<Shift>>,
        weekStart: DayOfWeek = DayOfWeek.MONDAY
    ): Map<LocalDate, List<RestIssue>> {
        val found = mutableListOf<RestIssue>()
        val spans = shifts.flatMap { (date, list) -> list.mapNotNull { span(date, it) } }
            .sortedBy { it.start }

        // ── Rest between shifts, and overlaps ──
        var latest: Span? = null
        for (span in spans) {
            val previous = latest
            if (previous != null) {
                if (span.start.isBefore(previous.end)) {
                    found += RestIssue(
                        span.date, RestIssueKind.OVERLAP,
                        "Overlaps another shift that runs until ${previous.end.format(dayTime)}"
                    )
                } else if (span.date != previous.date) {
                    // Split shifts on one day are a working pattern, not missing
                    // rest; only the gap into a new working day is checked.
                    val rest = Duration.between(previous.end, span.start)
                    if (rest < Duration.ofHours(MIN_REST_HOURS)) {
                        found += RestIssue(
                            span.date, RestIssueKind.SHORT_REST,
                            "Only ${durationLabel(rest)} off after the shift ending " +
                                "${previous.end.format(dayTime)} — $MIN_REST_HOURS h is the usual minimum"
                        )
                    }
                }
            }
            if (previous == null || span.end.isAfter(previous.end)) latest = span
        }

        // ── Days in a row ──
        val workDays = spans.map { it.date }.toSortedSet()
        var run = 0
        var last: LocalDate? = null
        for (day in workDays) {
            run = if (last != null && day == last.plusDays(1)) run + 1 else 1
            if (run > MAX_STREAK_DAYS) {
                found += RestIssue(
                    day, RestIssueKind.LONG_STREAK,
                    "${ordinal(run)} working day in a row without a day off"
                )
            }
            last = day
        }

        // ── Hours per week: flagged once, on the day the week tips over ──
        val workedByDay = shifts.mapValues { (_, list) ->
            list.filter { PayEngine.isWorked(it) }.sumOf { PayEngine.workedHours(it) }
        }.filterValues { it > 0.0 }
        workedByDay.keys.groupBy { it.with(TemporalAdjusters.previousOrSame(weekStart)) }
            .forEach { (_, days) ->
                var total = 0.0
                for (day in days.sorted()) {
                    val before = total
                    total += workedByDay.getValue(day)
                    if (before <= MAX_WEEK_HOURS && total > MAX_WEEK_HOURS) {
                        found += RestIssue(
                            day, RestIssueKind.LONG_WEEK,
                            "This week passes ${MAX_WEEK_HOURS.toInt()}h here " +
                                "(${hoursLabel(total)} so far, every job counted)"
                        )
                    }
                }
            }

        return found.groupBy { it.date }
    }

    /** A worked, timed shift as clock times; null for leave and all-day entries. */
    private fun span(date: LocalDate, shift: Shift): Span? {
        if (!PayEngine.isWorked(shift) || shift.isAllDaySpan) return null
        val start = runCatching { LocalTime.parse(shift.startTime) }.getOrNull() ?: return null
        val begins = date.atTime(start)
        // The same span the pay maths uses, so overnight shifts end the next day.
        val minutes = (PayEngine.grossSpanHours(shift) * 60).roundToLong()
        if (minutes <= 0L) return null
        return Span(date, begins, begins.plusMinutes(minutes))
    }

    private fun durationLabel(d: Duration): String {
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return if (m == 0L) "${h}h" else "${h}h ${m}m"
    }

    private fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}
