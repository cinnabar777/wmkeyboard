package com.wasimaster.wmkeyboard.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.ime.ui.KeyVisual
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyFaceProbe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lays the real keyboard out for [preset], unseen, and reports its geometry
 * to [onGeometry] whenever it settles (issue #397).
 *
 * The board is the theme editor's own preview, handed a screen of the preset's
 * size and density through [LocalConfiguration] and [LocalDensity] — the same
 * two things the keyboard sizes itself from, so a landscape or tablet preset
 * picks up that screen's sizing overrides and the tablet grid exactly as the
 * keyboard would. Measuring the board rather than working its geometry out
 * again is what keeps the templates right for every layout, number row, key
 * height and spacing without a second copy of the layout rules.
 *
 * Takes no room and draws nothing: it is laid out so its keys report where
 * they are, and that is all.
 */
@Composable
fun ThemeAssetBoard(
    settings: KeyboardSettings,
    theme: ThemeSpec,
    preset: AssetPreset,
    onGeometry: (BoardGeometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = LocalConfiguration.current
    val baseDensity = LocalDensity.current
    val deviceNavPx = WindowInsets.navigationBars.getBottom(baseDensity)
    val config = remember(base, preset) {
        if (preset.usesOwnSettings) {
            base
        } else {
            Configuration(base).apply {
                screenWidthDp = preset.widthDp
                screenHeightDp = preset.heightDp
                smallestScreenWidthDp = min(preset.widthDp, preset.heightDp)
                densityDpi = preset.densityDpi
                orientation = if (preset.landscape) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
            }
        }
    }
    val density = remember(baseDensity, preset) {
        if (preset.usesOwnSettings) {
            baseDensity
        } else {
            Density(preset.densityDpi / DENSITY_BASELINE_DPI, baseDensity.fontScale)
        }
    }
    // A fixed screen is drawn with the shipped sizing: a theme shared with
    // other people meets their defaults, not this user's key height. The
    // themes stay, since the theme under edit is looked up among them.
    val boardSettings = remember(settings, preset) {
        if (preset.usesOwnSettings) settings else SettingsDefaults.copy(customThemes = settings.customThemes)
    }
    val navBleedPx = if (preset.usesOwnSettings) {
        deviceNavPx
    } else {
        (PRESET_NAV_BAR_DP * density.density).roundToInt()
    }
    val sandbox = remember(preset) { mutableStateOf(ThemePreviewSandbox()) }
    val probe = remember(preset) { BoardProbe() }
    // Held, so the static local below never sees a new value and never
    // recomposes the board for it.
    val onFace = remember(probe) { probe::face }
    val onBoard = remember(probe) { probe::board }
    val report by rememberUpdatedState(onGeometry)
    LaunchedEffect(probe, navBleedPx) {
        snapshotFlow { probe.tick.intValue }.collectLatest {
            // Every key reports on its own; wait for the pass to finish. An
            // unchanged board reports an equal value, which the caller's
            // state takes as no change at all.
            delay(SETTLE_MS)
            probe.measure(density.density, navBleedPx)?.let(report)
        }
    }
    CompositionLocalProvider(
        LocalConfiguration provides config,
        LocalDensity provides density,
        LocalKeyFaceProbe provides onFace,
    ) {
        ThemeKeyboardPreview(
            settings = boardSettings,
            theme = theme,
            sandbox = sandbox,
            miniature = true,
            onBoardPositioned = onBoard,
            // Laid out at the width on offer, then given no room at all and
            // drawn invisible: the keys still place and report, and nothing
            // on the screen above can be touched through it.
            modifier = modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(0, 0) { placeable.place(0, 0) }
                }
                .graphicsLayer { alpha = 0f },
        )
    }
}

/** Android's baseline density: 160 dpi is 1 px per dp. */
private const val DENSITY_BASELINE_DPI = 160f

/** A gesture navigation bar, the default on current phones. */
private const val PRESET_NAV_BAR_DP = 24f

/** How long the key reports have to be quiet before the board counts as laid out. */
private const val SETTLE_MS = 120L

/**
 * Collects what the keys and the board report. Positions are kept as live
 * coordinates and read only when measured, so a key reporting twice in one
 * pass costs a map write and nothing more.
 */
private class BoardProbe {
    private var board: LayoutCoordinates? = null
    private val faces = LinkedHashMap<LayoutCoordinates, KeyVisual>()

    /** Bumped on each report; the measuring loop waits on it. */
    val tick = mutableIntStateOf(0)

    fun face(visual: KeyVisual, coordinates: LayoutCoordinates) {
        faces[coordinates] = visual
        tick.intValue++
    }

    fun board(coordinates: LayoutCoordinates) {
        board = coordinates
        tick.intValue++
    }

    /**
     * The board in its own pixels. Both ends are inside the preview's scale,
     * so the boxes come out at the size the keyboard really draws them.
     */
    fun measure(density: Float, navBleedPx: Int): BoardGeometry? {
        val root = board?.takeIf { it.isAttached } ?: return null
        faces.keys.removeAll { !it.isAttached }
        val width = root.size.width
        val height = root.size.height
        if (width <= 0 || height <= 0) return null
        val keys = faces.mapNotNull { (coordinates, visual) ->
            val box = root.localBoundingBoxOf(coordinates, clipBounds = false)
            if (box.width <= 0f || box.height <= 0f || box.bottom <= 0f || box.top >= height) {
                null
            } else {
                KeyFaceBox(
                    label = visual.label,
                    face = assetFaceOf(visual.key.action),
                    left = box.left,
                    top = box.top,
                    width = box.width,
                    height = box.height,
                )
            }
        }.sortedWith(compareBy({ it.top }, { it.left }))
        if (keys.isEmpty()) return null
        return BoardGeometry(width, height, density, keys, navBleedPx)
    }
}

/** The words drawn on a template, resolved by the caller in the user's language. */
class TemplateLabels(
    val toolbar: String,
    val navBar: String,
    val labelArea: String,
)

/**
 * The board template: the background image's whole canvas at [board]'s
 * pixels, transparent, with every key's outline, the toolbar band and the
 * navigation-bar band marked. Meant as the top layer of a drawing, so the
 * lines are drawn twice (dark under light) to show on any picture.
 */
fun renderBoardTemplate(
    board: BoardGeometry,
    cornerPx: (KeyFaceBox) -> Float,
    labels: TemplateLabels,
): Bitmap {
    val bitmap = Bitmap.createBitmap(board.widthPx, max(1, board.backgroundHeightPx), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val stroke = max(1f, board.density)
    val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAND_FILL }
    val text = labelPaint(board.density * TEXT_SP)
    val keysTop = board.keysTopPx
    if (keysTop > stroke) {
        canvas.drawRect(0f, 0f, board.widthPx.toFloat(), keysTop, band)
        drawCentredText(canvas, labels.toolbar, board.widthPx / 2f, keysTop / 2f, text)
    }
    if (board.navBleedPx > 0) {
        val top = board.heightPx.toFloat()
        val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BAND_FILL
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        canvas.save()
        canvas.clipRect(0f, top, board.widthPx.toFloat(), board.backgroundHeightPx.toFloat())
        val step = board.navBleedPx.toFloat()
        var x = -step
        while (x < board.widthPx) {
            canvas.drawLine(x, board.backgroundHeightPx.toFloat(), x + step, top, hatch)
            x += step / 2f
        }
        canvas.restore()
        drawCentredText(canvas, labels.navBar, board.widthPx / 2f, top + board.navBleedPx / 2f, text)
    }
    val keyText = labelPaint(board.density * KEY_TEXT_SP).apply { alpha = KEY_LABEL_ALPHA }
    for (key in board.keys) {
        val rect = RectF(key.left, key.top, key.left + key.width, key.top + key.height)
        drawOutline(canvas, rect, cornerPx(key), stroke)
        if (key.label.isNotBlank() && key.label.length <= MAX_DRAWN_LABEL) {
            drawCentredText(canvas, key.label, rect.centerX(), rect.centerY(), keyText)
        }
    }
    return bitmap
}

/**
 * A single key's template at [w]×[h]: its outline, and a dashed box where the
 * label sits, so a picture keeps its busy parts out from under the letter.
 */
fun renderKeyTemplate(w: Int, h: Int, cornerPx: Float, labels: TemplateLabels): Bitmap {
    val bitmap = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val stroke = max(1f, min(w, h) / KEY_STROKE_DIVISOR)
    val inset = stroke
    drawOutline(canvas, RectF(inset, inset, w - inset, h - inset), cornerPx, stroke)
    val side = min(w, h) * LABEL_AREA_SHARE
    val area = RectF(w / 2f - side / 2f, h / 2f - side / 2f, w / 2f + side / 2f, h / 2f + side / 2f)
    val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = LINE_LIGHT
        pathEffect = DashPathEffect(floatArrayOf(stroke * 3, stroke * 3), 0f)
    }
    canvas.drawRect(area, dash)
    val text = labelPaint(side / LABEL_AREA_TEXT_DIVISOR)
    drawCentredText(canvas, labels.labelArea, area.centerX(), area.bottom + text.textSize, text)
    return bitmap
}

private fun drawOutline(canvas: Canvas, rect: RectF, corner: Float, stroke: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    paint.strokeWidth = stroke * 2.5f
    paint.color = LINE_DARK
    canvas.drawRoundRect(rect, corner, corner, paint)
    paint.strokeWidth = stroke
    paint.color = LINE_LIGHT
    canvas.drawRoundRect(rect, corner, corner, paint)
}

private fun labelPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = LINE_LIGHT
    textSize = size
    textAlign = Paint.Align.CENTER
    setShadowLayer(size / 6f, 0f, 0f, LINE_DARK)
}

private fun drawCentredText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
    canvas.drawText(text, x, y - (paint.descent() + paint.ascent()) / 2f, paint)
}

private const val LINE_LIGHT = 0xFFFFFFFF.toInt()
private const val LINE_DARK = 0x99000000.toInt()
private const val BAND_FILL = 0x33808080
private const val TEXT_SP = 12f
private const val KEY_TEXT_SP = 14f
private const val KEY_LABEL_ALPHA = 150
private const val MAX_DRAWN_LABEL = 4
private const val KEY_STROKE_DIVISOR = 64f
private const val LABEL_AREA_SHARE = 0.5f
private const val LABEL_AREA_TEXT_DIVISOR = 7f
