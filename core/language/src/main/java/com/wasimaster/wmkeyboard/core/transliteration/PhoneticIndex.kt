package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup from a romanized spelling to the dictionary words it
 * could stand for, for a layout whose keys are Latin and whose text is not.
 *
 * Romanized spelling is loose in every language that has one, so an index
 * folds both sides — what was typed, and each dictionary word — to a lenient
 * key where the confusable sounds collapse, and words sharing a key are
 * siblings. How the fold works is the language's own business
 * ([BengaliPhoneticIndex], [HindiPhoneticIndex]); what the suggestion engine
 * needs from it is only this.
 */
interface PhoneticIndex {

    /** Dictionary words phonetically matching the romanized [input], best first. */
    fun lookup(input: String): List<String>

    /** Dictionary frequency of a native-script [word], 0 when unknown. */
    fun frequencyOf(word: String): Int

    /**
     * The list's native-script words that begin with [prefix], commonest
     * first, at most [limit] of them. What a layout whose keys already spell
     * the word (Khipro) asks to finish it. Empty by default: an index is built
     * for romanized lookup, and only one that keeps its words sorted can answer
     * this cheaply.
     */
    fun completions(prefix: String, limit: Int): List<String> = emptyList()

    /**
     * How good [lookup]'s best answer for [input] is: that word's frequency,
     * divided by whatever the fold's discarded detail disagreed with. 0 when
     * nothing matches. A caller weighing "is this romanization a word of the
     * language at all" wants this rather than [frequencyOf] the head, because a
     * sibling reached only by ignoring what was typed is weak evidence however
     * common it is.
     */
    fun matchStrength(input: String): Int = lookup(input).firstOrNull()?.let(::frequencyOf) ?: 0

    /** The largest frequency in the list, the scale [frequencyOf] is read against. 0 when empty. */
    val maxFrequency: Int get() = 0

    /** Whether there is no word behind this index at all — no list installed. */
    val isEmpty: Boolean

    companion object {
        /** No dictionary: every lookup misses, and the rules stand alone. */
        val EMPTY: PhoneticIndex = object : PhoneticIndex {
            override fun lookup(input: String): List<String> = emptyList()
            override fun frequencyOf(word: String): Int = 0
            override val isEmpty: Boolean get() = true
        }
    }
}
