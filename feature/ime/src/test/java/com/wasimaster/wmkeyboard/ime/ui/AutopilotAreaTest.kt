package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry behind "Outline the touch areas": the rectangle each favoured
 * letter has claimed has to be the boundary the hit test itself would draw, or
 * the setting is a decoration that lies about what the keyboard is doing.
 */
class AutopilotAreaTest {

    /** Three keys in a row, 100 wide and 100 tall, centres at 50, 150 and 250. */
    private val centers = mapOf(
        'a'.code to Offset(50f, 50f),
        'b'.code to Offset(150f, 50f),
        'c'.code to Offset(250f, 50f),
    )
    private val bounds = mapOf(
        'a'.code to Rect(0f, 0f, 100f, 100f),
        'b'.code to Rect(100f, 0f, 200f, 100f),
        'c'.code to Rect(200f, 0f, 300f, 100f),
    )

    private fun areas(bias: Map<Char, Float>, strength: Float = 0.5f) =
        autopilotAreas(centers, bounds, bias, strength, keyWidth = 100f)

    @Test
    fun `a favoured letter claims its share of the gap to each neighbour`() {
        // Weight 1.5 against two plain neighbours: the boundary sits 1.5/2.5 of
        // the 100 units between the centres, so 60 either side of centre 150.
        val area = areas(mapOf('b' to 1f))['b']!!.area
        assertEquals(90f, area.left, 0.01f)
        assertEquals(210f, area.right, 0.01f)
        assertEquals(1.2f, areas(mapOf('b' to 1f))['b']!!.scale, 0.01f)
    }

    @Test
    fun `two favoured letters split the gap they share`() {
        // Equal weights meet in the middle, exactly where they met unfavoured.
        val both = areas(mapOf('a' to 1f, 'b' to 1f))
        assertEquals(100f, both['a']!!.area.right, 0.01f)
        assertEquals(100f, both['b']!!.area.left, 0.01f)
        // Each still grows outward, away from the letter it is level with.
        assertTrue(both['a']!!.area.left < 0f)
        assertTrue(both['b']!!.area.right > 200f)
    }

    @Test
    fun `strength scales how far the boundary moves`() {
        val gentle = areas(mapOf('b' to 1f), strength = 0.1f)['b']!!.area
        val hard = areas(mapOf('b' to 1f), strength = 1.0f)['b']!!.area
        assertTrue(gentle.width < hard.width)
        // And never past the reach cap: one key width from the centre.
        assertTrue(hard.right <= 250f)
    }

    @Test
    fun `a letter squeezed by a likelier neighbour is not drawn`() {
        // 'a' is favoured, 'b' more so: 'a' loses ground and there is nothing
        // to show. Only the letter that actually grew comes back.
        val drawn = areas(mapOf('a' to 0.2f, 'b' to 1f))
        assertNull(drawn['a'])
        assertTrue(drawn.containsKey('b'))
    }

    @Test
    fun `nothing is drawn without a distribution, a grid or a width`() {
        assertTrue(areas(emptyMap()).isEmpty())
        assertTrue(autopilotAreas(centers, bounds, mapOf('b' to 1f), 0.5f, keyWidth = 0f).isEmpty())
        assertTrue(autopilotAreas(centers, emptyMap(), mapOf('b' to 1f), 0.5f, 100f).isEmpty())
    }

    /**
     * A whole row favoured equally takes nothing from anyone: each boundary is
     * shared between two equal weights and lands where it always was. The
     * effect is a difference between letters, not a size the strength setting
     * dials up for all of them at once, so the boxed-in middle letter is not
     * drawn at all.
     */
    @Test
    fun `a letter that took nothing from either neighbour is not drawn`() {
        assertNull(areas(mapOf('a' to 1f, 'b' to 1f, 'c' to 1f))['b'])
    }

    /**
     * Growth is measured against the plain nearest-centre boundary, never
     * against the drawn key. On a row of mixed key widths the plain boundary
     * already sits inside the wider key, so a favoured letter beside a narrow
     * one claims less than its own cell while still having won ground. Judged
     * against the cell it was thrown away, which is what left issue #76's
     * reporter looking at one arbitrary letter instead of the likeliest ones.
     */
    @Test
    fun `a letter beside a narrower key still counts as grown`() {
        // A wide vowel with a narrow key either side of it, as on the layout
        // the fault was reported from.
        val wide = 100f
        val narrow = 35f
        val xs = listOf('a' to wide, '.' to narrow, 'e' to wide, ',' to narrow, 'u' to wide)
        var x = 0f
        val cells = LinkedHashMap<Int, Rect>()
        val mids = LinkedHashMap<Int, Offset>()
        for ((ch, w) in xs) {
            cells[ch.code] = Rect(x, 100f, x + w, 200f)
            mids[ch.code] = Offset(x + w / 2f, 150f)
            x += w
        }
        // Plain rows above and below, so the vertical neighbours are ordinary.
        for ((i, entry) in xs.withIndex()) {
            mids[('A' + i).code] = Offset(mids[entry.first.code]!!.x, 50f)
            mids[('P' + i).code] = Offset(mids[entry.first.code]!!.x, 250f)
        }
        val drawn = autopilotAreas(mids, cells, mapOf('e' to 1f), 0.5f, wide)
        val grown = drawn['e']
        assertNotNull("the likeliest letter is drawn beside narrow keys", grown)
        // Its claim is narrower than its own key — the midpoint with a narrow
        // neighbour falls inside it — and it has still won ground on both sides.
        assertTrue(grown!!.area.width > grown.cell.width)
    }

