package com.leonidaskiachidis.shiftvault.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// --- Professional Light Theme Colors ---
val CharcoalGrey = Color(0xFF467C83)      // Professional dark grey primary
val AmberAccent = Color(0xFFF59E0B)       // Warm accent
val IvoryWhite = Color(0xFFFAFAFA)        // Softer white background
val PureWhite = Color(0xFFFFFFFF)         // Cards
val InkBlack = Color(0xFF1A202C)          // Text
val SilverGrey = Color(0xFFE2E8F0)        // Subtle surfaces

private val DarkColorScheme = darkColorScheme(
    primary = SoftCyan,
    secondary = ElectricViolet,
    tertiary = MutedText,
    background = MidnightBlue,
    surface = SlateGrey,
    surfaceVariant = Color(0xFF334155),
    surfaceContainerHigh = Color(0xFF1E293B),
    primaryContainer = SoftCyan.copy(alpha = 0.15f),
    onPrimary = Color(0xFF0A0E1A),
    onBackground = OffWhite,
    onSurface = OffWhite,
    onSurfaceVariant = Color(0xFFCBD5E1)
)

private val LightColorScheme = lightColorScheme(
    primary = CharcoalGrey,
    secondary = AmberAccent,
    tertiary = Color(0xFF64748B),
    background = IvoryWhite,
    surface = PureWhite,
    surfaceVariant = SilverGrey,
    surfaceContainerHigh = PureWhite,
    primaryContainer = CharcoalGrey.copy(alpha = 0.06f),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = InkBlack,
    onSurface = InkBlack,
    onSurfaceVariant = Color(0xFF394456)
)

private val ChampagneGoldLocal = Color(0xFFE8D5B7)


@Composable
fun ShiftVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
