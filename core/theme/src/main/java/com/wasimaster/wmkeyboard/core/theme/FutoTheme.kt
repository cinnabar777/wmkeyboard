package com.wasimaster.wmkeyboard.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

/**
 * Reading a FUTO Keyboard custom theme.
 *
 * A FUTO theme is a ZIP. Its settings are one of two files beside the pictures
 * they name:
 *
 * - `theme.txt`, TOML, the format people write by hand and share. Colours are
 *   `#RRGGBB` or `#RRGGBBAA` (alpha *last*), and a `system_…` name is a colour
 *   from the phone's own wallpaper palette.
 * - `config.json`, what FUTO's own theme editor saves. Colours are `#RRGGBB` or
 *   `#AARRGGBB` (alpha *first*), and the role names are camelCase.
 *
 * Both name the same Material colour roles plus eight of FUTO's own, and FUTO
 * draws its keyboard from a few of them: the board is `keyboard_surface`, a
 * letter key `keyboard_container` with `on_keyboard_container` on it, the other
 * keys `keyboard_container_variant`, a pressed key `keyboard_container_pressed`,
 * the enter key and the swipe trail `primary` with `on_primary`, and the
 * long-press popup `keyboard_press`. Those are the ones read here; the other
 * thirty-odd roles colour FUTO's own menus.
 *
 * **Written from the published format, not from FUTO's code.** FUTO Keyboard is
 * under its own source-first licence; what is transcribed here is the shape of
 * a file, the same footing as the other converters.
 *
 * What does not come across: pictures drawn per key (FUTO's `matchrules`, its
 * nine-patch key borders and icons), the background shader, and colours taken
 * from the wallpaper. Each is named in [FutoThemeResult.Converted.dropped].
 */
object FutoTheme {

    /** What the import picker accepts: a theme is a ZIP under whatever name it was given. */
    val IMPORT_MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")

    const val VERSION_ENTRY = "FUTOKeyboardTheme_Version"
    const val TOML_CONFIG = "theme.txt"
    const val JSON_CONFIG = "config.json"

    /** Past this, one entry is not part of a theme. */
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024
    private const val MAX_ENTRIES = 512

    /**
     * Whether a ZIP's entry names look like a FUTO theme. `theme.txt` alone is
     * a common name, so it is the caller's cue to read and not proof: [read]
     * still has to find the colours.
     */
    fun looksLikeFutoTheme(names: Collection<String>): Boolean =
        VERSION_ENTRY in names || TOML_CONFIG in names

    /** Reads a theme ZIP. Never throws. */
    fun read(input: InputStream): FutoThemeResult {
        val files = runCatching { unzip(input) }.getOrNull() ?: return FutoThemeResult.Unreadable
        return read(files)
    }

    /** Reads a theme already unpacked, by entry name. */
    fun read(files: Map<String, ByteArray>): FutoThemeResult {
        // TOML first, as FUTO does: a theme written by hand and then opened in
        // FUTO's editor keeps its hand-written file.
        files[TOML_CONFIG]?.let { bytes ->
            val toml = MiniToml.parse(bytes.decodeToString()) ?: return FutoThemeResult.NotATheme
            return fromToml(toml, files)
        }
        files[JSON_CONFIG]?.let { bytes ->
            val obj = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() as? JsonObject
                ?: return FutoThemeResult.NotATheme
            return fromJson(obj, files)
        }
        return FutoThemeResult.NotATheme
    }

    // ---- the two files ----

    private fun fromToml(toml: Map<String, Any?>, files: Map<String, ByteArray>): FutoThemeResult {
        val colours = toml.table("colors") ?: return FutoThemeResult.NotATheme
        val options = toml.table("options").orEmpty()
        val background = options.table("background")
        val matchrules = toml.table("matchrules").orEmpty()
        val roles = LinkedHashMap<String, String>()
        for ((key, value) in colours) (value as? String)?.let { roles[key] = it }
        return convert(
            roles = roles,
            alphaLast = true,
            name = toml["name"] as? String,
            author = toml["author"] as? String,
            borders = options["auto_borders"] as? Boolean ?: true,
            roundness = (options["roundedness"] as? Number)?.toFloat() ?: 1f,
            textScale = (options["scale_text"] as? Number)?.toFloat(),
            hintScale = (options["scale_hints"] as? Number)?.toFloat(),
            textWeight = (options["weight_text"] as? Number)?.toFloat(),
            backgroundImage = background?.get("image") as? String,
            backgroundOpacity = (background?.get("opacity") as? Number)?.toFloat() ?: TOML_BACKGROUND_OPACITY,
            font = options.table("font")?.get("font") as? String,
            keyPictures = (matchrules["border"] as? List<*>).orEmpty().isNotEmpty() ||
                (matchrules["icon"] as? List<*>).orEmpty().isNotEmpty(),
            shader = false,
            hint = null,
            navigationBar = null,
            files = files,
        )
    }

