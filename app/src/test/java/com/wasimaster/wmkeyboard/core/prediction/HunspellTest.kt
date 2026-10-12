package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Hunspell `.aff` and `.dic` pair imports as every word it spells (#619). */
class HunspellTest {

    private val aff = """
        SET UTF-8
        LANG bs_BA
        FORBIDDENWORD !
        NEEDAFFIX ~

        SFX A Y 2
        SFX A 0 a .
        SFX A 0 om/B [^o]

        SFX B N 1
        SFX B 0 e .

        PFX N Y 1
        PFX N 0 ne .

        SFX C N 1
        SFX C a e a
    """.trimIndent()

    private val dic = """
        6
        grad/AN
        selo/A
        kuća/C
        loš/~A
        zlo/!
        mrtvih	po:noun
    """.trimIndent()

    private fun words() = Hunspell.expand(aff.toByteArray(), dic.toByteArray()).toMap()

    @Test
    fun expandsSuffixesPrefixesTheirCrossAndContinuations() {
        val words = words()
        assertEquals(Hunspell.STEM_FREQUENCY, words["grad"])
        for (form in listOf("grada", "gradom", "gradome", "negrad", "negrada", "negradom")) {
            assertEquals(form, Hunspell.FORM_FREQUENCY, words[form])
        }
        assertEquals(Hunspell.FORM_FREQUENCY, words["kuće"])
        assertEquals(Hunspell.STEM_FREQUENCY, words["mrtvih"])
        // B does not combine with prefixes, so a form made through it takes none.
        assertFalse("negradome" in words)
    }

    @Test
    fun conditionsStripsAndFlagsAreHonoured() {
        val words = words()
        // `[^o]` keeps "om" off a stem that ends in o.
        assertTrue("seloa" in words)
        assertFalse("selom" in words)
        assertFalse("zlo" in words)
        // A NEEDAFFIX stem is not a word on its own, but its forms are.
        assertFalse("loš" in words)
        assertTrue("loša" in words)
    }

    @Test
    fun aliasesAndLongFlagsRead() {
        val aff = "FLAG long\nAF 1\nAF AaBb\nSFX Aa Y 1\nSFX Aa 0 s .\nSFX Bb Y 1\nSFX Bb 0 ed .\n"
        val words = Hunspell.expand(aff.toByteArray(), "1\nwalk/1\n".toByteArray()).toMap()
        assertEquals(setOf("walk", "walks", "walked"), words.keys)
    }

    @Test
    fun aLegacyEncodingIsDecoded() {
        val aff = "SET ISO8859-2\nSFX A Y 1\nSFX A 0 a .\n"
        val dic = "1\nkoš/A\n"
        val words = Hunspell.expand(aff.toByteArray(Charsets.ISO_8859_1), dic.toByteArray(charset("ISO-8859-2")))
        assertEquals(listOf("koš", "koša"), words.map { it.first })
    }

    @Test
    fun aDicIsKnownByItsCountLine() {
        assertTrue(Hunspell.looksLikeDic("bs_BA.dic", "212037\nabak/A\n".toByteArray()))
        assertFalse(Hunspell.looksLikeDic("mine.dic", "hello 10\nworld 5\n".toByteArray()))
        assertFalse(Hunspell.looksLikeDic("bs_BA.txt", "3\na\nb\nc\n".toByteArray()))
        assertEquals("bs_BA", Hunspell.language(aff.toByteArray()))
        assertNull(Hunspell.language("SET UTF-8\n".toByteArray()))
    }

    @Test
    fun importPairsTheTwoFilesAndAsksForTheMissingOne() {
        val affFile = CustomDictionaries.ImportFile("bs_BA.aff", aff.toByteArray())
        val dicFile = CustomDictionaries.ImportFile("bs_BA.dic", dic.toByteArray())
        val found = CustomDictionaries.inspect(listOf(dicFile, affFile)) as CustomDictionaries.Inspection.Found
        val candidate = found.dictionaries.single()
        assertEquals("bs_BA", candidate.locale)
        assertTrue(candidate.words.any { it.first == "negradom" })
        assertEquals(
            CustomDictionaries.Inspection.Refused(CustomDictionaries.Refusal.MissingAff),
            CustomDictionaries.inspect(listOf(dicFile)),
        )
        assertEquals(
            CustomDictionaries.Inspection.Refused(CustomDictionaries.Refusal.MissingDic),
            CustomDictionaries.inspect(listOf(affFile)),
        )
    }
}
