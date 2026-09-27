package com.wasimaster.wmkeyboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.net.toUri
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.ime.ClipImageViewer
import java.io.File

/**
 * An image clip, full screen (#371): the clipboard panel's View. The keyboard
 * cannot draw past its own window, so its hold popup starts this instead, and
 * the picture gets the app's own viewer, pinch to zoom and all. Tap it or
 * press back to return to the field.
 *
 * Not exported, and it opens only a file inside the history's own folder:
 * nothing else in the app hands it a path, and nothing else should be shown
 * through it.
 */
class ClipImageViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent?.getStringExtra(ClipImageViewer.EXTRA_PATH)?.let(::File)?.takeIf(::isClipImage)
        if (file == null) {
            finish()
            return
        }
        val description = getString(R.string.clipboard_image_viewer_desc)
        setContent {
            MaterialTheme {
                ImageViewerDialog(listOf(file.toUri().toString()), 0, description) { finish() }
            }
        }
    }

    /** Whether [file] is one of the history's images and still there. */
    private fun isClipImage(file: File): Boolean = runCatching {
        val folder = File(filesDir, ClipImageViewer.IMAGES_DIR).canonicalFile
        file.canonicalFile.parentFile == folder && file.isFile
    }.getOrDefault(false)
}
