package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * The settings app's small, shared motions: rows arriving in and leaving a
 * list, one state of a block giving way to the next, and a chart growing in.
 *
 * The search screen keeps its own choreography in [searchItemMotion] and its
 * neighbours; these are the plainer versions for everywhere else, in one place
 * so two lists that do the same thing move the same way.
 *
 * Every one of them is still under Reduce motion. The modifier and transition
 * builders are not composable, so they are handed the flag; the chart one is,
 * and reads [LocalReduceMotion] itself.
 */

/** A row's fade in, once the rows around it have made room. */
private val ListFadeIn = tween<Float>(durationMillis = 180, delayMillis = 60)

/** A row's fade out: first, and quick, because it is the row that is now wrong. */
private val ListFadeOut = tween<Float>(durationMillis = 140)

/** The survivors gliding into the gap, settled without a wobble. */
private val ListPlacement = spring(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/**
 * A row's part in a list the user edits: a deleted row fades out, the rows
 * under it glide up into its place, and a new one fades in once they have
 * moved aside. A re-sort glides every row to its new place instead of
 * redrawing the list in the new order.
 *
 * Only means anything on an `items` call with a stable `key`: the list has to
 * be able to tell that the row three places down is the one that was at the
 * top a moment ago. Goes on the item's root node — the list reads it from
 * there and nowhere else.
 *
 * Not for a list that is dragged into order: the drag owns where the row is,
 * and a second animation towards the same place would lag behind the finger.
 */
internal fun LazyItemScope.listItemMotion(reduceMotion: Boolean): Modifier =
    if (reduceMotion) {
        Modifier
    } else {
        Modifier.animateItem(
            fadeInSpec = ListFadeIn,
            placementSpec = ListPlacement,
            fadeOutSpec = ListFadeOut,
        )
    }

/** [listItemMotion] for a cell of a lazy grid. */
internal fun LazyGridItemScope.gridItemMotion(reduceMotion: Boolean): Modifier =
    if (reduceMotion) {
        Modifier
    } else {
        Modifier.animateItem(
            fadeInSpec = ListFadeIn,
            placementSpec = ListPlacement,
            fadeOutSpec = ListFadeOut,
        )
    }

/**
 * One state of a block handing over to the next: a spinner to the button it was
 * waiting to show, an empty note to the list that has just loaded.
 *
 * A cross-fade, the old one leaving a little ahead of the new one arriving, so
 * the two are never both legible. [animateSize] lets the box between them
 * grow or shrink with the change rather than jumping; off, the box is as big
 * as the bigger of the two until the old one has gone — the choice for a box
 * whose size is a window's, like a dialog's, where resizing every frame is
 * dearer than one step at the end.
 *
 * Reduce motion swaps the two in one frame.
 */
internal fun AnimatedContentTransitionScope<*>.stateSwapTransform(
    reduceMotion: Boolean,
    animateSize: Boolean = true,
): ContentTransform =
    if (reduceMotion) {
        fadeIn(snap()) togetherWith fadeOut(snap()) using null
    } else {
        // Clipped, so a bigger state arriving is uncovered as the box grows
        // rather than drawn over whatever sits below or beside the box. The
        // smaller of the two always fits, so nothing that stays is cut.
        val size = if (animateSize) SizeTransform(clip = true) else null
        fadeIn(tween(durationMillis = 180, delayMillis = 40)) togetherWith
            fadeOut(tween(durationMillis = 140)) using size
    }

/** How long a chart takes to grow to its values. */
private const val GrowInMs = 700

/**
 * How far a chart has grown in, 0 to 1, for multiplying into what it draws.
 *
 * Starts at nothing and grows once, the first time [ready] is true — the first
 * time there is something to draw — and then stays grown: a later change in
 * the data moves the bars to their new heights, it does not throw them back to
 * zero and grow them again. That includes the chart that already has its data
 * on the first frame, which a value animated towards "have we got data" never
 * grows at all, since it starts where it is going.
 *
 * Read the result in the draw phase (inside the Canvas), so the growth costs a
 * redraw a frame and no recomposition. Under Reduce motion it is simply there.
 */
@Composable
internal fun rememberGrowIn(ready: Boolean): State<Float> {
    val reduceMotion = LocalReduceMotion.current
    val grown = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(ready, reduceMotion) {
        when {
            !ready -> Unit
            reduceMotion -> grown.snapTo(1f)
            else -> grown.animateTo(1f, tween(durationMillis = GrowInMs, easing = FastOutSlowInEasing))
        }
    }
    return grown.asState()
}

/** How long one entry of a short list takes to settle in. */
private const val StaggerInMs = 220

/** The beat between one entry starting and the next. */
private const val StaggerStepMs = 35

/**
 * Past this many entries the rest start together: a long list should be there
 * in a moment, not dealt out one card at a time.
 */
private const val StaggerMaxSteps = 8

/** How far below its place an entry starts. */
private val StaggerRise = 6.dp

/**
 * One entry of a short list arriving: fades in and rises into place, a beat
 * after the entry above it. For the lists that are composed once and read — a
 * preview's first few lines, a picker's choices — rather than edited, which is
 * [listItemMotion]'s job and the group's own.
 *
 * Plays once, the first time [content] is composed. Read in the draw phase, so
 * the arrival costs a redraw a frame. Under Reduce motion it is simply there.
 */
@Composable
internal fun StaggerIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val shown = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            shown.snapTo(1f)
        } else {
            shown.animateTo(
                1f,
                tween(
                    durationMillis = StaggerInMs,
                    delayMillis = StaggerStepMs * index.coerceIn(0, StaggerMaxSteps),
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }
    Box(
        modifier.graphicsLayer {
            val t = shown.value
            alpha = t
            translationY = (1f - t) * StaggerRise.toPx()
        },
    ) { content() }
}
