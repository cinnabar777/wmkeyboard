package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A key's face flipping over to its new label on a language or layout switch:
 * squashed flat on the frame the new layout arrives, opening back out over
 * [KeyFlipMs], a little later the further right the key sits.
 *
 * Only a switch of [layoutId] flips anything, and only on a key whose [face]
 * changed with it. Shift and the symbol layers change the face and leave the
 * layout id alone, so they stay instant, which they must: they are on the
 * typing path. A key that reads the same in both layouts — space, enter, the
 * punctuation the two share — holds still.
 *
 * Drawn, never laid out: the squash is a draw-time scale, so the key's cell,
 * its reported rectangle and everything glide and the hit test read stay
 * exactly where they are, and the animated value is read in the draw pass, so a
 * frame of the flip redraws the key without recomposing it.
 */
internal fun Modifier.languageFlip(face: String, layoutId: String): Modifier =
    this then KeyFlipElement(face, layoutId)

private data class KeyFlipElement(val face: String, val layoutId: String) : ModifierNodeElement<KeyFlipNode>() {
    override fun create() = KeyFlipNode(face, layoutId)

    override fun update(node: KeyFlipNode) = node.update(face, layoutId)
}

private class KeyFlipNode(private var face: String, private var layoutId: String) :
    Modifier.Node(),
    DrawModifierNode,
    LayoutAwareModifierNode {

    /** 0 flat, 1 open; plain, since the node asks for its own redraws. */
    private var progress = 1f
    private var job: Job? = null

    /** Across the parent row, 0..1, for the stagger. */
    private var column = 0f

    override fun onPlaced(coordinates: LayoutCoordinates) {
        val width = coordinates.parentLayoutCoordinates?.size?.width ?: return
        if (width > 0) column = (coordinates.positionInParent().x / width).coerceIn(0f, 1f)
    }

    fun update(face: String, layoutId: String) {
        val switched = layoutId != this.layoutId
        val changed = face != this.face
        this.face = face
        this.layoutId = layoutId
        if (!switched || !changed || !isAttached) return
        job?.cancel()
        // Flat from the very frame the new label is drawn, not a frame later.
        progress = 0f
        invalidateDraw()
        val wait = (column * KeyFlipStaggerMs).toLong()
        job = coroutineScope.launch {
            delay(wait)
            animate(0f, 1f, animationSpec = tween(KeyFlipMs, easing = FastOutSlowInEasing)) { value, _ ->
                progress = value
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = progress
        if (p >= 1f) {
            drawContent()
            return
        }
        val squash = KeyFlipFloor + (1f - KeyFlipFloor) * p
        scale(scaleX = 1f, scaleY = squash, pivot = center) { this@draw.drawContent() }
    }

    override fun onDetach() {
        job = null
        progress = 1f
    }
}

/** How long a key takes to flip open, and how much later the last column starts. */
private const val KeyFlipMs = 180
private const val KeyFlipStaggerMs = 40f

/** How flat a flipping key starts: a sliver, so the row never reads as gaps. */
private const val KeyFlipFloor = 0.08f

/**
 * A tile of a panel grid sliding from where it was to where a reflow put it,
 * instead of jumping there: a strip folding away, a row of the layout changing.
 *
 * Drawn, never laid out — the tile is placed at its new cell at once, and only
 * its drawing trails behind — so a key's touch area and the cell table the
 * layer drag reads are right from the first frame. A move that comes with the
 * grid changing size is not a reflow but a resize (the panel being dragged
 * taller, the window changing), and that snaps: easing those would leave every
 * tile lagging a drag.
 */
internal fun Modifier.reflowOnMove(enabled: Boolean): Modifier =
    if (enabled) this then ReflowElement else this

private data object ReflowElement : ModifierNodeElement<ReflowNode>() {
    override fun create() = ReflowNode()

    override fun update(node: ReflowNode) = Unit
}

private class ReflowNode :
    Modifier.Node(),
    DrawModifierNode,
    LayoutAwareModifierNode {

    private var placed: Offset? = null
    private var parentSize = IntSize.Zero
    private var offset = Offset.Zero
    private var job: Job? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        val at = coordinates.positionInParent()
        val size = coordinates.parentLayoutCoordinates?.size ?: IntSize.Zero
        val before = placed
        val resized = size != parentSize
        placed = at
        parentSize = size
        if (before == null || before == at) return
        job?.cancel()
        if (resized) {
            offset = Offset.Zero
            invalidateDraw()
            return
        }
        // From wherever it is drawn right now, which mid-slide is not its old cell.
        val from = before + offset - at
        offset = from
        invalidateDraw()
        job = coroutineScope.launch {
            animate(
                typeConverter = Offset.VectorConverter,
                initialValue = from,
                targetValue = Offset.Zero,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = ReflowStiffness),
            ) { value, _ ->
                offset = value
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val shift = offset
        if (shift == Offset.Zero) {
            drawContent()
            return
        }
        translate(shift.x, shift.y) { this@draw.drawContent() }
    }

    override fun onDetach() {
        job = null
        offset = Offset.Zero
        placed = null
    }
}

/** Settles a tile in about 180 ms with no overshoot. */
private const val ReflowStiffness = 1_000f
