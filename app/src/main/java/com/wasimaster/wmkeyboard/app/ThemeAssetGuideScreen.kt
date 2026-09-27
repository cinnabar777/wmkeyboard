package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.findThemeFamily
import com.wasimaster.wmkeyboard.core.theme.selfAndVariants
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The image guide of one theme, as the nav graph names it (issue #397). */
internal fun themeAssetsRoute(themeId: String): String = "theme_assets/$themeId"

/**
 * Settings › Themes › (a theme) › Image sizes and templates (issue #397).
 *
 * What a person drawing a theme needs before they start: how big each picture
 * the theme can carry really is, and a template to draw on. The numbers come
 * from the keyboard itself, laid out unseen for the chosen screen — this
 * phone with its own layout and sizing by default, or a common phone, the
 * same phone on its side, or a tablet with the shipped sizing, for a theme
 * meant for other people.
 */
@Composable
internal fun ThemeAssetGuideScreen(settings: LiveSettings, themeId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme = settings.watch { s ->
        s.customThemes.findThemeFamily(themeId)?.selfAndVariants()?.find { it.id == themeId }
    }
    if (theme == null) {
        Text(
            stringResource(R.string.theme_editor_missing_body),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val own = settings.watch { it }
    var preset by rememberSaveable { mutableStateOf(AssetPreset.CURRENT) }
    // Keyed by the preset, so switching never shows one screen's numbers
    // under another screen's name while the new board is measured.
    var measured by remember { mutableStateOf<Pair<AssetPreset, BoardGeometry>?>(null) }
    val board = measured?.takeIf { it.first == preset }?.second
    // The sizing the numbers are for, which is also what the key corners and
    // the popup height are read from.
    val sizing = if (preset.usesOwnSettings) own else SettingsDefaults
    val labels = templateLabels(context)

    ThemeAssetBoard(
        settings = own,
        theme = theme,
        preset = preset,
        onGeometry = { measured = preset to it },
    )

    Text(
        stringResource(R.string.theme_assets_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    ChoiceControl(
        options = AssetPreset.entries.map { it to stringResource(presetLabelRes(it)) },
        selected = preset,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        label = stringResource(R.string.theme_assets_screen_label),
        detail = { ChoiceDetail(stringResource(presetDescRes(it))) },
    ) { preset = it }

    val diagram by produceState<Bitmap?>(null, board, theme, sizing) {
        value = board?.let { geometry ->
            withContext(Dispatchers.Default) {
                renderBoardTemplate(geometry, { keyCornerPx(theme, sizing, geometry.density, it.width, it.height) }, labels)
            }
        }
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.inverseSurface),
        contentAlignment = Alignment.Center,
    ) {
        val shown = diagram
        if (shown == null) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = stringResource(R.string.theme_assets_diagram_desc),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(shown.width / shown.height.toFloat()),
            )
        }
    }

    val sizes = remember(board, theme, sizing) {
        board?.let { assetSizes(it, popupHeightDp(theme, sizing)) }.orEmpty()
    }
    val presetName = stringResource(presetLabelRes(preset))
    val packLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ZIP_MIME),
    ) { uri ->
        val geometry = board ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        writeTemplatePack(context, out, geometry, sizes, theme, sizing, preset, presetName, labels)
                    }
                }.isSuccess
            }
            toast(context, if (saved) R.string.theme_assets_saved else R.string.theme_assets_save_failed)
        }
    }
    val boardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PNG_MIME),
    ) { uri ->
        val geometry = board ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        boardBitmap(geometry, theme, sizing, labels).writePng(out)
                    }
                }.isSuccess
            }
            toast(context, if (saved) R.string.theme_assets_saved else R.string.theme_assets_save_failed)
        }
    }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = { packLauncher.launch("${fileStem(theme, preset)}.zip") },
            enabled = board != null,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.theme_assets_save_pack)) }
        OutlinedButton(
            onClick = { boardLauncher.launch("${fileStem(theme, preset)}-board.png") },
            enabled = board != null,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.theme_assets_save_board)) }
    }

    SettingsGroup(
        stringResource(R.string.theme_assets_sizes_section),
        info = stringResource(R.string.theme_assets_sizes_info),
    ) {
        if (sizes.isEmpty()) {
            item {
                WmRow(title = stringResource(R.string.theme_assets_measuring))
            }
        }
        for (size in sizes) {
            item {
                WmRow(
                    title = stringResource(slotTitleRes(size.slot, preset)),
                    subtitle = sizeLine(size) + "\n" + stringResource(slotNoteRes(size.slot)),
                )
            }
        }
    }
}

@Composable
private fun sizeLine(size: AssetSize): String =
    if (size.screenW == size.templateW && size.screenH == size.templateH) {
        stringResource(R.string.theme_assets_size_exact, size.screenW, size.screenH)
    } else {
        stringResource(
            R.string.theme_assets_size_line,
            size.screenW,
            size.screenH,
            size.templateW,
            size.templateH,
        )
    }

private fun templateLabels(context: Context) = TemplateLabels(
    toolbar = context.getString(R.string.theme_assets_template_toolbar),
    navBar = context.getString(R.string.theme_assets_template_nav_bar),
    labelArea = context.getString(R.string.theme_assets_template_label_area),
)

private fun boardBitmap(
    board: BoardGeometry,
    theme: ThemeSpec,
    sizing: KeyboardSettings,
    labels: TemplateLabels,
): Bitmap = renderBoardTemplate(board, { keyCornerPx(theme, sizing, board.density, it.width, it.height) }, labels)

