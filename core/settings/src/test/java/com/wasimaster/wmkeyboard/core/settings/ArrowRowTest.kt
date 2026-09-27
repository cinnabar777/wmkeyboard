package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrowRowTest {

    @Test
    fun `an order round-trips through its stored form`() {
        val order = listOf(ArrowKey.UP, ArrowKey.DOWN, ArrowKey.LEFT, ArrowKey.RIGHT)
        assertEquals(order, decodeArrowRowOrder(encodeArrowRowOrder(order)))
    }

    @Test
    fun `nothing stored reads as the default order`() {
        assertEquals(DefaultArrowRowOrder, decodeArrowRowOrder(null))
        assertEquals(DefaultArrowRowOrder, decodeArrowRowOrder(""))
    }

    @Test
    fun `a damaged value still gives all four arrows once each`() {
        // Unknown names drop, duplicates collapse, and the missing arrows come
        // back in their default places at the end.
        assertEquals(
            listOf(ArrowKey.RIGHT, ArrowKey.LEFT, ArrowKey.UP, ArrowKey.DOWN),
            decodeArrowRowOrder("RIGHT,SIDEWAYS,RIGHT,LEFT"),
        )
    }
}
