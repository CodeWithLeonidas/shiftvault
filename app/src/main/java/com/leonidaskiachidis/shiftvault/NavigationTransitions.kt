package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring

object NavigationTransitions {
    private const val CUSTOM_SHIFT_EXIT_MS = 280

    /**
     * Bottom-bar tabs swap instantly. Sliding between peer destinations reads as
     * novelty on the first few taps and as lag on every one after that, so the
     * tabs get no transition at all. The CustomShift sheet keeps its own
     * animation — that one is driven by the AnimatedVisibility overlay in
     * MainActivity, not by these functions.
     */
    fun getEnterTransition(initialRoute: String?, targetRoute: String?): EnterTransition =
        EnterTransition.None

    fun getExitTransition(initialRoute: String?, targetRoute: String?): ExitTransition =
        ExitTransition.None

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
