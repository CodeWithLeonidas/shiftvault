package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class LedgerTest {

    private val oct = "2026-10-01" to "2026-10-31"
    private val sep = "2026-09-01" to "2026-09-30"

    private fun earning(
        id: Long,
        cents: Long,
        type: EarningType = EarningType.SALARY,
        period: Pair<String, String> = oct,
        jobId: String = "job",
        description: String = ""
    ) = Earning(
        id = id, jobId = jobId, type = type, description = description, amountCents = cents,
        periodStart = period.first, periodEnd = period.second, createdAt = id
    )

    private fun payment(id: Long, earningId: Long, cents: Long, date: String = "2026-11-01") =
        Payment(id = id, earningId = earningId, amountCents = cents, date = date, createdAt = id)

    private fun balances(earnings: List<Earning>, payments: List<Payment> = emptyList()) =
        Ledger.balances(LedgerData(earnings, payments))

    // ══════════ Earnings and payments stay separate ══════════

    @Test
    fun `salary paid leaves the extra shifts of the same month owed`() {
        val salary = earning(1, 150_000, EarningType.SALARY)
        val extras = earning(2, 24_000, EarningType.EXTRA_SHIFT)
        val list = balances(listOf(salary, extras), listOf(payment(1, earningId = 1, cents = 150_000)))

        val bySalary = list.first { it.earning.id == 1L }
        val byExtras = list.first { it.earning.id == 2L }
        assertEquals(PaymentStatus.PAID, bySalary.status)
        assertEquals(PaymentStatus.UNPAID, byExtras.status)
        assertEquals(24_000, byExtras.outstandingCents)

        val october = Ledger.byPeriod(list).single()
        assertEquals(PaymentStatus.PARTIAL, october.status)
        assertEquals(LedgerTotals(174_000, 150_000, 24_000), october.totals)
    }

    @Test
    fun `several partial payments keep an exact running balance`() {
        val e = earning(1, 100_000)
        val steps = listOf(
            payment(1, 1, 33_333, "2026-11-01"),
            payment(2, 1, 33_333, "2026-11-08"),
        )
        val partial = balances(listOf(e), steps).single()
        assertEquals(PaymentStatus.PARTIAL, partial.status)
        assertEquals(66_666, partial.paidCents)
        assertEquals(33_334, partial.outstandingCents)

        val full = balances(listOf(e), steps + payment(3, 1, 33_334, "2026-11-15")).single()
        assertEquals(PaymentStatus.PAID, full.status)
        assertEquals(0, full.outstandingCents)
        assertEquals(listOf("2026-11-01", "2026-11-08", "2026-11-15"), full.payments.map { it.date })
    }

    @Test
    fun `payments only ever count towards their own earning`() {
        val list = balances(
            listOf(earning(1, 10_000), earning(2, 10_000)),
            listOf(payment(1, earningId = 2, cents = 4_000), payment(2, earningId = 99, cents = 9_999))
        )
        assertEquals(0, list.first { it.earning.id == 1L }.paidCents)
        assertEquals(4_000, list.first { it.earning.id == 2L }.paidCents)
        // A payment whose earning is gone counts for nothing.
        assertEquals(4_000, LedgerTotals.of(list).receivedCents)
    }

    @Test
    fun `totals add up`() {
        val list = balances(
            listOf(earning(1, 120_000), earning(2, 30_000, EarningType.OVERTIME), earning(3, 5_000, EarningType.TIPS)),
            listOf(payment(1, 1, 120_000), payment(2, 2, 10_000))
        )
        val t = LedgerTotals.of(list)
        assertEquals(155_000, t.earnedCents)
        assertEquals(130_000, t.receivedCents)
        assertEquals(25_000, t.outstandingCents)
        assertEquals(t.earnedCents, t.receivedCents + t.outstandingCents)
    }

    // ══════════ Groupings ══════════

    @Test
    fun `periods are listed newest first with their own status`() {
        val list = balances(
            listOf(earning(1, 100_000, period = sep), earning(2, 100_000, period = oct)),
            listOf(payment(1, 1, 100_000))
        )
        val periods = Ledger.byPeriod(list)
        assertEquals(listOf("2026-10-31", "2026-09-30"), periods.map { it.key.end.toString() })
        assertEquals(listOf(PaymentStatus.UNPAID, PaymentStatus.PAID), periods.map { it.status })
    }

    @Test
    fun `months and types break the totals down`() {
        val list = balances(
            listOf(
                earning(1, 100_000, EarningType.SALARY, sep),
                earning(2, 100_000, EarningType.SALARY, oct),
                earning(3, 20_000, EarningType.OVERTIME, oct)
            ),
            listOf(payment(1, 1, 100_000), payment(2, 3, 5_000))
        )
        val months = Ledger.byMonth(list)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.key })
        assertEquals(LedgerTotals(120_000, 5_000, 115_000), months.first().totals)

        val types = Ledger.byType(list)
        assertEquals(EarningType.SALARY, types.first().key)
        assertEquals(LedgerTotals(200_000, 100_000, 100_000), types.first().totals)
        assertEquals(LedgerTotals(20_000, 5_000, 15_000), types.last().totals)
    }

    @Test
    fun `history lists payments newest first with what they paid`() {
        val list = balances(
            listOf(earning(1, 100_000, description = "October salary"), earning(2, 9_000, EarningType.BONUS)),
            listOf(payment(1, 1, 50_000, "2026-11-01"), payment(2, 2, 9_000, "2026-11-20"), payment(3, 1, 50_000, "2026-11-10"))
        )
        val history = Ledger.history(list)
        assertEquals(listOf("2026-11-20", "2026-11-10", "2026-11-01"), history.map { it.payment.date })
        assertEquals("Bonus", history.first().earning.title)
        assertEquals("October salary", history.last().earning.title)
    }

    @Test
    fun `filters pick owed or paid, optionally within one period`() {
        val list = balances(
            listOf(earning(1, 100, period = sep), earning(2, 100), earning(3, 100)),
            listOf(payment(1, 1, 100), payment(2, 2, 40))
        )
        assertEquals(setOf(2L, 3L), Ledger.filter(list, LedgerFilter.OWED).map { it.earning.id }.toSet())
        assertEquals(listOf(1L), Ledger.filter(list, LedgerFilter.PAID).map { it.earning.id })
        val october = DateRange(LocalDate.parse(oct.first), LocalDate.parse(oct.second))
        assertEquals(setOf(2L, 3L), Ledger.filter(list, LedgerFilter.ALL, october).map { it.earning.id }.toSet())
    }

    @Test
    fun `a job sees only its own ledger`() {
        val data = LedgerData(
            listOf(earning(1, 100, jobId = "a"), earning(2, 100, jobId = "b")),
            listOf(payment(1, 1, 50), payment(2, 2, 50))
        ).forJob("a")
        assertEquals(listOf(1L), data.earnings.map { it.id })
        assertEquals(listOf(1L), data.payments.map { it.earningId })
    }

    // ══════════ Validation ══════════

    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun `earnings need an amount above zero and a sensible period`() {
        val start = LocalDate.of(2026, 10, 1)
        val end = LocalDate.of(2026, 10, 31)
        assertNull(LedgerRules.earningError(1, start, end))
        assertNotNull(LedgerRules.earningError(null, start, end))
        assertNotNull(LedgerRules.earningError(0, start, end))
        assertNotNull(LedgerRules.earningError(100, end, start))
        assertNotNull(LedgerRules.earningError(100, null, end))
    }

    @Test
    fun `earning hours must be sensible`() {
        val start = LocalDate.of(2026, 10, 1)
        assertNull(LedgerRules.earningError(100, start, start, hours = 176.0))
        assertNull(LedgerRules.earningError(100, start, start, hours = null))
        assertNotNull(LedgerRules.earningError(100, start, start, hours = -1.0))
    }

    @Test
    fun `a payment may differ from what is owed, but not be in the future or on a settled earning`() {
        assertNull(LedgerRules.paymentError(2_500, outstandingCents = 2_500, date = today, today = today))
        // Employers pay what they pay: over and under are both recorded.
        assertNull(LedgerRules.paymentError(2_700, 2_500, today, today))
        assertNull(LedgerRules.paymentError(2_000, 2_500, today, today))
        assertNotNull(LedgerRules.paymentError(0, 2_500, today, today))
        assertNotNull(LedgerRules.paymentError(100, 2_500, today.plusDays(1), today))
        assertNotNull(LedgerRules.paymentError(100, 2_500, today, today, hoursPaid = -2.0))
        assertTrue(LedgerRules.paymentError(100, 0, today, today)!!.contains("settled"))
    }

    // ══════════ Differences and hours paid ══════════

    @Test
    fun `a short payment leaves the difference owed until it is accepted`() {
        val salary = earning(1, 130_000)
        val short = payment(1, 1, 127_500)
        val open = balances(listOf(salary), listOf(short)).single()
        assertEquals(PaymentStatus.PARTIAL, open.status)
        assertEquals(-2_500L, open.differenceCents)
        assertEquals(2_500L, open.outstandingCents)

        val accepted = balances(listOf(salary.copy(shortfallAccepted = true)), listOf(short)).single()
        assertEquals(PaymentStatus.SETTLED, accepted.status)
        assertEquals(0L, accepted.outstandingCents)
        assertEquals(-2_500L, accepted.differenceCents)
        assertEquals(listOf(accepted), Ledger.filter(listOf(accepted), LedgerFilter.PAID))
        assertEquals(PaymentStatus.SETTLED, Ledger.byPeriod(listOf(accepted)).single().status)
    }

    @Test
    fun `an overpayment is paid in full and shows the extra`() {
        val b = balances(listOf(earning(1, 130_000)), listOf(payment(1, 1, 132_000))).single()
        assertEquals(PaymentStatus.PAID, b.status)
        assertEquals(2_000L, b.differenceCents)
        assertEquals(0L, b.outstandingCents)
    }

    @Test
    fun `hours paid add up separately from hours worked`() {
        val e = earning(1, 130_000).copy(hours = 176.0)
        val list = balances(
            listOf(e),
            listOf(
                payment(1, 1, 100_000).copy(hoursPaid = 130.0),
                payment(2, 1, 27_500).copy(hoursPaid = 42.0),
                payment(3, 1, 100)
            )
        )
        assertEquals(172.0, list.single().hoursPaid!!, 1e-9)
        val totals = LedgerTotals.of(list)
        assertEquals(176.0, totals.hoursExpected!!, 1e-9)
        assertEquals(172.0, totals.hoursPaid!!, 1e-9)
        assertNull(LedgerTotals.of(balances(listOf(earning(2, 100)))).hoursPaid)
    }

    // ══════════ Money ══════════

    @Test
    fun `typed money becomes exact cents`() {
        assertEquals(1250L, parseCents("12,5"))
        assertEquals(1250L, parseCents("12.50"))
        assertEquals(1235L, parseCents("12.345"))
        assertEquals(30L, parseCents("0.3"))
        assertEquals(1_000_000L, parseCents(" 10000 "))
        assertNull(parseCents("-5"))
        assertNull(parseCents("abc"))
        assertNull(parseCents(""))
    }

    @Test
    fun `cents format and round-trip exactly`() {
        assertEquals("€1,234.56", formatCents(123_456, "€"))
        assertEquals("0.07", formatCents(7))
        assertEquals("12.50", centsToInput(1250))
        assertEquals(1250L, parseCents(centsToInput(1250)))
    }

    // ══════════ Storage ══════════

    @Test
    fun `earnings and payments survive the database mapping`() {
        val e = earning(7, 12_345, EarningType.OVERTIME, description = "Weekend cover")
            .copy(hours = 6.5, shortfallAccepted = true)
        assertEquals(e, e.toEntity().toEarning())
        val p = Payment(3, 7, 2_000, "2026-11-02", "Bank transfer", 42, hoursPaid = 6.0)
        assertEquals(p, p.toEntity().toPayment())
    }

    @Test
    fun `an unknown stored type reads back as other work`() {
        val row = earning(1, 100).toEntity().copy(type = "SOMETHING_NEW")
        assertEquals(EarningType.OTHER, row.toEarning().type)
    }

    @Test
    fun `backups carry the ledger, and old backups are marked as not having one`() {
        val data = BackupData(
            ledgerIncluded = true,
            earnings = listOf(earning(1, 5_000)),
            payments = listOf(payment(1, 1, 2_000))
        )
        val back = BackupHelper.deserialize(BackupHelper.serialize(data))!!
        assertEquals(data.earnings, back.earnings)
        assertEquals(data.payments, back.payments)
        assertTrue(back.ledgerIncluded)

        val old = BackupHelper.deserialize("""{"version":1,"shifts":{},"templates":[]}""")!!
        assertEquals(false, old.ledgerIncluded)
        assertTrue(old.earnings.isEmpty())
    }

    @Test
    fun `a backup with an unreadable ledger date is refused`() {
        val bad = BackupHelper.serialize(BackupData(ledgerIncluded = true, earnings = listOf(earning(1, 100, period = "2026-13-01" to "x"))))
        assertNull(BackupHelper.deserialize(bad))
    }
}
