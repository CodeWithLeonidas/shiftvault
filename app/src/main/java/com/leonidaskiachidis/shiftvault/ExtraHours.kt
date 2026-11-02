package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Extra hours — the shifts switched to Extra, and whether each has been paid.
 *
 *  Hours first: this is what the Wallet's "Extra hours" card shows, and what a
 *  tick marks paid or pending. Money for those hours stays in the ledger,
 *  where it already is; this only tracks the hours themselves.
 *
 *  Pure Kotlin, no Android — covered by plain JVM tests.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import java.time.LocalDate
import java.time.YearMonth

/**
 * One extra shift on the calendar. [index] is its place among its job's
 * shifts that day — the same position the shift form edits by.
 */
data class ExtraEntry(val date: LocalDate, val index: Int, val shift: Shift) {
    val hours: Double get() = PayEngine.workedHours(shift)
    val paid: Boolean get() = shift.extraPaid
}

/** A month's extra shifts, newest first, with what is still to be paid. */
data class ExtraMonth(val month: YearMonth, val entries: List<ExtraEntry>) {
    val pendingHours: Double = entries.filterNot { it.paid }.sumOf { it.hours }
    val paidHours: Double = entries.filter { it.paid }.sumOf { it.hours }
    val allPaid: Boolean get() = entries.all { it.paid }
}

object ExtraHours {

    /** Every worked extra shift in one job's [shifts], newest first. */
    fun entries(shifts: Map<LocalDate, List<Shift>>): List<ExtraEntry> =
        shifts.toSortedMap(compareByDescending { it }).flatMap { (date, list) ->
            list.mapIndexedNotNull { i, s ->
                if (s.isExtra && PayEngine.isWorked(s)) ExtraEntry(date, i, s) else null
            }
        }

    /** [entries] by the month they were worked in, newest month first. */
    fun byMonth(entries: List<ExtraEntry>): List<ExtraMonth> =
        entries.groupBy { YearMonth.from(it.date) }
            .map { (month, list) -> ExtraMonth(month, list) }
            .sortedByDescending { it.month }

    fun pendingHours(entries: List<ExtraEntry>): Double = entries.filterNot { it.paid }.sumOf { it.hours }
    fun paidHours(entries: List<ExtraEntry>): Double = entries.filter { it.paid }.sumOf { it.hours }

    /**
     * [all] (every job's shifts) with the extra shifts at [targets] — dates
     * and their positions within [jobId]'s day — marked [paid]. Nothing else
     * changes, in this job or any other.
     */
    fun setPaid(
        all: Map<LocalDate, List<Shift>>,
        jobId: String,
        targets: Map<LocalDate, Set<Int>>,
        paid: Boolean
    ): Map<LocalDate, List<Shift>> {
        var result = all
        for ((date, indices) in targets) {
            val slice = ProfileScope.daySlice(result, date, jobId)
            if (indices.none { it in slice.indices }) continue
            val updated = slice.mapIndexed { i, s ->
                if (i in indices && s.isExtra) s.copy(extraPaid = paid) else s
            }
            result = ProfileScope.withDaySlice(result, date, jobId, updated)
        }
        return result
    }

    /** Positions of [entries], as [setPaid] takes them. */
    fun targetsOf(entries: List<ExtraEntry>): Map<LocalDate, Set<Int>> =
        entries.groupBy({ it.date }, { it.index }).mapValues { it.value.toSet() }
}
