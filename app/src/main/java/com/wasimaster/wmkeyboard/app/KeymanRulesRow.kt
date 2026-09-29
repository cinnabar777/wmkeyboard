package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.addons.KeymanRuleDownloader
import com.wasimaster.wmkeyboard.core.notify.DownloadKeys
import com.wasimaster.wmkeyboard.core.keyman.KeymanRuleStore
import com.wasimaster.wmkeyboard.core.notify.DownloadNotifications
import com.wasimaster.wmkeyboard.core.layout.KeymanBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The row under a converted Keyman layout that fetches its typing rules.
 *
 * The grid ships in the app; the rules do not. Without them each key types the
 * character printed on it, which is right for a positional keyboard and wrong
 * for a mnemonic one, where the caps and the output deliberately differ. The
 * subtitle says which state the layout is in rather than making the user work it
 * out from what their typing looks like.
 *
 * **The title names its layout.** A language can offer several Keyman keyboards,
 * and their rows stack one after another under a single Layouts heading, where
 * a bare "Typing rules" belongs to whichever toggle happens to sit above it.
 * Reading the name out is what makes that unambiguous, and it is also the only
 * version that works for someone using TalkBack, who does not get "above" at
 * all.
 *
 * Nothing here downloads on its own. It is one row, one tap, and it names the
 * source, because a keyboard that reaches the network unasked is not something
 * to do quietly.
 *
 * [refreshKey] lets the screen tell the row that something else installed these
 * rules — the enable prompt does, and without it the row would still be
 * offering to download what is already there.
 */
@Composable
internal fun KeymanRulesRow(binding: KeymanBinding, layoutName: String, refreshKey: Int = 0) {
    val rules = rememberKeymanRules(binding, layoutName, refreshKey)
    val subtitle = when {
        rules.busy && rules.progress > 0 ->
            stringResource(R.string.languages_keyman_rules_downloading, rules.progress)
        rules.busy -> stringResource(R.string.languages_keyman_rules_checking)
        rules.failed -> rules.failure ?: stringResource(R.string.languages_keyman_rules_failed)
        rules.installedVersion == UNKNOWN_VERSION ->
            stringResource(R.string.languages_keyman_rules_installed_unknown)
        rules.installedVersion != null ->
            stringResource(R.string.languages_keyman_rules_installed, rules.installedVersion.orEmpty())
        else -> stringResource(R.string.languages_keyman_rules_missing)
    }

    NavRow(
        title = stringResource(R.string.languages_keyman_rules_title_for, layoutName),
        subtitle = subtitle,
        icon = SettingsRowIcons[R.string.languages_keyman_rules_title_for],
    ) {
        // A second press on an installed row removes the rules rather than
        // re-fetching them, so the row is its own undo.
        if (rules.installed) rules.remove() else rules.download()
    }
}

/**
 * One Keyman layout's typing rules as the screen sees them: on the device or
 * not, and a download in flight with its progress or its failure. Shared by
 * [KeymanRulesRow] and the layout cards on the More layouts page, so the two
 * can never tell different stories about the same file.
 *
 * Nothing here downloads on its own; [download] and [remove] are the only ways
 * the state moves, and both are ignored while a download is running.
 */
@Stable
internal class KeymanRulesState internal constructor(
    private val binding: KeymanBinding,
    private val layoutName: String,
    private val context: Context,
    private val scope: CoroutineScope,
    private val startDownload: State<(String, String) -> DownloadNotifications.Handle>,
) {
    private val version = mutableStateOf(KnownRules.of(binding.keyboardId))

    /**
     * The installed version, [UNKNOWN_VERSION] when it is unrecorded, or null.
     * Starts from what this process last read for the layout, so a card that
     * scrolls back into view shows its rules line as it left it rather than
     * flicking from "get" to "ready" once the disk has been read again.
     */
    var installedVersion: String?
        get() = version.value
        internal set(value) {
            version.value = value
            KnownRules.note(binding.keyboardId, value)
        }
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    val installed: Boolean get() = installedVersion != null

    fun remove() {
        if (busy || !installed) return
        scope.launch {
            withContext(Dispatchers.IO) {
                KeymanRuleDownloader.remove(context, binding.keyboardId)
            }
            installedVersion = null
            failed = false
            failure = null
        }
    }

    fun download() {
        if (busy) return
        busy = true
        failed = false
        failure = null
        progress = 0
        // The rules are small, but the row is one tap away from a screen the
        // user leaves immediately, and a fetch that failed silently is what
        // makes a Keyman layout type the wrong letters with no explanation.
        val notify = startDownload.value(
            DownloadKeys.keymanRules(binding.keyboardId),
            context.getString(R.string.notify_download_keyman_rules, layoutName),
        )
        scope.launch {
            val outcome = KeymanRuleDownloader.fetch(
                context = context,
                keyboardId = binding.keyboardId,
            ) { read, total ->
                if (total > 0) progress = ((read * 100) / total).toInt().coerceIn(0, 100)
                notify.progress(read, total)
            }
            when (outcome) {
                is KeymanRuleDownloader.Outcome.Installed -> {
                    installedVersion = outcome.version
                    notify.done()
                }
                // Nothing was fetched in either of these: the rules were
                // already current, or upstream has none. Neither is news.
                is KeymanRuleDownloader.Outcome.AlreadyCurrent -> {
                    installedVersion = outcome.version
                    notify.gone()
                }
                is KeymanRuleDownloader.Outcome.NotAvailable -> {
                    failed = true
                    failure = null
                    notify.gone()
                }
                is KeymanRuleDownloader.Outcome.Failed -> {
                    failed = true
                    failure = outcome.message
                    notify.failed(
                        outcome.message ?: context.getString(R.string.languages_keyman_rules_failed),
                    )
                }
            }
            busy = false
        }
    }
}

