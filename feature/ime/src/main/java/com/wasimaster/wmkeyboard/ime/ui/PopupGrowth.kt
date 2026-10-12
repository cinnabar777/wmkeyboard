package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/** How long a card or sheet over the keyboard takes to grow in, and to fold away. */
private const val PopupEnterMs = 160
private const val PopupExitMs = 120

/** How small a card starts: close enough to its size that it reads as the same card arriving. */
private const val PopupGrowFrom = 0.9f

/** The scrim's full strength, as the cards have always drawn it. */
internal const val PopupScrimAlpha = 0.5f

/**
 * Where a card or sheet over the keyboard is on its way in or out: [progress]
 * is 0 gone and 1 fully up. While [leaving], it is the last value being shown
 * on its way out, and nothing on it may act any more: the service has already
 * closed it, and a tap on what is fading would answer a question nobody asked.
 *
 * One instance for the whole life of the host, and [leaving] a plain field
 * read at the moment of the tap: the card's gesture handlers are keyed on
 * nothing and keep the lambdas they were first given.
 *
 * [progress] reads an animation, so read it in a draw or layer lambda, never
 * in composition.
 */
@Stable
internal class PopupGrowth(
    private val animatable: Animatable<Float, AnimationVector1D>,
    private val motion: Boolean,
) {
    var leaving: Boolean = false
        internal set

    val progress: Float get() = if (motion) animatable.value else 1f
}

/** Holds the value a popup last showed, for its way out. */
private class Held<T : Any> {
    var value: T? = null
}

/**
 * Shows [content] for [value] while it is set, and for the last one it held
 * while it folds away after it goes, so a card the service has closed can
 * still play its way out. Under reduce motion it comes and goes at once.
 *
 * The growth is decided in the composition that first shows a value, at 0,
 * so the card never draws a frame at full size before it starts to grow.
 */
@Composable
internal fun <T : Any> GrowingPopupHost(value: T?, content: @Composable (T, PopupGrowth) -> Unit) {
    val motion = LocalKbTheme.current.motion.any
    val held = remember { Held<T>() }
    val progress = remember { Animatable(0f) }
    val up = value != null
    if (value != null) held.value = value
    LaunchedEffect(up, motion) {
        when {
            !motion -> progress.snapTo(if (up) 1f else 0f)
            up -> progress.animateTo(1f, tween(PopupEnterMs, easing = FastOutSlowInEasing))
            else -> progress.animateTo(0f, tween(PopupExitMs, easing = FastOutLinearInEasing))
        }
    }
    // Only the end of the way out recomposes this; the frames between are
    // read by the card's layer alone.
    val gone by remember { derivedStateOf { progress.value == 0f } }
    if (!up && (!motion || gone)) {
        held.value = null
        return
    }
    val shown = held.value ?: return
    val growth = remember(progress, motion) { PopupGrowth(progress, motion) }
    growth.leaving = !up
    content(shown, growth)
}

/**
 * The card itself growing from the strip it was opened from. The held chip's
 * own bounds never reach the card, so it grows from its top edge, the side
 * nearest the suggestion strip. A plain layer fade would draw the card into
 * an offscreen buffer the size of the card and cut its shadow off.
 */
internal fun Modifier.popupGrow(growth: PopupGrowth): Modifier = graphicsLayer {
    val p = growth.progress
    val scale = PopupGrowFrom + (1f - PopupGrowFrom) * p
    alpha = p.coerceIn(0f, 1f)
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0.5f, 0f)
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/** The scrim behind a card, fading with it. */
internal fun Modifier.popupScrim(growth: PopupGrowth): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = PopupScrimAlpha * growth.progress.coerceIn(0f, 1f)))
}
