package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup from romanized Bengali to dictionary words.
 *
 * Banglish spelling is loose — আছি gets typed as "asi", "achi" or "achhi".
 * Instead of trying to predict every spelling, both the romanized input and
 * each Bengali dictionary word are folded to a lenient canonical key where
 * confusable sounds collapse (স/শ/ষ/ছ/চ → s, ত/ট → t, ব/ভ → b, aspiration
 * dropped, inherent vowels dropped). Words sharing a key are phonetic
 * siblings; the highest-frequency sibling is offered as the primary
 * suggestion, which is what makes "ami valo asi" become আমি ভালো আছি.
 *
 * Frequency alone is too blunt to order those siblings, because the key
 * throws away two things the typist actually wrote down:
 *
 *  1. **Aspiration.** থাকে and তাকে share the key "tk", and তাকে is the more
 *     common word — so "thake" used to come back as তাকে, silently replacing
 *     the word the h was there to ask for. The [aspiration] mask records, per
 *     key position, whether that consonant was written aspirated, and siblings
 *     that contradict what was typed are ranked down. It is a ranking signal
 *     rather than a filter, and deliberately lopsided: typing the h and not
 *     getting it ([ASPIRATION_MISMATCH_TYPED]) is a worse error than leaving it
 *     out and getting it anyway ([ASPIRATION_MISMATCH_WORD]), since casual
 *     romanization drops an h far more readily than it invents one. Only the
 *     ক/খ, গ/ঘ, ত/থ, ট/ঠ, দ/ধ and ড/ঢ pairs count: "ch" for চ and "ph" for ফ
 *     are ordinary English-influenced spellings, not claims about aspiration.
 *  2. **A final ও.** [dropTrailingO] folds ভালো and ভাল together so both are
 *     reachable from "valo", but it also folds কথা together with কথাও — and ও
 *     is a whole word's worth of meaning ("also"). [endsWithO] keeps the two
 *     apart again, so "kotha" answers কথা and only "kothao" answers কথাও.
 */
class BengaliPhoneticIndex(entries: List<Pair<String, Int>>) : PhoneticIndex {

    // ## Layout
    //
    // Flat arrays, not a map of lists of objects. The index covers the whole
    // downloaded list — tens of thousands of words at the smallest tier and
    // 451k at the largest — and as `HashMap<String, MutableList<Entry>>` plus
    // a word-to-frequency map it cost ~140 bytes a word: 12 MB of heap at the
    // small tier, held for the life of the keyboard. Here a word costs its
    // characters and a handful of ints.
    //
    // Entries are sorted by key, then by frequency (highest first), then by
    // the order they were given in; each run of one key is a bucket. That is
    // the order the old per-key lists were in, so ties still break the same
    // way.

    /** Every indexed word's characters, back to back, in entry order. */
    private val wordChars: CharArray

    /** Where entry `i`'s word starts in [wordChars]; one extra slot for the end. */
    private val wordStart: IntArray

    private val frequencies: IntArray

    /**
     * Bit `i` set when position `i` of the entry's key was written aspirated.
     * Positions past [MASK_BITS] are in [longAspiration], which in practice
     * holds nothing: no Bengali word folds to a key that long.
     */
    private val aspirationMasks: IntArray
    private val longAspiration: Map<Int, String>

    private val endsWithO: BooleanArray

    /** The distinct keys, sorted, back to back. Keys are ASCII by construction. */
    private val keyBytes: ByteArray
    private val keyStart: IntArray

    /** Entry range of key `k`: `bucketStart[k] until bucketStart[k + 1]`. */
    private val bucketStart: IntArray

    /** Entry indexes sorted by word, for [frequencyOf]. */
    private val byWord: IntArray

    override val maxFrequency: Int

