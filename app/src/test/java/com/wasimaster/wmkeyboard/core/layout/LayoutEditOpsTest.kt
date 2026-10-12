package com.wasimaster.wmkeyboard.core.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.app.KeyActionCatalog
import com.wasimaster.wmkeyboard.app.KeyRef
import com.wasimaster.wmkeyboard.app.caretRect
import com.wasimaster.wmkeyboard.app.dropGapAt
import com.wasimaster.wmkeyboard.app.dropLanding
import com.wasimaster.wmkeyboard.app.moveKeyIn
import com.wasimaster.wmkeyboard.app.rowMoveTarget
import com.wasimaster.wmkeyboard.app.withArm
import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid edits the editor performs, exercised against the model rather than
 * through Compose.
 *
 * These pin the copy-on-write rule that makes "a layout replaces everything"
 * survivable: a layout inherits every layer it has not authored, and touching an
 * inherited layer writes this layout's own copy of it — so moving one letter
 * never costs the user a phone pad.
 */
class LayoutEditOpsTest {

    private val layer = LayoutLayer.LETTERS

    /** The editor's copy-on-first-edit, mirrored from KeyLayoutEditorScreen. */
    private fun LayoutSpec.withLayerRows(rows: List<List<Key>>): LayoutSpec {
        val existing = layer(layer) ?: LayerSpec(rows)
        return copy(layers = layers + (layer.key to existing.copy(rows = rows)))
    }

    private fun mine() = LayoutSpec(id = "custom_1", name = "Mine")

    @Test
    fun `a layout with no layers inherits every one of them`() {
        val bare = mine()
        assertNull("nothing authored", bare.layer(LayoutLayer.SYMBOLS))
        assertEquals(
            "but it still compiles to the shipped grid",
            BuiltInLayouts.default.compile(LayoutLayer.SYMBOLS).rows,
            bare.compile(LayoutLayer.SYMBOLS).rows,
        )
    }

    @Test
    fun `editing an inherited layer authors only that layer`() {
        val edited = mine().let { it.withLayerRows(it.compile(layer).rows + listOf(listOf(Key("z")))) }

        assertNotNull("the edited layer is now this layout's own", edited.layer(layer))
        assertNull("the others are untouched", edited.layer(LayoutLayer.PHONE))
        assertEquals(
            "and the phone pad still comes from the built-in",
            BuiltInLayouts.default.compile(LayoutLayer.PHONE).rows,
            edited.compile(LayoutLayer.PHONE).rows,
        )
    }

    @Test
    fun `resetting a layer drops back to the built-in grid`() {
        val edited = mine().withLayerRows(listOf(listOf(Key("only"))))
        val reset = edited.copy(layers = edited.layers - layer.key)

        assertNull(reset.layer(layer))
        assertEquals(
            BuiltInLayouts.default.compile(layer).rows,
            reset.compile(layer).rows,
        )
    }

    @Test
    fun `authoring a layer preserves its number row`() {
        val withNumberRow = mine().copy(
            layers = mapOf(
                layer.key to LayerSpec(
                    rows = listOf(listOf(Key("a"))),
                    numberRow = listOf(Key("1"), Key("2")),
                ),
            ),
        )
        val edited = withNumberRow.withLayerRows(listOf(listOf(Key("b"))))
        assertEquals(
            "editing rows must not silently drop the layer's other fields",
            listOf(Key("1"), Key("2")),
            edited.layer(layer)?.numberRow,
        )
    }

    @Test
    fun `moving a key within its row keeps every key`() {
        val row = listOf(Key("a"), Key("b"), Key("c"))
        val moved = row.toMutableList().apply { add(2, removeAt(0)) }
        assertEquals(listOf(Key("b"), Key("c"), Key("a")), moved)
    }

    @Test
    fun `duplicating a key inserts the copy next to the original`() {
        val row = listOf(Key("a"), Key("b"), Key("c"))
        val col = 1
        val out = row.subList(0, col + 1) + row[col] + row.drop(col + 1)
        assertEquals(listOf(Key("a"), Key("b"), Key("b"), Key("c")), out)
    }

