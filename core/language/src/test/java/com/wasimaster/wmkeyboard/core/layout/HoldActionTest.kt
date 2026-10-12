package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a key's action alternates sit in its popup (issue #570), and the hold
 * action that runs with no popup at all.
 */
class HoldActionTest {

    private val copy = KeyAlternate(KeyAction.Edit(TextEditAction.COPY))
    private val cut = KeyAlternate(KeyAction.Edit(TextEditAction.CUT))
    private val forwardDelete = KeyAlternate(KeyAction.Edit(TextEditAction.FORWARD_DELETE))

    private fun Key.order(): List<String> = alternateEntries().map {
        when (it) {
            is AlternateEntry.Character -> it.text
            is AlternateEntry.Action -> (it.alternate.action as KeyAction.Edit).op.name
        }
    }

    private val c = Key("c", longPress = listOf("ç", "ć", "č"), actionAlternates = listOf(copy, cut))

    @Test
    fun `the actions go after the characters by default`() {
        assertEquals(listOf("ç", "ć", "č", "COPY", "CUT"), c.order())
    }

    @Test
    fun `a position puts the actions as one block among the characters`() {
        assertEquals(listOf("COPY", "CUT", "ç", "ć", "č"), c.copy(actionAlternatesAt = 0).order())
        assertEquals(listOf("ç", "COPY", "CUT", "ć", "č"), c.copy(actionAlternatesAt = 1).order())
        assertEquals(listOf("ç", "ć", "COPY", "CUT", "č"), c.copy(actionAlternatesAt = 2).order())
    }

    @Test
    fun `a position past the characters, or a broken one, is the default`() {
        val default = c.order()
        assertEquals(default, c.copy(actionAlternatesAt = 3).order())
        assertEquals(default, c.copy(actionAlternatesAt = 40).order())
        assertEquals(default, c.copy(actionAlternatesAt = -2).order())
    }

    @Test
    fun `the older first flag still reads as the front, and a position beats it`() {
        assertEquals(listOf("COPY", "CUT", "ç", "ć", "č"), c.copy(actionAlternatesFirst = true).order())
        assertEquals(
            listOf("ç", "COPY", "CUT", "ć", "č"),
            c.copy(actionAlternatesFirst = true, actionAlternatesAt = 1).order(),
        )
    }

    @Test
    fun `a hold action takes the hold from the popup`() {
        val key = c.copy(holdAction = cut)
        assertTrue(key.hasHoldAction())
        assertFalse(key.opensAlternatesPopup())
        assertEquals(KeyAction.Edit(TextEditAction.CUT), key.holdActionKey()!!.action)
    }

    @Test
    fun `a hold action does nothing where the hold is already spoken for`() {
        assertFalse(Key(" ", action = KeyAction.Space, holdAction = cut).hasHoldAction())
        assertFalse(Key("x", repeatOnHold = true, holdAction = cut).hasHoldAction())
        assertFalse(Key("⌫", action = KeyAction.Delete, holdAction = cut).hasHoldAction())
        assertFalse(Key("あ", flick = mapOf(FlickDirection.LEFT to "い"), holdAction = cut).hasHoldAction())
        // Nothing to press either, so no gesture can run it by mistake.
        assertNull(c.copy(holdAction = cut, flick = mapOf(FlickDirection.LEFT to "x")).holdActionKey())
    }

    @Test
    fun `a held delete or caret move repeats, a held copy does not`() {
        assertTrue(Key(".", holdAction = forwardDelete).holdActionKey()!!.flickArmRepeats())
        assertFalse(Key("c", holdAction = copy).holdActionKey()!!.flickArmRepeats())
    }

    @Test
    fun `repair drops a hold action that cannot do anything and a negative position`() {
        val repairs = mutableListOf<LayoutMessage>()
        val fixed = Key(
            "a",
            holdAction = KeyAlternate(KeyAction.Unknown("teleport")),
            actionAlternatesAt = -1,
        ).repairKey("a", repairs)
        assertNull(fixed?.holdAction)
        assertNull(fixed?.actionAlternatesAt)
    }
}
