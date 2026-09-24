package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  CustomShiftScreen — create / edit a saved shift.
 *
 *  The form follows the data model: what happened (day type) is asked
 *  separately from how it pays (rate × multiplier + bonus), so a worked public
 *  holiday and an unpaid one are both expressible. A live summary at the bottom
 *  runs the draft through PayEngine, so the user sees the money before saving.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val csDayLabelFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")

/** Default span for a brand-new shift. */
private const val DEFAULT_START_TIME = "09:00"
private const val DEFAULT_END_TIME = "17:00"

private data class MultiplierPreset(val value: Double, val label: String)

private val MultiplierPresets = listOf(
    MultiplierPreset(PayMultipliers.NORMAL, "Normal"),
    MultiplierPreset(PayMultipliers.OVERTIME, "1.5× OT"),
    MultiplierPreset(PayMultipliers.HOLIDAY, "1.75× Holiday"),
    MultiplierPreset(PayMultipliers.DOUBLE, "2× Double")
)

private fun dayTypeLabel(type: DayType): String = when (type) {
    DayType.WORKED -> "Worked"
    DayType.PAID_LEAVE -> "Paid leave"
    DayType.UNPAID_LEAVE -> "Unpaid"
}

/** "12.5" / "12" — keeps a prefilled rate from reading as "12.500000". */
private fun formatRate(rate: Double): String =
    if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()

