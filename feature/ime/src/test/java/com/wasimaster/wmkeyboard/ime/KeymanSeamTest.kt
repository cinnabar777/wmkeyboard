package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.keyman.KeyProcessor
import com.wasimaster.wmkeyboard.core.keyman.KeymanFault
import com.wasimaster.wmkeyboard.core.keyman.KeymanLayers
import com.wasimaster.wmkeyboard.core.keyman.KeymanResult
import com.wasimaster.wmkeyboard.core.keyman.KmxParser
import com.wasimaster.wmkeyboard.core.keyman.KmxProcessor
import com.wasimaster.wmkeyboard.core.keyman.KmxModifiers
import com.wasimaster.wmkeyboard.core.keyman.ProcessorKey
import com.wasimaster.wmkeyboard.core.keyman.ProcessorResult
import com.wasimaster.wmkeyboard.core.keyman.SyncDecision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam's decisions, checked without an `InputConnection`.
 *
 * These are small and dull individually, and together they decide whether the
 * engine deletes the right number of characters out of somebody's message. The
 * anchor arithmetic in particular has no other guard: it is pure bookkeeping
 * that only shows up as damage, in one app, some of the time.
 */
class KeymanSeamTest {

    /** A processor whose answers the test dictates. */
    private class FakeProcessor(
        private var answer: ProcessorResult = ProcessorResult.Declined,
    ) : KeyProcessor {
        var resets = 0
        var syncs = 0
        var lastBefore: CharSequence? = null
        var syncAnswer = SyncDecision.KEEP

        fun answerWith(result: ProcessorResult) {
            answer = result
        }

        override fun matches(vkey: Int, modifiers: Int) = true

        override fun resetContext(before: CharSequence) {
            resets++
            lastBefore = before
        }

        override fun syncContext(before: CharSequence): SyncDecision {
            syncs++
            lastBefore = before
            return syncAnswer
        }

        override fun process(key: ProcessorKey) = answer
        override fun onNewContext(before: CharSequence): String? = null
        override fun onPostKeystroke(newLayer: String, oldLayer: String): String? = null
        override val deadKeyPending = false
    }

    private fun edit(delete: Int, insert: String) =
        ProcessorResult.Edit(deleteBefore = delete, insert = insert)

    // --- anchor arithmetic ---

    @Test
    fun `anchor advances by inserted minus deleted`() {
        assertEquals(12, KeymanSeam.anchorAfter(10, edit(0, "ab")))
        assertEquals(8, KeymanSeam.anchorAfter(10, edit(2, "")))
        assertEquals(10, KeymanSeam.anchorAfter(10, edit(3, "xyz")))
    }

    /** A surrogate pair is two units, and the anchor counts units. */
    @Test
    fun `anchor counts utf-16 units, not code points`() {
        val osage = "𐒰" // U+104B0, one code point, two units
        assertEquals(1, osage.codePointCount(0, osage.length))
        assertEquals(2, osage.length)
        assertEquals(7, KeymanSeam.anchorAfter(5, edit(0, osage)))
    }

    /** An unknown anchor stays unknown rather than drifting from -1. */
    @Test
    fun `an unknown anchor is not advanced`() {
        assertEquals(-1, KeymanSeam.anchorAfter(-1, edit(0, "abc")))
    }

    // --- echo detection ---

    @Test
    fun `a collapsed caret at the anchor is our own echo`() {
        assertTrue(KeymanSeam.isOwnEcho(newSelStart = 7, newSelEnd = 7, anchor = 7))
    }

    @Test
    fun `a moved caret is not our echo`() {
        assertFalse(KeymanSeam.isOwnEcho(newSelStart = 6, newSelEnd = 6, anchor = 7))
    }

    /** A range selection is never an echo, even when it starts at the anchor. */
    @Test
    fun `a range selection is not our echo`() {
        assertFalse(KeymanSeam.isOwnEcho(newSelStart = 7, newSelEnd = 9, anchor = 7))
    }

    @Test
    fun `an unknown anchor is never an echo`() {
        assertFalse(KeymanSeam.isOwnEcho(newSelStart = 0, newSelEnd = 0, anchor = -1))
    }

    // --- modifiers ---

