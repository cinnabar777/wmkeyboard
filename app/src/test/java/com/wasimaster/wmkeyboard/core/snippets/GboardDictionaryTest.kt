package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GboardDictionaryTest {

    // As Gboard's Export writes it: shortcut, word, locale, tab-separated.
    private val export = "# Gboard Dictionary version:1\n" +
        "omw\ton my way\ten-US\n" +
        "\tWasimaster\ten-US\n" +
        "\tNew York City\t\n" +
        "\\alpha\tα\t\n" +
        "\\alpha\tΑ\t\n" +
        "->\t→\t\n"

    private fun zipped(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(GboardDictionary.ENTRY_NAME))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test
    fun `entries split into words and shortcuts`() {
        val contents = GboardDictionary.read(export)!!
        assertEquals(6, contents.entries.size)
        assertEquals("omw", contents.entries[0].shortcut)
        assertEquals("on my way", contents.entries[0].word)
        assertEquals("en-US", contents.entries[0].locale)
        // A phrase with no shortcut is offered as its words.
        assertEquals(listOf("Wasimaster", "New", "York", "City"), contents.words)
        assertEquals(4, contents.shortcuts.size)
    }

    @Test
    fun `shortcuts become snippets that ask first`() {
        val converted = GboardDictionary.toSnippets(GboardDictionary.read(export)!!)
        assertEquals(3, converted.snippets.size)
        val omw = converted.snippets[0]
        assertEquals("omw", omw.trigger)
        assertEquals("on my way", omw.text)
        assertTrue(omw.confirm)
        // One shortcut, two words: one snippet with both offered.
        val alpha = converted.snippets[1]
        assertEquals("\\alpha", alpha.trigger)
        assertEquals("α", alpha.text)
        assertEquals(listOf("Α"), alpha.alternates)
        assertEquals(MultiExpand.CHIPS_ONLY, alpha.multiExpand)
        // `->` fires on its last symbol, so it is kept.
        assertEquals("->", converted.snippets[2].trigger)
        assertTrue(converted.notes.isEmpty())
    }

    @Test
    fun `only the header makes a file a gboard dictionary`() {
        assertNull(GboardDictionary.read("omw\ton my way\ten-US\n"))
        assertTrue(GboardDictionary.looksLikeGboard("\uFEFF# Gboard Dictionary version:1\n"))
        assertFalse(GboardDictionary.looksLikeGboard("# Some other dictionary\n"))
    }

    @Test
    fun `the zip gboard exports is read`() {
        val contents = GboardDictionary.readZip(zipped(export).inputStream())
        assertNotNull(contents)
        assertEquals(6, contents!!.entries.size)
        assertTrue(GboardDictionary.looksLikeGboardZip(listOf("dictionary.txt")))
        assertFalse(GboardDictionary.looksLikeGboardZip(listOf("package.yml", "_manifest.yml")))
    }

    @Test
    fun `the snippet reader takes the shortcuts and counts the words`() {
        val text = SnippetPayload.readText(export, "dictionary.txt")!!
        assertEquals(SnippetPayload.Source.GBOARD, text.source)
        assertEquals(3, text.snippets.size)
        assertEquals(GboardDictionary.FOLDER_NAME, text.suggestedName)
        // The words have nowhere to go here, and a note says where they do.
        assertEquals(1, text.notes.size)
        val zip = SnippetPayload.read(zipped(export), "PersonalDictionary.zip")!!
        assertEquals(SnippetPayload.Source.GBOARD, zip.source)
        assertFalse(zip.isEspanso)
    }
}
