package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A verb's -s, -ed or -ing form the word list left out is not "corrected" to
 * the verb (#395): the bundled English list holds `corrected` but not
 * `corrects`, and "the autocorrect corrects" came out "the autocorrect
 * correct". A misspelling of a form the list does hold stays correctable.
 */
class VerbFormTest {

    private fun engine(vararg words: Pair<String, Int>): SuggestionEngine =
        SuggestionEngine(PackedTrie.of(words.toList()), BengaliPhoneticIndex(emptyList()), UserLexicon(null))

    @Test fun aVerbsMissingSFormIsLeftAlone() {
        val e = engine("correct" to 2_165, "corrected" to 400, "the" to 10_000)
        assertNull(e.shouldAutocorrect("corrects"))
        assertNull(e.shouldAutocorrect("Corrects"))
    }

    @Test fun esAndIesFormsAreLeftAlone() {
        val e = engine("fix" to 900, "fixed" to 700, "carry" to 800, "carried" to 500, "watch" to 900, "watching" to 300)
        assertNull(e.shouldAutocorrect("fixes"))
        assertNull(e.shouldAutocorrect("carries"))
        assertNull(e.shouldAutocorrect("watches"))
    }

    @Test fun participlesWithSpellingChangesCount() {
        val e = engine("hope" to 900, "hoping" to 500, "stop" to 900, "stopped" to 500)
        assertNull(e.shouldAutocorrect("hopes"))
        assertNull(e.shouldAutocorrect("stops"))
    }

    @Test fun missingEdAndIngFormsAreLeftAlone() {
        val e = engine(
            "correct" to 2_165, "corrected" to 400, "hope" to 900, "hoping" to 500,
            "stop" to 900, "stopping" to 500, "carry" to 800, "carrying" to 300, "agree" to 900, "agreeing" to 200,
        )
        assertNull(e.shouldAutocorrect("correcting"))
        assertNull(e.shouldAutocorrect("hoped"))
        assertNull(e.shouldAutocorrect("stopped"))
        assertNull(e.shouldAutocorrect("carried"))
        assertNull(e.shouldAutocorrect("agreed"))
    }

    @Test fun aMisspellingOfAListedFormIsStillCorrected() {
        val e = engine(
            "admit" to 900, "admitted" to 600, "admitting" to 300, "fix" to 900, "fixes" to 400, "fixed" to 700,
            "stop" to 900, "stopped" to 600, "stopping" to 500, "hope" to 900, "hoping" to 500, "hoped" to 400,
        )
        assertNull(verdictProtects(e, "admited"))
        assertNull(verdictProtects(e, "fixs"))
        assertNull(verdictProtects(e, "stoped"))
        assertNull(verdictProtects(e, "hopeing"))
    }

    @Test fun anIrregularVerbHasNoEd() {
        // `beginning` witnesses a verb, but `begin` is irregular: `begined`
        // is a misspelling, not a form the list left out.
        val e = engine("begin" to 900, "beginning" to 500, "begins" to 300)
        assertNull(verdictProtects(e, "begined"))
    }

    @Test fun aNounsPluralIsNoWitnessForIng() {
        val e = engine("bed" to 900, "beds" to 500, "being" to 5_000)
        assertNull(verdictProtects(e, "beding"))
    }

    /** Null when [word] is not shielded: it may be corrected or left, by the ordinary rules. */
    private fun verdictProtects(e: SuggestionEngine, word: String): String? {
        val english = e.shouldAutocorrect(word)
        e.englishSources = false
        e.customDictionary = e.dictionary
        val without = e.shouldAutocorrect(word)
        e.englishSources = true
        e.customDictionary = PackedTrie.EMPTY
        return if (english == without) null else "shielded"
    }

    @Test fun aTypoEndingInSIsStillCorrected() {
        // `the` is listed but is no verb: nothing vouches for `thes`.
        val e = engine("the" to 10_000, "then" to 50)
        assertEquals("the", e.shouldAutocorrect("thes"))
    }

    @Test fun offWhenEnglishIsNotInTheMix() {
        val e = engine("correct" to 2_165, "corrected" to 400)
        e.englishSources = false
        e.customDictionary = PackedTrie.of(listOf("correct" to 2_165, "corrected" to 400))
        assertEquals("correct", e.shouldAutocorrect("corrects"))
    }
}
