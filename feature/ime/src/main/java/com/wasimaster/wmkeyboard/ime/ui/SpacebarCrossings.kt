package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint

/**
 * How far down the spacebar a glide has to reach before the bar reads as a
 * word break, as a share of the bar's height from its top edge (#428).
 *
 * Half: the bar's middle. A key is aimed at its centre, so a finger that means
 * the spacebar reaches its middle the way it reaches the middle of any key,
 * and a finger that meant the letter above it does not get that far.
 */
internal const val SPACEBAR_CROSSING_DEPTH = 0.5f

/**
 * Reads a glide's samples against the spacebar and says which of them end a
 * word.
 *
 * Crossing the spacebar mid-stroke ends the word so far and starts the next
 * (`GestureSettings.spaceGlideMultiWord`). The bar sits directly under the
 * bottom letter row, and a finger reaching for `n` or `m` overshoots the
 * key's lower edge as a matter of course: the touch a digitiser reports sits
 * below the fingertip the user sees, and a fast stroke carries past its
 * turn. Reading "the sample is inside the bar's cell" as a crossing split
 * about one word in five that visited the bottom row — "entendiendo" landed
 * as "entren viento" and "similar" as "Sum Millar", the second word each time
 * starting on the bottom-row key above where the finger left the bar (#428).
 * A crossing now has to reach [SPACEBAR_CROSSING_DEPTH] of the way down.
 *
 * The band above that line is decided late. Its samples are held back until
 * the finger either reaches the crossing line, when they were the walk down
 * to the bar and are dropped with the rest of it, or leaves the bar upward,
 * when they were the tail of a bottom-row letter and go back to the word in
 * the order they were drawn. A lift inside the band drops them: the word
 * ended on the letter above, and the overshoot is the end anchor's noise, not
 * evidence. Below the line is the crossing itself, and once one is registered
 * every sample until the finger is out of the bar is transit, dropped the way
 * the old reading dropped the whole visit.
 *
 * One instance per stroke.
 */
internal class SpacebarCrossings(private val depth: Float = SPACEBAR_CROSSING_DEPTH) {

    /** What a sample means for the word being drawn. */
    enum class Step {
        /** The finger reached the crossing line: the word so far is finished. */
        CROSSED,

        /** Inside the bar: nothing for the word, at least not yet. */
        HELD,

        /**
         * Off the bar: this sample belongs to the word, after whatever
         * [drain] hands back in front of it.
         */
        KEEP,
    }

    private val held = ArrayList<GesturePoint>()
    private var crossed = false

    /** Whether the last sample fed was inside the bar's cell. */
    var overBar: Boolean = false
        private set

    /**
     * Files one sample: [point] as the word's buffer stores it, [at] the same
     * touch in [bar]'s coordinate space. A null [bar] is a board with no
     * spacebar measured yet, which no stroke can cross.
     */
    fun step(point: GesturePoint, at: Offset, bar: Rect?): Step {
        val inside = bar?.contains(at) == true
        overBar = inside
        if (bar == null || !inside) {
            crossed = false
            return Step.KEEP
        }
        if (at.y >= bar.top + bar.height * depth) {
            held.clear()
            if (crossed) return Step.HELD
            crossed = true
            return Step.CROSSED
        }
        if (!crossed) held.add(point)
        return Step.HELD
    }

    /**
     * Hands the samples held back from a dip that came straight back up to
     * [word], oldest first, and forgets them. The caller adds the sample that
     * brought the finger off the bar after these, so the word stays in the
     * order it was drawn.
     */
    fun drain(word: MutableList<GesturePoint>) {
        if (held.isEmpty()) return
        word.addAll(held)
        held.clear()
    }

    /**
     * [word] as a preview should see it: with the samples still held back,
     * which will be the word's if the finger comes back up. A stroke resting
     * in the band is more likely finishing a bottom-row letter than starting
     * a crossing, and the preview should say so.
     */
    fun withHeld(word: List<GesturePoint>): List<GesturePoint> =
        if (held.isEmpty()) word else word + held
}
