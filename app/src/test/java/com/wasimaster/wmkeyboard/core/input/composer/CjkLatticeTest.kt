package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The conversion decoder. Dictionaries are built from TSV string literals, the
 * way [ConversionDictionaryTest] and [CjkComposerTest] already do, so none of
 * this needs a device.
 *
 * Stand-in "words" are real Hanzi here rather than ASCII, because the decoder
 * relies on one character spelling one syllable and an ASCII placeholder like
 * `NH` quietly violates that.
 */
class CjkLatticeTest {

    @Before
    fun reset() {
        CjkConfig.fuzzyPinyin = false
        CjkConfig.jianpin = true
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        CjkConfig.traditionalOutput = false
        HanVariant.s2t = emptyMap()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.ngrams = CjkNgrams.EMPTY
        PinyinSyllables.valid = emptySet()
        Jianpin.index = Jianpin.EMPTY
    }

    @After
    fun tearDown() = reset()

    // --- the dictionary API the decoder is built on ---------------------------

    private val sample = ConversionDictionary.parse(
        sequenceOf(
            "ni\t你\t900",
            "ni\t尼\t100",
            "nihao\t你好\t500",
            "hao\t好\t800",
        ),
    )

    @Test
    fun `rowsFor finds the exact run and nothing else`() {
        val rows = sample.rowsFor("ni")
        assertEquals(2, rows.count())
        assertEquals(setOf("你", "尼"), rows.map { sample.word(it) }.toSet())
        assertTrue(sample.rowsFor("zzz").isEmpty())
        // "ni" must not pick up "nihao", which merely starts with it.
        assertFalse(rows.any { sample.word(it) == "你好" })
    }

    @Test
    fun `prefixRange narrows and prunes`() {
        val ni = sample.prefixRange("ni")
        // ni, ni, nihao — the exact rows plus the longer reading behind them.
        assertEquals(3, ni.count())
        // Narrowing within an existing range keeps only the deeper matches.
        val niha = sample.prefixRange("niha", ni)
        assertEquals(1, niha.count())
        assertEquals("你好", sample.word(niha.first))
        // The prune the decoder relies on: no reading starts this way.
        assertTrue(sample.prefixRange("nix", ni).isEmpty())
        assertTrue(sample.prefixRange("zzz").isEmpty())
    }

    @Test
    fun `row accessors agree with the string ones`() {
        val row = sample.rowsFor("nihao").first
        assertEquals("你好", sample.word(row))
        assertEquals(2, sample.wordLength(row))
        assertEquals(500, sample.frequency(row))
        assertEquals(900L + 100 + 500 + 800, sample.totalFreq)
    }

    // --- decoding -------------------------------------------------------------

    private fun pinyin(vararg rows: String) {
        CjkDictionaries.pinyin = ConversionDictionary.parse(rows.asSequence())
    }

    @Test
    fun `the decoded sentence leads when no single word covers the buffer`() {
        PinyinSyllables.valid = setOf("wo", "chi", "fan")
        pinyin("wo\t我\t900", "chi\t吃\t500", "fan\t饭\t400")
        val candidates = PinyinComposer.candidates("wochifan")
        // No entry spells the whole reading, so the sentence is stitched from
        // three words — the thing the old longest-prefix ranking could not do.
        assertEquals("我吃饭", candidates.first())
        assertEquals(8, PinyinComposer.consumedFor("wochifan", "我吃饭"))
    }

