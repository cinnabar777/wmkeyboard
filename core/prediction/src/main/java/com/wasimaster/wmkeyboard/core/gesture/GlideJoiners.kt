package com.wasimaster.wmkeyboard.core.gesture

/**
 * The punctuation that sits *inside* a word and that no finger can draw.
 *
 * The glide grid is letters only — `keySpelling` admits letters and combining
 * marks and nothing else, so `LayoutSet.glideKeys` never puts a hyphen, a dot
 * or an at sign on it. A dictionary entry is free to hold them all the same:
 * `f-droid` in the personal lexicon, `e-mail` or `in-depth` in an imported word
 * list, `wasi@example.com` learned from something the user typed. Before this,
 * the decoder met the joiner's trie edge, found no key for it, and threw the
 * whole subtree away — so the word was in the dictionary, showed up in
 * completion and autocorrect, and could not be swiped (issue #230).
 *
 * The decoder steps over these characters instead: it descends the edge without
 * spending any of the stroke, so `f-droid` is drawn as `fdroid` and comes back
 * spelled with its hyphen. That is the same shape the reporter asked for —
 * indexed stripped, emitted whole — without a second index to build, keep in
 * memory or hold in step with the first.
 *
 * The Persian half-space (ZWNJ, U+200C) is one of them for the same reason:
 * می‌کنم and سگ‌ها are spelled with it, the Persian layout gives it a key of its
 * own that is not a letter and so not on the grid, and the stroke for the word
 * is the stroke for its letters (#406).
 *
 * **The apostrophe is deliberately not here.** It has its own route onto the
 * grid (`GestureSettings.apostropheKey`, which puts `'` and `’` on a key the
 * user picks) and its own repair afterwards (`Apostrophes.fixExplicit`), built
 * that way because the contractions are exactly where a free skip does damage:
 * the shipped English list holds 89 of them, and `its`/`it's`, `were`/`we're`,
 * `ill`/`i'll`, `shed`/`she'd`, `cant`/`can't` are all one stroke. Letting them
 * through here would decide those by frequency, which is the guess that route
 * exists to refuse. When the user *has* chosen an apostrophe key the character
 * is on the grid, [isJoiner] never runs for it, and nothing here interferes.
 */
object GlideJoiners {

    /**
     * Whether [codePoint] is a joiner: punctuation a word may be spelled with
     * that a stroke passes straight through.
     *
     * Deliberately a short closed list rather than a Unicode category test. A
     * category would sweep in the quote marks, the brackets and every dash-like
     * character a word list might carry by accident, and each one it admits is
     * a subtree the decoder walks on every stroke for a word nobody swipes.
     */
    fun isJoiner(codePoint: Int): Boolean = when (codePoint) {
        '-'.code, HYPHEN, NON_BREAKING_HYPHEN -> true
        '.'.code, '@'.code, '_'.code, '&'.code, '+'.code, '/'.code -> true
        ZERO_WIDTH_NON_JOINER -> true
        else -> false
    }

    /**
     * Whether [word] is a list entry that uses a joiner to spell something that
     * is not a word. A decoder that steps over joiners reaches these too, and
     * the big downloadable lists hold them at high counts (issue #304).
     *
     * The English `en_full` list is built from film subtitles, which write
     * speech down as it was spoken. Two kinds of entry come out of that:
     *
     *  - **Ends on a joiner.** An interrupted word (`that-` at 6,685, `can-`,
     *    `to-`) or a sentence-final full stop left on the word (`no.` at
     *    11,666). A joiner sits *inside* a word, the way [isJoiner] says, and
     *    one at the end is not inside anything. It is also the same stroke as
     *    the bare word, so it can only ever be a second or third choice, and
     *    the slot it takes is one a real word would have had.
     *  - **A stutter.** `n-no`, `c-can`, `t-that`, `c-c-can`: the part before
     *    the hyphen starts the part after it again. A stutter draws the same
     *    as the word it stutters, with one extra repeat, so it sat in the
     *    strip's second slot after nearly every common word.
     *
     * The stutter test is by spelling alone, so it also catches a few real
     * words (`D-Day`, `t-test`). Each is the same stroke as a much commoner
     * word (`day`, `test`), so it seldom reached the strip anyway, and the
     * decoder only applies this to word lists. A word the user taught the
     * keyboard is still glided however it is spelled.
     */
    fun isNonWord(word: String): Boolean {
        if (word.isEmpty()) return false
        if (isJoiner(word.codePointBefore(word.length))) return true
        var hyphen = -1
        for (i in word.indices) {
            if (isHyphen(word[i].code)) {
                hyphen = i
                break
            }
        }
        if (hyphen <= 0) return false
        val rest = word.length - hyphen - 1
        return hyphen < rest && word.regionMatches(hyphen + 1, word, 0, hyphen, ignoreCase = true)
    }

    private fun isHyphen(codePoint: Int): Boolean =
        codePoint == '-'.code || codePoint == HYPHEN || codePoint == NON_BREAKING_HYPHEN

    /** U+2010 HYPHEN — what a list typeset rather than typed spells `-` as. */
    private const val HYPHEN = 0x2010

    /** U+2011 NON-BREAKING HYPHEN. */
    private const val NON_BREAKING_HYPHEN = 0x2011

    /** U+200C, the Persian half-space. */
    private const val ZERO_WIDTH_NON_JOINER = 0x200C
}
