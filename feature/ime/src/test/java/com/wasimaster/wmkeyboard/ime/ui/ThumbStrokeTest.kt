package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #640: a two-thumb swipe starts when its finger landed, not when it first moved. */
class ThumbStrokeTest {

    @Test
    fun `a swipe begins where and when the finger landed`() {
        // Down on a key at t=1000, held still while the other thumb taps, and
        // only past the slop at t=1400: the swipe still began at 1000, so the
        // tap made meanwhile falls inside it rather than before it.
        val stroke = ThumbStroke(Offset(12f, 34f), downAt = 1_000L, owned = false)
        assertEquals(GesturePoint(12f, 34f, 1_000L), stroke.firstPoint())
    }
}
