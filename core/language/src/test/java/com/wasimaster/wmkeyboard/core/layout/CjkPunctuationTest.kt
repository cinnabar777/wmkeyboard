package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Japanese and Chinese layouts borrow the shipped symbol pages, which carry
 * their punctuation in place of the Latin-only marks (issue #610).
 */
class CjkPunctuationTest {
    private fun spec(langId: String, vararg own: Pair<String, LayerSpec>) = LayoutSpec(
        id = "test_$langId",
        name = "Test",
        langId = langId,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))))) + own,
    )

    /** Every mark on [layer], tapped or held. */
    private fun LayoutSpec.reachable(layer: LayoutLayer): Set<String> =
        compile(layer).rows.flatten().flatMap { listOf(it.label) + it.longPress }.toSet()

    private fun LayoutSpec.faces(layer: LayoutLayer): Set<String> =
        compile(layer).rows.flatten().map { it.label }.toSet()

    @Test
    fun `japanese reaches every mark of the issue's table`() {
        val ja = spec("ja")
        val all = ja.reachable(LayoutLayer.SYMBOLS) + ja.reachable(LayoutLayer.SYMBOLS_SHIFTED)
        for (mark in listOf("、", "。", "？", "！", "「", "」", "『", "』", "（", "）", "【", "】", "［", "］", "・", "…", "—", "〜")) {
            assertTrue(mark, mark in all)
        }
        // The corner brackets are keys of their own, not holds.
        assertTrue(ja.faces(LayoutLayer.SYMBOLS).containsAll(listOf("「", "」")))
        assertTrue(ja.faces(LayoutLayer.SYMBOLS_SHIFTED).containsAll(listOf("『", "』")))
    }

    @Test
    fun `chinese reaches both quote conventions and the title marks`() {
        for (lang in listOf("zh", "yue")) {
            val zh = spec(lang)
            val all = zh.reachable(LayoutLayer.SYMBOLS) + zh.reachable(LayoutLayer.SYMBOLS_SHIFTED)
            for (mark in listOf("，", "。", "、", "？", "！", "：", "；", "“", "”", "‘", "’", "「", "」", "『", "』", "（", "）", "【", "】", "《", "》", "〈", "〉", "·", "……", "——")) {
                assertTrue("$lang $mark", mark in all)
            }
            assertTrue(zh.faces(LayoutLayer.SYMBOLS).containsAll(listOf("“", "”", "、")))
            assertTrue(zh.faces(LayoutLayer.SYMBOLS_SHIFTED).containsAll(listOf("《", "》", "「", "」")))
        }
    }

    @Test
    fun `the ascii marks stay reachable and the latin-only ones go`() {
        val symbols = spec("zh").reachable(LayoutLayer.SYMBOLS)
        for (mark in listOf("\"", "'", ":", ";", "!", "?", "(", ")", "*", ",", ".")) {
            assertTrue(mark, mark in symbols)
        }
        assertFalse("¿" in symbols)
        assertFalse("¡" in symbols)
    }

    @Test
    fun `other languages and a layout's own symbols page are untouched`() {
        // Spanish: no currency swap of its own either, so it is the default page as shipped.
        for (layer in listOf(LayoutLayer.SYMBOLS, LayoutLayer.SYMBOLS_SHIFTED)) {
            assertEquals(BuiltInLayouts.default.compile(layer).rows, spec("es").compile(layer).rows)
        }
        val own = LayerSpec(listOf(listOf(Key("\""), Key("?"))))
        val ja = spec("ja", LayoutLayer.SYMBOLS.key to own)
        assertEquals(listOf("\"", "?"), ja.compile(LayoutLayer.SYMBOLS).rows.flatten().map { it.label })
    }
}
