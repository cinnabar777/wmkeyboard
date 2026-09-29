package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Unicode Bengali to "ANSI" Bengali: the encoding Bijoy Bayanno and the
 * SutonnyMJ family of fonts used before Unicode, where a Bengali glyph sits on
 * a Latin code point and the text only reads as Bengali in one of those fonts.
 * আমার সোনার বাংলা is `Avgvi †mvbvi evsjv`.
 *
 * A port of okkhor52.com's converters, one per [Version]: the same three steps
 * in the same order, with the tables in [BijoyAnsiTables] generated from the
 * converters' own scripts and the output checked against them
 * (`BijoyAnsiTest`).
 *
 * 1. The two-part vowel signs ো and ৌ are split into ে plus their second half.
 * 2. The text is put in visual order: a vowel sign drawn to the left of its
 *    consonant cluster (ি ে ৈ) moves in front of the whole cluster, and a
 *    reph (র্) moves behind the cluster it sits on, since that is where both
 *    are typed in ANSI.
 * 3. Clusters become glyphs, longest first, and a list of fix-ups picks the
 *    glyph variant each context wants: the ু under a tall letter, the ে at the
 *    start of a word.
 *
 * The fix-ups read the character in front of the text too ([before]), because
 * a word's first glyph depends on it: after a space, ে is `†`, after a letter
 * it is `‡`.
 */
object BijoyAnsi {

    /** The ANSI encoding to write; each is a different font's layout. */
    enum class Version(val number: Int) {
        V1(1),
        V2(2),
        V3(3),
        ;

        companion object {
            /** The most common one, and what the converters open on. */
            val DEFAULT = V2

            /** The version numbered [number], or [DEFAULT] for one this build does not know. */
            fun of(number: Int): Version = entries.firstOrNull { it.number == number } ?: DEFAULT
        }
    }

    private class Tables(pre: Array<String>, map: Array<String>, fix: Array<String>, lead: Array<String>) {
        val pre = pairs(pre)
        val map = pairs(map)
        val fix = pairs(fix)
        val lead = pairs(lead)
    }

    private val tables: Map<Version, Tables> by lazy {
        mapOf(
            Version.V1 to Tables(BijoyAnsiTables.PRE_V1, BijoyAnsiTables.MAP_V1, BijoyAnsiTables.FIX_V1, BijoyAnsiTables.LEAD_V1),
            Version.V2 to Tables(BijoyAnsiTables.PRE_V2, BijoyAnsiTables.MAP_V2, BijoyAnsiTables.FIX_V2, BijoyAnsiTables.LEAD_V2),
            Version.V3 to Tables(BijoyAnsiTables.PRE_V3, BijoyAnsiTables.MAP_V3, BijoyAnsiTables.FIX_V3, BijoyAnsiTables.LEAD_V3),
        )
    }

    private fun pairs(flat: Array<String>): List<Pair<String, String>> =
        (flat.indices step 2).map { flat[it] to flat[it + 1] }

    /**
     * Whether [text] holds anything the converter changes: a Bengali letter,
     * sign or digit, the danda, or a curly quote (the fonts keep glyphs of
     * their own on those code points, so a quote has to move too).
     */
    fun needsConversion(text: CharSequence): Boolean = text.any(::converts)

    /** Whether [c] is one of the characters [needsConversion] looks for. */
    fun converts(c: Char): Boolean =
        c in 'ঀ'..'৿' || c == '।' || c == '॥' || c in '‘'..'’' || c in '“'..'”'

    /**
     * [text] in ANSI [version]. [before] is what sits in front of it in the
     * field, of which only the last character is read; empty means [text]
     * starts the field. Text with nothing Bengali in it comes back as it was.
     */
    fun convert(text: String, version: Version = Version.DEFAULT, before: CharSequence = ""): String {
        if (!needsConversion(text)) return text
        val t = tables.getValue(version)
        var s = text
        for ((from, to) in t.pre) s = s.replace(from, to)
        s = rearrange(s)
        for ((from, to) in t.map) s = s.replace(from, to)
        if (before.isEmpty()) {
            s = fix(s, t)
            // Only at the very start, as the converters do it: they look at the
            // first character of the whole text, and replace the first match.
            val first = s.firstOrNull()
            t.lead.firstOrNull { it.first.first() == first }?.let { (from, to) -> s = s.replaceFirst(from, to) }
            return s
        }
        val context = before.last().toString()
        val fixed = fix(context + s, t)
        // A fix-up that rewrote the character in front as well cannot be kept:
        // that character is already in the field. The text is then fixed alone.
        return if (fixed.startsWith(context)) fixed.substring(context.length) else fix(s, t)
    }

    private fun fix(text: String, t: Tables): String {
        var s = text
        for ((from, to) in t.fix) s = s.replace(from, to)
        return s
    }

    private const val HASANT = '্'
    private const val RA = 'র'

    private fun isPreKar(c: Char?) = c == 'ি' || c == 'ে' || c == 'ৈ'

    private fun isHasant(c: Char?) = c == HASANT

    /** The converters' consonant test, the three nasal and visarga signs included. */
    private fun isConsonant(c: Char?): Boolean = c != null &&
        (c in 'ঁ'..'ঃ' || c in 'ক'..'ন' || c in 'প'..'র' || c == 'ল' ||
            c in 'শ'..'হ' || c == 'ৎ' || c == 'য়')

    /**
     * Visual order, step 2 of [convert]. A line-for-line port of the
     * converters' `ReArrangeUnicodeText`, JavaScript string semantics
     * included: a read past either end is no character, and a substring's
     * bounds are clamped rather than thrown on.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    internal fun rearrange(text: String): String {
        var s = text
        fun at(i: Int): Char? = s.getOrNull(i)
        fun sub(from: Int, to: Int = s.length): String {
            val a = from.coerceIn(0, s.length)
            val b = to.coerceIn(0, s.length)
            return if (a <= b) s.substring(a, b) else s.substring(b, a)
        }
        var barrier = 0
        var i = 0
        while (i < s.length) {
            if (isPreKar(at(i))) {
                var j = 1
                while (isConsonant(at(i - j))) {
                    if (i - j < 0 || i - j <= barrier) break
                    if (isHasant(at(i - j - 1))) j += 2 else break
                }
                s = sub(0, i - j) + s[i] + sub(i - j, i) + sub(i + 1)
                barrier = i + 1
                i++
                continue
            }
            if (i < s.length - 1 && isHasant(at(i)) && at(i - 1) == RA && !isHasant(at(i - 2))) {
                var j = 1
                var kar = 0
                while (true) {
                    if (isConsonant(at(i + j)) && isHasant(at(i + j + 1))) {
                        j += 2
                    } else {
                        if (isConsonant(at(i + j)) && isPreKar(at(i + j + 1))) kar = 1
                        break
                    }
                }
                s = sub(0, i - 1) + sub(i + j + 1, i + j + kar + 1) + sub(i + 1, i + j + 1) +
                    s[i - 1] + s[i] + sub(i + j + kar + 1)
                i += j + kar
                barrier = i + 1
                i++
                continue
            }
            i++
        }
        return s
    }
}