    @Test
    fun `deleting the last key of a row leaves an empty row the renderer tolerates`() {
        val edited = mine().withLayerRows(listOf(emptyList()))
        // Phase 2 taught splitKeys and gridWeightOf to survive this; the editor
        // is allowed to produce it, and validate reports it.
        assertEquals(0f, gridWeightOf(edited.compile(layer).rows), 0.001f)
        // Matched by the resource the finding names, plus the row number and
        // layer it carries, so a reworded warning does not fail this test.
        val findings = validateLayout(edited)
        assertTrue(
            "findings were $findings",
            LayoutMessage(
                R.string.core_lang_layout_empty_row_warning,
                args = listOf(1, layer.key),
            ) in findings.map { it.text },
        )
    }

    /**
     * The key sheet's write, mirrored from KeyLayoutEditorScreen: a change to one
     * field, applied to the key as it is *stored*.
     */
    private fun LayoutSpec.editKey(row: Int, col: Int, change: (Key) -> Key): LayoutSpec {
        val rows = layer(layer)?.rows ?: compile(layer).rows
        return withLayerRows(
            rows.mapIndexed { r, keys ->
                if (r != row) keys else keys.mapIndexed { c, k -> if (c == col) change(k) else k }
            },
        )
    }

    @Test
    fun `a width edit made from a stale copy of the key keeps the label`() {
        // Issue #15. The sheet reads its key back out of the settings flow, which
        // lags the write that produced it, so the copy a control is holding can be
        // one or more edits behind. A control that handed back a whole key wrote
        // that stale copy over the stored one — label first, then width, and the
        // label was gone.
        val start = mine().withLayerRows(listOf(listOf(Key("new"))))
        val labelled = start.editKey(0, 0) { it.copy(label = "q") }
        // The copy a control in the sheet is still holding while the label edit is
        // in flight: the key as it was before the label landed.
        assertEquals("the stale copy is the pre-label key", "new", start.layer(layer)!!.rows[0][0].label)

        val widened = labelled.editKey(0, 0) { it.copy(width = 2f) }
        val key = widened.layer(layer)!!.rows[0][0]
        assertEquals("the width lands", 2f, key.width, 0.001f)
        assertEquals("and the label survives it", "q", key.label)
    }

    @Test
    fun `reordering the keys of a row keeps an edit made just before it`() {
        // Same staleness, one level up: the reorder dialog used to hand back the
        // keys themselves, which were this composition's copies of them.
        val stored = mine().withLayerRows(listOf(listOf(Key("a"), Key("b"), Key("c"))))
            .editKey(0, 2) { it.copy(label = "z") }
        val order = listOf(2, 0, 1)
        val rows = stored.layer(layer)!!.rows
        val reordered = stored.withLayerRows(
            rows.mapIndexed { i, row ->
                if (i == 0 && order.size == row.size) order.map { row[it] } else row
            },
        )
        assertEquals(
            listOf("z", "a", "b"),
            reordered.layer(layer)!!.rows[0].map { it.label },
        )
    }

    @Test
    fun `a key with no label and no output is reported, not silently deleted`() {
        // Issue #16: the key is legal to store, types nothing, and repair deletes
        // it at activation — which from the keyboard reads as a dead key. The
        // editor has to say so while it can still be fixed.
        val withBlank = mine().withLayerRows(listOf(listOf(Key(""), Key("a"))))
        assertTrue(
            "findings were ${validateLayout(withBlank)}",
            validateLayout(withBlank).any {
                it.text.pluralsRes == R.plurals.core_lang_layout_blank_key_warning &&
                    it.severity == LayoutSeverity.WARNING
            },
        )
        // A key with only a label is not blank: the keyboard types the label when
        // the output is empty, which is what the Output field's hint promises.
        val labelOnly = mine().withLayerRows(listOf(listOf(Key("q"))))
        assertTrue(
            validateLayout(labelOnly).none {
                it.text.pluralsRes == R.plurals.core_lang_layout_blank_key_warning
            },
        )
        assertTrue(
            "and repair keeps it, alongside the delete/space/enter keys it adds",
            labelOnly.repair().spec.layer(layer)!!.rows.flatten().contains(Key("q")),
        )
    }

