package com.wasimaster.wmkeyboard.core.prediction

/**
 * How much cheaper the fuzzy walk should price the slips this user actually
 * makes, learned from the typos they fixed by hand ([CorrectionMemory]).
 *
 * The walk's edit costs are one population's averages: a far substitution is
 * expensive because most people do not hit `3` for `e`, a stray letter costs
 * more than a doubled one because most strays are not habits. One user is not
 * a population. Someone whose thumb lands on the number row, or on `b` where
 * the space bar was, does it every day, and for them that edit is not far at
 * all. So each learned habit hands back a *shrink* — the fraction of the base
 * cost to take off — and the walk applies it where it prices that edit.
 *
 * Bounded and one-sided by design. A shrink never exceeds [MAX_SHRINK], the
 * walk floors the result at its cheapest tier, and nothing here ever makes an
 * edit *more* expensive: the model only ever admits more candidates, and the
 * confidence gate that decides whether any of them fires is untouched.
 *
 * Immutable snapshot with structural equality, so the engine's setter can
 * tell a rebuilt-but-identical model from a changed one and leave its walk
 * cache alone ([SuggestionEngine.editHabits]).
 */
class EditHabits internal constructor(
    private val subs: Map<Int, Double>,
    private val inserts: Map<Char, Double>,
    private val deletes: Map<Char, Double>,
    private val spaceSlips: Map<Char, Double>,
) {

    private val subKeys: IntArray = subs.keys.toIntArray().apply { sort() }
    private val subValues: DoubleArray = DoubleArray(subKeys.size) { subs[subKeys[it]] ?: 0.0 }

    private val insertKeys: CharArray = inserts.keys.toCharArray().apply { sort() }
    private val insertValues: DoubleArray = DoubleArray(insertKeys.size) { inserts[insertKeys[it]] ?: 0.0 }

    private val deleteKeys: CharArray = deletes.keys.toCharArray().apply { sort() }
    private val deleteValues: DoubleArray = DoubleArray(deleteKeys.size) { deletes[deleteKeys[it]] ?: 0.0 }

    private val spaceSlipKeys: CharArray = spaceSlips.keys.toCharArray().apply { sort() }
    private val spaceSlipValues: DoubleArray = DoubleArray(spaceSlipKeys.size) { spaceSlips[spaceSlipKeys[it]] ?: 0.0 }

    val isEmpty: Boolean
        get() = subKeys.isEmpty() && insertKeys.isEmpty() && deleteKeys.isEmpty() && spaceSlipKeys.isEmpty()

    /** Shrink for reading [typed] as a slip for [intended]; 0 when unknown. */
    fun substitution(typed: Char, intended: Char): Double {
        if (subKeys.isEmpty()) return 0.0
        val idx = subKeys.binarySearch(pack(typed, intended))
        return if (idx >= 0) subValues[idx] else 0.0
    }

    /** Shrink for a missing [intended] character. */
    fun insertion(intended: Char): Double {
        if (insertKeys.isEmpty()) return 0.0
        val idx = insertKeys.binarySearch(intended)
        return if (idx >= 0) insertValues[idx] else 0.0
    }

    /** Shrink for a stray [typed] character. */
    fun deletion(typed: Char): Double {
        if (deleteKeys.isEmpty()) return 0.0
        val idx = deleteKeys.binarySearch(typed)
        return if (idx >= 0) deleteValues[idx] else 0.0
    }

    /** Shrink for [typed] standing where a space was meant. */
    fun spaceSlip(typed: Char): Double {
        if (spaceSlipKeys.isEmpty()) return 0.0
        val idx = spaceSlipKeys.binarySearch(typed)
        return if (idx >= 0) spaceSlipValues[idx] else 0.0
    }

    override fun equals(other: Any?): Boolean =
        other is EditHabits && other.subs == subs && other.inserts == inserts &&
            other.deletes == deletes && other.spaceSlips == spaceSlips

    override fun hashCode(): Int {
        var h = subs.hashCode()
        h = 31 * h + inserts.hashCode()
        h = 31 * h + deletes.hashCode()
        h = 31 * h + spaceSlips.hashCode()
        return h
    }

    companion object {
        /** Nothing learned: every shrink is zero and the walk prices as shipped. */
        val NONE = EditHabits(emptyMap(), emptyMap(), emptyMap(), emptyMap())

        /** The most any habit may take off a base cost, however often it recurs. */
        const val MAX_SHRINK = 0.75

        /** Sightings at which a habit is worth half of [MAX_SHRINK]: two fixes
         * of the same slip say something, one says little. */
        const val PIVOT = 2.0

        /** Fixes a layout has to have taught before any habit moves a cost:
         * one evening's slips are not a hand. */
        const val WARMUP = 8

        /** The shrink [count] sightings earn, saturating towards [MAX_SHRINK]. */
        fun shrink(count: Int): Double =
            if (count <= 0) 0.0 else MAX_SHRINK * count / (count + PIVOT)

        internal fun pack(a: Char, b: Char): Int = (a.code shl 16) or b.code
    }
}
