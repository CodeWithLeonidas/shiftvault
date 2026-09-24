package com.leonidaskiachidis.shiftvault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════
//  Filtering / sorting model
// ══════════════════════════════════════════════════════════════

private enum class ShiftFilter(val label: String) {
    ALL("All"),
    MORNING("Morning"),
    EVENING("Evening"),
    NIGHT("Night"),
    ALL_DAY("All day")
}

private enum class ShiftSort(val label: String) {
    RECENT("Recent"),
    DURATION("Duration"),
    PAY("Pay")
}

private fun isAllDay(shift: Shift) = shift.startTime == "00:00" && shift.endTime == "23:59"

private fun matchesFilter(shift: Shift, filter: ShiftFilter): Boolean {
    if (filter == ShiftFilter.ALL) return true
    if (isAllDay(shift)) return filter == ShiftFilter.ALL_DAY
    if (filter == ShiftFilter.ALL_DAY) return false

    val hour = shift.startTime.substringBefore(":").toIntOrNull() ?: 9
    return when (filter) {
        ShiftFilter.MORNING -> hour in 5..11
        ShiftFilter.EVENING -> hour in 12..18
        ShiftFilter.NIGHT -> hour !in 5..18
        else -> true
    }
}

private fun timeLabel(shift: Shift): String =
    if (isAllDay(shift)) "All day" else "${shift.startTime} — ${shift.endTime}"

private fun durationLabel(hours: Double): String =
    if (hours % 1.0 == 0.0) "${hours.toInt()}h" else String.format("%.1fh", hours)

private fun dayTypeText(type: DayType): String = when (type) {
    DayType.WORKED -> "Worked"
    DayType.PAID_LEAVE -> "Paid leave"
    DayType.UNPAID_LEAVE -> "Unpaid leave"
}

/** "1.75" rather than "1.750000" — trims the noise off the multiplier. */
private fun multiplierText(value: Double): String =
    "×" + "%.2f".format(value).trimEnd('0').trimEnd('.')

// ══════════════════════════════════════════════════════════════
//  Screen
// ══════════════════════════════════════════════════════════════

