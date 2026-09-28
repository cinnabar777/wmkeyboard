package com.wasimaster.wmkeyboard.core.text

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.util.Locale

/**
 * [Graphemes] against the platform's own ICU (`android.icu`, which Robolectric
 * runs for real) and against the `java.text` fallback the plain-JVM tests get,
 * so the two cannot drift apart without one of the halves going red.
 *
 * Expectations stay clear of the one place the two engines are allowed to
 * differ by release: whether an Indic conjunct is one cluster (Unicode 15.1,
 * Android 15's ICU) or two. Those tests assert only what holds under both.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class GraphemesTest(private val icu: Boolean) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "icu={0}")
        fun engines(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))

        private const val FAMILY = "👨\u200D👩\u200D👧"
        private const val THUMB_TONE = "👍🏽"
        private const val FLAG_BD = "🇧🇩"
        private const val FLAG_US = "🇺🇸"
        private const val KEYCAP_ONE = "1\uFE0F\u20E3"
        private const val ENGLAND = "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        private const val SKULL = "☠\uFE0F"
        private const val E_ACUTE_NFD = "e\u0301"
        private const val HAN_JAMO = "\u1112\u1161\u11AB" // hieuh, a, nieun as conjoining jamo: draws as 한
        private const val IVS_KATSURAGI = "葛\uDB40\uDD00" // U+845B U+E0100
        private const val MATH_BOLD_A = "𝐀" // U+1D400
        private const val ARABIC_BA_FATHA = "ب\u064E" // ba with fatha
        private const val HEBREW_BET_DAGESH_QAMATS = "ב\u05BC\u05B8" // bet with dagesh and qamats
        private const val PERSIAN_ZWNJ = "می\u200C" // می + ZWNJ
        private const val DEVANAGARI_KI = "क\u093F"
        private const val DEVANAGARI_KSSA = "क\u094Dष"
        private const val BENGALI_KI = "ক\u09BF"
        private const val THAI_KAM = "\u0E01\u0E33" // ko kai with sara am, a spacing mark

        /** Mixed text for the whole-string invariants below. */
        private val CORPUS = listOf(
            "",
            "hello, world",
            "caf$E_ACUTE_NFD latte",
            "line one\r\nline two\nline three",
            "$FAMILY and $THUMB_TONE with $FLAG_BD$FLAG_US $KEYCAP_ONE $ENGLAND $SKULL",
            "$HAN_JAMO 한국어",
            "$IVS_KATSURAGI 葛",
            "$MATH_BOLD_A$MATH_BOLD_A",
            "$ARABIC_BA_FATHA$ARABIC_BA_FATHA $HEBREW_BET_DAGESH_QAMATS",
            PERSIAN_ZWNJ + "خواهم",
            "$DEVANAGARI_KI $DEVANAGARI_KSSA नमस\u094Dत\u0947",
            "$BENGALI_KI ক\u09CDষ র\u200D\u09CDয আম\u09BF",
            "$THAI_KAM สว\u0E31สด\u0E35",
            "a\u034Fb", // combining grapheme joiner
            "\u0301 leading mark",
            "trailing ZWJ\u200D",
        )
    }

    private var saved = true

    @Before
    fun pick() {
        saved = PlatformIcu.available
        PlatformIcu.available = icu
    }

    @After
    fun restore() {
        PlatformIcu.available = saved
    }

    @Test
    fun `the platform ICU really is there to test`() {
        // Guards the icu=true half from silently running the fallback twice.
        assertTrue(saved)
    }

    // ---- lastLength -------------------------------------------------------

    @Test
    fun `last cluster of plain and decomposed text`() {
        assertEquals(0, Graphemes.lastLength(""))
        assertEquals(1, Graphemes.lastLength("abc"))
        assertEquals(2, Graphemes.lastLength("caf$E_ACUTE_NFD"))
        assertEquals(3, Graphemes.lastLength("a\u0301\u0302"))
    }

    @Test
    fun `last cluster keeps CR LF and surrogate pairs whole`() {
        assertEquals(2, Graphemes.lastLength("x\r\n"))
        assertEquals(1, Graphemes.lastLength("x\n"))
        assertEquals(2, Graphemes.lastLength("a$MATH_BOLD_A"))
    }

    @Test
    fun `last cluster is a whole Hangul syllable from jamo`() {
        assertEquals(3, Graphemes.lastLength(HAN_JAMO))
        assertEquals(1, Graphemes.lastLength("한"))
    }

    @Test
    fun `last cluster takes an ideographic variation sequence whole`() {
        assertEquals(IVS_KATSURAGI.length, Graphemes.lastLength("x$IVS_KATSURAGI"))
    }

    @Test
    fun `last cluster takes emoji sequences whole`() {
        assertEquals(FAMILY.length, Graphemes.lastLength("hi $FAMILY"))
        assertEquals(THUMB_TONE.length, Graphemes.lastLength(THUMB_TONE))
        assertEquals(FLAG_US.length, Graphemes.lastLength(FLAG_BD + FLAG_US))
        assertEquals(KEYCAP_ONE.length, Graphemes.lastLength(KEYCAP_ONE))
        assertEquals(ENGLAND.length, Graphemes.lastLength(ENGLAND))
    }

    @Test
    fun `last cluster keeps marks with their base in every script`() {
        assertEquals(2, Graphemes.lastLength(ARABIC_BA_FATHA))
        assertEquals(3, Graphemes.lastLength(HEBREW_BET_DAGESH_QAMATS))
        assertEquals(2, Graphemes.lastLength(DEVANAGARI_KI))
        assertEquals(2, Graphemes.lastLength(BENGALI_KI))
        assertEquals(2, Graphemes.lastLength(THAI_KAM))
    }

    @Test
    fun `a conjunct is at least its last consonant on any release`() {
        // One cluster on Unicode 15.1 engines, "क\u094D" + "ष" before it.
        val n = Graphemes.lastLength(DEVANAGARI_KSSA)
        assertTrue("got $n", n == 1 || n == DEVANAGARI_KSSA.length)
    }

    // ---- backspaceLength --------------------------------------------------

    @Test
    fun `backspace takes one character of plain text`() {
        assertEquals(0, Graphemes.backspaceLength(""))
        assertEquals(1, Graphemes.backspaceLength("abc"))
        assertEquals(1, Graphemes.backspaceLength("a "))
    }

    @Test
    fun `backspace never splits a surrogate pair`() {
        assertEquals(2, Graphemes.backspaceLength(MATH_BOLD_A))
        assertEquals(2, Graphemes.backspaceLength("x😀"))
    }

    @Test
    fun `backspace peels a typed mark off and leaves its base`() {
        // The mark is a key of its own on these layouts; taking the letter with
        // it would make a wrong harakah cost a retyped letter.
        assertEquals(1, Graphemes.backspaceLength(ARABIC_BA_FATHA))
        assertEquals(1, Graphemes.backspaceLength(HEBREW_BET_DAGESH_QAMATS))
        assertEquals(1, Graphemes.backspaceLength("caf$E_ACUTE_NFD"))
        assertEquals(1, Graphemes.backspaceLength(DEVANAGARI_KI))
        assertEquals(1, Graphemes.backspaceLength(THAI_KAM))
    }

    @Test
    fun `backspace takes a typed zero-width non-joiner alone`() {
        assertEquals(1, Graphemes.backspaceLength(PERSIAN_ZWNJ))
    }

    @Test
    fun `backspace takes an invisible selector with its base`() {
        assertEquals(IVS_KATSURAGI.length, Graphemes.backspaceLength("x$IVS_KATSURAGI"))
        // A text-presentation selector after a digit: not an emoji, still invisible.
        assertEquals(2, Graphemes.backspaceLength("x1\uFE0E"))
        assertEquals(2, Graphemes.backspaceLength("x1\uFE0F"))
        // Mongolian free variation selector.
        assertEquals(2, Graphemes.backspaceLength("ᠠ\u180B"))
    }

    @Test
    fun `backspace takes a combining grapheme joiner with its base`() {
        assertEquals(2, Graphemes.backspaceLength("xa\u034F"))
    }

    @Test
    fun `backspace takes CR LF as one line break`() {
        assertEquals(2, Graphemes.backspaceLength("line\r\n"))
        assertEquals(1, Graphemes.backspaceLength("line\n"))
        assertEquals(1, Graphemes.backspaceLength("line\r"))
        // A lone LF right at the start has nothing to pair with.
        assertEquals(1, Graphemes.backspaceLength("\n"))
    }

    @Test
    fun `backspace takes a jamo syllable whole`() {
        assertEquals(3, Graphemes.backspaceLength(HAN_JAMO))
        assertEquals(2, Graphemes.backspaceLength("\u1112\u1161"))
        // A stray medial with nothing to join to is just itself.
        assertEquals(1, Graphemes.backspaceLength("x\u1161"))
    }

    @Test
    fun `backspace takes emoji sequences whole`() {
        assertEquals(FAMILY.length, Graphemes.backspaceLength("family: $FAMILY"))
        assertEquals(THUMB_TONE.length, Graphemes.backspaceLength(THUMB_TONE))
        assertEquals(FLAG_US.length, Graphemes.backspaceLength(FLAG_BD + FLAG_US))
        assertEquals(KEYCAP_ONE.length, Graphemes.backspaceLength(KEYCAP_ONE))
        assertEquals(ENGLAND.length, Graphemes.backspaceLength(ENGLAND))
        assertEquals(SKULL.length, Graphemes.backspaceLength(SKULL))
        // Text-style selector on a symbol the emoji walk knows.
        assertEquals(2, Graphemes.backspaceLength("↔\uFE0E"))
    }

    @Test
    fun `backspace leaves conjunct deletion to the composer`() {
        // Whole-conjunct backspace is a per-language setting that the Indic
        // composer answers; the default is one code point.
        assertEquals(1, Graphemes.backspaceLength(DEVANAGARI_KSSA))
        assertEquals(1, Graphemes.backspaceLength("ক\u09CDষ"))
    }

    // ---- firstLength ------------------------------------------------------

    @Test
    fun `first cluster of plain and decomposed text`() {
        assertEquals(0, Graphemes.firstLength(""))
        assertEquals(1, Graphemes.firstLength("abc"))
        assertEquals(2, Graphemes.firstLength("${E_ACUTE_NFD}x"))
        assertEquals(2, Graphemes.firstLength("$MATH_BOLD_A."))
    }

    @Test
    fun `first cluster takes CR LF and jamo syllables whole`() {
        assertEquals(2, Graphemes.firstLength("\r\nx"))
        assertEquals(1, Graphemes.firstLength("\nx"))
        assertEquals(3, Graphemes.firstLength("${HAN_JAMO}x"))
    }

    @Test
    fun `first cluster takes emoji sequences whole`() {
        assertEquals(FAMILY.length, Graphemes.firstLength("$FAMILY rest"))
        assertEquals(THUMB_TONE.length, Graphemes.firstLength("$THUMB_TONE!"))
        assertEquals(FLAG_BD.length, Graphemes.firstLength(FLAG_BD + FLAG_US))
        assertEquals(KEYCAP_ONE.length, Graphemes.firstLength("${KEYCAP_ONE}x"))
        assertEquals(ENGLAND.length, Graphemes.firstLength("${ENGLAND}x"))
    }

    @Test
    fun `first cluster keeps marks and selectors with their base`() {
        assertEquals(BENGALI_KI.length, Graphemes.firstLength(BENGALI_KI))
        assertEquals(DEVANAGARI_KI.length, Graphemes.firstLength("$DEVANAGARI_KI "))
        assertEquals(ARABIC_BA_FATHA.length, Graphemes.firstLength(ARABIC_BA_FATHA))
        assertEquals(IVS_KATSURAGI.length, Graphemes.firstLength("${IVS_KATSURAGI}x"))
        assertEquals(THAI_KAM.length, Graphemes.firstLength(THAI_KAM))
    }

    @Test
    fun `first cluster of a conjunct is at least consonant and virama`() {
        val n = Graphemes.firstLength(DEVANAGARI_KSSA)
        assertTrue("got $n", n == 2 || n == DEVANAGARI_KSSA.length)
    }

    // ---- split / count ----------------------------------------------------

    @Test
    fun `split cuts at user-perceived characters`() {
        assertEquals(listOf("h", E_ACUTE_NFD, "l", "l", "o"), Graphemes.split("h${E_ACUTE_NFD}llo"))
        assertEquals(listOf("a", FAMILY, "b"), Graphemes.split("a${FAMILY}b"))
        assertEquals(listOf(FLAG_BD, FLAG_US), Graphemes.split(FLAG_BD + FLAG_US))
        assertEquals(listOf("x", "\r\n", "y"), Graphemes.split("x\r\ny"))
        assertEquals(listOf(HAN_JAMO, "한"), Graphemes.split("${HAN_JAMO}한"))
        assertEquals(emptyList<String>(), Graphemes.split(""))
    }

    @Test
    fun `count agrees with split`() {
        for (text in CORPUS) {
            assertEquals(text, Graphemes.split(text).size, Graphemes.count(text))
        }
        assertEquals(0, Graphemes.count(""))
        assertEquals(3, Graphemes.count("a${FAMILY}b"))
    }

    // ---- invariants over every prefix and suffix of the corpus -------------

    @Test
    fun `split reassembles the text exactly`() {
        for (text in CORPUS) assertEquals(text, Graphemes.split(text).joinToString(""))
    }

    @Test
    fun `no answer ever cuts a surrogate pair or runs past the text`() {
        for (text in CORPUS) {
            for (end in 1..text.length) {
                val before = text.substring(0, end)
                for ((name, n) in listOf(
                    "lastLength" to Graphemes.lastLength(before),
                    "backspaceLength" to Graphemes.backspaceLength(before),
                )) {
                    assertTrue("$name(${before.debug()}) = $n", n in 1..before.length)
                    val left = before.substring(0, before.length - n)
                    assertFalse("$name cut a pair in ${before.debug()}", endsInHighSurrogate(left))
                }
                val after = text.substring(end - 1)
                val first = Graphemes.firstLength(after)
                assertTrue("firstLength(${after.debug()}) = $first", first in 1..after.length)
                assertFalse(
                    "firstLength cut a pair in ${after.debug()}",
                    first < after.length && Character.isLowSurrogate(after[first]),
                )
            }
        }
    }

    @Test
    fun `backspace never takes more than the whole cluster`() {
        for (text in CORPUS) {
            for (end in 1..text.length) {
                val before = text.substring(0, end)
                val back = Graphemes.backspaceLength(before)
                val cluster = Graphemes.lastLength(before)
                assertTrue("${before.debug()}: $back > $cluster", back <= cluster)
            }
        }
    }

    @Test
    fun `backspacing to empty visits every character`() {
        for (text in CORPUS) {
            var rest = text
            var presses = 0
            while (rest.isNotEmpty()) {
                rest = rest.substring(0, rest.length - Graphemes.backspaceLength(rest))
                presses++
                assertTrue("runaway on ${text.debug()}", presses <= text.length)
            }
            // Never fewer presses than clusters: no press ate two characters.
            assertTrue(text.debug(), presses >= Graphemes.count(text))
        }
    }

    private fun endsInHighSurrogate(s: String) = s.isNotEmpty() && Character.isHighSurrogate(s.last())

    private fun String.debug(): String =
        codePoints().toArray().joinToString(" ", "[", "]") { "U+%04X".format(Locale.ROOT, it) }
}
