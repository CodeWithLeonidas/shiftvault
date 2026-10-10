package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.IntOffset

object NavigationTransitions {
    private const val CUSTOM_SHIFT_EXIT_MS = 280
    private const val TAB_SLIDE_MS = 340

    /** Material's emphasized-decelerate: leaves quickly, lands softly. */
    private val TabEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val tabSlide = tween<IntOffset>(TAB_SLIDE_MS, easing = TabEasing)

    /**
     * +1 when [to] sits to the right of [from] in the bottom bar, -1 when to the
     * left, 0 when either isn't a tab (the shift form) or they're the same tab.
     * Taken from the bar's own order, so the page always travels the way the
     * pill does — adjacent or not.
     */
    fun tabDirection(from: String?, to: String?): Int {
        val origin = navIndexOf(from)
        val target = navIndexOf(to)
        if (origin < 0 || target < 0 || origin == target) return 0
        return if (target > origin) 1 else -1
    }

    /**
     * Tabs move like pages laid side by side: both travel the full width
     * together on one curve, so the incoming page fills exactly the space the
     * outgoing one leaves — no gap, no overlap, no blank frame. Anything to or
     * from the shift form keeps no transition; that one is the overlay's
     * AnimatedVisibility in MainActivity.
     */
    fun getEnterTransition(initialRoute: String?, targetRoute: String?): EnterTransition {
        val direction = tabDirection(initialRoute, targetRoute)
        if (direction == 0) return EnterTransition.None
        return slideInHorizontally(tabSlide) { fullWidth -> direction * fullWidth }
    }

    fun getExitTransition(initialRoute: String?, targetRoute: String?): ExitTransition {
        val direction = tabDirection(initialRoute, targetRoute)
        if (direction == 0) return ExitTransition.None
        return slideOutHorizontally(tabSlide) { fullWidth -> -direction * fullWidth }
    }

    // CustomShift: Smooth slide from bottom (nav bar fades in after exit completes)
    val customShiftEnter: EnterTransition =
        slideInVertically(
            initialOffsetY = { fullHeight -> fullHeight },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) +
        fadeIn(
            animationSpec = tween(200)
        )

    /**
     * The mirror image of [customShiftEnter]: the sheet slides all the way back
     * down and nothing else.
     *
     * It used to fade out over 200ms on top of a 300ms FastOutSlowIn slide —
     * an eased curve barely moves in its first third, so the sheet was already
     * fully transparent while still sitting near the top of the screen. That
     * read as "it just vanished". A single accelerating slide, with the screen
     * opaque the whole way, is the transition the entrance implies.
     */
    val customShiftExit: ExitTransition =
        slideOutVertically(
            targetOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(
                durationMillis = CUSTOM_SHIFT_EXIT_MS,
                easing = FastOutLinearInEasing
            )
        )
}
