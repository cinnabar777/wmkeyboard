package com.wasimaster.wmkeyboard.core.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// The scroll rail
// ---------------------------------------------------------------------------

/**
 * The strip down the right edge of a capped list that says the list goes on.
 *
 * A dialog that stops its list at 380 dp scrolls, and nothing on the screen
 * said so: the last row it could fit read as the last row there was. The key
 * action picker holds 31 actions in 7 groups, and someone filed issue #202
 * after reading its first 3 as all of them. Compose draws no scrollbar of its
 * own, so the list draws one.
 *
 * The rail is more than a scrollbar. Its track is cut into one segment per
 * group, each as tall as its share of the list, so the strip is also a table
 * of contents: 7 segments say "7 groups" before a finger touches anything.
 * The segment under the viewport is lit, the thumb rides over it, and dragging
 * the thumb scrubs the list with the group name beside it. The bottom edge of
 * the list fades into whatever holds it while there is more below, which is
 * the part you see without looking for it.
 *
 * On open the list also runs down a few dp and back, once. A still rail is
 * furniture; a list that moves is an invitation. The peek is skipped when the
 * device has its animations turned off.
 *
 * This overload owns the scroll: give it rows, it puts them in a scrolling
 * column. A list that has to stay lazy keeps its own [LazyListState] or
 * [LazyGridState] and uses [ScrollRailBox] instead.
 *
 * Use it for a list that is capped, not for a screen: a screen already scrolls
 * a whole page, and the top bar collapsing says so.
 */
@Composable
fun ScrollRail(
    state: ScrollRailState,
    modifier: Modifier = Modifier,
    /** What the edges fade into. The dialog container, where this is used. */
    fadeColor: Color = AlertDialogDefaults.containerColor,
    colors: ScrollRailColors = scrollRailColors(),
    /** See [ScrollRailBox]: off for a list that is not newly in front of you. */
    peek: Boolean = true,
    /** See [ScrollRailBox]. */
    reserveGutter: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = requireNotNull(state.scroll.plain) {
        "ScrollRail owns the scroll, so its state comes from rememberScrollRailState() " +
            "with no lazy state. A lazy list keeps its own and uses ScrollRailBox."
    }
    ScrollRailBox(state, modifier, fadeColor, colors, peek = peek, reserveGutter = reserveGutter) { listModifier ->
        Column(modifier = listModifier.verticalScroll(scroll), content = content)
    }
}

/**
 * The rail around a list that scrolls itself: a `LazyColumn`, a
 * `LazyVerticalGrid`, or anything else holding the state the rail was made
 * with. The modifier handed to [content] carries the fade, the gutter the rail
 * sits in, and the measurement the rail needs, so it goes on the list itself.
 */
@Composable
fun ScrollRailBox(
    state: ScrollRailState,
    modifier: Modifier = Modifier,
    fadeColor: Color = AlertDialogDefaults.containerColor,
    colors: ScrollRailColors = scrollRailColors(),
    /**
     * The index down the rail: one segment per bucket, named, for a list too
     * long to read through. A word list passes [alphabetBuckets]; a list with
     * headings passes a bucket per heading. Buckets are item positions rather
     * than measured pixels, because the rows they name are mostly not laid
     * out. They win over anything [railSection] measured.
     */
    buckets: List<RailBucket> = emptyList(),
    /**
     * The opening run down the list and back. On a dialog or a menu it is the
     * thing that says the list moves. On a screen the reader has already
     * scrolled something to get here, so it is noise: pass false.
     */
    peek: Boolean = true,
    /**
     * Keep [RailGutter] clear on the right for the rail. A caller that knows
     * its list cannot scroll (a short menu, counted by rows) passes false, so
     * its rows reach the edge instead of stopping at a gutter with no rail in
     * it. Decided up front, not from [ScrollRailState.scrollable], which is
     * only known after a measure and would shift the rows a frame late.
     */
    reserveGutter: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    if (peek) ScrollRailPeek(state)
    val sections by remember(state) {
        derivedStateOf {
            state.sectionTops.entries.sortedBy { it.value }.map { RailSection(it.key, it.value) }
        }
    }
    Box(modifier) {
        content(
            Modifier
                .fillMaxWidth()
                .padding(end = if (reserveGutter) RailGutter else 0.dp)
                // Drawn outside the scroll, so the gradient sits over the
                // window rather than travelling with the content. Reading the
                // scroll position in the draw phase and not in composition
                // keeps a long list off the recomposer while it moves.
                .drawWithContent {
                    drawContent()
                    val fade = FadeHeight.toPx()
                    val travelled = state.scroll.offsetPx
                    val left = state.scroll.totalPx - state.scroll.extentPx - travelled
                    val above = (travelled / fade).coerceIn(0f, 1f)
                    val below = (left / fade).coerceIn(0f, 1f)
                    if (above > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(fadeColor.copy(alpha = above), Color.Transparent),
                                startY = 0f,
                                endY = fade,
                            ),
                            size = Size(size.width, fade),
                        )
                    }
                    if (below > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, fadeColor.copy(alpha = below)),
                                startY = size.height - fade,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height - fade),
                            size = Size(size.width, fade),
                        )
                    }
                }
                .onGloballyPositioned { state.viewportCoords = it },
        )
        if (state.scrollable) ScrollRailTrack(state, sections, buckets, colors)
    }
}

