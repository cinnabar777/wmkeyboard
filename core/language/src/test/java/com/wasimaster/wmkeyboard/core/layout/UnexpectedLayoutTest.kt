package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reading an Unexpected Keyboard (and CleverKeys) layout.
 *
 * The fixtures are hand-written in the published format, not copied from
 * either project's layouts. A folder of real ones (the project's
 * `srcs/layouts`, its community repository) is swept only when
 * `UNEXPECTED_LAYOUTS` points at it.
 */
class UnexpectedLayoutTest {

    // ---- fixtures ----

    private fun converted(text: String, name: String = "pasted.xml"): ConvertedLayout =
        checkNotNull(UnexpectedLayouts.convert(text, name)) { "did not convert" }

    private fun ConvertedLayout.letters(): LayerSpec = checkNotNull(layout.layers[LayoutLayer.LETTERS.key])
    private fun ConvertedLayout.keys(): List<Key> = letters().rows.flatten()
    private fun ConvertedLayout.key(label: String): Key = keys().first { it.label == label }

    /** [key] alone on a first row, so the frame added to the last row leaves it first. */
    private fun single(key: String): Key =
        converted("""<keyboard><row>$key</row><row><key c="z"/></row></keyboard>""").keys().first()

    private val qwerty = """
        <?xml version="1.0" encoding="utf-8"?>
        <!-- A comment with a <key c="x"/> in it, which is not a key. -->
        <keyboard name="Mine (US)" script="latin">
          <row>
            <key c="q" ne="1" se="loc esc"/>
            <key c="w" nw="~" ne="2" sw="@"/>
            <key c="e" ne="3"/>
            <key c="r"/><key c="t"/><key c="y"/><key c="u"/><key c="i"/><key c="o"/><key c="p"/>
          </row>
          <row>
            <key shift="0.5" c="a" ne="`"/>
            <key c="s"/><key c="d"/><key c="f"/><key c="g"/><key c="h"/><key c="j"/><key c="k"/>
            <key c="l" ne="|" sw="\\"/>
          </row>
          <row>
            <key width="1.5" c="shift" ne="loc capslock"/>
            <key c="z"/><key c="x"/><key c="c"/><key c="v"/><key c="b"/><key c="n" ne=":" sw=";"/>
            <key c="m" ne="&quot;" sw="'"/>
            <key width="1.5" c="backspace" ne="delete"/>
          </row>
          <modmap>
            <shift a="i" b="İ"/>
          </modmap>
        </keyboard>
    """.trimIndent()

    // ---- recognising it ----

    @Test
    fun `a layout is recognised and the other formats are not`() {
        assertTrue(UnexpectedLayouts.looksLikeUnexpected(qwerty))
        assertFalse(UnexpectedLayouts.looksLikeUnexpected("""[[{"label":"a"}]]"""))
        assertFalse(UnexpectedLayouts.looksLikeUnexpected("name: x\nrows:\n  - letters: abc"))
        // AnySoftKeyboard's preferences are XML with neither element.
        assertFalse(UnexpectedLayouts.looksLikeUnexpected("""<map><pref><value auto_caps="true"/></pref></map>"""))
        // A bottom row on its own is not a layout.
        assertNull(UnexpectedLayouts.convert("""<row><key c="space"/></row>""", "x"))
    }

    @Test
    fun `a doctype is refused`() {
        val text = "<!DOCTYPE keyboard [<!ENTITY x \"y\">]>\n" + qwerty.substringAfter("-->")
        assertNull(UnexpectedLayouts.convert(text, "x"))
    }

    // ---- the grid ----

