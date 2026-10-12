package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Cantonese lazy pronunciation (懶音). */
class JyutpingFuzzyTest {

    /** The real inventory, so the validity filter is the shipped one. */
    private val inventory: Set<String> by lazy {
        val asset = File("src/main/assets/dictionaries/jyutping_syllables.txt").readText()
        JyutpingSyllables.parse(asset.lineSequence())
            .also { assertTrue("jyutping inventory missing", it.size > 400) }
    }

    @Before
    fun reset() {
        CjkConfig.lazyJyutping = false
        CjkConfig.traditionalOutput = false
        HanVariant.s2t = HanPhraseTable.EMPTY
        CjkDictionaries.jyutping = ConversionDictionary.EMPTY
        CjkDictionaries.ngrams = CjkNgrams.EMPTY
        JyutpingSyllables.valid = emptySet()
        CjkLearning.store = null
    }

    @After
    fun tearDown() = reset()

    @Test
    fun `the n-l merger goes both ways`() {
        // 你 nei5 said as lei5, and the hypercorrection 靚 leng3 said as neng3.
        assertTrue("lei" in JyutpingFuzzy.expand("nei", inventory))
        assertTrue("nei" in JyutpingFuzzy.expand("lei", inventory))
        assertTrue("neng" in JyutpingFuzzy.expand("leng", inventory))
    }

    @Test
    fun `the ng initial drops and is hypercorrected back on`() {
        assertTrue("o" in JyutpingFuzzy.expand("ngo", inventory))   // 我 ngo5 → o5
        assertTrue("ngoi" in JyutpingFuzzy.expand("oi", inventory)) // 愛 oi3 → ngoi3
    }

    @Test
    fun `a bare syllabic ng never expands to nothing`() {
        // 吳 is ng4 — dropping the initial would leave an empty syllable.
        val expanded = JyutpingFuzzy.expand("ng", inventory)
        assertFalse("" in expanded)
        assertTrue(expanded.all { it.isNotEmpty() })
    }

    @Test
    fun `labialised initials collapse only before a rounded final`() {
        // 國 gwok3 → gok3 and 廣 gwong2 → gong2 are real...
        assertTrue("gok" in JyutpingFuzzy.expand("gwok", inventory))
        assertTrue("gong" in JyutpingFuzzy.expand("gwong", inventory))
        // ...but 誇 kwaa1 never becomes kaa1, and a flat initial group would say
        // it does. This is the whole reason the rule is conditional.
        assertFalse("kaa" in JyutpingFuzzy.expand("kwaa", inventory))
        assertFalse("gaa" in JyutpingFuzzy.expand("gwaa", inventory))
    }

    @Test
    fun `coda mergers collapse toward the alveolar`() {
        assertTrue("han" in JyutpingFuzzy.expand("hang", inventory)) // 恒 -ng → -n
        assertTrue("sin" in JyutpingFuzzy.expand("sing", inventory)) // 星 -ng → -n
        assertTrue("gan" in JyutpingFuzzy.expand("gam", inventory))  // 感 -m → -n
        assertTrue("sat" in JyutpingFuzzy.expand("sak", inventory))  // 塞 -k → -t
        assertTrue("gat" in JyutpingFuzzy.expand("gap", inventory))  // 急 -p → -t
    }

    @Test
    fun `a final with no counterpart is left alone`() {
        // -oeng has no -oen to merge into, so 香 keeps its ending rather than
        // being offered a spelling Jyutping does not have.
        assertFalse("hoen" in JyutpingFuzzy.expand("hoeng", inventory))
    }

    @Test
    fun `expansion only ever yields real syllables`() {
        for (syllable in inventory) {
            for (variant in JyutpingFuzzy.expand(syllable, inventory)) {
                assertTrue("$syllable -> $variant", variant == syllable || variant in inventory)
            }
        }
    }

    @Test
    fun `with no inventory the syllable comes back alone`() {
        assertEquals(setOf("nei"), JyutpingFuzzy.expand("nei", emptySet()))
        assertEquals(emptySet<String>(), JyutpingFuzzy.expand("", inventory))
    }

    @Test
    fun `the composer finds a word typed the lazy way`() {
        JyutpingSyllables.valid = setOf("nei", "hou", "lei")
        CjkDictionaries.jyutping = ConversionDictionary.parse(
            sequenceOf("nei\t你\t900", "hou\t好\t800", "neihou\t你好\t500"),
        )
        // Off, the merged spelling finds nothing.
        assertEquals(emptyList<String>(), JyutpingComposer.candidates("leihou"))
        CjkConfig.lazyJyutping = true
        val candidates = JyutpingComposer.candidates("leihou")
        assertTrue("got $candidates", "你好" in candidates)
        // Tones stay optional and the consumed length still counts input chars.
        assertEquals(6, JyutpingComposer.consumedFor("leihou", "你好"))
    }

    @Test
    fun `the spelling actually typed still leads`() {
        JyutpingSyllables.valid = setOf("nei", "lei")
        CjkDictionaries.jyutping = ConversionDictionary.parse(
            sequenceOf("nei\t你\t300", "lei\t李\t100"),
        )
        CjkConfig.lazyJyutping = true
        assertEquals("李", JyutpingComposer.candidates("lei").first())
        assertEquals("你", JyutpingComposer.candidates("nei").first())
    }
}
