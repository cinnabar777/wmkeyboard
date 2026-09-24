package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.theme.GboardConverted
import com.wasimaster.wmkeyboard.core.theme.GboardLook
import com.wasimaster.wmkeyboard.core.theme.GboardResult
import com.wasimaster.wmkeyboard.core.theme.GboardTheme
import com.wasimaster.wmkeyboard.core.theme.GboardUnsupported
import com.wasimaster.wmkeyboard.core.theme.RboardCatalog
import com.wasimaster.wmkeyboard.core.theme.RboardPack
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.groupAsFamily
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The Rboard collection screen. */
internal const val RBOARD_THEMES_ROUTE = "rboard_themes"

// ---- saving ----

/**
 * One converted Gboard theme, stored: a single theme, or one family whose looks
 * are the theme with and without key borders.
 *
 * Saved, never switched to — the rule every converted theme follows here. A
 * theme from another keyboard is the thing worth looking at before it becomes
 * the keyboard, and the editor is where the parts that did not come across get
 * finished.
 */
internal suspend fun saveGboardTheme(
    context: Context,
    repository: SettingsRepository,
    theme: GboardConverted,
    baseId: String = "custom_${System.currentTimeMillis()}",
): ThemeSpec {
    val entry = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "theme_images").apply { mkdirs() }
        val looks = theme.looks.mapIndexed { index, look ->
            // Distinct ids per look: the extracted image files are keyed on
            // the id, so a shared one would have one look overwrite the other's.
            look.converted.stored(if (index == 0) baseId else "${baseId}_v$index", dir)
                .copy(name = gboardLookName(context, theme, look))
        }
        groupAsFamily(looks, theme.name)
    }
    repository.upsertCustomTheme(entry)
    return entry
}

/** Every theme of a pack, each its own entry in the gallery. */
internal suspend fun saveGboardPack(
    context: Context,
    repository: SettingsRepository,
    themes: List<GboardConverted>,
): String {
    val stamp = System.currentTimeMillis()
    themes.forEachIndexed { index, theme ->
        saveGboardTheme(context, repository, theme, baseId = "custom_${stamp}_p$index")
    }
    return context.resources.getQuantityString(R.plurals.import_gboard_done_pack, themes.size, themes.size)
}

/** What the result dialog says after one theme was saved. */
internal fun gboardSavedMessage(context: Context, theme: GboardConverted): String =
    if (theme.looks.size >= 2) {
        context.getString(R.string.import_gboard_done_family, theme.name, theme.looks.size)
    } else {
        context.getString(R.string.import_gboard_done, theme.name)
    }

/**
 * A look's name. A theme with one look keeps its own name; the two looks of a
 * bordered theme say which is which, since the family card names the theme.
 */
private fun gboardLookName(context: Context, theme: GboardConverted, look: GboardLook): String = when {
    theme.looks.size < 2 -> theme.name
    look.bordered -> context.getString(R.string.import_gboard_look_borders, theme.name)
    else -> context.getString(R.string.import_gboard_look_flat, theme.name)
}

/** The confirm dialog's words for one theme: the rule count, and the looks. */
internal fun gboardThemeBody(context: Context, theme: GboardConverted, author: String): String = buildString {
    append(context.getString(R.string.import_gboard_body, theme.mappedRuleCount, theme.ruleCount))
    if (theme.looks.size >= 2) append("\n\n").append(context.getString(R.string.import_gboard_looks_body))
    if (author.isNotBlank()) append("\n\n").append(context.getString(R.string.import_gboard_credit, author))
}

/** One line of the "what will change" list. */
internal fun gboardDroppedLine(context: Context, dropped: GboardUnsupported): String =
    context.getString(
        when (dropped) {
            GboardUnsupported.UNREADABLE_STYLESHEET -> R.string.import_gboard_dropped_stylesheet
            GboardUnsupported.KEY_ICONS -> R.string.import_gboard_dropped_icons
            GboardUnsupported.KEY_SPACING -> R.string.import_gboard_dropped_spacing
            GboardUnsupported.FONT -> R.string.import_gboard_dropped_font
            GboardUnsupported.SHADOW_COLOR -> R.string.import_gboard_dropped_shadow_color
            GboardUnsupported.PER_CORNER_RADIUS -> R.string.import_gboard_dropped_corners
            GboardUnsupported.EXTRA_IMAGES -> R.string.import_gboard_dropped_images
            // The same sentence the FlorisBoard import uses, because it is the
            // same thing happening.
            GboardUnsupported.LOW_CONTRAST_FALLBACK -> R.string.import_floris_dropped_contrast
        },
    )

