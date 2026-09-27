package com.wasimaster.wmkeyboard.app.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.OfflineImportRow
import com.wasimaster.wmkeyboard.app.SectionHeader
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.lock.LockTarget
import com.wasimaster.wmkeyboard.app.rememberLockGuard
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.formatBytes
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * What the app is using on your device, and how to get it back.
 *
 * The headline is the system's own figure — see [scanStorage] — so this screen
 * and the App info page in the system settings never disagree. Everything below
 * it is a share of that figure, including two residual rows that hold whatever
 * the named categories do not account for. Nothing is silently dropped.
 */
@Composable
internal fun StorageScreen(
    repository: SettingsRepository,
    onOpenCategory: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showEmpty by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<StorageCategory?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmFreeUp by remember { mutableStateOf(false) }

    // Painted from the last measurement, so stepping back in from a category
    // costs nothing and the list keeps both its order and its scroll position.
    var report by remember { mutableStateOf(StorageSnapshot.last ?: StorageReport()) }
    LaunchedEffect(revision) {
        if (revision == 0 && StorageSnapshot.isFresh()) return@LaunchedEffect
        // With nothing on screen yet, show each category as it lands. With a
        // picture already up, hold it and swap in the finished one: a partial
        // report would blank the rows it has not reached and reorder the rest.
        val progressive = StorageSnapshot.last == null
        scanStorage(context).collect { next ->
            report = if (progressive || !next.scanning) next
            else report.copy(scanning = true, progress = next.progress)
            if (!next.scanning) StorageSnapshot.publish(next)
        }
    }

    fun perform(work: suspend (StorageEnv) -> Unit) {
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                work(StorageEnv(context, repository, storageRoots(context)))
            }
            StorageSnapshot.invalidate()
            busy = false
            revision++
        }
    }

    // Largest first, but only re-sorted when a scan finishes. Sorting on every
    // emission had the rows leapfrogging for the whole of a measure, and doing
    // it mid-refresh would move them under a finger that is already reaching.
    var ordered by remember { mutableStateOf(StorageCategories.all) }
    LaunchedEffect(report.scanning) {
        if (!report.scanning) {
            ordered = StorageCategories.all.sortedByDescending { report.bytesOf(it.id) }
        }
    }

    StorageSummary(report, busy) { revision++ }

    val freeable = StorageCategories.all
        .filter { it.group == StorageGroup.CACHE && it.clearable }
    val freeableBytes = freeable.sumOf { report.bytesOf(it.id) }
    if (freeableBytes >= FREE_UP_FLOOR) {
        SettingsGroup {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FilledTonalButton(onClick = { confirmFreeUp = true }, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                        Text(
                            stringResource(R.string.storage_free_up_action, formatBytes(freeableBytes)),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.storage_free_up_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // Everything the app downloads can come in from a file instead: the one
    // place to bring a language pack, a model or a GIF pack fetched elsewhere,
    // for a phone that cannot fetch them itself.
    SettingsGroup {
        item {
            OfflineImportRow(
                title = stringResource(R.string.offline_import_storage_title),
                subtitle = stringResource(R.string.offline_import_storage_subtitle),
                links = emptyList(),
                onImported = {
                    StorageSnapshot.invalidate()
                    revision++
                },
            )
        }
    }

    var anyHidden = false
    for (group in StorageGroup.entries) {
        val inGroup = ordered.filter { it.group == group }
        val visible = inGroup.filter { showEmpty || report.bytesOf(it.id) > 0L }
        if (visible.size < inGroup.size) anyHidden = true
        if (visible.isEmpty()) continue
        SectionHeader(
            stringResource(group.title),
            trailing = formatBytes(inGroup.sumOf { report.bytesOf(it.id) }),
        )
        StorageBar(
            slices = inGroup.map { StorageSlice(it.accent, report.bytesOf(it.id)) },
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
        )
        SettingsGroup {
            for (category in visible) {
                item {
                    StorageCategoryRow(
                        category = category,
                        bytes = report.bytesOf(category.id),
                        items = report.itemsOf(category.id),
                        enabled = !busy,
                        onOpen = { onOpenCategory(category.id) },
                        onClear = {
                            if (category.danger == Danger.RESET) confirmReset = true else confirm = category
                        },
                    )
                }
            }
        }
    }

    if (anyHidden || showEmpty) {
        TextButton(
            onClick = { showEmpty = !showEmpty },
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                stringResource(
                    if (showEmpty) R.string.storage_show_less_action else R.string.storage_show_all_action,
                ),
            )
        }
    }

    confirm?.let { category ->
        val bytes = report.bytesOf(category.id)
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_title, stringResource(category.title)),
            body = stringResource(bodyFor(category.danger), formatBytes(bytes)),
            lock = AppLockTargets["action_storage_delete"],
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                perform { env -> category.clear(env) }
            },
        )
    }

    if (confirmReset) {
        ResetSettingsDialog(
            bytes = report.bytesOf(SETTINGS_CATEGORY),
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                StorageCategories.byId(SETTINGS_CATEGORY)?.let { category ->
                    perform { env -> category.clear(env) }
                }
            },
        )
    }

    if (confirmFreeUp) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_free_up_title),
            body = stringResource(R.string.storage_confirm_free_up_body, formatBytes(freeableBytes)),
            onDismiss = { confirmFreeUp = false },
            onConfirm = {
                confirmFreeUp = false
                perform { env -> freeable.forEach { it.clear(env) } }
            },
        )
    }
}