/** What each layout's rules were last read as, for the next state built for it. */
private object KnownRules {
    private val versions = HashMap<String, String?>()

    @Synchronized
    fun of(keyboardId: String): String? = versions[keyboardId]

    @Synchronized
    fun note(keyboardId: String, version: String?) {
        versions[keyboardId] = version
    }
}

/**
 * [KeymanRulesState] for [binding], re-reading the disk whenever a download
 * settles or [refreshKey] moves — which is how the enable prompt, installing
 * the same rules from elsewhere, tells this state to stop offering them.
 */
@Composable
internal fun rememberKeymanRules(
    binding: KeymanBinding,
    layoutName: String,
    refreshKey: Int = 0,
): KeymanRulesState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { KeymanRuleStore(context) }
    // Read through a state: the starter is a fresh lambda each composition,
    // and the holder outlives the one it was built in.
    val startDownload = rememberUpdatedState(rememberDownloadStarter())
    val state = remember(binding.keyboardId) {
        KeymanRulesState(binding, layoutName, context, scope, startDownload)
    }
    // Off the main thread: this stats a file, and a screen can build one of
    // these per Keyman layout the language offers.
    LaunchedEffect(binding.keyboardId, state.busy, refreshKey) {
        if (state.busy) return@LaunchedEffect
        state.installedVersion = withContext(Dispatchers.IO) { store.installedLabel(binding.keyboardId) }
    }
    return state
}

/**
 * Offers to fetch a layout's typing rules at the moment it is switched on.
 *
 * Mirrors `rememberLayoutEnableGate`: a function the caller invokes after
 * enabling, which owns the dialog it needs. Asking here rather than leaving the
 * row to be noticed is the difference between a keyboard that works and one that
 * types Latin letters at someone who selected Khmer — the row explains that, but
 * only to a user who already suspected something was wrong.
 *
 * The check runs before the dialog, so a layout whose rules are already present
 * enables silently. [onInstalled] fires only on a real install, and is how the
 * row behind the dialog learns to stop offering.
 */
@Composable
internal fun rememberKeymanRulesPrompt(
    onInstalled: () -> Unit,
): (KeymanBinding, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { KeymanRuleStore(context) }

    val startDownload = rememberDownloadStarter()
    var asking by remember { mutableStateOf<Pair<KeymanBinding, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }

    asking?.let { (binding, layoutName) ->
        val downloadName = stringResource(R.string.notify_download_keyman_rules, layoutName)
        fun close() {
            asking = null
            busy = false
            progress = 0
            failure = null
        }
        AlertDialog(
            // A download in flight must not be dismissed out from under itself;
            // the buttons are the way out until it settles.
            onDismissRequest = { if (!busy) close() },
            title = { Text(stringResource(R.string.languages_keyman_prompt_title, layoutName)) },
            text = {
                Column {
                    Text(stringResource(R.string.languages_keyman_prompt_body))
                    failure?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it)
                    }
                    if (busy) {
                        Spacer(Modifier.height(12.dp))
                        if (progress > 0) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        failure = null
                        progress = 0
                        val notify =
                            startDownload(DownloadKeys.keymanRules(binding.keyboardId), downloadName)
                        scope.launch {
                            val outcome = KeymanRuleDownloader.fetch(
                                context = context,
                                keyboardId = binding.keyboardId,
                            ) { read, total ->
                                if (total > 0) {
                                    progress = ((read * 100) / total).toInt().coerceIn(0, 100)
                                }
                                notify.progress(read, total)
                            }
                            when (outcome) {
                                is KeymanRuleDownloader.Outcome.Installed -> {
                                    notify.done()
                                    onInstalled()
                                    close()
                                }
                                // Already on disk: the dialog closes just the
                                // same, but nothing was fetched to report.
                                is KeymanRuleDownloader.Outcome.AlreadyCurrent -> {
                                    notify.gone()
                                    onInstalled()
                                    close()
                                }
                                is KeymanRuleDownloader.Outcome.NotAvailable -> {
                                    busy = false
                                    failure =
                                        context.getString(R.string.languages_keyman_rules_failed)
                                    // The dialog is still up and says so.
                                    notify.gone()
                                }
                                is KeymanRuleDownloader.Outcome.Failed -> {
                                    busy = false
                                    failure = outcome.message
                                        ?: context.getString(R.string.languages_keyman_rules_failed)
                                    notify.gone()
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.languages_keyman_prompt_download)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { close() }) {
                    Text(stringResource(R.string.languages_keyman_prompt_later))
                }
            },
        )
    }

    return remember(store) {
        { binding, layoutName ->
            scope.launch {
                // The file check is a disk hit, so it happens off the main
                // thread and only decides whether to ask at all. A layout whose
                // rules are already here enables without a word.
                val missing = withContext(Dispatchers.IO) {
                    store.installedLabel(binding.keyboardId) == null
                }
                if (missing) asking = binding to layoutName
            }
        }
    }
}

/**
 * What the rules directory says about a keyboard: its version, [UNKNOWN_VERSION]
 * when the file is there without one, or null when it is not installed.
 *
 * One call rather than `hasRules` then `installedVersion`, because those two
 * hit the disk separately and can disagree if a download lands between them.
 */
private fun KeymanRuleStore.installedLabel(keyboardId: String): String? =
    if (hasRules(keyboardId)) installedVersion(keyboardId) ?: UNKNOWN_VERSION else null

/**
 * Rules are present but their version is not recorded, which is what a restored
 * backup or a hand-placed file looks like. Distinct from "installed", so the row
 * does not claim to know something it does not.
 */
internal const val UNKNOWN_VERSION = "?"
