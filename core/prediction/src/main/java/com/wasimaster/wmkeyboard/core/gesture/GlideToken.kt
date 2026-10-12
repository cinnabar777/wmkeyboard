package com.wasimaster.wmkeyboard.core.gesture

/**
 * One gesture that contributes letters to a word typed with both thumbs
 * (issue #638): a tap, or a swipe over one or more keys.
 *
 * Coordinates are in the same keyboard-view pixel space as [GesturePoint] and
 * [KeyCenter]; times are uptime milliseconds. The clock is what [DuetBeam]
 * orders tokens by, so a token without a real time (0) only works on its own.
 */
sealed class GlideToken {

    /** When the finger went down. */
    abstract val tStart: Long

    /** When it lifted, or for a tap the same instant as [tStart]. */
    abstract val tEnd: Long

    /**
     * A tap. Only its down position and time count: a tap's letter is decided
     * where the finger landed, and the moment it landed is what places it among
     * the other thumb's letters.
     */
    class Tap(val x: Float, val y: Float, val t: Long) : GlideToken() {
        override val tStart: Long get() = t
        override val tEnd: Long get() = t
    }

    /** A swipe: the whole finger path, down to lift. Never empty. */
    class Swipe(val points: List<GesturePoint>) : GlideToken() {
        init {
            require(points.isNotEmpty()) { "a swipe needs at least one point" }
        }

        override val tStart: Long get() = points.first().t
        override val tEnd: Long get() = points.last().t
    }
}
