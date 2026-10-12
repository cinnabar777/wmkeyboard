package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Learned conversion picks. The store takes a nullable [java.io.File], so the
 * in-memory behaviour needs no filesystem and the persistence tests use a temp
 * folder — no Android dependencies either way.
 */
class CjkUserHistoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun reset() {
        CjkLearning.store = null
        CjkLearning.enabled = true
        CjkConfig.traditionalOutput = false
        HanVariant.s2t = HanPhraseTable.EMPTY
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.ngrams = CjkNgrams.EMPTY
        PinyinSyllables.valid = emptySet()
    }

    @After
    fun tearDown() = reset()

    @Test
    fun `a pick comes back for the same reading`() {
        val history = CjkUserHistory(null)
        assertEquals(0, history.countFor("pinyin", "ni", "尼"))
        history.learn("pinyin", "ni", "尼")
        history.learn("pinyin", "ni", "尼")
        assertEquals(2, history.countFor("pinyin", "ni", "尼"))
        // ...and only for that reading.
        assertEquals(0, history.countFor("pinyin", "hao", "尼"))
    }

    @Test
    fun `reading spaces do not overwrite each other`() {
        // The reason namespaces are reading spaces rather than languages: a
        // pinyin `ni` and a Cangjie `ni` are unrelated keys that happen to spell
        // the same letters, and Chinese has six such notations.
        val history = CjkUserHistory(null)
        history.learn("pinyin", "ni", "你")
        history.learn("cangjie", "ni", "呢")
        assertEquals(1, history.countFor("pinyin", "ni", "你"))
        assertEquals(0, history.countFor("pinyin", "ni", "呢"))
        assertEquals(1, history.countFor("cangjie", "ni", "呢"))
        history.clear("pinyin")
        assertTrue(history.isEmptyFor("pinyin"))
        assertFalse(history.isEmptyFor("cangjie"))
    }

    @Test
    fun `picks survive a reload`() {
        val file = folder.newFile("cjk_history.json")
        val first = CjkUserHistory(file)
        first.learn("ja_kana", "にほん", "日本")
        first.learn("ja_kana", "にほん", "日本")
        first.save()
        val second = CjkUserHistory(file)
        assertEquals(2, second.countFor("ja_kana", "にほん", "日本"))
        assertFalse(second.dirty)
    }

    @Test
    fun `an unreadable store degrades to remembering nothing`() {
        val file = folder.newFile("broken.json")
        file.writeText("{ this is not json")
        val history = CjkUserHistory(file)
        assertEquals(0, history.countFor("pinyin", "ni", "你"))
        // And it still works from here, rather than throwing on every commit.
        history.learn("pinyin", "ni", "你")
        assertEquals(1, history.countFor("pinyin", "ni", "你"))
    }

    @Test
    fun `ranking leaves unlearned order exactly alone`() {
        val history = CjkUserHistory(null)
        CjkLearning.store = history
        val items = listOf("你" to "ni", "尼" to "ni", "泥" to "ni")
        assertEquals(items, CjkLearning.rank("pinyin", items, { it.first }, { it.second }))
        // A learned pick floats up; everything else keeps its relative order.
        history.learn("pinyin", "ni", "泥")
        assertEquals(
            listOf("泥" to "ni", "你" to "ni", "尼" to "ni"),
            CjkLearning.rank("pinyin", items, { it.first }, { it.second }),
        )
        // Most-chosen leads among the learned ones.
        history.learn("pinyin", "ni", "尼")
        history.learn("pinyin", "ni", "尼")
        assertEquals(
            listOf("尼" to "ni", "泥" to "ni", "你" to "ni"),
            CjkLearning.rank("pinyin", items, { it.first }, { it.second }),
        )
        // Clearing restores the decoder's own order precisely.
        history.clear()
        assertEquals(items, CjkLearning.rank("pinyin", items, { it.first }, { it.second }))
    }

    // --- through the composer --------------------------------------------------

    private fun pinyinFixture() {
        PinyinSyllables.valid = setOf("ni", "hao")
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf("ni\t你\t900", "ni\t尼\t500", "ni\t泥\t100", "hao\t好\t900"),
        )
    }

    @Test
    fun `choosing a candidate promotes it next time`() {
        CjkLearning.store = CjkUserHistory(null)
        pinyinFixture()
        val before = PinyinComposer.candidates("ni")
        assertEquals(listOf("你", "尼", "泥"), before)
        // Pick the third one, the way a strip tap does.
        PinyinComposer.learnChoice("ni", 2)
        assertEquals("泥", PinyinComposer.candidates("ni").first())
        // The promotion is tied to the reading, so a different one is untouched.
        assertEquals("好", PinyinComposer.candidates("hao").first())
    }

    @Test
    fun `a promotion does not leak into another reading space`() {
        CjkLearning.store = CjkUserHistory(null)
        pinyinFixture()
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        PinyinComposer.learnChoice("ni", 2)
        assertEquals("泥", PinyinComposer.candidates("ni").first())
        // 64 is the T9 code for `ni`, but digits are their own notation and were
        // never the thing the user picked in.
        assertEquals("你", T9PinyinComposer.candidates("64").first())
    }

    @Test
    fun `learning off records nothing`() {
        CjkLearning.store = CjkUserHistory(null)
        CjkLearning.enabled = false
        pinyinFixture()
        PinyinComposer.learnChoice("ni", 2)
        assertEquals(listOf("你", "尼", "泥"), PinyinComposer.candidates("ni"))
    }

    @Test
    fun `no store at all changes nothing`() {
        CjkLearning.store = null
        pinyinFixture()
        PinyinComposer.learnChoice("ni", 2)
        assertEquals(listOf("你", "尼", "泥"), PinyinComposer.candidates("ni"))
    }

    @Test
    fun `a promoted candidate still commits only its own prefix`() {
        // The promotion reorders the list, and consumedForIndex resolves against
        // that same reordered list — so a re-ranked candidate must still report
        // the input length it actually covers.
        CjkLearning.store = CjkUserHistory(null)
        pinyinFixture()
        PinyinComposer.learnChoice("nihao", 1)
        val candidates = PinyinComposer.candidates("nihao")
        candidates.forEachIndexed { index, text ->
            val consumed = PinyinComposer.consumedForIndex("nihao", index)
            assertTrue("$text consumed $consumed", consumed in 1..5)
            assertEquals(text, PinyinComposer.consumedFor("nihao", text), consumed)
        }
    }
}
