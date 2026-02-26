package com.leonidaskiachidis.shiftvault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview
import java.time.LocalDate
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.collections.get
import kotlin.compareTo
import kotlin.text.chunked
import kotlin.text.forEach
import kotlin.text.format
import kotlin.text.toFloat
import kotlin.text.toInt
import kotlin.text.toLong
import kotlin.toString


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomShiftScreen(
    selectedDate: LocalDate?,
    templateIndex: Int? = null,
    shiftManager: ShiftManager,
    currencyCode: String = "EUR",
    onBackClick: () -> Unit,
    onSaveFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var hourlyRate by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("09:00") }
    var endTime by remember { mutableStateOf("17:00") }
    var breakDurationMinutes by remember { mutableStateOf(0f) }
    var isAllDay by remember { mutableStateOf(false) }
    var isHoliday by remember { mutableStateOf(false) }
    var selectedColorValue by remember { mutableStateOf(Color(0xFF6366F1).toArgb().toLong()) }
    var selectedIconName by remember { mutableStateOf<String?>(null) }
    var breakExpanded by remember { mutableStateOf(false) }
    var notesExpanded by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    val currencySymbol = remember(currencyCode) {
        java.util.Currency.getInstance(currencyCode).getSymbol(java.util.Locale.getDefault())
    }

    LaunchedEffect(templateIndex) {
        templateIndex?.let { index ->
            val templates = shiftManager.getTemplates.first()
            if (index in templates.indices) {
                val shift = templates[index]
                name = shift.name
                startTime = shift.startTime
                endTime = shift.endTime
                selectedColorValue = shift.colorValue
                selectedIconName = shift.iconName
                isHoliday = shift.isHoliday
                hourlyRate = if (shift.hourlyRate > 0) shift.hourlyRate.toString() else ""
                note = shift.notes
                breakDurationMinutes = shift.breakMinutes.toFloat()
                if (shift.breakMinutes > 0) breakExpanded = true
                if (shift.notes.isNotBlank()) notesExpanded = true
            }
        }
    }

    val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0)
    var showTimePicker by remember { mutableStateOf(false) }
    var pickingForStart by remember { mutableStateOf(true) }

    val canSave by remember {
        derivedStateOf { name.isNotBlank() && (isAllDay || startTime != endTime) }
    }

    val selectedColor = remember(selectedColorValue) {
        Color(selectedColorValue.toInt())
    }

    val colorOptions = remember {
        listOf(
            Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899),
            Color(0xFFF43F5E), Color(0xFFF97316), Color(0xFFFBBF24),
            Color(0xFF10B981), Color(0xFF14B8A6), Color(0xFF06B6D4),
            Color(0xFF3B82F6), Color(0xFF64748B), Color(0xFF78716C)
        )
    }

    val saveButtonAlpha by animateFloatAsState(
        targetValue = if (canSave) 1f else 0.45f,
        animationSpec = tween(250), label = "saveAlpha"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (templateIndex == null) "New Shift" else "Edit Shift",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        selectedDate?.let {
                            Text(
                                text = it.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d")),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    // Icon picker button in TopBar
                    Box {
                        IconButton(
                            onClick = { showIconPicker = true },
                            modifier = Modifier
                                .padding(end = 20.dp) // Increased from 8.dp to align with form
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(selectedColor.copy(alpha = 0.1f))
                        ) {
                            val currentIcon = getShiftIcon(selectedIconName)
                            Icon(
                                imageVector = currentIcon ?: Icons.Default.Category,
                                contentDescription = "Change Icon",
                                tint = selectedColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Icon picker dropdown
                        DropdownMenu(
                            expanded = showIconPicker,
                            onDismissRequest = { showIconPicker = false },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp)
                                .width(220.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = "Choose Icon",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )

                            // "No icon" option
                            Surface(
                                onClick = {
                                    selectedIconName = null
                                    showIconPicker = false
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedIconName == null)
                                    selectedColor.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(selectedColor)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Color only",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selectedIconName == null) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (selectedIconName == null) {
                                        Spacer(modifier = Modifier.weight(1f))
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = selectedColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            // Icon grid
                            val rows = shiftIconOptions.chunked(4)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                rows.forEach { rowIcons ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        rowIcons.forEach { shiftIcon ->
                                            val isSelected = selectedIconName == shiftIcon.name
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(
                                                        if (isSelected) selectedColor.copy(alpha = 0.15f)
                                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                    )
                                                    .clickable {
                                                        selectedIconName = shiftIcon.name
                                                        showIconPicker = false
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = shiftIcon.icon,
                                                    contentDescription = shiftIcon.label,
                                                    tint = if (isSelected) selectedColor
                                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 100.dp)
            ) {
                Spacer(modifier = Modifier.height(28.dp))

                // — Form —
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Shift Name and Color Picker Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom, // Aligns the color dot with the text field body
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SlimTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = "Shift Name",
                                placeholder = "e.g. Morning Shift",
                                singleLine = true,
                                accentColor = selectedColor
                            )
                        }

                        // This is the clickable color icon/button
                        Box {
                            IconButton(
                                onClick = { showColorPicker = true },
                                modifier = Modifier
                                    .padding(bottom = 8.dp) // Fine-tune alignment with the text field
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(selectedColor.copy(alpha = 0.1f))
                            ) {
                                // A simple colored circle to show current selection
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(selectedColor)
                                )
                            }
                            DropdownMenu(
                                expanded = showColorPicker,
                                onDismissRequest = { showColorPicker = false },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(12.dp)
                                    .width(180.dp), // Fixed width for a nice grid look
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = "Choose Tag Color",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )

                                // Grid of colors (3 columns x 4 rows)
                                val rows = colorOptions.chunked(4)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rows.forEach { rowColors ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            rowColors.forEach { color ->
                                                val colorVal = color.toArgb().toLong()
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                        .clickable {
                                                            selectedColorValue = colorVal
                                                            showColorPicker = false
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (selectedColorValue == colorVal) {
                                                        Icon(
                                                            Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Time Selectors
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isAllDay,
                        enter = fadeIn(tween(200)) + expandVertically(tween(250)),
                        exit = fadeOut(tween(150)) + shrinkVertically(tween(200))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MinimalTimeCard(
                                label = "START",
                                time = startTime,
                                accentColor = selectedColor,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    pickingForStart = true
                                    showTimePicker = true
                                }
                            )
                            MinimalTimeCard(
                                label = "END",
                                time = endTime,
                                accentColor = selectedColor,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    pickingForStart = false
                                    showTimePicker = true
                                }
                            )
                        }
                    }

                    // All Day Toggle
                    SlimToggleRow(
                        label = "All-day event",
                        checked = isAllDay,
                        onCheckedChange = { isAllDay = it },
                        accentColor = selectedColor
                    )

                    // Holiday Toggle
                    SlimToggleRow(
                        label = "Holiday / Day off",
                        subtitle = "Excluded from earnings",
                        checked = isHoliday,
                        onCheckedChange = { isHoliday = it },
                        accentColor = selectedColor
                    )

                    // Hourly Rate
                    SlimTextField(
                        value = hourlyRate,
                        onValueChange = { newVal ->
                            if (newVal.all { c -> c.isDigit() || c == '.' }) hourlyRate = newVal
                        },
                        label = "Hourly Rate",
                        placeholder = "0.00",
                        singleLine = true,
                        leadingText = currencySymbol,
                        keyboardType = KeyboardType.Decimal,
                        accentColor = selectedColor
                    )

                    // — Unpaid Break Dropdown —
                    ExpandableSection(
                        title = "Unpaid Break",
                        subtitle = if (breakDurationMinutes > 0f) "${breakDurationMinutes.toInt()} min" else null,
                        expanded = breakExpanded,
                        onToggle = { breakExpanded = !breakExpanded },
                        visible = !isAllDay
                    ) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Duration",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "${breakDurationMinutes.toInt()} minutes",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = selectedColor
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = breakDurationMinutes,
                                onValueChange = { breakDurationMinutes = it },
                                valueRange = 0f..120f,
                                steps = 23,
                                colors = SliderDefaults.colors(
                                    thumbColor = selectedColor,
                                    activeTrackColor = selectedColor,
                                    inactiveTrackColor = selectedColor.copy(alpha = 0.12f)
                                )
                            )
                            // Quick pick chips
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                listOf(0, 15, 30, 45, 60).forEach { mins ->
                                    val isActive = breakDurationMinutes.toInt() == mins
                                    Surface(
                                        onClick = { breakDurationMinutes = mins.toFloat() },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isActive) selectedColor.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = if (isActive) androidx.compose.foundation.BorderStroke(
                                            1.dp, selectedColor.copy(alpha = 0.4f)
                                        ) else null
                                    ) {
                                        Text(
                                            text = if (mins == 0) "None" else "${mins}m",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isActive) selectedColor
                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // — Notes Dropdown —
                    ExpandableSection(
                        title = "Notes",
                        subtitle = if (note.isNotBlank()) note.take(30) + if (note.length > 30) "…" else "" else null,
                        expanded = notesExpanded,
                        onToggle = { notesExpanded = !notesExpanded }
                    ) {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            placeholder = {
                                Text(
                                    "Anything worth remembering…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                                )
                            },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = selectedColor.copy(alpha = 0.5f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                cursorColor = selectedColor
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // — Save Button —
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Button(
                    onClick = {
                        val finalStart = if (isAllDay) "00:00" else startTime
                        val finalEnd = if (isAllDay) "23:59" else endTime
                        val newShift = Shift(
                            name = name,
                            startTime = finalStart,
                            endTime = finalEnd,
                            colorValue = selectedColorValue,
                            iconName = selectedIconName,
                            isHoliday = isHoliday,
                            hourlyRate = hourlyRate.toDoubleOrNull() ?: 0.0,
                            notes = note,
                            breakMinutes = breakDurationMinutes.toInt()
                        )
                        scope.launch {
                            selectedDate?.let { date ->
                                val currentMap = shiftManager.getShifts.first()
                                val currentDayList = currentMap[date] ?: emptyList()
                                val updatedList = currentDayList + newShift
                                shiftManager.saveShifts(currentMap + (date to updatedList))
                            }
                            val currentTemplates = shiftManager.getTemplates.first().toMutableList()
                            if (templateIndex != null && templateIndex in currentTemplates.indices) {
                                currentTemplates[templateIndex] = newShift
                            } else if (!currentTemplates.contains(newShift)) {
                                currentTemplates.add(newShift)
                            }
                            shiftManager.saveTemplates(currentTemplates)
                            onSaveFinished()
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer { alpha = saveButtonAlpha },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = selectedColor,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp
                    )
                ) {
                    Text(
                        text = if (templateIndex == null) "Save Shift" else "Update Shift",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // Time Picker
    if (showTimePicker) {
        TimePickerDialog(
            onDismissRequest = { showTimePicker = false },
            onConfirm = {
                val formattedTime = String.format(
                    "%02d:%02d", timePickerState.hour, timePickerState.minute
                )
                if (pickingForStart) startTime = formattedTime else endTime = formattedTime
                showTimePicker = false
            }
        ) {
            TimePicker(
                state = timePickerState,
                colors = TimePickerDefaults.colors(
                    timeSelectorSelectedContainerColor = selectedColor,
                    timeSelectorSelectedContentColor = Color.White,
                    selectorColor = selectedColor
                )
            )
        }
    }
}

// ── Reusable Components ──────────────────────────────────────────────

@Composable
private fun SlimTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    singleLine: Boolean = false,
    leadingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            letterSpacing = 1.2.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    placeholder,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
                )
            },
            singleLine = singleLine,
            leadingIcon = leadingText?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = accentColor.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                cursorColor = accentColor
            ),
            textStyle = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun MinimalTimeCard(
    label: String,
    time: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = accentColor.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, accentColor.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor.copy(alpha = 0.6f),
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = time,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun SlimToggleRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accentColor,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    visible: Boolean = true,
    content: @Composable () -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + expandVertically(tween(250)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(200))
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
            )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onToggle() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        if (!expanded && subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    val rotation by animateFloatAsState(
                        targetValue = if (expanded) 180f else 0f,
                        animationSpec = tween(250), label = "chevron"
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = rotation },
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(200)) + expandVertically(
                        animationSpec = spring(dampingRatio = 0.85f)
                    ),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(200))
                ) {
                    Column(
                        modifier = Modifier.padding(
                            start = 16.dp, end = 16.dp, bottom = 16.dp
                        )
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
fun TimePickerDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text("Cancel") }
        },
        text = content
    )
}

@Preview(showBackground = true)
@Composable
fun PreviewCustomShiftScreen() {
    val context = LocalContext.current
    val shiftManager = ShiftManager(context)
    CustomShiftScreen(
        selectedDate = LocalDate.now(),
        templateIndex = null,
        shiftManager = shiftManager,
        onBackClick = {},
        onSaveFinished = {}
    )
}