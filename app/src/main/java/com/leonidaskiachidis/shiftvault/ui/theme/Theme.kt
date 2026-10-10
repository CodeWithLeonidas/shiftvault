package com.leonidaskiachidis.shiftvault.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ══════════════════════════════════════════════════════════════
//  Shapes — soft, oversized radii
// ══════════════════════════════════════════════════════════════

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small      = RoundedCornerShape(14.dp),
    medium     = RoundedCornerShape(20.dp),
    large      = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

// ══════════════════════════════════════════════════════════════
//  Accents — colors Material3 has no slot for
// ══════════════════════════════════════════════════════════════

data class AppAccents(
    /**
     * Shadows carry no information on a dark ground, so components that lean on
     * elevation to separate themselves from the background switch to a hairline
     * outline instead. Read it through AppTheme.accents.isDark.
     */
    val isDark: Boolean,
    val morning: Color,
    val evening: Color,
    val night: Color,
    val morningSoft: Color,
    val eveningSoft: Color,
    val nightSoft: Color,
    val positive: Color,
    val negative: Color,
    /** Needs attention but isn't an error: rest warnings, an overdue backup. */
    val warning: Color,
    val cardShadow: Color,
    /**
     * Drawn on top of the calendar surface for Saturday / Sunday cells. A plain
     * black wash, so every palette gets the same "slightly darker" reading
     * without any of the sixteen schemes having to define a hue of its own.
     */
    val weekendWash: Color
)

private val LightAccents = AppAccents(
    isDark      = false,
    morning     = ShiftMorning,
    evening     = ShiftEvening,
    night       = ShiftNight,
    morningSoft = ShiftMorningSoft,
    eveningSoft = ShiftEveningSoft,
    nightSoft   = ShiftNightSoft,
    positive    = PositiveGreen,
    negative    = NegativeRed,
    warning     = Color(0xFFD9822B),
    cardShadow  = Color(0x14262433),
    weekendWash = Color(0x08000000)
)

private val DarkAccents = AppAccents(
    isDark      = true,
    morning     = Color(0xFF86BFA0),
    evening     = Color(0xFFA79BE8),
    night       = Color(0xFFD99A6C),
    morningSoft = Color(0xFF22322A),
    eveningSoft = Color(0xFF2A2740),
    nightSoft   = Color(0xFF33271E),
    positive    = Color(0xFF7FC79C),
    negative    = Color(0xFFE58A8A),
    warning     = Color(0xFFF0B060),
    cardShadow  = Color(0x33000000),
    weekendWash = Color(0x1A000000)
)

val LocalAppAccents = staticCompositionLocalOf { LightAccents }

/** Shorthand: AppTheme.accents.morning */
object AppTheme {
    val accents: AppAccents
        @Composable @ReadOnlyComposable get() = LocalAppAccents.current
}

// ══════════════════════════════════════════════════════════════
//  Scheme builders — keep every palette to the same recipe
// ══════════════════════════════════════════════════════════════

private fun softLight(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    tertiary: Color,
    background: Color,
    surfaceVariant: Color,
    outline: Color,
    outlineVariant: Color,
    ink: Color,
    muted: Color
): ColorScheme = lightColorScheme(
    primary              = primary,
    onPrimary            = Color.White,
    primaryContainer     = primaryContainer,
    onPrimaryContainer   = onPrimaryContainer,
    secondary            = secondary,
    onSecondary          = Color.White,
    tertiary             = tertiary,
    onTertiary           = Color.White,
    background           = background,
    onBackground         = ink,
    surface              = Color.White,
    onSurface            = ink,
    surfaceVariant       = surfaceVariant,
    onSurfaceVariant     = muted,
    surfaceContainerHigh = Color.White,
    outline              = outline,
    outlineVariant       = outlineVariant,
    error                = NegativeRed,
    onError              = Color.White
)

private fun softDark(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    tertiary: Color,
    background: Color,
    surface: Color,
    surfaceHigh: Color,
    surfaceVariant: Color,
    outline: Color,
    ink: Color,
    muted: Color
): ColorScheme = darkColorScheme(
    primary              = primary,
    onPrimary            = background,
    primaryContainer     = primaryContainer,
    onPrimaryContainer   = onPrimaryContainer,
    secondary            = secondary,
    onSecondary          = background,
    tertiary             = tertiary,
    onTertiary           = background,
    background           = background,
    onBackground         = ink,
    surface              = surface,
    onSurface            = ink,
    surfaceVariant       = surfaceVariant,
    onSurfaceVariant     = muted,
    surfaceContainerHigh = surfaceHigh,
    outline              = outline,
    outlineVariant       = outline,
    error                = Color(0xFFE58A8A),
    onError              = background
)

