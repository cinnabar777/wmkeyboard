package com.wasimaster.wmkeyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Where the keyboard's own copies go (#392).
 *
 * Normally that is the system clipboard. While incognito keeps the clipboard
 * private ([isPrivate], set by the service from `incognitoPrivateClipboard`),
 * a copy stays here instead, in memory only, and the keyboard's paste keys
 * paste it back. Nothing the keyboard copies reaches the system clipboard,
 * where every other app could read it. Leaving incognito forgets the clip.
 *
 * An object rather than service state because panels copy too (the password
 * generator, the scanner), and they only have a [Context].
 */
object KeyboardClipboard {
    /** Whether copies stay inside the keyboard. Turning it off forgets [held]. */
    @Volatile
    var isPrivate: Boolean = false
        set(value) {
            field = value
            if (!value) held = null
        }

    /** The last private copy, or null. Never set while [isPrivate] is off. */
    @Volatile
    var held: String? = null
        private set

    /** Copies [text]: into [held] while private, else onto the system clipboard. */
    fun copy(context: Context, text: CharSequence, label: String = "") {
        if (isPrivate) {
            held = text.toString()
            return
        }
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }
}
