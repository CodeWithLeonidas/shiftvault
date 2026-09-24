package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  RotationDialog — lay a repeating pattern onto the calendar.
 *
 *  Full screen, opened from Home's quick actions. Pick or draw a cycle, say
 *  which saved shift goes on its A and B days, choose the dates, and a live
 *  summary shows exactly what Apply will do. Apply goes through Home's undo,
 *  so a wrong fill is one tap to take back.
 * ─────────────────────────────────────────────────────────────────────────────
 */

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val rotationDateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotationDialog(
    /** The active job's saved shifts — what the A and B days can be. */
    savedShifts: List<Shift>,
    /** The whole calendar, for the live summary. */
    calendar: Map<LocalDate, List<Shift>>,
    profileId: String,
    today: LocalDate,
    onApply: (RotationPattern, Map<RotationSlot, Shift>, LocalDate, LocalDate, RotationConflict) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    var code by rememberSaveable { mutableStateOf(RotationPattern.Presets.first().code) }
    var slotA by rememberSaveable { mutableIntStateOf(if (savedShifts.isNotEmpty()) 0 else -1) }
    var slotB by rememberSaveable { mutableIntStateOf(if (savedShifts.size > 1) 1 else -1) }
    var start by rememberSaveable { mutableStateOf(today) }
    var until by rememberSaveable { mutableStateOf(today.plusMonths(3).minusDays(1)) }
    var conflict by rememberSaveable { mutableStateOf(RotationConflict.SKIP) }
    var pickingStart by rememberSaveable { mutableStateOf(false) }
    var pickingUntil by rememberSaveable { mutableStateOf(false) }

    val preset = RotationPattern.Presets.firstOrNull { it.code == code }
    val pattern = RotationPattern(preset?.name ?: "Custom", code)
    val slotShifts = buildMap {
        savedShifts.getOrNull(slotA)?.let { put(RotationSlot.A, it) }
        if (pattern.usesB) savedShifts.getOrNull(slotB)?.let { put(RotationSlot.B, it) }
    }
    val preview = remember(calendar, profileId, code, slotShifts, start, until, conflict) {
        RotationFill.preview(calendar, profileId, pattern, slotShifts, start, until, conflict)
    }
    val canApply = preview.shiftsAdded > 0

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
                Surface(
                    onClick = { if (canApply) onApply(pattern, slotShifts, start, until, conflict) },
                    enabled = canApply,
                    shape = CircleShape,
                    color = if (canApply) cs.primary else cs.surfaceVariant,
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

                // ── Pattern ──
                FormCard {
                    FieldLabel("Pattern")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RotationPattern.Presets.forEach { p ->
                            FilterPill(text = p.name, selected = p.code == code, onClick = { code = p.code })
                        }
                        FilterPill(text = "Custom", selected = preset == null, onClick = {})
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Tap a day to switch it between off, A and B.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    PatternGrid(
                        pattern = pattern,
                        colorA = savedShifts.getOrNull(slotA)?.colorValue,
                        colorB = savedShifts.getOrNull(slotB)?.colorValue,
                        onToggle = { i ->
                            val next = when (RotationSlot.of(code[i])) {
                                RotationSlot.OFF -> RotationSlot.A
                                RotationSlot.A -> RotationSlot.B
                                RotationSlot.B -> RotationSlot.OFF
                            }
                            code = code.substring(0, i) + next.code + code.substring(i + 1)
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${pattern.length}-day cycle",
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        CircleIconButton(
                            icon = AppIcons.ChevronLeft, contentDescription = "Shorter cycle",
                            onClick = { if (code.length > 1) code = code.dropLast(1) },
                            diameter = 40.dp, iconSize = 18.dp, container = cs.surfaceVariant, elevation = 0.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        CircleIconButton(
                            icon = AppIcons.ChevronRight, contentDescription = "Longer cycle",
                            onClick = { if (code.length < RotationPattern.MAX_LENGTH) code += RotationSlot.OFF.code },
                            diameter = 40.dp, iconSize = 18.dp, container = cs.surfaceVariant, elevation = 0.dp
                        )
                    }
                }

                Spacer(Modifier.height(AppSpace.cardGap))

                // ── Shifts ──
                FormCard {
                    FieldLabel("A days")
                    Spacer(Modifier.height(10.dp))
                    ShiftChooser(savedShifts, selected = slotA, onSelect = { slotA = it })
                    if (pattern.usesB) {
                        Spacer(Modifier.height(18.dp))
                        FieldLabel("B days")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The second shift in the pattern — nights, lates or week B.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        ShiftChooser(savedShifts, selected = slotB, onSelect = { slotB = it })
                    }
                }

                Spacer(Modifier.height(AppSpace.cardGap))

                // ── Dates ──
                FormCard {
                    DateTile("Day 1 of the cycle", start) { pickingStart = true }
                    Spacer(Modifier.height(10.dp))
                    DateTile("Fill until", until) { pickingUntil = true }
                    Spacer(Modifier.height(18.dp))
                    FieldLabel("Days that already have shifts")
                    Spacer(Modifier.height(10.dp))
                    SegmentedControl(
                        options = listOf(
                            RotationConflict.SKIP to "Keep",
                            RotationConflict.REPLACE to "Replace",
                            RotationConflict.ADD to "Add too"
                        ),
                        selected = conflict,
                        onSelect = { conflict = it }
                    )
                }

                Spacer(Modifier.height(AppSpace.cardGap))

                // ── Summary ──
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(cs.primary.copy(alpha = 0.10f))
                        .padding(20.dp)
                ) {
                    Text(
                        if (preview.shiftsAdded == 1) "Adds 1 shift" else "Adds ${preview.shiftsAdded} shifts",
                        style = MaterialTheme.typography.headlineSmall,
                        color = cs.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    val details = buildList {
                        add(DateRange(start, until).label())
                        if (preview.daysSkipped > 0) add("${preview.daysSkipped} busy day${if (preview.daysSkipped == 1) "" else "s"} kept")
                        if (preview.daysReplaced > 0) add("${preview.daysReplaced} day${if (preview.daysReplaced == 1) "" else "s"} replaced")
                    }
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                    if (until.isBefore(start)) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "\"Fill until\" is before day 1.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.accents.negative
                        )
                    }
                }
            }
        }
    }

    if (pickingStart) {
        RotationDatePicker(start, onDismiss = { pickingStart = false }) {
            val span = java.time.temporal.ChronoUnit.DAYS.between(start, until)
            start = it
            // Keep the same length of fill when day 1 moves.
            until = it.plusDays(span.coerceAtLeast(0))
            pickingStart = false
        }
    }
    if (pickingUntil) {
        RotationDatePicker(until, onDismiss = { pickingUntil = false }) {
            until = it
            pickingUntil = false
        }
    }
}

