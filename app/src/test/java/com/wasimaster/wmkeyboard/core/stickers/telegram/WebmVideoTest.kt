package com.wasimaster.wmkeyboard.core.stickers.telegram

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebM reader, against two files ffmpeg wrote: 16 by 16, three frames at
 * 30 a second, one with libvpx's alpha (`-pix_fmt yuva420p`, which is how
 * Telegram's own tools make video stickers) and one without.
 *
 * What is pinned is the part the platform's extractor gets wrong: every frame
 * of the transparent one must come back with its alpha bitstream attached.
 */
class WebmVideoTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("stickers/$name")) { name }.use { it.readBytes() }

    @Test
    fun `reads the frames and the alpha of a transparent VP9 sticker`() {
        val video = checkNotNull(WebmVideo.parse(fixture("alpha-vp9.webm")))
        assertEquals(16, video.width)
        assertEquals(16, video.height)
        assertEquals("V_VP9", video.codec)
        assertTrue(video.hasAlpha)
        assertEquals(3, video.frames.size)
        assertTrue(video.frames.all { it.alpha?.isNotEmpty() == true })
        assertTrue(video.frames.all { it.data.isNotEmpty() })
        assertEquals(listOf(0L, 33L, 67L), video.frames.map { it.timeMs })
    }

    @Test
    fun `an opaque video has no alpha`() {
        val video = checkNotNull(WebmVideo.parse(fixture("opaque-vp9.webm")))
        assertFalse(video.hasAlpha)
        assertEquals(3, video.frames.size)
        assertTrue(video.frames.all { it.alpha == null })
    }

    @Test
    fun `durations come from the gaps, and the last from the file`() {
        val video = WebmVideo.parse(fixture("alpha-vp9.webm"))!!
        val durations = video.durationsMs()
        assertEquals(3, durations.size)
        assertEquals(33, durations[0])
        assertEquals(34, durations[1])
        assertTrue("last ${durations[2]}", durations[2] in 30..40)
    }

    @Test
    fun `anything that is not a WebM is refused`() {
        assertNull(WebmVideo.parse(ByteArray(0)))
        assertNull(WebmVideo.parse("RIFF....WEBPVP8 ".toByteArray()))
        // A gzipped Lottie, which is the other thing a Telegram sticker can be.
        assertNull(WebmVideo.parse(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
    }

    @Test
    fun `a file cut short keeps the frames it holds and nothing made up`() {
        val whole = fixture("alpha-vp9.webm")
        for (cut in listOf(40, whole.size / 2, whole.size - 5)) {
            val video = WebmVideo.parse(whole.copyOf(cut)) ?: continue
            val full = WebmVideo.parse(whole)!!
            assertTrue(video.frames.size <= full.frames.size)
            video.frames.forEachIndexed { i, frame -> assertArrayEquals(full.frames[i].data, frame.data) }
        }
    }
}
