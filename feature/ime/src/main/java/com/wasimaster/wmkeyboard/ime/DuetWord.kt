package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideToken
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.prediction.TouchPoint

/**
 * The gestures behind a word built from more than one of them: taps and
 * swipes from both thumbs (issue #638), or typed letters a swipe finished
 * (#595). The composing buffer holds the word they were last read as; this
 * holds what the user actually did, so every new tap or swipe re-reads the
 * whole word rather than appending to a guess.
 *
 * Main thread only, like the composing buffer it shadows.
 */
internal class DuetWord {

    private val tokens = ArrayList<GlideToken>()

    /** What each tap typed, parallel to [tokens]; null for a swipe. */
    private val letters = ArrayList<String?>()

    /** The grid the swipes were drawn on, from the latest one. */
    var keys: List<KeyCenter> = emptyList()
        private set
    var keyWidthPx: Float = 0f
        private set

    /** True once a swipe is part of the word: before that, it is ordinary typing. */
    val active: Boolean get() = tokens.any { it is GlideToken.Swipe }

    val isEmpty: Boolean get() = tokens.isEmpty()

    /** An immutable copy of the gestures, for a decode off the main thread. */
    fun snapshot(): List<GlideToken> = ArrayList(tokens)

    /** The one swipe, when the word is exactly one swipe and nothing else. */
    fun loneSwipe(): List<GesturePoint>? =
        (tokens.singleOrNull() as? GlideToken.Swipe)?.points

    /**
     * Starts the word from letters already typed: one tap each, where the
     * finger landed, or the key's centre where nobody knows. False, and
     * nothing kept, when a letter is not on [grid] at all (a symbol, a letter
     * from another layer); the caller then falls back to ordinary glide.
     *
     * [touches] are in key widths, the touch model's units, and are trusted
     * only when there is one per character.
     */
    fun seed(typed: CharSequence, touches: List<TouchPoint?>?, grid: List<KeyCenter>, keyWidth: Float): Boolean {
        clear()
        if (keyWidth <= 0f) return false
        var at = 0
        while (at < typed.length) {
            val cp = Character.codePointAt(typed, at)
            val text = String(Character.toChars(cp))
            val touch = touches?.takeIf { it.size == typed.length }?.getOrNull(at)
            val tap = tapFor(cp, touch, grid, keyWidth)
            if (tap == null) {
                clear()
                return false
            }
            tokens.add(tap)
            letters.add(text)
            at += Character.charCount(cp)
        }
        keys = grid
        keyWidthPx = keyWidth
        return true
    }

    /** Adds a tap typing [text] at [touch] (key widths, with its down time). False when it cannot be placed. */
    fun addTap(text: String, touch: TouchPoint?): Boolean {
        val cp = text.codePointAt(0)
        val tap = tapFor(cp, touch, keys, keyWidthPx) ?: return false
        tokens.add(tap)
        letters.add(text)
        return true
    }

    /** Adds a swipe drawn on [grid]. Taps with no clock are placed just before it, in order. */
    fun addSwipe(points: List<GesturePoint>, grid: List<KeyCenter>, keyWidth: Float) {
        if (points.isEmpty()) return
        val start = points.first().t
        val unclocked = tokens.indices.filter { tokens[it] is GlideToken.Tap && tokens[it].tStart == 0L }
        if (start > 0L && unclocked.isNotEmpty()) {
            // Typed before the swipe began, so they come before it, each in
            // its own turn: spaced wider than the decoder's simultaneity slack.
            unclocked.forEachIndexed { order, i ->
                val tap = tokens[i] as GlideToken.Tap
                val t = start - (unclocked.size - order) * UNCLOCKED_SPACING_MS
                tokens[i] = GlideToken.Tap(tap.x, tap.y, t)
            }
        }
        tokens.add(GlideToken.Swipe(points))
        letters.add(null)
        keys = grid
        keyWidthPx = keyWidth
    }

    /** Takes back the latest gesture. False when there was none. */
    fun removeLast(): Boolean {
        if (tokens.isEmpty()) return false
        tokens.removeAt(tokens.lastIndex)
        letters.removeAt(letters.lastIndex)
        return true
    }

    /**
     * The word spelled straight off the keys: each tap's letter, and for each
     * swipe the keys it started and ended on. What a word still being built
     * shows when nothing in the dictionary even starts that way (issue #640),
     * so its gestures stay on screen, and in the buffer, until a later one
     * makes a word of them or a space types what is there.
     */
    fun literal(): String {
        val out = StringBuilder()
        tokens.forEachIndexed { i, token ->
            when (token) {
                is GlideToken.Tap -> out.append(letters[i].orEmpty())
                is GlideToken.Swipe -> {
                    val first = nearestKey(token.points.first())
                    val last = nearestKey(token.points.last())
                    first?.let { out.appendCodePoint(it) }
                    if (last != null && last != first) out.appendCodePoint(last)
                }
            }
        }
        return out.toString()
    }

    private fun nearestKey(point: GesturePoint): Int? =
        keys.minByOrNull { (it.x - point.x) * (it.x - point.x) + (it.y - point.y) * (it.y - point.y) }
            ?.codePoint?.let { Character.toLowerCase(it) }

    /** A separate copy, for a preview to add a stroke still being drawn without touching this one. */
    fun copy(): DuetWord = DuetWord().also { other ->
        other.tokens.addAll(tokens)
        other.letters.addAll(letters)
        other.keys = keys
        other.keyWidthPx = keyWidthPx
    }

    /** The letters the taps typed, in order, when no swipe is left in the word. */
    fun typedText(): String? = if (active) null else letters.joinToString("") { it.orEmpty() }

    fun clear() {
        tokens.clear()
        letters.clear()
    }

    private fun tapFor(cp: Int, touch: TouchPoint?, grid: List<KeyCenter>, keyWidth: Float): GlideToken.Tap? {
        if (touch != null && keyWidth > 0f) {
            return GlideToken.Tap(touch.x * keyWidth, touch.y * keyWidth, touch.t)
        }
        val folded = Character.toLowerCase(cp)
        val key = grid.firstOrNull { Character.toLowerCase(it.codePoint) == folded } ?: return null
        return GlideToken.Tap(key.x, key.y, touch?.t ?: 0L)
    }

    private companion object {
        /** Spacing for typed letters with no clock, wider than the decoder's order slack. */
        const val UNCLOCKED_SPACING_MS = 150L
    }
}
