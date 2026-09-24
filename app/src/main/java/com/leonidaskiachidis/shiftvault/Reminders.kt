package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Reminders — "Shift starts in 1 hour" and "Log today's shift?".
 *
 *  Both are one-off WorkManager jobs that schedule their own successor, so
 *  there is only ever one pending job of each kind and it always points at
 *  the next real moment. Every change to shifts or reminder settings asks for
 *  a reschedule (debounced), which replaces the pending jobs.
 *
 *  WorkManager rather than exact alarms: exact alarms need a special
 *  permission Google Play reserves for alarm-clock apps. The cost is that
 *  Android may deliver a reminder a few minutes late while the phone is in
 *  deep sleep — fine for a heads-up an hour ahead.
 *
 *  ReminderPlanner holds the timing rules and is pure, so it is unit-tested.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** What the user has asked to be reminded of. Both are off until switched on. */
data class ReminderSettings(
    val shiftEnabled: Boolean = false,
    /** How long before a shift starts to remind, in minutes. */
    val shiftLeadMinutes: Int = 60,
    val logEnabled: Boolean = false,
    /** "HH:mm" — when to ask about today's shift. */
    val logTime: String = "20:00"
) {
    val logLocalTime: LocalTime
        get() = runCatching { LocalTime.parse(logTime) }.getOrDefault(LocalTime.of(20, 0))

    companion object {
        val LeadChoices = listOf(15, 30, 60, 120)

        fun leadLabel(minutes: Int): String = when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}

/** A worked shift pinned to the moment it starts. */
data class UpcomingShift(val start: LocalDateTime, val shift: Shift)

// ══════════════════════════════════════════════════════════════
//  Timing rules (pure)
// ══════════════════════════════════════════════════════════════

object ReminderPlanner {

    /**
     * Worked shifts with a real start time, as moments, soonest first. Leave
     * and all-day markers have no start worth announcing.
     */
    fun upcoming(shifts: Map<LocalDate, List<Shift>>, from: LocalDateTime): List<UpcomingShift> =
        shifts.asSequence()
            .filter { (date, _) -> !date.isBefore(from.toLocalDate().minusDays(1)) }
            .flatMap { (date, list) ->
                list.asSequence()
                    .filter { PayEngine.isWorked(it) && !it.isAllDaySpan }
                    .mapNotNull { s ->
                        runCatching { LocalTime.parse(s.startTime) }.getOrNull()
                            ?.let { UpcomingShift(date.atTime(it), s) }
                    }
            }
            .filter { it.start.isAfter(from) }
            .sortedBy { it.start }
            .toList()

    /**
     * When the next shift reminder should fire: [leadMinutes] before the
     * earliest shift whose reminder moment is still ahead. A shift added with
     * less than the lead time to go gets no reminder — it would arrive late.
     */
    fun nextShiftReminder(
        shifts: Map<LocalDate, List<Shift>>,
        leadMinutes: Int,
        now: LocalDateTime
    ): LocalDateTime? =
        upcoming(shifts, now)
            .map { it.start.minusMinutes(leadMinutes.toLong()) }
            .firstOrNull { it.isAfter(now) }

    /**
     * The shifts a reminder firing at [now] should announce: those starting
     * within the lead time, allowing [grace] minutes for a late delivery.
     * [alreadyAnnouncedUntil] stops a shift from being announced twice.
     */
    fun dueNow(
        shifts: Map<LocalDate, List<Shift>>,
        leadMinutes: Int,
        now: LocalDateTime,
        alreadyAnnouncedUntil: LocalDateTime?,
        grace: Long = 20
    ): List<UpcomingShift> =
        upcoming(shifts, now)
            .filter { !it.start.isAfter(now.plusMinutes(leadMinutes + grace)) }
            .filter { alreadyAnnouncedUntil == null || it.start.isAfter(alreadyAnnouncedUntil) }

    /** The next time the daily log reminder is due: today at [time] if still ahead, else tomorrow. */
    fun nextLogReminder(time: LocalTime, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** True when any job has something — worked or leave — logged on [date]. */
    fun hasEntryOn(shifts: Map<LocalDate, List<Shift>>, date: LocalDate): Boolean =
        shifts[date].orEmpty().isNotEmpty()
}

// ══════════════════════════════════════════════════════════════
//  Scheduling
// ══════════════════════════════════════════════════════════════

object Reminders {
    private const val SHIFT_WORK = "shift_reminder"
    private const val LOG_WORK = "log_reminder"
    const val SHIFT_CHANNEL = "shift_reminders"
    const val LOG_CHANNEL = "log_reminders"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /**
     * Asks for the pending reminders to be worked out again. Debounced: a burst
     * of edits (tapping days in paste mode) settles into one reschedule.
     */
    fun requestReschedule(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(600)
                reschedule(app)
            }
        }
    }

    /** Replaces both pending jobs with ones aimed at the next real moments. */
    suspend fun reschedule(context: Context) {
        val manager = ShiftManager(context)
        val settings = manager.getReminderSettings.first()
        val work = WorkManager.getInstance(context)
        val now = LocalDateTime.now()

        if (settings.shiftEnabled) {
            val at = ReminderPlanner.nextShiftReminder(manager.getShifts.first(), settings.shiftLeadMinutes, now)
            if (at != null) enqueue(work, SHIFT_WORK, at, now, ShiftReminderWorker::class.java)
            else work.cancelUniqueWork(SHIFT_WORK)
        } else {
            work.cancelUniqueWork(SHIFT_WORK)
        }

        if (settings.logEnabled) {
            val at = ReminderPlanner.nextLogReminder(settings.logLocalTime, now)
            enqueue(work, LOG_WORK, at, now, LogReminderWorker::class.java)
        } else {
            work.cancelUniqueWork(LOG_WORK)
        }
    }

    private fun enqueue(
        work: WorkManager,
        name: String,
        at: LocalDateTime,
        now: LocalDateTime,
        worker: Class<out CoroutineWorker>
    ) {
        val delayMs = Duration.between(now, at).toMillis().coerceAtLeast(0)
        val request = androidx.work.OneTimeWorkRequest.Builder(worker)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    // ── Notifications ──

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(SHIFT_CHANNEL, "Shift reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A heads-up before each shift starts" }
        )
        nm.createNotificationChannel(
            NotificationChannel(LOG_CHANNEL, "Daily log reminder", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A nudge to log the day's shift" }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        if (!canNotify(context)) return
        createChannels(context)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}

// ══════════════════════════════════════════════════════════════
//  Workers
// ══════════════════════════════════════════════════════════════

class ShiftReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = ShiftManager(applicationContext)
        val settings = manager.getReminderSettings.first()
        if (settings.shiftEnabled) {
            val now = LocalDateTime.now()
            val due = ReminderPlanner.dueNow(
                manager.getShifts.first(), settings.shiftLeadMinutes, now, manager.lastAnnouncedShiftStart()
            )
            if (due.isNotEmpty()) {
                val profiles = manager.getProfiles.first()
                val showJob = profiles.size > 1
                due.forEach { upcoming ->
                    val minutes = Duration.between(now, upcoming.start).toMinutes().coerceAtLeast(0)
                    val job = profiles.firstOrNull { it.id == upcoming.shift.jobId }?.name
                    Reminders.post(
                        applicationContext,
                        id = 1000 + (upcoming.start.hashCode() and 0xFFF),
                        channel = Reminders.SHIFT_CHANNEL,
                        title = "${upcoming.shift.name} starts in ${ReminderSettings.leadLabel(minutes.toInt().coerceAtLeast(1))}",
                        text = buildList {
                            add("At ${upcoming.shift.startTime}")
                            if (upcoming.shift.location.isNotBlank()) add(upcoming.shift.location)
                            if (showJob && job != null) add(job)
                        }.joinToString(" · ")
                    )
                }
                manager.saveLastAnnouncedShiftStart(due.maxOf { it.start })
            }
        }
        Reminders.reschedule(applicationContext)
        return Result.success()
    }
}

class LogReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = ShiftManager(applicationContext)
        val settings = manager.getReminderSettings.first()
        if (settings.logEnabled && !ReminderPlanner.hasEntryOn(manager.getShifts.first(), LocalDate.now())) {
            Reminders.post(
                applicationContext,
                id = 2000,
                channel = Reminders.LOG_CHANNEL,
                title = "Log today's shift?",
                text = "Nothing is logged for today yet — tap to add it."
            )
        }
        Reminders.reschedule(applicationContext)
        return Result.success()
    }
}
