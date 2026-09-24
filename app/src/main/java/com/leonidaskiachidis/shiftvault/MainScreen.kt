package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Trimmed text metrics — keeps the tiny labels optically centred.
private val flatTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

private val monthTitleFmt = DateTimeFormatter.ofPattern("MMMM")
private val dayLabelFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")

/**
 * Swallows every scroll and fling the sheet body doesn't use, so the sheet's own
 * nested-scroll connection never sees leftover delta. The content scrolls to its
 * end without dragging the sheet, and only the handle/header can dismiss it.
 */
internal val SheetScrollLock = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

private fun greetingFor(hour: Int): String = when {
    hour < 12 -> "Good morning,"
    hour < 18 -> "Good afternoon,"
    else -> "Good evening,"
}

internal fun shiftTimeLabel(shift: Shift): String =
    if (shift.startTime == "00:00" && shift.endTime == "23:59") "All day"
    else "${shift.startTime} — ${shift.endTime}"

internal fun hoursLabel(hours: Double): String =
    if (hours % 1.0 == 0.0) "${hours.toInt()}h" else String.format("%.1fh", hours)

/** 42 consecutive days (6 rows) starting on the first visible weekday. */
private fun buildMonthCells(month: YearMonth, startOfWeek: String): List<LocalDate> {
    val first = month.atDay(1)
    val skip = if (startOfWeek == "Sunday") first.dayOfWeek.value % 7
    else first.dayOfWeek.value - 1
    val gridStart = first.minusDays(skip.toLong())
    return List(42) { gridStart.plusDays(it.toLong()) }
}