/**
 * Marks a heading inside a [ScrollRail] as the start of a group, which gives
 * the rail one segment and one name. A group with no heading needs no call:
 * the rail falls back to a single track.
 *
 * Labels are the segment's identity, so two groups with the same name in one
 * list collapse into one segment.
 */
fun Modifier.railSection(state: ScrollRailState, label: String): Modifier =
    this.onGloballyPositioned { coords ->
        val viewport = state.viewportCoords ?: return@onGloballyPositioned
        if (!coords.isAttached || !viewport.isAttached) return@onGloballyPositioned
        // Where the heading sits in the window, plus how far the window has
        // travelled: the offset from the top of the list, whatever the scroll.
        val top = viewport.localPositionOf(coords, Offset.Zero).y + state.scroll.offsetPx
        state.sectionTops[label] = top.roundToInt().coerceAtLeast(0)
    }

/** A rail over a plain scrolling column. */
@Composable
fun rememberScrollRailState(): ScrollRailState {
    val scroll = rememberScrollState()
    return remember(scroll) { ScrollRailState(PlainRailScroll(scroll)) }
}

/** A rail over a column that already has its scroll state, and drives it. */
@Composable
fun rememberScrollRailState(scroll: ScrollState): ScrollRailState =
    remember(scroll) { ScrollRailState(PlainRailScroll(scroll)) }

/** A rail over a `LazyColumn`. */
@Composable
fun rememberScrollRailState(list: LazyListState): ScrollRailState =
    remember(list) { ScrollRailState(LazyListRailScroll(list)) }

/** A rail over a `LazyVerticalGrid`. */
@Composable
fun rememberScrollRailState(grid: LazyGridState): ScrollRailState =
    remember(grid) { ScrollRailState(LazyGridRailScroll(grid)) }

/** What a [ScrollRail] knows about the list it is drawn beside. */
@Stable
class ScrollRailState internal constructor(internal val scroll: RailScroll) {
    /** The window's own node, which [railSection] measures headings against. */
    internal var viewportCoords: LayoutCoordinates? = null

    /** Group name to its offset from the top of the list, in pixels. */
    internal val sectionTops = mutableStateMapOf<String, Int>()

    /**
     * Whether the opening peek has run. A plain field rather than state: it is
     * read once by the effect that runs it, and a snapshot write here would
     * recompose the list for nothing.
     */
    internal var peeked = false

    internal val scrollable: Boolean get() = scroll.scrollable

    /**
     * The rail's stops: the index if it has one, otherwise whatever the
     * headings measured. An index wins because it knows about the rows that
     * are not laid out, and those are most of them.
     */
    internal fun sections(
        measured: List<RailSection>,
        buckets: List<RailBucket>,
    ): List<RailSection> = when {
        buckets.isEmpty() -> measured
        else -> buckets.map { RailSection(it.label, scroll.pxForIndex(it.index).toInt()) }
            .filter { it.topPx >= 0 }
    }
}

