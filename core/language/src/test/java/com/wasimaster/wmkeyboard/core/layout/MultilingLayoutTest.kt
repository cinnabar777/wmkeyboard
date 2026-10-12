package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reading a layout made in Multiling O Keyboard.
 *
 * The fixtures are hand-written in the three shapes the app shares layouts in,
 * not copied from anybody's published layout. A folder of real ones (pasted
 * DIY layouts, `OK_Layout_Begin` blocks, settings exports) is swept only when
 * `MULTILING_CORPUS` points at it.
 */
class MultilingLayoutTest {

    // ---- fixtures ----

    private fun converted(text: String, name: String = "pasted.txt"): ConvertedLayout =
        checkNotNull(MultilingLayouts.convert(text, name)) { "did not convert" }

    private fun ConvertedLayout.page(layer: String): LayerSpec = checkNotNull(layout.layers[layer]) { "no $layer page" }
    private fun ConvertedLayout.letters(): LayerSpec = page(LayoutLayer.LETTERS.key)
    private fun ConvertedLayout.keys(): List<Key> = letters().rows.flatten()
    private fun ConvertedLayout.key(label: String): Key = keys().first { it.label == label }

    /** A DIY layout: the whole keyboard, bottom row included. */
    private val diy = """
        {
        "title":"Pad",
        "onScreen":{
        "main":[
        "[4D:a[LEFT]b c][XK:eèé]q[MT:xyz]",
        "[SHIFT]w[MC:th:Th][DEL]",
        "[TOOL][ALTGR:,][SPACE][][SYM:.][ENTER]"
        ],
        "shifted":[
        "[4D:A[LEFT]B C][XK:EÈÉ]Q[MT:XYZ]",
        "[SHIFT]W[MC:TH:TH][DEL]",
        "[TOOL][ALTGR:,][SPACE][][SYM:.][ENTER]"
        ],
        "sym":[
        "1234",
        "[SHIFT]@#[DEL]",
        "[SYM][SPACE][ENTER]"
        ],
        "altGr":[
        "ñß",
        "[ALTGR][SPACE][ENTER]"
        ]
        }
        }
    """.trimIndent()

    /** A DIY+ layout: digits and three letter rows, then the same shifted, then symbols. */
    private val diyPlus = """
        OK_Layout_Begin
        Name:Plus
        1234567890
        qwertyuiop
        asdfghjkl
        zxcvbnm
        !@#${'$'}%^&*()



        []()<>
        +-=/
        ~`|\
        ?!;:
        ñáéíóú



        OK_Layout_End
    """.trimIndent()

    // ---- recognising it ----

    @Test
    fun `all three wrappers are recognised and nothing else is`() {
        assertTrue(MultilingLayouts.looksLikeMultiling(diy))
        assertTrue(MultilingLayouts.looksLikeMultiling(diyPlus))
        assertTrue(MultilingLayouts.looksLikeMultiling(settingsExport()))
        // A link with the JSON percent-encoded in it.
        assertTrue(MultilingLayouts.looksLikeMultiling("http://x/le.html?%7B%22title%22:%22a%22,%22onScreen%22:%7B%7D%7D"))
        // The formats the other readers take.
        assertFalse(MultilingLayouts.looksLikeMultiling("""[[{"label":"a"}]]"""))
        assertFalse(MultilingLayouts.looksLikeMultiling("q w e\n\na s d"))
        // A page that only talks about the markers.
        assertFalse(MultilingLayouts.looksLikeMultiling("Copy from OK_Layout_Begin to OK_Layout_End."))
        assertTrue(MultilingLayouts.convertAll("Copy from OK_Layout_Begin to OK_Layout_End.", "x").isEmpty())
    }

    // ---- DIY ----

    @Test
    fun `a DIY layout keeps its title and its frame`() {
        val converted = converted(diy)
        assertEquals("Pad", converted.layout.name)
        assertEquals(ForeignSource.MULTILING, converted.source)
        val letters = converted.letters()
        assertEquals(3, letters.rows.size)
        assertTrue("the file's own bottom row stays as it was", letters.bottomRowAsLaidOut)
        assertEquals(
            listOf(KeyAction.Symbols, KeyAction.Text, KeyAction.Space, KeyAction.Text, KeyAction.Enter),
            letters.rows.last().map { it.action },
        )
    }

