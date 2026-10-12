package com.wasimaster.wmkeyboard.core.vocab

import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZstdTest {

    // Both fixtures made with the zstd CLI from TEXT: `zstd -19` (checksum on),
    // and `zstd -1 --no-check`, a skippable frame and the -19 frame again.
    private val text = ("The quick brown fox jumps over the lazy dog. ".repeat(40) +
        "abhor benign castigate deride ephemeral ".repeat(30)).toByteArray()

    private val level19 = Base64.getDecoder().decode(
        "KLUv/WS4CpUCAEIFERGQPQZQ+kPpD6XP193V4/mvHE1M40yK5qaoacpNFUhVKsgfUs+MT5iGOECwXG/RblL9NBeeRmfXMzx/" +
            "GM5xRpSVNykEAgA3ZKn/FVv4qzKeEbWh",
    )

    private val framesAndSkippable = Base64.getDecoder().decode(
        "KLUv/WC4Cq0CAELFEReQpzqIwG4SRCkt+iLkww07u1uiYuw6B+f26Kjul9wXn51Jto44mUwfXsVxLowWIxJ3fGTeF9RLoSbw" +
            "QQNOBt3hAclG8BEQAgA3ZKn/FVv4qzJQKk0YAwAAAAECAyi1L/1kuAqVAgBCBRERkD0GUPpD6Q+lz9fd1eP5rxxNTONMiuam" +
            "qGnKTRVIVSrIH1LPjE+YhjhAsFxv0W5S/TQXnkZn1zM8fxjOcUaUlTcpBAIAN2Sp/xVb+KsynhG1oQ==",
    )

    @Test
    fun `one frame`() {
        assertArrayEquals(text, Zstd.decompress(level19))
    }

    @Test
    fun `several frames with a skippable frame between`() {
        assertArrayEquals(text + text, Zstd.decompress(framesAndSkippable))
    }

    @Test
    fun `magic number sniff`() {
        assertTrue(Zstd.isZstd(level19))
        assertFalse(Zstd.isZstd("PK\u0003\u0004".toByteArray()))
    }

    @Test(expected = Zstd.OutputLimitException::class)
    fun `output past the limit is refused`() {
        Zstd.decompress(level19, maxOutput = 100)
    }

    @Test(expected = IOException::class)
    fun `truncated input is an error, not a short file`() {
        Zstd.decompress(level19.copyOf(level19.size - 10))
    }
}
