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
 * One weight of the bundled Inter Tight variable font.
 *
 * The weight has to be set on the font's `wght` axis explicitly. The plain
 * `Font(resId, weight)` overload only *labels* the font with a weight and
 * leaves the axis alone, so every weight would render at the file's default
 * instance whatever the type scale below asked for.
 */
@OptIn(ExperimentalTextApi::class)
private fun interTight(weight: FontWeight) = Font(
    R.font.inter_tight_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

// 1. The base FontFamily — subset to Latin, Greek and symbols to keep the APK lean.
val InterTight = FontFamily(
    interTight(FontWeight.Normal),   // W400
    interTight(FontWeight.Medium),   // W500
    interTight(FontWeight.SemiBold), // W600
    interTight(FontWeight.Bold),     // W700
    interTight(FontWeight.ExtraBold) // W800
)

/**
 * Single switch for the whole app's font.
 * Swap this to another FontFamily and every screen follows — no other file
 * needs to change.
 */
val AppFontFamily: FontFamily = InterTight

// Tabular figures: keeps money/time columns vertically aligned
private const val TabularNums = "tnum"

// 2. The type scale
//
// Weights step down as text gets longer: Bold for the few words that anchor a
// screen, SemiBold for titles and anything tappable, Medium for reading text.
// Inter Tight is already narrow, so body sizes keep neutral tracking; the
// large sizes are pulled in further, as the display type in the design is.
val Typography = Typography(

    // ── Screen titles: "Analytics", "My Shifts"; sheet titles ──
    displayLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-1.3).sp
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.8).sp
    ),

    // ── Big numbers: "142h", "$2,840", "7.4h" ──
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-1.1).sp,
        fontFeatureSettings = TabularNums
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
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

    // ── Body: Medium, not Regular — a touch more presence on a phone screen ──
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
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp
    )
)
