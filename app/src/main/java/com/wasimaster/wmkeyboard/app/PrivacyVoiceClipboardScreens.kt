package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import com.wasimaster.wmkeyboard.core.input.composer.CjkLearning
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.settings.HoldToTalkRange
import com.wasimaster.wmkeyboard.core.settings.ClipboardView
import com.wasimaster.wmkeyboard.core.settings.ClipGridColumnsRange
import com.wasimaster.wmkeyboard.core.settings.ClipMaxTextCharsSteps
import com.wasimaster.wmkeyboard.core.settings.ClipPreviewLinesRange
import com.wasimaster.wmkeyboard.core.settings.ClipTimeLabel
import com.wasimaster.wmkeyboard.core.settings.CopiedCodeChip
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.ViewCompact
import androidx.compose.material.icons.outlined.ViewStream

/** The permission that lets the clipboard read the user's screenshots. */
private val ImagesPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
private fun hasImagesPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, ImagesPermission) ==
        PackageManager.PERMISSION_GRANTED
@Composable
internal fun PrivacySettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Decides which rows the group holds; the rows read everything else.
    val learnOn = settings.watch { it.learnFromTyping }
    // An unnamed group has no SectionHeader to hold it off the top bar, so the
    // breathing room a named group gets for free is spelled out here.
    Spacer(Modifier.height(12.dp))
    SettingsGroup {
        item {
            NavRow(
                R.string.privacy_permissions_title,
                stringResource(R.string.privacy_permissions_subtitle),
                route = "permissions",
            ) { onNavigate("permissions") }
        }
        item {
            NavRow(
                R.string.netlog_title,
                stringResource(R.string.netlog_subtitle),
                route = "network_activity",
            ) { onNavigate("network_activity") }
        }
        item {
            val lock = LocalAppLock.current
            val lockStatus by lock.status.collectAsStateWithLifecycle()
            val lockConfig by lock.config.collectAsStateWithLifecycle()
            NavRow(
                R.string.privacy_lock_title,
                stringResource(R.string.privacy_lock_subtitle),
                value = stringResource(
                    when {
                        // What the phone can do beats what the flag says; see
                        // [AppLockSettings]. A row reading "On" next to gates
                        // that are standing aside would be a lie.
                        !lockStatus.canEnable -> R.string.privacy_lock_state_unavailable
                        lockConfig?.enabled == true -> R.string.privacy_lock_state_on
                        else -> R.string.privacy_lock_state_off
                    },
                ),
                route = AppLockTargets.ROUTE,
            ) { onNavigate(AppLockTargets.ROUTE) }
        }
    }
    SettingsGroup(
        stringResource(R.string.privacy_learning_group_title),
        info = stringResource(R.string.privacy_on_device_info),
    ) {
        item {
            ToggleSetting(
                R.string.privacy_learn_typing_title,
                stringResource(R.string.privacy_learn_typing_subtitle),
                learnOn,
                info = stringResource(R.string.privacy_learn_typing_info),
                default = SettingsDefaults.learnFromTyping,
            ) { scope.launch { repository.setLearnFromTyping(it) } }
        }
        // Words only reach the system dictionary through the same learn path
        // the switch above owns, and that path returns before the mirror.
        if (learnOn) item {
            ToggleSetting(
                R.string.privacy_system_dictionary_title,
                stringResource(R.string.privacy_system_dictionary_subtitle),
                settings.watch { it.addWordsToSystemDictionary },
                info = stringResource(R.string.privacy_system_dictionary_info),
                default = SettingsDefaults.addWordsToSystemDictionary,
            ) { scope.launch { repository.setAddWordsToSystemDictionary(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_use_system_dictionary_title,
                stringResource(R.string.privacy_use_system_dictionary_subtitle),
                settings.watch { it.suggestionStrip.useSystemDictionary },
                info = stringResource(R.string.privacy_use_system_dictionary_info),
                default = SettingsDefaults.suggestionStrip.useSystemDictionary,
            ) { scope.launch { repository.setUseSystemDictionary(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_dict_shortcuts_title,
                stringResource(R.string.privacy_dict_shortcuts_subtitle),
                settings.watch { it.suggestionStrip.expandUserDictShortcuts },
                info = stringResource(R.string.privacy_dict_shortcuts_info),
                default = SettingsDefaults.suggestionStrip.expandUserDictShortcuts,
            ) { scope.launch { repository.setExpandUserDictShortcuts(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_incognito_title,
                stringResource(R.string.privacy_incognito_subtitle),
                settings.watch { it.incognito },
                info = stringResource(R.string.privacy_incognito_info),
                default = SettingsDefaults.incognito,
            ) { scope.launch { repository.setIncognito(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_auto_incognito_title,
                stringResource(R.string.privacy_auto_incognito_subtitle),
                settings.watch { it.autoIncognito },
                info = stringResource(AUTO_INCOGNITO_INFO),
                default = SettingsDefaults.autoIncognito,
            ) { scope.launch { repository.setAutoIncognito(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.privacy_backup_group_title)) {
        item {
            ToggleSetting(
                R.string.privacy_backup_title,
                stringResource(R.string.privacy_backup_subtitle),
                settings.watch { it.cloudBackup },
                info = stringResource(R.string.privacy_backup_info),
                default = SettingsDefaults.cloudBackup,
            ) { scope.launch { repository.setCloudBackup(it) } }
        }
    }
    OtherAppsGroup(repository, onNavigate)
    SettingsGroup(stringResource(R.string.privacy_data_group_title)) {
        item {
            ActionRow(
                title = R.string.privacy_delete_learned_words_title,
                subtitle = stringResource(R.string.privacy_delete_learned_words_subtitle),
                action = stringResource(R.string.privacy_delete_learned_words_action),
                confirm = stringResource(R.string.privacy_delete_learned_words_confirm),
                lock = AppLockTargets["action_delete_learned_words"],
            ) {
                scope.launch {
                    repository.clearLearnedData()
                    // The Chinese, Japanese and Cantonese picks are one of the
                    // files the repository deletes, but its live store is a
                    // `:core:input` object that `:core:settings` cannot reach,
                    // so the in-memory copy in this process is dropped here.
                    CjkLearning.store?.clear()
                }
            }
        }
    }
}
// ---- voice typing ----

/**
 * The Voice typing screen: the engine, the microphone view, and dictation.
 *
 * A Features row rather than the Voice typing tool page, which now holds one
 * row that opens this — the same split the Emoji tool has. Dictation is a way
 * of typing, and the microphone can be reached from a key or a hardware
 * shortcut with the tool nowhere on the toolbar, so these are not the tool's
 * settings.
 */
@Composable
internal fun VoiceSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val whisperEnabled = com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled()
    // What decides which groups and rows the screen holds; each row reads its
    // own value.
    val engine = settings.watch { it.whisper.engine }
    val voiceUiMode = settings.watch { it.voiceBar.mode }
    val typingMode = settings.watch { it.voiceBar.typingMode }
    val usingWhisper = whisperEnabled && engine == "whisper"
    val usingServer = engine == "server"
    // Every build has the picker now: the server engine needs no model and no
    // native runtime, so the lite build offers system and server.
    run {
        val systemEngine = stringResource(R.string.voice_engine_system)
        val whisperEngine = stringResource(R.string.voice_engine_whisper)
        val serverEngine = stringResource(R.string.voice_engine_server)
        SettingsGroup(stringResource(R.string.voice_engine_group)) {
            item {
                ChoiceSetting(
                    R.string.voice_engine_title,
                    subtitle = stringResource(R.string.voice_engine_subtitle),
                    // What the system engine is only matters while it is the one in use.
                    info = listOfNotNull(
                        stringResource(R.string.voice_engine_info),
                        stringResource(R.string.voice_system_info).takeIf { engine == "system" },
                    ).joinToString("\n\n"),
                    options = listOfNotNull(
                        "system" to systemEngine,
                        ("whisper" to whisperEngine).takeIf { whisperEnabled },
                        "server" to serverEngine,
                    ),
                    selected = engine,
                    default = SettingsDefaults.whisper.engine,
                    detail = { engine ->
                        when (engine) {
                            "whisper" -> ChoiceDetail(
                                stringResource(R.string.voice_engine_whisper_desc),
                                Icons.Outlined.Memory,
                            )
                            "server" -> ChoiceDetail(
                                stringResource(R.string.voice_engine_server_desc),
                                Icons.Outlined.Dns,
                            )
                            else -> ChoiceDetail(
                                stringResource(R.string.voice_engine_system_desc),
                                Icons.Outlined.PhoneAndroid,
                            )
                        }
                    },
                ) { scope.launch { repository.setVoiceEngine(it) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.voice_dictation_group)) {
        item {
            ChoiceSetting(
                R.string.voice_ui_title,
                subtitle = stringResource(R.string.voice_ui_subtitle),
                info = stringResource(R.string.voice_ui_info),
                options = listOf(
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_PANEL to
                        stringResource(R.string.voice_ui_panel),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP to
                        stringResource(R.string.voice_ui_strip),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_BAR to
                        stringResource(R.string.voice_ui_bar),
                ),
                selected = voiceUiMode,
                default = SettingsDefaults.voiceBar.mode,
                detail = { mode ->
                    ChoiceDetail(
                        stringResource(voiceUiDescRes(mode)),
                        when (mode) {
                            com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP -> Icons.Outlined.ViewStream
                            com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_BAR -> Icons.Outlined.ViewCompact
                            else -> Icons.Outlined.Dashboard
                        },
                    )
                },
            ) { scope.launch { repository.setVoiceUiMode(it) } }
        }
        item {
            ChoiceSetting(
                R.string.voice_typing_title,
                subtitle = stringResource(R.string.voice_typing_subtitle),
                info = stringResource(R.string.voice_typing_info),
                options = listOf(
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_BLOCK to
                        stringResource(R.string.voice_typing_block),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_INTERACTIVE to
                        stringResource(R.string.voice_typing_interactive),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN to
                        stringResource(R.string.voice_typing_plain),
                ),
                selected = typingMode,
                default = SettingsDefaults.voiceBar.typingMode,
                detail = { mode ->
                    ChoiceDetail(
                        stringResource(voiceTypingDescRes(mode)),
                        when (mode) {
                            com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_INTERACTIVE -> Icons.Outlined.TouchApp
                            com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN -> Icons.Outlined.TextFields
                            else -> Icons.Outlined.Block
                        },
                    )
                },
            ) { scope.launch { repository.setVoiceTypingMode(it) } }
        }
        item {
            ToggleSetting(
                R.string.voice_hold_picks_title,
                stringResource(R.string.voice_hold_picks_subtitle),
                settings.watch { it.voiceBar.holdPicksTypingMode },
                info = stringResource(R.string.voice_hold_picks_info),
                default = SettingsDefaults.voiceBar.holdPicksTypingMode,
            ) { scope.launch { repository.setVoiceHoldPicksTypingMode(it) } }
        }
        // Only the panel's mic reads a hold: the strip and collapsed-bar mics
        // are plain taps, and the panel is never opened in those modes.
        if (voiceUiMode == com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_PANEL) item {
            val holdMsFormat = stringResource(R.string.typing_value_milliseconds)
            SliderSetting(
                R.string.voice_hold_title,
                subtitle = stringResource(R.string.voice_hold_subtitle),
                value = settings.watch { it.voiceBar.holdToTalkMs }.toFloat(),
                range = HoldToTalkRange.first.toFloat()..HoldToTalkRange.last.toFloat(),
                display = { holdMsFormat.format((it / 50f).roundToInt() * 50) },
                info = stringResource(R.string.voice_hold_info),
                default = SettingsDefaults.voiceBar.holdToTalkMs.toFloat(),
            ) { picked ->
                scope.launch {
                    repository.setHoldToTalkMs((picked / 50f).roundToInt() * 50)
                }
            }
        }
        item {
            // Interactive and plain typing chain sessions whatever this says,
            // so it is drawn off rather than hidden: a missing row would read
            // as chaining being gone, when it is in fact forced on.
            val chainingForced =
                typingMode != com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_BLOCK
            ToggleSetting(
                R.string.voice_continuous_title,
                stringResource(R.string.voice_continuous_subtitle),
                settings.watch { it.voiceContinuous } || chainingForced,
                enabled = !chainingForced,
                default = SettingsDefaults.voiceContinuous,
            ) { scope.launch { repository.setVoiceContinuous(it) } }
        }
        if (typingMode != com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN) item {
            ToggleSetting(
                R.string.voice_punctuation_title,
                stringResource(R.string.voice_punctuation_subtitle),
                settings.watch { it.voiceSpokenPunctuation },
                default = SettingsDefaults.voiceSpokenPunctuation,
            ) { scope.launch { repository.setVoiceSpokenPunctuation(it) } }
        }
    }
    // Offline Whisper has no way to take a hint, so the group only shows for
    // the two engines that read it (#305).
    if (!usingWhisper) {
        SettingsGroup(stringResource(R.string.voice_bias_group)) {
            item {
                ToggleSetting(
                    R.string.voice_bias_personal_title,
                    stringResource(R.string.voice_bias_personal_subtitle),
                    settings.watch { it.whisper.biasPersonalWords },
                    info = stringResource(R.string.voice_bias_personal_info),
                    default = SettingsDefaults.whisper.biasPersonalWords,
                ) { scope.launch { repository.setVoiceBiasPersonalWords(it) } }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.voice_bias_words_label),
                    value = settings.watch { it.whisper.biasWords },
                    hint = stringResource(R.string.voice_bias_words_hint),
                    default = SettingsDefaults.whisper.biasWords,
                ) { repository.setVoiceBiasWords(it) }
            }
        }
    }
    if (usingWhisper) {
        SettingsGroup(stringResource(R.string.voice_offline_group)) {
            item {
                ToggleSetting(
                    R.string.voice_translate_title,
                    stringResource(R.string.voice_translate_subtitle),
                    settings.watch { it.whisper.translate },
                    default = SettingsDefaults.whisper.translate,
                ) { scope.launch { repository.setWhisperTranslate(it) } }
            }
        }
        WhisperModelManager(repository, settings)
    }
    if (usingServer) VoiceServerSettings(repository, settings)
}

/**
 * The transcription server behind the "server" engine (#286): where it is,
 * the model and key it wants, and a button that proves the three work before
 * the user finds out mid-sentence. The server is any that speaks OpenAI's
 * `/audio/transcriptions`; the docs page carries the recipes.
 */
@Composable
private fun VoiceServerSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.voice_server_group)) {
        item {
            TextFieldSetting(
                label = stringResource(R.string.voice_server_url_label),
                value = settings.watch { it.whisper.serverUrl },
                hint = stringResource(R.string.voice_server_url_hint),
                default = SettingsDefaults.whisper.serverUrl,
            ) { repository.setVoiceServerUrl(it) }
        }
        item {
            // No default model: speaches wants a model id, whisper.cpp ignores
            // the field, and a blank one lets each server pick its own.
            TextFieldSetting(
                label = stringResource(R.string.voice_server_model_label),
                value = settings.watch { it.whisper.serverModel },
                hint = stringResource(R.string.voice_server_model_hint),
                default = SettingsDefaults.whisper.serverModel,
            ) { repository.setVoiceServerModel(it) }
        }
        item {
            ApiKeyField(
                label = stringResource(R.string.voice_server_key_label),
                value = settings.watch { it.whisper.serverKey },
                builtInAvailable = false,
                emptyHint = stringResource(R.string.voice_server_key_hint),
            ) { repository.setVoiceServerKey(it) }
        }
        item {
            ToggleSetting(
                R.string.voice_server_language_title,
                stringResource(R.string.voice_server_language_subtitle),
                settings.watch { it.whisper.serverSendLanguage },
                default = SettingsDefaults.whisper.serverSendLanguage,
            ) { scope.launch { repository.setVoiceServerSendLanguage(it) } }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.voice_server_prompt_label),
                value = settings.watch { it.whisper.serverPrompt },
                hint = stringResource(R.string.voice_server_prompt_hint),
                default = SettingsDefaults.whisper.serverPrompt,
            ) { repository.setVoiceServerPrompt(it) }
        }
        item { VoiceServerTestRow(settings) }
    }
}

/**
 * Sends one second of silence to the server and says what came back. Silence
 * transcribes to nothing, so success is simply "it answered"; a failure shows
 * the same line the keyboard would show mid-dictation.
 */
@Composable
private fun VoiceServerTestRow(settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val hasUrl = settings.watch { it.whisper.serverUrl.isNotBlank() }
    val testing = stringResource(R.string.voice_server_test_running)
    val ok = stringResource(R.string.voice_server_test_ok)
    ActionRow(
        R.string.voice_server_test_title,
        subtitle = status ?: stringResource(R.string.voice_server_test_subtitle),
        action = stringResource(R.string.voice_server_test_action),
        enabled = !running && hasUrl,
    ) {
        val w = settings.value.whisper
        running = true
        status = testing
        scope.launch {
            status = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val silence = com.wasimaster.wmkeyboard.core.voice.WavEncoder.encode(
                        FloatArray(com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel.SAMPLE_RATE),
                    )
                    com.wasimaster.wmkeyboard.core.tools.TranscriptionClient.transcribe(
                        w.serverUrl, w.serverKey, w.serverModel, null, silence,
                    )
                }
            }.fold(
                onSuccess = { ok },
                onFailure = { com.wasimaster.wmkeyboard.core.tools.ToolHttp.friendlyMessage(context, it) },
            )
            running = false
        }
    }
}
// ---- clipboard ----

/**
 * The Clipboard screen: the history, the panel, and the sensitive-clip rules.
 *
 * A Features row rather than the Clipboard tool page, which now holds one row
 * that opens this — the same split the Emoji tool has. The history is filled
 * by every copy you make, whether or not the panel's button is on the toolbar,
 * so these are not the tool's settings.
 */
@Composable
internal fun ClipboardSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the groups hold; each row reads its own value.
    val historyOn = settings.watch { it.clipboard.history }
    val userScreenshots = settings.watch { it.clipboard.userScreenshots }
    val trackSource = settings.watch { it.clipboard.trackSource }
    val suggestRecent = settings.watch { it.clipboard.suggestRecent }
    val detectEntities = settings.watch { it.clipboard.detectEntities }
    val sensitiveHandling = settings.watch { it.clipboard.sensitiveHandling }
    // The slider readouts are plain lambdas, so their format strings are
    // resolved here and captured. The format also puts the number through the
    // locale, which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    val minutesFormat = stringResource(R.string.values_minutes)
    val hoursFormat = stringResource(R.string.values_hours)
    // Both grants happen on a system screen, so they are read through
    // rememberGrantState: the rows below disappear as soon as we come back
    // with the permission in hand, instead of on the next unrelated redraw.
    val screenshotsGranted = rememberGrantState(::hasImagesPermission)
    val usageAccessGranted = rememberGrantState(::hasUsageAccess)
    SettingsGroup(stringResource(R.string.clipboard_history_group)) {
        item {
            ToggleSetting(
                R.string.clipboard_history_title,
                stringResource(R.string.clipboard_history_subtitle),
                historyOn,
                default = SettingsDefaults.clipboard.history,
            ) { scope.launch { repository.setClipboardHistory(it) } }
        }
        item {
            SliderSetting(
                R.string.clipboard_max_title,
                subtitle = stringResource(R.string.clipboard_max_subtitle),
                value = settings.watch { it.clipboard.maxItems }.toFloat(),
                range = 5f..500f,
                display = { numberFormat.format(it.toInt()) },
                info = stringResource(R.string.clipboard_max_info),
                default = SettingsDefaults.clipboard.maxItems.toFloat(),
            ) { scope.launch { repository.setClipboardMaxItems(it.toInt()) } }
        }
        item {
            // The readout lambda is not composable, so the "never" word
            // is resolved here and captured, like the hours format.
            val never = stringResource(R.string.clipboard_expiry_never)
            SliderSetting(
                R.string.clipboard_expiry_title,
                subtitle = stringResource(R.string.clipboard_expiry_subtitle),
                value = settings.watch { it.clipboard.expiryHours }.toFloat(),
                range = 0f..168f,
                display = { if (it.toInt() == 0) never else hoursFormat.format(it.toInt()) },
                default = SettingsDefaults.clipboard.expiryHours.toFloat(),
            ) { scope.launch { repository.setClipboardExpiryHours(it.toInt()) } }
        }
        item {
            // A slider over a handful of stops rather than every number: the
            // choice is "about how much", and a round figure is one the user
            // can set again.
            val none = stringResource(R.string.clipboard_max_chars_none)
            val steps = ClipMaxTextCharsSteps
            val stored = settings.watch { it.clipboard.maxTextChars }
            val index = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - stored) } ?: 0
            SliderSetting(
                R.string.clipboard_max_chars_title,
                subtitle = stringResource(R.string.clipboard_max_chars_subtitle),
                value = index.toFloat(),
                range = 0f..steps.lastIndex.toFloat(),
                display = {
                    val chars = steps[it.roundToInt().coerceIn(steps.indices)]
                    if (chars == 0) none else numberFormat.format(chars)
                },
                info = stringResource(R.string.clipboard_max_chars_info),
                default = steps.indexOf(SettingsDefaults.clipboard.maxTextChars).coerceAtLeast(0).toFloat(),
            ) { scope.launch { repository.setClipboardMaxTextChars(steps[it.roundToInt().coerceIn(steps.indices)]) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_pinned_last_title,
                stringResource(R.string.clipboard_pinned_last_subtitle),
                settings.watch { it.clipboard.pinnedLast },
                default = SettingsDefaults.clipboard.pinnedLast,
            ) { scope.launch { repository.setClipboardPinnedLast(it) } }
        }
        // Screenshots, the source app and the paste chip are all read as a
        // clip is being stored, and nothing is stored with history off.
        if (historyOn) item {
            val context = LocalContext.current
            ToggleSetting(
                R.string.clipboard_screenshots_title,
                stringResource(R.string.clipboard_screenshots_subtitle),
                userScreenshots,
                default = SettingsDefaults.clipboard.userScreenshots,
            ) { on ->
                scope.launch { repository.setClipboardUserScreenshots(on) }
                if (on && !hasImagesPermission(context)) {
                    runCatching {
                        context.startActivity(Intent(context, ImagesPermissionActivity::class.java))
                    }
                }
            }
        }
        // The guard sits outside item {} on purpose: an item whose body
        // draws nothing still gets its own card, which showed up as a
        // sliver of empty surface once the permission was granted.
        if (historyOn && userScreenshots &&
            !screenshotsGranted
        ) {
            item {
                val context = LocalContext.current
                NavRow(
                    R.string.clipboard_storage_permission_title,
                    stringResource(R.string.clipboard_storage_permission_subtitle),
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        if (historyOn) item {
            val context = LocalContext.current
            val usageAccess = rememberDisclosedSpecialAccess(SpecialAccess.USAGE)
            ToggleSetting(
                R.string.clipboard_track_source_title,
                stringResource(R.string.clipboard_track_source_subtitle),
                trackSource,
                info = stringResource(R.string.clipboard_track_source_info),
                default = SettingsDefaults.clipboard.trackSource,
            ) { on ->
                scope.launch { repository.setClipboardTrackSource(on) }
                // Disclosure then the grant screen, the first time they
                // switch it on — but not when it is already granted, which
                // is the common case for a toggle flipped off and on again.
                if (on && !hasUsageAccess(context)) usageAccess()
            }
        }
        if (historyOn && trackSource &&
            !usageAccessGranted
        ) {
            item {
                val usageAccessRow = rememberDisclosedSpecialAccess(SpecialAccess.USAGE)
                NavRow(
                    R.string.clipboard_usage_permission_title,
                    stringResource(R.string.clipboard_usage_permission_subtitle),
                ) { usageAccessRow() }
            }
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_suggest_group)) {
        if (!historyOn) return@SettingsGroup
        item {
            ToggleSetting(
                R.string.clipboard_suggest_recent_title,
                stringResource(R.string.clipboard_suggest_recent_subtitle),
                suggestRecent,
                default = SettingsDefaults.clipboard.suggestRecent,
            ) { scope.launch { repository.setClipboardSuggestRecent(it) } }
        }
        item(visible = suggestRecent) {
            val untilDismissed =
                stringResource(R.string.clipboard_chip_until_dismissed)
            val chipMinutesFormat = stringResource(R.string.values_minutes)
            val secondsFormat = stringResource(R.string.values_seconds)
            SliderSetting(
                R.string.clipboard_chip_life_title,
                subtitle = stringResource(R.string.clipboard_chip_life_subtitle),
                value = settings.watch { it.clipboard.pasteChipSeconds }.toFloat(),
                // Steps of 30 s to 30 min, with 0 at the top of the
                // range reading as a word rather than a duration.
                range = 0f..1800f,
                display = { value ->
                    val secs = (value / 30f).roundToInt() * 30
                    when {
                        secs <= 0 -> untilDismissed
                        secs < 60 -> secondsFormat.format(secs)
                        else -> chipMinutesFormat.format(secs / 60)
                    }
                },
                info = stringResource(R.string.clipboard_chip_life_info),
                default = SettingsDefaults.clipboard.pasteChipSeconds.toFloat(),
            ) { value ->
                val secs = (value / 30f).roundToInt() * 30
                scope.launch { repository.setPasteChipSeconds(secs) }
            }
        }
        item(visible = suggestRecent) {
            ChoiceSetting(
                title = R.string.clipboard_suggest_codes_title,
                subtitle = stringResource(R.string.clipboard_suggest_codes_subtitle),
                info = stringResource(R.string.clipboard_suggest_codes_info),
                options = CopiedCodeChip.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.clipboard.copiedCodeChip },
                default = SettingsDefaults.clipboard.copiedCodeChip,
                detail = { chip -> ChoiceDetail(stringResource(copiedCodeChipDescRes(chip))) },
            ) { scope.launch { repository.setClipboardCopiedCodeChip(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_entities_title,
                stringResource(R.string.clipboard_entities_subtitle),
                detectEntities,
                info = stringResource(R.string.clipboard_entities_info),
                default = SettingsDefaults.clipboard.detectEntities,
            ) { scope.launch { repository.setClipboardDetectEntities(it) } }
        }
        // The number chips are the ones that go wrong, because a phone
        // number is the one fragment with no shape of its own. This row
        // is where the user gives it one.
        item(visible = detectEntities) {
            val count = settings.watch { it.clipboard.phoneFormats.size }
            NavRow(
                R.string.clipboard_phone_formats_title,
                subtitle = if (count == 0) {
                    stringResource(R.string.clipboard_phone_formats_subtitle)
                } else {
                    pluralStringResource(
                        R.plurals.clipboard_phone_formats_count_subtitle,
                        count,
                        count,
                    )
                },
                route = "phoneformats",
                onClick = { onNavigate("phoneformats") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_panel_group)) {
        // The panel's grid — and the abc / space / backspace row the old toggle
        // here switched on — is a panel layout now (issue #63).
        item {
            NavRow(
                title = R.string.panel_layout_row_title,
                subtitle = stringResource(R.string.panel_layout_row_subtitle),
            ) { onNavigate(panelEditRoute(PanelKind.CLIPBOARD)) }
        }
        item {
            ToggleSetting(
                R.string.clipboard_full_bleed_title,
                stringResource(R.string.clipboard_full_bleed_subtitle),
                settings.watch { it.clipboard.fullBleed },
                info = stringResource(R.string.clipboard_full_bleed_info),
                default = SettingsDefaults.clipboard.fullBleed,
            ) { scope.launch { repository.setClipboardFullBleed(it) } }
        }
        item {
            ChoiceSetting(
                title = R.string.clipboard_view_title,
                subtitle = stringResource(R.string.clipboard_view_subtitle),
                info = stringResource(R.string.clipboard_view_info),
                options = ClipboardView.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.clipboard.view },
                default = SettingsDefaults.clipboard.view,
            ) { scope.launch { repository.setClipboardView(it) } }
        }
        item {
            SliderSetting(
                R.string.clipboard_columns_title,
                subtitle = stringResource(R.string.clipboard_columns_subtitle),
                value = settings.watch { it.clipboard.gridColumns }.toFloat(),
                range = ClipGridColumnsRange.first.toFloat()..ClipGridColumnsRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.clipboard_columns_info),
                enabled = settings.watch { it.clipboard.view == ClipboardView.GRID },
                default = SettingsDefaults.clipboard.gridColumns.toFloat(),
            ) { scope.launch { repository.setClipboardGridColumns(it.roundToInt()) } }
        }
        item {
            val auto = stringResource(R.string.clipboard_lines_auto)
            SliderSetting(
                R.string.clipboard_lines_title,
                subtitle = stringResource(R.string.clipboard_lines_subtitle),
                value = settings.watch { it.clipboard.previewLines }.toFloat(),
                range = ClipPreviewLinesRange.first.toFloat()..ClipPreviewLinesRange.last.toFloat(),
                display = { if (it.roundToInt() == 0) auto else numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.clipboard_lines_info),
                default = SettingsDefaults.clipboard.previewLines.toFloat(),
            ) { scope.launch { repository.setClipboardPreviewLines(it.roundToInt()) } }
        }
        item {
            ChoiceSetting(
                title = R.string.clipboard_time_title,
                subtitle = stringResource(R.string.clipboard_time_subtitle),
                info = stringResource(R.string.clipboard_time_info),
                options = ClipTimeLabel.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.clipboard.timeLabel },
                default = SettingsDefaults.clipboard.timeLabel,
            ) { scope.launch { repository.setClipboardTimeLabel(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_numbers_title,
                stringResource(R.string.clipboard_numbers_subtitle),
                settings.watch { it.clipboard.showNumbers },
                info = stringResource(R.string.clipboard_numbers_info),
                default = SettingsDefaults.clipboard.showNumbers,
            ) { scope.launch { repository.setClipboardShowNumbers(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_swipe_delete_title,
                stringResource(R.string.clipboard_swipe_delete_subtitle),
                settings.watch { it.clipboard.swipeToDelete },
                info = stringResource(R.string.clipboard_swipe_delete_info),
                default = SettingsDefaults.clipboard.swipeToDelete,
            ) { scope.launch { repository.setClipboardSwipeToDelete(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_undo_delete_title,
                stringResource(R.string.clipboard_undo_delete_subtitle),
                settings.watch { it.clipboard.undoDelete },
                info = stringResource(R.string.clipboard_undo_delete_info),
                default = SettingsDefaults.clipboard.undoDelete,
            ) { scope.launch { repository.setClipboardUndoDelete(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_search_title,
                stringResource(R.string.clipboard_search_subtitle),
                settings.watch { it.clipboard.search },
                default = SettingsDefaults.clipboard.search,
            ) { scope.launch { repository.setClipboardSearch(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_link_previews_title,
                stringResource(R.string.clipboard_link_previews_subtitle),
                settings.watch { it.clipboard.linkPreviews },
                default = SettingsDefaults.clipboard.linkPreviews,
            ) { scope.launch { repository.setClipboardLinkPreviews(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_toast_title,
                stringResource(R.string.clipboard_toast_subtitle),
                settings.watch { it.feedback.toastOnCopy },
                info = stringResource(R.string.clipboard_toast_info),
                default = SettingsDefaults.feedback.toastOnCopy,
            ) { scope.launch { repository.setToastOnCopy(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_sensitive_group)) {
        item {
            ToggleSetting(
                R.string.clipboard_password_paste_title,
                stringResource(R.string.clipboard_password_paste_subtitle),
                settings.watch { it.clipboard.clearAfterPasswordPaste },
                info = stringResource(R.string.clipboard_password_paste_info),
                default = SettingsDefaults.clipboard.clearAfterPasswordPaste,
            ) { scope.launch { repository.setClipboardClearAfterPasswordPaste(it) } }
        }
        item {
            ChoiceSetting(
                title = R.string.clipboard_sensitive_title,
                subtitle = stringResource(R.string.clipboard_sensitive_subtitle),
                info = stringResource(R.string.clipboard_sensitive_info),
                options = SensitiveClipHandling.entries.map { it to stringResource(it.labelRes) },
                selected = sensitiveHandling,
                default = SettingsDefaults.clipboard.sensitiveHandling,
                // The enum has carried the line under each answer since it was
                // written; the picker only now has somewhere to draw it.
                detail = { handling -> ChoiceDetail(stringResource(handling.detailRes)) },
            ) { scope.launch { repository.setClipboardSensitiveHandling(it) } }
        }
        // Detection runs in the same listener, which returns with history off.
        if (historyOn &&
            sensitiveHandling != SensitiveClipHandling.KEEP
        ) {
            item {
                ToggleSetting(
                    R.string.clipboard_detect_sensitive_title,
                    stringResource(R.string.clipboard_detect_sensitive_subtitle),
                    settings.watch { it.clipboard.detectSensitive },
                    info = stringResource(R.string.clipboard_detect_sensitive_info),
                    default = SettingsDefaults.clipboard.detectSensitive,
                ) { scope.launch { repository.setClipboardDetectSensitive(it) } }
            }
        }
        item(visible = sensitiveHandling == SensitiveClipHandling.SHORT_LIVED) {
            SliderSetting(
                R.string.clipboard_sensitive_expiry_title,
                subtitle = stringResource(
                    R.string.clipboard_sensitive_expiry_subtitle,
                ),
                value = settings.watch { it.clipboard.sensitiveExpiryMinutes }.toFloat(),
                range = 1f..120f,
                display = { minutesFormat.format(it.toInt()) },
                default = SettingsDefaults.clipboard.sensitiveExpiryMinutes.toFloat(),
            ) {
                scope.launch { repository.setClipboardSensitiveExpiryMinutes(it.toInt()) }
            }
        }
    }
}

/** What the microphone button opens under each answer, for the picker sheet. */
private fun voiceUiDescRes(mode: String): Int = when (mode) {
    VoiceBarSettings.MODE_STRIP -> R.string.voice_ui_strip_desc
    VoiceBarSettings.MODE_BAR -> R.string.voice_ui_bar_desc
    else -> R.string.voice_ui_panel_desc
}

/** How voice typing and the keys share the field, for the picker sheet. */
private fun voiceTypingDescRes(mode: String): Int = when (mode) {
    VoiceBarSettings.TYPING_INTERACTIVE -> R.string.voice_typing_interactive_desc
    VoiceBarSettings.TYPING_PLAIN -> R.string.voice_typing_plain_desc
    else -> R.string.voice_typing_block_desc
}

/** Where a copied code may be offered, for the picker sheet. */
private fun copiedCodeChipDescRes(chip: CopiedCodeChip): Int = when (chip) {
    CopiedCodeChip.OFF -> R.string.clipboard_suggest_codes_off_desc
    CopiedCodeChip.CODE_FIELDS -> R.string.clipboard_suggest_codes_fields_desc
    CopiedCodeChip.ANY_FIELD -> R.string.clipboard_suggest_codes_any_desc
}