private fun hoursText(hours: Double): String {
    val whole = hours.toInt()
    val minutes = Math.round((hours - whole) * 60).toInt()
    return if (minutes > 0) "${whole}h ${minutes}m" else "${whole}h"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomShiftScreen(
    selectedDate: LocalDate?,
    templateIndex: Int? = null,
    /**
     * Edits one calendar entry instead of creating a shift: the index within
     * the active job's shifts on [selectedDate]. Saving replaces only that
     * entry and leaves the saved-shift library alone.
     */
    entryIndex: Int? = null,
    shiftManager: ShiftManager,
    currencyCode: String = "EUR",
    /** The job this shift is being created for; edits keep their own. */
    activeProfile: JobProfile = JobProfile.Default,
    onBackClick: () -> Unit,
    onSaveFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Frozen at entry so the title can't flip while the exit animation runs.
    val frozenTemplateIndex = remember { templateIndex }
    val frozenEntryIndex = remember { entryIndex }
    val isEditing = frozenTemplateIndex != null || frozenEntryIndex != null

    /**
     * The form stays composed for the whole exit animation, so a text field that
     * still holds focus when we navigate away gets its input session restarted
     * on the way out — which is what made the keyboard flash up again right
     * after a shift was added. Dropping focus and the IME first stops that.
     */
    val leaveScreen: (() -> Unit) -> Unit = { andThen ->
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        andThen()
    }

    // Every field is saveable: a rotation, or the system switching to dark mode
    // at sunset, used to wipe a half-filled form.
    var name by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var rateText by rememberSaveable { mutableStateOf("") }
    var bonusText by rememberSaveable { mutableStateOf("") }
    var breakText by rememberSaveable { mutableStateOf("") }
    var paidLeaveText by rememberSaveable { mutableStateOf("8") }
    var startTime by rememberSaveable { mutableStateOf(DEFAULT_START_TIME) }
    var endTime by rememberSaveable { mutableStateOf(DEFAULT_END_TIME) }
    var dayType by rememberSaveable { mutableStateOf(DayType.WORKED) }
    var paidBreak by rememberSaveable { mutableStateOf(false) }
    var multiplier by rememberSaveable { mutableDoubleStateOf(PayMultipliers.NORMAL) }
    var selectedColorValue by rememberSaveable { mutableLongStateOf(ShiftColors.Palette[0].toArgb().toLong()) }
    var showCustomColor by rememberSaveable { mutableStateOf(false) }
    // Keeps the custom slot filled once a custom colour has been used, so
    // switching to a preset and back does not throw the choice away.
    var customColorValue by rememberSaveable { mutableStateOf<Long?>(null) }

    // Carried through untouched — no control on this screen.
    var carriedIconName by rememberSaveable { mutableStateOf<String?>(null) }
    // A new shift belongs to the job you are on; an edit keeps the job it was
    // filed under, even if you have since switched profiles.
    var carriedJobId by rememberSaveable { mutableStateOf(activeProfile.id) }

    // Both prefill effects below run once per form, not once per composition:
    // after a rotation they would otherwise overwrite what the user has typed
    // since with the stored values again.
    var defaultRateApplied by rememberSaveable { mutableStateOf(false) }
    var editLoaded by rememberSaveable { mutableStateOf(false) }

    // A job's default rate is a starting point, not a rule — it only fills the
    // field on a blank form, and the user types over it freely.
    LaunchedEffect(templateIndex, activeProfile.id) {
        if (defaultRateApplied) return@LaunchedEffect
        defaultRateApplied = true
        if (!isEditing && rateText.isBlank() && activeProfile.defaultHourlyRate > 0.0) {
            rateText = formatRate(activeProfile.defaultHourlyRate)
        }
    }

    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var pickingStart by rememberSaveable { mutableStateOf(true) }

    val currencySymbol = remember(currencyCode) {
        try { java.util.Currency.getInstance(currencyCode).getSymbol(java.util.Locale.getDefault()) }
        catch (_: Exception) { currencyCode }
    }

    /** Puts a stored shift into the form fields. */
    fun fill(stored: Shift) {
        val shift = stored.migrated()
        name = shift.name
        startTime = shift.startTime
        endTime = shift.endTime
        selectedColorValue = shift.colorValue
        if (!ShiftColors.isPreset(Color(shift.colorValue.toInt()))) {
            customColorValue = shift.colorValue
        }
        dayType = shift.dayType
        paidBreak = shift.paidBreak
        multiplier = if (shift.payMultiplier > 0.0) shift.payMultiplier else 1.0
        note = shift.notes
        location = shift.location
        rateText = if (shift.hourlyRate > 0.0) formatDecimalInput(shift.hourlyRate) else ""
        bonusText = if (shift.bonus > 0.0) formatDecimalInput(shift.bonus) else ""
        breakText = if (shift.breakMinutes > 0) shift.breakMinutes.toString() else ""
        paidLeaveText = if (shift.paidLeaveHours > 0.0) {
            if (shift.paidLeaveHours % 1.0 == 0.0) shift.paidLeaveHours.toInt().toString()
            else shift.paidLeaveHours.toString()
        } else "8"
        carriedIconName = shift.iconName
        carriedJobId = shift.jobId ?: activeProfile.id
    }

    // Load the shift being edited: a saved shift, or one day's calendar entry.
    LaunchedEffect(templateIndex, entryIndex) {
        if (editLoaded || !isEditing) return@LaunchedEffect
        val stored = when {
            frozenEntryIndex != null && selectedDate != null ->
                ProfileScope.daySlice(shiftManager.getShifts.first(), selectedDate, activeProfile.id)
                    .getOrNull(frozenEntryIndex)
            frozenTemplateIndex != null ->
                shiftManager.getTemplates.first().getOrNull(frozenTemplateIndex)
            else -> null
        }
        editLoaded = true
        stored?.let(::fill)
    }

    val isWorked = dayType == DayType.WORKED
    val isPaid = dayType != DayType.UNPAID_LEAVE

    // A name is all that's required. Equal start and end times are a full
    // 24-hour shift (PayEngine already counts them that way), and the summary
    // card shows "24h on the clock", so an accidental one is visible before
    // saving. The old check also captured `isWorked` once inside
    // derivedStateOf and never saw the day type change afterwards.
    val canSave = name.isNotBlank()

    val cs = MaterialTheme.colorScheme

    // The draft, assembled the same way save() does it — so the summary below
    // reflects exactly what will be stored.
    val draft = remember(
        name, startTime, endTime, dayType, paidBreak, multiplier,
        rateText, bonusText, breakText, paidLeaveText, selectedColorValue
    ) {
        Shift(
            name = name.trim().ifBlank { "Shift" },
            startTime = if (isWorked) startTime else "00:00",
            endTime = if (isWorked) endTime else "23:59",
            colorValue = selectedColorValue,
            hourlyRate = parseDecimal(rateText) ?: 0.0,
            payMultiplier = multiplier,
            bonus = parseDecimal(bonusText) ?: 0.0,
            breakMinutes = if (isWorked) (breakText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0) else 0,
            paidBreak = paidBreak,
            dayType = dayType,
            paidLeaveHours = parseDecimal(paidLeaveText) ?: 8.0
        )
    }

    // A second tap on Save while the first is still writing would add the
    // shift to the day twice.
    var saving by remember { mutableStateOf(false) }

    val save: () -> Unit = save@{
        if (saving) return@save
        saving = true
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        val newShift = draft.copy(
            name = name.trim(),
            location = location.trim(),
            notes = note,
            iconName = carriedIconName,
            jobId = carriedJobId
        )
        scope.launch {
            // Editing one calendar entry: replace just that entry, in place.
            if (frozenEntryIndex != null && selectedDate != null) {
                shiftManager.updateShifts { map ->
                    val slice = ProfileScope.daySlice(map, selectedDate, activeProfile.id).toMutableList()
                    if (frozenEntryIndex in slice.indices) {
                        slice[frozenEntryIndex] = newShift
                        ProfileScope.withDaySlice(map, selectedDate, activeProfile.id, slice)
                    } else map
                }
                onSaveFinished()
                return@launch
            }
            // Both edits run against the stored data inside one transaction
            // each, so nothing written in the meantime can be overwritten.
            selectedDate?.let { date ->
                shiftManager.updateShifts { map ->
                    map + (date to (map[date].orEmpty() + newShift))
                }
            }
            shiftManager.updateTemplates { templates ->
                val updated = templates.toMutableList()
                if (frozenTemplateIndex != null && frozenTemplateIndex in updated.indices) {
                    updated[frozenTemplateIndex] = newShift
                } else if (!updated.contains(newShift)) {
                    updated.add(newShift)
                }
                updated
            }
            onSaveFinished()
        }
    }

    // The system back gesture / button would otherwise pop straight through the
    // NavController and skip the IME teardown above.
    BackHandler { leaveScreen(onBackClick) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding()
    ) {
        // ══════════════ Header ══════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.screenH)
                .padding(top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(
                icon = AppIcons.ChevronLeft,
                contentDescription = "Back",
                onClick = { leaveScreen(onBackClick) },
                diameter = 44.dp,
                iconSize = 20.dp
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    if (isEditing) "Edit shift" else "New shift",
                    style = MaterialTheme.typography.displayMedium,
                    color = cs.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                selectedDate?.let {
                    Text(
                        it.format(csDayLabelFmt),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Surface(
                onClick = { if (canSave) save() },
                enabled = canSave,
                shape = CircleShape,
                color = if (canSave) cs.primary else cs.surfaceVariant,
                contentColor = if (canSave) cs.onPrimary else cs.onSurfaceVariant,
                shadowElevation = 0.dp
            ) {
                Text(
                    "Save",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 13.dp)
                )
            }
        }

        // ══════════════ Form ══════════════
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpace.screenH)
                .padding(top = 4.dp, bottom = 32.dp)
        ) {
            // ── Basics ──
            FormCard {
                FieldLabel("Name")
                Spacer(Modifier.height(10.dp))
                FormTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "e.g. Barista Shift",
                    singleLine = true
                )
                Spacer(Modifier.height(22.dp))
                FieldLabel("Color")
                Spacer(Modifier.height(12.dp))
                ColorPicker(
                    selected = selectedColorValue,
                    custom = customColorValue,
                    onSelect = { selectedColorValue = it },
                    onCustom = {
                        keyboard?.hide(); focusManager.clearFocus(force = true)
                        showCustomColor = true
                    }
                )
            }

            Spacer(Modifier.height(AppSpace.cardGap))

            // ── Day type ──
            FormCard {
                FieldLabel("Day type")
                Spacer(Modifier.height(4.dp))
                Text(
                    when (dayType) {
                        DayType.WORKED -> "Time on the clock, paid by the hour."
                        DayType.PAID_LEAVE -> "Away but still paid — annual leave, sick leave, a paid public holiday."
                        DayType.UNPAID_LEAVE -> "Away and not paid. Logged for the record, earns nothing."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(cs.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DayType.entries.forEach { type ->
                        val selected = type == dayType
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(if (selected) cs.primary else Color.Transparent)
                                .clickable { dayType = type }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                dayTypeLabel(type),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (isWorked) {
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeField("Start", startTime, Modifier.weight(1f)) {
                            keyboard?.hide(); focusManager.clearFocus(force = true)
                            pickingStart = true; showTimePicker = true
                        }
                        TimeField("End", endTime, Modifier.weight(1f)) {
                            keyboard?.hide(); focusManager.clearFocus(force = true)
                            pickingStart = false; showTimePicker = true
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    FieldLabel("Break", optional = true)
                    Spacer(Modifier.height(10.dp))
                    FormTextField(
                        value = breakText,
                        onValueChange = { breakText = it },
                        placeholder = "0",
                        singleLine = true,
                        keyboardType = KeyboardType.Number,
                        suffix = "min"
                    )
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        title = "Paid break",
                        subtitle = if (paidBreak) "Break counts as worked time"
                        else "Break is deducted from worked time",
                        checked = paidBreak
                    ) { paidBreak = it }
                } else if (dayType == DayType.PAID_LEAVE) {
                    Spacer(Modifier.height(20.dp))
                    FieldLabel("Paid hours credited")
                    Spacer(Modifier.height(10.dp))
                    FormTextField(
                        value = paidLeaveText,
                        onValueChange = { paidLeaveText = it },
                        placeholder = "8",
                        singleLine = true,
                        keyboardType = KeyboardType.Decimal,
                        suffix = "hours"
                    )
                }
            }

            // ── Pay ──
            if (isPaid) {
                Spacer(Modifier.height(AppSpace.cardGap))
                FormCard {
                    FieldLabel("Hourly rate", optional = true)
                    Spacer(Modifier.height(10.dp))
                    FormTextField(
                        value = rateText,
                        onValueChange = { rateText = it },
                        placeholder = "0.00",
                        singleLine = true,
                        keyboardType = KeyboardType.Decimal,
                        prefix = currencySymbol,
                        suffix = "/hr"
                    )

                    if (isWorked) {
                        Spacer(Modifier.height(22.dp))
                        FieldLabel("Rate multiplier")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "For overtime, Sundays, night shifts or working a public holiday.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MultiplierPresets.forEach { preset ->
                                FilterPill(
                                    text = preset.label,
                                    selected = multiplier == preset.value,
                                    onClick = { multiplier = preset.value }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    FieldLabel("Bonus", optional = true)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tips, travel allowance, or any flat extra for this shift.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    FormTextField(
                        value = bonusText,
                        onValueChange = { bonusText = it },
                        placeholder = "0.00",
                        singleLine = true,
                        keyboardType = KeyboardType.Decimal,
                        prefix = currencySymbol
                    )
                }
            }

            // ── Details ──
            Spacer(Modifier.height(AppSpace.cardGap))
            FormCard {
                FieldLabel("Location", optional = true)
                Spacer(Modifier.height(10.dp))
                FormTextField(
                    value = location,
                    onValueChange = { location = it },
                    placeholder = "e.g. Downtown Cafe",
                    singleLine = true
                )
                Spacer(Modifier.height(22.dp))
                FieldLabel("Note", optional = true)
                Spacer(Modifier.height(10.dp))
                FormTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = "Add any notes...",
                    singleLine = false,
                    minHeight = 96.dp
                )
            }

            // ── Live summary ──
            Spacer(Modifier.height(AppSpace.cardGap))
            SummaryCard(draft = draft, currencySymbol = currencySymbol)
        }
    }

    if (showCustomColor) {
        CustomColorDialog(
            initial = Color((customColorValue ?: selectedColorValue).toInt()),
            onDismiss = { showCustomColor = false },
            onPick = {
                val value = it.toArgb().toLong()
                selectedColorValue = value
                customColorValue = value
                showCustomColor = false
            }
        )
    }

    if (showTimePicker) {
        val base = if (pickingStart) startTime else endTime
        val h = base.substringBefore(":").toIntOrNull() ?: 9
        val m = base.substringAfter(":").toIntOrNull() ?: 0
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
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
                    if (pickingStart) startTime = t else endTime = t
                    showTimePicker = false
                }) { Text("OK", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  Form building blocks
// ══════════════════════════════════════════════════════════════

@Composable
internal fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    SoftCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        content = content
    )
}

@Composable
internal fun FieldLabel(text: String, optional: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        if (optional) {
            Spacer(Modifier.width(6.dp))
            Text(
                "optional",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
    suffix: String? = null,
    minHeight: Dp = 0.dp
) {
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (minHeight > 0.dp) Modifier.heightIn(min = minHeight) else Modifier),
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant
            )
        },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        prefix = prefix?.let {
            { Text(it, style = MaterialTheme.typography.titleSmall, color = cs.onSurface) }
        },
        suffix = suffix?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.primary,
            unfocusedBorderColor = cs.outline,
            focusedContainerColor = cs.background,
            unfocusedContainerColor = cs.background,
            cursorColor = cs.primary
        )
    )
}

@Composable
private fun ColorPicker(
    selected: Long,
    custom: Long?,
    onSelect: (Long) -> Unit,
    onCustom: () -> Unit
) {
    val customColor = remember(custom) { custom?.let { Color(it.toInt()) } }
    val isCustomSelected = custom != null && custom == selected

    // Thirteen presets plus the custom slot fill two clean rows of seven.
    val rows = remember { ShiftColors.Palette.chunked(7) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            val isLastRow = rowIndex == rows.lastIndex
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { c ->
                    val v = c.toArgb().toLong()
                    Swatch(
                        color = c,
                        isSelected = v == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(v) }
                    )
                }
                if (isLastRow) {
                    CustomSwatch(
                        color = customColor,
                        isSelected = isCustomSelected,
                        modifier = Modifier.weight(1f),
                        onClick = onCustom
                    )
                }
                // Keeps the last row aligned to the same 7-column rhythm.
                val used = row.size + if (isLastRow) 1 else 0
                repeat(7 - used) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .then(if (isSelected) Modifier.border(2.5.dp, color, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(if (isSelected) 4.dp else 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxSize().background(color, CircleShape))
    }
}

/**
 * The fourteenth slot. Empty it shows a hue wheel and a plus; once a custom
 * colour is in play it behaves exactly like any other swatch, so the custom
 * colour reads as part of the palette rather than as a separate feature.
 */
@Composable
private fun CustomSwatch(
    color: Color?,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val wheel = remember {
        Brush.sweepGradient(ShiftColors.Palette + ShiftColors.Palette.first())
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .then(
                if (isSelected && color != null)
                    Modifier.border(2.5.dp, color, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(if (isSelected) 4.dp else 1.dp),
        contentAlignment = Alignment.Center
    ) {
        if (color != null) {
            Box(Modifier.fillMaxSize().background(color, CircleShape))
        } else {
            Box(
                Modifier.fillMaxSize().background(wheel, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxSize(0.52f)
                        .background(cs.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        AppIcons.Add, "Custom color",
                        tint = cs.onSurface,
                        modifier = Modifier.fillMaxSize(0.72f)
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
//  Custom colour
// ════════════════════════════════════════════════════════════

private fun Color.toHsl(): FloatArray =
    FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

private fun hsl(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(h.coerceIn(0f, 360f), s, l)))

@Composable
private fun CustomColorDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit
) {
    val cs = MaterialTheme.colorScheme

    val start = remember(initial) { initial.toHsl() }
    var hue by remember(initial) { mutableFloatStateOf(start[0]) }
    var saturation by remember(initial) { mutableFloatStateOf(start[1]) }
    var lightness by remember(initial) { mutableFloatStateOf(start[2]) }

    val color = remember(hue, saturation, lightness) { hsl(hue, saturation, lightness) }
    val hex = remember(color) { "#%06X".format(color.toArgb() and 0xFFFFFF) }
    val onSwatch = if (lightness > 0.55f) Color(0xFF1A1A1A) else Color.White

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = cs.surface,
        title = {
            Text("Custom color", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(color)
                        .border(1.dp, cs.outline, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    Text(hex, style = MaterialTheme.typography.titleMedium, color = onSwatch)
                }

                Spacer(Modifier.height(20.dp))
                SliderLabel("Hue")
                GradientSlider(
                    value = hue / 360f,
                    thumbColor = color,
                    trackColors = remember(saturation, lightness) {
                        List(13) {
                            hsl(
                                it * 30f,
                                saturation.coerceAtLeast(0.35f),
                                lightness.coerceIn(0.35f, 0.65f)
                            )
                        }
                    },
                    onValueChange = { hue = it * 360f }
                )

                Spacer(Modifier.height(14.dp))
                SliderLabel("Saturation")
                GradientSlider(
                    value = saturation,
                    thumbColor = color,
                    trackColors = remember(hue, lightness) {
                        listOf(hsl(hue, 0f, lightness), hsl(hue, 1f, lightness))
                    },
                    onValueChange = { saturation = it }
                )

                Spacer(Modifier.height(14.dp))
                SliderLabel("Lightness")
                GradientSlider(
                    value = lightness,
                    thumbColor = color,
                    trackColors = remember(hue, saturation) {
                        listOf(
                            hsl(hue, saturation, 0.06f),
                            hsl(hue, saturation, 0.5f),
                            hsl(hue, saturation, 0.94f)
                        )
                    },
                    onValueChange = { lightness = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onPick(color) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary)
            ) { Text("Use color", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SliderLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * A track painted with the outcome of the axis it controls, so the choice is
 * made by looking rather than by reading numbers. Hand-rolled rather than a
 * Material Slider purely so the track can *be* the gradient.
 */
@Composable
private fun GradientSlider(
    value: Float,
    thumbColor: Color,
    trackColors: List<Color>,
    onValueChange: (Float) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val thumbSize = 24.dp
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(0f) }
    val thumbPx = with(density) { thumbSize.toPx() }
    val travel = (widthPx - thumbPx).coerceAtLeast(1f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(thumbSize + 12.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                // One gesture loop handles both a tap and a drag, so the track
                // responds on touch-down instead of waiting for slop.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val span = (size.width - thumbPx).coerceAtLeast(1f)
                    fun fractionAt(x: Float) = ((x - thumbPx / 2f) / span).coerceIn(0f, 1f)
                    onValueChange(fractionAt(down.position.x))
                    drag(down.id) { change ->
                        onValueChange(fractionAt(change.position.x))
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(trackColors))
                .border(1.dp, cs.outline, CircleShape)
        )
        Box(
            Modifier
                .offset { IntOffset((value.coerceIn(0f, 1f) * travel).roundToInt(), 0) }
                .size(thumbSize)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape)
                .border(3.dp, thumbColor, CircleShape)
        )
    }
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                AppIcons.Schedule, null,
                tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
            .clickable { onCheckedChange(!checked) }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = cs.primary,
                checkedBorderColor = cs.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = cs.surfaceVariant,
                uncheckedBorderColor = cs.outline
            )
        )
    }
}

@Composable
private fun SummaryCard(draft: Shift, currencySymbol: String) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents

    val payableHours = PayEngine.payableHours(draft)
    val earnings = PayEngine.earnings(draft)
    val grossSpan = PayEngine.grossSpanHours(draft)
    val multiplierActive = PayEngine.effectiveMultiplier(draft) != 1.0

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.primary.copy(alpha = 0.10f))
            .padding(20.dp)
    ) {
        Text(
            "This shift is worth",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$currencySymbol${"%,.2f".format(earnings)}",
                style = MaterialTheme.typography.headlineLarge,
                color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(10.dp))
            Text(
                hoursText(payableHours),
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (draft.dayType) {
                DayType.WORKED -> {
                    SummaryChip("${hoursText(grossSpan)} on the clock", cs.surfaceVariant, cs.onSurfaceVariant)
                    if (draft.breakMinutes > 0) {
                        SummaryChip(
                            if (draft.paidBreak) "${draft.breakMinutes}m break, paid"
                            else "${draft.breakMinutes}m break, unpaid",
                            cs.surfaceVariant, cs.onSurfaceVariant
                        )
                    }
                    if (multiplierActive) {
                        SummaryChip(
                            "×${"%.2f".format(draft.payMultiplier).trimEnd('0').trimEnd('.')} rate",
                            accents.nightSoft, accents.night
                        )
                    }
                }
                DayType.PAID_LEAVE ->
                    SummaryChip("Paid leave", accents.morningSoft, accents.positive)
                DayType.UNPAID_LEAVE ->
                    SummaryChip("Unpaid — earns nothing", accents.negative.copy(alpha = 0.12f), accents.negative)
            }
            if (draft.bonus > 0.0) {
                SummaryChip(
                    "+$currencySymbol${"%,.2f".format(draft.bonus)} bonus",
                    accents.eveningSoft, accents.evening
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String, container: Color, contentColor: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor, maxLines = 1)
    }
}

@Preview(showBackground = true)
@Composable
fun CustomShiftScreenPreview() {
    ShiftVaultTheme {
        // Preview only — CustomShiftScreen needs a real ShiftManager at runtime.
    }
}