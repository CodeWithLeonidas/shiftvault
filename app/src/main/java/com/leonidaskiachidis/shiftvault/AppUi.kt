package com.leonidaskiachidis.shiftvault

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme

// ══════════════════════════════════════════════════════════════
//  Spacing scale — use these instead of magic numbers
// ══════════════════════════════════════════════════════════════

object AppSpace {
    val screenH: Dp = 20.dp      // horizontal screen padding
    val cardGap: Dp = 16.dp      // gap between cards
    val cardPad: Dp = 20.dp      // inner card padding
    val tight: Dp = 8.dp
}

// ══════════════════════════════════════════════════════════════
//  SoftCard — the white rounded container used everywhere
// ══════════════════════════════════════════════════════════════

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = PaddingValues(AppSpace.cardPad),
    elevation: Dp = 2.dp,
    showBorder: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val stroke = when {
        showBorder -> BorderStroke(1.dp, cs.outline)
        // A drop shadow is invisible against a dark background, which left cards
        // and page merging into one flat surface. The outline does that job.
        AppTheme.accents.isDark -> BorderStroke(1.dp, cs.outline)
        else -> null
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = cs.surface,
            contentColor = cs.onSurface,
            shadowElevation = elevation,
            border = stroke
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = cs.surface,
            contentColor = cs.onSurface,
            shadowElevation = elevation,
            border = stroke
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  ScreenHeader — big title + subtitle + optional trailing action
// ══════════════════════════════════════════════════════════════

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpace.screenH, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = cs.onBackground
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  SectionTitle — "Today's shift"  +  optional trailing
// ══════════════════════════════════════════════════════════════

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) trailing()
    }
}

// ══════════════════════════════════════════════════════════════
//  StatCard — label / big value / delta
// ══════════════════════════════════════════════════════════════

@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    delta: String? = null,
    deltaPositive: Boolean? = null
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val deltaColor = when (deltaPositive) {
        true -> accents.positive
        false -> accents.negative
        null -> cs.onSurfaceVariant
    }

    SoftCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = cs.onSurface,
            maxLines = 1
        )
        if (delta != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = delta,
                style = MaterialTheme.typography.labelMedium,
                color = deltaColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  PillButton — white pill with optional icon ("Export")
// ══════════════════════════════════════════════════════════════

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    selected: Boolean = false,
    tinted: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val container = when {
        selected -> cs.primary
        tinted -> cs.surfaceVariant
        else -> cs.surface
    }
    val contentColor = when {
        selected -> cs.onPrimary
        tinted -> cs.onSurface
        else -> cs.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = contentColor,
        shadowElevation = if (selected || tinted) 0.dp else 2.dp,
        border = if (AppTheme.accents.isDark && !selected)
            BorderStroke(1.dp, cs.outline) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = if (selected) cs.onPrimary else cs.primary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  FilterPill — "All (24)" / "Morning" / "Evening"
// ══════════════════════════════════════════════════════════════

@Composable
fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) cs.primary else cs.surface,
        contentColor = if (selected) cs.onPrimary else cs.onSurface,
        shadowElevation = if (selected) 0.dp else 1.dp,
        border = if (AppTheme.accents.isDark && !selected)
            BorderStroke(1.dp, cs.outline) else null
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  SegmentedControl — one-of-a-few choice in a pill track
//  ("Worked / Paid leave / Unpaid", "Monthly / Weekly / Every 2 weeks")
// ══════════════════════════════════════════════════════════════

@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val gap = 4.dp
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    // One pill slides under the options instead of each option switching its
    // own background, so the eye follows the choice from old to new.
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(cs.surfaceVariant)
            .padding(gap)
    ) {
        val segment = (maxWidth - gap * (options.size - 1)) / options.size
        val offset by animateDpAsState(
            targetValue = (segment + gap) * index,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
            label = "segmentIndicator"
        )
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .offset { IntOffset(offset.roundToPx(), 0) }
                    .width(segment)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(cs.primary)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val textColor by animateColorAsState(
                    if (isSelected) cs.onPrimary else cs.onSurfaceVariant,
                    tween(180),
                    label = "segmentText"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .selectable(selected = isSelected, role = Role.Tab) {
                            if (!isSelected) onSelect(value)
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  TextTab — borderless label tab ("Date (Newest)" / "Duration")
// ══════════════════════════════════════════════════════════════

@Composable
fun TextTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) cs.surfaceVariant else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  CircleIconButton — floating white circle (bell, filter, arrows)
// ══════════════════════════════════════════════════════════════

@Composable
fun CircleIconButton(
    icon: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
    iconSize: Dp = 21.dp,
    container: Color? = null,
    tint: Color? = null,
    elevation: Dp = 2.dp
) {
    val cs = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = modifier.size(diameter),
        shape = CircleShape,
        color = container ?: cs.surface,
        contentColor = tint ?: cs.onSurface,
        shadowElevation = elevation,
        border = if (AppTheme.accents.isDark) BorderStroke(1.dp, cs.outline) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = icon,
                contentDescription = contentDescription,
                tint = tint ?: cs.onSurface,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  SoftBadge — tinted rounded label ("$128.00", "In 2 hours")
// ══════════════════════════════════════════════════════════════

@Composable
fun SoftBadge(
    text: String,
    container: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  LegendDot — colored dot + label (calendar legend, donut legend)
// ══════════════════════════════════════════════════════════════

@Composable
fun LegendDot(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    dotSize: Dp = 9.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  Preview — visual check of the whole kit
// ══════════════════════════════════════════════════════════════

@Preview(showBackground = true, backgroundColor = 0xFFF7F4EF, heightDp = 640)
@Composable
private fun AppUiKitPreview() {
    ShiftVaultTheme(darkTheme = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpace.cardGap)
        ) {
            ScreenHeader(
                title = "Wallet",
                subtitle = "Earnings & workload insights",
                trailing = { PillButton(text = "Export", onClick = {}, icon = AppIcons.Upload) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.screenH),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard("Hours", "142h", Modifier.weight(1f), "+12% vs last mo", true)
                StatCard("Earnings", "\$2,840", Modifier.weight(1f), "+8.4%", true)
                StatCard("Avg shift", "7.4h", Modifier.weight(1f), "Stable", null)
            }

            SoftCard(modifier = Modifier.padding(horizontal = AppSpace.screenH)) {
                SectionTitle(
                    text = "Hours worked",
                    trailing = { PillButton("Month view", onClick = {}, tinted = true) }
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterPill("All (24)", true, {})
                    FilterPill("Morning", false, {})
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextTab("Date (Newest)", true, {})
                    TextTab("Duration", false, {})
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SoftBadge("\$128.00", AppTheme.accents.morningSoft, AppTheme.accents.positive)
                    SoftBadge("\$165.00", AppTheme.accents.eveningSoft, MaterialTheme.colorScheme.onSurface)
                    CircleIconButton(AppIcons.NavSettings, "Settings", {}, diameter = 40.dp, iconSize = 18.dp)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    LegendDot(AppTheme.accents.morning, "Morning")
                    LegendDot(AppTheme.accents.evening, "Evening")
                    LegendDot(AppTheme.accents.night, "Night")
                }
            }
        }
    }
}