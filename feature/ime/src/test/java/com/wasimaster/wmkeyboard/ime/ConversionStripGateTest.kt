package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.input.composer.PinyinSyllables
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A conversion IME keeps its candidates in fields that silence the suggestion
 * strip (issue #260).
 *
 * For Chinese or Japanese the candidate list is not a guess the keyboard is
 * offering — it is the input method, the same way a transliterator's composing
 * buffer is. So the gates that quiet the strip (the setting, a field's
 * NO_SUGGESTIONS flag or FILTER/URI/email variation, a password box) cannot
 * also apply to it: the pinyin composes either way, and with no Hanzi behind it
 * the language is simply untypeable. Every search box in every app was in that
 * state.
 *
 * Driven like the other service tests here: a subclass with a context attached
 * and `onCreate` never called.
 */
@RunWith(RobolectricTestRunner::class)
class ConversionStripGateTest {

    /** The whole dictionary these tests need: one reading, one character. */
    @Before
    fun loadPinyinTable() {
        PinyinSyllables.valid = setOf("ni")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("ni\t你\t100"))
    }

    // Process globals, so they go back as they were for every other test.
    @After
    fun resetPinyinTable() {
        PinyinSyllables.valid = emptySet()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
    }

    private fun pinyinState(
        suggestions: Boolean,
        fieldNoSuggestions: Boolean,
        secureField: Boolean = false,
    ) = glideReadyState(
        glideReady = false,
        settings = KeyboardSettings(
            learnFromTyping = false,
            suggestions = suggestions,
            haptics = HapticSettings(enabled = false),
        ),
        secureField = secureField,
        fieldNoSuggestions = fieldNoSuggestions,
    ).copy(composer = PinyinComposer)

    /** Types `ni` on a pinyin board into [state]'s field and hands back the strip. */
    private fun candidatesAfterTypingNi(state: KeyboardUiState): List<String> {
        val editor = RecordingEditor()
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        seedState(service, state)
        service.onKey(Key(label = "n"))
        service.onKey(Key(label = "i"))
        // Decoded off the main thread; the strip fills when that comes back.
        settle { service.uiState.value.suggestions.isNotEmpty() }
        return service.uiState.value.suggestions
    }

    @Test
    fun `a field that asked for a quiet strip still gets its candidates`() {
        val candidates = candidatesAfterTypingNi(
            pinyinState(suggestions = true, fieldNoSuggestions = true),
        )
        assertEquals(listOf("你"), candidates)
    }

    @Test
    fun `the suggestion strip switched off does not switch Chinese off with it`() {
        val candidates = candidatesAfterTypingNi(
            pinyinState(suggestions = false, fieldNoSuggestions = false),
        )
        assertEquals(listOf("你"), candidates)
    }

    /**
     * A password field converts too — people whose language needs conversion
     * have to be able to type one. What it must not do is remember the pick,
     * and that is `learningAllowed`'s job, not the strip's: it never covers a
     * secure field.
     */
    @Test
    fun `a password field converts without remembering the reading`() {
        val service = GlideKeyboard(RecordingEditor())
        plantPersonalStores(service)
        seedState(service, pinyinState(suggestions = true, fieldNoSuggestions = true, secureField = true))
        service.onKey(Key(label = "n"))
        service.onKey(Key(label = "i"))
        settle { service.uiState.value.suggestions.isNotEmpty() }

        assertEquals(listOf("你"), service.uiState.value.suggestions)
        // The buffer is the reading, not the characters: nothing has been
        // committed yet, so nothing has been learned from either.
        assertTrue(service.uiState.value.composingPreview.isNotEmpty())
    }

    /**
     * The chip that says the pack is missing, which is the other half of #260:
     * with an empty table the same keystrokes produce no candidates at all, and
     * something on screen has to say why.
     */
    @Test
    fun `an empty table puts the missing-pack chip up instead`() {
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        val service = GlideKeyboard(RecordingEditor())
        plantPersonalStores(service)
        seedState(service, pinyinState(suggestions = true, fieldNoSuggestions = true))
        service.onKey(Key(label = "n"))
        service.onKey(Key(label = "i"))
        // Nothing to wait for but the decode itself: an empty table has no answer.
        settle { false }

        assertEquals(emptyList<String>(), service.uiState.value.suggestions)
        assertEquals("pinyin", service.uiState.value.conversionPackOffer)
    }

    /**
     * The strip is decoded off the main thread, so it can still show the list
     * for the buffer as it was a keystroke ago. A tap on it commits by
     * position, and a position from an old list points into the new one at
     * something else — the tap has to fall back to the candidate's text.
     */
    @Test
    fun `a tap on a strip a keystroke behind commits the candidate tapped`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("ni\t你\t100", "hao\t好\t100", "nihao\t你好\t200"))
        val editor = RecordingEditor()
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        seedState(service, pinyinState(suggestions = true, fieldNoSuggestions = false))
        for (c in "nihao") service.onKey(Key(label = c.toString()))
        settle { "你好" in service.uiState.value.suggestions }

        // 你 tapped at a position that, in this buffer's own ranking, holds
        // 你好 — the whole reading. Taken by position, the tap would eat all
        // five letters for a character that spells two of them.
        val staleIndex = service.uiState.value.suggestions.indexOf("你好")
        service.onCandidateTapped("你", staleIndex)

        assertEquals("你", editor.typed)
        // "hao" is still being composed behind it.
        assertTrue(editor.text.toString(), editor.text.length > 1)
    }
}
