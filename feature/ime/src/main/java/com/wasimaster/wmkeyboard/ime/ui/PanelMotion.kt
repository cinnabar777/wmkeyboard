package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.ime.PanelMode
import kotlinx.coroutines.launch

/**
 * Where the thing that opened a panel was, in window coordinates.
 *
 * [label] is the tool's name inside that opener, when it had one: a panel whose
 * header carries the same name moves it from there into the header (see
 * [rememberPanelTitleMorph]). An icon has none, and the header just grows in
 * with the rest of the panel.
 */
internal class PanelOrigin(val rect: Rect, val label: Rect?)

/**
 * The last opener pressed, held until the panel it opens asks for it.
 *
 * Panels are opened from a dozen places: the toolbar's icons, but just as often
 * a tool's *name* — a toolbox pill, a toolbox cell, a chip on the strip, a key
 * on a panel's own grid — and none of those know which panel their tap will
 * end up opening, or whether it opens one at all. So nothing is passed down to
 * the panel. Every opener records its bounds here on the press ([panelOrigin]),
 * and the panel slot takes whatever is here when the panel actually changes.
 *
 * Plain fields: written from a pointer handler and read once per panel change,
 * neither of which wants a snapshot read.
 */
internal class PanelOrigins {
    private var rect: Rect? = null
    private var label: Rect? = null
    private var downAt = Long.MIN_VALUE

    /**
     * Notes an opener under a press that went down at [uptimeMs]. The first
     * opener a press reaches wins: the Initial pass runs outermost first, so a
     * toolbox cell beats the icon circle drawn inside it, and the cell is the
     * one that knows where its name is.
     */
    fun record(bounds: Rect, labelBounds: Rect?, uptimeMs: Long) {
        if (uptimeMs == downAt && rect != null) return
        rect = bounds
        label = labelBounds
        downAt = uptimeMs
    }

    /**
     * The opener behind a panel change happening now, or null when there was
     * none recent enough to be its cause — a hardware hotkey, a swipe, a panel
     * the service opened by itself. Always clears, so a press that opened
     * nothing cannot claim the next change.
     */
    fun take(nowMs: Long): PanelOrigin? {
        val bounds = rect
        val labelBounds = label
        rect = null
        label = null
        if (bounds == null || nowMs - downAt !in 0..PanelOriginFreshMs) return null
        return PanelOrigin(bounds, labelBounds)
    }
}

/**
 * The keyboard's one [PanelOrigins]. Static: it never changes identity, and a
 * press writing to it must not recompose anything.
 */
internal val LocalPanelOrigins = staticCompositionLocalOf { PanelOrigins() }

/**
 * Where an opener's tool name is drawn: filled by [panelOriginLabel] on the
 * name's own node and [onTextLayout] from its text, read by [panelOrigin] on
 * the opener when it is pressed.
 *
 * The first line's own box rather than the node's: a toolbox cell's name is a
 * full-width, two-line box whatever the name, and morphing a header title out
 * of that would start it at twice its height and off to one side.
 */
internal class PanelLabelAnchor {
    var coordinates: LayoutCoordinates? = null

    /** The first line of the name, in the text's own space; null until laid out. */
    private var line: Rect? = null

    fun onTextLayout(result: TextLayoutResult) {
        line = if (result.lineCount == 0) {
            null
        } else {
            Rect(result.getLineLeft(0), result.getLineTop(0), result.getLineRight(0), result.getLineBottom(0))
        }
    }

    fun boundsInWindow(): Rect? {
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        val first = line ?: return coords.boundsInWindow()
        return Rect(coords.localToWindow(first.topLeft), first.size)
    }
}

/** Marks the text naming the tool inside an opener carrying [anchor]. */
internal fun Modifier.panelOriginLabel(anchor: PanelLabelAnchor): Modifier =
    onPlaced { anchor.coordinates = it }

/**
 * Marks this element as something that may open a panel: a press on it records
 * its bounds, and the panel it opens grows out of them and folds back into them
 * when it closes.
 *
 * Watches the press in the Initial pass and consumes nothing, so it can sit on
 * a button with its own click, on a cell inside a grid whose pointer loop owns
 * the gesture, or on a key, without changing what the press does.
 */
internal fun Modifier.panelOrigin(label: PanelLabelAnchor? = null): Modifier =
    this then PanelOriginElement(label)

