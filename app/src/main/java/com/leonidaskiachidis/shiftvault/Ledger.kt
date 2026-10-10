package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Ledger — money earned, and money actually received.
 *
 *  An Earning is something the user is owed: a month's salary, the overtime
 *  in it, a run of extra shifts, a bonus. A Payment is money that arrived,
 *  and it always belongs to exactly one earning. Nothing is ever allocated
 *  automatically: being paid the salary for October says nothing about the
 *  extra shifts worked in October, which stay owed until a payment is
 *  recorded against them.
 *
 *  Whether an earning is paid, and every balance, is *derived* from its
 *  payments here — never stored — so a status can't drift from the money.
 *  Amounts are whole cents (minor units), so totals are exact.
 *
 *  Pure Kotlin, no Android — covered by plain JVM tests.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

@Serializable
enum class EarningType(val label: String) {
    SALARY("Salary"),
    SHIFT_PAY("Shift pay"),
    OVERTIME("Overtime"),
    EXTRA_SHIFT("Extra shifts"),
    BONUS("Bonus"),
    TIPS("Tips"),
    OTHER("Other work")
}

/** Money the user is owed for work done in [periodStart]..[periodEnd] (ISO dates). */
@Serializable
data class Earning(
    val id: Long = 0,
    val jobId: String,
    val type: EarningType,
    val description: String = "",
    val amountCents: Long,
    val periodStart: String,
    val periodEnd: String,
    /** Epoch millis; orders earnings of the same period. */
    val createdAt: Long = 0,
    /** Hours this amount is for, when known (e.g. 176h of a month's salary). */
    val hours: Double? = null,
    /**
     * The user accepted being paid less than [amountCents]: what is missing
     * no longer counts as owed, and the shortfall stays visible.
     */
    val shortfallAccepted: Boolean = false
) {
    val period: DateRange get() = DateRange(LocalDate.parse(periodStart), LocalDate.parse(periodEnd))

    /** What the user called it, or the type when they didn't. */
    val title: String get() = description.trim().ifBlank { type.label }
}

/** Money received on [date] (ISO) towards one earning. */
@Serializable
data class Payment(
    val id: Long = 0,
    val earningId: Long,
    val amountCents: Long,
    val date: String,
    val note: String = "",
    val createdAt: Long = 0,
    /** Hours of work this payment covers, as the employer paid them — not the hours worked. */
    val hoursPaid: Double? = null
) {
    val day: LocalDate get() = LocalDate.parse(date)
}

enum class PaymentStatus(val label: String) {
    UNPAID("Unpaid"),
    PARTIAL("Partly paid"),
    PAID("Paid"),
    /** Closed with less received than expected, by the user's choice. */
    SETTLED("Settled short")
}

/** One earning with the payments made against it, and what that adds up to. */
data class EarningBalance(val earning: Earning, val payments: List<Payment>) {
    val paidCents: Long = payments.sumOf { it.amountCents }

    /** Received minus expected: negative while short, positive when overpaid. */
    val differenceCents: Long = paidCents - earning.amountCents

    val status: PaymentStatus = when {
        paidCents >= earning.amountCents -> PaymentStatus.PAID
        earning.shortfallAccepted -> PaymentStatus.SETTLED
        paidCents <= 0L -> PaymentStatus.UNPAID
        else -> PaymentStatus.PARTIAL
    }

    /** Nothing more is expected: paid in full (or over), or a shortfall was accepted. */
    val isClosed: Boolean get() = status == PaymentStatus.PAID || status == PaymentStatus.SETTLED

    /** Still to come. Zero once closed, whatever the difference. */
    val outstandingCents: Long = if (isClosed) 0L else earning.amountCents - paidCents

    /** Hours the payments say they covered; null until any payment records hours. */
    val hoursPaid: Double? = payments.mapNotNull { it.hoursPaid }.takeIf { it.isNotEmpty() }?.sum()

    /** Share received, 0..1, for progress bars. */
    val paidFraction: Float
        get() = if (earning.amountCents <= 0L) 1f
        else (paidCents.toDouble() / earning.amountCents).coerceIn(0.0, 1.0).toFloat()
}

