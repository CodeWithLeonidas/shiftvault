package com.leonidaskiachidis.shiftvault.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// --- Updated "Dimmed" Light Theme Colors ---
val RoyalBlue = Color(0xFF151B54)      // Darker Royal Blue for better contrast
val ChampagneGold = Color(0xFFC5A028)  // Slightly darker Gold
val PlatinumGrey = Color(0xFFE3E6EA)   // Replaces IceWhite (Background) - Darker, cooler grey
val CloudWhite = Color(0xFFF7F9FC)     // Replaces PureWhite (Surface) - Not blinding white
val DeepSlate = Color(0xFF1F2933)      // Darker text color
val CardGrey = Color(0xFFC2C4C7)       // Darker container color

private val DarkColorScheme = darkColorScheme(
    primary = SoftCyan,
    secondary = ElectricViolet,
    tertiary = MutedText,
    background = MidnightBlue,
    surface = SlateGrey,
    surfaceVariant = SlateGrey.copy(alpha = 0.5f),
    onPrimary = MidnightBlue,
    onBackground = OffWhite,
    onSurface = OffWhite
)

private val LightColorScheme = lightColorScheme(
    primary = RoyalBlue,
    secondary = ChampagneGold,
    tertiary = DeepSlate,
    background = PlatinumGrey,       // Changed from IceWhite to PlatinumGrey
    surface = CloudWhite,            // Changed from PureWhite to CloudWhite
    surfaceVariant = CardGrey,       // Darker cards
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = DeepSlate,        // Darker text
    onSurface = DeepSlate,           // Darker text
    onSurfaceVariant = DeepSlate
)

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
