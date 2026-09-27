package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictStore
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.stickers.CutoutModel
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Installing from files the user fetched elsewhere, the offline half of every
 * downloader: each file lands where its download would have put it, through
 * the same parse, and is recognised by the name it is published under.
 */
class OfflineInstallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir: File get() = tmp.root

    private fun gz(name: String, text: String): File =
        File(tmp.newFolder(), name).apply { GZIPOutputStream(outputStream()).use { it.write(text.toByteArray()) } }

    private fun plain(name: String, text: String): File = File(tmp.newFolder(), name).apply { writeText(text) }

    @Test
    fun `files are recognised by their published names`() {
        assertEquals("de", DictionaryCatalog.byFileName("de_full.txt.gz")?.languageId)
        assertEquals("de", DictionaryCatalog.byFileName("de_full.txt")?.languageId)
        assertNull(DictionaryCatalog.byFileName("de_offensive.txt.gz"))
        assertEquals("bn_rom" to true, NgramPackCatalog.byFileName("bn_rom_trigrams.txt.gz")?.let { it.first.languageId to it.second })
        assertEquals("en" to false, NgramPackCatalog.byFileName("en_bigrams.txt")?.let { it.first.languageId to it.second })
        assertEquals("de", EmojiDictCatalog.byFileName("de_emoji.json.gz")?.languageId)
        assertEquals("pinyin", CjkDictCatalog.byFileName("pinyin.tsv")?.id)
        assertEquals("ben", OcrPacks.packOf("ben.traineddata"))
        assertNull(OcrPacks.packOf("ben.txt"))
        assertNull(OcrPacks.packOf("nonexistent.traineddata"))
    }

    @Test
    fun `every entry's file name is its url's last segment`() {
        for (entry in DictionaryCatalog.entries) assertEquals(entry.url.substringAfterLast('/'), entry.fileName)
        for (entry in EmojiDictCatalog.entries) assertEquals(entry.url.substringAfterLast('/'), entry.fileName)
        for (entry in NgramPackCatalog.entries) {
            assertEquals(entry.bigramUrl().substringAfterLast('/'), entry.bigramFileName)
            assertEquals(entry.trigramUrl().substringAfterLast('/'), entry.trigramFileName)
        }
    }

    @Test
    fun `a word list installs from its gzip file`() {
        val entry = DictionaryCatalog.byId("de")!!
        val file = gz(entry.fileName, "Hund 100\nKatze 50\n")
        val words = WordlistDownloadManager.install(filesDir, entry, DictionaryCatalog.DictionarySize.SMALL, file)
        assertEquals(2, words)
        assertTrue(DictionaryStore.isDownloaded(filesDir, "de"))
        assertEquals("de", DictionaryStore.sourceEntryId(filesDir, "de"))
        assertTrue(WordlistDownloadManager.states.value["de"] is WordlistDownloadManager.DownloadStatus.Downloaded)
    }

    @Test
    fun `a word list installs from plain text too`() {
        val entry = DictionaryCatalog.byId("de")!!
        val words = WordlistDownloadManager.install(
            filesDir, entry, DictionaryCatalog.DictionarySize.SMALL, plain("de_full.txt", "eins 9\nzwei 8\ndrei 7\n"),
        )
        assertEquals(3, words)
    }

    @Test
    fun `an unreadable word list leaves nothing behind`() {
        val entry = DictionaryCatalog.byId("de")!!
        val failed = runCatching {
            WordlistDownloadManager.install(filesDir, entry, DictionaryCatalog.DictionarySize.SMALL, plain("de_full.txt", "not a list"))
        }
        assertTrue(failed.isFailure)
        assertFalse(DictionaryStore.isDownloaded(filesDir, "de"))
        assertFalse(DictionaryStore.partFile(filesDir, "de").exists())
    }

    @Test
    fun `word pairs compile from either or both halves`() {
        val entry = NgramPackCatalog.forLanguage("en")!!
        NgramPackDownloadManager.install(
            filesDir, entry,
            bigrams = plain(entry.bigramFileName.removeSuffix(".gz"), "of the 10\nin the 5\n"),
            trigrams = gz(entry.trigramFileName, "one of the 4\n"),
        )
        assertTrue(NgramPackDownloadManager.isDownloaded(filesDir, "en"))

        val bn = NgramPackCatalog.forLanguage("bn")!!
        NgramPackDownloadManager.install(filesDir, bn, bigrams = gz(bn.bigramFileName, "a b 3\n"), trigrams = null)
        assertTrue(NgramPackDownloadManager.isDownloaded(filesDir, "bn"))
    }

    @Test
    fun `emoji names install and undo an earlier delete`() {
        val entry = EmojiDictCatalog.forLanguage("de")!!
        File(filesDir, "emoji_dict/de").mkdirs()
        val count = EmojiDictDownloadManager.install(
            filesDir, entry,
            gz(entry.fileName, """[{"emoji":"😀","name":"grinsen","keywords":["lachen"],"category":"Smileys"}]"""),
        )
        assertEquals(1, count)
        assertTrue(EmojiDictStore.isDownloaded(filesDir, "de"))
        assertFalse(EmojiDictStore.isDeclined(filesDir, "de"))
    }

    @Test
    fun `whisper graphs are matched on size as well as name`() {
        val keys = WhisperCatalog.models.map { it.modelFile to it.modelBytes }
        assertEquals(keys.size, keys.toSet().size)
        val model = WhisperCatalog.models.minBy { it.modelBytes }
        assertTrue(WhisperDownloadManager.recognises(model.modelFile, model.modelBytes))
        assertFalse(WhisperDownloadManager.recognises(model.modelFile, model.modelBytes + 1))
    }

    private fun sized(name: String, bytes: Long): File =
        File(tmp.newFolder(), name).apply { RandomAccessFile(this, "rw").use { it.setLength(bytes) } }

    @Test
    fun `a whisper vocabulary imported first waits for its model`() {
        val model = WhisperCatalog.models.minBy { it.modelBytes }
        val vocab = WhisperDownloadManager.install(filesDir, sized(model.vocabFile, model.vocabBytes), model.vocabFile)
        assertTrue(vocab is WhisperDownloadManager.Installed.Vocab)
        // Waiting in the pool, not in a model folder where it would read as half a download.
        assertFalse(WhisperStore.hasPartial(filesDir, model))
        assertTrue(WhisperStore.orphanDirs(filesDir).isEmpty())

        val graph = WhisperDownloadManager.install(filesDir, sized(model.modelFile, model.modelBytes), model.modelFile)
        assertEquals(WhisperDownloadManager.Installed.Ready(model), graph)
        assertTrue(WhisperStore.isDownloaded(filesDir, model))
    }

    @Test
    fun `a whisper model without its vocabulary says what it still needs`() {
        val model = WhisperCatalog.models.minBy { it.modelBytes }
        val graph = WhisperDownloadManager.install(filesDir, sized(model.modelFile, model.modelBytes), model.modelFile)
        assertEquals(WhisperDownloadManager.Installed.NeedsVocab(model, model.vocabFile), graph)
        assertFalse(WhisperStore.isDownloaded(filesDir, model))

        WhisperDownloadManager.install(filesDir, sized(model.vocabFile, model.vocabBytes), model.vocabFile)
        assertTrue(WhisperStore.isDownloaded(filesDir, model))
    }

    @Test
    fun `the background remover refuses any other file`() {
        assertFalse(CutoutModel.install(filesDir, plain(CutoutModel.FILE_NAME, "not the model")))
        assertFalse(CutoutModel.isDownloaded(filesDir))
        assertFalse(CutoutModel.file(filesDir).exists())
    }
}
