package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth
import kotlin.text.format
import kotlin.toString

enum class StatsMode {
    MONTHLY, YEARLY, CUSTOM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    savedShifts: Map<java.time.LocalDate, List<Shift>>,
    onBackClick: () -> Unit,
    currencyCode: String = "EUR",
    modifier: Modifier = Modifier
) {
    var currentMode by remember { mutableStateOf(StatsMode.MONTHLY) }
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedYear by remember { mutableStateOf(java.time.Year.now()) }
    var startDate by remember { mutableStateOf(java.time.LocalDate.now().minusWeeks(1)) }
    var endDate by remember { mutableStateOf(java.time.LocalDate.now()) }
    var showRangePicker by remember { mutableStateOf(false) }

    val stats = remember(savedShifts, selectedMonth, selectedYear, startDate, endDate, currentMode) {
        when (currentMode) {
            StatsMode.MONTHLY -> StatsHelper.calculateMonthlyStats(savedShifts, selectedMonth)
            StatsMode.YEARLY -> StatsHelper.calculateYearlyStats(savedShifts, selectedYear)
            StatsMode.CUSTOM -> StatsHelper.calculateRangeStats(savedShifts, startDate, endDate)
        }
    }

    val currencySymbol = remember(currencyCode) {
        java.util.Currency.getInstance(currencyCode).getSymbol(java.util.Locale.getDefault())
    }

    val accentColor = MaterialTheme.colorScheme.primary

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Statistics",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (currentMode) {
                                StatsMode.MONTHLY -> selectedMonth.month.name.lowercase()
                                    .replaceFirstChar { it.uppercase() } + " ${selectedMonth.year}"
                                StatsMode.YEARLY -> selectedYear.toString()
                                StatsMode.CUSTOM -> "${startDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} – ${endDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            // ── Mode Selector ──
            item(key = "mode_selector") {
                Spacer(modifier = Modifier.height(12.dp))
                StatsPillSelector(
                    currentMode = currentMode,
                    onModeChange = { currentMode = it },
                    accentColor = accentColor,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            // ── Range Navigator ──
            item(key = "range_nav") {
                Spacer(modifier = Modifier.height(8.dp))
                when (currentMode) {
                    StatsMode.MONTHLY -> SlimRangeNavigator(
                        label = selectedMonth.month.name.lowercase()
                            .replaceFirstChar { it.uppercase() },
                        subtitle = selectedMonth.year.toString(),
                        onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                        onNext = { selectedMonth = selectedMonth.plusMonths(1) },
                        accentColor = accentColor,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    StatsMode.YEARLY -> SlimRangeNavigator(
                        label = selectedYear.toString(),
                        subtitle = null,
                        onPrevious = { selectedYear = selectedYear.minusYears(1) },
                        onNext = { selectedYear = selectedYear.plusYears(1) },
                        accentColor = accentColor,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    StatsMode.CUSTOM -> SlimCustomRangeButton(
                        start = startDate,
                        end = endDate,
                        accentColor = accentColor,
                        onClick = { showRangePicker = true },
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

            // ── Hero Earnings Card ──
            item(key = "hero_earnings") {
                Spacer(modifier = Modifier.height(16.dp))
                HeroEarningsCard(
                    earnings = stats.estimatedEarnings,
                    currencySymbol = currencySymbol,
                    totalShifts = stats.totalShifts,
                    accentColor = accentColor,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            // ── Metric Grid ──
            item(key = "metrics") {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(
                            label = "Total Hours",
                            value = StatsHelper.formatHours(stats.totalHours),
                            icon = Icons.Default.WatchLater,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Avg / Shift",
                            value = StatsHelper.formatHours(stats.averageHoursPerShift),
                            icon = Icons.Default.HourglassBottom,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(
                            label = "Rest Days",
                            value = "${stats.restDays}",
                            icon = Icons.Default.DateRange,
                            tintColor = Color(0xFF06B6D4),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Holidays",
                            value = "${stats.holidayDays}",
                            icon = Icons.Default.BatteryChargingFull,
                            tintColor = Color(0xFF4CAF50),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Breakdown Header ──
            item(key = "breakdown_header") {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(3.dp, 16.dp)
                            .background(accentColor, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SHIFT BREAKDOWN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
            }

            // ── Breakdown List or Empty ──
            if (stats.totalShifts == 0) {
                item(key = "empty") {
                    SlimEmptyStatsView(modifier = Modifier.padding(horizontal = 24.dp))
                }
            } else {
                items(
                    items = stats.shiftBreakdown.toList(),
                    key = { it.first }
                ) { (shiftName, count) ->
                    SlimBreakdownRow(
                        name = shiftName,
                        count = count,
                        total = stats.totalShifts,
                        shiftColor = findShiftColor(savedShifts, shiftName),
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }
    }

    // ── Date Range Picker Dialog ──
    if (showRangePicker) {
        val dateRangePickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val s = dateRangePickerState.selectedStartDateMillis
                    val e = dateRangePickerState.selectedEndDateMillis
                    if (s != null && e != null) {
                        startDate = java.time.Instant.ofEpochMilli(s)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        endDate = java.time.Instant.ofEpochMilli(e)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    }
                    showRangePicker = false
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) { Text("Cancel") }
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.height(500.dp),
                title = { Text("Select Range", modifier = Modifier.padding(16.dp)) },
                headline = {
                    Text(
                        "Select work period",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    todayContentColor = MaterialTheme.colorScheme.primary,
                    selectedDayContainerColor = MaterialTheme.colorScheme.primary,
                    dayInSelectionRangeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            )
        }
    }
}

// ── Helper: find a shift's color from the saved data ──
private fun findShiftColor(
    savedShifts: Map<java.time.LocalDate, List<Shift>>,
    shiftName: String
): Color {
    val colorValue = savedShifts.values.flatten()
        .firstOrNull { it.name == shiftName }?.colorValue
    return if (colorValue != null) Color(colorValue.toInt()) else Color(0xFF6366F1)
}

// ── Pill Mode Selector ──
@Composable
private fun StatsPillSelector(
    currentMode: StatsMode,
    onModeChange: (StatsMode) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val modes = remember { StatsMode.entries.toList() }
    val labels = remember { listOf("Month", "Year", "Custom") }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            modes.forEachIndexed { index, mode ->
                val isSelected = currentMode == mode
                val bgAlpha by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = tween(220), label = "pillBg"
                )
                Surface(
                    onClick = { onModeChange(mode) },
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    shape = RoundedCornerShape(11.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.background
                    else Color.Transparent,
                    shadowElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = labels[index],
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) accentColor
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

// ── Slim Range Navigator ──
@Composable
private fun SlimRangeNavigator(
    label: String,
    subtitle: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(40.dp)
                .background(
                    accentColor.copy(alpha = 0.08f),
                    RoundedCornerShape(12.dp)
                )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous",
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                )
            }
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(40.dp)
                .background(
                    accentColor.copy(alpha = 0.08f),
                    RoundedCornerShape(12.dp)
                )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next",
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ── Custom Range Button ──
@Composable
private fun SlimCustomRangeButton(
    start: LocalDate,
    end: LocalDate,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = remember { java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy") }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accentColor.copy(alpha = 0.06f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, accentColor.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${start.format(formatter)} – ${end.format(formatter)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit Range",
                tint = accentColor.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── Hero Earnings Card ──
@Composable
private fun HeroEarningsCard(
    earnings: Double,
    currencySymbol: String,
    totalShifts: Int,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val animatedEarnings by animateFloatAsState(
        targetValue = earnings.toFloat(),
        animationSpec = tween(durationMillis = 600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "earningsAnim"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = accentColor.copy(alpha = 0.07f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, accentColor.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "ESTIMATED EARNINGS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = accentColor.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = currencySymbol,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format("%.2f", animatedEarnings),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(accentColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$totalShifts shift${if (totalShifts != 1) "s" else ""} recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ── Metric Tile ──
@Composable
private fun MetricTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.06f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(tintColor.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tintColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ── Breakdown Row ──
@Composable
private fun SlimBreakdownRow(
    name: String,
    count: Int,
    total: Int,
    shiftColor: Color,
    modifier: Modifier = Modifier
) {
    val fraction = remember(count, total) { if (total > 0) count.toFloat() / total else 0f }
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "progressAnim"
    )
    val percentage = remember(count, total) {
        if (total > 0) ((count.toFloat() / total) * 100).toInt() else 0
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.06f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(shiftColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = "$count shift${if (count != 1) "s" else ""} · $percentage%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(
                        shiftColor.copy(alpha = 0.1f),
                        RoundedCornerShape(3.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction)
                        .fillMaxHeight()
                        .background(shiftColor, RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

// ── Empty State ──
@Composable
private fun SlimEmptyStatsView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No shifts recorded",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Try selecting a different period",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
        )
    }
}

@Preview(showBackground = true, name = "Stats Light Mode")
@Composable
fun StatsScreenPreview() {
    ShiftVaultTheme(darkTheme = false) {
        val mockShifts = mapOf(
            LocalDate.now() to listOf(
                Shift("Day Shift", "09:00", "17:00", 0xFF82B1FF, isHoliday = false),
                Shift("Holiday", "00:00", "23:59", 0xFFE91E63, isHoliday = true)
            )
        )
        StatsScreen(
            savedShifts = mockShifts,
            onBackClick = {},
            currencyCode = "EUR"
        )
    }
}
