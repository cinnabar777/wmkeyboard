package com.wasimaster.wmkeyboard.app

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.translate.OfflineModelState
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateLanguages
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.core.translate.TranslateModuleState
import com.wasimaster.wmkeyboard.core.translate.downloadNotified
import kotlinx.coroutines.launch

/**
 * The on-device translation models: what is on the phone, what is coming, and
 * a button for the rest.
 *
 * Two groups rather than one list of sixty. The first holds what this user is
 * likely to want: every model already here or on its way, the language they
 * translate into, and the languages they type in. The second is a fold with
 * everything else, because the language somebody translates *from* is, often
 * enough, exactly one they cannot type.
 *
 * State comes from [OnDeviceTranslator.models], which the keyboard reads too,
 * so a download started in the panel shows as running here and the other way
 * round. Nothing on this screen owns a download; leaving it cancels nothing.
 */
@Composable
internal fun TranslateModelManager(settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by OnDeviceTranslator.models.collectAsState()
    val module by OnDeviceTranslator.moduleState.collectAsState()
    // Keyed on the module: on Play the first refresh finds no engine to ask,
    // and the one that matters is the one after it arrives.
    LaunchedEffect(module) { OnDeviceTranslator.refresh(context.applicationContext) }

    val downloadDecision = rememberDownloadDecision(settings)
    var confirmMetered by remember { mutableStateOf<String?>(null) }
    var blockedMetered by remember { mutableStateOf(false) }

    // A Play install carries the engine as an on-demand module. Until it is
    // here there are no models to list, only the one thing to fetch first.
    if (module != TranslateModuleState.Installed) {
        TranslateModuleBanner(
            module = module,
            onDownload = {
                when (downloadDecision()) {
                    MeteredDecision.ALLOWED -> OnDeviceTranslator.requestModule()
                    MeteredDecision.ASK -> confirmMetered = MODULE_CONFIRM
                    MeteredDecision.BLOCKED -> blockedMetered = true
                }
            },
        )
        if (confirmMetered == MODULE_CONFIRM) {
            MeteredDownloadDialog(
                detail = stringResource(R.string.tooldetail_translate_module_metered_body),
                onConfirm = {
                    confirmMetered = null
                    OnDeviceTranslator.requestModule()
                },
                onDismiss = { confirmMetered = null },
            )
        }
        if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }
        return
    }
    val offerPermission = rememberNotificationPermissionOffer()
    val start: (String) -> Unit = { code ->
        offerPermission()
        OnDeviceTranslator.downloadNotified(
            context = context.applicationContext,
            code = code,
            title = context.getString(
                CommonR.string.common_notify_download_translate,
                TranslateClient.languageName(code),
            ),
        )
    }

    // Picker order, so the two groups read the way every other language list
    // in the tool does. The picker's zh-CN is the model's zh.
    val all = remember {
        TranslateClient.languages.mapNotNull { (code, name) ->
            OfflineTranslateLanguages.modelCode(code)?.let { it to name }
        }.distinctBy { it.first }
    }
    val wanted = settings.watch { s ->
        (listOf(s.translateTargetLang) + s.enabledLanguages.map { it.id })
            .mapNotNull { OfflineTranslateLanguages.modelCode(it) }
            .toSet()
    }
    val (near, far) = all.partition { (code, _) ->
        code in wanted || code == OfflineTranslateLanguages.PIVOT ||
            models[code].let { it is OfflineModelState.Downloaded || it is OfflineModelState.Downloading }
    }

    val row: @Composable (String, String) -> Unit = { code, name ->
        TranslateModelRow(
            code = code,
            name = name,
            state = models[code],
            onDownload = {
                when (downloadDecision()) {
                    MeteredDecision.ALLOWED -> start(code)
                    MeteredDecision.ASK -> confirmMetered = code
                    MeteredDecision.BLOCKED -> blockedMetered = true
                }
            },
            onCancel = { OnDeviceTranslator.cancelDownload(context.applicationContext, code) },
            onDelete = { scope.launch { OnDeviceTranslator.delete(context.applicationContext, code) } },
        )
    }
    SettingsGroup(
        stringResource(R.string.tooldetail_translate_models_group),
        info = stringResource(R.string.tooldetail_translate_models_info),
    ) {
        for ((code, name) in near) item { row(code, name) }
    }
    if (far.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.tooldetail_translate_models_more_group),
            foldKey = "translate_models_more",
        ) {
            for ((code, name) in far) item { row(code, name) }
        }
    }

    confirmMetered?.let { code ->
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.tooldetail_translate_model_metered_body,
                TranslateClient.languageName(code),
                Formatter.formatShortFileSize(context, OfflineTranslateLanguages.APPROX_MODEL_BYTES),
            ),
            onConfirm = {
                confirmMetered = null
                start(code)
            },
            onDismiss = { confirmMetered = null },
        )
    }
    if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }
}

