package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CompensationTest {

    private fun shift(
        start: String = "09:00",
        end: String = "17:00",
        rate: Double = 0.0,
        multiplier: Double = 1.0,
        bonus: Double = 0.0,
        breakMinutes: Int = 0,
        type: DayType = DayType.WORKED,
        leaveHours: Double = 8.0
    ) = Shift(
        name = "S", startTime = start, endTime = end, colorValue = 0,
        hourlyRate = rate, payMultiplier = multiplier, bonus = bonus, breakMinutes = breakMinutes,
        dayType = type, paidLeaveHours = leaveHours, jobId = "job"
    )

    private fun month(ym: YearMonth) = DateRange(ym.atDay(1), ym.atEndOfMonth())
    private val october = month(YearMonth.of(2026, 10))
    private val september = month(YearMonth.of(2026, 9))

    private fun hourly(setup: PaySetup = PaySetup()) =
        JobProfile(id = "job", name = "Bar", colorValue = 0, pay = setup)

    private fun salaried(
        salary: Long = 130_000,
        rate: Double = 0.0,
        source: OvertimeSource = OvertimeSource.MARKED_SHIFTS,
        compensation: OvertimeCompensation = OvertimeCompensation.PAID,
        weekly: Double = 0.0,
        daily: Double = 0.0,
        payPeriod: PayPeriod = PayPeriod()
    ) = JobProfile(
        id = "job", name = "Office", colorValue = 0, payPeriod = payPeriod,
        pay = PaySetup(
            mode = PayMode.FIXED_MONTHLY,
            salaryHistory = listOf(SalaryRate(null, salary)),
            referenceHourlyRate = rate,
            contractHoursPerWeek = weekly,
            overtimeSource = source,
            dailyLimitHours = daily,
            overtimeCompensation = compensation
        )
    )

    /** Weekdays of [ym], each with [shift]. */
    private fun weekdays(ym: YearMonth, s: Shift = shift(), count: Int = Int.MAX_VALUE) =
        (1..ym.lengthOfMonth()).map { ym.atDay(it) }
            .filter { it.dayOfWeek.value <= 5 }
            .take(count)
            .associateWith { listOf(s) }

    // ══════════ Hourly: unchanged ══════════

    @Test
    fun `hourly with default rules is exactly the existing calculation`() {
        val shifts = mapOf(
            LocalDate.of(2026, 10, 1) to listOf(shift(rate = 12.35, breakMinutes = 30, bonus = 4.5)),
            LocalDate.of(2026, 10, 2) to listOf(shift(rate = 12.35, multiplier = 1.5), shift("18:00", "22:30", rate = 9.99)),
            LocalDate.of(2026, 10, 3) to listOf(shift("22:00", "06:00", rate = 13.0, multiplier = 1.75)),
            LocalDate.of(2026, 10, 4) to listOf(shift(rate = 11.0, type = DayType.PAID_LEAVE, leaveHours = 7.5)),
            LocalDate.of(2026, 10, 5) to listOf(shift(rate = 11.0, type = DayType.UNPAID_LEAVE, bonus = 50.0)),
            LocalDate.of(2026, 11, 1) to listOf(shift(rate = 99.0))
        )
        val old = PeriodTotals.of(shifts, october)
        val pay = Compensation.forRange(hourly(), shifts, october)
        assertEquals(Math.round(old.earnings * 100), pay.expectedCents)
        assertEquals(old.hours, pay.hoursWorked, 1e-9)
        assertEquals(old.shifts, pay.shiftsWorked)
        assertEquals(Compensation.totals(hourly(), shifts, october), old.copy(earnings = pay.expectedCents / 100.0))
        // Marked shifts are its overtime: 8h at 1.5× and 8h at 1.75×.
        assertEquals(16.0, pay.overtimeHours, 1e-9)
        assertEquals(450L, pay.extrasCents)
    }

    @Test
    fun `hourly overtime over a weekly limit is paid at the overtime multiplier`() {
        val week = (5..9).associate { LocalDate.of(2026, 10, it) to listOf(shift("08:00", "17:00", rate = 10.0)) }
        val pay = Compensation.forRange(
            hourly(PaySetup(overtimeSource = OvertimeSource.WEEKLY_LIMIT, contractHoursPerWeek = 40.0)),
            week, october
        )
        assertEquals(5.0, pay.overtimeHours, 1e-9)
        assertEquals(7_500L, pay.overtimeCents)
        assertEquals(40_000L, pay.baseCents)
        assertEquals(47_500L, pay.expectedCents)
    }

    @Test
    fun `hourly overtime banked as time off is not paid`() {
        val shifts = mapOf(
            LocalDate.of(2026, 10, 1) to listOf(shift(rate = 10.0)),
            LocalDate.of(2026, 10, 2) to listOf(shift(rate = 10.0, multiplier = 1.5))
        )
        val pay = Compensation.forRange(hourly(PaySetup(overtimeCompensation = OvertimeCompensation.TIME_OFF)), shifts, october)
        assertEquals(8_000L, pay.expectedCents)
        assertEquals(12.0, pay.timeOffHours, 1e-9)
    }

    // ══════════ Fixed salary ══════════

    @Test
    fun `a fixed salary is the same in a 20-day and a 23-day month`() {
        val feb = YearMonth.of(2027, 2)   // 20 weekdays
        val mar = YearMonth.of(2027, 3)   // 23 weekdays
        val oct = YearMonth.of(2026, 10)  // 22 weekdays
        val job = salaried()
        assertEquals(20, weekdays(feb).size)
        assertEquals(23, weekdays(mar).size)
        listOf(weekdays(feb) to month(feb), weekdays(oct) to month(oct), weekdays(mar) to month(mar))
            .forEach { (shifts, range) ->
                val pay = Compensation.forRange(job, shifts, range)
                assertEquals(130_000L, pay.baseCents)
                assertEquals(130_000L, pay.expectedCents)
            }
    }

    @Test
    fun `hours are tracked even though they don't change the salary`() {
        val pay = Compensation.forRange(salaried(), weekdays(YearMonth.of(2026, 10)), october)
        assertEquals(22, pay.shiftsWorked)
        assertEquals(176.0, pay.hoursWorked, 1e-9)
        assertEquals(130_000L, pay.expectedCents)
    }

    @Test
    fun `shift rates are never added on top of a salary`() {
        val shifts = weekdays(YearMonth.of(2026, 10), shift(rate = 25.0))
        assertEquals(130_000L, Compensation.forRange(salaried(), shifts, october).expectedCents)
    }

    @Test
    fun `absences are tracked but never deducted`() {
        val shifts = weekdays(YearMonth.of(2026, 10)) +
            (LocalDate.of(2026, 10, 31) to listOf(shift(type = DayType.UNPAID_LEAVE)))
        val pay = Compensation.forRange(salaried(), shifts, october)
        assertEquals(1, pay.unpaidLeaveDays)
        assertEquals(130_000L, pay.expectedCents)
    }

    @Test
    fun `marked overtime is paid on top at the reference rate`() {
        val shifts = mapOf(
            LocalDate.of(2026, 10, 1) to listOf(shift()),
            LocalDate.of(2026, 10, 3) to listOf(shift("10:00", "14:00", multiplier = 1.5))
        )
        val pay = Compensation.forRange(salaried(rate = 10.0), shifts, october)
        assertEquals(4.0, pay.overtimeHours, 1e-9)
        assertEquals(6_000L, pay.overtimeCents)
        assertEquals(136_000L, pay.expectedCents)
    }

    @Test
    fun `without a reference rate overtime is hours only and the salary still stands`() {
        val shifts = mapOf(LocalDate.of(2026, 10, 3) to listOf(shift(multiplier = 1.5)))
        val pay = Compensation.forRange(salaried(rate = 0.0), shifts, october)
        assertNull(pay.overtimeCents)
        assertTrue(pay.overtimeUnvalued)
        assertEquals(130_000L, pay.expectedCents)
    }

    @Test
    fun `weekly limit overtime`() {
        val week = (5..9).associate { LocalDate.of(2026, 10, it) to listOf(shift("08:00", "17:00")) }  // 45h
        val pay = Compensation.forRange(
            salaried(rate = 10.0, source = OvertimeSource.WEEKLY_LIMIT, weekly = 40.0), week, october
        )
        assertEquals(5.0, pay.overtimeHours, 1e-9)
        assertEquals(7_500L, pay.overtimeCents)
    }

    @Test
    fun `a week split across two periods is counted once`() {
        // Mon 28 Sep – Fri 2 Oct, 9h a day: 45h, the limit is crossed on the Friday.
        val week = (0L..4L).associate { LocalDate.of(2026, 9, 28).plusDays(it) to listOf(shift("08:00", "17:00")) }
        val job = salaried(rate = 10.0, source = OvertimeSource.WEEKLY_LIMIT, weekly = 40.0)
        val sep = Compensation.forRange(job, week, september, DayOfWeek.MONDAY)
        val oct = Compensation.forRange(job, week, october, DayOfWeek.MONDAY)
        assertEquals(0.0, sep.overtimeHours, 1e-9)
        assertEquals(5.0, oct.overtimeHours, 1e-9)
    }

    @Test
    fun `daily limit overtime`() {
        val shifts = mapOf(LocalDate.of(2026, 10, 5) to listOf(shift("08:00", "18:00")))
        val pay = Compensation.forRange(salaried(rate = 10.0, source = OvertimeSource.DAILY_LIMIT, daily = 8.0), shifts, october)
        assertEquals(2.0, pay.overtimeHours, 1e-9)
        assertEquals(3_000L, pay.overtimeCents)
    }

    @Test
    fun `overtime as time off or tracked only adds no money`() {
        val shifts = mapOf(LocalDate.of(2026, 10, 3) to listOf(shift("10:00", "14:00", multiplier = 1.5)))
        val off = Compensation.forRange(salaried(rate = 10.0, compensation = OvertimeCompensation.TIME_OFF), shifts, october)
        assertEquals(6.0, off.timeOffHours, 1e-9)
        assertEquals(130_000L, off.expectedCents)
        val tracked = Compensation.forRange(salaried(rate = 10.0, compensation = OvertimeCompensation.TRACK_ONLY), shifts, october)
        assertEquals(4.0, tracked.overtimeHours, 1e-9)
        assertEquals(130_000L, tracked.expectedCents)
    }

    @Test
    fun `shift extras are paid on top of a salary`() {
        val shifts = mapOf(LocalDate.of(2026, 10, 3) to listOf(shift(bonus = 12.5)))
        assertEquals(131_250L, Compensation.forRange(salaried(), shifts, october).expectedCents)
    }

    @Test
    fun `a raise applies from the month it was set for`() {
        val job = salaried().let { it.copy(pay = it.pay.withSalary(LocalDate.of(2026, 11, 1), 135_000)) }
        assertEquals(130_000L, Compensation.forRange(job, emptyMap(), october).baseCents)
        assertEquals(135_000L, Compensation.forRange(job, emptyMap(), month(YearMonth.of(2026, 11))).baseCents)
        val year = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        assertEquals(10 * 130_000L + 2 * 135_000L, Compensation.forRange(job, emptyMap(), year).baseCents)
    }

    @Test
    fun `a period before any salary was set says so`() {
        val job = salaried().copy(pay = PaySetup(mode = PayMode.FIXED_MONTHLY, salaryHistory = listOf(SalaryRate("2026-11-01", 135_000))))
        val pay = Compensation.forRange(job, emptyMap(), october)
        assertTrue(pay.salaryMissing)
        assertEquals(0L, pay.baseCents)
    }

    @Test
    fun `a pay period starting mid-month earns one salary`() {
        val job = salaried(payPeriod = PayPeriod(cycle = PayCycle.MONTHLY, startDay = 15))
        val period = job.payPeriod.periodContaining(LocalDate.of(2026, 10, 1))  // 15 Sep – 14 Oct
        assertEquals(130_000L, Compensation.forRange(job, emptyMap(), period).baseCents)
    }

    @Test
    fun `contract hours suggest a rate without dividing by hours worked`() {
        assertEquals(7.5, salaried(weekly = 40.0).pay.contractHourlyRate()!!, 1e-9)
        assertNull(salaried().pay.contractHourlyRate())
    }

    @Test
    fun `older profiles read back as hourly`() {
        val json = """{"id":"default","name":"My Job","colorValue":4282351070}"""
        val profile = StoreJson.decodeFromString<JobProfile>(json)
        assertEquals(PayMode.HOURLY, profile.pay.mode)
        assertEquals(OvertimeSource.MARKED_SHIFTS, profile.pay.overtimeSource)
        assertEquals(OvertimeCompensation.PAID, profile.pay.overtimeCompensation)
    }

    // ══════════ Statements ══════════

    private val today = LocalDate.of(2026, 11, 3)

    @Test
    fun `expected pay becomes salary, overtime and extras lines with their hours`() {
        val shifts = weekdays(YearMonth.of(2026, 10)) +
            (LocalDate.of(2026, 10, 3) to listOf(shift("10:00", "14:00", multiplier = 1.5, bonus = 20.0)))
        val job = salaried(rate = 10.0)
        val pay = Compensation.forRange(job, shifts, october)
        val lines = Statements.expectedEarnings(job, pay)
        assertEquals(listOf(EarningType.SALARY, EarningType.OVERTIME, EarningType.BONUS), lines.map { it.type })
        assertEquals(listOf(130_000L, 6_000L, 2_000L), lines.map { it.amountCents })
        assertEquals(176.0, lines[0].hours!!, 1e-9)  // 180h worked, 4h of it overtime
        assertEquals(4.0, lines[1].hours!!, 1e-9)
        assertEquals(pay.expectedCents, lines.sumOf { it.amountCents })
    }

    @Test
    fun `hourly expected pay is a shift pay line`() {
        val shifts = mapOf(LocalDate.of(2026, 10, 1) to listOf(shift(rate = 10.0)))
        val lines = Statements.expectedEarnings(hourly(), Compensation.forRange(hourly(), shifts, october))
        assertEquals(listOf(EarningType.SHIFT_PAY), lines.map { it.type })
        assertEquals(8_000L, lines.single().amountCents)
    }

    @Test
    fun `statements compare hours worked with hours paid, and stop offering once added`() {
        val job = salaried()
        val shifts = weekdays(YearMonth.of(2026, 10))   // 176h
        val before = Statements.build(job, shifts, emptyList(), today).first { it.range == october }
        assertTrue(before.canAddExpected)
        assertNull(before.hoursUnpaid)

        val salary = Earning(id = 1, jobId = "job", type = EarningType.SALARY, amountCents = 130_000,
            periodStart = "2026-10-01", periodEnd = "2026-10-31", hours = 176.0)
        val paid = Payment(id = 1, earningId = 1, amountCents = 127_500, date = "2026-11-01", hoursPaid = 172.0)
        val balances = Ledger.balances(LedgerData(listOf(salary), listOf(paid)))
        val after = Statements.build(job, shifts, balances, today).first { it.range == october }
        assertFalse(after.canAddExpected)
        assertEquals(176.0, after.hoursDue!!, 1e-9)
        assertEquals(172.0, after.hoursPaid!!, 1e-9)
        assertEquals(4.0, after.hoursUnpaid!!, 1e-9)
        assertEquals(-2_500L, after.recorded!!.totals.differenceCents)
    }

    @Test
    fun `a salaried job lists recent months even without logged shifts`() {
        val ranges = Statements.build(salaried(), emptyMap(), emptyList(), today).map { it.range }
        assertEquals(
            listOf(month(YearMonth.of(2026, 11)), october, september),
            ranges
        )
    }
}
