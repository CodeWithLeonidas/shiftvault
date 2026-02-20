package com.leonidaskiachidis.shiftvault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview
import java.time.LocalDate
import androidx.compose.material3.MaterialTheme
import kotlin.plus
import kotlin.text.get
import kotlin.text.set


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomShiftScreen(
    selectedDate: java.time.LocalDate?,
    templateIndex: Int? = null,
    shiftManager: ShiftManager,
    onBackClick: () -> Unit,
    onSaveFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // State
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var hourlyRate by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("09:00") }
    var endTime by remember { mutableStateOf("17:00") }
    var breakDurationMinutes by remember { mutableStateOf(0f) } // 0 to 120 mins
    var isAllDay by remember { mutableStateOf(false) }
    var selectedColorValue by remember { mutableStateOf(ElectricViolet.toArgb().toLong()) }

    // Pre-fill data if editing
    LaunchedEffect(templateIndex) {
        templateIndex?.let { index ->
            val templates = shiftManager.getTemplates.first()
            if (index in templates.indices) {
                val shift = templates[index]
                name = shift.name
                startTime = shift.startTime
                endTime = shift.endTime
                selectedColorValue = shift.colorValue
                // Assume notes/break/rate would be loaded here if Shift model supported them
                // For now, we keep the UI ready for when the model expands
            }
        }
    }

    val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0)
    var showTimePicker by remember { mutableStateOf(false) }
    var pickingForStart by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()
    val canSave = name.isNotBlank() && (isAllDay || startTime != endTime)

    val colorOptions = listOf(
        Color(0xFF82B1FF).toArgb().toLong(), // Sky Blue
        Color(0xFFB39DDB).toArgb().toLong(), // Soft Lavender
        Color(0xFF80CBC4).toArgb().toLong(), // Seafoam Teal
        Color(0xFFA5D6A7).toArgb().toLong(), // Pale Leaf
        Color(0xFFFFCC80).toArgb().toLong(), // Soft Amber
        Color(0xFFEF9A9A).toArgb().toLong(), // Muted Rose
        Color(0xFFCE93D8).toArgb().toLong(), // Thistle Purple
        Color(0xFF90CAF9).toArgb().toLong(), // Steel Blue
        Color(0xFFC5E1A5).toArgb().toLong(), // Light Sage
        Color(0xFFFFF59D).toArgb().toLong(), // Soft Lemon
        Color(0xFFB0BEC5).toArgb().toLong(), // Cool Slate
        Color(0xFFFFAB91).toArgb().toLong()  // Peach Clay
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, // Updated
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (templateIndex == null) "New Template" else "Edit Shift",
                        color = MaterialTheme.colorScheme.onBackground, // Updated
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground // Updated
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background // Updated
                )
            )
        },

        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background, // Updated
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        if (canSave) {
                            // Only copying the UI structure change for brevity
                            val finalStart = if (isAllDay) "00:00" else startTime
                            val finalEnd = if (isAllDay) "23:59" else endTime
                            val newShift = Shift(name, finalStart, finalEnd, selectedColorValue)
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
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                    enabled = canSave,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary, // Updated
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant // Updated
                    )
                ) {
                    Text(
                        "SAVE SHIFT",
                        color = MaterialTheme.colorScheme.onPrimary, // Updated
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {

            // --- SECTION 1: IDENTITY ---
            SectionHeader("Identity", Icons.Default.Edit)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant // Updated
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Name Field
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Shift Name", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        placeholder = { Text("e.g. Night Watch", color = MutedText.copy(alpha = 0.5f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = proTextFieldColors()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Color Tag", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    // Added a horizontal scroll state
                    val colorScrollState = rememberScrollState()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(colorScrollState) // This enables scrolling
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        colorOptions.forEach { colorVal ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorVal.toInt()))
                                    .clickable { selectedColorValue = colorVal }
                            ) {
                                if (selectedColorValue == colorVal) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        // Changed tint to black for better contrast on light colors
                                        tint = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- SECTION 2: SCHEDULE ---
            SectionHeader("Time & Duration", Icons.Default.DateRange)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant // Updated
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // All Day Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("All Day Event", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = isAllDay,
                            onCheckedChange = { isAllDay = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary, // Updated
                                checkedTrackColor = MaterialTheme.colorScheme.primary,   // Updated
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant, // Updated
                                uncheckedTrackColor = MaterialTheme.colorScheme.surface // Updated
                            )
                        )
                    }

                    if (!isAllDay) {
                        Divider(modifier = Modifier.padding(vertical = 12.dp), color = MutedText.copy(alpha = 0.2f))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProTimeSelector("Start Time", startTime, modifier = Modifier.weight(1f)) {
                                pickingForStart = true
                                showTimePicker = true
                            }
                            ProTimeSelector("End Time", endTime, modifier = Modifier.weight(1f)) {
                                pickingForStart = false
                                showTimePicker = true
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Unpaid Break Slider
                        Text("Unpaid Break: ${breakDurationMinutes.toInt()} min", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = breakDurationMinutes,
                            onValueChange = { breakDurationMinutes = it },
                            valueRange = 0f..120f,
                            steps = 3, // 0, 30, 60, 90, 120
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary, // Updated
                                activeTrackColor = MaterialTheme.colorScheme.primary, // Updated
                                inactiveTrackColor = MaterialTheme.colorScheme.surface // Updated
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- SECTION 3: DETAILS (OPTIONAL) ---
            SectionHeader("Details (Optional)", Icons.Default.Info)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant // Updated
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Wage and Paid Time Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Hourly Rate Field
                        OutlinedTextField(
                            value = hourlyRate,
                            onValueChange = { if (it.all { char -> char.isDigit() || char == '.' }) hourlyRate = it },
                            label = { Text("Wage/Hour ($)", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = proTextFieldColors()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Notes", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        colors = proTextFieldColors()
                    )
                }
            }


            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Time Picker Dialog
    if (showTimePicker) {
        TimePickerDialog(
            onDismissRequest = { showTimePicker = false },
            onConfirm = {
                val formattedTime = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                if (pickingForStart) startTime = formattedTime else endTime = formattedTime
                showTimePicker = false
            }
        ) {
            TimePicker(
                state = timePickerState,
                colors = TimePickerDefaults.colors(
                    timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    timeSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
                    clockDialSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
                    clockDialUnselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectorColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

// --- HELPER COMPONENTS ---

@Composable
fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, // Updated
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary, // Updated
            fontWeight = FontWeight.Bold
        )
    }
}


@Composable
fun ProTimeSelector(label: String, time: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface, // Updated
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)), // Updated
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold) // Updated
            Spacer(modifier = Modifier.height(4.dp))
            Text(time, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) // Updated
        }
    }
}


@Composable
fun TimePickerDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("OK", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface, // Fixed parameter name
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(
                "Select Time",
                style = MaterialTheme.typography.titleMedium // Optional: Add typography
            )
        },
        text = content
    )
}

@Composable
fun proTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedBorderColor = MaterialTheme.colorScheme.primary,      // Updated
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), // Updated
    cursorColor = MaterialTheme.colorScheme.primary,             // Updated
    focusedTextColor = MaterialTheme.colorScheme.onSurface,      // Updated
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,    // Updated
    focusedLabelColor = MaterialTheme.colorScheme.primary,       // Updated
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant // Updated
)


@Preview(showBackground = true)
@Composable
fun PreviewCustomShiftScreen() {
    val context = LocalContext.current
    val shiftManager = ShiftManager(context) // Use actual constructor

    CustomShiftScreen(
        selectedDate = LocalDate.now(),
        templateIndex = null,
        shiftManager = shiftManager,
        onBackClick = {},
        onSaveFinished = {}
    )
}