    @Test
    fun `a half-built layout can be saved but not enabled`() {
        val broken = mine().withLayerRows(listOf(listOf(Key("a"))))
        assertTrue(
            "no delete, enter or space — the editor still has to let it exist",
            validateLayout(broken).any { it.severity == LayoutSeverity.BLOCKING },
        )
        assertTrue("but repair makes it usable at activation", broken.repair().spec.canBeEnabled())
    }

    @Test
    fun `an edited built-in keeps the built-in id so the shadow resolves`() {
        val edited = BuiltInLayouts.QWERTY.withLayerRows(listOf(listOf(Key("z"))))
        assertEquals(BuiltInLayouts.QWERTY_ID, edited.id)
        assertEquals(edited, resolveLayout(listOf(edited), BuiltInLayouts.QWERTY_ID))
    }

    /**
     * The editor's "what does an edit to this layer start from", mirrored from
     * KeyLayoutEditorScreen exactly as [withLayerRows] is.
     */
    private fun LayoutSpec.baseLayerOf(which: LayoutLayer): LayerSpec {
        layer(which)?.let { return it }
        if (BuiltInLayouts.default.layer(which) == null) return LayerSpec(rows = emptyList())
        val compiled = compile(which)
        return LayerSpec(rows = compiled.rows, rowHeights = compiled.rowHeights)
    }

    @Test
    fun `compile falls through to the letters grid for a layer nothing ships`() {
        // The fact the editor has to work around: Fn is the one layer no built-in
        // defines, so compile runs off the end of its fallback chain. Anything
        // that treats compile's answer as "the grid to edit" authors Fn as a
        // second copy of the alphabet.
        assertNull(BuiltInLayouts.default.layer(LayoutLayer.FN))
        assertEquals(
            BuiltInLayouts.default.compile(LayoutLayer.LETTERS).rows,
            mine().compile(LayoutLayer.FN).rows,
        )
    }

    @Test
    fun `an edit to the Fn layer starts from nothing, not from the letters`() {
        val base = mine().baseLayerOf(LayoutLayer.FN)
        assertEquals("Fn has no shipped grid to inherit", emptyList<List<Key>>(), base.rows)
    }

    @Test
    fun `an edit to an inherited shipped layer still starts from that layer`() {
        val base = mine().baseLayerOf(LayoutLayer.PHONE)
        assertEquals(
            BuiltInLayouts.default.compile(LayoutLayer.PHONE).rows,
            base.rows,
        )
    }

    @Test
    fun `an authored Fn layer is edited from itself`() {
        val withFn = mine().copy(
            layers = mapOf(LayoutLayer.FN.key to BuiltInLayouts.FN_DEFAULT),
        )
        assertEquals(BuiltInLayouts.FN_DEFAULT.rows, withFn.baseLayerOf(LayoutLayer.FN).rows)
    }

    @Test
    fun `the editing encoding drops defaults and still round-trips`() {
        val full = LayoutCodec.encode(BuiltInLayouts.QWERTY)
        val lean = LayoutCodec.encodeForEditing(BuiltInLayouts.QWERTY)

        assertTrue(
            "the raw-JSON screen is read by a person; ${lean.length} vs ${full.length}",
            lean.length < full.length / 2,
        )
        assertTrue("no null-valued field survives", !lean.contains(":null"))
        assertEquals(
            "and what it prints still decodes to the same layout",
            BuiltInLayouts.QWERTY,
            LayoutCodec.decode(lean),
        )
    }