    private fun fromJson(obj: JsonObject, files: Map<String, ByteArray>): FutoThemeResult {
        val roles = LinkedHashMap<String, String>()
        for ((key, value) in obj) {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (text.startsWith("#")) roles[snakeCase(key)] = text
        }
        fun string(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        fun float(key: String) = (obj[key] as? JsonPrimitive)?.floatOrNull
        val keyPictures = (obj["keyBackgrounds"] as? JsonObject)?.isNotEmpty() == true ||
            (obj["keyIcons"] as? JsonObject)?.isNotEmpty() == true
        return convert(
            roles = roles,
            alphaLast = false,
            name = string("name"),
            author = string("author"),
            borders = (obj["keyBorders"] as? JsonPrimitive)?.booleanOrNull ?: true,
            roundness = float("keyRoundness") ?: 1f,
            textScale = null,
            hintScale = null,
            textWeight = null,
            backgroundImage = string("backgroundImage"),
            backgroundOpacity = float("backgroundImageOpacity") ?: 1f,
            font = string("keysFont"),
            keyPictures = keyPictures,
            shader = !string("keyboardBackgroundShader").isNullOrBlank(),
            hint = roles["hint_color"],
            navigationBar = roles["navigation_bar_color"],
            files = files,
        )
    }

    // ---- the mapping ----

    @Suppress("LongParameterList")
    private fun convert(
        roles: Map<String, String>,
        alphaLast: Boolean,
        name: String?,
        author: String?,
        borders: Boolean,
        roundness: Float,
        textScale: Float?,
        hintScale: Float?,
        textWeight: Float?,
        backgroundImage: String?,
        backgroundOpacity: Float,
        font: String?,
        keyPictures: Boolean,
        shader: Boolean,
        hint: String?,
        navigationBar: String?,
        files: Map<String, ByteArray>,
    ): FutoThemeResult {
        val dropped = linkedSetOf<FutoThemeUnsupported>()
        fun colour(text: String?): Long? {
            if (text == null) return null
            if (!text.startsWith("#")) {
                // A wallpaper colour, or a name for another role: FUTO only
                // allows the first in the colour table.
                if (text.startsWith(SYSTEM_PREFIX)) dropped += FutoThemeUnsupported.WALLPAPER_COLOURS
                return null
            }
            return parseHex(text, alphaLast)
        }
        fun role(name: String): Long? = colour(roles[name])

        val board = role("keyboard_surface") ?: role("background") ?: return FutoThemeResult.NotATheme
        val key = role("keyboard_container") ?: board
        val modifier = role("keyboard_container_variant") ?: key
        // Without borders FUTO draws no key at all, and the label sits on the
        // board in the board's own text colour.
        val keyText = (if (borders) role("on_keyboard_container") else role("on_background"))
            ?: role("on_keyboard_container")
            ?: onColorFor(composite(key, board))
        val primary = role("primary")
        val enter = primary ?: modifier
        val night = Color(composite(board, OPAQUE_BLACK)).luminance() < DARK_THRESHOLD

        if (keyPictures) dropped += FutoThemeUnsupported.KEY_PICTURES
        if (shader) dropped += FutoThemeUnsupported.SHADER

        val images = LinkedHashMap<String, ByteArray>()
        backgroundImage?.let { bytesOf(it, files) }?.let { images[FlexTheme.IMAGE_BACKGROUND] = it }
        val fontFile = font?.let { path ->
            bytesOf(path, files)?.let { ConvertedFont(name ?: DEFAULT_NAME, path.substringAfterLast('/'), it) }
        }

        val theme = ThemeSpec(
            id = "",
            name = name?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME,
            dark = night,
            boardBackground = board,
            keyBackground = key,
            keyText = keyText,
            modifierKeyBackground = modifier,
            modifierKeyText = keyText,
            enterKeyBackground = enter,
            enterKeyText = role("on_primary") ?: onColorFor(composite(enter, board)),
            pressedKeyBackground = role("keyboard_container_pressed"),
            // FUTO draws the hint letters in the label colour at half strength.
            hintText = colour(hint) ?: halfAlpha(keyText),
            accent = primary ?: onColorFor(board),
            gestureTrailColor = primary,
            popupBackground = role("keyboard_press"),
            popupText = role("on_keyboard_container"),
            suggestionBarBackground = if (borders) board else role("keyboard_surface_dim") ?: board,
            suggestionText = role("on_background"),
            navigationBarBackground = colour(navigationBar),
            keyShape = when {
                !borders -> KeyShapeKind.NONE
                roundness <= SHARP_ROUNDNESS -> KeyShapeKind.SHARP
                else -> KeyShapeKind.ROUNDED
            },
            keyCornerRadiusDp = roundness.takeIf { borders && it > SHARP_ROUNDNESS && it != 1f }
                ?.let { (FUTO_CORNER_DP * it).roundToInt() },
            fontScale = textScale?.takeIf { it != 1f && it in SCALE_RANGE },
            hintFontScale = hintScale?.takeIf { it != 1f && it in SCALE_RANGE },
            boldKeyLabels = textWeight?.takeIf { it >= BOLD_WEIGHT }?.let { true },
            backgroundImageOpacity = if (images.isEmpty()) 1f else backgroundOpacity.coerceIn(0f, 1f),
        )
        return FutoThemeResult.Converted(
            converted = ConvertedTheme(theme, images, fontFile),
            author = author?.takeIf { it.isNotBlank() && it != "Anonymous" }.orEmpty(),
            dropped = dropped.toList(),
        )
    }

    /** A path inside the ZIP, or a `data:image/…;base64,` URI, as bytes. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun bytesOf(source: String, files: Map<String, ByteArray>): ByteArray? {
        if (source.startsWith("data:")) {
            val comma = source.indexOf(',')
            if (comma < 0) return null
            return runCatching { Base64.Default.decode(source.substring(comma + 1)) }.getOrNull()
        }
        return files[source.trimStart('/')]
    }

    /**
     * `#RGB`, `#RRGGBB`, or eight digits with the alpha last ([alphaLast], the
     * TOML file) or first (the JSON one). As an ARGB long.
     */
    internal fun parseHex(text: String, alphaLast: Boolean): Long? {
        val hex = text.removePrefix("#")
        if (hex.any { it.digitToIntOrNull(HEX) == null }) return null
        val argb = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> if (alphaLast) hex.substring(6) + hex.substring(0, 6) else hex
            else -> return null
        }
        return argb.toLong(HEX)
    }