    /**
     * Ranked by how likely the letter is, not by how much room it won: the
     * likeliest letter can be boxed in by its neighbours and grow the least of
     * the lot, and it is still the one the user is looking for.
     */
    @Test
    fun `the likeliest letter is drawn even when its neighbours crowd it`() {
        // 'b' is the likeliest by far, but sits between two letters the word
        // list also expects, so it takes almost nothing from either of them.
        val drawn = areas(mapOf('a' to 0.6f, 'b' to 1f, 'c' to 0.6f))
        assertTrue(drawn.containsKey('b'))
        assertEquals("likeliest first", 'b', drawn.keys.first())
    }

    /** A letter the word list barely mentions is not worth a rectangle. */
    @Test
    fun `an also-ran is filtered out`() {
        val drawn = areas(mapOf('a' to 1f, 'c' to 0.05f))
        assertTrue(drawn.containsKey('a'))
        assertTrue(drawn.containsKey('c').not())
    }

    /** The board stays readable: a wide distribution is capped, likeliest first. */
    @Test
    fun `at most three areas are drawn, and they are the likeliest`() {
        val many = ('a'..'z').toList()
        val manyCenters = many.mapIndexed { i, ch -> ch.code to Offset(50f + i * 100f, 50f) }.toMap()
        val manyBounds = many.mapIndexed { i, ch ->
            ch.code to Rect(i * 100f, 0f, i * 100f + 100f, 100f)
        }.toMap()
        // Every other letter is expected, in descending order, so each favoured
        // letter has two plain neighbours to take ground from.
        val bias = many.mapIndexed { i, ch -> ch to if (i % 2 == 0) 1f - i / 100f else 0f }.toMap()
        val drawn = autopilotAreas(manyCenters, manyBounds, bias, 0.5f, 100f)
        assertEquals(3, drawn.size)
        assertEquals(listOf('a', 'c', 'e'), drawn.keys.sorted())
    }

    /** Nothing is ever drawn off the edge of the keyboard it belongs to. */
    @Test
    fun `an area is clamped to the board`() {
        // 'a' is on the first column, so it grows against an imaginary
        // neighbour and reaches past the left edge.
        val edge = areas(mapOf('a' to 1f))['a']!!
        assertTrue(edge.area.left < 0f)
        val clamped = edge.clampedTo(width = 300f, height = 100f)
        assertEquals(0f, clamped.area.left, 0.01f)
        assertEquals(100f, clamped.area.bottom, 0.01f)
        assertTrue("the label shrinks with the box", clamped.scale < edge.scale)
        // An area that never left the board comes back exactly as it was.
        val inside = AutopilotArea(Rect(10f, 10f, 60f, 60f), Rect(15f, 15f, 55f, 55f), 1.25f)
        assertEquals(inside, inside.clampedTo(width = 300f, height = 300f))
    }

    /**
     * "Size of the effect" multiplies the growth, not the rectangle, and never
     * touches the area the press is judged against.
     */
    @Test
    fun `the drawn size multiplies the growth alone`() {
        val claimed = areas(mapOf('b' to 1f))['b']!!
        // 90..210 around a 100..200 cell: 10 units of growth on each side.
        assertEquals(90f, claimed.area.left, 0.01f)
        val doubled = claimed.drawnAt(2f)
        assertEquals(80f, doubled.area.left, 0.01f)
        assertEquals(220f, doubled.area.right, 0.01f)
        assertEquals(1.4f, doubled.scale, 0.01f)
        // The cell, and so the area the next multiplication starts from, is kept.
        assertEquals(claimed.cell, doubled.cell)
        assertEquals("×1 is the true size", claimed, claimed.drawnAt(1f))
    }

    /** A side that claimed nothing stays put: the difference is what grows. */
    @Test
    fun `the drawn size leaves an unclaimed side on the cell edge`() {
        // 'b' grows sideways only: above and below it there is no neighbour, so
        // check the axis it did share. 'a' and 'b' are level, so their shared
        // edge did not move and must not move when the drawing is exaggerated.
        val level = areas(mapOf('a' to 1f, 'b' to 1f))['b']!!
        assertEquals(100f, level.area.left, 0.01f)
        assertEquals(100f, level.drawnAt(3f).area.left, 0.01f)
    }

    /** The strength setting the user sees maps onto the pull the hit test uses. */
    @Test
    fun `the strength setting maps onto the hit test`() {
        assertEquals(0.5f, smartHitStrength(5), 0.001f)
        assertEquals(0.1f, smartHitStrength(1), 0.001f)
        assertEquals(1.0f, smartHitStrength(10), 0.001f)
        assertEquals(2.0f, smartHitStrength(20), 0.001f)
        assertEquals("out of range is clamped", 2.0f, smartHitStrength(99), 0.001f)
    }
}
