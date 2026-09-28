package com.wasimaster.wmkeyboard.ime

import app.cash.turbine.test
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one-shot shift, watched through the state the keyboard publishes.
 *
 * The key grid redraws from [WMKeyboardService.uiState], so what matters is not
 * only where the shift ends up but how it gets there: one published change per
 * real change. Turbine makes every emission count, so a stray extra publish (a
 * whole board recomposing for nothing) fails here as surely as a missing one.
 */
@RunWith(RobolectricTestRunner::class)
class ShiftStateFlowTest {

    private val quiet = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false))

    @Test
    fun `a shifted letter types a capital and lets go of the shift once`() = runTest {
        val (service, editor, _) = glideKeyboard(glideReadyState(settings = quiet, shiftState = ShiftState.ON))

        service.uiState.map { it.shiftState }.distinctUntilChanged().test {
            assertEquals(ShiftState.ON, awaitItem())

            service.onKey(Key(label = "h"))
            assertEquals("the letter reached the field capitalised", "H", editor.text.toString())
            assertEquals(ShiftState.OFF, awaitItem())

            // Lower case from here on, with no further change to announce.
            service.onKey(Key(label = "i"))
            assertEquals("Hi", editor.text.toString())
            expectNoEvents()
        }
    }

    @Test
    fun `caps lock survives typing`() = runTest {
        val (service, editor, _) = glideKeyboard(glideReadyState(settings = quiet, shiftState = ShiftState.CAPS_LOCK))

        service.uiState.map { it.shiftState }.distinctUntilChanged().test {
            assertEquals(ShiftState.CAPS_LOCK, awaitItem())
            service.onKey(Key(label = "o"))
            service.onKey(Key(label = "k"))
            assertEquals("OK", editor.text.toString())
            expectNoEvents()
        }
    }
}
