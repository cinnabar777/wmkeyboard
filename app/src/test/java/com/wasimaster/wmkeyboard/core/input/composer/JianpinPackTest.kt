package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Jianpin against the real pinyin pack — the issue's own examples (#405) and
 * the cost of decoding a run of bare initials, which the fixture tests cannot
 * speak to. The pack is host-only, so this runs only where the data repo is
 * checked out beside the app and is skipped everywhere else.
 */
class JianpinPackTest {

    private val packFile = File(System.getProperty("user.home"), "Work/wmkeyboard-data/cjk/pinyin.tsv")

    @Before
    fun load() {
        assumeTrue("pinyin pack not on this machine", packFile.isFile)
        val asset = File("src/main/assets/dictionaries/pinyin_syllables.txt")
        assumeTrue(asset.isFile)
        PinyinSyllables.valid = PinyinSyllables.parse(asset.readText().lineSequence())
        Jianpin.index = Jianpin.build(PinyinSyllables.valid)
        CjkDictionaries.pinyin = packFile.bufferedReader().useLines(ConversionDictionary::parse)
        CjkConfig.jianpin = true
        CjkConfig.fuzzyPinyin = false
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
    }

    @After
    fun reset() {
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        PinyinSyllables.valid = emptySet()
        Jianpin.index = Jianpin.EMPTY
    }

    @Test
    fun `the issue's examples resolve against the shipped pack`() {
        assertEquals("我们", PinyinComposer.candidates("wm").first())
        assertTrue("北京" in PinyinComposer.candidates("bj").take(3))
        assertTrue("中国" in PinyinComposer.candidates("zg").take(3))
        assertTrue("上海" in PinyinComposer.candidates("sh", 100))
        // CC-CEDICT carries no 好的 entry, and two initials mean a phrase: the
        // pack's hundreds of h…d… words (回答, 活动, 后代…) lead, each covering
        // the buffer. A lone 好 is what `h` on its own is for.
        val hd = PinyinComposer.candidates("hd")
        assertEquals(hd.toString(), 2, PinyinComposer.consumedFor("hd", hd.first()))
        assertTrue("好" in PinyinComposer.candidates("h"))
        // With the first syllable spelled out, a phrase covering the buffer
        // leads (好多 is a real entry and outranks the stitched 好 + 的).
        val haod = PinyinComposer.candidates("haod")
        assertEquals(haod.toString(), 4, PinyinComposer.consumedFor("haod", haod.first()))
        assertTrue(haod.toString(), haod.first().startsWith("好"))
        assertTrue("中国" in PinyinComposer.candidates("zguo").take(3))
        assertTrue("北京" in PinyinComposer.candidates("beij").take(3))
    }

    @Test
    fun `a run of initials decodes within the frame budget`() {
        val buffers = listOf("hd", "zgrm", "wsygzgr", "nihaoz")
        for (b in buffers) PinyinComposer.candidates(b) // warm-up
        for (b in buffers) {
            CjkDictionaries.invalidate() // drop the one-entry cache
            val t0 = System.nanoTime()
            val cands = PinyinComposer.candidates(b)
            val ms = (System.nanoTime() - t0) / 1e6
            println("jianpin $b → ${cands.take(4)} in ${"%.1f".format(ms)} ms")
            assertTrue(cands.isNotEmpty())
            // Generous: a JVM run, a cold JIT and a shared CI box all count.
            assertTrue("$b took $ms ms", ms < 200.0)
        }
    }
}