    @Test
    fun `every action a key can carry draws something when its label is blank`() {
        // A key with no label and no glyph is a button the user cannot see. The
        // action picker never asks for a label, so every action it offers has to
        // answer this — including the payload-carrying ones.
        val actions = listOf(
            KeyAction.Symbols, KeyAction.Letters, KeyAction.Fn, KeyAction.Numpad,
            KeyAction.KanaVariant, KeyAction.MorseDot, KeyAction.MorseDash,
            KeyAction.AddStroke, KeyAction.DoubleConsonant,
            KeyAction.Mod(ModifierKey.CTRL), KeyAction.Mod(ModifierKey.ALT),
            KeyAction.Mod(ModifierKey.META),
            KeyAction.SendKey(61), KeyAction.SendKey(111), KeyAction.SendKey(19),
            KeyAction.SendKey(20), KeyAction.SendKey(21), KeyAction.SendKey(22),
            KeyAction.SendKey(999), KeyAction.BrailleDot(3), KeyAction.Broadcast("x"),
        )
        for (action in actions) {
            assertTrue("$action draws nothing", action.fallbackLabel().isNotEmpty())
        }
    }

    @Test
    fun `the actions drawn from an icon slot claim no text fallback`() {
        // These never reach the text branch — the keyboard answers them with an
        // icon first — and handing them a glyph too would be a second spelling of
        // the same key that only the editor would ever show.
        val drawnAsIcons = listOf(
            KeyAction.Shift, KeyAction.Delete, KeyAction.ForwardDelete,
            KeyAction.Enter, KeyAction.LanguageSwitch, KeyAction.InputMethodPicker,
            KeyAction.Emoji, KeyAction.Text, KeyAction.None,
        )
        for (action in drawnAsIcons) {
            assertEquals("$action", "", action.fallbackLabel())
        }
    }

    @Test
    fun `a flick field writes its arm and a blank one removes it`() {
        // Issue #339: the key sheet's flick fields. The map stays in the file's
        // own left-up-right-down order whatever order the fields were filled in,
        // and an emptied field leaves no empty arm behind.
        val arms = emptyMap<FlickDirection, String>()
            .withArm(FlickDirection.DOWN, "お")
            .withArm(FlickDirection.LEFT, "い")
        assertEquals(listOf(FlickDirection.LEFT, FlickDirection.DOWN), arms.keys.toList())
        assertEquals(mapOf(FlickDirection.DOWN to "お"), arms.withArm(FlickDirection.LEFT, ""))
        // A corner arm (issue #410) files after the four edges, whatever order
        // the pad was filled in.
        val corners = arms.withArm(FlickDirection.UP_LEFT, "q").withArm(FlickDirection.UP, "u")
        assertEquals(
            listOf(FlickDirection.LEFT, FlickDirection.UP, FlickDirection.DOWN, FlickDirection.UP_LEFT),
            corners.keys.toList(),
        )
    }

    @Test
    fun `the action picker offers the kana-mark key`() {
        // The flick pad's 小゛゜ key shipped with no entry here, so its sheet
        // read "Unknown action".
        assertTrue(KeyActionCatalog.any { it.matches(KeyAction.KanaVariant) })
    }

    @Test
    fun `the action picker offers the two naratgeul keys`() {
        assertTrue(KeyActionCatalog.any { it.matches(KeyAction.AddStroke) })
        assertTrue(KeyActionCatalog.any { it.matches(KeyAction.DoubleConsonant) })
    }

    @Test
    fun `the action picker offers the pinyin syllable column`() {
        assertTrue(KeyActionCatalog.any { it.matches(KeyAction.ReadingColumn) })
    }

    @Test
    fun `a key that becomes the kana-mark key survives the JSON round trip`() {
        val key = Key("🌐", action = KeyAction.LanguageSwitch, kanaVariantWhileComposing = true)
        val spec = mine().withLayerRows(listOf(listOf(key)))
        val back = LayoutCodec.decode(LayoutCodec.encodeForEditing(spec))!!
        assertTrue(back.compile(layer).rows[0][0].kanaVariantWhileComposing)
        assertFalse(
            "an unflagged key does not write the field",
            LayoutCodec.encodeForEditing(BuiltInLayouts.QWERTY).contains("kanaVariantWhileComposing"),
        )
    }

