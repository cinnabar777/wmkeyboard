package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Immutable
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.theme.KeyShapeKind
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.popupOnKeyOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The screens the theme image guide can size a theme's pictures for (issue
 * #397). [CURRENT] is this device with the user's own layout and sizing; the
 * others are fixed screens with the shipped defaults, which is what a theme
 * meant for other people should be drawn against.
 *
 * The fixed screens are chosen so their pixels come out round: 360 dp at 3x is
 * the common 1080-pixel-wide phone, and 800 dp at 2x a 1600-pixel tablet.
 */
enum class AssetPreset(
    val widthDp: Int,
    val heightDp: Int,
    val densityDpi: Int,
) {
    CURRENT(0, 0, 0),
    PHONE(360, 780, 480),
    PHONE_LANDSCAPE(780, 360, 480),
    TABLET(800, 1280, 320),
    ;

    val landscape: Boolean get() = widthDp > heightDp

    /** A fixed screen draws the shipped defaults, not the user's own sizing. */
    val usesOwnSettings: Boolean get() = this == CURRENT
}

/** Which of the theme's key-texture slots a key face draws from. */
enum class AssetFace { LETTER, MODIFIER, ENTER, SPACE }

/** The texture slot a key action reads, as `KeyTextures.forKey` picks it. */
fun assetFaceOf(action: KeyAction): AssetFace = when (action) {
    KeyAction.Enter -> AssetFace.ENTER
    KeyAction.Space -> AssetFace.SPACE
    KeyAction.Text -> AssetFace.LETTER
    else -> AssetFace.MODIFIER
}

/** One key's visible face on the measured board, in the board's pixels. */
@Immutable
data class KeyFaceBox(
    val label: String,
    val face: AssetFace,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * The board as the keyboard lays it out on one screen: its size, every key's
 * face, and the band under the system navigation bar that the background runs
 * on into on Android 15 and later.
 */
@Immutable
data class BoardGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val keys: List<KeyFaceBox>,
    val navBleedPx: Int,
) {
    /** The top of the key area; everything above it is the toolbar and strip. */
    val keysTopPx: Float get() = keys.minOfOrNull { it.top } ?: 0f

    /** The whole picture a background image covers, bleed included. */
    val backgroundHeightPx: Int get() = heightPx + navBleedPx

    /**
     * The face most keys of [face] share, so one odd key (a wide shift, a
     * short number-row key) does not decide the template. Ties go to the
     * larger face; the space bar is simply the widest one.
     */
    fun typical(face: AssetFace): KeyFaceBox? {
        val candidates = keys.filter { it.face == face }
        if (candidates.isEmpty()) return null
        if (face == AssetFace.SPACE) return candidates.maxBy { it.width }
        return candidates
            .groupBy { it.width.roundToInt() to it.height.roundToInt() }
            .entries
            .maxWith(compareBy({ it.value.size }, { it.key.first * it.key.second }))
            .value
            .first()
    }
}

/** A picture a theme can carry, in the order the guide lists them. */
enum class AssetSlot {
    BACKGROUND,
    KEY,
    MODIFIER,
    ENTER,
    SPACE,
    PRESSED,
    POPUP,
    STICKER,
    EFFECT,
}

/**
 * One slot's sizes. [screenW]×[screenH] is what the slot covers on the chosen
 * screen; [templateW]×[templateH] the size to draw it at — the same shape,
 * scaled up to the most the keyboard keeps of it, so a picture drawn at that
 * size is sharp without being bigger than it will ever be shown.
 */
@Immutable
data class AssetSize(
    val slot: AssetSlot,
    val screenW: Int,
    val screenH: Int,
    val templateW: Int,
    val templateH: Int,
)

/**
 * The longest edges the keyboard decodes each picture to. They mirror
 * `KeyTextures`, `BoardDecals` and the effect importer; a template bigger than
 * these would only be scaled down again on the way in.
 */
internal const val ASSET_KEY_MAX_PX = 256
internal const val ASSET_SPACE_MAX_W = 1024
internal const val ASSET_SPACE_MAX_H = 320
internal const val ASSET_POPUP_MAX_W = 512
internal const val ASSET_POPUP_MAX_H = 256
internal const val ASSET_STICKER_MAX_PX = 512
internal const val ASSET_EFFECT_MAX_PX = 192

/** A sticker's default width, as a share of the key area's width. */
private const val STICKER_DEFAULT_SCALE = 0.25f

/** How much wider than its key an on-key popup draws, in dp. */
private const val POPUP_EXTRA_WIDTH_DP = 8f

