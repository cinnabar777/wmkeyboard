package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemClipMatchTest {

    private fun text(id: Long, text: String, time: Long = id, pinned: Boolean = false) =
        ClipItem(id = id, text = text, timestamp = time, pinned = pinned)

    private fun image(id: Long, time: Long = id, pinned: Boolean = false) =
        ClipItem(id = id, text = "", timestamp = time, kind = ClipKind.IMAGE, imagePath = "/x/$id.png", pinned = pinned)

    private fun clears(
        primary: SystemClip,
        removed: List<ClipItem>,
        kept: List<ClipItem> = emptyList(),
        clearAll: Boolean = false,
        maxTextChars: Int = 0,
    ) = clearsSystemClip(primary, removed, kept, clearAll, maxTextChars)

    @Test
    fun `deleting the clip on the clipboard clears it`() {
        assertTrue(clears(SystemClip("secret", null), listOf(text(2, "secret"))))
    }

    @Test
    fun `the clipboard's own spaces do not matter`() {
        assertTrue(clears(SystemClip("  secret\n", null), listOf(text(2, "secret"))))
    }

    @Test
    fun `deleting an older clip leaves a later copy alone`() {
        assertFalse(clears(SystemClip("newer", null), listOf(text(1, "older")), kept = listOf(text(2, "newer"))))
    }

    @Test
    fun `a clip cut to the length cap still matches the whole copy`() {
        assertTrue(clears(SystemClip("abcdefgh", null), listOf(text(1, "abcd")), maxTextChars = 4))
        assertFalse(clears(SystemClip("abcdefgh", null), listOf(text(1, "abcd"))))
    }

    @Test
    fun `an empty clipboard is left alone`() {
        assertFalse(clears(SystemClip(null, null), listOf(text(1, "a")), clearAll = true))
        assertFalse(clears(SystemClip("", null), listOf(text(1, "a")), clearAll = true))
    }

    @Test
    fun `a picture is on the clipboard only while it is the newest clip`() {
        val primary = SystemClip(null, "content://gallery/1")
        assertTrue(clears(primary, listOf(image(3)), kept = listOf(text(2, "a"))))
        assertFalse(clears(primary, listOf(image(1)), kept = listOf(text(2, "a"))))
    }

    @Test
    fun `a file clip matches by its address`() {
        val file = ClipItem(id = 1, text = "", timestamp = 1, kind = ClipKind.FILE, uriString = "content://f/1")
        assertTrue(clears(SystemClip(null, "content://f/1"), listOf(file)))
        assertFalse(clears(SystemClip(null, "content://f/2"), listOf(file)))
    }

    @Test
    fun `clear all empties whatever the clipboard holds`() {
        assertTrue(clears(SystemClip("never saved", null), listOf(text(1, "a")), clearAll = true))
    }

    @Test
    fun `clear all keeps a pinned clip on the clipboard`() {
        val pinned = text(5, "keep me", pinned = true)
        assertFalse(clears(SystemClip("keep me", null), listOf(text(1, "a")), kept = listOf(pinned), clearAll = true))
        assertFalse(
            clears(SystemClip(null, "content://p/1"), listOf(text(1, "a")), kept = listOf(image(9, pinned = true)), clearAll = true),
        )
    }
}
