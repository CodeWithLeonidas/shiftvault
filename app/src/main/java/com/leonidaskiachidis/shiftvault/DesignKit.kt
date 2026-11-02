package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  DesignKit — the pieces the redesigned screens share: the titled sheet, the
 *  pill switch and buttons, chips, segmented choices, form fields and the day
 *  timeline. Each screen moves onto these as it is redesigned, so a control
 *  looks and moves the same wherever it appears.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DayPillShape = RoundedCornerShape(7.dp)

/**
 * The hairline for fields and grouped blocks: the outline tone, eased back so
 * edges separate things without drawing attention to themselves.
 */
internal val ColorScheme.softEdge: Color
    get() = outline.copy(alpha = 0.6f)

/**
 * How thick those edges are drawn. Half a dp: a single fine line on a phone
 * screen, where a whole dp already reads as a heavy rule.
 */
internal val HairlineWidth: Dp = 0.5.dp

/** The big screen title: "November 2026", "My Shifts". */
internal val ScreenTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.displayLarge.copy(
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1.4).sp
    )

// Trimmed text metrics — keeps the tiny pill labels optically centred.
private val pillTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

// ══════════════════════════════════════════════════════════════
//  Sheets, switches, buttons
// ══════════════════════════════════════════════════════════════

/**
 * The redesign's titled sheet: [AppBottomSheet] — its drag and swipe-to-dismiss untouched —
 * under a header that says what the sheet is for above what it is about
 * ("Add a shift" / "Friday, 13 November"), with a close button for anyone
 * who would rather tap than swipe.
 */
@Composable
internal fun TitledSheet(
    onDismiss: () -> Unit,
    sheetState: AppSheetState,
    eyebrow: String,
    title: String,
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
                Column(Modifier.weight(1f)) {
                    Text(
                        eyebrow,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.displayMedium,
                        color = cs.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
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
            body()
        }
    }
}


/** The switch's look only; the row around it does the toggling. */
@Composable
internal fun PillSwitch(checked: Boolean) {
    val cs = MaterialTheme.colorScheme
    val isDark = AppTheme.accents.isDark
    val progress = animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.75f, stiffness = 500f),
        label = "pillSwitch"
    )
    val track by animateColorAsState(if (checked) cs.primary else cs.outline, tween(200), label = "switchTrack")
    // On a dark theme the "on" track is light, so the knob takes the dark ink.
    val knobOff = if (isDark) cs.onSurfaceVariant else Color.White
    val knob by animateColorAsState(if (checked) cs.onPrimary else knobOff, tween(200), label = "switchKnob")
    Box(
        Modifier
            .size(46.dp, 28.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(track) }
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .offset { IntOffset((progress.value * 18.dp.toPx()).roundToInt(), 0) }
                .size(22.dp)
                .shadow(1.5.dp, CircleShape)
                .background(knob, CircleShape)
        )
    }
}


/** The sheet's one main action: solid when it can be taken, muted until then. */
@Composable
internal fun PrimaryPillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (enabled) cs.primary else cs.surfaceVariant, tween(200), label = "ctaContainer")
    val content by animateColorAsState(if (enabled) cs.onPrimary else cs.onSurfaceVariant, tween(200), label = "ctaContent")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "ctaScale")
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .drawBehind { drawRect(container) }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(100))) },
            label = "ctaText"
        ) { label ->
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
                color = content,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
    }
}


/**
 * The job on show: its colour, its name, and a chevron saying there are others.
 * Opens the switcher. Kept to text, so the month below stays the loudest thing.
 */
@Composable
internal fun ProfileSwitch(
    profile: JobProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A quiet note after the chevron: "6 saved". */
    detail: String? = null
) {
    val cs = MaterialTheme.colorScheme
    val dot = rememberShiftAccent(profile.colorValue)
    Row(
        modifier
            // The ripple reaches past the dot; the dot still lines up with the gutter.
            .offset(x = (-8).dp)
            .height(44.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "Switch job profile", onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            profile.name,
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(4.dp))
        Icon(AppIcons.ChevronDown, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        if (detail != null) {
            Text(
                " · $detail",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}


/**
 * A shift's name on the calendar, in a soft wash of its colour. Extra hours get
 * a dashed edge and a leading "+", so they are told apart without colour too.
 */
@Composable
internal fun DayPill(shift: Shift, isDark: Boolean) {
    // Any colour can land here now that the form has a free picker, so the
    // stored value is normalised for this theme before it is drawn.
    val base = remember(shift.colorValue) { Color(shift.colorValue.toInt()) }
    val tint = remember(base, isDark) { ShiftColors.accent(base, isDark).copy(alpha = if (isDark) 0.26f else 0.16f) }
    val ink = remember(base, isDark) { ShiftColors.ink(base, isDark) }
    val extra = shift.isExtra && shift.dayType == DayType.WORKED
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp)
            .height(18.dp)
            .clip(DayPillShape)
            .background(tint)
            .then(
                if (extra) Modifier.drawBehind {
                    val width = 1.dp.toPx()
                    val dash = 3.dp.toPx()
                    drawRoundRect(
                        color = ink,
                        topLeft = Offset(width / 2, width / 2),
                        size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius(7.dp.toPx() - width / 2),
                        style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.7f)))
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (extra) "+${shift.name}" else shift.name,
            color = ink,
            fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = pillTextStyle,
            modifier = Modifier.padding(horizontal = 3.dp)
        )
    }
}


/** A round 44dp header action: tinted grey by default, solid for the main one. */
@Composable
internal fun HeaderButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

// ══════════════════════════════════════════════════════════════
//  Controls
// ══════════════════════════════════════════════════════════════

/**
 * A row that is one switch: what it does, what it means now, and the switch.
 * The whole row toggles; [PillSwitch] is only its look.
 */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 0 inside an already padded block. */
    horizontalPadding: Dp = 16.dp
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = horizontalPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(1.dp))
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(100))) },
                label = "switchSubtitle"
            ) { text ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        PillSwitch(checked)
    }
}

