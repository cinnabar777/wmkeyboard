package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideToken
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Reproducible synthetic two-thumb words (issue #638): the [SwipeCorpus] of
 * [com.wasimaster.wmkeyboard.core.gesture.DuetBeam].
 *
 * A word is split the way two thumbs split it: each letter belongs to the
 * thumb on its half of the board, and a run of letters on one half is that
 * thumb's turn. A run of two or more keys is swiped ([SWIPE_SHARE] of the
 * time, drawn by [SwipeCorpus] with the level's noise) or tapped key by key;
 * a single letter is tapped. The thumbs take turns, and each turn starts up to
 * [maxOverlapMs] *before* the other thumb's turn ended — the overlap that
 * makes two-thumb typing fast and makes the order of letters something the
 * decoder has to work out rather than read off.
 *
 * Like the stroke corpus, every number here is a claim about hands, not a
 * measurement of them: how often a run is swiped, how far turns overlap, how
 * far a tap lands from its key. A gain measured against it is an upper bound
 * until a device says otherwise.
 */
class DuetCorpus(
    seed: Long = 42L,
    private val keyWidth: Float = SwipeCorpus.KEY_WIDTH,
    val grid: GlideGrid = GlideGrid.of(BuiltInLayouts.QWERTY),
    /** How far a turn may start before the other thumb's ended, in ms. */
    private val maxOverlapMs: Long = 120L,
) {

    data class Case(
        val intended: String,
        val tokens: List<GlideToken>,
        val noise: SwipeCorpus.Noise,
    )

    private val random = Random(seed)
    private val strokes = SwipeCorpus(seed + 1, keyWidth, grid)

    /** The board's midline in key widths: the letters' own centre, not the screen's. */
    private val midline: Float = run {
        val xs = grid.keyCenters(1f).map { it.x }
        (xs.min() + xs.max()) / 2f
    }

    /** [count] two-thumb cases at [noise], words sampled by frequency. */
    fun generate(entries: List<Pair<String, Int>>, noise: SwipeCorpus.Noise, count: Int): List<Case> {
        val usable = entries.filter { (word, _) -> word.length in MIN_WORD..MAX_WORD && grid.canSpell(word) }
        val total = usable.sumOf { it.second.coerceAtLeast(1).toLong() }
        val out = ArrayList<Case>(count)
        var guard = 0
        while (out.size < count && guard++ < count * GIVE_UP_FACTOR) {
            var pick = random.nextLong(total)
            val word = usable.first { (_, f) ->
                pick -= f.coerceAtLeast(1)
                pick < 0
            }.first
            val tokens = tokensFor(word, noise) ?: continue
            out.add(Case(word, tokens, noise))
        }
        return out
    }

    /**
     * [word] as two thumbs type it, or null when it comes out as one gesture
     * or taps alone — neither is a two-thumb word, and the decoder is never
     * asked to read one.
     */
    fun tokensFor(word: String, noise: SwipeCorpus.Noise): List<GlideToken>? {
        val runs = runsOf(word) ?: return null
        val tapSigma = TAP_SIGMA.getValue(noise)
        val tokens = ArrayList<GlideToken>()
        // Each thumb's own clock: when it is free again.
        val free = longArrayOf(0L, 0L)
        var lastEnd = START_MS
        for ((thumb, letters) in runs) {
            val overlap = if (tokens.isEmpty()) 0L else random.nextLong(maxOverlapMs + 1)
            var at = maxOf(lastEnd - overlap, free[thumb] + SAME_THUMB_GAP_MS, START_MS)
            val swiped = letters.toSet().size >= 2 && random.nextFloat() < SWIPE_SHARE
            val path = if (swiped) strokes.swipe(letters, noise.profile) else null
            if (path != null) {
                val shift = at - path.first().t
                tokens.add(GlideToken.Swipe(path.map { GesturePoint(it.x, it.y, it.t + shift) }))
                at += path.last().t - path.first().t
            } else {
                var previous: Char? = null
                for (ch in letters) {
                    // A doubled letter is sometimes one tap standing for both.
                    if (ch == previous && random.nextFloat() < ONE_TAP_DOUBLE_SHARE) continue
                    if (previous != null) at += TAP_GAP_MS + random.nextLong(TAP_GAP_JITTER_MS + 1)
                    val key = grid.centerOf(ch.code) ?: return null
                    tokens.add(
                        GlideToken.Tap(
                            (key.x + gaussian() * tapSigma) * keyWidth,
                            (key.y + gaussian() * tapSigma) * keyWidth,
                            at,
                        ),
                    )
                    previous = ch
                }
            }
            free[thumb] = at
            lastEnd = at
        }
        if (tokens.size < 2 || tokens.none { it is GlideToken.Swipe }) return null
        return tokens
    }

    /** The word cut into turns: (thumb, letters), thumb 0 on the left. */
    private fun runsOf(word: String): List<Pair<Int, String>>? {
        val out = ArrayList<Pair<Int, String>>()
        val current = StringBuilder()
        var thumb = -1
        for (ch in word) {
            val key = grid.centerOf(ch.code) ?: return null
            val side = if (key.x < midline) 0 else 1
            if (side != thumb && current.isNotEmpty()) {
                out.add(thumb to current.toString())
                current.setLength(0)
            }
            thumb = side
            current.append(ch)
        }
        if (current.isNotEmpty()) out.add(thumb to current.toString())
        return out
    }

    private fun gaussian(): Float {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }

    private companion object {
        const val MIN_WORD = 3
        const val MAX_WORD = 14
        const val START_MS = 10_000L
        const val SWIPE_SHARE = 0.7f
        const val ONE_TAP_DOUBLE_SHARE = 0.3f
        const val TAP_GAP_MS = 110L
        const val TAP_GAP_JITTER_MS = 60L
        const val SAME_THUMB_GAP_MS = 60L
        const val GIVE_UP_FACTOR = 50

        /** How far a tap lands from its key's centre, σ in key widths, per level. */
        val TAP_SIGMA = mapOf(
            SwipeCorpus.Noise.CLEAN to 0.10f,
            SwipeCorpus.Noise.LIGHT to 0.20f,
            SwipeCorpus.Noise.TYPICAL to 0.30f,
            SwipeCorpus.Noise.SLOPPY to 0.40f,
        )
    }
}
