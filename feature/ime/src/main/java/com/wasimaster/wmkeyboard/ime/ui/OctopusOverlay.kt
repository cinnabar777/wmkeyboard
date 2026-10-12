package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OctopusPlacement
import com.wasimaster.wmkeyboard.ime.OctopusBoard
import com.wasimaster.wmkeyboard.ime.allWords
import kotlin.math.roundToInt

/**
 * One floating word and the rectangle it occupies, both in the key grid's own
 * space.
 *
 * [area] is where it draws, which on the top row reaches above the grid; [hit]
 * is the part of that a finger can actually reach, which is [area] clipped to
 * the board. The two differ only on the top row, and that is the whole of the
 * compromise there: the word is drawn where the eye expects it and tapped where
 * the grid still receives touches.
 */
@Immutable
internal data class OctopusSlot(
    val word: OctopusWord,
    val area: Rect,
    val hit: Rect,
    /** How much the word had to shrink to fit its space; 1 is full size. */
    val scale: Float = 1f,
)

/**
 * Where each word draws, given the keys' measured cells.
 *
 * Pure geometry, so the whole of the layout's behaviour — the placements, the
 * overhang, the overlap rule, the top row — is checkable without a keyboard.
 * [widthOf] measures a word at the size it will be drawn; it is a parameter for
 * the same reason, and because it is the only thing here that needs a font.
 *
 * A key that has not reported its bounds yet yields no slot rather than a slot
 * at the origin.
 */
internal fun octopusSlots(
    words: Collection<OctopusWord>,
    bounds: Map<Int, Rect>,
    placement: OctopusPlacement,
    bandHeightPx: Float,
    /**
     * How much of the band sits above the key's top edge, 0..1. Not 1: the word
     * straddles its key rather than clearing it, which is what both the Z10 and
     * the Octopus tweak drew. On the top row the band is pulled down to the
     * board's edge whatever this says (#171) — a band above the grid is
     * outside every touch the grid gets.
     */
    straddle: Float,
    gapVPx: Float,
    maxOverhangPx: Float,
    boardSize: Size,
    /** Smallest a word may be shrunk before it is dropped instead. */
    minScale: Float,
    widthOf: (String) -> Float,
): List<OctopusSlot> {
    if (bounds.isEmpty() || bandHeightPx <= 0f) return emptyList()
    val kept = ArrayList<OctopusSlot>(words.size)
    for (word in words.sortedBy { it.rank }) {
        val cell = bounds[word.keyCodePoint] ?: continue
        val full = widthOf(word.word)
        if (full <= 0f) continue
        // A word leans into the space either side of its key — the Z10 and the
        // Octopus tweak both let a long word run over its neighbours, and a
        // word that may only be as wide as one key is a word that disappears
        // the moment it has eight letters. "Downloaded" over a 36dp key is the
        // case that found this: it was simply dropped, so the board looked
        // empty exactly when the strip had most to offer.
        //
        // Past that it shrinks rather than vanishing, down to [minScale]. Only
        // a word that still will not fit at its smallest is given up on.
        val allowed = cell.width + maxOverhangPx * 2
        val scale = (allowed / full).coerceAtMost(1f)
        if (scale < minScale) continue
        val text = full * scale
        // A stacked word (#136) sits one band further from the key per tier,
        // away from the key: upward for the bands above it, downward for the
        // words drawn inside it, so a stack never grows into the key's own
        // letter.
        val lift = bandHeightPx * word.tier.coerceAtLeast(0)
        val top = when (placement) {
            // STRIP shares FLOAT's arithmetic exactly: the reserved lane moved
            // the cell down, so "the band above the cell" is already the lane.
            //
            // Held inside the board (#171). On the top row the straddle would
            // put the upper part of the band over the suggestion strip, and
            // the strip is a sibling of the grid, not something drawn over it:
            // a finger landing there is the strip's, and the strip commits its
            // own word. Hanging the band there looked like the reference
            // boards and lost the tap that is the whole point of the word, so
            // the top row's words sit a little lower than the rest instead.
            OctopusPlacement.FLOAT, OctopusPlacement.STRIP ->
                (cell.top - bandHeightPx * straddle.coerceIn(0f, 1f) - lift).coerceAtLeast(0f)
            OctopusPlacement.IN_KEY -> cell.top + gapVPx + lift
        }
        val centre = cell.center.x
        var left = centre - text / 2
        var right = centre + text / 2
        // Pulled inside the board rather than allowed to hang off it: nothing
        // out there can be read, let alone pressed.
        if (left < 0f) {
            right -= left
            left = 0f
        }
        if (right > boardSize.width) {
            left -= right - boardSize.width
            right = boardSize.width
        }
        val area = Rect(left.coerceAtLeast(0f), top, right, top + bandHeightPx)
        // Walked in rank order, so the better word keeps its place and the
        // worse one goes. Shrinking or shifting the loser would leave a word
        // that no longer points at its own key, which is the one thing the
        // whole feature must not do.
        // A little overlap is what the reference boards look like; a lot is
        // unreadable. Two words may touch, and the worse one goes only when it
        // would take a real bite out of the better one.
        if (kept.none { it.area.overlaps(area) && overlapFraction(it.area, area) > OctopusMaxOverlap }) {
            val hitTop = area.top.coerceIn(0f, boardSize.height)
            val hitBottom = area.bottom.coerceIn(0f, boardSize.height)
            kept.add(
                OctopusSlot(
                    word = word,
                    scale = scale,
                    area = area,
                    // Only the part still over the board can be tapped. Since
                    // the band is held inside the board above, that is the
                    // whole word on every row; the clamp stays as the last
                    // word on what a grid-space touch can reach.
                    hit = if (hitBottom > hitTop) {
                        Rect(area.left, hitTop, area.right, hitBottom)
                    } else {
                        Rect.Zero
                    },
                )
            )
        }
    }
    return kept
}