@Composable
fun TemplatesScreen(
    shiftManager: ShiftManager,
    /**
     * Collected once at app level and handed down, so the list is already in
     * hand on this screen's very first frame. Collecting it here meant the
     * first composition always ran with an empty list and the user saw the
     * "No shifts yet" state flash before the real cards arrived.
     */
    templates: List<Shift>,
    onEdit: (Int) -> Unit,
    onCreateNew: () -> Unit,
    currencyCode: String = "EUR",
    /**
     * The active Job Profile. The list is filtered to it, but the indices
     * handed to [onEdit] / delete stay indices into the *full* library, which
     * is what CustomShiftScreen and ShiftManager operate on.
     */
    activeProfile: JobProfile = JobProfile.Default,
    onOpenProfiles: () -> Unit = {},
    /** Null when there is no other profile to copy from. */
    onCopyFromProfile: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()

    val cs = MaterialTheme.colorScheme

    val currencySymbol = remember(currencyCode) {
        try { java.util.Currency.getInstance(currencyCode).getSymbol(java.util.Locale.getDefault()) }
        catch (_: Exception) { currencyCode }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ShiftFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(ShiftSort.RECENT) }
    var filtersVisible by rememberSaveable { mutableStateOf(false) }

    // Everything filed under the job currently selected. A brand-new profile
    // has nothing here yet, which is exactly the point.
    val profileTemplates = remember(templates, activeProfile.id) {
        templates.filter { it.jobId == activeProfile.id }
    }

    // Keep the original index so onEdit / onDelete stay correct after filtering.
    val visible = remember(templates, activeProfile.id, query, filter, sort) {
        val q = query.trim().lowercase()
        val base = templates.withIndex()
            .filter { (_, shift) ->
                shift.jobId == activeProfile.id &&
                        (q.isEmpty() ||
                        shift.name.lowercase().contains(q) ||
                        shift.notes.lowercase().contains(q)) &&
                        matchesFilter(shift, filter)
            }
        when (sort) {
            ShiftSort.RECENT -> base
            // Same figures the cards display, so the order matches what is read.
            ShiftSort.DURATION -> base.sortedByDescending { PayEngine.payableHours(it.value) }
            ShiftSort.PAY -> base.sortedByDescending { PayEngine.earnings(it.value) }
        }.map { it.index to it.value }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ──
            ScreenHeader(
                title = "My Shifts",
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onCopyFromProfile != null) {
                            CircleIconButton(
                                icon = AppIcons.ContentCopy,
                                contentDescription = "Copy shifts from another job profile",
                                onClick = onCopyFromProfile,
                                container = cs.surfaceVariant,
                                tint = cs.onSurface
                            )
                        }
                        CircleIconButton(
                            icon = AppIcons.Filters,
                            contentDescription = if (filtersVisible) "Hide filters" else "Show filters",
                            onClick = { filtersVisible = !filtersVisible },
                            container = if (filtersVisible) cs.surface else cs.surfaceVariant,
                            tint = cs.onSurface
                        )
                    }
                }
            )

            // Sits where the subtitle was: the list below is this job's shifts,
            // so the job has to be readable without leaving the screen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH)
                    .padding(top = 2.dp)
            ) {
                ProfileChip(profile = activeProfile, onClick = onOpenProfiles)
            }

            AnimatedVisibility(
                visible = filtersVisible,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(120))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(14.dp))

                // ── Search ──
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search by name or note...",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpace.screenH)
                )

                Spacer(Modifier.height(16.dp))

                // ── Period pills ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = AppSpace.screenH),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ShiftFilter.entries.forEach { entry ->
                        val count = profileTemplates.count { matchesFilter(it, entry) }
                        FilterPill(
                            text = if (entry == ShiftFilter.ALL) "All ($count)" else entry.label,
                            selected = filter == entry,
                            onClick = { filter = entry }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Sort tabs ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpace.screenH),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Sort by:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    ShiftSort.entries.forEach { entry ->
                        TextTab(
                            text = entry.label,
                            selected = sort == entry,
                            onClick = { sort = entry }
                        )
                    }
                }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── List ──
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    profileTemplates.isEmpty() ->
                        EmptyProfileShiftsState(
                            profile = activeProfile,
                            canCopy = onCopyFromProfile != null,
                            onCopy = { onCopyFromProfile?.invoke() },
                            modifier = Modifier.fillMaxSize()
                        )

                    visible.isEmpty() ->
                        NoMatchesState(Modifier.fillMaxSize(), cs) {
                            query = ""; filter = ShiftFilter.ALL
                        }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = AppSpace.screenH,
                            end = AppSpace.screenH,
                            // Clears the 20.dp header fade below, plus the card's
                            // own drop shadow, so the first card is never touched
                            // by either.
                            top = 28.dp,
                            bottom = 108.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(
                            items = visible,
                            key = { (index, s) -> "$index-${s.name}-${s.startTime}-${s.colorValue}" }
                        ) { (index, shift) ->
                            ShiftCard(
                                shift = shift,
                                currencySymbol = currencySymbol,
                                onEdit = { onEdit(index) },
                                onDelete = {
                                    scope.launch {
                                        shiftManager.updateTemplates { current ->
                                            if (index in current.indices)
                                                current.toMutableList().also { it.removeAt(index) }
                                            else current
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // Soft fade so the list doesn't butt sharply against the filters
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(20.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    cs.background,
                                    cs.background.copy(alpha = 0f)
                                )
                            )
                        )
                )
            }
        }

        // ── FAB ──
        Surface(
            onClick = onCreateNew,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = AppSpace.screenH, bottom = 24.dp)
                .size(64.dp),
            shape = CircleShape,
            color = cs.primary,
            contentColor = cs.onPrimary,
            shadowElevation = 10.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.Add, "New shift",
                    tint = cs.onPrimary, modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Search field
// ══════════════════════════════════════════════════════════════

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = cs.surface,
        shadowElevation = 2.dp,
        border = if (AppTheme.accents.isDark) BorderStroke(1.dp, cs.outline) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                AppIcons.Search, null,
                tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(14.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    AppIcons.Close, "Clear search",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onValueChange("") }
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Shift card
// ══════════════════════════════════════════════════════════════