    @Test
    fun `the action picker never shows one group twice`() {
        // The picker prints a header whenever the group changes as it walks the
        // catalogue, so a catalogue that leaves a group and comes back to it
        // splits that group across two headings. Fn used to sit after the
        // modifiers, giving two "Layers" sections.
        val order = KeyActionCatalog.map { it.groupRes }
        assertEquals(
            "each group must be one contiguous run",
            order.distinct().size,
            order.zipWithNext().count { (a, b) -> a != b } + 1,
        )
    }

    @Test
    fun `a layout with a blocking finding is refused by the enable gate`() {
        // Mirrors rememberLayoutEnableGate: the editor has always said "you must
        // fix this before you turn this layout on", and nothing enforced it.
        fun blockers(spec: LayoutSpec) =
            validateLayout(spec).filter { it.severity == LayoutSeverity.BLOCKING }

        val broken = mine().withLayerRows(listOf(listOf(Key("a"))))
        assertTrue("no delete, enter or space", blockers(broken).isNotEmpty())
        assertFalse(broken.canBeEnabled())

        assertTrue("a shipped layout still passes", blockers(BuiltInLayouts.QWERTY).isEmpty())
        // A warning is not a refusal: a layout with no shift key still enables.
        val noShift = BuiltInLayouts.QWERTY.withLayerRows(
            BuiltInLayouts.QWERTY.compile(layer).rows
                .map { row -> row.filter { it.action != KeyAction.Shift } },
        )
        assertTrue(validateLayout(noShift).any { it.severity == LayoutSeverity.WARNING })
        assertTrue("but it is not blocked", blockers(noShift).isEmpty())
    }

    @Test
    fun `a layout edited after it was turned on is repaired where it is used`() {
        // What the keyboard does to a spec before drawing it (WMKeyboardService's
        // resolveLayoutSet). The editor saves whatever you are mid-way through,
        // and an enabled layout is live while you build it — so deleting the row
        // that carries ⌫ must not cost the running keyboard its backspace.
        val enabledAndBroken = BuiltInLayouts.QWERTY.withLayerRows(
            BuiltInLayouts.QWERTY.compile(layer).rows
                .map { row -> row.filter { it.action != KeyAction.Delete } },
        )
        assertTrue(
            "the stored layout is allowed to be broken",
            validateLayout(enabledAndBroken).any { it.severity == LayoutSeverity.BLOCKING },
        )

        val drawn = enabledAndBroken.repair().spec
        assertTrue(
            "but the grid the keyboard draws always has one",
            drawn.compile(layer).rows.flatten().any { it.action == KeyAction.Delete },
        )
        assertEquals(
            "and repairing an already-good layout changes nothing",
            drawn.repair().spec,
            drawn,
        )
    }

    // -----------------------------------------------------------------------
    // Moving one key: the sheet's four arrows and a drag in the preview both
    // land through moveKeyIn, so these pin it rather than either caller.
    // -----------------------------------------------------------------------

    private fun grid() = listOf(
        listOf(Key("a"), Key("b"), Key("c")),
        listOf(Key("d"), Key("e")),
    )

    @Test
    fun `a key moved to another row leaves the first and joins the second`() {
        val moved = moveKeyIn(grid(), KeyRef(0, 1), KeyRef(1, 0))
        assertEquals(listOf("a", "c"), moved[0].map { it.label })
        assertEquals(listOf("b", "d", "e"), moved[1].map { it.label })
    }

