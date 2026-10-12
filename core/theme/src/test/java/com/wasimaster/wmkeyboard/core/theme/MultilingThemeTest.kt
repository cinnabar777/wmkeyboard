package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a Multiling O Keyboard theme.
 *
 * The fixtures are hand-written settings blocks in the shape the app exports,
 * not themes copied from anybody's collection.
 */
class MultilingThemeTest {

    private fun block(vararg lines: String) =
        (listOf("OK_Settings_Begin") + lines + "OK_Settings_End").joinToString("\n")

    private fun converted(text: String): MultilingThemeResult.Converted {
        val result = MultilingTheme.read(text)
        assertTrue("expected a theme, got $result", result is MultilingThemeResult.Converted)
        return result as MultilingThemeResult.Converted
    }

    private val dark = block(
        "__V.co0::ff101010::X",
        "__V.co1::ff303030::X",
        "__V.co2::ffeeeeee::X",
        "__V.co3::ff202020::X",
        "__V.co4::ffaaaaaa::X",
        "__V.co5::ff3366ff::X",
        "__V.co6::0::X",
        "__V.co7::0::X",
        "__V.co10::a0000000::X",
        "__V.co11::ffffffff::X",
        "__V.co12::26000000::X",
        "__V.co13::0::X",
        "__V.co14::ff888888::X",
        "__V.co16::ff404040::X",
        "__V.co17::ffffff00::X",
        "__K.rx::10.0::F",
        "__K.fs::90::I",
        "__V.FB::true::B",
    )

    @Test
    fun `the colour screen's slots land on their fields`() {
        val theme = converted(dark).theme
        assertTrue(theme.dark)
        assertEquals(0xFF101010, theme.boardBackground)
        assertEquals(0xFF303030, theme.keyBackground)
        assertEquals(0xFFEEEEEE, theme.keyText)
        assertEquals(0xFF202020, theme.modifierKeyBackground)
        assertEquals(0xFFAAAAAA, theme.modifierKeyText)
        assertEquals(0xFF202020, theme.enterKeyBackground)
        assertEquals(0xFF3366FF, theme.pressedKeyBackground)
        assertEquals(0xA0000000, theme.popupBackground)
        assertEquals(0xFFFFFFFF, theme.popupText)
        assertEquals(0x26000000L, theme.suggestionBarBackground)
        assertEquals(0xFF888888, theme.hintText)
        assertEquals(0xFF404040, theme.keyOverrides["SPACE"]?.background)
        assertEquals(0xFFFFFF00, theme.keyOverrides["SPACE"]?.text)
    }

    @Test
    fun `a zero is no colour, not black`() {
        val theme = converted(dark).theme
        // Suggestion bar text 0 leaves the field to be worked out.
        assertNull(theme.suggestionText)
        // Key shadow 0 draws no shadow.
        assertNull(theme.keyShadowColor)
        assertEquals(0f, theme.keyElevationDp)
    }

    @Test
    fun `radius, label size and bold come across`() {
        val theme = converted(dark).theme
        assertEquals(7, theme.keyCornerRadiusDp)
        assertEquals(0.9f, theme.fontScale)
        assertEquals(true, theme.boldKeyLabels)
    }

    @Test
    fun `a light board is a light theme`() {
        assertFalse(converted(block("__V.co0::fff0f0f0::X", "__V.co1::ffffffff::X")).theme.dark)
    }

    @Test
    fun `a share link reads like the block`() {
        val link = "http://example.invalid/e.html?OK_Settings_Begin%0A__V.co0::ff636363::X%0A" +
            "__V.co1::8e000000::X%0AOK_Settings_End&%09%09"
        val theme = converted(link).theme
        assertEquals(0xFF636363, theme.boardBackground)
        assertEquals(0x8E000000, theme.keyBackground)
    }

    @Test
    fun `what does not come across is said`() {
        val result = converted(block("__V.co0::ff000000::X", "__V.co7::ff00ff00::X", "__V.WP::true::B", "__V.co15::ff123456::X"))
        assertTrue(MultilingUnsupported.TEXT_SHADOWS in result.dropped)
        assertTrue(MultilingUnsupported.WALLPAPER in result.dropped)
    }

    @Test
    fun `text with no colours in it is not a theme`() {
        assertEquals(MultilingThemeResult.NotATheme, MultilingTheme.read("hello"))
        assertEquals(MultilingThemeResult.NotATheme, MultilingTheme.read(block("__K.fs::90::I")))
    }
}
