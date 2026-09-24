package com.leonidaskiachidis.shiftvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// ══════════════════════════════════════════════════════════════
//  Number input
// ══════════════════════════════════════════════════════════════

/**
 * Reads a money or hours field. Accepts a decimal comma as well as a point —
 * Greek and most European keyboards type "12,50", which `toDoubleOrNull()`
 * rejects, so the amount used to be saved as zero without a word. Negative
 * values are refused: no rate, bonus or hour count is ever below zero.
 */
fun parseDecimal(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 }

/** "12.5" / "12" — a stored amount as it should reappear in a field. */
fun formatDecimalInput(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

// ══════════════════════════════════════════════════════════════
//  State that survives rotation
// ══════════════════════════════════════════════════════════════

/**
 * Saves a @Serializable model (a Shift, a JobProfile) across configuration
 * changes by way of its JSON, since those classes aren't Parcelable. Works for
 * nullable types too (`jsonSaver<Shift?>()`). Anything that fails to restore
 * falls back to the state's initial value.
 */
internal inline fun <reified T> jsonSaver(): Saver<T, String> = Saver(
    save = { value -> StoreJson.encodeToString(value) },
    restore = { json -> runCatching { StoreJson.decodeFromString<T>(json) }.getOrNull() }
)

// ══════════════════════════════════════════════════════════════
//  Clock
// ══════════════════════════════════════════════════════════════

/**
 * The current time, kept fresh: re-read whenever the app comes back to the
 * foreground, and on every hour boundary while it stays open. A plain
 * `remember { LocalDate.now() }` froze "today" at whenever the screen was
 * first drawn, so the calendar kept highlighting yesterday after midnight —
 * exactly when night-shift workers open the app.
 */
@Composable
fun rememberNow(): State<LocalDateTime> {
    val now = remember { mutableStateOf(LocalDateTime.now()) }

    LifecycleResumeEffect(Unit) {
        now.value = LocalDateTime.now()
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            val nextHour = current.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            // A beat past the boundary, so the new hour is certainly current.
            delay(Duration.between(current, nextHour).toMillis() + 500)
            now.value = LocalDateTime.now()
        }
    }
    return now
}
