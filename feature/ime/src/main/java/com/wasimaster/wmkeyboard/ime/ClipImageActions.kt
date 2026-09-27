package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool

/**
 * The full-screen viewer an image clip's View opens (#371). The activity lives
 * in the settings app's module, which this one cannot see, so the keyboard
 * starts it by name, the way it starts the KDE Connect file picker.
 */
object ClipImageViewer {
    const val ACTIVITY = "com.wasimaster.wmkeyboard.app.ClipImageViewerActivity"

    /** The clip's image file, an absolute path inside the history's own folder. */
    const val EXTRA_PATH = "com.wasimaster.wmkeyboard.extra.CLIP_IMAGE_PATH"

    /** That folder, under the app's files; the viewer opens nothing outside it. */
    const val IMAGES_DIR = "clipboard/images"
}

/**
 * Whether an image clip offers Extract text (#371): this build reads text off
 * pictures, and the user has the OCR tool on. A tool turned off is one the
 * user asked not to see, so its action stays out of the clip's popup too.
 */
internal fun clipOcrAvailable(settings: KeyboardSettings): Boolean =
    isSupportedTool(ToolbarTool.OCR) && ToolbarTool.OCR in settings.enabledTools
