package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.applyDeviceForm
import com.wasimaster.wmkeyboard.core.ui.LiveState

/**
 * The settings as every screen of the app takes them: one holder for the life
 * of the activity, read a field at a time.
 *
 * A screen reads `settings.watch { it.field }` inside the row that draws the
 * field, and `settings.value.field` inside a click or a coroutine. A write then
 * recomposes the rows showing what it changed, and nothing else: not the other
 * rows, not the screen, not the nav graph. See [LiveState] for the two reads.
 */
internal typealias LiveSettings = LiveState<KeyboardSettings>

/**
 * [stored] as a [LiveSettings], with the device-form defaults applied the same
 * way the keyboard applies them. Null until DataStore's first emission.
 *
 * The holder is created once and stays: the form (a fold opened, a window
 * resized) feeds in through the derivation rather than replacing it, so nothing
 * that captured it goes stale and nothing that took it has a new argument.
 */
@Composable
internal fun rememberLiveSettings(stored: State<KeyboardSettings?>, form: DeviceForm): LiveSettings? {
    val currentForm = rememberUpdatedState(form)
    val loaded by remember { derivedStateOf { stored.value != null } }
    if (!loaded) return null
    return remember {
        LiveState(
            derivedStateOf(structuralEqualityPolicy()) {
                // Never null again once it has been loaded: DataStore does not
                // emit a null, and the lifecycle collector keeps the last value.
                checkNotNull(stored.value).applyDeviceForm(currentForm.value)
            },
        )
    }
}
