package com.wasimaster.wmkeyboard.core.input.composer

/**
 * The two keys of the 나랏글 (Naratgeul) pad that change a letter already typed
 * rather than typing one (issue #599): 획추가 (add a stroke) and 쌍자음 (double
 * the consonant).
 *
 * Both rewrite the last jamo of a Hangul composing buffer in place. The buffer
 * holds jamo and [HangulComposer] re-reads all of it on every key, so turning
 * the ㅁ under 롬 into ㅂ is enough to show 롭: nothing has to be decomposed,
 * and a jamo that can no longer close the syllable simply moves out of it (낛
 * plus a stroke is 낙ㅈ, because ㄱㅈ is no final). Each edit replaces one
 * character with one character, so the service's per-character twins of the
 * buffer stay aligned without being touched.
 *
 * Only compatibility jamo (U+3131…) are read: the two-set grids and both
 * keypads type them, and a three-set grid has its own keys for every letter.
 */
object HangulStrokes {

    /**
     * One stroke more, cycling back to the plain letter: ㄱ ㅋ, ㄴ ㄷ ㅌ, ㅁ ㅂ ㅍ,
     * ㅅ ㅈ ㅊ, ㅇ ㅎ, and the y-vowels ㅏ ㅑ, ㅓ ㅕ, ㅗ ㅛ, ㅜ ㅠ. Samsung's table.
     */
    private val STROKE: Map<Char, Char> = cycles(
        "ㄱㅋ", "ㄴㄷㅌ", "ㅁㅂㅍ", "ㅅㅈㅊ", "ㅇㅎ",
        "ㅏㅑ", "ㅓㅕ", "ㅗㅛ", "ㅜㅠ",
    )

    /** The tense consonants, each a pair with its plain one. */
    private val DOUBLE: Map<Char, Char> = cycles("ㄱㄲ", "ㄷㄸ", "ㅂㅃ", "ㅅㅆ", "ㅈㅉ")

    /** The vowels a following ㅣ turns into ㅐ ㅒ ㅔ ㅖ on a [NaratgeulComposer] pad. */
    private const val TAKES_I = "ㅏㅑㅓㅕ"

    /**
     * [buffer] with one more stroke on its last letter, or null when the last
     * letter has none to take.
     *
     * With [vowelPairs] — the Naratgeul composer, where ㅐ is typed ㅏ ㅣ — a
     * trailing ㅣ that finishes ㅐ or ㅔ passes the stroke to the vowel it
     * joins, so ㅐ becomes ㅒ and ㅔ becomes ㅖ. Not when that vowel is the
     * tail of ㅙ or ㅞ, whose y-forms do not exist.
     */
    fun addStroke(buffer: CharSequence, vowelPairs: Boolean): String? {
        val last = buffer.lastIndex
        if (last < 0) return null
        STROKE[buffer[last]]?.let { return buffer.replacedAt(last, it) }
        val joined = last - 1
        val pairs = vowelPairs && buffer[last] == 'ㅣ' && joined >= 0 && buffer[joined] in TAKES_I &&
            (joined == 0 || buffer[joined - 1] !in "ㅗㅜ")
        return if (pairs) buffer.replacedAt(joined, STROKE.getValue(buffer[joined])) else null
    }

    /** [buffer] with its last consonant doubled, or undoubled; null when it has no pair. */
    fun doubleConsonant(buffer: CharSequence): String? {
        val last = buffer.lastIndex
        if (last < 0) return null
        return DOUBLE[buffer[last]]?.let { buffer.replacedAt(last, it) }
    }

    private fun CharSequence.replacedAt(index: Int, c: Char): String =
        StringBuilder(this).apply { setCharAt(index, c) }.toString()

    /** Each letter of each cycle mapped to the next, the last back to the first. */
    private fun cycles(vararg cycles: String): Map<Char, Char> = buildMap {
        for (cycle in cycles) cycle.forEachIndexed { i, c -> put(c, cycle[(i + 1) % cycle.length]) }
    }
}
