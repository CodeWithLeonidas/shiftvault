package com.leonidaskiachidis.shiftvault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.R

/**
 * One weight of the bundled Manrope variable font.
 *
 * The weight has to be set on the font's `wght` axis explicitly. The plain
 * `Font(resId, weight)` overload only *labels* the font with a weight and
 * leaves the axis alone, so every one of these rendered at the file's default
 * instance — Manrope's default is 200, ExtraLight — and the whole app read as
 * hairline-thin no matter what the type scale below asked for.
 */
@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: FontWeight) = Font(
    R.font.manrope_variablefont,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

// 1. The base FontFamily
val Manrope = FontFamily(
    manrope(FontWeight.Normal),   // W400
    manrope(FontWeight.Medium),   // W500
    manrope(FontWeight.SemiBold), // W600
    manrope(FontWeight.Bold),     // W700
    manrope(FontWeight.ExtraBold) // W800
)

/**
 * Single switch for the whole app's font.
 * Swap this to another FontFamily (e.g. a rounded face) and every
 * screen follows — no other file needs to change.
 */
val AppFontFamily: FontFamily = Manrope

// Tabular figures: keeps money/time columns vertically aligned
private const val TabularNums = "tnum"

// 2. The type scale
//
// Weights step down as text gets longer: Bold for the few words that anchor a
// screen, SemiBold for titles and anything tappable, Medium for reading text.
// Manrope is open and wide, so it needs no positive tracking at body sizes;
// only the large sizes are tightened, and only the smallest labels opened up.
val Typography = Typography(

    // ── Screen titles: "Analytics", "My Shifts"; sheet titles ──
    displayLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp
    ),

    // ── Big numbers: "142h", "$2,840", "7.4h" ──
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TabularNums
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = TabularNums
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TabularNums
    ),

    // ── Card / section titles: "Hours worked", "Shift distribution" ──
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    // List item titles: "Barista Shift", "Floor Supervisor"
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),

    // ── Body: Medium, not Regular — Manrope's 400 is light on a phone screen ──
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    // Subtitles under headers: "Earnings & workload insights"
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),

    // ── Labels: chips, pills, buttons, nav bar ──
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    )
)