/**
 * The floating words, drawn over the keys (discussion #102).
 *
 * A sibling of the key grid rather than something the keys draw, for the reason
 * [AutopilotOverlay] states and [KeyVisual] enforces: a key that read the live
 * prediction would cost the whole board its per-keystroke recomposition skip.
 * The live maps are read here, in this leaf's own scope, so a key reporting its
 * position invalidates the overlay and nothing else.
 *
 * Not a [androidx.compose.ui.window.Popup], deliberately, unlike the glide
 * overlay and the key preview. Those exist only while a finger is down; this
 * one would stand for the whole session, and a window over the host app is
 * exactly what the untrusted-touch rules punish. Drawn in the grid instead, it
 * costs no window, it is hit-tested in the grid's own coordinates, and the
 * accessibility carve-out — which is the grid, not the headroom — covers all of
 * it rather than half.
 *
 * Nothing here takes a touch: plain boxes and text, so every press falls
 * through to the key underneath. The taps are resolved from [rects] by the
 * grid's own pointer loop.
 */
@Composable
internal fun BoxScope.OctopusOverlay(
    words: OctopusBoard,
    bounds: Map<Int, Rect>,
    /** One key's width, the unit a word's allowed overhang is measured in. */
    keyWidth: Float,
    /** The key grid's own size; nothing is tapped past it. */
    boardSize: Size,
    /** A glide stroke owns the board, and the picker may be asking already. */
    hidden: Boolean,
    /**
     * The word the keyboard is promising to type, drawn whole in [promiseColor]
     * rather than split into typed head and offered tail. Null on every surface
     * but the widest setting of
     * [com.wasimaster.wmkeyboard.core.settings.GlideCommitColorScope].
     */
    promised: String? = null,
    /** The strip's own primary colour, or null when the user has set none. */
    promiseColor: Color? = null,
    settings: KeyboardSettings,
    palette: KeyPalette,
    kb: KbTheme,
    rects: OctopusRects,
) {
    val octopus = settings.octopus
    // Remembered before any early way out, so a word leaving still has the
    // record of what was up a moment ago.
    val motion = remember { OctopusMotion() }
    // Words fade and bloom only while the board is the one they were on. A
    // picker opening takes them at once, as does a layout change: the cells
    // they were measured against are gone.
    val animate = kb.motion.any && !hidden && bounds.isNotEmpty() && boardSize.width > 0f
    if (hidden || words.isEmpty() || bounds.isEmpty() || boardSize.width <= 0f) {
        rects.publish(emptyList(), bounds)
        val leaving = motion.settle(emptyList(), animate, rects.recentlyTaken(), null)
        OctopusLeavingWords(motion, leaving)
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val fontSize = OctopusLabelSp * settings.fontScale * octopus.fontScale
    val style = hintTextStyle().copy(fontSize = fontSize.sp)
    // Measured, not guessed. The band used to be a multiple of the font size,
    // and the box is sized to exactly that — so a glyph taller than the guess
    // was clipped by its own container. Only the ones that hang below the
    // baseline showed it: "Job" was whole and "job" lost the tail of its j.
    // `includeFontPadding = false`, which the hint style sets, is what makes it
    // bite — it takes away the leading that would otherwise have hidden the
    // descender. Asking the font for a string with both a tall letter and a
    // deep one gives the height the glyphs actually need.
    val bandHeightPx = measurer.measure(OctopusMetricProbe, style, maxLines = 1)
        .size.height.toFloat()
    val gapVPx = with(density) { keyGapV(settings).toPx() }
    val maxOverhangPx = keyWidth * OctopusOverhangWidths
    val slots = octopusSlots(
        words = words.allWords(),
        bounds = bounds,
        placement = octopus.placement,
        bandHeightPx = bandHeightPx,
        straddle = OctopusStraddle,
        gapVPx = gapVPx,
        maxOverhangPx = maxOverhangPx,
        minScale = OctopusMinScale,
        boardSize = boardSize,
        widthOf = { measurer.measure(it, style, maxLines = 1).size.width.toFloat() },
    )
    // Stamped with the very table the rectangles were measured from, so a
    // layout change — which hands the grid a fresh one — reads as nothing until
    // this has run again against it.
    rects.publish(slots, bounds)
    val head = palette.keyText.copy(alpha = OctopusHeadAlpha)
    val labelFor = { slot: OctopusSlot -> octopusLabel(slot.word, head, kb.accent, promised, promiseColor) }
    val styleFor = { slot: OctopusSlot ->
        // Shrunk to fit where a long word would otherwise not have been drawn
        // at all.
        if (slot.scale == 1f) style else style.copy(fontSize = (fontSize * slot.scale).sp)
    }
    val taken = rects.recentlyTaken()
    val leaving = motion.settle(slots, animate, taken) { labelFor(it) to styleFor(it) }
    for ((order, slot) in slots.withIndex()) {
        // One box per key the word hangs from, so the box outlives the word on
        // it: a new word on the same key crossfades rather than popping.
        key(slot.word.keyCodePoint, slot.word.tier) {
            OctopusWordBox(
                slot = slot,
                text = labelFor(slot),
                style = styleFor(slot),
                anchor = bounds[slot.word.keyCodePoint]?.center,
                animate = animate,
                order = order,
                taken = taken,
            )
        }
    }
    OctopusLeavingWords(motion, leaving)
}

/**
 * One floating word in its band. Plain boxes and text, so every press falls
 * through to the key underneath; the motion is all in the drawing.
 *
 * With motion on it blooms out of its key the first time it appears (from the
 * key's centre, a few ms behind the better words), and when the word on its key
 * changes the old one fades out under the new one coming in.
 */
@Composable
private fun OctopusWordBox(
    slot: OctopusSlot,
    text: AnnotatedString,
    style: TextStyle,
    /** The centre of the key the word belongs to, in the grid's space. */
    anchor: Offset?,
    animate: Boolean,
    order: Int,
    /** The word a tap or a flick just took, if any; see [OctopusRects.markTaken]. */
    taken: String?,
) {
    val density = LocalDensity.current
    val area = slot.area
    // Born at nothing in the composition that brings the word in, so its first
    // frame is never drawn whole.
    val bloom = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(bloom) {
        if (bloom.value >= 1f) return@LaunchedEffect
        delay(order.coerceAtMost(OctopusStaggerSteps) * OctopusStaggerMs)
        bloom.animateTo(1f, tween(OctopusBloomMs, easing = FastOutSlowInEasing))
    }
    val face = remember { OctopusFace(text, style) }
    val epoch = face.swap(text, style, animate, taken)
    val cross = remember(epoch) { Animatable(if (face.previous != null) 0f else 1f) }
    LaunchedEffect(cross) {
        if (cross.value >= 1f) return@LaunchedEffect
        cross.animateTo(1f, tween(OctopusSwapMs))
        face.previous = null
    }
    Box(
        modifier = Modifier
            // The key underneath is what a screen reader reads; this word
            // reaches TalkBack through that key's own custom action, so a
            // node here would only offer a target it could never activate.
            .clearAndSetSemantics { }
            // Declared after the key grid, so it already draws over it —
            // but stated rather than inherited from declaration order,
            // because a word half-swallowed by the key under it is the one
            // failure this layer cannot recover from, and z-order is
            // exactly the kind of thing a later refactor moves by accident.
            .zIndex(1f)
            .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
            .size(
                width = with(density) { area.width.toDp() },
                height = with(density) { area.height.toDp() },
            )
            .graphicsLayer {
                val b = bloom.value
                if (b >= 1f) return@graphicsLayer
                alpha = b
                val s = OctopusBloomFrom + (1f - OctopusBloomFrom) * b
                scaleX = s
                scaleY = s
                if (anchor != null && area.width > 0f && area.height > 0f) {
                    transformOrigin = TransformOrigin(
                        (anchor.x - area.left) / area.width,
                        (anchor.y - area.top) / area.height,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val previous = face.previous
        if (previous != null) {
            Text(
                text = previous.first,
                style = previous.second,
                maxLines = 1,
                softWrap = false,
                // Its own width, not the new word's box, so a longer word on
                // its way out is not clipped while it goes.
                modifier = Modifier
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer { alpha = 1f - cross.value },
            )
        }
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            modifier = if (previous != null) Modifier.graphicsLayer { alpha = cross.value } else Modifier,
        )
    }
}

/**
 * The words that have just left the board, each drawn where it was for the
 * moment it takes to go: fading where it stood, or — the one a tap or a flick
 * just took — rising toward the strip it is on its way to.
 */
@Composable
private fun OctopusLeavingWords(motion: OctopusMotion, leaving: List<LeavingWord>) {
    if (leaving.isEmpty()) return
    val density = LocalDensity.current
    // Up past the top of the grid, where the strip and the field are.
    val rise = with(density) { OctopusTakenRise.toPx() }
    for (gone in leaving) {
        key(gone.id) {
            val out = remember { Animatable(0f) }
            LaunchedEffect(out) {
                out.animateTo(
                    1f,
                    if (gone.taken) {
                        tween(OctopusTakenMs, easing = FastOutLinearInEasing)
                    } else {
                        tween(OctopusLeaveMs)
                    },
                )
                motion.drop(gone)
            }
            val area = gone.area
            Box(
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .zIndex(1f)
                    .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                    .size(
                        width = with(density) { area.width.toDp() },
                        height = with(density) { area.height.toDp() },
                    )
                    .graphicsLayer {
                        val t = out.value
                        alpha = 1f - t
                        if (gone.taken) {
                            translationY = -(area.bottom + rise) * t
                            val s = 1f + OctopusTakenGrow * t
                            scaleX = s
                            scaleY = s
                        } else {
                            val s = 1f - (1f - OctopusBloomFrom) * 0.5f * t
                            scaleX = s
                            scaleY = s
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = gone.text,
                    style = gone.style,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.wrapContentWidth(unbounded = true),
                )
            }
        }
    }
}

/** A word that has left the board, drawn for as long as it takes to go. */
private class LeavingWord(
    val id: Long,
    val area: Rect,
    val text: AnnotatedString,
    val style: TextStyle,
    /** Taken by a tap or a flick, rather than simply no longer offered. */
    val taken: Boolean,
)

/**
 * What the overlay drew last time, and the words on their way out since.
 *
 * Plain fields, worked out in composition from the slots it is handed: running
 * it again with the same slots changes nothing, so a recomposition for some
 * other reason cannot double a word's exit. [version] is the one piece of
 * snapshot state, bumped when a word has finished going so the overlay drops it.
 */
private class OctopusMotion {
    private var last: List<OctopusSlot> = emptyList()
    private var lastFaces: Map<Long, Pair<AnnotatedString, TextStyle>> = emptyMap()
    private val leaving = ArrayList<LeavingWord>()
    private var nextId = 0L
    private var version by mutableIntStateOf(0)

    /**
     * The words to draw leaving, after [slots] replace what was up. With
     * [animate] off, nothing leaves: the board simply changes.
     */
    fun settle(
        slots: List<OctopusSlot>,
        animate: Boolean,
        taken: String?,
        faceOf: ((OctopusSlot) -> Pair<AnnotatedString, TextStyle>)?,
    ): List<LeavingWord> {
        // Read, so a word finishing its exit recomposes the overlay.
        check(version >= 0)
        if (!animate) {
            leaving.clear()
        } else {
            val next = slots.associateBy { anchorOf(it) }
            val now = next.keys
            for (slot in last) {
                val anchor = anchorOf(slot)
                // Still on its key: the box crossfades it. Unless it is the word
                // just taken, which goes up to the strip even when the key has
                // a new word to offer in its place.
                val wasTaken = taken != null && slot.word.word == taken
                val stays = next[anchor]?.word?.word == slot.word.word
                if (anchor in now && (!wasTaken || stays)) continue
                val face = lastFaces[anchor] ?: continue
                leaving.add(
                    LeavingWord(
                        id = nextId++,
                        area = slot.area,
                        text = face.first,
                        style = face.second,
                        taken = wasTaken,
                    ),
                )
            }
        }
        if (slots !== last) {
            last = slots
            lastFaces = if (faceOf == null) emptyMap() else slots.associate { anchorOf(it) to faceOf(it) }
        }
        return leaving.toList()
    }

    fun drop(word: LeavingWord) {
        if (leaving.remove(word)) version++
    }

    private fun anchorOf(slot: OctopusSlot): Long =
        (slot.word.keyCodePoint.toLong() shl 8) or (slot.word.tier.toLong() and 0xFF)
}

/**
 * The word a floating box is showing, and the one it showed before when that
 * changed. Compared by the word, not its colours: the typed head growing by a
 * letter is the same offer, and only a different word crossfades.
 */
private class OctopusFace(var text: AnnotatedString, var style: TextStyle) {
    var previous: Pair<AnnotatedString, TextStyle>? = null
    private var epoch = 0

    fun swap(next: AnnotatedString, nextStyle: TextStyle, animate: Boolean, taken: String?): Int {
        if (next.text != text.text) {
            // The word just taken is already flying off to the strip as a
            // leaving word of its own; fading it here too would draw it twice.
            previous = if (animate && text.text != taken) text to style else null
            epoch++
        }
        text = next
        style = nextStyle
        return epoch
    }
}

/** How much of the narrower of two words the wider may cover before one goes. */
private fun overlapFraction(a: Rect, b: Rect): Float {
    val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
    if (overlap <= 0f) return 0f
    return overlap / minOf(a.width, b.width).coerceAtLeast(1f)
}

/**
 * The word in two tones, split where what the user has typed ends.
 *
 * The head is faded rather than absent: it says "you have written this much",
 * which is what makes the tail read as an offer instead of a whole new word.
 * A correction has no head worth claiming — the point of it is that what was
 * typed was wrong — so it is drawn in the accent throughout, and that is what
 * separates a fix from a continuation at a glance.
 */
internal fun octopusLabel(
    word: OctopusWord,
    head: Color,
    accent: Color,
    promised: String? = null,
    promiseColor: Color? = null,
): AnnotatedString {
    // The promised word is drawn whole in the strip's colour rather than split.
    // The split says "this much is already yours", and none of a glided word is
    // yours until the finger comes up.
    if (promiseColor != null && word.word.equals(promised, ignoreCase = true)) {
        return buildAnnotatedString {
            withStyle(SpanStyle(color = promiseColor)) { append(word.word) }
        }
    }
    // Coerced rather than trusted: a candidate and a buffer that have drifted
    // apart must not throw in the middle of a draw.
    val split = if (word.kind == OctopusKind.CORRECTION) {
        0
    } else {
        word.typedChars.coerceIn(0, word.word.length)
    }
    return buildAnnotatedString {
        if (split > 0) {
            withStyle(SpanStyle(color = head)) { append(word.word.substring(0, split)) }
        }
        if (split < word.word.length) {
            withStyle(SpanStyle(color = accent)) { append(word.word.substring(split)) }
        }
    }
}

/**
 * Base size of a floating word, before the board's own font scale and the
 * user's octopus size. Well above the corner hint's 8.5sp: a hint is one
 * character read off a key already under the eye, and these are whole words
 * read across a board at a glance.
 */
private const val OctopusLabelSp = 12.6f

/**
 * Measured to size the band a word is drawn in: a capital, a letter with a
 * descender and one with both, so the height covers everything a word can
 * contain rather than what a multiple of the font size guesses it might.
 */
private const val OctopusMetricProbe = "Xjgy"

/**
 * How much of that band clears the key. The rest overlaps it, so the word reads
 * as belonging to the key rather than floating between two rows — and so the top
 * row, whose band would otherwise be entirely off the grid, keeps a strip of
 * itself inside the only area that receives touches.
 *
 * Half, rather than the three quarters it started at: reaching further up put
 * the word well inside the cell of the row above, where it competed with that
 * row's key for the same few pixels.
 */
private const val OctopusStraddle = 0.5f

/**
 * How far a word may lean past its own key, as a multiple of the key's width.
 * Generous on purpose: the boards this copies let a long word run right over
 * its neighbours, and the alternative is that every word of eight letters or
 * more silently disappears.
 */
private const val OctopusOverhangWidths = 1f

/** The smallest a word may be drawn before it is dropped instead. */
private const val OctopusMinScale = 0.72f

/** How much of the narrower word two of them may share before one is dropped. */
private const val OctopusMaxOverlap = 0.35f

/** The typed head, faded against the completion it introduces. */
private const val OctopusHeadAlpha = 0.55f

/** A word's bloom out of its key: how long, and from what size. */
private const val OctopusBloomMs = 140
private const val OctopusBloomFrom = 0.6f

/** How far behind the better words a word blooms, per rank, and for how many ranks. */
private const val OctopusStaggerMs = 8L
private const val OctopusStaggerSteps = 3

/** A word crossfading to the next on the same key, and one fading out. */
private const val OctopusSwapMs = 120
private const val OctopusLeaveMs = 110

/** A taken word rising toward the strip: how long, how far past the grid, how much it grows. */
private const val OctopusTakenMs = 180
private val OctopusTakenRise = 24.dp
private const val OctopusTakenGrow = 0.15f

/**
 * Which keys are carrying a word this frame, as one boolean per key.
 *
 * The obvious way to hide a corner hint under a floating word — a flag on
 * [KeyVisual] — is exactly what `KeyVisualTest` forbids: it would put a
 * per-keystroke value in the grid's `remember` key list and re-run all forty
 * key bodies on every keypress, which is the skip that class exists to buy.
 *
 * So the keys read this at *draw* time instead. A flag flipping invalidates
 * that one key's drawing; composition, measurement and layout never hear about
 * it, and in steady state exactly two keys redraw per keystroke — the one that
 * gained a word and the one that lost it.
 *
 * A flag asked for after [set] has run starts out already correct, because a
 * key composing late must not draw a hint under a word that is already there.
 */
internal class OctopusOccupancy {

    private val flags = HashMap<Int, MutableState<Boolean>>()
    private var occupied: Set<Int> = emptySet()

    fun flag(codePoint: Int): State<Boolean> =
        flags.getOrPut(codePoint) { mutableStateOf(codePoint in occupied) }

    fun set(next: Set<Int>) {
        occupied = next
        for ((codePoint, flag) in flags) {
            val wanted = codePoint in next
            // Only the ones that actually changed: writing the same value back
            // would invalidate every hinted key's drawing on every keystroke.
            if (flag.value != wanted) flag.value = wanted
        }
    }
}

/**
 * Static, so a key reading it is not an observable read and the holder never
 * changes identity — the whole point being that nothing recomposes on its
 * account.
 */
internal val LocalOctopusOccupancy = staticCompositionLocalOf { OctopusOccupancy() }
