package com.wasimaster.wmkeyboard.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The text envelope Multiling O Keyboard shares its settings and layouts in. */
class MultilingExportTest {

    @Test
    fun `settings lines read as key and value, values decoded`() {
        val settings = MultilingExport.settings(
            """
            OK_Settings_Begin
            __V.co0::ff000000::X
            __K.rx::9.6::F
            __DIYs.en::My%20pad%0AOther::S
            __L.lang::[LANG:01]%20[IME]::S
            not a setting
            OK_Settings_End
            """.trimIndent(),
        )!!
        assertEquals("ff000000", settings["V.co0"])
        assertEquals("9.6", settings["K.rx"])
        assertEquals("My pad\nOther", settings["DIYs.en"])
        // A value with colons in it keeps them.
        assertEquals("[LANG:01] [IME]", settings["L.lang"])
        assertEquals(4, settings.size)
    }

    @Test
    fun `a share link decodes back into lines`() {
        val link = "http://example.invalid/e.html?OK_Settings_Begin%0A__V.co1::8e000000::X%0A__V.FB::false::B%0AOK_Settings_End&%09"
        val settings = MultilingExport.settings(link)!!
        assertEquals("8e000000", settings["V.co1"])
        assertEquals("false", settings["V.FB"])
    }

    @Test
    fun `no block is no settings`() {
        assertNull(MultilingExport.settings("just some text"))
    }

    @Test
    fun `layout blocks need their markers on lines of their own`() {
        val text = """
            Select from OK_Layout_Begin to OK_Layout_End, then copy.
            OK_Layout_Begin
            Name:One
             qwerty
            OK_Layout_End
            OK_Layout_Begin
            Name:Two
            asdf
            """.trimIndent()
        val blocks = MultilingExport.layoutBlocks(text)
        // The second runs to the end; the leading space of a row is a key.
        assertEquals(listOf("Name:One\n qwerty", "Name:Two\nasdf"), blocks)
    }

    @Test
    fun `a percent sign that starts no escape stays a percent sign`() {
        assertEquals("[4D:%  ‰]", MultilingExport.percentDecode("[4D:%%20%20‰]"))
        assertEquals("100%", MultilingExport.percentDecode("100%"))
        assertEquals("a+b", MultilingExport.percentDecode("a+b"))
        assertEquals("ক", MultilingExport.percentDecode("%E0%A6%95"))
    }
}
