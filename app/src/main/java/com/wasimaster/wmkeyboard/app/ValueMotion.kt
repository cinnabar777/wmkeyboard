package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * The settings app's motion for numbers: charts that grow in, figures that
 * count up to themselves, a set of values sliding to the next set, and the
 * tick a finished job earns. Used by Statistics, Storage and Backup.
 *
 * Every piece reads [LocalReduceMotion] and is simply at its end state under
 * it. The animated values are meant to be read in the draw phase wherever the
 * thing drawing them is a Canvas, so a frame of growth costs a redraw rather
 * than a recomposition.
 */

/**
 * Whether the charts and counters under it play their entrance.
 *
 * True the first time a screen opens; false when it is composed again for the
 * same visit — stepping back to Storage from one of its categories, or a
 * rotation — so a screen the reader has already watched fill in is simply
 * there, and only a change in the numbers moves anything.
 */
internal val LocalChartIntro = compositionLocalOf { true }

/** Provides [LocalChartIntro] for one screen's visit. */
@Composable
internal fun ChartIntroScope(content: @Composable () -> Unit) {
    var played by rememberSaveable { mutableStateOf(false) }
    // Decided once, on the composition that opens the screen: flipping it
    // later would hand the charts a different start in the middle of growing.
    val intro = remember { !played }
    LaunchedEffect(Unit) { played = true }
    CompositionLocalProvider(LocalChartIntro provides intro, content = content)
}

/**
 * A chart's entrance: one linear clock, 0 to 1, that every mark on the chart
 * reads its own eased slice of — see [at]. One animation per chart rather than
 * one per bar, so a staggered row of thirty bars is still one redraw a frame.
 *
 * Waits until the chart has been on screen ([seen], set by [chartEntrance]):
 * a chart below the fold would otherwise grow in where nobody can see it, and
 * be sitting there finished by the time the reader scrolls down to it.
 */
@Stable
internal class ChartEntrance(instant: Boolean, internal var playedKey: Any?) {
    private val clock = Animatable(if (instant) 1f else 0f)

    /** Whether the chart has been on screen yet. */
    var seen by mutableStateOf(instant)

    /** The raw clock, 0 to 1. */
    val value: Float get() = clock.value

    suspend fun play(durationMs: Int) {
        clock.animateTo(1f, tween(durationMillis = durationMs, easing = LinearEasing))
    }

    suspend fun replay(durationMs: Int) {
        clock.snapTo(0f)
        play(durationMs)
    }

    suspend fun finish() = clock.snapTo(1f)

    /**
     * How far the mark at [position] (0 for the first, 1 for the last) has
     * arrived, eased. The first [spread] of the clock is spent staggering the
     * starts; every mark then takes the same time to arrive. The curve is
     * applied per mark, so the middle of the chart does not arrive faster than
     * its ends.
     */
    fun at(position: Float, spread: Float = DEFAULT_SPREAD): Float {
        val t = clock.value
        if (t >= 1f) return 1f
        val start = spread * position.coerceIn(0f, 1f)
        val local = ((t - start) / (1f - spread)).coerceIn(0f, 1f)
        return FastOutSlowInEasing.transform(local)
    }

    /** [at] for the [index]th of [count] marks laid out left to right. */
    fun at(index: Int, count: Int, spread: Float = DEFAULT_SPREAD): Float =
        at(if (count > 1) index / (count - 1f) else 0f, spread)

    private companion object {
        const val DEFAULT_SPREAD = 0.4f
    }
}

/**
 * The entrance for one chart. Plays once, when [ready] (there is something to
 * draw) and the chart has been seen. A new [replayKey] plays it again from the
 * start — for a chart that has been handed a different picture altogether, such
 * as another keyboard's heatmap; a mere change of numbers is [rememberValuesMorph]'s.
 */
@Composable
internal fun rememberChartEntrance(
    ready: Boolean,
    durationMs: Int = CHART_ENTRANCE_MS,
    replayKey: Any? = null,
): ChartEntrance {
    val reduceMotion = LocalReduceMotion.current
    val intro = LocalChartIntro.current
    val entrance = remember { ChartEntrance(instant = reduceMotion || !intro, playedKey = replayKey) }
    LaunchedEffect(entrance, ready, entrance.seen, reduceMotion, replayKey) {
        when {
            reduceMotion -> {
                entrance.seen = true
                entrance.finish()
            }
            !ready || !entrance.seen -> Unit
            replayKey != entrance.playedKey -> {
                entrance.playedKey = replayKey
                entrance.replay(durationMs)
            }
            else -> entrance.play(durationMs)
        }
    }
    return entrance
}

