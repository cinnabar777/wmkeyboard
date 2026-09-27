package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes
import com.wasimaster.wmkeyboard.core.transliteration.Khipro

/**
 * Khipro (issue #400): lowercase keys composed to Bengali by the official
 * Khipro spec ([Khipro]), the whole roman buffer re-read on every key the way
 * KhiproTeam's own library does it.
 *
 * Unlike Avro there is no reading to guess. The keys spell exactly one word,
 * so the commit is [composeBuffer] as it stands and the dictionary only
 * finishes words ([completionLanguage]), never corrects them.
 *
 * Khipro's modifiers are punctuation keys, so this composer takes more than
 * letters into its buffer: `/` (the slicer, and ঁ after a vowel), `;` (the
 * separator: `budh;bar` is বুধবার), `?` and `\` (the blinder, whose run stays
 * Latin) and, inside a word, `,` (`,,` is the nukta).
 *
 * Two specs ship. A phone keyboard already has keys for Bengali digits and the
 * danda, so soft keys read against the touchscreen spec, which leaves those
 * alone. A hardware keyboard has only ASCII, so a word typed on one reads
 * against the desktop spec, where `1` is ১, `.` is । and `$` is ৳. [variant]
 * is chosen when a word starts and kept until it is committed, so one word is
 * never read half one way and half the other.
 */
object KhiproComposer : Composer {

    /** Everything the touchscreen spec gives a meaning to, besides letters. */
    private const val TOUCH_KEYS = "/;?\\"

    /** What the desktop spec adds: the danda, currency, arithmetic, ZWNJ/ZWJ. */
    private const val DESKTOP_KEYS = "/;?\\.\$+-=`"

    private const val HASANT = '্'
    private const val ZWNJ = '‌'
    private const val ZWJ = '‍'

    /**
     * Which spec the word being composed reads against. Set by the service
     * when a word starts, from whether its first key came from a hardware
     * keyboard.
     */
    @Volatile
    var variant: Khipro.Variant = Khipro.Variant.TOUCHSCREEN

    override val isTransliterating: Boolean get() = true

    override val completionLanguage: String get() = "bn"

    /** The desktop spec turns digits into Bengali ones, so they belong to the word there. */
    override val bufferDigits: Boolean get() = variant == Khipro.Variant.DESKTOP

    override val digitsStartBuffer: Boolean get() = variant == Khipro.Variant.DESKTOP

    override fun buffersChar(c: Char): Boolean =
        c in (if (variant == Khipro.Variant.DESKTOP) DESKTOP_KEYS else TOUCH_KEYS)

    /**
     * A comma only joins a word already being composed: `j,,` is জ়, but a
     * comma between words is the punctuation it looks like.
     */
    override fun buffersChar(c: Char, composing: CharSequence): Boolean =
        buffersChar(c) || (c == ',' && composing.isNotEmpty())

    override fun composeBuffer(buffer: String): String = Khipro.convert(buffer, variant)

    /**
     * Asked of the spec itself, as Avro's is: convert with and without the key
     * and read the answer off the two. Diffed on the common prefix, because a
     * Khipro key rewrites what came before it as often as it appends (`kh`
     * turns ক into খ, the slicer turns ক্ত back into কত).
     */
    override fun keyPreview(buffer: String, key: String, wholeCluster: Boolean): String? {
        if (key.isEmpty() || !key.all { it.isLetter() || buffersChar(it) }) return null
        val after = composeBuffer(buffer + key)
        if (wholeCluster) return after.takeLast(BengaliGraphemes.clusterDeleteLength(after))
        val before = composeBuffer(buffer)
        var shared = 0
        while (shared < before.length && shared < after.length && before[shared] == after[shared]) {
            shared++
        }
        return after.substring(shared)
    }

    override fun deleteLength(before: CharSequence): Int =
        BengaliGraphemes.clusterDeleteLength(before)

    /**
     * A word with Latin in it (a blinded run), with no Bengali letter at all
     * (a lone comma), or ending on a hasant or a joiner (a conjunct left
     * half-typed) is a buffer committed mid-thought, not a word to learn.
     */
    override fun isPlausibleWord(word: String): Boolean =
        word.any { BengaliGraphemes.isBengali(it) && it.isLetter() } &&
            word.none { it in 'a'..'z' || it in 'A'..'Z' } &&
            word.last() != HASANT && word.last() != ZWNJ && word.last() != ZWJ
}
