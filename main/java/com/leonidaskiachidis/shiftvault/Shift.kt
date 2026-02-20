package com.leonidaskiachidis.shiftvault

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Shift(
    val name: String,
    @SuppressLint("UnsafeOptInUsageError") val startTime: String,
    val endTime: String,
    val colorValue: Long // We store the color as a number for easy saving
)