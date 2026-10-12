// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import kotlin.math.ln
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measurement, not a gate: what the sandbox idea is actually worth, and which
 * shape of it to ship.
 *
 * `GlideSandboxEvalTest` establishes the premise — a stroke decoded against
 * only the words this user writes is read far better *when the word is one of
 * them* — and shows that no gate on the decoder's shape cost can tell that case
 * from its opposite. This asks the two questions that follow.
 *
 *  - **Is a second decode needed at all?** The sandbox wins because the
 *    dictionary's competitors are absent. Weighting the user tier harder
 *    approximates that with one decode instead of two, and the user tier
 *    already carries a weight. [userWeightSweep] asks how much of the gain a
 *    bigger one buys.
 *  - **When does dropping the dictionary pay?** The overall number for
 *    `LEARNED_ONLY` is dominated by how much of the user's writing the lexicon
 *    covers, which is a fact about the user rather than the decoder.
 *    [coverageSweep] varies it and finds the crossover.
 *
 * Skipped unless `WM_GLIDE_SANDBOX_SWEEP` is set, for the same reason
 * `GlideTuningSweepTest` is: minutes of work, and nothing here regresses. Set
 * it when re-deciding the sandbox policy:
 *
 * ```
 * WM_GLIDE_SANDBOX_SWEEP=1 ./gradlew :app:testFullIntlDebugUnitTest --tests "*GlideSandboxSweepTest"
 * ```
 */
class GlideSandboxSweepTest {

    private companion object {
        const val SEED = 42L
        const val CASES_PER_LEVEL = 400
        const val RANK_DEPTH = 8
        val LEVELS = listOf(SwipeCorpus.Noise.TYPICAL, SwipeCorpus.Noise.SLOPPY)

        /** The shipped `SuggestionEngine.USER_WORD_WEIGHT`. */
        const val SHIPPED_USER_WEIGHT = 500.0
    }

