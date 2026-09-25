package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * The input view's outermost frame: measures the keyboard against the same
 * height in both of the platform's measure passes.
 *
 * The IME window's own layout (a vertical `LinearLayout` with a weighted
 * fullscreen area above the input area) measures the input view twice per
 * traversal, first against the whole screen and then against what is left —
 * `AT_MOST 2400`, then `AT_MOST 1512`, measured on a 1080x2400 phone. Compose
 * reads two different constraints inside one traversal as "this view is
 * measured with multiple constraints" (`AndroidComposeView`'s
 * `wasMeasuredWithMultipleConstraints`), and from then until the next
 * traversal *any* node that needs measuring again is walked up to the root and
 * turned into a full window layout, because its size might now matter to a
 * parent that is measured more than one way. For a keyboard that is every key
 * press, every release and every strip update: a whole-window layout pass two
 * or three times per keystroke, with the whole tree measured twice in each
 * because its root constraints changed between the passes.
 *
 * So both passes are handed one height: the largest room offered in this
 * traversal. The first pass offers the whole screen and the second what is
 * left, so the second is measured against the first's room too, and Compose
 * sees one set of constraints and keeps a key press to the nodes it touched.
 *
 * It used to be the smaller of what was offered and the height the frame was
 * last laid out in. That also held the room to the window's old height, so
 * the window could never grow: a panel that asked for more room (the AI chat's
 * composer, Translate, Wikipedia's expanded article) opened into the height
 * the last panel left and squeezed its own content to fit. The first pass of
 * each traversal starts the room afresh, so a rotation or the extract view
 * that really does shrink the room is picked up at once.
 *
 * The second pass is not always the window echoing the height the first one
 * reported, though. The first offer is the whole display, status bar included,
 * and the window cannot cover the status bar, so when the keyboard asks for
 * more than the display less that bar the second pass offers less than it
 * asked for (issue #370). On a phone held sideways that is every time: the
 * key-preview band fills whatever the board leaves of the room, up to ~130dp,
 * so board and band together reached the whole display, the window was held
 * the status bar's height shorter, and the board, laid out at the first
 * pass's room, ran that far past the screen's bottom edge. A second offer
 * below what the frame just reported is therefore a [ceiling], not an echo:
 * the room drops to it in that pass, and every later traversal under the same
 * first offer starts from it, so both passes agree again from then on. A new
 * first offer (a rotation, a resized window) forgets it.
 *
 * Exact specs are passed through untouched: they cannot differ between passes
 * in a way this could smooth over.
 */
internal class StableMeasureFrame(context: Context) : FrameLayout(context) {

    /** The largest `AT_MOST` height offered since the last layout. */
    private var roomSize = 0

    /** No measure pass has run since the last layout, so the next one starts the room afresh. */
    private var freshTraversal = true

    /** The first `AT_MOST` height offered in this traversal. */
    private var firstOffer = 0

    /**
     * The most the window has been let have, learned from a second pass that
     * offered less than the frame reported, or 0 while none has; see the class
     * comment. Only good under the first offer it was learned under.
     */
    private var ceiling = 0
    private var ceilingUnder = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, stableHeightSpec(heightMeasureSpec))
        // Never report more than was offered. The content may have been
        // measured against the traversal's larger room. A pass that offered
        // less than the content took has already lowered the room to it (see
        // stableHeightSpec), so this only trims an echo, never the keys.
        setMeasuredDimension(measuredWidth, View.resolveSize(measuredHeight, heightMeasureSpec))
    }

    private fun stableHeightSpec(offered: Int): Int {
        if (MeasureSpec.getMode(offered) != MeasureSpec.AT_MOST) return offered
        val size = MeasureSpec.getSize(offered)
        roomSize = when {
            freshTraversal -> {
                firstOffer = size
                if (size != ceilingUnder) ceiling = 0
                if (ceiling > 0) minOf(size, ceiling) else size
            }
            // Held below what was just reported: the window's real limit.
            size < roomSize && size < measuredHeight -> {
                ceiling = size
                ceilingUnder = firstOffer
                size
            }
            else -> maxOf(roomSize, size)
        }
        freshTraversal = false
        return MeasureSpec.makeMeasureSpec(roomSize, MeasureSpec.AT_MOST)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        freshTraversal = true
    }
}
