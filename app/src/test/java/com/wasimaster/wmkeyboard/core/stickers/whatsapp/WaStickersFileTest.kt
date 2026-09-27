package com.wasimaster.wmkeyboard.core.stickers.whatsapp

import com.wasimaster.wmkeyboard.core.stickers.ProcessedSticker
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the screen resolves before the import starts; see [StickerPackFileTest]. */
private const val FALLBACK_NAME = "Imported stickers"

/**
 * The `.wastickers` reader against the file as Sticker Maker writes it and the
 * variants the other tools produce. Pictures are stand-in bytes: the
 * normaliser is a passthrough here, since decoding needs a device.
 */
class WaStickersFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val passthrough: (ByteArray) -> ProcessedSticker? = { bytes ->
        ProcessedSticker(bytes, "image/webp", animated = false, aspectRatio = 1f)
    }

    private fun store(dir: String = "stickers") =
        StickerPackStore(File(temp.root, dir).apply { mkdirs() })

    /** A flat archive, entries in the order given, as `zip -j` writes one. */
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun text(s: String) = s.toByteArray()

    private fun stickerMakerPack(): ByteArray = archive(
        "title.txt" to text("Grumpy cats"),
        "author.txt" to text("Wasi"),
        "tray.png" to byteArrayOf(9, 9),
        "sticker_1.webp" to byteArrayOf(1),
        "sticker_2.webp" to byteArrayOf(2),
        "sticker_3.webp" to byteArrayOf(3),
    )

    private fun import(bytes: ByteArray, target: StickerPackStore = store()) =
        WaStickersFile.import(ByteArrayInputStream(bytes), target, FALLBACK_NAME, normalize = passthrough)

    @Test
    fun `reads a Sticker Maker export`() {
        val target = store()
        val result = import(stickerMakerPack(), target) as StickerImportResult.Imported

        assertEquals("Grumpy cats", result.pack.name)
        assertEquals("whatsapp", result.pack.source)
        // The tray icon is not a sticker.
        assertEquals(3, result.pack.stickers.size)
        assertEquals(listOf("sticker_1", "sticker_2", "sticker_3"), result.sourceLabels(target))
        assertTrue(result.repairs.isEmpty())
        for (sticker in result.pack.stickers) assertTrue(target.fileFor(result.pack.id, sticker)!!.isFile)
    }

    /** The bytes each sticker was written from, in pack order, by stand-in id. */
    private fun StickerImportResult.Imported.sourceLabels(target: StickerPackStore): List<String> =
        pack.stickers.map { sticker ->
            "sticker_" + target.fileFor(pack.id, sticker)!!.readBytes().single()
        }

    @Test
    fun `peek reads the header without a store`() {
        val header = WaStickersFile.peek(ByteArrayInputStream(stickerMakerPack()))!!
        assertEquals("Grumpy cats", header.title)
        assertEquals("Wasi", header.author)
        assertEquals(3, header.stickerCount)
    }

    @Test
    fun `peek refuses an archive that is not a wastickers`() {
        assertNull(WaStickersFile.peek(ByteArrayInputStream(archive("a.webp" to byteArrayOf(1)))))
        assertNull(WaStickersFile.peek(ByteArrayInputStream("not a zip".toByteArray())))
    }

    @Test
    fun `a lone PNG among WebPs is the tray even under another name`() {
        // laggykiller's tool names the cover by a timestamp. WhatsApp wants
        // the tray as PNG and the stickers as WebP, which is enough to tell.
        val result = import(
            archive(
                "title.txt" to text("Pack"),
                "1700000000.png" to byteArrayOf(9),
                "1700000001.webp" to byteArrayOf(1),
                "1700000002.webp" to byteArrayOf(2),
            ),
        ) as StickerImportResult.Imported
        assertEquals(2, result.pack.stickers.size)
    }

    @Test
    fun `a pack written entirely as PNGs keeps every picture`() {
        // Nothing left to tell a timestamp-named tray apart by: keeping a
        // small extra sticker beats dropping a real one.
        val result = import(
            archive(
                "title.txt" to text("Pack"),
                "author.txt" to text("x"),
                "1.png" to byteArrayOf(1),
                "2.png" to byteArrayOf(2),
                "3.png" to byteArrayOf(3),
            ),
        ) as StickerImportResult.Imported
        assertEquals(3, result.pack.stickers.size)
    }

    @Test
    fun `cover and icon are tray names too`() {
        assertEquals(
            listOf("a.webp"),
            WaStickersFile.stickerEntries(listOf("title.txt", "Cover.png", "icon.webp", "a.webp")),
        )
    }

    @Test
    fun `an archive with a folder in it reads the same as a flat one`() {
        val result = import(
            archive(
                "pack/title.txt" to text("Nested"),
                "pack/tray.png" to byteArrayOf(9),
                "pack/a.webp" to byteArrayOf(1),
            ),
        ) as StickerImportResult.Imported
        assertEquals("Nested", result.pack.name)
        assertEquals(1, result.pack.stickers.size)
    }

    @Test
    fun `a missing or blank title takes the caller's name`() {
        val noTitle = import(archive("author.txt" to text("x"), "a.webp" to byteArrayOf(1)))
        assertEquals(FALLBACK_NAME, (noTitle as StickerImportResult.Imported).pack.name)

        val blank = import(archive("title.txt" to text("  \n"), "a.webp" to byteArrayOf(1)), store("blank"))
        assertEquals(FALLBACK_NAME, (blank as StickerImportResult.Imported).pack.name)
    }

    @Test
    fun `a title with a byte-order mark and trailing newline is cleaned`() {
        val result = import(archive("title.txt" to text("﻿Dogs\r\n"), "a.webp" to byteArrayOf(1)))
        assertEquals("Dogs", (result as StickerImportResult.Imported).pack.name)
    }

    @Test
    fun `an archive with a pack_json is not a wastickers`() {
        // That is one of the app's own formats, whatever else sits beside it.
        val target = store()
        val result = import(
            archive(
                "pack.json" to text("""{"format":"wmkeyboard-stickers"}"""),
                "title.txt" to text("x"),
                "a.webp" to byteArrayOf(1),
            ),
            target,
        )
        assertEquals(StickerImportResult.NotAStickerPack, result)
        assertTrue(target.isEmpty())
    }

    @Test
    fun `a zip of pictures with no text file is not a wastickers`() {
        val target = store()
        assertEquals(
            StickerImportResult.NotAStickerPack,
            import(archive("a.webp" to byteArrayOf(1), "b.webp" to byteArrayOf(2)), target),
        )
        assertTrue(target.isEmpty())
    }

    @Test
    fun `something that is not a zip is refused`() {
        val target = store()
        val result = WaStickersFile.import(
            ByteArrayInputStream("just some text".toByteArray()),
            target,
            FALLBACK_NAME,
            normalize = passthrough,
        )
        assertTrue(result.toString(), result is StickerImportResult.NotAStickerPack || result is StickerImportResult.Failed)
        assertTrue(target.isEmpty())
    }

    @Test
    fun `a pack whose only picture is the tray is refused rather than imported empty`() {
        val target = store()
        val result = import(archive("title.txt" to text("Empty"), "tray.png" to byteArrayOf(9)), target)
        assertTrue(result.toString(), result is StickerImportResult.NoStickers)
        assertTrue(target.isEmpty())
    }

    @Test
    fun `import leaves no staging directory behind`() {
        val target = store("target")
        import(stickerMakerPack(), target)
        val leftovers = File(temp.root, "target").list()!!.filter { it.startsWith(".") }
        assertTrue(leftovers.toString(), leftovers.isEmpty())
    }

    @Test
    fun `looksLikeWaStickers is the two text files and no manifest`() {
        assertTrue(WaStickersFile.looksLikeWaStickers(listOf("title.txt", "a.webp")))
        assertTrue(WaStickersFile.looksLikeWaStickers(listOf("x/Author.TXT", "x/a.webp")))
        assertFalse(WaStickersFile.looksLikeWaStickers(listOf("a.webp", "b.webp")))
        assertFalse(WaStickersFile.looksLikeWaStickers(listOf("pack.json", "title.txt", "a.webp")))
        assertFalse(WaStickersFile.looksLikeWaStickers(emptyList()))
    }
}
