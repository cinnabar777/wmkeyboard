package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long a spacebar language switch stays on screen after the lift. A flick
 * never saw its preview and gets the echo; a slow swipe already watched the
 * language land and gets none.
 */
class LanguageEchoTest {

    @Test
    fun `a flick that lifts at once echoes in full`() {
        assertEquals(500L, languageEchoMs(0L))
    }

    @Test
    fun `a brief preview tops the echo up to the full time`() {
        assertEquals(470L, languageEchoMs(30L))
        assertEquals(251L, languageEchoMs(249L))
    }

    @Test
    fun `a language on screen for the seen threshold echoes nothing`() {
        assertEquals(0L, languageEchoMs(250L))
        assertEquals(0L, languageEchoMs(2_000L))
    }

    @Test
    fun `a clock that runs backwards counts as unseen`() {
        assertEquals(500L, languageEchoMs(-5L))
    }

    @Test
    fun `the setting sets the whole time on screen`() {
        assertEquals(1_000L, languageEchoMs(0L, 1_000L))
        assertEquals(800L, languageEchoMs(200L, 1_000L))
        assertEquals(0L, languageEchoMs(250L, 1_000L))
    }

    @Test
    fun `off, or shorter than the preview already ran, echoes nothing`() {
        assertEquals(0L, languageEchoMs(0L, 0L))
        assertEquals(0L, languageEchoMs(150L, 100L))
    }
}
