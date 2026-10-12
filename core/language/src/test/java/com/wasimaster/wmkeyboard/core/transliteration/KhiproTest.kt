package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Khipro against the Khipro team's own conformance cases
 * (KhiproTeam/khipro-testcases and khipro-testcases-touch, and the tables in
 * KhiproTeam/khipro-dark-m17n and khipro-dark-mim-touchscreen, copied verbatim
 * into `resources/khipro/`), the same tables their C++ library is released
 * against. A failure here is a disagreement with upstream, not a matter of
 * taste: fix the interpreter, never the table.
 */
class KhiproTest {

    private fun cases(name: String): List<Pair<String, String>> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("khipro/$name")) {
            "missing test fixture khipro/$name"
        }.bufferedReader(Charsets.UTF_8).readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line -> line.split('\t').let { it[0].trim() to it[1].trim() } }

    private fun assertConforms(name: String, variant: Khipro.Variant) {
        val cases = cases(name)
        assertTrue("no cases read from $name", cases.size > 50)
        val failures = cases.mapNotNull { (input, expected) ->
            val actual = Khipro.convert(input, variant)
            if (actual == expected) null else "$input: expected $expected, got $actual"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun `touchscreen spec passes the touch conformance cases`() =
        assertConforms("khipro-testcases-touch.tsv", Khipro.Variant.TOUCHSCREEN)

    @Test
    fun `desktop spec passes the desktop conformance cases`() =
        assertConforms("khipro-testcases.tsv", Khipro.Variant.DESKTOP)

    @Test
    fun `dark touchscreen spec passes the dark touch conformance cases`() =
        assertConforms("khipro-dark-testcases-touch.tsv", Khipro.Variant.DARK_TOUCHSCREEN)

    @Test
    fun `dark desktop spec passes the dark desktop conformance cases`() =
        assertConforms("khipro-dark-testcases.tsv", Khipro.Variant.DARK_DESKTOP)

    @Test
    fun `dark joins consonants only on the slash, as many as it is pressed`() {
        val dark = Khipro.Variant.DARK_TOUCHSCREEN
        assertEquals("লাগবে", Khipro.convert("lagbe", dark))
        assertEquals("নতর\u200D্য", Khipro.convert("ntrz/", dark))
        assertEquals("নত্র্য", Khipro.convert("ntrz//", dark))
        assertEquals("ন্ত্র্য", Khipro.convert("ntrz///", dark))
        assertEquals("চন্দ্রবিন্দু", Khipro.convert("cndr//bind/u", dark))
    }

    @Test
    fun `everyday words`() {
        assertEquals("আমি", Khipro.convert("ami"))
        assertEquals("তুমি", Khipro.convert("tumi"))
        assertEquals("ক্ষিপ্র", Khipro.convert("kkhipr"))
        assertEquals("বুধবার", Khipro.convert("budh;bar"))
        assertEquals("আমিই", Khipro.convert("ami;i"))
        assertEquals("ভালো", Khipro.convert("valw"))
        assertEquals("বাংলা", Khipro.convert("baxla"))
    }

    @Test
    fun `the slicer after a vowel is a chandrabindu`() {
        assertEquals("চাঁদ", Khipro.convert("ca/d"))
    }

    @Test
    fun `the desktop spec types Bengali digits and the danda, the touch spec leaves them`() {
        assertEquals("১২৩", Khipro.convert("123", Khipro.Variant.DESKTOP))
        assertEquals("123", Khipro.convert("123", Khipro.Variant.TOUCHSCREEN))
        assertEquals("কথা।", Khipro.convert("kotha.", Khipro.Variant.DESKTOP))
    }

    @Test
    fun `the touch spec builds the double danda and the abbreviation sign on the danda`() {
        // Issue #488: the danda key's own output, then f.
        assertEquals("।", Khipro.convert("।"))
        assertEquals("॥", Khipro.convert("।f"))
        assertEquals("৺", Khipro.convert("।ff"))
        assertEquals("আমি॥", Khipro.convert("ami।f"))
        // Any other key after it starts afresh.
        assertEquals("আমি।ত", Khipro.convert("ami।t"))
    }

    @Test
    fun `nukta letters come out precomposed, as the word lists spell them`() {
        val word = Khipro.convert("barfi")
        assertTrue(word, 'ড়' in word)
        assertTrue(word, '়' !in word)
    }

    @Test
    fun `empty in, empty out`() {
        assertEquals("", Khipro.convert(""))
    }
}
