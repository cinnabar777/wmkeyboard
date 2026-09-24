package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec

/**
 * Physical adjacency on a keyboard layout, used to weight typo corrections:
 * substituting a key with one of its neighbours is a likely fat-finger slip,
 * substituting a distant key is probably a different word entirely.
 *
 * Neighbours are derived from the layout's letter rows: the keys either side in
 * the same row, plus the three keys straddling the same column in the rows above
 * and below (the phone grid is only half-key staggered, so i-1..i+1 covers every
 * touching key).
 */
class KeyProximity private constructor(rows: List<String>) {

    /**
     * Hand per key, by column against the widest row. Built once with
     * [neighbors] rather than on demand: the beam asks about it per
     * transposition edge, per candidate, per keystroke.
     */
    private val hands: Map<Char, Int> = buildMap {
        val width = rows.maxOfOrNull { it.length } ?: 0
        val split = (width + 1) / 2
        for (row in rows) {
            for ((i, key) in row.withIndex()) {
                // First writer wins, so a character on two rows keeps the hand
                // of the row it appears on first. Nothing shipped does this;
                // an imported layout might.
                putIfAbsent(key, if (i < split) LEFT else RIGHT)
            }
        }
    }

    private val neighbors: Map<Char, String> = buildMap {
        for ((r, row) in rows.withIndex()) {
            for ((i, key) in row.withIndex()) {
                val adjacent = StringBuilder()
                if (i > 0) adjacent.append(row[i - 1])
                if (i < row.length - 1) adjacent.append(row[i + 1])
                for (other in listOfNotNull(rows.getOrNull(r - 1), rows.getOrNull(r + 1))) {
                    for (j in (i - 1)..(i + 1)) {
                        other.getOrNull(j)?.let { adjacent.append(it) }
                    }
                }
                put(key, adjacent.toString())
            }
        }
    }

    // Fast ASCII tables for 0 allocations and O(1) bitwise operations.
    private val asciiAdjacency = LongArray(128 * 2).also { bits ->
        for ((key, adj) in neighbors) {
            val a = key.code
            if (a < 128) {
                for (ch in adj) {
                    val b = ch.code
                    if (b < 128) {
                        val idx = (a shl 1) or (b ushr 6)
                        bits[idx] = bits[idx] or (1L shl (b and 63))
                    }
                }
            }
        }
    }

    private val asciiHands = ByteArray(128) { UNKNOWN.toByte() }.also { table ->
        for ((key, hand) in hands) {
            val c = key.code
            if (c < 128) table[c] = hand.toByte()
        }
    }

    fun areAdjacent(a: Char, b: Char): Boolean {
        val ac = a.code
        val bc = b.code
        return if (ac < 128 && bc < 128) {
            val idx = (ac shl 1) or (bc ushr 6)
            (asciiAdjacency[idx] and (1L shl (bc and 63))) != 0L
        } else {
            neighbors[a]?.contains(b) == true
        }
    }

    /**
     * Which hand reaches [c]: [LEFT], [RIGHT], or [UNKNOWN] for a character
     * this layout does not draw.
     *
     * The split is by column against the widest row, not by position within
     * each row — the rows already share a column origin (that is what makes
     * the row-above/row-below straddle in [neighbors] meaningful), and a
     * bottom row of seven keys would otherwise put `b` on the right hand.
     * QWERTY, QWERTZ, AZERTY and Dvorak all come out as the split a touch
     * typist actually uses.
     */
    fun handOf(c: Char): Int {
        val code = c.code
        return if (code < 128) asciiHands[code].toInt() else hands[c] ?: UNKNOWN
    }

    /**
     * Whether [a] and [b] are typed by the same hand.
     *
     * False when either is unknown to the layout: a caller weighting a
     * two-key gesture then gets the answer it had before hands existed,
     * rather than a guess about a key this board does not have.
     */
    fun sameHand(a: Char, b: Char): Boolean {
        val ha = handOf(a)
        return ha != UNKNOWN && ha == handOf(b)
    }