    @Test
    fun `the name comes from the file and the frame is added`() {
        val converted = converted(qwerty)
        assertEquals("Mine (US)", converted.layout.name)
        assertEquals(ForeignSource.UNEXPECTED, converted.source)
        val rows = converted.letters().rows
        assertEquals("three letter rows and this keyboard's bottom row", 4, rows.size)
        assertTrue(rows.last().any { it.action == KeyAction.Space })
        // The file placed its own shift and delete, so neither is added twice.
        assertEquals(1, rows.flatten().count { it.action == KeyAction.Shift })
        assertEquals(1, rows.flatten().count { it.action == KeyAction.Delete })
        assertFalse(converted.letters().bottomRowAsLaidOut)
    }

    @Test
    fun `corner swipes become flick arms and are held too`() {
        val w = converted(qwerty).key("w")
        assertEquals("~", w.flick[FlickDirection.UP_LEFT])
        assertEquals("2", w.flick[FlickDirection.UP_RIGHT])
        assertEquals("@", w.flick[FlickDirection.DOWN_LEFT])
        assertEquals(listOf("~", "2", "@"), w.longPress)
        assertTrue("the arms are drawn on the face, so no corner hint", w.hideHint)
    }

    @Test
    fun `escapes and entities are read`() {
        val converted = converted(qwerty)
        assertEquals("\\", converted.key("l").flick[FlickDirection.DOWN_LEFT])
        assertEquals("\"", converted.key("m").flick[FlickDirection.UP_RIGHT])
        // A bare colon does not parse as a key value there, so it is text.
        assertEquals(":", converted.key("n").flick[FlickDirection.UP_RIGHT])
    }

    @Test
    fun `a loc slot is skipped without a note and a lost swipe is named`() {
        val converted = converted(qwerty)
        assertNull(converted.key("q").flick[FlickDirection.DOWN_RIGHT])
        // The delete key's own swipe is taken here, so ne="delete" is the one loss.
        assertEquals(1, converted.notes.size)
        assertEquals(R.plurals.core_lang_foreign_gestures_dropped, converted.notes.single().pluralsRes)
        assertEquals(listOf<Any>(1), converted.notes.single().args)
    }

    @Test
    fun `the shift modmap becomes a shift label`() {
        assertEquals("İ", converted(qwerty).key("i").shiftLabel)
    }

    @Test
    fun `a row that starts with a gap stays left-aligned`() {
        val home = converted(qwerty).letters().rows[1]
        assertEquals(KeyAction.None, home.first().action)
        assertEquals(KeyAction.None, home.last().action)
        assertEquals(home.first().width, home.last().width)
        assertEquals("a", home[1].label)
    }

    @Test
    fun `the numbered swipe names point the same way as the compass ones`() {
        val key = single("""<key key0="o" key1="1" key2="2" key3="3" key4="4" key5="5" key6="6" key7="7" key8="8"/>""")
        assertEquals(
            mapOf(
                FlickDirection.UP_LEFT to "1", FlickDirection.UP_RIGHT to "2",
                FlickDirection.DOWN_LEFT to "3", FlickDirection.DOWN_RIGHT to "4",
                FlickDirection.LEFT to "5", FlickDirection.RIGHT to "6",
                FlickDirection.UP to "7", FlickDirection.DOWN to "8",
            ),
            key.flick,
        )
    }

    @Test
    fun `a file with its own bottom row keeps it`() {
        val converted = converted(
            """
            <keyboard bottom_row="false">
              <row><key c="a"/><key c="b"/><key c="backspace"/></row>
              <row><key c="switch_numeric"/><key width="3" c="space" w="cursor_left"/><key c="enter"/></row>
            </keyboard>
            """.trimIndent(),
        )
        val letters = converted.letters()
        assertTrue(letters.bottomRowAsLaidOut)
        assertEquals(
            listOf(KeyAction.Symbols, KeyAction.Space, KeyAction.Enter),
            letters.rows.last().map { it.action },
        )
    }

    @Test
    fun `an embedded number row becomes the layout's own`() {
        val converted = converted(
            """
            <keyboard embedded_number_row="true">
              <row><key c="1"/><key c="2"/><key c="3"/></row>
              <row><key c="a"/><key c="b"/><key c="c"/></row>
            </keyboard>
            """.trimIndent(),
        )
        assertEquals(listOf("1", "2", "3"), converted.letters().numberRow?.map { it.label })
        assertTrue(converted.keys().none { it.label == "1" })
    }