/** The ring, its legend, and what the device has left, on one card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageSummary(report: StorageReport, busy: Boolean, onRefresh: () -> Unit) {
    val working = busy || report.scanning
    SettingsGroup {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StorageRing(
                    report = report,
                    contentDescription = stringResource(
                        R.string.storage_ring_desc,
                        formatBytes(report.totalBytes),
                        formatBytes(report.appBytes),
                        formatBytes(report.userDataBytes),
                        formatBytes(report.cacheBytes),
                    ),
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StorageLegendChip(ringBucketColor(0), stringResource(R.string.storage_bucket_app), report.appBytes)
                    StorageLegendChip(ringBucketColor(1), stringResource(R.string.storage_bucket_data), report.userDataBytes)
                    StorageLegendChip(ringBucketColor(2), stringResource(R.string.storage_bucket_cache), report.cacheBytes)
                }
                if (report.deviceBytes > 0L) {
                    Text(
                        stringResource(
                            R.string.storage_device_caption,
                            formatBytes(report.deviceUsedBytes),
                            formatBytes(report.deviceBytes),
                            formatBytes(report.freeBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (!report.exact) {
                    Text(
                        stringResource(R.string.storage_estimate_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // Only while something is actually running. A measure that
                // follows a cached paint takes about a second, and this is the
                // only sign of it — the numbers above deliberately hold still.
                if (working) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            if (busy) R.string.storage_deleting_label else R.string.storage_measuring_label,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { report.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    TextButton(onClick = onRefresh, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Text(
                            stringResource(R.string.storage_refresh_action),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageCategoryRow(
    category: StorageCategory,
    bytes: Long,
    items: Int,
    enabled: Boolean,
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    val summary = when {
        bytes <= 0L -> stringResource(R.string.storage_empty_label)
        items <= 0 -> formatBytes(bytes)
        else -> stringResource(
            R.string.storage_row_summary,
            formatBytes(bytes),
            pluralStringResource(R.plurals.storage_item_count, items, items),
        )
    }
    val deleteDesc = stringResource(R.string.storage_delete_all_desc, stringResource(category.title))
    WmRow(
        title = stringResource(category.title),
        icon = category.icon,
        accent = category.accent,
        highlightKey = category.title,
        flightTo = storageRoute(category.id),
        // One block rather than a subtitle *and* a supporting line: WmRow draws
        // whichever of the two it is given and prefers supporting, so a row with
        // a note used to show the note in place of its size. On a storage screen
        // the size is the row, so it goes first and the note sits under it.
        supporting = {
            Column {
                Text(summary)
                if (category.note != 0) {
                    Text(
                        stringResource(category.note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NOTE_ALPHA),
                    )
                }
            }
        },
        trailing = {
            if (category.clearable) {
                IconButton(onClick = onClear, enabled = enabled && bytes > 0L) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = deleteDesc,
                        tint = if (enabled && bytes > 0L) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
                    )
                }
            }
        },
        onClick = onOpen,
    )
}

/**
 * [lock] names this dialog's confirm button in the fingerprint lock's
 * registry. The check runs after the dialog, so the order the user sees is
 * "are you sure" and then "prove it", which puts the fingerprint immediately
 * before the deletion rather than in front of a dialog they may cancel.
 */
@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    body: String,
    confirmLabel: String = stringResource(CommonR.string.common_delete),
    lock: LockTarget? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val guarded = rememberLockGuard(lock, onConfirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = guarded) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * Two dialogs rather than one. Clearing the settings store is the only action
 * on this screen a user cannot undo *and* cannot see the shape of beforehand —
 * every theme, layout and mode they ever made goes with it — so it costs a
 * second, deliberate press rather than one mis-aimed tap.
 */
@Composable
private fun ResetSettingsDialog(bytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var second by remember { mutableStateOf(false) }
    if (!second) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_reset_title),
            body = stringResource(R.string.storage_confirm_reset_body),
            onDismiss = onDismiss,
            onConfirm = { second = true },
        )
    } else {
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_reset_again_title),
            body = stringResource(R.string.storage_confirm_reset_again_body, formatBytes(bytes)),
            // On the second dialog only, so the sequence reads: are you sure,
            // are you certain, prove it, gone. The fingerprint stays the last
            // step rather than becoming the first of three.
            lock = AppLockTargets["action_factory_reset"],
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

private fun bodyFor(danger: Danger): Int = when (danger) {
    Danger.NONE -> R.string.storage_confirm_free_body
    Danger.REDOWNLOAD -> R.string.storage_confirm_redownload_body
    Danger.PERSONAL, Danger.RESET -> R.string.storage_confirm_personal_body
}

/** Below this the button is noise: the press costs more than the space it wins. */
private const val FREE_UP_FLOOR = 512L * 1024
private const val DISABLED_ALPHA = 0.38f
private const val NOTE_ALPHA = 0.75f
private const val SETTINGS_CATEGORY = "settings_data"
