package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file to fetch on another device: what it is, and where it is published. */
internal data class OfflineLink(val label: String, val url: String)

/**
 * The row every download screen carries for a phone that cannot download:
 * **Get the files** says where they are published (opened in the browser, or
 * shared to another device), and **Import** takes them from the file picker
 * and installs them through [OfflineImport], exactly as the download would
 * have. Any number of files at once, zips included, so one pick can bring a
 * language pack, a Whisper graph with its vocabulary, or a folder's worth of
 * OCR data.
 *
 * [onImported] runs after an import that installed anything, for a screen
 * that has to re-read what is on disk.
 */
@Composable
internal fun OfflineImportRow(
    subtitle: String,
    links: List<OfflineLink>,
    wordlistSize: DictionaryCatalog.DictionarySize = DictionaryCatalog.DictionarySize.LARGE,
    title: String = stringResource(R.string.offline_import_row_title),
    onImported: () -> Unit = {},
) {
    var showLinks by remember { mutableStateOf(false) }
    val runner = rememberOfflineImport(wordlistSize, onImported)
    WmRow(
        title = title,
        supporting = { Text(subtitle) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (links.isNotEmpty()) {
                    TextButton(onClick = { showLinks = true }) {
                        Text(stringResource(R.string.offline_import_get_action))
                    }
                }
                TextButton(onClick = runner.pick, enabled = !runner.running) {
                    Text(stringResource(R.string.offline_import_action))
                }
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    if (showLinks) OfflineLinksDialog(links) { showLinks = false }
}

/** The picker, the running import and its report, for any surface that starts one. */
internal class OfflineImportRunner(val pick: () -> Unit, val running: Boolean)

@Composable
internal fun rememberOfflineImport(
    wordlistSize: DictionaryCatalog.DictionarySize = DictionaryCatalog.DictionarySize.LARGE,
    onImported: () -> Unit = {},
): OfflineImportRunner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<List<OfflineImport.Outcome>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        working = ""
        scope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                OfflineImport.run(context.applicationContext, uris, wordlistSize) { name ->
                    scope.launch { working = name }
                }
            }
            working = null
            report = outcomes
            if (outcomes.any { it.ok }) onImported()
        }
    }
    working?.let { OfflineImportProgress(it) }
    report?.let { OfflineImportReport(it) { report = null } }
    return OfflineImportRunner(pick = { launcher.launch(arrayOf("*/*")) }, running = working != null)
}

@Composable
private fun OfflineImportProgress(fileName: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.offline_import_working_title)) },
        text = {
            Column {
                if (fileName.isNotEmpty()) {
                    Text(fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun OfflineImportReport(outcomes: List<OfflineImport.Outcome>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (outcomes.isNotEmpty() && outcomes.all { it.ok }) {
                        R.string.offline_import_done_title
                    } else {
                        R.string.offline_import_report_title
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (outcomes.isEmpty()) Text(stringResource(R.string.offline_import_nothing))
                for (outcome in outcomes) {
                    Row(modifier = Modifier.padding(vertical = 6.dp)) {
                        Icon(
                            if (outcome.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = if (outcome.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(outcome.fileName, style = MaterialTheme.typography.labelLarge)
                            Text(
                                stringResource(outcome.messageRes, *outcome.args.toTypedArray()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_ok)) } },
    )
}

/**
 * Where to fetch [links] on another device: each one opens in the browser or
 * copies, and **Share** sends the whole list to wherever the user will do the
 * downloading. The browser does the fetching, so this works in a build that
 * cannot reach the network itself.
 */
@Composable
internal fun OfflineLinksDialog(links: List<OfflineLink>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.offline_import_links_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.offline_import_links_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (link in links) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(link.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                link.url.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { copyLink(context, link.url) }) {
                            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.offline_import_copy_link_desc))
                        }
                        IconButton(onClick = { openLink(context, link.url) }) {
                            Icon(Icons.Outlined.OpenInBrowser, stringResource(R.string.offline_import_open_link_desc))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { shareLinks(context, links) }) {
                Text(stringResource(R.string.offline_import_share_links_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copyLink(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(url, url))
}

private fun shareLinks(context: Context, links: List<OfflineLink>) {
    val text = links.joinToString("\n") { "${it.label}: ${it.url}" }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
