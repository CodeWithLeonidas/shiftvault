package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

object NavigationTransitions {
    private const val DURATION_MS = 300

    // Helper to determine navigation direction
    private fun getScreenPosition(route: String?): Int {
        return when (route) {
            Screen.Vault.route -> 0
            Screen.Templates.route -> 1
            Screen.Stats.route -> 2
            Screen.Settings.route -> 3
            else -> -1
        }
    }

    fun getEnterTransition(initialRoute: String?, targetRoute: String?): EnterTransition {
        val fromPos = getScreenPosition(initialRoute)
        val toPos = getScreenPosition(targetRoute)

        // Moving right (forward)
        return if (toPos > fromPos) {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(DURATION_MS, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(DURATION_MS))
        } else {
            // Moving left (back)
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(DURATION_MS, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(DURATION_MS))
        }
    }

    fun getExitTransition(initialRoute: String?, targetRoute: String?): ExitTransition {
        val fromPos = getScreenPosition(initialRoute)
        val toPos = getScreenPosition(targetRoute)

        // Moving right (forward) - slide left and fade
        return if (toPos > fromPos) {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth / 4 },
                animationSpec = tween(DURATION_MS, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(DURATION_MS))
        } else {
            // Moving left (back) - slide right and fade
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth / 4 },
                animationSpec = tween(DURATION_MS, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(DURATION_MS))
        }
    }

    // CustomShift always slides up from bottom
    val customShiftEnter: EnterTransition = slideInVertically(
        initialOffsetY = { fullHeight -> fullHeight },
        animationSpec = tween(DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(DURATION_MS))

    val customShiftExit: ExitTransition = slideOutVertically(
        targetOffsetY = { fullHeight -> fullHeight },
        animationSpec = tween(DURATION_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(DURATION_MS))
}
