package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.compareTo

@Composable
fun rememberPulseAnimation(
    trigger: Any?,
    durationMillis: Int = 300
): Float {
    var animationTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(trigger) {
        if (trigger != null) {
            animationTrigger++
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (animationTrigger > 0) 1f else 1.1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pulse"
    )

    return scale
}

@Composable
fun rememberShakeAnimation(
    trigger: Any?,
    durationMillis: Int = 400
): Float {
    var animationTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(trigger) {
        if (trigger != null) {
            animationTrigger++
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shake")
    val shake by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (animationTrigger > 0) 10f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(50, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shake"
    )


    return if (animationTrigger > 0) shake else 0f
}

fun Modifier.shakeEffect(offsetX: Float): Modifier {
    return this.graphicsLayer {
        translationX = offsetX
    }
}

fun Modifier.pulseEffect(scale: Float): Modifier {
    return this.scale(scale)
}
