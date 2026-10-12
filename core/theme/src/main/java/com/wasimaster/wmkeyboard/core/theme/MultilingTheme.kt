package com.wasimaster.wmkeyboard.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.wasimaster.wmkeyboard.core.util.MultilingExport
import kotlin.math.roundToInt

/**
 * Reading a Multiling O Keyboard theme.
 *
 * Like HeliBoard, Multiling O has no theme file. Its *Color* screen keeps
 * nineteen colours, and a theme is shared as the settings block holding them
 * (`OK_Settings_Begin` … `OK_Settings_End`), copied out of *Misc → Import |
 * Export* or out of a link to the community theme page. A full settings export
 * carries the same keys among everything else, so either one reads. See
 * [MultilingExport] for the envelope.
 *
 * **Written from the shape of the text and the names on Multiling's own
 * colour screen, not from its code.** The colours are `__V.co0` to
 * `__V.co18`, ARGB in hex, in the order the screen lists them:
 *
 * | | | | |
 * |---|---|---|---|
 * | 0 Background | 1 Key1 | 2 Text1 | 3 Key2 |
 * | 4 Text2 | 5 Highlight | 6 Key1 shadow | 7 Text1 shadow |
 * | 8 Key2 shadow | 9 Text2 shadow | 10 Popup | 11 Popup text |
 * | 12 Suggestion bar | 13 Suggestion bar text | 14 Sub-text1 | 15 Sub-text2 |
 * | 16 Space bar | 17 Space bar text | 18 Suggestion highlight | |
 *
 * Key1 is the letter keys and Key2 everything else: punctuation, shift,
 * delete, enter. Sub-text is the small character in a key's corner. A colour
 * of `0` (transparent black) is how the screen says "none", and for the
 * shadow, space bar and sub-text slots it means "the same as the key", so it
 * is read as nothing stated.
 *
 * Besides the colours, `__K.rx` is the key corner radius (the screen's slider
 * divided by a little over three, roughly 0.7 dp a unit on a phone), `__K.fs`
 * the label size in percent, and `__V.FB` bold labels.
 */
object MultilingTheme {

    /** What the import picker accepts: a theme travels as plain text. */
    val IMPORT_MIME_TYPES = arrayOf("text/plain", "application/octet-stream", "*/*")

    /**
     * Converts [text], from a file, the clipboard or a pasted link. Never
     * throws: a settings block with no colours in it is [MultilingThemeResult.NotATheme].
     */
    fun read(text: String): MultilingThemeResult {
        val settings = MultilingExport.settings(text) ?: return MultilingThemeResult.NotATheme
        val colours = (0 until SLOTS).associateWith { slot -> settings["$COLOUR_KEY$slot"]?.let(::parseColour) }
            .filterValues { it != null }
            .mapValues { it.value!! }
        if (colours.isEmpty()) return MultilingThemeResult.NotATheme
        return convert(settings, colours)
    }

    private fun convert(settings: Map<String, String>, colours: Map<Int, Long>): MultilingThemeResult {
        val dropped = linkedSetOf<MultilingUnsupported>()
        val used = linkedSetOf<Int>()
        /** The slot's colour when the file states one, "none" being no statement. */
        fun stated(slot: Int): Long? = colours[slot]?.takeIf { it != NONE }?.also { used += slot }
        /** The slot's colour as written, transparent included. */
        fun exact(slot: Int): Long? = colours[slot]?.also { used += slot }

        val board = exact(SLOT_BACKGROUND) ?: return MultilingThemeResult.NotATheme
        val key = exact(SLOT_KEY1) ?: board
        val modifier = exact(SLOT_KEY2) ?: key
        val night = Color(composite(board, OPAQUE_BLACK)).luminance() < DARK_THRESHOLD
        val keySurface = composite(key, board)
        val modifierSurface = composite(modifier, board)
        val keyText = stated(SLOT_TEXT1) ?: onColorFor(keySurface)
        val modifierText = stated(SLOT_TEXT2)
        val highlight = stated(SLOT_HIGHLIGHT)
        val suggestionHighlight = stated(SLOT_SUGGESTION_HIGHLIGHT)
        val keyShadow = stated(SLOT_KEY1_SHADOW) ?: stated(SLOT_KEY2_SHADOW)
        val spaceBackground = stated(SLOT_SPACE)
        val spaceText = stated(SLOT_SPACE_TEXT)

        if (stated(SLOT_TEXT1_SHADOW) != null || stated(SLOT_TEXT2_SHADOW) != null) {
            dropped += MultilingUnsupported.TEXT_SHADOWS
        }
        if (settings[WALLPAPER_KEY]?.toBooleanStrictOrNull() == true) dropped += MultilingUnsupported.WALLPAPER

        val theme = ThemeSpec(
            id = "",
            name = DEFAULT_NAME,
            dark = night,
            boardBackground = board,
            keyBackground = key,
            keyText = keyText,
            modifierKeyBackground = modifier,
            modifierKeyText = modifierText,
            // Multiling draws enter as one of the Key2 keys.
            enterKeyBackground = modifier,
            enterKeyText = modifierText ?: onColorFor(modifierSurface),
            pressedKeyBackground = highlight,
            hintText = stated(SLOT_SUBTEXT1) ?: stated(SLOT_SUBTEXT2),
            keyShadowColor = keyShadow,
            keyElevationDp = if (keyShadow != null) SHADOW_ELEVATION_DP else 0f,
            popupBackground = stated(SLOT_POPUP),
            popupText = stated(SLOT_POPUP_TEXT),
            suggestionBarBackground = exact(SLOT_SUGGESTION_BAR),
            suggestionText = stated(SLOT_SUGGESTION_TEXT),
            accent = suggestionHighlight ?: highlight ?: onColorFor(board),
            keyCornerRadiusDp = settings[RADIUS_KEY]?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
                ?.let { (it * RADIUS_DP_PER_UNIT).roundToInt() },
            fontScale = settings[FONT_SIZE_KEY]?.toIntOrNull()
                ?.let { it / PERCENT }?.takeIf { it in FONT_SCALE_RANGE },
            boldKeyLabels = settings[BOLD_KEY]?.toBooleanStrictOrNull()?.takeIf { it },
            keyOverrides = buildMap {
                if (spaceBackground != null || spaceText != null) {
                    put(KEY_SPACE, KeyOverride(background = spaceBackground, text = spaceText))
                }
            },
        )
        if (used.size < colours.count { it.value != NONE }) dropped += MultilingUnsupported.UNUSED_COLOURS
        return MultilingThemeResult.Converted(
            theme = theme,
            coloursRead = colours.size,
            coloursUsed = used.size,
            dropped = dropped.toList(),
        )
    }

