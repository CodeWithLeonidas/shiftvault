package com.leonidaskiachidis.shiftvault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview
import kotlin.text.toInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    shiftManager: ShiftManager,
    onEdit: (Int) -> Unit,
    onCreateNew: () -> Unit // Add this parameter
) {
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf(listOf<Shift>()) }

    // Load templates from DataStore
    LaunchedEffect(Unit) {
        shiftManager.getTemplates.collect { loadedTemplates ->
            templates = loadedTemplates
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNew,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create New Template"
                )
            }
        }
    ) { paddingValues ->
        TemplatesScreenContent(
            templates = templates,
            onEdit = onEdit,
            onDelete = { shift ->
                scope.launch {
                    val current = shiftManager.getTemplates.first()
                    val updated = current.filter { it != shift }
                    shiftManager.saveTemplates(updated)
                }
            },
            modifier = Modifier.padding(paddingValues)
        )
    }
}

@Composable
fun TemplatesScreenContent(
    templates: List<Shift>,
    onEdit: (Int) -> Unit,
    onDelete: (Shift) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier // Apply the modifier here
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "Your Shifts",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
        )
        Text(
            text = "Manage your custom work hours.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (templates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No custom templates yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(templates) { index, shift ->
                    TemplateItem(
                        shift = shift,
                        onEdit = { onEdit(index) },
                        onDelete = { onDelete(shift) }
                    )
                }
            }
        }
    }
}


@Composable
fun TemplateItem(shift: Shift, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(Color(shift.colorValue.toInt()), RoundedCornerShape(2.dp))
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(shift.name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${shift.startTime} - ${shift.endTime}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }

            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


// Preview for TemplatesScreenContent
@Preview(showBackground = true)
@Composable
fun TemplatesScreenContentPreview() {
    val fakeShifts = listOf(
        Shift("Morning Shift", "08:00", "16:00", 0xFF2196F3),
        Shift("Evening Shift", "16:00", "00:00", 0xFFF44336)
    )
    TemplatesScreenContent(
        templates = fakeShifts,
        onEdit = {},
        onDelete = {}
    )
}

// Preview for TemplateItem
@Preview(showBackground = true)
@Composable
fun TemplateItemPreview() {
    TemplateItem(
        shift = Shift("Sample Shift", "09:00", "17:00", 0xFF4CAF50),
        onEdit = {},
        onDelete = {}
    )
}
