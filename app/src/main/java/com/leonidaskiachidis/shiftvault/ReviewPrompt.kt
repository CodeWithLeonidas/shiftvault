package com.leonidaskiachidis.shiftvault

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * When the app may ask for a rating: once, ever, and only from someone who
 * has really used it — ten worked shifts logged, three days after installing.
 * Never on launch; [ReviewPrompt.maybeAsk] is called right after something
 * went well (a shift logged, a report exported).
 */
object ReviewPolicy {
    const val MIN_WORKED_SHIFTS = 10
    const val MIN_DAYS_INSTALLED = 3L

    fun shouldAsk(workedShifts: Int, installedAt: Long, askedAt: Long?, now: Long): Boolean =
        askedAt == null &&
            workedShifts >= MIN_WORKED_SHIFTS &&
            now - installedAt >= MIN_DAYS_INSTALLED * 86_400_000L
}

object ReviewPrompt {
    private val asking = AtomicBoolean(false)

    /**
     * Shows Google Play's review card if [ReviewPolicy] allows it. Play decides
     * whether the card actually appears (it has its own quota) and nothing is
     * shown for builds not installed from Play — both are fine, the flow
     * simply completes.
     */
    suspend fun maybeAsk(activity: Activity, manager: ShiftManager, workedShifts: Int) {
        if (!asking.compareAndSet(false, true)) return
        try {
            val installedAt = runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0).firstInstallTime
            }.getOrNull() ?: return
            val now = System.currentTimeMillis()
            if (!ReviewPolicy.shouldAsk(workedShifts, installedAt, manager.reviewAskedAt(), now)) return

            val review = ReviewManagerFactory.create(activity)
            val shown = runCatching {
                val info = review.requestReviewFlow().await()
                review.launchReviewFlow(activity, info).await()
            }.isSuccess
            // Only a flow that ran counts as asked; a Play Services hiccup
            // leaves the next good moment free to try again.
            if (shown) manager.saveReviewAskedAt(now)
        } finally {
            asking.set(false)
        }
    }
}

/** Waits for a Play task, without pulling in the -ktx add-on for one call. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
    }
}
