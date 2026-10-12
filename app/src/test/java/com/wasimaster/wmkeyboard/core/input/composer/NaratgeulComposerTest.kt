package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 나랏글 (Naratgeul) pad (issue #599): its composer, and the 획추가 and
 * 쌍자음 keys driven the way the service drives them, by rewriting the last
 * jamo of the buffer and composing what is left.
 */
class NaratgeulComposerTest {

    private fun compose(buffer: String) = NaratgeulComposer.composeBuffer(buffer)

    /** [buffer] after [presses] of [key], composed — what the field shows. */
    private fun press(buffer: String, presses: Int, key: (String) -> String?): List<String> {
        var b = buffer
        return (1..presses).map {
            b = key(b) ?: b
            compose(b)
        }
    }

    private val stroke = { b: String -> HangulStrokes.addStroke(b, vowelPairs = true) }
    private val double = { b: String -> HangulStrokes.doubleConsonant(b) }

    @Test fun `it is a transliterating input method`() {
        assertTrue(NaratgeulComposer.isTransliterating)
    }

    @Test fun `a vowel and a following i make the ae family`() {
        assertEquals("ㅐ", NaratgeulComposer.foldVowelPairs("ㅏㅣ"))
        assertEquals("ㅒ", NaratgeulComposer.foldVowelPairs("ㅑㅣ"))
        assertEquals("ㅔ", NaratgeulComposer.foldVowelPairs("ㅓㅣ"))
        assertEquals("ㅖ", NaratgeulComposer.foldVowelPairs("ㅕㅣ"))
        assertEquals("개", compose("ㄱㅏㅣ"))
        assertEquals("게", compose("ㄱㅓㅣ"))
        // The compounds built on them come through HangulComposer.
        assertEquals("왜", compose("ㅇㅗㅏㅣ"))
        assertEquals("웨", compose("ㅇㅜㅓㅣ"))
        assertEquals("의", compose("ㅇㅡㅣ"))
        assertEquals("외", compose("ㅇㅗㅣ"))
    }

    @Test fun `the two-set rules still apply`() {
        assertEquals("한글", compose("ㅎㅏㄴㄱㅡㄹ"))
        assertEquals("가나", compose("ㄱㅏㄴㅏ"))
    }

    @Test fun `add stroke cycles the issue's letters`() {
        val cycles = listOf("ㄱㅋ", "ㄴㄷㅌ", "ㅁㅂㅍ", "ㅅㅈㅊ", "ㅇㅎ", "ㅏㅑ", "ㅓㅕ", "ㅗㅛ", "ㅜㅠ")
        for (cycle in cycles) {
            for ((i, c) in cycle.withIndex()) {
                assertEquals("$c", cycle[(i + 1) % cycle.length].toString(), stroke(c.toString()))
            }
        }
        assertNull("ㄹ takes no stroke", stroke("ㄹ"))
        assertNull("ㅡ takes no stroke", stroke("ㅡ"))
        assertNull(stroke(""))
    }

    @Test fun `double consonant toggles the five tense pairs`() {
        for (pair in listOf("ㄱㄲ", "ㄷㄸ", "ㅂㅃ", "ㅅㅆ", "ㅈㅉ")) {
            assertEquals(pair[1].toString(), double(pair[0].toString()))
            assertEquals(pair[0].toString(), double(pair[1].toString()))
        }
        assertNull("ㅋ has no tense twin of its own", double("ㅋ"))
        assertNull("a vowel is never doubled", double("ㅏ"))
        assertNull(double(""))
    }

    @Test fun `add stroke on built syllables follows the issue's examples`() {
        assertEquals(listOf("갸", "가"), press("ㄱㅏ", 2, stroke))
        assertEquals(listOf("낙ㅈ", "낙ㅊ", "낛"), press("ㄴㅏㄱㅅ", 3, stroke))
        assertEquals(listOf("롭", "롶", "롬"), press("ㄹㅗㅁ", 3, stroke))
    }

    @Test fun `double consonant on built syllables follows the issue's examples`() {
        assertEquals(listOf("갂", "각"), press("ㄱㅏㄱ", 2, double))
        assertEquals(listOf("바ㅃ", "밥"), press("ㅂㅏㅂ", 2, double))
    }

    @Test fun `add stroke on ae and e reaches yae and ye`() {
        assertEquals(listOf("걔", "개"), press("ㄱㅏㅣ", 2, stroke))
        assertEquals(listOf("계", "게"), press("ㄱㅓㅣ", 2, stroke))
        // ㅙ and ㅞ have no y-form, so the stroke leaves them alone.
        assertNull(stroke("ㅇㅗㅏㅣ"))
        assertNull(stroke("ㅇㅜㅓㅣ"))
        // On a grid where ㅏ ㅣ is not ㅐ, the ㅣ is just a letter with no stroke.
        assertNull(HangulStrokes.addStroke("ㄱㅏㅣ", vowelPairs = false))
    }

    @Test fun `an edit keeps the buffer's length`() {
        // The service's per-character twins of the buffer rely on it.
        for (b in listOf("ㄴㅏㄱㅅ", "ㄱㅏㅣ", "ㅂㅏㅂ")) {
            stroke(b)?.let { assertEquals(b.length, it.length) }
            double(b)?.let { assertEquals(b.length, it.length) }
        }
    }
}
