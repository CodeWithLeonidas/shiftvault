package com.leonidaskiachidis.shiftvault

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Shift(
    val name: String,
    @SuppressLint("UnsafeOptInUsageError") val startTime: String,
    val endTime: String,
    val colorValue: Long, // We store the color as a number for easy saving
    val isHoliday: Boolean = false,
    val hourlyRate: Double = 0.0,    // ADDED
    val notes: String = "",          // ADDED
    val breakMinutes: Int = 0        // ADDED
)

// Add this to your data model file
data class CurrencyInfo(
    val symbol: String,
    val code: String,
    val name: String
)