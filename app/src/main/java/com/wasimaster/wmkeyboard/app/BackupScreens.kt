package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.text.format.DateUtils
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.wasimaster.wmkeyboard.app.drive.driveAuthorizer
import com.wasimaster.wmkeyboard.app.oauth.BackupOAuth
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsBackup
import com.wasimaster.wmkeyboard.core.settings.AutoBackupIntervals
import com.wasimaster.wmkeyboard.core.settings.AutoBackupKeepRange
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.FtpConfig
import com.wasimaster.wmkeyboard.core.settings.S3Config
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.needsNetwork
import com.wasimaster.wmkeyboard.core.settings.signsIn
import com.wasimaster.wmkeyboard.core.settings.sectionSet
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import com.wasimaster.wmkeyboard.core.settings.SyncMode
import com.wasimaster.wmkeyboard.core.settings.SyncSettings
import com.wasimaster.wmkeyboard.core.settings.activeLocations
import com.wasimaster.wmkeyboard.core.settings.backupTargets
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import androidx.compose.material3.Checkbox
import com.wasimaster.wmkeyboard.core.settings.exportSectionSet
import com.wasimaster.wmkeyboard.core.settings.sync.SyncRunner
import com.wasimaster.wmkeyboard.core.settings.keepLocalGroups
import com.wasimaster.wmkeyboard.core.settings.sync.SyncScheduler
import com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys
import com.wasimaster.wmkeyboard.core.settings.targets

// ---- backup ----

/** Human name for a bundle section, used in toggles and the import dialog. */
@StringRes
internal fun sectionLabelRes(section: ConfigBackup.Section): Int = when (section) {
    ConfigBackup.Section.SETTINGS -> R.string.backup_section_settings_label
    ConfigBackup.Section.THEMES -> R.string.backup_section_themes_label
    ConfigBackup.Section.DICTIONARY -> R.string.backup_section_dictionary_label
    ConfigBackup.Section.CLIPBOARD -> R.string.backup_section_clipboard_label
    ConfigBackup.Section.SNIPPETS -> R.string.backup_section_snippets_label
    ConfigBackup.Section.STICKERS -> R.string.backup_section_stickers_label
    ConfigBackup.Section.ICONS -> R.string.backup_section_icons_label
    ConfigBackup.Section.WORDLISTS -> R.string.backup_section_wordlists_label
    ConfigBackup.Section.ADDONS -> R.string.backup_section_addons_label
    ConfigBackup.Section.EMOJI -> R.string.backup_section_emoji_label
    ConfigBackup.Section.STATISTICS -> R.string.backup_section_statistics_label
    ConfigBackup.Section.VOCAB -> R.string.backup_section_vocab_label
    ConfigBackup.Section.SWIPE -> R.string.backup_section_swipe_label
}
internal fun sectionLabel(context: Context, section: ConfigBackup.Section): String =
    context.getString(sectionLabelRes(section))
/**
 * The same name for the middle of a sentence ("Restored themes, snippets.").
 * A translation cannot be lowercased in code, so each name carries its own
 * lower-case value.
 */
internal fun sectionLabelLowercase(context: Context, section: ConfigBackup.Section): String =
    context.getString(
        when (section) {
            ConfigBackup.Section.SETTINGS -> R.string.backup_section_settings_label_lowercase
            ConfigBackup.Section.THEMES -> R.string.backup_section_themes_label_lowercase
            ConfigBackup.Section.DICTIONARY -> R.string.backup_section_dictionary_label_lowercase
            ConfigBackup.Section.CLIPBOARD -> R.string.backup_section_clipboard_label_lowercase
            ConfigBackup.Section.SNIPPETS -> R.string.backup_section_snippets_label_lowercase
            ConfigBackup.Section.STICKERS -> R.string.backup_section_stickers_label_lowercase
            ConfigBackup.Section.ICONS -> R.string.backup_section_icons_label_lowercase
            ConfigBackup.Section.WORDLISTS -> R.string.backup_section_wordlists_label_lowercase
            ConfigBackup.Section.ADDONS -> R.string.backup_section_addons_label_lowercase
            ConfigBackup.Section.EMOJI -> R.string.backup_section_emoji_label_lowercase
            ConfigBackup.Section.STATISTICS -> R.string.backup_section_statistics_label_lowercase
            ConfigBackup.Section.VOCAB -> R.string.backup_section_vocab_label_lowercase
            ConfigBackup.Section.SWIPE -> R.string.backup_section_swipe_label_lowercase
        },
    )
@PluralsRes
private fun sectionCountPlural(section: ConfigBackup.Section): Int = when (section) {
    ConfigBackup.Section.SETTINGS -> R.plurals.backup_section_settings_count
    ConfigBackup.Section.THEMES -> R.plurals.backup_section_themes_count
    ConfigBackup.Section.DICTIONARY -> R.plurals.backup_section_dictionary_count
    ConfigBackup.Section.CLIPBOARD -> R.plurals.backup_section_clipboard_count
    ConfigBackup.Section.SNIPPETS -> R.plurals.backup_section_snippets_count
    ConfigBackup.Section.STICKERS -> R.plurals.backup_section_stickers_count
    ConfigBackup.Section.ICONS -> R.plurals.backup_section_icons_count
    ConfigBackup.Section.WORDLISTS -> R.plurals.backup_section_wordlists_count
    ConfigBackup.Section.ADDONS -> R.plurals.backup_section_addons_count
    ConfigBackup.Section.EMOJI -> R.plurals.backup_section_emoji_count
    ConfigBackup.Section.STATISTICS -> R.plurals.backup_section_statistics_count
    ConfigBackup.Section.VOCAB -> R.plurals.backup_section_vocab_count
    ConfigBackup.Section.SWIPE -> R.plurals.backup_section_swipe_count
}
/** "3 themes", "1 snippet": the count line shown per section on import. */
internal fun sectionSummary(context: Context, section: ConfigBackup.Section, count: Int): String =
    context.resources.getQuantityString(sectionCountPlural(section), count, count)