    @Test
    fun `a move keeps every key of the grid`() {
        val before = grid().flatten().map { it.label }.sorted()
        for (fromRow in 0..1) {
            for (fromCol in 0..2) {
                for (toRow in 0..1) {
                    for (toCol in 0..3) {
                        val after = moveKeyIn(grid(), KeyRef(fromRow, fromCol), KeyRef(toRow, toCol))
                        assertEquals(
                            "moving ($fromRow,$fromCol) to ($toRow,$toCol) changed the key set",
                            before,
                            after.flatten().map { it.label }.sorted(),
                        )
                        assertEquals("and the row count", 2, after.size)
                    }
                }
            }
        }
    }

    @Test
    fun `a move from an address that no longer holds a key changes nothing`() {
        // The ref is read from the composition's copy of the grid and the move
        // runs against the store's, which can be a keystroke ahead.
        val rows = grid()
        assertEquals(rows, moveKeyIn(rows, KeyRef(0, 9), KeyRef(1, 0)))
        assertEquals(rows, moveKeyIn(rows, KeyRef(5, 0), KeyRef(1, 0)))
        assertEquals(rows, moveKeyIn(rows, KeyRef(0, 0), KeyRef(7, 0)))
    }

    @Test
    fun `a column past the end of the target row puts the key on the end`() {
        val moved = moveKeyIn(grid(), KeyRef(0, 0), KeyRef(1, 99))
        assertEquals(listOf("d", "e", "a"), moved[1].map { it.label })
    }

    @Test
    fun `a drop into a gap after the key itself comes back one place`() {
        // The gap is counted with the key still in the row, so dropping "a"
        // into the gap before "c" (index 2) lands it at index 1.
        assertEquals(KeyRef(0, 1), dropLanding(KeyRef(0, 0), KeyRef(0, 2)))
        // A gap before the key, or in another row, is already the answer.
        assertEquals(KeyRef(0, 0), dropLanding(KeyRef(0, 2), KeyRef(0, 0)))
        assertEquals(KeyRef(1, 2), dropLanding(KeyRef(0, 0), KeyRef(1, 2)))
    }

    @Test
    fun `dropping a key back where it was is a move to itself`() {
        val from = KeyRef(0, 1)
        // The gap on either side of the key it came from.
        assertEquals(from, dropLanding(from, KeyRef(0, 1)))
        assertEquals(from, dropLanding(from, KeyRef(0, 2)))
    }

    @Test
    fun `up and down stop at the edges of the grid`() {
        assertNull(rowMoveTarget(grid(), KeyRef(0, 0), -1))
        assertNull(rowMoveTarget(grid(), KeyRef(1, 0), +1))
        assertEquals(KeyRef(1, 0), rowMoveTarget(grid(), KeyRef(0, 0), +1))
        assertEquals(KeyRef(0, 1), rowMoveTarget(grid(), KeyRef(1, 1), -1))
    }

    @Test
    fun `a key moving into a shorter row joins the end of it`() {
        // The third key of row 0 has no third seat to take in row 1.
        val to = rowMoveTarget(grid(), KeyRef(0, 2), +1)
        assertEquals(KeyRef(1, 2), to)
        val moved = moveKeyIn(grid(), KeyRef(0, 2), to!!)
        assertEquals(listOf("a", "b"), moved[0].map { it.label })
        assertEquals(listOf("d", "e", "c"), moved[1].map { it.label })
    }

    // -----------------------------------------------------------------------
    // Where a drop lands. The grid arithmetic is not consulted: the drag reads
    // the rectangles the cells actually took, so these drive it with a grid of
    // rectangles rather than with a Compose tree.
    // -----------------------------------------------------------------------

    /** Three keys 100 wide over two 150-wide ones, with a 10 gap between rows. */
    private fun twoRowBounds() = mapOf(
        KeyRef(0, 0) to Rect(0f, 0f, 100f, 50f),
        KeyRef(0, 1) to Rect(100f, 0f, 200f, 50f),
        KeyRef(0, 2) to Rect(200f, 0f, 300f, 50f),
        KeyRef(1, 0) to Rect(0f, 60f, 150f, 110f),
        KeyRef(1, 1) to Rect(150f, 60f, 300f, 110f),
    )

