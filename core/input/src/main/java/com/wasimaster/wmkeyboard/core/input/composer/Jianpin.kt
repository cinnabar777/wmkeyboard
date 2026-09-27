package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Jianpin (简拼): pinyin abbreviated to the first letter of each syllable, so
 * `wm` reaches 我们 and `zg` reaches 中国. Every shipping Chinese IME takes it,
 * and most Chinese is typed that way in practice — a full-pinyin phrase of four
 * syllables is a dozen keys where four would do.
 *
 * The trick, as with [T9Pinyin], is to never scan the dictionary for it. A bare
 * initial is one more kind of *unit* for the segmenter — `hd` splits into `h`
 * and `d` exactly the way `haode` splits into `hao` and `de` — and each such
 * unit hands the decoder the syllables it could stand for ([Index.expansions]).
 * The decoder already resolves per-unit ambiguity against the sorted conversion
 * table with a binary search per step, pruning a reading the moment no word
 * starts with it, so the cost is what the dictionary contains under those
 * initials, not the product of the option lists.
 *
 * Boundaries are the point: `hd` means `h…` + `d…`, never "any reading
 * containing h and d", and `zguo` means `z…` + `guo`. Because a full syllable
 * is always longer than its own initial, the segmenter's longest-first walk keeps
 * every full-pinyin buffer segmenting exactly as before; only a letter that is
 * not a whole syllable becomes an abbreviation.
 *
 * Conventions follow Sogou and Google Pinyin: `z`, `c` and `s` cover the
 * retroflex initials too (`zg` → 中国), and a typed `zh`, `ch` or `sh` reads
 * both ways — as the digraph initial (`sh` → 是) and as two initials (`sh` →
 * 上海). The segmenter only ever cuts single letters, so the digraph is not a
 * unit; instead [Index.expansions] carries it and the composer hands the decoder
 * a *merged* reading over the two units ([Lattice.Input.merged]), which an
 * apostrophe between them (`s'h`) switches off. The zero-initial syllables `a`,
 * `e` and `o` are both a syllable and an abbreviation (`aq` → 安全), and the
 * composer ranks the exact reading first.
 *
 * Derived from [PinyinSyllables.valid], so the index is empty until that
 * inventory loads and is rebuilt alongside it. Fuzzy Pinyin is a separate
 * concern — it widens a *complete* syllable to its confusable spellings — and the
 * two compose: an exact syllable may carry fuzzy variants, an abbreviated one
 * carries its expansions, and the decoder sees both as options.
 */
object Jianpin {

    /**
     * What the segmenter and the decoder need, built together so they cannot
     * disagree about which units are abbreviations.
     */
    class Index(
        /**
         * Abbreviation → the syllables it can stand for, sorted: `h` → [ha, hai,
         * …]. Keyed by single letters and by the digraphs `zh`/`ch`/`sh`.
         */
        val expansions: Map<String, List<String>>,
        /** The segmenter inventory: every full syllable plus every single-letter abbreviation. */
        val units: Set<String>,
    )

    val EMPTY = Index(emptyMap(), emptySet())

    /** The loaded index; [EMPTY] until the syllable inventory arrives. */
    @Volatile
    var index: Index = EMPTY

    /** The digraph initials: expansions of their own, but never segmenter units. */
    val DIGRAPHS = listOf("zh", "ch", "sh")

    /**
     * Builds the index over [inventory]. Every syllable's first letter is a unit
     * and a key (including the vowels that lead zero-initial syllables); each
     * digraph initial is a key when the inventory has syllables under it, for
     * the decoder's merged reading of the two letters.
     */
    fun build(inventory: Set<String>): Index {
        if (inventory.isEmpty()) return EMPTY
        val syllables = inventory.map { it.lowercase() }.filter { it.isNotEmpty() }.sorted()
        val letters = LinkedHashSet<String>()
        for (s in syllables) letters.add(s.substring(0, 1))
        val keys = LinkedHashSet(letters)
        for (d in DIGRAPHS) if (syllables.any { it.startsWith(d) }) keys.add(d)
        val expansions = HashMap<String, List<String>>(keys.size * 2)
        for (key in keys) {
            // `startsWith` on the letter key is what makes `z` cover `zh…`.
            expansions[key] = syllables.filter { it.startsWith(key) }
        }
        return Index(expansions, syllables.toSet() + letters)
    }
}
