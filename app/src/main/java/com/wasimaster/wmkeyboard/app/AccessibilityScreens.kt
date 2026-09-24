package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.accessibility.KeyboardPassthrough
import com.wasimaster.wmkeyboard.core.settings.ColorVisionFilter
import com.wasimaster.wmkeyboard.core.settings.ScreenReaderMode
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import kotlinx.coroutines.launch

/**
 * The dyslexia-friendly face. Atkinson Hyperlegible was designed by the
 * Braille Institute specifically to disambiguate letterforms that collapse
 * into each other at low vision or with reading difficulty (I/l/1, O/0), and
 * unlike OpenDyslexic it is on Google Fonts, so it rides the existing
 * downloadable-fonts path with no bundled asset.
 */
private const val READABLE_FONT = "Atkinson Hyperlegible"

@Composable
internal fun AccessibilitySettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenFonts: () -> Unit,
    onOpenLayout: () -> Unit,
    onOpenKeyPress: () -> Unit,
    onOpenAppearance: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val readableFontId = KeyboardFonts.googleId(READABLE_FONT)
    // What decides which rows the group holds; each row reads its own value.
    val screenReader = settings.watch { it.accessibility.screenReader }

    SettingsGroup(stringResource(R.string.accessibility_vision_title)) {
        item {
            ChoiceSetting(
                title = R.string.accessibility_color_vision_title,
                subtitle = stringResource(R.string.accessibility_color_vision_subtitle),
                info = stringResource(R.string.accessibility_color_vision_info),
                options = listOf(
                    ColorVisionFilter.NONE to stringResource(CommonR.string.common_off),
                    ColorVisionFilter.DEUTERANOPIA to
                        stringResource(R.string.accessibility_color_vision_deutan),
                    ColorVisionFilter.PROTANOPIA to
                        stringResource(R.string.accessibility_color_vision_protan),
                    ColorVisionFilter.TRITANOPIA to
                        stringResource(R.string.accessibility_color_vision_tritan),
                    ColorVisionFilter.GRAYSCALE to
                        stringResource(R.string.accessibility_color_vision_grey),
                ),
                selected = settings.watch { it.accessibility.colorVision },
                default = SettingsDefaults.accessibility.colorVision,
                detail = { filter -> ChoiceDetail(stringResource(colorVisionDescRes(filter))) },
            ) { scope.launch { repository.setColorVisionFilter(it) } }
        }
        item {
            ToggleSetting(
                R.string.accessibility_high_contrast_title,
                stringResource(R.string.accessibility_high_contrast_subtitle),
                settings.watch { it.accessibility.highContrast },
                info = stringResource(R.string.accessibility_high_contrast_info),
                default = SettingsDefaults.accessibility.highContrast,
            ) { scope.launch { repository.setHighContrastKeys(it) } }
        }
        item {
            ToggleSetting(
                R.string.accessibility_key_outlines_title,
                stringResource(R.string.accessibility_key_outlines_subtitle),
                settings.watch { it.accessibility.keyOutlines },
                info = stringResource(R.string.accessibility_key_outlines_info),
                default = SettingsDefaults.accessibility.keyOutlines,
            ) { scope.launch { repository.setKeyOutlines(it) } }
        }
        item {
            // The active theme's "Layout for this theme" group may carry its
            // own answer; the row then says so instead of flipping a switch
            // the board no longer reads (#88).
            val pinned = themePinSubtitle(settings) { it.boldKeyLabels }
            ToggleSetting(
                R.string.accessibility_bold_labels_title,
                pinned ?: stringResource(R.string.accessibility_bold_labels_subtitle),
                settings.watch { it.accessibility.boldLabels },
                info = stringResource(R.string.accessibility_bold_labels_info),
                enabled = pinned == null,
                default = SettingsDefaults.accessibility.boldLabels,
            ) { scope.launch { repository.setBoldKeyLabels(it) } }
        }
        item {
            ToggleSetting(
                R.string.accessibility_readable_font_title,
                stringResource(R.string.accessibility_readable_font_subtitle, READABLE_FONT),
                settings.watch { it.keyFontId == readableFontId },
                info = stringResource(R.string.accessibility_readable_font_info, READABLE_FONT),
                default = SettingsDefaults.keyFontId == readableFontId,
            ) { on ->
                scope.launch {
                    repository.setKeyFontId(if (on) readableFontId else KeyboardFonts.DEFAULT_ID)
                }
            }
        }
        item {
            NavRow(
                R.string.accessibility_text_size_title,
                stringResource(R.string.accessibility_text_size_subtitle),
                "${(settings.watch { it.fontScale } * 100).toInt()}%",
                onClick = onOpenAppearance,
            )
        }
        item {
            NavRow(
                R.string.accessibility_keyboard_font_title,
                stringResource(R.string.accessibility_keyboard_font_subtitle),
                KeyboardFonts.displayName(
                    context,
                    settings.watch { it.keyFontId },
                    settings.watch { it.customFontName },
                ),
                onClick = onOpenFonts,
            )
        }
    }

    SettingsGroup(stringResource(R.string.accessibility_motion_title)) {
        item {
            ToggleSetting(
                R.string.accessibility_reduce_motion_title,
                stringResource(R.string.accessibility_reduce_motion_subtitle),
                settings.watch { it.reduceMotion },
                info = stringResource(R.string.accessibility_reduce_motion_info),
                default = SettingsDefaults.reduceMotion,
            ) { scope.launch { repository.setReduceMotion(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.accessibility_settings_app_title)) {
        item {
            ToggleSetting(
                R.string.accessibility_row_icons_title,
                stringResource(R.string.accessibility_row_icons_subtitle),
                settings.watch { it.appUi.rowIcons },
                info = stringResource(R.string.accessibility_row_icons_info),
                default = SettingsDefaults.appUi.rowIcons,
            ) { scope.launch { repository.setSettingsRowIcons(it) } }
        }
        item {
            ToggleSetting(
                R.string.accessibility_screen_transitions_title,
                stringResource(R.string.accessibility_screen_transitions_subtitle),
                settings.watch { it.appUi.screenTransitions },
                info = stringResource(R.string.accessibility_screen_transitions_info),
                default = SettingsDefaults.appUi.screenTransitions,
            ) { scope.launch { repository.setSettingsScreenTransitions(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.accessibility_screen_reader_title)) {
        item {
            ChoiceSetting(
                title = R.string.accessibility_talkback_title,
                subtitle = stringResource(R.string.accessibility_talkback_subtitle),
                info = stringResource(R.string.accessibility_talkback_info),
                options = listOf(
                    ScreenReaderMode.OFF to stringResource(CommonR.string.common_off),
                    ScreenReaderMode.LABELS to
                        stringResource(R.string.accessibility_talkback_mode_labels),
                    ScreenReaderMode.EXPLORE to
                        stringResource(R.string.accessibility_talkback_mode_explore),
                    ScreenReaderMode.PASSTHROUGH to
                        stringResource(R.string.accessibility_talkback_mode_gestures),
                ),
                selected = screenReader,
                default = SettingsDefaults.accessibility.screenReader,
                detail = { mode -> ChoiceDetail(stringResource(screenReaderDescRes(mode))) },
            ) { scope.launch { repository.setScreenReaderMode(it) } }
        }
        item(visible = screenReader == ScreenReaderMode.PASSTHROUGH) {
            val granted = rememberGrantState(KeyboardPassthrough::isServiceEnabled)
            // Disclosure before the accessibility screen: Play scrutinises
            // this API harder than any permission, and the system's own
            // warning there describes powers this service never asks for.
            val accessibility = rememberDisclosedSpecialAccess(SpecialAccess.ACCESSIBILITY)
            NavRow(
                if (granted) {
                    stringResource(R.string.accessibility_passthrough_service_title)
                } else {
                    stringResource(R.string.accessibility_passthrough_service_title_required)
                },
                if (granted) {
                    stringResource(R.string.accessibility_passthrough_service_on)
                } else {
                    stringResource(
                        R.string.accessibility_passthrough_service_off,
                        stringResource(R.string.passthrough_service_label),
                    )
                },
                icon = SettingsRowIcons[R.string.accessibility_passthrough_service_title],
            ) { accessibility() }
        }
    }

    when (screenReader) {
        ScreenReaderMode.EXPLORE ->
            CaptionText(stringResource(R.string.accessibility_explore_caption))
        ScreenReaderMode.PASSTHROUGH ->
            CaptionText(stringResource(R.string.accessibility_passthrough_caption))
        else -> Unit
    }

    SettingsGroup(stringResource(R.string.accessibility_touch_title)) {
        item {
            val offLabel = stringResource(CommonR.string.common_off)
            val debounceMs = settings.watch { it.accessibility.keyDebounceMs }
            SliderSetting(
                title = R.string.accessibility_debounce_title,
                subtitle = if (debounceMs == 0) {
                    stringResource(R.string.accessibility_debounce_subtitle_off)
                } else {
                    stringResource(R.string.accessibility_debounce_subtitle_on)
                },
                value = debounceMs.toFloat(),
                range = 0f..500f,
                display = { if (it.toInt() == 0) offLabel else "${it.toInt()} ms" },
                info = stringResource(R.string.accessibility_debounce_info),
                default = SettingsDefaults.accessibility.keyDebounceMs.toFloat(),
            ) { scope.launch { repository.setKeyDebounceMs(it.toInt()) } }
        }
        item {
            NavRow(
                R.string.accessibility_long_press_title,
                stringResource(R.string.accessibility_long_press_subtitle),
                "${settings.watch { it.longPressDelayMs }} ms",
                onClick = onOpenKeyPress,
            )
        }
        item {
            NavRow(
                R.string.accessibility_key_size_title,
                stringResource(R.string.accessibility_key_size_subtitle),
                null,
                onClick = onOpenLayout,
            )
        }
        item {
            NavRow(
                R.string.accessibility_haptics_title,
                stringResource(R.string.accessibility_haptics_subtitle),
                null,
                onClick = onOpenKeyPress,
            )
        }
    }
}

/**
 * What each colour vision filter does, one line, for the picker sheet. The
 * option names are the clinical short forms, which say nothing to a reader who
 * does not already know which type they have.
 */
private fun colorVisionDescRes(filter: ColorVisionFilter): Int = when (filter) {
    ColorVisionFilter.NONE -> R.string.accessibility_color_vision_none_desc
    ColorVisionFilter.DEUTERANOPIA -> R.string.accessibility_color_vision_deutan_desc
    ColorVisionFilter.PROTANOPIA -> R.string.accessibility_color_vision_protan_desc
    ColorVisionFilter.TRITANOPIA -> R.string.accessibility_color_vision_tritan_desc
    ColorVisionFilter.GRAYSCALE -> R.string.accessibility_color_vision_grey_desc
}

/** What each screen reader mode does, one line, for the picker sheet. */
private fun screenReaderDescRes(mode: ScreenReaderMode): Int = when (mode) {
    ScreenReaderMode.OFF -> R.string.accessibility_talkback_mode_off_desc
    ScreenReaderMode.LABELS -> R.string.accessibility_talkback_mode_labels_desc
    ScreenReaderMode.EXPLORE -> R.string.accessibility_talkback_mode_explore_desc
    ScreenReaderMode.PASSTHROUGH -> R.string.accessibility_talkback_mode_gestures_desc
}
