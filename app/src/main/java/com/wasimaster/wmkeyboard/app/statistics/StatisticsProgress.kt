package com.wasimaster.wmkeyboard.app.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.ChoiceControl
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.rememberGrowIn
import com.wasimaster.wmkeyboard.core.tools.TypingHeatmapMath
import com.wasimaster.wmkeyboard.core.tools.TypingProgress
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import java.text.NumberFormat
import kotlin.math.sqrt

/*
 * The playful half of the Statistics screen (issue #390): a level that climbs
 * with the words typed, SwiftKey-style achievements with a ring each, and the
 * heatmap of where the thumb lands. All of it is read off [TypingStats]; the
 * arithmetic lives in [TypingProgress] and [TypingHeatmapMath].
 */

/** The level, its name, and a bar to the next one. The words restate the bar. */
@Composable
internal fun LevelCard(level: TypingProgress.Level) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.statistics_level_number, level.number),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(titleRes(level.title)),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Icon(
                        Icons.Outlined.MilitaryTech,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                LinearProgressIndicator(
                    progress = { level.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clearAndSetSemantics {},
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(
                            R.string.statistics_level_xp,
                            numbers.format(level.xp),
                            numbers.format(level.next),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.statistics_level_next,
                            numbers.format(level.next - level.xp),
                            level.number + 1,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One row per achievement: what it counts, which step it is on, and the count
 * against the next target beside a ring. The ring is decoration — the numbers
 * beside it say the same thing — so screen readers skip it.
 */
@Composable
internal fun AchievementRows(states: List<TypingProgress.AchievementState>) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    SettingsGroup {
        for (state in states) {
            item {
                val a = state.achievement
                val step = if (state.finished) {
                    pluralStringResource(R.plurals.statistics_achievement_finished, state.stepCount, state.stepCount)
                } else {
                    stringResource(R.string.statistics_achievement_step, state.steps + 1, state.stepCount)
                }
                val count = stringResource(
                    if (a == TypingProgress.Achievement.GLIDE_DISTANCE) {
                        R.string.statistics_achievement_metres
                    } else {
                        R.string.statistics_achievement_count
                    },
                    numbers.format(state.value),
                    numbers.format(state.target),
                )
                WmRow(
                    title = stringResource(achievementTitle(a)),
                    subtitle = stringResource(achievementSubtitle(a)) + "\n" + step,
                    icon = achievementIcon(a),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                count,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AchievementRing(
                                state.fraction,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AchievementRing(fraction: Float, modifier: Modifier = Modifier) {
    val grown by rememberGrowIn(ready = true)
    CircularProgressIndicator(
        progress = { fraction * grown },
        modifier = modifier
            .size(RingSize)
            .clearAndSetSemantics {},
        strokeWidth = 4.dp,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/**
 * The tap heatmap: the board's letter keys, the heat laid over them, and the
 * most tapped letters named underneath — the words are the screen reader's
 * version of the picture, which is hidden from it. With taps on more than one
 * layout, chips pick the board.
 */
@Composable
internal fun HeatmapCard(maps: List<TypingStats.Heatmap>) {
    var chosen by rememberSaveable { mutableStateOf(maps.first().layoutId) }
    val shown = maps.take(MAX_BOARDS)
    val map = shown.firstOrNull { it.layoutId == chosen } ?: shown.first()
    val top = remember(map) { TypingHeatmapMath.topKeys(map, TOP_KEYS) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (shown.size > 1) {
                    ChoiceControl(
                        options = shown.map { it.layoutId to it.name.ifEmpty { it.layoutId } },
                        selected = map.layoutId,
                        label = stringResource(R.string.statistics_heatmap_layout_label),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) { chosen = it }
                }
                HeatmapBoard(map)
                Text(
                    pluralStringResource(
                        R.plurals.statistics_heatmap_taps,
                        map.taps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        numbers.format(map.taps),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (top.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.statistics_heatmap_top,
                            top.joinToString(", ") { it.first.uppercase() },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Draws [map]: key caps from the stored centres, heat from the tap cells, the
 * letters on top so the heat never hides them. Rows are drawn [ROW_ASPECT]
 * times as tall as a key is wide, the shape of a phone keyboard; the canvas
 * takes its height from the board, so a four-row layout and a six-row one
 * both fill the width without stretching.
 */
@Composable
private fun HeatmapBoard(map: TypingStats.Heatmap) {
    val bounds = remember(map) { HeatBounds.of(map) }
    val keyColor = MaterialTheme.colorScheme.surfaceVariant
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val cool = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val peak = remember(map) { map.cells.values.maxOrNull() ?: 0L }
    // Fades in once, the way the history bars grow.
    val grown by rememberGrowIn(ready = peak > 0L)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bounds.width / (bounds.height * ROW_ASPECT))
            .clearAndSetSemantics {},
    ) {
        val ux = size.width / bounds.width
        val uy = ux * ROW_ASPECT
        fun x(value: Float) = (value - bounds.minX) * ux
        fun y(value: Float) = (value - bounds.minY) * uy
        for (key in map.keys) {
            drawRoundRect(
                color = keyColor,
                topLeft = Offset(x(key.x - KEY_WIDTH / 2), y(key.y - KEY_HEIGHT / 2)),
                size = Size(KEY_WIDTH * ux, KEY_HEIGHT * uy),
                cornerRadius = CornerRadius(ux * KEY_CORNER),
            )
        }
        if (peak > 0L) {
            val radius = ux * HEAT_RADIUS
            for ((cell, count) in map.cells) {
                val (cx, cy) = TypingHeatmapMath.centreOf(cell)
                // Square root, so a key hit a tenth as often still shows.
                val heat = sqrt(count.toFloat() / peak)
                val centre = Offset(x(cx), y(cy))
                val color = lerp(cool, HotColor, heat)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = HEAT_ALPHA * heat * grown), Color.Transparent),
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                )
            }
        }
        for (key in map.keys) {
            val text = measurer.measure(key.label, labelStyle)
            drawText(
                text,
                topLeft = Offset(
                    x(key.x) - text.size.width / 2f,
                    y(key.y) - text.size.height / 2f,
                ),
            )
        }
    }
}

/** The stretch of board a heatmap covers: every key cap and every tapped cell. */
private class HeatBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY

    companion object {
        fun of(map: TypingStats.Heatmap): HeatBounds {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            fun take(x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
                minX = minOf(minX, x - halfWidth)
                maxX = maxOf(maxX, x + halfWidth)
                minY = minOf(minY, y - halfHeight)
                maxY = maxOf(maxY, y + halfHeight)
            }
            for (key in map.keys) take(key.x, key.y, 0.5f, 0.5f)
            val half = TypingHeatmapMath.CELL / 2
            for (cell in map.cells.keys) {
                val (x, y) = TypingHeatmapMath.centreOf(cell)
                take(x, y, half, half)
            }
            if (minX > maxX || minY > maxY) return HeatBounds(0f, 0f, EMPTY_WIDTH, EMPTY_ROWS)
            return HeatBounds(minX - PAD, minY - PAD, maxX + PAD, maxY + PAD)
        }
    }
}

private fun titleRes(title: TypingProgress.Title): Int = when (title) {
    TypingProgress.Title.BEGINNER -> R.string.statistics_level_title_beginner
    TypingProgress.Title.NOVICE -> R.string.statistics_level_title_novice
    TypingProgress.Title.APPRENTICE -> R.string.statistics_level_title_apprentice
    TypingProgress.Title.REGULAR -> R.string.statistics_level_title_regular
    TypingProgress.Title.SKILLED -> R.string.statistics_level_title_skilled
    TypingProgress.Title.ADEPT -> R.string.statistics_level_title_adept
    TypingProgress.Title.EXPERT -> R.string.statistics_level_title_expert
    TypingProgress.Title.MASTER -> R.string.statistics_level_title_master
    TypingProgress.Title.GRANDMASTER -> R.string.statistics_level_title_grandmaster
    TypingProgress.Title.LEGEND -> R.string.statistics_level_title_legend
}

private fun achievementTitle(a: TypingProgress.Achievement): Int = when (a) {
    TypingProgress.Achievement.WORDS_TYPED -> R.string.statistics_achievement_words_title
    TypingProgress.Achievement.KEYSTROKES_SAVED -> R.string.statistics_achievement_saved_title
    TypingProgress.Achievement.WORDS_PREDICTED -> R.string.statistics_achievement_predicted_title
    TypingProgress.Achievement.WORDS_COMPLETED -> R.string.statistics_achievement_completed_title
    TypingProgress.Achievement.GLIDE_WORDS -> R.string.statistics_achievement_glide_title
    TypingProgress.Achievement.GLIDE_DISTANCE -> R.string.statistics_achievement_distance_title
}

private fun achievementSubtitle(a: TypingProgress.Achievement): Int = when (a) {
    TypingProgress.Achievement.WORDS_TYPED -> R.string.statistics_achievement_words_subtitle
    TypingProgress.Achievement.KEYSTROKES_SAVED -> R.string.statistics_achievement_saved_subtitle
    TypingProgress.Achievement.WORDS_PREDICTED -> R.string.statistics_achievement_predicted_subtitle
    TypingProgress.Achievement.WORDS_COMPLETED -> R.string.statistics_achievement_completed_subtitle
    TypingProgress.Achievement.GLIDE_WORDS -> R.string.statistics_achievement_glide_subtitle
    TypingProgress.Achievement.GLIDE_DISTANCE -> R.string.statistics_achievement_distance_subtitle
}

private fun achievementIcon(a: TypingProgress.Achievement): ImageVector = when (a) {
    TypingProgress.Achievement.WORDS_TYPED -> Icons.Outlined.TextFields
    TypingProgress.Achievement.KEYSTROKES_SAVED -> Icons.Outlined.Keyboard
    TypingProgress.Achievement.WORDS_PREDICTED -> Icons.Outlined.AutoAwesome
    TypingProgress.Achievement.WORDS_COMPLETED -> Icons.AutoMirrored.Outlined.ShortText
    TypingProgress.Achievement.GLIDE_WORDS -> Icons.Outlined.Gesture
    TypingProgress.Achievement.GLIDE_DISTANCE -> Icons.Outlined.Straighten
}

private val RingSize = 32.dp

/** The hot end of the heat ramp; the cool end is the theme's primary. */
private val HotColor = Color(0xFFFF5722)

private const val ROW_ASPECT = TypingHeatmapMath.ROW_ASPECT
private const val KEY_WIDTH = 0.88f
private const val KEY_HEIGHT = 0.82f
private const val KEY_CORNER = 0.14f
private const val HEAT_RADIUS = 0.5f
private const val HEAT_ALPHA = 0.55f
private const val PAD = 0.1f
private const val EMPTY_WIDTH = 10f
private const val EMPTY_ROWS = 4f
private const val TOP_KEYS = 5
private const val MAX_BOARDS = 4