// ══════════════════════════════════════════════════════════════
//  LIGHT PALETTES — eight distinct hue families
// ══════════════════════════════════════════════════════════════

// 1 · CORAL — the redesign baseline
private val CoralLight = softLight(
    primary = Color(0xFFF0685A), primaryContainer = Color(0xFFFDE7E3),
    onPrimaryContainer = Color(0xFFC24C3D),
    secondary = Color(0xFF9C94DC), tertiary = Color(0xFF8CC4A8),
    background = Color(0xFFF7F4EF), surfaceVariant = Color(0xFFF1EDE6),
    outline = Color(0xFFD4CDC0), outlineVariant = Color(0xFFE3DDD1),
    ink = Color(0xFF262433), muted = Color(0xFF5F5B6B)
)

// 2 · SAGE — muted green
private val SageLight = softLight(
    primary = Color(0xFF6F9E82), primaryContainer = Color(0xFFE2EFE7),
    onPrimaryContainer = Color(0xFF4A7A5E),
    secondary = Color(0xFF8FA9C4), tertiary = Color(0xFFD2A86E),
    background = Color(0xFFF4F7F3), surfaceVariant = Color(0xFFEBF0EA),
    outline = Color(0xFFC9D5C7), outlineVariant = Color(0xFFD9E2D7),
    ink = Color(0xFF222A25), muted = Color(0xFF55615A)
)

// 3 · LILAC — soft violet
private val LilacLight = softLight(
    primary = Color(0xFF8E80D9), primaryContainer = Color(0xFFE9E5F8),
    onPrimaryContainer = Color(0xFF6354AE),
    secondary = Color(0xFF84B8B4), tertiary = Color(0xFFDFA0B8),
    background = Color(0xFFF6F5FB), surfaceVariant = Color(0xFFEDEBF5),
    outline = Color(0xFFCDC9E1), outlineVariant = Color(0xFFDCD8EA),
    ink = Color(0xFF262336), muted = Color(0xFF5B5770)
)

// 4 · OCEAN — dusty blue
private val OceanLight = softLight(
    primary = Color(0xFF4F8CA8), primaryContainer = Color(0xFFE0EDF2),
    onPrimaryContainer = Color(0xFF356A83),
    secondary = Color(0xFF7FB0A2), tertiary = Color(0xFFE0A98A),
    background = Color(0xFFF2F6F8), surfaceVariant = Color(0xFFE8EFF2),
    outline = Color(0xFFC4D2DA), outlineVariant = Color(0xFFD5E1E7),
    ink = Color(0xFF1F2A31), muted = Color(0xFF53616B)
)

// 5 · CLAY — warm terracotta
private val ClayLight = softLight(
    primary = Color(0xFFB57A5E), primaryContainer = Color(0xFFF5E6DD),
    onPrimaryContainer = Color(0xFF8C5942),
    secondary = Color(0xFF8FA98F), tertiary = Color(0xFFD9B36C),
    background = Color(0xFFF9F5F1), surfaceVariant = Color(0xFFF1EAE3),
    outline = Color(0xFFD5C8B9), outlineVariant = Color(0xFFE1D6CA),
    ink = Color(0xFF2B221C), muted = Color(0xFF62574D)
)

// 6 · PLUM — dusty mauve
private val PlumLight = softLight(
    primary = Color(0xFFA0658A), primaryContainer = Color(0xFFF2E4EC),
    onPrimaryContainer = Color(0xFF7C476A),
    secondary = Color(0xFF7FA3B5), tertiary = Color(0xFFDCA98C),
    background = Color(0xFFF8F4F7), surfaceVariant = Color(0xFFF0E9EE),
    outline = Color(0xFFD6C8D1), outlineVariant = Color(0xFFE2D6DE),
    ink = Color(0xFF2B2129), muted = Color(0xFF60555D)
)

// 7 · MOSS — olive green
private val MossLight = softLight(
    primary = Color(0xFF7D8F52), primaryContainer = Color(0xFFEAEEDB),
    onPrimaryContainer = Color(0xFF5F6E3B),
    secondary = Color(0xFF8BA8A0), tertiary = Color(0xFFC98F63),
    background = Color(0xFFF6F7F1), surfaceVariant = Color(0xFFEDEFE4),
    outline = Color(0xFFCFD4BF), outlineVariant = Color(0xFFDBE0CD),
    ink = Color(0xFF262719), muted = Color(0xFF5A5F4C)
)

