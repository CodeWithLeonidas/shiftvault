package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min

// ══════════════════════════════════════════════════════════════
//  Bottom sheet
// ══════════════════════════════════════════════════════════════
//
// The app's one sheet. It replaces Material3's ModalBottomSheet, whose gesture
// handling caused the problems this file exists to avoid (as of M3 1.4.0):
//
//  • its drag started with no touch slop whenever the sheet was animating, so
//    a tap or scroll landing during any open/settle animation moved the sheet;
//  • every content fling ended in a settle() call, starting exactly such an
//    animation after each scroll;
//  • it re-measured its resting position from its own height and snapped to
//    it, so a snackbar appearing inside a sheet, or status-bar padding kicking
//    in as a tall sheet neared the top, made the sheet jump;
//  • 56dp of travel or a 125dp/s flick was enough to dismiss it.
//
// Here the sheet is laid out once, resting on the bottom edge, and a drag only
// changes a graphicsLayer translation — the layout and every touch target stay
// put. Who a vertical gesture belongs to is decided once per gesture by
// [SheetDragCoordinator] (see its rules), so content and sheet never both act on
// the same movement.

/** Above this downward release speed (dp/s) a flick dismisses, given [FlingMinTravel]. */
private const val FlingDismissVelocity = 800f
/** Releasing while moving up faster than this (dp/s) always keeps the sheet open. */
private const val ReturnVelocity = 200f
/** A flick only dismisses once the sheet has really moved — a twitch never does. */
private const val FlingMinTravel = 32f
/** Without a flick, the sheet closes once dragged this share of its height… */
private const val PositionalFraction = 0.4f
/** …or this far (dp), whichever is less, so tall sheets don't need a huge pull. */
private const val PositionalMax = 220f

private const val ScrimAlphaLight = 0.32f
private const val ScrimAlphaDark = 0.5f

private val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
private val SheetMaxWidth = 640.dp
/** Gap kept above the tallest sheet, so it never runs under the status bar. */
private val SheetTopGap = 24.dp

private val ShowSpec: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)
private val ReturnSpec: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private val HideSpec: AnimationSpec<Float> = tween(240, easing = FastOutLinearInEasing)

/**
 * Whether a released sheet, [offset] px below its resting place and moving at
 * [velocity] px/s (positive = down), should close rather than spring back.
 */
internal fun shouldDismissSheet(offset: Float, velocity: Float, sheetHeight: Float, density: Float): Boolean {
    if (offset <= 0f) return false
    if (velocity <= -ReturnVelocity * density) return false
    if (velocity >= FlingDismissVelocity * density && offset >= FlingMinTravel * density) return true
    val positional = min(sheetHeight * PositionalFraction, PositionalMax * density)
    return offset >= positional
}

/** Who the current vertical gesture inside a sheet belongs to. */
internal enum class SheetGestureOwner { Undecided, Sheet, Content }

/**
 * The gesture rules, kept free of Compose so they can be unit-tested:
 *
 *  1. A gesture's owner is decided by its first movement. If the content
 *     scrolled, the content owns the whole gesture: reaching the top just stops
 *     there, it never starts pulling the sheet down. If the content couldn't
 *     move (already at the top, or it doesn't scroll), the sheet owns it.
 *  2. While the sheet is pulled down, it takes every movement first. Pushing
 *     it back up to rest hands whatever is left of that movement to the
 *     content, so one continuous upward swipe closes the gap and then scrolls.
 *  3. Momentum (flings, programmatic scrolls) never moves the sheet.
 *
 * Offsets are px below the resting position, never negative.
 */
