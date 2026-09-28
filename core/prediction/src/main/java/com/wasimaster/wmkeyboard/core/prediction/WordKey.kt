package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.text.Nfc

/**
 * The one spelling a word is stored and looked up under.
 *
 * Every store here matches words by their characters — a hash key in
 * [UserLexicon], a binary search over a follower run in [MappedNgramPack], a
 * walk down a [Trie] — so two spellings of the same word are two different
 * words to all of them, and the lookup does not fail, it simply misses.
 *
 * Case was always folded for that reason. Composition is the other half of the
 * same problem, and Bengali is where it stops being theoretical: য় has both a
 * precomposed spelling (U+09DF) and a decomposed one (য + U+09BC NUKTA). NFC
 * keeps the decomposed one, because U+09DF and its siblings U+09DC and U+09DD
 * are on Unicode's composition-exclusion list, so NFC takes them apart and
 * never puts them back. The word lists write that form; every layout and
 * `AvroPhonetic` commit the precomposed one. They render identically, so every
 * disagreement between them looks, on screen, exactly like a word nothing had
 * ever seen.
 *
 * Folding at the store boundary rather than at the producer is deliberate.
 * Words do not only arrive from the layout the user is typing on: the context
 * for the next suggestion is re-read from the text field itself, which may hold
 * text pasted from elsewhere, typed on another keyboard, or left by the app. No
 * fix applied to a composer could reach those; this reaches all of them.
 *
 * Applying it on both sides of every store is what makes it safe — a store
 * written through here and read through here cannot disagree with itself.
 *
 * Cost is one pass over the characters. A word made only of characters below
 * U+0300 returns at once: nothing there composes with anything, which is the
 * same boundary ICU's own NFC quick check starts from. Everything else goes
 * through [Nfc], whose quick check reads ICU's tables in place. It used to be
 * `java.text.Normalizer.isNormalized`, which on Android wraps the same ICU but
 * allocates on every call — measured at about a third of a megabyte per twenty
 * keystrokes when it ran for every n-gram lookup, Latin words included. The
 * shortcut above took Latin out of that, but every Bengali or Hindi lookup
 * still paid it until the quick check replaced the call.
 */
object WordKey {

    fun of(word: String): String = normalize(word.lowercase())

    /**
     * The same normalisation without the case fold: the spelling a word is
     * *shown* in, next to the key it is stored under.
     *
     * Case memory (#44) keeps both — "Boston" beside "boston" — and the two
     * are only comparable if they went through the same composition pass, so
     * a surface form recorded here always satisfies `of(surface(w)) == of(w)`.
     */
    fun surface(word: String): String = normalize(word)

    private fun normalize(word: String): String {
        if (word.all { it < FIRST_COMPOSING }) return word
        return Nfc.normalize(word)
    }

    /** The combining grave accent: the first code point NFC can do anything with. */
    private const val FIRST_COMPOSING = '̀'
}
