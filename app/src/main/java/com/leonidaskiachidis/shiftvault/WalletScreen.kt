package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Wallet — what the active job has earned, what has been received, and what
 *  is still owed; with the shift analytics alongside as Insights.
 *
 *      Balance   the money picture: owed / earned / received, work periods and
 *                whether each is paid, by month, by type, recent payments
 *      Earnings  the ledger itself: every earning, its payments, its balance
 *      Insights  hours and pay worked out from shifts (InsightsPane)
 *
 *  Balances and statuses are never stored; they come from Ledger.kt, so the
 *  screen only ever shows what the payments add up to.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private enum class WalletTab(val label: String) {
    BALANCE("Balance"), EARNINGS("Earnings"), INSIGHTS("Insights")
}

private val paymentDateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private val extraDayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val shortMonthFmt = DateTimeFormatter.ofPattern("MMM")

/** How many work periods Balance lists before "Show all". */
private const val PeriodPreview = 5

@Composable
fun WalletScreen(
    /** The active job's shifts: Insights, and the "from your shifts" amount. */
    savedShifts: Map<LocalDate, List<Shift>>,
    /** The active job's earnings and payments. */
    ledger: LedgerData,
    currencyCode: String,
    activeProfile: JobProfile,
    onOpenProfiles: () -> Unit,
    onExport: (ExportPeriod) -> Unit,
    /** Each returns why it couldn't save, or null once saved. */
    onSaveEarning: suspend (Earning) -> String?,
    onDeleteEarning: (Long) -> Unit,
    onAddPayment: suspend (Payment) -> String?,
    onDeletePayment: (Long) -> Unit,
    /** The calendar's first day, for weekly overtime. */
    weekStart: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY,
    /** Adds a period's calculated pay to the ledger; returns the problem, or null. */
    onAddExpected: suspend (List<Earning>) -> String? = { null },
    onSetShortfallAccepted: (Long, Boolean) -> Unit = { _, _ -> },
    /** Marks extra shifts (dates → positions in the job's day) paid or pending. */
    onSetExtraPaid: (Map<LocalDate, Set<Int>>, Boolean) -> Unit = { _, _ -> }
) {
    val cs = MaterialTheme.colorScheme
    val currencySymbol = remember(currencyCode) {
        try { java.util.Currency.getInstance(currencyCode).getSymbol(java.util.Locale.getDefault()) }
        catch (_: Exception) { currencyCode }
    }
    val money = remember(currencySymbol) { { cents: Long -> formatCents(cents, currencySymbol) } }

    val balances = remember(ledger) { Ledger.balances(ledger) }
    val today = remember { LocalDate.now() }
    // Every pay period: what the app works out it should pay, beside the ledger.
    val statements = remember(activeProfile, savedShifts, balances, weekStart) {
        Statements.build(activeProfile, savedShifts, balances, today, weekStart)
    }
    val extraEntries = remember(savedShifts) { ExtraHours.entries(savedShifts) }
    var statementStart by rememberSaveable { mutableStateOf<String?>(null) }
    var statementEnd by rememberSaveable { mutableStateOf<String?>(null) }

    var tab by rememberSaveable { mutableStateOf(WalletTab.BALANCE) }
    // Set from a pay period's sheet: Earnings then shows that period only.
    var periodStart by rememberSaveable { mutableStateOf<String?>(null) }
    var periodEnd by rememberSaveable { mutableStateOf<String?>(null) }
    val periodFilter = periodStart?.let { s -> periodEnd?.let { e -> DateRange(LocalDate.parse(s), LocalDate.parse(e)) } }

    // Open sheets, by earning id so they always show the live balance.
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var paymentForId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showOwedPicker by rememberSaveable { mutableStateOf(false) }

    fun openEditor(id: Long?) {
        editingId = id
        showEditor = true
    }

    // The title gives its space back as soon as the content scrolls, and
    // returns on the first scroll back up; only the tab switch stays put.
    val header = remember { CollapsingHeaderState() }
    LaunchedEffect(tab) { header.expand() }
    val insightsHeader = remember { InsightsHeaderState() }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding()
            .nestedScroll(header.connection)
    ) {
        Column(Modifier.collapsing(header)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppSpace.screenH).padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Wallet",
                    style = ScreenTitleStyle,
                    color = cs.onBackground,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // Insights' period and export, only while Insights is showing.
                AnimatedVisibility(
                    visible = tab == WalletTab.INSIGHTS,
                    enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.9f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .height(44.dp)
                                .widthIn(max = 150.dp)
                                .clip(CircleShape)
                                .background(cs.surfaceVariant)
                                .clickable(onClickLabel = "Choose the period", role = Role.Button) {
                                    insightsHeader.onPickPeriod()
                                }
                                .padding(start = 12.dp, end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(AppIcons.Calendar, null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                insightsHeader.periodLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        HeaderButton(
                            icon = AppIcons.Upload,
                            contentDescription = "Export this period",
                            onClick = { insightsHeader.onExport() }
                        )
                    }
                }
            }
            // The job applies to every tab, so it sits under the title.
            ProfileSwitch(
                profile = activeProfile,
                onClick = onOpenProfiles,
                modifier = Modifier.padding(start = AppSpace.screenH, top = 2.dp)
            )
        }
        SegmentedPills(
            options = WalletTab.entries.map { it to it.label },
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = AppSpace.screenH).padding(top = 12.dp)
        )

        // A quick crossfade between tabs, so a switch reads as one movement.
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "walletTab",
            modifier = Modifier.weight(1f)
        ) { shown ->
            when (shown) {
                WalletTab.BALANCE -> BalancePane(
                    balances = balances,
                    extraEntries = extraEntries,
                    onSetExtraPaid = onSetExtraPaid,
                    statements = statements,
                    today = today,
                    money = money,
                    onAdd = { openEditor(null) },
                    onRecordPayment = { showOwedPicker = true },
                    onOpenStatement = { range ->
                        statementStart = range.start.toString()
                        statementEnd = range.end.toString()
                    },
                    modifier = Modifier.fillMaxSize()
                )
                WalletTab.EARNINGS -> EarningsPane(
                    balances = balances,
                    period = periodFilter,
                    onClearPeriod = { periodStart = null; periodEnd = null },
                    money = money,
                    onOpenEarning = { detailId = it },
                    onAdd = { openEditor(null) },
                    modifier = Modifier.fillMaxSize()
                )
                WalletTab.INSIGHTS -> InsightsPane(
                    savedShifts = savedShifts,
                    currencyCode = currencyCode,
                    onExport = onExport,
                    activeProfile = activeProfile,
                    weekStart = weekStart,
                    header = insightsHeader,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // ── Sheets ──

    if (showOwedPicker) {
        OwedPickerSheet(
            owed = remember(balances) { balances.filter { it.outstandingCents > 0L } },
            money = money,
            onPick = { id -> showOwedPicker = false; paymentForId = id },
            onDismiss = { showOwedPicker = false }
        )
    }

    val statement = statementStart?.let { start ->
        statements.firstOrNull { it.range.start.toString() == start && it.range.end.toString() == statementEnd }
    }
    if (statement != null) {
        PeriodStatementSheet(
            statement = statement,
            profile = activeProfile,
            currencySymbol = currencySymbol,
            money = money,
            onAddExpected = onAddExpected,
            onOpenEarning = { detailId = it },
            onShowEarnings = { range ->
                statementStart = null; statementEnd = null
                periodStart = range.start.toString()
                periodEnd = range.end.toString()
                tab = WalletTab.EARNINGS
            },
            onDismiss = { statementStart = null; statementEnd = null }
        )
    }

    val detail = detailId?.let { id -> balances.firstOrNull { it.earning.id == id } }
    // Deleted from the editor while its detail sheet was open: close that too.
    LaunchedEffect(detailId, detail) { if (detailId != null && detail == null) detailId = null }
    if (detail != null) {
        EarningDetailSheet(
            balance = detail,
            money = money,
            onRecordPayment = { paymentForId = detail.earning.id },
            onEdit = { openEditor(detail.earning.id) },
            onDeletePayment = onDeletePayment,
            onSetShortfallAccepted = { accepted -> onSetShortfallAccepted(detail.earning.id, accepted) },
            onDismiss = { detailId = null }
        )
    }

    if (showEditor) {
        EarningEditorSheet(
            editing = editingId?.let { id -> balances.firstOrNull { it.earning.id == id } },
            profile = activeProfile,
            shifts = savedShifts,
            weekStart = weekStart,
            currencySymbol = currencySymbol,
            money = money,
            onSave = onSaveEarning,
            onDelete = { id ->
                onDeleteEarning(id)
                if (detailId == id) detailId = null
            },
            onDismiss = { showEditor = false; editingId = null }
        )
    }

    val payingFor = paymentForId?.let { id -> balances.firstOrNull { it.earning.id == id } }
    if (payingFor != null) {
        PaymentSheet(
            balance = payingFor,
            currencySymbol = currencySymbol,
            money = money,
            onSave = onAddPayment,
            onDismiss = { paymentForId = null }
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  Balance
// ══════════════════════════════════════════════════════════════

@Composable
private fun BalancePane(
    balances: List<EarningBalance>,
    extraEntries: List<ExtraEntry>,
    onSetExtraPaid: (Map<LocalDate, Set<Int>>, Boolean) -> Unit,
    statements: List<PeriodStatement>,
    today: LocalDate,
    money: (Long) -> String,
    onAdd: () -> Unit,
    onRecordPayment: () -> Unit,
    onOpenStatement: (DateRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val totals = remember(balances) { LedgerTotals.of(balances) }
    // Finished periods whose pay the app has worked out but nobody has added.
    val pending = remember(statements, today) { statements.filter { it.canAddExpected && it.range.end.isBefore(today) } }
    val payments = remember(balances) { Ledger.history(balances) }
    var allPeriods by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = AppSpace.screenH, end = AppSpace.screenH, top = 22.dp, bottom = 32.dp)
    ) {
        item(key = "hero") {
            BalanceHero(
                totals = totals,
                hasEarnings = balances.isNotEmpty(),
                pendingPeriods = pending.size,
                pendingCents = pending.sumOf { it.calculated?.expectedCents ?: 0L },
                money = money,
                onAdd = onAdd,
                onRecordPayment = onRecordPayment
            )
        }

        item(key = "extra") {
            Spacer(Modifier.height(28.dp))
            ExtraHoursCard(extraEntries, onSetExtraPaid)
        }

        if (statements.isNotEmpty()) {
            item(key = "periodsHeader") {
                SectionHeader(
                    title = "Pay periods",
                    action = if (statements.size > PeriodPreview) {
                        if (allPeriods) "Show fewer" else "See all ${statements.size}"
                    } else null,
                    onAction = { allPeriods = !allPeriods },
                    modifier = Modifier.padding(top = 28.dp)
                )
            }
            val shown = if (allPeriods) statements else statements.take(PeriodPreview)
            items(shown, key = { "period-${it.range.start}-${it.range.end}" }) { st ->
                StatementRow(st, today, money, Modifier.animateItem()) { onOpenStatement(st.range) }
            }
        }

        if (payments.isNotEmpty()) {
            item(key = "received") {
                ReceivedByMonth(payments = payments, today = today, money = money, modifier = Modifier.padding(top = 28.dp))
            }
        }
    }
}

/** A section's title, with an optional text action on the right. */
@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            color = cs.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        trailing?.invoke()
    }
}

/** The figure that matters most first: what is still owed. */
@Composable
private fun BalanceHero(
    totals: LedgerTotals,
    hasEarnings: Boolean,
    pendingPeriods: Int,
    pendingCents: Long,
    money: (Long) -> String,
    onAdd: () -> Unit,
    onRecordPayment: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val pendingNote = if (pendingPeriods > 0)
        "$pendingPeriods finished pay period${if (pendingPeriods == 1) " isn't" else "s aren't"} in your wallet yet " +
            "(${money(pendingCents)} expected). Open ${if (pendingPeriods == 1) "it" else "one"} below to add it."
    else null

    Column(Modifier.fillMaxWidth()) {
        if (!hasEarnings) {
            Text("Track what you're owed", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "Add what each pay period should pay (the app works it out from your salary or shifts), " +
                    "then record each payment as it arrives. Whatever hasn't been paid stays owed.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant
            )
            pendingNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = accents.warning)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryPillButton(text = "Add an earning", enabled = true, onClick = onAdd)
            return@Column
        }

        val settled = totals.outstandingCents == 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (settled) {
                Icon(AppIcons.Check, null, tint = accents.positive, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (settled) "All paid up" else "Owed to you",
                style = MaterialTheme.typography.labelLarge,
                color = if (settled) accents.positive else cs.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // The cents step back, so the whole amount is what is read first.
            val amount = money(totals.outstandingCents)
            AnimatedContent(
                targetState = amount,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "owedAmount",
                modifier = Modifier.weight(1f)
            ) { text ->
                Text(
                    buildAnnotatedString {
                        append(text.dropLast(3))
                        withStyle(SpanStyle(color = cs.onSurfaceVariant.copy(alpha = 0.55f))) { append(text.takeLast(3)) }
                    },
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-2.4).sp, fontFeatureSettings = "tnum"
                    ),
                    color = cs.onSurface,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 52.sp, stepSize = 2.sp)
                )
            }
            if (!settled) {
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier
                        .padding(bottom = 4.dp)
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(cs.primary)
                        .clickable(onClickLabel = "Record a payment", role = Role.Button, onClick = onRecordPayment)
                        .padding(start = 12.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Add, null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Payment", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
                }
            }
        }
        Text(
            if (settled) "nothing is owed to you" else "still owed to you",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        ProgressTrack(fraction = totals.receivedFraction, fill = cs.primary, track = cs.surfaceVariant, height = 6.dp)
        Spacer(Modifier.height(12.dp))
        Row {
            HeroFigure(
                "Expected", money(totals.earnedCents),
                totals.hoursExpected?.let { hoursLabel(it) }, Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            HeroFigure(
                "Received", money(totals.receivedCents),
                totals.hoursPaid?.let { "${hoursLabel(it)} paid" }, Modifier.weight(1f)
            )
        }
        pendingNote?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = accents.warning)
        }
    }
}

