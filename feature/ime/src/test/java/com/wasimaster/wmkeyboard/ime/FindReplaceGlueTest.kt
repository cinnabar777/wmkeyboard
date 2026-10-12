package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Test

class FindReplaceGlueTest {

    private val matches = listOf(0..2, 10..12, 20..22)

    @Test
    fun `the current match is the one the selection covers exactly`() {
        assertEquals(1, FindReplaceGlue.currentMatchIndex(matches, 10, 13))
        assertEquals(-1, FindReplaceGlue.currentMatchIndex(matches, 10, 12))
        assertEquals(-1, FindReplaceGlue.currentMatchIndex(emptyList(), 0, 3))
    }

    @Test
    fun `stepping wraps, and starts from the selection when nothing is current`() {
        assertEquals(2, FindReplaceGlue.stepIndex(matches, 1, 0, +1))
        assertEquals(0, FindReplaceGlue.stepIndex(matches, 2, 0, +1))
        assertEquals(2, FindReplaceGlue.stepIndex(matches, 0, 0, -1))
        assertEquals(1, FindReplaceGlue.stepIndex(matches, -1, 5, +1))
        assertEquals(0, FindReplaceGlue.stepIndex(matches, -1, 5, -1))
        assertEquals(0, FindReplaceGlue.stepIndex(matches, -1, 25, +1))
        assertEquals(2, FindReplaceGlue.stepIndex(matches, -1, 0, -1))
        assertEquals(-1, FindReplaceGlue.stepIndex(emptyList(), -1, 0, +1))
    }

    @Test
    fun `replacing one match drops it and shifts the later ones`() {
        assertEquals(listOf(0..2, 22..24), FindReplaceGlue.shiftMatches(matches, 1, +2))
        assertEquals(listOf(10..12, 20..22), FindReplaceGlue.shiftMatches(matches, 0, -3).map { (it.first + 3)..(it.last + 3) })
        assertEquals(matches, FindReplaceGlue.shiftMatches(matches, 7, 1))
    }

    @Test
    fun `an offset moves by the edits before it`() {
        val edits = listOf(TextEdit(20, 23, "x"), TextEdit(0, 3, "hello"))
        assertEquals(12, FindReplaceGlue.afterOffset(edits, 10))
        assertEquals(0, FindReplaceGlue.afterOffset(edits, 0))
        assertEquals(30 + 2 - 2, FindReplaceGlue.afterOffset(edits, 30))
    }

    @Test
    fun `the preview cuts at the radius and at line breaks, and marks what it left out`() {
        val short = FindReplaceGlue.preview("the cat sat", 4..6)!!
        assertEquals("the cat sat", short.text)
        assertEquals(4..6, short.match)

        val long = FindReplaceGlue.preview("a".repeat(40) + "cat" + "b".repeat(40), 40..42, radius = 5)!!
        assertEquals("…aaaaacatbbbbb…", long.text)
        assertEquals("cat", long.text.substring(long.match.first, long.match.last + 1))

        val lines = FindReplaceGlue.preview("first line\nthe cat\nlast", 15..17)!!
        assertEquals("the cat", lines.text)

        val spanning = FindReplaceGlue.preview("a\nb", 0..2)!!
        assertEquals("a⏎b", spanning.text)
        assertEquals(null, FindReplaceGlue.preview("abc", 2..5))
    }

    @Test
    fun `history keeps the newest first, once each, and only short one-line searches`() {
        val full = (1..FindHistoryPrefs.MAX_ENTRIES).map { "q$it" }
        assertEquals(listOf("new") + full.dropLast(1), FindHistoryPrefs.pushRecent(full, "new"))
        assertEquals(listOf("q3", "q1", "q2"), FindHistoryPrefs.pushRecent(listOf("q1", "q2", "q3"), "q3"))
        assertEquals(listOf("q1"), FindHistoryPrefs.pushRecent(listOf("q1"), "  "))
        assertEquals(listOf("q1"), FindHistoryPrefs.pushRecent(listOf("q1"), "two\nlines"))
        assertEquals(listOf("q1"), FindHistoryPrefs.pushRecent(listOf("q1"), "x".repeat(FindHistoryPrefs.MAX_LENGTH + 1)))
    }
}
