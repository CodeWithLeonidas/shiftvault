package com.leonidaskiachidis.shiftvault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch

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

    // Everything filed under the job currently selected. A brand-new profile
    // has nothing here yet, which is exactly the point.
    val profileTemplates = remember(templates, activeProfile.id) {
        templates.filter { it.jobId == activeProfile.id }
    }

    // Keep each shift's index in the full library, so onEdit / onDelete
    // reach the right one.
    val visible = remember(templates, activeProfile.id) {
        templates.withIndex()
            .filter { (_, shift) -> shift.jobId == activeProfile.id }
            .map { it.index to it.value }
    }

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
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "My Shifts",
                style = ScreenTitleStyle,
                color = cs.onBackground,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (onCopyFromProfile != null) {
                HeaderButton(
                    icon = AppIcons.Copy,
                    contentDescription = "Copy shifts from another job",
                    onClick = onCopyFromProfile
                )
                Spacer(Modifier.width(8.dp))
            }
            NewShiftButton(onClick = onCreateNew)
        }

        // The list below is this job's shifts, so the job has to be readable
        // — and switchable — without leaving the screen.
        ProfileSwitch(
            profile = activeProfile,
            onClick = onOpenProfiles,
            detail = "${profileTemplates.size} saved",
            modifier = Modifier.padding(start = AppSpace.screenH, top = 2.dp)
        )

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

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AppSpace.screenH,
                        end = AppSpace.screenH,
                        top = 18.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = visible,
                        key = { (index, s) -> "$index-${s.name}-${s.startTime}-${s.colorValue}" }
                    ) { (index, shift) ->
                        ShiftCard(
                            shift = shift,
                            currencySymbol = currencySymbol,
                            salaried = activeProfile.pay.isFixed,
                            onEdit = { onEdit(index) },
                            onDelete = {
                                scope.launch {
                                    shiftManager.updateTemplates { current ->
                                        if (index in current.indices)
                                            current.toMutableList().also { it.removeAt(index) }
                                        else current
                                    }
                                }
                            },
                            // Cards glide into place as shifts are added or removed.
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

/** The header's main action: a solid pill, "+ New". */
@Composable
private fun NewShiftButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(cs.primary)
            .clickable(onClickLabel = "Create a new shift", role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(AppIcons.Add, null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("New", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
    }
}

// ══════════════════════════════════════════════════════════════
//  Shift card
// ══════════════════════════════════════════════════════════════

/**
 * One saved shift, washed in its own colour: what kind of day it is, its
 * name, when it runs — large, with a 24-hour ring showing where in the day
 * that falls — and what it pays.
 */
@Composable
private fun ShiftCard(
    shift: Shift,
    currencySymbol: String,
    /** On a salaried job a shift has no price of its own. */
    salaried: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val isDark = accents.isDark
    var showDelete by remember { mutableStateOf(false) }

    val base = rememberShiftColor(shift.colorValue)
    val accent = rememberShiftAccent(base)
    val ink = rememberShiftInk(base)
    val wash = accent.copy(alpha = if (isDark) 0.22f else 0.14f)
    val worked = shift.dayType == DayType.WORKED
    val hours = remember(shift) { PayEngine.payableHours(shift) }
    val span = remember(shift) { PayEngine.grossSpanHours(shift) }
    val total = remember(shift) { PayEngine.earnings(shift) }

    val kind = when (shift.dayType) {
        DayType.WORKED -> "Worked · ${hoursLabel(hours)}"
        DayType.PAID_LEAVE -> "Paid leave · ${hoursLabel(hours)}"
        DayType.UNPAID_LEAVE -> "Unpaid leave"
    }
    val subtitle = when (shift.dayType) {
        DayType.WORKED -> null
        DayType.PAID_LEAVE -> "counts as ${hoursLabel(hours)} paid"
        DayType.UNPAID_LEAVE -> "no pay"
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(cs.background)
            .background(wash)
            // The whole card opens the shift; only Delete does anything else.
            .clickable(onClickLabel = "Edit ${shift.name}", onClick = onEdit)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        kind,
                        style = MaterialTheme.typography.labelMedium,
                        color = ink,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    shift.name,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp
                    ),
                    color = cs.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            DayRing(
                start = shift.startTime,
                spanHours = if (worked) span else 24.0,
                allDay = !worked,
                label = if (worked) hoursLabel(hours) else "Day",
                color = accent,
                ink = ink,
                track = cs.background,
                contentDescription = if (worked) "Runs ${shift.startTime} to ${shift.endTime}"
                else "All day"
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (worked) shiftTimeLabel(shift) else "All day",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp
                ),
                color = cs.onSurface,
                maxLines = 1
            )
            if (subtitle != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(cs.background)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        total > 0.0 -> buildAnnotatedString {
                            withStyle(SpanStyle(color = cs.onSurface, fontWeight = FontWeight.SemiBold)) {
                                append("$currencySymbol${"%.2f".format(total)}")
                            }
                            append(" / shift")
                        }
                        shift.dayType == DayType.UNPAID_LEAVE -> buildAnnotatedString { append("Unpaid") }
                        salaried -> buildAnnotatedString { append("Covered by your salary") }
                        else -> buildAnnotatedString { append("No rate set") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.weight(1f))
            CardActionButton(
                icon = AppIcons.Edit,
                desc = "Edit ${shift.name}",
                tint = cs.onSurface,
                onClick = onEdit
            )
            Spacer(Modifier.width(6.dp))
            CardActionButton(
                icon = AppIcons.Delete,
                desc = "Delete ${shift.name}",
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
                    "“${shift.name}” will be removed from your saved shifts. Shifts already on your calendar stay where they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showDelete = false },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accents.negative, contentColor = Color.White)
                ) {
                    Text("Delete", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text("Cancel", color = cs.onSurface)
                }
            }
        )
    }
}

/**
 * The shift on an ordinary clock face: 12 at the top, 3 on the right, so a
 * 09:00–17:00 shift is drawn from the 9 round to the 5, just as the hands
 * would sweep it. A shift of 12 hours or more fills the whole dial. A day of
 * leave is the whole ring, dotted, since it has no hours on the clock.
 */
@Composable
private fun DayRing(
    start: String,
    spanHours: Double,
    allDay: Boolean,
    label: String,
    color: Color,
    ink: Color,
    track: Color,
    contentDescription: String
) {
    val startHour = remember(start) {
        val h = start.substringBefore(":").toIntOrNull() ?: 0
        val m = start.substringAfter(":", "0").toIntOrNull() ?: 0
        h + m / 60f
    }
    Box(
        Modifier
            .size(64.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val width = 7.dp.toPx()
            val inset = width / 2 + 3.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(width))
            if (allDay) {
                val dot = 3.dp.toPx()
                drawArc(
                    color, -90f, 360f, false, topLeft, arcSize,
                    style = Stroke(width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, dot + width)))
                )
            } else {
                val sweep = (spanHours.coerceIn(0.0, 12.0) / 12.0 * 360.0).toFloat()
                drawArc(
                    color, -90f + (startHour % 12f) / 12f * 360f, sweep, false, topLeft, arcSize,
                    style = Stroke(width, cap = StrokeCap.Round)
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1)
    }
}

@Composable
private fun CardActionButton(
    icon: Painter,
    desc: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
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
                    AppIcons.NavShifts, null,
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
                "Tap New to set up your first shift. Save it once, then add it to any day with a single tap.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EmptyTemplatesPreview() {
    ShiftVaultTheme { EmptyTemplatesState(Modifier.fillMaxSize()) }
}
