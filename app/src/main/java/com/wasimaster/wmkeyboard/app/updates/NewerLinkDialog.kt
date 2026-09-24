package com.wasimaster.wmkeyboard.app.updates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.rememberDisclosedSpecialAccess
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.support.Support

/**
 * Says why a settings link opened nothing, and what to do about it.
 *
 * A link from the documentation site, a support reply or a chat can name a
 * screen or a setting that arrived after the version on this phone. Before this
 * dialog such a link simply opened the app on whatever it last showed, which
 * reads as a broken link. Now the app asks its own updater whether a version
 * that has it is out:
 *
 * - it is: offer the update, through the same controls as the update card;
 * - it is not: say the version is not released yet, and that the way to have
 *   it now is to build the app from its source code;
 * - the link says this version should have it: say that an update is not the
 *   answer, because the link is wrong or the setting has moved.
 *
 * The check is the one the About screen's row runs, so it obeys the same rules.
 * On F-Droid, where the app checks only when asked, the dialog asks first.
 */
@Composable
internal fun NewerLinkDialog(link: MissingLink, settings: LiveSettings, onDismiss: () -> Unit) {
    val updater = LocalAppUpdater.current
    val state by updater.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val installed = BuildConfig.VERSION_NAME
    val autoCheck = !UpdateChannel.FDROID
    LaunchedEffect(link) {
        if (LinkVerdict.shouldCheck(link.since, installed, updater.state.value, autoCheck)) {
            updater.check(userAsked = true)
        }
    }
    val verdict = LinkVerdict.of(link.since, installed, state, autoCheck)
    val download = rememberUpdateDownloadRequest(
        updater,
        (state as? UpdateState.Available)?.sizeBytes ?: 0L,
        settings,
    )
    val grant = updater.installGrant
    val requestGrant = rememberDisclosedSpecialAccess(SpecialAccess.INSTALL_UPDATES)
    val since = link.since.takeIf { it.isNotEmpty() }

    val title = when {
        verdict == LinkVerdict.NotInThisVersion -> stringResource(R.string.link_update_title_missing)
        verdict == LinkVerdict.NotReleased -> stringResource(R.string.link_update_title_unreleased)
        since != null -> stringResource(R.string.link_update_title_versioned, since)
        else -> stringResource(R.string.link_update_title)
    }
    val lead = stringResource(
        if (link.row) R.string.link_update_lead_row else R.string.link_update_lead_screen,
        installed,
    )
    val status = when (verdict) {
        LinkVerdict.NotInThisVersion -> stringResource(R.string.link_update_missing_body, link.since)
        LinkVerdict.Ask -> stringResource(R.string.link_update_ask)
        LinkVerdict.Checking -> stringResource(R.string.link_update_checking)
        is LinkVerdict.Update -> when (val name = verdict.versionName) {
            null -> stringResource(R.string.link_update_available, stringResource(updater.sourceNameRes))
            else -> stringResource(
                R.string.link_update_available_versioned,
                name,
                stringResource(updater.sourceNameRes),
            )
        }
        LinkVerdict.NotReleased -> when (since) {
            null -> stringResource(R.string.link_update_not_released)
            else -> stringResource(R.string.link_update_not_released_versioned, since)
        }
        LinkVerdict.Updating -> stringResource(R.string.link_update_updating)
        is LinkVerdict.CannotCheck -> when {
            verdict.failed -> stringResource(R.string.link_update_check_failed)
            since != null -> stringResource(R.string.link_update_no_updater_versioned, since)
            else -> stringResource(R.string.link_update_no_updater)
        }
    }

    // The one thing worth pressing in each case, or null where the answer is
    // only to close the dialog.
    val action: Pair<String, () -> Unit>? = when (verdict) {
        LinkVerdict.Ask ->
            stringResource(R.string.update_row_check_title) to { updater.check(userAsked = true) }
        is LinkVerdict.Update -> startActionLabel(updater) to download
        LinkVerdict.NotReleased ->
            stringResource(R.string.link_update_action_source) to { uriHandler.openUri(Support.SOURCE_URL) }
        LinkVerdict.Updating -> if (state == UpdateState.Downloaded) {
            stringResource(R.string.update_action_install) to {
                // Same as the card: the press is recorded either way, and the
                // install picks up after the grant when one is needed.
                updater.install()
                if (grant != null) requestGrant()
            }
        } else {
            null
        }
        is LinkVerdict.CannotCheck -> if (verdict.failed) {
            stringResource(CommonR.string.common_retry) to { updater.check(userAsked = true) }
        } else {
            stringResource(R.string.link_update_action_releases) to { uriHandler.openUri(GithubReleases.LIST_PAGE) }
        }
        LinkVerdict.NotInThisVersion,
        LinkVerdict.Checking,
        -> null
    }
    val closeLabel = stringResource(CommonR.string.common_close)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(lead)
                Spacer(Modifier.height(8.dp))
                Text(status)
                val busy = verdict == LinkVerdict.Checking || state == UpdateState.Installing
                val downloading = state as? UpdateState.Downloading
                if (busy || downloading != null) {
                    Spacer(Modifier.height(16.dp))
                    val fraction = downloading?.fraction
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            if (action != null) {
                TextButton(onClick = action.second) { Text(action.first) }
            } else {
                TextButton(onClick = onDismiss) { Text(closeLabel) }
            }
        },
        dismissButton = if (action != null) {
            { TextButton(onClick = onDismiss) { Text(closeLabel) } }
        } else {
            null
        },
    )
}
