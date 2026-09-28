package com.wasimaster.wmkeyboard.core.text

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * [Nfc] on the platform ICU and on the `java.text` fallback. The cases are the
 * ones the prediction stores depend on (see `WordKey`): Bengali's excluded
 * nukta letters above all, whose two spellings render identically and must
 * land on one key.
 *
 * Every non-ASCII string is spelled with escapes, because the point of most of
 * them is which of two identical-looking spellings they are.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class NfcTest(private val icu: Boolean) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "icu={0}")
        fun engines(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))

        /** Bengali YYA, precomposed: on the composition-exclusion list, so never NFC. */
        private const val YYA_PRECOMPOSED = "\u09DF"

        /** The same letter as YA + NUKTA, which is what NFC turns it into. */
        private const val YYA_DECOMPOSED = "\u09AF\u09BC"

        private val ALREADY_NFC = listOf(
            "",
            "hello",
            "caf\u00E9",
            "na\u00EFve",
            "\u09AD\u09BE\u09B2\u09CB", // bhalo, with the precomposed o-kar
            "\u09A8\u09AE\u09B8\u09CD\u0995\u09BE\u09B0", // nomoskar
            YYA_DECOMPOSED,
            "\u0928\u092E\u0938\u094D\u0924\u0947", // namaste
            "\uD55C\uAD6D\uC5B4", // hangugeo, precomposed syllables
            "\u0395\u03BB\u03BB\u03B7\u03BD\u03B9\u03BA\u03AC", // ellinika, tonos precomposed
            "\u65E5\u672C\u8A9E", // nihongo
            "\uD83D\uDE00\uD83D\uDC4D\uD83C\uDFFD", // grinning face, thumbs up with a tone
            "\u0627\u0644\u0639\u0631\u0628\u064A\u0629", // al-arabiya
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
    fun `normalized text is recognised and returned as the same instance`() {
        for (word in ALREADY_NFC) {
            assertTrue(word, Nfc.isNormalized(word))
            assertSame(word, Nfc.normalize(word))
        }
    }

    @Test
    fun `decomposed Latin composes`() {
        assertFalse(Nfc.isNormalized("cafe\u0301"))
        assertEquals("caf\u00E9", Nfc.normalize("cafe\u0301"))
        // Marks reorder by combining class before composing: a + dot below +
        // circumflex, typed in either order, is one precomposed letter.
        assertEquals("\u1EAD", Nfc.normalize("a\u0323\u0302"))
        assertEquals("\u1EAD", Nfc.normalize("a\u0302\u0323"))
    }

    @Test
    fun `Bengali excluded nukta letters decompose and stay decomposed`() {
        assertFalse(Nfc.isNormalized(YYA_PRECOMPOSED))
        assertEquals(YYA_DECOMPOSED, Nfc.normalize(YYA_PRECOMPOSED))
        assertEquals("\u09A1\u09BC", Nfc.normalize("\u09DC"))
        assertEquals("\u09A2\u09BC", Nfc.normalize("\u09DD"))
        assertEquals("\u0986$YYA_DECOMPOSED", Nfc.normalize("\u0986$YYA_PRECOMPOSED"))
    }

    @Test
    fun `Devanagari nukta letters decompose too`() {
        // QA, U+0958, is excluded from composition like Bengali's three.
        assertEquals("\u0915\u093C", Nfc.normalize("\u0958"))
        assertFalse(Nfc.isNormalized("\u0958"))
    }

    @Test
    fun `conjoining jamo compose into a syllable`() {
        assertEquals("\uD55C", Nfc.normalize("\u1112\u1161\u11AB"))
        assertFalse(Nfc.isNormalized("\u1112\u1161\u11AB"))
    }

    @Test
    fun `singletons map to their canonical form`() {
        // OHM SIGN and ANGSTROM SIGN are canonical singletons of omega and A-ring.
        assertEquals("\u03A9", Nfc.normalize("\u2126"))
        assertEquals("\u00C5", Nfc.normalize("\u212B"))
    }

    @Test
    fun `normalizing is idempotent`() {
        val inputs = ALREADY_NFC + listOf(
            "cafe\u0301", YYA_PRECOMPOSED, "\u1112\u1161\u11AB", "a\u0302\u0323", "\u2126\u212B",
        )
        for (text in inputs) {
            val once = Nfc.normalize(text)
            assertTrue(text, Nfc.isNormalized(once))
            assertEquals(once, Nfc.normalize(once))
        }
    }

    @Test
    fun `both engines agree with the JDK`() {
        val inputs = ALREADY_NFC + listOf("cafe\u0301", YYA_PRECOMPOSED, "\u1112\u1161\u11AB", "\u0958")
        for (text in inputs) {
            val expected = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
            assertEquals(text, expected, Nfc.normalize(text))
        }
    }
}
