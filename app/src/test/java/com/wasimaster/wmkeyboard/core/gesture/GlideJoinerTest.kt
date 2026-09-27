package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #230: a word spelled with a hyphen, a dot or an at sign is in the
 * dictionary, completes, autocorrects — and could not be swiped, because the
 * glide grid is letters only and the decoder threw away every subtree behind
 * the character it could not find a key for.
 *
 * The finger draws the letters and nothing else, so the stroke for `f-droid`
 * *is* the stroke for `fdroid`. Every gesture below is built from the stripped
 * spelling for exactly that reason; what the tests check is that the word comes
 * back with its punctuation on.
 */
class GlideJoinerTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)
    private val beam = GlideBeam()
    private val workspace = GlideWorkspace()

    private fun sourcesOf(vararg entries: Pair<String, Int>): List<FuzzyBeamSearch.WalkSource> {
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        return trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
    }

    /** Straight lines through the key centres of [drawn], densely sampled. */
    private fun gestureFor(drawn: String): List<GesturePoint> {
        val anchors = drawn.toCharArray().toList()
            .filterIndexed { i, c -> i == 0 || c != drawn[i - 1] }
            .map { centers.getValue(it.code) }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (step in 0..10) {
                val t = step / 10f
                points.add(GesturePoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
            }
        }
        return points
    }

    private fun decode(
        drawn: String,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int = 4,
    ): List<String> =
        beam.decode(gestureFor(drawn), grid, keyWidth, sources, workspace, limit).map { it.word }

    /** The issue as reported: `f-droid` added by hand, swiped as `fdroid`. */
    @Test
    fun hyphenatedWordIsDecodedFromItsLettersAlone() {
        val decoded = decode("fdroid", sourcesOf("f-droid" to 100))
        assertEquals(listOf("f-droid"), decoded)
    }

    /** The other two shapes the reporter named: a dotted host, an address. */
    @Test
    fun dotsAndAtSignsAreSteppedOverToo() {
        assertTrue(decode("wasime", sourcesOf("wasi.me" to 100)).contains("wasi.me"))
        assertTrue(decode("mepost", sourcesOf("me@post" to 100)).contains("me@post"))
        assertTrue(decode("optout", sourcesOf("opt_out" to 100)).contains("opt_out"))
    }

    /**
     * Two joiners in one word is fine — `co-op.uk` is one stroke — as long as
     * no two sit next to each other. That bound is the whole defence against a
     * malformed entry sending the walk down a run of punctuation.
     */
    @Test
    fun severalJoinersInOneWordAreFine() {
        assertTrue(decode("wediskuk", sourcesOf("we-disk.uk" to 100)).contains("we-disk.uk"))
    }

    @Test
    fun adjacentJoinersAreNotSteppedOver() {
        assertFalse(decode("fdroid", sourcesOf("f--droid" to 100)).contains("f--droid"))
    }

    /**
     * A word cannot *start* on a joiner. The first letter is anchored to the
     * stroke's first sample — there is nothing for a leading hyphen to be
     * placed against, and the entry is not what the finger drew anyway.
     */
    @Test
    fun aLeadingJoinerIsNotSteppedOver() {
        assertFalse(decode("ish", sourcesOf("-ish" to 100)).contains("-ish"))
    }

    /**
     * The skip costs [GlideBeam.Tuning.joinerCost], so where a list holds both
     * spellings at the same count the plain one takes the top slot — and the
     * hyphenated one is still on the strip, one tap away.
     */
    @Test
    fun theUnpunctuatedSpellingWinsATie() {
        val decoded = decode("email", sourcesOf("email" to 100, "e-mail" to 100))
        assertEquals("email", decoded.first())
        assertTrue(decoded.contains("e-mail"))
    }

    /** And the charge is a tiebreak, not a filter: frequency still decides. */
    @Test
    fun aMuchCommonerPunctuatedSpellingStillWins() {
        val decoded = decode("email", sourcesOf("email" to 2, "e-mail" to 900))
        assertEquals("e-mail", decoded.first())
    }

    /**
     * The doubled letter a hyphen hides. `co-op` is drawn `coop`: the finger
     * visits `o` once, so the second `o` is a repeat and pays the repeat charge
     * — the letter before the hyphen has to survive the skip for that to work.
     */
    @Test
    fun theLetterBeforeAJoinerSurvivesIt() {
        assertTrue(decode("coop", sourcesOf("co-op" to 100)).contains("co-op"))
    }

    /**
     * Issue #406: the Persian half-space (U+200C) has a key on the Persian
     * layout but no letter, so it is not on the grid, and the stroke for
     * می‌کنم is the stroke for its letters. Latin stand-ins here, because the
     * grid under test is QWERTY; the skip does not care which script it is in.
     */
    @Test
    fun theHalfSpaceIsSteppedOver() {
        assertTrue(GlideJoiners.isJoiner(0x200C))
        assertEquals(listOf("mi\u200Ckonam"), decode("mikonam", sourcesOf("mi\u200Ckonam" to 100)))
    }

    /**
     * The apostrophe is deliberately excluded ([GlideJoiners]): it has its own
     * key and its own repair, and the contractions are exactly where a free
     * skip would decide `its` against `it's` by frequency.
     */
    @Test
    fun apostrophesAreNotJoiners() {
        assertFalse(GlideJoiners.isJoiner('\''.code))
        assertFalse(GlideJoiners.isJoiner('’'.code))
        assertFalse(decode("its", sourcesOf("it's" to 900)).contains("it's"))
    }

    /**
     * Issue #304. The subtitle-built `en_full` list holds interrupted words and
     * stutters at high counts, and once joiners could be stepped over they
     * filled the strip's second and third slots behind the word itself.
     */
    @Test
    fun aListsInterruptedWordsAndStuttersAreNotOffered() {
        val sources = sourcesOf(
            "can" to 900, "can-" to 400, "can." to 300, "c-can" to 200, "c-c-can" to 100, "cab" to 50,
        )
        val decoded = decode("can", sources, limit = 6)
        assertEquals("can", decoded.first())
        assertTrue(decoded.contains("cab"))
        for (junk in listOf("can-", "can.", "c-can", "c-c-can")) assertFalse(junk, decoded.contains(junk))
    }

    /** What the user wrote or added is theirs, however it is spelled. */
    @Test
    fun theUsersOwnWordsAreOfferedWhateverTheirSpelling() {
        val trie = Trie().apply {
            insert("t-test", 5)
            insert("etc.", 5)
        }
        val user = trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.USER) }
        assertTrue(decode("ttest", user).contains("t-test"))
        assertTrue(decode("etc", user).contains("etc."))
    }

    @Test
    fun nonWordsAreToldApartFromWordsWithJoiners() {
        for (word in listOf("that-", "no.", "n-no", "c-c-can", "wh-what", "T-that")) {
            assertTrue(word, GlideJoiners.isNonWord(word))
        }
        for (word in listOf("e-mail", "co-op", "go-go", "so-so", "f-droid", "wasi.me", "no")) {
            assertFalse(word, GlideJoiners.isNonWord(word))
        }
    }

    /**
     * A hyphenated reading has to be alignable, or it takes no shape lesson
     * (#52) and no casing (`GlideCase.Letters`) from the stroke that drew it.
     * The offsets are the *word's*, so they point past the hyphen.
     */
    @Test
    fun aHyphenatedWordAligns() {
        val alignment = beam.align("f-droid", gestureFor("fdroid"), grid, keyWidth, workspace)
        assertNotNull(alignment)
        // f d r o i d — six visits, and every offset after the hyphen shifted
        // by it. The `-` at offset 1 is not a visit of its own.
        assertEquals(listOf(0, 2, 3, 4, 5, 6), alignment!!.chars.toList())
    }
}