    @Test
    fun `shift and caps are added only where the layout drew no shift page`() {
        assertEquals(0, KeymanSeam.runtimeModifiers(LayoutMode.LETTERS, ShiftState.OFF, hasShiftGrid = false))
        assertEquals(
            KmxModifiers.SHIFT,
            KeymanSeam.runtimeModifiers(LayoutMode.LETTERS, ShiftState.ON, hasShiftGrid = false),
        )
        assertEquals(
            KmxModifiers.SHIFT or KmxModifiers.CAPS,
            KeymanSeam.runtimeModifiers(LayoutMode.LETTERS, ShiftState.CAPS_LOCK, hasShiftGrid = false),
        )
        // The shift page's keys already carry shift; adding it again is harmless
        // but a caps bit would change which rules match.
        assertEquals(0, KeymanSeam.runtimeModifiers(LayoutMode.LETTERS, ShiftState.CAPS_LOCK, hasShiftGrid = true))
        assertEquals(0, KeymanSeam.runtimeModifiers(LayoutMode.NAMED, ShiftState.ON, hasShiftGrid = false))
    }

    /**
     * Android's `META_SHIFT_ON` is 1, which is Keyman's left-control bit. If
     * these ever became the same number by accident, a rule written for ctrl
     * would fire on a shifted letter.
     */
    @Test
    fun `keyman shift is not android meta shift`() {
        val androidMetaShiftOn = 1
        assertTrue(KmxModifiers.SHIFT != androidMetaShiftOn)
        assertEquals(androidMetaShiftOn, KmxModifiers.LEFT_CTRL)
    }

    // --- session ---

    @Test
    fun `a new session is stale until it syncs`() {
        val session = KeymanSession(FakeProcessor())
        assertTrue(session.stale)
        assertEquals(-1, session.anchor)
    }

    @Test
    fun `sync reads the field only while stale`() {
        val fake = FakeProcessor()
        val session = KeymanSession(fake)
        var reads = 0
        session.syncIfNeeded(4) { reads++; "abcd" }
        assertEquals(1, reads)
        assertEquals(4, session.anchor)
        assertFalse(session.stale)

        assertNull("a fresh context must not re-read", session.syncIfNeeded(4) { reads++; "abcd" })
        assertEquals(1, reads)
    }

    @Test
    fun `a foreign selection report marks the context stale`() {
        val session = KeymanSession(FakeProcessor())
        session.reset("abc", at = 3)
        assertFalse(session.stale)

        session.onSelectionReported(3, 3)
        assertFalse("our own echo must not invalidate the context", session.stale)

        session.onSelectionReported(1, 1)
        assertTrue("a moved caret must invalidate the context", session.stale)
    }

    @Test
    fun `an applied edit moves the anchor so the next report is an echo`() {
        val fake = FakeProcessor()
        val session = KeymanSession(fake)
        session.reset("abc", at = 3)

        val e = edit(delete = 1, insert = "XY")
        session.onEdited(e)
        assertEquals(4, session.anchor)

        session.onSelectionReported(4, 4)
        assertFalse(session.stale)
    }

    /**
     * A blown budget is deterministic, so retrying it would stall every
     * subsequent key. The session gives up for the rest of the field.
     */
    @Test
    fun `a fault disables the session permanently`() {
        val fake = FakeProcessor(ProcessorResult.Failed(KeymanFault.RULE_BUDGET))
        val session = KeymanSession(fake)

        assertNull(session.process(ProcessorKey(65, 0)))
        assertTrue(session.disabled)

        fake.answerWith(edit(0, "a"))
        assertNull("a disabled session must stay disabled", session.process(ProcessorKey(65, 0)))
    }

    @Test
    fun `a declined key is passed back for the ordinary path`() {
        val session = KeymanSession(FakeProcessor(ProcessorResult.Declined))
        assertEquals(ProcessorResult.Declined, session.process(ProcessorKey(65, 0)))
        assertFalse(session.disabled)
    }

    /** `if(&layer = ...)` sees Keyman's names for the grid we are showing. */
    @Test
    fun `the active layer follows shift, caps and named layers`() {
        fun layer(mode: LayoutMode, shift: ShiftState, named: String? = null, caps: Boolean = false) =
            KeymanLayers.keymanId(KeymanSeam.activeLayerKey(mode, shift, named, caps))
        assertEquals("default", layer(LayoutMode.LETTERS, ShiftState.OFF))
        assertEquals("shift", layer(LayoutMode.LETTERS, ShiftState.ON))
        assertEquals("shift", layer(LayoutMode.LETTERS, ShiftState.CAPS_LOCK))
        assertEquals("caps", layer(LayoutMode.LETTERS, ShiftState.CAPS_LOCK, caps = true))
        assertEquals("numeric", layer(LayoutMode.SYMBOLS, ShiftState.OFF))
        assertEquals("symbol", layer(LayoutMode.SYMBOLS_SHIFTED, ShiftState.OFF))
        assertEquals("rightalt", layer(LayoutMode.NAMED, ShiftState.OFF, named = "k:rightalt"))
    }

