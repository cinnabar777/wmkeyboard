package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetCsvTest {

    @Test
    fun `a header names the columns in any order`() {
        val read = SnippetCsv.read(
            "Label,Expansion,Abbreviation\nWay,on my way,omw\nAddr,\"1 Main St\nTown\",addr\n",
            allowHeaderless = false,
        )!!
        assertTrue(read.hasHeader)
        assertEquals(2, read.snippets.size)
        assertEquals("omw", read.snippets[0].trigger)
        assertEquals("on my way", read.snippets[0].text)
        assertEquals("Way", read.snippets[0].label)
        // A quoted field keeps its line break.
        assertEquals("1 Main St\nTown", read.snippets[1].text)
    }

    @Test
    fun `a headerless table is read by position only when asked`() {
        val text = "omw,on my way\nbrb,be right back,Back soon\n"
        assertNull(SnippetCsv.read(text, allowHeaderless = false))
        val read = SnippetCsv.read(text, allowHeaderless = true)!!
        assertEquals(listOf("omw", "brb"), read.snippets.map { it.trigger })
        assertEquals("Back soon", read.snippets[1].label)
        // No label column on the first row: named after its text.
        assertEquals("on my way", read.snippets[0].label)
    }

    @Test
    fun `tabs and semicolons are sniffed from the first line`() {
        assertEquals('\t', SnippetCsv.sniffDelimiter("trigger\ttext, with a comma\n"))
        assertEquals(';', SnippetCsv.sniffDelimiter("trigger;text;label\n"))
        assertEquals(',', SnippetCsv.sniffDelimiter("\"a;b\",c\n"))
        assertNull(SnippetCsv.sniffDelimiter("just some words\n"))
        val read = SnippetCsv.read("shortcut\tphrase\nty\tthank you, really\n", allowHeaderless = false)!!
        assertEquals("thank you, really", read.snippets.single().text)
    }

    @Test
    fun `doubled quotes and CRLF line ends are read as written`() {
        val rows = SnippetCsv.parseRows("a,\"say \"\"hi\"\"\"\r\nb,c\r\n", ',')
        assertEquals(listOf(listOf("a", "say \"hi\""), listOf("b", "c")), rows)
    }

    @Test
    fun `folders and tags come across`() {
        val read = SnippetCsv.read(
            "trigger,text,folder,tags\nhi,hello,Greetings,work;polite\nyo,hey,greetings,\nok,okay,,\n",
            allowHeaderless = false,
        )!!
        assertEquals(1, read.folders.size)
        assertEquals("Greetings", read.folders.single().name)
        assertEquals(read.folders.single().id, read.snippets[0].folderId)
        // The same folder whatever the case it is spelled in.
        assertEquals(read.folders.single().id, read.snippets[1].folderId)
        assertEquals(0L, read.snippets[2].folderId)
        assertEquals(listOf("work", "polite"), read.snippets[0].tags)
    }

    @Test
    fun `rows without text are dropped and a trigger that cannot fire is removed`() {
        val read = SnippetCsv.read("trigger,text\nempty,\n\"a\tb\",tabbed trigger\nok,fine\n", allowHeaderless = false)!!
        assertEquals(2, read.snippets.size)
        assertNull(read.snippets[0].trigger)
        assertEquals(2, read.notes.size)
    }

    @Test
    fun `a table that only looks like snippets is refused`() {
        // A contacts export: a name but no text column.
        assertNull(SnippetCsv.read("Name,Phone\nAda,555\n", allowHeaderless = false))
        // Mostly one column.
        assertNull(SnippetCsv.read("a\nb\nc,d\n", allowHeaderless = true))
        assertNull(SnippetCsv.read("", allowHeaderless = true))
    }

    @Test
    fun `the payload reader takes a csv by name but not unnamed plain text`() {
        val parsed = SnippetPayload.readText("omw,on my way\n", "replies.csv")
        assertNotNull(parsed)
        assertEquals(SnippetPayload.Source.CSV, parsed!!.source)
        assertEquals("replies", parsed.suggestedName)
        assertTrue(parsed.isForeign)
        assertTrue(!parsed.isEspanso)
        assertNull(SnippetPayload.readText("omw,on my way\n", "notes.txt"))
        assertNotNull(SnippetPayload.readText("omw,on my way\n", "notes.txt", expectTable = true))
    }
}