// ══════════════════════════════════════════════════════════════
//  Screen
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onSettingsClick: () -> Unit,
    onCreateCustomClick: (LocalDate) -> Unit,
    startOfWeek: String = "Monday",
    currencyCode: String = "EUR",
    userName: String = "",
    /**
     * Saved shifts for the active profile only — this screen just lists them,
     * so there is no index to keep in step with the full library.
     */
    customTemplates: List<Shift> = emptyList(),
    /**
     * The *whole* calendar, every profile included. The grid shows only the
     * active profile's shifts, but writes have to carry the rest through
     * untouched or switching jobs would erase the others.
     */
    shifts: Map<LocalDate, List<Shift>> = emptyMap(),
    /** The app-wide selection. Every shift this screen writes is filed under it. */
    activeProfile: JobProfile = JobProfile.Default,
    onOpenProfiles: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shiftManager = remember { ShiftManager(context) }

    val currencySymbol = remember(currencyCode) {
        try { java.util.Currency.getInstance(currencyCode).getSymbol(java.util.Locale.getDefault()) }
        catch (_: Exception) { currencyCode }
    }

    // Page `startPage` is the month the pager was opened on. Both the page →
    // month mapping and "Jump to today" read this one anchor, so they can't
    // disagree after the month rolls over while the app is open.
    val startPage = Int.MAX_VALUE / 2
    val anchorMonth = remember { YearMonth.now() }
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { Int.MAX_VALUE })
    val currentMonth by remember {
        derivedStateOf { anchorMonth.plusMonths((pagerState.currentPage - startPage).toLong()) }
    }

    // Mirrors `shifts`, but can run ahead of it: tapping days in paste or eraser
    // mode has to repaint on the next frame, not after DataStore has written and
    // re-decoded the whole map. Holds every profile's shifts — see the param docs.
    var savedShifts by remember { mutableStateOf(shifts) }

    // Writes still on their way to DataStore. While any are in flight, a value
    // arriving from storage is older than what is on screen, so adopting it
    // would briefly undo the later taps.
    var pendingWrites by remember { mutableIntStateOf(0) }
    val settled = pendingWrites == 0

    // Keyed on `settled` as well, so a stored value that arrived while writes
    // were still pending is adopted the moment the last one completes.
    LaunchedEffect(shifts, settled) { if (settled) savedShifts = shifts }

    /**
     * The single path every edit on this screen takes. [transform] is applied
     * to the on-screen map at once, and then again inside ShiftManager to
     * whatever is actually stored, so overlapping taps each land on the latest
     * data instead of overwriting one another.
     */
    val applyUpdate: (transform: (Map<LocalDate, List<Shift>>) -> Map<LocalDate, List<Shift>>) -> Unit =
        { transform ->
            savedShifts = transform(savedShifts)
            pendingWrites++
            scope.launch {
                try {
                    shiftManager.updateShifts(transform)
                } finally {
                    pendingWrites--
                }
            }
        }

    val activeProfileId = activeProfile.id

    val snackbarHostState = remember { SnackbarHostState() }

    /** Shows [message] with an Undo action; runs [onUndo] if it is tapped. */
    fun offerUndo(message: String, onUndo: () -> Unit) {
        scope.launch {
            // A newer action replaces the older offer rather than queueing behind it.
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    /**
     * Puts back [originals] — each day's shifts for this job as they were —
     * leaving every other job, and every other day, as it is now.
     */
    fun restoreDays(originals: Map<LocalDate, List<Shift>>) {
        if (originals.isEmpty()) return
        applyUpdate { map ->
            originals.entries.fold(map) { acc, (date, slice) ->
                ProfileScope.withDaySlice(acc, date, activeProfileId, slice)
            }
        }
    }

    /**
     * [applyUpdate] plus an Undo that restores exactly the days [transform]
     * changed for the active job. Used by deletes and rotation fills.
     */
    fun applyWithUndo(
        message: (changedDays: Int) -> String,
        transform: (Map<LocalDate, List<Shift>>) -> Map<LocalDate, List<Shift>>
    ) {
        val before = savedShifts
        val changed = changedDays(
            ProfileScope.shifts(before, activeProfileId),
            ProfileScope.shifts(transform(before), activeProfileId)
        )
        if (changed.isEmpty()) return
        val originals = changed.associateWith { ProfileScope.daySlice(before, it, activeProfileId) }
        applyUpdate(transform)
        offerUndo(message(changed.size)) { restoreDays(originals) }
    }

    // Erase mode collects every day it clears, so one Undo brings back the
    // whole session rather than only the last tap. Reset on entering the mode.
    val erasedDays = remember { mutableStateMapOf<LocalDate, List<Shift>>() }

    // What the grid actually draws: the active job's shifts, nothing else.
    val visibleShifts = remember(savedShifts, activeProfileId) {
        ProfileScope.shifts(savedShifts, activeProfileId)
    }

    // Saveable, so a rotation or the system flipping to dark mode doesn't
    // close whatever the user had open.
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showDaySheet by rememberSaveable { mutableStateOf(false) }
    var showQuickSheet by rememberSaveable { mutableStateOf(false) }
    var showPickToPasteSheet by rememberSaveable { mutableStateOf(false) }
    var selectedDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Edit dialog state
    var editingShift by rememberSaveable(stateSaver = jsonSaver<Shift?>()) { mutableStateOf(null) }
    var editingIndex by rememberSaveable { mutableIntStateOf(-1) }

    // Bulk modes
    var clipboardShift by rememberSaveable(stateSaver = jsonSaver<Shift?>()) { mutableStateOf(null) }
    var isEraserMode by rememberSaveable { mutableStateOf(false) }
    val isPasteMode by remember { derivedStateOf { clipboardShift != null } }
    val isActiveMode = isPasteMode || isEraserMode

    val now by rememberNow()
    val today = now.toLocalDate()

    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents

    val greeting = greetingFor(now.hour)

    val weekDays = remember(startOfWeek) {
        if (startOfWeek == "Sunday") listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
        else listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(cs.background)
                .statusBarsPadding()
        ) {
            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // The date used to sit here. The job you are logging against is
                    // the more load-bearing fact once there is more than one of
                    // them — and today's date is a tap away in the month bar below.
                    ProfileChip(profile = activeProfile, onClick = onOpenProfiles)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.displayMedium,
                        color = cs.onBackground
                    )
                }
                Spacer(Modifier.width(12.dp))
                // The month bar shows the month alone now; the year lives here, as a
                // quiet pill. It tracks the month you are *browsing*, not today, so
                // paging into another year is never ambiguous. Reading the pager
                // state inside the pill keeps the rest of the header off the
                // recomposition path.
                YearPill(year = { currentMonth.year })
                Spacer(Modifier.width(10.dp))
                CircleIconButton(
                    icon = AppIcons.EditCalendar,
                    contentDescription = "Quick actions",
                    onClick = { showQuickSheet = true }
                )
            }

            // ── Totals: this week and the pay period you are in ──
            //
            // Read from the on-screen map, so a pasted or erased day moves the
            // numbers on the same frame. Both follow today, not the month being
            // browsed: they answer "how is this week going / what's my next pay?".
            val week = remember(today, startOfWeek) { weekContaining(today, startOfWeek) }
            val payRange = remember(today, activeProfile.payPeriod) {
                activeProfile.payPeriod.periodContaining(today)
            }
            val weekTotals = remember(visibleShifts, week) { PeriodTotals.of(visibleShifts, week) }
            val payTotals = remember(visibleShifts, payRange) { PeriodTotals.of(visibleShifts, payRange) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TotalsTile(
                    title = "This week",
                    range = week.shortLabel(),
                    totals = weekTotals,
                    currencySymbol = currencySymbol,
                    modifier = Modifier.weight(1f)
                )
                TotalsTile(
                    title = "Pay period",
                    range = payRange.shortLabel(),
                    totals = payTotals,
                    currencySymbol = currencySymbol,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Active-mode banner (paste / erase) ──
            //
            // The text and tint are latched while the banner is up: leaving the mode
            // clears the very state the banner describes, so without this it would
            // flip to the other mode's wording for the length of the exit.
            var bannerContent by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
            if (isActiveMode) {
                val next = isEraserMode to
                        if (isEraserMode) "Tap a day to clear it"
                        else "Tap days to add \"${clipboardShift?.name ?: ""}\""
                if (bannerContent != next) bannerContent = next
            }

            AnimatedVisibility(
                visible = isActiveMode,
                enter = expandVertically(animationSpec = tween(160)) + fadeIn(tween(160)),
                exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(tween(100))
            ) {
                val (eraser, message) = bannerContent ?: (false to "")
                val accent = if (eraser) accents.negative else cs.primary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpace.screenH)
                        .padding(bottom = 6.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (eraser) AppIcons.Delete else AppIcons.ContentCopy,
                        contentDescription = null, tint = accent, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.labelMedium, color = accent,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        AppIcons.Close, contentDescription = "Done", tint = accent,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { clipboardShift = null; isEraserMode = false }
                    )
                }
            }

            // ── Month bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    icon = AppIcons.ChevronLeft,
                    contentDescription = "Previous month",
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                    diameter = 44.dp,
                    iconSize = 19.dp,
                    container = cs.surfaceVariant,
                    tint = cs.onSurfaceVariant,
                    elevation = 0.dp
                )
                MonthTitle(
                    month = { currentMonth },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                CircleIconButton(
                    icon = AppIcons.ChevronRight,
                    contentDescription = "Next month",
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    diameter = 44.dp,
                    iconSize = 19.dp,
                    container = cs.surfaceVariant,
                    tint = cs.onSurfaceVariant,
                    elevation = 0.dp
                )
            }

            // ── Weekday strip ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(bottom = 8.dp)
            ) {
                weekDays.forEach { label ->
                    val weekend = label == "SAT" || label == "SUN"
                    Text(
                        label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (weekend) cs.onSurface else cs.onSurfaceVariant
                    )
                }
            }

            // ── Month grid ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = AppSpace.screenH)
                    .padding(bottom = 16.dp),
                key = { it },
                // Without this the two grids butt straight into each other mid-swipe
                // and read as one continuous sheet.
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1
            ) { page ->
                val monthForPage = remember(page) { anchorMonth.plusMonths((page - startPage).toLong()) }
                val cells = remember(monthForPage, startOfWeek) { buildMonthCells(monthForPage, startOfWeek) }
                val weeks = remember(cells) { cells.chunked(7) }

                // Hoisted once per page — shared by all 42 cells.
                val gridLine = cs.outline
                val weekendWash = accents.weekendWash
                val isDark = accents.isDark

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.large)
                        .background(cs.surface)
                ) {
                    weeks.forEachIndexed { rowIndex, week ->
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            week.forEachIndexed { colIndex, date ->
                                DayCell(
                                    date = date,
                                    inMonth = YearMonth.from(date) == monthForPage,
                                    isToday = date == today,
                                    dayShifts = visibleShifts[date] ?: emptyList(),
                                    gridLine = gridLine,
                                    weekendWash = weekendWash,
                                    isDark = isDark,
                                    drawTopRule = rowIndex > 0,
                                    drawStartRule = colIndex > 0,
                                    cs = cs,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val pasted = clipboardShift
                                        when {
                                            isEraserMode -> {
                                                val slice = ProfileScope.daySlice(savedShifts, date, activeProfileId)
                                                if (slice.isNotEmpty()) {
                                                    if (date !in erasedDays) erasedDays[date] = slice
                                                    applyUpdate { map ->
                                                        ProfileScope.withDaySlice(map, date, activeProfileId, emptyList())
                                                    }
                                                    val count = erasedDays.size
                                                    offerUndo("Cleared $count day${if (count == 1) "" else "s"}") {
                                                        restoreDays(erasedDays.toMap())
                                                        erasedDays.clear()
                                                    }
                                                }
                                            }
                                            pasted != null -> applyUpdate { map ->
                                                val updated = ProfileScope.daySlice(map, date, activeProfileId) +
                                                        pasted.copy(jobId = activeProfileId)
                                                ProfileScope.withDaySlice(map, date, activeProfileId, updated)
                                            }
                                            else -> {
                                                selectedDate = date
                                                if ((visibleShifts[date] ?: emptyList()).isNotEmpty()) showDaySheet = true
                                                else showAddSheet = true
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floats over the calendar, just above the bottom bar.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }

    // ─────────────────────────── Sheets & dialogs ───────────────────────────

    if (showAddSheet) {
        AppSheet(
            onDismiss = { showAddSheet = false },
            sheetState = sheetState,
            title = "Add a shift",
            caption = selectedDate?.format(dayLabelFmt)
        ) {
            Spacer(Modifier.height(18.dp))

            if (customTemplates.isNotEmpty()) {
                SheetLabel("Saved shifts")
                Spacer(Modifier.height(10.dp))
            }

            // "New shift" is the last row of the list rather than a fixed
            // header: it is one more thing you can pick here, and it scrolls
            // with the rest instead of pinning the top of the sheet.
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .nestedScroll(SheetScrollLock)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (customTemplates.isEmpty()) {
                    EmptyHint("No saved shifts yet — create one to reuse it later.")
                }

                customTemplates.forEach { entry ->
                    ShiftPickRow(entry) {
                        scope.launch {
                            sheetState.hide(); showAddSheet = false
                            selectedDate?.let { date ->
                                applyUpdate { map ->
                                    val updated = ProfileScope.daySlice(map, date, activeProfileId) +
                                            entry.copy(jobId = activeProfileId)
                                    ProfileScope.withDaySlice(map, date, activeProfileId, updated)
                                }
                            }
                        }
                    }
                }

                NewShiftRow {
                    scope.launch {
                        sheetState.hide(); showAddSheet = false
                        selectedDate?.let(onCreateCustomClick)
                    }
                }
            }
        }
    }

    if (showDaySheet) {
        val date = selectedDate ?: today
        val dayShifts = visibleShifts[date] ?: emptyList()
        AppSheet(
            onDismiss = { showDaySheet = false },
            sheetState = sheetState,
            title = "${dayShifts.size} shift${if (dayShifts.size != 1) "s" else ""}",
            caption = date.format(dayLabelFmt)
        ) {
            Spacer(Modifier.height(18.dp))

            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .nestedScroll(SheetScrollLock)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                dayShifts.forEachIndexed { index, shift ->
                    DayShiftRow(
                        shift = shift,
                        onEdit = { editingIndex = index; editingShift = shift.copy() },
                        onDelete = {
                            scope.launch {
                                if (dayShifts.size == 1) { sheetState.hide(); showDaySheet = false }
                                applyWithUndo({ "\"${shift.name}\" deleted" }) { map ->
                                    val slice = ProfileScope.daySlice(map, date, activeProfileId)
                                    val updated = if (index in slice.indices)
                                        slice.toMutableList().also { it.removeAt(index) }
                                    else slice
                                    ProfileScope.withDaySlice(map, date, activeProfileId, updated)
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SheetActionButton(
                text = "Add another shift",
                icon = AppIcons.Add
            ) {
                scope.launch {
                    sheetState.hide()      // slides the day sheet down
                    showDaySheet = false
                    showAddSheet = true    // the add sheet slides up from Hidden
                }
            }
            // The sheet is its own window, above the screen's snackbar, so an
            // Undo offered while it is open is shown here as well.
            SnackbarHost(snackbarHostState, Modifier.padding(top = 8.dp))
        }
    }

    if (showQuickSheet) {
        AppSheet(
            onDismiss = { showQuickSheet = false },
            sheetState = sheetState,
            title = "Quick actions"
        ) {
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(
                    AppIcons.Today, "Jump to today",
                    "Go back to the current month", cs.primary
                ) {
                    scope.launch {
                        sheetState.hide(); showQuickSheet = false
                        val monthsAway = ChronoUnit.MONTHS.between(anchorMonth, YearMonth.from(today))
                        pagerState.animateScrollToPage(startPage + monthsAway.toInt())
                    }
                }
                QuickAction(
                    AppIcons.ContentCopy, "Copy a shift",
                    "Tap days to add it to each one", cs.primary
                ) { scope.launch { sheetState.hide(); showQuickSheet = false; showPickToPasteSheet = true } }
                QuickAction(
                    AppIcons.Delete, "Erase days",
                    "Tap days to clear their shifts", accents.negative
                ) {
                    scope.launch {
                        sheetState.hide(); showQuickSheet = false
                        erasedDays.clear()
                        isEraserMode = true
                    }
                }
            }
        }
    }

    if (showPickToPasteSheet) {
        AppSheet(
            onDismiss = { showPickToPasteSheet = false },
            sheetState = sheetState,
            title = "Which shift?",
            caption = "Pick one, then tap the days you want it on"
        ) {
            Spacer(Modifier.height(18.dp))
            if (customTemplates.isEmpty()) {
                EmptyHint("No saved shifts yet.")
            } else {
                Column(
                    Modifier
                        .heightIn(max = 400.dp)
                        .nestedScroll(SheetScrollLock)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    customTemplates.forEach { shift ->
                        ShiftPickRow(shift) {
                            scope.launch {
                                sheetState.hide(); showPickToPasteSheet = false
                                clipboardShift = shift
                            }
                        }
                    }
                }
            }
        }
    }

    editingShift?.let { shift ->
        ShiftEditDialog(
            shift = shift,
            currencySymbol = currencySymbol,
            cs = cs,
            onDismiss = { editingShift = null },
            onSave = { updated ->
                val date = selectedDate
                val index = editingIndex
                if (date != null) {
                    applyUpdate { map ->
                        val list = ProfileScope.daySlice(map, date, activeProfileId).toMutableList()
                        if (index in list.indices) {
                            list[index] = updated.copy(jobId = activeProfileId)
                            ProfileScope.withDaySlice(map, date, activeProfileId, list)
                        } else map
                    }
                }
                editingShift = null
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  Totals tile
// ══════════════════════════════════════════════════════════════

@Composable
private fun TotalsTile(
    title: String,
    range: String,
    totals: PeriodTotals,
    currencySymbol: String,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(cs.surface)
            .then(
                if (AppTheme.accents.isDark) Modifier.border(1.dp, cs.outline, MaterialTheme.shapes.large)
                else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            Text(
                range,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$currencySymbol${"%,.2f".format(totals.earnings)}",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                hoursLabel(totals.hours),
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Month bar pieces
//
//  Both take the value as a lambda rather than a plain argument: the pager
//  state is then read inside these composables, so a swipe repaints the title
//  and the year and leaves the rest of the header alone.
// ══════════════════════════════════════════════════════════════

@Composable
private fun YearPill(year: () -> Int) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(CircleShape)
            .background(cs.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            year().toString(),
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun MonthTitle(month: () -> YearMonth, modifier: Modifier = Modifier) {
    Text(
        text = month().format(monthTitleFmt),
        modifier = modifier,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

// ══════════════════════════════════════════════════════════════
//  Calendar cell
// ══════════════════════════════════════════════════════════════

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    dayShifts: List<Shift>,
    gridLine: Color,
    weekendWash: Color,
    isDark: Boolean,
    drawTopRule: Boolean,
    drawStartRule: Boolean,
    cs: ColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Saturday and Sunday always, whatever the week starts on.
    val isWeekend = remember(date) {
        date.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                date.dayOfWeek == java.time.DayOfWeek.SUNDAY
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .drawBehind {
                if (isWeekend) drawRect(weekendWash)
                if (drawTopRule) {
                    drawLine(gridLine, Offset(0f, 0f), Offset(size.width, 0f), 1f)
                }
                if (drawStartRule) {
                    drawLine(gridLine, Offset(0f, 0f), Offset(0f, size.height), 1f)
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isToday) {
                Box(
                    Modifier
                        .size(24.dp)
                        .background(cs.primary.copy(alpha = 0.22f), CircleShape)
                )
                Box(
                    Modifier.size(22.dp).background(cs.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${date.dayOfMonth}", color = cs.onPrimary, fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold, style = flatTextStyle
                    )
                }
            } else {
                Text(
                    "${date.dayOfMonth}",
                    color = if (inMonth) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = if (inMonth) FontWeight.ExtraBold else FontWeight.Medium,
                    style = flatTextStyle
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        val shown = if (dayShifts.size > 2) dayShifts.subList(0, 2) else dayShifts
        shown.forEach { shift ->
            // Any colour can land here now that the form has a free picker, so
            // the stored value is normalised for this theme before it is drawn.
            val base = remember(shift.colorValue) { Color(shift.colorValue.toInt()) }
            val mark = remember(base, isDark) { ShiftColors.accent(base, isDark) }
            val ink = remember(base, isDark) { ShiftColors.ink(base, isDark) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(mark.copy(alpha = if (inMonth) 0.28f else 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    shift.name,
                    color = ink.copy(alpha = if (inMonth) 1f else 0.6f),
                    fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = flatTextStyle,
                    modifier = Modifier.padding(horizontal = 3.dp)
                )
            }
        }
        if (dayShifts.size > 2) {
            Text(
                "+${dayShifts.size - 2}",
                color = cs.onSurfaceVariant,
                fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, style = flatTextStyle
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Sheet shell & text
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState,
    title: String,
    caption: String? = null,
    body: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
        contentColor = cs.onSurface,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp)
        ) {
            // ── Draggable region: handle + title + date ──
            SheetTitle(title)
            if (caption != null) SheetCaption(caption)

            // ── Everything below swallows vertical drags, so it can't move the sheet ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, _ -> /* consumed on purpose */ }
                    },
                content = body
            )
        }
    }
}

@Composable
private fun SheetHandle() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(42.dp, 5.dp).clip(CircleShape).background(cs.outline))
    }
}

@Composable
private fun SheetTitle(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface
    )

@Composable
private fun SheetCaption(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 5.dp)
    )

@Composable
internal fun EmptyHint(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )

@Composable
internal fun SheetActionButton(text: String, icon: Painter, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        shadowElevation = 0.dp
    ) {
        Row(
            Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = cs.primary)
            Spacer(Modifier.width(9.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
    }
}

@Composable
internal fun SheetLabel(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

@Composable
private fun ShiftPickRow(shift: Shift, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val color = rememberShiftAccent(shift.colorValue)
    val hours = remember(shift) { PayEngine.payableHours(shift) }
    val secondary = remember(shift) {
        val base = when (shift.dayType) {
            DayType.WORKED -> shiftTimeLabel(shift)
            DayType.PAID_LEAVE -> "Paid leave"
            DayType.UNPAID_LEAVE -> "Unpaid leave"
        }
        if (shift.location.isBlank()) base else "$base · ${shift.location}"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(11.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                shift.name,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                hoursLabel(hours),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant
            )
            if (shift.payMultiplier != 1.0 && shift.dayType == DayType.WORKED) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "×" + "%.2f".format(shift.payMultiplier).trimEnd('0').trimEnd('.'),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.primary
                )
            }
        }
    }
}

/**
 * Sits at the end of the saved-shift list and is built to the same measurements
 * as [ShiftPickRow] — same corner, same padding, same leading mark — so it
 * belongs to the list. Only the tint sets it apart, which is what marks it as
 * the action rather than one of the saved shifts.
 */
@Composable
private fun NewShiftRow(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(cs.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Add, null, tint = cs.primary, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "New shift",
                style = MaterialTheme.typography.titleMedium,
                color = cs.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Create one and reuse it later",
                style = MaterialTheme.typography.bodySmall,
                color = cs.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            AppIcons.ChevronRight, null,
            tint = cs.primary, modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DayShiftRow(shift: Shift, onEdit: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val color = rememberShiftAccent(shift.colorValue)
    val hours = remember(shift) { PayEngine.payableHours(shift) }
    val detail = remember(shift) {
        val base = when (shift.dayType) {
            DayType.WORKED -> "${shiftTimeLabel(shift)} · ${hoursLabel(PayEngine.payableHours(shift))}"
            DayType.PAID_LEAVE -> "Paid leave · ${hoursLabel(PayEngine.payableHours(shift))}"
            DayType.UNPAID_LEAVE -> "Unpaid leave"
        }
        val withMultiplier =
            if (shift.payMultiplier != 1.0 && shift.dayType == DayType.WORKED)
                "$base · ×" + "%.2f".format(shift.payMultiplier).trimEnd('0').trimEnd('.')
            else base
        if (shift.location.isBlank()) withMultiplier else "$withMultiplier · ${shift.location}"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.large)
            .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(11.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                shift.name,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        RowActionButton(AppIcons.Edit, "Edit", cs.surfaceVariant, cs.onSurfaceVariant, onEdit)
        Spacer(Modifier.width(8.dp))
        RowActionButton(
            AppIcons.Delete, "Delete",
            accents.negative.copy(alpha = 0.12f), accents.negative, onDelete
        )
    }
}

@Composable
private fun RowActionButton(
    icon: Painter,
    desc: String,
    container: Color,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun QuickAction(
    icon: Painter,
    label: String,
    desc: String,
    color: Color,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Edit dialog
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftEditDialog(
    shift: Shift,
    currencySymbol: String,
    cs: ColorScheme,
    onDismiss: () -> Unit,
    onSave: (Shift) -> Unit
) {
    var draft by rememberSaveable(stateSaver = jsonSaver<Shift>()) { mutableStateOf(shift) }
    // The fields keep exactly what was typed. Re-deriving them from the parsed
    // number on every keystroke turned "12." into "12.0", so the next digit
    // produced 12.05, backspace could never remove the ".0", and 0.5 couldn't
    // be entered at all.
    var rateText by rememberSaveable {
        mutableStateOf(if (shift.hourlyRate > 0.0) formatDecimalInput(shift.hourlyRate) else "")
    }
    var breakText by rememberSaveable {
        mutableStateOf(if (shift.breakMinutes > 0) shift.breakMinutes.toString() else "")
    }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var pickingStart by rememberSaveable { mutableStateOf(true) }
    val color = rememberShiftAccent(draft.colorValue)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = cs.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(11.dp).background(color, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(draft.name, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeChip("Start", draft.startTime, cs, Modifier.weight(1f)) {
                        pickingStart = true; showTimePicker = true
                    }
                    TimeChip("End", draft.endTime, cs, Modifier.weight(1f)) {
                        pickingStart = false; showTimePicker = true
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = rateText,
                    onValueChange = {
                        rateText = it
                        draft = draft.copy(hourlyRate = parseDecimal(it) ?: 0.0)
                    },
                    label = { Text("Hourly rate") },
                    prefix = { Text(currencySymbol) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = breakText,
                    onValueChange = {
                        breakText = it
                        draft = draft.copy(breakMinutes = it.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    },
                    label = { Text("Break (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { draft = draft.copy(notes = it) },
                    label = { Text("Note") },
                    shape = MaterialTheme.shapes.small, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) }, shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary)
            ) { Text("Save", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showTimePicker) {
        val base = if (pickingStart) draft.startTime else draft.endTime
        val h = base.substringBefore(":").toIntOrNull() ?: 9
        val m = base.substringAfter(":").toIntOrNull() ?: 0
        val state = rememberTimePickerState(h, m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            text = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = state)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = "%02d:%02d".format(state.hour, state.minute)
                    draft = if (pickingStart) draft.copy(startTime = t) else draft.copy(endTime = t)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TimeChip(label: String, value: String, cs: ColorScheme, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ShiftVaultTheme {
        MainScreen(onSettingsClick = {}, onCreateCustomClick = {})
    }
}