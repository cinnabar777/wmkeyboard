package com.wasimaster.wmkeyboard.core.input.composer

/**
 * One OpenCC text dictionary (`key<TAB>value value…`), held for [HanVariant]'s
 * longest-prefix matching.
 *
 * The phrase table has ~50,000 rows, so it is not a `HashMap<String, String>`:
 * that would cost several megabytes of entry objects and strings in the IME
 * process. Keys and values are instead packed into one string each, keys in
 * sorted order, with offset arrays — about a megabyte, and a prefix match is a
 * walk down the sorted keys, one narrowing per character, the way a trie would.
 */
class HanPhraseTable private constructor(
    private val keys: String,
    private val keyStarts: IntArray,
    private val values: String,
    private val valueStarts: IntArray,
) {
    val size: Int get() = keyStarts.size - 1

    fun isEmpty(): Boolean = size == 0

    fun keyLength(entry: Int): Int = keyStarts[entry + 1] - keyStarts[entry]

    fun appendValue(entry: Int, out: StringBuilder) {
        out.append(values, valueStarts[entry], valueStarts[entry + 1])
    }

    /** The value stored for exactly [key], or null. */
    operator fun get(key: String): String? {
        val entry = matchPrefix(key, 0, key.length)
        if (entry < 0 || keyLength(entry) != key.length) return null
        return values.substring(valueStarts[entry], valueStarts[entry + 1])
    }

    /**
     * The entry with the longest key that [text] starts with at [start], looking
     * no further than [end]; -1 when no key matches.
     *
     * Every key in `[lo, hi)` shares the first `depth` characters of the text, and
     * since a key sorts before all its extensions, a key exactly that long is the
     * first one in the range.
     */
    fun matchPrefix(text: CharSequence, start: Int, end: Int): Int {
        var lo = 0
        var hi = size
        var depth = 0
        var best = -1
        while (start + depth < end) {
            val c = text[start + depth]
            lo = bound(lo, hi, depth, c, upper = false)
            hi = bound(lo, hi, depth, c, upper = true)
            if (lo >= hi) break
            depth++
            if (keyLength(lo) == depth) best = lo
        }
        return best
    }

    /** First entry in `[lo, hi)` whose character at [depth] is `>=` [c] (or `>` when [upper]). */
    private fun bound(lo: Int, hi: Int, depth: Int, c: Char, upper: Boolean): Int {
        var l = lo
        var h = hi
        while (l < h) {
            val mid = (l + h) ushr 1
            // A key that ends before [depth] sorts first, as if its next char were -1.
            val k = if (keyLength(mid) > depth) keys[keyStarts[mid] + depth].code else -1
            if (k < c.code || (upper && k == c.code)) l = mid + 1 else h = mid
        }
        return l
    }

    companion object {
        val EMPTY = HanPhraseTable("", IntArray(1), "", IntArray(1))

        /**
         * Parses OpenCC's text format. A row may list several values; the first is
         * the one OpenCC converts to, so it is the one kept. Identity rows are
         * kept too — 皇后 → 皇后 is a phrase rule, and it is what stops the
         * character table from writing 皇後.
         */
        fun parse(lines: Sequence<String>): HanPhraseTable {
            val rows = HashMap<String, String>()
            for (raw in lines) {
                if (raw.isEmpty() || raw.startsWith("#")) continue
                val tab = raw.indexOf('\t')
                if (tab <= 0) continue
                val key = raw.substring(0, tab).trim()
                val value = raw.substring(tab + 1).trim().substringBefore(' ')
                if (key.isEmpty() || value.isEmpty()) continue
                rows.putIfAbsent(key, value)
            }
            return of(rows)
        }

        fun of(rows: Map<String, String>): HanPhraseTable {
            if (rows.isEmpty()) return EMPTY
            // String.compareTo orders by UTF-16 unit, the same order [bound] reads.
            val sorted = rows.keys.sorted()
            val keys = StringBuilder()
            val values = StringBuilder()
            val keyStarts = IntArray(sorted.size + 1)
            val valueStarts = IntArray(sorted.size + 1)
            sorted.forEachIndexed { i, key ->
                keyStarts[i] = keys.length
                valueStarts[i] = values.length
                keys.append(key)
                values.append(rows.getValue(key))
            }
            keyStarts[sorted.size] = keys.length
            valueStarts[sorted.size] = values.length
            return HanPhraseTable(keys.toString(), keyStarts, values.toString(), valueStarts)
        }
    }
}