private data class PanelOriginElement(val label: PanelLabelAnchor?) : ModifierNodeElement<PanelOriginNode>() {
    override fun create() = PanelOriginNode(label)

    override fun update(node: PanelOriginNode) {
        node.label = label
    }
}

private class PanelOriginNode(var label: PanelLabelAnchor?) :
    Modifier.Node(),
    PointerInputModifierNode,
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {

    private var coordinates: LayoutCoordinates? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial || pointerEvent.type != PointerEventType.Press) return
        val own = coordinates?.takeIf { it.isAttached } ?: return
        val down = pointerEvent.changes.firstOrNull() ?: return
        val labelBounds = label?.boundsInWindow()
        currentValueOf(LocalPanelOrigins).record(own.boundsInWindow(), labelBounds, down.uptimeMillis)
    }

    override fun onCancelPointerInput() = Unit
}

/** What the panel slot knows about itself between changes. */
internal class PanelSlotState {
    /** The slot outside its own transform: where it really is. */
    var outer: LayoutCoordinates? = null

    /** The slot inside its transform, which its content is measured against. */
    var inner: LayoutCoordinates? = null

    /** Which of the two layers is recording what is on screen now. */
    var live = 0
    val recorded = BooleanArray(2)

    /** The opener of the panel up now, folded back into when it closes. */
    var openOrigin: PanelOrigin? = null
    var shown: PanelMode? = null

    /** [window] in the slot's own space, pulled inside a slot [size] big. */
    fun toLocal(window: Rect, size: Size): Rect? {
        val coords = outer?.takeIf { it.isAttached } ?: return null
        val topLeft = coords.windowToLocal(window.topLeft)
        return Rect(
            left = topLeft.x.coerceIn(0f, size.width),
            top = topLeft.y.coerceIn(0f, size.height),
            right = (topLeft.x + window.width).coerceIn(0f, size.width),
            bottom = (topLeft.y + window.height).coerceIn(0f, size.height),
        )
    }

    fun begin(panel: PanelMode, animate: Boolean, origins: PanelOrigins, layers: Array<GraphicsLayer>): PanelChange {
        val previous = shown
        shown = panel
        // Taken whatever happens, so a stale press never carries over.
        val fresh = origins.take(SystemClock.uptimeMillis())
        val closing = panel == PanelMode.NONE
        if (previous == null || previous == panel || !animate) {
            openOrigin = if (closing) null else fresh
            return PanelChange(this, panel, closing, origin = null, frozen = null, Offset.Zero, IntSize.Zero, grows = false)
        }
        // Closing folds into what opened the panel, not into the back button
        // the user just pressed.
        val origin = if (closing) openOrigin else fresh
        openOrigin = if (closing) null else fresh
        val was = live
        val frozen = layers[was].takeIf { recorded[was] }
        live = 1 - was
        recorded[live] = false
        val coords = outer?.takeIf { it.isAttached }
        return PanelChange(
            slot = this,
            panel = panel,
            closing = closing,
            origin = origin,
            frozen = frozen,
            frozenAt = coords?.windowToLocal(Offset.Zero)?.let { -it } ?: Offset.Zero,
            frozenSize = coords?.size ?: IntSize.Zero,
            grows = !closing,
        )
    }
}

/**
 * One change of panel and its two animations: [enter] grows the new content
 * out of [origin], [leave] retires the picture of what was there before.
 *
 * Both start where they must be on the very first frame — the content at
 * nothing, the picture at full strength — because they are born in the
 * composition that brings the change in.
 */
internal class PanelChange(
    val slot: PanelSlotState,
    val panel: PanelMode,
    val closing: Boolean,
    val origin: PanelOrigin?,
    /** The last picture of what was on screen, or null when nothing animates. */
    val frozen: GraphicsLayer?,
    /** Where that picture was, in window space, and how big. */
    val frozenAt: Offset,
    val frozenSize: IntSize,
    val grows: Boolean,
) {
    val enter = Animatable(if (grows) 0f else 1f)
    val leave = Animatable(if (frozen != null) 0f else 1f)

    /** Set once the picture has been emptied, so it pins nothing in memory. */
    var released = false
}

/**
 * The panel change on screen, for the header title that morphs with it. Null
 * outside the panel slot.
 */
internal val LocalPanelChange = staticCompositionLocalOf<PanelChange?> { null }

