package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBufferTest {

    @Test
    fun testSnapshotAndRestore() {
        val buffer = LearningBuffer()
        buffer.push(
            word = "hello",
            langId = "en",
            weight = 1,
            caseTrusted = true,
            known = false,
            origin = WordOrigin.TYPED,
            replaces = "helo",
        )
        val shapeBytes = byteArrayOf(0x01, 0x02, 0x0f, 0xff.toByte())
        buffer.attachGlide("hello", GlideShapeSample(12345L, shapeBytes))

        buffer.push(
            word = "world",
            langId = "en",
            weight = 2,
            caseTrusted = false,
            known = true,
            origin = WordOrigin.GLIDE,
        )

        val json = buffer.snapshotToJson()
        assertTrue(json.contains("hello"))
        assertTrue(json.contains("world"))
        assertTrue(json.contains("01020fff"))

        val restoredBuffer = LearningBuffer()
        restoredBuffer.restoreFromJson(json)

        assertEquals(2, restoredBuffer.size)
        val drained = restoredBuffer.drain()
        assertEquals(2, drained.size)

        val first = drained[0]
        assertEquals("hello", first.word)
        assertEquals("en", first.langId)
        assertEquals(1, first.weight)
        assertTrue(first.caseTrusted)
        assertFalse(first.known)
        assertEquals(WordOrigin.TYPED, first.origin)
        assertEquals("helo", first.replaces)
        assertNotNull(first.glideShape)
        assertEquals(12345L, first.glideShape!!.layoutKey)
        assertArrayEquals(shapeBytes, first.glideShape!!.shape)

        val second = drained[1]
        assertEquals("world", second.word)
        assertEquals("en", second.langId)
        assertEquals(2, second.weight)
        assertFalse(second.caseTrusted)
        assertTrue(second.known)
        assertEquals(WordOrigin.GLIDE, second.origin)
    }
}