@Composable
private fun PatternGrid(
    pattern: RotationPattern,
    colorA: Long?,
    colorB: Long?,
    onToggle: (Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accentA = colorA?.let { rememberShiftAccent(it) } ?: cs.primary
    val accentB = colorB?.let { rememberShiftAccent(it) } ?: cs.tertiary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        pattern.days.withIndex().chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { (i, slot) ->
                    val (bg, fg, mark) = when (slot) {
                        RotationSlot.OFF -> Triple(cs.surfaceVariant, cs.onSurfaceVariant, "–")
                        RotationSlot.A -> Triple(accentA.copy(alpha = 0.28f), cs.onSurface, "A")
                        RotationSlot.B -> Triple(accentB.copy(alpha = 0.28f), cs.onSurface, "B")
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .background(bg)
                            .clickable { onToggle(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("${i + 1}", fontSize = 9.sp, color = cs.onSurfaceVariant)
                        Text(mark, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = fg)
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ShiftChooser(shifts: List<Shift>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        shifts.forEachIndexed { i, shift ->
            val isSelected = i == selected
            val accent = rememberShiftAccent(shift.colorValue)
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) accent.copy(alpha = 0.22f) else cs.background)
                    .border(1.dp, if (isSelected) accent else cs.outline, CircleShape)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(shift.name, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1)
                    Text(
                        shiftTimeLabel(shift),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

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
        Icon(AppIcons.CalendarMonth, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
