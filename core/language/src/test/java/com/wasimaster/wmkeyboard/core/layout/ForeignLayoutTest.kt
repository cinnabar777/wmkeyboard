package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a layout written for FlorisBoard or HeliBoard.
 *
 * The fixtures are hand-written minimal files, not packs taken from either
 * project: FlorisBoard is Apache-2.0 and HeliBoard is GPL-3.0, and this repo is
 * MIT, so the formats are exercised without any of their content entering the
 * tree.
 */
class ForeignLayoutTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("foreign/$name")) {
            "missing test fixture foreign/$name"
        }.use { it.readBytes().decodeToString() }

    private fun ConvertedLayout.letters(): List<List<Key>> =
        checkNotNull(layout.layer(LayoutLayer.LETTERS)).rows

    private fun ConvertedLayout.keys(): List<Key> = letters().flatten()

    /** The first row's keys the file wrote, without the ⇧ and ⌫ the import frames them with. */
    private fun ConvertedLayout.firstRowText(): List<Key> =
        letters()[0].filter { it.action == KeyAction.Text }

    // ---- the text format ----

    @Test
    fun `a blank line starts a new row`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText(fixture("heliboard_qwerty.txt"), "q.txt"))
        // Three rows in the file, and the bottom row this app gives every
        // import as a fourth.
        assertEquals(4, converted.letters().size)
        assertEquals(listOf("q", "w", "e"), converted.letters()[0].map { it.label })
    }

    @Test
    fun `the tokens after the label become the press-and-hold letters`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText(fixture("heliboard_qwerty.txt"), "q.txt"))
        val e = converted.keys().first { it.label == "e" }
        assertEquals(listOf("3", "é", "è", "ê"), e.longPress)
    }

    @Test
    fun `a comment line is not a key`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText(fixture("heliboard_qwerty.txt"), "q.txt"))
        assertTrue(converted.keys().none { it.label.startsWith("//") })
    }

    @Test
    fun `a file with no keys converts to nothing`() {
        assertNull(ForeignLayouts.fromSimpleText("", "empty.txt"))
        assertNull(ForeignLayouts.fromSimpleText("// only a comment\n\n\n", "empty.txt"))
    }

    // ---- the JSON format ----

    @Test
    fun `every key shape is read`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        val keys = converted.keys()
        // auto_text_key and text_key both commit their own label.
        assertEquals("q", keys.first { it.label == "q" }.label)
        // multi_text_key joins its code points into one output.
        val multi = keys.first { it.label == "th" }
        assertEquals("th", multi.output ?: multi.label)
    }

    @Test
    fun `main comes before relevant so it becomes the hint`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        assertEquals(listOf("é", "è"), converted.keys().first { it.label == "e" }.longPress)
    }

    @Test
    fun `an auto_text_key gets no shift label`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        // A null shiftLabel is what makes the keyboard uppercase the key itself.
        // Writing "Q" in would freeze the case for every script that has none.
        assertTrue(converted.keys().filter { it.action == KeyAction.Text }.all { it.shiftLabel == null })
    }

    @Test
    fun `negative codes become the matching actions`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        val actions = converted.keys().map { it.action }
        assertTrue(KeyAction.Shift in actions)
        assertTrue(KeyAction.Delete in actions)
        assertTrue(KeyAction.Symbols in actions)
        assertTrue(actions.any { it is KeyAction.SendKey && it.keyCode == KEYCODE_TAB })
    }

    @Test
    fun `space and enter are actions even though their codes are positive`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        val actions = converted.keys().map { it.action }
        assertTrue(KeyAction.Space in actions)
        assertTrue(KeyAction.Enter in actions)
        // Exactly one of each: if code 32 had stayed a text key, repair would
        // have found no space key and added a second spacebar.
        assertEquals(1, actions.count { it == KeyAction.Space })
        assertEquals(1, actions.count { it == KeyAction.Enter })
    }

    @Test
    fun `a key this keyboard has no action for is dropped and reported`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        // -233 is voice input, which is a tool here rather than a key.
        assertEquals(listOf(-233), converted.unmapped)
        assertTrue(converted.keys().none { it.label == "voice" })
        val note = converted.notes.first { it.pluralsRes == R.plurals.core_lang_foreign_keys_dropped }
        assertEquals(1, note.quantity)
        assertTrue(note.args.contains("-233"))
    }

    @Test
    fun `the comma and period popup groups become punctuation slots`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        // Group 1 is the other keyboard's comma set, which it picks from the
        // layout's language. Tagging the slot hands that job to this keyboard's
        // own field adaptation instead of copying one language's punctuation in.
        val r = converted.keys().first { it.label == "r" }
        assertEquals(KeyRole.Comma, r.role)
        assertTrue(r.longPress.isEmpty())
        assertFalse(converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_popups_missing })
    }

    @Test
    fun `a key that points at a popup group it did not ship is reported`() {
        // Group 3 is the action key's own popups, which live in a file this
        // import never saw and have no slot on this side.
        val converted = checkNotNull(
            ForeignLayouts.fromFlorisJson("""[[{"label":"a","groupId":3}]]""", "x.json"),
        )
        assertTrue(converted.keys().first { it.label == "a" }.longPress.isEmpty())
        assertTrue(converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_popups_missing })
    }

    @Test
    fun `an icon key ignores the word the file drew on it`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        // The keyboard draws shift and delete from icon slots, so a file that
        // labels them "shift" and "delete" must not put those words on screen.
        val shift = converted.keys().first { it.action == KeyAction.Shift }
        assertEquals("", shift.label)
        assertEquals("", converted.keys().first { it.action == KeyAction.Delete }.label)
        // A text-drawn action keeps the file's label.
        assertEquals("?123", converted.keys().first { it.action == KeyAction.Symbols }.label)
    }

    @Test
    fun `comments and a trailing comma do not fail the file`() {
        // Both fixtures carry them, because HeliBoard's own reader allows both
        // and these files are hand-written.
        assertNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        assertNotNull(ForeignLayouts.fromFlorisJson(fixture("heliboard_bengali.json"), "b.json"))
    }

    @Test
    fun `text that is not JSON converts as the text format`() {
        val converted = checkNotNull(ForeignLayouts.convert(fixture("heliboard_qwerty.txt"), "q.txt"))
        assertEquals(ForeignSource.HELIBOARD_TEXT, converted.source)
        assertEquals(
            ForeignSource.FLORIS_JSON,
            checkNotNull(ForeignLayouts.convert(fixture("floris_qwerty.json"), "f.json")).source,
        )
    }

    @Test
    fun `a file that is neither converts to nothing`() {
        assertNull(ForeignLayouts.fromFlorisJson("{not json", "x.json"))
        assertNull(ForeignLayouts.fromFlorisJson("""{"shopping":["milk"]}""", "x.json"))
        assertNull(ForeignLayouts.fromFlorisJson("[[],[]]", "x.json"))
    }

    // ---- the language guess ----

    @Test
    fun `the language is guessed from the letters and never written in`() {
        val bengali = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("heliboard_bengali.json"), "b.json"))
        assertEquals("bn", bengali.guessedLangId)
        val latin = checkNotNull(ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"))
        assertEquals("en", latin.guessedLangId)
        // Left blank in the layout itself: the guess has to pass in front of
        // someone before it can reach storage and pick a dictionary.
        assertEquals("", bengali.layout.langId)
    }

    @Test
    fun `a layout with no letters at all guesses English`() {
        assertEquals("en", guessLangId(listOf(listOf(Key(label = "1"), Key(label = "!")))))
    }

    // ---- the name ----

    @Test
    fun `the layout is named after the file`() {
        val converted = checkNotNull(ForeignLayouts.convert("a\n", "/tmp/my_grid.wmlayout.json"))
        assertEquals("my grid", converted.layout.name)
        assertEquals("Imported layout", checkNotNull(ForeignLayouts.convert("a\n", ".json")).layout.name)
    }

    // ---- what must never come out ----

    @Test
    fun `no converted key ever carries the unknown action`() {
        // KeyAction.Unknown cannot be serialized — its serializer throws — so a
        // converted layout carrying one would take the whole custom-layouts
        // write down with it. The converter drops those keys instead.
        for (name in FIXTURES) {
            val converted = checkNotNull(ForeignLayouts.convert(fixture(name), name)) { name }
            assertTrue(name, converted.keys().none { it.action is KeyAction.Unknown })
        }
    }

    @Test
    fun `every conversion survives being written out`() {
        for (name in FIXTURES) {
            val converted = checkNotNull(ForeignLayouts.convert(fixture(name), name)) { name }
            // The assertion that matters most in this file: encode() is what
            // upsertCustomLayout calls, inside a DataStore edit that catches
            // nothing, and an unwritable action would take the whole
            // custom-layouts store down with it.
            val stored = converted.withLanguage(converted.guessedLangId)
            assertEquals(name, stored, LayoutCodec.decode(LayoutCodec.encode(stored)))
        }
    }

    @Test
    fun `a layout stored with no language silently becomes English`() {
        // Not a wish, a fact about LayoutCodec.migrateLayout — and the reason
        // withLanguage is the only way out of a conversion. If this ever starts
        // failing, that guard can be reconsidered.
        val converted = checkNotNull(ForeignLayouts.convert(fixture("heliboard_bengali.json"), "b.json"))
        assertEquals("", converted.layout.langId)
        val reread = checkNotNull(LayoutCodec.decode(LayoutCodec.encode(converted.layout)))
        assertEquals("en", reread.langId)
        // Going through withLanguage is what keeps the Bengali grid Bengali.
        assertEquals("bn", converted.withLanguage("bn").langId)
        assertEquals("bn", converted.withLanguage("").langId)
    }

    @Test
    fun `every conversion is already repaired`() {
        for (name in FIXTURES) {
            val converted = checkNotNull(ForeignLayouts.convert(fixture(name), name)) { name }
            // The keyboard repairs again on every layout compile and throws the
            // notes away. Anything left to fix here would be a silent
            // difference between the grid the user approved and the one they
            // type on.
            assertEquals(name, converted.layout, converted.layout.repair().spec)
            assertTrue(name, converted.layout.repair().repairNotes.isEmpty())
        }
    }

    @Test
    fun `every conversion can be turned on`() {
        for (name in FIXTURES) {
            val converted = checkNotNull(ForeignLayouts.convert(fixture(name), name)) { name }
            assertTrue(name, converted.layout.canBeEnabled())
        }
    }

    @Test
    fun `a conversion round-trips through the layout file format`() {
        for (name in FIXTURES) {
            val converted = checkNotNull(ForeignLayouts.convert(fixture(name), name)) { name }
            val stored = converted.withLanguage(converted.guessedLangId)
            val reread = checkNotNull(LayoutFile.decode(LayoutFile.encode(stored, 0, ""))) { name }
            assertEquals(name, stored.layers, reread.layout.layers)
            // Nothing left for the file's own repair pass either.
            assertTrue(name, reread.repairNotes.isEmpty())
        }
    }

    @Test
    fun `the missing bottom row is added back, once`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText(fixture("heliboard_qwerty.txt"), "q.txt"))
        // The text format cannot spell these three, so every file is missing
        // all of them and the import is expected to supply them.
        val actions = converted.keys().map { it.action }
        assertEquals(1, actions.count { it == KeyAction.Delete })
        assertEquals(1, actions.count { it == KeyAction.Space })
        assertEquals(1, actions.count { it == KeyAction.Enter })
        // Where the shipped grids have them, so repair has nothing to add at
        // the end of the last row.
        assertEquals(KeyAction.Delete, converted.letters()[2].last().action)
        assertFalse(converted.notes.any { it.stringRes == R.string.core_lang_repair_delete_key_added })
    }

    private companion object {
        val FIXTURES = listOf("heliboard_qwerty.txt", "floris_qwerty.json", "heliboard_bengali.json")

        /** `?123 , 🌐 ␣ . ⏎`, as the shipped grids end. */
        val HOUSE_BOTTOM_ROW = listOf(
            KeyAction.Symbols to null,
            KeyAction.Text to KeyRole.Comma,
            KeyAction.LanguageSwitch to null,
            KeyAction.Space to null,
            KeyAction.Text to KeyRole.Period,
            KeyAction.Enter to null,
        )

        const val KEYCODE_TAB = 61
    }

    @Test
    fun `a bare string is a key`() {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson("""[["a","b"]]""", "x.json"))
        assertEquals(listOf("a", "b"), converted.firstRowText().take(2).map { it.label })
    }

    @Test
    fun `rows wrapped in an object are found`() {
        assertNotNull(ForeignLayouts.fromFlorisJson("""{"arrangement":[["a"]]}""", "x.json"))
        assertFalse(ForeignLayouts.fromFlorisJson("""{"arrangement":[["a"]]}""", "x.json")!!.keys().isEmpty())
    }

    // ---- label flags ----

    /** Converts one key object and hands back the key it became. */
    private fun oneKey(json: String): Key {
        val converted = checkNotNull(ForeignLayouts.fromFlorisJson("""[[$json]]""", "x.json"))
        // A one-row file's row is its last letter row, so it comes back framed
        // as `⇧ … ⌫`; the key under test is the one that is not the shift key.
        return converted.letters()[0].first { it.action != KeyAction.Shift }
    }

    private fun scaleOf(json: String): Float = checkNotNull(oneKey(json).labelScale)

    @Test
    fun `the follow-ratio flags become a label size`() {
        // 0x80 followKeyLetterRatio: draw it the size of a letter, which is what
        // this keyboard would not have done on its own for a two-letter label.
        assertEquals(1f, scaleOf("""{"label":"ab","labelFlags":128}"""), 0f)
        // 0xC0 followKeyLabelRatio lands on the mode-label size.
        assertEquals(0.68f, scaleOf("""{"label":"ab","labelFlags":192}"""), 0f)
        // 0x140 followKeyHintLabelRatio has a bit outside the other three and
        // must not read as 0x40.
        assertEquals(0.44f, scaleOf("""{"label":"ab","labelFlags":320}"""), 0f)
        assertEquals(1.15f, scaleOf("""{"label":"ab","labelFlags":64}"""), 0f)
    }

    @Test
    fun `a key with no flags keeps the automatic size`() {
        assertNull(oneKey("""{"label":"a"}""").labelScale)
        assertNull(oneKey("""{"label":"a","labelFlags":0}""").labelScale)
        // autoScale is what this keyboard already does to every label.
        assertNull(oneKey("""{"label":"a","labelFlags":49152}""").labelScale)
    }

    @Test
    fun `flags may be written in hex`() {
        // The format's own documentation is written in hex, so a hand-written
        // file is likely to be too.
        assertEquals(0.44f, scaleOf("""{"label":"ab","labelFlags":"0x140"}"""), 0f)
        // The same flag in decimal, which is what an exported file carries.
        assertTrue(oneKey("""{"label":"a","labelFlags":1073741824}""").hideHint)
    }

    @Test
    fun `disableKeyHintLabel hides the corner hint`() {
        val key = oneKey("""{"label":"a","popup":["b"],"labelFlags":"0x40000000"}""")
        assertTrue(key.hideHint)
        // The alternates are still reachable; only the corner is cleared.
        assertEquals(listOf("b"), key.longPress)
    }

    @Test
    fun `a style this keyboard cannot draw is reported, not applied`() {
        // 0x20 fontMonoSpace on its own, with no ratio bits.
        val converted = checkNotNull(
            ForeignLayouts.fromFlorisJson("""[[{"label":"a","labelFlags":32}]]""", "x.json"),
        )
        assertNull(converted.firstRowText().first().labelScale)
        assertTrue(converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_labels_restyled })
    }

    @Test
    fun `flags on an action key come across too`() {
        // -202 is the symbols key, whose "?123" label this keyboard shrinks by
        // itself. The file asking for the letter ratio is asking it not to.
        assertEquals(1f, scaleOf("""{"code":-202,"label":"?123","labelFlags":128}"""), 0f)
    }

    @Test
    fun `a suppressed popup group is not a lost popup`() {
        // groupId -1 means "add no popups", which is the key getting what it
        // asked for rather than anything going missing.
        val converted = checkNotNull(
            ForeignLayouts.fromFlorisJson("""[[{"label":"a","groupId":-1}]]""", "x.json"),
        )
        assertFalse(converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_popups_missing })
        // A group this keyboard has no slot for still reports, because those
        // letters live in a file this import never saw.
        val grouped = checkNotNull(
            ForeignLayouts.fromFlorisJson("""[[{"label":"a","groupId":3}]]""", "x.json"),
        )
        assertTrue(grouped.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_popups_missing })
    }

    // ---- conditional keys ----

    @Test
    fun `a case selector keeps both cases`() {
        val key = oneKey("""{"$":"case_selector","lower":"ß","upper":"ẞ"}""")
        assertEquals("ß", key.label)
        assertEquals("ẞ", key.shiftLabel)
    }

    @Test
    fun `a case selector whose upper is the plain uppercase writes no shift label`() {
        // A null shiftLabel already uppercases at commit time, so writing one
        // in would only freeze what already happens.
        val key = oneKey("""{"$":"case_selector","lower":"a","upper":"A"}""")
        assertNull(key.shiftLabel)
    }

    @Test
    fun `a shift state selector keeps the resting key and says so`() {
        val converted = checkNotNull(
            ForeignLayouts.fromFlorisJson(
                """[[{"$":"shift_state_selector","unshifted":"a","capsLock":"Z"}]]""",
                "x.json",
            ),
        )
        assertEquals("a", converted.firstRowText().first().label)
        assertTrue(
            converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_selectors_flattened },
        )
    }

    @Test
    fun `a selector that names only a later branch still converts`() {
        assertEquals("a", oneKey("""{"$":"variation_selector","default":"a"}""").label)
        assertEquals("b", oneKey("""{"$":"layout_direction_selector","ltr":"b","rtl":"c"}""").label)
    }

    @Test
    fun `a selector key used to be dropped with nothing said`() {
        // The regression this exists for: no code and no label of its own, so
        // the object reader returned null and the key vanished silently.
        val converted = checkNotNull(
            ForeignLayouts.fromFlorisJson(
                """[["q",{"$":"case_selector","lower":"w","upper":"W"},"e"]]""",
                "x.json",
            ),
        )
        assertEquals(listOf("q", "w", "e"), converted.firstRowText().take(3).map { it.label })
    }

    // ---- label sugar ----

    @Test
    fun `a functional label becomes the key it names`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText("a\ndelete\nalpha", "x.txt"))
        val keys = converted.letters()[0]
        assertEquals(KeyAction.Delete, keys[1].action)
        assertEquals(KeyAction.Letters, keys[2].action)
        // And the word is not left on the key.
        assertFalse(keys.any { it.label == "delete" })
    }

    @Test
    fun `a functional label does not make repair add a second key`() {
        // The old reader drew a text key saying "space" and repair then added a
        // real spacebar beside it.
        val converted = checkNotNull(ForeignLayouts.fromSimpleText("a\n\nshift space action", "x.txt"))
        assertEquals(1, converted.keys().count { it.action == KeyAction.Space })
        assertEquals(1, converted.keys().count { it.action == KeyAction.Enter })
    }

    @Test
    fun `a backslash escapes a functional label`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText("""\space""", "x.txt"))
        val key = converted.firstRowText().first()
        assertEquals("space", key.label)
        assertEquals(KeyAction.Text, key.action)
    }

    @Test
    fun `a label and its output are split on the pipe`() {
        val key = oneKey("""{"label":"aa|bb"}""")
        assertEquals("aa", key.label)
        assertEquals("bb", key.output)
    }

    @Test
    fun `a code written in the label becomes the action`() {
        assertEquals(KeyAction.Delete, oneKey("""{"label":"x|!code/-7"}""").action)
    }

    @Test
    fun `a declared code beats the sugar in the label`() {
        // The format says so outright: with both, the label is only a drawing.
        val key = oneKey("""{"code":97,"label":"space"}""")
        assertEquals(KeyAction.Text, key.action)
        assertEquals("a", key.output ?: key.label)
    }

    @Test
    fun `a label this keyboard has no key for is dropped and reported`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText("a\ndpad", "x.txt"))
        assertFalse(converted.keys().any { it.label == "dpad" })
        val note = converted.notes.first {
            it.pluralsRes == R.plurals.core_lang_foreign_labels_dropped
        }
        assertTrue(note.args.contains("dpad"))
    }

    @Test
    fun `the currency labels become signs rather than dollars of text`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText("\$\$\$\n\$\$\$1", "x.txt"))
        val local = converted.keys().first { it.label == "$" }
        assertTrue(local.longPress.contains("€"))
        assertTrue(converted.keys().any { it.label == "€" })
        assertTrue(
            converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_keys_approximated },
        )
    }

    // ---- case ----

    @Test
    fun `an auto_text_key rests in lowercase whatever case its code is written in`() {
        // FlorisBoard's own Rusyn grid gives ї the code of Ї. That keyboard
        // lowercases an auto_text_key until shift is down; typed as written
        // here, the key put a capital in the middle of every word.
        val converted = checkNotNull(
            ForeignLayouts.fromFlorisJson(
                """
                [[
                  { "$": "auto_text_key", "code": 1031, "label": "ї" },
                  { "$": "auto_text_key", "code": 1067, "label": "Ы" },
                  { "$": "auto_text_key", "code": 1081, "label": "й" }
                ]]
                """.trimIndent(),
                "rusyn.json",
            ),
        )
        val keys = converted.firstRowText()
        assertEquals(listOf("ї", "ы", "й"), keys.map { it.label })
        // Nothing left to type but the label, and shift still reaches the
        // capital through the null shift label.
        assertTrue(keys.all { it.output == null && it.shiftLabel == null })
    }

    @Test
    fun `a text_key types what its code says`() {
        // Only the auto key follows shift. A plain one committing a different
        // case from its label is the file's own statement.
        val key = oneKey("""{"$":"text_key","code":1031,"label":"ї"}""")
        assertEquals("ї", key.label)
        assertEquals("Ї", key.output)
    }

    // ---- the frame every import is given ----

    @Test
    fun `a character grid gets the house frame`() {
        // FlorisBoard's character grids hold letters and nothing else: shift,
        // delete and the whole bottom row are that keyboard's to draw.
        val converted = checkNotNull(
            ForeignLayouts.fromFlorisJson(
                """
                [
                  [ { "code": 1081, "label": "й" }, { "code": 1094, "label": "ц" }, { "code": 1091, "label": "у" } ],
                  [ { "code": 1092, "label": "ф" }, { "code": 1110, "label": "і" }, { "code": 1074, "label": "в" } ],
                  [ { "code": 1103, "label": "я" }, { "code": 1095, "label": "ч" }, { "code": 1089, "label": "с" } ]
                ]
                """.trimIndent(),
                "grid.json",
            ),
        )
        val rows = converted.letters()
        assertEquals(4, rows.size)
        assertEquals(
            listOf(KeyAction.Shift, KeyAction.Text, KeyAction.Text, KeyAction.Text, KeyAction.Delete),
            rows[2].map { it.action },
        )
        assertEquals(listOf(1.5f, 1.5f), listOf(rows[2].first().width, rows[2].last().width))
        assertEquals(HOUSE_BOTTOM_ROW, rows[3].map { it.action to it.role })
        assertEquals(listOf("?123", ",", "🌐", " ", ".", "⏎"), rows[3].map { it.label })
        assertEquals(4f, rows[3][3].width, 0f)
        assertTrue(converted.layout.repair().repairNotes.isEmpty())
    }

    @Test
    fun `a text layout gets the house frame`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText(fixture("heliboard_qwerty.txt"), "q.txt"))
        val rows = converted.letters()
        assertEquals(listOf("⇧", "z", "x", "c", "⌫"), rows[2].map { it.label })
        assertEquals(HOUSE_BOTTOM_ROW, rows[3].map { it.action to it.role })
    }

    @Test
    fun `a bottom row key the house row has no slot for keeps its seat by the spacebar`() {
        // The fixture's bottom row is `?123 voice ␣ tab ⏎`. Voice is dropped as
        // before; tab has no house slot and stays, one key wide, taking that
        // off the spacebar so the row keeps its length.
        val bottom = checkNotNull(
            ForeignLayouts.fromFlorisJson(fixture("floris_qwerty.json"), "f.json"),
        ).letters().last()
        val tab = bottom.indexOfFirst { it.action is KeyAction.SendKey }
        assertEquals(KeyAction.Space, bottom[tab + 1].action)
        assertEquals(KeyAction.LanguageSwitch, bottom[tab - 1].action)
        assertEquals(3f, bottom[tab + 1].width, 0.01f)
        assertEquals(1, bottom.count { it.action == KeyAction.Symbols })
    }

    @Test
    fun `a grid with a key spanning rows is left as it was laid out`() {
        // Moving keys under a tall one would slide them beneath it. Repair
        // still guarantees the grid types.
        val converted = checkNotNull(
            ForeignLayouts.fromFlorisJson("""[["a","b"],["c","d"]]""", "x.json"),
        )
        val spanned = listOf(
            listOf(Key("a", rowSpan = 2), Key("b")),
            listOf(Key("c")),
        )
        assertEquals(spanned, withHouseStructure(spanned))
        // And the plain grid beside it is framed, so the difference is the span.
        assertEquals(3, converted.letters().size)
    }

    // ---- popup markers ----

    @Test
    fun `a popup marker is not a letter`() {
        val key = oneKey("""{"label":"a","popup":["b","!hasLabels!","c"]}""")
        assertEquals(listOf("b", "c"), key.longPress)
    }

    @Test
    fun `a column marker sets the popup columns`() {
        assertEquals(4, oneKey("""{"label":"a","popup":["b","!fixedColumnOrder!4"]}""").alternateColumns)
        // Out of range is no opinion rather than a value repair has to clamp.
        assertEquals(0, oneKey("""{"label":"a","popup":["b","!autoColumnOrder!99"]}""").alternateColumns)
    }

    @Test
    fun `a marker in the text format is read too`() {
        val converted = checkNotNull(ForeignLayouts.fromSimpleText("a b !fixedColumnOrder!3", "x.txt"))
        val key = converted.firstRowText().first()
        assertEquals(listOf("b"), key.longPress)
        assertEquals(3, key.alternateColumns)
    }
}