/** The message for a file that did not convert. */
internal fun gboardFailureMessage(context: Context, result: GboardResult): String = context.getString(
    if (result == GboardResult.Compiled) R.string.import_gboard_compiled_body else R.string.import_gboard_unreadable_body,
)

// ---- dialogs ----

/**
 * What the gallery shows once a Gboard file has been read: straight to the
 * confirm dialog for a theme, through a list first for a pack. [onMessage]
 * carries whatever there is to say afterwards; [onDismiss] closes the flow.
 */
@Composable
internal fun GboardImportDialogs(
    result: GboardResult.Converted,
    repository: SettingsRepository,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember(result) { mutableStateOf(result.themes.singleOrNull()) }
    val chosen = picked
    if (chosen != null) {
        GboardThemeConfirmDialog(
            theme = chosen,
            author = result.packAuthor,
            repository = repository,
            onDismiss = { if (result.themes.size > 1) picked = null else onDismiss() },
            onSaved = { message ->
                onDismiss()
                onMessage(message)
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.packName.ifBlank { stringResource(R.string.import_gboard_pack_fallback_title) }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(pluralStringResource(R.plurals.import_gboard_pack_body, result.themes.size, result.themes.size))
                if (result.skipped > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(pluralStringResource(R.plurals.import_gboard_pack_skipped, result.skipped, result.skipped))
                }
                Spacer(Modifier.height(8.dp))
                for (theme in result.themes) {
                    Text(
                        theme.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = theme }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val message = saveGboardPack(context, repository, result.themes)
                    onDismiss()
                    onMessage(message)
                }
            }) { Text(stringResource(R.string.import_gboard_pack_add_all)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * One theme, shown before it is saved: a miniature of its first look, the
 * count of style rules that came across, and what did not.
 */
@Composable
internal fun GboardThemeConfirmDialog(
    theme: GboardConverted,
    author: String,
    repository: SettingsRepository,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val preview by produceState<ThemeSpec?>(null, theme) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "gboard_preview").apply {
                    deleteRecursively()
                    mkdirs()
                }
                theme.looks.first().converted.stored("gboard_preview", dir)
            }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(theme.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                preview?.let {
                    ThemePreview(it, animatedBadge = false)
                    Spacer(Modifier.height(12.dp))
                }
                Text(remember(theme, author) { gboardThemeBody(context, theme, author) })
                if (theme.dropped.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.import_repairs_pending_title), fontWeight = FontWeight.Medium)
                    for (line in theme.dropped) Text("• ${gboardDroppedLine(context, line)}")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        saveGboardTheme(context, repository, theme)
                        onSaved(gboardSavedMessage(context, theme))
                    }
                },
            ) { Text(stringResource(CommonR.string.common_import)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}

// ---- the Rboard collection ----

/** Where the screen has got to with the index. */
private sealed interface RboardIndexLoad {
    data object Waiting : RboardIndexLoad
    data object Loading : RboardIndexLoad
    data class Loaded(val packs: List<RboardPack>) : RboardIndexLoad
    data class Failed(val text: String) : RboardIndexLoad
}

/** Where the screen has got to with the pack being looked at. */
private sealed interface RboardPackLoad {
    data class Loading(val pack: RboardPack) : RboardPackLoad
    data class Opened(val pack: RboardPack, val result: GboardResult.Converted) : RboardPackLoad
    data class Failed(val pack: RboardPack, val text: String) : RboardPackLoad
}

/**
 * The Rboard community's theme packs, from its repository on GitHub.
 *
 * Nothing is fetched until this screen opens, and data saving is asked once,
 * before the first request, the way the Signal pack preview asks: the answer
 * covers the list and the packs opened from it, which are one browse as far as
 * the user is concerned. A pack is downloaded only when pressed, checked
 * against the SHA-256 the list gives for it, and kept in the cache so pressing
 * it again costs nothing.
 *
 * The index is somebody else's file. If it stops being the shape
 * [RboardCatalog] reads, the screen says the list could not be read and offers
 * Retry; a theme ZIP from anywhere still imports through the gallery's button.
 */
@Composable
internal fun RboardThemesScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheDir = remember { File(context.cacheDir, "rboard") }

    var index by remember { mutableStateOf<RboardIndexLoad>(RboardIndexLoad.Waiting) }
    var pack by remember { mutableStateOf<RboardPackLoad?>(null) }
    var askMetered by remember { mutableStateOf(false) }
    var blockedMetered by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<GboardConverted?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val listError = stringResource(R.string.rboard_list_error)
    val mismatch = stringResource(R.string.rboard_pack_mismatch)
    val emptyPack = stringResource(R.string.rboard_pack_empty)

    fun fetchIndex() {
        index = RboardIndexLoad.Loading
        scope.launch {
            index = withContext(Dispatchers.IO) {
                try {
                    val file = File(cacheDir, "list.json")
                    ToolHttp.download(
                        RboardCatalog.INDEX_URL,
                        file,
                        maxBytes = RboardCatalog.MAX_INDEX_BYTES.toLong(),
                        source = NetSource.RBOARD_THEMES,
                        route = NetLog.pathOf(RboardCatalog.INDEX_URL),
                    )
                    RboardCatalog.parse(file.readText())
                        ?.let { RboardIndexLoad.Loaded(it.sortedBy { p -> p.name.lowercase() }) }
                        ?: RboardIndexLoad.Failed(listError)
                } catch (e: IOException) {
                    RboardIndexLoad.Failed(ToolHttp.friendlyMessage(context, e))
                }
            }
        }
    }

    fun openPack(target: RboardPack) {
        pack = RboardPackLoad.Loading(target)
        scope.launch {
            pack = withContext(Dispatchers.IO) {
                try {
                    val file = downloadPack(target, cacheDir)
                        ?: return@withContext RboardPackLoad.Failed(target, mismatch)
                    when (val result = file.inputStream().use { GboardTheme.read(it, target.name) }) {
                        is GboardResult.Converted -> RboardPackLoad.Opened(target, result)
                        GboardResult.Compiled -> RboardPackLoad.Failed(target, gboardFailureMessage(context, result))
                        else -> RboardPackLoad.Failed(target, emptyPack)
                    }
                } catch (e: IOException) {
                    RboardPackLoad.Failed(target, ToolHttp.friendlyMessage(context, e))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> fetchIndex()
            MeteredDecision.ASK -> askMetered = true
            MeteredDecision.BLOCKED -> blockedMetered = true
        }
    }

    BackHandler(enabled = pack != null) { pack = null }

    SettingsGroup(info = stringResource(R.string.rboard_caption)) {
        item {
            WmRow(
                title = stringResource(R.string.rboard_screen_title),
                subtitle = RboardCatalog.REPOSITORY.removePrefix("https://raw.githubusercontent.com/").trimEnd('/'),
                icon = Icons.Outlined.Palette,
                accent = routeAccent("themes"),
            )
        }
    }

    when (val current = pack) {
        null -> PackList(index, onRetry = { fetchIndex() }, onOpen = { openPack(it) })
        is RboardPackLoad.Loading -> SettingsGroup(current.pack.name) {
            item { WmRow(title = stringResource(R.string.rboard_pack_downloading, current.pack.name)) }
        }
        is RboardPackLoad.Failed -> SettingsGroup(current.pack.name) {
            item { WmRow(title = current.text) }
            item { WmRow(title = stringResource(CommonR.string.common_retry), onClick = { openPack(current.pack) }) }
            item { BackToList { pack = null } }
        }
        is RboardPackLoad.Opened -> OpenedPack(current, onBack = { pack = null }, onPick = { confirming = it })
    }

    confirming?.let { theme ->
        GboardThemeConfirmDialog(
            theme = theme,
            author = (pack as? RboardPackLoad.Opened)?.pack?.author.orEmpty(),
            repository = repository,
            onDismiss = { confirming = null },
            onSaved = { text ->
                confirming = null
                message = text
            },
        )
    }
    if (blockedMetered) {
        MeteredBlockedDialog {
            blockedMetered = false
            onBack()
        }
    }
    if (askMetered) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                askMetered = false
                fetchIndex()
            },
            onDismiss = {
                askMetered = false
                onBack()
            },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

@Composable
private fun PackList(index: RboardIndexLoad, onRetry: () -> Unit, onOpen: (RboardPack) -> Unit) {
    when (index) {
        RboardIndexLoad.Waiting, RboardIndexLoad.Loading -> SettingsGroup {
            item { WmRow(title = stringResource(R.string.rboard_list_loading)) }
        }
        is RboardIndexLoad.Failed -> SettingsGroup {
            item { WmRow(title = index.text) }
            item { WmRow(title = stringResource(CommonR.string.common_retry), onClick = onRetry) }
        }
        is RboardIndexLoad.Loaded -> SettingsGroup(stringResource(R.string.rboard_packs_section_title)) {
            for (entry in index.packs) {
                item {
                    val count = pluralStringResource(R.plurals.rboard_pack_theme_count, entry.themes.size, entry.themes.size)
                    WmRow(
                        title = entry.name,
                        subtitle = if (entry.author.isBlank()) {
                            count
                        } else {
                            stringResource(R.string.rboard_pack_subtitle, count, entry.author)
                        },
                        onClick = { onOpen(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackToList(onClick: () -> Unit) {
    WmRow(
        title = stringResource(R.string.rboard_back_to_list),
        icon = Icons.AutoMirrored.Outlined.ArrowBack,
        accent = routeAccent("themes"),
        onClick = onClick,
    )
}

/** A pack's themes, two to a row, each with the picture Rboard ships for it. */
@Composable
private fun OpenedPack(opened: RboardPackLoad.Opened, onBack: () -> Unit, onPick: (GboardConverted) -> Unit) {
    val result = opened.result
    SettingsGroup(result.packName.ifBlank { opened.pack.name }) {
        item {
            WmRow(
                title = pluralStringResource(R.plurals.rboard_pack_theme_count, result.themes.size, result.themes.size),
                subtitle = result.skipped.takeIf { it > 0 }?.let {
                    pluralStringResource(R.plurals.import_gboard_pack_skipped, it, it)
                },
            )
        }
        item { BackToList(onBack) }
    }
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in result.themes.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (theme in row) {
                    Box(Modifier.weight(1f)) { ThemeCell(theme) { onPick(theme) } }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeCell(theme: GboardConverted, onClick: () -> Unit) {
    val picture by produceState<ImageBitmap?>(null, theme) {
        value = theme.preview?.let { bytes -> withContext(Dispatchers.Default) { decodeSampled(bytes) } }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        val shown = picture
        if (shown != null) {
            androidx.compose.foundation.Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            // No picture in the pack: the colours themselves, drawn the way
            // the gallery draws a theme.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) { ThemePreview(theme.looks.first().converted.theme, animatedBadge = false) }
        }
        Text(
            theme.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
    }
}

/**
 * A preview picture decoded no larger than a cell needs. Rboard's are 1280 px
 * wide, and a pack of twenty decoded at full size would be 90 MB of bitmaps.
 */
private fun decodeSampled(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= PREVIEW_MAX_PX) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

/**
 * The pack's file, downloaded or from the cache, or null when what arrived is
 * not what the index describes.
 */
private fun downloadPack(pack: RboardPack, cacheDir: File): File? {
    val name = (pack.sha256 ?: sha256Hex(pack.url.toByteArray())) + ".zip"
    val file = File(cacheDir, name)
    if (file.isFile && (pack.sha256 == null || sha256Hex(file) == pack.sha256)) return file
    ToolHttp.download(
        pack.url,
        file,
        timeoutMs = 30_000,
        maxBytes = RboardCatalog.MAX_PACK_BYTES,
        source = NetSource.RBOARD_THEMES,
        route = NetLog.pathOf(pack.url),
    )
    if (pack.sha256 != null && sha256Hex(file) != pack.sha256) {
        file.delete()
        return null
    }
    return file
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Rboard's preview pictures are about 3:2. */
private const val PREVIEW_ASPECT = 1.5f

/** Wide enough for half a phone screen at a high density. */
private const val PREVIEW_MAX_PX = 480
