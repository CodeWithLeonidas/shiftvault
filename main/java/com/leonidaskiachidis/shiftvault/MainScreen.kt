package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import java.time.YearMonth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddToPhotos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import kotlin.compareTo
import kotlin.text.get
import kotlin.text.set
import kotlin.text.toInt
import kotlin.toString


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onSettingsClick: () -> Unit,
    onCreateCustomClick: (java.time.LocalDate) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shiftManager = remember { ShiftManager(context) }

    // --- STATE ---
    val startPage = Int.MAX_VALUE / 2
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { Int.MAX_VALUE })

    val currentMonth by remember {
        derivedStateOf {
            val offset = pagerState.currentPage - startPage
            YearMonth.now().plusMonths(offset.toLong())
        }
    }

    var showAddSheet by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var savedShifts by remember { mutableStateOf(mapOf<java.time.LocalDate, List<Shift>>()) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingShiftIndex by remember { mutableStateOf(-1) }
    var tempEditingShift by remember { mutableStateOf<Shift?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickingStartTime by remember { mutableStateOf(true) }

    var clipboardShift by remember { mutableStateOf<Shift?>(null) }
    val isPasteMode by remember { derivedStateOf { clipboardShift != null } }
    var isEraserMode by remember { mutableStateOf(false) }

    var customTemplates by remember { mutableStateOf(listOf<Shift>()) }
    var lastAddedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var animationTrigger by remember { mutableStateOf(0) }
    var showQuickActionsSheet by remember { mutableStateOf(false) }
    var showShiftSelectionSheet by remember { mutableStateOf(false) }


    LaunchedEffect(Unit) {
        launch { shiftManager.getShifts.collect { savedShifts = it } }
        launch { shiftManager.getTemplates.collect { customTemplates = it } }
    }

    val currentMonthStats = remember(savedShifts, currentMonth) {
        StatsHelper.calculateMonthlyStats(savedShifts, currentMonth)
    }

    val today = remember { java.time.LocalDate.now() }

    // --- UI ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // --- TOP ACTION BAR ---
            AnimatedVisibility(
                visible = isPasteMode || isEraserMode,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
            ) {
                val barColor = if (isEraserMode) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                val barText = if (isEraserMode) "Eraser Mode — Tap to clear" else "Paste: ${clipboardShift?.name ?: ""}"


                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = barColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, barColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (isEraserMode) Icons.Default.Delete else Icons.Default.Check,
                                contentDescription = null,
                                tint = barColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = barText,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = barColor
                            )
                        }
                        Surface(
                            onClick = {
                                clipboardShift = null
                                isEraserMode = false
                            },
                            shape = CircleShape,
                            color = barColor.copy(alpha = 0.15f),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = barColor, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // --- STATS RIBBON ---
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RibbonStat(
                        value = StatsHelper.formatHours(currentMonthStats.totalHours),
                        label = "Hours",
                        color = MaterialTheme.colorScheme.primary
                    )
                    VerticalDivider(
                            modifier = Modifier.height(28.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    RibbonStat(
                        value = currentMonthStats.totalShifts.toString(),
                        label = "Shifts",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    VerticalDivider(
                        modifier = Modifier.height(28.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    // Quick Actions Icon Button
                    FilledIconButton(
                        onClick = { showQuickActionsSheet = true },
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        )
                    ) {
                        Icon(
                            Icons.Default.EditCalendar,
                            contentDescription = "Quick Actions",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }


            // --- MONTH SELECTOR ---
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = currentMonth.month.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = currentMonth.year.toString(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    FilledIconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // --- WEEKDAY HEADER ---
            val days = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                userScrollEnabled = false
            ) {
                items(days.size) { index ->
                    Text(
                        text = days[index],
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // --- CALENDAR PAGER ---
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                key = { it }
            ) { page ->
                val monthForPage = remember(page) {
                    YearMonth.now().plusMonths((page - startPage).toLong())
                }
                val daysToSkip = remember(monthForPage) {
                    monthForPage.atDay(1).dayOfWeek.value - 1
                }
                val lengthOfMonth = remember(monthForPage) { monthForPage.lengthOfMonth() }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = true
                ) {
                    items(daysToSkip, key = { "skip_$it" }) {
                        Spacer(modifier = Modifier.aspectRatio(0.72f))
                    }

                    items(lengthOfMonth, key = { "day_${monthForPage}_$it" }) { index ->
                        val dayNumber = index + 1
                        val dateOfCell = remember(monthForPage, dayNumber) { monthForPage.atDay(dayNumber) }
                        val dayShifts = savedShifts[dateOfCell] ?: emptyList()
                        val isAnimatingAdd = lastAddedDate == dateOfCell
                        val isToday = dateOfCell == today
                        val isWeekend = remember(dateOfCell) {
                            dateOfCell.dayOfWeek.value >= 6 // Saturday=6, Sunday=7
                        }

                        RedesignedCalendarDayCell(
                            dayNumber = dayNumber,
                            dayShifts = dayShifts,
                            isToday = isToday,
                            isAnimatingAdd = isAnimatingAdd,
                            isPasteMode = isPasteMode,
                            isEraserMode = isEraserMode,
                            isWeekend = isWeekend,
                            onClick = {
                                when {
                                    isEraserMode -> {
                                        val newMap = savedShifts - dateOfCell
                                        savedShifts = newMap
                                        scope.launch { shiftManager.saveShifts(newMap) }
                                    }
                                    isPasteMode && clipboardShift != null -> {
                                        val updated = (savedShifts[dateOfCell] ?: emptyList()) + clipboardShift!!
                                        val newMap = savedShifts + (dateOfCell to updated)
                                        savedShifts = newMap
                                        lastAddedDate = dateOfCell
                                        scope.launch { shiftManager.saveShifts(newMap) }
                                    }
                                    else -> {
                                        selectedDate = dateOfCell
                                        if (dayShifts.isNotEmpty()) showEditSheet = true else showAddSheet = true
                                    }
                                }
                            },
                            onAnimationFinished = { lastAddedDate = null }
                        )
                    }
                }
            }
        }
    }

    // --- ADD BOTTOM SHEET ---
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Surface(
                    modifier = Modifier.padding(vertical = 12.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                ) {
                    Spacer(modifier = Modifier.size(width = 40.dp, height = 4.dp))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                selectedDate?.let { date ->
                    val formatter = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d") }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = date.format(formatter),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Choose a shift to add",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                val onAddShift: (Shift) -> Unit = { shift ->
                    scope.launch {
                        selectedDate?.let { date ->
                            val updated = (savedShifts[date] ?: emptyList()) + shift
                            val newMap = savedShifts + (date to updated)
                            lastAddedDate = date
                            animationTrigger++
                            savedShifts = newMap
                            shiftManager.saveShifts(newMap)
                        }
                        sheetState.hide()
                        showAddSheet = false
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Templates Section
                    item(span = { GridItemSpan(2) }) {
                        SheetSectionLabel("YOUR TEMPLATES")
                    }

                    item {
                        CreateNewCard(onClick = {
                            scope.launch {
                                sheetState.hide()
                                showAddSheet = false
                                selectedDate?.let { onCreateCustomClick(it) }
                            }
                        })
                    }

                    items(customTemplates.size, key = { "tmpl_$it" }) { index ->
                        val shift = customTemplates[index]
                        RedesignedShiftCard(
                            shift = shift,
                            onClick = { onAddShift(shift) },
                            onLongClick = {
                                clipboardShift = shift
                                scope.launch {
                                    sheetState.hide()
                                    showAddSheet = false
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // --- EDIT BOTTOM SHEET ---
    if (showEditSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEditSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Surface(
                    modifier = Modifier.padding(vertical = 12.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                ) {
                    Spacer(modifier = Modifier.size(width = 40.dp, height = 4.dp))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                val date = selectedDate ?: java.time.LocalDate.now()
                val dayShifts = savedShifts[date] ?: emptyList()
                val formatter = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d") }

                Text(
                    text = date.format(formatter),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${dayShifts.size} shift${if (dayShifts.size != 1) "s" else ""} scheduled",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                dayShifts.forEachIndexed { index, shift ->
                    val shiftColor = Color(shift.colorValue.toInt())
                    val timeDisplay = if (shift.startTime == "00:00" && shift.endTime == "23:59") "All day" else "${shift.startTime} — ${shift.endTime}"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = shiftColor.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, shiftColor.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Color bar
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(36.dp)
                                    .background(shiftColor, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = shift.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = timeDisplay,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                onClick = {
                                    editingShiftIndex = index
                                    tempEditingShift = shift.copy()  // Create a safe copy
                                    showEditDialog = true
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Visibility,
                                        contentDescription = "View",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                onClick = {
                                    scope.launch {
                                        val updated = dayShifts.toMutableList().apply { removeAt(index) }
                                        val newMap = if (updated.isEmpty()) savedShifts - date else savedShifts + (date to updated)
                                        if (updated.isEmpty()) { sheetState.hide(); showEditSheet = false }
                                        savedShifts = newMap
                                        shiftManager.saveShifts(newMap)
                                    }
                                },
                                shape = CircleShape,
                                color = Color(0xFFD32F2F).copy(alpha = 0.1f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFD32F2F), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = { showEditSheet = false; showAddSheet = true },
                        modifier = Modifier
                            .width(220.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Another Shift", fontWeight = FontWeight.SemiBold)
                    }
                }



        }
    }


        // --- DETAIL VIEW DIALOG ---
    // Replace the existing "DETAIL VIEW DIALOG" section with this:

    if (showEditDialog && tempEditingShift != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.99f)
                .wrapContentHeight(),
            title = null,
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    // Header with color accent
                    val shiftColor = Color(tempEditingShift!!.colorValue.toInt())
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(shiftColor.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(shiftColor, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = tempEditingShift!!.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = "Shift Details",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Surface(
                                onClick = { showEditDialog = false },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))

                    // Time Section with visual enhancement
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                Icon(
                                    Icons.Default.EditCalendar,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Schedule",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedCard(
                                    onClick = { pickingStartTime = true; showTimePicker = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            "START",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            tempEditingShift!!.startTime,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                // Arrow separator
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .padding(vertical = 8.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                OutlinedCard(
                                    onClick = { pickingStartTime = false; showTimePicker = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            "END",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            tempEditingShift!!.endTime,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Financial Details in grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Hourly Rate",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = tempEditingShift!!.hourlyRate.toString(),
                                    onValueChange = {
                                        tempEditingShift = tempEditingShift!!.copy(
                                            hourlyRate = it.toDoubleOrNull() ?: tempEditingShift!!.hourlyRate
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    prefix = { Text("\$", fontWeight = FontWeight.Bold) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Break Time",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = tempEditingShift!!.breakMinutes.toString(),
                                    onValueChange = {
                                        tempEditingShift = tempEditingShift!!.copy(
                                            breakMinutes = it.toIntOrNull() ?: tempEditingShift!!.breakMinutes
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    suffix = { Text("min", fontSize = 12.sp) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Notes Section
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Notes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tempEditingShift!!.notes ?: "",
                                onValueChange = {
                                    tempEditingShift = tempEditingShift!!.copy(notes = it)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp),
                                shape = RoundedCornerShape(10.dp),
                                placeholder = { Text("Add optional notes...", fontSize = 14.sp) },
                                maxLines = 5,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val date = selectedDate ?: return@launch
                            val list = savedShifts[date]?.toMutableList() ?: return@launch
                            if (editingShiftIndex in list.indices) {
                                list[editingShiftIndex] = tempEditingShift!!
                                val updatedMap = savedShifts + (date to list)
                                savedShifts = updatedMap
                                shiftManager.saveShifts(updatedMap)
                            }
                            showEditDialog = false
                            sheetState.hide()
                            showEditSheet = false
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Changes", fontWeight = FontWeight.SemiBold)
                }
            }
        )

        // Keep the existing time picker dialog
        if (showTimePicker) {
            val initialHour = if (pickingStartTime) {
                tempEditingShift!!.startTime.split(":").getOrNull(0)?.toIntOrNull() ?: 9
            } else {
                tempEditingShift!!.endTime.split(":").getOrNull(0)?.toIntOrNull() ?: 17
            }
            val initialMinute = if (pickingStartTime) {
                tempEditingShift!!.startTime.split(":").getOrNull(1)?.toIntOrNull() ?: 0
            } else {
                tempEditingShift!!.endTime.split(":").getOrNull(1)?.toIntOrNull() ?: 0
            }

            val timeState = rememberTimePickerState(
                initialHour = initialHour,
                initialMinute = initialMinute,
                is24Hour = true
            )
            DatePickerDialog(
                onDismissRequest = { showTimePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val formatted = String.format("%02d:%02d", timeState.hour, timeState.minute)
                        tempEditingShift = if (pickingStartTime) {
                            tempEditingShift!!.copy(startTime = formatted)
                        } else {
                            tempEditingShift!!.copy(endTime = formatted)
                        }
                        showTimePicker = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                }
            ) {
                Box(modifier = Modifier.padding(24.dp)) {
                    TimePicker(state = timeState)
                }
            }
        }
    }


// --- QUICK ACTIONS BOTTOM SHEET ---
    if (showQuickActionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQuickActionsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Surface(
                    modifier = Modifier.padding(vertical = 12.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                ) {
                    Spacer(modifier = Modifier.size(width = 40.dp, height = 4.dp))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Quick Actions",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Select a shift to paste or delete shifts in bulk",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                // Bulk Actions Section
                SheetSectionLabel("BULK ACTIONS")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Paste Mode Card
                    Surface(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                showQuickActionsSheet = false
                                // Let user select which shift to paste
                                showShiftSelectionSheet = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.AddToPhotos,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Paste Shifts",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Eraser Mode Card
                    Surface(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                showQuickActionsSheet = false
                                isEraserMode = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFD32F2F).copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, Color(0xFFD32F2F).copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = Color(0xFFD32F2F),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Delete Shifts",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFFD32F2F)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

// --- SHIFT SELECTION FOR PASTE ---
    if (showShiftSelectionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showShiftSelectionSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Surface(
                    modifier = Modifier.padding(vertical = 12.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                ) {
                    Spacer(modifier = Modifier.size(width = 40.dp, height = 4.dp))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Select Shift to Paste",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Tap any shift to activate paste mode",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                if (customTemplates.isEmpty()) {
                    Text(
                        text = "No shift templates available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 40.dp)
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(customTemplates.size) { index ->
                            val shift = customTemplates[index]
                            RedesignedShiftCard(
                                shift = shift,
                                onClick = {
                                    scope.launch {
                                        clipboardShift = shift
                                        sheetState.hide()
                                        showShiftSelectionSheet = false
                                    }
                                },
                                onLongClick = { }
                            )
                        }
                    }
                }
            }
        }
    }


}

// ===================== COMPONENTS =====================

@Composable
private fun RibbonStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun CreateNewCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = Modifier.defaultMinSize(minHeight = 78.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Create New",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RedesignedShiftCard(
    shift: Shift,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shiftColor = Color(shift.colorValue.toInt())
    val timeDisplay = if (shift.startTime == "00:00" && shift.endTime == "23:59") "All day" else "${shift.startTime} — ${shift.endTime}"
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "cardScale"
    )

    Surface(
        modifier = Modifier
            .scale(scale)
            .combinedClickable(
                onClick = { isPressed = true; onClick() },
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(14.dp),
        color = shiftColor.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, shiftColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(shiftColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = shift.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    text = timeDisplay,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) { kotlinx.coroutines.delay(100); isPressed = false }
    }
}

@Composable
private fun RedesignedCalendarDayCell(
    dayNumber: Int,
    dayShifts: List<Shift>,
    isToday: Boolean,
    isWeekend: Boolean,
    isAnimatingAdd: Boolean,
    isPasteMode: Boolean,
    isEraserMode: Boolean,
    onClick: () -> Unit,
    onAnimationFinished: () -> Unit
) {
    val scale = if (isAnimatingAdd) {
        animateFloatAsState(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            finishedListener = { onAnimationFinished() },
            label = "cellScale"
        ).value
    } else 1f


    val todayIndicatorColor = MaterialTheme.colorScheme.primary
    val surfaceColor = when {
        isToday -> todayIndicatorColor.copy(alpha = 0.12f)
        isWeekend -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f) // Weekend highlight
        else -> MaterialTheme.colorScheme.surface
    }

    val borderColor = if (isToday) {
        todayIndicatorColor.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }

    Box(
        modifier = Modifier
            .padding(1.dp)
            .aspectRatio(0.72f)
            .scale(scale)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.TopStart
    ) {
        // Today border overlay
        if (isToday) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            // Day number
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopStart
            ) {
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(todayIndicatorColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayNumber.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 10.sp
                        )
                    }
                } else {
                    Text(
                        text = dayNumber.toString(),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            // Shift indicators - use color bars
            val displayShifts = if (dayShifts.size > 3) dayShifts.take(3) else dayShifts
            displayShifts.forEach { shift ->
                val shiftColor = Color(shift.colorValue.toInt())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(shiftColor.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = shift.name,
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        lineHeight = 7.sp,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both
                            )
                        )
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            if (dayShifts.size > 3) {
                Text(
                    text = "+${dayShifts.size - 3}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold
                )
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
            onCreateCustomClick = {}
        )
    }
}