internal class SheetDragCoordinator(
    private val readOffset: () -> Float,
    private val writeOffset: (Float) -> Unit
) {
    var owner = SheetGestureOwner.Undecided
        private set

    /** A finger went down: whatever the last gesture decided no longer applies. */
    fun onGestureStart() {
        owner = SheetGestureOwner.Undecided
    }

    /** Before the content scrolls. Returns the part of [delta] the sheet used. */
    fun preScroll(delta: Float, fromUser: Boolean): Float {
        if (!fromUser) return 0f
        val offset = readOffset()
        if (offset <= 0f) return 0f
        owner = SheetGestureOwner.Sheet
        val next = (offset + delta).coerceAtLeast(0f)
        writeOffset(next)
        return next - offset
    }

    /** After the content scrolled by [consumed]. Returns the part of [available] the sheet used. */
    fun postScroll(consumed: Float, available: Float, fromUser: Boolean): Float {
        // Nothing sits above the sheet, so leftover momentum simply stops here.
        if (!fromUser) return available
        if (owner == SheetGestureOwner.Undecided && (consumed != 0f || available != 0f)) {
            owner = if (consumed != 0f) SheetGestureOwner.Content else SheetGestureOwner.Sheet
        }
        if (owner != SheetGestureOwner.Sheet || available <= 0f) return 0f
        writeOffset(readOffset() + available)
        return available
    }

    /** A drag on a part of the sheet that doesn't scroll (handle, title, rows). */
    fun drag(delta: Float) {
        writeOffset((readOffset() + delta).coerceAtLeast(0f))
    }

    /**
     * A scroll gesture ended. True if the sheet is pulled down, in which case it
     * must settle with the release velocity and the content must not fling.
     */
    fun endScroll(): Boolean {
        owner = SheetGestureOwner.Undecided
        return readOffset() > 0f
    }
}

/**
 * State of one [AppBottomSheet]. Several sheets that are never open at the same
 * time may share one; each re-enters from below.
 */
@Stable
class AppSheetState internal constructor() {
    /** Px below the resting position; NaN until the sheet has been measured and shown. */
    internal var offset by mutableFloatStateOf(Float.NaN)
    internal var height by mutableFloatStateOf(0f)

    /** Closing for good: input is ignored until the sheet leaves the composition. */
    internal var isDismissing by mutableStateOf(false)
        private set

    /** The opening slide is running; content scrolls freely meanwhile. */
    internal var isShowing by mutableStateOf(false)
        private set

    var isVisible by mutableStateOf(false)
        private set

    private var motion: Job? = null

    internal val coordinator = SheetDragCoordinator(
        readOffset = { offset.takeUnless { it.isNaN() } ?: 0f },
        writeOffset = { moveBy(it) }
    )

    /** User input moving the sheet: it takes over from any animation in flight. */
    private fun moveBy(target: Float) {
        if (isDismissing || height <= 0f) return
        motion?.cancel()
        motion = null
        isShowing = false
        offset = target.coerceIn(0f, height)
    }

    /** Called as the sheet enters the composition. */
    internal fun reset() {
        motion?.cancel()
        motion = null
        offset = Float.NaN
        // A shared state may still hold the previous sheet's height; this one
        // must slide in from its own.
        height = 0f
        isDismissing = false
        isShowing = false
        isVisible = true
        coordinator.onGestureStart()
    }

    internal suspend fun show() {
        if (height <= 0f) return
        isShowing = true
        try {
            animateOffset(target = 0f, velocity = 0f, spec = ShowSpec)
        } finally {
            isShowing = false
        }
    }

    /**
     * Slides the sheet away and suspends until it is gone. The caller then
     * removes it from the composition — the same contract as M3's SheetState.
     */
    suspend fun hide() {
        isDismissing = true
        if (!offset.isNaN() && height > 0f) {
            animateOffset(target = height, velocity = 0f, spec = HideSpec)
        }
        isVisible = false
    }

    /** Scrim tap, back press, or the accessibility dismiss action. */
    internal fun dismiss(scope: CoroutineScope, onDismissed: () -> Unit) {
        if (isDismissing) return
        scope.launch {
            hide()
            onDismissed()
        }
    }

    /** A drag ended with the sheet [offset] px down, moving at [velocity] px/s. */
    internal fun settle(scope: CoroutineScope, velocity: Float, density: Float, onDismissed: () -> Unit) {
        val current = offset
        if (isDismissing || current.isNaN() || current <= 0f) return
        if (shouldDismissSheet(current, velocity, height, density)) {
            isDismissing = true
            // Carry the finger's speed: a hard flick leaves quicker than a slow release.
            val remaining = height - current
            val speed = maxOf(velocity, 1500f * density)
            val duration = (remaining / speed * 1000f * 1.4f).toInt().coerceIn(140, 280)
            scope.launch {
                animateOffset(height, velocity, tween(duration, easing = LinearOutSlowInEasing))
                isVisible = false
                onDismissed()
            }
        } else {
            scope.launch { animateOffset(0f, velocity, ReturnSpec) }
        }
    }

    private suspend fun animateOffset(target: Float, velocity: Float, spec: AnimationSpec<Float>) {
        val job = currentCoroutineContext()[Job]
        if (motion != job) motion?.cancel()
        motion = job
        val start = offset.takeUnless { it.isNaN() } ?: height
        try {
            animate(start, target, velocity, spec) { value, _ ->
                offset = value.coerceIn(0f, height)
            }
        } finally {
            if (motion == job) motion = null
        }
    }
}

