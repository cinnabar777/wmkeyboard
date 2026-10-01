package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shape test that decides whether a downward stroke off a key types its
 * corner hint or is left to the glide decoder (issue #178).
 *
 * Direction, length, straightness and total duration decide. The octopus flick
 * does without the last of those; this one keeps it because a short downward
 * stroke is what a two-letter word across neighbouring rows looks like, and the
 * clock is the only thing that separates a flick from a quick "ed".
 */
class HintFlickTest {

    private val keyHeight = 100f
    private val minTravel = keyHeight * HINT_FLICK_MIN_TRAVEL_HEIGHTS

    /** A straight stroke [degrees] off straight down, sampled evenly. */
    private fun stroke(
        degrees: Float = 0f,
        length: Float = 100f,
        durationMs: Long = 90L,
        samples: Int = 6,
    ): List<GesturePoint> {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = (sin(radians) * length).toFloat()
        val dy = (cos(radians) * length).toFloat()
        return (0..samples).map { i ->
            val f = i.toFloat() / samples
            GesturePoint(200f + dx * f, 400f + dy * f, durationMs * i / samples)
        }
    }

    private fun flick(points: List<GesturePoint>) =
        hintFlick(points = points, keyHeightPx = keyHeight, minTravelPx = minTravel)

    // ---- what a flick looks like ----

    @Test
    fun `a quick straight flick down types the hint`() {
        assertTrue(flick(stroke()))
    }

    @Test
    fun `a slight lean either way is still a flick`() {
        assertTrue(flick(stroke(degrees = 20f)))
        assertTrue(flick(stroke(degrees = -20f)))
    }

    @Test
    fun `half a key height is enough`() {
        assertTrue(flick(stroke(length = minTravel)))
    }

    @Test
    fun `two samples are enough to have a direction`() {
        assertTrue(flick(stroke(samples = 1)))
    }

    @Test
    fun `a flick that decelerates at the end still counts`() {
        // Most of the travel in the first half, then a crawl: the total time is
        // what is judged, never the speed of the last samples.
        val points = listOf(
            GesturePoint(200f, 400f, 0L),
            GesturePoint(200f, 470f, 30L),
            GesturePoint(200f, 495f, 90L),
            GesturePoint(200f, 500f, 180L),
        )
        assertTrue(flick(points))
    }

    // ---- what is not one ----

    @Test
    fun `an upward stroke is never a hint flick`() {
        assertFalse(flick(stroke(degrees = 180f)))
    }

    @Test
    fun `a sideways stroke is left alone`() {
        assertFalse(flick(stroke(degrees = 60f)))
        assertFalse(flick(stroke(degrees = 90f)))
    }

    @Test
    fun `too short is a tap that drifted`() {
        assertFalse(flick(stroke(length = minTravel - 1f)))
    }

    @Test
    fun `too long is a drawn stroke`() {
        assertFalse(flick(stroke(length = keyHeight * HINT_FLICK_MAX_TRAVEL_HEIGHTS + 1f)))
    }

    @Test
    fun `a slow drag down is not a flick`() {
        assertFalse(flick(stroke(durationMs = HINT_FLICK_MAX_MS + 1)))
    }

    @Test
    fun `a wandering path that ends lower is a glide`() {
        // Out to the side and back before heading down: far more path than
        // straight line.
        val points = listOf(
            GesturePoint(200f, 400f, 0L),
            GesturePoint(260f, 420f, 20L),
            GesturePoint(140f, 440f, 40L),
            GesturePoint(200f, 500f, 60L),
        )
        assertFalse(flick(points))
    }

    @Test
    fun `a single point has no direction`() {
        assertFalse(flick(listOf(GesturePoint(200f, 400f, 0L))))
    }

    // ---- swipe to spacebar center ----

    @Test
    fun `swipe ending in spacebar center is accepted`() {
        val spaceCell = Rect(left = 100f, top = 500f, right = 400f, bottom = 600f) // center at (250, 550)
        val points = listOf(
            GesturePoint(200f, 200f, 0L),
            GesturePoint(225f, 375f, 50L),
            GesturePoint(250f, 550f, 100L),
        )
        assertTrue(hintFlickToSpacebarCenter(points, spaceCell))
    }

    @Test
    fun `swipe ending far from spacebar center is rejected`() {
        val spaceCell = Rect(left = 100f, top = 500f, right = 400f, bottom = 600f) // center at (250, 550)
        val points = listOf(
            GesturePoint(200f, 200f, 0L),
            GesturePoint(100f, 300f, 50L),
            GesturePoint(50f, 400f, 100L),
        )
        assertFalse(hintFlickToSpacebarCenter(points, spaceCell))
    }

    @Test
    fun `upward swipe is rejected for spacebar center`() {
        val spaceCell = Rect(left = 100f, top = 100f, right = 400f, bottom = 200f) // center at (250, 150)
        val points = listOf(
            GesturePoint(250f, 300f, 0L),
            GesturePoint(250f, 150f, 100L),
        )
        assertFalse(hintFlickToSpacebarCenter(points, spaceCell))
    }
}
