package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyRole
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.withGlobeInPlace
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LayoutBehaviorSettings
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.LayoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The 🌐 key in one place on every layout (#310): the built-in rows are left
 * exactly as they were, the odd rows move the key to the built-in slot, and a
 * row that would be damaged by the move is left alone.
 */
class GlobeInPlaceTest {

    private val letters: KeyboardLayout = BuiltInLayouts.QWERTY.compile(LayoutLayer.LETTERS)

    private fun globe(width: Float = 1f) = Key("🌐", action = KeyAction.LanguageSwitch, width = width)
    private fun space(width: Float) = Key(" ", action = KeyAction.Space, width = width)
    private fun enter(width: Float = 1.5f) = Key("⏎", action = KeyAction.Enter, width = width)
    private fun symbols(width: Float = 1.5f) = Key("?123", action = KeyAction.Symbols, width = width)
    private fun comma() = Key(",", role = KeyRole.Comma)

    /** A grid of one letter row over [bottom]. */
    private fun grid(vararg bottom: Key) =
        KeyboardLayout(name = "t", rows = listOf(listOf(Key("q"), Key("w")), bottom.toList()))

    private fun KeyboardLayout.bottom() = rows.last()

    /** Where the 🌐 key starts and ends, as shares of its row. */
    private fun KeyboardLayout.globeSpan(): Pair<Float, Float> {
        val row = bottom()
        val total = row.sumOf { it.width.toDouble() }.toFloat()
        var x = 0f
        for (key in row) {
            if (key.action == KeyAction.LanguageSwitch) return x / total to (x + key.width) / total
            x += key.width
        }
        error("no globe")
    }

    private fun KeyboardLayout.total() = bottom().sumOf { it.width.toDouble() }.toFloat()

    @Test
    fun `the built-in row is already in place`() {
        assertSame(letters, letters.withGlobeInPlace(outer = false))
    }

    @Test
    fun `under the swap the built-in row comes out as the swap made it`() {
        val placed = letters.withGlobeInPlace(outer = true)!!
        assertEquals(
            listOf(KeyAction.Symbols, KeyAction.LanguageSwitch, KeyAction.Text, KeyAction.Space),
            placed.bottom().take(4).map { it.action },
        )
        assertEquals(
            letters.bottom().map { it.width }.let { listOf(it[0], it[2], it[1]) + it.drop(3) },
            placed.bottom().map { it.width },
        )
    }

    @Test
    fun `built-in layouts draw the same with the setting on or off`() {
        for (swap in listOf(true, false)) {
            for (spec in listOf(BuiltInLayouts.QWERTY, BuiltInLayouts.AVRO)) {
                val on = drawn(spec, swap, inOnePlace = true)
                val off = drawn(spec, swap, inOnePlace = false)
                assertEquals("${spec.id} swap=$swap", off.rows, on.rows)
            }
        }
    }

    @Test
    fun `a globe at the far left moves to the slot and covers up to it`() {
        val placed = grid(globe(1.4f), space(9.3f), enter(1.45f)).withGlobeInPlace(outer = false)!!
        val (start, end) = placed.globeSpan()
        assertEquals(0f, start, 0.001f)
        assertEquals(0.35f, end, 0.001f)
        assertEquals(1.45f, placed.bottom().last().width, 0.001f)
        assertEquals(12.15f, placed.total(), 0.001f)
    }

    @Test
    fun `a globe right after symbols moves to the built-in slot`() {
        val row = grid(symbols(1.3f), globe(), space(6.5f), Key("."), enter())
        for ((outer, expected) in listOf(false to 0.25f, true to 0.15f)) {
            val placed = row.withGlobeInPlace(outer)!!
            val (start, end) = placed.globeSpan()
            assertEquals(expected, start, 0.001f)
            assertEquals(expected + 0.10f, end, 0.001f)
            assertEquals(row.total(), placed.total(), 0.001f)
        }
    }

    @Test
    fun `a globe after the spacebar moves in front of it`() {
        val placed = grid(symbols(), comma(), space(4f), globe(), Key("."), enter())
            .withGlobeInPlace(outer = false)!!
        assertEquals(
            listOf(KeyAction.Symbols, KeyAction.Text, KeyAction.LanguageSwitch, KeyAction.Space),
            placed.bottom().take(4).map { it.action },
        )
        assertEquals(0.25f, placed.globeSpan().first, 0.001f)
    }

    @Test
    fun `under the swap the comma follows the globe`() {
        val placed = grid(symbols(1.3f), comma(), globe(), space(6.5f), enter())
            .withGlobeInPlace(outer = true)!!
        val labels = placed.bottom().map { it.label }
        assertEquals(listOf("?123", "🌐", ",", " ", "⏎"), labels)
        assertEquals(0.15f, placed.globeSpan().first, 0.001f)
    }

    @Test
    fun `rows it would damage are left alone`() {
        // No spacebar.
        assertNull(grid(symbols(), globe(), enter()).withGlobeInPlace(outer = false))
        // Two globes.
        assertNull(grid(globe(), globe(), space(5f), enter()).withGlobeInPlace(outer = false))
        // A long run of keys before the spacebar would shrink below half.
        val run = Array(7) { Key("k$it") }
        assertNull(grid(*run, globe(), space(3f), enter()).withGlobeInPlace(outer = false))
        // A tiny spacer before the globe would be stretched far past its size.
        assertNull(grid(Key("", action = KeyAction.None, width = 0.1f), globe(), space(8f), enter())
            .withGlobeInPlace(outer = false))
        // A key spanning rows would have keys slid under it.
        val spanning = KeyboardLayout(
            name = "t",
            rows = listOf(
                listOf(Key("q"), Key("⏎", action = KeyAction.Enter, rowSpan = 2)),
                listOf(globe(), space(5f)),
            ),
        )
        assertNull(spanning.withGlobeInPlace(outer = false))
        // No globe at all.
        assertNull(grid(symbols(), space(5f), enter()).withGlobeInPlace(outer = false))
    }

    @Test
    fun `a moved row keeps every key but moves only the globe among them`() {
        val row = grid(Key("a"), Key("b"), globe(), Key("c"), space(4f), Key("d"), enter())
        val placed = row.withGlobeInPlace(outer = false)
        assertNotNull(placed)
        assertEquals(listOf("a", "b", "c", "🌐", " ", "d", "⏎"), placed!!.bottom().map { it.label })
    }

    @Test
    fun `the keyboard moves the globe only while the setting is on`() {
        val odd = LayoutSet(
            grid(globe(1.4f), space(9.3f), enter(1.45f)),
            letters,
            letters,
        )
        val state = KeyboardUiState(
            settings = KeyboardSettings(globeAsEmoji = false, swapCommaAndGlobe = false),
            layouts = odd,
            layoutMode = LayoutMode.LETTERS,
        )
        assertEquals(0.35f, currentLayout(state).globeSpan().second, 0.001f)
        val off = state.copy(
            settings = state.settings.copy(layoutBehavior = LayoutBehaviorSettings(globeInOnePlace = false)),
        )
        assertEquals(1.4f / 12.15f, currentLayout(off).globeSpan().second, 0.001f)
    }

    private fun drawn(spec: LayoutSpec, swap: Boolean, inOnePlace: Boolean): KeyboardLayout =
        currentLayout(
            KeyboardUiState(
                settings = KeyboardSettings(
                    globeAsEmoji = false,
                    swapCommaAndGlobe = swap,
                    layoutBehavior = LayoutBehaviorSettings(globeInOnePlace = inOnePlace),
                ),
                layouts = LayoutSet(
                    spec.compile(LayoutLayer.LETTERS),
                    spec.compile(LayoutLayer.SYMBOLS),
                    spec.compile(LayoutLayer.SYMBOLS_SHIFTED),
                ),
            ),
        )
}