    /**
     * Structural equality: two proximities derived from the same rows are the
     * same proximity. The IME rebuilds this object on every settings emission,
     * so identity comparisons silently treat every emission as a layout change
     * — value equality is what cache invalidation must key on.
     */
    override fun equals(other: Any?): Boolean =
        other is KeyProximity && other.neighbors == neighbors

    override fun hashCode(): Int = neighbors.hashCode()

    companion object {

        const val UNKNOWN = -1
        const val LEFT = 0
        const val RIGHT = 1

        /**
         * Adjacency for the grid [spec] actually draws.
         *
         * This used to be five hand-written tables of row strings, one per Latin
         * layout, which a custom layout could never appear in — it would have
         * silently borrowed QWERTY's neighbours and scored typos against keys
         * that were nowhere near the user's finger. Deriving them means a
         * rearranged grid gets correct proximity for free, and `KeyProximityTest`
         * pins the derivation against the old tables so this stayed a refactor.
         *
         * [LayoutSpec.proximityRows] overrides the derivation for the one thing a
         * grid cannot express: a staggered or split arrangement whose rows do not
         * share a column origin.
         */
        fun forLayout(spec: LayoutSpec, numberRow: Boolean = false): KeyProximity {
            val rows = spec.proximityRows ?: letterRows(spec)
            // The number row is injected at render time (it is a setting, not
            // part of any LayoutSpec), so it is stitched on here the same way:
            // digits become neighbours of the top letter row, which is what
            // prices a number-row slip ("as3" for "ase") as an adjacent
            // substitution instead of a distant one.
            return KeyProximity(if (numberRow) listOf(NUMBER_ROW) + rows else rows)
        }

        private const val NUMBER_ROW = "1234567890"

        /** The default a suggestion engine starts on, before a layout is known. */
        val QWERTY: KeyProximity = forLayout(BuiltInLayouts.QWERTY)

        /**
         * The letter grid projected to one string of committed characters per row.
         *
         * Only single-character text keys count, so shift, delete and the layer
         * switches drop out and the remaining keys keep their column positions —
         * which is what makes the row-above/row-below straddle meaningful.
         *
         * The row holding the spacebar is skipped entirely. It is punctuation and
         * layer keys rather than letters, and including it would hang a fourth
         * proximity row of "," and "." under the alphabet, making them neighbours
         * of whatever happened to sit above.
         *
         * Rows of one character or none are dropped: a lone character has no
         * in-row neighbour, and leaving it in would shift the column alignment of
         * every row below it. The filter is deliberately *any* single character
         * rather than `Char::isLetter` — Dvorak's top row is `'`, `,` and `.`
         * followed by seven letters, and dropping those three would move the rest
         * three columns left.
         */
        /**
         * The letters a thumb reaching for the space bar can land on instead:
         * the bottom letter row less its two leftmost keys, which on a phone
         * sit above the symbol and emoji keys rather than the bar. QWERTY,
         * QWERTZ and AZERTY all come out as the set the engine always assumed
         * for them; other layouts finally get their own.
         */
        fun spaceAdjacentKeys(spec: LayoutSpec): String {
            val bottom = letterRows(spec).lastOrNull() ?: return ""
            return if (bottom.length > SPACE_ROW_SKIP + 2) bottom.substring(SPACE_ROW_SKIP) else bottom
        }

        private const val SPACE_ROW_SKIP = 2

        private fun letterRows(spec: LayoutSpec): List<String> {
            val rows = spec.layer(LayoutLayer.LETTERS)?.rows ?: return emptyList()
            return rows
                .filterNot { row -> row.any { it.action == KeyAction.Space } }
                .map { row ->
                    buildString {
                        for (key in row) {
                            if (key.action != KeyAction.Text) continue
                            val text = key.output ?: key.label
                            text.singleOrNull()?.let(::append)
                        }
                    }
                }
                .filter { it.length > 1 }
        }
    }
}
