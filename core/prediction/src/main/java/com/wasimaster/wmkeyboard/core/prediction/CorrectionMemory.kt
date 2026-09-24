package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the user's own fixes have taught autocorrect.
 *
 * [CorrectionStats] remembers the corrections the keyboard made and the user
 * undid. This is its mirror image: the corrections the *user* made — a word
 * typed, left standing, then repaired by hand — which autocorrect either
 * declined or got wrong. Two things are kept from each:
 *
 * * **The pair.** `teh → the`, counted. A pair fixed twice is a fix the user
 *   wants made for them; one fixed once is worth asking about. The engine
 *   consults [fixFor] before it decides anything else about the word.
 * * **The slip.** The pair aligned letter by letter ([EditOps]) and each
 *   difference counted as a habit of this hand on this layout: `3` for `e`,
 *   `b` for space, a doubled letter missed. [habitsFor] turns those counts
 *   into the [EditHabits] the fuzzy walk prices with, so a habit learned on
 *   one word helps on every word it recurs in.
 *
 * Habits are kept per layout because a slip is a property of where the keys
 * sit under the hand, and only taught by [Kind.PAIR_AND_HABITS]: a real word
 * swapped for another real word ("form" for "from") is a choice, not a finger
 * landing badly, and must not move any cost.
 *
 * Everything decays. Pairs untouched for [EXPIRE_GENERATIONS] saves lose a
 * count; a layout's habit counters halve when they pass [HABIT_WINDOW], so a
 * hand that changes is followed. Storage follows the personal-store contract
 * (`learning/learned_corrections.json`): nullable file for a locked device,
 * dirty-flag save on dismissal, [reload] for edits the settings app made.
 */
class CorrectionMemory(private val storageFile: File?) {

    /** What a fix teaches: the pair alone, or the pair and the slip behind it. */
    enum class Kind { PAIR_ONLY, PAIR_AND_HABITS }

    /** A fix the user has made for one typed spelling, with how often. */
    class Taught(val fixed: String, val count: Int)

    /** One slip this hand makes, for the viewer. */
    enum class HabitKind { SUBSTITUTION, MISSING, STRAY, SWAP, SPACE_SLIP, MISSED_SPACE }

    class Habit(val kind: HabitKind, val from: Char?, val to: Char?, val count: Int)

    @Serializable
    private data class Stored(val fixed: String, val count: Int, val gen: Long)

    @Serializable
    private data class Snapshot(
        val pairs: Map<String, List<Stored>> = emptyMap(),
        /** layout id -> habit key -> count; see [habitKey]. */
        val habits: Map<String, Map<String, Int>> = emptyMap(),
        val generation: Long = 0L,
    )