/** Stands in for a model code in the metered confirm, for the module itself. */
private const val MODULE_CONFIRM = "\u0000module"

/**
 * The engine's own download, on a Play install that has not fetched it. Live
 * state with the button that fixes it, which is what a banner is for.
 */
@Composable
private fun TranslateModuleBanner(module: TranslateModuleState, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (module) {
        is TranslateModuleState.Installing -> StateBanner(
            if (module.totalBytes > 0L) {
                stringResource(
                    R.string.tooldetail_translate_module_installing_sized_info,
                    Formatter.formatShortFileSize(context, module.bytes),
                    Formatter.formatShortFileSize(context, module.totalBytes),
                )
            } else {
                stringResource(R.string.tooldetail_translate_module_installing_info)
            },
        )
        is TranslateModuleState.Missing -> StateBanner(
            stringResource(
                if (module.failed) {
                    R.string.tooldetail_translate_module_failed_info
                } else {
                    R.string.tooldetail_translate_module_missing_info
                },
            ),
            action = stringResource(
                if (module.failed) CommonR.string.common_retry else CommonR.string.common_download,
            ),
            tone = if (module.failed) BannerTone.WARNING else BannerTone.INFO,
            onAction = onDownload,
        )
        TranslateModuleState.Installed -> Unit
    }
}

@Composable
private fun TranslateModelRow(
    code: String,
    name: String,
    state: OfflineModelState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val builtIn = code == OfflineTranslateLanguages.PIVOT
    val reduceMotion = LocalReduceMotion.current
    // What the model is in now, read at tap time by the actions below.
    val latest = rememberUpdatedState(state)
    WmRow(
        title = name,
        subtitle = when {
            // Every model translates through English, so English is part of
            // the engine and not a thing to fetch or remove.
            builtIn -> stringResource(R.string.tooldetail_translate_model_builtin_label)
            state == null -> stringResource(R.string.privacy_handwriting_status_checking)
            state is OfflineModelState.Downloaded ->
                stringResource(R.string.privacy_handwriting_status_downloaded)
            state is OfflineModelState.Downloading -> when {
                state.bytes <= 0L -> stringResource(R.string.privacy_handwriting_status_preparing)
                state.totalBytes > 0L -> stringResource(
                    R.string.privacy_handwriting_status_downloading_of_total,
                    Formatter.formatShortFileSize(context, state.bytes),
                    Formatter.formatShortFileSize(context, state.totalBytes),
                )
                else -> stringResource(
                    R.string.privacy_handwriting_status_downloading,
                    Formatter.formatShortFileSize(context, state.bytes),
                )
            }
            state is OfflineModelState.Missing && state.failed ->
                stringResource(R.string.privacy_handwriting_status_failed)
            else -> stringResource(
                R.string.privacy_handwriting_status_missing_sized,
                Formatter.formatShortFileSize(context, OfflineTranslateLanguages.APPROX_MODEL_BYTES),
            )
        },
        trailing = {
            if (!builtIn) {
                // The row's one control changes with the model: a spinner while
                // it is looked up, then Download, then progress with a cancel,
                // then a bin. Cross-faded from one to the next, keyed on which
                // of those it is so the progress ticking along does not fade.
                // Keyed on the code as well: the groups are not lazy lists, so
                // when a model moves between them this slot can be handed a
                // different language, which must start still rather than
                // cross-fade out of the one that was here.
                key(code) {
                    AnimatedContent(
                        targetState = state,
                        contentKey = { it?.let { shown -> shown::class } },
                        contentAlignment = Alignment.CenterEnd,
                        transitionSpec = { stateSwapTransform(reduceMotion) },
                        label = "translateModelAction",
                    ) { shown ->
                        // From [shown], not [state]: the control on its way
                        // out keeps drawing the state it belonged to. It also
                        // still takes taps while it fades, so each action first
                        // checks the model is still in that state: a quick
                        // second tap on Download must not start it twice.
                        val kind = shown?.let { it::class }
                        fun live(action: () -> Unit): () -> Unit = {
                            if (latest.value?.let { it::class } == kind) action()
                        }
                        TranslateModelAction(shown, name, live(onDownload), live(onCancel), live(onDelete))
                    }
                }
            }
        },
    )
}

/** The control at the end of a [TranslateModelRow], for one [state] of its model. */
@Composable
private fun TranslateModelAction(
    state: OfflineModelState?,
    name: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    when (state) {
        null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        is OfflineModelState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
            val fraction = state.fraction
            if (fraction != null) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(
                        R.string.tooldetail_translate_model_cancel_desc,
                        name,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is OfflineModelState.Downloaded -> IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.privacy_handwriting_delete_desc, name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is OfflineModelState.Missing -> TextButton(onClick = onDownload) {
            Text(
                stringResource(
                    if (state.failed) CommonR.string.common_retry else CommonR.string.common_download,
                ),
            )
        }
    }
}
