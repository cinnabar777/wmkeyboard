package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip of a layout whose keys already spell the word (Khipro, #400): the
 * composed word first, since that is what a space commits, then the Bengali
 * list's words that start with it. Nothing is corrected.
 */
class KhiproCompletionTest {

    private val bengali = BengaliPhoneticIndex(
        listOf(
            "আমি" to 9000,
            "আমার" to 8000,
            "আমাদের" to 5000,
            "আমরা" to 7000,
            "আম" to 1566,
            "তুমি" to 6000,
        ),
    )

    private fun engine(lexicon: UserLexicon = UserLexicon(null)) =
        SuggestionEngine(Trie(), bengali, lexicon, SpellingMap.EMPTY).apply {
            primaryLanguageId = "bn"
            englishSources = false
        }

    private fun SuggestionEngine.khipro(composed: String, previous: String? = null, limit: Int = 5) =
        suggest(composed, previousWord = previous, limit = limit, completionLanguage = "bn")

    @Test
    fun `the composed word leads, then the list's words that start with it, commonest first`() {
        assertEquals(listOf("আম", "আমি", "আমার", "আমরা", "আমাদের"), engine().khipro("আম"))
    }

    @Test
    fun `a word the list does not know still leads, uncorrected`() {
        val strip = engine().khipro("আমক")
        assertEquals("আমক", strip.first())
        assertEquals(1, strip.size)
    }

    @Test
    fun `a word the user taught the keyboard is offered`() {
        // Written with a precomposed ড়, as the keys type it; the lexicon keeps
        // it decomposed, so the two are compared the way the stores compare.
        val word = "আম\u09DCা"
        val lexicon = UserLexicon(null).apply { repeat(3) { learnWord(word, langId = "bn") } }
        assertTrue(engine(lexicon).khipro("আম").any { WordKey.of(it) == WordKey.of(word) })
    }

    @Test
    fun `a prefix with a nukta letter finds list words spelled either way`() {
        val decomposed = BengaliPhoneticIndex(listOf("বা\u09A1\u09BCি" to 900))
        val engine = SuggestionEngine(Trie(), decomposed, UserLexicon(null), SpellingMap.EMPTY)
        val strip = engine.khipro("বা\u09DC")
        assertEquals(2, strip.size)
        assertEquals(WordKey.of("বা\u09DCি"), WordKey.of(strip[1]))
    }

    @Test
    fun `the index completes a prefix from its sorted words`() {
        assertEquals(listOf("আমি", "আমার"), bengali.completions("আম", 2))
        assertEquals(listOf("তুমি"), bengali.completions("তু", 5))
        assertEquals(emptyList<String>(), bengali.completions("", 5))
        assertEquals(emptyList<String>(), bengali.completions("ক", 5))
    }
}