// 8 · SLATE — blue-grey
private val SlateLight = softLight(
    primary = Color(0xFF5C7391), primaryContainer = Color(0xFFE3E9F1),
    onPrimaryContainer = Color(0xFF445A76),
    secondary = Color(0xFF8FA5A0), tertiary = Color(0xFFC79A86),
    background = Color(0xFFF3F5F8), surfaceVariant = Color(0xFFE9EDF3),
    outline = Color(0xFFC7D0DB), outlineVariant = Color(0xFFD8DFE8),
    ink = Color(0xFF1F2430), muted = Color(0xFF535C6A)
)

// ══════════════════════════════════════════════════════════════
//  DARK PALETTES — eight distinct hue families
// ══════════════════════════════════════════════════════════════

// 1 · WARM NIGHT — coral on warm charcoal
private val WarmNightDark = softDark(
    primary = Color(0xFFF5867A), primaryContainer = Color(0xFF3B2622),
    onPrimaryContainer = Color(0xFFFFC0B6),
    secondary = Color(0xFFA79BE8), tertiary = Color(0xFF8CC4A8),
    background = Color(0xFF17161C), surface = Color(0xFF211F27),
    surfaceHigh = Color(0xFF272430), surfaceVariant = Color(0xFF2B2833),
    outline = Color(0xFF474350), ink = Color(0xFFF4F1EC), muted = Color(0xFFBEB8C7)
)

// 2 · FOREST — sage on deep green-black
private val ForestDark = softDark(
    primary = Color(0xFF86BFA0), primaryContainer = Color(0xFF213329),
    onPrimaryContainer = Color(0xFFB4DEC5),
    secondary = Color(0xFF9FB4D6), tertiary = Color(0xFFD9B07C),
    background = Color(0xFF121815), surface = Color(0xFF1A221E),
    surfaceHigh = Color(0xFF1F2823), surfaceVariant = Color(0xFF242D28),
    outline = Color(0xFF3F4C45), ink = Color(0xFFE8F0EA), muted = Color(0xFFB6C4BB)
)

// 3 · AMETHYST — violet on deep indigo
private val AmethystDark = softDark(
    primary = Color(0xFFA79BE8), primaryContainer = Color(0xFF2C2745),
    onPrimaryContainer = Color(0xFFCFC7FF),
    secondary = Color(0xFF7FB6B0), tertiary = Color(0xFFDFA0B8),
    background = Color(0xFF16151E), surface = Color(0xFF1F1D2A),
    surfaceHigh = Color(0xFF252231), surfaceVariant = Color(0xFF2A2839),
    outline = Color(0xFF46415C), ink = Color(0xFFEEECF7), muted = Color(0xFFBAB5CB)
)

// 4 · DEEP SEA — teal on ink blue
private val DeepSeaDark = softDark(
    primary = Color(0xFF6FAABF), primaryContainer = Color(0xFF1D323B),
    onPrimaryContainer = Color(0xFFA5D2E1),
    secondary = Color(0xFF8FBFA8), tertiary = Color(0xFFD9A184),
    background = Color(0xFF0F171C), surface = Color(0xFF172128),
    surfaceHigh = Color(0xFF1C272E), surfaceVariant = Color(0xFF212C34),
    outline = Color(0xFF3B4B56), ink = Color(0xFFE6EFF3), muted = Color(0xFFB1BEC8)
)

// 5 · EMBER — warm amber on brown-black
private val EmberDark = softDark(
    primary = Color(0xFFD99A6C), primaryContainer = Color(0xFF382A1E),
    onPrimaryContainer = Color(0xFFF2C49C),
    secondary = Color(0xFF9FB6C4), tertiary = Color(0xFF9FBA9C),
    background = Color(0xFF191512), surface = Color(0xFF221D19),
    surfaceHigh = Color(0xFF28221D), surfaceVariant = Color(0xFF2D2621),
    outline = Color(0xFF4B423A), ink = Color(0xFFF2ECE5), muted = Color(0xFFC4B9AC)
)

// 6 · MULBERRY — mauve on plum-black
private val MulberryDark = softDark(
    primary = Color(0xFFC88BAE), primaryContainer = Color(0xFF3A2733),
    onPrimaryContainer = Color(0xFFEBBAD5),
    secondary = Color(0xFF8FAEB8), tertiary = Color(0xFFD5A98C),
    background = Color(0xFF1A141A), surface = Color(0xFF231C23),
    surfaceHigh = Color(0xFF292129), surfaceVariant = Color(0xFF2E252E),
    outline = Color(0xFF4C3E4C), ink = Color(0xFFF1EAEF), muted = Color(0xFFC4B4BE)
)