    init {
        val words = ArrayList<String>(entries.size)
        val keys = ArrayList<String>(entries.size)
        val aspirations = ArrayList<String>(entries.size)
        val freqs = ArrayList<Int>(entries.size)
        for ((word, frequency) in entries) {
            val folded = foldBengaliFull(word)
            if (folded.key.isEmpty()) continue
            words += word
            keys += folded.key
            aspirations += folded.aspiration
            freqs += frequency
        }
        val order = words.indices.sortedWith(
            compareBy<Int> { keys[it] }.thenByDescending { freqs[it] }.thenBy { it },
        )
        val n = order.size
        wordStart = IntArray(n + 1)
        frequencies = IntArray(n)
        aspirationMasks = IntArray(n)
        endsWithO = BooleanArray(n)
        val long = HashMap<Int, String>()
        var chars = 0
        for (word in words) chars += word.length
        wordChars = CharArray(chars)
        val distinctKeys = ArrayList<String>()
        val bucketStarts = ArrayList<Int>()
        var at = 0
        for ((position, source) in order.withIndex()) {
            val word = words[source]
            wordStart[position] = at
            word.toCharArray(wordChars, at)
            at += word.length
            frequencies[position] = freqs[source]
            val aspiration = aspirations[source]
            var mask = 0
            for (i in 0 until minOf(aspiration.length, MASK_BITS)) {
                if (aspiration[i] == ASPIRATED) mask = mask or (1 shl i)
            }
            aspirationMasks[position] = mask
            if (aspiration.length > MASK_BITS) long[position] = aspiration
            endsWithO[position] = endsWithO(word)
            val key = keys[source]
            if (distinctKeys.isEmpty() || distinctKeys.last() != key) {
                distinctKeys += key
                bucketStarts += position
            }
        }
        wordStart[n] = at
        longAspiration = long
        bucketStart = IntArray(distinctKeys.size + 1)
        for (k in bucketStarts.indices) bucketStart[k] = bucketStarts[k]
        bucketStart[distinctKeys.size] = n
        keyStart = IntArray(distinctKeys.size + 1)
        var keyLength = 0
        for (key in distinctKeys) keyLength += key.length
        keyBytes = ByteArray(keyLength)
        var keyAt = 0
        for ((k, key) in distinctKeys.withIndex()) {
            keyStart[k] = keyAt
            for (ch in key) keyBytes[keyAt++] = ch.code.toByte()
        }
        keyStart[distinctKeys.size] = keyAt
        byWord = (0 until n).sortedWith { a, b -> compareWords(a, b) }.toIntArray()
        maxFrequency = frequencies.maxOrNull() ?: 0
    }

    override val isEmpty: Boolean get() = bucketStart.size <= 1

    private fun wordAt(position: Int): String =
        String(wordChars, wordStart[position], wordStart[position + 1] - wordStart[position])

    /** Entries [a] and [b] in [String.compareTo] order of their words. */
    private fun compareWords(a: Int, b: Int): Int {
        val aStart = wordStart[a]
        val aLength = wordStart[a + 1] - aStart
        val bStart = wordStart[b]
        val bLength = wordStart[b + 1] - bStart
        for (i in 0 until minOf(aLength, bLength)) {
            val diff = wordChars[aStart + i] - wordChars[bStart + i]
            if (diff != 0) return diff
        }
        return aLength - bLength
    }

    /** Entry [position]'s word against [word], in [String.compareTo] order. */
    private fun compareWord(position: Int, word: String): Int {
        val start = wordStart[position]
        val length = wordStart[position + 1] - start
        for (i in 0 until minOf(length, word.length)) {
            val diff = wordChars[start + i] - word[i]
            if (diff != 0) return diff
        }
        return length - word.length
    }