    /** `ff3f3f47` as ARGB; `0` and a short value pad with zeros, as an int does. */
    private fun parseColour(text: String): Long? =
        text.trim().takeIf { it.isNotEmpty() && it.length <= ARGB_DIGITS }?.toLongOrNull(HEX)

    private const val COLOUR_KEY = "V.co"
    private const val WALLPAPER_KEY = "V.WP"
    private const val RADIUS_KEY = "K.rx"
    private const val FONT_SIZE_KEY = "K.fs"
    private const val BOLD_KEY = "V.FB"

    private const val SLOTS = 19
    private const val SLOT_BACKGROUND = 0
    private const val SLOT_KEY1 = 1
    private const val SLOT_TEXT1 = 2
    private const val SLOT_KEY2 = 3
    private const val SLOT_TEXT2 = 4
    private const val SLOT_HIGHLIGHT = 5
    private const val SLOT_KEY1_SHADOW = 6
    private const val SLOT_TEXT1_SHADOW = 7
    private const val SLOT_KEY2_SHADOW = 8
    private const val SLOT_TEXT2_SHADOW = 9
    private const val SLOT_POPUP = 10
    private const val SLOT_POPUP_TEXT = 11
    private const val SLOT_SUGGESTION_BAR = 12
    private const val SLOT_SUGGESTION_TEXT = 13
    private const val SLOT_SUBTEXT1 = 14
    private const val SLOT_SUBTEXT2 = 15
    private const val SLOT_SPACE = 16
    private const val SLOT_SPACE_TEXT = 17
    private const val SLOT_SUGGESTION_HIGHLIGHT = 18

    private const val NONE = 0L
    private const val OPAQUE_BLACK = 0xFF000000L
    private const val HEX = 16
    private const val ARGB_DIGITS = 8
    private const val DARK_THRESHOLD = 0.5f
    private const val SHADOW_ELEVATION_DP = 2f
    private const val RADIUS_DP_PER_UNIT = 0.7f
    private const val PERCENT = 100f
    private val FONT_SCALE_RANGE = 0.5f..2f
    private const val KEY_SPACE = "SPACE"
    private const val DEFAULT_NAME = "Multiling O theme"
}

/** What reading a Multiling O theme produced. */
sealed interface MultilingThemeResult {

    data class Converted(
        val theme: ThemeSpec,
        /** Colour slots the text states. */
        val coloursRead: Int,
        /** How many of them had somewhere to go here. */
        val coloursUsed: Int,
        /** What the theme asked for that did not come across whole. */
        val dropped: List<MultilingUnsupported> = emptyList(),
    ) : MultilingThemeResult

    /** No settings block, or one with no colours in it. */
    data object NotATheme : MultilingThemeResult
}

/** Something a Multiling O theme asked for that did not come across whole. */
enum class MultilingUnsupported {
    /** A shadow behind the key labels, which this keyboard does not draw. */
    TEXT_SHADOWS,

    /** A wallpaper picture: the text carries only a switch, never the picture. */
    WALLPAPER,

    /** Colours stated for parts of that keyboard this one has not. */
    UNUSED_COLOURS,
}