/** One group of the list, as the rail draws it. */
internal data class RailSection(val label: String, val topPx: Int)

/**
 * One stop on an index rail: the name a reader scrubs to, and the item it
 * starts at. "M" and the first word beginning with M.
 */
@Immutable
data class RailBucket(val label: String, val index: Int)

/**
 * The letters of a sorted list of words, as rail stops: one per initial
 * letter, in the order the list already has. Anything not starting with a
 * letter goes under "#", which is where a sorted list puts it anyway.
 *
 * [key] pulls the word out of a row. The list has to be sorted by that word
 * already, or the stops come out in an order no reader can use.
 */
fun <T> alphabetBuckets(items: List<T>, key: (T) -> String): List<RailBucket> {
    val stops = mutableListOf<RailBucket>()
    var last: String? = null
    items.forEachIndexed { index, item ->
        val first = key(item).firstOrNull() ?: return@forEachIndexed
        val label = if (first.isLetter()) first.uppercaseChar().toString() else "#"
        if (label != last) {
            stops += RailBucket(label, index)
            last = label
        }
    }
    return stops
}

/** The colours the rail draws with. Keyboard popups pass their own. */
@Immutable
data class ScrollRailColors(
    val track: Color,
    val live: Color,
    val thumb: Color,
    val pill: Color,
    val onPill: Color,
)

@Composable
fun scrollRailColors(
    track: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f),
    live: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
    thumb: Color = MaterialTheme.colorScheme.primary,
    pill: Color = MaterialTheme.colorScheme.secondaryContainer,
    onPill: Color = MaterialTheme.colorScheme.onSecondaryContainer,
): ScrollRailColors = ScrollRailColors(track, live, thumb, pill, onPill)

// ---------------------------------------------------------------------------
// What the rail measures
// ---------------------------------------------------------------------------

/**
 * The list under the rail, in pixels, whoever is keeping them.
 *
 * A plain `ScrollState` knows all three numbers outright. A lazy list knows
 * only the items it has laid out, so its rail works from the average of those,
 * which is exact for rows of one height and close enough for the rest. The
 * alternative is measuring ten thousand rows to draw a thumb.
 */
internal interface RailScroll {
    val state: ScrollableState

    /** The window the list is read through. */
    val extentPx: Float

    /** The whole list, window included. */
    val totalPx: Float

    /** How far down the list the window has travelled. */
    val offsetPx: Float

    /** The scroll state itself, when it is a plain one the rail may own. */
    val plain: ScrollState? get() = null

    /**
     * Where an item sits in the list, in pixels, or -1 when this kind of
     * scroll cannot say. A plain column cannot: it has rows, not items.
     */
    fun pxForIndex(index: Int): Float = -1f

    val scrollable: Boolean get() = totalPx - extentPx > 1f
}

private class PlainRailScroll(private val scroll: ScrollState) : RailScroll {
    override val state: ScrollableState get() = scroll
    override val plain: ScrollState get() = scroll
    override val extentPx: Float get() = scroll.viewportSize.toFloat()
    override val totalPx: Float get() = (scroll.viewportSize + scroll.maxValue).toFloat()
    override val offsetPx: Float get() = scroll.value.toFloat()
    override val scrollable: Boolean get() = scroll.maxValue > 0
}

private class LazyListRailScroll(private val list: LazyListState) : RailScroll {
    override val state: ScrollableState get() = list

    override val extentPx: Float
        get() = with(list.layoutInfo) { (viewportEndOffset - viewportStartOffset).toFloat() }

    override val totalPx: Float get() = itemPx * list.layoutInfo.totalItemsCount

    override val offsetPx: Float
        get() = list.firstVisibleItemIndex * itemPx + list.firstVisibleItemScrollOffset

    override val scrollable: Boolean
        get() = list.canScrollForward || list.canScrollBackward

    override fun pxForIndex(index: Int): Float = index * itemPx

    /** One row, averaged over the rows on screen, spacing included. */
    private val itemPx: Float
        get() {
            val visible = list.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return 1f
            val spread = visible.last().offset + visible.last().size - visible.first().offset
            return (spread.toFloat() / visible.size).coerceAtLeast(1f)
        }
}