/**
 * Marks [entrance] seen the first time this node has any area inside the
 * window. Dropped once it has, so a chart that has played costs nothing on a
 * scroll.
 */
internal fun Modifier.chartEntrance(entrance: ChartEntrance): Modifier =
    if (entrance.seen) {
        this
    } else {
        onGloballyPositioned { coordinates ->
            if (!entrance.seen) {
                val bounds = coordinates.boundsInWindow()
                if (bounds.width > 0f && bounds.height > 0f) entrance.seen = true
            }
        }
    }

/**
 * A whole number moving to its target: counting up from [from] the first time
 * (when the screen is making its entrance), and from wherever it is to the new
 * target each time that changes after — a total ticking down when a category
 * is cleared.
 *
 * Interpolated in doubles and finished on the exact target, so a count of
 * forty million lands on the right figure rather than on the nearest float.
 */
@Stable
internal class CountMotion(start: Long, target: Long) {
    private var from by mutableLongStateOf(start)
    private var to by mutableLongStateOf(target)
    private val fraction = Animatable(if (start == target) 1f else 0f)

    val value: Long
        get() {
            val f = fraction.value
            return if (f >= 1f) to else from + ((to - from) * f.toDouble()).roundToLong()
        }

    suspend fun moveTo(target: Long, animate: Boolean, durationMs: Int, delayMs: Int) {
        if (!animate) {
            from = target
            to = target
            fraction.snapTo(1f)
            return
        }
        if (target != to) {
            val current = value
            from = current
            to = target
            fraction.snapTo(0f)
        }
        if (fraction.value < 1f) {
            fraction.animateTo(
                1f,
                tween(durationMillis = durationMs, delayMillis = delayMs, easing = FastOutSlowInEasing),
            )
        }
    }
}

/**
 * A [CountMotion] for [target]. [start] holds the entrance back (still at
 * [from]) until it is true — pass a chart's [ChartEntrance.seen] to count up
 * only once the figure is on screen. [intro] false skips the entrance and
 * animates changes only.
 */
@Composable
internal fun rememberCount(
    target: Long,
    from: Long = 0L,
    start: Boolean = true,
    intro: Boolean = LocalChartIntro.current,
    durationMs: Int = COUNT_MS,
    delayMs: Int = 0,
): CountMotion {
    val reduceMotion = LocalReduceMotion.current
    val motion = remember {
        CountMotion(start = if (reduceMotion || !intro) target else minOf(from, target), target = target)
    }
    LaunchedEffect(target, start, reduceMotion) {
        if (!start && !reduceMotion) return@LaunchedEffect
        motion.moveTo(target, animate = !reduceMotion, durationMs = durationMs, delayMs = delayMs)
    }
    return motion
}

/**
 * A figure that counts up to [target]. The finished figure sits underneath,
 * invisible, holding the width — so the tiles beside it do not shuffle as the
 * digits grow — and the screen reader is handed the finished figure and never
 * a number on its way there.
 */