@Composable
fun rememberAppSheetState(): AppSheetState = remember { AppSheetState() }

/**
 * A modal sheet resting on the bottom edge, in its own window so it sits above
 * everything — dialogs included — and owns the back gesture.
 *
 * Closes by dragging it down (from any part that isn't scrolled away from its
 * top), a flick, a scrim tap or back. [onDismissRequest] is called once it is
 * fully off screen. Content that should scroll needs no special modifier: any
 * vertically scrolling child cooperates with the sheet on its own.
 */
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: AppSheetState = rememberAppSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val scope = rememberCoroutineScope()

    DisposableEffect(sheetState) {
        sheetState.reset()
        onDispose { }
    }

    Dialog(
        onDismissRequest = { sheetState.dismiss(scope) { currentOnDismiss() } },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        SheetWindowSetup()
        SheetLayout(
            state = sheetState,
            onDismiss = { sheetState.dismiss(scope) { currentOnDismiss() } },
            onSettle = { velocity, density ->
                sheetState.settle(scope, velocity, density) { currentOnDismiss() }
            },
            content = content
        )
    }
}

/**
 * The dialog window draws nothing of its own: no dim (the sheet has its own
 * scrim, which follows the drag) and no window animation (the sheet slides).
 * System-bar icons follow the theme, as over the rest of the app.
 */
@Composable
private fun SheetWindowSetup() {
    val view = LocalView.current
    val isDark = AppTheme.accents.isDark
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setDimAmount(0f)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setWindowAnimations(0)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}

@Composable
private fun SheetLayout(
    state: AppSheetState,
    onDismiss: () -> Unit,
    onSettle: (velocity: Float, density: Float) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val scrimAlpha = if (AppTheme.accents.isDark) ScrimAlphaDark else ScrimAlphaLight

    // Slide in once the sheet knows how tall it is.
    LaunchedEffect(state) {
        snapshotFlow { state.height }.first { it > 0f }
        state.show()
    }

    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (state.isShowing) return Offset.Zero
                val used = state.coordinator.preScroll(available.y, source == NestedScrollSource.UserInput)
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val fromUser = source == NestedScrollSource.UserInput
                if (state.isShowing) return if (fromUser) Offset.Zero else available
                return Offset(0f, state.coordinator.postScroll(consumed.y, available.y, fromUser))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val pulledDown = state.coordinator.endScroll()
                if (!pulledDown || state.isShowing) return Velocity.Zero
                onSettle(available.y, density)
                return available
            }

            // Fling momentum never reaches the sheet (rule 3).
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }
    val dragState = rememberDraggableState { delta -> state.coordinator.drag(delta) }

    Box(Modifier.fillMaxSize()) {
        // Scrim: fades with the sheet's position, and is drawn, not recomposed, as it moves.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val offset = state.offset
                    val shown = if (offset.isNaN() || state.height <= 0f) 0f
                    else 1f - (offset / state.height).coerceIn(0f, 1f)
                    drawRect(Color.Black, alpha = scrimAlpha * shown)
                }
                .pointerInput(state) { detectTapGestures { onDismiss() } }
                .semantics {
                    contentDescription = "Close sheet"
                    onClick { onDismiss(); true }
                }
        )

        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = SheetTopGap),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier
                    .widthIn(max = SheetMaxWidth)
                    .fillMaxWidth()
                    .onSizeChanged { state.height = it.height.toFloat() }
                    .graphicsLayer {
                        val offset = state.offset
                        translationY = if (offset.isNaN()) size.height else offset
                    }
                    .clip(SheetShape)
                    .background(cs.surface)
                    // Observes every gesture without consuming anything — except
                    // while the sheet is closing, when nothing inside may react.
                    .pointerInput(state) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            state.coordinator.onGestureStart()
                            if (state.isDismissing) down.consume()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (state.isDismissing) event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .nestedScroll(connection)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        enabled = !state.isDismissing,
                        onDragStopped = { velocity -> onSettle(velocity, density) }
                    )
                    .semantics { paneTitle = "Sheet" }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            ) {
                CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
                    SheetHandle(onDismiss)
                    content()
                }
            }
        }
    }
}

@Composable
private fun SheetHandle(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp)
            .semantics { dismiss("Close sheet") { onDismiss(); true } },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(42.dp, 5.dp).clip(CircleShape).background(cs.outline))
    }
}