    @Test
    fun `a CleverKeys language seeds the language`() {
        val text = """<keyboard language="de"><row><key c="a"/><key c="b"/></row></keyboard>"""
        assertEquals("de", converted(text).guessedLangId)
        // The caller's binding wins over the file's.
        assertEquals("fr", UnexpectedLayouts.convert(text, "x", languageTag = "fr")?.guessedLangId)
    }

    // ---- key values ----

    @Test
    fun `named keys become actions`() {
        assertEquals(KeyAction.Symbols, single("""<key c="switch_numeric"/>""").action)
        assertEquals(KeyAction.ForwardDelete, single("""<key c="delete"/>""").action)
        assertEquals(KeyAction.Edit(TextEditAction.COPY), single("""<key c="copy"/>""").action)
        assertEquals(KeyAction.SendKey(131 + 4), single("""<key c="f5"/>""").action)
        assertEquals("F5", single("""<key c="f5"/>""").label)
    }

    @Test
    fun `a legend with a quoted string types the string`() {
        val key = single("""<key c="http:'https://'"/>""")
        assertEquals("http", key.label)
        assertEquals("https://", key.output)
    }

    @Test
    fun `a key event and a shortcut macro become key presses`() {
        val play = single("""<key c="⏯:keyevent:85"/>""")
        assertEquals(KeyAction.SendKey(85), play.action)
        assertEquals("⏯", play.label)
        val undo = single("""<key c="Cz:ctrl,z"/>""")
        assertEquals(KeyAction.SendKey(54, 0x1000), undo.action)
        assertEquals("Cz", undo.label)
    }

    @Test
    fun `a macro of text is one run of text`() {
        assertEquals("th", single("""<key c="TH:t,h"/>""").output)
    }

    @Test
    fun `an accent key is a dead key`() {
        val key = single("""<key c="accent_aigu"/>""")
        assertEquals("\u0301", key.output)
        assertEquals("´", key.label)
    }

    @Test
    fun `the old escapes are read`() {
        assertEquals("?", single("""<key c="\?"/>""").label)
        assertEquals("#", single("""<key c="\#"/>""").label)
    }

    @Test
    fun `a named key with no equivalent is removed and named`() {
        val converted = converted("""<keyboard><row><key c="a" ne="compose"/><key c="b"/></row></keyboard>""")
        assertTrue(converted.key("a").flickActions.isEmpty())
        assertTrue(converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_labels_dropped })
    }

    @Test
    fun `a key with swipes and no tap takes its first swipe as the tap`() {
        val key = single("""<key nw="esc" sw="config"/>""")
        assertEquals(KeyAction.SendKey(111), key.action)
    }

    // ---- the corpus ----

    @Test
    fun `every layout in a real corpus converts and repairs cleanly`() {
        // Takes a folder of XML files from `UNEXPECTED_LAYOUTS` and skips
        // silently when it is unset, the way the other sweeps do.
        val folder = System.getenv("UNEXPECTED_LAYOUTS")?.let(::File) ?: return
        val files = folder.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        var layouts = 0
        val failures = mutableListOf<String>()
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (!UnexpectedLayouts.looksLikeUnexpected(text)) continue
            val converted = UnexpectedLayouts.convert(text, file.name)
            if (converted == null) {
                failures += file.name
                continue
            }
            layouts++
            val again = converted.layout.repair()
            if (again.repairNotes.isNotEmpty() || !converted.layout.copy(langId = "en").canBeEnabled()) {
                failures += file.name
            }
        }
        println("Unexpected Keyboard corpus: $layouts layouts from ${files.size} files")
        assertTrue("failed: $failures", failures.isEmpty())
    }
}
