package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  InsightsPane — the Wallet's "Insights" tab (formerly the Analytics screen).
 *
 *  Header: big title + Export menu (PDF / Excel), a period pill, then tabs.
 *    Overview — three stat cards with period-over-period deltas, hours bar
 *               chart, shift-distribution donut, earnings trend, insights,
 *               rest / day-off tiles
 *    Shifts   — per-template breakdown rows (tap for the detail sheet)
 *    Earnings — total plus earnings per shift type with share bars
 *    Trends   — earnings over time, bucketed by day or week
 *
 *  Charts are plain Canvas / layout draws, no chart dependency.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

enum class StatsMode { PAY_PERIOD, MONTHLY, YEARLY, CUSTOM }

private enum class StatsTab(val label: String) {
    OVERVIEW("Overview"), SHIFTS("Shifts"), EARNINGS("Earnings"), TRENDS("Trends")
}

private enum class Bucket(val label: String) { DAY("By day"), WEEK("By week") }

private val entryDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private val shortDateFmt = DateTimeFormatter.ofPattern("MMM d")
private val longDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val fullDateFmt = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")

private const val FallbackSlate = 0xFF9AA1AC

// ══════════════════════════════════════════════════════════════
//  Screen
// ══════════════════════════════════════════════════════════════

/**
 * The shift analytics — hours, earnings worked out from shifts, charts and
 * insights — as the Insights tab of the Wallet. The Wallet screen supplies the
 * title and the job chip; this pane brings its own period picker and export.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun InsightsPane(
    /**
     * Already narrowed to [activeProfile] by the caller, so every figure on this
     * screen — totals, deltas, charts, exports — is that job's and only that
     * job's. Grouping the *unfiltered* map by jobId is what a future
     * "compare my jobs" view would do instead.
     */
    savedShifts: Map<LocalDate, List<Shift>>,
    currencyCode: String = "EUR",
    /** Opens the export sheet, offering the period currently on show first. */
    onExport: (ExportPeriod) -> Unit = {},
    activeProfile: JobProfile = JobProfile.Default,
    /** The calendar's first day, for weekly overtime. */
    weekStart: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY,
    /** When given, the period and Export go to the host's header instead of the list. */
    header: InsightsHeaderState? = null,
    modifier: Modifier = Modifier
) {
    // Saveable (java.time types are Serializable), so the period the user
    // picked survives rotation and a trip to another tab.
    // Opens on the pay period: for a job paid by calendar month that is simply
    // this month, and for everyone else it is the figure that matters.
    var mode by rememberSaveable { mutableStateOf(StatsMode.PAY_PERIOD) }
    var tab by rememberSaveable { mutableStateOf(StatsTab.OVERVIEW) }
    var bucket by rememberSaveable { mutableStateOf(Bucket.WEEK) }

    var selectedMonth by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var selectedYear by rememberSaveable { mutableStateOf(java.time.Year.now()) }
    var startDate by rememberSaveable { mutableStateOf(LocalDate.now().minusWeeks(1)) }
    var endDate by rememberSaveable { mutableStateOf(LocalDate.now()) }
    // Any day inside the pay period on show; stepping moves it a period at a time.
    var payAnchor by rememberSaveable { mutableStateOf(LocalDate.now()) }
    val payPeriod = activeProfile.payPeriod
    val payRange = remember(payPeriod, payAnchor) { payPeriod.periodContaining(payAnchor) }

    var showPeriodSheet by rememberSaveable { mutableStateOf(false) }
    var showRangePicker by rememberSaveable { mutableStateOf(false) }
    var selectedBreakdown by rememberSaveable { mutableStateOf<String?>(null) }
    var showDaysOff by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberAppSheetState()

    val rawStats = remember(savedShifts, selectedMonth, selectedYear, startDate, endDate, mode, payRange) {
        when (mode) {
            StatsMode.PAY_PERIOD -> StatsHelper.calculateRangeStats(savedShifts, payRange.start, payRange.end)
            StatsMode.MONTHLY -> StatsHelper.calculateMonthlyStats(savedShifts, selectedMonth)
            StatsMode.YEARLY -> StatsHelper.calculateYearlyStats(savedShifts, selectedYear)
            StatsMode.CUSTOM -> StatsHelper.calculateRangeStats(savedShifts, startDate, endDate)
        }
    }

    // Previous comparable period — drives the deltas on the stat cards.
    val rawPrevStats = remember(savedShifts, selectedMonth, selectedYear, startDate, endDate, mode, payRange) {
        when (mode) {
            StatsMode.PAY_PERIOD -> {
                val prev = payPeriod.previous(payRange)
                StatsHelper.calculateRangeStats(savedShifts, prev.start, prev.end)
            }
            StatsMode.MONTHLY ->
                StatsHelper.calculateMonthlyStats(savedShifts, selectedMonth.minusMonths(1))
            StatsMode.YEARLY ->
                StatsHelper.calculateYearlyStats(savedShifts, selectedYear.minusYears(1))
            StatsMode.CUSTOM -> {
                val len = ChronoUnit.DAYS.between(startDate, endDate) + 1
                StatsHelper.calculateRangeStats(
                    savedShifts,
                    startDate.minusDays(len),
                    startDate.minusDays(1)
                )
            }
        }
    }

    val deltaSuffix = when (mode) {
        StatsMode.PAY_PERIOD -> "vs last period"
        StatsMode.MONTHLY -> "vs last mo"
        StatsMode.YEARLY -> "vs last yr"
        StatsMode.CUSTOM -> "vs prev"
    }

    // Period bounds — used by the charts and the header pill.
    val periodStart = remember(mode, selectedMonth, selectedYear, startDate, payRange) {
        when (mode) {
            StatsMode.PAY_PERIOD -> payRange.start
            StatsMode.MONTHLY -> selectedMonth.atDay(1)
            StatsMode.YEARLY -> LocalDate.of(selectedYear.value, 1, 1)
            StatsMode.CUSTOM -> startDate
        }
    }
    val periodEnd = remember(mode, selectedMonth, selectedYear, endDate, payRange) {
        when (mode) {
            StatsMode.PAY_PERIOD -> payRange.end
            StatsMode.MONTHLY -> selectedMonth.atEndOfMonth()
            StatsMode.YEARLY -> LocalDate.of(selectedYear.value, 12, 31)
            StatsMode.CUSTOM -> endDate
        }
    }
    val periodLabel = remember(mode, selectedMonth, selectedYear, startDate, endDate, payRange) {
        when (mode) {
            StatsMode.PAY_PERIOD -> payRange.label()
            StatsMode.MONTHLY ->
                "${selectedMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${selectedMonth.year}"
            StatsMode.YEARLY -> selectedYear.toString()
            StatsMode.CUSTOM -> "${startDate.format(shortDateFmt)} – ${endDate.format(longDateFmt)}"
        }
    }

    // A salary isn't earned shift by shift. For a salaried job the money
    // figures come from the same period calculation as Home and the Wallet
    // (the salary for each pay period ending in the range, plus overtime and
    // extras), and the earnings charts show what comes on top of it.
    val isFixed = activeProfile.pay.isFixed
    val fixedPay = remember(isFixed, activeProfile, savedShifts, periodStart, periodEnd, weekStart) {
        if (isFixed) Compensation.forRange(activeProfile, savedShifts, DateRange(periodStart, periodEnd), weekStart) else null
    }
    val stats = remember(rawStats, fixedPay) {
        fixedPay?.let { rawStats.copy(estimatedEarnings = it.expectedCents / 100.0) } ?: rawStats
    }
    val prevStats = remember(rawPrevStats, isFixed, activeProfile, savedShifts, mode, payRange, selectedMonth, selectedYear, startDate, endDate, weekStart) {
        if (!isFixed) rawPrevStats
        else {
            val prev = when (mode) {
                StatsMode.PAY_PERIOD -> payPeriod.previous(payRange)
                StatsMode.MONTHLY -> selectedMonth.minusMonths(1).let { DateRange(it.atDay(1), it.atEndOfMonth()) }
                StatsMode.YEARLY -> selectedYear.minusYears(1).let { DateRange(it.atDay(1), it.atMonth(12).atEndOfMonth()) }
                StatsMode.CUSTOM -> {
                    val len = ChronoUnit.DAYS.between(startDate, endDate) + 1
                    DateRange(startDate.minusDays(len), startDate.minusDays(1))
                }
            }
            rawPrevStats.copy(
                estimatedEarnings = Compensation.forRange(activeProfile, savedShifts, prev, weekStart).expectedCents / 100.0
            )
        }
    }

    // Name to colour, built once per data change instead of scanning inside rows.
    val colorByName = remember(savedShifts) {
        val m = HashMap<String, Long>()
        for (list in savedShifts.values) for (s in list) m.putIfAbsent(s.name, s.colorValue)
        m
    }
    // Same, for each shift's mark in the per-shift lists.
    val iconByName = remember(savedShifts) {
        val m = HashMap<String, String>()
        for (list in savedShifts.values) for (s in list) s.iconName?.let { m.putIfAbsent(s.name, it) }
        m
    }

    // Earnings per day inside the period — source for the trend chart & insights.
    val dailyEarnings = remember(savedShifts, periodStart, periodEnd, fixedPay) {
        if (fixedPay != null) return@remember HashMap(fixedPay.extraByDay.mapValues { it.value / 100.0 })
        val out = HashMap<LocalDate, Double>()
        for ((date, list) in savedShifts) {
            if (date.isBefore(periodStart) || date.isAfter(periodEnd)) continue
            val sum = list.sumOf { PayEngine.earnings(it) }
            if (sum != 0.0) out[date] = sum
        }
        out
    }

    // Worked hours per day inside the period.
    val dailyHours = remember(savedShifts, periodStart, periodEnd) {
        val out = HashMap<LocalDate, Double>()
        for ((date, list) in savedShifts) {
            if (date.isBefore(periodStart) || date.isAfter(periodEnd)) continue
            val h = list.filter { PayEngine.isWorked(it) }.sumOf { PayEngine.workedHours(it) }
            if (h > 0.0) out[date] = h
        }
        out
    }

    val hourBuckets = remember(dailyHours, mode, selectedMonth, selectedYear, periodStart, periodEnd) {
        buildHourBuckets(dailyHours, mode, selectedMonth, selectedYear, periodStart, periodEnd)
    }

    val insights = remember(dailyHours, dailyEarnings, stats, mode, selectedMonth, isFixed) {
        buildInsights(dailyHours, dailyEarnings, stats, mode, selectedMonth, projectable = !isFixed)
    }

    val spanDays = remember(periodStart, periodEnd) {
        (ChronoUnit.DAYS.between(periodStart, periodEnd) + 1).toInt().coerceAtLeast(1)
    }
    // Daily buckets stop being readable past about two months, so force weeks there.
    val effectiveBucket = if (spanDays > 62) Bucket.WEEK else bucket

    val series = remember(dailyEarnings, effectiveBucket, periodStart, spanDays) {
        buildSeries(dailyEarnings, periodStart, spanDays, effectiveBucket)
    }

    val currencySymbol = remember(currencyCode) {
        try { java.util.Currency.getInstance(currencyCode).getSymbol(java.util.Locale.getDefault()) }
        catch (_: Exception) { currencyCode }
    }

    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val accent = cs.primary

    Column(modifier = modifier.fillMaxSize()) {
        // Nothing sits above the list: the period row scrolls away with the
        // content and the tabs pin themselves, so the charts get the screen.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = AppSpace.screenH,
                end = AppSpace.screenH,
                top = 2.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Scope: which period, and getting it out as a report ──
            if (header == null) item(key = "scope") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillButton(
                        text = periodLabel,
                        onClick = { showPeriodSheet = true },
                        icon = AppIcons.Calendar,
                        tinted = true
                    )
                    PillButton(
                        text = "Export",
                        onClick = {
                            onExport(ExportPeriod("Shown in Insights", DateRange(periodStart, periodEnd)))
                        },
                        icon = AppIcons.Upload
                    )
                }
            }

            // ── Tabs: pinned while their content scrolls beneath ──
            if (header != null) item(key = "scopeGap") { Spacer(Modifier.height(4.dp)) }
            stickyHeader(key = "tabs") {
                UnderlineTabs(
                    options = StatsTab.entries.map { it to it.label },
                    selected = tab,
                    onSelect = { tab = it }
                )
            }

            when (tab) {

                // ═════════════ OVERVIEW ═════════════
                StatsTab.OVERVIEW -> {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val hoursDelta = pctDelta(stats.totalHours, prevStats.totalHours)
                            val earnDelta = pctDelta(stats.estimatedEarnings, prevStats.estimatedEarnings)
                            val avgDiff = stats.averageHoursPerShift - prevStats.averageHoursPerShift

                            StatCard(
                                label = "Hours",
                                value = compactHours(stats.totalHours),
                                modifier = Modifier.weight(1f),
                                delta = deltaText(hoursDelta, deltaSuffix),
                                deltaPositive = hoursDelta?.let { it >= 0 }
                            )
                            StatCard(
                                label = "Earnings",
                                value = shortMoney(currencySymbol, stats.estimatedEarnings),
                                modifier = Modifier.weight(1f),
                                delta = deltaText(earnDelta, null),
                                deltaPositive = earnDelta?.let { it >= 0 }
                            )
                            StatCard(
                                label = "Avg shift",
                                value = compactHours(stats.averageHoursPerShift),
                                modifier = Modifier.weight(1f),
                                delta = when {
                                    prevStats.totalShifts == 0 -> "—"
                                    abs(avgDiff) < 0.25 -> "Stable"
                                    avgDiff > 0 -> "+${compactHours(avgDiff)}"
                                    else -> "-${compactHours(abs(avgDiff))}"
                                },
                                deltaPositive = when {
                                    prevStats.totalShifts == 0 || abs(avgDiff) < 0.25 -> null
                                    else -> avgDiff > 0
                                }
                            )
                        }
                    }

                    // ── Hours worked ──
                    item {
                        StatsCard(
                            title = "Hours worked",
                            subtitle = bucketSubtitle(mode, selectedMonth, selectedYear)
                        ) {
                            if (hourBuckets.all { it.hours <= 0.0 }) {
                                EmptyNote("No hours recorded in this period")
                            } else {
                                Spacer(Modifier.height(18.dp))
                                HoursBars(hourBuckets)
                            }
                        }
                    }

                    // ── Shift distribution ──
                    item {
                        StatsCard(title = "Shift distribution") {
                            if (stats.totalShifts == 0) {
                                EmptyNote("No shifts in this period")
                            } else {
                                Spacer(Modifier.height(18.dp))
                                HoursDonut(
                                    stats.shiftBreakdownHours,
                                    stats.totalHours,
                                    colorByName
                                )
                            }
                        }
                    }

                    // ── Earnings trend ──
                    item {
                        val growth = pctDelta(stats.estimatedEarnings, prevStats.estimatedEarnings)
                        StatsCard(
                            title = if (isFixed) "Extra pay trend" else "Earnings trend",
                            trailing = {
                                if (growth != null) {
                                    val up = growth >= 0
                                    SoftBadge(
                                        text = "${if (up) "+" else ""}${"%.1f".format(growth)}%",
                                        container = if (up) accents.morningSoft
                                        else accents.negative.copy(alpha = 0.12f),
                                        contentColor = if (up) accents.positive else accents.negative
                                    )
                                }
                            }
                        ) {
                            Spacer(Modifier.height(18.dp))
                            if (series.size < 2) {
                                EmptyNote("Not enough data to chart yet")
                            } else {
                                EarningsChart(series, currencySymbol, accent)
                            }
                        }
                    }

                    // ── Insights ──
                    if (insights.isNotEmpty()) {
                        item {
                            StatsCard(title = "Insights") {
                                Spacer(Modifier.height(6.dp))
                                insights.forEachIndexed { index, insight ->
                                    if (index > 0) Spacer(Modifier.height(4.dp))
                                    InsightRow(insight, currencySymbol)
                                }
                            }
                        }
                    }

                    // ── Rest / days off ──
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MetricTile(
                                "Rest days", "${stats.restDays}", AppIcons.TimeOff, accents.evening,
                                clickable = false, modifier = Modifier.weight(1f)
                            )
                            MetricTile(
                                "Days off", "${stats.dayOffDays}", AppIcons.Vacation, accents.morning,
                                stats.dayOffDays > 0, Modifier.weight(1f)
                            ) { showDaysOff = true }
                        }
                    }
                }

                // ═════════════ SHIFTS ═════════════
                StatsTab.SHIFTS -> {
                    if (stats.totalShifts == 0) {
                        item {
                            EmptyState(
                                "No shifts in this period",
                                "Add shifts to your calendar and they'll show up here."
                            )
                        }
                    } else {
                        items(stats.shiftBreakdown.toList(), key = { it.first }) { (name, count) ->
                            BreakdownRow(
                                name, count,
                                stats.shiftBreakdownHours[name] ?: 0.0, stats.totalHours,
                                rememberShiftAccent(colorByName[name] ?: FallbackSlate),
                                iconName = iconByName[name],
                                colorValue = colorByName[name] ?: FallbackSlate
                            ) { selectedBreakdown = name }
                        }
                    }
                }

                // ═════════════ EARNINGS ═════════════
                StatsTab.EARNINGS -> {
                    item {
                        SoftCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(
                                "Total earnings",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "$currencySymbol${"%,.2f".format(stats.estimatedEarnings)}",
                                style = MaterialTheme.typography.headlineLarge,
                                color = cs.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${StatsHelper.formatHours(stats.totalHours)} worked · ${stats.totalShifts} shift${if (stats.totalShifts != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                    if (stats.totalShifts == 0) {
                        item {
                            EmptyState(
                                "Nothing earned yet",
                                "Set an hourly rate on your shifts to see earnings here."
                            )
                        }
                    } else {
                        item {
                            StatsCard(title = "Earnings by shift type") {
                                Spacer(Modifier.height(18.dp))
                                val rows = remember(stats) {
                                    stats.shiftBreakdownEntries
                                        .map { (name, entries) -> name to entries.sumOf { it.earnings } }
                                        .sortedByDescending { it.second }
                                }
                                val top = rows.firstOrNull()?.second ?: 0.0
                                rows.forEachIndexed { index, pair ->
                                    if (index > 0) Spacer(Modifier.height(16.dp))
                                    EarningsRow(
                                        pair.first, pair.second, top, currencySymbol,
                                        rememberShiftAccent(colorByName[pair.first] ?: FallbackSlate),
                                        iconName = iconByName[pair.first],
                                        colorValue = colorByName[pair.first] ?: FallbackSlate
                                    )
                                }
                            }
                        }
                    }
                }

                // ═════════════ TRENDS ═════════════
                StatsTab.TRENDS -> {
                    item {
                        StatsCard(
                            title = if (isFixed) "Overtime & extras over time" else "Earnings over time",
                            trailing = {
                                if (spanDays <= 62) {
                                    BucketToggle(bucket) { bucket = it }
                                } else {
                                    Text(
                                        "By week",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                            }
                        ) {
                            Spacer(Modifier.height(18.dp))
                            if (series.size < 2) {
                                EmptyNote("Not enough data to chart yet")
                            } else {
                                EarningsChart(series, currencySymbol, accent)
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val perShift = if (stats.totalShifts > 0)
                                stats.estimatedEarnings / stats.totalShifts else 0.0
                            MetricTile(
                                "Avg per shift", shortMoney(currencySymbol, perShift),
                                AppIcons.Wallet, accents.evening, false, Modifier.weight(1f)
                            ) {}
                            MetricTile(
                                "Avg hours", compactHours(stats.averageHoursPerShift),
                                AppIcons.Clock, accents.morning, false, Modifier.weight(1f)
                            ) {}
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Period sheet
    // ══════════════════════════════════════════════════════════
    if (header != null) {
        // Handed up every composition, so the header always shows and acts
        // on the period currently on screen.
        SideEffect {
            header.periodLabel = periodLabel
            header.onPickPeriod = { showPeriodSheet = true }
            header.onExport = { onExport(ExportPeriod("Shown in Insights", DateRange(periodStart, periodEnd))) }
        }
    }

    if (showPeriodSheet) {
        StatsSheet(
            onDismiss = { showPeriodSheet = false },
            sheetState = sheetState,
            title = "Period"
        ) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(cs.surfaceVariant)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    StatsMode.PAY_PERIOD to "Pay period",
                    StatsMode.MONTHLY to "Month",
                    StatsMode.YEARLY to "Year",
                    StatsMode.CUSTOM to "Custom"
                ).forEach { pair ->
                    val selected = pair.first == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (selected) accent else Color.Transparent)
                            .clickable { mode = pair.first }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            pair.second,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            color = if (selected) cs.onPrimary else cs.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when (mode) {
                StatsMode.PAY_PERIOD -> Stepper(
                    payRange.shortLabel(),
                    payPeriod.describe(),
                    { payAnchor = payPeriod.previous(payRange).start },
                    { payAnchor = payPeriod.next(payRange).start }
                )
                StatsMode.MONTHLY -> Stepper(
                    selectedMonth.month.name.lowercase().replaceFirstChar { it.uppercase() },
                    selectedMonth.year.toString(),
                    { selectedMonth = selectedMonth.minusMonths(1) },
                    { selectedMonth = selectedMonth.plusMonths(1) }
                )
                StatsMode.YEARLY -> Stepper(
                    selectedYear.toString(), null,
                    { selectedYear = selectedYear.minusYears(1) },
                    { selectedYear = selectedYear.plusYears(1) }
                )
                StatsMode.CUSTOM -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(cs.background)
                        .border(1.dp, cs.outline, MaterialTheme.shapes.large)
                        .clickable { showRangePicker = true }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Calendar, null, tint = accent, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${startDate.format(shortDateFmt)} – ${endDate.format(longDateFmt)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        AppIcons.Edit, null,
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Breakdown detail sheet
    // ══════════════════════════════════════════════════════════
    selectedBreakdown?.let { name ->
        val entries = stats.shiftBreakdownEntries[name] ?: emptyList()
        val color = ShiftColors.accent(
            Color((colorByName[name] ?: FallbackSlate).toInt()),
            accents.isDark
        )
        val totalH = stats.shiftBreakdownHours[name] ?: 0.0
        val totalEarn = entries.sumOf { it.earnings }

        AppBottomSheet(
            onDismissRequest = { selectedBreakdown = null },
            sheetState = sheetState
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShiftIcon(name, iconByName[name], colorByName[name] ?: FallbackSlate, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.displayMedium,
                        color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${entries.size} occurrence${if (entries.size != 1) "s" else ""} · ${StatsHelper.formatHours(totalH)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp, bottom = 18.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(color.copy(alpha = 0.14f))
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SheetStat("Total", StatsHelper.formatHours(totalH))
                    SheetStat(
                        "Avg/shift",
                        StatsHelper.formatHours(if (entries.isNotEmpty()) totalH / entries.size else 0.0)
                    )
                    if (totalEarn > 0) {
                        SheetStat("Earnings", shortMoney(currencySymbol, totalEarn))
                    }
                }
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries) { e -> EntryRow(e, color, currencySymbol) }
                }
            }
        }
    }

    if (showDaysOff) {
        AppBottomSheet(
            onDismissRequest = { showDaysOff = false },
            sheetState = sheetState
        ) {
            DayListSheet(
                "Days off",
                "${stats.dayOffDays} day${if (stats.dayOffDays != 1) "s" else ""} off",
                stats.dayOffDates, accents.morning, AppIcons.Vacation,
                "No days off in this period"
            )
        }
    }

    if (showRangePicker) {
        val rangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = startDate.toEpochDay() * 86_400_000L,
            initialSelectedEndDateMillis = endDate.toEpochDay() * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Picking only a start date means a single day. The old
                        // code kept the previous end date, which could now fall
                        // before the new start and leave an inverted range.
                        val start = rangeState.selectedStartDateMillis
                            ?.let { LocalDate.ofEpochDay(it / 86_400_000L) }
                        if (start != null) {
                            val end = rangeState.selectedEndDateMillis
                                ?.let { LocalDate.ofEpochDay(it / 86_400_000L) }
                                ?.takeIf { !it.isBefore(start) }
                                ?: start
                            startDate = start
                            endDate = end
                        }
                        showRangePicker = false
                    },
                    enabled = rangeState.selectedStartDateMillis != null
                ) { Text("Apply", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { showRangePicker = false }) { Text("Cancel") } }
        ) {
            DateRangePicker(state = rangeState, modifier = Modifier.weight(1f))
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Card shell
// ══════════════════════════════════════════════════════════════

@Composable
internal fun StatsCard(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    SoftCard(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
        content()
    }
}

// ══════════════════════════════════════════════════════════════
//  Hours bar chart
// ══════════════════════════════════════════════════════════════

@Composable
private fun HoursBars(buckets: List<HourBucket>) {
    val cs = MaterialTheme.colorScheme
    val max = buckets.maxOfOrNull { it.hours } ?: 0.0
    if (max <= 0.0) {
        EmptyNote("No hours recorded in this period")
        return
    }
    val peakIndex = buckets.indexOfFirst { it.hours == max }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
            horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 8) 4.dp else 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            buckets.forEachIndexed { index, b ->
                val ratio = (b.hours / max).toFloat().coerceIn(0f, 1f)
                val isPeak = index == peakIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(if (ratio < 0.06f) 0.06f else ratio)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(
                            if (isPeak) cs.primary
                            else cs.primary.copy(alpha = 0.28f + 0.32f * ratio)
                        )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 8) 4.dp else 10.dp)
        ) {
            buckets.forEachIndexed { index, b ->
                Text(
                    b.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == peakIndex) cs.primary else cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Peak: ${buckets[peakIndex].label} · ${StatsHelper.formatHours(max)}",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  Donut
// ══════════════════════════════════════════════════════════════

@Composable
private fun HoursDonut(
    hoursByName: Map<String, Double>,
    totalHours: Double,
    colorByName: Map<String, Long>
) {
    val cs = MaterialTheme.colorScheme
    val isDark = AppTheme.accents.isDark
    val slices = remember(hoursByName, colorByName, isDark) {
        hoursByName.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .map {
                Triple(
                    it.key,
                    it.value,
                    ShiftColors.accent(Color((colorByName[it.key] ?: FallbackSlate).toInt()), isDark)
                )
            }
    }
    if (slices.isEmpty() || totalHours <= 0.0) {
        EmptyNote("No hours recorded")
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val strokePx = 18.dp.toPx()
                val diameter = size.minDimension - strokePx
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                var startAngle = -90f
                slices.forEach { slice ->
                    val sweep = (slice.second / totalHours * 360.0).toFloat()
                    drawArc(
                        color = slice.third,
                        startAngle = startAngle,
                        sweepAngle = (sweep - 3f).coerceAtLeast(1f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    compactHours(totalHours),
                    style = MaterialTheme.typography.headlineSmall,
                    color = cs.onSurface
                )
                Text(
                    "total",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            slices.take(5).forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(slice.third))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        slice.first,
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${(slice.second / totalHours * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurface,
                        maxLines = 1
                    )
                }
            }
            if (slices.size > 5) {
                Text(
                    "+${slices.size - 5} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Line chart
// ══════════════════════════════════════════════════════════════

@Composable
private fun BucketToggle(current: Bucket, onChange: (Bucket) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Bucket.entries.forEach { b ->
            TextTab(text = b.label, selected = b == current, onClick = { onChange(b) })
        }
    }
}

@Composable
private fun EarningsChart(
    series: List<Pair<LocalDate, Double>>,
    currencySymbol: String,
    accent: Color
) {
    val cs = MaterialTheme.colorScheme
    val maxValue = remember(series) { niceCeil(series.maxOf { it.second }) }
    val gridColor = cs.outline
    val yLabels = remember(maxValue, currencySymbol) {
        (3 downTo 0).map { shortMoney(currencySymbol, maxValue * it / 3.0) }
    }
    val xLabels = remember(series) {
        val n = series.size
        listOf(0, (n - 1) / 3, (n - 1) * 2 / 3, n - 1)
            .distinct()
            .map { series[it].first.format(shortDateFmt) }
    }

    Row(Modifier.fillMaxWidth()) {
        Column(
            Modifier.height(160.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            yLabels.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.weight(1f).height(160.dp)) {
            for (i in 0..3) {
                val y = size.height * i / 3f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }

            if (series.size < 2) return@Canvas

            val stepX = size.width / (series.size - 1)
            val line = Path()
            val fill = Path()
            var lastY = size.height

            series.forEachIndexed { i, point ->
                val x = i * stepX
                val ratio = (point.second / maxValue).toFloat().coerceIn(0f, 1f)
                val y = size.height - ratio * size.height
                if (i == 0) {
                    line.moveTo(x, y)
                    fill.moveTo(x, size.height)
                    fill.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    fill.lineTo(x, y)
                }
                lastY = y
            }
            fill.lineTo(size.width, size.height)
            fill.close()

            drawPath(
                fill,
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)))
            )
            drawPath(
                line, accent,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawCircle(Color.White, radius = 5.5.dp.toPx(), center = Offset(size.width, lastY))
            drawCircle(
                accent, radius = 5.5.dp.toPx(), center = Offset(size.width, lastY),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().padding(start = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        xLabels.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Rows, tiles, insights
// ══════════════════════════════════════════════════════════════

@Composable
private fun InsightRow(insight: Insight, currencySymbol: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            val painter = when (insight.iconKey) {
                InsightIcon.CALENDAR -> AppIcons.Calendar
                InsightIcon.STAR -> AppIcons.SetRate
                InsightIcon.MONEY -> AppIcons.Wallet
                InsightIcon.CHART -> AppIcons.Statistics
            }
            Icon(painter, null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            insight.label,
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (insight.isMoney) shortMoney(currencySymbol, insight.money) else insight.value,
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    icon: Painter,
    color: Color,
    clickable: Boolean,
    modifier: Modifier,
    onClick: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    SoftCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(16.dp),
        onClick = if (clickable) onClick else null
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    name: String,
    count: Int,
    hours: Double,
    totalHours: Double,
    color: Color,
    iconName: String?,
    colorValue: Long,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val pct = if (totalHours > 0) (hours / totalHours).toFloat().coerceIn(0f, 1f) else 0f

    SoftCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShiftIcon(name, iconName, colorValue, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${count}× · ${StatsHelper.formatHours(hours)}",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant, maxLines = 1
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth(pct).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun EarningsRow(
    name: String,
    amount: Double,
    topAmount: Double,
    currencySymbol: String,
    color: Color,
    iconName: String?,
    colorValue: Long
) {
    val cs = MaterialTheme.colorScheme
    val pct = if (topAmount > 0) (amount / topAmount).toFloat().coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShiftIcon(name, iconName, colorValue, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$currencySymbol${"%,.2f".format(amount)}",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface, maxLines = 1
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth(pct).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(80.dp).clip(CircleShape).background(cs.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Statistics, null,
                tint = cs.onSurfaceVariant, modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  Sheet pieces
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsSheet(
    onDismiss: () -> Unit,
    sheetState: AppSheetState,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    AppBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                color = cs.onSurface
            )
            content()
        }
    }
}


@Composable
private fun Stepper(
    label: String,
    subtitle: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.large)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleIconButton(
            icon = AppIcons.ChevronLeft,
            contentDescription = "Previous",
            onClick = onPrev,
            diameter = 44.dp,
            iconSize = 19.dp,
            container = cs.surfaceVariant,
            tint = cs.onSurfaceVariant,
            elevation = 0.dp
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        CircleIconButton(
            icon = AppIcons.ChevronRight,
            contentDescription = "Next",
            onClick = onNext,
            diameter = 44.dp,
            iconSize = 19.dp,
            container = cs.surfaceVariant,
            tint = cs.onSurfaceVariant,
            elevation = 0.dp
        )
    }
}

@Composable
private fun SheetStat(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun EntryRow(entry: ShiftBreakdownEntry, color: Color, currencySymbol: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.date.format(entryDateFmt),
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface, maxLines = 1
            )
            Text(
                "${entry.startTime} — ${entry.endTime}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant, maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                StatsHelper.formatHours(entry.hours),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface, maxLines = 1
            )
            if (entry.earnings > 0) {
                Text(
                    shortMoney(currencySymbol, entry.earnings),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DayListSheet(
    title: String,
    subtitle: String,
    dates: List<LocalDate>,
    accentColor: Color,
    icon: Painter,
    emptyMessage: String
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.82f)
            .padding(horizontal = 22.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accentColor, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (dates.isEmpty()) {
            Text(
                emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(dates) { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(cs.background)
                            .border(1.dp, cs.outline, MaterialTheme.shapes.large)
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(accentColor))
                        Spacer(Modifier.width(13.dp))
                        Text(
                            d.format(fullDateFmt),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Data helpers
// ══════════════════════════════════════════════════════════════

private data class HourBucket(val label: String, val hours: Double)

private enum class InsightIcon { CALENDAR, STAR, MONEY, CHART }

private class Insight(
    val iconKey: InsightIcon,
    val label: String,
    val value: String = "",
    val money: Double = 0.0,
    val isMoney: Boolean = false
)

/** Buckets worked hours for the overview bar chart. */
private fun buildHourBuckets(
    dailyHours: Map<LocalDate, Double>,
    mode: StatsMode,
    month: YearMonth,
    year: java.time.Year,
    start: LocalDate,
    end: LocalDate
): List<HourBucket> = when (mode) {

    StatsMode.MONTHLY -> {
        val weeks = ((month.lengthOfMonth() - 1) / 7) + 1
        List(weeks) { w ->
            var sum = 0.0
            for (day in 1..month.lengthOfMonth()) {
                if ((day - 1) / 7 == w) sum += dailyHours[month.atDay(day)] ?: 0.0
            }
            HourBucket("W${w + 1}", sum)
        }
    }

    StatsMode.YEARLY -> List(12) { m ->
        val ym = YearMonth.of(year.value, m + 1)
        var sum = 0.0
        for (day in 1..ym.lengthOfMonth()) sum += dailyHours[ym.atDay(day)] ?: 0.0
        HourBucket(ym.month.name.take(1), sum)
    }

    StatsMode.CUSTOM, StatsMode.PAY_PERIOD -> {
        val span = (ChronoUnit.DAYS.between(start, end) + 1).toInt().coerceAtLeast(1)
        if (span <= 10) {
            List(span) { i ->
                val d = start.plusDays(i.toLong())
                HourBucket("${d.dayOfMonth}", dailyHours[d] ?: 0.0)
            }
        } else {
            val weeks = ((span + 6) / 7).coerceAtMost(12)
            List(weeks) { w ->
                var sum = 0.0
                for (d in 0 until 7) {
                    val date = start.plusDays((w * 7 + d).toLong())
                    if (!date.isAfter(end)) sum += dailyHours[date] ?: 0.0
                }
                HourBucket("W${w + 1}", sum)
            }
        }
    }
}

private fun bucketSubtitle(mode: StatsMode, month: YearMonth, year: java.time.Year): String =
    when (mode) {
        StatsMode.MONTHLY ->
            "Weekly distribution (${month.month.name.lowercase().replaceFirstChar { it.uppercase() }})"
        StatsMode.YEARLY -> "Monthly distribution (${year.value})"
        StatsMode.CUSTOM -> "Distribution across the range"
        StatsMode.PAY_PERIOD -> "Distribution across the pay period"
    }

/** Small, practical read-outs shown under the charts. */
private fun buildInsights(
    dailyHours: Map<LocalDate, Double>,
    dailyEarnings: Map<LocalDate, Double>,
    stats: MonthlyStats,
    mode: StatsMode,
    month: YearMonth,
    /** False for a salary: it is paid whole, so a pace projection would be meaningless. */
    projectable: Boolean = true
): List<Insight> = buildList {

    // Busiest weekday
    if (dailyHours.isNotEmpty()) {
        val byWeekday = dailyHours.entries
            .groupBy { it.key.dayOfWeek }
            .mapValues { (_, list) -> list.sumOf { it.value } }
        val top = byWeekday.maxByOrNull { it.value }
        if (top != null && top.value > 0) {
            val dayName = top.key.name.lowercase().replaceFirstChar { it.uppercase() }
            add(Insight(InsightIcon.CALENDAR, "Busiest day", "${dayName}s · ${StatsHelper.formatHours(top.value)}"))
        }
    }

    // Longest streak of consecutive worked days
    if (dailyHours.isNotEmpty()) {
        val dates = dailyHours.keys.sorted()
        var best = 1
        var run = 1
        for (i in 1 until dates.size) {
            run = if (ChronoUnit.DAYS.between(dates[i - 1], dates[i]) == 1L) run + 1 else 1
            if (run > best) best = run
        }
        add(Insight(InsightIcon.STAR, "Longest streak", "$best day${if (best != 1) "s" else ""} in a row"))
    }

    // Best paid day
    val bestDay = dailyEarnings.maxByOrNull { it.value }
    if (bestDay != null && bestDay.value > 0) {
        add(Insight(InsightIcon.MONEY, "Best day (${bestDay.key.format(shortDateFmt)})", money = bestDay.value, isMoney = true))
    }

    // Month-end projection — only while looking at the running month
    if (projectable && mode == StatsMode.MONTHLY && month == YearMonth.now() && stats.estimatedEarnings > 0) {
        val today = LocalDate.now()
        val elapsed = today.dayOfMonth
        val total = month.lengthOfMonth()
        if (elapsed in 1 until total) {
            val projected = stats.estimatedEarnings / elapsed * total
            add(Insight(InsightIcon.CHART, "Projected month end", money = projected, isMoney = true))
        }
    }
}

/**
 * Insight rows need a Painter, which only resolves inside a @Composable, so the
 * data layer stores an icon key and the row picks the Painter at draw time.
 */

/** Buckets daily earnings for the trend chart. Empty buckets stay as zeros. */
private fun buildSeries(
    daily: Map<LocalDate, Double>,
    start: LocalDate,
    spanDays: Int,
    bucket: Bucket
): List<Pair<LocalDate, Double>> {
    val step = if (bucket == Bucket.DAY) 1 else 7
    val count = ((spanDays + step - 1) / step).coerceAtMost(60)
    if (count < 1) return emptyList()
    return List(count) { i ->
        val from = start.plusDays(i.toLong() * step)
        var sum = 0.0
        for (d in 0 until step) sum += daily[from.plusDays(d.toLong())] ?: 0.0
        from to sum
    }
}

/** Percentage change, or null when there's no baseline to compare against. */
private fun pctDelta(current: Double, previous: Double): Double? =
    if (previous <= 0.0) null else (current - previous) / previous * 100.0

private fun deltaText(pct: Double?, suffix: String?): String {
    if (pct == null) return "No baseline"
    val sign = if (pct >= 0) "+" else ""
    val body = "$sign${"%.1f".format(pct)}%"
    return if (suffix == null) body else "$body $suffix"
}

private fun compactHours(hours: Double): String =
    if (hours % 1.0 == 0.0) "${hours.toInt()}h" else "%.1fh".format(hours)

/** Rounds up to a readable axis maximum: 1, 2, 2.5 or 5 times a power of ten. */
private fun niceCeil(value: Double): Double {
    if (value <= 0.0) return 100.0
    val exponent = floor(log10(value))
    val base = 10.0.pow(exponent)
    val n = value / base
    val m = when {
        n <= 1.0 -> 1.0
        n <= 2.0 -> 2.0
        n <= 2.5 -> 2.5
        n <= 5.0 -> 5.0
        else -> 10.0
    }
    return m * base
}

private fun shortMoney(symbol: String, value: Double): String = when {
    value >= 1_000_000 -> symbol + "%.1f".format(value / 1_000_000) + "M"
    value >= 1_000 -> {
        val k = value / 1_000
        if (k == floor(k)) symbol + "%.0f".format(k) + "k" else symbol + "%.1f".format(k) + "k"
    }
    else -> symbol + "%.0f".format(value)
}
/**
 * What Insights hands to the screen hosting it: the period on show, and the
 * actions to change it or export it, for the host to place in its header.
 */
@Stable
internal class InsightsHeaderState {
    var periodLabel by mutableStateOf("")
    var onPickPeriod: () -> Unit by mutableStateOf({})
    var onExport: () -> Unit by mutableStateOf({})
}
