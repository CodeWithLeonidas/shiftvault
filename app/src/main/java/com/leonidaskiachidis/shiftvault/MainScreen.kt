package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    /** Opens the full shift form on one calendar entry (index within the active job's day). */
    onEditEntry: (LocalDate, Int) -> Unit = { _, _ -> },
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
    /** Opens the job profile editor on [activeProfile] (goals, deductions). */
    onEditProfile: () -> Unit = {},
    /** Mark days whose schedule leaves too little rest (Settings switch). */
    restWarnings: Boolean = true,
    /**
     * A day to start adding a shift on — the "Add shift" launcher shortcut.
     * Handled once, then reported back through [onAddRequestHandled].
     */
    addRequest: LocalDate? = null,
    onAddRequestHandled: () -> Unit = {},
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
    val sheetState = rememberAppSheetState()

    // Bulk modes
    var clipboardShift by rememberSaveable(stateSaver = jsonSaver<Shift?>()) { mutableStateOf(null) }
    var isEraserMode by rememberSaveable { mutableStateOf(false) }
    var showRotation by rememberSaveable { mutableStateOf(false) }
    val isPasteMode by remember { derivedStateOf { clipboardShift != null } }
    val isActiveMode = isPasteMode || isEraserMode

    val now by rememberNow()
    val today = now.toLocalDate()

    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents

    // Every job's shifts count towards rest; only days the active job works
    // on are marked, so the calendar never flags a day it shows empty.
    val restIssues = remember(savedShifts, startOfWeek, restWarnings) {
        if (!restWarnings) emptyMap()
        else RestCheck.issues(
            savedShifts,
            if (startOfWeek == "Sunday") java.time.DayOfWeek.SUNDAY else java.time.DayOfWeek.MONDAY
        )
    }

    // The earnings strip's detail sheet, held as plain dates so it saves.
    var summaryStart by rememberSaveable { mutableStateOf<String?>(null) }
    var summaryEnd by rememberSaveable { mutableStateOf<String?>(null) }
    val summarySheetState = rememberAppSheetState()

    // The "Add shift" shortcut: the same as tapping that day on the calendar.
    LaunchedEffect(addRequest) {
        val date = addRequest ?: return@LaunchedEffect
        val monthsAway = ChronoUnit.MONTHS.between(anchorMonth, YearMonth.from(date)).toInt()
        pagerState.scrollToPage(startPage + monthsAway)
        selectedDate = date
        if ((visibleShifts[date] ?: emptyList()).isNotEmpty()) showDaySheet = true
        else showAddSheet = true
        onAddRequestHandled()
    }

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
            // ── Top bar: which job, which year, quick actions ──
            //
            // One slim row, so as much of the screen as possible goes to the
            // calendar itself.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileChip(profile = activeProfile, onClick = onOpenProfiles)
                Spacer(Modifier.weight(1f))
                // The year of the month being *browsed*, not today's, so paging
                // into another year is never ambiguous. Read inside the pill to
                // keep the rest of the bar off the recomposition path.
                YearPill(year = { currentMonth.year })
                Spacer(Modifier.width(8.dp))
                CircleIconButton(
                    icon = AppIcons.EditCalendar,
                    contentDescription = "Quick actions",
                    onClick = { showQuickSheet = true },
                    diameter = 40.dp,
                    iconSize = 19.dp
                )
            }

            // ── Earnings for the month on screen ──
            //
            // Read from the on-screen map, so a pasted or erased day moves the
            // figure on the same frame. Follows the browsed month — see
            // PayPeriod.homeRange for which days that means per pay cycle.
            MonthEarnings(
                month = { currentMonth },
                today = today,
                profile = activeProfile,
                weekStart = weekStartDay(startOfWeek),
                shifts = visibleShifts,
                currencySymbol = currencySymbol,
                onClick = { range ->
                    summaryStart = range.start.toString()
                    summaryEnd = range.end.toString()
                },
                modifier = Modifier
                    .padding(horizontal = AppSpace.screenH)
                    .padding(bottom = 8.dp)
            )

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
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (eraser) AppIcons.Delete else AppIcons.Copy,
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
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    icon = AppIcons.ChevronLeft,
                    contentDescription = "Previous month",
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                    diameter = 36.dp,
                    iconSize = 17.dp,
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
                    diameter = 36.dp,
                    iconSize = 17.dp,
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
                    .padding(bottom = 4.dp)
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
                    .padding(bottom = 8.dp),
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
                                    warn = date in restIssues && !visibleShifts[date].isNullOrEmpty(),
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

            restIssues[date]?.let { issues ->
                RestWarnings(issues)
                Spacer(Modifier.height(14.dp))
            }

            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                dayShifts.forEachIndexed { index, shift ->
                    DayShiftRow(
                        shift = shift,
                        onEdit = {
                            scope.launch {
                                sheetState.hide(); showDaySheet = false
                                onEditEntry(date, index)
                            }
                        },
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
                    AppIcons.Copy, "Copy a shift",
                    "Tap days to add it to each one", cs.primary
                ) { scope.launch { sheetState.hide(); showQuickSheet = false; showPickToPasteSheet = true } }
                QuickAction(
                    AppIcons.Rotation, "Fill a rotation",
                    "Repeat a pattern like 4 on / 4 off", cs.primary
                ) { scope.launch { sheetState.hide(); showQuickSheet = false; showRotation = true } }
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

    val summaryRange = summaryStart?.let { start ->
        summaryEnd?.let { end -> DateRange(LocalDate.parse(start), LocalDate.parse(end)) }
    }
    if (summaryRange != null) {
        PeriodSummarySheet(
            range = summaryRange,
            today = today,
            profile = activeProfile,
            weekStart = weekStartDay(startOfWeek),
            shifts = visibleShifts,
            currencySymbol = currencySymbol,
            sheetState = summarySheetState,
            onEditProfile = {
                scope.launch {
                    summarySheetState.hide()
                    summaryStart = null; summaryEnd = null
                    onEditProfile()
                }
            },
            onDismiss = { summaryStart = null; summaryEnd = null }
        )
    }

    if (showRotation) {
        RotationDialog(
            savedShifts = customTemplates,
            calendar = savedShifts,
            profileId = activeProfileId,
            today = today,
            startOfWeek = startOfWeek,
            onApply = { pattern, slots, start, until, conflict ->
                showRotation = false
                applyWithUndo({ days -> "Rotation filled $days day${if (days == 1) "" else "s"}" }) { map ->
                    RotationFill.apply(map, activeProfileId, pattern, slots, start, until, conflict)
                }
            },
            onDismiss = { showRotation = false }
        )
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
}

// ══════════════════════════════════════════════════════════════
//  Earnings strip
// ══════════════════════════════════════════════════════════════

/**
 * The strip for the month on screen. Takes the month as a lambda, like the
 * month bar pieces below, so a swipe recomposes only this strip.
 */
@Composable
private fun MonthEarnings(
    month: () -> YearMonth,
    today: LocalDate,
    profile: JobProfile,
    weekStart: java.time.DayOfWeek,
    shifts: Map<LocalDate, List<Shift>>,
    currencySymbol: String,
    onClick: (DateRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val shown = month()
    val payPeriod = profile.payPeriod
    val range = remember(shown, today, payPeriod) { payPeriod.homeRange(shown, today) }
    // Salary or shift pay, by the job's pay setup — the one calculation every
    // screen uses, so Home, the Wallet and Insights can never disagree.
    val totals = remember(profile, shifts, range, weekStart) { Compensation.totals(profile, shifts, range, weekStart) }
    // The ring only appears where it means what it says: on a range that is
    // itself one pay period, which is what a goal is set for.
    val progress = remember(profile, totals, range, today) {
        if (profile.hasGoal && payPeriod.goalRange(range, today) == range) {
            GoalProgress.of(profile, totals).headline
        } else null
    }
    // The month is already the calendar's title, so a calendar-month total is
    // labelled by what it is, and only a period that runs across months
    // spells out its dates.
    val isWholeMonth = range.start.dayOfMonth == 1 &&
            range.end == YearMonth.from(range.start).atEndOfMonth()
    EarningsStrip(
        title = if (isWholeMonth) "Month total" else "Pay period",
        range = if (isWholeMonth) null else range.shortLabel(),
        totals = totals,
        currencySymbol = currencySymbol,
        goalProgress = progress,
        onClick = { onClick(range) },
        modifier = modifier
    )
}

/**
 * One slim line: "Pay period  15 Sep – 14 Oct ··· €1,024.00 · 96h", or
 * "Month total ··· €1,024.00 · 96h" when the range is the month on screen.
 * With a goal set, a small ring in front fills as the period earns towards it.
 * Tapping opens the period's summary: take-home, goals, hours.
 */
@Composable
private fun EarningsStrip(
    title: String,
    range: String?,
    totals: PeriodTotals,
    currencySymbol: String,
    goalProgress: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(cs.surface)
            .then(
                if (AppTheme.accents.isDark) Modifier.border(1.dp, cs.outline, CircleShape)
                else Modifier
            )
            .clickable(onClickLabel = "Show pay period summary", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (goalProgress != null) {
            GoalRing(goalProgress, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurface,
            maxLines = 1
        )
        Spacer(Modifier.width(6.dp))
        Text(
            range.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$currencySymbol${"%,.2f".format(totals.earnings)}",
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
            maxLines = 1
        )
        Spacer(Modifier.width(8.dp))
        Text(
            hoursLabel(totals.hours),
            style = MaterialTheme.typography.labelMedium,
            color = cs.primary,
            maxLines = 1
        )
    }
}

/** A thin progress ring; turns the success colour once the goal is reached. */
@Composable
private fun GoalRing(progress: Double, modifier: Modifier = Modifier, stroke: Dp = 3.dp) {
    val cs = MaterialTheme.colorScheme
    val reached = progress >= 1.0
    val track = cs.outline
    val fill = if (reached) AppTheme.accents.positive else cs.primary
    val sweep by animateFloatAsState(
        (progress.coerceIn(0.0, 1.0) * 360.0).toFloat(),
        tween(600), label = "goalRing"
    )
    Canvas(modifier.semantics { contentDescription = "Goal ${(progress * 100).toInt()}% reached" }) {
        val width = stroke.toPx()
        val inset = width / 2
        val arcSize = Size(size.width - width, size.height - width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
        drawArc(fill, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(width, cap = StrokeCap.Round))
    }
}

// ══════════════════════════════════════════════════════════════
//  Pay period summary
// ══════════════════════════════════════════════════════════════

/**
 * What a period adds up to: gross pay, the take-home estimate, hours, and how
 * the job's goals are going. Goals are per pay period, so when the range on
 * Home is a calendar month (weekly and two-weekly jobs) they are read against
 * the pay period running today instead, and say so.
 */
@Composable
private fun PeriodSummarySheet(
    range: DateRange,
    today: LocalDate,
    profile: JobProfile,
    weekStart: java.time.DayOfWeek,
    shifts: Map<LocalDate, List<Shift>>,
    currencySymbol: String,
    sheetState: AppSheetState,
    onEditProfile: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val pay = remember(profile, shifts, range, weekStart) { Compensation.forRange(profile, shifts, range, weekStart) }
    val totals = remember(pay) { PeriodTotals(hours = pay.hoursWorked, earnings = pay.expectedCents / 100.0, shifts = pay.shiftsWorked) }
    val goalRange = remember(profile.payPeriod, range, today) { profile.payPeriod.goalRange(range, today) }
    val goalTotals = remember(profile, shifts, goalRange, weekStart) { Compensation.totals(profile, shifts, goalRange, weekStart) }
    val money = { amount: Double -> "$currencySymbol${"%,.2f".format(amount)}" }

    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = range.label(),
        caption = profile.name
    ) {
        Spacer(Modifier.height(18.dp))

        // ── What it adds up to ──
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(cs.primaryContainer)
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                if (pay.mode == PayMode.FIXED_MONTHLY) "Expected pay" else "Earned",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onPrimaryContainer
            )
            Text(
                money(totals.earnings),
                style = MaterialTheme.typography.headlineLarge,
                color = cs.onSurface
            )
            if (profile.deductionPercent > 0.0) {
                Text(
                    "≈ ${money(profile.takeHome(totals.earnings))} take-home " +
                        "after ${formatDecimalInput(profile.deductionPercent)}% deductions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${hoursLabel(totals.hours)} worked · ${totals.shifts} shift${if (totals.shifts == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary
            )
        }

        // ── How it is made up ──
        Spacer(Modifier.height(14.dp))
        PayBreakdown(pay, currencySymbol)

        // ── Goals ──
        if (profile.hasGoal) {
            Spacer(Modifier.height(18.dp))
            SheetLabel(
                if (goalRange == range) "Goals"
                else "Goals · this pay period, ${goalRange.shortLabel()}"
            )
            Spacer(Modifier.height(10.dp))
            val progress = GoalProgress.of(profile, goalTotals)
            progress.earnings?.let {
                GoalBar(
                    label = "${money(goalTotals.earnings)} of ${money(profile.earningsGoal)}",
                    status = if (it >= 1.0) "Reached"
                    else "${money(profile.earningsGoal - goalTotals.earnings)} to go",
                    progress = it
                )
            }
            if (progress.earnings != null && progress.hours != null) Spacer(Modifier.height(12.dp))
            progress.hours?.let {
                GoalBar(
                    label = "${hoursLabel(goalTotals.hours)} of ${hoursLabel(profile.hoursGoal)}",
                    status = if (it >= 1.0) "Reached"
                    else "${hoursLabel(profile.hoursGoal - goalTotals.hours)} to go",
                    progress = it
                )
            }
        } else if (profile.deductionPercent <= 0.0) {
            Spacer(Modifier.height(12.dp))
            EmptyHint("Set an earnings or hours goal, or your deductions, to see progress and take-home pay here.")
        }

        Spacer(Modifier.height(18.dp))
        SheetActionButton(
            text = if (profile.hasGoal || profile.deductionPercent > 0.0) "Edit goals & deductions"
            else "Set goals & deductions",
            icon = AppIcons.Edit,
            onClick = onEditProfile
        )
    }
}

@Composable
private fun GoalBar(label: String, status: String, progress: Double) {
    val cs = MaterialTheme.colorScheme
    val reached = progress >= 1.0
    val fill = if (reached) AppTheme.accents.positive else cs.primary
    val shown by animateFloatAsState(progress.coerceIn(0.0, 1.0).toFloat(), tween(600), label = "goalBar")
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(status, style = MaterialTheme.typography.labelMedium, color = if (reached) fill else cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(fill)
            )
        }
    }
}