// 7 · OLIVE — moss on dark khaki
private val OliveDark = softDark(
    primary = Color(0xFFAEBB80), primaryContainer = Color(0xFF2C3220),
    onPrimaryContainer = Color(0xFFD3DCAE),
    secondary = Color(0xFF8FB0AA), tertiary = Color(0xFFCE9770),
    background = Color(0xFF16180F), surface = Color(0xFF1E2116),
    surfaceHigh = Color(0xFF23261A), surfaceVariant = Color(0xFF282C1F),
    outline = Color(0xFF444A35), ink = Color(0xFFEDEFE3), muted = Color(0xFFBBBFA9)
)

// 8 · STEEL — cool blue-grey
private val SteelDark = softDark(
    primary = Color(0xFF90A7C6), primaryContainer = Color(0xFF262F3C),
    onPrimaryContainer = Color(0xFFC0D2E8),
    secondary = Color(0xFF9FB8AF), tertiary = Color(0xFFC99F8A),
    background = Color(0xFF13161C), surface = Color(0xFF1C2028),
    surfaceHigh = Color(0xFF21262E), surfaceVariant = Color(0xFF262B34),
    outline = Color(0xFF424955), ink = Color(0xFFEBEEF4), muted = Color(0xFFB7BECB)
)

// ══════════════════════════════════════════════════════════════
//  Public registries — used by Settings to build the picker
// ══════════════════════════════════════════════════════════════

data class ThemeVariantInfo(
    val key: String,
    val displayName: String,
    val isDark: Boolean,
    val previewPrimary: Color,
    val previewBackground: Color,
    val previewSurface: Color,
    val colorScheme: ColorScheme
)

private fun lightInfo(key: String, name: String, scheme: ColorScheme) =
    ThemeVariantInfo(key, name, false, scheme.primary, scheme.background, scheme.surface, scheme)

private fun darkInfo(key: String, name: String, scheme: ColorScheme) =
    ThemeVariantInfo(key, name, true, scheme.primary, scheme.background, scheme.surface, scheme)

val LightThemeVariants: List<ThemeVariantInfo> = listOf(
    lightInfo("default", "Coral", CoralLight),
    lightInfo("sage", "Sage", SageLight),
    lightInfo("lilac", "Lilac", LilacLight),
    lightInfo("ocean", "Ocean", OceanLight),
    lightInfo("clay", "Clay", ClayLight),
    lightInfo("plum", "Plum", PlumLight),
    lightInfo("moss", "Moss", MossLight),
    lightInfo("slate", "Slate", SlateLight)
)

val DarkThemeVariants: List<ThemeVariantInfo> = listOf(
    darkInfo("default", "Warm Night", WarmNightDark),
    darkInfo("forest", "Forest", ForestDark),
    darkInfo("amethyst", "Amethyst", AmethystDark),
    darkInfo("deepsea", "Deep Sea", DeepSeaDark),
    darkInfo("ember", "Ember", EmberDark),
    darkInfo("mulberry", "Mulberry", MulberryDark),
    darkInfo("olive", "Olive", OliveDark),
    darkInfo("steel", "Steel", SteelDark)
)

/**
 * The Material You theme: colours taken from the wallpaper (Android 12+). On
 * older systems a stored "dynamic" choice falls back to the default palette
 * through [resolveColorScheme], like any key this build doesn't know.
 */
const val DynamicThemeKey = "dynamic"

val supportsDynamicTheme: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@RequiresApi(Build.VERSION_CODES.S)
private fun dynamicScheme(isDark: Boolean, context: Context): ColorScheme =
    if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

/** What the theme picker offers: the wallpaper theme first where there is one, then the app's eight. */
fun themeVariantsFor(isDark: Boolean, context: Context): List<ThemeVariantInfo> {
    val fixed = if (isDark) DarkThemeVariants else LightThemeVariants
    if (!supportsDynamicTheme) return fixed
    val scheme = dynamicScheme(isDark, context)
    return listOf(
        ThemeVariantInfo(DynamicThemeKey, "Wallpaper", isDark, scheme.primary, scheme.background, scheme.surface, scheme)
    ) + fixed
}

fun resolveColorScheme(isDark: Boolean, variant: String): ColorScheme =
    if (isDark) {
        DarkThemeVariants.firstOrNull { it.key == variant }?.colorScheme ?: WarmNightDark
    } else {
        LightThemeVariants.firstOrNull { it.key == variant }?.colorScheme ?: CoralLight
    }

@Composable
fun ShiftVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeVariant: String = "default",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = remember(darkTheme, themeVariant, context) {
        if (themeVariant == DynamicThemeKey && supportsDynamicTheme) dynamicScheme(darkTheme, context)
        else resolveColorScheme(darkTheme, themeVariant)
    }
    val accents = if (darkTheme) DarkAccents else LightAccents

    CompositionLocalProvider(LocalAppAccents provides accents) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}