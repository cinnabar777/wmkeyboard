package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.TextWordScan
import com.wasimaster.wmkeyboard.core.settings.LearnFromTextSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnFromTextTest {

    private val enders = charArrayOf('.', '!', '?')

    private fun scan(text: String) = TextWordScan.scan(text, enders)

    @Test
    fun rowsKeepOnlyUnknownLearnableWords() {
        val known = setOf("the", "cat", "sat", "on", "a")
        val result = scan("the cat sat on a zorblax. zorblax again, x marks teh spot")
        val rows = LearnFromText.rowsFor(
            result,
            isKnown = { it in known },
            blacklist = setOf("spot"),
            sightings = { if (it == "teh") 2 else 0 },
        )
        val keys = rows.map { it.key }
        assertEquals(listOf("zorblax", "again", "marks", "teh"), keys)
        // One letter is too short to learn; blacklisted words are never offered.
        assertFalse("x" in keys)
        assertFalse("spot" in keys)
        assertEquals(2, rows.first { it.key == "zorblax" }.count)
        assertEquals(2, rows.first { it.key == "teh" }.seen)
        assertTrue(rows.all { it.checked })
    }

    @Test
    fun sortsByFrequencyThenTextOrder() {
        val rows = listOf(
            row("beta", count = 1, start = 5),
            row("alpha", count = 3, start = 10),
            row("gamma", count = 1, start = 0),
        )
        assertEquals(listOf("alpha", "gamma", "beta"), LearnFromText.sorted(rows, LearnFromTextSort.MOST_FREQUENT).map { it.key })
        assertEquals(listOf("gamma", "beta", "alpha"), LearnFromText.sorted(rows, LearnFromTextSort.TEXT_ORDER).map { it.key })
        assertEquals(listOf("alpha", "beta", "gamma"), LearnFromText.sorted(rows, LearnFromTextSort.ALPHABETICAL).map { it.key })
    }

    @Test
    fun alphabeticalOrderFollowsTheEditedSpelling() {
        val rows = listOf(row("zzz", start = 0).copy(edited = "aaa"), row("bbb", start = 1))
        assertEquals(listOf("zzz", "bbb"), LearnFromText.sorted(rows, LearnFromTextSort.ALPHABETICAL).map { it.key })
    }

    @Test
    fun planTeachesOnlyPairsBetweenKnownWordsAndFollowsEdits() {
        val result = scan("see teh cat and zorblax now. the end")
        val rows = listOf(row("teh", start = 4).copy(edited = "the"))
        val known = setOf("see", "the", "cat", "and", "now", "end")
        val plan = LearnFromText.plan(
            result,
            renames = LearnFromText.renamesOf(rows),
            isKnown = { it in known },
            blacklist = setOf("end"),
        )
        assertTrue(("see" to "the") in plan.pairs)
        assertTrue(("the" to "cat") in plan.pairs)
        assertTrue(Triple("see", "the", "cat") in plan.triples)
        assertTrue(("see" to "cat") in plan.skips)
        // zorblax was left unchecked, so nothing hangs off it.
        assertFalse(plan.pairs.any { "zorblax" in it.toList() })
        // A skip-gram asks only about its two ends, as typing does: the unknown
        // word between them is where that store earns its keep.
        assertTrue(("and" to "now") in plan.skips)
        // Three back, across two, under the same rule.
        assertTrue(("cat" to "now") in plan.skips2)
        assertFalse(plan.skips2.any { "zorblax" in it.toList() })
        // A blacklisted end is refused like an unknown one.
        assertFalse(("the" to "end") in plan.pairs)
        assertFalse(plan.pairs.any { "teh" in it.toList() })
    }

    @Test
    fun editValidityFollowsTheLearnableRule() {
        assertTrue(LearnFromTextUi(editText = "the").editValid)
        assertFalse(LearnFromTextUi(editText = "t").editValid)
        assertFalse(LearnFromTextUi(editText = "th\"e").editValid)
    }

    @Test
    fun scannedWordCountPreservedInLearnResult() {
        val result = LearnResult(words = 3, pairs = 5, scannedWords = 42)
        assertEquals(3, result.words)
        assertEquals(5, result.pairs)
        assertEquals(42, result.scannedWords)
    }

    private fun row(key: String, count: Int = 1, start: Int = 0) =
        LearnRow(key = key, spelling = key, count = count, start = start, length = key.length, caseEvidence = false)
}