    // --- multitap take-back ---

    @Test
    fun `a multitap undo deletes what the tap added and puts back what it deleted`() {
        assertEquals(edit(1, ""), KeymanSeam.multitapUndo("abc", listOf(edit(0, "d"))))
        assertEquals(edit(2, "x"), KeymanSeam.multitapUndo("abx", listOf(edit(1, "yz"))))
        // A deadkey press changes nothing in the field, so neither does its undo.
        assertEquals(edit(0, ""), KeymanSeam.multitapUndo("ab", listOf(edit(0, ""))))
        assertEquals(edit(1, "c"), KeymanSeam.multitapUndo("abc", listOf(edit(1, "d"), edit(1, "e"))))
    }

    /** Half a surrogate pair is never kept as common text: U+104B0 and U+104B1 share their high half. */
    @Test
    fun `a multitap undo never splits a surrogate pair`() {
        assertEquals(edit(2, "𐒰"), KeymanSeam.multitapUndo("a𐒰", listOf(edit(2, "𐒱"))))
    }

    @Test
    fun `a multitap undo refuses an edit that deleted more than there was`() {
        assertNull(KeymanSeam.multitapUndo("a", listOf(edit(2, ""))))
    }

    /** What an editor holds, driven through [session] the way the service drives the field. */
    private class Typist(val session: KeymanSession) {
        val field = StringBuilder()

        fun apply(edit: ProcessorResult.Edit) {
            field.setLength(field.length - edit.deleteBefore)
            field.append(edit.insert)
            session.onEdited(edit)
        }

        fun press(vkey: Int) {
            val result = session.process(ProcessorKey(vkey, 0))
            assertTrue("key $vkey: $result", result is ProcessorResult.Edit)
            apply(result as ProcessorResult.Edit)
        }
    }

    private fun khmer(): KeymanSession {
        val bytes = File("../../core/keyman/src/test/resources/kmx/khmer_angkor.kmx").readBytes()
        val keyboard = (KmxParser.parse(bytes) as KeymanResult.Success).value
        return KeymanSession(KmxProcessor(keyboard)).apply { reset("", 0) }
    }

    private fun typed(vararg keys: Int): String = Typist(khmer()).apply { keys.forEach(::press) }.field.toString()

    /**
     * A later tap of a Keyman multitap key acts as if it were the first thing
     * typed after the text before the run, as KeymanWeb's does. On Khmer
     * Angkor, `x j` leaves ខ and a coeng waiting for the next consonant, so
     * the step has to see that state again, not the base tap's ្ម.
     */
    @Test
    fun `a multitap step runs its rules on the context before the run`() {
        val typist = Typist(khmer())
        typist.press(VK_X)
        typist.press(VK_J)
        val beforeRun = typist.field.toString()

        assertTrue(typist.session.beginTap())
        typist.press(VK_M)
        val tap = checkNotNull(typist.session.endTap())
        assertEquals(typed(VK_X, VK_J, VK_M), typist.field.toString())

        val undo = checkNotNull(typist.session.takeBack(tap, typist.session.anchor) { typist.field })
        typist.apply(undo)
        assertEquals(beforeRun, typist.field.toString())
        assertEquals("the context came back with its deadkeys", tap.before, typist.session.processor.saveContext())

        assertTrue(typist.session.beginTap())
        typist.press(VK_K)
        typist.session.endTap()
        assertEquals(typed(VK_X, VK_J, VK_K), typist.field.toString())
    }

    /** Text typed after the tap means the tap is not behind the caret any more. */
    @Test
    fun `a multitap step is not taken back once something else was typed`() {
        val typist = Typist(khmer())
        typist.session.beginTap()
        typist.press(VK_M)
        val tap = checkNotNull(typist.session.endTap())
        typist.press(VK_K)
        val context = typist.session.processor.saveContext()

        assertNull(typist.session.takeBack(tap, typist.session.anchor) { typist.field })
        assertEquals(context, typist.session.processor.saveContext())
    }

    /** A processor that keeps no context has nothing to rewind to, so it records nothing. */
    @Test
    fun `a processor with no context cannot record a tap`() {
        val session = KeymanSession(FakeProcessor(edit(0, "a")))
        assertFalse(session.beginTap())
        assertNull(session.endTap())
    }

    private companion object {
        const val VK_J = 74
        const val VK_K = 75
        const val VK_M = 77
        const val VK_X = 88
    }
}