    /** The bucket for [key], or -1. Binary search over the sorted keys. */
    private fun bucketOf(key: String): Int {
        var low = 0
        var high = keyStart.size - 2
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = keyStart[mid]
            val length = keyStart[mid + 1] - start
            var cmp = 0
            for (i in 0 until minOf(length, key.length)) {
                cmp = keyBytes[start + i].toInt() - key[i].code
                if (cmp != 0) break
            }
            if (cmp == 0) cmp = length - key.length
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Dictionary words phonetically matching the romanized [input], best first. */
    override fun lookup(input: String): List<String> {
        val folded = foldRomanFull(input)
        val bucket = bucketOf(folded.key)
        if (bucket < 0) return emptyList()
        val from = bucketStart[bucket]
        val until = bucketStart[bucket + 1]
        // One sibling is the overwhelmingly common case; skip the comparator.
        if (until - from == 1) return listOf(wordAt(from))
        val typedFinalO = input.isNotEmpty() && input.last().lowercaseChar() in "ow"
        return (from until until)
            .sortedByDescending { frequencies[it].toLong() * SCALE / handicap(it, folded.aspiration, typedFinalO) }
            .map(::wordAt)
    }

    /** Whether position [i] of entry [position]'s key was written aspirated. */
    private fun aspiratedAt(position: Int, i: Int): Boolean =
        if (i < MASK_BITS) {
            aspirationMasks[position] and (1 shl i) != 0
        } else {
            longAspiration[position]?.getOrNull(i) == ASPIRATED
        }

    /**
     * What to divide entry [position]'s frequency by for disagreeing with what
     * was actually typed. 1 means it agrees on every count and its frequency
     * stands as it is.
     */
    private fun handicap(position: Int, typed: String, typedFinalO: Boolean): Long {
        var divisor = 1L
        if (endsWithO[position] != typedFinalO) divisor *= FINAL_O_MISMATCH
        // An entry's aspiration pattern is as long as its key, and the key is
        // the typed one — that is how the bucket was found — so [typed], built
        // in lockstep with it, is exactly as long.
        val shared = typed.length
        for (i in 0 until shared) {
            val typedAspirated = typed[i] == ASPIRATED
            val wordAspirated = aspiratedAt(position, i)
            if (typedAspirated == wordAspirated) continue
            divisor *= if (typedAspirated) ASPIRATION_MISMATCH_TYPED else ASPIRATION_MISMATCH_WORD
        }
        return divisor
    }

    /** Dictionary frequency of a Bengali [word], 0 when unknown. */
    override fun frequencyOf(word: String): Int {
        var low = 0
        var high = byWord.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareWord(byWord[mid], word)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> {
                    // A word listed twice (the bundled list and an imported
                    // one) answers with its higher frequency, as it always has.
                    var best = frequencies[byWord[mid]]
                    var i = mid - 1
                    while (i >= 0 && compareWord(byWord[i], word) == 0) best = maxOf(best, frequencies[byWord[i--]])
                    i = mid + 1
                    while (i < byWord.size && compareWord(byWord[i], word) == 0) best = maxOf(best, frequencies[byWord[i++]])
                    return best
                }
            }
        }
        return 0
    }

    /**
     * [byWord] is already in word order, so the words with [prefix] are one
     * contiguous run of it: binary-search the run's start and keep the
     * commonest [limit] while walking it. A one-letter prefix over a big
     * downloaded list is a run of tens of thousands, which is still a linear
     * pass over ints on the suggestion thread, not a sort.
     */
    override fun completions(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        var low = 0
        var high = byWord.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareWord(byWord[mid], prefix) < 0) low = mid + 1 else high = mid
        }
        // Min-heap on frequency: the head is the weakest word kept so far.
        val best = java.util.PriorityQueue<Int>(limit + 1, compareBy { frequencies[it] })
        var i = low
        while (i < byWord.size && startsWith(byWord[i], prefix)) {
            best.add(byWord[i])
            if (best.size > limit) best.poll()
            i++
        }
        val out = ArrayList<String>(best.size)
        while (best.isNotEmpty()) out.add(wordAt(best.poll()))
        // A word listed twice (bundled and imported) is offered once.
        return out.asReversed().distinct()
    }

    /** Whether entry [position]'s word begins with [prefix]. */
    private fun startsWith(position: Int, prefix: String): Boolean {
        val start = wordStart[position]
        if (wordStart[position + 1] - start < prefix.length) return false
        for (i in prefix.indices) if (wordChars[start + i] != prefix[i]) return false
        return true
    }

    override fun matchStrength(input: String): Int {
        val folded = foldRomanFull(input)
        val bucket = bucketOf(folded.key)
        if (bucket < 0) return 0
        val typedFinalO = input.isNotEmpty() && input.last().lowercaseChar() in "ow"
        return (bucketStart[bucket] until bucketStart[bucket + 1])
            .maxOf { frequencies[it] / handicap(it, folded.aspiration, typedFinalO) }
            .toInt()
    }

    companion object {

        /** Marks a key position whose consonant was written aspirated. */
        private const val ASPIRATED = 'h'
        private const val PLAIN = '.'

        /** Key positions an entry's aspiration fits into as an [Int] mask. */
        private const val MASK_BITS = 32

        /**
         * Disagreements divide a sibling's frequency rather than outranking
         * it outright, so the penalty is a handicap and not a veto. Typing
         * "tik" for ঠিক leaves an h out, which is ordinary — ঠিক should still
         * win on being the far commoner word. Typing "thake" and getting তাকে
         * is a different matter: that h was a deliberate request, so the
         * handicap has to be big enough to overturn তাকে being ~3x commoner.
         */
        private const val ASPIRATION_MISMATCH_TYPED = 20

        /** Left the h out and the word has one — ordinary casual spelling. */
        private const val ASPIRATION_MISMATCH_WORD = 2

        private const val FINAL_O_MISMATCH = 4

        /** Keeps the divided frequencies in integer arithmetic. */
        private const val SCALE = 1024L

        /**
         * Bengali consonants whose aspiration a typist genuinely signals with
         * an h. ছ and ফ are absent on purpose: "ch" is how চ gets written by
         * anyone used to English, and "ph" is the ordinary spelling of ফ, so
         * neither h says anything about aspiration. ঝ and ভ likewise, since
         * "jh"/"bh" compete with the plain "j"/"v" spellings of the same
         * sounds.
         */
        private val aspiratedLetters = setOf('খ', 'ঘ', 'ঠ', 'ঢ', 'থ', 'ধ')

        /** Roman consonants where a following h is a real aspiration mark. */
        private const val ASPIRABLE_ROMAN = "kgtd"

        private val consonantClasses = mapOf(
            'ক' to 'k', 'খ' to 'k', 'গ' to 'g', 'ঘ' to 'g', 'ঙ' to 'q',
            'চ' to 's', 'ছ' to 's', 'জ' to 'j', 'ঝ' to 'j', 'ঞ' to 'q',
            'ট' to 't', 'ঠ' to 't', 'ড' to 'd', 'ঢ' to 'd', 'ণ' to 'n',
            'ত' to 't', 'থ' to 't', 'দ' to 'd', 'ধ' to 'd', 'ন' to 'n',
            'প' to 'p', 'ফ' to 'p', 'ব' to 'b', 'ভ' to 'b', 'ম' to 'm',
            'য' to 'j', 'র' to 'r', 'ল' to 'l', 'শ' to 's', 'ষ' to 's',
            'স' to 's', 'হ' to 'h',
            // খণ্ড-ত is a ত, and nobody spells it with a key of its own when
            // typing fast: "hotat" has to be able to reach হঠাৎ.
            'ৎ' to 't',
            // Nukta letters as precomposed code points (ড় ঢ় য়); decomposed
            // input is normalized before lookup.
            'ড়' to 'r', 'ঢ়' to 'r', 'য়' to 'y',
            'ং' to 'q', 'ঃ' to 'h',
        )

        private val vowelClasses = mapOf(
            'অ' to 'o', 'আ' to 'a', 'া' to 'a',
            'ই' to 'i', 'ঈ' to 'i', 'ি' to 'i', 'ী' to 'i',
            'উ' to 'u', 'ঊ' to 'u', 'ু' to 'u', 'ূ' to 'u',
            'এ' to 'e', 'ে' to 'e', 'ঐ' to 'i', 'ৈ' to 'i',
            'ও' to 'o', 'ো' to 'o', 'ঔ' to 'u', 'ৌ' to 'u',
            'ঋ' to 'r', 'ৃ' to 'r',
        )

        /**
         * A canonical key plus, character for character, which of its
         * positions were written aspirated. The two strings are built in
         * lockstep and edited in lockstep, so position `i` of [aspiration]
         * always describes position `i` of [key] — that alignment is what
         * lets the roman and Bengali sides be compared at all.
         */
        class Folded(val key: String, val aspiration: String)

        /**
         * [word] with its decomposed nukta pairs as precomposed code points,
         * and the corrupt U+0985 U+09BE pair (seen in scraped word lists) as
         * U+0986. NFC leaves the nukta pairs alone, since U+09DC, U+09DD and
         * U+09DF are composition exclusions, so the downloaded list and the
         * bundled one can spell the same word two ways.
         */
        private fun precomposed(word: String): String = word
            .replace("\u09A1\u09BC", "\u09DC")
            .replace("\u09A2\u09BC", "\u09DD")
            .replace("\u09AF\u09BC", "\u09DF")
            .replace("\u0985\u09BE", "\u0986")

        /**
         * [listed] with [bundled]'s frequencies put back over it, when
         * [listed] has none of its own.
         *
         * A downloaded Bangla list replaces the bundled one, and the repo's
         * list is a bare wordlist: all 451k words say frequency 1. Siblings
         * then tie, the literal-over-sibling guard in the suggestion engine
         * has nothing to weigh, and a tie falls to trie order, which is ছ
         * before স. So the hand-ranked frequencies the bundled list carries
         * come back for the words it has, and [listed] keeps the rest. That
         * also restores the common words a download from before flat lists
         * were taken whole is missing: it kept the first lines of an
         * alphabetical list, which stop part way through the alphabet. A list
         * with real frequencies is returned as it is, since the two would not
         * be on the same scale; [bundled] is only opened when it is needed.
         */
        fun withBundledRanking(
            listed: List<Pair<String, Int>>,
            bundled: () -> List<Pair<String, Int>>,
        ): List<Pair<String, Int>> {
            if (listed.isEmpty()) return listed
            val flat = listed[0].second
            if (listed.any { it.second != flat }) return listed
            val ranked = bundled()
            if (ranked.isEmpty()) return listed
            val known = ranked.mapTo(HashSet(ranked.size * 2)) { precomposed(it.first) }
            return ranked + listed.filter { precomposed(it.first) !in known }
        }

        /** Folds a Bengali word to its canonical phonetic key. */
        fun foldBengali(word: String): String = foldBengaliFull(word).key

        /** Whether a Bengali word ends in the standalone ও or its ো sign. */
        fun endsWithO(word: String): Boolean =
            word.isNotEmpty() && (word.last() == 'ও' || word.last() == 'ো')

        /** Folds a Bengali word, keeping its aspiration pattern. */
        fun foldBengaliFull(word: String): Folded {
            // Normalize decomposed nukta pairs to precomposed code points,
            // and the corrupt U+0985 U+09BE pair (seen in scraped word lists)
            // to U+0986: the pair folds to "oa" and would hijack keys like
            // "oasi"/"wasi".
            val normalized = precomposed(word)
            val out = StringBuilder()
            val marks = StringBuilder()
            for (ch in normalized) {
                val c = consonantClasses[ch]
                val v = vowelClasses[ch]
                when {
                    c != null -> {
                        out.append(c)
                        marks.append(if (ch in aspiratedLetters) ASPIRATED else PLAIN)
                    }
                    v != null -> {
                        out.append(v)
                        marks.append(PLAIN)
                    }
                    // hasant, chandrabindu and anything unmapped fold away
                }
            }
            return dropTrailingO(Folded(out.toString(), marks.toString()))
        }

        /** Folds romanized Bengali typing to the same canonical key. */
        fun foldRoman(input: String): String = foldRomanFull(input).key

        /** Folds romanized typing, keeping the aspiration the typist wrote. */
        fun foldRomanFull(input: String): Folded {
            val lower = input.lowercase()
            val out = StringBuilder()
            val marks = StringBuilder()
            var i = 0
            while (i < lower.length) {
                val ch = lower[i]
                val next = lower.getOrNull(i + 1)
                when {
                    // Aspiration folds: kh→k, gh→g, ch→s, th→t, dh→d, ph→p, bh→b, sh→s
                    next == 'h' && ch in "kgctdpbs" -> {
                        out.append(if (ch == 'c') 's' else ch)
                        marks.append(if (ch in ASPIRABLE_ROMAN) ASPIRATED else PLAIN)
                        i += 2
                        // chh → s as well
                        if (ch == 'c' && lower.getOrNull(i) == 'h') i++
                        continue
                    }
                    ch == 'c' -> out.append('s').also { marks.append(PLAIN) }
                    ch == 'v' -> out.append('b').also { marks.append(PLAIN) }
                    ch == 'w' -> out.append('o').also { marks.append(PLAIN) }
                    ch == 'z' -> out.append('j').also { marks.append(PLAIN) }
                    ch == 'f' -> out.append('p').also { marks.append(PLAIN) }
                    // "qq" is ঁ, which the Bengali side folds away; a lone
                    // q is ক, the same as on the Avro layout.
                    ch == 'q' && next == 'q' -> i++
                    ch == 'q' -> out.append('k').also { marks.append(PLAIN) }
                    ch == 'x' -> {
                        out.append('k').append('s')
                        marks.append(PLAIN).append(PLAIN)
                    }
                    ch in "aeiou" -> {
                        // Doubled vowels lengthen: ee→i (ঈ), oo→u (উ)
                        if (next == ch) {
                            i++
                            out.append(when (ch) { 'e' -> 'i'; 'o' -> 'u'; else -> ch })
                        } else {
                            out.append(ch)
                        }
                        marks.append(PLAIN)
                    }
                    ch.isLetter() -> out.append(ch).also { marks.append(PLAIN) }
                    // punctuation and digits fold away
                }
                i++
            }
            return dropTrailingO(collapseInherent(Folded(out.toString(), marks.toString())))
        }

        /**
         * Romanized inherent vowels: "kori" carries its vowels explicitly, but
         * the Bengali fold of করি is "kri" (inherent o is invisible). Dropping
         * medial "o" between consonants from the roman side aligns the two.
         */
        private fun collapseInherent(folded: Folded): Folded {
            val key = folded.key
            val out = StringBuilder()
            val marks = StringBuilder()
            for ((index, ch) in key.withIndex()) {
                val prevIsConsonant = index > 0 && key[index - 1] !in "aeiou"
                val nextIsConsonant = index < key.length - 1 && key[index + 1] !in "aeiou"
                if (ch == 'o' && prevIsConsonant && nextIsConsonant) continue
                out.append(ch)
                marks.append(folded.aspiration[index])
            }
            return Folded(out.toString(), marks.toString())
        }

        /** Word-final inherent/o-kar ambiguity: ভালো vs ভাল both end "balo". */
        private fun dropTrailingO(folded: Folded): Folded =
            if (folded.key.length > 1 && folded.key.endsWith('o')) {
                Folded(folded.key.dropLast(1), folded.aspiration.dropLast(1))
            } else {
                folded
            }
    }
}
