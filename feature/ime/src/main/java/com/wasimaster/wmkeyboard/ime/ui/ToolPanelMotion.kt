package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Small motion pieces the tool panels share: a cross-fade between states, the
 * password scramble, a touch blocker for content on its way out, and the
 * typing test's personal-best burst. Every one of them takes its gate as a
 * parameter and, with it off, draws the end state at once — no animation, no
 * delay — so callers pass `LocalKbTheme.current.motion.any` and nothing else.
 */

/**
 * [content] for [target], cross-faded into the next one when [contentKey]
 * changes. A change that keeps the key (a player's position tick, a device's
 * battery) re-renders the visible content in place with the new value rather
 * than fading it into itself.
 *
 * The outgoing state leaves a little ahead of the incoming one arriving, so
 * the two are never both legible. With [animate] off the content is drawn
 * straight, with no AnimatedContent around it at all.
 */
@Composable
internal fun <T> MotionCrossfade(
    target: T,
    animate: Boolean,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    contentKey: (T) -> Any? = { it },
    label: String = "crossfade",
    content: @Composable (T) -> Unit,
) {
    if (!animate) {
        Box(modifier, contentAlignment = contentAlignment) { content(target) }
        return
    }
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(CROSSFADE_IN_MS, delayMillis = CROSSFADE_IN_DELAY_MS)) togetherWith
                fadeOut(tween(CROSSFADE_OUT_MS)) using null
        },
        contentAlignment = contentAlignment,
        contentKey = contentKey,
        label = label,
    ) { state -> content(state) }
}

/**
 * Swallows every touch inside while [blocked], before any child sees it.
 *
 * For a state that is cross-fading out: AnimatedContent keeps the old content
 * composed, and clickable, until its fade ends, and a second tap on a pairing
 * card's Accept that has already been accepted is a second request on the
 * wire. Children that ask for unconsumed downs only (every clickable) see
 * nothing; a raw pointer loop that takes consumed ones still does.
 */
internal fun Modifier.blockTouches(blocked: Boolean): Modifier =
    if (!blocked) {
        this
    } else {
        this.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    }

/**
 * [content] for [target], except that for a moment after [target] changes it
 * is handed a scramble of it instead: every character a random glyph, settling
 * on the real one left to right, the whole run over in [durationMs].
 *
 * The first value shown is not scrambled, only a change from it — a panel
 * opening on a password should read at once. The scramble is display only:
 * whoever acts on the value (copy, insert) holds [target] itself, so a tap
 * mid-scramble copies the real thing. Same length as [target] throughout, so
 * a monospaced line never reflows.
 *
 * A function of its own so the frame-by-frame reads recompose this scope and
 * not the caller's whole panel.
 */
@Composable
internal fun ScrambledText(
    target: String,
    animate: Boolean,
    durationMs: Int = SCRAMBLE_MS,
    content: @Composable (String) -> Unit,
) {
    // The value already shown (or on its way in); a change away from it is a
    // regeneration. Decided in the composition that brings the new value, so
    // its first frame is already scrambled rather than flashing the result.
    var shownFor by remember { mutableStateOf(target) }
    val progress = remember(target) {
        Animatable(if (animate && target != shownFor && target.isNotEmpty()) 0f else 1f)
    }
    LaunchedEffect(target) {
        shownFor = target
        if (progress.value < 1f) progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
    }
    val p = progress.value
    content(if (p >= 1f) target else scramble(target, p))
}

/** [target] with everything past the settled prefix swapped for a random glyph. */
private fun scramble(target: String, progress: Float): String {
    val settled = (progress * target.length).toInt()
    return buildString(target.length) {
        for ((i, c) in target.withIndex()) {
            // Spaces stay put so a passphrase keeps its word shapes; a
            // surrogate half is never swapped alone into a broken pair.
            val keep = i < settled || c.isWhitespace() || c.isSurrogate()
            append(if (keep) c else SCRAMBLE_GLYPHS[Random.nextInt(SCRAMBLE_GLYPHS.length)])
        }
    }
}

