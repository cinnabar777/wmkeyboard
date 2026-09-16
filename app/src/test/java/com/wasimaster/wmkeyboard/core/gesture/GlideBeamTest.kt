package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideBeamTest {

    // A synthetic QWERTY grid: 60px keys, rows offset like a real keyboard.
    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    private val lexicon = listOf(
        "hello" to 900, "help" to 700, "held" to 300, "hell" to 200, "ho" to 400,
        "the" to 1000, "they" to 800, "then" to 700, "them" to 650,
        "was" to 900, "war" to 400, "what" to 850, "good" to 800,
        "god" to 300, "food" to 500, "test" to 400, "text" to 350,
    )

    private val beam = GlideBeam()
    private val workspace = GlideWorkspace()

    private fun sourcesOf(entries: List<Pair<String, Int>>): List<FuzzyBeamSearch.WalkSource> {
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        return trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
    }

    private val sources = sourcesOf(lexicon)

    private fun decode(
        path: List<GesturePoint>,
        from: List<FuzzyBeamSearch.WalkSource> = sources,
        limit: Int = 4,
        on: GlideKeyMap = grid,
    ): List<String> =
        beam.decode(path, on, keyWidth, from, workspace, limit).map { it.word }

    /** Ideal gesture: straight lines through the word's key centres, densely sampled. */
    private fun gestureFor(word: String, jitter: Float = 0f): List<GesturePoint> {
        val anchors = word.toCharArray().distinctConsecutive().map { centers.getValue(it.code) }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (step in 0..10) {
                val t = step / 10f
                // Deterministic zig-zag "noise" so tests stay reproducible.
                val offset = if (jitter == 0f) 0f else jitter * (if (step % 2 == 0) 1 else -1)
                points.add(GesturePoint(a.x + t * (b.x - a.x) + offset, a.y + t * (b.y - a.y) + offset))
            }
        }
        if (anchors.size == 1) points.add(GesturePoint(anchors[0].x, anchors[0].y))
        return points
    }

    /**
     * The same stroke with the finger holding still on [letter]'s key, and a
     * clock attached — a synthetic path has no timestamps at all, which reads
     * as "never paused anywhere" and is the right default.
     */
    private fun pausedAt(path: List<GesturePoint>, letter: Char): List<GesturePoint> {
        val key = centers.getValue(letter.code)
        var nearest = 0
        var best = Float.MAX_VALUE
        path.forEachIndexed { i, p ->
            val d = (p.x - key.x) * (p.x - key.x) + (p.y - key.y) * (p.y - key.y)
            if (d < best) {
                best = d
                nearest = i
            }
        }
        val out = ArrayList<GesturePoint>(path.size + HOLD_SAMPLES)
        var clock = 0L
        path.forEachIndexed { i, p ->
            out.add(GesturePoint(p.x, p.y, clock))
            clock += SAMPLE_MS
            if (i == nearest) {
                repeat(HOLD_SAMPLES) {
                    out.add(GesturePoint(p.x, p.y, clock))
                    clock += SAMPLE_MS
                }
            }
        }
        return out
    }

    /**
     * The same stroke with a small circle drawn on [letter]'s key: the finger
     * reaches the key, goes once round through it and carries on. No clock,
     * so the loop is geometry alone and no pause reading can help it.
     */
    private fun loopedAt(path: List<GesturePoint>, letter: Char): List<GesturePoint> {
        val key = centers.getValue(letter.code)
        var nearest = 0
        var best = Float.MAX_VALUE
        path.forEachIndexed { i, p ->
            val d = (p.x - key.x) * (p.x - key.x) + (p.y - key.y) * (p.y - key.y)
            if (d < best) {
                best = d
                nearest = i
            }
        }
        val at = path[nearest]
        val before = path[(nearest - 1).coerceAtLeast(0)]
        val after = path[(nearest + 1).coerceAtMost(path.lastIndex)]
        var dx = after.x - before.x
        var dy = after.y - before.y
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: 1f
        dx /= len
        dy /= len
        // Centre off to one side of the line of travel, so the circle passes
        // through the key rather than around it.
        val cx = at.x - dy * LOOP_RADIUS_PX
        val cy = at.y + dx * LOOP_RADIUS_PX
        val start = atan2(at.y - cy, at.x - cx)
        val out = ArrayList<GesturePoint>(path.size + LOOP_POINTS)
        path.forEachIndexed { i, p ->
            out.add(p)
            if (i == nearest) {
                for (s in 1..LOOP_POINTS) {
                    val angle = start + 2.0 * PI * s / LOOP_POINTS
                    out.add(
                        GesturePoint(
                            cx + LOOP_RADIUS_PX * cos(angle).toFloat(),
                            cy + LOOP_RADIUS_PX * sin(angle).toFloat(),
                            p.t,
                        ),
                    )
                }
            }
        }
        return out
    }

    /** The alignment cost [beam] charges [word] for [path]. */
    private fun costOf(word: String, path: List<GesturePoint>, beam: GlideBeam): Double =
        beam.decode(path, grid, keyWidth, sources, workspace, 8).first { it.word == word }.shapeCost

    private fun CharArray.distinctConsecutive(): List<Char> {
        val out = ArrayList<Char>()
        for (c in this) if (out.lastOrNull() != c) out.add(c)
        return out
    }

    @Test
    fun `perfect trace decodes the word`() {
        assertEquals("hello", decode(gestureFor("hello")).first())
    }

    @Test
    fun `double letters need no repeat in the path`() {
        // "good" traces g-o-d only, so both spellings must be on offer.
        val words = decode(gestureFor("god"))
        assertTrue("expected both spellings in $words", "good" in words && "god" in words)
    }

    @Test
    fun `a doubled letter needs the finger to have paused on it`() {
        // The stroke is identical either way — the o is one key, crossed once —
        // so the only thing that can separate "good" from "god" is whether the
        // finger hesitated there. "good" is the commoner word by frequency, and
        // that deliberately is not enough on its own.
        assertEquals("god", decode(gestureFor("god")).first())
        assertEquals("good", decode(pausedAt(gestureFor("god"), 'o')).first())
    }

    @Test
    fun `a pause on a key no letter claims counts against the word`() {
        // t, y and u sit in a row, so "tu" and "tyu" are the same straight
        // stroke and the same alignment cost; "tu" leads on frequency alone.
        val paused = GlideBeam(GlideBeam.Tuning(unclaimedDwell = 1f))
        val src = sourcesOf(listOf("tu" to 900, "tyu" to 300))
        val stroke = gestureFor("tyu")
        assertEquals("tu", paused.decode(stroke, grid, keyWidth, src, workspace, 4).first().word)
        // Resting on y is evidence the y was meant: "tu" has no letter within
        // reach of that pause and pays for it, "tyu" does and pays nothing.
        val rested = paused.decode(pausedAt(stroke, 'y'), grid, keyWidth, src, workspace, 4)
        assertEquals("tyu", rested.first().word)
    }

    @Test
    fun `a claimed pause costs nothing`() {
        // The charge is for pauses a word ignores, so a pause on one of its
        // own letters must leave its cost exactly where it was. A word with no
        // doubled letter, or the pause would also be waiving dwellPenalty.
        val paused = GlideBeam(GlideBeam.Tuning(unclaimedDwell = 1f))
        val untimed = paused.decode(gestureFor("help"), grid, keyWidth, sources, workspace, 4)
        val timed = paused.decode(pausedAt(gestureFor("help"), 'l'), grid, keyWidth, sources, workspace, 4)
        assertEquals("help", timed.first().word)
        assertEquals(untimed.first().shapeCost, timed.first().shapeCost, 1e-6)
    }

    @Test
    fun `the charge is off by default`() {
        // The stroke above, decoded by the shipped tuning: the default must be
        // whatever GestureEvalBaseline was measured with, and a test here says
        // so the day someone changes one without the other.
        val src = sourcesOf(listOf("tu" to 900, "tyu" to 300))
        val stroke = pausedAt(gestureFor("tyu"), 'y')
        val default = GlideBeam.Tuning().unclaimedDwell
        val leader = decode(stroke, from = src).first()
        assertEquals(if (default > 0f) "tyu" else "tu", leader)
    }

    @Test
    fun `a shape the user drew a word in lifts that word`() {
        // A wobbly stroke that says "god"; a store that remembers this very
        // stroke as how the user draws "good" makes "good" the reading. A
        // perfect trace would not do: its ideal distance is already nothing,
        // and a learned shape can only ever bring a word nearer than that.
        // The test also pins that the store's shape is the shape channel's
        // own normalisation of the stroke: distance zero to itself.
        val stroke = gestureFor("god", jitter = 14f)
        val shape = beam.sampleShape(stroke, keyWidth, workspace) ?: error("too short")
        val store = GlideShapeStore(null)
        val layout = GlideKeyMap.fingerprint(keys, keyWidth)
        store.learn(GlideShapeSample(layout, shape), "good")
        val learned = store.forLayout(layout) ?: error("nothing learned")
        assertEquals(0f, learned.minDistance("good", shape), 1e-6f)
        assertEquals("god", decode(stroke).first())
        assertEquals("good", beam.decode(stroke, grid, keyWidth, sources, workspace, 4, learned).first().word)
        // A store for another grid says nothing.
        val elsewhere = GlideShapeStore(null).forLayout(layout)
        assertEquals("god", beam.decode(stroke, grid, keyWidth, sources, workspace, 4, elsewhere).first().word)
    }

    @Test
    fun `a loop on a key reads as the doubled spelling`() {
        // Same stroke and the same clock, which is none: the only difference
        // is the circle drawn on the o, Swype's mark for a letter written twice.
        assertEquals("god", decode(gestureFor("god")).first())
        assertEquals("good", decode(loopedAt(gestureFor("god"), 'o')).first())
    }

    @Test
    fun `a loop outweighs frequency through the unclaimed charge`() {
        // With the single spelling the commoner word, the waiver alone only
        // ties the two on shape and frequency keeps "god". The charge on the
        // word that ignores the loop is what turns it.
        val src = sourcesOf(listOf("god" to 800, "good" to 300))
        val stroke = loopedAt(gestureFor("god"), 'o')
        val charged = GlideBeam(GlideBeam.Tuning(unclaimedLoop = 0.5f))
        val waived = GlideBeam(GlideBeam.Tuning(unclaimedLoop = 0f))
        assertEquals("good", charged.decode(stroke, grid, keyWidth, src, workspace, 4).first().word)
        assertEquals("god", waived.decode(stroke, grid, keyWidth, src, workspace, 4).first().word)
    }

    @Test
    fun `a loop does not penalise the single spelling more than before`() {
        // The loop's arc is collapsed out of the travel term, so "god" costs
        // about what it cost on the plain stroke — the window is a sample or
        // so short of the whole circle, and that much stays as travel.
        // Without the collapse the same arc is a detour no key distance
        // explains, and the word pays for all of it.
        val quiet = GlideBeam(GlideBeam.Tuning(unclaimedLoop = 0f))
        val plain = costOf("god", gestureFor("god"), quiet)
        val looped = loopedAt(gestureFor("god"), 'o')
        val collapsed = costOf("god", looped, quiet)
        val uncollapsed = costOf("god", looped, GlideBeam(GlideBeam.Tuning(loopExtent = 0f)))
        assertTrue("collapsed $collapsed against plain $plain", collapsed <= plain + 0.8)
        assertTrue("uncollapsed $uncollapsed against plain $plain", uncollapsed >= plain + 1.0)
    }

    @Test
    fun `a corner is not a loop`() {
        // p-o-p is the tightest reversal two adjacent keys allow: twice the
        // arc of its extent, where a loop needs two and a half. A zigzag has
        // arc to spare but its turns cancel. Only the circle reads.
        decode(gestureFor("pop"), from = sourcesOf(listOf("pop" to 100)))
        assertEquals(0, workspace.loopCount)
        decode(gestureFor("what", jitter = 14f))
        assertEquals(0, workspace.loopCount)
        decode(loopedAt(gestureFor("god"), 'o'))
        assertEquals(1, workspace.loopCount)
    }

    @Test
    fun `the loop charge default is what the baseline was measured with`() {
        val src = sourcesOf(listOf("god" to 800, "good" to 300))
        val stroke = loopedAt(gestureFor("god"), 'o')
        val default = GlideBeam.Tuning().unclaimedLoop
        assertEquals(if (default > 0f) "good" else "god", decode(stroke, from = src).first())
    }

    @Test
    fun `an alignment on a looped stroke still lands each key`() {
        val aligned = beam.align("good", loopedAt(gestureFor("god"), 'o'), grid, keyWidth, workspace)!!
        assertEquals(3, aligned.size)
        for (i in 0 until aligned.size) {
            val k = aligned.keys[i]
            val dx = aligned.x[i] - grid.keyX[k]
            val dy = aligned.y[i] - grid.keyY[k]
            assertTrue("key $k off by ($dx, $dy)", dx * dx + dy * dy < 0.2f * 0.2f)
        }
    }

    @Test
    fun `an alignment puts each key where the stroke passed it`() {
        val aligned = beam.align("hello", gestureFor("hello"), grid, keyWidth, workspace)!!
        // h-e-l-o: the doubled l is one visit.
        assertEquals(4, aligned.size)
        for (i in 0 until aligned.size) {
            val k = aligned.keys[i]
            val dx = aligned.x[i] - grid.keyX[k]
            val dy = aligned.y[i] - grid.keyY[k]
            // A perfect trace passes through every centre; the resampling grid
            // is what keeps this from being exact.
            assertTrue("key $k off by ($dx, $dy)", dx * dx + dy * dy < 0.2f * 0.2f)
        }
    }

    @Test
    fun `an alignment reads a consistent miss as that miss`() {
        val off = gestureFor("what").map { GesturePoint(it.x + 15f, it.y - 9f, it.t) }
        val aligned = beam.align("what", off, grid, keyWidth, workspace)!!
        for (i in 0 until aligned.size) {
            val k = aligned.keys[i]
            assertEquals(0.25f, aligned.x[i] - grid.keyX[k], 0.12f)
            assertEquals(-0.15f, aligned.y[i] - grid.keyY[k], 0.12f)
        }
    }

    @Test
    fun `a word the grid cannot spell has no alignment`() {
        assertEquals(null, beam.align("héllo", gestureFor("hello"), grid, keyWidth, workspace))
        assertEquals(null, beam.align("h", gestureFor("hello"), grid, keyWidth, workspace))
    }

    @Test
    fun `noisy trace still decodes`() {
        assertEquals("what", decode(gestureFor("what", jitter = 14f)).first())
    }

    @Test
    fun `travel between letters rules out a word that only hits the endpoints`() {
        // "ho" puts both its letters exactly where the "hello" stroke starts
        // and ends. Only the distance the finger covered in between separates
        // them, which is the whole reason the gap term exists.
        val words = decode(gestureFor("hello"))
        assertEquals("hello", words.first())
        assertTrue("ho should rank below hello, got $words", words.indexOf("ho") != 0)
    }

    @Test
    fun `frequency breaks shape ties`() {
        // t-h-e is a prefix of they/then/them paths; "the" must win on prior.
        assertEquals("the", decode(gestureFor("the")).first())
    }

    @Test
    fun `a weighted personal source can outrank the dictionary`() {
        // The user tier is how a learned habit reaches the glide decoder, the
        // same way it reaches the typing beam.
        val personal = sourcesOf(listOf("good" to 50)).map {
            FuzzyBeamSearch.WalkSource(it.walker, 4.0, FuzzyBeamSearch.Tier.USER)
        }
        assertEquals("god", decode(gestureFor("god")).first())
        assertEquals("good", decode(gestureFor("god"), from = sources + personal).first())
    }

    @Test
    fun `alternates include shape neighbours`() {
        val words = decode(gestureFor("hello"))
        assertTrue(words.size > 1)
        assertTrue("expected hell in $words", "hell" in words)
    }

    @Test
    fun `start anchor prunes distant words`() {
        assertFalse("the" in decode(gestureFor("was")))
    }

    @Test
    fun `custom tuning startRadius endRadius and nearRadius affect decoding`() {
        // Test with restrictive startRadius (0.1 key width)
        val strictBeam = GlideBeam(GlideBeam.Tuning(startRadius = 0.1f))
        val strictWorkspace = GlideWorkspace()
        // Start stroke offset from the 'h' key center (which is at 150f, 90f)
        val offsetStroke = gestureFor("hello").mapIndexed { i, p ->
            if (i == 0) GesturePoint(p.x + 20f, p.y + 20f) else p
        }
        val strictResult = strictBeam.decode(offsetStroke, grid, keyWidth, sources, strictWorkspace, 4)
        // With strict startRadius, the start key 'h' is not within startRadius (20px > 0.1 * 60px = 6px)
        assertFalse("hello should be pruned with strict startRadius", "hello" in strictResult.map { it.word })

        // With generous startRadius (2.0 key widths = 120px)
        val lenientBeam = GlideBeam(GlideBeam.Tuning(startRadius = 2.0f))
        val lenientWorkspace = GlideWorkspace()
        val lenientResult = lenientBeam.decode(offsetStroke, grid, keyWidth, sources, lenientWorkspace, 4)
        assertTrue("hello should be decoded with lenient startRadius", "hello" in lenientResult.map { it.word })
    }

    @Test
    fun `too short a path returns nothing`() {
        val h = centers.getValue('h'.code)
        val stub = listOf(
            GesturePoint(h.x, h.y),
            GesturePoint(h.x + 2, h.y),
            GesturePoint(h.x + 4, h.y),
        )
        assertTrue(decode(stub).isEmpty())
    }

    @Test
    fun `no sources returns nothing`() {
        assertTrue(decode(gestureFor("hello"), from = emptyList()).isEmpty())
    }

    @Test
    fun `words with unmapped characters are skipped`() {
        val accented = sourcesOf(listOf("thé" to 5000, "the" to 10))
        assertEquals(listOf("the"), decode(gestureFor("the"), from = accented))
    }

    @Test
    fun `limit caps the result count`() {
        assertTrue(decode(gestureFor("the"), limit = 2).size <= 2)
    }

    @Test
    fun `characters sharing a key are all reachable`() {
        // The Bengali case in miniature. Probhat puts ক and খ on one key, so a
        // stroke crossing it cannot say which was meant: both spellings have to
        // come back, and the language model has to be what separates them.
        //
        // The second alphabet is built by codepoint arithmetic rather than
        // written out, because Bengali literals in source have a history of
        // arriving decomposed and failing invisibly.
        val other = { index: Int -> BENGALI_KA + index }
        val doubled = keys + keys.mapIndexed { i, k -> KeyCenter(other(i), k.x, k.y) }
        val doubledGrid = GlideKeyMap.of(doubled, keyWidth)
        val twin = buildString {
            for (c in "the") append(other(keys.indexOfFirst { it.codePoint == c.code }))
        }
        val paired = sourcesOf(listOf("the" to 100, twin to 900))

        val words = beam.decode(gestureFor("the"), doubledGrid, keyWidth, paired, workspace, 4)
            .map { it.word }
        assertEquals("both spellings should survive, got $words", 2, words.size)
        assertEquals(twin, words.first())
    }

    @Test
    fun `a grid the word cannot be drawn on returns nothing for it`() {
        // Only the top row exists: nothing spelling "was" is reachable.
        val topRow = GlideKeyMap.of(keys.filter { it.y < 60f }, keyWidth)
        assertFalse("was" in decode(gestureFor("was"), on = topRow))
    }

    private companion object {
        /** U+0995 BENGALI LETTER KA, the base of the synthetic second alphabet. */
        const val BENGALI_KA = 'ক'

        /** A plausible digitizer interval, and a hold long enough to read as one. */
        const val SAMPLE_MS = 8L
        const val HOLD_SAMPLES = 30

        /** A loop a third of a key wide, drawn as two dozen points. */
        const val LOOP_RADIUS_PX = 18f
        const val LOOP_POINTS = 24
    }
}
