package com.wasimaster.wmkeyboard.ime

import android.content.ClipboardManager
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The keyboard's own copies while incognito keeps the clipboard private
 * (#392): they stay in memory, never reach the system clipboard, and are
 * forgotten when the mode ends.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardClipboardTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val system = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @After
    fun reset() {
        KeyboardClipboard.isPrivate = false
    }

    private fun systemText(): String? =
        system.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    @Test
    fun `a private copy stays off the system clipboard`() {
        KeyboardClipboard.copy(context, "before")
        KeyboardClipboard.isPrivate = true
        KeyboardClipboard.copy(context, "secret")
        assertEquals("secret", KeyboardClipboard.held)
        assertEquals("before", systemText())
    }

    @Test
    fun `leaving private mode forgets the copy`() {
        KeyboardClipboard.isPrivate = true
        KeyboardClipboard.copy(context, "secret")
        KeyboardClipboard.isPrivate = false
        assertNull(KeyboardClipboard.held)
    }

    @Test
    fun `an ordinary copy goes to the system clipboard and is not held`() {
        KeyboardClipboard.copy(context, "hello")
        assertEquals("hello", systemText())
        assertNull(KeyboardClipboard.held)
    }
}