    @Test
    fun `an empty bracket after a key widens it`() {
        val space = converted(diy).keys().first { it.action == KeyAction.Space }
        assertEquals(2f, space.width)
    }

    @Test
    fun `a 4D key types its tap and swipes to the rest`() {
        val key = converted(diy).key("a")
        assertEquals(KeyAction.Edit(TextEditAction.LEFT), key.flickActions[FlickDirection.LEFT]?.action)
        assertEquals("b", key.flick[FlickDirection.UP])
        // A space is an empty arm, and the arm after it still lands right.
        assertNull(key.flick[FlickDirection.RIGHT])
        assertEquals("c", key.flick[FlickDirection.DOWN])
    }

    @Test
    fun `an XK key holds its other letters`() {
        assertEquals(listOf("è", "é"), converted(diy).key("e").longPress)
    }

    @Test
    fun `a multi-tap key cycles its letters`() {
        val key = converted(diy).key("xyz")
        assertEquals("x", key.output)
        assertEquals(listOf("y", "z"), key.multitap)
    }

    @Test
    fun `MC types its text under its own label`() {
        val key = converted(diy).key("Th")
        assertEquals("th", key.output)
    }

    @Test
    fun `the shifted page becomes shift labels only where it is not plain capitals`() {
        val converted = converted(diy)
        // q over Q is what shift does anyway.
        assertNull(converted.key("q").shiftLabel)
        // TH is what shift makes of th as well.
        assertNull(converted.key("Th").shiftLabel)
    }

    @Test
    fun `the page keys type on a tap and open their page on a swipe`() {
        val converted = converted(diy)
        val comma = converted.key(",")
        assertEquals(KeyAction.LayerSwitch("altgr"), comma.flickActions[FlickDirection.UP]?.action)
        assertEquals(KeyAction.LayerSwitch("altgr"), comma.actionAlternates.single().action)
        val period = converted.key(".")
        assertEquals(KeyAction.Symbols, period.flickActions[FlickDirection.DOWN]?.action)
    }

    @Test
    fun `the tool key is the symbols key when the letters have no other`() {
        assertEquals(KeyAction.Symbols, converted(diy).letters().rows.last().first().action)
    }

    @Test
    fun `the symbols and AltGr pages come across`() {
        val converted = converted(diy)
        val symbols = converted.page(LayoutLayer.SYMBOLS.key)
        // [SYM] on the symbols page goes back to the letters.
        assertTrue(symbols.rows.flatten().any { it.action == KeyAction.Letters })
        // [SHIFT] there is the way to the second symbols page.
        assertTrue(symbols.rows.flatten().any { it.action == KeyAction.Symbols })
        val altGr = converted.page("altgr")
        assertEquals(listOf("ñ", "ß"), altGr.rows.first().map { it.label })
        // [ALTGR] on its own page goes back too.
        assertTrue(altGr.rows.flatten().any { it.action == KeyAction.Letters })
    }

    @Test
    fun `a percent-encoded DIY layout reads the same`() {
        val encoded = diy.replace("\n", "%0A").replace("\"", "%22").replace("{", "%7B")
        assertEquals(converted(diy).keys().map { it.label }, converted(encoded).keys().map { it.label })
    }

    // ---- DIY+ ----

    @Test
    fun `a DIY+ layout gets the house frame and keeps its digits as the number row`() {
        val converted = converted(diyPlus)
        assertEquals("Plus", converted.layout.name)
        val letters = converted.letters()
        assertEquals("1234567890", letters.numberRow.orEmpty().joinToString("") { it.label })
        // The digits' shifted forms are what the shifted block says.
        assertEquals("!", letters.numberRow.orEmpty().first().shiftLabel)
        // Three letter rows, then the house bottom row with its own ?123 and 🌐.
        assertEquals(4, letters.rows.size)
        assertTrue(letters.rows.flatten().any { it.action == KeyAction.Shift })
        assertTrue(letters.rows.flatten().any { it.action == KeyAction.Delete })
        assertTrue(letters.rows.last().any { it.action == KeyAction.LanguageSwitch })
        assertFalse(letters.bottomRowAsLaidOut)
    }

