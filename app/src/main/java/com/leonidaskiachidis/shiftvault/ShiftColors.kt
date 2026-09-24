package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  ShiftColors — the palette a shift can be painted with, and the two rules
 *  that keep any colour legible once it lands in the UI.
 *
 *  A shift's colour is stored as a raw ARGB value, and since the New Shift form
 *  now offers a free custom picker it can be *any* value — including near-white
 *  and near-black, which would disappear against one theme or the other. So the
 *  stored colour is treated as an identity, never as a paint: every drawing site
 *  runs it through [ShiftColors.ink] (text, small marks) or
 *  [ShiftColors.accent] (dots, arcs, bars) first, which keeps the hue and pulls
 *  the lightness into a band that reads on the current theme.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme

object ShiftColors {

    /**
     * Thirteen presets, spread around the wheel so no two sit close enough to be
     * mistaken for each other at the size of a calendar dot, plus one neutral.
     * The fourteenth slot in the picker grid is the custom colour.
     */
    val Palette: List<Color> = listOf(
        Color(0xFFE04B4B), // red
        Color(0xFFEE7B21), // orange
        Color(0xFFD5A312), // amber
        Color(0xFF8AAE22), // lime
        Color(0xFF3BA757), // green
        Color(0xFF14A58F), // emerald
        Color(0xFF17A2C4), // cyan
        Color(0xFF3F7DDE), // blue
        Color(0xFF6260D8), // indigo
        Color(0xFF9152D6), // violet
        Color(0xFFC049BE), // magenta
        Color(0xFFE0538C), // pink
        Color(0xFF6B7A8C)  // slate
    )

    /** True when [color] is one of the presets — i.e. not a custom pick. */
    fun isPreset(color: Color): Boolean = Palette.any { it.toArgb() == color.toArgb() }

    private fun reshade(color: Color, minL: Float, maxL: Float, minS: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        // A colour the user desaturated to near-grey still has to read as a
        // marker, so saturation gets a floor — except for deliberate greys,
        // which are left alone.
        if (hsl[1] > 0.06f) hsl[1] = hsl[1].coerceAtLeast(minS)
        hsl[2] = hsl[2].coerceIn(minL, maxL)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    /** For text and small icons drawn in the shift's colour. */
    fun ink(color: Color, isDark: Boolean): Color =
        if (isDark) reshade(color, 0.66f, 0.86f, 0.30f)
        else reshade(color, 0.22f, 0.38f, 0.30f)

    /** For solid marks: dots, donut slices, progress bars. */
    fun accent(color: Color, isDark: Boolean): Color =
        if (isDark) reshade(color, 0.52f, 0.78f, 0.28f)
        else reshade(color, 0.30f, 0.54f, 0.28f)
}

// ══════════════════════════════════════════════════════════════
//  Composable shorthands — each memoises per colour + theme
// ══════════════════════════════════════════════════════════════

@Composable
fun rememberShiftColor(colorValue: Long): Color =
    remember(colorValue) { Color(colorValue.toInt()) }

@Composable
fun rememberShiftInk(color: Color): Color {
    val isDark = AppTheme.accents.isDark
    return remember(color, isDark) { ShiftColors.ink(color, isDark) }
}

@Composable
fun rememberShiftAccent(color: Color): Color {
    val isDark = AppTheme.accents.isDark
    return remember(color, isDark) { ShiftColors.accent(color, isDark) }
}

@Composable
fun rememberShiftAccent(colorValue: Long): Color =
    rememberShiftAccent(rememberShiftColor(colorValue))
