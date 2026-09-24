package com.wasimaster.wmkeyboard.app.launcher

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.StateBanner
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.LauncherSplitCombo
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.AppLaunchModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route of the split-screen pairs list. */
object LauncherCombos {
    const val ROUTE = "launchercombos"

    /** The app's name, or its package when it is not installed (or not visible). */
    internal fun label(pm: PackageManager, packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/**
 * The app launcher's split-screen pairs: rename, swap sides, reorder, delete.
 * Pairs are made in the keyboard itself (hold an app, **Pair in split screen
 * with…**), where the apps are already laid out to pick from, so this screen
 * only manages them.
 */
@Composable
internal fun LauncherCombosScreen(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val combos = settings.watch { it.launcher.combos }
    val packages = combos.flatMap { listOf(it.first, it.second) }.toSet()
    val labels by produceState(emptyMap<String, String>(), packages) {
        value = withContext(Dispatchers.IO) {
            packages.associateWith { LauncherCombos.label(context.packageManager, it) }
        }
    }
    var renaming by remember { mutableStateOf<Int?>(null) }

    Spacer(Modifier.height(12.dp))
    if (!AppLaunchModes.splitSupported(context)) {
        StateBanner(stringResource(R.string.launchercombos_unsupported_body))
    }
    if (combos.isEmpty()) {
        CaptionText(stringResource(R.string.launchercombos_empty))
        return
    }
    CaptionText(stringResource(R.string.launchercombos_caption))
    SettingsGroup {
        combos.forEachIndexed { index, combo ->
            item {
                ComboRow(
                    combo = combo,
                    firstLabel = labels[combo.first] ?: combo.first,
                    secondLabel = labels[combo.second] ?: combo.second,
                    canMoveUp = index > 0,
                    canMoveDown = index < combos.lastIndex,
                    onRename = { renaming = index },
                    onSwap = {
                        scope.launch {
                            repository.updateLauncherCombo(
                                index,
                                combo.copy(first = combo.second, second = combo.first),
                            )
                        }
                    },
                    onMove = { delta -> scope.launch { repository.moveLauncherCombo(index, index + delta) } },
                    onDelete = { scope.launch { repository.removeLauncherCombo(combo) } },
                )
            }
        }
    }

    val editing = renaming?.let { combos.getOrNull(it) }
    val editingIndex = renaming
    if (editing != null && editingIndex != null) {
        RenameComboDialog(
            initial = editing.name,
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                scope.launch { repository.updateLauncherCombo(editingIndex, editing.copy(name = name)) }
            },
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ComboRow(
    combo: LauncherSplitCombo,
    firstLabel: String,
    secondLabel: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: () -> Unit,
    onSwap: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val pairLabel = stringResource(R.string.launchercombos_pair_label, firstLabel, secondLabel)
    WmRow(
        title = combo.name.ifBlank { pairLabel },
        subtitle = if (combo.name.isBlank()) {
            stringResource(R.string.launchercombos_rename_hint)
        } else {
            pairLabel
        },
        leading = {
            Box(Modifier.width(40.dp).height(32.dp)) {
                PackageIcon(combo.second, Modifier.offset(x = 12.dp, y = 8.dp))
                PackageIcon(combo.first, Modifier)
            }
        },
        trailing = {
            Row {
                IconButton(onClick = onSwap) {
                    Icon(
                        Icons.Outlined.SwapHoriz,
                        contentDescription = stringResource(R.string.launchercombos_swap),
                    )
                }
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        contentDescription = stringResource(R.string.launchercombos_move_up),
                    )
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(
                        Icons.Outlined.ArrowDownward,
                        contentDescription = stringResource(R.string.launchercombos_move_down),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(CommonR.string.common_delete),
                    )
                }
            }
        },
        onClick = onRename,
    )
}

@Composable
private fun RenameComboDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.launchercombos_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace('\n', ' ').replace('\t', ' ') },
                label = { Text(stringResource(R.string.launchercombos_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** An app's icon at 24 dp, a placeholder tile until it decodes or when it is gone. */
@Composable
private fun PackageIcon(packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val px = (24 * density).toInt().coerceAtLeast(1)
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(px, px)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val bitmap = icon
    if (bitmap == null) {
        Box(
            modifier
                .size(24.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape),
        )
    } else {
        Image(
            bitmap,
            contentDescription = null,
            modifier = modifier
                .size(24.dp)
                .clip(shape),
        )
    }
}