/** A file picked for import, once we know which of the two formats it is. */
private sealed interface PendingImport {
    val text: String
    data class Config(override val text: String) : PendingImport
    data class Legacy(override val text: String) : PendingImport
}
/**
 * Where [hours] sits on [AutoBackupIntervals], for the slider's thumb.
 *
 * Nearest rather than exact: a value stored before the ladder existed, or by a
 * restored backup from a build with a different one, still has to put the thumb
 * somewhere sensible instead of snapping to the first stop.
 */
private fun intervalSliderIndex(hours: Int): Int =
    AutoBackupIntervals.indices.minBy { kotlin.math.abs(AutoBackupIntervals[it] - hours) }
/** The ladder value under a slider position. */
private fun intervalAt(index: Float): Int =
    AutoBackupIntervals[index.roundToInt().coerceIn(AutoBackupIntervals.indices)]
/** "Every 6 hours", "Every day", "Every 3 days". */
private fun backupIntervalLabel(context: Context, hours: Int): String =
    if (hours % 24 == 0) {
        context.resources.getQuantityString(
            R.plurals.backup_auto_interval_days,
            hours / 24,
            hours / 24,
        )
    } else {
        context.resources.getQuantityString(R.plurals.backup_auto_interval_hours, hours, hours)
    }
/**
 * One sentence for a recorded failure, or null when the last run was fine.
 * [destination] picks the advice: a lost folder grant, a lost account
 * sign-in and a refused server password each need a different thing done.
 */
internal fun autoBackupErrorText(
    context: Context,
    error: String,
    destination: BackupDestination = BackupDestination.FOLDER,
): String? = when (error) {
    "" -> null
    SinkError.PERMISSION_LOST.name -> context.getString(
        when {
            destination == BackupDestination.FOLDER -> R.string.backup_auto_error_permission
            destination.signsIn -> R.string.backup_auto_error_permission_account
            else -> R.string.backup_auto_error_permission_server
        },
    )
    SinkError.TARGET_MISSING.name -> context.getString(
        if (destination == BackupDestination.FOLDER) {
            R.string.backup_auto_error_target
        } else {
            R.string.backup_auto_error_target_remote
        },
    )
    SinkError.OUT_OF_SPACE.name -> context.getString(R.string.backup_auto_error_space)
    SinkError.UNSAFE.name -> context.getString(
        when (destination) {
            BackupDestination.SFTP -> R.string.backup_auto_error_unsafe_sftp
            BackupDestination.GIT -> R.string.backup_auto_error_unsafe_git
            BackupDestination.SMB -> R.string.backup_auto_error_unsafe_smb
            else -> R.string.backup_auto_error_unsafe
        },
    )
    else -> context.getString(R.string.backup_auto_error_io)
}
/**
 * A text field backed by the settings store, for the handful of backup values
 * that are typed rather than picked.
 *
 * Same shape and same reason as the layout editor's `SheetField`: the value is
 * read back out of the repository a frame or more after the keystroke that
 * caused it, and fed straight back in it rewinds the text and the cursor
 * mid-word. The text lives here, and an incoming value is taken only while
 * nothing of ours is in flight.
 *
 * [password] masks the text and adds the reveal button. It also sets the field
 * to a password type, which matters more here than it usually would: this is
 * the keyboard, and an ordinary field would learn the passphrase into the very
 * dictionary the backup is about to carry.
 */
@Composable
internal fun StoredTextField(
    label: String,
    value: String,
    supporting: String,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** For a pasted key or a template: lets the field grow instead of scrolling one line. */
    multiLine: Boolean = false,
    onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    var pending by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    when {
        pending == null -> if (value != text) text = value
        value == pending -> pending = null
    }
    val masked = password && !visible
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            pending = it
            onChange(it)
        },
        label = { Text(label) },
        supportingText = if (supporting.isEmpty()) null else ({ Text(supporting) }),
        singleLine = !multiLine,
        maxLines = if (multiLine) MULTI_LINE_MAX else 1,
        visualTransformation =
        if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
        ),
        trailingIcon = if (!password) {
            null
        } else {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (visible) {
                                R.string.backup_auto_passphrase_hide
                            } else {
                                R.string.backup_auto_passphrase_show
                            },
                        ),
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The activity a composable is drawn in, by unwrapping the context.
 *
 * `:core:common` has one of these, and it is `internal` there, so it stops at
 * that module's boundary. Needed here for the one thing on this screen that has
 * to launch a system consent screen.
 */
internal tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/**
 * What each destination needs from the user, one line, for the picker sheet.
 * The list is brand names, and a brand name says nothing about whether the
 * choice wants a sign-in, a URL or a pair of keys.
 */
internal fun destinationDescRes(destination: BackupDestination): Int = when (destination) {
    BackupDestination.FOLDER -> R.string.backup_auto_dest_folder_desc
    BackupDestination.WEBDAV -> R.string.backup_auto_dest_webdav_desc
    BackupDestination.DRIVE -> R.string.backup_auto_dest_drive_desc
    BackupDestination.S3 -> R.string.backup_auto_dest_s3_desc
    BackupDestination.FTP -> R.string.backup_auto_dest_ftp_desc
    BackupDestination.DROPBOX -> R.string.backup_auto_dest_dropbox_desc
    BackupDestination.ONEDRIVE -> R.string.backup_auto_dest_onedrive_desc
    BackupDestination.SFTP -> R.string.backup_auto_dest_sftp_desc
    BackupDestination.SMB -> R.string.backup_auto_dest_smb_desc
    BackupDestination.GIT -> R.string.backup_auto_dest_git_desc
    BackupDestination.IMAP -> R.string.backup_auto_dest_imap_desc
}

