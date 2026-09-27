package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #367: shift held down while another finger types. What each tapped key
 * commits is decided by [ShiftHold.route]; the finger tracking around it lives
 * in the chord loop and needs a device.
 */
class ShiftHoldTest {

    private val shift = Key("⇧", action = KeyAction.Shift)

    @Test
    fun `nothing held passes every key through untouched`() {
        val hold = ShiftHold()
        val a = Key("a")
        assertSame(a, hold.route(a))
        assertSame(shift, hold.route(shift))
        assertFalse(hold.typed)
    }

    @Test
    fun `every letter under a held shift is a capital`() {
        val hold = ShiftHold().apply { begin() }
        assertEquals("A", hold.route(Key("a"))!!.output)
        assertEquals("B", hold.route(Key("b"))!!.output)
        // The text route, so the word keeps its composing buffer.
        assertEquals(KeyAction.Text, hold.route(Key("c"))!!.action)
        assertTrue(hold.typed)
    }

    @Test
    fun `space and enter stay themselves but still spend the hold`() {
        val hold = ShiftHold().apply { begin() }
        val space = Key(" ", action = KeyAction.Space)
        assertSame(space, hold.route(space))
        // Lifting shift after "A B" must not arm it for the next letter.
        assertTrue(hold.typed)
    }

    @Test
    fun `the held shift's long press waits for the lift`() {
        val hold = ShiftHold().apply { begin() }
        assertNull(hold.route(shift))
        assertSame(shift, hold.deferred)
        assertFalse(hold.typed)
    }

    @Test
    fun `ending the hold forgets everything`() {
        val hold = ShiftHold().apply { begin() }
        hold.route(shift)
        hold.route(Key("a"))
        hold.end()
        assertFalse(hold.held)
        assertFalse(hold.typed)
        assertNull(hold.deferred)
        val a = Key("a")
        assertSame(a, hold.route(a))
    }
}