    private fun twoRowKeys() = listOf(
        listOf(Key("a"), Key("b"), Key("c")),
        listOf(Key("d"), Key("e")),
    )

    @Test
    fun `a drop lands in the gap the finger is nearest`() {
        val rows = twoRowKeys()
        val bounds = twoRowBounds()
        // Left of the first key's middle: in front of it.
        assertEquals(KeyRef(0, 0), dropGapAt(rows, bounds, Offset(10f, 25f)))
        // Past that middle but not the next: between the first two.
        assertEquals(KeyRef(0, 1), dropGapAt(rows, bounds, Offset(60f, 25f)))
        // Past every middle in the row: on the end.
        assertEquals(KeyRef(0, 3), dropGapAt(rows, bounds, Offset(290f, 25f)))
        // The second row, by its own rectangles.
        assertEquals(KeyRef(1, 1), dropGapAt(rows, bounds, Offset(100f, 80f)))
    }

    @Test
    fun `a finger off the top or the bottom takes the nearest row`() {
        val rows = twoRowKeys()
        val bounds = twoRowBounds()
        assertEquals(KeyRef(0, 0), dropGapAt(rows, bounds, Offset(10f, -400f)))
        assertEquals(KeyRef(1, 0), dropGapAt(rows, bounds, Offset(10f, 400f)))
    }

    @Test
    fun `a row a spanning key reaches into is judged by its own keys`() {
        // A two-row key at the left of row 0 covers row 1 as well. Counting it
        // as row 0's would put every drop in the lower half a row too high.
        val rows = listOf(
            listOf(Key("tall", rowSpan = 2), Key("b"), Key("c")),
            listOf(Key("d"), Key("e")),
        )
        val bounds = mapOf(
            KeyRef(0, 0) to Rect(0f, 0f, 100f, 110f),
            KeyRef(0, 1) to Rect(100f, 0f, 200f, 50f),
            KeyRef(0, 2) to Rect(200f, 0f, 300f, 50f),
            KeyRef(1, 0) to Rect(100f, 60f, 200f, 110f),
            KeyRef(1, 1) to Rect(200f, 60f, 300f, 110f),
        )
        assertEquals(1, dropGapAt(rows, bounds, Offset(150f, 80f))?.row)
        assertEquals(0, dropGapAt(rows, bounds, Offset(150f, 20f))?.row)
    }

    @Test
    fun `a grid nothing has been drawn from yet has no gap`() {
        assertNull(dropGapAt(twoRowKeys(), emptyMap(), Offset(10f, 10f)))
        assertNull(dropGapAt(emptyList(), twoRowBounds(), Offset(10f, 10f)))
    }

    @Test
    fun `the caret sits at the edge of the gap it marks`() {
        val rows = twoRowKeys()
        val bounds = twoRowBounds()
        // In front of the second key: down its left edge, the row's height.
        val between = caretRect(rows, bounds, KeyRef(0, 1))!!
        assertEquals(100f, between.left, 0.01f)
        assertEquals(0f, between.top, 0.01f)
        assertEquals(50f, between.bottom, 0.01f)
        // On the end of the row: down the right edge of the last key.
        assertEquals(300f, caretRect(rows, bounds, KeyRef(0, 3))!!.left, 0.01f)
        // And a gap past even that — a seat a longer grid left in the map —
        // still reads as the end of the row rather than as a stale rectangle.
        assertEquals(300f, caretRect(rows, bounds, KeyRef(1, 9))!!.left, 0.01f)
    }

    @Test
    fun `moving the last key out of a row leaves the row behind`() {
        // Empty rows are legal — the renderer tolerates them and validate warns
        // — so a move must not quietly delete one and shift every row up.
        val single = listOf(listOf(Key("a")), listOf(Key("b")))
        val moved = moveKeyIn(single, KeyRef(0, 0), KeyRef(1, 0))
        assertEquals(2, moved.size)
        assertTrue(moved[0].isEmpty())
        assertEquals(listOf("a", "b"), moved[1].map { it.label })
    }
}