/** One sentence for whatever a backup run turned out to be. */
private fun autoBackupOutcomeText(
    context: Context,
    outcome: AutoBackupRunner.Outcome,
    auto: AutoBackupSettings,
): String {
    fun typeOf(id: String?) = auto.locations.firstOrNull { it.id == id }?.type ?: BackupDestination.FOLDER
    return when (outcome) {
        is AutoBackupRunner.Outcome.Done -> buildString {
            append(
                if (outcome.skipped.isEmpty()) {
                    context.getString(R.string.backup_auto_done, outcome.name)
                } else {
                    context.getString(
                        R.string.backup_auto_done_skipped,
                        outcome.name,
                        outcome.skipped.joinToString { sectionLabelLowercase(context, it) },
                    )
                },
            )
            for ((id, reason) in outcome.failed) {
                val location = auto.locations.firstOrNull { it.id == id } ?: continue
                append("\n\n")
                append(location.title(context)).append(": ")
                append(autoBackupErrorText(context, reason.name, location.type).orEmpty())
            }
        }
        AutoBackupRunner.Outcome.Locked -> context.getString(R.string.backup_auto_locked)
        AutoBackupRunner.Outcome.Skipped -> context.getString(R.string.backup_auto_skipped)
        is AutoBackupRunner.Outcome.Failed ->
            autoBackupErrorText(context, outcome.reason.name, typeOf(auto.activeLocations.firstOrNull()?.id))
                ?: context.getString(R.string.backup_auto_error_io)
    }
}

/** One sentence for a sync pass. */
private fun syncOutcomeText(context: Context, outcome: SyncRunner.Outcome, auto: AutoBackupSettings): String =
    when (outcome) {
        is SyncRunner.Outcome.Done -> when {
            SyncRunner.ERROR_PASSPHRASE in outcome.failed.values ->
                context.getString(R.string.backup_sync_error_passphrase)
            outcome.applied == 0 -> context.getString(R.string.backup_sync_done_nothing)
            else -> context.resources.getQuantityString(
                R.plurals.backup_sync_done_changes,
                outcome.applied,
                outcome.applied,
            )
        }
        SyncRunner.Outcome.Locked -> context.getString(R.string.backup_sync_locked)
        SyncRunner.Outcome.Skipped -> context.getString(R.string.backup_sync_skipped)
        is SyncRunner.Outcome.Failed -> syncErrorText(context, outcome.reason, auto)
    }

/** A recorded sync failure in words, or null for none. */
private fun syncErrorText(context: Context, reason: String, auto: AutoBackupSettings): String =
    if (reason == SyncRunner.ERROR_PASSPHRASE) {
        context.getString(R.string.backup_sync_error_passphrase)
    } else {
        val type = auto.sync.targets(auto.locations).firstOrNull()?.type ?: BackupDestination.FOLDER
        autoBackupErrorText(context, reason, type) ?: context.getString(R.string.backup_auto_error_io)
    }

/**
 * The top of the Backup & restore screen: is the keyboard covered, and when
 * was it last, with the two buttons that act on it. The one place on the
 * screen that answers "am I safe?" without reading anything else.
 */
@Composable
private fun BackupStatusCard(
    repository: SettingsRepository,
    settings: LiveSettings,
    onMessage: (String) -> Unit,
) {
    val auto = settings.watch { it.autoBackup }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backingUp by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    val active = auto.backupTargets
    val backupOn = auto.enabled && active.isNotEmpty()
    val error = autoBackupErrorText(context, auto.lastError, active.firstOrNull()?.type ?: BackupDestination.FOLDER)
    val syncError = auto.sync.lastError.takeIf { it.isNotEmpty() }?.let { syncErrorText(context, it, auto) }
    val problem = error != null || (auto.sync.enabled && syncError != null)
    val colors = MaterialTheme.colorScheme
    val container = when {
        problem -> colors.errorContainer
        backupOn || auto.sync.enabled -> colors.secondaryContainer
        else -> colors.surfaceContainerHigh
    }
    val onContainer = when {
        problem -> colors.onErrorContainer
        backupOn || auto.sync.enabled -> colors.onSecondaryContainer
        else -> colors.onSurface
    }

    Surface(
        color = container,
        contentColor = onContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        problem -> Icons.Outlined.CloudOff
                        backupOn -> Icons.Outlined.CloudDone
                        else -> Icons.Outlined.CloudQueue
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (backupOn) R.string.backup_hub_backup_on else R.string.backup_hub_backup_off),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (active.isEmpty()) {
                            stringResource(R.string.backup_hub_no_locations)
                        } else {
                            pluralStringResource(
                                R.plurals.backup_hub_backup_where,
                                active.size,
                                backupIntervalLabel(context, auto.intervalHours),
                                active.size,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                error ?: if (auto.lastRunAtMs > 0L) {
                    stringResource(
                        R.string.backup_auto_last_run,
                        DateUtils.getRelativeTimeSpanString(auto.lastRunAtMs).toString(),
                    )
                } else {
                    stringResource(R.string.backup_auto_never)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (auto.sync.enabled) {
                Text(
                    syncError ?: if (auto.sync.lastRunAtMs > 0L) {
                        stringResource(
                            R.string.backup_hub_last_sync,
                            DateUtils.getRelativeTimeSpanString(auto.sync.lastRunAtMs).toString(),
                        )
                    } else {
                        stringResource(R.string.backup_hub_never_synced)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Filled, not tonal: a tonal button on a container-coloured
                // card is the card's own colour, and reads as a text link.
                Button(
                    enabled = active.isNotEmpty() && !backingUp,
                    onClick = {
                        backingUp = true
                        scope.launch {
                            val outcome = AutoBackupRunner.run(context, repository, force = true)
                            backingUp = false
                            onMessage(autoBackupOutcomeText(context, outcome, repository.settings.first().autoBackup))
                        }
                    },
                ) {
                    if (backingUp) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(if (backingUp) R.string.backup_auto_running else R.string.backup_auto_now_action),
                    )
                }
                if (auto.sync.enabled) {
                    OutlinedButton(
                        enabled = !syncing,
                        onClick = {
                            syncing = true
                            scope.launch {
                                val outcome = SyncRunner.run(context, repository, force = true)
                                syncing = false
                                onMessage(syncOutcomeText(context, outcome, repository.settings.first().autoBackup))
                            }
                        },
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (syncing) R.string.backup_hub_syncing else R.string.backup_hub_sync_now))
                    }
                }
            }
        }
    }
}