private class LazyGridRailScroll(private val grid: LazyGridState) : RailScroll {
    override val state: ScrollableState get() = grid

    override val extentPx: Float
        get() = with(grid.layoutInfo) { (viewportEndOffset - viewportStartOffset).toFloat() }

    override val totalPx: Float
        get() = ceil(grid.layoutInfo.totalItemsCount / columns.toFloat()) * rowPx

    override val offsetPx: Float
        get() = (grid.firstVisibleItemIndex / columns) * rowPx + grid.firstVisibleItemScrollOffset

    override val scrollable: Boolean
        get() = grid.canScrollForward || grid.canScrollBackward

    override fun pxForIndex(index: Int): Float = (index / columns) * rowPx

    /** Cells across, read off the row the grid has actually laid out. */
    private val columns: Int
        get() = (grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1

    private val rowPx: Float
        get() {
            val visible = grid.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return 1f
            val rows = (visible.last().row - visible.first().row + 1).coerceAtLeast(1)
            val spread =
                visible.last().offset.y + visible.last().size.height - visible.first().offset.y
            return (spread.toFloat() / rows).coerceAtLeast(1f)
        }
}

// ---------------------------------------------------------------------------
// Drawing it
// ---------------------------------------------------------------------------

/**
 * The one-time peek: the list runs down a little and comes back, so that the
 * first thing the reader learns about it is that it moves.
 *
 * It waits a beat for the dialog to finish arriving, and it gives up the moment
 * the reader is ahead of it, either by scrolling first or by having animations
 * turned off on the device.
 */
@Composable
private fun ScrollRailPeek(state: ScrollRailState) {
    val context = LocalContext.current
    val animated = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
    val peekPx = with(LocalDensity.current) { PeekDistance.toPx() }
    LaunchedEffect(state, state.scrollable) {
        if (state.peeked || !animated || !state.scrollable) return@LaunchedEffect
        state.peeked = true
        delay(PeekDelayMs)
        if (state.scroll.offsetPx != 0f || state.scroll.state.isScrollInProgress) {
            return@LaunchedEffect
        }
        val room = state.scroll.totalPx - state.scroll.extentPx
        val distance = peekPx.coerceAtMost(room)
        state.scroll.state.animateScrollBy(
            distance,
            tween(PeekOutMs, easing = FastOutSlowInEasing),
        )
        delay(PeekHoldMs)
        state.scroll.state.animateScrollBy(
            -distance,
            tween(PeekBackMs, easing = FastOutSlowInEasing),
        )
    }
}

/**
 * The track, its segments, the thumb, and the name that follows a drag.
 *
 * Everything positional is read in the draw or the layout phase. The rail
 * repaints while the list moves; it does not recompose, except when the group
 * under the drag changes and the name has to be reworded.
 */
@Composable
private fun BoxScope.ScrollRailTrack(
    state: ScrollRailState,
    measured: List<RailSection>,
    buckets: List<RailBucket>,
    colors: ScrollRailColors,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var trackPx by remember { mutableFloatStateOf(0f) }
    val thumbWidth by animateDpAsState(
        if (dragging) ThumbWidthHeld else ThumbWidth,
        label = "railThumbWidth",
    )
    val thumbColor = colors.thumb.copy(alpha = if (dragging) 0.95f else 0.60f)

    Canvas(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(RailTouchWidth)
            .onSizeChanged { trackPx = it.height.toFloat() }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    val track = trackPx
                    val content = state.scroll.totalPx
                    if (track <= 0f || content <= 0f) return@rememberDraggableState
                    // A pixel of rail is a whole list's worth of pixels: the
                    // thumb keeps up with the finger rather than the list.
                    scope.launch { state.scroll.state.scrollBy(delta * content / track) }
                },
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false },
            )
            // The list beside it is already a scrollable node for TalkBack, and
            // a second one that reads "unlabelled" helps nobody.
            .clearAndSetSemantics {},
    ) {
        val content = state.scroll.totalPx
        if (content <= 0f || size.height <= 0f) return@Canvas
        val x = size.width - RailInset.toPx() - (thumbWidth.toPx() / 2f)
        val trackWidth = RailWidth.toPx()
        val gap = SegmentGap.toPx()
        val scrolled = state.scroll.offsetPx
        // Buckets are item positions, and what an item is worth in pixels is
        // only known once the list has laid some rows out — so they are turned
        // into offsets here, in the draw, rather than held as state.
        val sections = state.sections(measured, buckets)
        // The group the top of the window is in. It is the one the reader is
        // reading, so it is the one the rail lights up.
        val liveAt = sections.indexOfLast { it.topPx <= scrolled + 1f }

        fun capsule(fromY: Float, toY: Float, color: Color, width: Float) {
            val cap = width / 2f
            val from = fromY + cap
            val to = toY - cap
            if (to <= from) {
                // Too short to draw as a line. A dot still holds the place of a
                // one-row group, which is the point of the segment.
                drawCircle(color, radius = cap, center = Offset(x, (fromY + toY) / 2f))
            } else {
                drawLine(
                    color = color,
                    start = Offset(x, from),
                    end = Offset(x, to),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
        }

        if (sections.isEmpty()) {
            capsule(0f, size.height, colors.track, trackWidth)
        } else {
            sections.forEachIndexed { index, section ->
                val nextTop = sections.getOrNull(index + 1)?.topPx?.toFloat() ?: content
                val top = section.topPx / content * size.height
                val bottom = nextTop / content * size.height
                capsule(
                    fromY = top + gap / 2f,
                    toY = bottom - gap / 2f,
                    color = if (index == liveAt) colors.live else colors.track,
                    width = trackWidth,
                )
            }
        }

        val span = (state.scroll.extentPx / content).coerceIn(ThumbMinSpan, 1f)
        val thumbHeight = span * size.height
        val room = content - state.scroll.extentPx
        val at = if (room > 0f) scrolled / room else 0f
        val thumbTop = at.coerceIn(0f, 1f) * (size.height - thumbHeight)
        capsule(thumbTop, thumbTop + thumbHeight, thumbColor, thumbWidth.toPx())
    }

    val label by remember(state, measured, buckets) {
        derivedStateOf {
            state.sections(measured, buckets)
                .lastOrNull { it.topPx <= state.scroll.offsetPx + 1f }
                ?.label
        }
    }
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = dragging && label != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset {
                // Beside the thumb, and read in the layout phase so that a drag
                // moves the name without recomposing it.
                val content = state.scroll.totalPx
                val track = trackPx
                if (content <= 0f || track <= 0f) return@offset IntOffset.Zero
                val span = (state.scroll.extentPx / content).coerceIn(ThumbMinSpan, 1f)
                val thumbHeight = span * track
                val room = content - state.scroll.extentPx
                val at = if (room > 0f) state.scroll.offsetPx / room else 0f
                val centre = at.coerceIn(0f, 1f) * (track - thumbHeight) + thumbHeight / 2f
                val pill = with(density) { PillHeight.toPx() }
                IntOffset(
                    x = -with(density) { RailTouchWidth.toPx() }.roundToInt(),
                    y = (centre - pill / 2f).roundToInt()
                        .coerceIn(0, (track - pill).roundToInt().coerceAtLeast(0)),
                )
            },
    ) {
        Surface(
            color = colors.pill,
            contentColor = colors.onPill,
            shape = RoundedCornerShape(PillCorner),
            shadowElevation = PillElevation,
        ) {
            Text(
                text = label.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** The room kept on the right of the list so that no row runs under the rail. */
private val RailGutter = 14.dp
private val RailTouchWidth = 28.dp
private val RailInset = 6.dp
private val RailWidth = 3.dp
private val ThumbWidth = 5.dp
private val ThumbWidthHeld = 7.dp
private val SegmentGap = 4.dp
private val FadeHeight = 24.dp
private val PeekDistance = 28.dp
private val PillHeight = 32.dp
private val PillCorner = 50.dp
private val PillElevation = 3.dp

/** A thumb below this reads as a speck, whatever the list's length says. */
private const val ThumbMinSpan = 0.10f
private const val PeekDelayMs = 420L
private const val PeekOutMs = 260
private const val PeekHoldMs = 90L
private const val PeekBackMs = 340
