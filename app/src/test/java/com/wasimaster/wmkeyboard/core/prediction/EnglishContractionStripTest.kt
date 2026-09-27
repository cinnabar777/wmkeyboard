package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English contraction reaches the suggestion strip, not only the commit
 * (#240).
 *
 * The report was "thats is not corrected", and the step that showed it was
 * "that's is not showing among the available suggestions". Both spellings
 * were in the list, the space bar *did* commit the fix, and the strip went on
 * showing `thats` the whole time — so the only evidence the user had said the
 * feature did not work. A strip that disagrees with the space bar is the bug.
 */
class EnglishContractionStripTest {

    /**
     * Counts from the downloadable English list, which is tokenised at the
     * apostrophe: it holds `'s` as its sixth commonest token, `thats` at
     * 3,866 and `that's` at 2,116 — the fused misspelling ahead of the real
     * spelling. That ordering is why the reading needs its own margin rather
     * than a frequency ranking.
     */
    private fun english(): SuggestionEngine {
        val e = SuggestionEngine(
            PackedTrie.of(
                listOf(
                    "thats" to 3_866, "that's" to 2_116, "that" to 10_203_742,
                    "theres" to 991, "there's" to 4_600, "there" to 3_148_528,
                    "dont" to 9_523, "don't" to 4_911, "done" to 500_000,
                    "its" to 900_000, "it's" to 2_697,
                    "im" to 2_000, "i'm" to 4_386_306,
                    "were" to 800_000, "we're" to 300_000,
                    "ill" to 1_530, "i'll" to 5_200, "illness" to 900, "illegal" to 1_000,
                    "id" to 1_003,
                ),
            ),
            BengaliPhoneticIndex(emptyList()),
            UserLexicon(null),
        )
        e.primaryLanguageId = "en"
        return e
    }

    @Test fun theContractionLeadsTheStripAndIsTheWordCommitted() {
        val e = english()
        for ((typed, fixed) in listOf(
            "thats" to "that's",
            "theres" to "there's",
            "dont" to "don't",
            "im" to "I'm",
        )) {
            assertEquals(typed, fixed, e.suggest(typed, previousWord = null).first())
            assertEquals(typed, fixed, e.elide(typed))
        }
    }

    @Test fun theSpellingItRepairsStaysOnTheStripBehindIt() {
        // A rewrite the user cannot undo in one tap is worse than no rewrite.
        val e = english()
        assertTrue("thats" in e.suggest("thats", previousWord = null))
    }

    @Test fun anAmbiguousFormIsLeftAlone() {
        // `its`, `were` and the rest are words in their own right, so the
        // table refuses to commit them and the strip keeps them first. Only a
        // glide drawn through the apostrophe key says otherwise; see
        // [Apostrophes].
        val e = english()
        for (word in listOf("its", "were")) {
            assertNull(word, e.elide(word))
            assertEquals(word, word, e.suggest(word, previousWord = null).first())
        }
    }

    @Test fun anAmbiguousFormOffersItsContractionBehindTheTypedWord() {
        // "ill" is I'll far more often than it is ill, but it is both, so the
        // strip offers the contraction and the space bar keeps what was typed
        // (#384). The list has i'll commoner than ill; it still sits second.
        val e = english()
        val strip = e.suggest("ill", previousWord = null)
        assertEquals("ill", strip.first())
        assertEquals("I'll", strip[1])
        assertNull(e.elide("ill"))
        // A contraction the list does not hold at all still reaches the strip.
        assertTrue("I'd" in e.suggest("id", previousWord = null))
        assertNull(e.elide("id"))
        // Common enough either way to be worth a slot.
        assertTrue("we're" in e.suggest("were", previousWord = null))
    }

    @Test fun anAmbiguousFormOffersNothingWithTheSettingOff() {
        val e = english()
        e.apostropheFixes = false
        assertTrue("I'll" !in e.suggest("ill", previousWord = null))
    }

    @Test fun theTypedCaseIsKept() {
        val e = english()
        assertEquals("That's", e.elide("Thats"))
        assertEquals("DON'T", e.elide("DONT"))
        // The lone "i" reaches its capital nowhere else, and the correctly
        // spelled "i'm" reaches it only here (#128).
        assertEquals("I", e.elide("i"))
        assertEquals("I'm", e.elide("i'm"))
        assertNull(e.elide("I'm"))
    }

    @Test fun aRepairTakenBackWithBackspaceIsNotMadeAgain() {
        // Backspace after "i" became "I" puts the "i" back and remembers the
        // undo; the next space must not capitalise it straight back (#402).
        val e = english()
        e.rejectCorrection("i", "I")
        assertNull(e.elide("i"))
        e.rejectCorrection("dont", "don't")
        assertNull(e.elide("dont"))
        // Still offered, behind what was typed, so the repair is one tap away.
        val strip = e.suggest("dont", previousWord = null)
        assertEquals("dont", strip.first())
        assertTrue("don't" in strip)
        // Only the pair that was undone.
        assertEquals("that's", e.elide("thats"))
    }

    @Test fun theSettingTakesTheReadingOffTheStripAsWellAsTheCommit() {
        // "Fix missing apostrophes" off has to mean off everywhere, or the
        // strip would keep promising a repair the space bar no longer makes.
        val e = english()
        e.apostropheFixes = false
        assertNull(e.elide("thats"))
        assertEquals("thats", e.suggest("thats", previousWord = null).first())
    }

    @Test fun anotherLanguageDoesNotReadTheEnglishTable() {
        val e = english()
        e.primaryLanguageId = "de"
        assertNull(e.elide("thats"))
    }
}
