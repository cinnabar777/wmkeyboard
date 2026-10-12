package com.wasimaster.wmkeyboard.core.util

import java.io.ByteArrayOutputStream

/**
 * The envelope Multiling O Keyboard shares things in.
 *
 * Multiling O has no export *file*. Everything it shares is text that travels
 * through the clipboard, a forum post or a share link, and comes in three
 * wrappers:
 *
 * - a settings export, `OK_Settings_Begin` … `OK_Settings_End`, one
 *   `__key::value::type` line per preference. Its *Misc → Import | Export*
 *   screen writes the whole set; a theme shared on its own is the same block
 *   with only the colour, size and corner keys in it;
 * - a DIY+ layout, `OK_Layout_Begin` … `OK_Layout_End`, a `Name:` line and then
 *   rows of keys;
 * - a DIY layout, a bare JSON object with `title` and `onScreen`.
 *
 * Any of them may arrive percent-encoded, because the community tools pass them
 * through a URL (`okbdtheme.appspot.com/e.html?OK_Settings_Begin%0A…`), and a
 * settings value is always percent-encoded inside the block (`%0A` between the
 * rows of a stored layout). This is the shared reading of that envelope, here
 * rather than beside either converter because the layout reader and the theme
 * reader live in modules that cannot see each other, and both need it.
 *
 * Read from what the app writes and what its users post, not from its code.
 */
object MultilingExport {

    const val SETTINGS_BEGIN = "OK_Settings_Begin"
    const val SETTINGS_END = "OK_Settings_End"
    const val LAYOUT_BEGIN = "OK_Layout_Begin"
    const val LAYOUT_END = "OK_Layout_End"

    /** Far above a real export: a full settings block with every emoji page is 60 KB. */
    const val MAX_LENGTH = 1024 * 1024

    /**
     * The settings block in [text] as key → value, with the leading `__` taken
     * off each key and each value percent-decoded. Null when there is no block.
     *
     * The block may be anywhere in the text, so a whole share link pastes as
     * well as the block alone. When the block itself is encoded — its lines
     * joined by `%0A` rather than by newlines — it is decoded once as a whole
     * first, which is what turns a link back into lines.
     */
    fun settings(text: String): Map<String, String>? {
        if (text.length > MAX_LENGTH) return null
        val start = text.indexOf(SETTINGS_BEGIN)
        if (start < 0) return null
        var block = text.substring(start + SETTINGS_BEGIN.length)
        block.indexOf(SETTINGS_END).takeIf { it >= 0 }?.let { block = block.substring(0, it) }
        if (block.indexOf('\n') < 0 && looksEncoded(block)) block = percentDecode(block)
        val entries = LinkedHashMap<String, String>()
        for (raw in block.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith(ENTRY_PREFIX)) continue
            val keyEnd = line.indexOf(SEPARATOR, ENTRY_PREFIX.length)
            val typeStart = line.lastIndexOf(SEPARATOR)
            if (keyEnd < 0 || typeStart <= keyEnd) continue
            val key = line.substring(ENTRY_PREFIX.length, keyEnd)
            val value = line.substring(keyEnd + SEPARATOR.length, typeStart)
            entries[key] = percentDecode(value)
        }
        return entries
    }

    /**
     * Each `OK_Layout_Begin` … `OK_Layout_End` body in [text], in order, with
     * the markers taken off. A missing end marker runs to the end of the text.
     *
     * A marker counts only on a line of its own. The pages that share these
     * layouts also explain them, and "select from OK_Layout_Begin to
     * OK_Layout_End" in a sentence is not a layout.
     */
    fun layoutBlocks(text: String): List<String> {
        if (text.length > MAX_LENGTH) return emptyList()
        val decoded = if (text.contains(LAYOUT_BEGIN + "%0A", ignoreCase = true)) percentDecode(text) else text
        val blocks = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = lineMarker(decoded, LAYOUT_BEGIN, from) ?: break
            val bodyStart = start + LAYOUT_BEGIN.length
            val end = lineMarker(decoded, LAYOUT_END, bodyStart) ?: decoded.length
            // Newlines only: a leading space on the first row is a gap key.
            blocks += decoded.substring(bodyStart, end).trim('\n', '\r')
            from = end
        }
        return blocks
    }

    /** Where [marker] next stands alone on its line in [text], from [from]. */
    private fun lineMarker(text: String, marker: String, from: Int): Int? {
        var at = text.indexOf(marker, from)
        while (at >= 0) {
            val lineStart = text.lastIndexOf('\n', at - 1) + 1
            val lineEnd = text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length
            val before = text.substring(lineStart, at)
            val after = text.substring(at + marker.length, lineEnd)
            if (before.isBlank() && after.isBlank()) return at
            at = text.indexOf(marker, at + marker.length)
        }
        return null
    }

    /**
     * Whether [text] carries the percent-escapes a link or a settings value
     * puts in: an encoded newline, quote or brace. A layout written by hand
     * can hold a bare `%` (it is on most symbol rows), so a lone percent sign
     * is not evidence.
     */
    fun looksEncoded(text: String): Boolean =
        EncodedMarkers.any { text.contains(it, ignoreCase = true) }

    /**
     * [text] with every `%XX` escape turned back into its byte, and the bytes
     * read as UTF-8. A `%` not followed by two hex digits stays a percent sign,
     * which is how Multiling's own encoder leaves one (`[4D:%%20…]` in a real
     * export is a percent key followed by encoded spaces). `+` stays `+`: the
     * layouts are full of plus keys and nothing here encodes a space that way.
     */
    fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hi = if (c == '%') text.getOrNull(i + 1)?.let(::hexValue) else null
            val lo = if (hi != null) text.getOrNull(i + 2)?.let(::hexValue) else null
            if (hi != null && lo != null) {
                out.write((hi shl HEX_SHIFT) or lo)
                i += ESCAPE_LENGTH
                continue
            }
            // Everything else goes in as its own UTF-8, a surrogate pair whole.
            val end = if (c.isHighSurrogate() && i + 1 < text.length) i + 2 else i + 1
            out.write(text.substring(i, end).encodeToByteArray())
            i = end
        }
        return out.toByteArray().decodeToString()
    }

    private fun hexValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + HEX_TEN
        in 'A'..'F' -> c - 'A' + HEX_TEN
        else -> null
    }

    private const val ENTRY_PREFIX = "__"
    private const val SEPARATOR = "::"
    private const val ESCAPE_LENGTH = 3
    private const val HEX_SHIFT = 4
    private const val HEX_TEN = 10

    private val EncodedMarkers = listOf("%0A", "%22", "%7B", "%5B")
}