    private val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
    private val keys = GlideKeyMap.of(grid.keyCenters(SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH)
    private val beam = GlideBeam()
    private val workspace = GlideWorkspace()

    private fun load(name: String): List<Pair<String, Int>> {
        val candidates = listOf(File("dictionaries-src/$name"), File("app/dictionaries-src/$name"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("$name not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    private fun decode(
        path: List<GesturePoint>,
        sources: List<FuzzyBeamSearch.WalkSource>,
    ): List<GlideBeam.Candidate> =
        beam.decode(path, keys, SwipeCorpus.KEY_WIDTH, sources, workspace, RANK_DEPTH)

    private fun lexiconTrie(entries: List<Pair<String, Int>>) =
        Trie().apply { entries.forEach { (w, f) -> insert(w, 1 + f / 500) } }

    /** One case, held so every arm of a sweep is scored on identical strokes. */
    private class Case(val want: String, val path: List<GesturePoint>, val known: Boolean)

    private fun cases(entries: List<Pair<String, Int>>, known: Set<String>): List<Case> {
        val out = ArrayList<Case>()
        for (level in LEVELS) {
            val corpus = SwipeCorpus(SEED, grid = grid)
            for (case in corpus.generate(entries, level, CASES_PER_LEVEL)) {
                val want = case.intended.lowercase(Locale.ROOT)
                out.add(Case(want, case.path, want in known))
            }
        }
        return out
    }

    private class Score(var hits: Int = 0, var inHits: Int = 0, var outHits: Int = 0)

    private fun score(cases: List<Case>, sources: List<FuzzyBeamSearch.WalkSource>): Score {
        val s = Score()
        for (case in cases) {
            val hit = decode(case.path, sources).firstOrNull()
                ?.word?.equals(case.want, ignoreCase = true) == true
            if (!hit) continue
            s.hits++
            if (case.known) s.inHits++ else s.outHits++
        }
        return s
    }

    /**
     * Does weighting the user tier harder buy the sandbox's gain without the
     * sandbox? One decode over every source, the user tier's weight swept.
     */
    @Test
    fun userWeightSweep() {
        assumeTrue("set WM_GLIDE_SANDBOX_SWEEP to run this measurement", !System.getenv("WM_GLIDE_SANDBOX_SWEEP").isNullOrBlank())
        val english = load("en.txt")
        val lexiconWords = english.take(2_000)
        val known = lexiconWords.mapTo(HashSet()) { it.first.lowercase(Locale.ROOT) }
        val cases = cases(english, known)
        val inTotal = cases.count { it.known }
        val outTotal = cases.size - inTotal

        val dictionary = Trie().apply { english.forEach { (w, f) -> insert(w, f) } }
        val lexicon = lexiconTrie(lexiconWords)

        val out = StringBuilder("user-tier weight sweep (one decode, all sources)\n")
        out.append("lexicon=2000, in-lexicon ${pct(inTotal / cases.size.toDouble())}\n")
        out.append("  weight       top1     in-lex   out-lex\n")
        for (weight in listOf(500.0, 2_000.0, 10_000.0, 100_000.0, 1_000_000.0, 1e12)) {
            val sources = dictionary.walkers().map {
                FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
            } + lexicon.walkers().map {
                FuzzyBeamSearch.WalkSource(it, ln(weight), FuzzyBeamSearch.Tier.USER)
            }
            val s = score(cases, sources)
            out.append(
                "  ${String.format(Locale.ROOT, "%10.0f", weight)}" +
                    (if (weight == SHIPPED_USER_WEIGHT) "*" else " ") +
                    " ${pct(s.hits / cases.size.toDouble())}  " +
                    "${pct(s.inHits / inTotal.toDouble())}  " +
                    "${pct(s.outHits / outTotal.toDouble())}\n"
            )
        }
        // The sandbox arm, for the same strokes: the ceiling a weight is
        // chasing.
        val only = lexicon.walkers().map {
            FuzzyBeamSearch.WalkSource(it, ln(SHIPPED_USER_WEIGHT), FuzzyBeamSearch.Tier.USER)
        }
        val s = score(cases, only)
        out.append(
            "  LEARNED_ONLY ${pct(s.hits / cases.size.toDouble())}  " +
                "${pct(s.inHits / inTotal.toDouble())}  " +
                "${pct(s.outHits / outTotal.toDouble())}\n"
        )
        emit("sandbox-userweight.txt", out.toString())
    }

    /**
     * How much of a user's writing the lexicon must cover before dropping the
     * dictionary is a win overall, rather than only on the words it holds.
     */
    @Test
    fun coverageSweep() {
        assumeTrue("set WM_GLIDE_SANDBOX_SWEEP to run this measurement", !System.getenv("WM_GLIDE_SANDBOX_SWEEP").isNullOrBlank())
        val english = load("en.txt")
        val dictionary = Trie().apply { english.forEach { (w, f) -> insert(w, f) } }
        val dictSources = dictionary.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }

        val out = StringBuilder("lexicon coverage sweep\n")
        out.append("  words   coverage   OFF      ONLY     ONLY in-lex  ONLY out-lex\n")
        for (size in listOf(500, 1_000, 2_000, 4_000, 8_000, 16_000)) {
            val lexiconWords = english.take(size)
            val known = lexiconWords.mapTo(HashSet()) { it.first.lowercase(Locale.ROOT) }
            val cases = cases(english, known)
            val inTotal = cases.count { it.known }.coerceAtLeast(1)
            val outTotal = (cases.size - inTotal).coerceAtLeast(1)
            val lexicon = lexiconTrie(lexiconWords)
            val userSources = lexicon.walkers().map {
                FuzzyBeamSearch.WalkSource(it, ln(SHIPPED_USER_WEIGHT), FuzzyBeamSearch.Tier.USER)
            }
            val off = score(cases, dictSources + userSources)
            val only = score(cases, userSources)
            out.append(
                "  ${String.format(Locale.ROOT, "%6d", size)}  " +
                    "${pct(inTotal / cases.size.toDouble())}   " +
                    "${pct(off.hits / cases.size.toDouble())}  " +
                    "${pct(only.hits / cases.size.toDouble())}  " +
                    "${pct(only.inHits / inTotal.toDouble())}       " +
                    "${pct(only.outHits / outTotal.toDouble())}\n"
            )
        }
        emit("sandbox-coverage.txt", out.toString())
    }

    /**
     * The model the other two get wrong, and the only one the sandbox's own
     * argument is about.
     *
     * [coverageSweep] builds the lexicon as a frequency *prefix* of the
     * dictionary and then makes the synthetic user write words that are not in
     * it. That is incoherent as a person: the lexicon is supposed to be a
     * record of what this user writes, so a word they write is a word it holds.
     * It also flatters the sandbox, because a prefix of the frequency list is
     * exactly the vocabulary a small-vocabulary decoder is best at.
     *
     * Here the user has a vocabulary V — a frequency-weighted *sample*, so it
     * has holes the way a real one does — their lexicon is V, and their strokes
     * are for words in V except for a rate `r` of genuinely new ones. Both
     * policies are scored on V-words and on non-V-words separately, which is
     * enough to price every `r` at once:
     *
     *     top1(r) = (1 - r)·accuracy_in + r·accuracy_out
     *
     * and `LEARNED_ONLY`, whose accuracy_out is zero by construction, wins
     * exactly while
     *
     *     r < (ONLY_in - OFF_in) / (OFF_out + ONLY_in - OFF_in)
     *
     * which is the number this prints. It is the whole feature in one figure:
     * the share of a user's swipes that may be for words their keyboard has
     * not learned before dropping the dictionary stops paying.
     */
    @Test
    fun settledUserSweep() {
        assumeTrue("set WM_GLIDE_SANDBOX_SWEEP to run this measurement", !System.getenv("WM_GLIDE_SANDBOX_SWEEP").isNullOrBlank())
        val english = load("en.txt")
        val vocabulary = sampleVocabulary(english, 2_000)
        val known = vocabulary.mapTo(HashSet()) { it.first.lowercase(Locale.ROOT) }
        val outside = english.filter { it.first.lowercase(Locale.ROOT) !in known }

        val lexicon = lexiconTrie(vocabulary)
        val userSources = lexicon.walkers().map {
            FuzzyBeamSearch.WalkSource(it, ln(SHIPPED_USER_WEIGHT), FuzzyBeamSearch.Tier.USER)
        }
        val dictionary = Trie().apply { english.forEach { (w, f) -> insert(w, f) } }
        val fullSources = dictionary.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        } + userSources

        // Strokes for words the user writes, and strokes for words they have
        // not written before. Scored apart, mixed arithmetically.
        val inCases = cases(vocabulary, known)
        val outCases = cases(outside, known)

        val offIn = score(inCases, fullSources).hits / inCases.size.toDouble()
        val offOut = score(outCases, fullSources).hits / outCases.size.toDouble()
        val onlyIn = score(inCases, userSources).hits / inCases.size.toDouble()
        val onlyOut = score(outCases, userSources).hits / outCases.size.toDouble()

        val out = StringBuilder("settled user: vocabulary is a 2000-word sample, lexicon = vocabulary\n")
        out.append("  policy        on V-words  on new words\n")
        out.append("  OFF           ${pct(offIn)}      ${pct(offOut)}\n")
        out.append("  LEARNED_ONLY  ${pct(onlyIn)}      ${pct(onlyOut)}\n\n")
        out.append("  new-word rate   OFF      LEARNED_ONLY\n")
        for (r in listOf(0.0, 0.01, 0.02, 0.03, 0.05, 0.10, 0.20)) {
            out.append(
                "  ${pct(r)}         ${pct((1 - r) * offIn + r * offOut)}  " +
                    "${pct((1 - r) * onlyIn + r * onlyOut)}\n"
            )
        }
        val edge = onlyIn - offIn
        val crossover = if (edge <= 0) 0.0 else edge / (offOut - onlyOut + edge)
        out.append("\n  LEARNED_ONLY wins while new-word rate < ${pct(crossover)}\n")
        emit("sandbox-settled.txt", out.toString())
    }

    /**
     * A vocabulary with holes: [size] distinct words drawn by frequency, so it
     * looks like a person's rather than like the top of the list.
     */
    private fun sampleVocabulary(
        entries: List<Pair<String, Int>>,
        size: Int,
    ): List<Pair<String, Int>> {
        val random = kotlin.random.Random(SEED)
        val picked = LinkedHashMap<String, Int>()
        val total = entries.sumOf { it.second.toLong() }
        var guard = 0
        while (picked.size < size && guard < size * 200) {
            guard++
            var t = (random.nextDouble() * total).toLong()
            for ((word, frequency) in entries) {
                t -= frequency
                if (t <= 0) {
                    picked[word] = frequency
                    break
                }
            }
        }
        return picked.toList()
    }

    private fun emit(name: String, body: String) {
        println(body)
        File("build/reports/gestureeval").mkdirs()
        File("build/reports/gestureeval/$name").writeText(body)
    }

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.4f", v)
}