/** A labelled figure in a sheet, in [color]. */
@Composable
private fun HeroFigure(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
    }
}

@Composable
private fun HeroFigure(label: String, value: String, note: String?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.4).sp, fontFeatureSettings = "tnum"),
            color = cs.onSurface,
            maxLines = 1
        )
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = cs.onSurfaceVariant)
        }
    }
}

/**
 * Extra hours, one month at a time: what is still waiting to be paid, and a
 * box per shift. Tick the ones that were paid, then mark them in one go; a
 * paid shift tapped again goes back to pending.
 */
@Composable
private fun ExtraHoursCard(entries: List<ExtraEntry>, onSetPaid: (Map<LocalDate, Set<Int>>, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val months = remember(entries) { ExtraHours.byMonth(entries) }
    val shape = RoundedCornerShape(24.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(HairlineWidth, cs.softEdge, shape)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)
    ) {
        if (months.isEmpty()) {
            Text("Extra hours", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "Switch on Extra hours on a shift, and its hours are tracked here until they're paid.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            return@Column
        }

        // Opens on the newest month with something still pending.
        var monthKey by rememberSaveable(months.size) {
            mutableStateOf((months.firstOrNull { it.pendingHours > 0.0 } ?: months.first()).month.toString())
        }
        val index = months.indexOfFirst { it.month.toString() == monthKey }.coerceAtLeast(0)
        val month = months[index]
        val selected = remember(monthKey) { mutableStateMapOf<String, Boolean>() }
        val keyOf = { e: ExtraEntry -> "${e.date}#${e.index}" }
        val picked = month.entries.filter { !it.paid && selected[keyOf(it)] == true }

        Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Extra hours", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Older months are to the right of the list, as time runs back.
                    MonthStep(AppIcons.ChevronLeft, "Earlier month", enabled = index < months.lastIndex) {
                        monthKey = months[index + 1].month.toString()
                    }
                    Text(
                        month.month.format(monthFmt),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    MonthStep(AppIcons.ChevronRight, "Later month", enabled = index > 0) {
                        monthKey = months[index - 1].month.toString()
                    }
                }
            }
            ExtraFigure(hoursLabel(month.pendingHours), "Pending", ShiftColors.ink(accents.warning, accents.isDark))
            Spacer(Modifier.width(16.dp))
            ExtraFigure(hoursLabel(month.paidHours), "Paid", cs.onSurface)
        }

        // Paid against pending, side by side.
        val paidShare by animateFloatAsState(
            if (month.paidHours + month.pendingHours > 0.0) (month.paidHours / (month.paidHours + month.pendingHours)).toFloat() else 0f,
            tween(500, easing = FastOutSlowInEasing), label = "extraShare"
        )
        Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (paidShare > 0f) Box(Modifier.weight(paidShare).fillMaxHeight().clip(CircleShape).background(cs.primary))
            if (paidShare < 1f) Box(Modifier.weight(1f - paidShare).fillMaxHeight().clip(CircleShape).background(accents.warning))
        }
        Spacer(Modifier.height(6.dp))

        month.entries.forEachIndexed { i, entry ->
            if (i > 0) GroupDivider()
            val checked = entry.paid || selected[keyOf(entry)] == true
            ExtraShiftRow(entry, checked) {
                if (entry.paid) onSetPaid(mapOf(entry.date to setOf(entry.index)), false)
                else selected[keyOf(entry)] = !(selected[keyOf(entry)] ?: false)
            }
        }

        val unpaid = month.entries.filter { !it.paid }
        if (unpaid.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PrimaryPillButton(
                text = when {
                    picked.isNotEmpty() -> "Mark ${hoursLabel(picked.sumOf { it.hours })} as paid"
                    else -> "Mark all ${hoursLabel(month.pendingHours)} as paid"
                },
                enabled = true,
                onClick = {
                    val targets = picked.ifEmpty { unpaid }
                    onSetPaid(ExtraHours.targetsOf(targets), true)
                    selected.clear()
                }
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MonthStep(icon: Painter, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, null,
            tint = cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun ExtraFigure(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = color)
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One extra shift with its box: ticked when picked, or already paid. */
@Composable
private fun ExtraShiftRow(entry: ExtraEntry, checked: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dot = rememberShiftAccent(entry.shift.colorValue)
    val box by animateColorAsState(if (checked) cs.primary else Color.Transparent, tween(160), label = "extraBox")
    val edge by animateColorAsState(if (checked) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f), tween(160), label = "extraEdge")
    val boxShape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (entry.paid) 0.5f else 1f)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .semantics { stateDescription = if (entry.paid) "Paid, tap to mark pending" else if (checked) "Selected" else "Pending" }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(boxShape)
                .background(box)
                .border(2.dp, edge, boxShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(AppIcons.Check, null, tint = cs.onPrimary, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(entry.shift.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                entry.date.format(extraDayFmt) + " · " + shiftTimeLabel(entry.shift) + if (entry.paid) " · paid" else "",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(hoursLabel(entry.hours), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
    }
}

/**
 * One pay period: what's expected (from the ledger once added, else as
 * calculated), hours worked against hours paid, and where it stands.
 */
@Composable
private fun StatementRow(st: PeriodStatement, today: LocalDate, money: (Long) -> String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val recorded = st.recorded
    val calc = st.calculated
    val amount = recorded?.totals?.earnedCents ?: calc?.expectedCents
    val hours = listOfNotNull(
        st.hoursDue?.takeIf { it > 0.0 }?.let { "${hoursLabel(it)} worked" },
        st.hoursPaid?.let { "${hoursLabel(it)} paid" }
    ).joinToString(" · ").ifEmpty { if (calc == null) "Separate period" else "No hours yet" }
    val owed = recorded?.totals?.outstandingCents?.takeIf { it > 0L }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Open pay period", onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(st.range.label(), style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (owed != null) "$hours · ${money(owed)} owed" else hours,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (amount != null && amount > 0L) {
                    Text(money(amount), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                    Spacer(Modifier.height(4.dp))
                }
                when {
                    recorded != null -> StatusPill(recorded.status)
                    today in st.range -> Pill("In progress", cs.onSurfaceVariant)
                    calc != null && calc.expectedCents > 0L -> Pill("Not in wallet", accents.warning)
                }
            }
        }
        GroupDivider()
    }
}

/**
 * Money received in each of the last six months, by the day it arrived. The
 * best month is drawn solid; the bars rise once when the chart appears.
 */
@Composable
private fun ReceivedByMonth(payments: List<PaymentEntry>, today: LocalDate, money: (Long) -> String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val current = YearMonth.from(today)
    val months = remember(current) { (5 downTo 0).map { current.minusMonths(it.toLong()) } }
    val totals = remember(payments, months) {
        val byMonth = payments.groupBy { YearMonth.from(it.payment.day) }
        months.map { m -> byMonth[m].orEmpty().sumOf { it.payment.amountCents } }
    }
    val max = totals.maxOrNull()?.takeIf { it > 0L } ?: 1L
    val best = totals.indexOf(totals.max())
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }

    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Received by month",
            trailing = {
                Text(
                    if (months.first().year == months.last().year) "${months.last().year}"
                    else "${months.first().year}–${months.last().year}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            months.forEachIndexed { i, m ->
                val cents = totals[i]
                val fill = when {
                    cents > 0L && i == best -> cs.primary
                    m == current -> cs.surfaceVariant
                    else -> cs.outline
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (cents > 0L) {
                        // Whole units: the bar is the detail, the label a reading of it.
                        Text(
                            money((cents + 50) / 100 * 100).dropLast(3),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((92.dp * (cents.toFloat() / max) * rise.value).coerceAtLeast(4.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(fill)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        m.format(shortMonthFmt),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (m == current) cs.onSurface else cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Which owed earning a payment is for — one tap opens its payment form. */
@Composable
private fun OwedPickerSheet(
    owed: List<EarningBalance>,
    money: (Long) -> String,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberAppSheetState()
    val scope = rememberCoroutineScope()
    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = "Record a payment",
        caption = "Which earning is it for?"
    ) {
        Spacer(Modifier.height(14.dp))
        if (owed.isEmpty()) {
            EmptyHint("Nothing is owed to you right now.")
        } else {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                owed.forEach { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { scope.launch { sheetState.hide(); onPick(b.earning.id) } }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TypeBadge(b.earning.type)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.earning.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(b.earning.period.label(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${money(b.outstandingCents)} owed", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Earnings
// ══════════════════════════════════════════════════════════════

/**
 * Everything beyond a pay period's regular pay — overtime, bonuses, tips,
 * other work — and every payment that has come in, newest first.
 */
@Composable
private fun EarningsPane(
    balances: List<EarningBalance>,
    period: DateRange?,
    onClearPeriod: () -> Unit,
    money: (Long) -> String,
    onOpenEarning: (Long) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val inScope = remember(balances, period) { Ledger.filter(balances, LedgerFilter.ALL, period) }
    val other = remember(inScope) { inScope.filter { it.earning.type !in BasePayTypes } }
    val payments = remember(inScope) { Ledger.history(inScope) }
    val byMonth = remember(payments) { payments.groupBy { YearMonth.from(it.payment.day) } }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = AppSpace.screenH, end = AppSpace.screenH, top = 16.dp, bottom = 32.dp)
    ) {
        if (period != null) {
            item(key = "period") {
                Row(
                    Modifier
                        .padding(bottom = 8.dp)
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(cs.primary)
                        .clickable(onClickLabel = "Show every period", onClick = onClearPeriod)
                        .padding(start = 14.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(period.shortLabel(), style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
                    Spacer(Modifier.width(6.dp))
                    Icon(AppIcons.Close, null, tint = cs.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }

        item(key = "otherHeader") {
            SectionHeader(
                title = "Other income",
                modifier = Modifier.padding(top = 8.dp),
                trailing = {
                    Row(
                        Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(cs.surfaceVariant)
                            .clickable(onClickLabel = "Add an earning", role = Role.Button, onClick = onAdd)
                            .padding(start = 10.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(AppIcons.Add, null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                    }
                }
            )
        }
        if (other.isEmpty()) {
            item(key = "otherEmpty") {
                Text(
                    "Overtime, bonuses, tips or other work you're owed beyond your regular pay. " +
                        "Your regular pay is under Pay periods on the Balance tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        items(other, key = { "earning-${it.earning.id}" }) { balance ->
            EarningRow(balance, money, Modifier.animateItem()) { onOpenEarning(balance.earning.id) }
        }

        item(key = "paymentsHeader") {
            SectionHeader(
                title = "Payments",
                modifier = Modifier.padding(top = 28.dp),
                trailing = if (payments.isEmpty()) null else {
                    {
                        Text(
                            "${money(payments.sumOf { it.payment.amountCents })} received",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            )
        }
        if (payments.isEmpty()) {
            item(key = "paymentsEmpty") {
                Text(
                    "Nothing received yet. Use Payment on the Balance tab when money comes in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        byMonth.forEach { (month, list) ->
            item(key = "month-$month") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
                    SheetLabel(month.format(monthFmt))
                    Spacer(Modifier.weight(1f))
                    Text(
                        money(list.sumOf { it.payment.amountCents }),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant
                    )
                }
            }
            items(list, key = { "payment-${it.payment.id}" }) { entry ->
                PaymentHistoryRow(entry, money) { onOpenEarning(entry.earning.id) }
            }
        }
    }
}

@Composable
private fun EarningRow(balance: EarningBalance, money: (Long) -> String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val e = balance.earning
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Open earning", onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TypeBadge(e.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${e.type.label} · ${e.period.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(money(e.amountCents), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                StatusPill(balance.status)
            }
        }
        GroupDivider()
    }
}

// ══════════════════════════════════════════════════════════════
//  Sheets
// ══════════════════════════════════════════════════════════════

@Composable
private fun EarningDetailSheet(
    balance: EarningBalance,
    money: (Long) -> String,
    onRecordPayment: () -> Unit,
    onEdit: () -> Unit,
    onDeletePayment: (Long) -> Unit,
    onSetShortfallAccepted: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val e = balance.earning
    var confirmDelete by remember { mutableStateOf<Payment?>(null) }
    var confirmAccept by remember { mutableStateOf(false) }

    AppSheet(
        onDismiss = onDismiss,
        sheetState = rememberAppSheetState(),
        title = e.title,
        caption = "${e.type.label} · ${e.period.label()}"
    ) {
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(cs.background)
                .border(1.dp, cs.outline, MaterialTheme.shapes.large)
                .padding(16.dp)
        ) {
            Text(
                "Expected" + (e.hours?.let { " · for ${hoursLabel(it)}" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(money(e.amountCents), style = MaterialTheme.typography.headlineMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                StatusPill(balance.status)
            }
            Spacer(Modifier.height(12.dp))
            ProgressTrack(fraction = balance.paidFraction, fill = accents.positive, track = cs.surfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row {
                HeroFigure("Received", money(balance.paidCents), accents.positive, Modifier.weight(1f))
                if (balance.isClosed) {
                    HeroFigure(
                        "Difference", signedCents(balance.differenceCents, money(0).takeWhile { !it.isDigit() }),
                        when {
                            balance.differenceCents < 0L -> accents.negative
                            balance.differenceCents > 0L -> accents.positive
                            else -> cs.onSurface
                        },
                        Modifier.weight(1f)
                    )
                } else {
                    HeroFigure("Still owed", money(balance.outstandingCents), accents.negative, Modifier.weight(1f))
                }
            }
            val paidHours = balance.hoursPaid
            if (paidHours != null || e.hours != null) {
                Spacer(Modifier.height(10.dp))
                Row {
                    HeroFigure("Hours expected", e.hours?.let { hoursLabel(it) } ?: "—", cs.onSurface, Modifier.weight(1f))
                    HeroFigure(
                        "Hours paid", paidHours?.let { hoursLabel(it) } ?: "Not recorded",
                        if (paidHours != null && e.hours != null && paidHours < e.hours) accents.warning else cs.onSurface,
                        Modifier.weight(1f)
                    )
                }
            }
            if (balance.status == PaymentStatus.SETTLED) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Closed with ${money(-balance.differenceCents)} accepted as not coming.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SheetLabel("Payments")
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (balance.payments.isEmpty()) {
                EmptyHint("Nothing received for this yet.")
            }
            balance.payments.asReversed().forEachIndexed { i, p ->
                if (i > 0) RowRule()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.day.format(paymentDateFmt), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                        p.hoursPaid?.let {
                            Text("Covers ${hoursLabel(it)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        if (p.note.isNotBlank()) {
                            Text(p.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("+${money(p.amountCents)}", style = MaterialTheme.typography.titleSmall, color = accents.positive)
                    Spacer(Modifier.width(6.dp))
                    CircleIconButton(
                        icon = AppIcons.Delete,
                        contentDescription = "Delete payment",
                        onClick = { confirmDelete = p },
                        diameter = 34.dp, iconSize = 16.dp,
                        container = cs.surfaceVariant, tint = cs.onSurfaceVariant, elevation = 0.dp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (!balance.isClosed) {
            SheetActionButton(text = "Record a payment", icon = AppIcons.Wallet, onClick = onRecordPayment)
            Spacer(Modifier.height(10.dp))
        }
        SecondaryAction(text = "Edit earning", icon = AppIcons.Edit, onClick = onEdit)
        when (balance.status) {
            PaymentStatus.PARTIAL -> {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextTab(text = "Accept the shortfall and close", selected = false, onClick = { confirmAccept = true })
                }
            }
            PaymentStatus.SETTLED -> {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextTab(text = "Reopen — the rest is still owed", selected = false, onClick = { onSetShortfallAccepted(false) })
                }
            }
            else -> Unit
        }
    }

    if (confirmAccept) {
        AlertDialog(
            onDismissRequest = { confirmAccept = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            title = { Text("Close ${money(balance.outstandingCents)} short?", style = MaterialTheme.typography.titleLarge, color = cs.onSurface) },
            text = {
                Text(
                    "It will no longer count as owed. The difference stays on record, and you can reopen it any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(onClick = { confirmAccept = false; onSetShortfallAccepted(true) }, shape = CircleShape) {
                    Text("Close it", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = { TextButton(onClick = { confirmAccept = false }) { Text("Cancel") } }
        )
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            title = { Text("Delete this payment?", style = MaterialTheme.typography.titleLarge, color = cs.onSurface) },
            text = {
                Text(
                    "${money(p.amountCents)} received on ${p.day.format(paymentDateFmt)} will be removed, " +
                        "and that amount will show as owed again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = null; onDeletePayment(p.id) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accents.negative)
                ) { Text("Delete", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

/**
 * One pay period in full: what the app calculates (salary or regular pay,
 * overtime, extras), what is in the wallet for it, what arrived, and hours
 * worked against hours paid. Adding the calculated pay is one tap; nothing is
 * added otherwise.
 */
@Composable
private fun PeriodStatementSheet(
    statement: PeriodStatement,
    profile: JobProfile,
    currencySymbol: String,
    money: (Long) -> String,
    onAddExpected: suspend (List<Earning>) -> String?,
    onOpenEarning: (Long) -> Unit,
    onShowEarnings: (DateRange) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val calc = statement.calculated
    val recorded = statement.recorded

    AppSheet(
        onDismiss = onDismiss,
        sheetState = rememberAppSheetState(),
        title = statement.range.label(),
        caption = "${profile.name} · ${profile.pay.mode.label}"
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(18.dp))
            if (calc != null) {
                SheetLabel("Calculated")
                Spacer(Modifier.height(10.dp))
                PayBreakdown(calc, currencySymbol)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${hoursLabel(calc.hoursWorked)} worked · ${calc.shiftsWorked} shift${if (calc.shiftsWorked == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            } else {
                Text(
                    "A custom period: the app calculates pay for whole pay periods only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            SheetLabel("In your wallet")
            Spacer(Modifier.height(6.dp))
            if (recorded == null) {
                EmptyHint("Nothing added for this period yet.")
            } else {
                recorded.balances.forEachIndexed { i, b ->
                    if (i > 0) RowRule()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onOpenEarning(b.earning.id) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TypeBadge(b.earning.type, size = 34)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.earning.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${money(b.paidCents)} of ${money(b.earning.amountCents)}" +
                                    (b.earning.hours?.let { " · ${hoursLabel(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        StatusPill(b.status)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val t = recorded.totals
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(cs.background)
                        .border(1.dp, cs.outline, MaterialTheme.shapes.large)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row {
                        HeroFigure("Expected", money(t.earnedCents), cs.onSurface, Modifier.weight(1f))
                        HeroFigure("Received", money(t.receivedCents), accents.positive, Modifier.weight(1f))
                    }
                    if (t.receivedCents > 0L || t.outstandingCents > 0L) {
                        Row {
                            HeroFigure(
                                "Difference", signedCents(t.differenceCents, currencySymbol),
                                when {
                                    t.differenceCents < 0L -> accents.negative
                                    t.differenceCents > 0L -> accents.positive
                                    else -> cs.onSurface
                                },
                                Modifier.weight(1f)
                            )
                            HeroFigure(
                                "Still owed", money(t.outstandingCents),
                                if (t.outstandingCents > 0L) accents.negative else cs.onSurface,
                                Modifier.weight(1f)
                            )
                        }
                    }
                    val due = statement.hoursDue
                    val paid = statement.hoursPaid
                    if (due != null || paid != null) {
                        Row {
                            HeroFigure("Hours worked", due?.let { hoursLabel(it) } ?: "—", cs.onSurface, Modifier.weight(1f))
                            HeroFigure("Hours paid", paid?.let { hoursLabel(it) } ?: "Not recorded", cs.onSurface, Modifier.weight(1f))
                        }
                        statement.hoursUnpaid?.takeIf { it > 0.0 }?.let {
                            Text(
                                "${hoursLabel(it)} worked but not covered by any payment",
                                style = MaterialTheme.typography.bodySmall,
                                color = accents.warning
                            )
                        }
                    }
                }
                if (calc != null && !statement.canAddExpected && calc.expectedCents != t.earnedCents) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The app now calculates ${money(calc.expectedCents)} for this period; your wallet holds ${money(t.earnedCents)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = accents.negative)
            }

            Spacer(Modifier.height(18.dp))
            if (statement.canAddExpected && calc != null) {
                val lines = remember(calc) { Statements.expectedEarnings(profile, calc) }
                SheetActionButton(
                    text = if (adding) "Adding…" else "Add ${money(lines.sumOf { it.amountCents })} to wallet",
                    icon = AppIcons.Add
                ) {
                    if (!adding) {
                        adding = true
                        scope.launch {
                            error = onAddExpected(lines)
                            adding = false
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    lines.joinToString(" + ") { "${it.title} ${money(it.amountCents)}" } +
                        ". You can change any of it afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
            }
            if (recorded != null) {
                SecondaryAction(text = "Show in Earnings", icon = AppIcons.Wallet, onClick = { onShowEarnings(statement.range) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EarningEditorSheet(
    /** Null adds a new earning. */
    editing: EarningBalance?,
    profile: JobProfile,
    shifts: Map<LocalDate, List<Shift>>,
    weekStart: java.time.DayOfWeek,
    currencySymbol: String,
    money: (Long) -> String,
    onSave: suspend (Earning) -> String?,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val scope = rememberCoroutineScope()
    val sheetState = rememberAppSheetState()
    val today = remember { LocalDate.now() }
    val original = editing?.earning
    val pay = profile.payPeriod

    var type by rememberSaveable {
        mutableStateOf(original?.type ?: if (profile.pay.isFixed) EarningType.SALARY else EarningType.SHIFT_PAY)
    }
    var hoursText by rememberSaveable { mutableStateOf(original?.hours?.let(::formatDecimalInput) ?: "") }
    var amountText by rememberSaveable { mutableStateOf(original?.amountCents?.let(::centsToInput) ?: "") }
    var description by rememberSaveable { mutableStateOf(original?.description ?: "") }
    val defaultPeriod = remember(pay, today) { pay.periodContaining(today) }
    var start by rememberSaveable { mutableStateOf(original?.periodStart ?: defaultPeriod.start.toString()) }
    var end by rememberSaveable { mutableStateOf(original?.periodEnd ?: defaultPeriod.end.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showRangePicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val range = DateRange(LocalDate.parse(start), LocalDate.parse(end))
    val presets = remember(pay, today) {
        val thisPay = pay.periodContaining(today)
        val thisMonth = YearMonth.from(today)
        listOf(
            "This pay period" to thisPay,
            "Last pay period" to pay.previous(thisPay),
            "This month" to DateRange(thisMonth.atDay(1), thisMonth.atEndOfMonth()),
            "Last month" to thisMonth.minusMonths(1).let { DateRange(it.atDay(1), it.atEndOfMonth()) }
        ).distinctBy { it.second }
    }
    // What the app works out for this period and this type — the same
    // calculation as Home and the pay periods — offered as a starting point.
    val calculated = remember(profile, shifts, range, weekStart) { Compensation.forRange(profile, shifts, range, weekStart) }
    val suggestion: Pair<Long, Double?>? = remember(calculated, type) {
        val overtimeLine = calculated.compensation == OvertimeCompensation.PAID && (calculated.overtimeCents ?: 0L) > 0L
        when (type) {
            EarningType.SALARY, EarningType.SHIFT_PAY -> calculated.baseCents to
                (calculated.hoursWorked + calculated.paidLeaveHours - if (overtimeLine) calculated.overtimeHours else 0.0)
            EarningType.OVERTIME -> (calculated.overtimeCents ?: 0L) to calculated.overtimeHours
            EarningType.BONUS, EarningType.TIPS -> calculated.extrasCents to null
            else -> null
        }?.takeIf { it.first > 0L }
    }

    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = if (original == null) "Add earning" else "Edit earning",
        caption = if (original == null) "Money you are owed for work done" else original.title
    ) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(20.dp))
            FieldLabel("Type")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EarningType.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            FilterPill(text = t.label, selected = t == type, onClick = { type = t }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("Period worked")
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (label, preset) ->
                    FilterPill(
                        text = label,
                        selected = preset == range,
                        onClick = { start = preset.start.toString(); end = preset.end.toString() }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(cs.background)
                    .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
                    .clickable { showRangePicker = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("From – to", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text(range.label(), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                }
                Icon(AppIcons.Calendar, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("Amount")
            Spacer(Modifier.height(10.dp))
            FormTextField(
                value = amountText,
                onValueChange = { amountText = it; error = null },
                placeholder = "0.00",
                singleLine = true,
                keyboardType = KeyboardType.Decimal,
                prefix = currencySymbol
            )
            suggestion?.takeIf { parseCents(amountText) != it.first }?.let { (cents, hours) ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Calculated for this period: ${money(cents)}" +
                            (hours?.takeIf { it > 0.0 }?.let { " · ${hoursLabel(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextTab(text = "Use", selected = false, onClick = {
                        amountText = centsToInput(cents)
                        hours?.takeIf { it > 0.0 }?.let { hoursText = formatDecimalInput(Math.round(it * 100) / 100.0) }
                        error = null
                    })
                }
            }
            if (editing != null && editing.paidCents > 0L) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${money(editing.paidCents)} already received for this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("Hours", optional = true)
            Spacer(Modifier.height(6.dp))
            Text(
                "The hours this amount is for. Payments record the hours they cover, so the two can be compared.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            FormTextField(
                value = hoursText,
                onValueChange = { hoursText = it; error = null },
                placeholder = "e.g. 176",
                singleLine = true,
                keyboardType = KeyboardType.Decimal,
                suffix = "h"
            )

            Spacer(Modifier.height(20.dp))
            FieldLabel("Description", optional = true)
            Spacer(Modifier.height(10.dp))
            FormTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = "e.g. October salary, Saturday cover",
                singleLine = true
            )

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = accents.negative)
            }

            Spacer(Modifier.height(22.dp))
            PrimaryAction(
                text = when {
                    saving -> "Saving…"
                    original == null -> "Add earning"
                    else -> "Save changes"
                },
                enabled = !saving
            ) {
                val cents = parseCents(amountText)
                val hours = parseHours(hoursText)
                if (hoursText.isNotBlank() && hours == null) { error = "Hours must be a number"; return@PrimaryAction }
                val localError = LedgerRules.earningError(cents, range.start, range.end, hours)
                if (localError != null) { error = localError; return@PrimaryAction }
                saving = true
                scope.launch {
                    val earning = Earning(
                        id = original?.id ?: 0L,
                        jobId = original?.jobId ?: profile.id,
                        type = type,
                        description = description.trim(),
                        amountCents = cents!!,
                        periodStart = range.start.toString(),
                        periodEnd = range.end.toString(),
                        createdAt = original?.createdAt ?: 0L,
                        hours = hours,
                        shortfallAccepted = original?.shortfallAccepted ?: false
                    )
                    val problem = onSave(earning)
                    if (problem != null) {
                        error = problem
                        saving = false
                    } else {
                        sheetState.hide()
                        onDismiss()
                    }
                }
            }

            if (original != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable { confirmDelete = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Delete earning", style = MaterialTheme.typography.labelLarge, color = accents.negative)
                }
            }
        }
    }

    if (showRangePicker) {
        val rangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = range.start.toEpochDay() * 86_400_000L,
            initialSelectedEndDateMillis = range.end.toEpochDay() * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = rangeState.selectedStartDateMillis?.let { LocalDate.ofEpochDay(it / 86_400_000L) }
                        if (s != null) {
                            val e = rangeState.selectedEndDateMillis
                                ?.let { LocalDate.ofEpochDay(it / 86_400_000L) }
                                ?.takeIf { !it.isBefore(s) } ?: s
                            start = s.toString()
                            end = e.toString()
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

    if (confirmDelete && original != null) {
        val payments = editing.payments.size
        val received = editing.paidCents
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            title = { Text("Delete \"${original.title}\"?", style = MaterialTheme.typography.titleLarge, color = cs.onSurface) },
            text = {
                Text(
                    if (payments == 0) "This earning will be removed from your wallet."
                    else "Its $payments payment${if (payments == 1) "" else "s"} " +
                        "(${money(received)}) will be deleted with it. This can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            sheetState.hide()
                            onDelete(original.id)
                            onDismiss()
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accents.negative)
                ) { Text("Delete", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSheet(
    balance: EarningBalance,
    currencySymbol: String,
    money: (Long) -> String,
    onSave: suspend (Payment) -> String?,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val scope = rememberCoroutineScope()
    val sheetState = rememberAppSheetState()
    val today = remember { LocalDate.now() }
    val owed = balance.outstandingCents

    // Starts at the full amount owed — the usual case — and is easy to change.
    var amountText by rememberSaveable { mutableStateOf(centsToInput(owed)) }
    // Prefilled with the hours not yet paid for, when the earning says how many it is for.
    val hoursLeft = balance.earning.hours?.let { it - (balance.hoursPaid ?: 0.0) }?.takeIf { it > 0.0 }
    var hoursText by rememberSaveable { mutableStateOf(hoursLeft?.let { formatDecimalInput(Math.round(it * 100) / 100.0) } ?: "") }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var note by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = "Record payment",
        caption = "${balance.earning.title} · ${money(owed)} owed"
    ) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(20.dp))
            FieldLabel("Amount received")
            Spacer(Modifier.height(10.dp))
            FormTextField(
                value = amountText,
                onValueChange = { amountText = it; error = null },
                placeholder = "0.00",
                singleLine = true,
                keyboardType = KeyboardType.Decimal,
                prefix = currencySymbol
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill(
                    text = "All of it · ${money(owed)}",
                    selected = parseCents(amountText) == owed,
                    onClick = { amountText = centsToInput(owed); error = null }
                )
                if (owed >= 2L) {
                    FilterPill(
                        text = "Half",
                        selected = parseCents(amountText) == owed / 2,
                        onClick = { amountText = centsToInput(owed / 2); error = null }
                    )
                }
            }
            // Employers pay what they pay: say what this amount means before it is saved.
            parseCents(amountText)?.takeIf { it > 0L }?.let { cents ->
                val diff = cents - owed
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        diff < 0L -> "${money(-diff)} will still be owed"
                        diff > 0L -> "${money(diff)} more than expected — it will show as overpaid"
                        else -> "Pays it in full"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        diff < 0L -> cs.onSurfaceVariant
                        diff > 0L -> accents.positive
                        else -> accents.positive
                    }
                )
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("Hours paid", optional = true)
            Spacer(Modifier.height(6.dp))
            Text(
                "The hours this payment covers, as your employer paid them — they can differ from the hours you worked.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            FormTextField(
                value = hoursText,
                onValueChange = { hoursText = it; error = null },
                placeholder = "e.g. 172",
                singleLine = true,
                keyboardType = KeyboardType.Decimal,
                suffix = "h"
            )

            Spacer(Modifier.height(20.dp))
            FieldLabel("Date received")
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(cs.background)
                    .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
                    .clickable { showDatePicker = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    LocalDate.parse(date).format(paymentDateFmt),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(AppIcons.Calendar, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.height(20.dp))
            FieldLabel("Note", optional = true)
            Spacer(Modifier.height(10.dp))
            FormTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = "e.g. Bank transfer, cash",
                singleLine = true
            )

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = accents.negative)
            }

            Spacer(Modifier.height(22.dp))
            PrimaryAction(text = if (saving) "Saving…" else "Save payment", enabled = !saving) {
                val cents = parseCents(amountText)
                val day = LocalDate.parse(date)
                val hours = parseHours(hoursText)
                if (hoursText.isNotBlank() && hours == null) { error = "Hours paid must be a number"; return@PrimaryAction }
                val localError = LedgerRules.paymentError(cents, owed, day, today, hours)
                if (localError != null) { error = localError; return@PrimaryAction }
                saving = true
                scope.launch {
                    val problem = onSave(
                        Payment(
                            earningId = balance.earning.id, amountCents = cents!!, date = day.toString(),
                            note = note.trim(), hoursPaid = hours
                        )
                    )
                    if (problem != null) {
                        error = problem
                        saving = false
                    } else {
                        sheetState.hide()
                        onDismiss()
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val todayMillis = today.toEpochDay() * 86_400_000L
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(date).toEpochDay() * 86_400_000L,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { date = LocalDate.ofEpochDay(it / 86_400_000L).toString() }
                    error = null
                    showDatePicker = false
                }) { Text("OK", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun PaymentHistoryRow(entry: PaymentEntry, money: (Long) -> String, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypeBadge(entry.earning.type, size = 34)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.earning.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entry.payment.day.format(paymentDateFmt) +
                    (entry.payment.hoursPaid?.let { " · ${hoursLabel(it)}" } ?: "") +
                    (entry.payment.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text("+${money(entry.payment.amountCents)}", style = MaterialTheme.typography.titleSmall, color = AppTheme.accents.positive)
    }
}

// ══════════════════════════════════════════════════════════════
//  Small pieces
// ══════════════════════════════════════════════════════════════

@Composable
private fun EarningType.icon(): Painter = when (this) {
    EarningType.SALARY -> AppIcons.Wallet
    EarningType.SHIFT_PAY -> AppIcons.NavShifts
    EarningType.OVERTIME -> AppIcons.Clock
    EarningType.EXTRA_SHIFT -> AppIcons.Calendar
    EarningType.BONUS -> AppIcons.SetRate
    EarningType.TIPS -> AppIcons.SetCurrency
    EarningType.OTHER -> AppIcons.Edit
}

@Composable
private fun EarningType.tint(): Color {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    return when (this) {
        EarningType.SALARY -> cs.primary
        EarningType.SHIFT_PAY -> cs.tertiary
        EarningType.OVERTIME -> accents.evening
        EarningType.EXTRA_SHIFT -> accents.morning
        EarningType.BONUS -> accents.night
        EarningType.TIPS -> accents.positive
        EarningType.OTHER -> cs.secondary
    }
}

@Composable
private fun TypeBadge(type: EarningType, size: Int = 40) {
    val tint = type.tint()
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(type.icon(), contentDescription = type.label, tint = tint, modifier = Modifier.size((size * 0.48f).dp))
    }
}

@Composable
private fun StatusPill(status: PaymentStatus) {
    val accents = AppTheme.accents
    val color = when (status) {
        PaymentStatus.PAID -> accents.positive
        PaymentStatus.PARTIAL -> accents.warning
        PaymentStatus.UNPAID -> accents.negative
        PaymentStatus.SETTLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(status.label, color)
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun ProgressTrack(fraction: Float, fill: Color, track: Color, height: androidx.compose.ui.unit.Dp = 8.dp) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "ledgerProgress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track)
    ) {
        if (shown > 0f) {
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(fill))
        }
    }
}

/**
 * A header that slides up out of the way as content below it scrolls up, and
 * back down on the first scroll down (Material's "enter always"). It only ever
 * moves by the scroll the content asked for, so it never jumps; a release
 * part-way settles it fully open or fully closed.
 */
private class CollapsingHeaderState {
    var height by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)

    fun expand() {
        offset = 0f
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val before = offset
            offset = (offset - available.y).coerceIn(0f, height)
            return Offset(0f, before - offset)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (offset > 0f && offset < height) {
                val target = if (offset > height / 2) height else 0f
                animate(offset, target, animationSpec = tween(180)) { value, _ -> offset = value }
            }
            return Velocity.Zero
        }
    }
}

/** Lays [state]'s header out at its visible height only, so the content below gets the rest. */
private fun Modifier.collapsing(state: CollapsingHeaderState): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val full = placeable.height.toFloat()
        if (state.height != full) state.height = full
        val shift = state.offset.coerceAtMost(full).roundToInt()
        layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
    }

@Composable
private fun RowRule() = GroupDivider()

@Composable
private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    PrimaryPillButton(text = text, enabled = enabled, onClick = onClick)

@Composable
private fun SecondaryAction(text: String, icon: Painter, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
        color = cs.background,
        contentColor = cs.onSurface,
        border = androidx.compose.foundation.BorderStroke(HairlineWidth, cs.softEdge)
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