/**
 * A choice of a few, side by side in one track. The thumb slides to the pick
 * on a spring rather than jumping, so the change is seen happening.
 */
@Composable
internal fun <T> SegmentedPills(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val isDark = AppTheme.accents.isDark
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val position = animateFloatAsState(
        index.toFloat(),
        spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "segmentThumb"
    )
    // On a dark ground the surface is darker than the track, so the thumb
    // takes the lighter outline tone to still stand out.
    val thumb = if (isDark) cs.outline else cs.surface

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(cs.surfaceVariant)
            .padding(3.dp)
    ) {
        val segment = maxWidth / options.size
        Box(
            Modifier
                .offset { IntOffset((segment.toPx() * position.value).roundToInt(), 0) }
                .width(segment)
                .fillMaxHeight()
                .shadow(if (isDark) 0.dp else 2.dp, CircleShape)
                .background(thumb, CircleShape)
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val color by animateColorAsState(
                    if (isSelected) cs.onSurface else cs.onSurfaceVariant,
                    tween(180), label = "segmentLabel"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** A filter chip: solid when on, outlined when off. */
@Composable
internal fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) cs.primary else cs.background, tween(180), label = "chipContainer")
    val content by animateColorAsState(if (selected) cs.onPrimary else cs.onSurface, tween(180), label = "chipContent")
    val edge by animateColorAsState(if (selected) cs.primary else cs.outline, tween(180), label = "chipEdge")
    Box(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(container) }
            .border(1.dp, edge, CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** The small grey label over a form field: "Name", "Location · optional". */
@Composable
internal fun FieldCaption(text: String, optional: Boolean = false, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        buildAnnotatedString {
            append(text)
            if (optional) {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(" · optional") }
            }
        },
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = cs.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * The redesign's text field: a quiet hairline that darkens to the ink colour
 * while focused, on a rounded 16dp box.
 */
@Composable
internal fun DesignTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    prefix: String? = null,
    suffix: String? = null,
    leadingIcon: Painter? = null,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = 0.dp
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
        unfocusedBorderColor = cs.softEdge,
        focusedContainerColor = cs.background,
        unfocusedContainerColor = cs.background,
        cursorColor = cs.onSurface
    )
    // Built from the parts rather than OutlinedTextField, which only draws a
    // full-dp edge: here the edge is a hairline until the field is in use.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(minHeight, 52.dp)),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, color = cs.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6,
        interactionSource = interaction,
        cursorBrush = SolidColor(cs.onSurface)
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = inner,
            enabled = true,
            singleLine = singleLine,
            visualTransformation = VisualTransformation.None,
            interactionSource = interaction,
            placeholder = {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant.copy(alpha = 0.8f))
            },
            leadingIcon = leadingIcon?.let {
                { Icon(it, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
            },
            trailingIcon = trailing,
            prefix = prefix?.let {
                { Text(it, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface) }
            },
            suffix = suffix?.let {
                { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            },
            colors = colors,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 14.dp, bottom = 14.dp),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = false,
                    interactionSource = interaction,
                    colors = colors,
                    shape = shape,
                    focusedBorderThickness = 1.dp,
                    unfocusedBorderThickness = HairlineWidth
                )
            }
        )
    }
}

/** A hairline-bordered group of rows, as in the form's Extra / Break / Pay block. */
@Composable
internal fun GroupedRows(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(HairlineWidth, cs.softEdge, RoundedCornerShape(20.dp)),
        content = content
    )
}

/** The rule between rows of a [GroupedRows]. */
@Composable
internal fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(HairlineWidth)
            .background(MaterialTheme.colorScheme.softEdge)
    )
}

// ══════════════════════════════════════════════════════════════
//  Day timeline
// ══════════════════════════════════════════════════════════════

