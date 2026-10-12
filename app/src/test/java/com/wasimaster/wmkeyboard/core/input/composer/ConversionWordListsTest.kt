package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Imported word lists as conversion rows (#633): the formats people have for
 * Chinese, Cantonese and Japanese, and how the rows merge into a pack.
 */
class ConversionWordListsTest {

    @Before
    @After
    fun resetCjkGlobals() {
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        PinyinSyllables.valid = emptySet()
        Jianpin.index = Jianpin.EMPTY
        CjkConfig.jianpin = false
        CjkConfig.fuzzyPinyin = false
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        CjkConfig.traditionalOutput = false
    }

    private fun rows(text: String, reading: ConversionWordLists.Reading) =
        ConversionWordLists.rows(text.lineSequence(), reading)

    private fun ConversionDictionary.Row.pair() = reading to word

    @Test
    fun `a CC-CEDICT line gives the toneless pinyin and the Simplified word`() {
        val read = rows(
            """
            # CC-CEDICT
            關係 关系 [guan1 xi4] /relation/relationship/
            齊 齐 [qi2] /neat/even/
            女兒 女儿 [nu:3 er2] /daughter/
            """.trimIndent(),
            ConversionWordLists.Reading.PINYIN,
        )
        assertEquals(listOf("guanxi" to "关系", "qi" to "齐", "nver" to "女儿"), read.map { it.pair() })
    }

    @Test
    fun `a CC-Canto line gives the Jyutping and the Traditional word`() {
        val read = rows("你好 你好 [ni3 hao3] {nei5 hou2} /hello/", ConversionWordLists.Reading.JYUTPING)
        assertEquals(listOf("neihou" to "你好"), read.map { it.pair() })
        // A CC-CEDICT line has no Jyutping to read.
        assertTrue(rows("關係 关系 [guan1 xi4] /relation/", ConversionWordLists.Reading.JYUTPING).isEmpty())
    }

    @Test
    fun `a Rime dictionary reads below its header, spaced readings and all`() {
        val read = rows(
            """
            ---
            name: luna_pinyin
            version: "2023.01.01"
            sort: by_weight
            ...
            齊	qi	100
            關係	guan xi	50
            的	de	99%
            """.trimIndent(),
            ConversionWordLists.Reading.PINYIN,
        )
        assertEquals(listOf("qi" to "齊", "guanxi" to "關係", "de" to "的"), read.map { it.pair() })
        // Counts keep their order; a percentage scales the typical row.
        assertTrue(read[0].frequency > read[1].frequency)
        assertTrue(read[0].raises)
        assertEquals(ConversionWordLists.Scale.DEFAULT.typical * 99 / 100, read[2].frequency)
    }

    @Test
    fun `the app's own pack format reads as itself`() {
        val read = rows("nihao\t你好\t900", ConversionWordLists.Reading.PINYIN)
        assertEquals(listOf("nihao" to "你好"), read.map { it.pair() })
    }

    @Test
    fun `tone marks and the u-umlaut read as the packs spell them`() {
        val read = rows("绿色\tlǜ sè", ConversionWordLists.Reading.PINYIN)
        assertEquals(listOf("lvse" to "绿色"), read.map { it.pair() })
    }

    @Test
    fun `a Mozc user dictionary reads its kana reading as hiragana`() {
        val read = rows(
            "ありがとう\t有難う\t感動詞\nテスト\tテスト\t名詞",
            ConversionWordLists.Reading.KANA,
        )
        assertEquals(listOf("ありがとう" to "有難う", "てすと" to "テスト"), read.map { it.pair() })
    }

    @Test
    fun `a line with no reading or no word is skipped`() {
        val text = "你好\nhello world 5\n# comment\n\n"
        assertTrue(rows(text, ConversionWordLists.Reading.PINYIN).isEmpty())
        assertEquals(0, ConversionWordLists.count(text.lineSequence(), ConversionWordLists.Reading.PINYIN))
    }

    @Test
    fun `merging keeps a pack word's frequency unless the list ranks its own`() {
        val pack = ConversionDictionary.parse(sequenceOf("qi\t起\t900", "qi\t齐\t50"))
        val merged = pack.plus(
            listOf(
                ConversionDictionary.Row("qi", "齐", 300),
                ConversionDictionary.Row("qi", "其", 10),
            ),
        )
        assertEquals(listOf("起", "齐", "其"), merged.exact("qi"))
        assertEquals(50, merged.frequency(merged.rowsFor("qi").first { merged.word(it) == "齐" }))

        val raised = pack.plus(listOf(ConversionDictionary.Row("qi", "齐", 950, raises = true)))
        assertEquals(listOf("齐", "起"), raised.exact("qi"))
    }

    @Test
    fun `nothing new leaves the table as it was`() {
        val pack = ConversionDictionary.parse(sequenceOf("qi\t起\t900"))
        assertSame(pack, pack.plus(listOf(ConversionDictionary.Row("qi", "起", 10))))
    }

    @Test
    fun `an imported list alone is enough for pinyin to convert`() {
        PinyinSyllables.valid = setOf("guan", "xi", "qi")
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY.plus(
            rows("關係 关系 [guan1 xi4] /relation/\n齊 齐 [qi2] /neat/", ConversionWordLists.Reading.PINYIN),
        )
        assertNull(PinyinComposer.missingPack)
        assertEquals("关系", PinyinComposer.candidates("guanxi").first())
        assertEquals("齐", PinyinComposer.candidates("qi").first())
    }

    @Test
    fun `a list's rows land on the pack's scale`() {
        val pack = ConversionDictionary.parse((1..9).map { "a\tw$it\t${it * 1000}" }.asSequence())
        val scale = ConversionWordLists.Scale.of(pack)
        assertEquals(9000, scale.top)
        assertEquals(5000, scale.typical)
        val read = ConversionWordLists.rows(sequenceOf("齐\tqi"), ConversionWordLists.Reading.PINYIN, scale)
        assertEquals(5000, read.single().frequency)
    }
}
