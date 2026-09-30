package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English contraction table meeting a word of another language (#425).
 *
 * The reporter types German with English as its secondary, and "Ich bin im
 * Büro" came out "Ich bin I'm Büro": `im` is not an English word, so the
 * table held it for a slip, but it is among the commonest German ones. And an
 * undo did not stop it for long, because a repair undone once was still
 * committed as soon as the in-process block ran out.
 */
class ContractionInAnotherLanguageTest {

    private val english = listOf(
        "im" to 2_000, "i'm" to 4_386_306,
        "dont" to 9_523, "don't" to 4_911,
        "thats" to 3_866, "that's" to 2_116, "the" to 20_000_000,
    )

    private val german = listOf(
        "im" to 8_400_000, "ich" to 9_000_000, "bin" to 2_100_000,
        "büro" to 90_000, "und" to 12_000_000, "der" to 15_000_000,
    )

    /** German in front, bundled English riding as its secondary. */
    private fun germanFirst(): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.of(english), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.englishSources = false
        e.englishAsSecondary = true
        e.customDictionary = PackedTrie.of(german)
        e.primaryLanguageId = "de"
        return e
    }

    /** English in front, German as a secondary list. */
    private fun englishFirst(): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.of(english), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.secondaryDictionaries = listOf(SecondaryDictionary("de", PackedTrie.of(german)))
        e.primaryLanguageId = "en"
        return e
    }

    @Test fun aGermanWordIsNotRewrittenAsAnEnglishContraction() {
        val e = germanFirst()
        assertNull(e.elide("im"))
        assertNull(e.elide("Im"))
    }

    @Test fun theContractionIsStillOfferedBehindIt() {
        val strip = germanFirst().suggest("im", previousWord = null)
        assertEquals("im", strip.first())
        assertTrue("I'm" in strip)
    }

    @Test fun aSlipGermanDoesNotHaveIsStillRepaired() {
        val e = germanFirst()
        assertEquals("don't", e.elide("dont"))
        assertEquals("that's", e.elide("thats"))
    }

    @Test fun anEnglishFieldKeepsTheRepairWithGermanAlongside() {
        assertEquals("I'm", englishFirst().elide("im"))
    }

    @Test fun anEnglishKeyboardStopsOnceTheFieldIsGerman() {
        val e = englishFirst()
        e.fieldDetectionShift = SuggestionEngine.FIELD_SHIFT_BALANCED
        e.seedFieldContext(listOf("ich", "bin", "der", "und", "ich", "bin"))
        assertNull(e.elide("im"))
    }

    @Test fun oneUndoOutlastsTheBlockThatFollowsIt() {
        val e = SuggestionEngine(PackedTrie.of(english), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.primaryLanguageId = "en"
        e.rejectCorrection("im", "I'm")
        // The user leaves the field: the in-process block is gone and the
        // pair is left with its one persisted undo.
        e.correctionStats.endFieldSession()
        assertNull(e.elide("im"))
        assertTrue("I'm" in e.suggest("im", previousWord = null))
        assertEquals("don't", e.elide("dont"))
    }
}
