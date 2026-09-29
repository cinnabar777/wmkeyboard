package com.wasimaster.wmkeyboard.core.keyman

import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSeverity
import com.wasimaster.wmkeyboard.core.layout.canBeEnabled
import com.wasimaster.wmkeyboard.core.layout.multitapKey
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.validateLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Converts real Keyman touch layouts and holds the result to the same standard
 * every shipped asset layout is held to.
 *
 * The invariants here are the ones `AssetLayoutsTest` enforces on the committed
 * grids — parses, needs no repair, can be turned on — checked at the point of
 * conversion rather than after the fact, so a pipeline run cannot produce a file
 * that only fails once it is in the repository.
 */
class TouchLayoutConverterTest {

    private fun fixture(id: String): KeymanTouchLayout {
        val text = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("touch/$id.keyman-touch-layout"),
        ) { "missing fixture touch/$id.keyman-touch-layout" }.use { it.readBytes().decodeToString() }
        return when (val r = KeymanTouchLayoutReader.parse(text)) {
            is KeymanResult.Success -> r.value
            is KeymanResult.Failure -> error("$id did not parse: ${r.fault}")
        }
    }

    private fun convert(id: String): ConvertedKeymanLayout =
        when (val r = TouchLayoutConverter.convert(fixture(id), id, id)) {
            is KeymanResult.Success -> r.value
            is KeymanResult.Failure -> error("$id did not convert: ${r.fault}")
        }

    @Test
    fun `every fixture converts`() {
        for (id in FIXTURES) {
            val spec = convert(id).layout
            assertTrue("$id produced no layers", spec.layers.isNotEmpty())
            assertTrue(
                "$id has no letters layer",
                LayoutLayer.LETTERS.key in spec.layers,
            )
        }
    }

    /**
     * The invariant `AssetLayoutsTest` enforces on everything committed: a
     * converted layout must already be repaired, so the pipeline cannot emit a
     * file that the loader silently rewrites on first read.
     */
    @Test
    fun `every conversion is already repaired`() {
        for (id in FIXTURES) {
            val spec = convert(id).layout
            val repaired = spec.repair()
            assertTrue(
                "$id needed repair: " + repaired.repairNotes.joinToString {
                    "res=${it.stringRes}/plural=${it.pluralsRes} args=${it.args}"
                },
                repaired.repairNotes.isEmpty(),
            )
            assertEquals("$id changed under repair", spec, repaired.spec)
        }
    }

    @Test
    fun `every conversion can be turned on`() {
        for (id in FIXTURES) {
            val spec = convert(id).layout.copy(langId = "en")
            assertTrue(
                "$id cannot be enabled: " + validateLayout(spec)
                    .filter { it.severity == LayoutSeverity.BLOCKING }
                    .joinToString { "${it.layer}: res=${it.text.stringRes} args=${it.text.args}" },
                spec.canBeEnabled(),
            )
        }
    }

    @Test
    fun `every conversion survives the layout file format`() {
        for (id in FIXTURES) {
            val spec = convert(id).layout.copy(langId = "en")
            val decoded = LayoutFile.decode(LayoutFile.encode(spec, 1, "test"))?.layout
            assertTrue("$id did not round-trip through LayoutFile", decoded != null)
            assertEquals("$id lost layers in the round trip", spec.layers.keys, decoded!!.layers.keys)
        }
    }

    /**
     * Every `nextLayer` must name a layer that exists. A dangling one strands
     * the user on a grid they cannot type on and cannot leave, which is the
     * exact failure the repair pass's way-back rules exist to prevent.
     */
    @Test
    fun `every next layer names a layer that exists`() {
        for (id in FIXTURES) {
            val spec = convert(id).layout
            // Our own layers always exist: a missing one draws the built-in grid.
            val known = spec.layers.keys + LayoutLayer.entries.map { it.key }
            for ((name, layer) in spec.layers) {
                for (row in layer.rows) {
                    for (key in row) {
                        val keyman = key.action as? KeyAction.KeymanKey ?: continue
                        val targets = listOfNotNull(keyman.nextLayer) +
                            keyman.longPress.mapNotNull { it.nextLayer } +
                            keyman.flick.values.mapNotNull { it.nextLayer }
                        for (target in targets) {
                            assertTrue(
                                "$id layer '$name' key '${key.label}' points at missing layer '$target'",
                                target in known,
                            )
                        }
                    }
                }
            }
        }
    }

    /** No key may be wider than the grid, or a row silently overflows. */
    @Test
    fun `key widths stay inside the grid`() {
        for (id in FIXTURES) {
            val spec = convert(id).layout
            for ((name, layer) in spec.layers) {
                for ((r, row) in layer.rows.withIndex()) {
                    val total = row.sumOf { it.width.toDouble() }
                    assertTrue(
                        "$id layer '$name' row $r sums to $total",
                        total > 0.0 && total < 40.0,
                    )
                }
            }
        }
    }

    private fun ConvertedKeymanLayout.keys(layer: String): List<com.wasimaster.wmkeyboard.core.layout.Key> =
        layout.layers.getValue(layer).rows.flatten()

    /**
     * Keyman's shift layer is its own set of keys. Khmer Angkor's puts `K_M`
     * pressed with shift where the letters page has `ើ`, and `K_B` where it has
     * `វ` — so it is kept whole, each key as Keyman knows it, rather than being
     * read as the letters page with shift held.
     */
    @Test
    fun `the shift layer is kept whole, each key as keyman knows it`() {
        val converted = convert("khmer_angkor")
        val shift = converted.keys(KeymanLayers.SHIFT)
        val am = shift.single { it.label == "ំ" }.action as KeyAction.KeymanKey
        assertEquals(77, am.vkey)
        assertEquals(KmxFormat.K_SHIFTFLAG, am.modifiers)
        // `៊` is K_SLASH with `layer: default` on the shift page: no shift.
        val triisap = shift.single { it.label == "៊" }.action as KeyAction.KeymanKey
        assertEquals(191, triisap.vkey)
        assertEquals(0, triisap.modifiers)
        // A U_ key keeps its name and types its code point with no rules.
        val repeat = shift.single { it.label == "ៗ" }
        assertEquals("U_17D7", (repeat.action as KeyAction.KeymanKey).id)
        // The letters page's shift key is ours, and so is the shift page's.
        assertTrue(converted.keys(LayoutLayer.LETTERS.key).any { it.action == KeyAction.Shift })
        assertTrue(shift.any { it.action == KeyAction.Shift })
    }

    /** A key's `layer` attribute, else its layer's name, is what the rules see as held. */
    @Test
    fun `keys on a right alt layer are pressed with right alt`() {
        val converted = convert("lao_2008_basic")
        val keys = converted.keys(KeymanLayers.PREFIX + "rightalt")
            .mapNotNull { it.action as? KeyAction.KeymanKey }
            .filter { !it.isLayerSwitch }
        assertTrue("no keys on Lao's rightalt layer", keys.isNotEmpty())
        assertTrue(keys.all { it.modifiers and KmxFormat.RALTFLAG != 0 })
    }

    /** A flick or long press is a key of its own in Keyman, and reaches the rules as one. */
    @Test
    fun `gesture keys keep their keyman identity`() {
        val letters = convert("khmer_angkor").keys(LayoutLayer.LETTERS.key)
        val q = letters.single { it.label == "ឆ" }
        val qFlick = (q.action as KeyAction.KeymanKey).flick.values.single()
        assertEquals("T_17D2_1786", qFlick.id)
        assertEquals(null, qFlick.text)
        assertEquals("្ឆ", q.flick.values.single())
        val w = letters.single { it.label == "ឹ" }
        val wFlick = (w.action as KeyAction.KeymanKey).flick.values.single()
        assertEquals(87, wFlick.vkey)
        assertEquals(KmxFormat.K_SHIFTFLAG, wFlick.modifiers)
    }

    /** Long-press entries and their keys stay parallel, which is how a pick finds its key. */
    @Test
    fun `long press keys line up with their labels`() {
        for (id in FIXTURES) {
            for ((_, layer) in convert(id).layout.layers) {
                for (key in layer.rows.flatten()) {
                    val keyman = key.action as? KeyAction.KeymanKey ?: continue
                    assertEquals("$id '${key.label}'", key.longPress.size, keyman.longPress.size)
                    assertEquals("$id '${key.label}'", key.flick.keys, keyman.flick.keys)
                }
            }
        }
    }

    /** Keyman layer ids that are also names of ours must not land on ours. */
    @Test
    fun `keyman layer names are kept apart from ours`() {
        assertEquals("letters", KeymanLayers.specKey("default"))
        assertEquals("symbols", KeymanLayers.specKey("numeric"))
        assertEquals("symbols2", KeymanLayers.specKey("symbol"))
        assertEquals("k:symbols", KeymanLayers.specKey("symbols"))
        assertEquals("k:number", KeymanLayers.specKey("number"))
        for (id in listOf("default", "numeric", "symbol", "symbols", "rightalt-shift")) {
            assertEquals(id, KeymanLayers.keymanId(KeymanLayers.specKey(id)))
        }
    }

    /** Layer names are read the way KeymanWeb reads them: by what they contain. */
    @Test
    fun `layer names become modifiers the way keymanweb reads them`() {
        assertEquals(0, KeymanLayers.modifiers("default"))
        assertEquals(0, KeymanLayers.modifiers("numeric"))
        assertEquals(KmxFormat.K_SHIFTFLAG, KeymanLayers.modifiers("shift"))
        assertEquals(KmxFormat.RALTFLAG or KmxFormat.K_SHIFTFLAG, KeymanLayers.modifiers("rightalt-shift"))
        assertEquals(KmxFormat.LCTRLFLAG, KeymanLayers.modifiers("leftctrl"))
        assertEquals(KmxFormat.K_CTRLFLAG or KmxFormat.K_ALTFLAG, KeymanLayers.modifiers("ctrlalt"))
        assertEquals(KmxFormat.CAPITALFLAG, KeymanLayers.modifiers("caps"))
    }

    /**
     * The gestures we cannot express are counted, not silently discarded. A
     * cycle on a layer switch is one: these returned before the cycle was
     * looked at, and went uncounted.
     */
    @Test
    fun `dropped gestures are reported`() {
        // Six `*123*`/`*Currency*`/`*Symbol*` keys that cycle through pages.
        assertEquals(6, convert("sil_euro_latin").report.droppedMultitaps)
        // Two `K_NUMLOCK` keys that cycle to the western digits.
        assertEquals(2, convert("khmer_angkor").report.droppedMultitaps)
    }

    /**
     * A cycle on a key that types is kept: its caps on the key, and a Keyman
     * key for each step on the action, so each later tap reaches the rules as
     * the key it is — `U_1255` here, which types ቕ with no rules loaded.
     */
    @Test
    fun `multitap steps keep their keyman identity`() {
        val converted = convert("geezword_tigrinya")
        assertEquals(0, converted.report.droppedMultitaps)
        val q = converted.keys(LayoutLayer.LETTERS.key).single { it.label == "ቅ." }
        val keyman = q.action as KeyAction.KeymanKey
        assertEquals(listOf("ቕ"), q.multitap)
        val step = keyman.multitap.single()
        assertEquals("U_1255", step.id)
        assertEquals(0, step.vkey)
        // The cap is the step's own character, so it needs no fallback of its own.
        assertEquals(null, step.text)

        val pressed = q.multitapKey(1)
        assertEquals("ቕ", pressed.label)
        assertEquals(step.toAction(), pressed.action)
        assertTrue(pressed.multitap.isEmpty())
        assertEquals(q, q.multitapKey(0))
    }

    /** Multitap caps and their keys stay parallel, which is how a step finds its key. */
    @Test
    fun `multitap keys line up with their labels`() {
        var kept = 0
        for (id in FIXTURES) {
            for ((_, layer) in convert(id).layout.layers) {
                for (key in layer.rows.flatten()) {
                    val keyman = key.action as? KeyAction.KeymanKey
                    assertEquals("$id '${key.label}'", key.multitap.size, keyman?.multitap?.size ?: 0)
                    kept += key.multitap.size
                }
            }
        }
        assertTrue("no fixture kept a multitap", kept > 0)
    }

    /**
     * Sweeps the whole `release/` corpus when it is checked out, which is how a
     * pipeline run is validated before it writes anything. Skipped — not failed
     * — when the corpus is absent, so an ordinary build does not need a 78 MB
     * checkout to go green.
     *
     * Point `KEYMAN_CORPUS` at the checkout to run it.
     */
    @Test
    fun `the whole corpus converts when it is available`() {
        val root = System.getenv("KEYMAN_CORPUS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val files = root.resolve("release").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".keyman-touch-layout") }
            .toList()
        assertTrue("KEYMAN_CORPUS is set but holds no touch layouts", files.isNotEmpty())

        val failures = mutableListOf<String>()
        var converted = 0
        for (file in files) {
            val id = file.name.removeSuffix(".keyman-touch-layout")
            val doc = when (val r = KeymanTouchLayoutReader.parse(file.readText())) {
                is KeymanResult.Success -> r.value
                is KeymanResult.Failure -> {
                    failures += "$id: parse ${r.fault}"
                    continue
                }
            }
            val spec = when (val r = TouchLayoutConverter.convert(doc, id, id)) {
                is KeymanResult.Success -> r.value.layout
                is KeymanResult.Failure -> {
                    failures += "$id: convert ${r.fault}"
                    continue
                }
            }
            converted++
            val repaired = spec.repair()
            if (repaired.repairNotes.isNotEmpty()) {
                failures += "$id: repairs " + repaired.repairNotes.joinToString {
                    "res=${it.stringRes} args=${it.args}"
                }
            }
            val blocking = validateLayout(spec.copy(langId = "en"))
                .filter { it.severity == LayoutSeverity.BLOCKING }
            if (blocking.isNotEmpty()) {
                failures += "$id: blocking " + blocking.joinToString {
                    "${it.layer}/res=${it.text.stringRes} args=${it.text.args}"
                }
            }
            val known = spec.layers.keys
            for ((name, layer) in spec.layers) {
                for (row in layer.rows) {
                    for (key in row) {
                        val target = (key.action as? KeyAction.KeymanKey)?.nextLayer ?: continue
                        if (target !in known) failures += "$id: '$name' -> missing '$target'"
                    }
                }
            }
        }
        println("corpus sweep: $converted of ${files.size} converted, ${failures.size} problems")
        assertTrue(
            "corpus problems (${failures.size}):\n" + failures.take(40).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private companion object {
        val FIXTURES = listOf(
            "basic_kbdus",
            "khmer_angkor",
            "lao_2008_basic",
            "sil_euro_latin",
            "geezword_tigrinya",
            "urdu_dvorak",
        )
    }
}
