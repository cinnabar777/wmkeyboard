package com.wasimaster.wmkeyboard.core.stickers.whatsapp

import com.wasimaster.wmkeyboard.core.stickers.ProcessedSticker
import com.wasimaster.wmkeyboard.core.stickers.StickerArchive
import com.wasimaster.wmkeyboard.core.stickers.StickerImage
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackAdoption
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A WhatsApp sticker pack as the sticker-maker apps export it: a `.wastickers`
 * file.
 *
 * WhatsApp itself has no pack file. A pack reaches WhatsApp through another
 * app's content provider, which only WhatsApp may read, so the apps people make
 * packs with (Sticker Maker, Personal Stickers for WhatsApp, and the desktop
 * tools that imitate them) settled on a ZIP of their own for handing a pack
 * around:
 *
 * ```
 * mypack.wastickers
 * ├── title.txt          the pack's name
 * ├── author.txt         who made it
 * ├── tray.png           the 96×96 icon WhatsApp shows in its tray (or cover.png)
 * ├── sticker_1.webp     512×512, still or animated
 * └── sticker_2.webp     …
 * ```
 *
 * There is no manifest and no emoji per sticker: the two text files and the
 * pictures are all there is. The pictures are what a WhatsApp sticker already
 * is — a 512-pixel WebP — so [StickerImage] keeps them byte for byte, and a
 * pack that a tool wrote as PNGs is re-encoded the way a picked photo is.
 *
 * Entry names are never used as paths; see [StickerArchive].
 */
object WaStickersFile {

    const val FILE_EXTENSION = "wastickers"

    /** The name of the pack, as the exporting app wrote it. */
    const val TITLE_ENTRY = "title.txt"

    /** Who made the pack. Read for the confirm dialog; a pack keeps no author. */
    const val AUTHOR_ENTRY = "author.txt"

    /**
     * What the import picker accepts: the same trio the app's own pack file
     * takes, because a provider reports a `.wastickers` as any one of them.
     */
    val IMPORT_MIME_TYPES: Array<String> get() = StickerPackFile.IMPORT_MIME_TYPES

    /**
     * What the tray icon is called by the apps that write these files. Matched
     * on the name without its extension, case-insensitively.
     */
    private val TRAY_NAMES = setOf("tray", "cover", "icon", "tray_icon", "trayicon", "tray_image")

    private val IMAGE_EXTENSIONS = setOf("webp", "png", "gif")

    private val TEXT_ENTRIES = setOf(TITLE_ENTRY, AUTHOR_ENTRY)

    /** A title is one line; anything longer is somebody's mistake. */
    private const val MAX_TITLE_LENGTH = 128

    /** What a `.wastickers` says about itself before it is imported. */
    class Header(
        val title: String,
        val author: String,
        /** Pictures the import would add, tray icon already left out. */
        val stickerCount: Int,
    )

    /**
     * Whether an archive with these entry names is a `.wastickers`.
     *
     * The two text files are the whole signature: the pictures alone are any
     * ZIP of images, and a `pack.json` means the archive is one of this app's
     * own formats, whatever else is in it.
     */
    fun looksLikeWaStickers(entryNames: Collection<String>): Boolean {
        val tails = entryNames.map { StickerArchive.tailOf(it).lowercase() }
        if (StickerPackFile.MANIFEST in tails) return false
        return tails.any { it in TEXT_ENTRIES }
    }

