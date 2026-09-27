package com.wasimaster.wmkeyboard.core.stickers

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The one way a sticker archive is opened, shared by the app's own
 * `.wmstickers` reader and the WhatsApp `.wastickers` reader.
 *
 * Every entry is spilled into a staging directory under a name of this
 * object's choosing (`e1.bin`, `e2.bin`, …), and the archive's own entry
 * names survive only as the keys of the returned map, for looking entries up
 * afterwards. Nothing is ever written to a path derived from an entry name,
 * so neither `../` in an entry nor a manifest that points outside the pack
 * can escape the directory. The small text entries a format keys off — a
 * manifest, a title — are kept in memory instead of on disk.
 */
internal object StickerArchive {

    /** Zip-bomb guards: nothing legitimate comes close to either. */
    const val MAX_ENTRIES = 500
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** A text entry is a manifest or a title, never megabytes. */
    private const val MAX_TEXT_BYTES = 256 * 1024

    /**
     * The archive spilled into a staging directory.
     *
     * [files] is entry name -> staged file, in the order the archive listed
     * them. [texts] holds the entries named in the `texts` argument, keyed by
     * the name they were asked for. [read] is false when the archive read
     * threw partway; whatever landed before that is still in the two maps.
     */
    class Unpacked(
        val files: Map<String, File>,
        val texts: Map<String, String>,
        val read: Boolean,
    )

    /**
     * Spills every entry of [input] into [staging].
     *
     * [texts] names the entries to keep as text rather than on disk, matched
     * case-insensitively on the entry's file name so that an archive built
     * with a folder in it (`pack/title.txt`) is read the same as a flat one.
     * An entry past [StickerImage.MAX_SOURCE_BYTES] is dropped; once the whole
     * archive passes [MAX_TOTAL_BYTES] the rest is not read at all.
     */
    fun unpack(input: InputStream, staging: File, texts: Set<String> = emptySet()): Unpacked {
        val staged = LinkedHashMap<String, File>()
        val kept = HashMap<String, String>()
        val wanted = texts.associateBy { it.lowercase() }
        val read = runCatching {
            ZipInputStream(input.buffered()).use { zip ->
                var count = 0
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++count > MAX_ENTRIES) break
                    val name = entry.name
                    val asText = wanted[tailOf(name).lowercase()]
                    if (asText != null) {
                        kept[asText] = readText(zip)
                        continue
                    }
                    val target = File(staging, "e$count.bin")
                    val written = spill(zip, target, total)
                    total += written
                    if (written < 0 || written > StickerImage.MAX_SOURCE_BYTES) {
                        target.delete()
                        if (written < 0) break
                    } else {
                        staged[name] = target
                    }
                }
            }
            true
        }.getOrDefault(false)
        return Unpacked(staged, kept, read)
    }

    /** The file name part of an entry name: `stickers/a.webp` -> `a.webp`. */
    fun tailOf(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')

    private fun readText(zip: ZipInputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < MAX_TEXT_BYTES) {
            val n = zip.read(buffer, 0, minOf(buffer.size, MAX_TEXT_BYTES - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray().decodeToString()
    }

    /**
     * Copies one entry into [target]. Returns the bytes written, or -1 once the
     * archive as a whole has gone past [MAX_TOTAL_BYTES] — at which point there
     * is no point reading the rest of it.
     */
    private fun spill(zip: ZipInputStream, target: File, soFar: Long): Long {
        var written = 0L
        var overran = false
        target.outputStream().buffered().use { sink ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = zip.read(buffer)
                if (n <= 0) break
                written += n
                if (soFar + written > MAX_TOTAL_BYTES) {
                    overran = true
                    break
                }
                if (written > StickerImage.MAX_SOURCE_BYTES) break
                sink.write(buffer, 0, n)
            }
        }
        return if (overran) -1 else written
    }
}
