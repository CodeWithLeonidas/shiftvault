package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.foundation.horizontalScroll
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
private val dayLabelFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")

internal fun shiftTimeLabel(shift: Shift): String =
    if (shift.startTime == "00:00" && shift.endTime == "23:59") "All day"
    else "${shift.startTime} – ${shift.endTime}"

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
        if (startOfWeek == "Sunday") listOf("S", "M", "T", "W", "T", "F", "S")
        else listOf("M", "T", "W", "T", "F", "S", "S")
    }

    // The day a sheet is open on. The grid rings it, so it stays clear which
    // day the sheet is talking about.
    val sheetDate = if (showAddSheet || showDaySheet) selectedDate else null

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── Top bar: which job, and the calendar's tools ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The name may take whatever is left, but no more than it
                // needs, so the button below always sits flush right.
                ProfileSwitch(
                    profile = activeProfile,
                    onClick = onOpenProfiles,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 12.dp)
                )
                HeaderButton(
                    icon = AppIcons.Edit,
                    contentDescription = "Quick actions",
                    onClick = { showQuickSheet = true }
                )
            }

            // ── Month and year, with the arrows ──
            //
            // The year of the month being *browsed*, not today's, so paging
            // into another year is never ambiguous.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonthTitle(month = { currentMonth }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                MonthArrow(AppIcons.ChevronLeft, "Previous month") {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }
                Spacer(Modifier.width(6.dp))
                MonthArrow(AppIcons.ChevronRight, "Next month") {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            }

            // ── What the month on screen adds up to ──
            //
            // Read from the on-screen map, so a pasted or erased day moves the
            // figures on the same frame. Follows the browsed month — see
            // PayPeriod.homeRange for which days that means per pay cycle.
            MonthStats(
                month = { currentMonth },
                today = today,
                profile = activeProfile,
                weekStart = weekStartDay(startOfWeek),
                shifts = visibleShifts,
                onClick = { range ->
                    summaryStart = range.start.toString()
                    summaryEnd = range.end.toString()
                },
                // The tap target reaches past the text; the text itself still
                // lines up with the title above.
                modifier = Modifier
                    .padding(horizontal = AppSpace.screenH - 6.dp)
                    .padding(top = 4.dp)
            )

            // ── Active-mode banner (paste / erase) ──
            //
            // The text and tint are latched while the banner is up: leaving the mode
            // clears the very state the banner describes, so without this it would
            // flip to the other mode's wording for the length of the exit.
            var bannerContent by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
            if (isActiveMode) {
                val next = isEraserMode to
                        if (isEraserMode) "Tap days to clear them"
                        else "Tap days to add “${clipboardShift?.name ?: ""}”"
                if (bannerContent != next) bannerContent = next
            }

            AnimatedVisibility(
                visible = isActiveMode,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(120))
            ) {
                val (eraser, message) = bannerContent ?: (false to "")
                ModeBanner(
                    eraser = eraser,
                    message = message,
                    onDone = { clipboardShift = null; isEraserMode = false },
                    modifier = Modifier
                        .padding(horizontal = AppSpace.screenH)
                        .padding(top = 10.dp)
                )
            }

            // ── Weekday strip ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GridGutter)
                    .padding(top = 14.dp, bottom = 6.dp)
            ) {
                weekDays.forEach { label ->
                    Text(
                        label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            // ── Month grid ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                key = { it },
                beyondViewportPageCount = 1
            ) { page ->
                val monthForPage = remember(page) { anchorMonth.plusMonths((page - startPage).toLong()) }
                val cells = remember(monthForPage, startOfWeek) { buildMonthCells(monthForPage, startOfWeek) }
                val weeks = remember(cells) { cells.chunked(7) }

                // Hoisted once per page — shared by all 42 cells.
                val rule = cs.outlineVariant
                val isDark = accents.isDark

                // Each page carries its own gutter, so mid-swipe the two grids
                // sit apart instead of reading as one continuous sheet.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = GridGutter)
                        .padding(bottom = 4.dp)
                        .drawBehind { drawLine(rule, Offset.Zero, Offset(size.width, 0f), 1f) }
                ) {
                    weeks.forEach { week ->
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            week.forEach { date ->
                                DayCell(
                                    date = date,
                                    inMonth = YearMonth.from(date) == monthForPage,
                                    isToday = date == today,
                                    selected = date == sheetDate,
                                    dayShifts = visibleShifts[date] ?: emptyList(),
                                    warn = date in restIssues && !visibleShifts[date].isNullOrEmpty(),
                                    rule = rule,
                                    isDark = isDark,
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
        val date = selectedDate ?: today
        // Which saved shift is ticked, and whether it goes in as extra hours.
        // Held inside this block, so both start fresh each time the sheet opens.
        var pickedIndex by rememberSaveable { mutableIntStateOf(-1) }
        var markExtra by rememberSaveable { mutableStateOf(false) }
        val picked = customTemplates.getOrNull(pickedIndex)
        // Leave days have no hours to be extra.
        val canBeExtra = picked == null || picked.dayType == DayType.WORKED

        TitledSheet(
            onDismiss = { showAddSheet = false },
            sheetState = sheetState,
            eyebrow = "Add a shift",
            title = date.format(dayLabelFmt)
        ) {
            Spacer(Modifier.height(16.dp))

            if (customTemplates.isEmpty()) {
                EmptyHint("No saved shifts yet. Save the ones you work often in the Shifts tab.")
                Spacer(Modifier.height(8.dp))
            } else {
                Column(
                    Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    customTemplates.forEachIndexed { index, entry ->
                        ShiftTile(
                            shift = entry,
                            selected = index == pickedIndex,
                            onClick = {
                                pickedIndex = index
                                // A saved shift that is itself extra starts switched on.
                                markExtra = entry.isExtra && entry.dayType == DayType.WORKED
                            },
                            trailing = { SelectionMark(index == pickedIndex) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            ExtraHoursSwitch(
                checked = markExtra && canBeExtra,
                enabled = canBeExtra,
                onCheckedChange = { markExtra = it }
            )

            Spacer(Modifier.height(16.dp))
            PrimaryPillButton(
                text = picked?.let { "Add ${it.name}" } ?: "Choose a shift",
                enabled = picked != null,
                onClick = {
                    val entry = picked ?: return@PrimaryPillButton
                    val extra = markExtra && entry.dayType == DayType.WORKED
                    scope.launch {
                        sheetState.hide(); showAddSheet = false
                        applyWithUndo({ "“${entry.name}” added" }) { map ->
                            val updated = ProfileScope.daySlice(map, date, activeProfileId) +
                                    entry.copy(jobId = activeProfileId, isExtra = extra, extraPaid = false)
                            ProfileScope.withDaySlice(map, date, activeProfileId, updated)
                        }
                    }
                }
            )
        }
    }

    if (showDaySheet) {
        val date = selectedDate ?: today
        val dayShifts = visibleShifts[date] ?: emptyList()
        TitledSheet(
            onDismiss = { showDaySheet = false },
            sheetState = sheetState,
            eyebrow = if (dayShifts.size == 1) "1 shift" else "${dayShifts.size} shifts",
            title = date.format(dayLabelFmt)
        ) {
            Spacer(Modifier.height(16.dp))

            restIssues[date]?.let { issues ->
                RestWarnings(issues)
                Spacer(Modifier.height(12.dp))
            }

            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                applyWithUndo({ "“${shift.name}” deleted" }) { map ->
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

            Spacer(Modifier.height(20.dp))
            SheetLabel("Add another")
            Spacer(Modifier.height(10.dp))
            // One tap adds a saved shift right here — the sheet stays open and
            // the list above grows — with Undo in case of a mis-tap.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                customTemplates.forEach { entry ->
                    QuickAddPill(entry) {
                        applyWithUndo({ "“${entry.name}” added" }) { map ->
                            val updated = ProfileScope.daySlice(map, date, activeProfileId) +
                                    entry.copy(jobId = activeProfileId, extraPaid = false)
                            ProfileScope.withDaySlice(map, date, activeProfileId, updated)
                        }
                    }
                }
                QuickAddPill(null) {
                    scope.launch {
                        sheetState.hide(); showDaySheet = false
                        onCreateCustomClick(date)
                    }
                }
            }
            // The sheet is its own window, above the screen's snackbar, so an
            // Undo offered while it is open is shown here as well.
            SnackbarHost(snackbarHostState, Modifier.padding(top = 8.dp))
        }
    }

    if (showQuickSheet) {
        TitledSheet(
            onDismiss = { showQuickSheet = false },
            sheetState = sheetState,
            eyebrow = "Calendar",
            title = "Quick actions"
        ) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionTile(AppIcons.Today, "Jump to today", "Go back to the current month") {
                    scope.launch {
                        sheetState.hide(); showQuickSheet = false
                        val monthsAway = ChronoUnit.MONTHS.between(anchorMonth, YearMonth.from(today))
                        pagerState.animateScrollToPage(startPage + monthsAway.toInt())
                    }
                }
                ActionTile(AppIcons.Copy, "Copy a shift", "Pick a shift, then tap the days to add it to") {
                    scope.launch { sheetState.hide(); showQuickSheet = false; showPickToPasteSheet = true }
                }
                ActionTile(AppIcons.Rotation, "Fill a rotation", "Repeat a pattern, like 4 on, 4 off") {
                    scope.launch { sheetState.hide(); showQuickSheet = false; showRotation = true }
                }
                ActionTile(
                    AppIcons.Delete, "Erase days", "Tap days to clear their shifts",
                    tint = accents.negative
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
        TitledSheet(
            onDismiss = { showPickToPasteSheet = false },
            sheetState = sheetState,
            eyebrow = "Copy a shift",
            title = "Which shift?"
        ) {
            Text(
                "Pick one, then tap each day you want it on.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(14.dp))
            if (customTemplates.isEmpty()) {
                EmptyHint("No saved shifts yet. Save the ones you work often in the Shifts tab.")
            } else {
                Column(
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    customTemplates.forEach { shift ->
                        ShiftTile(
                            shift = shift,
                            onClick = {
                                scope.launch {
                                    sheetState.hide(); showPickToPasteSheet = false
                                    clipboardShift = shift
                                }
                            },
                            trailing = {
                                Icon(
                                    AppIcons.ChevronRight, null,
                                    tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)
                                )
                            }
                        )
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
 * One quiet line under the title: "152h logged  19 shifts  16h extra". Takes
 * the month as a lambda, like the title, so a swipe recomposes only this line.
 * With a goal set, a small ring in front fills towards it; for a pay period
 * that runs across months, its dates follow on a second line. Tapping opens
 * the period's summary, where the money is: pay, take-home, goals.
 */
@Composable
private fun MonthStats(
    month: () -> YearMonth,
    today: LocalDate,
    profile: JobProfile,
    weekStart: java.time.DayOfWeek,
    shifts: Map<LocalDate, List<Shift>>,
    onClick: (DateRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val shown = month()
    val payPeriod = profile.payPeriod
    val range = remember(shown, today, payPeriod) { payPeriod.homeRange(shown, today) }
    // Salary or shift pay, by the job's pay setup — the one calculation every
    // screen uses, so Home, the Wallet and Insights can never disagree.
    val pay = remember(profile, shifts, range, weekStart) { Compensation.forRange(profile, shifts, range, weekStart) }
    val totals = remember(pay) { PeriodTotals(hours = pay.hoursWorked, earnings = pay.expectedCents / 100.0, shifts = pay.shiftsWorked) }
    // The ring only appears where it means what it says: on a range that is
    // itself one pay period, which is what a goal is set for.
    val progress = remember(profile, totals, range, today) {
        if (profile.hasGoal && payPeriod.goalRange(range, today) == range) {
            GoalProgress.of(profile, totals).headline
        } else null
    }
    // The month is already the title, so only a period that runs across
    // months spells out its dates.
    val isWholeMonth = range.start.dayOfMonth == 1 &&
            range.end == YearMonth.from(range.start).atEndOfMonth()

    val ink = cs.onSurface
    val stats = remember(pay, ink) {
        val figure = SpanStyle(color = ink, fontWeight = FontWeight.SemiBold)
        buildAnnotatedString {
            withStyle(figure) { append(hoursLabel(pay.hoursWorked)) }
            append(" logged")
            append(" ")
            withStyle(figure) { append("${pay.shiftsWorked}") }
            append(if (pay.shiftsWorked == 1) " shift" else " shifts")
            if (pay.overtimeHours > 0.0) {
                append(" ")
                withStyle(figure) { append(hoursLabel(pay.overtimeHours)) }
                append(" extra")
            }
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Show pay period summary") { onClick(range) }
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (progress != null) {
                GoalRing(progress, Modifier.size(16.dp), stroke = 2.5.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stats,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                AppIcons.ChevronRight, null,
                tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }
        if (!isWholeMonth) {
            Text(
                "Pay period · ${range.shortLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** "Extra", or "Extra · paid" / "Extra · pending" where the paid tick matters. */
@Composable
internal fun ExtraTag(paid: Boolean?) {
    val warning = AppTheme.accents.warning
    val positive = AppTheme.accents.positive
    val color = if (paid == true) positive else warning
    Text(
        when (paid) {
            null -> "Extra"
            true -> "Extra · paid"
            false -> "Extra · pending"
        },
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** A saved shift as a one-tap pill; null is "Custom time". */
@Composable
private fun QuickAddPill(shift: Shift?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(44.dp)
            .clip(CircleShape)
            .border(1.dp, cs.outline, CircleShape)
            .clickable(onClickLabel = shift?.let { "Add ${it.name}" } ?: "Add a shift with a custom time", onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (shift != null) {
            ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 32.dp)
        } else {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(cs.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Add, null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            shift?.let { if (it.isExtra) "${it.name} · extra" else it.name } ?: "Custom time",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurface,
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

    TitledSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        eyebrow = profile.name,
        title = range.label()
    ) {
        Spacer(Modifier.height(16.dp))

        // ── What it adds up to ──
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(cs.surfaceVariant)
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                if (pay.mode == PayMode.FIXED_MONTHLY) "Expected pay" else "Earned",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
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
                color = cs.onSurface
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
        OutlinePillButton(
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
                pay.overtimeUnvalued -> BreakdownLine("Extra hours · $hours", "Set a reference rate to value it", muted = true)
                pay.compensation == OvertimeCompensation.TIME_OFF ->
                    BreakdownLine("Extra hours · $hours", "${hoursLabel(pay.timeOffHours)} time off")
                pay.compensation == OvertimeCompensation.TRACK_ONLY ->
                    BreakdownLine("Extra hours · $hours", "Tracked only", muted = true)
                else -> BreakdownLine("Extra hours · $hours", money(pay.overtimeCents ?: 0L))
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
//  Header pieces
// ══════════════════════════════════════════════════════════════

/** Grid inset from the screen edge — narrower than the text gutter, so the cells get the width. */
private val GridGutter = 10.dp
private val TileShape = RoundedCornerShape(18.dp)
private val DayCellShape = RoundedCornerShape(12.dp)

@Composable
private fun MonthArrow(icon: Painter, contentDescription: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(1.dp, cs.outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
    }
}

private val MonthTitleStyle: TextStyle
    @Composable get() = ScreenTitleStyle

/**
 * "November 2026", the year a step quieter. Takes the month as a lambda so the
 * pager is read in here: a swipe repaints the title and leaves the rest of the
 * header alone. A new month slides in from the side it was paged in from.
 */
@Composable
private fun MonthTitle(month: () -> YearMonth, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val style = MonthTitleStyle
    val yearColor = cs.onSurfaceVariant.copy(alpha = 0.55f)
    AnimatedContent(
        targetState = month(),
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it * direction / 5 } +
                    fadeIn(tween(220, delayMillis = 40)))
                .togetherWith(
                    slideOutHorizontally(tween(200)) { -it * direction / 5 } + fadeOut(tween(120))
                )
                .using(SizeTransform(clip = false))
        },
        label = "monthTitle"
    ) { shown ->
        Text(
            buildAnnotatedString {
                append(shown.format(monthTitleFmt))
                append(' ')
                withStyle(SpanStyle(color = yearColor, fontWeight = FontWeight.SemiBold)) {
                    append(shown.year.toString())
                }
            },
            style = style,
            color = cs.onBackground,
            maxLines = 1,
            // Long months on narrow phones step down rather than clip.
            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 36.sp, stepSize = 1.sp)
        )
    }
}

/** The paste / erase banner: what a tap on a day will do, and the way out. */
@Composable
private fun ModeBanner(eraser: Boolean, message: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val negative = AppTheme.accents.negative
    val accent = if (eraser) negative else cs.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (eraser) negative.copy(alpha = 0.12f) else cs.surfaceVariant)
            .padding(start = 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (eraser) AppIcons.Delete else AppIcons.Copy,
            contentDescription = null, tint = accent, modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.labelLarge, color = accent,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .height(32.dp)
                .clip(CircleShape)
                .background(if (eraser) negative else cs.primary)
                .clickable(role = Role.Button, onClick = onDone)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Done",
                style = MaterialTheme.typography.labelLarge,
                color = if (eraser) Color.White else cs.onPrimary
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Calendar cell
// ══════════════════════════════════════════════════════════════

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    /** The day an open sheet is about: washed and ringed. */
    selected: Boolean,
    dayShifts: List<Shift>,
    /** Draws the rest-warning dot. */
    warn: Boolean,
    rule: Color,
    isDark: Boolean,
    cs: ColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Saturday and Sunday always, whatever the week starts on.
    val isWeekend = remember(date) {
        date.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                date.dayOfWeek == java.time.DayOfWeek.SUNDAY
    }
    // Read only in the draw phase below, so fading the selection in and out
    // redraws this one cell and never recomposes the grid.
    val selection = animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "daySelection")
    val wash = cs.surfaceVariant
    val ring = cs.onSurface
    val warning = AppTheme.accents.warning

    Column(
        modifier = modifier
            .fillMaxHeight()
            .drawBehind {
                val y = size.height - 0.5f
                drawLine(rule, Offset(0f, y), Offset(size.width, y), 1f)
            }
            .then(if (inMonth) Modifier else Modifier.alpha(0.45f))
            .clip(DayCellShape)
            .drawBehind { if (selection.value > 0f) drawRect(wash, alpha = selection.value) }
            .clickable(onClick = onClick)
            .padding(start = 2.dp, end = 2.dp, top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(30.dp)
                .drawBehind {
                    val radius = size.minDimension / 2
                    if (isToday) drawCircle(cs.primary, radius)
                    else if (selection.value > 0f) {
                        val width = 2.dp.toPx()
                        drawCircle(ring, radius - width / 2, style = Stroke(width), alpha = selection.value)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${date.dayOfMonth}",
                color = when {
                    isToday -> cs.onPrimary
                    isWeekend || !inMonth -> cs.onSurfaceVariant
                    else -> cs.onSurface
                },
                fontSize = 14.sp,
                fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Medium,
                style = flatTextStyle
            )
            if (warn) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-1).dp)
                        .size(9.dp)
                        .background(cs.background, CircleShape)
                        .padding(1.5.dp)
                        .background(warning, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(5.dp))

        val shown = if (dayShifts.size > 2) dayShifts.subList(0, 2) else dayShifts
        shown.forEach { shift -> DayPill(shift, isDark) }
        if (dayShifts.size > 2) {
            Text(
                "+${dayShifts.size - 2}",
                color = cs.onSurfaceVariant,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, style = flatTextStyle
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Sheet shell & text
// ══════════════════════════════════════════════════════════════

/**
 * The titled sheet most of the app uses: [AppBottomSheet] plus the standard
 * title, caption, close button and padding. Any part of it can be dragged to
 * close it; scrolling lists inside cooperate with that on their own.
 */
@Composable
internal fun AppSheet(
    onDismiss: () -> Unit,
    sheetState: AppSheetState,
    title: String,
    caption: String? = null,
    body: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    AppBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.screenH)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SheetTitle(title) }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceVariant)
                        .clickable(role = Role.Button) {
                            scope.launch { sheetState.hide(); onDismiss() }
                        }
                        .semantics { contentDescription = "Close" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.Close, null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
                }
            }
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

/**
 * A saved shift as a row: its mark, name, times and hours, then [trailing] —
 * a selection mark where the row is one choice of several, a chevron where a
 * tap acts at once. A [selected] row is washed in, as the design's pick is.
 */
@Composable
private fun ShiftTile(
    shift: Shift,
    onClick: () -> Unit,
    selected: Boolean? = null,
    trailing: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val hours = remember(shift) { PayEngine.payableHours(shift) }
    val worked = shift.dayType == DayType.WORKED
    val secondary = remember(shift) {
        val base = when (shift.dayType) {
            DayType.WORKED -> shiftTimeLabel(shift)
            DayType.PAID_LEAVE -> "Paid leave"
            DayType.UNPAID_LEAVE -> "Unpaid leave"
        }
        val withMultiplier =
            if (shift.payMultiplier != 1.0 && shift.dayType == DayType.WORKED)
                "$base · ×" + "%.2f".format(shift.payMultiplier).trimEnd('0').trimEnd('.')
            else base
        if (shift.location.isBlank()) withMultiplier else "$withMultiplier · ${shift.location}"
    }
    val wash by animateColorAsState(
        if (selected == true) cs.surfaceVariant else Color.Transparent,
        tween(180), label = "tileWash"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(TileShape)
            .drawBehind { drawRect(wash) }
            .then(
                if (selected != null) Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                else Modifier.clickable(onClick = onClick)
            )
            .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shift.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (shift.isExtra && worked) {
                    Spacer(Modifier.width(6.dp))
                    ExtraTag(paid = null)
                }
            }
            Spacer(Modifier.height(1.dp))
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (shift.dayType != DayType.UNPAID_LEAVE) {
            Spacer(Modifier.width(10.dp))
            Text(
                hoursLabel(hours),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** A round tick: an empty ring, filling from the centre when chosen. */
@Composable
private fun SelectionMark(selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val progress = animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "selectionMark"
    )
    val ring = cs.onSurfaceVariant.copy(alpha = 0.4f)
    val fill = cs.primary
    Box(
        Modifier
            .size(22.dp)
            .drawBehind {
                val p = progress.value.coerceIn(0f, 1f)
                val radius = size.minDimension / 2
                val width = 2.dp.toPx()
                drawCircle(ring, radius - width / 2, style = Stroke(width), alpha = 1f - p)
                if (p > 0f) drawCircle(fill, radius * p)
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            AppIcons.Check, null,
            tint = cs.onPrimary,
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer {
                    val p = progress.value
                    scaleX = p; scaleY = p
                    alpha = p.coerceIn(0f, 1f)
                }
        )
    }
}

@Composable
private fun ExtraHoursSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(TileShape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Extra hours", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(1.dp))
            Text(
                if (enabled) "Overtime or a picked-up shift" else "Only for worked shifts",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        PillSwitch(checked)
    }
}

@Composable
private fun OutlinePillButton(text: String, icon: Painter, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .border(1.dp, cs.outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
    }
}

@Composable
private fun DayShiftRow(shift: Shift, onEdit: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
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
            .clip(TileShape)
            .background(cs.surfaceVariant)
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shift.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (shift.isExtra && shift.dayType == DayType.WORKED) {
                    Spacer(Modifier.width(6.dp))
                    ExtraTag(paid = shift.extraPaid)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        RowActionButton(AppIcons.Edit, "Edit", cs.surface, cs.onSurface, onEdit)
        Spacer(Modifier.width(6.dp))
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
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
}

/** A row in Quick actions. [tint] marks the one that takes things away. */
@Composable
private fun ActionTile(
    icon: Painter,
    label: String,
    desc: String,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(TileShape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint?.copy(alpha = 0.12f) ?: cs.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint ?: cs.onSurface, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label, style = MaterialTheme.typography.titleSmall, color = tint ?: cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(AppIcons.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ShiftVaultTheme {
        MainScreen(onSettingsClick = {}, onCreateCustomClick = {})
    }
}