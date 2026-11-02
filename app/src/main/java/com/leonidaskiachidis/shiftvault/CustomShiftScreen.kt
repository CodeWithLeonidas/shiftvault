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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
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

private val csDayLabelFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")

/** Default span for a brand-new shift. */
private const val DEFAULT_START_TIME = "09:00"
private const val DEFAULT_END_TIME = "17:00"


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
    // Blank means 1×, the normal rate. Any value can be typed; nothing is
    // applied automatically — an extra shift pays its normal rate unless set.
    var multiplierText by rememberSaveable { mutableStateOf("") }
    var isExtra by rememberSaveable { mutableStateOf(false) }
    // An edited calendar entry keeps its paid tick; anything new starts pending.
    var extraPaid by rememberSaveable { mutableStateOf(false) }
    // Multiplier and bonus are rarely needed: folded away unless in use.
    var payOptionsExpanded by rememberSaveable { mutableStateOf(false) }
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
        multiplierText = shift.payMultiplier.takeIf { it > 0.0 && it != 1.0 }?.let(::formatDecimalInput) ?: ""
        isExtra = shift.isExtra
        extraPaid = shift.extraPaid
        payOptionsExpanded = multiplierText.isNotEmpty() || shift.bonus > 0.0
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
    val multiplier = parseDecimal(multiplierText)?.takeIf { it > 0.0 } ?: PayMultipliers.NORMAL

    val cs = MaterialTheme.colorScheme

    // The draft, assembled the same way save() does it — so the summary below
    // reflects exactly what will be stored.
    val draft = remember(
        name, startTime, endTime, dayType, paidBreak, multiplier, isExtra,
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
            paidLeaveHours = parseDecimal(paidLeaveText) ?: 8.0,
            // Only worked time can be extra hours.
            isExtra = dayType == DayType.WORKED && isExtra
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
            jobId = carriedJobId,
            // Saved shifts never carry a paid tick; a calendar entry being
            // edited keeps the one it had.
            extraPaid = frozenEntryIndex != null && draft.isExtra && extraPaid
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

    val accent = rememberShiftAccent(selectedColorValue)
    val money = { amount: Double -> "$currencySymbol${"%,.2f".format(amount)}" }

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
                .padding(top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderButton(
                icon = AppIcons.ChevronLeft,
                contentDescription = "Back",
                onClick = { leaveScreen(onBackClick) }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (isEditing) "Edit shift" else "New shift",
                    style = MaterialTheme.typography.titleMedium,
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
                shadowElevation = 0.dp,
                modifier = Modifier.height(44.dp)
            ) {
                Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                    Text("Save", style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        // ══════════════ Form ══════════════
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpace.screenH)
                .padding(top = 8.dp, bottom = 32.dp)
        ) {
            // ── Basics ──
            FieldCaption("Name", modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(8.dp))
            DesignTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. Barista shift",
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focusManager.clearFocus() })
            )

            FieldCaption("Icon", modifier = Modifier.padding(top = 24.dp))
            Spacer(Modifier.height(10.dp))
            IconChoices(
                name = name,
                selected = iconName,
                colorValue = selectedColorValue,
                onPick = { iconName = it },
                onMore = {
                    keyboard?.hide(); focusManager.clearFocus(force = true)
                    showIconPicker = true
                }
            )

            FieldCaption("Color", modifier = Modifier.padding(top = 24.dp))
            Spacer(Modifier.height(10.dp))
            ColorPicker(
                selected = selectedColorValue,
                custom = customColorValue,
                onSelect = { selectedColorValue = it },
                onCustom = {
                    keyboard?.hide(); focusManager.clearFocus(force = true)
                    showCustomColor = true
                }
            )

            // ── Day type ──
            FieldCaption("Day type", modifier = Modifier.padding(top = 28.dp))
            Spacer(Modifier.height(10.dp))
            SegmentedPills(
                options = DayType.entries.map { it to dayTypeLabel(it) },
                selected = dayType,
                onSelect = { dayType = it }
            )
            // The line underneath changes length with the choice, so it
            // crossfades and the form eases to its new height.
            AnimatedContent(
                targetState = dayType,
                transitionSpec = {
                    fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(100)) using
                        SizeTransform(clip = false) { _, _ -> tween(220, easing = FastOutSlowInEasing) }
                },
                label = "dayTypeHint",
                modifier = Modifier.padding(top = 8.dp)
            ) { type ->
                Text(
                    when (type) {
                        DayType.WORKED -> "Time on the clock, paid by the hour."
                        DayType.PAID_LEAVE -> "A paid day away: annual leave, sick leave, a paid public holiday."
                        DayType.UNPAID_LEAVE -> "A day away without pay. Logged for the record, earns nothing."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Each block carries its own leading space, so nothing is left
            // behind to jump when it leaves.
            AnimatedVisibility(visible = isWorked, enter = FormMotion.enter, exit = FormMotion.exit) {
                Column {
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeTile("Starts", startTime, Modifier.weight(1f)) {
                            keyboard?.hide(); focusManager.clearFocus(force = true)
                            pickingStart = true; showTimePicker = true
                        }
                        TimeTile("Ends", endTime, Modifier.weight(1f)) {
                            keyboard?.hide(); focusManager.clearFocus(force = true)
                            pickingStart = false; showTimePicker = true
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DayTimeline(start = startTime, end = endTime, color = accent, showScale = false)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        spanCaption(draft, startTime, endTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(
                visible = dayType == DayType.PAID_LEAVE,
                enter = FormMotion.enter, exit = FormMotion.exit
            ) {
                Column {
                    FieldCaption("Paid hours credited", modifier = Modifier.padding(top = 18.dp))
                    Spacer(Modifier.height(8.dp))
                    DesignTextField(
                        value = paidLeaveText,
                        onValueChange = { paidLeaveText = it },
                        placeholder = "8",
                        keyboardType = KeyboardType.Decimal,
                        suffix = "hours"
                    )
                }
            }

            // ── Extra hours, break and pay ──
            AnimatedVisibility(visible = isPaid, enter = FormMotion.enter, exit = FormMotion.exit) {
                Column {
                    Spacer(Modifier.height(24.dp))
                    GroupedRows {
                        AnimatedVisibility(visible = isWorked, enter = FormMotion.enter, exit = FormMotion.exit) {
                            Column {
                                SwitchRow(
                                    title = "Extra hours",
                                    subtitle = if (isExtra) "Counted as extra and tracked in the Wallet until paid"
                                    else "Overtime or a picked-up shift, counted separately",
                                    checked = isExtra,
                                    onCheckedChange = { isExtra = it }
                                )
                                GroupDivider()
                                val minutes = breakText.trim().toIntOrNull()?.takeIf { it > 0 }
                                ExpandRow(
                                    title = "Break",
                                    summary = when {
                                        minutes == null -> "No break"
                                        paidBreak -> "$minutes min · paid"
                                        else -> "$minutes min · unpaid"
                                    },
                                    summaryActive = minutes != null,
                                    expanded = breakExpanded,
                                    onToggle = {
                                        if (breakExpanded) { keyboard?.hide(); focusManager.clearFocus(force = true) }
                                        breakExpanded = !breakExpanded
                                    }
                                ) {
                                    DesignTextField(
                                        value = breakText,
                                        onValueChange = { breakText = it },
                                        placeholder = "0",
                                        keyboardType = KeyboardType.Number,
                                        suffix = "min"
                                    )
                                    SwitchRow(
                                        title = "Paid break",
                                        subtitle = if (paidBreak) "Counts as worked time"
                                        else "Taken off the worked time",
                                        checked = paidBreak,
                                        onCheckedChange = { paidBreak = it },
                                        horizontalPadding = 0.dp
                                    )
                                }
                                GroupDivider()
                            }
                        }

                        val bonus = parseDecimal(bonusText)?.takeIf { it > 0.0 }
                        val rate = parseDecimal(rateText)?.takeIf { it > 0.0 }
                        val customised = (multiplier != 1.0 && isWorked) || bonus != null
                        val paySummary = listOfNotNull(
                            if (salaried) "Monthly salary"
                            else rate?.let { "$currencySymbol${formatDecimalInput(it)}/hr" } ?: "No rate set",
                            if (isWorked) {
                                if (multiplier != 1.0) "${formatDecimalInput(multiplier)}× rate" else "normal rate"
                            } else null,
                            bonus?.let { "+${money(it)} bonus" } ?: "no bonus"
                        ).joinToString(" · ")
                        ExpandRow(
                            title = "Pay",
                            summary = paySummary,
                            summaryActive = customised,
                            expanded = payOptionsExpanded,
                            onToggle = {
                                if (payOptionsExpanded) { keyboard?.hide(); focusManager.clearFocus(force = true) }
                                payOptionsExpanded = !payOptionsExpanded
                            }
                        ) {
                            // Each figure is a short number, so they sit two to
                            // a row; what each one means is a tap away on its
                            // "?" rather than spelled out under it. A salaried
                            // job is paid by the month: a rate per shift would
                            // mean nothing, so none is asked for.
                            val payFields = buildList<@Composable (Modifier) -> Unit> {
                                if (!salaried) add { m ->
                                    NumberField(
                                        label = "Hourly rate",
                                        help = "What this shift pays for each hour. Optional: it starts from your job's default rate.",
                                        value = rateText,
                                        onValueChange = { rateText = it },
                                        placeholder = "0.00",
                                        prefix = currencySymbol,
                                        suffix = "/hr",
                                        modifier = m
                                    )
                                }
                                if (isWorked) add { m ->
                                    NumberField(
                                        label = "Multiplier",
                                        help = "Optional. Leave blank for the normal rate, or set one if this shift really " +
                                            "pays more, like 1.5 for time and a half. Marking hours as extra never changes pay on its own.",
                                        value = multiplierText,
                                        onValueChange = { multiplierText = it },
                                        placeholder = "1",
                                        suffix = "×",
                                        modifier = m
                                    )
                                }
                                add { m ->
                                    NumberField(
                                        label = "Bonus",
                                        help = "Optional. Tips, a travel allowance, or any flat amount on top of this shift's pay.",
                                        value = bonusText,
                                        onValueChange = { bonusText = it },
                                        placeholder = "0.00",
                                        prefix = currencySymbol,
                                        modifier = m
                                    )
                                }
                            }
                            payFields.chunked(2).forEachIndexed { rowIndex, row ->
                                if (rowIndex > 0) Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { field -> field(Modifier.weight(1f)) }
                                    // A lone field keeps its half width.
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (salaried) {
                        Text(
                            "Covered by your monthly salary, so no rate is needed. The hours still count.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // ── Details ──
            FieldCaption("Location", optional = true, modifier = Modifier.padding(top = 26.dp))
            Spacer(Modifier.height(8.dp))
            DesignTextField(
                value = location,
                onValueChange = { location = it },
                placeholder = "e.g. Downtown office"
            )
            FieldCaption("Note", optional = true, modifier = Modifier.padding(top = 20.dp))
            Spacer(Modifier.height(8.dp))
            DesignTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = "Anything to remember",
                singleLine = false,
                minHeight = 96.dp
            )
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

/** "8h on the clock · ends the same day", plus the paid time when a break is taken off. */
private fun spanCaption(draft: Shift, start: String, end: String): String {
    val span = PayEngine.grossSpanHours(draft)
    val paid = PayEngine.payableHours(draft)
    // Equal times are a full 24 hours, so they end the next day too.
    val nextDay = end <= start
    val base = "${hoursText(span)} on the clock · ends the ${if (nextDay) "next" else "same"} day"
    return if (paid < span) "$base · ${hoursText(paid)} paid" else base
}

/** The quick picks: the name's letter, four common marks, and the rest a tap away. */
private val QuickIcons = listOf("shift_morning", "shift_evening", "shift_night", "vacation")

@Composable
private fun IconChoices(
    name: String,
    selected: String?,
    colorValue: Long,
    onPick: (String?) -> Unit,
    onMore: () -> Unit
) {
    // A pick made in the full picker takes the last quick slot, so the
    // chosen mark is always in view.
    val keys: List<String?> = remember(selected) {
        val quick = if (selected != null && selected !in QuickIcons) QuickIcons.take(3) + selected else QuickIcons
        listOf<String?>(null) + quick
    }
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { key ->
            IconTile(
                key = key,
                name = name,
                colorValue = colorValue,
                selected = key == selected,
                modifier = Modifier.weight(1f),
                onClick = { onPick(key) }
            )
        }
        val cs = MaterialTheme.colorScheme
        Box(
            Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surfaceVariant)
                .clickable(onClickLabel = "Choose from all icons", onClick = onMore),
            contentAlignment = Alignment.Center
        ) {
            // Three dots: "and more", without a word to squeeze into the tile.
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { Box(Modifier.size(4.dp).background(cs.onSurfaceVariant, CircleShape)) }
            }
        }
    }
}

@Composable
private fun IconTile(
    key: String?,
    name: String,
    colorValue: Long,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDark = AppTheme.accents.isDark
    val base = rememberShiftColor(colorValue)
    val accent = rememberShiftAccent(base)
    val ink = rememberShiftInk(base)
    val shape = RoundedCornerShape(16.dp)
    val fill by animateColorAsState(
        if (selected) accent.copy(alpha = if (isDark) 0.24f else 0.16f) else cs.surfaceVariant,
        tween(180), label = "iconTileFill"
    )
    val edge by animateColorAsState(if (selected) ink else Color.Transparent, tween(180), label = "iconTileEdge")
    val res = ShiftIconCatalog.resOf(key)
    Box(
        modifier
            .height(50.dp)
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .border(1.5.dp, edge, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = key?.let(ShiftIconCatalog::labelOf) ?: "First letter of the name" },
        contentAlignment = Alignment.Center
    ) {
        val tint = if (selected) ink else cs.onSurfaceVariant
        if (res != null) {
            ToneIcon(
                id = res,
                contentDescription = null,
                tones = if (selected) IconTones(outline = ink, fill = accent.copy(alpha = 0.35f), accent = accent)
                else IconTones.of(tint, isDark),
                size = 22.dp
            )
        } else {
            Text(
                initialOf(name) ?: "Aa",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp),
                color = tint
            )
        }
    }
}

@Composable
private fun TimeTile(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(72.dp)
            .clip(shape)
            .border(HairlineWidth, cs.softEdge, shape)
            .clickable(onClickLabel = if (label == "Starts") "Change start time" else "Change end time", onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
            label = "timeTile"
        ) { time ->
            Text(
                time,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
                color = cs.onSurface
            )
        }
    }
}

/**
 * A row of the grouped block that opens in place: its title, what is set,
 * and the fields underneath once opened. The summary means a folded row never
 * hides a real value.
 */
@Composable
private fun ExpandRow(
    title: String,
    summary: String,
    summaryActive: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val chevronTurn by animateFloatAsState(
        if (expanded) 90f else 0f, tween(240, easing = FastOutSlowInEasing), label = "rowChevron"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "${if (expanded) "Hide" else "Show"} ${title.lowercase()} options", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                Spacer(Modifier.height(1.dp))
                AnimatedContent(
                    targetState = summary,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                    label = "rowSummary"
                ) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summaryActive) cs.onSurface else cs.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                AppIcons.ChevronRight, null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp).rotate(chevronTurn)
            )
        }
        AnimatedVisibility(visible = expanded, enter = FormMotion.enter, exit = FormMotion.exit) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}

/**
 * A folded group of optional settings: a header that says what is set, and
 * the fields underneath once opened. Keeps rarely-used options a tap away
 * instead of in the way. Same look as the break section above.
 */
@Composable
internal fun CollapsibleSection(
    icon: androidx.compose.ui.graphics.painter.Painter,
    title: String,
    summary: String,
    summaryActive: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val chevronTurn by animateFloatAsState(
        if (expanded) 180f else 0f, tween(240, easing = FastOutSlowInEasing), label = "sectionChevron"
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
                .clickable(onClickLabel = if (expanded) "Hide $title" else "Show $title", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = cs.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summaryActive) cs.primary else cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                AppIcons.ChevronDown, null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(chevronTurn)
            )
        }
        AnimatedVisibility(visible = expanded, enter = FormMotion.enter, exit = FormMotion.exit) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), content = content)
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
internal fun ToggleRow(
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

@Preview(showBackground = true)
@Composable
fun CustomShiftScreenPreview() {
    ShiftVaultTheme {
        // Preview only — CustomShiftScreen needs a real ShiftManager at runtime.
    }
}