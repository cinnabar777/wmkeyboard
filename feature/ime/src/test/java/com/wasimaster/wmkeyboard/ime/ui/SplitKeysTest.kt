package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitKeysTest {

    private fun labels(keys: List<Key>) = keys.joinToString(" ") { key ->
        if (key.action == KeyAction.Space) "SPACE" else key.label
    }

    @Test
    fun `even ten-key row splits five and five`() {
        val (left, right) = splitKeys(Layouts.QWERTY.rows[0])
        assertEquals("q w e r t", labels(left))
        assertEquals("y u i o p", labels(right))
    }

    @Test
    fun `nine-key home row ties go right`() {
        val (left, right) = splitKeys(Layouts.QWERTY.rows[1])
        assertEquals("a s d f g", labels(left))
        assertEquals("h j k l", labels(right))
    }

    @Test
    fun `modifier row keeps v on the left`() {
        val (left, right) = splitKeys(Layouts.QWERTY.rows[2])
        assertEquals("⇧ z x c v", labels(left))
        assertEquals("b n m ⌫", labels(right))
    }

    @Test
    fun `straddling spacebar is divided at the midpoint`() {
        val row = Layouts.QWERTY.rows[3]
        val total = row.map { it.width }.sum()
        val (left, right) = splitKeys(row)
        // Both halves end/start with a space key and weigh exactly half the row.
        assertEquals(KeyAction.Space, left.last().action)
        assertEquals(KeyAction.Space, right.first().action)
        assertEquals("", left.last().label)
        assertEquals(total / 2f, left.map { it.width }.sum(), 0.001f)
        assertEquals(total / 2f, right.map { it.width }.sum(), 0.001f)
    }

    /**
     * A custom layout can hand us a row the shipped ones never do. The cut
     * search indexes from 1, so an empty row used to throw out of the whole
     * keyboard in split mode — a deleted row taking typing down with it.
     */
    @Test
    fun `an empty row splits without throwing`() {
        val (left, right) = splitKeys(emptyList())
        assertEquals(emptyList<Key>(), left)
        assertEquals(emptyList<Key>(), right)
    }

    @Test
    fun `a one key row keeps its key on the left`() {
        val row = listOf(Key("a"))
        val (left, right) = splitKeys(row)
        assertEquals(row, left)
        assertEquals(emptyList<Key>(), right)
    }

    @Test
    fun `every layout row splits into non-empty halves preserving total width`() {
        val layouts = listOf(Layouts.QWERTY, Layouts.PROBHAT, Layouts.JATIYA, Layouts.SYMBOLS, Layouts.SYMBOLS_SHIFTED)
        for (layout in layouts) {
            for (row in layout.rows) {
                val (left, right) = splitKeys(row)
                assertEquals(true, left.isNotEmpty())
                assertEquals(true, right.isNotEmpty())
                assertEquals(
                    row.map { it.width }.sum(),
                    (left + right).map { it.width }.sum(),
                    0.001f,
                )
            }
        }
    }

    @Test
    fun `a kept spacebar swallows the gap and nothing else moves`() {
        val row = Layouts.QWERTY.rows[3]
        val bridged = bridgeSpaceAcrossGap(row, 1.2f)!!
        assertEquals(row.size, bridged.size)
        for (i in row.indices) {
            val grow = if (row[i].action == KeyAction.Space) 1.2f else 0f
            assertEquals(row[i].width + grow, bridged[i].width, 0.001f)
            assertEquals(row[i].label, bridged[i].label)
        }
    }

    @Test
    fun `a spacebar ending the left half bridges the gap`() {
        val row = listOf(Key("a"), Key(" ", action = KeyAction.Space), Key("b"), Key("c"))
        val bridged = bridgeSpaceAcrossGap(row, 1f)!!
        assertEquals(2f, bridged[1].width, 0.001f)
    }

    @Test
    fun `a row with no spacebar at the cut is left to split`() {
        assertEquals(null, bridgeSpaceAcrossGap(Layouts.QWERTY.rows[0], 1f))
        assertEquals(null, bridgeSpaceAcrossGap(listOf(Key("a")), 1f))
    }
}
