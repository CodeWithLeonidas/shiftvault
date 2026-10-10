package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Rotations — repeating work patterns laid onto the calendar in one go.
 *
 *  A pattern is a cycle of days, each either off or one of up to three shift
 *  "slots" (A, B, C — say day, evening and night). Filling a range walks the
 *  dates, finds each one's place in the cycle, and files the slot's saved
 *  shift on that day.
 *
 *  Pure logic, no Android — covered by plain JVM tests. The screen is in
 *  RotationDialog.kt.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One day of a cycle. */
enum class RotationSlot(val code: Char) {
    OFF('0'), A('1'), B('2'), C('3');

    companion object {
        fun of(code: Char): RotationSlot = entries.firstOrNull { it.code == code } ?: OFF

        /** The slots a shift can be assigned to, in order. */
        val Working: List<RotationSlot> = listOf(A, B, C)
    }
}

/**
 * A repeating cycle, day 1 first. Stored compactly as a string of slot codes
 * ("11110000" is four on, four off) so it saves across rotation easily.
 */
data class RotationPattern(
    val name: String,
    val code: String,
    /** One plain sentence on how the cycle runs — shown under a preset. */
    val description: String = ""
) {
    val days: List<RotationSlot> get() = code.map(RotationSlot::of)
    val length: Int get() = code.length
    val usesB: Boolean get() = RotationSlot.B.code in code

    /** The working slots that appear in the cycle, in slot order. */
    val slotsUsed: List<RotationSlot> get() = RotationSlot.Working.filter { it.code in code }

    companion object {
        const val MAX_LENGTH = 42

        val Presets = listOf(
            RotationPattern("4 on / 4 off", "11110000", "Four working days, then four days off."),
            RotationPattern("5 on / 2 off", "1111100", "A regular week: five working days, two off."),
            RotationPattern(
                "2-2-3 (Panama)", "11001110011000",
                "2 on, 2 off, 3 on — then the reverse. Every other weekend is free."
            ),
            RotationPattern("2 days, 2 nights, 4 off", "11220000", "Two day shifts, two night shifts, four days off."),
            RotationPattern(
                "Day, evening, night", "1122330000",
                "Two of each shift in turn, then four days off."
            ),
            RotationPattern(
                "DuPont", "2222000111022200011110000000",
                "4 weeks: nights and days in blocks, ending with a week off."
            ),
            RotationPattern("A/B weeks", "11111002222200", "One week on the first shift, the next on the second.")
        )
    }
}

/** What to do with a day that already has shifts for this job. */
enum class RotationConflict { SKIP, REPLACE, ADD }

/** The outcome of a fill, before it is applied — for the screen's summary. */
data class RotationPreview(val shiftsAdded: Int, val daysSkipped: Int, val daysReplaced: Int)

object RotationFill {

    /** The longest range one fill may cover, so a slip of the date picker can't write decades. */
    const val MAX_DAYS = 731

    /** The pattern slot that falls on [date] when day 1 of the cycle is [start]. */
    fun slotOn(pattern: RotationPattern, start: LocalDate, date: LocalDate): RotationSlot {
        if (pattern.length == 0) return RotationSlot.OFF
        val offset = Math.floorMod(ChronoUnit.DAYS.between(start, date), pattern.length.toLong()).toInt()
        return pattern.days[offset]
    }

    /**
     * Returns [all] with the pattern filled in between [start] and [until]
     * (inclusive) for [profileId]. Other jobs' shifts are never touched. A slot
     * with no shift assigned is treated as a day off.
     */
    fun apply(
        all: Map<LocalDate, List<Shift>>,
        profileId: String,
        pattern: RotationPattern,
        slotShifts: Map<RotationSlot, Shift>,
        start: LocalDate,
        until: LocalDate,
        conflict: RotationConflict
    ): Map<LocalDate, List<Shift>> {
        var result = all
        forEachDay(pattern, slotShifts, start, until) { date, shift ->
            val existing = ProfileScope.daySlice(result, date, profileId)
            val filed = shift.copy(jobId = profileId)
            val slice = when {
                existing.isEmpty() -> listOf(filed)
                conflict == RotationConflict.SKIP -> return@forEachDay
                conflict == RotationConflict.REPLACE -> listOf(filed)
                else -> existing + filed
            }
            result = ProfileScope.withDaySlice(result, date, profileId, slice)
        }
        return result
    }

    /** What [apply] would do, counted rather than done. */
    fun preview(
        all: Map<LocalDate, List<Shift>>,
        profileId: String,
        pattern: RotationPattern,
        slotShifts: Map<RotationSlot, Shift>,
        start: LocalDate,
        until: LocalDate,
        conflict: RotationConflict
    ): RotationPreview {
        var added = 0
        var skipped = 0
        var replaced = 0
        forEachDay(pattern, slotShifts, start, until) { date, _ ->
            val occupied = ProfileScope.daySlice(all, date, profileId).isNotEmpty()
            when {
                !occupied -> added++
                conflict == RotationConflict.SKIP -> skipped++
                conflict == RotationConflict.REPLACE -> { added++; replaced++ }
                else -> added++
            }
        }
        return RotationPreview(added, skipped, replaced)
    }

    private inline fun forEachDay(
        pattern: RotationPattern,
        slotShifts: Map<RotationSlot, Shift>,
        start: LocalDate,
        until: LocalDate,
        action: (LocalDate, Shift) -> Unit
    ) {
        if (until.isBefore(start) || pattern.length == 0) return
        val days = minOf(ChronoUnit.DAYS.between(start, until) + 1, MAX_DAYS.toLong())
        for (i in 0 until days) {
            val date = start.plusDays(i)
            val slot = slotOn(pattern, start, date)
            if (slot == RotationSlot.OFF) continue
            val shift = slotShifts[slot] ?: continue
            action(date, shift)
        }
    }
}
