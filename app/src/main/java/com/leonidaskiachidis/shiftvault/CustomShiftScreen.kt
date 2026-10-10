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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.ui.platform.LocalFocusManager
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
    // Progressive disclosure: most shifts have no break worth setting, so the
    // section starts folded. Its header still shows a break that is set.
    var breakExpanded by rememberSaveable { mutableStateOf(false) }
    var multiplier by rememberSaveable { mutableDoubleStateOf(PayMultipliers.NORMAL) }
    var selectedColorValue by rememberSaveable { mutableLongStateOf(ShiftColors.Palette[0].toArgb().toLong()) }
    var showCustomColor by rememberSaveable { mutableStateOf(false) }
    // Keeps the custom slot filled once a custom colour has been used, so
    // switching to a preset and back does not throw the choice away.
    var customColorValue by rememberSaveable { mutableStateOf<Long?>(null) }

    // Null means the default mark: the first letter of the name.
    var iconName by rememberSaveable { mutableStateOf<String?>(null) }
    var showIconPicker by rememberSaveable { mutableStateOf(false) }
    // A new shift belongs to the job you are on; an edit keeps the job it was
    // filed under, even if you have since switched profiles.
    var carriedJobId by rememberSaveable { mutableStateOf(activeProfile.id) }
    val salaried = activeProfile.pay.isFixed

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
        iconName = shift.iconName
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
            iconName = iconName,
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
            val saveContainer by animateColorAsState(
                if (canSave) cs.primary else cs.surfaceVariant, tween(180), label = "saveContainer"
            )
            val saveContent by animateColorAsState(
                if (canSave) cs.onPrimary else cs.onSurfaceVariant, tween(180), label = "saveContent"
            )
            Surface(
                onClick = { if (canSave) save() },
                enabled = canSave,
                shape = CircleShape,
                color = saveContainer,
                contentColor = saveContent,
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
                Spacer(Modifier.height(12.dp))
                IconField(
                    name = name,
                    iconName = iconName,
                    colorValue = selectedColorValue,
                    onClick = {
                        keyboard?.hide(); focusManager.clearFocus(force = true)
                        showIconPicker = true
                    }
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
                // The line underneath changes length with the choice, so it
                // crossfades and the card eases to its new height.
                AnimatedContent(
                    targetState = dayType,
                    transitionSpec = {
                        fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(100)) using
                            SizeTransform(clip = false) { _, _ -> tween(220, easing = FastOutSlowInEasing) }
                    },
                    label = "dayTypeHint"
                ) { type ->
                    Text(
                        when (type) {
                            DayType.WORKED -> "Time on the clock, paid by the hour."
                            DayType.PAID_LEAVE -> "Away but still paid — annual leave, sick leave, a paid public holiday."
                            DayType.UNPAID_LEAVE -> "Away and not paid. Logged for the record, earns nothing."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(14.dp))
                SegmentedControl(
                    options = DayType.entries.map { it to dayTypeLabel(it) },
                    selected = dayType,
                    onSelect = { dayType = it }
                )

                // Each block carries its own leading space, so nothing is left
                // behind to jump when it leaves.
                AnimatedVisibility(visible = isWorked, enter = FormMotion.enter, exit = FormMotion.exit) {
                    Column {
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
                        Spacer(Modifier.height(12.dp))
                        BreakSection(
                            expanded = breakExpanded,
                            onToggle = {
                                if (breakExpanded) { keyboard?.hide(); focusManager.clearFocus(force = true) }
                                breakExpanded = !breakExpanded
                            },
                            breakText = breakText,
                            onBreakText = { breakText = it },
                            paidBreak = paidBreak,
                            onPaidBreak = { paidBreak = it }
                        )
                    }
                }
                AnimatedVisibility(
                    visible = dayType == DayType.PAID_LEAVE,
                    enter = FormMotion.enter, exit = FormMotion.exit
                ) {
                    Column {
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
            }

            // ── Pay ──
            AnimatedVisibility(visible = isPaid, enter = FormMotion.enter, exit = FormMotion.exit) {
                Column {
                    Spacer(Modifier.height(AppSpace.cardGap))
                    FormCard {
                        // A salaried job is paid by the month: a rate per shift
                        // would mean nothing, so the form doesn't ask for one.
                        if (salaried) {
                            Text(
                                "Paid by your monthly salary, so this shift needs no rate. " +
                                    "Its hours still count, and a multiplier below marks it as overtime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        } else {
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
                        }

                        AnimatedVisibility(visible = isWorked, enter = FormMotion.enter, exit = FormMotion.exit) {
                            Column {
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
            SummaryCard(draft = draft, currencySymbol = currencySymbol, pay = activeProfile.pay)
        }
    }

    if (showIconPicker) {
        IconPickerSheet(
            name = name,
            colorValue = selectedColorValue,
            selected = iconName,
            sections = ShiftIconCatalog.forShifts,
            onPick = { iconName = it; showIconPicker = false },
            onDismiss = { showIconPicker = false }
        )
    }

    if (showCustomColor) {
        CustomColorSheet(
            initial = Color((customColorValue ?: selectedColorValue).toInt()),
            previewName = name,
            previewIcon = iconName,
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

/**
 * How optional parts of the form come and go: the space opens first and the
 * content fades in just behind it, and on the way out the content goes first
 * so nothing is seen being squashed. Quick enough to never hold the user up.
 */
internal object FormMotion {
    val enter: EnterTransition =
        expandVertically(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(180, delayMillis = 60))
    val exit: ExitTransition =
        fadeOut(tween(110)) +
            shrinkVertically(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)
}

/**
 * Break minutes and whether they are paid, folded behind one header row. The
 * header says what is set, so a folded section never hides a real value.
 */
@Composable
private fun BreakSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    breakText: String,
    onBreakText: (String) -> Unit,
    paidBreak: Boolean,
    onPaidBreak: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val minutes = breakText.trim().toIntOrNull()?.takeIf { it > 0 }
    val summary = when {
        minutes == null -> "No break"
        paidBreak -> "$minutes min · paid"
        else -> "$minutes min · unpaid"
    }
    val chevronTurn by animateFloatAsState(
        if (expanded) 180f else 0f, tween(240, easing = FastOutSlowInEasing), label = "breakChevron"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(
                    onClickLabel = if (expanded) "Hide break" else "Set a break",
                    onClick = onToggle
                )
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Break, null, tint = cs.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Break", optional = true)
                Spacer(Modifier.height(2.dp))
                AnimatedContent(
                    targetState = summary,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                    label = "breakSummary"
                ) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (minutes == null) cs.onSurfaceVariant else cs.primary,
                        maxLines = 1
                    )
                }
            }
            Icon(
                AppIcons.ChevronDown, null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(chevronTurn)
            )
        }

        AnimatedVisibility(visible = expanded, enter = FormMotion.enter, exit = FormMotion.exit) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                FormTextField(
                    value = breakText,
                    onValueChange = onBreakText,
                    placeholder = "0",
                    singleLine = true,
                    keyboardType = KeyboardType.Number,
                    suffix = "min"
                )
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    title = "Paid break",
                    subtitle = if (paidBreak) "Break counts as worked time"
                    else "Break is deducted from worked time",
                    checked = paidBreak,
                    onCheckedChange = onPaidBreak
                )
            }
        }
    }
}

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
    // Baseline, not Bottom: the two styles have different line heights, so
    // aligning their boxes' bottoms left "optional" sitting below the title.
    Row {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
            modifier = Modifier.alignByBaseline()
        )
        if (optional) {
            Spacer(Modifier.width(6.dp))
            Text(
                "optional",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.alignByBaseline()
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
                AppIcons.Clock, null,
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
private fun SummaryCard(draft: Shift, currencySymbol: String, pay: PaySetup = PaySetup()) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents

    val payableHours = PayEngine.payableHours(draft)
    val grossSpan = PayEngine.grossSpanHours(draft)
    val multiplierActive = PayEngine.effectiveMultiplier(draft) != 1.0
    // On a salary a shift earns nothing by itself — only marked overtime,
    // valued at the reference rate, and its bonus come on top.
    val markedOvertime = pay.isFixed && multiplierActive && PayEngine.isWorked(draft) &&
        pay.overtimeSource == OvertimeSource.MARKED_SHIFTS && pay.overtimeCompensation == OvertimeCompensation.PAID
    val earnings = if (pay.isFixed) {
        (if (markedOvertime) PayEngine.workedHours(draft) * pay.referenceHourlyRate * PayEngine.effectiveMultiplier(draft) else 0.0) +
            (if (draft.dayType == DayType.UNPAID_LEAVE) 0.0 else draft.bonus)
    } else PayEngine.earnings(draft)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.primary.copy(alpha = 0.10f))
            .animateContentSize(tween(220, easing = FastOutSlowInEasing))
            .padding(20.dp)
    ) {
        Text(
            if (pay.isFixed) "On top of your salary" else "This shift is worth",
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