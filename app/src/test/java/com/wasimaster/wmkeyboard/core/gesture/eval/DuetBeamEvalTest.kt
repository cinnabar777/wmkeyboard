// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.DuetBeam
import com.wasimaster.wmkeyboard.core.gesture.DuetWorkspace
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measurement, not yet a gate: two-thumb words ([DuetCorpus]) through
 * [DuetBeam] on the bundled English list, top-1 and top-3 per noise level,
 * with decode latency.
 *
 * Skipped unless `WM_DUET_EVAL` is set, until it has been run once and its
 * numbers written down as floors the way [GestureEvalBaseline] holds the
 * stroke decoder's — a floor guessed before the first measurement would be a
 * number someone liked. An environment variable rather than `@Ignore`, which
 * JUnit 4 honours even for a test picked by name (issue #640):
 *
 * ```
 * WM_DUET_EVAL=1 ./gradlew :app:testFullIntlDebugUnitTest --tests "*DuetBeamEvalTest"
 * ```
 */
class DuetBeamEvalTest {

    private companion object {
        const val SEED = 42L
        const val CASES_PER_LEVEL = 300
        const val LIMIT = 8
        const val NANOS_PER_MS = 1_000_000.0
    }

    private fun realEntries(): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .firstOrNull { it.exists() }
            ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    @Test
    fun measure() {
        assumeTrue("set WM_DUET_EVAL to run this measurement", !System.getenv("WM_DUET_EVAL").isNullOrBlank())
        val entries = realEntries()
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
        val keys = GlideKeyMap.of(SwipeCorpus.keyCenters(), SwipeCorpus.KEY_WIDTH)
        val beam = DuetBeam()
        val ws = DuetWorkspace()
        println("DuetBeam on en/qwerty, $CASES_PER_LEVEL two-thumb words a level")
        for (noise in SwipeCorpus.Noise.entries) {
            val cases = DuetCorpus(SEED).generate(entries, noise, CASES_PER_LEVEL)
            var top1 = 0
            var top3 = 0
            var empty = 0
            val times = ArrayList<Double>(cases.size)
            for (case in cases) {
                val started = System.nanoTime()
                val words = beam.decode(case.tokens, keys, SwipeCorpus.KEY_WIDTH, sources, ws, LIMIT)
                    .map { it.word }
                times.add((System.nanoTime() - started) / NANOS_PER_MS)
                if (words.isEmpty()) empty++
                if (words.firstOrNull() == case.intended) top1++
                if (case.intended in words.take(3)) top3++
            }
            times.sort()
            val n = cases.size.toDouble()
            println(
                String.format(
                    Locale.ROOT,
                    "  %-8s top1 %.4f  top3 %.4f  empty %.3f  p50 %.2f ms  p95 %.2f ms",
                    noise.name, top1 / n, top3 / n, empty / n,
                    times[times.size / 2], times[(times.size * 95) / 100],
                ),
            )
        }
    }
}
