package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.text.Graphemes

/**
 * Compares the text an AI action ran on against what it produced, so the panel
 * can show what changed the way `git diff` does rather than only the new text.
 *
 * Pure text in, data out: no resources, no Android UI types. The caller decides
 * how a [Span] is drawn and what to say about a [Result] that came back empty.
 */
object TextDiff {

    enum class Op {
        /** In both texts, unchanged. */
        KEEP,

        /** Only in the result: the model wrote this. */
        ADD,

        /** Only in the source: the model took this out. */
        DELETE,
    }

    /**
     * One run of the comparison. [text] is a verbatim slice of one input, never
     * re-spelled, so the pieces always reassemble to the originals:
     * everything that is not [Op.ADD] rebuilds the source, and everything that
     * is not [Op.DELETE] rebuilds the result. The tests assert exactly that,
     * because it is the property every other guarantee here rests on.
     */
    data class Span(val op: Op, val text: String)

    /** How the two texts were cut up before being matched. */
    enum class Granularity {
        /** A word and the space after it. The usual case. */
        WORD,

        /**
         * One grapheme cluster. For scripts that do not put spaces between
         * words, where whole-text word tokens would mean whole-text spans.
         */
        GRAPHEME,

        /** A line and its newline. The step down when a text is too big. */
        LINE,

        /** Nothing was compared: see [Result.tooLong]. */
        NONE,
    }

    data class Result(
        val spans: List<Span>,
        val granularity: Granularity,
        /** Tokens only in the result. */
        val added: Int,
        /** Tokens only in the source. */
        val deleted: Int,
        /**
         * A ceiling was reached and no comparison was made, so [spans] is empty.
         * The caller shows the plain result and says why.
         */
        val tooLong: Boolean,
    ) {
        /** The model gave back exactly what it was given. */
        val identical: Boolean get() = added == 0 && deleted == 0
    }

    /**
     * Longest pair of texts to compare at all. Past this the work stops being
     * worth a keyboard panel, whatever the granularity.
     */
    const val MAX_CHARS = 40_000

    /** Most tokens to match after the common ends are trimmed off. */
    const val MAX_TOKENS = 4_000

    /**
     * Most edits to trace. The matcher costs O(tokens × edits), so this is the
     * knob that bounds the work: 4,000 × 256 is about a million steps, and a
     * pair of texts that needs more than 256 edits has been rewritten rather
     * than edited, which no inline comparison reads well anyway.
     */
    const val MAX_EDIT_DISTANCE = 256

    /**
     * Fraction of the letters that must belong to a script written without
     * spaces before the comparison drops to [Granularity.GRAPHEME]. Well under
     * half, because a mostly-Chinese text with an English clause in it still
     * needs per-character matching to say anything useful.
     */
    private const val SPACELESS_SHARE = 0.30

    /** How much of each text to sample when picking the granularity. */
    private const val SCRIPT_SAMPLE_CHARS = 2_000

    /**
     * Compares [source] with [result].
     *
     * Fast paths first, then: pick a granularity, cut both texts into tokens,
     * drop the common start and end, and match what is left. Trimming first is
     * what makes this cheap in the case that matters most, where an action
     * changed three words in five hundred.
     */
    fun diff(source: String, result: String): Result {
        if (source == result) {
            val spans = if (source.isEmpty()) emptyList() else listOf(Span(Op.KEEP, source))
            return Result(spans, Granularity.WORD, added = 0, deleted = 0, tooLong = false)
        }
        if (source.isEmpty()) {
            return Result(listOf(Span(Op.ADD, result)), Granularity.WORD, 1, 0, tooLong = false)
        }
        if (result.isEmpty()) {
            return Result(listOf(Span(Op.DELETE, source)), Granularity.WORD, 0, 1, tooLong = false)
        }
        if (source.length + result.length > MAX_CHARS) return tooLong()

        val granularity = pickGranularity(source, result)
        return compare(source, result, granularity)
            ?: compare(source, result, Granularity.LINE)
            ?: tooLong()
    }

    private fun tooLong() =
        Result(emptyList(), Granularity.NONE, added = 0, deleted = 0, tooLong = true)

    /** One attempt at [granularity]; null when a ceiling was reached. */
    private fun compare(source: String, result: String, granularity: Granularity): Result? {
        val a = tokenize(source, granularity)
        val b = tokenize(result, granularity)

        // Trim the common ends off as tokens rather than characters. Doing it
        // by character would cut through a word (and, worse, through a grapheme
        // cluster), leaving the middle to start mid-letter.
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (
            tail < a.size - head &&
            tail < b.size - head &&
            a[a.size - 1 - tail] == b[b.size - 1 - tail]
        ) {
            tail++
        }

        val midA = a.subList(head, a.size - tail)
        val midB = b.subList(head, b.size - tail)
        if (midA.size > MAX_TOKENS || midB.size > MAX_TOKENS) return null
        val ops = myers(midA, midB) ?: return null

        val spans = ArrayList<Span>()
        val prefix = a.subList(0, head).joinToString("")
        if (prefix.isNotEmpty()) spans += Span(Op.KEEP, prefix)
        spans += middleSpans(midA, midB, ops)
        val suffix = a.subList(a.size - tail, a.size).joinToString("")
        if (suffix.isNotEmpty()) spans += Span(Op.KEEP, suffix)

        return Result(
            spans = coalesce(factorSharedSpace(spans)),
            granularity = granularity,
            added = ops.count { it == Op.ADD },
            deleted = ops.count { it == Op.DELETE },
            tooLong = false,
        )
    }

