package com.leonidaskiachidis.shiftvault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.leonidaskiachidis.shiftvault.R

// 1. Define the Manrope FontFamily
val Manrope = FontFamily(
    Font(R.font.manrope_variablefont, FontWeight.Normal),   // W400
    Font(R.font.manrope_variablefont, FontWeight.Medium),   // W500 - The "Professional" Sweet Spot
    Font(R.font.manrope_variablefont, FontWeight.SemiBold), // W600
    Font(R.font.manrope_variablefont, FontWeight.Bold)      // W700
)

// 2. Configure the Typography
val Typography = Typography(
    // Large titles (e.g., Screen Headers)
    titleLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // Standard body text (e.g., Descriptions, Lists)
    bodyLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium, // Use Medium if Normal feels too thin
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    // Small labels (e.g., Buttons, Captions)
    labelMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)