/**
 * A period's expected pay, line by line: the salary or regular pay, overtime
 * (paid, banked or tracked), extras — then the hours behind it. Shared by
 * Home's summary and the Wallet's period statement.
 */
@Composable
internal fun PayBreakdown(pay: PeriodPay, currencySymbol: String) {
    val cs = MaterialTheme.colorScheme
    val money = { cents: Long -> formatCents(cents, currencySymbol) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pay.mode == PayMode.FIXED_MONTHLY) {
            BreakdownLine(
                "Base salary",
                if (pay.salaryMissing && pay.baseCents == 0L) "Not set for this period" else money(pay.baseCents),
                muted = pay.salaryMissing && pay.baseCents == 0L
            )
        } else {
            BreakdownLine("Regular pay", money(pay.baseCents))
        }
        if (pay.overtimeHours > 0.0) {
            val hours = hoursLabel(pay.overtimeHours)
            when {
                pay.overtimeUnvalued -> BreakdownLine("Overtime · $hours", "Set a reference rate to value it", muted = true)
                pay.compensation == OvertimeCompensation.TIME_OFF ->
                    BreakdownLine("Overtime · $hours", "${hoursLabel(pay.timeOffHours)} time off")
                pay.compensation == OvertimeCompensation.TRACK_ONLY ->
                    BreakdownLine("Overtime · $hours", "Tracked only", muted = true)
                else -> BreakdownLine("Overtime · $hours", money(pay.overtimeCents ?: 0L))
            }
        }
        if (pay.extrasCents > 0L) BreakdownLine("Bonuses & tips", money(pay.extrasCents))
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outline.copy(alpha = 0.5f)))
        BreakdownLine("Expected in total", money(pay.expectedCents), strong = true)
        val notes = buildList {
            if (pay.paidLeaveHours > 0.0) add("${hoursLabel(pay.paidLeaveHours)} paid leave")
            if (pay.unpaidLeaveDays > 0) add("${pay.unpaidLeaveDays} unpaid day${if (pay.unpaidLeaveDays == 1) "" else "s"}")
        }
        if (notes.isNotEmpty()) {
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BreakdownLine(label: String, value: String, muted: Boolean = false, strong: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            color = if (muted) cs.onSurfaceVariant else cs.onSurface,
            maxLines = 1
        )
    }
}