    /** [argb] at half its alpha, rounded the way Compose's `copy(alpha = 0.5f)` rounds. */
    private fun halfAlpha(argb: Long): Long {
        val alpha = (((argb ushr 24) and 0xFF) / 2.0).roundToInt()
        return (argb and 0x00FFFFFFL) or (alpha.toLong() shl 24)
    }

    /** `onKeyboardContainer` → `on_keyboard_container`, so both files share one role table. */
    private fun snakeCase(name: String): String = buildString {
        for (c in name) {
            if (c.isUpperCase()) append('_').append(c.lowercaseChar()) else append(c)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.table(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    private fun unzip(input: InputStream): Map<String, ByteArray>? {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            while (count++ < MAX_ENTRIES) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.trimStart('/')
                if (name.split('/').any { it == ".." }) continue
                val bytes = readCapped(zip) ?: return null
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) return null
                out[name] = bytes
            }
        }
        return out
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private const val SYSTEM_PREFIX = "system_"
    private const val DEFAULT_NAME = "FUTO theme"
    private const val OPAQUE_BLACK = 0xFF000000L
    private const val DARK_THRESHOLD = 0.5f
    private const val HEX = 16

    /** FUTO's background opacity when a TOML theme names a picture but no opacity. */
    private const val TOML_BACKGROUND_OPACITY = 0.5f

    /** FUTO's key corner at a roundedness of 1. */
    private const val FUTO_CORNER_DP = 9f
    private const val SHARP_ROUNDNESS = 0.05f
    private const val BOLD_WEIGHT = 600f
    private val SCALE_RANGE = 0.5f..2f
    private const val BUFFER = 64 * 1024
}

/** What reading a FUTO theme produced. */
sealed interface FutoThemeResult {

    data class Converted(
        /** The theme, with its background picture and font beside it, ready to store. */
        val converted: ConvertedTheme,
        /** Who the file says made it, or blank. */
        val author: String,
        val dropped: List<FutoThemeUnsupported> = emptyList(),
    ) : FutoThemeResult

    /** A ZIP, but no FUTO theme settings in it, or no colours in them. */
    data object NotATheme : FutoThemeResult

    /** Not a ZIP, truncated, or past the size caps. */
    data object Unreadable : FutoThemeResult
}

/** Something a FUTO theme asked for that did not come across whole. */
enum class FutoThemeUnsupported {
    /** Pictures drawn per key: FUTO's key borders and icons. */
    KEY_PICTURES,

    /** Colours taken from the wallpaper, which this app works out for itself. */
    WALLPAPER_COLOURS,

