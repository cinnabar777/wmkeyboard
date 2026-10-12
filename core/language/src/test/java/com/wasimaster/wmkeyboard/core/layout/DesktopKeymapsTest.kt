package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three desktop readers, below the grid: what each one makes of a file.
 * The fixtures are hand-written, each small enough to read, and each uses the
 * parts of its format that are easy to get wrong.
 */
class DesktopKeymapsTest {

    private fun DesktopKeymap.text(pos: DesktopKeyPos, level: Int): String? =
        (keys[pos]?.getOrNull(level) as? DesktopOutput.Text)?.text

    private fun DesktopKeymap.dead(pos: DesktopKeyPos, level: Int): DesktopOutput.Dead? =
        keys[pos]?.getOrNull(level) as? DesktopOutput.Dead

    // ---- .klc ----

    @Test
    fun `a klc file reads its levels, ligatures and dead keys`() {
        assertTrue(KlcReader.looksLikeKlc(KLC))
        val map = requireNotNull(KlcReader.read(KLC))
        assertEquals("Lithuanian Test", map.name)
        assertEquals("lt-LT", map.languageTag)
        assertEquals("ą", map.text(DesktopKeyPos.AE01, LEVEL_BASE))
        assertEquals("Ą", map.text(DesktopKeyPos.AE01, LEVEL_SHIFT))
        // Column 6 is Ctrl+Alt, which is AltGr.
        assertEquals("1", map.text(DesktopKeyPos.AE01, LEVEL_ALTGR))
        assertEquals("á", map.text(DesktopKeyPos.AC01, LEVEL_ALTGR))
        assertEquals("Á", map.text(DesktopKeyPos.AC01, LEVEL_SHIFT_ALTGR))
        // %% is a run of characters from the LIGATURE table.
        assertEquals("ž", map.text(DesktopKeyPos.AB01, LEVEL_ALTGR))
        val dead = requireNotNull(map.dead(DesktopKeyPos.AC11, LEVEL_BASE))
        assertEquals("á", dead.compose("a"))
        assertEquals("é", dead.compose("e"))
        assertNull(dead.compose("q"))
        assertEquals('́', dead.mark)
        assertEquals("\"", map.text(DesktopKeyPos.AC11, LEVEL_SHIFT))
    }