    /**
     * Walks the edit script back over the two token lists, in a fixed
     * delete-then-add order for a replacement. The matcher can produce either
     * order, and reading "the old words, then the new ones" everywhere is worth
     * more than following whichever path it happened to take.
     */
    private fun middleSpans(a: List<String>, b: List<String>, ops: List<Op>): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        var j = 0
        var index = 0
        while (index < ops.size) {
            when (ops[index]) {
                Op.KEEP -> {
                    out += Span(Op.KEEP, a[i])
                    i++
                    j++
                    index++
                }
                else -> {
                    // Gather the whole run of edits, then emit the deletions
                    // ahead of the additions whatever order they arrived in.
                    val deleted = StringBuilder()
                    val added = StringBuilder()
                    while (index < ops.size && ops[index] != Op.KEEP) {
                        if (ops[index] == Op.DELETE) deleted.append(a[i++])
                        else added.append(b[j++])
                        index++
                    }
                    if (deleted.isNotEmpty()) out += Span(Op.DELETE, deleted.toString())
                    if (added.isNotEmpty()) out += Span(Op.ADD, added.toString())
                }
            }
        }
        return out
    }

    /**
     * Pulls the whitespace a replacement did not actually change out of its
     * two spans and into a KEEP of its own.
     *
     * A word token carries the space after it (see [wordTokens]), so swapping
     * "quick" for "rapid" produced spans that ran "quick " → "rapid " and drew
     * the strike and the underline straight through a space that is in both
     * texts. Only a DELETE immediately followed by an ADD is treated this way:
     * in a plain deletion the space really is gone from the result, and moving
     * it would leave the panel showing two spaces where the model left one.
     *
     * The reassembly property holds because the whitespace moved is present on
     * both sides — everything that is not ADD still rebuilds the source, and
     * everything that is not DELETE still rebuilds the result.
     */
    private fun factorSharedSpace(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>(spans.size)
        var index = 0
        while (index < spans.size) {
            val span = spans[index]
            val next = spans.getOrNull(index + 1)
            if (span.op != Op.DELETE || next?.op != Op.ADD) {
                out += span
                index++
                continue
            }
            var deleted = span.text
            var added = next.text
            val lead = sharedSpaceAffix(deleted, added, leading = true)
            deleted = deleted.substring(lead.length)
            added = added.substring(lead.length)
            val tail = sharedSpaceAffix(deleted, added, leading = false)
            deleted = deleted.dropLast(tail.length)
            added = added.dropLast(tail.length)
            if (lead.isNotEmpty()) out += Span(Op.KEEP, lead)
            if (deleted.isNotEmpty()) out += Span(Op.DELETE, deleted)
            if (added.isNotEmpty()) out += Span(Op.ADD, added)
            if (tail.isNotEmpty()) out += Span(Op.KEEP, tail)
            index += 2
        }
        return out
    }

    /** The longest all-whitespace prefix (or suffix) both strings share. */
    private fun sharedSpaceAffix(a: String, b: String, leading: Boolean): String {
        val limit = minOf(a.length, b.length)
        var length = 0
        while (length < limit) {
            val ca = if (leading) a[length] else a[a.length - 1 - length]
            val cb = if (leading) b[length] else b[b.length - 1 - length]
            if (ca != cb || !ca.isWhitespace()) break
            length++
        }
        if (length == 0) return ""
        return if (leading) a.substring(0, length) else a.substring(a.length - length)
    }

    /** Merges neighbouring spans with the same op, so styling stays cheap. */
    private fun coalesce(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>(spans.size)
        for (span in spans) {
            if (span.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.op == span.op) {
                out[out.size - 1] = last.copy(text = last.text + span.text)
            } else {
                out += span
            }
        }
        return out
    }

    // ---- tokenizing ----

    internal fun tokenize(text: String, granularity: Granularity): List<String> =
        when (granularity) {
            Granularity.WORD -> wordTokens(text)
            Granularity.GRAPHEME -> graphemeTokens(text)
            Granularity.LINE -> lineTokens(text)
            Granularity.NONE -> emptyList()
        }

    /**
     * A word together with the space after it, so deleting a word deletes its
     * space too: "a b c" losing "b" leaves "a c", not "a  c".
     *
     * A run of whitespace that contains a newline is its own token instead. A
     * paragraph break that the model removed is a real change, and gluing it to
     * the word in front would hide it inside an otherwise unchanged word.
     */
    private fun wordTokens(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                val start = i
                while (i < text.length && text[i].isWhitespace()) i++
                out += text.substring(start, i)
                continue
            }
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            var end = i
            while (end < text.length && text[end].isWhitespace()) end++
            val trailing = text.substring(i, end)
            if (trailing.isNotEmpty() && !trailing.contains('\n')) {
                out += text.substring(start, end)
                i = end
            } else {
                out += text.substring(start, i)
            }
        }
        return out
    }

    /**
     * One grapheme cluster per token, via [Graphemes]. That is what keeps a
     * comparison from cutting through an emoji built out of joined parts, a skin
     * tone or variation selector, a Bengali or Devanagari conjunct, a nukta
     * form, or a surrogate pair. Every one of those is several code units that
     * mean one thing on screen. [Graphemes] adds what the platform's own
     * iterator misses on older Android releases, where emoji sequences split.
     */
    private fun graphemeTokens(text: String): List<String> = Graphemes.split(text)

    /** A line and the newline that ends it. */
    private fun lineTokens(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                out += text.substring(start)
                break
            }
            out += text.substring(start, newline + 1)
            start = newline + 1
        }
        return out
    }

    /**
     * [Granularity.GRAPHEME] when enough of the letters belong to a script that
     * does not separate words with spaces, or when a long text has no
     * whitespace at all. One decision for the whole comparison: a text that
     * mixes Chinese and English matches per character throughout, which is
     * noisier than per word but never wrong, whereas the reverse would turn a
     * whole Chinese sentence into one span.
     */
    internal fun pickGranularity(source: String, result: String): Granularity {
        var letters = 0
        var spaceless = 0
        var whitespace = 0
        var sampled = 0
        for (text in listOf(source, result)) {
            var index = 0
            while (index < text.length && sampled < SCRIPT_SAMPLE_CHARS) {
                val code = text.codePointAt(index)
                index += Character.charCount(code)
                sampled++
                if (Character.isWhitespace(code)) {
                    whitespace++
                    continue
                }
                if (!Character.isLetter(code)) continue
                letters++
                if (isSpaceless(code)) spaceless++
            }
        }
        if (letters > 0 && spaceless.toDouble() / letters >= SPACELESS_SHARE) {
            return Granularity.GRAPHEME
        }
        // No spaces anywhere in a text of any length: word tokens would be the
        // whole text, so every change would highlight all of it.
        if (whitespace == 0 && sampled > 24) return Granularity.GRAPHEME
        return Granularity.WORD
    }

    /** Whether a code point belongs to a script written without word spaces. */
    private fun isSpaceless(code: Int): Boolean = when (Character.UnicodeScript.of(code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.TIBETAN,
        Character.UnicodeScript.JAVANESE,
        -> true
        else -> false
    }

    // ---- matching ----

    /**
     * Myers' greedy shortest-edit-script search. Returns the edit script in
     * order, or null once it has spent [MAX_EDIT_DISTANCE] edits without
     * finding the end.
     *
     * Chosen over a longest-common-subsequence table because its cost is
     * O(tokens × edits) rather than O(tokens²): the case this exists for is a
     * fix-grammar run, where the two texts are nearly the same and the search
     * finishes in a handful of edits.
     */
    private fun myers(a: List<String>, b: List<String>): List<Op>? {
        val n = a.size
        val m = b.size
        if (n == 0) return List(m) { Op.ADD }
        if (m == 0) return List(n) { Op.DELETE }

        val maxD = minOf(n + m, MAX_EDIT_DISTANCE)
        // Offset by maxD + 1 so that reading v[k + 1] at k == maxD stays inside
        // the array, which is the off-by-one this algorithm is famous for.
        val offset = maxD + 1
        val v = IntArray(2 * maxD + 3)
        val trace = ArrayList<IntArray>(maxD + 1)

        for (d in 0..maxD) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                val down = k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])
                var x = if (down) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) return backtrack(trace, offset, n, m)
                k += 2
            }
        }
        return null
    }

    /** Replays [trace] backwards into the edit script that produced it. */
    private fun backtrack(trace: List<IntArray>, offset: Int, n: Int, m: Int): List<Op> {
        val reversed = ArrayList<Op>()
        var x = n
        var y = m
        for (d in trace.indices.reversed()) {
            val v = trace[d]
            val k = x - y
            val down = k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])
            val prevK = if (down) k + 1 else k - 1
            val prevX = v[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                reversed += Op.KEEP
                x--
                y--
            }
            if (d > 0) reversed += if (x == prevX) Op.ADD else Op.DELETE
            x = prevX
            y = prevY
        }
        reversed.reverse()
        return reversed
    }
}
