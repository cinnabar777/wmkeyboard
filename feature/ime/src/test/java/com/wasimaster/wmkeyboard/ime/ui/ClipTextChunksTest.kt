package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pieces a clip's full text is shown in (#414): nothing of the text may be
 * lost or doubled, and no piece may split a word, a line or a surrogate pair
 * when it can help it.
 */
class ClipTextChunksTest {

    @Test fun shortTextIsOnePiece() {
        assertEquals(listOf("hello"), clipTextChunks("hello", max = 10))
        assertEquals(listOf(""), clipTextChunks("", max = 10))
    }

    @Test fun cutsAtTheLastLineBreakInsideTheLimit() {
        assertEquals(listOf("one two", "three four"), clipTextChunks("one two\nthree four", max = 12))
    }

    @Test fun cutsAtASpaceWhenALineRunsOn() {
        assertEquals(listOf("alpha beta", "gamma"), clipTextChunks("alpha beta gamma", max = 12))
    }

    @Test fun cutsHardInsideOneLongWord() {
        assertEquals(listOf("abcde", "fghij", "k"), clipTextChunks("abcdefghijk", max = 5))
    }

    @Test fun neverSplitsASurrogatePair() {
        // "ab" then an emoji (two chars): a cut at 3 would split the emoji.
        val text = "ab😀cd"
        val pieces = clipTextChunks(text, max = 3)
        assertEquals(listOf("ab", "😀c", "d"), pieces)
        pieces.forEach { assertTrue(it.isEmpty() || !Character.isHighSurrogate(it.last())) }
    }

    @Test fun keepsEveryCharacterButTheBreaksItCutAt() {
        val text = (1..500).joinToString(" ") { "word$it" } + "\n" + "x".repeat(3_000)
        val pieces = clipTextChunks(text, max = 700)
        assertTrue(pieces.all { it.length <= 700 })
        // Only the separators the cuts landed on are gone.
        val kept = pieces.sumOf { it.length }
        assertTrue(text.length - kept < pieces.size)
        assertEquals(text.filter { !it.isWhitespace() }, pieces.joinToString("").filter { !it.isWhitespace() })
    }
}