@Composable
private fun ShiftCard(
    shift: Shift,
    currencySymbol: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    var showDelete by remember { mutableStateOf(false) }

    val color = rememberShiftAccent(shift.colorValue)
    val hours = remember(shift) { PayEngine.payableHours(shift) }
    val total = remember(shift) { PayEngine.earnings(shift) }
    val payLabel =
        if (total > 0.0) "$currencySymbol${"%.2f".format(total)}" else "No pay"
    val subtitle = shift.location.ifBlank { shift.notes }
    val hasChips = shift.dayType != DayType.WORKED ||
            shift.payMultiplier != 1.0 ||
            shift.bonus > 0.0 ||
            (shift.breakMinutes > 0 && shift.paidBreak)

    SoftCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 14.dp)
    ) {
        // ── Top: dot · name · pay badge ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    shift.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (shift.location.isNotBlank()) {
                            Icon(
                                AppIcons.Place, null,
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(color.copy(alpha = 0.20f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    payLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                    maxLines = 1
                )
            }
        }

        if (hasChips) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (shift.dayType != DayType.WORKED) {
                    CardChip(
                        text = dayTypeText(shift.dayType),
                        container = if (shift.dayType == DayType.PAID_LEAVE)
                            accents.morningSoft else accents.negative.copy(alpha = 0.12f),
                        contentColor = if (shift.dayType == DayType.PAID_LEAVE)
                            accents.positive else accents.negative
                    )
                }
                if (shift.payMultiplier != 1.0 && shift.dayType == DayType.WORKED) {
                    CardChip(
                        text = "${multiplierText(shift.payMultiplier)} rate",
                        container = accents.nightSoft,
                        contentColor = accents.night
                    )
                }
                if (shift.bonus > 0.0) {
                    CardChip(
                        text = "+$currencySymbol${"%.2f".format(shift.bonus)} bonus",
                        container = accents.eveningSoft,
                        contentColor = accents.evening
                    )
                }
                if (shift.breakMinutes > 0 && shift.paidBreak) {
                    CardChip(
                        text = "Paid break",
                        container = cs.surfaceVariant,
                        contentColor = cs.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(cs.outline)
        )
        Spacer(Modifier.height(12.dp))

        // ── Bottom: time · actions ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.Schedule, null,
                tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (shift.dayType == DayType.WORKED)
                    "${timeLabel(shift)} (${durationLabel(hours)})"
                else
                    "${dayTypeText(shift.dayType)} · ${durationLabel(hours)}",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            CardActionButton(
                icon = AppIcons.Edit,
                desc = "Edit",
                container = cs.surfaceVariant,
                tint = cs.onSurface,
                onClick = onEdit
            )
            Spacer(Modifier.width(10.dp))
            CardActionButton(
                icon = AppIcons.Delete,
                desc = "Delete",
                container = accents.negative.copy(alpha = 0.12f),
                tint = accents.negative,
                onClick = { showDelete = true }
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            title = {
                Text(
                    "Delete this shift?",
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface
                )
            },
            text = {
                Text(
                    "\"${shift.name}\" will be removed from your saved shifts. Shifts already on your calendar stay where they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showDelete = false },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accents.negative)
                ) {
                    Text("Delete", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            }
        )
    }
}
@Composable
private fun CardChip(text: String, container: Color, contentColor: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}
@Composable
private fun CardActionButton(
    icon: Painter,
    desc: String,
    container: Color,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(19.dp)) }
}

// ══════════════════════════════════════════════════════════════
//  Empty states
// ══════════════════════════════════════════════════════════════

@Composable
fun EmptyTemplatesState(modifier: Modifier = Modifier, cs: ColorScheme = MaterialTheme.colorScheme) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 44.dp).padding(bottom = 60.dp)
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Shift, null,
                    tint = cs.primary, modifier = Modifier.size(38.dp)
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "No shifts yet",
                style = MaterialTheme.typography.displayMedium,
                color = cs.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap the ＋ button to build your first shift. Save it once and drop it onto any day in a single tap.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoMatchesState(
    modifier: Modifier = Modifier,
    cs: ColorScheme = MaterialTheme.colorScheme,
    onReset: () -> Unit
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 44.dp).padding(bottom = 60.dp)
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(cs.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Search, null,
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Nothing matches",
                style = MaterialTheme.typography.titleLarge,
                color = cs.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Try a different search or filter.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            PillButton(text = "Clear filters", onClick = onReset, tinted = true)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EmptyTemplatesPreview() {
    ShiftVaultTheme { EmptyTemplatesState(Modifier.fillMaxSize()) }
}