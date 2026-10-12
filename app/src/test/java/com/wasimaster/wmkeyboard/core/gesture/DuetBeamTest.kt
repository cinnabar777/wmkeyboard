package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two-thumb decoding (issue #638) and typed letters finished by a swipe (#595). */
class DuetBeamTest {

    // The same synthetic QWERTY grid as GlideBeamTest: 60px keys, offset rows.
    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    private val lexicon = listOf(
        "something" to 800, "some" to 900, "nothing" to 700, "thing" to 600, "same" to 500,
        "hello" to 900, "help" to 700, "hell" to 200, "held" to 300, "ho" to 400,
        "together" to 700, "gather" to 300, "tether" to 100, "the" to 1000,
        "keep" to 500, "kept" to 400, "moms" to 50, "mose" to 10,
    )

    private val sources: List<FuzzyBeamSearch.WalkSource> = run {
        val trie = Trie().apply { lexicon.forEach { (word, frequency) -> insert(word, frequency) } }
        trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
    }

    private val beam = DuetBeam()
    private val workspace = DuetWorkspace()

    private fun decode(vararg tokens: GlideToken): List<String> =
        beam.decode(tokens.toList(), grid, keyWidth, sources, workspace, limit = 4).map { it.word }

    private fun tap(letter: Char, t: Long): GlideToken.Tap {
        val key = centers.getValue(letter.code)
        // A little off centre, the way a thumb lands.
        return GlideToken.Tap(key.x + 6f, key.y - 4f, t)
    }

    /** Straight lines through [letters]' keys, [msPerKey] per key-to-key leg, from [t0]. */
    private fun swipe(letters: String, t0: Long, msPerKey: Long = 150L): GlideToken.Swipe {
        val anchors = letters.map { centers.getValue(it.code) }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            val last = if (i == anchors.size - 2) STEPS else STEPS - 1
            for (step in 0..last) {
                val f = step / STEPS.toFloat()
                points.add(
                    GesturePoint(
                        a.x + f * (b.x - a.x),
                        a.y + f * (b.y - a.y),
                        t0 + i * msPerKey + (f * msPerKey).toLong(),
                    ),
                )
            }
        }
        return GlideToken.Swipe(points)
    }

    @Test
    fun `left taps and right swipes overlapping in time spell one word`() {
        // The example from the issue: S + OM + E + THING.
        val words = decode(
            tap('s', 0L),
            swipe("om", 40L),
            tap('e', 300L),
            swipe("thing", 330L, msPerKey = 120L),
        )
        assertEquals("something", words.firstOrNull())
    }

    @Test
    fun `a tap landing inside the other thumb's swipe goes where the swipe was at that moment`() {
        // h→e→l→o drawn by one thumb, the l reached at 300 ms; the other taps l
        // just after. The doubled l comes from the tap, between the swipe's l and o.
        val words = decode(swipe("helo", 0L), tap('l', 320L))
        assertEquals("hello", words.firstOrNull())
    }

    @Test
    fun `typed letters before a swipe are the start of the swiped word`() {
        // Issue #595: type "to", swipe the rest.
        val words = decode(tap('t', 0L), tap('o', 180L), swipe("gether", 450L))
        assertEquals("together", words.firstOrNull())
    }

    @Test
    fun `tokens without a clock are taken in list order`() {
        val words = decode(
            GlideToken.Tap(centers.getValue('t'.code).x, centers.getValue('t'.code).y, 0L),
            GlideToken.Tap(centers.getValue('o'.code).x, centers.getValue('o'.code).y, 0L),
            GlideToken.Swipe(swipe("gether", 0L).points.map { GesturePoint(it.x, it.y) }),
        )
        assertEquals("together", words.firstOrNull())
    }

    @Test
    fun `one tap may stand for a doubled letter`() {
        val words = decode(tap('k', 0L), tap('e', 200L), tap('p', 400L))
        assertEquals("keep", words.firstOrNull())
    }

    @Test
    fun `a tap that came well after a swipe never lands inside it`() {
        // o→m drawn and lifted, then s and e tapped long after: the letters
        // cannot be reordered into "some", whatever the dictionary prefers.
        val words = decode(swipe("om", 0L), tap('s', 600L), tap('e', 900L))
        assertFalse("some" in words)
        assertFalse("same" in words)
    }

    @Test
    fun `every gesture has to be used`() {
        // "thing" off the swipe alone is not an answer: the s tap must be in it.
        val words = decode(tap('s', 0L), swipe("thing", 400L))
        assertFalse("thing" in words)
    }

    @Test
    fun `gestures that finish no word yet still read as the start of one`() {
        // Issue #640: s + o→m is on its way to "something" and is no word
        // itself. The word reading is empty, and the partial one is what the
        // field shows meanwhile, so nothing the user did is dropped.
        val tokens = listOf(tap('s', 0L), swipe("om", 40L))
        assertTrue(beam.decode(tokens, grid, keyWidth, sources, workspace).isEmpty())
        val started = beam.decode(tokens, grid, keyWidth, sources, workspace, partial = true).map { it.word }
        assertEquals("som", started.firstOrNull())
    }

    @Test
    fun `routing sends a lone swipe and taps alone elsewhere`() {
        assertFalse(DuetBeam.needsDuet(listOf(swipe("helo", 0L))))
        assertFalse(DuetBeam.needsDuet(listOf(tap('a', 0L), tap('b', 100L))))
        assertTrue(DuetBeam.needsDuet(listOf(tap('t', 0L), swipe("gether", 200L))))
        assertTrue(DuetBeam.needsDuet(listOf(swipe("om", 0L), swipe("thing", 400L))))
    }

    private companion object {
        const val STEPS = 10
    }
}
