package com.wasimaster.wmkeyboard.core.text

/**
 * User-perceived characters (Unicode extended grapheme clusters, UAX #29),
 * through the platform's ICU `BreakIterator`.
 *
 * ICU is the source of truth for what a cluster is, which is more than any
 * hand-written walk here knew about: a base with its combining marks, CR LF,
 * Hangul conjoining jamo (ᄒ + ᅡ + ᆫ draws as one 한), prepended marks, and, on
 * Android 15 and later (ICU 74, Unicode 15.1), whole Indic conjuncts.
 *
 * Its one blind spot is age. The rules that keep an emoji ZWJ sequence or a
 * skin-toned emoji together only reached the platform's ICU in stages, and an
 * Android 7 phone would happily split 👨‍👩‍👧 into three. So every answer is
 * the wider of ICU's and [EmojiGraphemes]'s, which knows those sequences by
 * their code points and does not depend on the release it runs on.
 *
 * Off-device (the JVM unit tests) the same questions go to `java.text`'s
 * iterator, which implements the same rules on JDK 20 and later; see
 * [PlatformIcu].
 */
object Graphemes {

    /**
     * How far the emoji walk looks. The longest emoji sequences (a family of
     * four with skin tones, tag flags) run to about 35 UTF-16 units.
     */
    private const val LOOKAROUND = 64

    private const val CR = '\r'
    private const val LF = '\n'
    private const val COMBINING_GRAPHEME_JOINER = 0x034F

    /**
     * UTF-16 length of the grapheme cluster that ends [text], so deleting that
     * many units removes exactly one character as the user sees it. 0 when
     * [text] is empty.
     */
    fun lastLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val icu = text.length - boundaries(text).preceding(text.length).coerceAtLeast(0)
        val emoji = EmojiGraphemes.deleteLength(tail(text))
        return maxOf(icu, emoji, Character.charCount(Character.codePointBefore(text, text.length)))
    }

    /**
     * UTF-16 length of the grapheme cluster that starts [text]: what one ⌦
     * takes. 0 when [text] is empty, and never less than one code point
     * otherwise.
     */
    fun firstLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        return firstLength(boundaries(text), text, 0)
    }

    /**
     * What one backspace takes off the end of [before].
     *
     * Not simply [lastLength], on purpose. Where the parts of a cluster are
     * things the user types one key at a time — an Arabic harakah, a Hebrew
     * niqqud point, a Vietnamese tone mark typed as a combining accent, a
     * Persian zero-width non-joiner — a backspace takes the last part back and
     * leaves the rest, which is how every Android keyboard has always edited
     * them. Only the parts that mean nothing on their own go with their base:
     *
     *  - emoji sequences, whole (see [EmojiGraphemes.deleteLength]);
     *  - a variation selector, which is invisible, so deleting only it would
     *    be a keypress that changes nothing on screen (葛󠄀, a text-style ︎);
     *  - the combining grapheme joiner, invisible for the same reason;
     *  - the LF of a CR LF pair, which would strand an invisible CR;
     *  - a Hangul vowel or final jamo, which draws as part of one syllable
     *    block with the jamo before it.
     *
     * Everything else is one code point, so a surrogate pair is never split.
     * 0 only for empty text.
     */
    fun backspaceLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val emoji = EmojiGraphemes.deleteLength(tail(before))
        if (emoji > 0) return emoji
        val last = Character.codePointBefore(before, before.length)
        val single = Character.charCount(last)
        return if (bindsToWhatPrecedes(last, before)) maxOf(single, lastLength(before)) else single
    }

    /** [text] cut into its grapheme clusters, in order. Concatenated, they are [text]. */
    fun split(text: CharSequence): List<String> {
        if (text.isEmpty()) return emptyList()
        val s = text.toString()
        val iterator = boundaries(s)
        val out = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            val end = start + firstLength(iterator, s, start)
            out += s.substring(start, end)
            start = end
        }
        return out
    }

    /** How many grapheme clusters [text] holds. */
    fun count(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val iterator = boundaries(text)
        var n = 0
        var at = 0
        while (at < text.length) {
            at += firstLength(iterator, text, at)
            n++
        }
        return n
    }

    /** Length of the cluster at [start], by ICU and by the emoji walk, whichever is wider. */
    private fun firstLength(iterator: Boundaries, text: CharSequence, start: Int): Int {
        val icu = iterator.following(start).let { if (it <= start) text.length - start else it - start }
        val window = text.subSequence(start, minOf(text.length, start + LOOKAROUND))
        val emoji = EmojiGraphemes.forwardDeleteLength(window)
        return maxOf(icu, emoji, Character.charCount(Character.codePointAt(text, start)))
            .coerceAtMost(text.length - start)
    }

    private fun bindsToWhatPrecedes(cp: Int, before: CharSequence): Boolean = when {
        isVariationSelector(cp) -> true
        cp == COMBINING_GRAPHEME_JOINER -> true
        cp == LF.code -> before.length >= 2 && before[before.length - 2] == CR
        isHangulMedialOrFinal(cp) -> true
        else -> false
    }

    private fun isVariationSelector(cp: Int) =
        cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x180B..0x180D || cp == 0x180F

    /** Conjoining jamo vowels (V) and finals (T), including the extended-B block. */
    private fun isHangulMedialOrFinal(cp: Int) =
        cp in 0x1160..0x11FF || cp in 0xD7B0..0xD7C6 || cp in 0xD7CB..0xD7FB

    private fun tail(text: CharSequence): CharSequence =
        if (text.length <= LOOKAROUND) text else text.subSequence(text.length - LOOKAROUND, text.length)

    private fun boundaries(text: CharSequence): Boundaries {
        val s = text.toString()
        return if (PlatformIcu.available) IcuBoundaries(s) else JdkBoundaries(s)
    }

    /** The two iterators' shared shape; they are not related by type. */
    private interface Boundaries {
        /** The first boundary after [offset], or -1 past the end. */
        fun following(offset: Int): Int

        /** The last boundary before [offset], or -1 before the start. */
        fun preceding(offset: Int): Int
    }

    /**
     * Built per call rather than cached: an iterator is not thread safe, and
     * these run on the main thread for a keystroke and on background threads
     * for the diff tool at once. ICU hands out clones of a cached prototype,
     * so construction is cheap.
     */
    private class IcuBoundaries(text: String) : Boundaries {
        private val iterator = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(text) }
        override fun following(offset: Int) = iterator.following(offset)
        override fun preceding(offset: Int) = iterator.preceding(offset)
    }

    private class JdkBoundaries(text: String) : Boundaries {
        private val iterator = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
        override fun following(offset: Int) = iterator.following(offset)
        override fun preceding(offset: Int) = iterator.preceding(offset)
    }
}