/** "09:30" → 9.5; anything unreadable → null. */
private fun hourOf(time: String): Float? {
    val h = time.substringBefore(":").toIntOrNull() ?: return null
    val m = time.substringAfter(":", "0").toIntOrNull() ?: 0
    return h + m / 60f
}

/**
 * Where [start]–[end] falls in a 24-hour day, as a thin track with the shift's
 * span on it. A shift that runs past midnight wraps: its evening part at the
 * right end, the morning part at the left. Equal times are a full day.
 */
@Composable
internal fun DayTimeline(
    start: String,
    end: String,
    color: Color,
    modifier: Modifier = Modifier,
    showScale: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val track = cs.surfaceVariant
    val from = hourOf(start) ?: 0f
    val to = hourOf(end) ?: 0f
    // Eases between spans as the times change, instead of snapping.
    val a by animateFloatAsState(from / 24f, tween(260, easing = FastOutSlowInEasing), label = "timelineStart")
    val b by animateFloatAsState(to / 24f, tween(260, easing = FastOutSlowInEasing), label = "timelineEnd")
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .drawBehind {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    fun span(x0: Float, x1: Float) {
                        if (x1 <= x0) return
                        drawRoundRect(
                            color,
                            topLeft = Offset(size.width * x0, 0f),
                            size = Size(size.width * (x1 - x0), size.height),
                            cornerRadius = r
                        )
                    }
                    when {
                        a == b -> span(0f, 1f)
                        b > a -> span(a, b)
                        else -> { span(a, 1f); span(0f, b) }
                    }
                }
        )
        if (showScale) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00:00", "12:00", "24:00").forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Help tips
// ══════════════════════════════════════════════════════════════

/**
 * Below the anchor, centred on it and kept inside the window; above it when
 * there is no room below.
 */
private class TipPosition(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - margin) below
        else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, y)
    }
}

/**
 * A small "?" that opens [text] in a little card beside it. A tap anywhere
 * else closes it. Keeps explanations out of the form until they are wanted.
 */
@Composable
internal fun HelpTip(text: String, contentDescription: String = "More about this") {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = open
    val position = remember(density) {
        with(density) { TipPosition(gap = 6.dp.roundToPx(), margin = 16.dp.roundToPx()) }
    }

    Box {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { open = !open }
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .border(1.3.dp, cs.onSurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "?",
                    style = pillTextStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        // Stays in the composition until its fade-out has finished.
        if (visibility.currentState || visibility.targetState) {
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true)
            ) {
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.92f),
                    exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.96f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = cs.inverseSurface,
                        contentColor = cs.inverseOnSurface,
                        shadowElevation = 6.dp,
                        modifier = Modifier.widthIn(max = 260.dp)
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

/** A short figure with its label and a "?" for what it means: rate, multiplier, bonus. */
@Composable
internal fun NumberField(
    label: String,
    help: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    suffix: String? = null
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldCaption(label)
            HelpTip(help, contentDescription = "About ${label.lowercase()}")
        }
        Spacer(Modifier.height(2.dp))
        DesignTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardType = KeyboardType.Decimal,
            prefix = prefix,
            suffix = suffix
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  Underline tabs
// ══════════════════════════════════════════════════════════════

/**
 * Text tabs over a hairline, the current one bold with an ink underline that
 * slides across to the pick. For a second level of tabs inside a screen.
 */
@Composable
internal fun <T> UnderlineTabs(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    // Each tab's left edge and width, as laid out, for the underline to travel between.
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Float, Float>>() }
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val target = bounds[index]
    val left by animateFloatAsState(target?.first ?: 0f, spring(dampingRatio = 0.85f, stiffness = 500f), label = "tabLeft")
    val width by animateFloatAsState(target?.second ?: 0f, spring(dampingRatio = 0.85f, stiffness = 500f), label = "tabWidth")
    val rule = cs.softEdge
    val ink = cs.onSurface

    Row(
        modifier
            .fillMaxWidth()
            .background(cs.background)
            .drawBehind {
                drawLine(rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                if (width > 0f) {
                    val h = 2.dp.toPx()
                    drawRect(ink, topLeft = Offset(left, size.height - h), size = Size(width, h))
                }
            }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        options.forEachIndexed { i, (value, label) ->
            val isSelected = i == index
            val color by animateColorAsState(if (isSelected) cs.onSurface else cs.onSurfaceVariant, tween(180), label = "tabColor")
            Box(
                Modifier
                    .onGloballyPositioned { c ->
                        val x = c.positionInParent().x
                        val w = c.size.width.toFloat()
                        if (bounds[i] != x to w) bounds[i] = x to w
                    }
                    .selectable(selected = isSelected, role = Role.Tab, indication = null,
                        interactionSource = remember { MutableInteractionSource() }) { onSelect(value) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = color,
                    maxLines = 1
                )
            }
        }
    }
}
