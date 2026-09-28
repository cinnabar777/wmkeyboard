package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What one backspace and one forward delete take out of a real field, through
 * the service, with the platform ICU that Robolectric runs.
 *
 * `Graphemes` has its own exhaustive test in `:core:common`; this one is about
 * the wiring: that the keys reach it, that the emoji rule still comes first, and
 * that a mark the user typed on its own key still comes off on its own.
 *
 * Every non-ASCII string is spelled with escapes, because what each case is
 * about is exactly which invisible code points it holds.
 */
@RunWith(RobolectricTestRunner::class)
class GraphemeDeleteServiceTest {

    /** A field that logs each deletion the service asks for. */
    private class Field(initial: String = "", after: String = "") :
        RecordingEditor(initial = initial, after = after) {
        val deletions = mutableListOf<Pair<Int, Int>>()

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletions += beforeLength to afterLength
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
    }

    /**
     * The harness's service with an ordinary text field behind it. Without an
     * `EditorInfo` the service reads the field as TYPE_NULL and answers every
     * delete with a key event instead, which is the terminal path, not this one.
     */
    private class TextKeyboard(private val editor: InputConnection) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo =
            EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }
    }

    private fun keyboard(field: Field): WMKeyboardService {
        val service = TextKeyboard(field)
        plantPersonalStores(service)
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
            ),
        )
        return service
    }

    /** How many UTF-16 units one backspace takes from the end of [text]. */
    private fun backspace(text: String): Int {
        val field = Field(initial = text)
        keyboard(field).onKey(Key(label = "", action = KeyAction.Delete))
        return field.deletions.single().first
    }

    /** How many UTF-16 units one forward delete takes from the start of [text]. */
    private fun forwardDelete(text: String): Int {
        val field = Field(after = text)
        keyboard(field).onKey(Key(label = "", action = KeyAction.ForwardDelete))
        return field.deletions.single().second
    }

    @Test
    fun `backspace takes one character of plain text`() {
        assertEquals(1, backspace("abc"))
    }

    @Test
    fun `backspace never halves a surrogate pair`() {
        assertEquals(2, backspace("x\uD83D\uDE00")) // grinning face
        assertEquals(2, backspace("x\uD835\uDC00")) // mathematical bold A
    }

    @Test
    fun `backspace still takes an emoji sequence whole`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family.length, backspace("hi $family"))
        assertEquals(4, backspace("\uD83D\uDC4D\uD83C\uDFFD")) // thumbs up, medium skin tone
    }

    @Test
    fun `backspace takes a typed mark back and leaves its letter`() {
        assertEquals(1, backspace("\u0628\u064E")) // ba + fatha
        assertEquals(1, backspace("cafe\u0301")) // decomposed e-acute
        assertEquals(1, backspace("\u0645\u06CC\u200C")) // Persian word + ZWNJ
    }

    @Test
    fun `backspace takes the LF of a CR LF with its CR`() {
        assertEquals(2, backspace("line\r\n"))
        assertEquals(1, backspace("line\n"))
    }

    @Test
    fun `backspace takes an invisible variation selector with its base`() {
        assertEquals(3, backspace("x\u845B\uDB40\uDD00")) // ideographic variation sequence
        assertEquals(2, backspace("x1\uFE0E")) // text-style digit
    }

    @Test
    fun `backspace takes a conjoining-jamo syllable whole`() {
        assertEquals(3, backspace("\u1112\u1161\u11AB"))
    }

    @Test
    fun `forward delete takes a whole cluster`() {
        assertEquals(1, forwardDelete("abc"))
        assertEquals(2, forwardDelete("e\u0301x"))
        assertEquals(2, forwardDelete("\r\nx"))
        assertEquals(3, forwardDelete("\u1112\u1161\u11ABx"))
        assertEquals(3, forwardDelete("\u845B\uDB40\uDD00x"))
        assertEquals(2, forwardDelete("\u0995\u09BF")) // Bengali ki
    }

    @Test
    fun `forward delete takes an emoji sequence whole`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family.length, forwardDelete("$family rest"))
        assertEquals(4, forwardDelete("\uD83C\uDDE7\uD83C\uDDE9\uD83C\uDDFA\uD83C\uDDF8")) // BD then US flag
    }
}