    private val pairs = HashMap<String, ArrayList<Stored>>()
    private val habits = HashMap<String, HashMap<String, Int>>()
    private var generation = 0L
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)
    private val json = Json { ignoreUnknownKeys = true }

    /** Bumped on every change; the engine's caches key on it. */
    @Volatile
    var version: Int = 0
        private set

    init {
        load()
    }

    /**
     * The user turned [original] into [revised] by hand.
     *
     * Both are folded through [WordKey], so the pair is spelling-only; a
     * revised form may hold one space (a fat-fingered space bar put back).
     * Callers gate with [accepts] first; this only records.
     */
    @Synchronized
    fun teach(original: String, revised: String, layoutId: String, kind: Kind) {
        val typed = WordKey.of(original)
        val fixed = WordKey.of(revised)
        if (typed.isEmpty() || fixed.isEmpty() || typed == fixed) return
        val list = pairs.getOrPut(typed) { ArrayList(1) }
        val at = list.indexOfFirst { it.fixed == fixed }
        if (at >= 0) {
            val was = list[at]
            list[at] = Stored(fixed, (was.count + 1).coerceAtMost(MAX_COUNT), generation)
        } else {
            list.add(Stored(fixed, 1, generation))
        }
        list.sortByDescending { it.count }
        while (list.size > MAX_FIXES_PER_TYPED) list.removeAt(list.size - 1)
        if (pairs.size > MAX_PAIRS) {
            val oldest = pairs.entries.minByOrNull { e -> e.value.maxOf { it.gen } }?.key
            if (oldest != null) pairs.remove(oldest)
        }
        if (kind == Kind.PAIR_AND_HABITS && layoutId.isNotBlank()) learnHabits(typed, fixed, layoutId)
        changed()
    }

    /**
     * The user undid a taught fix: one count off, and the pair is gone once
     * it has none. The penalty in [CorrectionStats] stops it firing at once;
     * this is what keeps the viewer honest about it.
     */
    @Synchronized
    fun unteach(typed: String, fixed: String) {
        val key = WordKey.of(typed)
        val list = pairs[key] ?: return
        val at = list.indexOfFirst { it.fixed == WordKey.of(fixed) }
        if (at < 0) return
        val was = list[at]
        if (was.count <= 1) list.removeAt(at) else list[at] = Stored(was.fixed, was.count - 1, generation)
        if (list.isEmpty()) pairs.remove(key)
        changed()
    }

    /**
     * The fix the user has taught for [typed], or null.
     *
     * When the same spelling has been fixed different ways, only a fix that
     * clearly dominates the others ([DOMINANCE]) is named: a spelling the user
     * means two things by is not one autocorrect can make for them.
     */
    @Synchronized
    fun fixFor(typed: String): Taught? {
        val list = pairs[WordKey.of(typed)] ?: return null
        val top = list.firstOrNull() ?: return null
        val runnerUp = list.getOrNull(1)
        if (runnerUp != null && top.count < runnerUp.count * DOMINANCE) return null
        return Taught(top.fixed, top.count)
    }

    /** Forgets everything taught about [typed]. */
    @Synchronized
    fun forget(typed: String) {
        if (pairs.remove(WordKey.of(typed)) != null) changed()
    }

    /** Every taught pair, most-used first, for the viewer. */
    @Synchronized
    fun pairs(): List<Pair<String, Taught>> =
        pairs.flatMap { (typed, list) -> list.map { typed to Taught(it.fixed, it.count) } }
            .sortedWith(compareByDescending<Pair<String, Taught>> { it.second.count }.thenBy { it.first })

    @Synchronized
    fun pairCount(): Int = pairs.values.sumOf { it.size }

    /**
     * The shrink model for [layoutId], or [EditHabits.NONE] until that layout
     * has taught [EditHabits.WARMUP] slips.
     */
    @Synchronized
    fun habitsFor(layoutId: String): EditHabits {
        val counts = habits[layoutId] ?: return EditHabits.NONE
        if (counts.values.sum() < EditHabits.WARMUP) return EditHabits.NONE
        val subs = HashMap<Int, Double>()
        val inserts = HashMap<Char, Double>()
        val deletes = HashMap<Char, Double>()
        val spaceSlips = HashMap<Char, Double>()
        for ((key, n) in counts) {
            val shrink = EditHabits.shrink(n)
            if (shrink <= 0.0) continue
            val colon = key.indexOf(':')
            if (colon < 0) continue
            val kind = key.substring(0, colon)
            val arg = key.substring(colon + 1)
            when (kind) {
                KEY_SUB -> if (arg.length == 2) subs[EditHabits.pack(arg[0], arg[1])] = shrink
                KEY_INS -> if (arg.length == 1) inserts[arg[0]] = shrink
                KEY_DEL -> if (arg.length == 1) deletes[arg[0]] = shrink
                KEY_SPACE -> if (arg.length == 1) spaceSlips[arg[0]] = shrink
            }
        }
        if (subs.isEmpty() && inserts.isEmpty() && deletes.isEmpty() && spaceSlips.isEmpty()) {
            return EditHabits.NONE
        }
        return EditHabits(subs, inserts, deletes, spaceSlips)
    }

    /** This hand's slips on [layoutId], most frequent first, for the viewer. */
    @Synchronized
    fun habitSummary(layoutId: String): List<Habit> {
        val counts = habits[layoutId] ?: return emptyList()
        return summarise(counts)
    }

    /** This hand's slips across every layout, summed, for a viewer with no layout in mind. */
    @Synchronized
    fun habitSummary(): List<Habit> {
        val merged = HashMap<String, Int>()
        for (counts in habits.values) {
            for ((key, n) in counts) merged[key] = (merged[key] ?: 0) + n
        }
        return summarise(merged)
    }

    private fun summarise(counts: Map<String, Int>): List<Habit> =
        counts.mapNotNull { (key, n) -> habitOf(key, n) }
            .sortedWith(compareByDescending<Habit> { it.count }.thenBy { it.kind.ordinal })

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            generation++
            expireStalePairs()
            dirty = false
            file.ticket() to Snapshot(
                pairs = pairs.mapValues { it.value.toList() },
                habits = habits.mapValues { it.value.toMap() },
                generation = generation,
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        pairs.clear()
        habits.clear()
        generation = 0L
        load()
        dirty = false
        version++
    }

    @Synchronized
    fun clear() {
        pairs.clear()
        habits.clear()
        version++
        // The delete is the write; stay dirty only if it failed, so the next
        // save overwrites the stale file with the empty snapshot.
        dirty = snapshotFile?.delete() == false
    }

    private fun changed() {
        dirty = true
        version++
    }

    private fun learnHabits(typed: String, fixed: String, layoutId: String) {
        val counts = habits.getOrPut(layoutId) { HashMap() }
        for (op in EditOps.align(typed, fixed)) {
            val key = habitKey(op) ?: continue
            counts[key] = ((counts[key] ?: 0) + 1).coerceAtMost(MAX_COUNT)
        }
        // Halving counters: an exponential window with no timestamps, the same
        // trick CorrectionStats plays with its fired/reverted pair.
        if (counts.values.sum() >= HABIT_WINDOW) {
            val it = counts.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val halved = e.value / 2
                if (halved <= 0) it.remove() else e.setValue(halved)
            }
        }
    }

    /** A pair nobody has fixed in [EXPIRE_GENERATIONS] saves loses a count. */
    private fun expireStalePairs() {
        val stale = pairs.keys.toList()
        for (typed in stale) {
            val list = pairs[typed] ?: continue
            val kept = ArrayList<Stored>(list.size)
            for (s in list) {
                when {
                    generation - s.gen <= EXPIRE_GENERATIONS -> kept.add(s)
                    s.count > 1 -> kept.add(Stored(s.fixed, s.count - 1, generation))
                }
            }
            if (kept.isEmpty()) pairs.remove(typed) else pairs[typed] = kept
        }
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            for ((typed, list) in snapshot.pairs) {
                val clean = list.filter { it.count > 0 && it.fixed.isNotEmpty() }
                if (clean.isNotEmpty()) pairs[typed] = ArrayList(clean.sortedByDescending { it.count })
            }
            for ((layout, counts) in snapshot.habits) {
                val clean = counts.filterValues { it > 0 }
                if (clean.isNotEmpty()) habits[layout] = HashMap(clean)
            }
            generation = snapshot.generation
        }
    }

    companion object {
        /**
         * Whether a fix of [original] into [revised] is one worth learning.
         *
         * The original has to be a word-sized single token; the revised form
         * a word the keyboard knows ([known]), or two known halves — a space
         * bar hit on the bottom row put back. The distance between them must
         * be a slip's worth, not a rewrite's, and the revised form must not
         * simply continue the original: "hel" becoming "hello" is a word
         * committed early, which teaches nothing about how it was spelled.
         * Case is folded first, so a capital put back is not a fix either —
         * the case memory owns that.
         */
        fun accepts(original: String, revised: String, known: (String) -> Boolean): Boolean {
            val o = WordKey.of(original.trim())
            val r = WordKey.of(revised.trim())
            if (o.length < MIN_TYPED_LENGTH || o.length > MAX_WORD_LENGTH || r.isEmpty()) return false
            if (o == r || o.any { it.isWhitespace() }) return false
            val parts = r.split(' ')
            if (parts.size > 2 || parts.any { it.length < MIN_HALF_LENGTH || !known(it) }) return false
            if (r.startsWith(o)) return false
            val limit = if (o.length >= LONG_WORD_LENGTH) MAX_EDITS_LONG else MAX_EDITS
            return EditOps.distance(o, r) <= limit
        }

        /** The habit a single alignment step records, or null for a match or
         * a step this model does not price. */
        internal fun habitKey(op: EditOps.Op): String? = when (op) {
            is EditOps.Op.Match -> null
            is EditOps.Op.Sub -> when {
                op.intended == ' ' -> "$KEY_SPACE:${op.typed}"
                op.typed == ' ' -> null
                else -> "$KEY_SUB:${op.typed}${op.intended}"
            }
            is EditOps.Op.Ins -> if (op.intended == ' ') KEY_NOSPACE else "$KEY_INS:${op.intended}"
            is EditOps.Op.Del -> if (op.typed == ' ') null else "$KEY_DEL:${op.typed}"
            is EditOps.Op.Swap -> "$KEY_SWAP:${op.first}${op.second}"
        }

        private fun habitOf(key: String, count: Int): Habit? {
            if (key == KEY_NOSPACE) return Habit(HabitKind.MISSED_SPACE, null, null, count)
            val colon = key.indexOf(':')
            if (colon < 0) return null
            val arg = key.substring(colon + 1)
            return when (key.substring(0, colon)) {
                KEY_SUB -> arg.takeIf { it.length == 2 }?.let { Habit(HabitKind.SUBSTITUTION, it[0], it[1], count) }
                KEY_INS -> arg.singleOrNull()?.let { Habit(HabitKind.MISSING, null, it, count) }
                KEY_DEL -> arg.singleOrNull()?.let { Habit(HabitKind.STRAY, it, null, count) }
                KEY_SWAP -> arg.takeIf { it.length == 2 }?.let { Habit(HabitKind.SWAP, it[0], it[1], count) }
                KEY_SPACE -> arg.singleOrNull()?.let { Habit(HabitKind.SPACE_SLIP, it, null, count) }
                else -> null
            }
        }

        private const val KEY_SUB = "sub"
        private const val KEY_INS = "ins"
        private const val KEY_DEL = "del"
        private const val KEY_SWAP = "swap"
        private const val KEY_SPACE = "space"
        private const val KEY_NOSPACE = "nospace"

        /** Fixes of the same spelling that make it a fix the user wants made. */
        const val APPLY_AT = 2

        /** ...when the spelling is itself a word ("form"): the bar is higher,
         * and the engine asks the bigram context as well. */
        const val APPLY_KNOWN_AT = 3

        /** A fix has to outnumber the next most common fix of the same
         * spelling by this factor before it is the fix. */
        const val DOMINANCE = 2

        const val MAX_FIXES_PER_TYPED = 3
        const val MAX_PAIRS = 1000
        const val MAX_COUNT = 100
        const val EXPIRE_GENERATIONS = 180L

        /** Habit counters halve past this many sightings on one layout. */
        const val HABIT_WINDOW = 400

        /** Autocorrect never touches shorter words; nor does this. */
        const val MIN_TYPED_LENGTH = 3
        const val MAX_WORD_LENGTH = 32
        const val MIN_HALF_LENGTH = 2
        const val MAX_EDITS = 2
        const val LONG_WORD_LENGTH = 8
        const val MAX_EDITS_LONG = 3
    }
}
