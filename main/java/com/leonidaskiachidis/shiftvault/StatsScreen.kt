package com.leonidaskiachidis.shiftvault

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.ui.theme.*
import java.time.YearMonth
import kotlin.toString

@Composable
fun StatsScreen(
    savedShifts: Map<java.time.LocalDate, List<Shift>>,
    onBackClick: () -> Unit,  // NEW PARAMETER
    modifier: Modifier = Modifier
)
 {
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    val stats = remember(savedShifts, selectedMonth) {
        StatsHelper.calculateMonthlyStats(savedShifts, selectedMonth)
    }

     Column(
         modifier = modifier
             .fillMaxSize()
             .background(MaterialTheme.colorScheme.background) // Updated
             .systemBarsPadding()
             .padding(16.dp)
     ) {

        // Top Bar with Back Button
        StatsTopBar(
            onBackClick = onBackClick,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Month Selector
        MonthSelector(
            currentMonth = selectedMonth,
            onPreviousMonth = { selectedMonth = selectedMonth.minusMonths(1) },
            onNextMonth = { selectedMonth = selectedMonth.plusMonths(1) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Summary Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryCard(
                title = "Total Hours",
                value = StatsHelper.formatHours(stats.totalHours),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Total Shifts",
                value = stats.totalShifts.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SummaryCard(
            title = "Average Hours/Shift",
            value = StatsHelper.formatHours(stats.averageHoursPerShift),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Shift Breakdown Section
         Text(
             text = "SHIFT BREAKDOWN",
             fontSize = 14.sp,
             fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.onSurfaceVariant, // Updated
             modifier = Modifier.padding(bottom = 12.dp)
         )


         LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(stats.shiftBreakdown.toList()) { (shiftName, count) ->
                ShiftBreakdownRow(
                    name = shiftName,
                    count = count,
                    total = stats.totalShifts
                )
            }
        }
    }
}

@Composable
fun MonthSelector(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant // Updated
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousMonth, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    "Previous",
                    tint = MaterialTheme.colorScheme.primary, // Updated
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentMonth.month.name,
                    color = MaterialTheme.colorScheme.onSurface, // Updated
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = currentMonth.year.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // Updated
                    fontSize = 12.sp
                )
            }

            IconButton(onClick = onNextMonth, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    "Next",
                    tint = MaterialTheme.colorScheme.primary, // Updated
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}


@Composable
fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant // Updated
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary // Updated
            )
            Text(
                text = title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant // Updated
            )
        }
    }
}

@Composable
fun ShiftBreakdownRow(
    name: String,
    count: Int,
    total: Int
) {
    val percentage = if (total > 0) (count.toFloat() / total) else 0f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant // Updated
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onSurface, // Updated
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$count shifts",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // Updated
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp)) // Updated Track (darker than surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percentage)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)) // Updated Progress
                )
            }}
    }

}
@Composable
fun StatsTopBar(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant // Updated
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back Button
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary, // Updated
                    modifier = Modifier.size(24.dp)
                )
            }

            // Title
            Text(
                text = "Statistics",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface // Updated
            )

            // Empty space to balance the layout
            Spacer(modifier = Modifier.size(36.dp))
        }
    }
}

