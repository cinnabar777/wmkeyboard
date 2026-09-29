package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.withGlobeInPlace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 🌐 placement (#310) run over every layout the app ships, on the three
 * layers a board draws, both ways round the comma swap. A row it moves keeps
 * every key and its total width, no key ends up with no width, and the 🌐 key
 * lands in the slot; a row it refuses is simply drawn as before.
 */
class GlobeInPlaceShippedTest {

    private val assetDir = File("../../app/src/main/assets/layouts")

    @Test
    fun `every shipped layout keeps its keys and gets the slot`() {
        val files = assetDir.listFiles { f -> f.name.endsWith(".wmlayout.json") }.orEmpty()
        assertTrue("no layouts found at ${assetDir.absolutePath}", files.size > 1000)
        var moved = 0
        var inPlace = 0
        var refused = 0
        for (file in files) {
            val spec = LayoutFile.decode(file.readText())?.layout ?: continue
            for (layer in listOf(LayoutLayer.LETTERS, LayoutLayer.SYMBOLS, LayoutLayer.SYMBOLS_SHIFTED)) {
                val grid = spec.compile(layer)
                if (grid.rows.lastOrNull()?.any { it.action == KeyAction.LanguageSwitch } != true) continue
                for (outer in listOf(false, true)) {
                    val placed = grid.withGlobeInPlace(outer)
                    when {
                        placed == null -> refused++
                        placed === grid -> inPlace++
                        else -> {
                            moved++
                            check(grid, placed, outer, "${file.name} $layer outer=$outer")
                        }
                    }
                }
            }
        }
        println("globe placement over shipped layouts: moved=$moved inPlace=$inPlace refused=$refused")
        assertTrue(moved > 0)
    }

    private fun check(before: KeyboardLayout, after: KeyboardLayout, outer: Boolean, what: String) {
        assertEquals(what, before.rows.dropLast(1), after.rows.dropLast(1))
        val old = before.rows.last()
        val new = after.rows.last()
        assertEquals(what, old.map { it.label to it.action }.sortedBy { it.toString() },
            new.map { it.label to it.action }.sortedBy { it.toString() })
        val total = old.sumOf { it.width.toDouble() }
        assertEquals(what, total, new.sumOf { it.width.toDouble() }, 0.01)
        assertTrue(what, new.all { it.width > 0f })
        var x = 0.0
        for (key in new) {
            if (key.action == KeyAction.LanguageSwitch) {
                val end = (x + key.width) / total
                assertEquals(what, if (outer) 0.25 else 0.35, end, 0.001)
                return
            }
            x += key.width
        }
        error("$what lost its globe")
    }
}