/** One section's row on a "what goes in" list: its subtitle and its "?". */
private class SectionRow(
    val section: ConfigBackup.Section,
    @StringRes val subtitle: Int,
    @StringRes val info: Int? = null,
)

private val SECTION_ROWS = listOf(
    SectionRow(ConfigBackup.Section.SETTINGS, R.string.backup_include_settings_subtitle),
    SectionRow(ConfigBackup.Section.THEMES, R.string.backup_include_themes_subtitle, R.string.backup_include_themes_info),
    SectionRow(
        ConfigBackup.Section.DICTIONARY,
        R.string.backup_include_dictionary_subtitle,
        R.string.backup_include_dictionary_info,
    ),
    SectionRow(
        ConfigBackup.Section.CLIPBOARD,
        R.string.backup_include_clipboard_subtitle,
        R.string.backup_include_clipboard_info,
    ),
    SectionRow(ConfigBackup.Section.SNIPPETS, R.string.backup_include_snippets_subtitle),
    SectionRow(
        ConfigBackup.Section.STICKERS,
        R.string.backup_include_stickers_subtitle,
        R.string.backup_include_stickers_info,
    ),
    SectionRow(ConfigBackup.Section.ICONS, R.string.backup_include_icons_subtitle, R.string.backup_include_icons_info),
    SectionRow(ConfigBackup.Section.WORDLISTS, R.string.backup_include_wordlists_subtitle),
    SectionRow(ConfigBackup.Section.ADDONS, R.string.backup_include_addons_subtitle, R.string.backup_include_addons_info),
    SectionRow(ConfigBackup.Section.EMOJI, R.string.backup_include_emoji_subtitle),
    SectionRow(ConfigBackup.Section.STATISTICS, R.string.backup_include_statistics_subtitle),
    SectionRow(ConfigBackup.Section.VOCAB, R.string.backup_include_vocab_subtitle),
    SectionRow(ConfigBackup.Section.SWIPE, R.string.backup_include_swipe_subtitle),
)

/**
 * The same thirteen switches for the three lists that pick sections: the
 * export, the automatic backup and sync. [sections] picks the list's sections
 * out of the settings, which each row reads for itself. [secrets] adds the
 * API-key switch under Settings where the list has one, shown while
 * [settingsIncluded] (Settings is among the sections, read by the caller since
 * it decides the rows).
 */
private fun SettingsGroupScope.sectionRows(
    settings: LiveSettings,
    sections: (KeyboardSettings) -> Set<ConfigBackup.Section>,
    defaults: Set<String>,
    secrets: Pair<(KeyboardSettings) -> Boolean, (Boolean) -> Unit>?,
    settingsIncluded: Boolean = false,
    secretsEnabled: Boolean = true,
    @StringRes secretsSubtitle: Int = R.string.backup_include_secrets_subtitle,
    onToggle: (ConfigBackup.Section, Boolean) -> Unit,
) {
    for (row in SECTION_ROWS) {
        item {
            ToggleSetting(
                sectionLabelRes(row.section),
                stringResource(row.subtitle),
                settings.watch { row.section in sections(it) },
                info = row.info?.let { stringResource(it) },
                default = row.section.id in defaults,
            ) { onToggle(row.section, it) }
        }
        if (row.section == ConfigBackup.Section.SETTINGS && secrets != null) {
            item(visible = settingsIncluded) {
                ToggleSetting(
                    R.string.backup_include_secrets_title,
                    stringResource(secretsSubtitle),
                    settings.watch { secrets.first(it) },
                    info = stringResource(R.string.backup_include_secrets_info),
                    enabled = secretsEnabled,
                    default = false,
                ) { secrets.second(it) }
            }
        }
    }
}

/**
 * One location with a tick box, for the two lists that pick where things go:
 * Back up to, and Sync through. The whole row toggles, as a switch row does.
 */