    @Test
    fun `brackets that are not key names are keys of their own`() {
        val symbols = converted(diyPlus).page(LayoutLayer.SYMBOLS.key)
        val firstOwnRow = symbols.rows.first { row -> row.any { it.label == "(" } }
        assertEquals(listOf("[", "]", "(", ")", "<", ">"), firstOwnRow.map { it.label })
    }

    @Test
    fun `a DIY+ symbols page gets digits, a delete key and a bottom row`() {
        val symbols = converted(diyPlus).page(LayoutLayer.SYMBOLS.key).rows
        assertTrue(symbols.first().all { key -> key.label.all { it.isDigit() } })
        assertTrue(symbols.flatten().any { it.action == KeyAction.Delete })
        assertTrue(symbols.last().any { it.action == KeyAction.Letters })
    }

    @Test
    fun `the AltGr block is a page reached from the comma`() {
        val converted = converted(diyPlus)
        assertEquals(listOf("ñ", "á", "é", "í", "ó", "ú"), converted.page("altgr").rows.first().map { it.label })
        val comma = converted.letters().rows.last().first { it.label == "," }
        assertEquals(KeyAction.LayerSwitch("altgr"), comma.flickActions[FlickDirection.LEFT]?.action)
    }

    // ---- settings exports ----

    private fun settingsExport(): String {
        val body = "1234567890%0Aqwertz%0Aasdf%0Ayxcv"
        return """
            OK_Settings_Begin
            __V.co0::ff000000::X
            __DIYs.de::Mein%20QWERTZ%0APad::S
            __DIYs..Lde::Mein%20QWERTZ::S
            __Mein QWERTZde::$body::S
            __Padde::${diy.replace("\n", "%0A")}::S
            __Mein QWERTZ.Lde::$body::S
            OK_Settings_End
        """.trimIndent()
    }

    @Test
    fun `a settings export carries every layout with its language`() {
        val all = MultilingLayouts.convertAll(settingsExport(), "backup.txt")
        assertEquals(listOf("Mein QWERTZ", "Pad"), all.map { it.layout.name })
        assertTrue(all.all { it.guessedLangId == "de" })
    }

    // ---- what is dropped ----

    @Test
    fun `a key this keyboard has nothing for is dropped and named`() {
        val converted = converted("""{"title":"x","onScreen":{"main":["a[INFO]b","[DEL][SPACE][ENTER]"]}}""")
        assertTrue(converted.keys().none { it.label.contains("INFO") })
        assertTrue(converted.notes.isNotEmpty())
    }

    @Test
    fun `a swipe on the delete key is dropped and counted`() {
        val converted = converted("""{"title":"x","onScreen":{"main":["a[4D:[DEL][WDEL]]","[SPACE][ENTER]"]}}""")
        val delete = converted.keys().first { it.action == KeyAction.Delete }
        assertTrue(delete.flickActions.isEmpty())
        assertTrue(converted.notes.isNotEmpty())
    }

    // ---- real files ----

    @Test
    fun `every layout in a real corpus converts and repairs cleanly`() {
        // Takes a folder of text files from `MULTILING_CORPUS` and skips
        // silently when it is unset, the way the FUTO and Keys Cafe sweeps do.
        val folder = System.getenv("MULTILING_CORPUS")?.let(::File) ?: return
        val files = folder.walkTopDown().filter { it.isFile && it.length() < 1_000_000 }.toList()
        var layouts = 0
        val failures = mutableListOf<String>()
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (!MultilingLayouts.looksLikeMultiling(text)) continue
            val all = MultilingLayouts.convertAll(text, file.name)
            if (all.isEmpty()) failures += file.name
            for (converted in all) {
                layouts++
                // Already repaired, so a second pass must find nothing to do.
                val again = converted.layout.repair()
                if (again.repairNotes.isNotEmpty() || !converted.layout.copy(langId = "en").canBeEnabled()) {
                    failures += "${file.name}: ${converted.layout.name}"
                }
            }
        }
        println("Multiling corpus: $layouts layouts from ${files.size} files")
        assertTrue("failed: $failures", failures.isEmpty())
    }
}
