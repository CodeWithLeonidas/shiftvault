package com.leonidaskiachidis.shiftvault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

data class ShiftIcon(
    val name: String,
    val icon: ImageVector,
    val label: String
)

val shiftIconOptions = listOf(
    ShiftIcon("work", Icons.Outlined.WorkOutline, "Work"),
    ShiftIcon("home", Icons.Outlined.Home, "Home"),
    ShiftIcon("restaurant", Icons.Outlined.Restaurant, "Food"),
    ShiftIcon("coffee", Icons.Outlined.Coffee, "Cafe"),
    ShiftIcon("medical", Icons.Outlined.MedicalServices, "Medical"),
    ShiftIcon("school", Icons.Outlined.School, "School"),
    ShiftIcon("fitness", Icons.Outlined.FitnessCenter, "Fitness"),
    ShiftIcon("phone", Icons.Outlined.Phone, "Phone"),
    ShiftIcon("day", Icons.Outlined.WbSunny, "Day"),
    ShiftIcon("car", Icons.Outlined.DirectionsCar, "Drive"),
    ShiftIcon("flight", Icons.Outlined.Flight, "Travel"),
    ShiftIcon("night", Icons.Outlined.NightsStay, "Night")
)

fun getShiftIcon(name: String?): ImageVector? {
    return name?.let { iconName ->
        shiftIconOptions.find { it.name == iconName }?.icon
    }
}
