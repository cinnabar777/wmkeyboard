package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A shift armed on one script does not survive onto a caseless one.
 *
 * At a sentence start the keyboard arms shift for English. Switching to Arabic
 * kept it, and on Arabic the shift layer is a second set of letters, not
 * capitals, so every switch at a sentence start opened the Arabic grid on the
 * wrong letters until the user pressed shift to get back.
 */
@RunWith(RobolectricTestRunner::class)
class ShiftAcrossScriptSwitchTest {

    private fun keyboardOn(shift: ShiftState, pressedByUser: Boolean = false): GlideKeyboard {
        val (service, _, _) = glideKeyboard(
            state = glideReadyState(
                // Haptics off: a switch buzzes through a service that never booted.
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                ),
                shiftState = shift,
            ).copy(shiftPressedByUser = pressedByUser),
        )
        val field = WMKeyboardService::class.java.getDeclaredField("settingsRepository")
        field.isAccessible = true
        field.set(service, SettingsRepository(RuntimeEnvironment.getApplication()))
        return service
    }

    @Test
    fun `an auto shift from English does not open Arabic on its shift layer`() {
        val service = keyboardOn(ShiftState.ON)

        service.onLayoutSelected(BuiltInLayouts.ARABIC_ID)

        val state = service.uiState.value
        assertEquals(ScriptId.ARABIC, state.script.id)
        assertEquals(ShiftState.OFF, state.shiftState)
        assertFalse(state.shiftPressedByUser)
    }

    @Test
    fun `caps lock does not carry onto Arabic either`() {
        val service = keyboardOn(ShiftState.CAPS_LOCK, pressedByUser = true)

        service.onLayoutSelected(BuiltInLayouts.ARABIC_ID)

        assertEquals(ShiftState.OFF, service.uiState.value.shiftState)
    }

    @Test
    fun `a shift between two cased scripts is left alone`() {
        val service = keyboardOn(ShiftState.CAPS_LOCK, pressedByUser = true)

        service.onLayoutSelected(BuiltInLayouts.RUSSIAN.id)

        val state = service.uiState.value
        assertEquals(ScriptId.CYRILLIC, state.script.id)
        assertEquals(ShiftState.CAPS_LOCK, state.shiftState)
    }

    @Test
    fun `switch to custom Thai layout`() {
        val thaiSpec = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_thai",
            name = "Thai",
            langId = "th",
        )
        val service = keyboardOn(ShiftState.ON)
        val repo = SettingsRepository(RuntimeEnvironment.getApplication())
        // Seed customLayouts
        val stateWithCustom = service.uiState.value.copy(
            settings = service.uiState.value.settings.copy(
                customLayouts = listOf(thaiSpec)
            )
        )
        val field = WMKeyboardService::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(service) as kotlinx.coroutines.flow.MutableStateFlow<KeyboardUiState>).value = stateWithCustom

        service.onLayoutSelected("custom_thai")
        val state = service.uiState.value
        println("custom layoutId: ${state.layoutId}")
        println("custom script: ${state.script.id}")
        println("custom shiftState: ${state.shiftState}")
    }
}






