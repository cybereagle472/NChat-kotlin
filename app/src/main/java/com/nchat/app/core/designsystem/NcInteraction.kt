package com.nchat.app.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Mirrors the web reference interaction model: `transition: all .2s` plus
 * `:active { transform: scale(0.98) }`. Rows never show a Material ripple in
 * nchat-friends, and the ripple's translucent overlay is what smeared bright
 * bands across the glass cards on device, so tapping is expressed purely with
 * a spring-driven scale.
 */
@Composable
fun rememberPressSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * Applies the reference press scale. The scale is read inside the graphics
 * layer block, so the animation runs on the draw phase without recomposing the
 * row's content on every frame.
 */
@Composable
fun Modifier.pressFeedback(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.975f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
