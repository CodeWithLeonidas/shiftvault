package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  RotationDialog — lay a repeating pattern onto the calendar.
 *
 *  Full screen, opened from Home's quick actions, laid out as five numbered
 *  steps that each answer one question:
 *
 *    1. Which pattern?        common rotations, each drawn as a strip
 *    2. Which shifts?         the saved shift for each part of the cycle
 *    3. What does a cycle     the days one by one, painted with a brush,
 *       look like?            and a plain statement of how it repeats
 *    4. When?                 day 1 and how long to fill
 *    5. What will happen?     four weeks of the real calendar, and what to
 *                             do with days that already have shifts
 *
 *  Everything is drawn with the shifts' own marks and colours, never with
 *  abstract slot letters. Apply goes through Home's undo, so a wrong fill is
 *  one tap to take back.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val rotationDateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

/** "How long to fill" presets, in months. 0 means a date picked by hand. */
private val FillSpans = listOf(1 to "1 month", 3 to "3 months", 6 to "6 months", 12 to "1 year", 0 to "Pick a date")

private fun slotOrdinal(slot: RotationSlot): String = when (slot) {
    RotationSlot.A -> "1st shift"
    RotationSlot.B -> "2nd shift"
    RotationSlot.C -> "3rd shift"
    RotationSlot.OFF -> "Day off"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotationDialog(
    /** The active job's saved shifts — what each part of the cycle can be. */
    savedShifts: List<Shift>,
    /** The whole calendar, for the preview and the summary. */
    calendar: Map<LocalDate, List<Shift>>,
    profileId: String,
    today: LocalDate,
    startOfWeek: String = "Monday",
    onApply: (RotationPattern, Map<RotationSlot, Shift>, LocalDate, LocalDate, RotationConflict) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    var code by rememberSaveable { mutableStateOf(RotationPattern.Presets.first().code) }
    // Custom as soon as the user builds or edits a cycle, even if it happens
    // to match a preset again — so the card they chose stays the one lit.
    var isCustom by rememberSaveable { mutableStateOf(false) }
    var slotA by rememberSaveable { mutableIntStateOf(if (savedShifts.isNotEmpty()) 0 else -1) }
    var slotB by rememberSaveable { mutableIntStateOf(if (savedShifts.size > 1) 1 else -1) }
    var slotC by rememberSaveable { mutableIntStateOf(if (savedShifts.size > 2) 2 else -1) }
    var brush by rememberSaveable { mutableStateOf(RotationSlot.A) }
    var start by rememberSaveable { mutableStateOf(today) }
    var spanMonths by rememberSaveable { mutableIntStateOf(3) }
    var customUntil by rememberSaveable { mutableStateOf(today.plusMonths(3).minusDays(1)) }
    var conflict by rememberSaveable { mutableStateOf(RotationConflict.SKIP) }
    var pickingStart by rememberSaveable { mutableStateOf(false) }
    var pickingUntil by rememberSaveable { mutableStateOf(false) }
    var pickingSlot by rememberSaveable { mutableStateOf<RotationSlot?>(null) }

    val until = if (spanMonths > 0) start.plusMonths(spanMonths.toLong()).minusDays(1) else customUntil

    val preset = if (isCustom) null else RotationPattern.Presets.firstOrNull { it.code == code }
    val pattern = RotationPattern(preset?.name ?: "Custom", code)

    fun indexOf(slot: RotationSlot) = when (slot) {
        RotationSlot.A -> slotA
        RotationSlot.B -> slotB
        RotationSlot.C -> slotC
        RotationSlot.OFF -> -1
    }
    fun shiftFor(slot: RotationSlot): Shift? = savedShifts.getOrNull(indexOf(slot))

    val slotShifts = buildMap {
        pattern.slotsUsed.forEach { slot -> shiftFor(slot)?.let { put(slot, it) } }
    }
    val preview = remember(calendar, profileId, code, slotShifts, start, until, conflict) {
        RotationFill.preview(calendar, profileId, pattern, slotShifts, start, until, conflict)
    }
    val canApply = preview.shiftsAdded > 0

    // Painting with a shift that isn't in the cycle yet is how a 2nd or 3rd
    // shift is added, so the brushes run one past the slots in use.
    val brushes = remember(code) {
        val used = RotationPattern("", code).slotsUsed
        val offered = RotationSlot.Working.take((used.size + 1).coerceAtMost(RotationSlot.Working.size))
        listOf(RotationSlot.OFF) + (offered + used).distinct().sortedBy { it.ordinal }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(cs.background)
                .systemBarsPadding()
        ) {
            // ── Header ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(top = 8.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    icon = AppIcons.Close, contentDescription = "Close",
                    onClick = onDismiss, diameter = 44.dp, iconSize = 20.dp
                )
                Text(
                    "Fill a rotation",
                    style = MaterialTheme.typography.displayMedium,
                    color = cs.onBackground,
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val applyContainer by animateColorAsState(
                    if (canApply) cs.primary else cs.surfaceVariant, tween(180), label = "applyContainer"
                )
                Surface(
                    onClick = { if (canApply) onApply(pattern, slotShifts, start, until, conflict) },
                    enabled = canApply,
                    shape = CircleShape,
                    color = applyContainer,
                    contentColor = if (canApply) cs.onPrimary else cs.onSurfaceVariant
                ) {
                    Text(
                        "Apply",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 13.dp)
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpace.screenH)
                    .padding(bottom = 32.dp)
            ) {
                if (savedShifts.isEmpty()) {
                    FormCard {
                        FieldLabel("No saved shifts yet")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A rotation lays your saved shifts onto days. Create one in the " +
                                "Shifts tab first — for example \"Day\" and \"Night\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant
                        )
                    }
                    return@Column
                }

                // ── 1. Pattern ──
                StepCard(1, "Pick a pattern", "Start from a common rotation, or build your own.") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RotationPattern.Presets.forEach { p ->
                            PatternCard(
                                title = p.name,
                                description = p.description,
                                code = p.code,
                                selected = !isCustom && p.code == code,
                                colorOf = ::shiftFor,
                                onClick = {
                                    isCustom = false
                                    code = p.code
                                    if (brush != RotationSlot.OFF && brush.code !in p.code) brush = RotationSlot.A
                                }
                            )
                        }
                        PatternCard(
                            title = "Custom",
                            description = "Build any cycle, day by day, in step 3.",
                            code = null,
                            selected = isCustom || preset == null,
                            colorOf = ::shiftFor,
                            onClick = { isCustom = true }
                        )
                    }
                }

                Spacer(Modifier.height(AppSpace.cardGap))

                // ── 2. Shifts ──
                StepCard(2, "Choose the shifts", "The saved shift that works each part of the cycle.") {
                    val used = pattern.slotsUsed
                    if (used.isEmpty()) {
                        Text(
                            "This cycle has no working days yet — paint some in step 3.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        used.forEach { slot ->
                            SlotRow(
                                slot = slot,
                                shift = shiftFor(slot),
                                days = code.count { it == slot.code },
                                onClick = { pickingSlot = slot }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(AppSpace.cardGap))

                // ── 3. Cycle ──
                StepCard(
                    3, "Check the cycle",
                    "Day 1 falls on your start date. After day ${pattern.length} it starts again from day 1."
                ) {
                    Text(
                        "Pick one, then tap days to change them:",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        brushes.forEach { slot ->
                            BrushChip(
                                slot = slot,
                                shift = shiftFor(slot),
                                selected = slot == brush,
                                onClick = { brush = slot }
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    CycleGrid(
                        code = code,
                        shiftOf = ::shiftFor,
                        onPaint = { i ->
                            if (RotationSlot.of(code[i]) != brush) {
                                code = code.substring(0, i) + brush.code + code.substring(i + 1)
                                isCustom = true
                            }
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Cycle length", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                            Text(
                                "${pattern.length} day${if (pattern.length == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        StepperButton("−", "Remove the last day", enabled = code.length > 1) {
                            code = code.dropLast(1); isCustom = true
                        }
                        Spacer(Modifier.width(8.dp))
                        StepperButton("+", "Add a day", enabled = code.length < RotationPattern.MAX_LENGTH) {
                            code += RotationSlot.OFF.code; isCustom = true
                        }
                    }
                }

                Spacer(Modifier.height(AppSpace.cardGap))

                // ── 4. Dates ──
                StepCard(4, "Pick the dates", null) {
                    DateTile("Starts on — day 1 of the cycle", start) { pickingStart = true }
                    Spacer(Modifier.height(14.dp))
                    FieldLabel("Fill for")
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FillSpans.forEach { (months, label) ->
                            FilterPill(
                                text = label,
                                selected = spanMonths == months,
                                onClick = {
                                    if (months == 0) {
                                        customUntil = until
                                        pickingUntil = true
                                    }
                                    spanMonths = months
                                }
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = spanMonths == 0,
                        enter = FormMotion.enter, exit = FormMotion.exit
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            DateTile("Fill until", customUntil) { pickingUntil = true }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (until.isBefore(start)) "\"Fill until\" is before the start date."
                        else DateRange(start, until).label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (until.isBefore(start)) AppTheme.accents.negative else cs.primary
                    )
                }

                Spacer(Modifier.height(AppSpace.cardGap))

                // ── 5. Review ──
                StepCard(5, "Review", "The first four weeks, as they will land on your calendar.") {
                    PreviewCalendar(
                        pattern = pattern,
                        slotShifts = slotShifts,
                        calendar = calendar,
                        profileId = profileId,
                        start = start,
                        until = until,
                        conflict = conflict,
                        startOfWeek = startOfWeek
                    )
                    Spacer(Modifier.height(18.dp))
                    FieldLabel("Days that already have a shift")
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConflictOption(
                            title = "Keep what's there",
                            body = "Skip those days — the rotation only fills empty days.",
                            selected = conflict == RotationConflict.SKIP
                        ) { conflict = RotationConflict.SKIP }
                        ConflictOption(
                            title = "Replace",
                            body = "Remove their shifts and put the rotation's in.",
                            selected = conflict == RotationConflict.REPLACE
                        ) { conflict = RotationConflict.REPLACE }
                        ConflictOption(
                            title = "Add alongside",
                            body = "Keep their shifts and add the rotation's as well.",
                            selected = conflict == RotationConflict.ADD
                        ) { conflict = RotationConflict.ADD }
                    }
                }

                Spacer(Modifier.height(AppSpace.cardGap))

                // ── Summary ──
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(cs.primary.copy(alpha = 0.10f))
                        .animateContentSize(tween(220, easing = FastOutSlowInEasing))
                        .padding(20.dp)
                ) {
                    Text(
                        when (preview.shiftsAdded) {
                            0 -> "Nothing to add yet"
                            1 -> "Adds 1 shift"
                            else -> "Adds ${preview.shiftsAdded} shifts"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = cs.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    val details = buildList {
                        if (!until.isBefore(start)) add(DateRange(start, until).label())
                        if (preview.daysSkipped > 0) add("${preview.daysSkipped} busy day${if (preview.daysSkipped == 1) "" else "s"} kept")
                        if (preview.daysReplaced > 0) add("${preview.daysReplaced} day${if (preview.daysReplaced == 1) "" else "s"} replaced")
                        if (pattern.slotsUsed.any { shiftFor(it) == null }) add("some days have no shift chosen")
                    }
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }

    pickingSlot?.let { slot ->
        RotationShiftSheet(
            slot = slot,
            shifts = savedShifts,
            selected = indexOf(slot),
            onPick = { index ->
                when (slot) {
                    RotationSlot.A -> slotA = index
                    RotationSlot.B -> slotB = index
                    RotationSlot.C -> slotC = index
                    RotationSlot.OFF -> Unit
                }
                pickingSlot = null
            },
            onDismiss = { pickingSlot = null }
        )
    }

    if (pickingStart) {
        RotationDatePicker(start, onDismiss = { pickingStart = false }) {
            if (spanMonths == 0) {
                // Keep the same length of fill when day 1 moves.
                val span = ChronoUnit.DAYS.between(start, customUntil)
                customUntil = it.plusDays(span.coerceAtLeast(0))
            }
            start = it
            pickingStart = false
        }
    }
    if (pickingUntil) {
        RotationDatePicker(customUntil, onDismiss = { pickingUntil = false }) {
            customUntil = it
            pickingUntil = false
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Step shell
// ══════════════════════════════════════════════════════════════

@Composable
private fun StepCard(
    number: Int,
    title: String,
    caption: String?,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    FormCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(cs.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("$number", color = cs.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        }
        if (caption != null) {
            Spacer(Modifier.height(6.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.animateContentSize(tween(220, easing = FastOutSlowInEasing)), content = content)
    }
}

// ══════════════════════════════════════════════════════════════
//  1 · Pattern cards
// ══════════════════════════════════════════════════════════════

/** A slot's colour in strips and cells: its shift's, or a theme stand-in. */
@Composable
private fun slotColor(slot: RotationSlot, shift: Shift?): Color {
    val cs = MaterialTheme.colorScheme
    val shiftAccent = shift?.let { rememberShiftAccent(it.colorValue) }
    return when {
        slot == RotationSlot.OFF -> cs.surfaceVariant
        shiftAccent != null -> shiftAccent
        slot == RotationSlot.A -> cs.primary
        slot == RotationSlot.B -> cs.tertiary
        else -> cs.secondary
    }
}

@Composable
private fun PatternCard(
    title: String,
    description: String,
    /** Null draws the custom card's placeholder instead of a strip. */
    code: String?,
    selected: Boolean,
    colorOf: (RotationSlot) -> Shift?,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) cs.primary else cs.outline, tween(180), label = "patternBorder")
    val container by animateColorAsState(
        if (selected) cs.primary.copy(alpha = 0.08f) else cs.background, tween(180), label = "patternBg"
    )
    Column(
        Modifier
            .width(196.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .border(if (selected) 2.dp else 1.dp, border, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(14.dp)
    ) {
        if (code != null) {
            // The whole cycle at a glance, one block per day.
            Row(
                Modifier.fillMaxWidth().height(12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                code.forEach { c ->
                    val slot = RotationSlot.of(c)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(slotColor(slot, colorOf(slot)))
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(12.dp)) {
                Icon(AppIcons.Add, null, tint = cs.primary, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  2 · Shift per slot
// ══════════════════════════════════════════════════════════════

@Composable
private fun SlotRow(slot: RotationSlot, shift: Shift?, days: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.background)
            .border(1.dp, if (shift == null) AppTheme.accents.negative else cs.outline, MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Choose the ${slotOrdinal(slot)}", onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (shift != null) {
            ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 40.dp)
        } else {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(cs.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Text("?", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${slotOrdinal(slot)} · $days day${if (days == 1) "" else "s"} per cycle",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                shift?.name ?: "Choose a shift",
                style = MaterialTheme.typography.titleSmall,
                color = if (shift == null) cs.primary else cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                shift?.let(::shiftTimeLabel) ?: "Until then these days stay empty",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(AppIcons.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RotationShiftSheet(
    slot: RotationSlot,
    shifts: List<Shift>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberAppSheetState()
    val scope = rememberCoroutineScope()
    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = slotOrdinal(slot).replaceFirstChar { it.uppercase() },
        caption = "Which saved shift works these days?"
    ) {
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            shifts.forEachIndexed { index, shift ->
                val isSelected = index == selected
                val accent = rememberShiftAccent(shift.colorValue)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(accent.copy(alpha = if (isSelected) 0.22f else 0.10f))
                        .clickable {
                            scope.launch { sheetState.hide(); onPick(index) }
                        }
                        .semantics { this.selected = isSelected }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(shift.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(shiftTimeLabel(shift), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    if (isSelected) Icon(AppIcons.Check, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  3 · Cycle editor
// ══════════════════════════════════════════════════════════════

@Composable
private fun BrushChip(slot: RotationSlot, shift: Shift?, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accent = slotColor(slot, shift)
    val container by animateColorAsState(
        if (selected) accent.copy(alpha = if (slot == RotationSlot.OFF) 1f else 0.22f) else cs.background,
        tween(160), label = "brushBg"
    )
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(container)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outline, CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlotMark(slot, shift, size = 30)
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                slot == RotationSlot.OFF -> "Day off"
                shift != null -> shift.name
                else -> slotOrdinal(slot)
            },
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurface,
            maxLines = 1
        )
    }
}

/** What stands for a slot: the shift's mark, a numbered stand-in, or "off". */
@Composable
private fun SlotMark(slot: RotationSlot, shift: Shift?, size: Int) {
    val cs = MaterialTheme.colorScheme
    val standIn = slotColor(slot, null)
    when {
        slot == RotationSlot.OFF -> Box(
            Modifier.size(size.dp).clip(CircleShape).background(cs.surface),
            contentAlignment = Alignment.Center
        ) {
            Text("–", color = cs.onSurfaceVariant, fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold)
        }
        shift != null -> ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = size.dp)
        else -> Box(
            Modifier.size(size.dp).clip(CircleShape).background(standIn.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${slot.ordinal}",
                color = standIn,
                fontSize = (size * 0.42f).sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
private fun CycleGrid(
    code: String,
    shiftOf: (RotationSlot) -> Shift?,
    onPaint: (Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        code.withIndex().chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { (i, c) ->
                    val slot = RotationSlot.of(c)
                    val shift = shiftOf(slot)
                    val tint = slotColor(slot, shift)
                    val bg by animateColorAsState(
                        if (slot == RotationSlot.OFF) cs.surfaceVariant else tint.copy(alpha = 0.16f),
                        tween(160), label = "cycleCell"
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.82f)
                            .clip(MaterialTheme.shapes.small)
                            .background(bg)
                            .clickable { onPaint(i) }
                            .clearAndSetSemantics {
                                contentDescription = "Day ${i + 1}: " + when {
                                    slot == RotationSlot.OFF -> "off"
                                    shift != null -> shift.name
                                    else -> slotOrdinal(slot)
                                }
                                role = Role.Button
                            }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text("${i + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                        if (slot == RotationSlot.OFF) {
                            Text("Off", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                        } else {
                            SlotMark(slot, shift, size = 24)
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StepperButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).semantics { contentDescription = description },
        shape = CircleShape,
        color = cs.surfaceVariant,
        contentColor = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.4f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  4 · Dates
// ══════════════════════════════════════════════════════════════

@Composable
private fun DateTile(label: String, date: LocalDate, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(date.format(rotationDateFmt), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        }
        Icon(AppIcons.Calendar, null, tint = cs.primary, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RotationDatePicker(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDay() * 86_400_000L)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / 86_400_000L)) } ?: onDismiss()
            }) { Text("OK", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}

// ══════════════════════════════════════════════════════════════
//  5 · Review
// ══════════════════════════════════════════════════════════════

/**
 * Four real weeks from the start date's week: each day shows the mark the
 * fill will put there. A day that already has a shift carries a dot, and when
 * it is kept as is the mark is shown faded — so the conflict choice below can
 * be judged by looking, not imagined.
 */
@Composable
private fun PreviewCalendar(
    pattern: RotationPattern,
    slotShifts: Map<RotationSlot, Shift>,
    calendar: Map<LocalDate, List<Shift>>,
    profileId: String,
    start: LocalDate,
    until: LocalDate,
    conflict: RotationConflict,
    startOfWeek: String
) {
    val cs = MaterialTheme.colorScheme
    val firstDay = if (startOfWeek == "Sunday") DayOfWeek.SUNDAY else DayOfWeek.MONDAY
    val gridStart = start.with(TemporalAdjusters.previousOrSame(firstDay))
    val days = remember(gridStart) { List(28) { gridStart.plusDays(it.toLong()) } }
    val anyBusy = remember(days, calendar, profileId, start, until) {
        days.any { !it.isBefore(start) && !it.isAfter(until) && ProfileScope.daySlice(calendar, it, profileId).isNotEmpty() }
    }

    Row(Modifier.fillMaxWidth()) {
        days.take(7).forEach { d ->
            Text(
                d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    val inRange = !date.isBefore(start) && !date.isAfter(until)
                    val shift = if (inRange) slotShifts[RotationFill.slotOn(pattern, start, date)] else null
                    val busy = ProfileScope.daySlice(calendar, date, profileId).isNotEmpty()
                    val kept = busy && shift != null && conflict == RotationConflict.SKIP
                    Column(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (date == start) cs.primary.copy(alpha = 0.12f) else cs.background)
                            .alpha(if (inRange) 1f else 0.4f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${date.dayOfMonth}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (date == start) cs.primary else cs.onSurfaceVariant
                            )
                            if (busy) {
                                Spacer(Modifier.width(2.dp))
                                Box(Modifier.size(5.dp).clip(CircleShape).background(cs.onSurfaceVariant))
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        if (shift != null) {
                            ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 24.dp, dimmed = kept)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(MaterialTheme.shapes.extraSmall).background(cs.primary.copy(alpha = 0.25f)))
        Spacer(Modifier.width(6.dp))
        Text("Day 1", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        if (anyBusy) {
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(cs.onSurfaceVariant))
            Spacer(Modifier.width(6.dp))
            Text(
                if (conflict == RotationConflict.SKIP) "Already has a shift — kept, shown faded"
                else "Already has a shift",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConflictOption(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) cs.primary else cs.outline, tween(160), label = "conflictBorder")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) cs.primary.copy(alpha = 0.08f) else cs.background)
            .border(if (selected) 2.dp else 1.dp, border, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = cs.primary))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
