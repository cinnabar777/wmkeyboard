package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English beside a Chinese or Japanese reading (#633): the letters typed for
 * pinyin can be an English word, and with English among the language's
 * secondaries the candidate strip offers it.
 */
class ConversionEnglishTest {

    private val english = listOf(
        "hello" to 90_000, "help" to 120_000, "helmet" to 4_000, "he" to 5_000_000, "hen" to 3_000,
    )

    private fun chinese(englishSecondary: Boolean): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.of(english), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.englishSources = false
        e.englishAsSecondary = englishSecondary
        e.primaryLanguageId = "zh"
        return e
    }

    @Test
    fun `completions come from the English list, the word as typed first`() {
        val e = chinese(englishSecondary = true)
        assertEquals(listOf("hello"), e.englishCompletions("hello", 2))
        assertEquals("hen", e.englishCompletions("hen", 2).first())
        assertTrue("help" in e.englishCompletions("hel", 3))
    }

    @Test
    fun `nothing without English among the secondaries`() {
        assertTrue(chinese(englishSecondary = false).englishCompletions("hello", 2).isEmpty())
    }

    @Test
    fun `no corrections, and nothing for a reading that is not letters`() {
        val e = chinese(englishSecondary = true)
        // One edit from "hello": a reading, not a misspelt English word.
        assertTrue(e.englishCompletions("hrllo", 2).isEmpty())
        assertTrue(e.englishCompletions("nei5", 2).isEmpty())
        assertTrue(e.englishCompletions("h", 2).isEmpty())
    }
}