/**
 * The template pack: the board, one template per key slot and the popup, and
 * a text file with every size in it, so the numbers travel with the pictures
 * to whatever the user draws in.
 */
private fun writeTemplatePack(
    context: Context,
    out: OutputStream,
    board: BoardGeometry,
    sizes: List<AssetSize>,
    theme: ThemeSpec,
    sizing: KeyboardSettings,
    preset: AssetPreset,
    presetName: String,
    labels: TemplateLabels,
) {
    ZipOutputStream(out).use { zip ->
        fun entry(name: String, write: (OutputStream) -> Unit) {
            zip.putNextEntry(ZipEntry(name))
            write(zip)
            zip.closeEntry()
        }
        entry("board.png") { boardBitmap(board, theme, sizing, labels).writePng(it) }
        for (size in sizes) {
            val name = templateFileName(size.slot) ?: continue
            val scale = size.templateW / size.screenW.toFloat().coerceAtLeast(1f)
            val corner = keyCornerPx(theme, sizing, board.density * scale, size.templateW.toFloat(), size.templateH.toFloat())
            entry(name) { renderKeyTemplate(size.templateW, size.templateH, corner, labels).writePng(it) }
        }
        entry("README.txt") { it.write(readme(context, board, sizes, preset, presetName).toByteArray()) }
    }
}

private fun readme(
    context: Context,
    board: BoardGeometry,
    sizes: List<AssetSize>,
    preset: AssetPreset,
    presetName: String,
): String =
    buildString {
        appendLine(context.getString(R.string.theme_assets_readme_title))
        appendLine(context.getString(R.string.theme_assets_readme_screen, presetName, board.density))
        appendLine()
        for (size in sizes) {
            appendLine(context.getString(slotTitleRes(size.slot, preset)))
            appendLine("  " + context.getString(R.string.theme_assets_readme_on_screen, size.screenW, size.screenH))
            appendLine("  " + context.getString(R.string.theme_assets_readme_template, size.templateW, size.templateH))
            templateFileName(size.slot)?.let { appendLine("  $it") }
            appendLine("  " + context.getString(slotNoteRes(size.slot)))
            appendLine()
        }
        appendLine(context.getString(R.string.theme_assets_sizes_info))
    }

/** The slots that get a template of their own in the pack; the rest are sizes only. */
private fun templateFileName(slot: AssetSlot): String? = when (slot) {
    AssetSlot.KEY -> "key-letter.png"
    AssetSlot.MODIFIER -> "key-modifier.png"
    AssetSlot.ENTER -> "key-enter.png"
    AssetSlot.SPACE -> "key-space.png"
    AssetSlot.POPUP -> "popup.png"
    else -> null
}

private fun fileStem(theme: ThemeSpec, preset: AssetPreset): String {
    val name = theme.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" }
    return "$name-${preset.name.lowercase().replace('_', '-')}"
}

private fun Bitmap.writePng(out: OutputStream) {
    compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
}

private fun toast(context: Context, @StringRes message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

@StringRes
private fun presetLabelRes(preset: AssetPreset): Int = when (preset) {
    AssetPreset.CURRENT -> R.string.theme_assets_preset_current
    AssetPreset.PHONE -> R.string.theme_assets_preset_phone
    AssetPreset.PHONE_LANDSCAPE -> R.string.theme_assets_preset_landscape
    AssetPreset.TABLET -> R.string.theme_assets_preset_tablet
}

@StringRes
private fun presetDescRes(preset: AssetPreset): Int = when (preset) {
    AssetPreset.CURRENT -> R.string.theme_assets_preset_current_desc
    AssetPreset.PHONE -> R.string.theme_assets_preset_phone_desc
    AssetPreset.PHONE_LANDSCAPE -> R.string.theme_assets_preset_landscape_desc
    AssetPreset.TABLET -> R.string.theme_assets_preset_tablet_desc
}

@StringRes
private fun slotTitleRes(slot: AssetSlot, preset: AssetPreset): Int = when (slot) {
    AssetSlot.BACKGROUND -> if (preset.landscape) {
        R.string.theme_assets_slot_background_landscape
    } else {
        R.string.theme_assets_slot_background
    }
    AssetSlot.KEY -> R.string.theme_assets_slot_key
    AssetSlot.MODIFIER -> R.string.theme_assets_slot_modifier
    AssetSlot.ENTER -> R.string.theme_assets_slot_enter
    AssetSlot.SPACE -> R.string.theme_assets_slot_space
    AssetSlot.PRESSED -> R.string.theme_assets_slot_pressed
    AssetSlot.POPUP -> R.string.theme_assets_slot_popup
    AssetSlot.STICKER -> R.string.theme_assets_slot_sticker
    AssetSlot.EFFECT -> R.string.theme_assets_slot_effect
}

@StringRes
private fun slotNoteRes(slot: AssetSlot): Int = when (slot) {
    AssetSlot.BACKGROUND -> R.string.theme_assets_note_background
    AssetSlot.KEY, AssetSlot.MODIFIER, AssetSlot.ENTER, AssetSlot.PRESSED -> R.string.theme_assets_note_key
    AssetSlot.SPACE -> R.string.theme_assets_note_space
    AssetSlot.POPUP -> R.string.theme_assets_note_popup
    AssetSlot.STICKER -> R.string.theme_assets_note_sticker
    AssetSlot.EFFECT -> R.string.theme_assets_note_effect
}

private const val ZIP_MIME = "application/zip"
private const val PNG_MIME = "image/png"
private const val PNG_QUALITY = 100