/**
 * The body's panel slot: the keys or the open panel, and the motion between them.
 *
 * A panel grows out of whatever opened it ([panelOrigin]) — scaled up from a
 * little under full size around that point, revealed from the opener's own
 * rectangle outward, and faded in — and on close it folds back into the same
 * place. With no opener on record (a hotkey, a swipe) it fades in with a slight
 * scale from the toolbar's centre instead.
 *
 * What was there before is drawn from a picture rather than kept composed. The
 * slot records its own content into one of two [GraphicsLayer]s on every draw,
 * and a change swaps which one records; the other keeps the last frame of the
 * old content. Opening, that picture is drawn under the growing panel and fades
 * as it arrives, so the board never shows empty for a frame. Closing, it is
 * drawn over the keys and folds away. Either way the old content has already
 * left the composition: the keys under a closing panel are live, at full
 * strength, and take the very next tap, because a picture takes no touches.
 *
 * Nothing ever transforms the keys themselves ([PanelMode.NONE] never grows):
 * their rectangles are read for glide and hit testing, and a scaled board would
 * hand those a board that is not where the finger is.
 *
 * A Column, so every panel keeps the ColumnScope it was written against.
 * ModulateAlpha applies the fade to what is drawn rather than through an
 * offscreen buffer the size of the slot, which would clip anything a panel
 * draws past its own edge for the length of the fade.
 */
@Composable
internal fun PanelMotionSlot(
    panel: PanelMode,
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val origins = LocalPanelOrigins.current
    val slot = remember { PanelSlotState() }
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val change = remember(panel) { slot.begin(panel, animate, origins, arrayOf(first, second)) }
    LaunchedEffect(change) {
        if (change.leave.value < 1f) {
            launch {
                change.leave.animateTo(
                    1f,
                    if (change.closing) {
                        tween(PanelCloseMs, easing = EmphasizedAccelerate)
                    } else {
                        tween(PanelUnderFadeMs, easing = LinearEasing)
                    },
                )
            }
        }
        if (change.enter.value < 1f) {
            change.enter.animateTo(1f, tween(PanelOpenMs, easing = EmphasizedDecelerate))
        }
    }
    Column(
        modifier = modifier
            .onPlaced { slot.outer = it }
            .drawWithContent {
                val frozen = change.frozen
                val leave = if (frozen != null) change.leave.value else 1f
                if (frozen != null && leave >= 1f && !change.released) {
                    // Done with: emptied, so the old panel's drawing (and any
                    // bitmap in it) is not held until the next change.
                    change.released = true
                    frozen.record { }
                }
                if (frozen != null && leave < 1f && !change.closing) drawFrozen(change, frozen, leave)
                drawContent()
                if (frozen != null && leave < 1f && change.closing) drawFrozen(change, frozen, leave)
            }
            .graphicsLayer {
                val p = change.enter.value
                if (p >= 1f) return@graphicsLayer
                compositingStrategy = CompositingStrategy.ModulateAlpha
                val from = change.origin?.let { slot.toLocal(it.rect, size) }
                val pivot = from?.center ?: Offset(size.width / 2f, 0f)
                if (size.width > 0f && size.height > 0f) {
                    transformOrigin = TransformOrigin(pivot.x / size.width, pivot.y / size.height)
                }
                val s = mix(if (from != null) PanelGrowScale else PanelFallbackScale, 1f, p)
                scaleX = s
                scaleY = s
                alpha = p
                if (from != null) {
                    clip = true
                    shape = RevealShape(from, p, PanelRevealCorner.toPx())
                }
            }
            .onPlaced { slot.inner = it }
            .drawWithContent {
                if (!animate) {
                    // Nothing recorded: a picture kept from before the switch
                    // was turned off would be months stale by the next change.
                    slot.recorded[slot.live] = false
                    drawContent()
                    return@drawWithContent
                }
                val layer = if (slot.live == 0) first else second
                layer.record { this@drawWithContent.drawContent() }
                slot.recorded[slot.live] = true
                layer.alpha = 1f
                drawLayer(layer)
            },
    ) {
        CompositionLocalProvider(LocalPanelChange provides change) {
            key(panel) { content() }
        }
    }
}

/**
 * The picture of the old content, [t] of the way out: under a growing panel it
 * only fades; over the keys on a close it folds back into the panel's opener.
 * Drawn where it was in the window, which is not where the slot is now when the
 * rows above the slot came or went with the panel.
 */
