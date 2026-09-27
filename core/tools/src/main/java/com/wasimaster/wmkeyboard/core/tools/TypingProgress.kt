package com.wasimaster.wmkeyboard.core.tools

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The levels and achievements on the Statistics screen (issue #390), worked
 * out from [TypingStats.Totals] and nothing else.
 *
 * Nothing here is stored. A level is a function of the lifetime counts, so it
 * can never disagree with them: deleting the statistics resets it, a backup
 * restores it, and changing a ladder below re-reads everyone's history with
 * the new numbers rather than leaving badges earned under the old ones.
 */
object TypingProgress {

    /**
     * Something to work towards: a count, and the targets it climbs through.
     * Each target reached is a step. The last one is the finish, and the count
     * keeps going past it.
     */
    enum class Achievement(val targets: LongArray) {
        WORDS_TYPED(longArrayOf(500, 2_000, 10_000, 50_000, 100_000, 250_000, 500_000, 1_000_000)),
        KEYSTROKES_SAVED(
            longArrayOf(200, 1_000, 5_000, 20_000, 50_000, 100_000, 250_000, 500_000, 1_000_000),
        ),
        WORDS_PREDICTED(longArrayOf(200, 1_000, 5_000, 20_000, 50_000, 100_000)),
        WORDS_COMPLETED(longArrayOf(100, 500, 2_000, 10_000, 50_000, 100_000)),
        GLIDE_WORDS(longArrayOf(200, 1_000, 5_000, 20_000, 50_000, 100_000)),

        /** In whole metres; the last step is a marathon. */
        GLIDE_DISTANCE(longArrayOf(20, 100, 500, 1_000, 5_000, 10_000, 42_195)),
    }

    /**
     * Where one achievement stands. [target] is the next step's number, or the
     * last one's once they are all done; [steps] counts the ones reached.
     */
    data class AchievementState(
        val achievement: Achievement,
        val value: Long,
        val steps: Int,
        val target: Long,
    ) {
        val stepCount: Int get() = achievement.targets.size
        val finished: Boolean get() = steps >= stepCount

        /** How far along the ring is drawn: towards the next step, full when finished. */
        val fraction: Float
            get() = if (finished) 1f else (value.toFloat() / target).coerceIn(0f, 1f)
    }

    /**
     * The names that go with the levels, from the first level each one starts
     * at. Bands widen as the levels get further apart, so a name lasts about as
     * long in weeks of typing at every stage.
     */
    enum class Title(val fromLevel: Int) {
        BEGINNER(1), NOVICE(3), APPRENTICE(5), REGULAR(8), SKILLED(12),
        ADEPT(17), EXPERT(23), MASTER(30), GRANDMASTER(40), LEGEND(50),
    }

    /**
     * A level: its [number] and [title], the [xp] behind it, and the XP at
     * which this level began ([floor]) and the next one begins ([next]).
     */
    data class Level(
        val number: Int,
        val title: Title,
        val xp: Long,
        val floor: Long,
        val next: Long,
    ) {
        /** Progress through this level, for the bar. */
        val fraction: Float
            get() = ((xp - floor).toFloat() / (next - floor)).coerceIn(0f, 1f)
    }

    /** Level n+1 costs this much XP more than level n did. */
    const val LEVEL_STEP_XP = 500L

    /** What each achievement step is worth, on top of the words that earned it. */
    const val STEP_XP = 100L

    fun value(achievement: Achievement, totals: TypingStats.Totals): Long = when (achievement) {
        Achievement.WORDS_TYPED -> totals.words
        Achievement.KEYSTROKES_SAVED -> totals.keystrokesSaved
        Achievement.WORDS_PREDICTED -> totals.wordsPredicted
        Achievement.WORDS_COMPLETED -> totals.wordsCompleted
        Achievement.GLIDE_WORDS -> totals.glideWords
        Achievement.GLIDE_DISTANCE -> (totals.glideDistanceMm / MM_PER_METRE).toLong()
    }

    fun state(achievement: Achievement, totals: TypingStats.Totals): AchievementState {
        val value = value(achievement, totals).coerceAtLeast(0)
        val targets = achievement.targets
        val steps = targets.count { value >= it }
        return AchievementState(achievement, value, steps, targets[steps.coerceAtMost(targets.lastIndex)])
    }

    fun achievements(totals: TypingStats.Totals): List<AchievementState> =
        Achievement.entries.map { state(it, totals) }

    /**
     * Experience: a point for every word, a second for every word the keyboard
     * predicted, finished or read off a glide, and [STEP_XP] for each
     * achievement step. Typing earns; typing with the keyboard's help earns
     * faster, which is the point of counting any of it.
     */
    fun xp(totals: TypingStats.Totals): Long {
        val assisted = totals.wordsPredicted + totals.wordsCompleted + totals.glideWords
        val steps = achievements(totals).sumOf { it.steps }
        return totals.words.coerceAtLeast(0) + assisted.coerceAtLeast(0) + steps * STEP_XP
    }

    /** The XP at which [level] (1-based) begins: 0, 500, 1 500, 3 000, 5 000… */
    fun levelStart(level: Int): Long {
        val n = level.coerceAtLeast(1).toLong()
        return LEVEL_STEP_XP * (n - 1) * n / 2
    }

    /** The level [xp] reaches. Solved in closed form, then nudged for rounding. */
    fun levelOf(xp: Long): Int {
        if (xp <= 0L) return 1
        // levelStart(n) <= xp  <=>  n(n-1) <= 2xp/STEP
        val k = 2.0 * xp / LEVEL_STEP_XP
        var n = floor((1.0 + sqrt(1.0 + 4.0 * k)) / 2.0).toInt().coerceAtLeast(1)
        while (n > 1 && levelStart(n) > xp) n--
        while (levelStart(n + 1) <= xp) n++
        return n
    }

    fun titleOf(level: Int): Title = Title.entries.last { level >= it.fromLevel }

    fun level(totals: TypingStats.Totals): Level {
        val xp = xp(totals)
        val number = levelOf(xp)
        return Level(number, titleOf(number), xp, levelStart(number), levelStart(number + 1))
    }

    /** Whether there is anything at all to show a level or an achievement for. */
    fun hasProgress(totals: TypingStats.Totals): Boolean =
        totals.words > 0L || totals.glideDistanceMm > 0.0

    private const val MM_PER_METRE = 1_000.0
}

/**
 * The geometry of the tap heatmap: the grid taps are counted on, and the
 * questions the screen asks of it. Coordinates are key widths across and rows
 * down, the frame [TypingStats.onKeyTap] takes.
 */
object TypingHeatmapMath {

    /** A cell is a quarter of a key each way. */
    const val CELL = 0.25f

    /** Cells per packed row: 128 keys across, far wider than any board. */
    private const val STRIDE = 512

    /** How tall a row is drawn relative to a key's width. */
    const val ROW_ASPECT = 1.35f

    /** A tap further than this from every letter (in key widths) belongs to
     * no letter — the space bar, shift, the number row's own keys. */
    const val MAX_KEY_REACH = 0.75f

    fun cellOf(x: Float, y: Float): Int {
        val col = (x / CELL).toInt().coerceIn(0, STRIDE - 1)
        val row = (y / CELL).toInt().coerceIn(0, STRIDE - 1)
        return row * STRIDE + col
    }

    /** The centre of a [cellOf] cell, as (x, y). */
    fun centreOf(cell: Int): Pair<Float, Float> {
        val col = Math.floorMod(cell, STRIDE)
        val row = Math.floorDiv(cell, STRIDE)
        return (col + 0.5f) * CELL to (row + 0.5f) * CELL
    }

    /**
     * The distance from one row of letters to the next, in the units [ys] are
     * given in: the smallest clear gap between distinct rows. A board with one
     * row, or none, answers 1 so a caller can always divide by it.
     */
    fun rowPitch(ys: List<Float>): Float {
        val rows = ys.filter { it.isFinite() }.sorted()
        var pitch = Float.MAX_VALUE
        for (i in 1 until rows.size) {
            val gap = rows[i] - rows[i - 1]
            if (gap > MIN_ROW_GAP && gap < pitch) pitch = gap
        }
        return if (pitch == Float.MAX_VALUE) 1f else pitch
    }

    /**
     * The letters tapped most on [map], most first, each with its tap count:
     * every cell goes to the nearest letter, if one is close enough. Rows are
     * measured at their drawn height, so a tap between two rows goes to the
     * one it looks closer to.
     */
    fun topKeys(map: TypingStats.Heatmap, limit: Int): List<Pair<String, Long>> {
        if (map.keys.isEmpty() || limit <= 0) return emptyList()
        val byKey = HashMap<String, Long>()
        for ((cell, count) in map.cells) {
            val (x, y) = centreOf(cell)
            var best: TypingStats.HeatKey? = null
            var bestDistance = Float.MAX_VALUE
            for (key in map.keys) {
                val dx = x - key.x
                val dy = (y - key.y) * ROW_ASPECT
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = key
                }
            }
            if (best != null && bestDistance <= MAX_KEY_REACH * MAX_KEY_REACH) {
                byKey.merge(best.label, count, Long::plus)
            }
        }
        return byKey.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }
    }

    /** Rows closer than this are the same row, staggered. */
    private const val MIN_ROW_GAP = 0.3f
}
