package com.wasimaster.wmkeyboard.core.notes

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFilesTest {

    @Test
    fun `a titled, tagged note round-trips`() {
        val note = Note(1, "line one\nline two", title = "Plans", tags = listOf("Work", "school stuff"))
        val text = NoteFiles.toText(note)
        assertEquals("# Plans\n\nline one\nline two\n\nTags: Work, school stuff\n", text)
        val back = NoteFiles.fromText("plans.md", text, modifiedAt = 7)
        // The heading stays in the body; displayTitle reads it back.
        assertEquals("Plans", back.displayTitle())
        assertEquals("# Plans\n\nline one\nline two", back.body)
        assertEquals(listOf("Work", "school stuff"), back.tags)
        assertEquals(7, back.createdAt)
    }

    @Test
    fun `a derived title is not written as a second heading`() {
        val note = Note(1, "# Shopping\n- milk")
        assertEquals("# Shopping\n- milk\n", NoteFiles.toText(note))
        val titled = Note(1, "# Shopping\n- milk", title = "Shopping")
        assertEquals("# Shopping\n- milk\n", NoteFiles.toText(titled))
    }

    @Test
    fun `a Tags line only counts when it is last, and an empty file keeps its name`() {
        val note = NoteFiles.fromText("x.txt", "Tags: not these\nbody")
        assertTrue(note.tags.isEmpty())
        assertEquals("Tags: not these\nbody", note.body)
        val named = NoteFiles.fromText("Meeting notes.txt", "")
        assertEquals("Meeting notes", named.title)
        val crlf = NoteFiles.fromText("a.md", "﻿one\r\ntwo\r\n\r\ntags: a, b\r\n")
        assertEquals("one\ntwo", crlf.body)
        assertEquals(listOf("a", "b"), crlf.tags)
    }

    @Test
    fun `file names are slugs, deduped, with the id as a fallback`() {
        val taken = HashSet<String>()
        assertEquals("Buy-milk-eggs.md", NoteFiles.fileName(Note(1, "Buy milk & eggs!"), NoteFiles.Format.MARKDOWN, taken))
        // The same words in another case still collide: the taken set ignores case.
        assertEquals("buy-milk-eggs-2.md", NoteFiles.fileName(Note(2, "buy milk & eggs"), NoteFiles.Format.MARKDOWN, taken))
        assertEquals("note-3.txt", NoteFiles.fileName(Note(3, "   "), NoteFiles.Format.TEXT, taken))
        assertEquals(60, NoteFiles.slug("w".repeat(90)).length)
    }

    @Test
    fun `a zip round-trips and skips what is not a note`() {
        val notes = listOf(
            Note(1, "first", title = "One", tags = listOf("T"), updatedAt = 1_700_000_000_000L),
            Note(2, "second"),
        )
        val bytes = ByteArrayOutputStream().also { NoteFiles.writeZip(notes, NoteFiles.Format.MARKDOWN, it) }.toByteArray()
        val back = NoteFiles.readZip(ByteArrayInputStream(bytes))
        assertEquals(2, back.size)
        assertEquals("One", back[0].displayTitle())
        assertEquals(listOf("T"), back[0].tags)
        assertEquals("second", back[1].body)
        assertTrue(NoteFiles.isNoteFile("a.MD"))
        assertTrue(NoteFiles.isNoteFile("b.markdown"))
        assertFalse(NoteFiles.isNoteFile("c.pdf"))
        assertFalse(NoteFiles.isNoteFile("md"))
    }
}