/**
 * A short burst of paper from [origin] — the typing test's personal best.
 *
 * Its own small `Canvas` rather than the key grid's `ParticleField`: that one
 * is built around pre-rasterized theme glyphs and the grid's frame loop, for a
 * burst per key press; this is one throw, once, over a panel. Pieces fan up
 * and out, gravity brings them down, and they fade over the last stretch. The
 * clock is read in the draw alone, so the burst never recomposes anything.
 *
 * Plays once per [key]. Draws nothing and holds no touch: lay it over the
 * content with `matchParentSize`. [origin] is read at draw time, in this
 * composable's own coordinates; [Offset.Unspecified] falls back to the top
 * right, where a results header keeps its badge. Callers leave it out
 * entirely when motion is off.
 */
@Composable
internal fun ConfettiBurst(
    key: Any?,
    accent: Color,
    modifier: Modifier = Modifier,
    origin: () -> Offset = { Offset.Unspecified },
) {
    val clock = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { clock.animateTo(1f, tween(BURST_MS, easing = LinearEasing)) }
    val pieces = remember(key) { List(BURST_PIECES) { BurstPiece(Random) } }
    val density = LocalDensity.current
    val pieceSize = with(density) { 7.dp.toPx() }
    val pxPerDp = density.density
    val colors = remember(accent) { listOf(accent) + BURST_PALETTE }
    Canvas(modifier) {
        val p = clock.value
        if (p >= 1f) return@Canvas
        val from = origin().takeIf { it.isSpecified } ?: Offset(size.width * 0.85f, 24f * pxPerDp)
        val t = p * BURST_MS / 1000f
        // Fully opaque for most of the flight, out over the last third.
        val alpha = ((1f - p) * 3f).coerceIn(0f, 1f)
        for (piece in pieces) {
            val x = from.x + piece.vx * pxPerDp * t
            val y = from.y + piece.vy * pxPerDp * t + 0.5f * BURST_GRAVITY * pxPerDp * t * t
            val w = pieceSize * piece.scale
            rotate(degrees = piece.spin * t, pivot = Offset(x, y)) {
                drawRect(
                    color = colors[piece.colorIndex % colors.size],
                    topLeft = Offset(x - w / 2, y - w / 4),
                    size = Size(w, w / 2),
                    alpha = alpha,
                )
            }
        }
    }
}

/** One piece of the burst: its throw, its spin, its look. Velocities in dp/s. */
private class BurstPiece(random: Random) {
    // A fan from up-left through up to up-right, never straight down.
    private val angle = Math.toRadians(-160.0 + random.nextDouble() * 140.0)
    // Modest, because the badge sits near the top of the panel: a piece
    // peaks under 40 dp above it rather than vanishing off the edge.
    private val speed = 120f + random.nextFloat() * 200f
    val vx = (cos(angle) * speed).toFloat()
    val vy = (sin(angle) * speed).toFloat()
    val spin = (random.nextFloat() - 0.5f) * 1440f
    val scale = 0.7f + random.nextFloat() * 0.7f
    val colorIndex = random.nextInt(BURST_PALETTE.size + 1)
}

/** Festive and theme-independent, beside the theme's accent. */
private val BURST_PALETTE = listOf(
    Color(0xFFEF5350), Color(0xFFFFA726), Color(0xFFFFEE58),
    Color(0xFF66BB6A), Color(0xFF42A5F5), Color(0xFFAB47BC),
)

private const val CROSSFADE_IN_MS = 180
private const val CROSSFADE_IN_DELAY_MS = 40
private const val CROSSFADE_OUT_MS = 140

/** Whole scramble, first glyph to last settled. */
internal const val SCRAMBLE_MS = 250

/** What a scrambling character shows: password-ish, all one width in monospace. */
private const val SCRAMBLE_GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789#\$%&*+=?@"

private const val BURST_MS = 700
private const val BURST_PIECES = 40

/** dp/s², strong enough that a piece thrown up is back past its start inside the burst. */
private const val BURST_GRAVITY = 1400f
