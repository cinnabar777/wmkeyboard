package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord

/**
 * Where each floating word ended up, for the pointer loop to hit-test against.
 *
 * Plain fields rather than snapshot state, for the reason [GlidePickerState]
 * gives about its own rectangles: this is written by the overlay's layout and
 * read from a pointer handler at touch-report rate, and a rectangle that lived
 * in snapshot state would recompose the keyboard every time a finger moved.
 *
 * The overlay is not itself touchable — a word that took presses would swallow
 * the key underneath — so both the tap and the flick come through here.
 */
internal class OctopusRects {

    private var slots: List<OctopusSlot> = emptyList()

    /**
     * The bounds table these rectangles were measured from, held by identity.
     * A layout change hands the grid a fresh table, and until the overlay has
     * republished against it this one answers nothing — the trap [KeyRects]
     * documents, where a stale table hit-tests a finger against a board that
     * has already left the screen.
     */
    private var token: Any? = null

    fun publish(list: List<OctopusSlot>, gridToken: Any? = null) {
        slots = list
        token = gridToken
    }

    fun clear() {
        slots = emptyList()
    }

    /** The word last taken off the board by a tap or a flick, and when. */
    private var taken: String? = null
    private var takenAt = 0L

    /**
     * Notes that [word] was just taken, so the overlay can send it up toward
     * the strip as it leaves rather than fading it where it stood. Drawing only:
     * nothing here changes what [wordAt] answers.
     */
    fun markTaken(word: String) {
        taken = word
        takenAt = SystemClock.uptimeMillis()
    }

    /** The word taken a moment ago, if one was; see [markTaken]. */
    fun recentlyTaken(): String? =
        taken?.takeIf { SystemClock.uptimeMillis() - takenAt <= TakenFreshMs }

    /** Whether anything is floating at all — the gate the flick arms on. */
    fun isEmpty(): Boolean = slots.isEmpty()

    /**
     * The word drawn under [point], or null. Walks best-first, so where two
     * words overlap the likelier one wins — which is the right bias for a
     * target the user aimed at by eye.
     */
    fun wordAt(point: Offset, gridToken: Any? = null): OctopusWord? {
        if (token !== gridToken) return null
        return slots.firstOrNull { it.hit.width > 0f && it.hit.contains(point) }?.word
    }

    /** The word floating over the key anchored at [keyCodePoint], or null. */
    fun wordFor(keyCodePoint: Int, gridToken: Any? = null): OctopusWord? {
        if (token !== gridToken) return null
        return slots.firstOrNull { it.word.keyCodePoint == keyCodePoint }?.word
    }
}

/**
 * The key centre nearest [at] among the keys currently carrying a word, with
 * its anchor code point.
 *
 * Argmin rather than a radius test, so "the key I started on" has exactly one
 * answer and a finger landing between two keys picks one of them. Restricted to
 * the keys that have something to offer, because a nearer key with nothing on
 * it should not shadow the one that does — the flick's own reach test then says
 * whether the finger was close enough to claim it.
 */
internal fun nearestOctopusCentre(
    centres: Map<Int, androidx.compose.ui.geometry.Offset>,
    keys: Set<Int>,
    at: Offset,
): Pair<Int, Offset>? {
    var best: Pair<Int, Offset>? = null
    var bestDistance = Float.MAX_VALUE
    for (key in keys) {
        val centre = centres[key] ?: continue
        val dx = centre.x - at.x
        val dy = centre.y - at.y
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = key to centre
        }
    }
    return best
}

/**
 * How long a taken word stays the one the board is saying goodbye to: long
 * enough for the service to clear the words, short of the next keystroke's.
 */
private const val TakenFreshMs = 600L