/** Roughly how big a press-effect particle draws, in dp. */
private const val EFFECT_DRAWN_DP = 32f

/**
 * [w]×[h] scaled up to fill [maxW]×[maxH] with its shape kept, and never
 * scaled down: a slot already bigger than the keyboard keeps is drawn at its
 * own size.
 */
fun fitUp(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
    if (w <= 0 || h <= 0) return max(1, w) to max(1, h)
    val scale = max(1f, min(maxW / w.toFloat(), maxH / h.toFloat()))
    return (w * scale).roundToInt() to (h * scale).roundToInt()
}

/** The key-preview bubble's height the theme draws, as `specKbTheme` picks it. */
fun popupHeightDp(theme: ThemeSpec, settings: KeyboardSettings): Int {
    val onKey = popupOnKeyOrNull(theme.popupPlacement) ?: settings.popup.onKey
    val own = if (onKey) theme.popupHeightDp else theme.popupFloatingHeightDp
    return own ?: settings.popup.heightFor(onKey)
}

/**
 * The corner radius a key template is drawn with, in the board's pixels. Only
 * an outline to design inside of: the decorative shapes are drawn as the
 * nearest rounded rectangle.
 */
fun keyCornerPx(theme: ThemeSpec, settings: KeyboardSettings, density: Float, w: Float, h: Float): Float =
    when (theme.keyShape) {
        KeyShapeKind.SHARP, KeyShapeKind.NONE -> 0f
        KeyShapeKind.PILL, KeyShapeKind.CIRCLE -> min(w, h) / 2f
        else -> ((theme.keyCornerRadiusDp ?: settings.keyCornerRadiusDp) * density)
            .coerceAtMost(min(w, h) / 2f)
    }

/** Every slot's sizes on [board], for a theme whose bubble is [popupDp] tall. */
fun assetSizes(board: BoardGeometry, popupDp: Int): List<AssetSize> = buildList {
    fun add(slot: AssetSlot, w: Int, h: Int, maxW: Int, maxH: Int) {
        val (tw, th) = fitUp(w, h, maxW, maxH)
        add(AssetSize(slot, w, h, tw, th))
    }
    // The background is centre-cropped to the board, so its template is the
    // board itself, pixel for pixel.
    add(AssetSize(AssetSlot.BACKGROUND, board.widthPx, board.backgroundHeightPx, board.widthPx, board.backgroundHeightPx))
    val letter = board.typical(AssetFace.LETTER)
    fun faceSlot(slot: AssetSlot, box: KeyFaceBox?, maxW: Int, maxH: Int) {
        box ?: return
        add(slot, box.width.roundToInt(), box.height.roundToInt(), maxW, maxH)
    }
    faceSlot(AssetSlot.KEY, letter, ASSET_KEY_MAX_PX, ASSET_KEY_MAX_PX)
    faceSlot(AssetSlot.MODIFIER, board.typical(AssetFace.MODIFIER), ASSET_KEY_MAX_PX, ASSET_KEY_MAX_PX)
    faceSlot(AssetSlot.ENTER, board.typical(AssetFace.ENTER), ASSET_KEY_MAX_PX, ASSET_KEY_MAX_PX)
    faceSlot(AssetSlot.SPACE, board.typical(AssetFace.SPACE), ASSET_SPACE_MAX_W, ASSET_SPACE_MAX_H)
    faceSlot(AssetSlot.PRESSED, letter, ASSET_KEY_MAX_PX, ASSET_KEY_MAX_PX)
    if (letter != null) {
        val w = (letter.width + POPUP_EXTRA_WIDTH_DP * board.density).roundToInt()
        val h = (popupDp * board.density).roundToInt()
        add(AssetSlot.POPUP, w, h, ASSET_POPUP_MAX_W, ASSET_POPUP_MAX_H)
    }
    val keysWidth = if (board.keys.isEmpty()) {
        board.widthPx.toFloat()
    } else {
        board.keys.maxOf { it.left + it.width } - board.keys.minOf { it.left }
    }
    val sticker = (keysWidth * STICKER_DEFAULT_SCALE).roundToInt()
    add(AssetSlot.STICKER, sticker, sticker, ASSET_STICKER_MAX_PX, ASSET_STICKER_MAX_PX)
    val effect = (EFFECT_DRAWN_DP * board.density).roundToInt()
    add(AssetSlot.EFFECT, effect, effect, ASSET_EFFECT_MAX_PX, ASSET_EFFECT_MAX_PX)
}
