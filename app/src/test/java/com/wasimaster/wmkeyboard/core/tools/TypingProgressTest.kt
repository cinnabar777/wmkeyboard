package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/** Levels, achievements and the tap heatmap on the Statistics screen (issue #390). */
class TypingProgressTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "stats/typing_stats.json")

    private fun store(target: File? = file()): TypingStats =
        TypingStats(target) { TimeZone.getTimeZone("UTC") }.also { it.enabled = true }

    private val noon = 1_754_000_000_000L

    private fun totals(
        words: Long = 0,
        saved: Long = 0,
        predicted: Long = 0,
        completed: Long = 0,
        glide: Long = 0,
        distanceMm: Double = 0.0,
    ) = TypingStats.Totals(
        chars = words * 5, words = words, backspaces = 0, activeMs = 0, hourHistogram = emptyList(),
        keystrokesSaved = saved, wordsPredicted = predicted, wordsCompleted = completed,
        glideWords = glide, glideDistanceMm = distanceMm,
    )

    @Test
    fun `a prediction, a completion and a correction count apart`() {
        val stats = store(null)
        // Nothing typed, "the" and its space landed: four taps for one.
        stats.onWordsPicked(1, typed = 0, letters = 3, spaced = true, nowMillis = noon)
        // "hel" typed, "hello " landed: three taps for one.
        stats.onWordsPicked(1, typed = 3, letters = 5, spaced = true, nowMillis = noon)
        // "teh" typed, "the" picked: a fix, not a completion.
        stats.onWordsPicked(1, typed = 3, letters = 3, spaced = true, nowMillis = noon)
        val totals = stats.lifetime()
        assertEquals(3, totals.words)
        assertEquals(1, totals.wordsPredicted)
        assertEquals(1, totals.wordsCompleted)
        assertEquals(3 + 2, totals.keystrokesSaved)
    }

    @Test
    fun `a glided word saves all but one tap and adds its stroke`() {
        val stats = store(null)
        stats.onGlideWord(letters = 5, distanceMm = 42.5, nowMillis = noon)
        stats.onGlideWord(letters = 1, distanceMm = Double.NaN, nowMillis = noon)
        val totals = stats.lifetime()
        assertEquals(2, totals.words)
        assertEquals(2, totals.glideWords)
        assertEquals(4, totals.keystrokesSaved)
        assertEquals(42.5, totals.glideDistanceMm, 1e-9)
    }

    @Test
    fun `nothing is counted while recording is off`() {
        val stats = store(null)
        stats.enabled = false
        stats.onWordsPicked(1, 0, 3, true, noon)
        stats.onGlideWord(4, 10.0, noon)
        stats.onKeyTap("qwerty", "English", 1f, 1f, listOf(TypingStats.HeatKey("q", 0.5f, 0.5f)))
        assertEquals(0, stats.lifetime().words)
        assertTrue(stats.heatmaps().isEmpty())
    }

    @Test
    fun `the tallies and the heatmap survive the round trip and the delete`() {
        val keys = listOf(TypingStats.HeatKey("q", 0.5f, 0.5f), TypingStats.HeatKey("w", 1.5f, 0.5f))
        val first = store()
        first.onWordsPicked(1, 0, 3, true, noon)
        first.onGlideWord(5, 30.0, noon)
        first.onKeyTap("qwerty", "English (US)", 0.4f, 0.6f, keys)
        first.onKeyTap("qwerty", "English (US)", 0.45f, 0.55f, keys)
        first.save()
        val second = store()
        assertEquals(first.lifetime(), second.lifetime())
        assertEquals(first.heatmaps(), second.heatmaps())
        val map = second.heatmaps().single()
        assertEquals("English (US)", map.name)
        assertEquals(2, map.taps)
        assertEquals(keys, map.keys)
        second.clear()
        assertEquals(0, store().lifetime().glideWords)
        assertTrue(store().heatmaps().isEmpty())
    }

    @Test
    fun `the least tapped board makes room past the cap`() {
        val stats = store(null)
        for (board in 0 until TypingStats.MAX_HEATMAPS) {
            repeat(board + 2) { stats.onKeyTap("layout$board", "", 1f, 1f, emptyList()) }
        }
        stats.onKeyTap("new", "", 1f, 1f, emptyList())
        val ids = stats.heatmaps().map { it.layoutId }
        assertEquals(TypingStats.MAX_HEATMAPS, ids.size)
        assertFalse("layout0" in ids)
        assertTrue("new" in ids)
        assertEquals("most tapped first", "layout${TypingStats.MAX_HEATMAPS - 1}", ids.first())
    }

    @Test
    fun `achievement steps and the next target`() {
        val none = TypingProgress.state(TypingProgress.Achievement.KEYSTROKES_SAVED, totals())
        assertEquals(0, none.steps)
        assertEquals(200, none.target)
        assertEquals(0f, none.fraction)

        val some = TypingProgress.state(TypingProgress.Achievement.KEYSTROKES_SAVED, totals(saved = 1_500))
        assertEquals(2, some.steps)
        assertEquals(5_000, some.target)
        assertEquals(0.3f, some.fraction, 1e-6f)

        val all = TypingProgress.state(TypingProgress.Achievement.WORDS_COMPLETED, totals(completed = 5_000_000))
        assertTrue(all.finished)
        assertEquals(100_000, all.target)
        assertEquals(1f, all.fraction)
    }

    @Test
    fun `glide distance is judged in whole metres`() {
        val state = TypingProgress.state(
            TypingProgress.Achievement.GLIDE_DISTANCE, totals(distanceMm = 20_999.0),
        )
        assertEquals(20, state.value)
        assertEquals(1, state.steps)
        assertEquals(100, state.target)
    }

    @Test
    fun `xp adds a bonus for help from the keyboard and for each step`() {
        // 600 words (one step of Words typed), 250 of them predicted (one step).
        val totals = totals(words = 600, predicted = 250)
        assertEquals(600 + 250 + 2 * TypingProgress.STEP_XP, TypingProgress.xp(totals))
    }

    @Test
    fun `levels start at growing thresholds and the closed form agrees`() {
        assertEquals(0, TypingProgress.levelStart(1))
        assertEquals(500, TypingProgress.levelStart(2))
        assertEquals(1_500, TypingProgress.levelStart(3))
        assertEquals(3_000, TypingProgress.levelStart(4))
        assertEquals(1, TypingProgress.levelOf(0))
        assertEquals(1, TypingProgress.levelOf(499))
        assertEquals(2, TypingProgress.levelOf(500))
        assertEquals(3, TypingProgress.levelOf(2_999))
        assertEquals(4, TypingProgress.levelOf(3_000))
        for (xp in listOf(1L, 77L, 12_345L, 999_999L, 123_456_789L)) {
            val level = TypingProgress.levelOf(xp)
            assertTrue(TypingProgress.levelStart(level) <= xp)
            assertTrue(TypingProgress.levelStart(level + 1) > xp)
        }
    }

    @Test
    fun `level names follow their bands`() {
        assertEquals(TypingProgress.Title.BEGINNER, TypingProgress.titleOf(1))
        assertEquals(TypingProgress.Title.BEGINNER, TypingProgress.titleOf(2))
        assertEquals(TypingProgress.Title.NOVICE, TypingProgress.titleOf(3))
        assertEquals(TypingProgress.Title.LEGEND, TypingProgress.titleOf(500))
        val level = TypingProgress.level(totals(words = 700))
        assertEquals(2, level.number)
        assertEquals(500, level.floor)
        assertEquals(1_500, level.next)
        assertEquals((800 - 500) / 1_000f, level.fraction, 1e-6f)
    }

    @Test
    fun `cells pack and unpack around their centre`() {
        val cell = TypingHeatmapMath.cellOf(2.3f, 1.1f)
        val (x, y) = TypingHeatmapMath.centreOf(cell)
        assertEquals(2.375f, x, 1e-6f)
        assertEquals(1.125f, y, 1e-6f)
        // Off the top-left edge clamps into the first cell rather than wrapping.
        assertEquals(TypingHeatmapMath.cellOf(0f, 0f), TypingHeatmapMath.cellOf(-3f, -1f))
    }

    @Test
    fun `row pitch ignores staggered keys on one row`() {
        assertEquals(1.5f, TypingHeatmapMath.rowPitch(listOf(0.75f, 0.75f, 0.76f, 2.26f, 3.76f)), 1e-6f)
        assertEquals(1f, TypingHeatmapMath.rowPitch(listOf(0.5f, 0.5f)), 1e-6f)
        assertEquals(1f, TypingHeatmapMath.rowPitch(emptyList()), 1e-6f)
    }

    @Test
    fun `top keys take the nearest letter and skip far taps`() {
        val keys = listOf(
            TypingStats.HeatKey("a", 0.5f, 0.5f),
            TypingStats.HeatKey("s", 1.5f, 0.5f),
        )
        val cells = mapOf(
            TypingHeatmapMath.cellOf(0.5f, 0.5f) to 3L,
            TypingHeatmapMath.cellOf(1.6f, 0.4f) to 5L,
            TypingHeatmapMath.cellOf(0.3f, 0.3f) to 4L,
            // The space bar, two rows down: no letter's tap.
            TypingHeatmapMath.cellOf(1.0f, 2.5f) to 50L,
        )
        val map = TypingStats.Heatmap("qwerty", "", 62, cells, keys)
        assertEquals(listOf("a" to 7L, "s" to 5L), TypingHeatmapMath.topKeys(map, 5))
        assertEquals(listOf("a" to 7L), TypingHeatmapMath.topKeys(map, 1))
    }
}
