package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Desktop layouts as grids: what a `.klc`, a `.keylayout` and an XKB file look
 * like once they are a layout of this keyboard. The parsing itself is pinned in
 * [DesktopKeymapsTest]; this is about where the keys land.
 */
class DesktopLayoutTest {

    private fun ConvertedLayout.letters(): List<List<Key>> =
        layout.layers.getValue(LayoutLayer.LETTERS.key).rows

    private fun ConvertedLayout.key(text: String): Key =
        letters().flatten().first { (it.output ?: it.label) == text }

    @Test
    fun `a klc keeps its letter keys, and AltGr and a stray letter go on hold`() {
        val converted = DesktopLayouts.convertAll(DesktopKeymapsTest.KLC, "lttest.klc").single()
        assertEquals(ForeignSource.WINDOWS_KLC, converted.source)
        assertEquals("Lithuanian Test", converted.layout.name)
        assertEquals("lt", converted.guessedLangId)
        // AltGr first, then ą from the number row, which has too few letters
        // to be a row of its own and is built on a.
        assertEquals(listOf("á", "ą"), converted.key("a").longPress)
        assertEquals(listOf("ž"), converted.key("z").longPress)
        assertTrue(converted.letters().flatten().any { it.action == KeyAction.Shift })
        assertTrue(converted.letters().flatten().any { it.action == KeyAction.Space })
    }

    @Test
    fun `a keylayout puts Option on hold and spreads its dead key over the letters`() {
        val converted = DesktopLayouts.convertAll(DesktopKeymapsTest.KEYLAYOUT, "test.keylayout").single()
        assertEquals(ForeignSource.MAC_KEYLAYOUT, converted.source)
        assertEquals("Test Mac", converted.layout.name)
        val a = converted.key("a")
        assertEquals("å", a.longPress.first())
        assertTrue("the dead acute's á is on a", "á" in a.longPress)
        assertTrue("and its é on e", "é" in converted.key("e").longPress)
    }

    @Test
    fun `an xkb file gives a layout per variant, built on us(basic)`() {
        val layouts = DesktopLayouts.convertAll(DesktopKeymapsTest.XKB, "test")
        assertEquals(listOf("Testish", "Testish (extra)"), layouts.map { it.layout.name })
        val basic = layouts[0]
        assertEquals(ForeignSource.XKB_SYMBOLS, basic.source)
        val rows = basic.letters()
        assertEquals("qwertyuiop", rows[0].joinToString("") { it.output ?: it.label })
        // ö is a letter beside l, so it keeps its place at the end of the row.
        assertEquals("ö", rows[1].last().label)
        val a = basic.key("a")
        assertEquals("ā", a.longPress.first())
        assertTrue("the dead acute spreads onto a", "á" in a.longPress)
        assertTrue("the dead diaeresis too", "ä" in a.longPress)
        assertTrue("and onto e", "é" in basic.key("e").longPress)
        // The dead keys' own key is punctuation's place, so it is not drawn.
        assertFalse(rows.flatten().any { it.output == "́" })
        assertTrue(basic.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_desktop_unknown_symbols })
        assertTrue(basic.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_desktop_dead_keys })
        assertTrue(layouts[1].notes.any { it.pluralsRes == R.plurals.core_lang_foreign_desktop_includes_missing })
    }

    @Test
    fun `a number row of letters becomes a row of the grid`() {
        val czechish = """
            |default xkb_symbols "basic" {
            |    include "us(basic)"
            |    name[Group1] = "Czechish";
            |    key <AE01> { [ ecaron, 1 ] };
            |    key <AE02> { [ scaron, 2 ] };
            |    key <AE03> { [ ccaron, 3 ] };
            |    key <AE04> { [ rcaron, 4 ] };
            |    key <AE05> { [ zcaron, 5 ] };
            |};
        """.trimMargin()
        val converted = DesktopLayouts.convertAll(czechish, "cz").single()
        val top = converted.letters().first()
        assertEquals(listOf("ě", "š", "č", "ř", "ž"), top.map { it.label })
        assertEquals("1", top.first().shiftLabel)
        // The punctuation of the number row stays on the symbol pages.
        assertFalse(top.any { it.label == "-" })
    }

    @Test
    fun `a script's own punctuation goes on the full stop rather than nowhere`() {
        val armenianish = """
            |default xkb_symbols "basic" {
            |    include "us(basic)"
            |    key <AB08> { [ U055D, comma ] };
            |};
        """.trimMargin()
        val converted = DesktopLayouts.convertAll(armenianish, "am").single()
        val period = converted.letters().last().first { it.role == KeyRole.Period }
        assertTrue("՝" in period.longPress)
        assertTrue(converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_desktop_symbols_on_period })
    }

    @Test
    fun `text that is no desktop layout converts to nothing`() {
        assertFalse(DesktopLayouts.looksLikeDesktopLayout("""{"rows": [["a"]]}"""))
        assertTrue(DesktopLayouts.convertAll("hello", "x.txt").isEmpty())
    }

    /**
     * Every file in a folder of real layouts converts, and repairs to a fixed
     * point. Skips unless `DESKTOP_LAYOUTS` names that folder: the files are
     * other people's and are not committed here. The `.keylayout` files under
     * `/System/Library/Input Methods` on a Mac, a checkout of xkeyboard-config's
     * `symbols/` and any `.klc` collection all work.
     */
    @Test
    fun `every desktop layout in the corpus converts`() {
        val dir = System.getenv("DESKTOP_LAYOUTS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val files = dir!!.walkTopDown().filter { it.isFile && it.length() < MAX_CORPUS_FILE }.toList()
        var read = 0
        val failures = mutableListOf<String>()
        for (file in files) {
            val text = DesktopLayouts.decodeText(file.readBytes())
            if (!DesktopLayouts.looksLikeDesktopLayout(text)) continue
            read++
            val layouts = DesktopLayouts.convertAll(text, file.name)
            for (converted in layouts) {
                if (converted.layout.repair().repairNotes.isNotEmpty()) failures += "${file.name}: not a fixed point"
            }
        }
        println("desktop corpus: $read files read")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        const val MAX_CORPUS_FILE = 4L * 1024 * 1024
    }
}