/** Earned, received and still owed across some set of earnings. */
data class LedgerTotals(
    val earnedCents: Long,
    val receivedCents: Long,
    val outstandingCents: Long,
    /** Hours the earnings are for, where recorded. */
    val hoursExpected: Double? = null,
    /** Hours the payments covered, where recorded. */
    val hoursPaid: Double? = null
) {
    /** Received minus expected, across the lot. */
    val differenceCents: Long get() = receivedCents - earnedCents

    val receivedFraction: Float
        get() = if (earnedCents <= 0L) 0f
        else (receivedCents.toDouble() / earnedCents).coerceIn(0.0, 1.0).toFloat()

    companion object {
        val Zero = LedgerTotals(0, 0, 0)

        fun of(balances: List<EarningBalance>) = LedgerTotals(
            earnedCents = balances.sumOf { it.earning.amountCents },
            receivedCents = balances.sumOf { it.paidCents },
            outstandingCents = balances.sumOf { it.outstandingCents },
            hoursExpected = balances.mapNotNull { it.earning.hours }.takeIf { it.isNotEmpty() }?.sum(),
            hoursPaid = balances.mapNotNull { it.hoursPaid }.takeIf { it.isNotEmpty() }?.sum()
        )
    }
}

/** The earnings of one group (a work period, a month, a type) and their totals. */
data class LedgerGroup<K>(val key: K, val balances: List<EarningBalance>) {
    val totals: LedgerTotals = LedgerTotals.of(balances)

    /** Closed only when everything in it is; unpaid only when nothing in it has been touched. */
    val status: PaymentStatus = when {
        balances.all { it.isClosed } ->
            if (balances.any { it.status == PaymentStatus.SETTLED }) PaymentStatus.SETTLED else PaymentStatus.PAID
        balances.all { it.status == PaymentStatus.UNPAID } -> PaymentStatus.UNPAID
        else -> PaymentStatus.PARTIAL
    }
}

/** A payment in the history, with the earning it went towards. */
data class PaymentEntry(val payment: Payment, val earning: Earning)

enum class LedgerFilter(val label: String) { ALL("All"), OWED("Owed"), PAID("Paid") }

/** Everything stored, before it is joined up. */
data class LedgerData(val earnings: List<Earning>, val payments: List<Payment>) {
    fun forJob(jobId: String): LedgerData {
        val mine = earnings.filter { it.jobId == jobId }
        val ids = mine.mapTo(HashSet()) { it.id }
        return LedgerData(mine, payments.filter { it.earningId in ids })
    }

    companion object {
        val Empty = LedgerData(emptyList(), emptyList())
    }
}

object Ledger {

    /**
     * Each earning with its own payments — matched by id and nothing else.
     * Newest work first; payments oldest first, as a history reads.
     */
    fun balances(data: LedgerData): List<EarningBalance> {
        val byEarning = data.payments.groupBy { it.earningId }
        return data.earnings
            .sortedWith(compareByDescending<Earning> { it.periodEnd }.thenByDescending { it.createdAt })
            .map { e ->
                EarningBalance(
                    e,
                    byEarning[e.id].orEmpty().sortedWith(compareBy<Payment> { it.date }.thenBy { it.createdAt })
                )
            }
    }

    /** Grouped by work period, most recent first. */
    fun byPeriod(balances: List<EarningBalance>): List<LedgerGroup<DateRange>> =
        balances.groupBy { it.earning.period }
            .map { (period, list) -> LedgerGroup(period, list) }
            .sortedWith(compareByDescending<LedgerGroup<DateRange>> { it.key.end }.thenByDescending { it.key.start })

    /**
     * Grouped by the month the work was done in — the month a period ends in,
     * which is the one a payslip is usually named after. Most recent first.
     */
    fun byMonth(balances: List<EarningBalance>): List<LedgerGroup<YearMonth>> =
        balances.groupBy { YearMonth.from(it.earning.period.end) }
            .map { (month, list) -> LedgerGroup(month, list) }
            .sortedByDescending { it.key }

    /** Grouped by type, largest earned first. */
    fun byType(balances: List<EarningBalance>): List<LedgerGroup<EarningType>> =
        balances.groupBy { it.earning.type }
            .map { (type, list) -> LedgerGroup(type, list) }
            .sortedByDescending { it.totals.earnedCents }

    /** Every payment received, newest first. */
    fun history(balances: List<EarningBalance>): List<PaymentEntry> =
        balances.flatMap { b -> b.payments.map { PaymentEntry(it, b.earning) } }
            .sortedWith(
                compareByDescending<PaymentEntry> { it.payment.date }
                    .thenByDescending { it.payment.createdAt }
            )