    @Test
    fun `a klc saved as UTF-16 the way MSKLC saves it still reads`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + KLC.toByteArray(Charsets.UTF_16LE)
        val text = decodeDesktopText(bytes)
        assertEquals(KLC, text)
        requireNotNull(KlcReader.read(text))
        // And with no byte order mark at all.
        assertEquals(KLC, decodeDesktopText(KLC.toByteArray(Charsets.UTF_16LE)))
        assertEquals("plain", decodeDesktopText("plain".toByteArray()))
    }

    @Test
    fun `a semicolon inside the quoted name is not a comment`() {
        val map = requireNotNull(KlcReader.read(KLC.replace("\"Lithuanian Test\"", "\"Lithuanian; Test\"")))
        assertEquals("Lithuanian; Test", map.name)
    }

    // ---- .keylayout ----

    @Test
    fun `a keylayout reads its maps by modifier and its dead key table`() {
        assertTrue(MacKeylayoutReader.looksLikeKeylayout(KEYLAYOUT))
        val map = requireNotNull(MacKeylayoutReader.read(KEYLAYOUT))
        assertEquals("Test Mac", map.name)
        assertEquals("a", map.text(DesktopKeyPos.AC01, LEVEL_BASE))
        assertEquals("A", map.text(DesktopKeyPos.AC01, LEVEL_SHIFT))
        assertEquals("å", map.text(DesktopKeyPos.AC01, LEVEL_ALTGR))
        assertEquals("Å", map.text(DesktopKeyPos.AC01, LEVEL_SHIFT_ALTGR))
        // An entity in the attribute, and a key map that builds on another.
        assertEquals("<", map.text(DesktopKeyPos.AD01, LEVEL_ALTGR))
        assertEquals("<", map.text(DesktopKeyPos.AD01, LEVEL_SHIFT_ALTGR))
        val dead = requireNotNull(map.dead(DesktopKeyPos.AC11, LEVEL_BASE))
        assertEquals("á", dead.compose("a"))
        assertEquals("é", dead.compose("e"))
        assertEquals("´", dead.standalone)
        assertEquals('́', dead.mark)
        // Return is not one of the grid's keys, and a control character is
        // never something a key types.
        assertFalse(map.keys.values.flatten().any { (it as? DesktopOutput.Text)?.text == "\r" })
    }

    @Test
    fun `the xml scanner takes comments, a doctype and entities in its stride`() {
        val tags = xmlTags("""<?xml version="1.1"?><!DOCTYPE k SYSTEM "x"><!-- <a b="c"/> --><k a="&#x41;&amp;&lt;" b='>'/>""")
            .toList()
        assertEquals(1, tags.size)
        assertEquals("A&<", tags[0].attrs["a"])
        assertEquals(">", tags[0].attrs["b"])
        assertTrue(tags[0].selfClosing)
    }

    // ---- XKB ----

    @Test
    fun `an xkb file reads every variant, following includes`() {
        assertTrue(XkbSymbolsReader.looksLikeXkb(XKB))
        val variants = XkbSymbolsReader.read(XKB, "test")
        assertEquals(listOf("Testish", "Testish (extra)"), variants.map { it.name })
        val basic = variants[0]
        assertEquals("q", basic.text(DesktopKeyPos.AD01, LEVEL_BASE))
        assertEquals("ä", basic.text(DesktopKeyPos.AD01, LEVEL_ALTGR))
        assertEquals("Ä", basic.text(DesktopKeyPos.AD01, LEVEL_SHIFT_ALTGR))
        // From the built-in us(basic).
        assertEquals("d", basic.text(DesktopKeyPos.AC03, LEVEL_BASE))
        assertEquals("D", basic.text(DesktopKeyPos.AC03, LEVEL_SHIFT))
        // A key type ahead of the symbols, and symbols[Group1] = […].
        assertEquals("ā", basic.text(DesktopKeyPos.AC01, LEVEL_ALTGR))
        assertEquals("ö", basic.text(DesktopKeyPos.AC10, LEVEL_BASE))
        // Keysym names from the table, and a 0x100xxxx code point.
        assertEquals("з", basic.text(DesktopKeyPos.AB01, LEVEL_BASE))
        assertEquals("З", basic.text(DesktopKeyPos.AB01, LEVEL_SHIFT))
        assertEquals("ʒ", basic.text(DesktopKeyPos.AB01, LEVEL_ALTGR))
        assertEquals('́', basic.dead(DesktopKeyPos.AC11, LEVEL_BASE)?.mark)
        assertEquals("á", basic.dead(DesktopKeyPos.AC11, LEVEL_BASE)?.compose("a"))
        assertEquals(listOf("mystery_sym"), basic.unknownSymbols)
        assertTrue(basic.missingIncludes.isEmpty())

        val extra = variants[1]
        assertEquals("ẃ", extra.text(DesktopKeyPos.AD02, LEVEL_ALTGR))
        // Inherited from the variant it includes, in the same file.
        assertEquals("ä", extra.text(DesktopKeyPos.AD01, LEVEL_ALTGR))
        assertEquals(listOf("foo(bar)"), extra.missingIncludes)
    }

    @Test
    fun `a variant that is only a fragment is not offered as a layout`() {
        val variants = XkbSymbolsReader.read(XKB, "test")
        assertFalse(variants.any { it.name == "frag" })
    }

    @Test
    fun `the default variant comes first whatever its place in the file`() {
        val swapped = XKB.replace("default partial alphanumeric_keys", "partial alphanumeric_keys")
            .replace("partial alphanumeric_keys\nxkb_symbols \"extra\"", "default partial alphanumeric_keys\nxkb_symbols \"extra\"")
        assertEquals("Testish (extra)", XkbSymbolsReader.read(swapped, "test").first().name)
    }

    @Test
    fun `the sniffs do not take each other's files`() {
        assertFalse(KlcReader.looksLikeKlc(KEYLAYOUT))
        assertFalse(KlcReader.looksLikeKlc(XKB))
        assertFalse(MacKeylayoutReader.looksLikeKeylayout(KLC))
        assertFalse(MacKeylayoutReader.looksLikeKeylayout(XKB))
        assertFalse(XkbSymbolsReader.looksLikeXkb(KLC))
        assertFalse(XkbSymbolsReader.looksLikeXkb(KEYLAYOUT))
        assertFalse(XkbSymbolsReader.looksLikeXkb("""{"rows": [["a"]]}"""))
    }

    @Test
    fun `markOf finds the mark a table amounts to, and refuses a table that is not one`() {
        assertEquals('̈', markOf(mapOf("a" to "ä", "o" to "ö", "u" to "ü")))
        assertNull(markOf(mapOf("a" to "x", "o" to "y")))
    }

    companion object {
        val KLC = """
            |KBD	lttest	"Lithuanian Test"
            |
            |COPYRIGHT	"(c) test"
            |
            |LOCALENAME	"lt-LT"
            |
            |LOCALEID	"00000427"
            |
            |VERSION	1.0
            |
            |SHIFTSTATE
            |
            |0	//Column 4
            |1	//Column 5 : Shft
            |2	//Column 6 :       Ctrl
            |6	//Column 7 :       Ctrl Alt
            |7	//Column 8 : Shft  Ctrl Alt
            |
            |LAYOUT		;an extra '@' at the end is a dead key
            |
            |//SC	VK_		Cap	0	1	2	6	7
            |//--	----		----	----	----	----	----	----
            |
            |02	1		0	0105	0104	-1	1	-1	// ą Ą
            |10	Q		1	q	Q	-1	-1	-1
            |1e	A		1	a	A	-1	00e1	00c1
            |28	OEM_7		0	0027@	0022	-1	-1	-1
            |2c	Z		1	z	Z	-1	%%	-1
            |39	SPACE		0	0020	0020	0020	-1	-1
            |
            |LIGATURE
            |
            |//VK_	Mod#	Char0	Char1
            |Z	3	007a	030c
            |
            |DEADKEY	0027
            |
            |0061	00e1	// a -> á
            |0065	00e9	// e -> é
            |0020	0027
            |
            |KEYNAME
            |
            |01	Esc
            |
            |ENDKBD
        """.trimMargin()

        val KEYLAYOUT = """
            |<?xml version="1.1" encoding="UTF-8"?>
            |<!DOCTYPE keyboard SYSTEM "file://localhost/System/Library/DTDs/KeyboardLayout.dtd">
            |<!--Last edited by Ukelele-->
            |<keyboard group="126" id="-1234" name="Test Mac" maxout="1">
            |    <layouts>
            |        <layout first="0" last="17" mapSet="16c" modifiers="f4"/>
            |    </layouts>
            |    <modifierMap id="f4" defaultIndex="0">
            |        <keyMapSelect mapIndex="0">
            |            <modifier keys=""/>
            |        </keyMapSelect>
            |        <keyMapSelect mapIndex="1">
            |            <modifier keys="anyShift caps?"/>
            |        </keyMapSelect>
            |        <keyMapSelect mapIndex="2">
            |            <modifier keys="caps"/>
            |        </keyMapSelect>
            |        <keyMapSelect mapIndex="3">
            |            <modifier keys="anyOption"/>
            |        </keyMapSelect>
            |        <keyMapSelect mapIndex="4">
            |            <modifier keys="anyShift caps? anyOption"/>
            |        </keyMapSelect>
            |    </modifierMap>
            |    <keyMapSet id="16c">
            |        <keyMap index="0">
            |            <key code="0" action="a"/>
            |            <key code="12" output="q"/>
            |            <key code="14" action="e"/>
            |            <key code="36" output="&#x000D;"/>
            |            <key code="39" action="acute"/>
            |        </keyMap>
            |        <keyMap index="1">
            |            <key code="0" output="A"/>
            |            <key code="12" output="Q"/>
            |            <key code="14" output="E"/>
            |            <key code="39" output="&#x0022;"/>
            |        </keyMap>
            |        <keyMap index="2" baseMapSet="16c" baseIndex="1"/>
            |        <keyMap index="3">
            |            <key code="0" output="å"/>
            |            <key code="12" output="&lt;"/>
            |        </keyMap>
            |        <keyMap index="4" baseMapSet="16c" baseIndex="3">
            |            <key code="0" output="Å"/>
            |        </keyMap>
            |    </keyMapSet>
            |    <actions>
            |        <action id="a">
            |            <when state="none" output="a"/>
            |            <when state="acute" output="á"/>
            |        </action>
            |        <action id="e">
            |            <when state="none" output="e"/>
            |            <when state="acute" output="é"/>
            |        </action>
            |        <action id="acute">
            |            <when state="none" next="acute"/>
            |        </action>
            |    </actions>
            |    <terminators>
            |        <when state="acute" output="´"/>
            |    </terminators>
            |</keyboard>
        """.trimMargin()

        val XKB = """
            |// Test layout
            |default partial alphanumeric_keys
            |xkb_symbols "basic" {
            |    include "us(basic)"
            |    name[Group1]= "Testish";
            |
            |    key <AD01> { [ q, Q, adiaeresis, Adiaeresis ] };
            |    key <AC01> { type[Group1] = "FOUR_LEVEL_ALPHABETIC", [ a, A, U0101, U0100 ] };
            |    key <AC10> { symbols[Group1] = [ odiaeresis, Odiaeresis ] };
            |    key <AC11> { [ dead_acute, dead_diaeresis ] };
            |    key <AB01> { [ Cyrillic_ze, Cyrillic_ZE, 0x1000292, VoidSymbol ] };
            |    key <AB02> { [ x, X, mystery_sym ] };
            |
            |    include "level3(ralt_switch)"
            |};
            |
            |partial alphanumeric_keys
            |xkb_symbols "extra" {
            |    include "test(basic)"
            |    name[Group1]= "Testish (extra)";
            |    key <AD02> { [ w, W, U1E83 ] };
            |    include "foo(bar)"
            |};
            |
            |hidden partial modifier_keys
            |xkb_symbols "frag" {
            |    key <RALT> { [ ISO_Level3_Shift ] };
            |};
        """.trimMargin()
    }
}
