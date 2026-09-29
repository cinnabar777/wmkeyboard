package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 천지인 (Cheonjiin) pad (discussion #372), driven the way the keyboard
 * feeds it: consonants arrive already picked (the service resolves the
 * multi-tap), vowels arrive as the raw strokes ㅣ ㆍ ㅡ.
 */
class CheonjiinComposerTest {

    private fun compose(buffer: String) = CheonjiinComposer.composeBuffer(buffer)

    @Test fun `it is a transliterating input method whose space can end a multitap run`() {
        assertTrue(CheonjiinComposer.isTransliterating)
        assertTrue(CheonjiinComposer.spaceEndsMultitap)
    }

    @Test fun `every vowel is spelled by its strokes`() {
        val table = mapOf(
            "ㅣ" to "ㅣ", "ㅡ" to "ㅡ",
            "ㅣㆍ" to "ㅏ", "ㅣㆍㆍ" to "ㅑ", "ㆍㅣ" to "ㅓ", "ㆍㆍㅣ" to "ㅕ",
            "ㆍㅡ" to "ㅗ", "ㆍㆍㅡ" to "ㅛ", "ㅡㆍ" to "ㅜ", "ㅡㆍㆍ" to "ㅠ", "ㅡㅣ" to "ㅢ",
            "ㅣㆍㅣ" to "ㅐ", "ㅣㆍㆍㅣ" to "ㅒ", "ㆍㅣㅣ" to "ㅔ", "ㆍㆍㅣㅣ" to "ㅖ",
            "ㆍㅡㅣ" to "ㅚ", "ㆍㅡㅣㆍ" to "ㅘ", "ㆍㅡㅣㆍㅣ" to "ㅙ",
            "ㅡㆍㅣ" to "ㅟ", "ㅡㆍㆍㅣ" to "ㅝ", "ㅡㆍㆍㅣㅣ" to "ㅞ",
        )
        for ((strokes, vowel) in table) {
            assertEquals(strokes, vowel, CheonjiinComposer.foldStrokes(strokes))
        }
        // All 21 medials are reachable.
        assertEquals(21, table.values.toSet().size)
    }

    @Test fun `strokes after a consonant build the syllable`() {
        assertEquals("가", compose("ㄱㅣㆍ"))
        assertEquals("거", compose("ㄱㆍㅣ"))
        assertEquals("와", compose("ㅇㆍㅡㅣㆍ"))
        assertEquals("의", compose("ㅇㅡㅣ"))
        assertEquals("웨", compose("ㅇㅡㆍㆍㅣㅣ"))
    }

    @Test fun `the same consonant twice is two letters, which is what a closed run leaves`() {
        // 안녕: ㄴ, space, ㄴ on the pad; the buffer holds both.
        assertEquals("안녕", compose("ㅇㅣㆍㄴㄴㆍㆍㅣㅇ"))
        // 먹고: ㅁ is the ㅇㅁ key tapped twice, already resolved by the service.
        assertEquals("먹고", compose("ㅁㆍㅣㄱㄱㆍㅡ"))
        assertEquals("안녕하세요", compose("ㅇㅣㆍㄴㄴㆍㆍㅣㅇㅎㅣㆍㅅㆍㅣㅣㅇㆍㆍㅡ"))
    }

    @Test fun `an unfinished vowel waits, then pulls the final into the next syllable`() {
        assertEquals("각ㆍ", compose("ㄱㅣㆍㄱㆍ"))
        assertEquals("가거", compose("ㄱㅣㆍㄱㆍㅣ"))
        assertEquals("기", compose("ㄱㅣ"))
        assertEquals("가", compose("ㄱㅣㆍ"))
    }

    @Test fun `dots that spell nothing yet are shown, and a third dot cycles back to one`() {
        assertEquals("ㆍ", compose("ㆍ"))
        assertEquals("‥", compose("ㆍㆍ"))
        assertEquals("ㆍ", compose("ㆍㆍㆍ"))
        assertEquals("ㄱ‥", compose("ㄱㆍㆍ"))
        // ㅏ ㆍ is ㅑ, and ㅑ ㆍ is ㅏ again.
        assertEquals("ㅑ", compose("ㅣㆍㆍ"))
        assertEquals("ㅏ", compose("ㅣㆍㆍㆍ"))
    }

    @Test fun `text that is not a stroke is left alone`() {
        assertEquals("abc", CheonjiinComposer.foldStrokes("abc"))
        assertEquals("", compose(""))
    }
}