    /**
     * Reads the title, the author and the sticker count out of [input] without
     * spilling a single picture, or null when the archive is not a
     * `.wastickers`. Blocking; call off the main thread.
     */
    fun peek(input: InputStream): Header? {
        val names = ArrayList<String>()
        val texts = HashMap<String, String>()
        runCatching {
            ZipInputStream(input.buffered()).use { zip ->
                var count = 0
                while (count++ < StickerArchive.MAX_ENTRIES) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    names += entry.name
                    val tail = StickerArchive.tailOf(entry.name).lowercase()
                    if (tail in TEXT_ENTRIES) texts[tail] = readText(zip)
                }
            }
        }
        if (!looksLikeWaStickers(names)) return null
        return Header(
            title = titleOf(texts[TITLE_ENTRY]),
            author = titleOf(texts[AUTHOR_ENTRY]),
            stickerCount = stickerEntries(names).size,
        )
    }

    /**
     * Reads a pack out of [input] and registers it with [store] under a fresh
     * id. [defaultName] names a pack whose `title.txt` is missing or blank;
     * the caller resolves it, because this runs with no context of its own.
     * [normalize] is [StickerImage] unless a test says otherwise.
     */
    fun import(
        input: InputStream,
        store: StickerPackStore,
        defaultName: String,
        now: Long = System.currentTimeMillis(),
        normalize: (ByteArray) -> ProcessedSticker? = { bytes ->
            (StickerImage.process(bytes) as? StickerImage.Result.Ok)?.sticker
        },
    ): StickerImportResult {
        val staging = store.stagingDir() ?: return StickerImportResult.Failed
        try {
            val unpacked = StickerArchive.unpack(input, staging, texts = TEXT_ENTRIES)
            if (!unpacked.read && unpacked.files.isEmpty() && unpacked.texts.isEmpty()) {
                return StickerImportResult.Failed
            }
            if (!looksLikeWaStickers(unpacked.files.keys + unpacked.texts.keys)) {
                return StickerImportResult.NotAStickerPack
            }
            val incoming = stickerEntries(unpacked.files.keys).map { name ->
                StickerPackAdoption.Incoming(
                    label = StickerArchive.tailOf(name).substringBeforeLast('.'),
                    read = { unpacked.files[name]?.readBytes() },
                )
            }
            return StickerPackAdoption.adopt(
                store = store,
                name = titleOf(unpacked.texts[TITLE_ENTRY]).ifBlank { defaultName },
                incoming = incoming,
                now = now,
                source = StickerPack.whatsappSource(),
                normalize = normalize,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * The entries that are stickers, in archive order.
     *
     * Every picture but the tray icon. The tray is found by its name first,
     * and failing that by the one rule the format's shape allows: when exactly
     * one picture is a PNG and every other one is a WebP, the PNG is the tray,
     * because WhatsApp wants the tray as PNG and the stickers as WebP. A pack
     * written entirely as PNGs keeps them all; if its tray was named by a
     * timestamp there is nothing left to tell it apart by, and it arrives as a
     * small sticker rather than the pack losing a real one.
     */
    fun stickerEntries(entryNames: Collection<String>): List<String> {
        val images = entryNames.filter { extensionOf(it) in IMAGE_EXTENSIONS }
        val named = images.filterNot { baseNameOf(it) in TRAY_NAMES }
        val pngs = named.filter { extensionOf(it) == "png" }
        if (pngs.size == 1 && named.size > 1 && (named - pngs.single()).all { extensionOf(it) == "webp" }) {
            return named - pngs.single()
        }
        return named
    }

    private fun extensionOf(name: String): String =
        StickerArchive.tailOf(name).substringAfterLast('.', "").lowercase()

    private fun baseNameOf(name: String): String =
        StickerArchive.tailOf(name).substringBeforeLast('.').lowercase()

    /** Enough of a text entry for a title; the rest of a bloated one is not read. */
    private fun readText(zip: ZipInputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        val limit = MAX_TITLE_LENGTH * 4
        while (out.size() < limit) {
            val n = zip.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray().decodeToString()
    }

    /**
     * The first line of a text entry, trimmed and cut to a sane length. A
     * byte-order mark is trimmed too: Windows tools write one, and `trim()`
     * does not count it as whitespace.
     */
    private fun titleOf(text: String?): String =
        text.orEmpty().trimStart('\uFEFF').lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.take(MAX_TITLE_LENGTH).orEmpty()
}
