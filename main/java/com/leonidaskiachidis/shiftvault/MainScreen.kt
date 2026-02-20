package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.MidnightBlue
import com.leonidaskiachidis.shiftvault.ui.theme.MutedText
import com.leonidaskiachidis.shiftvault.ui.theme.OffWhite
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import com.leonidaskiachidis.shiftvault.ui.theme.ElectricViolet
import com.leonidaskiachidis.shiftvault.ui.theme.SlateGrey
import java.time.YearMonth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import com.leonidaskiachidis.shiftvault.ui.theme.SoftCyan
import kotlin.compareTo
import kotlin.inc
import kotlin.plus
import kotlin.text.get
import kotlin.text.toInt
import kotlin.times
import kotlin.toString


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onSettingsClick: () -> Unit,
    onCreateCustomClick: (java.time.LocalDate) -> Unit,
    onStatsClick: () -> Unit  // NEW PARAMETER
){

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // --- 1. STATE SECTION ---
    val startPage = Int.MAX_VALUE / 2
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { Int.MAX_VALUE })

    val currentMonth = remember(pagerState.currentPage) {
        val monthsToAdd = pagerState.currentPage - startPage
        YearMonth.now().plusMonths(monthsToAdd.toLong())
    }

    var showSheet by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var savedShifts by remember { mutableStateOf(mapOf<java.time.LocalDate, List<Shift>>()) }

    val presetShifts = listOf(
        Shift("Morning", "09:00", "17:00", com.leonidaskiachidis.shiftvault.ui.theme.SoftCyan.toArgb().toLong()),
        Shift("Afternoon", "14:00", "22:00", androidx.compose.ui.graphics.Color.Cyan.toArgb().toLong()),
        Shift("Night", "22:00", "06:00", androidx.compose.ui.graphics.Color.Magenta.toArgb().toLong())
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val shiftManager = remember { ShiftManager(context) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        shiftManager.getShifts.collect { loadedShifts ->
            savedShifts = loadedShifts
        }
    }

    var customTemplates by remember { mutableStateOf(listOf<Shift>()) }
    LaunchedEffect(Unit) {
        shiftManager.getTemplates.collect { templates ->
            customTemplates = templates
        }
    }

    // ANIMATIONS VARIABLES
    var lastAddedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var lastDeletedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var animationTrigger by remember { mutableStateOf(0) }


    // --- 2. UI LAYOUT ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding() // <--- ADDED: Prevents overlapping with notification/status bar
            .padding(16.dp)
    ) {
        // Calculate current month stats
        val currentMonthStats = remember(savedShifts, currentMonth) {
            StatsHelper.calculateMonthlyStats(savedShifts, currentMonth)
        }

        // Quick View Stats Header
        QuickViewHeader(
            stats = currentMonthStats,
            onStatsClick = onStatsClick,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // --- REDESIGNED HEADER SECTION ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous Month",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentMonth.month.name,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = currentMonth.year.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next Month",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Calendar Section
        Column(modifier = Modifier.fillMaxWidth()) {

            // Weekday Labels
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                days.forEach { day ->
                    Text(
                        text = day,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.width(50.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- REDESIGNED PAGER SECTION ---
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                val monthForPage = remember(page) {
                    val monthsToAdd = page - startPage
                    YearMonth.now().plusMonths(monthsToAdd.toLong())
                }

                val firstDayOfMonth = monthForPage.atDay(1).dayOfWeek.value
                val daysToSkip = firstDayOfMonth - 1

                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(500.dp)
                ) {
                    items(daysToSkip) {
                        Spacer(modifier = Modifier.aspectRatio(1f))
                    }

                    items(monthForPage.lengthOfMonth()) { index ->
                        val dayNumber = index + 1
                        val dateOfCell = monthForPage.atDay(dayNumber)
                        val dayShifts = savedShifts[dateOfCell] ?: emptyList()

                        val isAnimatingAdd = lastAddedDate == dateOfCell

                        val scale = animateFloatAsState(
                            targetValue = when {
                                isAnimatingAdd -> 1f
                                else -> 1f
                            },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            finishedListener = {
                                if (isAnimatingAdd) lastAddedDate = null
                            },
                            label = "cellScale"
                        ).value

                        val alpha = animateFloatAsState(
                            targetValue = 1f,
                            animationSpec = tween(200),
                            label = "cellAlpha"
                        ).value

                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .height(75.dp)
                                .scale(scale)
                                .graphicsLayer { this.alpha = alpha }
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable {
                                    selectedDate = dateOfCell
                                    showSheet = true
                                },
                            contentAlignment = Alignment.TopStart
                        ) {
                            Column(modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp)) {
                                Text(
                                    text = dayNumber.toString(),
                                    color = if (dateOfCell == java.time.LocalDate.now()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.sp,
                                    fontWeight = if (dateOfCell == java.time.LocalDate.now()) FontWeight.ExtraBold else FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                dayShifts.take(3).forEachIndexed { index, shift ->
                                    val shiftAppearDelay = if (isAnimatingAdd) index * 50 else 0

                                    AnimatedShiftBadge(
                                        shift = shift,
                                        isVisible = true,
                                        delayMillis = shiftAppearDelay
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }

                                if (dayShifts.size > 3) {
                                    Text("+${dayShifts.size - 3}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp)
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    // --- 3. BOTTOM SHEET SECTION ---
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onBackground, // Fixed capitalization here
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
        Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                selectedDate?.let { date ->
                    val formatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = date.format(formatter),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                text = "Add a shift to your schedule",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (savedShifts.containsKey(date)) {
                            var deletePressed by remember { mutableStateOf(false) }

                            val deleteScale = animateFloatAsState(
                                targetValue = if (deletePressed) 0.85f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessHigh
                                ),
                                label = "deleteScale"
                            ).value

                            val deleteRotation = animateFloatAsState(
                                targetValue = if (deletePressed) 15f else 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "deleteRotation"
                            ).value

                            IconButton(
                                onClick = {
                                    selectedDate?.let { date ->
                                        // 1. Remove from local state immediately
                                        val newMap = savedShifts - date
                                        savedShifts = newMap

                                        // 2. Persist the change
                                        scope.launch {
                                            shiftManager.saveShifts(newMap)
                                            sheetState.hide()
                                            showSheet = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .scale(deleteScale)
                                    .graphicsLayer { rotationZ = deleteRotation }
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                    contentDescription = "Clear Day",
                                    tint = Color(0xFFEF5350)
                                )
                            }

                        }
                    }
                }

                val onAddShift: (Shift) -> Unit = { shift ->
                    scope.launch {
                        selectedDate?.let { date ->
                            val currentDayList = savedShifts[date] ?: emptyList()
                            val updatedList = currentDayList + shift
                            val newMap = savedShifts + (date to updatedList)

                            // Trigger animation
                            lastAddedDate = date
                            animationTrigger++

                            savedShifts = newMap
                            shiftManager.saveShifts(newMap)
                        }
                        sheetState.hide()
                        showSheet = false
                    }
                }


                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item(span = { GridItemSpan(2) }) {
                        Text(
                            text = "YOUR TEMPLATES",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    item {
                        Surface(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    showSheet = false
                                    selectedDate?.let { date -> onCreateCustomClick(date) }
                                }
                            },
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MutedText.copy(alpha=0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth()
                                    .height(44.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Add,
                                    contentDescription = null,
                                    tint = OffWhite,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Create New",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    items(customTemplates.size) { index ->
                        val shift = customTemplates[index]
                        ShiftCard(shift = shift, onClick = { onAddShift(shift) })
                    }

                    item(span = { GridItemSpan(2) }) {
                        Text(
                            text = "PRESETS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }

                    items(presetShifts.size) { index ->
                        val shift = presetShifts[index]
                        ShiftCard(shift = shift, onClick = { onAddShift(shift) })
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ShiftVaultTheme {
        MainScreen(
            onSettingsClick = {},
            onCreateCustomClick = {},
            onStatsClick = {}  // NEW
        )
    }
}
@Composable
fun ShiftCard(shift: Shift, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }

    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "cardScale"
    ).value

    Surface(
        onClick = {
            isPressed = true
            onClick()
        },
        color = Color(shift.colorValue.toInt()).copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(shift.colorValue.toInt())),
        modifier = Modifier.scale(scale)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(shift.colorValue.toInt()), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = shift.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "${shift.startTime} - ${shift.endTime}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(100)
            isPressed = false
        }
    }
}


@Composable
fun QuickViewHeader(
    stats: MonthlyStats,
    onStatsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Quick Stats
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatCard(
                    label = "Hours",
                    value = StatsHelper.formatHours(stats.totalHours)
                )
                StatCard(
                    label = "Shifts",
                    value = stats.totalShifts.toString()
                )
            }

            // Right: Stats Icon Button
            IconButton(
                onClick = onStatsClick,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.BarChart,
                    contentDescription = "View Stats",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun AnimatedShiftBadge(
    shift: Shift,
    isVisible: Boolean,
    delayMillis: Int = 0
) {
    var startAnimation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMillis.toLong())
        startAnimation = true
    }

    val scale = animateFloatAsState(
        targetValue = if (startAnimation && isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "badgeScale"
    ).value

    val alpha = animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(200),
        label = "badgeAlpha"
    ).value

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .scale(scale)
            .graphicsLayer { this.alpha = alpha }
            .background(Color(shift.colorValue.toInt()), RoundedCornerShape(2.dp))
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = shift.name,
            color = MidnightBlue,
            fontSize = 8.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            lineHeight = 8.sp,
            style = TextStyle(
                platformStyle = PlatformTextStyle(
                    includeFontPadding = false
                ),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both
                )
            )
        )
    }
}