    fun filter(balances: List<EarningBalance>, filter: LedgerFilter, period: DateRange? = null): List<EarningBalance> =
        balances.filter { b ->
            (period == null || b.earning.period == period) && when (filter) {
                LedgerFilter.ALL -> true
                LedgerFilter.OWED -> !b.isClosed
                LedgerFilter.PAID -> b.isClosed
            }
        }
}

// ══════════════════════════════════════════════════════════════
//  Validation
// ══════════════════════════════════════════════════════════════

/**
 * What may be stored. Each returns a message for the user, or null when the
 * input is fine. The same checks run again inside the database transaction
 * that writes, so a stale screen can't slip an invalid value past them.
 */
object LedgerRules {
    /** Ten billion in any currency — far past any earning, short of Long overflow. */
    const val MAX_CENTS = 1_000_000_000_000L
    /** More hours than any one earning or payment could cover. */
    const val MAX_HOURS = 10_000.0

    /**
     * An earning's amount can be set below what was already received: that is
     * an overpayment, and is shown as one rather than refused.
     */
    fun earningError(amountCents: Long?, start: LocalDate?, end: LocalDate?, hours: Double? = null): String? = when {
        amountCents == null -> "Enter an amount"
        amountCents <= 0L -> "The amount must be more than zero"
        amountCents > MAX_CENTS -> "That amount is too large"
        start == null || end == null -> "Choose the period this was earned in"
        start.isAfter(end) -> "The period can't end before it starts"
        hours != null && (hours < 0.0 || hours > MAX_HOURS) -> "Hours must be between 0 and ${MAX_HOURS.toInt()}"
        else -> null
    }

    /**
     * Employers pay what they pay: a payment may be less than what is owed
     * (the rest stays owed) or more (shown as overpaid). Only an earning that
     * is already closed takes no further payments.
     */
    fun paymentError(
        amountCents: Long?,
        outstandingCents: Long,
        date: LocalDate?,
        today: LocalDate,
        hoursPaid: Double? = null
    ): String? = when {
        outstandingCents <= 0L -> "This is already settled"
        amountCents == null -> "Enter an amount"
        amountCents <= 0L -> "The amount must be more than zero"
        amountCents > MAX_CENTS -> "That amount is too large"
        hoursPaid != null && (hoursPaid < 0.0 || hoursPaid > MAX_HOURS) ->
            "Hours paid must be between 0 and ${MAX_HOURS.toInt()}"
        date == null -> "Choose the date it arrived"
        date.isAfter(today) -> "A payment can't be dated in the future"
        else -> null
    }
}

// ══════════════════════════════════════════════════════════════
//  Period statements
//
//  For each pay period: what the app calculates it should pay (Compensation),
//  what has been put in the ledger for it, and what arrived — money and hours
//  side by side. The calculation is only ever a suggestion: nothing reaches
//  the ledger until the user adds it.
// ══════════════════════════════════════════════════════════════

/** The earning types that carry a period's main pay. */
val BasePayTypes = setOf(EarningType.SALARY, EarningType.SHIFT_PAY)

data class PeriodStatement(
    val range: DateRange,
    /** The app's calculation; null for a ledger period that isn't one of the job's pay periods. */
    val calculated: PeriodPay?,
    /** What was recorded in the ledger for exactly this period. */
    val recorded: LedgerGroup<DateRange>?
) {
    /** Hours due pay: worked plus paid leave. */
    val hoursDue: Double? get() = calculated?.let { it.hoursWorked + it.paidLeaveHours }
    val hoursPaid: Double? get() = recorded?.totals?.hoursPaid

    /** Due but not covered by any payment; null until a payment records its hours. */
    val hoursUnpaid: Double?
        get() {
            val paid = hoursPaid ?: return null
            val due = hoursDue ?: return null
            return (due - paid).coerceAtLeast(0.0)
        }

    /** The period's main pay hasn't been added to the ledger yet. */
    val canAddExpected: Boolean
        get() = calculated != null && calculated.expectedCents > 0L &&
            recorded?.balances?.none { it.earning.type in BasePayTypes } != false
}

object Statements {