/** The day sheet's explanation of why the day carries a warning dot. */
@Composable
private fun RestWarnings(issues: List<RestIssue>) {
    val cs = MaterialTheme.colorScheme
    val warning = AppTheme.accents.warning
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(warning.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        issues.forEach { issue ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(AppIcons.Warning, null, tint = warning, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(issue.message, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
            }
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
    /** Draws the rest-warning dot. */
    warn: Boolean,
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
            if (warn) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-1).dp)
                        .size(7.dp)
                        .background(AppTheme.accents.warning, CircleShape)
                )
            }
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

/**
 * The titled sheet most of the app uses: [AppBottomSheet] plus the standard
 * title, caption and padding. Any part of it can be dragged to close it;
 * scrolling lists inside cooperate with that on their own.
 */
@Composable
internal fun AppSheet(
    onDismiss: () -> Unit,
    sheetState: AppSheetState,
    title: String,
    caption: String? = null,
    body: @Composable ColumnScope.() -> Unit
) {
    AppBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp)
        ) {
            SheetTitle(title)
            if (caption != null) SheetCaption(caption)
            body()
        }
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 36.dp)
        Spacer(Modifier.width(12.dp))
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(cs.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Add, null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
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
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 40.dp)
        Spacer(Modifier.width(12.dp))
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

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ShiftVaultTheme {
        MainScreen(onSettingsClick = {}, onCreateCustomClick = {})
    }
}