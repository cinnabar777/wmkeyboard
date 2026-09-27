package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spacebar cursor drag's acceleration (issue #385): exact for a short
 * nudge, faster the further the drag runs one way, held at the top speed while
 * it keeps going, and slow again once it turns back.
 */
class SpaceCursorAccelerationTest {

    private fun step(travel: Float, topSpeed: Float = 4f) =
        spaceCursorStepPx(basePx = 16f, travelPx = travel, rampStartPx = 48f, rampEndPx = 240f, topSpeed = topSpeed)

    @Test
    fun `a short drag keeps the set step`() {
        assertEquals(16f, step(0f), 1e-4f)
        assertEquals(16f, step(48f), 1e-4f)
    }

    @Test
    fun `a top speed of one is the flat step at any distance`() {
        assertEquals(16f, step(10_000f, topSpeed = 1f), 1e-4f)
    }

    @Test
    fun `the step shrinks steadily across the ramp`() {
        var last = step(48f)
        for (travel in 60..240 step 12) {
            val now = step(travel.toFloat())
            assertTrue("step grew at $travel", now < last)
            last = now
        }
        // Halfway along the ramp the boost is halfway to the top speed.
        assertEquals(16f / 2.5f, step(144f), 1e-4f)
    }

    @Test
    fun `the top speed holds however far the drag goes`() {
        assertEquals(4f, step(240f), 1e-4f)
        assertEquals(4f, step(5_000f), 1e-4f)
        assertEquals(2f, step(5_000f, topSpeed = 8f), 1e-4f)
    }

    @Test
    fun `travel one way adds up`() {
        val ramp = SpaceCursorRamp(reversePx = 12f)
        repeat(10) { ramp.add(30f) }
        assertEquals(300f, ramp.travel, 1e-4f)
    }

    @Test
    fun `a small wobble back keeps the speed`() {
        val ramp = SpaceCursorRamp(reversePx = 12f)
        ramp.add(300f)
        ramp.add(-8f)
        assertEquals(300f, ramp.travel, 1e-4f)
        // Going on the original way forgets the wobble.
        ramp.add(10f)
        ramp.add(-8f)
        assertEquals(310f, ramp.travel, 1e-4f)
    }

    @Test
    fun `turning back starts a new run from the turn`() {
        val ramp = SpaceCursorRamp(reversePx = 12f)
        ramp.add(300f)
        ramp.add(-8f)
        ramp.add(-8f)
        assertEquals(16f, ramp.travel, 1e-4f)
        ramp.add(-20f)
        assertEquals(36f, ramp.travel, 1e-4f)
    }
}
