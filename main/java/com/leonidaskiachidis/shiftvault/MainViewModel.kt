package com.leonidaskiachidis.shiftvault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val shiftManager = ShiftManager(application)

    // Eagerly start collecting so data is ready before any screen renders
    val shifts: StateFlow<Map<LocalDate, List<Shift>>> = shiftManager.getShifts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    val templates: StateFlow<List<Shift>> = shiftManager.getTemplates
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val currency: StateFlow<String> = shiftManager.getCurrency
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = "EUR"
        )

    // Track whether initial data load is complete
    val isDataReady: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        shiftManager.getShifts,
        shiftManager.getTemplates,
        shiftManager.getCurrency
    ) { _, _, _ -> true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    // Expose ShiftManager for save operations
    val manager: ShiftManager get() = shiftManager
}
