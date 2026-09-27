package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.whatsapp.WaStickersFile
import com.wasimaster.wmkeyboard.core.theme.FlexTheme
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which format a file is.
 *
 * The ordering inside `WMFileTypes.identify` is a safety property, not a
 * convenience: every format it recognises carries a tag except the theme, which
 * is told apart by its file name alone and is documented as a known compromise.
 * The tests that matter here are therefore the negative ones — a file that is
 * *not* ours has to come back unrecognised rather than falling into that last
 * branch and being imported as an all-defaults theme.
 */
class FileImportSniffTest {

    // ---- text ----

    @Test
    fun `a tagged layout is a layout`() {
        val text = LayoutFile.encode(
            com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.default,
            appVersion = 1,
            appVersionName = "1",
        )
        assertTrue(WMFileTypes.textKindFor(text, "grid.wmlayout.json") is WMFileTypes.Opened.Layout)
    }

    @Test
    fun `a theme is recognised only by its name`() {
        val theme = ThemeCodec.encode(
            com.wasimaster.wmkeyboard.core.theme.ThemeSpec(id = "x", name = "X"),
        )
        assertTrue(WMFileTypes.textKindFor(theme, "x.wmtheme.json") is WMFileTypes.Opened.Theme)
        // The same bytes under another name are not a theme, because there is
        // nothing in them that says they are one.
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor(theme, "x.json"),
        )
    }

    @Test
    fun `a layout from another keyboard is never read as a theme`() {
        // The one test this file exists for. A FlorisBoard or HeliBoard layout
        // carries no tag of ours, so if it reached the theme branch under a
        // .wmtheme.json name it would decode to an all-defaults theme and the
        // user would have imported a black keyboard instead of their grid.
        val foreign = """[[{"${'$'}":"text_key","code":113,"label":"q"}]]"""
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor(foreign, "qwerty.json"),
        )
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor(foreign, "qwerty.txt"),
        )
        // Even renamed, it is refused: it decodes to a theme that sets nothing,
        // and importing that is worse than importing nothing.
        val renamed = WMFileTypes.textKindFor(foreign, "qwerty.wmtheme.json")
        assertTrue(
            "a foreign layout was read as a theme",
            renamed !is WMFileTypes.Opened.Layout,
        )
    }

    @Test
    fun `an unrelated file is unrecognised`() {
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor("""{"shopping":["milk","eggs"]}""", "list.json"),
        )
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor("hello", "notes.txt"))
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor("", "empty.json"))
    }

    // ---- the two YAML formats ----

    @Test
    fun `a FUTO layout is a layout rather than text for the editor`() {
        val yaml = "name: QWERTY\nlanguages: [fr]\nrows:\n  - letters: q w e\n  - letters: a s d"
        val opened = WMFileTypes.textKindFor(yaml, "qwerty.yaml")
        assertTrue("a FUTO layout was not recognised", opened is WMFileTypes.Opened.FutoLayout)
        val converted = (opened as WMFileTypes.Opened.FutoLayout).converted
        assertEquals("QWERTY", converted.layout.name)
        // The language the dialog's picker starts from. A blank one would be
        // migrated to English on the next read.
        assertEquals("fr", converted.guessedLangId)
    }

    @Test
    fun `an Espanso match file is snippets`() {
        val yaml = "matches:\n  - trigger: \":sig\"\n    replace: \"Wasi Master\""
        val opened = WMFileTypes.textKindFor(yaml, "base.yml")
        assertTrue("an Espanso file was not recognised", opened is WMFileTypes.Opened.EspansoSnippets)
        val parsed = (opened as WMFileTypes.Opened.EspansoSnippets).parsed
        assertEquals(1, parsed.snippets.size)
        assertTrue("an Espanso file was read as one of ours", parsed.isEspanso)
    }

    @Test
    fun `an ordinary YAML file is only text`() {
        // The whole reason the two tests above are a key at the start of a line:
        // everything else that is YAML has to keep falling through to the
        // editor. A CI config and a compose file are the common ones.
        val compose = "services:\n  web:\n    image: nginx\n    ports:\n      - 80:80\n"
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor(compose, "compose.yaml"))
        assertTrue(WMFileTypes.isEditableText(compose))
    }

    @Test
    fun `a name key alone is not a FUTO layout`() {
        // Half the YAML files ever written open with a name. Both keys are
        // wanted, and the one that means something is `rows:`.
        val yaml = "name: my-package\nversion: 1.0.0\ndependencies:\n  - thing\n"
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor(yaml, "package.yaml"))
    }

    // ---- archives ----

    @Test
    fun `an archive is told apart by the tag in its manifest`() {
        assertEquals(
            WMFileTypes.Opened.Icons,
            WMFileTypes.archiveKindFor("""{"format":"${IconPackFile.FORMAT}"}"""),
        )
        assertEquals(
            WMFileTypes.Opened.Stickers,
            WMFileTypes.archiveKindFor("""{"format":"${StickerPackFile.FORMAT}"}"""),
        )
        assertEquals(
            WMFileTypes.Opened.Plugin,
            WMFileTypes.archiveKindFor("""{"format":"${PluginFile.FORMAT}"}"""),
        )
    }

    @Test
    fun `a WhatsApp pack is told by its two text files, never by pictures alone`() {
        // The one archive format with no manifest. Its signature is the
        // title.txt / author.txt beside the pictures; a ZIP of pictures with
        // neither is any ZIP of pictures, and a pack.json makes it ours.
        assertTrue(WaStickersFile.looksLikeWaStickers(listOf("title.txt", "author.txt", "tray.png", "sticker_1.webp")))
        assertFalse(WaStickersFile.looksLikeWaStickers(listOf("tray.png", "sticker_1.webp")))
        assertFalse(WaStickersFile.looksLikeWaStickers(listOf("pack.json", "title.txt", "sticker_1.webp")))
    }

    @Test
    fun `a manifest with no tag we know is unrecognised`() {
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.archiveKindFor("{}"))
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.archiveKindFor("not json"))
    }

    @Test
    fun `a FlorisBoard extension is recognised by its tag and not its file name`() {
        assertTrue(WMFileTypes.isFlexManifest("""{"${'$'}":"${FlexTheme.FORMAT}"}"""))
        // FlorisBoard's other extension kinds use the same manifest name and
        // hold nothing this app can read, so the name alone must never be enough.
        assertFalse(WMFileTypes.isFlexManifest("""{"${'$'}":"ime.extension.keyboard"}"""))
        assertFalse(WMFileTypes.isFlexManifest("""{"${'$'}":"ime.extension.languagepack"}"""))
        assertFalse(WMFileTypes.isFlexManifest("{}"))
        // And a flex manifest is not one of the app's own archive formats.
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.archiveKindFor("""{"${'$'}":"${FlexTheme.FORMAT}"}"""),
        )
    }

    @Test
    fun `a Gboard theme and an Rboard pack are recognised by their manifests`() {
        assertTrue(WMFileTypes.isGboardManifest("""{"name":"Dusk","style_sheets":["style_sheet_md2.css"]}"""))
        assertTrue(WMFileTypes.isGboardManifest("name=Gradients\r\nauthor=RKBDI"))
        // One of this app's own pack manifests names a pack too, as JSON.
        assertFalse(WMFileTypes.isGboardManifest("""{"format":"wm-sticker-pack","name":"x"}"""))
        assertFalse(WMFileTypes.isGboardManifest("""{"${'$'}":"${FlexTheme.FORMAT}"}"""))
        assertFalse(WMFileTypes.isGboardManifest("just some text"))
    }

    @Test
    fun `a sound pack is told apart from the other pack_json formats`() {
        // Sticker, icon and sound packs all name their manifest pack.json, so
        // the format tag is the whole decision. A sound pack sent to the sticker
        // importer would be refused with a message about stickers.
        assertEquals(
            WMFileTypes.Opened.SoundPack,
            WMFileTypes.archiveKindFor("""{"format":"${SoundPackFile.FORMAT}","id":"click"}"""),
        )
        assertEquals(
            WMFileTypes.Opened.Icons,
            WMFileTypes.archiveKindFor("""{"format":"${IconPackFile.FORMAT}"}"""),
        )
    }

    // ---- text that is nobody's ----

    @Test
    fun `someone else's JSON opens in the editor rather than on an error`() {
        // The app is offered for every .json now, so this is the common case
        // rather than a mistake, and it has to end somewhere useful.
        val opened = WMFileTypes.textKindFor("""{"services":{"web":{"image":"nginx"}}}""", "compose.json")
        assertEquals(WMFileTypes.Opened.Unrecognized, opened)
        assertTrue(WMFileTypes.isEditableText("""{"services":{}}"""))
    }

    @Test
    fun `broken JSON is still text`() {
        // Half-typed or truncated: worth opening precisely because it is broken.
        assertTrue(WMFileTypes.isEditableText("""{"a": 1,"""))
    }

    @Test
    fun `a binary file is not offered to the editor`() {
        // decodeToString turns every unreadable byte into U+FFFD rather than
        // failing, so without this a PNG opens as a screen of garbage.
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13).decodeToString()
        assertFalse(WMFileTypes.isEditableText(png))
        assertFalse(WMFileTypes.isEditableText(""))
    }

    @Test
    fun `one odd byte does not condemn a readable file`() {
        // A stray control character in an otherwise readable config is not a
        // reason to refuse it, so the test is a share of the whole.
        assertTrue(WMFileTypes.isEditableText("key = value\u0007\n" + "line\n".repeat(50)))
    }
}

class VocabImportSniffTest {

    private val pack = com.wasimaster.wmkeyboard.core.vocab.VocabPack(
        com.wasimaster.wmkeyboard.core.vocab.VocabPackMeta(id = "mine", name = "Mine"),
        listOf(com.wasimaster.wmkeyboard.core.vocab.VocabWord("abhor")),
    )

    @Test
    fun `a tagged vocabulary pack is a vocabulary pack`() {
        val text = com.wasimaster.wmkeyboard.core.vocab.VocabPackFile.encode(pack, 1, "1")
        val opened = WMFileTypes.textKindFor(text, "mine.wmvocab.json")
        assertTrue(opened is WMFileTypes.Opened.Vocabulary)
        assertEquals("abhor", (opened as WMFileTypes.Opened.Vocabulary).pack.words.single().word)
    }

    @Test
    fun `the tag beats a misleading theme name`() {
        val text = com.wasimaster.wmkeyboard.core.vocab.VocabPackFile.encode(pack, 1, "1")
        assertTrue(WMFileTypes.textKindFor(text, "oops.wmtheme.json") is WMFileTypes.Opened.Vocabulary)
    }
}
