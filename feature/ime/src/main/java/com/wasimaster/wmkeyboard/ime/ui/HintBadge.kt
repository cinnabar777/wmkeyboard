package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The key that fires the button this is drawn on: `Ctrl+2`, `Shift+F`, `Alt+1`.
 *
 * Accent text on a translucent accent pill. Deliberately *not* the look of
 * [focusRing], which is a 2 dp accent outline with a fill: the ring means "the
 * arrow keys are pointing here", and a badge means "this key gets you here". A
 * grid can show both at once, so they have to read differently.
 *
 * Drawn *under* its icon rather than over it. Over the glyph it was covering the
 * thing it was labelling, and the icon is what the user is scanning for.
 *
 * One rule for callers: it never changes a measured footprint. Arming the picker
 * must not resize the keyboard, because a bar that grows a row the moment you
 * double-tap Ctrl shoves the app's own text under your hands.
 *
 * Fitting a spelled-out label is handled two ways, because the parents differ.
 * A narrow but unclipped parent — a symbol chip, an emoji cell — gets an
 * unbounded measure, so the badge simply overhangs its cell. A parent that clips
 * its own contents, which every [ToolCircle] does, cannot be overhung, so
 * [hintBadgeFontSize] steps the text down instead.
 */
@Composable
internal fun HintBadge(label: String, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val size = hintBadgeFontSize(label)
    // Arming the picker brings every badge at once, so they arrive as a wave
    // from left to right rather than all popping on the same frame. Born at
    // nothing, so no badge is drawn whole for a frame before it fades in.
    // Leaving is instant: the caller drops the badge with the picker.
    val arrive = remember { Animatable(if (kb.motion.any) 0f else 1f) }
    val wave = remember { BadgeWave() }
    LaunchedEffect(arrive) {
        if (arrive.value >= 1f) return@LaunchedEffect
        // One frame, so the badge has been placed and knows where it is.
        withFrameNanos { }
        delay((wave.across * HintBadgeSpreadMs).toLong())
        arrive.animateTo(1f, tween(HintBadgeInMs, easing = FastOutSlowInEasing))
    }
    Text(
        label,
        modifier = modifier
            .onPlaced { wave.place(it) }
            .graphicsLayer {
                val p = arrive.value
                if (p >= 1f) return@graphicsLayer
                alpha = p
                val s = HintBadgeFrom + (1f - HintBadgeFrom) * p
                scaleX = s
                scaleY = s
                // The badge overhangs a narrow cell on purpose; an offscreen
                // buffer the size of the node would cut that off mid-fade.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .wrapContentWidth(unbounded = true)
            .clip(RoundedCornerShape(4.dp))
            .background(kb.accent.copy(alpha = 0.22f))
            .padding(horizontal = 2.dp),
        color = kb.accent,
        fontSize = size,
        lineHeight = size * 1.1f,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/**
 * Shrinks the longer spellings so they still fit.
 *
 * A toolbar button is 38 dp wide and clips its own contents, so `Shift+Q` at the
 * size `⇧Q` wants would lose its tail — and a truncated key name is worse than a
 * small one, because it names a key that does not exist. Stepped by length
 * rather than measured: the labels are drawn from a fixed vocabulary of at most
 * eight characters, so three tiers cover all of it without a text measure per
 * badge per frame.
 */
private fun hintBadgeFontSize(label: String) = when {
    label.length <= 3 -> 10.sp
    label.length <= 5 -> 9.sp
    else -> 8.sp
}

/**
 * Room a badge needs under an icon: the text plus its own vertical padding.
 * Callers that centre an icon in a fixed box lift it by half of this so the
 * badge has somewhere to sit, which moves the glyph without resizing the box.
 */
internal val HintBadgeHeight = 12.dp

/** Where a badge sits across the keyboard, 0 at the left edge and 1 at the right. */
private class BadgeWave {
    var across = 0f
        private set

    fun place(coordinates: LayoutCoordinates) {
        val width = coordinates.findRootCoordinates().size.width
        if (width > 0) across = (coordinates.positionInWindow().x / width).coerceIn(0f, 1f)
    }
}

/** A badge's fade and grow in, and the most the wave across the board lags it. */
private const val HintBadgeInMs = 120
private const val HintBadgeSpreadMs = 100f
private const val HintBadgeFrom = 0.6f