    @Test
    fun `a word that covers the buffer beats stitching it together`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        pinyin("ni\t你\t900", "hao\t好\t800", "nihao\t你好\t500")
        assertEquals("你好", PinyinComposer.candidates("nihao").first())
    }

    @Test
    fun `first words are ranked by what the rest of the buffer can do`() {
        // 长 is far commoner than 常, but only 常 leaves a real word behind it.
        PinyinSyllables.valid = setOf("chang", "shi")
        pinyin(
            "chang\t长\t900",
            "chang\t常\t50",
            "changshi\t常识\t400",
            "shi\t是\t800",
        )
        val candidates = PinyinComposer.candidates("changshi")
        assertEquals("常识", candidates.first())
        assertEquals(8, PinyinComposer.consumedFor("changshi", "常识"))
    }

    @Test
    fun `every candidate consumes a real prefix of the buffer`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        pinyin("ni\t你\t900", "ni\t尼\t100", "hao\t好\t800", "nihao\t你好\t500")
        val buffer = "nihao"
        val candidates = PinyinComposer.candidates(buffer)
        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            val consumed = PinyinComposer.consumedFor(buffer, candidate)
            assertTrue("$candidate consumed $consumed", consumed in 1..buffer.length)
        }
        // Candidate texts are unique, so resolving one by text is unambiguous.
        assertEquals(candidates.size, candidates.toSet().size)
    }

    @Test
    fun `consumedForIndex agrees with resolving the same candidate by text`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        pinyin("ni\t你\t900", "ni\t尼\t100", "hao\t好\t800", "nihao\t你好\t500")
        val candidates = PinyinComposer.candidates("nihao")
        candidates.forEachIndexed { index, text ->
            assertEquals(text, PinyinComposer.consumedFor("nihao", text), PinyinComposer.consumedForIndex("nihao", index))
        }
    }

    @Test
    fun `a word too short for its span is not an answer`() {
        // One Hanzi is one syllable, so 现 cannot cover the two the user spelled
        // out — that is what makes the apostrophe worth typing.
        PinyinSyllables.valid = setOf("xi", "an", "xian")
        pinyin("xian\t现\t911", "xian\t西安\t7")
        assertEquals(listOf("西安"), PinyinComposer.candidates("xi'an"))
        // Typed as one syllable it is merely under-segmented, so both stand.
        assertEquals(listOf("现", "西安"), PinyinComposer.candidates("xian"))
    }

    @Test
    fun `an unknown syllable still leaves the known ones convertible`() {
        PinyinSyllables.valid = setOf("ni", "zzz")
        pinyin("ni\t你\t900")
        // The raw reading is never offered as a conversion...
        val candidates = PinyinComposer.candidates("ni")
        assertEquals(listOf("你"), candidates)
        // ...and a buffer nothing converts yields nothing rather than echoing.
        PinyinSyllables.valid = setOf("zzz")
        assertEquals(emptyList<String>(), PinyinComposer.candidates("zzz"))
    }

    @Test
    fun `a candidate past the strip still commits only its own prefix`() {
        // The bug the expanded grid would otherwise ship with: consumedFor used
        // to rank only as deep as the strip, so a candidate the grid could show
        // but the ranking could not find fell through to "consumed everything"
        // and ate the rest of the reading.
        PinyinSyllables.valid = setOf("ni", "hao")
        val rows = (1..40).map { "ni\t${(0x4E00 + it).toChar()}\t${1000 - it}" } + "hao\t好\t900"
        CjkDictionaries.pinyin = ConversionDictionary.parse(rows.asSequence())
        val wide = PinyinComposer.candidates("nihao", 40)
        assertTrue("expected a deep list, got ${wide.size}", wide.size > 12)
        val deep = wide[20]
        assertEquals(2, PinyinComposer.consumedFor("nihao", deep))
        assertEquals(2, PinyinComposer.consumedForIndex("nihao", 20))
    }

    @Test
    fun `every conversion composer widens without reordering`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        ZhuyinSyllables.table = ZhuyinSyllables.buildTable(PinyinSyllables.valid)
        JyutpingSyllables.valid = setOf("nei", "hou")
        val rows = (1..20).map { "ni\t${(0x4E00 + it).toChar()}\t${1000 - it}" } + "hao\t好\t900"
        CjkDictionaries.pinyin = ConversionDictionary.parse(rows.asSequence())
        CjkDictionaries.japanese = ConversionDictionary.parse(
            ((1..20).map { "に\t${(0x4E00 + it).toChar()}\t${1000 - it}" }).asSequence(),
        )
        CjkDictionaries.jyutping = ConversionDictionary.parse(
            ((1..20).map { "nei\t${(0x4E00 + it).toChar()}\t${1000 - it}" }).asSequence(),
        )
        val cases = listOf<Pair<Composer, String>>(
            PinyinComposer to "nihao",
            T9PinyinComposer to "64426",
            ZhuyinComposer to "ㄋㄧㄏㄠ",
            JyutpingComposer to "neihou",
            JapaneseComposer to "ni",
        )
        for ((composer, buffer) in cases) {
            val wide = composer.candidates(buffer, 40)
            val narrow = composer.candidates(buffer, 3)
            assertEquals(composer.toString(), narrow, wide.take(narrow.size))
        }
    }

    @Test
    fun `candidates widen without reordering what the strip already showed`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        pinyin(
            "ni\t你\t900", "ni\t尼\t800", "ni\t泥\t700", "ni\t逆\t600",
            "ni\t匿\t500", "ni\t妮\t400", "hao\t好\t300", "nihao\t你好\t200",
        )
        val wide = PinyinComposer.candidates("nihao", 40)
        val narrow = PinyinComposer.candidates("nihao", 3)
        // The strip and the expanded grid must agree on which candidate is which.
        assertEquals(narrow, wide.take(narrow.size))
    }

    // --- fuzzy ----------------------------------------------------------------

    @Test
    fun `fuzzy variants are reachable even when exact matches fill the list`() {
        // The starvation this replaced: the exact reading alone produced more
        // candidates than the limit, so every fuzzy variant was cut before it
        // was ever looked up and the setting appeared to do nothing.
        PinyinSyllables.valid = setOf("si", "shi")
        pinyin(
            "si\t四\t900", "si\t死\t800", "si\t斯\t700", "si\t丝\t600",
            "si\t思\t500", "si\t司\t400", "si\t似\t300", "si\t寺\t200",
            "si\t撕\t150", "si\t私\t120", "si\t饲\t110", "si\t肆\t100",
            "si\t嗣\t90", "si\t祀\t80",
            "shi\t是\t950",
        )
        CjkConfig.fuzzyPinyin = true
        assertTrue("是" in PinyinComposer.candidates("si"))
    }

    @Test
    fun `an exact spelling outranks a moderately commoner fuzzy one`() {
        PinyinSyllables.valid = setOf("si", "shi")
        pinyin("si\t四\t300", "shi\t是\t900")
        CjkConfig.fuzzyPinyin = true
        val candidates = PinyinComposer.candidates("si")
        assertEquals("四", candidates.first())
        assertTrue("是" in candidates)
    }

    @Test
    fun `a far commoner fuzzy match does win`() {
        // The penalty is a lean, not a veto: fuzzy is on because the user knows
        // they conflate these sounds, so a word an order of magnitude likelier is
        // probably what they meant. The crossover sits near a 7x frequency ratio.
        PinyinSyllables.valid = setOf("si", "shi")
        pinyin("si\t四\t20", "shi\t是\t900")
        CjkConfig.fuzzyPinyin = true
        assertEquals("是", PinyinComposer.candidates("si").first())
    }

    @Test
    fun `fuzzy off finds only what was actually typed`() {
        PinyinSyllables.valid = setOf("si", "shi")
        pinyin("shi\t是\t900")
        assertEquals(emptyList<String>(), PinyinComposer.candidates("si"))
    }

    // --- T9 -------------------------------------------------------------------

    @Test
    fun `t9 reaches a reading the old cartesian cap cut off`() {
        // 726 spells pan/pao/ran/rao/san/sao. The capped product kept only the
        // alphabetically-first prefixes, so every surviving reading of 726726726
        // began `pan` and 三三三 could not be typed at all.
        PinyinSyllables.valid = setOf("pan", "pao", "ran", "rao", "san", "sao")
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        pinyin("san\t三\t900", "sansansan\t三三三\t10", "pan\t盘\t900")
        assertTrue("三三三" in T9PinyinComposer.candidates("726726726"))
    }

    @Test
    fun `t9 still counts consumed input in digits`() {
        PinyinSyllables.valid = setOf("ni", "hao")
        T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
        pinyin("ni\t你\t900", "hao\t好\t800", "nihao\t你好\t500")
        assertEquals("你好", T9PinyinComposer.candidates("64426").first())
        assertEquals(5, T9PinyinComposer.consumedFor("64426", "你好"))
        assertEquals(2, T9PinyinComposer.consumedFor("64426", "你"))
    }

    // --- n-grams --------------------------------------------------------------

    @Test
    fun `an empty ngram model leaves a working unigram decoder`() {
        assertTrue(CjkNgrams.EMPTY.isEmpty)
        PinyinSyllables.valid = setOf("ni", "hao")
        pinyin("ni\t你\t900", "hao\t好\t800")
        // With no language model the pack's own frequencies still rank a path, so
        // the two single characters are stitched into a sentence.
        assertEquals("你好", PinyinComposer.candidates("nihao").first())
        assertEquals(5, PinyinComposer.consumedFor("nihao", "你好"))
    }

    @Test
    fun `bigram context decides between two readings of the same sound`() {
        val ngrams = CjkNgrams.parse(
            sequenceOf(
                "这件\t50",
                "事情\t40",
                "事请\t40",
                "这件\t事情\t35",
            ),
        )
        assertFalse(ngrams.isEmpty)
        // Seen pair beats the backed-off alternative at equal unigram weight.
        val seen = ngrams.logProbability("这件", "事情", 1, 100)
        val unseen = ngrams.logProbability("这件", "事请", 1, 100)
        assertTrue("seen=$seen unseen=$unseen", seen > unseen)
    }

    @Test
    fun `ngram parsing tolerates junk and stays finite`() {
        val ngrams = CjkNgrams.parse(
            sequenceOf("# comment", "", "bad", "你好\tnotanumber", "你好\t10", "你\t好\t5"),
        )
        assertEquals(10, ngrams.unigram("你好"))
        assertEquals(5, ngrams.bigram("你", "好"))
        assertEquals(0, ngrams.unigram("没有"))
        // A word the model has never seen must still score finitely, or its whole
        // path drops out of the lattice.
        assertTrue(ngrams.logProbability(null, "没有", 0, 0).isFinite())
        assertTrue(CjkNgrams.parse(sequenceOf("# nothing")).isEmpty)
    }

    // --- caching --------------------------------------------------------------

    @Test
    fun `swapping a dictionary drops the cached ranking`() {
        PinyinSyllables.valid = setOf("ni")
        pinyin("ni\t你\t900")
        assertEquals(listOf("你"), PinyinComposer.candidates("ni"))
        // Same buffer, different table: a cache that ignored the swap would still
        // be answering out of a dictionary that no longer exists.
        pinyin("ni\t尼\t900")
        assertEquals(listOf("尼"), PinyinComposer.candidates("ni"))
    }

    @Test
    fun `toggling a setting drops the cached ranking`() {
        PinyinSyllables.valid = setOf("si", "shi")
        pinyin("shi\t是\t900")
        assertEquals(emptyList<String>(), PinyinComposer.candidates("si"))
        CjkConfig.fuzzyPinyin = true
        assertEquals(listOf("是"), PinyinComposer.candidates("si"))
    }
}
