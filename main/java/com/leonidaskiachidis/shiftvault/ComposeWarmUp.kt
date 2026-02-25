package com.leonidaskiachidis.shiftvault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Renders all heavy composables off-screen at zero opacity
 * so Compose measures/compiles them during the splash screen.
 * This eliminates first-navigation jank.
 */
@Composable
fun ComposeWarmUp(
    savedShifts: Map<LocalDate, List<Shift>>,
    templates: List<Shift>,
    currencyCode: String
) {
    Box(
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
    ) {
        // Warm up the calendar/main screen composables
        MainScreen(
            onSettingsClick = {},
            onCreateCustomClick = {},
            onStatsClick = {}
        )

        // Warm up stats screen
        StatsScreen(
            savedShifts = savedShifts,
            onBackClick = {},
            currencyCode = currencyCode
        )

        // Warm up settings screen
        SettingsScreen(
            isDarkTheme = false,
            onThemeChange = {},
            selectedCurrencyCode = currencyCode,
            onCurrencyChange = {},
            onExportPdf = {},
            onBackClick = {}
        )

        // Warm up templates screen
        TemplatesScreenContent(
            templates = templates,
            onEdit = {},
            onDelete = {}
        )
    }
}
