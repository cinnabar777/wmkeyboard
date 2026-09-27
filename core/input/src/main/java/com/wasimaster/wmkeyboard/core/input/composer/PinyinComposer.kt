package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.ln

/**
 * Chinese Pinyin input. The roman buffer is toneless pinyin (or Double Pinyin
 * key codes), shown in the composing region, and the strip offers Hanzi/word
 * candidates from the pinyin→Hanzi pack ([isConversion] + [candidates]).
 *
 * The buffer is split into syllables so a multi-syllable reading like `nihao`
 * offers both the whole-phrase 你好 and the leading-syllable 你/尼/…, each
 * remembering how many *input* chars it consumed ([consumedFor]) so the service
 * commits that prefix and re-converts the tail. Three [CjkConfig] knobs feed in
 * at call time: Double Pinyin ([DoublePinyin]) changes how the buffer segments,
 * Jianpin ([Jianpin]) lets a bare initial stand in for a whole syllable, and
 * Fuzzy Pinyin ([PinyinFuzzy]) widens each syllable's lookup. With no dictionary
 * match the raw pinyin commits, so the buffer never traps the user.
 */
object PinyinComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin

    /** Reading space for learned picks: pinyin letters, whatever scheme typed them. */
    private const val NAMESPACE = "pinyin"

    private const val LIMIT = 12

    /**
     * How deep [consumedFor] ranks. The expanded grid shows far more than the
     * strip, and a candidate the user can tap but the ranking cannot find would
     * fall through to "consumed the whole buffer" and swallow their text.
     */
    private const val LOOKUP_LIMIT = 128

    /** Fuzzy variants offered per syllable, beyond the one actually typed. */
    private const val FUZZY_VARIANTS = 2

    /**
     * What a fuzzy syllable costs, per syllable that differs from what was typed.
     * An exact match therefore loses only to a fuzzy word about seven times more
     * probable, and a one-syllable slip outranks a three-syllable one.
     */
    private val FUZZY_PENALTY = ln(0.15)

    /**
     * What an abbreviated syllable costs. Most of the time this decides nothing
     * — a bare `h` has no exact reading, so every option pays the same — and the
     * real ordering comes from the decoder's word cost: a phrase covering N
     * units always beats N stitched single characters, and a unit typed in full
     * pays nothing at all, which is what puts exact > mixed > pure jianpin. It
     * only discriminates on `a`, `e` and `o`, which are both a syllable and an
     * initial: typed alone, the exact 啊 should lead the abbreviated 爱.
     */
    private val PARTIAL_PENALTY = ln(0.15)

    /**
     * Buffer length, in syllables, past which the lattice gives way to plain
     * prefix lookup. Nobody types two dozen syllables without committing, and the
     * bound is what keeps a pathological buffer off the frame budget.
     */
    private const val MAX_LATTICE_UNITS = 24

    /** The composing region shows the pinyin — translated from key codes in Double Pinyin. */
    override fun composeBuffer(buffer: String): String {
        val table = doublePinyinTable()
        return if (table != null) DoublePinyin.translate(buffer.lowercase(), table, PinyinSyllables.valid)
        else buffer.lowercase()
    }

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer.lowercase()).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int {
        val b = buffer.lowercase()
        return ranked(b).firstOrNull { it.text == chosen }?.consumed ?: b.length
    }

    override fun consumedForIndex(buffer: String, index: Int): Int {
        val b = buffer.lowercase()
        return ranked(b).getOrNull(index)?.consumed ?: b.length
    }

    override fun learnChoice(buffer: String, index: Int) {
        val cand = ranked(buffer.lowercase()).getOrNull(index) ?: return
        CjkLearning.learn(NAMESPACE, cand.reading, cand.text)
    }


    private fun doublePinyinTable(): DoublePinyin.Table? {
        val scheme = CjkConfig.doublePinyin
        return if (scheme == DoublePinyinScheme.OFF) null else DoublePinyin.tableFor(scheme)
    }

    /**
     * Jianpin's index while it applies: full pinyin with the switch on. Under
     * Double Pinyin a syllable is always two keys, so there is nothing to
     * abbreviate and the index is left out of the segmentation entirely.
     */
    private fun jianpin(): Jianpin.Index? =
        if (CjkConfig.jianpin && CjkConfig.doublePinyin == DoublePinyinScheme.OFF) Jianpin.index else null

    /**
     * Syllables of [buffer] with per-syllable input spans, in the active input
     * mode. With Jianpin on the inventory also holds every initial, so `hd`
     * splits into `h` and `d`; a full syllable is always longer than its own
     * initial, so the longest-first walk keeps `nihao` as `ni` + `hao`.
     */
    private fun segments(buffer: String): List<Seg> {
        val table = doublePinyinTable()
        if (table != null) return DoublePinyin.segments(buffer, table, PinyinSyllables.valid)
        val jianpin = jianpin()
        return if (jianpin != null && jianpin.units.isNotEmpty()) PinyinSyllables.segment(buffer, jianpin.units)
        else PinyinSyllables.segment(buffer)
    }

    /**
     * The lattice input for [segs]: one unit per syllable, each offering the
     * syllable as typed, then — with Fuzzy Pinyin on — a couple of confusable
     * spellings behind a penalty, then — with Jianpin on — every syllable an
     * abbreviated unit could stand for. A bare initial has no as-typed reading,
     * so its options are abbreviations alone; `a`, `e` and `o` are both.
     *
     * The variants are *options*, not readings. Building the cartesian product
     * of readings was what forced a cap, and the cap dropped real candidates;
     * the decoder instead prunes each variant against the dictionary before
     * extending it, so an impossible spelling costs one binary search.
     */
    private fun latticeInput(segs: List<Seg>): Lattice.Input {
        val n = segs.size
        val boundaries = IntArray(n + 1)
        for (i in 0 until n) boundaries[i + 1] = boundaries[i] + segs[i].inputLen
        val fuzzy = CjkConfig.fuzzyPinyin
        val fuzzyPairs = CjkConfig.fuzzyPinyinPairs
        val valid = PinyinSyllables.valid
        val jianpin = jianpin()
        val units = List(n) { i -> unitOptions(segs[i].syllable, valid, fuzzy, fuzzyPairs, jianpin) }
        // A typed digraph reads both ways: `sh` is s + h (上海) by segmentation,
        // and the initial sh… (是) by merging the two units. An apostrophe
        // between them is the user's own boundary, so it blocks the merge.
        val merged = Array(n) { i ->
            val digraph = segs[i].syllable + segs.getOrNull(i + 1)?.syllable
            if (jianpin == null || i + 1 >= n || segs[i + 1].inputLen != 1 || digraph !in Jianpin.DIGRAPHS) emptyArray()
            else jianpin.expansions[digraph].orEmpty().toTypedArray()
        }
        return Lattice.Input(
            boundaries,
            Array(n) { units[it].readings },
            Array(n) { units[it].costs },
            IntArray(n) { units[it].partialFrom },
            merged,
            Array(n) { DoubleArray(merged[it].size) { PARTIAL_PENALTY } },
        )
    }

    /** One unit's readings, what each costs, and where the abbreviations begin. */
    private class UnitOptions(val readings: Array<String>, val costs: DoubleArray, val partialFrom: Int)

    private fun unitOptions(
        typed: String,
        valid: Set<String>,
        fuzzy: Boolean,
        fuzzyPairs: Set<String>,
        jianpin: Jianpin.Index?,
    ): UnitOptions {
        // Without Jianpin every unit came out of the syllable inventory.
        val exact = jianpin == null || typed in valid
        val readings = ArrayList<String>(4)
        val costs = ArrayList<Double>(4)
        if (exact) { readings.add(typed); costs.add(0.0) }
        if (exact && fuzzy) {
            for (v in PinyinFuzzy.expand(typed, valid, fuzzyPairs).filter { it != typed }.take(FUZZY_VARIANTS)) {
                readings.add(v); costs.add(FUZZY_PENALTY)
            }
        }
        val partialFrom = readings.size
        if (jianpin != null) {
            for (s in jianpin.expansions[typed].orEmpty()) {
                if (s == typed) continue
                readings.add(s); costs.add(PARTIAL_PENALTY)
            }
        }
        return UnitOptions(readings.toTypedArray(), costs.toDoubleArray(), partialFrom)
    }

    /**
     * Candidates for [buffer], best first, each tagged with its consumed input
     * length. Falls back to the dictionary's own prefix matching when the buffer
     * has no segmentable syllable yet (a still-typing first syllable).
     *
     * Cached on the buffer, because the service ranks the same one twice per
     * commit — once to fill the strip and once to ask how much to delete.
     */
    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val dict = CjkDictionaries.pinyin
        val segs = segments(buffer)
        if (segs.isEmpty() || segs.size > MAX_LATTICE_UNITS) {
            // Nothing segments yet, or the buffer is longer than anyone types
            // before committing: fall back to plain prefix lookup, which cannot
            // offer a prefix commit but always answers.
            return dict.candidates(buffer, LOOKUP_LIMIT)
                .map { Cand(HanVariant.toTraditional(it), buffer.length, buffer) }
        }
        val decoded = Lattice.decode(latticeInput(segs), dict, CjkDictionaries.ngrams, Lattice.Opts(limit = LOOKUP_LIMIT))
            .map { Cand(HanVariant.toTraditional(it.text), it.consumed, it.reading) }
            // Converting to Traditional can merge two Simplified words onto one
            // form, so de-duplicate after the conversion rather than before it.
            .distinctBy { it.text }
        return CjkLearning.rank(NAMESPACE, decoded, { it.text }, { it.reading })
    }

    /** A candidate word, the input chars (keys) it covers, and the reading behind it. */
    private data class Cand(val text: String, val consumed: Int, val reading: String)

    private val cache = RankCache<List<Cand>>()
}