    /**
     * Statements newest first: every pay period with work in the last
     * [monthsBack] months, the current one, every period that has ledger
     * entries, and — for a salary, which is due with or without logged
     * shifts — the two before the current one.
     */
    fun build(
        profile: JobProfile,
        shifts: Map<LocalDate, List<Shift>>,
        balances: List<EarningBalance>,
        today: LocalDate,
        weekStart: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY,
        monthsBack: Long = 24
    ): List<PeriodStatement> {
        val pay = profile.payPeriod
        val current = pay.periodContaining(today)
        val ranges = LinkedHashSet<DateRange>()
        ranges += current
        if (profile.pay.isFixed) {
            var p = current
            repeat(2) { p = pay.previous(p); ranges += p }
        }
        val earliest = today.minusMonths(monthsBack)
        shifts.keys
            .filter { !it.isBefore(earliest) && !it.isAfter(current.end) }
            .forEach { ranges += pay.periodContaining(it) }
        val recorded = Ledger.byPeriod(balances).associateBy { it.key }
        ranges += recorded.keys

        return ranges.map { range ->
            val isPayPeriod = pay.periodContaining(range.start) == range
            PeriodStatement(
                range = range,
                calculated = if (isPayPeriod) Compensation.forRange(profile, shifts, range, weekStart) else null,
                recorded = recorded[range]
            )
        }.sortedWith(compareByDescending<PeriodStatement> { it.range.end }.thenByDescending { it.range.start })
    }

    /**
     * The ledger lines for a period's calculated pay: the base (salary or shift
     * pay), overtime when it is paid in money, and shift extras. Each carries
     * the hours it is for, so payments can later be checked against them.
     */
    fun expectedEarnings(profile: JobProfile, pay: PeriodPay): List<Earning> {
        val overtimeLine = pay.compensation == OvertimeCompensation.PAID && (pay.overtimeCents ?: 0L) > 0L
        val baseHours = pay.hoursWorked + pay.paidLeaveHours - if (overtimeLine) pay.overtimeHours else 0.0
        val start = pay.range.start.toString()
        val end = pay.range.end.toString()
        return buildList {
            if (pay.baseCents > 0L) add(
                Earning(
                    jobId = profile.id,
                    type = if (pay.mode == PayMode.FIXED_MONTHLY) EarningType.SALARY else EarningType.SHIFT_PAY,
                    amountCents = pay.baseCents,
                    periodStart = start, periodEnd = end,
                    hours = baseHours.takeIf { it > 0.0 }
                )
            )
            if (overtimeLine) add(
                Earning(
                    jobId = profile.id, type = EarningType.OVERTIME,
                    amountCents = pay.overtimeCents!!,
                    periodStart = start, periodEnd = end,
                    hours = pay.overtimeHours
                )
            )
            if (pay.extrasCents > 0L) add(
                Earning(
                    jobId = profile.id, type = EarningType.BONUS,
                    description = "Bonuses & tips from shifts",
                    amountCents = pay.extrasCents,
                    periodStart = start, periodEnd = end
                )
            )
        }
    }
}

/** Typed hours ("7,5" or "7.5"); null when blank or not a non-negative number. */
fun parseHours(text: String): Double? = parseDecimal(text)

/** "+€20.00" / "−€25.00" / "€0.00" — a difference, signed. */
fun signedCents(cents: Long, symbol: String): String = when {
    cents > 0L -> "+" + formatCents(cents, symbol)
    cents < 0L -> "−" + formatCents(-cents, symbol)
    else -> formatCents(0, symbol)
}

// ══════════════════════════════════════════════════════════════
//  Money in cents
// ══════════════════════════════════════════════════════════════

/**
 * Reads typed money into whole cents: "12,5" and "12.50" are both 1250 (a
 * decimal comma is what Greek and most European keyboards type). More than two
 * decimals round half-up. Null for anything that isn't a non-negative number.
 */
fun parseCents(text: String): Long? {
    val value = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (value.signum() < 0) return null
    return runCatching {
        value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    }.getOrNull()
}

/** 1250 → "12.50", for putting an amount back into a text field. */
fun centsToInput(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

/** 123456 → "€1,234.56". Exact: formatted from the decimal, never from a double. */
fun formatCents(cents: Long, symbol: String = ""): String =
    symbol + "%,.2f".format(BigDecimal.valueOf(cents, 2))