@Composable
private fun LocationCheckRow(location: BackupLocation, checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    WmRow(
        title = location.title(context),
        subtitle = if (location.configured) {
            location.detail(context)
        } else {
            stringResource(needsSetupRes(location.type))
        },
        leading = { WmIconTile(icon = destinationIcon(location.type), accent = location.type.accent) },
        trailing = { Checkbox(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/**
 * The "include API keys" switch, which asks before it goes on without a
 * passphrase. It does not refuse: the keys and the storage are the user's.
 * It says plainly what the file will then hold.
 */
@Composable
private fun rememberKeysSwitch(encrypted: Boolean, onSet: (Boolean) -> Unit): (Boolean) -> Unit {
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.backup_secrets_warning_title)) },
            text = { Text(stringResource(R.string.backup_secrets_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onSet(true)
                }) { Text(stringResource(R.string.backup_secrets_warning_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
    return { on -> if (on && !encrypted) asking = true else onSet(on) }
}

/**
 * Backup & restore: where backups go, how the automatic backup and sync run,
 * and the one-off file export and import.
 */
@Composable
internal fun BackupSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var message by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf<PendingImport?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigBackup.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val exportSections = settings.value.autoBackup.exportSectionSet
        val includeSecrets = settings.value.autoBackup.includeSecrets
        scope.launch {
            val ok = runCancellable {
                val text = repository.exportConfig(
                    sections = exportSections,
                    includeSecrets = includeSecrets,
                    appVersion = BuildConfig.VERSION_CODE,
                    appVersionName = BuildConfig.VERSION_NAME,
                )
                withContext(Dispatchers.IO) {
                    context.contentResolver.requireOutputStream(uri).use {
                        it.write(text.toByteArray())
                    }
                }
            }.isSuccess
            message = when {
                !ok -> context.getString(R.string.backup_export_write_error)
                ConfigBackup.Section.SETTINGS in exportSections && includeSecrets ->
                    context.getString(R.string.backup_export_done_with_keys)
                else -> context.getString(R.string.backup_export_done)
            }
        }
    }

    // Import reads the file first and asks before writing: restoring is not
    // something to discover you have done. Both the full-config bundle and the
    // older settings-only file are accepted.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { it.readBytes().decodeToString() }
                }.getOrNull()?.firstJsonDocument()
            }
            confirmImport = when {
                text == null -> {
                    message = context.getString(R.string.backup_import_read_error); null
                }
                ConfigBackup.decode(text) != null -> PendingImport.Config(text)
                SettingsBackup.decode(text) != null -> PendingImport.Legacy(text)
                else -> {
                    message = context.getString(R.string.backup_not_a_backup); null
                }
            }
        }
    }
    val exportGuarded = rememberLockGuard(AppLockTargets["action_export_settings"]) {
        // Datestamped, Locale.US: see the automatic backup's names.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        exportLauncher.launch("wmkeyboard-backup-$stamp.${ConfigBackup.FILE_EXTENSION}")
    }

    BackupStatusCard(repository, settings) { message = it }

    LocationsGroup(repository, settings.watch { it.autoBackup }) { message = it }

    SettingsGroup {
        item {
            // The interval and the count kept, or null while it is off.
            val schedule = settings.watch { s ->
                s.autoBackup.takeIf { it.enabled && it.backupTargets.isNotEmpty() }?.let { it.intervalHours to it.keep }
            }
            NavRow(
                R.string.backup_auto_group_title,
                subtitle = if (schedule != null) {
                    val (intervalHours, keep) = schedule
                    stringResource(
                        R.string.backup_hub_auto_summary,
                        backupIntervalLabel(context, intervalHours),
                        context.resources.getQuantityString(R.plurals.backup_auto_keep_value, keep, keep),
                    )
                } else {
                    stringResource(R.string.backup_hub_backup_off)
                },
                route = "backup/auto",
            ) { onNavigate("backup/auto") }
        }
        item {
            val syncOn = settings.watch { it.autoBackup.sync.enabled }
            val syncMode = settings.watch { it.autoBackup.sync.mode }
            NavRow(
                R.string.backup_sync_title,
                subtitle = stringResource(
                    if (!syncOn) {
                        R.string.backup_sync_nav_subtitle
                    } else {
                        when (syncMode) {
                            SyncMode.SOON -> R.string.backup_sync_mode_soon
                            SyncMode.SCHEDULE -> R.string.backup_sync_mode_schedule
                            SyncMode.MANUAL -> R.string.backup_sync_mode_manual
                        }
                    },
                ),
                route = "backup/sync",
            ) { onNavigate("backup/sync") }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_files_title),
        info = stringResource(R.string.backup_info),
    ) {
        item {
            WmRow(
                title = stringResource(R.string.backup_files_export_title),
                subtitle = stringResource(R.string.backup_files_export_subtitle),
                icon = Icons.Outlined.FileUpload,
                enabled = settings.watch { it.autoBackup.exportSectionSet.isNotEmpty() },
                onClick = exportGuarded,
            )
        }
        item {
            NavRow(
                R.string.backup_files_contents_title,
                subtitle = stringResource(
                    R.string.backup_include_nav_subtitle,
                    settings.watch { it.autoBackup.exportSectionSet.size },
                    ConfigBackup.Section.entries.size,
                ),
                route = "backup/contents",
            ) { onNavigate("backup/contents") }
        }
        item {
            WmRow(
                title = stringResource(R.string.backup_files_import_title),
                subtitle = stringResource(R.string.backup_files_import_subtitle),
                icon = Icons.Outlined.FileDownload,
                onClick = { importLauncher.launch(arrayOf("*/*")) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    when (val pending = confirmImport) {
        is PendingImport.Config -> {
            val parsed = remember(pending.text) { ConfigBackup.decode(pending.text) }
            val counts = remember(pending.text) { parsed?.let { repository.describeConfig(it) }.orEmpty() }
            val hasSecrets = remember(pending.text) { parsed?.let { repository.configContainsSecrets(it) } ?: false }
            AlertDialog(
                onDismissRequest = { confirmImport = null },
                title = { Text(stringResource(R.string.backup_import_confirm_title)) },
                text = {
                    Text(
                        buildString {
                            append(context.getString(R.string.backup_import_contains))
                            append("\n")
                            for ((section, count) in counts) {
                                append("\n")
                                append(
                                    context.getString(
                                        R.string.backup_import_section_line,
                                        sectionLabel(context, section),
                                        sectionSummary(context, section, count),
                                    ),
                                )
                            }
                            append("\n\n")
                            append(context.getString(R.string.backup_import_merge_note))
                            if (hasSecrets) {
                                append("\n\n")
                                append(context.getString(R.string.backup_import_api_keys_note))
                            }
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmImport = null
                        scope.launch {
                            message = when (val result = repository.importConfig(pending.text)) {
                                is SettingsRepository.ConfigImportResult.Applied -> buildString {
                                    if (result.restored.isEmpty()) {
                                        append(context.getString(R.string.backup_restore_nothing))
                                    } else {
                                        append(
                                            context.getString(
                                                R.string.backup_restore_done,
                                                result.restored.joinToString {
                                                    sectionLabelLowercase(context, it)
                                                },
                                            ),
                                        )
                                    }
                                    if (result.settingsFailed) {
                                        append("\n\n")
                                        append(
                                            context.getString(R.string.backup_restore_settings_failed),
                                        )
                                    }
                                }
                                SettingsRepository.ConfigImportResult.NotABackup ->
                                    context.getString(R.string.backup_not_a_backup)
                            }
                        }
                    }) { Text(stringResource(CommonR.string.common_import)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmImport = null }) {
                        Text(stringResource(CommonR.string.common_cancel))
                    }
                },
            )
        }
        is PendingImport.Legacy -> {
            val parsed = remember(pending.text) { SettingsBackup.decode(pending.text) }
            AlertDialog(
                onDismissRequest = { confirmImport = null },
                title = { Text(stringResource(R.string.backup_import_settings_confirm_title)) },
                text = {
                    Text(
                        buildString {
                            val entries = parsed?.entries?.size ?: 0
                            append(
                                context.resources.getQuantityString(
                                    R.plurals.backup_import_settings_overwrite,
                                    entries,
                                    entries,
                                ),
                            )
                            if (parsed?.containsSecrets == true) {
                                append("\n\n")
                                append(context.getString(R.string.backup_import_api_keys_note))
                            }
                            val skipped = parsed?.skipped ?: 0
                            if (skipped > 0) {
                                append("\n\n")
                                append(
                                    context.resources.getQuantityString(
                                        R.plurals.backup_import_settings_skipped,
                                        skipped,
                                        skipped,
                                    ),
                                )
                            }
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmImport = null
                        scope.launch {
                            message = when (val result = repository.importSettings(pending.text)) {
                                is SettingsRepository.ImportResult.Applied ->
                                    context.resources.getQuantityString(
                                        R.plurals.backup_restore_settings_count,
                                        result.settings,
                                        result.settings,
                                    )
                                SettingsRepository.ImportResult.RolledBack ->
                                    context.getString(R.string.backup_restore_rolled_back)
                                SettingsRepository.ImportResult.NotABackup ->
                                    context.getString(R.string.backup_not_a_settings_backup)
                            }
                        }
                    }) { Text(stringResource(CommonR.string.common_import)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmImport = null }) {
                        Text(stringResource(CommonR.string.common_cancel))
                    }
                },
            )
        }
        null -> {}
    }

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
}

/**
 * The automatic backup on a page of its own: the switch, the schedule, the
 * passphrase and what goes in. Where the backups go is on the main screen,
 * as the list of locations.
 */
@Composable
internal fun BackupAutoSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows and groups the screen holds; each row reads its
    // own value.
    val autoOn = settings.watch { it.autoBackup.enabled }
    val configured = settings.watch { it.autoBackup.backupTargets.isNotEmpty() }
    val overNetwork = settings.watch { s -> s.autoBackup.backupTargets.any { it.type.needsNetwork } }
    val locations = settings.watch { it.autoBackup.locations }
    val encryptOn = settings.watch { it.autoBackup.encrypt }
    val encrypted = settings.watch { it.autoBackup.encrypt && it.autoBackup.passphrase.isNotEmpty() }
    val settingsIncluded = settings.watch { ConfigBackup.Section.SETTINGS in it.autoBackup.sectionSet }
    val setKeys = rememberKeysSwitch(encrypted) { on -> scope.launch { repository.setBackupIncludeSecrets(on) } }
    val personal = settings.watch { s ->
        ConfigBackup.Section.DICTIONARY.id in s.autoBackup.sections ||
            ConfigBackup.Section.CLIPBOARD.id in s.autoBackup.sections ||
            ConfigBackup.Section.SWIPE.id in s.autoBackup.sections
    }

    fun resync() = scope.launch { AutoBackupScheduler.sync(context, repository.settings.first().autoBackup) }

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.backup_auto_enabled_title,
                stringResource(
                    when {
                        configured -> R.string.backup_auto_enabled_subtitle
                        locations.isEmpty() -> R.string.backup_auto_needs_location
                        else -> R.string.backup_auto_needs_target
                    },
                ),
                autoOn && configured,
                enabled = configured,
                default = SettingsDefaults.autoBackup.enabled && configured,
            ) { on ->
                scope.launch {
                    repository.setAutoBackupEnabled(on)
                    resync()
                }
            }
        }
        item(visible = autoOn && configured) {
            // The ladder by index, so every stop is a value somebody would pick.
            SliderSetting(
                R.string.backup_auto_interval_title,
                value = intervalSliderIndex(settings.watch { it.autoBackup.intervalHours }).toFloat(),
                range = 0f..(AutoBackupIntervals.size - 1).toFloat(),
                display = { backupIntervalLabel(context, intervalAt(it)) },
                default = intervalSliderIndex(SettingsDefaults.autoBackup.intervalHours).toFloat(),
            ) { index ->
                scope.launch {
                    repository.setAutoBackupIntervalHours(intervalAt(index))
                    resync()
                }
            }
        }
        item(visible = autoOn && configured) {
            SliderSetting(
                R.string.backup_auto_keep_title,
                subtitle = stringResource(R.string.backup_auto_keep_subtitle),
                value = settings.watch { it.autoBackup.keep }.toFloat(),
                range = AutoBackupKeepRange.first.toFloat()..AutoBackupKeepRange.last.toFloat(),
                display = { kept ->
                    context.resources.getQuantityString(
                        R.plurals.backup_auto_keep_value,
                        kept.roundToInt(),
                        kept.roundToInt(),
                    )
                },
                default = SettingsDefaults.autoBackup.keep.toFloat(),
            ) { kept -> scope.launch { repository.setAutoBackupKeep(kept.roundToInt()) } }
        }
        item(visible = autoOn && configured) {
            ToggleSetting(
                R.string.backup_auto_charging_title,
                stringResource(R.string.backup_auto_charging_subtitle),
                settings.watch { it.autoBackup.requireCharging },
                info = stringResource(R.string.backup_auto_charging_info),
                default = SettingsDefaults.autoBackup.requireCharging,
            ) { on ->
                scope.launch {
                    repository.setAutoBackupRequireCharging(on)
                    resync()
                }
            }
        }
        // A folder is storage on this device: a network condition there would
        // only ever delay a backup that costs nothing.
        item(visible = autoOn && configured && overNetwork) {
            ToggleSetting(
                R.string.backup_auto_unmetered_title,
                stringResource(R.string.backup_auto_unmetered_subtitle),
                settings.watch { it.autoBackup.requireUnmetered },
                info = stringResource(R.string.backup_auto_unmetered_info),
                default = SettingsDefaults.autoBackup.requireUnmetered,
            ) { on ->
                scope.launch {
                    repository.setAutoBackupRequireUnmetered(on)
                    resync()
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_auto_targets_title),
        info = stringResource(R.string.backup_auto_targets_info),
    ) {
        if (locations.isEmpty()) {
            item { WmRow(title = stringResource(R.string.backup_auto_targets_none)) }
        }
        for (location in locations) {
            item {
                LocationCheckRow(location, location.backup) { on ->
                    scope.launch {
                        repository.upsertBackupLocation(location.copy(backup = on))
                        resync()
                    }
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_auto_protect_title),
        info = stringResource(R.string.backup_auto_protect_info),
    ) {
        item {
            ToggleSetting(
                R.string.backup_auto_encrypt_title,
                stringResource(R.string.backup_auto_encrypt_subtitle),
                encryptOn,
                info = stringResource(R.string.backup_auto_encrypt_info),
                default = SettingsDefaults.autoBackup.encrypt,
            ) { on -> scope.launch { repository.setAutoBackupEncrypt(on) } }
        }
        item(visible = encryptOn) {
            StoredTextField(
                label = stringResource(R.string.backup_auto_passphrase_label),
                value = settings.watch { it.autoBackup.passphrase },
                supporting = "",
                password = true,
            ) { entered -> scope.launch { repository.setAutoBackupPassphrase(entered) } }
        }
    }

    // The one thing here that has to be said out loud: these sections send
    // words the user typed, and things they copied, off the device on a timer.
    if (personal && !encrypted) {
        StateBanner(stringResource(R.string.backup_auto_personal_warning), tone = BannerTone.WARNING)
    }

    SettingsGroup(stringResource(R.string.backup_auto_contents_title)) {
        sectionRows(
            settings = settings,
            sections = { it.autoBackup.sectionSet },
            defaults = AutoBackupSettings.DEFAULT_SECTIONS,
            secrets = { s: KeyboardSettings -> s.autoBackup.backupIncludeSecrets } to setKeys,
            settingsIncluded = settingsIncluded,
            secretsSubtitle = if (encrypted) {
                R.string.backup_include_secrets_subtitle
            } else {
                R.string.backup_secrets_unprotected_subtitle
            },
        ) { section, on ->
            scope.launch {
                val current = repository.settings.first().autoBackup.sectionSet
                repository.setAutoBackupSections(if (on) current + section else current - section)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * Sync between devices: whether this device syncs, when, where, and what.
 * The explanation of how it works is the screen's subtitle; the rules about
 * what stays on one device sit behind the "What syncs" heading.
 */
@Composable
internal fun BackupSyncSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows the groups hold; each row reads its own value.
    val syncOn = settings.watch { it.autoBackup.sync.enabled }
    val syncMode = settings.watch { it.autoBackup.sync.mode }
    val locations = settings.watch { it.autoBackup.locations }
    val encrypted = settings.watch { it.autoBackup.encrypt && it.autoBackup.passphrase.isNotEmpty() }
    var message by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    val setKeys = rememberKeysSwitch(encrypted) { on -> scope.launch { repository.setSyncIncludeSecrets(on) } }

    fun runNow() {
        syncing = true
        scope.launch {
            val outcome = SyncRunner.run(context, repository, force = true)
            syncing = false
            message = syncOutcomeText(context, outcome, repository.settings.first().autoBackup)
        }
    }

    SettingsGroup {
        item {
            val hasActive = settings.watch { it.autoBackup.activeLocations.isNotEmpty() }
            val hasTargets = settings.watch { s -> s.autoBackup.sync.targets(s.autoBackup.locations).isNotEmpty() }
            ToggleSetting(
                R.string.backup_sync_enabled_title,
                stringResource(
                    when {
                        !hasActive -> R.string.backup_auto_needs_location
                        syncOn && !hasTargets -> R.string.backup_sync_needs_target
                        else -> R.string.backup_sync_enabled_subtitle
                    },
                ),
                syncOn && hasActive,
                info = stringResource(R.string.backup_sync_first_note),
                enabled = hasActive,
                default = SettingsDefaults.autoBackup.sync.enabled,
            ) { on ->
                val auto = settings.value.autoBackup
                val active = auto.activeLocations
                val targets = auto.sync.targets(auto.locations)
                scope.launch {
                    // Turned on with nothing ticked: tick the first usable
                    // location, the focused default, rather than switch on a
                    // sync that has nowhere to go.
                    if (on && targets.isEmpty()) active.firstOrNull()?.let { repository.setSyncLocation(it.id, true) }
                    repository.setSyncEnabled(on)
                    // The first pass straight away, with the screen still
                    // open to say how it went.
                    if (on) runNow()
                }
            }
        }
        item(visible = syncOn) {
            ChoiceSetting(
                R.string.backup_sync_mode_title,
                options = listOf(
                    SyncMode.SOON to stringResource(R.string.backup_sync_mode_soon),
                    SyncMode.SCHEDULE to stringResource(R.string.backup_sync_mode_schedule),
                    SyncMode.MANUAL to stringResource(R.string.backup_sync_mode_manual),
                ),
                selected = syncMode,
                default = SettingsDefaults.autoBackup.sync.mode,
                detail = { mode ->
                    ChoiceDetail(
                        stringResource(
                            when (mode) {
                                SyncMode.SOON -> R.string.backup_sync_mode_soon_desc
                                SyncMode.SCHEDULE -> R.string.backup_sync_mode_schedule_desc
                                SyncMode.MANUAL -> R.string.backup_sync_mode_manual_desc
                            },
                        ),
                    )
                },
            ) { mode -> scope.launch { repository.setSyncMode(mode) } }
        }
        item(visible = syncOn && syncMode == SyncMode.SCHEDULE) {
            SliderSetting(
                R.string.backup_sync_interval_title,
                value = intervalSliderIndex(settings.watch { it.autoBackup.sync.intervalHours }).toFloat(),
                range = 0f..(AutoBackupIntervals.size - 1).toFloat(),
                display = { backupIntervalLabel(context, intervalAt(it)) },
                default = intervalSliderIndex(SettingsDefaults.autoBackup.sync.intervalHours).toFloat(),
            ) { index -> scope.launch { repository.setSyncIntervalHours(intervalAt(index)) } }
        }
        item(visible = syncOn) {
            val keysProtected = settings.watch { it.autoBackup.encrypt && it.autoBackup.passphrase.isNotEmpty() }
            ToggleSetting(
                R.string.backup_sync_secrets_title,
                stringResource(
                    if (keysProtected) R.string.backup_sync_secrets_subtitle else R.string.backup_sync_secrets_unprotected,
                ),
                settings.watch { it.autoBackup.sync.includeSecrets },
                default = SettingsDefaults.autoBackup.sync.includeSecrets,
            ) { on -> setKeys(on) }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_sync_targets_title),
        info = stringResource(R.string.backup_sync_targets_info),
    ) {
        if (locations.isEmpty()) {
            item { WmRow(title = stringResource(R.string.backup_auto_targets_none)) }
        }
        for (location in locations) {
            item {
                LocationCheckRow(location, settings.watch { location.id in it.autoBackup.sync.locationIds }) { on ->
                    scope.launch { repository.setSyncLocation(location.id, on) }
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_sync_contents_title),
        info = stringResource(R.string.backup_sync_local_note),
    ) {
        sectionRows(
            settings = settings,
            sections = { it.autoBackup.sync.sectionSet },
            defaults = SyncSettings.DEFAULT_SECTIONS,
            secrets = null,
        ) { section, on ->
            scope.launch {
                val current = repository.settings.first().autoBackup.sync.sectionSet
                repository.setSyncSections(if (on) current + section else current - section)
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_sync_keep_local_title),
        info = stringResource(R.string.backup_sync_keep_local_info),
    ) {
        for (group in SyncKeys.LocalGroup.entries) {
            item {
                val syncsSettings = settings.watch { ConfigBackup.Section.SETTINGS in it.autoBackup.sync.sectionSet }
                ToggleSetting(
                    when (group) {
                        SyncKeys.LocalGroup.TOOLBAR -> R.string.backup_sync_keep_toolbar_title
                        SyncKeys.LocalGroup.LAYOUTS -> R.string.backup_sync_keep_layouts_title
                    },
                    stringResource(
                        when {
                            !syncsSettings -> R.string.backup_sync_keep_needs_settings
                            group == SyncKeys.LocalGroup.TOOLBAR -> R.string.backup_sync_keep_toolbar_subtitle
                            else -> R.string.backup_sync_keep_layouts_subtitle
                        },
                    ),
                    settings.watch { group in it.autoBackup.sync.keepLocalGroups },
                    enabled = syncsSettings,
                    default = false,
                ) { on ->
                    scope.launch {
                        repository.setSyncKeepLocal(group, on)
                        // Let go of: take what the other devices have now,
                        // not whenever the next change happens to be made.
                        if (!on) SyncScheduler.requestSoon(context, repository.settings.first().autoBackup, delayMs = 0L)
                    }
                }
            }
        }
    }

    SettingsGroup {
        item {
            val canSync = settings.watch { s ->
                s.autoBackup.sync.enabled && s.autoBackup.sync.targets(s.autoBackup.locations).isNotEmpty()
            }
            FilledTonalButton(
                enabled = canSync && !syncing,
                onClick = { runNow() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (syncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(if (syncing) R.string.backup_hub_syncing else R.string.backup_hub_sync_now))
            }
        }
    }
    val syncError = settings.watch { s ->
        s.autoBackup.sync.lastError.takeIf { it.isNotEmpty() }?.let { syncErrorText(context, it, s.autoBackup) }
    }
    val lastSyncAtMs = settings.watch { it.autoBackup.sync.lastRunAtMs }
    StateBanner(
        syncError ?: if (lastSyncAtMs > 0L) {
            stringResource(
                R.string.backup_hub_last_sync,
                DateUtils.getRelativeTimeSpanString(lastSyncAtMs).toString(),
            )
        } else {
            stringResource(R.string.backup_hub_never_synced)
        },
        tone = if (syncError != null) BannerTone.WARNING else BannerTone.INFO,
    )
    Spacer(Modifier.height(16.dp))

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

/** What the one-off "Export to a file" puts in. The automatic backup and sync have their own lists. */
@Composable
internal fun BackupContentsSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // Decides whether the key switch is among the rows.
    val settingsIncluded = settings.watch { ConfigBackup.Section.SETTINGS in it.autoBackup.exportSectionSet }
    SettingsGroup {
        sectionRows(
            settings = settings,
            sections = { it.autoBackup.exportSectionSet },
            defaults = AutoBackupSettings.DEFAULT_SECTIONS,
            secrets = { s: KeyboardSettings -> s.autoBackup.includeSecrets } to
                { on -> scope.launch { repository.setAutoBackupIncludeSecrets(on) } },
            settingsIncluded = settingsIncluded,
        ) { section, on ->
            scope.launch {
                val current = repository.settings.first().autoBackup.exportSectionSet
                repository.setExportSections(if (on) current + section else current - section)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** How tall a multi-line [StoredTextField] grows before it scrolls. */
private const val MULTI_LINE_MAX = 6