    /** A shader drawn behind the keys. */
    SHADER,
}

/**
 * The part of TOML a FUTO theme uses: `[table]` and `[table.sub]` headers,
 * `[[array.of.tables]]`, and `key = value` lines whose value is a string, a
 * number, a boolean, an array of those, or an inline table of them. Comments
 * start with `#` outside a string.
 *
 * Not a TOML parser: dotted keys on the left of `=`, multi-line strings and
 * dates are not read. A line that is none of the above is skipped rather than
 * failing the file, so a theme that uses one still gives its colours.
 */
internal object MiniToml {

    /** The document as nested maps, or null when it holds no key at all. */
    fun parse(text: String): Map<String, Any?>? {
        val root = LinkedHashMap<String, Any?>()
        var current: MutableMap<String, Any?> = root
        var keys = 0
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = stripComment(lines[i]).trim()
            i++
            if (line.isEmpty()) continue
            if (line.startsWith("[[") && line.endsWith("]]")) {
                val path = line.removePrefix("[[").removeSuffix("]]").split('.').map { it.trim().trim('"') }
                val parent = tableAt(root, path.dropLast(1))
                @Suppress("UNCHECKED_CAST")
                val list = parent.getOrPut(path.last()) { ArrayList<Any?>() } as? MutableList<Any?> ?: continue
                val table = LinkedHashMap<String, Any?>()
                list += table
                current = table
                continue
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = tableAt(root, line.removePrefix("[").removeSuffix("]").split('.').map { it.trim().trim('"') })
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().trim('"')
            var raw = line.substring(eq + 1).trim()
            // An array may run over several lines.
            while (raw.startsWith("[") && depth(raw) > 0 && i < lines.size) {
                raw += " " + stripComment(lines[i]).trim()
                i++
            }
            val value = Values(raw).value() ?: continue
            current[key] = value
            keys++
        }
        return if (keys > 0) root else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun tableAt(root: MutableMap<String, Any?>, path: List<String>): MutableMap<String, Any?> {
        var table = root
        for (part in path) {
            table = when (val next = table[part]) {
                is MutableMap<*, *> -> next as MutableMap<String, Any?>
                is MutableList<*> -> (next.lastOrNull() as? MutableMap<String, Any?>) ?: LinkedHashMap()
                else -> LinkedHashMap<String, Any?>().also { table[part] = it }
            }
        }
        return table
    }

    private fun depth(text: String): Int {
        var depth = 0
        var quote: Char? = null
        for (c in text) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '[' -> depth++
                c == ']' -> depth--
            }
        }
        return depth
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        for ((index, c) in line.withIndex()) {
            when {
                escaped -> escaped = false
                quote == '"' && c == '\\' -> escaped = true
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' -> return line.substring(0, index)
            }
        }
        return line
    }

    /** One value, read left to right. */
    private class Values(private val text: String) {
        private var at = 0

        fun value(): Any? {
            skipSpace()
            if (at >= text.length) return null
            return when (val c = text[at]) {
                '"' -> basicString()
                '\'' -> literalString()
                '[' -> array()
                '{' -> inlineTable()
                else -> if (c == 't' || c == 'f') boolean() else number()
            }
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun basicString(): String? {
            at++
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (at >= text.length) return null
                        when (val e = text[at++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'u' -> {
                                if (at + UNICODE_DIGITS > text.length) return null
                                out.append(text.substring(at, at + UNICODE_DIGITS).toInt(HEX_RADIX).toChar())
                                at += UNICODE_DIGITS
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            return null
        }

        private fun literalString(): String? {
            at++
            val end = text.indexOf('\'', at)
            if (end < 0) return null
            return text.substring(at, end).also { at = end + 1 }
        }

        private fun array(): List<Any?>? {
            at++
            val out = ArrayList<Any?>()
            while (true) {
                skipSpace()
                if (at >= text.length) return null
                if (text[at] == ']') {
                    at++
                    return out
                }
                out += value() ?: return null
                skipSpace()
                if (at < text.length && text[at] == ',') at++
            }
        }

        private fun inlineTable(): Map<String, Any?>? {
            at++
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpace()
                if (at >= text.length) return null
                if (text[at] == '}') {
                    at++
                    return out
                }
                val eq = text.indexOf('=', at)
                if (eq < 0) return null
                val key = text.substring(at, eq).trim().trim('"')
                at = eq + 1
                out[key] = value() ?: return null
                skipSpace()
                if (at < text.length && text[at] == ',') at++
            }
        }

        private fun boolean(): Boolean? = when {
            text.startsWith("true", at) -> true.also { at += "true".length }
            text.startsWith("false", at) -> false.also { at += "false".length }
            else -> null
        }

        private fun number(): Number? {
            val start = at
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] in "+-._")) at++
            val raw = text.substring(start, at).replace("_", "")
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()
        }
    }

    private const val UNICODE_DIGITS = 4
    private const val HEX_RADIX = 16
}