@Composable
internal fun CountingText(
    target: Long,
    format: @Composable (Long) -> String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    start: Boolean = true,
    delayMs: Int = 0,
) {
    val count = rememberCount(target, start = start, delayMs = delayMs)
    val finished = format(target)
    Box(
        modifier = modifier.clearAndSetSemantics { contentDescription = finished },
        contentAlignment = Alignment.Center,
    ) {
        Text(finished, style = style, color = color, maxLines = 1, modifier = Modifier.alpha(0f))
        Text(format(count.value), style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * A row of values sliding to the next row of values: a chart's bars moving to
 * their heights for another period instead of being thrown back to zero and
 * grown again. Values are meant to be normalised (0 to 1 of the chart).
 *
 * Two rows of different lengths — thirty days, then twelve months — are
 * matched by position along the axis: the old row is resampled to the new
 * length, so each new bar starts from the height the old chart had at that
 * place, and the newest bucket, which is the last in both, from the newest.
 */
@Stable
internal class ValuesMorph(initial: List<Float>) {
    private class Span(val from: List<Float>, val to: List<Float>)

    private var span by mutableStateOf(Span(initial, initial))
    private val fraction = Animatable(1f)

    val size: Int get() = span.to.size

    operator fun get(index: Int): Float {
        val now = span
        val end = now.to.getOrElse(index) { 0f }
        val f = fraction.value
        return if (f >= 1f) end else now.from.getOrElse(index) { end } * (1f - f) + end * f
    }

    suspend fun moveTo(target: List<Float>, animate: Boolean, durationMs: Int) {
        if (target == span.to && fraction.value >= 1f) return
        if (!animate) {
            span = Span(target, target)
            fraction.snapTo(1f)
            return
        }
        if (target != span.to) {
            val current = List(size) { get(it) }
            span = Span(resample(current, target.size), target)
            fraction.snapTo(0f)
        }
        fraction.animateTo(1f, tween(durationMillis = durationMs, easing = FastOutSlowInEasing))
    }

    private fun resample(values: List<Float>, size: Int): List<Float> = when {
        values.size == size -> values
        values.isEmpty() -> List(size) { 0f }
        values.size == 1 -> List(size) { values[0] }
        size == 1 -> listOf(values.last())
        else -> List(size) { index ->
            val position = index * (values.size - 1f) / (size - 1)
            val low = position.toInt().coerceIn(0, values.lastIndex)
            val high = min(low + 1, values.lastIndex)
            val t = position - low
            values[low] * (1f - t) + values[high] * t
        }
    }
}

/** A [ValuesMorph] following [target]. Under Reduce motion it jumps. */
@Composable
internal fun rememberValuesMorph(target: List<Float>, durationMs: Int = MORPH_MS): ValuesMorph {
    val reduceMotion = LocalReduceMotion.current
    val morph = remember { ValuesMorph(target) }
    LaunchedEffect(target, reduceMotion) {
        morph.moveTo(target, animate = !reduceMotion, durationMs = durationMs)
    }
    return morph
}

/** Where a one-off job's button is: waiting, working, or just finished well. */
internal enum class RunPhase { IDLE, RUNNING, DONE }

/**
 * The glyph at the front of a button that runs a job: a spinner while it
 * works, then a tick that draws itself on with a small pop when it has worked,
 * then nothing. Slides the label over to make room rather than shoving it.
 */
@Composable
internal fun RowScope.RunGlyph(phase: RunPhase, color: Color = LocalContentColor.current) {
    val reduceMotion = LocalReduceMotion.current
    // What the glyph showed last, so the one leaving keeps its face on the way
    // out instead of turning into nothing while it fades.
    val held = remember { arrayOf(RunPhase.RUNNING) }
    if (phase != RunPhase.IDLE) held[0] = phase
    val glyph: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (reduceMotion) {
                    RunGlyphFace(held[0], color)
                } else {
                    AnimatedContent(
                        targetState = held[0],
                        transitionSpec = {
                            (
                                fadeIn(tween(durationMillis = 120)) +
                                    scaleIn(
                                        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                                        initialScale = 0.5f,
                                    )
                                ) togetherWith fadeOut(tween(durationMillis = 100)) using null
                        },
                        contentAlignment = Alignment.Center,
                        label = "runGlyph",
                    ) { shown -> RunGlyphFace(shown, color) }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
    if (reduceMotion) {
        if (phase != RunPhase.IDLE) glyph()
    } else {
        AnimatedVisibility(
            visible = phase != RunPhase.IDLE,
            enter = fadeIn(tween(durationMillis = 150)) + expandHorizontally(tween(durationMillis = 180)),
            exit = fadeOut(tween(durationMillis = 140)) + shrinkHorizontally(tween(durationMillis = 180)),
            label = "runGlyphVisible",
        ) { glyph() }
    }
}

@Composable
private fun RunGlyphFace(phase: RunPhase, color: Color) {
    if (phase == RunPhase.DONE) {
        DrawnCheck(color, Modifier.size(18.dp))
    } else {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
    }
}

/**
 * A tick that draws itself on, short stroke then long, the first time it is
 * composed. Under Reduce motion it is simply there.
 */
@Composable
internal fun DrawnCheck(color: Color, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val drawn = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        drawn.animateTo(1f, tween(durationMillis = CHECK_DRAW_MS, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val a = Offset(size.width * 0.18f, size.height * 0.54f)
        val b = Offset(size.width * 0.41f, size.height * 0.76f)
        val c = Offset(size.width * 0.84f, size.height * 0.28f)
        val first = (b - a).getDistance()
        val second = (c - b).getDistance()
        val along = (first + second) * drawn.value
        if (along <= 0f) return@Canvas
        val path = Path().apply {
            moveTo(a.x, a.y)
            if (along <= first) {
                val end = a + (b - a) * (along / first)
                lineTo(end.x, end.y)
            } else {
                lineTo(b.x, b.y)
                val end = b + (c - b) * ((along - first) / second)
                lineTo(end.x, end.y)
            }
        }
        drawPath(
            path,
            color,
            style = Stroke(width = size.minDimension * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** A chart's entrance, all marks together. */
internal const val CHART_ENTRANCE_MS = 650

/** A figure counting up to itself. */
internal const val COUNT_MS = 650

/** A chart sliding from one set of values to the next. */
internal const val MORPH_MS = 380

/** The tick drawing itself on. */
internal const val CHECK_DRAW_MS = 320