private fun DrawScope.drawFrozen(change: PanelChange, frozen: GraphicsLayer, t: Float) {
    val now = change.slot.outer?.takeIf { it.isAttached }?.windowToLocal(Offset.Zero)?.let { -it }
        ?: change.frozenAt
    val shift = change.frozenAt - now
    frozen.alpha = 1f - t
    translate(shift.x, shift.y) {
        if (!change.closing) {
            drawLayer(frozen)
            return@translate
        }
        val size = Size(change.frozenSize.width.toFloat(), change.frozenSize.height.toFloat())
        val from = change.origin?.rect?.let { window ->
            val local = window.translate(-change.frozenAt)
            Rect(
                left = local.left.coerceIn(0f, size.width),
                top = local.top.coerceIn(0f, size.height),
                right = local.right.coerceIn(0f, size.width),
                bottom = local.bottom.coerceIn(0f, size.height),
            )
        }
        if (from == null) {
            val s = mix(1f, PanelFallbackScale, t)
            scale(s, s, Offset(size.width / 2f, 0f)) { drawLayer(frozen) }
            return@translate
        }
        val s = mix(1f, PanelGrowScale, t)
        val clip = lerp(Rect(Offset.Zero, size), from, t)
        scale(s, s, from.center) {
            clipRect(clip.left, clip.top, clip.right, clip.bottom) { drawLayer(frozen) }
        }
    }
}

/**
 * The rounded rectangle a growing panel is revealed through: the opener's own
 * rectangle at [progress] 0, the whole slot at 1.
 */
private data class RevealShape(val from: Rect, val progress: Float, val cornerPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rect = lerp(from, Rect(Offset.Zero, size), progress)
        return Outline.Rounded(RoundRect(rect, CornerRadius(cornerPx * (1f - progress))))
    }
}

/**
 * The header title of a panel opened from its own name — a toolbox pill, a
 * cell — moving from where that name was into the header as the panel grows,
 * so the word the user tapped is the word they land on. Nothing when the opener
 * was an icon or there was none, and nothing under reduce motion, where the
 * slot never grows.
 *
 * The slot is scaling around the opener while this runs, so the title's own
 * move undoes that scale: the two together put the title on a straight path
 * from the label to the header, at a size going from the label's to its own.
 */
@Composable
internal fun rememberPanelTitleMorph(): Modifier {
    val change = LocalPanelChange.current ?: return Modifier
    val origin = change.origin ?: return Modifier
    val label = origin.label
    if (label == null || !change.grows) return Modifier
    return remember(change) {
        val title = PanelLabelAnchor()
        Modifier
            .onPlaced { title.coordinates = it }
            .graphicsLayer {
                val p = change.enter.value
                if (p >= 1f) return@graphicsLayer
                val own = title.coordinates?.takeIf { it.isAttached } ?: return@graphicsLayer
                val inner = change.slot.inner?.takeIf { it.isAttached } ?: return@graphicsLayer
                val slotSize = Size(inner.size.width.toFloat(), inner.size.height.toFloat())
                val box = inner.localBoundingBoxOf(own, clipBounds = false)
                if (box.height <= 0f) return@graphicsLayer
                val from = change.slot.toLocal(label, slotSize) ?: return@graphicsLayer
                // The same pivot the slot scales around.
                val pivot = change.slot.toLocal(origin.rect, slotSize)?.center ?: return@graphicsLayer
                val s = mix(PanelGrowScale, 1f, p)
                val target = lerp(from.center, box.center, p)
                val local = pivot + (target - pivot) / s
                translationX = local.x - box.center.x
                translationY = local.y - box.center.y
                val k = mix(from.height, box.height, p) / (s * box.height)
                scaleX = k
                scaleY = k
            }
    }
}

private fun mix(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/** Material's emphasized easings: in fast and settle, or leave slowly then go. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** How long a panel takes to grow in, and to fold away. */
private const val PanelOpenMs = 190
private const val PanelCloseMs = 140

/** How long the picture of the board under an arriving panel takes to go. */
private const val PanelUnderFadeMs = 120

/** The size a panel grows from around its opener, and from with no opener. */
private const val PanelGrowScale = 0.85f
private const val PanelFallbackScale = 0.96f

/** The corner the reveal starts with, straightening as it fills the slot. */
private val PanelRevealCorner = 16.dp

/**
 * How long a press on an opener stays the cause of the next panel change: past
 * a long press and its lift, short of anything that happens later.
 */
private const val PanelOriginFreshMs = 1_500L
