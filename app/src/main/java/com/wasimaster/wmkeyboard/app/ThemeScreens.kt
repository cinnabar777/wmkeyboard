package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.BuildConfig
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.core.net.toUri
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPlayer
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.ui.WmSlider
import com.wasimaster.wmkeyboard.core.ui.rememberLiveSlider
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.settings.AutoThemeTrigger
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.RotationInterval
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.slotFixedId
import com.wasimaster.wmkeyboard.core.settings.slotPool
import com.wasimaster.wmkeyboard.core.settings.slotRandom
import com.wasimaster.wmkeyboard.core.settings.usesRandomSlot
import com.wasimaster.wmkeyboard.core.settings.SidePadScaleRange
import com.wasimaster.wmkeyboard.core.settings.ThemeGalleryStyle
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.PoolEntry
import com.wasimaster.wmkeyboard.core.settings.softenedForPhoto
import com.wasimaster.wmkeyboard.core.settings.themeGalleryGrouped
import com.wasimaster.wmkeyboard.core.tools.PhotoBackgroundManager
import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.builtInThemeNameRes
import com.wasimaster.wmkeyboard.core.theme.GradientSpec
import com.wasimaster.wmkeyboard.core.theme.GradientType
import com.wasimaster.wmkeyboard.core.settings.DefaultThemesPanelBuiltIns
import com.wasimaster.wmkeyboard.core.theme.DecalSpec
import com.wasimaster.wmkeyboard.core.theme.KeyEffectKind
import com.wasimaster.wmkeyboard.core.theme.KEY_OVERRIDE_LABEL_SCALE_RANGE
import com.wasimaster.wmkeyboard.core.theme.KeyOverride
import com.wasimaster.wmkeyboard.core.theme.popupOnKeyOrNull
import com.wasimaster.wmkeyboard.core.theme.EFFECT_DURATION_RANGE
import com.wasimaster.wmkeyboard.core.theme.EFFECT_GRAVITY_RANGE
import com.wasimaster.wmkeyboard.core.theme.EFFECT_SIZE_RANGE
import com.wasimaster.wmkeyboard.core.theme.EFFECT_SPEED_RANGE
import com.wasimaster.wmkeyboard.core.theme.EFFECT_SPREAD_RANGE
import com.wasimaster.wmkeyboard.core.theme.KeyEffectColorMode
import com.wasimaster.wmkeyboard.core.theme.keyEffectColorMode
import com.wasimaster.wmkeyboard.core.theme.keyEffectKindOrNull
import com.wasimaster.wmkeyboard.core.theme.KeyShapeKind
import com.wasimaster.wmkeyboard.core.theme.MAX_DECALS
import com.wasimaster.wmkeyboard.core.theme.MAX_EFFECT_IMAGES
import com.wasimaster.wmkeyboard.core.theme.MAX_THEME_VARIANTS
import com.wasimaster.wmkeyboard.core.theme.withSidePad
import com.wasimaster.wmkeyboard.core.theme.KeyTextureScale
import com.wasimaster.wmkeyboard.core.theme.keyTextureScaleOrDefault
import com.wasimaster.wmkeyboard.core.theme.SeedSwatches
import com.wasimaster.wmkeyboard.core.theme.isAnimatedImageFile
import com.wasimaster.wmkeyboard.core.theme.ThemeAnimation
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.brush
import com.wasimaster.wmkeyboard.core.theme.keyShapeFor
import com.wasimaster.wmkeyboard.core.theme.keyShapeKindOrNull
import com.wasimaster.wmkeyboard.core.theme.safeContainerKind
import com.wasimaster.wmkeyboard.core.theme.findThemeFamily
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import com.wasimaster.wmkeyboard.core.theme.groupAsFamily
import com.wasimaster.wmkeyboard.core.theme.onColorFor
import com.wasimaster.wmkeyboard.ime.ui.DEFAULT_POPUP_ELEVATION_DP
import com.wasimaster.wmkeyboard.ime.ui.MAX_ELEVATION_DP
import com.wasimaster.wmkeyboard.core.theme.replacingMember
import com.wasimaster.wmkeyboard.core.theme.reseeded
import com.wasimaster.wmkeyboard.core.theme.selfAndVariants
import com.wasimaster.wmkeyboard.core.theme.themeFamilyName
import com.wasimaster.wmkeyboard.core.theme.themeFromSeed
import com.wasimaster.wmkeyboard.core.theme.themeName
import com.wasimaster.wmkeyboard.core.theme.withFreshIds
import com.wasimaster.wmkeyboard.core.theme.withEmbeddedImages
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.material.icons.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.theme.FlexResult
import com.wasimaster.wmkeyboard.core.theme.FlexTheme
import com.wasimaster.wmkeyboard.core.theme.dynamicSnyggPalette
import com.wasimaster.wmkeyboard.core.theme.GboardResult
import com.wasimaster.wmkeyboard.core.theme.GboardTheme
import com.wasimaster.wmkeyboard.core.theme.HeliResult
import com.wasimaster.wmkeyboard.core.theme.HeliTheme
import androidx.compose.material.icons.outlined.Crop169
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune

// ---- shared helpers ----

private fun colorOf(argb: Long): Color = Color(argb.toInt())
private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

private fun themeImagesDir(context: android.content.Context): File =
    File(context.filesDir, "theme_images").apply { mkdirs() }

/** Effective (fallback-resolved) colors the editor shows for nullable fields. */
private fun ThemeSpec.effectivePressed(): Long =
    pressedKeyBackground ?: lerp(colorOf(keyBackground), colorOf(accent), 0.40f).argb()

private fun ThemeSpec.effectivePopup(): Long =
    popupBackground ?: colorOf(keyText).copy(alpha = if (dark) 0.20f else 0.06f)
        .compositeOver(colorOf(boardBackground)).argb()

private fun ThemeSpec.effectiveToolCircle(): Long =
    toolCircleBackground ?: colorOf(keyText).copy(alpha = 0.14f)
        .compositeOver(colorOf(boardBackground)).argb()

/** The label for one of the app-wide light/dark modes. */
@StringRes
private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system_label
    ThemeMode.LIGHT -> R.string.theme_mode_light_label
    ThemeMode.DARK -> R.string.theme_mode_dark_label
    ThemeMode.AMOLED -> R.string.theme_mode_amoled_label
}

/**
 * What each mode does, one line, for the picker sheet. AMOLED needs it most:
 * it names a screen technology rather than a shade, so the name alone does
 * not say that it is dark, nor why anyone would want it.
 */
@StringRes
private fun themeModeDescRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system_desc
    ThemeMode.LIGHT -> R.string.theme_mode_light_desc
    ThemeMode.DARK -> R.string.theme_mode_dark_desc
    ThemeMode.AMOLED -> R.string.theme_mode_amoled_desc
}

/** What makes the auto pair change over, one line, for the picker sheet. */
@StringRes
private fun autoTriggerDescRes(trigger: AutoThemeTrigger): Int = when (trigger) {
    AutoThemeTrigger.SYSTEM -> R.string.theme_auto_trigger_system_desc
    AutoThemeTrigger.SCHEDULE -> R.string.theme_auto_trigger_schedule_desc
    AutoThemeTrigger.SUN -> R.string.theme_auto_trigger_sun_desc
}

/** How a key wears its texture, one line, for the picker sheet. */
@StringRes
private fun textureScaleDescRes(scale: KeyTextureScale): Int = when (scale) {
    KeyTextureScale.CROP -> R.string.theme_texture_scale_crop_desc
    KeyTextureScale.STRETCH -> R.string.theme_texture_scale_stretch_desc
    KeyTextureScale.TILE -> R.string.theme_texture_scale_tile_desc
}

/** What each animation moves, one line, for the picker sheet. */
@StringRes
private fun themeAnimationDescRes(anim: ThemeAnimation): Int = when (anim) {
    ThemeAnimation.NONE -> R.string.theme_animation_none_desc
    ThemeAnimation.FLOW -> R.string.theme_animation_flow_desc
    ThemeAnimation.HUE_CYCLE -> R.string.theme_animation_hue_cycle_desc
}

/** How a gradient runs its colors, one line, for the picker sheet. */
@StringRes
private fun gradientTypeDescRes(type: GradientType): Int = when (type) {
    GradientType.LINEAR -> R.string.theme_gradient_linear_desc
    GradientType.RADIAL -> R.string.theme_gradient_radial_desc
    GradientType.SWEEP -> R.string.theme_gradient_sweep_desc
}

/**
 * Sentinel for the crop dialog's "free" ratio: keep the picture's own shape.
 *
 * A value no real aspect can be, so it never collides with a preset. The
 * three presets are all wider-than-tall keyboard shapes, and a split or a
 * floating keyboard is none of them, so every one of them cropped away
 * something the user meant to keep.
 */
private const val FREE_CROP_ASPECT = -1f

/**
 * The display name for a theme id in the same namespace as keyboardThemeId.
 * Composable so the built-in default resolves in the caller's locale; the id
 * itself is what gets stored, never this text.
 */
@Composable
internal fun themeDisplayName(settings: LiveSettings, id: String): String {
    val defaultName = stringResource(R.string.theme_default_name)
    val builtInName = builtInThemeNameRes(id)?.let { stringResource(it) }
    val customName = settings.watch { s -> s.customThemes.flattenedThemes().find { it.id == id }?.name }
    return when (id) {
        DEFAULT_THEME_ID -> defaultName
        else -> customName
            ?: builtInName
            ?: defaultName
    }
}

/**
 * What one half of the auto pair is set to, for the row that opens its picker:
 * the theme's name, or how many themes it selects from.
 */
@Composable
private fun autoSlotSummary(settings: LiveSettings, darkSlot: Boolean): String {
    val fixedId = settings.watch { it.autoTheme.slotFixedId(darkSlot) }
    if (!settings.watch { it.autoTheme.slotRandom(darkSlot) }) {
        return themeDisplayName(settings, fixedId)
    }
    val count = settings.watch { it.autoTheme.slotPool(darkSlot).size }
    if (count == 0) return themeDisplayName(settings, fixedId)
    return pluralStringResource(R.plurals.theme_auto_slot_random_summary, count, count)
}

/** One row of the theme pickers: a heading or a selectable theme. */
internal sealed interface ThemePickerRow {
    /** Where built-in themes end and the user's own begin. */
    data class Section(val label: String) : ThemePickerRow

    /** A family, with the ids of every look under it. */
    data class Family(val label: String, val memberIds: List<String>) : ThemePickerRow

    /** A selectable theme. [indented] marks it as a look inside the family above. */
    data class Choice(val id: String, val name: String, val indented: Boolean) : ThemePickerRow
}

/**
 * The selectable themes: the default row, then built-ins, then the user's own.
 *
 * Inside each section the themes that stand alone come first and the families
 * follow, each under its own heading with its looks indented. Order matters
 * here rather than being cosmetic: a family used to be followed by whatever
 * came next in the list, so the four standalone built-ins after Deep sea read
 * as looks of Deep sea, and the user's themes ran on from the built-ins with
 * nothing to mark the join. Sorting the standalone ones to the front means a
 * bare row can never trail a family heading.
 */
@Composable
internal fun themePickerRows(settings: LiveSettings): List<ThemePickerRow> {
    val context = LocalContext.current
    return themePickerRows(
        builtIns = BuiltInThemes,
        customs = settings.watch { it.customThemes },
        defaultName = stringResource(R.string.theme_default_name),
        builtInLabel = stringResource(R.string.theme_picker_section_builtin_label),
        customLabel = stringResource(R.string.theme_picker_section_custom_label),
        familyName = { themeFamilyName(context, it) },
        name = { themeName(context, it) },
    )
}

/**
 * [themePickerRows] without the resources, so the ordering rule it exists to
 * hold can be tested. Names arrive as lambdas because a built-in resolves its
 * through the resources and a custom one carries its own.
 */
internal fun themePickerRows(
    builtIns: List<ThemeSpec>,
    customs: List<ThemeSpec>,
    defaultName: String,
    builtInLabel: String,
    customLabel: String,
    familyName: (ThemeSpec) -> String,
    name: (ThemeSpec) -> String,
): List<ThemePickerRow> {
    val rows = mutableListOf<ThemePickerRow>()
    rows += ThemePickerRow.Choice(DEFAULT_THEME_ID, defaultName, indented = false)
    val sections = listOf(
        builtInLabel to builtIns,
        customLabel to customs.sortedBy { it.name.lowercase() },
    )
    for ((label, entries) in sections) {
        if (entries.isEmpty()) continue
        rows += ThemePickerRow.Section(label)
        val (families, singles) = entries.partition { it.variants.isNotEmpty() }
        singles.forEach { rows += ThemePickerRow.Choice(it.id, name(it), indented = false) }
        for (family in families) {
            val members = family.selfAndVariants()
            rows += ThemePickerRow.Family(familyName(family), members.map { it.id })
            members.forEach { rows += ThemePickerRow.Choice(it.id, name(it), indented = true) }
        }
    }
    return rows
}

/** The name of a family, above its looks. */
@Composable
private fun ThemePickerHeading(label: String, control: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (control != null) {
            control()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Where one part of the list ends and the next begins. Quieter than a family
 * heading and carries a rule above it, so the two never read as the same thing.
 */
@Composable
private fun ThemePickerSectionHeading(label: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ThemePickerChoiceRow(
    name: String,
    selected: Boolean,
    indented: Boolean,
    onClick: () -> Unit,
    multiSelect: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = if (indented) 12.dp else 0.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiSelect) {
            Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = enabled)
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
        Spacer(Modifier.width(8.dp))
        Text(name)
    }
}

/**
 * The list of every selectable theme, for one half of the auto pair.
 *
 * Two modes in one dialog rather than two dialogs: the half either shows one
 * theme, which is what it has always done, or selects from a set. The control
 * at the top swaps the radio buttons for checkboxes, so the list itself, and
 * the reading of it, stay the same either way.
 */
@Composable
private fun ThemePickerDialog(
    title: String,
    settings: LiveSettings,
    selectedId: String,
    randomOn: Boolean,
    poolIds: Set<String>,
    onModeChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = themePickerRows(settings)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChoiceControl(
                    options = listOf(
                        false to stringResource(R.string.theme_auto_slot_mode_one_label),
                        true to stringResource(R.string.theme_auto_slot_mode_random_label),
                    ),
                    selected = randomOn,
                    modifier = Modifier.padding(bottom = 4.dp),
                    label = stringResource(R.string.theme_auto_slot_mode_label),
                    detail = { random ->
                        ChoiceDetail(
                            icon = if (random) Icons.Outlined.Shuffle else Icons.Outlined.Palette,
                        )
                    },
                    onChange = onModeChange,
                )
                if (randomOn) {
                    Text(
                        stringResource(R.string.theme_auto_slot_random_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                for (row in rows) {
                    when (row) {
                        is ThemePickerRow.Section -> ThemePickerSectionHeading(row.label)
                        is ThemePickerRow.Family -> ThemePickerHeading(row.label) {
                            if (randomOn) FamilyPoolCheckbox(row.memberIds, poolIds, onToggle)
                        }
                        // Opens on the theme it is already set to. The list is
                        // every built-in plus every theme the user made, so the
                        // row that is on is routinely well below the fold.
                        is ThemePickerRow.Choice -> ScrollAnchor(row.id == selectedId) {
                            val inPool = row.id in poolIds
                            ThemePickerChoiceRow(
                                name = row.name,
                                selected = if (randomOn) inPool else row.id == selectedId,
                                indented = row.indented,
                                multiSelect = randomOn,
                                // The set cannot be emptied: a half that selects
                                // at random from nothing has nothing to show.
                                enabled = !randomOn || !inPool || poolIds.size > 1,
                                onClick = {
                                    if (randomOn) onToggle(row.id, !inPool) else onPick(row.id)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) }
        },
    )
}

/**
 * The checkbox on a family heading: adds every look in the family to the set,
 * or takes them all out. Indeterminate while only some of them are in it, which
 * is a state the per-look checkboxes can reach on their own.
 */
@Composable
private fun FamilyPoolCheckbox(
    memberIds: List<String>,
    poolIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    val inPool = memberIds.count { it in poolIds }
    val state = when (inPool) {
        0 -> ToggleableState.Off
        memberIds.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val label = stringResource(R.string.theme_picker_family_select_desc)
    TriStateCheckbox(
        state = state,
        onClick = {
            val add = state != ToggleableState.On
            memberIds.forEach { onToggle(it, add) }
        },
        modifier = Modifier.semantics { contentDescription = label },
    )
}

/**
 * [ThemePickerDialog] with an "Inherit" row at the top, for a keyboard mode —
 * where having no theme of its own is the default and the common case.
 */
@Composable
internal fun ModeThemePickerDialog(
    settings: LiveSettings,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** The layout editor borrows this picker for a layout's or a layer's theme. */
    title: String = stringResource(R.string.theme_mode_picker_title),
) {
    val inheritLabel = stringResource(R.string.theme_mode_inherit_label)
    val rows = themePickerRows(settings)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ScrollAnchor(selectedId == null) {
                    ThemePickerChoiceRow(
                        name = inheritLabel,
                        selected = selectedId == null,
                        indented = false,
                        onClick = { onPick(null) },
                    )
                }
                for (row in rows) {
                    when (row) {
                        is ThemePickerRow.Section -> ThemePickerSectionHeading(row.label)
                        is ThemePickerRow.Family -> ThemePickerHeading(row.label)
                        is ThemePickerRow.Choice -> ScrollAnchor(row.id == selectedId) {
                            ThemePickerChoiceRow(
                                name = row.name,
                                selected = row.id == selectedId,
                                indented = row.indented,
                                onClick = { onPick(row.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) }
        },
    )
}

/** The name of a key shape, in the language of the device. */
@Composable
internal fun keyShapeName(kind: KeyShapeKind): String = stringResource(
    when (kind) {
        KeyShapeKind.NONE -> R.string.theme_key_shape_none_label
        KeyShapeKind.ROUNDED -> R.string.theme_key_shape_rounded_label
        KeyShapeKind.SHARP -> R.string.theme_key_shape_sharp_label
        KeyShapeKind.PILL -> R.string.theme_key_shape_pill_label
        KeyShapeKind.CUT -> R.string.theme_key_shape_cut_label
        KeyShapeKind.SQUIRCLE -> R.string.theme_key_shape_squircle_label
        KeyShapeKind.ARCH -> R.string.theme_key_shape_arch_label
        KeyShapeKind.LEAF -> R.string.theme_key_shape_leaf_label
        KeyShapeKind.SLANT -> R.string.theme_key_shape_slant_label
        KeyShapeKind.HEXAGON -> R.string.theme_key_shape_hexagon_label
        KeyShapeKind.SCALLOP -> R.string.theme_key_shape_scallop_label
        KeyShapeKind.TICKET -> R.string.theme_key_shape_ticket_label
        KeyShapeKind.CIRCLE -> R.string.theme_key_shape_circle_label
    },
)

/**
 * One key drawn in [kind], at the size and the proportions of a real key so the
 * shape reads the way it will on the keyboard. [radiusDp] is the theme's own
 * key radius, which two of the shapes follow.
 *
 * A letter key on a phone is about 31 dp across and 48 dp tall — taller than it
 * is wide. The swatch used to be 52 x 34, half again as wide as it was tall,
 * which flattered every shape that leans or points: a slant that eats a third
 * of a real key barely tilted, and the radius slider looked far gentler than it
 * is. Drawing the swatch at the real size means the same radius in dp draws the
 * same corner here and on the keyboard.
 */
@Composable
internal fun KeyShapeSwatch(kind: KeyShapeKind, radiusDp: Int, color: Color) {
    val shape = keyShapeFor(kind, radiusDp, bleedDp = KeySwatchGapDp)
    Box(
        modifier = Modifier
            // The horizontal room a key has either side of it on the keyboard,
            // which is what a leaning shape spills into. Without it the slant
            // would draw over whatever sits beside the swatch.
            .padding(horizontal = KeySwatchGapDp.dp)
            .width(31.dp)
            .height(48.dp)
            .then(
                if (kind == KeyShapeKind.NONE) {
                    // No shape fills nothing, and an empty slot reads as a swatch
                    // that failed to draw. A dashed line marks where the key sits
                    // without giving it an edge.
                    Modifier.drawBehind {
                        drawOutline(
                            outline = shape.createOutline(size, layoutDirection, this),
                            color = color,
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                                ),
                            ),
                        )
                    }
                } else {
                    Modifier.background(color, shape)
                },
            ),
    )
}

/** The keyboard's own horizontal key gap, which [KeyShapeSwatch] reproduces. */
private const val KeySwatchGapDp = 2.5f

/**
 * Small preview of a theme image file, for the editor's picker rows — a
 * texture, a press-effect particle, a background. Falls back to the plain
 * image glyph while there is nothing to show, so an unset row looks exactly
 * as it always has.
 *
 * The thumbnail keeps the picture's own shape. It used to be cropped square,
 * which is the one thing a preview must not do here: a wallpaper picked for
 * a phone screen is about half as wide as it is tall, and a centre crop threw
 * away everything the choice was made on — two different photos of the same
 * sky came back as the same blue square. It is fitted inside the 40 dp slot
 * instead, so the row's layout never moves and nothing is cut off.
 */
@Composable
private fun ImageThumb(path: String?) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            path?.let { p ->
                runCatching {
                    val bounds = android.graphics.BitmapFactory.Options()
                        .apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(p, bounds)
                    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / THUMB_DECODE_PX)
                    val opts = android.graphics.BitmapFactory.Options()
                        .apply { inSampleSize = sample }
                    android.graphics.BitmapFactory.decodeFile(p, opts)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        // The slot stays 40 dp square whatever the picture is; the image takes
        // as much of it as its own aspect allows, so the row beside it never
        // shifts as a thumbnail loads.
        Box(modifier = Modifier.size(THUMB_BOX_DP.dp), contentAlignment = Alignment.Center) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(6.dp)),
            )
        }
    } else {
        Icon(Icons.Outlined.Image, contentDescription = null)
    }
}

/** The square slot a picker-row thumbnail is fitted into, in dp. */
private const val THUMB_BOX_DP = 40

/** Longest edge a picker-row thumbnail decodes to; the box is [THUMB_BOX_DP]. */
private const val THUMB_DECODE_PX = 96

/** What chips draw with when a theme sets no chip radius; mirrors the keyboard. */
private const val DefaultChipRadiusDp = 12

/**
 * Radio list of every key shape, each row with the shape drawn beside its name.
 * Shared by every shape a theme sets (keys, popups, tools, chips, menus and
 * cards), hence the caller's [title].
 */
@Composable
internal fun KeyShapePickerDialog(
    selected: KeyShapeKind?,
    radiusDp: Int,
    onPick: (KeyShapeKind) -> Unit,
    onDismiss: () -> Unit,
    @StringRes title: Int = R.string.theme_key_shape_title,
    // The menu and card shapes may be left on "Automatic", which derives a
    // safe shape instead of naming one; those pickers pass a reset handler.
    onAuto: (() -> Unit)? = null,
    // Only keys may have no shape at all (issue #149): a popup, a chip or a
    // card with no outline is its text floating over the board. The key
    // shape picker is the one caller that passes true.
    offerNone: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (onAuto != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAuto() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == null, onClick = { onAuto() })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.theme_shape_auto_safe_label),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                for (kind in KeyShapeKind.entries) {
                    if (kind == KeyShapeKind.NONE && !offerNone) continue
                    val picked = kind == selected
                    ScrollAnchor(picked) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(kind) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = picked, onClick = { onPick(kind) })
                            Spacer(Modifier.width(8.dp))
                            Text(keyShapeName(kind), modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            KeyShapeSwatch(
                                kind = kind,
                                radiusDp = radiusDp,
                                // The picked shape carries the accent, so the row
                                // that is on reads at a glance from the swatches.
                                color = if (picked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** `7:00 AM` / `19:00`, following the phone's own 12-vs-24-hour setting. */
@Composable
private fun formatMinutesOfDay(minutes: Int): String {
    val context = LocalContext.current
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
        set(java.util.Calendar.MINUTE, minutes % 60)
    }
    // The Android formatter, not java.text's: only this one honours the
    // "use 24-hour format" switch, which is a system setting rather than
    // something the locale decides.
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}

/** Clock-face picker for one of the auto-theme switchover times. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOfDayPickerDialog(
    title: String,
    minutes: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = minutes / 60,
        initialMinute = minutes % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.theme_time_set_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * One theme's editor, as flights name it.
 *
 * The navigation route with its argument filled in, which is what a flight is
 * keyed on: the pattern (`theme_edit/{themeId}`) is the same string for every
 * theme and would hang one key on all of them.
 */
internal fun themeEditRoute(themeId: String): String = "theme_edit/$themeId"

/**
 * The name the theme editor's heading wears: the look the route names, as the
 * user typed it.
 *
 * Read by the nav graph rather than by the editor, because the heading belongs
 * to the frame around it. Null for a theme that is not there — a stale link, or
 * one deleted from under the back stack — and the frame then falls back to the
 * generic title.
 */
@Composable
internal fun themeEditTitle(settings: LiveSettings, themeId: String): String? =
    settings.watch { s ->
        s.customThemes.findThemeFamily(themeId)
            ?.selfAndVariants()
            ?.find { it.id == themeId }
    }
        ?.let { themeName(it) }
        ?.takeIf { it.isNotBlank() }

// ---- theme gallery ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemesScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
    onEditTheme: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Export: pick a destination file, then write the pending theme's JSON
    // (with the background image embedded as base64) into it.
    var pendingExport by remember { mutableStateOf<ThemeSpec?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ThemeCodec.MIME_TYPE)
    ) { uri ->
        val theme = pendingExport
        pendingExport = null
        if (uri != null && theme != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        out.write(ThemeCodec.encode(theme.withEmbeddedImages()).toByteArray())
                    }
                }
            }
        }
    }
    // Whatever the last import had to say, shown in a dialog. A theme file
    // carries no format tag, so a file that is not one decodes to an
    // all-defaults theme or to nothing at all — either way the user has to be
    // told, or picking the wrong file looks like the button doing nothing.
    var message by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val parsed = text?.let { ThemeCodec.decode(it) }
            if (parsed == null) {
                message = context.getString(R.string.theme_import_wrong_file_error)
                return@launch
            }
            val id = "custom_${System.currentTimeMillis()}"
            // Set the fresh ids first — the parent's and every variant's — so
            // the extracted image filenames key off them and stay unique
            // against existing themes.
            val stored = withContext(Dispatchers.IO) {
                parsed.withFreshIds(id).withExtractedImages(themeImagesDir(context))
            }
            repository.upsertCustomTheme(stored)
            repository.setKeyboardThemeId(id)
            message = context.getString(R.string.theme_import_done_message, stored.name)
        }
    }
    val florisLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        FlexTheme.read(it, dynamicSnyggPalette(context))
                    }
                }.getOrElse { FlexResult.Unreadable }
            }
            if (result !is FlexResult.Converted) {
                message = context.getString(R.string.import_floris_unreadable_body)
                return@launch
            }
            val dir = withContext(Dispatchers.IO) { themeImagesDir(context) }
            val base = "custom_${System.currentTimeMillis()}"
            val stored = withContext(Dispatchers.IO) {
                result.themes.mapIndexed { index, converted ->
                    // A day and night pair need distinct ids: the extracted
                    // image file names are keyed on the id, so a shared one
                    // would have the second theme overwrite the first's images.
                    converted.stored(
                        if (index == 0) base else "${base}_v$index",
                        dir,
                        FontStore.get(context),
                    )
                }
            }
            // One entry, not N: an extension's themes are the looks of one
            // theme, named after the extension itself.
            val entry = groupAsFamily(stored, result.title)
            repository.upsertCustomTheme(entry)
            // Saved, not switched to. A converted theme is exactly the thing
            // worth looking at before it becomes the keyboard.
            message = if (stored.size >= 2) {
                context.getString(
                    R.string.import_floris_done_family,
                    themeFamilyName(context, entry),
                    stored.size,
                )
            } else {
                context.resources.getQuantityString(
                    R.plurals.import_floris_done,
                    stored.size,
                    stored.size,
                )
            }
        }
    }
    // HeliBoard and LeanType have no theme file: a theme is a line of JSON,
    // copied out of their colour screen and pasted into a forum post. So this
    // import takes text from either source, and the button opens a two-way
    // chooser rather than a file picker.
    var heliChooser by remember { mutableStateOf(false) }
    suspend fun applyHeliTheme(text: String?) {
        if (text.isNullOrBlank()) {
            message = context.getString(R.string.import_heli_clipboard_empty_body)
            return
        }
        val result = withContext(Dispatchers.Default) { HeliTheme.read(text) }
        if (result !is HeliResult.Converted) {
            message = context.getString(R.string.import_heli_unreadable_body)
            return
        }
        val stored = result.theme.copy(id = "custom_${System.currentTimeMillis()}")
        repository.upsertCustomTheme(stored)
        // Saved, not switched to, for the reason the FlorisBoard import is:
        // a converted theme is the thing worth looking at first.
        message = context.getString(
            R.string.import_heli_done,
            stored.name,
            result.coloursUsed,
            result.coloursRead,
        )
    }
    val heliLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            applyHeliTheme(text)
        }
    }
    // Gboard themes: a ZIP picked here, or the Rboard collection on its own
    // screen. What was read waits in gboardRead for the dialogs to take it.
    var gboardChooser by remember { mutableStateOf(false) }
    var gboardRead by remember { mutableStateOf<GboardResult.Converted?>(null) }
    val gboardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = WMFileTypes.displayName(context, uri).substringBeforeLast('.')
                    context.contentResolver.requireInputStream(uri).use { GboardTheme.read(it, name) }
                }.getOrElse { GboardResult.Unreadable }
            }
            if (result is GboardResult.Converted) {
                gboardRead = result
            } else {
                message = gboardFailureMessage(context, result)
            }
        }
    }
    fun export(theme: ThemeSpec) {
        pendingExport = theme
        exportLauncher.launch("${theme.name.ifBlank { "theme" }}.${ThemeCodec.FILE_EXTENSION}")
    }
    fun duplicateAndEdit(base: ThemeSpec) {
        scope.launch {
            val id = "custom_${System.currentTimeMillis()}"
            val copyName = context.getString(R.string.theme_duplicate_name, base.name)
            // The copy is of one look alone: carrying the variants over
            // as-is would smuggle built-in (or another device's) ids into
            // the custom store, where they must never exist.
            repository.upsertCustomTheme(
                base.copy(id = id, name = copyName, variants = emptyList(), familyName = null),
            )
            repository.setKeyboardThemeId(id)
            onEditTheme(id)
        }
    }
    fun duplicateFamilyAndEdit(entry: ThemeSpec) {
        scope.launch {
            val id = "custom_${System.currentTimeMillis()}"
            // withFreshIds remints the whole family, so the "no foreign ids
            // in the custom store" rule holds for every look. The looks keep
            // their names; the copy marker goes on the group label, which is
            // what the card shows.
            val groupName = context.getString(
                R.string.theme_duplicate_name,
                themeFamilyName(context, entry),
            )
            repository.upsertCustomTheme(
                entry.withFreshIds(id).copy(familyName = groupName),
            )
            repository.setKeyboardThemeId(id)
            onEditTheme(id)
        }
    }

    SettingsGroup(stringResource(R.string.theme_mode_section_title)) {
        item {
            ChoiceControl(
                options = ThemeMode.entries.map { it to stringResource(themeModeLabelRes(it)) },
                selected = settings.watch { it.themeMode },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                label = stringResource(R.string.theme_mode_choice_label),
                detail = { mode -> ChoiceDetail(stringResource(themeModeDescRes(mode))) },
            ) { mode -> scope.launch { repository.setThemeMode(mode) } }
        }
        item {
            ToggleSetting(
                R.string.theme_material_you_title,
                stringResource(R.string.theme_material_you_subtitle),
                checked = settings.watch { it.dynamicColor },
                default = SettingsDefaults.dynamicColor,
            ) { scope.launch { repository.setDynamicColor(it) } }
        }
    }

    // null = closed; true = choosing the light theme; false = the dark theme.
    var pickerForLight by remember { mutableStateOf<Boolean?>(null) }
    // null = closed; true = editing when day starts, false = when night does.
    var timePickerForDay by remember { mutableStateOf<Boolean?>(null) }
    // What decides which rows the auto group holds, and what its "?" says;
    // each row reads its own value.
    val autoOn = settings.watch { it.autoTheme.enabled }
    val autoTrigger = settings.watch { it.autoTheme.trigger }
    val shuffles = settings.watch { it.autoTheme.usesRandomSlot }
    // The chosen trigger's explanation rides in the section's "?" with the
    // rest; the one state that needs doing something about — sun times with
    // no place to compute them for — is a banner instead.
    val hasSunLocation = settings.watch { it.weather.latitude != null && it.weather.longitude != null }
    val sunPlace = settings.watch { it.weather.placeName }.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.theme_auto_trigger_sun_place_fallback)
    val triggerNote = when (autoTrigger) {
        AutoThemeTrigger.SYSTEM -> stringResource(R.string.theme_auto_trigger_system_body)
        AutoThemeTrigger.SUN ->
            if (hasSunLocation) stringResource(R.string.theme_auto_trigger_sun_body, sunPlace) else null
        else -> null
    }
    SettingsGroup(
        stringResource(R.string.theme_auto_section_title),
        info = listOfNotNull(stringResource(R.string.theme_auto_section_body), triggerNote)
            .joinToString("\n\n"),
    ) {
        item {
            ToggleSetting(
                R.string.theme_auto_title,
                stringResource(R.string.theme_auto_subtitle),
                checked = autoOn,
                default = SettingsDefaults.autoTheme.enabled,
            ) { scope.launch { repository.setAutoThemeEnabled(it) } }
        }
        if (autoOn) {
            item {
                NavRow(
                    R.string.theme_auto_light_title,
                    value = autoSlotSummary(settings, darkSlot = false),
                ) { pickerForLight = true }
            }
            item {
                NavRow(
                    R.string.theme_auto_dark_title,
                    value = autoSlotSummary(settings, darkSlot = true),
                ) { pickerForLight = false }
            }
            item(visible = shuffles) {
                ChoiceSetting(
                    title = R.string.theme_shuffle_interval_title,
                    subtitle = stringResource(R.string.theme_shuffle_interval_subtitle),
                    options = RotationInterval.entries
                        .map { it to stringResource(it.labelRes) },
                    selected = settings.watch { it.autoTheme.shuffleInterval },
                    default = SettingsDefaults.autoTheme.shuffleInterval,
                ) { value -> scope.launch { repository.setAutoThemeShuffleInterval(value) } }
            }
            item(visible = shuffles) {
                ActionRow(
                    title = R.string.theme_shuffle_now_title,
                    subtitle = stringResource(R.string.theme_shuffle_now_subtitle),
                    action = stringResource(R.string.theme_shuffle_now_action),
                ) { scope.launch { repository.shuffleAutoThemeNow() } }
            }
            item {
                ChoiceControl(
                    options = AutoThemeTrigger.entries.map { it to stringResource(it.labelRes) },
                    selected = autoTrigger,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    label = stringResource(R.string.theme_auto_trigger_label),
                    detail = { trigger ->
                        ChoiceDetail(stringResource(autoTriggerDescRes(trigger)))
                    },
                ) { trigger -> scope.launch { repository.setAutoThemeTrigger(trigger) } }
            }
            when (autoTrigger) {
                AutoThemeTrigger.SYSTEM -> Unit
                AutoThemeTrigger.SCHEDULE -> {
                    item {
                        NavRow(
                            R.string.theme_auto_light_from_title,
                            value = formatMinutesOfDay(settings.watch { it.autoTheme.dayStartMinutes }),
                        ) { timePickerForDay = true }
                    }
                    item {
                        NavRow(
                            R.string.theme_auto_dark_from_title,
                            value = formatMinutesOfDay(settings.watch { it.autoTheme.nightStartMinutes }),
                        ) { timePickerForDay = false }
                    }
                }
                AutoThemeTrigger.SUN -> if (!hasSunLocation) {
                    item { StateBanner(stringResource(R.string.theme_auto_trigger_sun_no_location_body)) }
                }
            }
        }
    }
    timePickerForDay?.let { forDay ->
        TimeOfDayPickerDialog(
            title = stringResource(
                if (forDay) R.string.theme_auto_light_from_title
                else R.string.theme_auto_dark_from_title,
            ),
            minutes = settings.watch {
                if (forDay) it.autoTheme.dayStartMinutes else it.autoTheme.nightStartMinutes
            },
            onPick = { picked ->
                scope.launch {
                    if (forDay) repository.setAutoThemeDayStart(picked)
                    else repository.setAutoThemeNightStart(picked)
                }
                timePickerForDay = null
            },
            onDismiss = { timePickerForDay = null },
        )
    }
    pickerForLight?.let { forLight ->
        val darkSlot = !forLight
        ThemePickerDialog(
            title = stringResource(
                if (forLight) R.string.theme_auto_light_title else R.string.theme_auto_dark_title,
            ),
            settings = settings,
            selectedId = settings.watch { it.autoTheme.slotFixedId(darkSlot) },
            randomOn = settings.watch { it.autoTheme.slotRandom(darkSlot) },
            poolIds = settings.watch { it.autoTheme.slotPool(darkSlot) },
            onModeChange = { on ->
                scope.launch { repository.setAutoThemeSlotRandom(darkSlot, on) }
            },
            onPick = { id ->
                scope.launch {
                    if (forLight) repository.setAutoThemeLightId(id)
                    else repository.setAutoThemeDarkId(id)
                }
                // Selecting one theme closes the dialog; assembling a set does
                // not, because that takes more than one press.
                pickerForLight = null
            },
            onToggle = { id, inPool ->
                scope.launch { repository.setAutoThemePoolMember(darkSlot, id, inPool) }
            },
            onDismiss = { pickerForLight = null },
        )
    }

    // The gallery is a grid of theme cards, which are their own surfaces, so
    // it keeps a plain header rather than being wrapped in a settings card.
    val grouped = settings.watch { it.themeGalleryGrouped() }
    // The "?" used to describe whichever style was live, which said nothing
    // about the other two; each style now describes itself in the picker.
    SectionHeaderPublic(stringResource(R.string.theme_gallery_section_title))
    if (autoOn) {
        // Not a sentence telling the user where to go: the card carries the
        // switch that makes the gallery live again.
        StateBanner(
            text = stringResource(R.string.theme_gallery_auto_on_body),
            action = stringResource(CommonR.string.common_disable),
        ) { scope.launch { repository.setAutoThemeEnabled(false) } }
    }
    ChoiceControl(
        options = ThemeGalleryStyle.entries.map { it to stringResource(themeGalleryStyleLabelRes(it)) },
        selected = settings.watch { it.appUi.themeGalleryStyle },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        label = stringResource(R.string.theme_gallery_style_label),
        detail = { style -> ChoiceDetail(stringResource(themeGalleryStyleDescRes(style))) },
    ) { style -> scope.launch { repository.setThemeGalleryStyle(style) } }
    val newThemeName = stringResource(R.string.theme_new_default_name)
    val newThemeDark = isSystemInDarkTheme()
    RegisterAddFab(stringResource(R.string.theme_create_action)) {
        scope.launch {
            val id = "custom_${System.currentTimeMillis()}"
            // Was hard-coded dark on a phone set to light, and always the
            // first swatch. Follows whatever the keyboard is wearing now,
            // which is the only signal available at this point.
            repository.upsertCustomTheme(
                themeFromSeed(id, newThemeName, SeedSwatches.first(), dark = newThemeDark)
            )
            repository.setKeyboardThemeId(id)
            onEditTheme(id)
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { importLauncher.launch(ThemeCodec.IMPORT_MIME_TYPES) }) {
            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(CommonR.string.common_import))
        }
        OutlinedButton(onClick = { florisLauncher.launch(FlexTheme.IMPORT_MIME_TYPES) }) {
            Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.theme_import_floris_action))
        }
        OutlinedButton(onClick = { heliChooser = true }) {
            Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.theme_import_heli_action))
        }
        // The Play build has no Rboard browser (it would fetch a collection of
        // unlicensed third-party artwork from inside the app), so there is
        // nothing to choose between and the button opens the file picker.
        OutlinedButton(onClick = {
            if (BuildConfig.ENABLE_PLAY_STORE) {
                gboardLauncher.launch(GboardTheme.IMPORT_MIME_TYPES)
            } else {
                gboardChooser = true
            }
        }) {
            Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.theme_import_gboard_action))
        }
    }
    if (gboardChooser) {
        AlertDialog(
            onDismissRequest = { gboardChooser = false },
            title = { Text(stringResource(R.string.import_gboard_title)) },
            text = { Text(stringResource(R.string.import_gboard_chooser_body)) },
            confirmButton = {
                TextButton(onClick = {
                    gboardChooser = false
                    onNavigate(RBOARD_THEMES_ROUTE)
                }) { Text(stringResource(R.string.import_gboard_browse_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    gboardChooser = false
                    gboardLauncher.launch(GboardTheme.IMPORT_MIME_TYPES)
                }) { Text(stringResource(R.string.import_gboard_file_action)) }
            },
        )
    }
    gboardRead?.let { read ->
        GboardImportDialogs(
            result = read,
            repository = repository,
            onDismiss = { gboardRead = null },
            onMessage = { message = it },
        )
    }
    if (heliChooser) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { heliChooser = false },
            title = { Text(stringResource(R.string.import_heli_title)) },
            text = { Text(stringResource(R.string.import_heli_body)) },
            confirmButton = {
                TextButton(onClick = {
                    heliChooser = false
                    val pasted = clipboard.getText()?.text
                    scope.launch { applyHeliTheme(pasted) }
                }) { Text(stringResource(R.string.import_heli_paste_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    heliChooser = false
                    heliLauncher.launch(HeliTheme.IMPORT_MIME_TYPES)
                }) { Text(stringResource(R.string.import_heli_file_action)) }
            },
        )
    }
    Spacer(Modifier.height(8.dp))

    // The cards are drawn in the screen's own body rather than in rows, so the
    // theme that is on is read once, here, for all of them.
    val selectedThemeId = settings.watch { it.keyboardThemeId }
    // Default (system) card first, then customs, then built-ins — two per row.
    DefaultThemeCard(
        selected = selectedThemeId == DEFAULT_THEME_ID,
        onSelect = { scope.launch { repository.setKeyboardThemeId(DEFAULT_THEME_ID) } },
    )
    // Which look each family's card is showing, when the user tapped a dot.
    // Falls back to the selected member, then the parent, in the card logic.
    val shownVariant = remember { mutableStateMapOf<String, String>() }
    // A pencil tap on a family card asks what to copy: (family, shown look).
    var copyScopeFor by remember { mutableStateOf<Pair<ThemeSpec, ThemeSpec>?>(null) }
    val customs = settings.watch { it.customThemes }.sortedBy { it.name.lowercase() }
    val customEntries = if (grouped) customs else customs.flattenedThemes()
    if (customEntries.isNotEmpty()) {
        SectionHeaderPublic(stringResource(R.string.theme_custom_section_title))
    }
    for (rowThemes in customEntries.chunked(2)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp)) {
            for (entry in rowThemes) {
                Box(modifier = Modifier.weight(1f)) {
                    // Keyed on the theme, not on the slot: adding or deleting
                    // a theme shifts every card after it along a place, and a
                    // card must not play the selection ring and tick at a slot
                    // that merely changed hands. See [ThemeCard].
                    key(entry.id) {
                        val members = if (grouped) entry.selfAndVariants() else listOf(entry)
                        val isFamily = members.size > 1
                        val shownId = if (isFamily) {
                            shownVariant[entry.id]
                                ?: members.find { it.id == selectedThemeId }?.id
                                ?: entry.id
                        } else {
                            entry.id
                        }
                        val shown = members.find { it.id == shownId } ?: entry
                        // Downloaded themes are custom themes, so this is where an
                        // addon's Use button lands — on the card, not on the header.
                        // A family card answers for every id it holds.
                        HighlightableItem(members.map { it.id }) {
                            ThemeCard(
                                theme = shown,
                                selected = members.any { it.id == selectedThemeId },
                                onSelect = { scope.launch { repository.setKeyboardThemeId(shown.id) } },
                                onEdit = { onEditTheme(shown.id) },
                                editRoute = themeEditRoute(shown.id),
                                onExport = {
                                    // A family card exports the family; a flat card
                                    // exports the one look it shows.
                                    export(
                                        if (grouped) entry
                                        else entry.copy(variants = emptyList(), familyName = null),
                                    )
                                },
                                onDelete = {
                                    scope.launch {
                                        // Image files are always left to the sweep:
                                        // "Add look" and family copies share paths
                                        // between specs, and only the sweep knows
                                        // what else still points at a file.
                                        val parent = settings.value.customThemes.findThemeFamily(entry.id)
                                        if (parent != null && parent.id != entry.id) {
                                            repository.deleteCustomThemeVariant(parent.id, entry.id)
                                        } else {
                                            repository.deleteCustomTheme(entry.id)
                                        }
                                    }
                                },
                                title = if (isFamily) themeFamilyName(entry) else null,
                                subtitle = if (isFamily) themeName(shown) else null,
                                swatches = if (isFamily) {
                                    {
                                        VariantSwatchRow(entry, shownId) { variant ->
                                            shownVariant[entry.id] = variant.id
                                            scope.launch { repository.setKeyboardThemeId(variant.id) }
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
            if (rowThemes.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    SectionHeaderPublic(
        stringResource(R.string.theme_builtin_section_title),
        info = stringResource(R.string.theme_panel_pin_body),
    )
    val panelBuiltIns = settings.watch { it.toolbarBehavior.themesPanelBuiltIns ?: DefaultThemesPanelBuiltIns }
    val builtinEntries = if (grouped) BuiltInThemes else BuiltInThemes.flattenedThemes()
    for (rowThemes in builtinEntries.chunked(2)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp)) {
            for (entry in rowThemes) {
                Box(modifier = Modifier.weight(1f)) {
                    // Keyed on the theme, not on the slot: switching between
                    // grouped and flat reshuffles which card sits where, and
                    // a card must not play the selection ring and tick at a
                    // slot that merely changed hands. See [ThemeCard].
                    key(entry.id) {
                        val members = if (grouped) entry.selfAndVariants() else listOf(entry)
                        val isFamily = members.size > 1
                        val shownId = if (isFamily) {
                            shownVariant[entry.id]
                                ?: members.find { it.id == selectedThemeId }?.id
                                ?: entry.id
                        } else {
                            entry.id
                        }
                        val shown = members.find { it.id == shownId } ?: entry
                        val pinned = shown.id in panelBuiltIns
                        ThemeCard(
                            theme = shown,
                            selected = members.any { it.id == selectedThemeId },
                            onSelect = { scope.launch { repository.setKeyboardThemeId(shown.id) } },
                            // A family card asks whether the copy is of the shown
                            // look or of the whole set; a lone card just copies.
                            onEdit = {
                                if (isFamily) copyScopeFor = entry to shown else duplicateAndEdit(shown)
                            },
                            onExport = {
                                export(
                                    if (grouped) entry
                                    else entry.copy(variants = emptyList(), familyName = null),
                                )
                            },
                            onDelete = null,
                            title = if (isFamily) themeFamilyName(entry) else null,
                            subtitle = if (isFamily) themeName(shown) else null,
                            // The pin is per look, not per family: the keyboard
                            // panel lists looks, so the toggle names what shows.
                            panelShown = pinned,
                            onTogglePanel = {
                                scope.launch { repository.setThemesPanelBuiltIn(shown.id, !pinned) }
                            },
                            swatches = if (isFamily) {
                                {
                                    VariantSwatchRow(entry, shownId) { variant ->
                                        shownVariant[entry.id] = variant.id
                                        scope.launch { repository.setKeyboardThemeId(variant.id) }
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            if (rowThemes.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    // After the built-ins, where someone who has looked through everything the
    // app ships with is standing.
    AddonStoreGroup(AddonType.Theme, onNavigate)
    Spacer(Modifier.height(24.dp))

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
    copyScopeFor?.let { (entry, shown) ->
        AlertDialog(
            onDismissRequest = { copyScopeFor = null },
            title = { Text(stringResource(R.string.theme_copy_scope_title)) },
            text = { Text(stringResource(R.string.theme_copy_scope_body, themeName(shown))) },
            confirmButton = {
                TextButton(onClick = {
                    copyScopeFor = null
                    duplicateFamilyAndEdit(entry)
                }) {
                    Text(stringResource(R.string.theme_copy_scope_all))
                }
            },
            dismissButton = {
                // Two actions in the dismiss slot on purpose — same shape as
                // the addon requires dialog (AlertDialogFlowRow takes both).
                TextButton(onClick = {
                    copyScopeFor = null
                    duplicateAndEdit(shown)
                }) {
                    Text(stringResource(R.string.theme_copy_scope_one))
                }
                TextButton(onClick = { copyScopeFor = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/** The label of one [ThemeGalleryStyle] choice. */
private fun themeGalleryStyleLabelRes(style: ThemeGalleryStyle): Int = when (style) {
    ThemeGalleryStyle.AUTO -> R.string.theme_gallery_style_auto_label
    ThemeGalleryStyle.GROUPED -> R.string.theme_gallery_style_grouped_label
    ThemeGalleryStyle.FLAT -> R.string.theme_gallery_style_flat_label
}

/** What each gallery style shows, one line, for the picker sheet. */
@StringRes
private fun themeGalleryStyleDescRes(style: ThemeGalleryStyle): Int = when (style) {
    ThemeGalleryStyle.AUTO -> R.string.theme_gallery_style_auto_desc
    ThemeGalleryStyle.GROUPED -> R.string.theme_gallery_style_grouped_desc
    ThemeGalleryStyle.FLAT -> R.string.theme_gallery_style_flat_desc
}

/**
 * One dot per look of a family. Each dot is the look's board — its gradient
 * when it has one, its board colour otherwise, so Pitch black reads black and
 * the light looks read light — with the accent as a small core so same-board
 * colorways still tell apart. Tapping a dot both turns the card's preview to
 * that look and applies it — the card is the whole picker, with no second
 * level to open.
 */
@Composable
private fun VariantSwatchRow(
    family: ThemeSpec,
    shownId: String,
    onPick: (ThemeSpec) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    ) {
        family.selfAndVariants().forEach { variant ->
            val shown = variant.id == shownId
            val label = themeName(variant)
            // Opaque even when the theme's own board is see-through over a
            // photo — a transparent dot would read as a hole in the card.
            val fill = variant.boardGradient?.brush()
                ?: SolidColor(Color(variant.boardBackground or 0xFF000000L))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (shown) 2.dp else 1.dp,
                        color = if (shown) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .semantics { contentDescription = label }
                    .clickable { onPick(variant) }
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(fill),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(variant.accent)),
                )
            }
        }
    }
}

@Composable
private fun DefaultThemeCard(selected: Boolean, onSelect: () -> Unit) {
    // Preview approximated from the app's own Material scheme.
    val scheme = MaterialTheme.colorScheme
    val preview = remember(scheme) {
        themeFromSeed("preview_default", "Default", scheme.primary.argb(), dark = false).copy(
            boardBackground = scheme.surfaceContainerLow.argb(),
            keyBackground = scheme.surfaceContainerHighest.argb(),
            keyText = scheme.onSurface.argb(),
            modifierKeyBackground = scheme.surfaceContainerHigh.argb(),
            enterKeyBackground = scheme.primary.argb(),
            enterKeyText = scheme.onPrimary.argb(),
            accent = scheme.primary.argb(),
        )
    }
    Row(modifier = Modifier.padding(horizontal = 12.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            ThemeCard(
                theme = preview.copy(name = stringResource(R.string.theme_default_name)),
                selected = selected,
                onSelect = onSelect,
                onEdit = null,
                onExport = null,
                onDelete = null,
                subtitle = stringResource(R.string.theme_default_card_subtitle),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** A theme card's outline, which its selection ring follows. */
private val ThemeCardShape = RoundedCornerShape(14.dp)

/** How long the picked card takes to put its ring and tick on. */
private const val CardSelectMs = 180

/** How long the card left behind takes to let go of its tick: a little quicker. */
private const val CardDeselectMs = 140

@Composable
private fun ThemeCard(
    theme: ThemeSpec,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    subtitle: String? = null,
    /** Whether this theme sits on the keyboard Themes tool's shortlist; null hides the toggle. */
    panelShown: Boolean? = null,
    onTogglePanel: (() -> Unit)? = null,
    /** Replaces the theme's own name as the card title — the family's label. */
    title: String? = null,
    /** A family card's dot row, under the preview; null on single-look cards. */
    swatches: (@Composable () -> Unit)? = null,
    /**
     * The editor this card's pencil opens, when it opens one directly. The
     * preview and the name then fly into that screen's own preview and
     * heading, so the card reads as the thing being opened.
     *
     * Null on the cards whose pencil does something else first — a built-in is
     * copied before it is edited, and the copy has an id this card never sees.
     */
    editRoute: String? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    // A built-in theme draws its translated name; a theme the user made keeps
    // the name the user typed.
    val displayName = title ?: themeName(theme)
    // The editor is headed with the *look's* name. On a plain card that is the
    // headline; on a family card the headline is the family's label and the
    // look's name is the line under it. The flight has to leave from whichever
    // of the two says the same word the heading will.
    val nameTag = if (editRoute == null) Modifier
    else Modifier.wmSharedBounds(takeOffKey("title", editRoute))
    // Picking a theme moves the ring from one card to another. It thickens and
    // takes the accent over a moment on the card picked, and lets go of it on
    // the card left, rather than both snapping. Read in the draw phase below,
    // so each frame of it redraws the two cards without recomposing them —
    // each card holds a whole keyboard preview. The gallery keys every card
    // on its theme, so this plays when the pick changes and not when a card
    // is handed a different slot.
    val reduceMotion = LocalReduceMotion.current
    val ringTarget = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val ringWidthTarget = if (selected) 2.dp else 1.dp
    val ringColor by animateColorAsState(
        ringTarget,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = CardSelectMs),
        label = "themeCardRing",
    )
    val ringWidth by animateDpAsState(
        ringWidthTarget,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = CardSelectMs),
        label = "themeCardRingWidth",
    )
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clip(ThemeCardShape)
            .drawWithCache {
                val outline = ThemeCardShape.createOutline(size, layoutDirection, this)
                onDrawWithContent {
                    drawContent()
                    // Reduce motion reads the target, not a value a frame
                    // behind it. The stroke is centred on the edge and twice
                    // the ring's width: the clip above cuts it to exactly the
                    // ring's width inside the card, which is what `border`
                    // drew here before.
                    val width = if (reduceMotion) ringWidthTarget else ringWidth
                    drawOutline(
                        outline,
                        color = if (reduceMotion) ringTarget else ringColor,
                        style = Stroke(width = width.toPx() * 2f),
                    )
                }
            }
            .clickable(onClick = onSelect)
            .padding(6.dp),
    ) {
        ThemePreview(
            theme,
            modifier = if (editRoute == null) Modifier
            else Modifier.wmSharedBounds(takeOffKey("preview", editRoute)),
        )
        swatches?.invoke()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp)
                        .then(if (title == null) nameTag else Modifier),
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .then(if (title != null) nameTag else Modifier),
                    )
                }
            }
            val check: @Composable () -> Unit = {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = stringResource(R.string.theme_selected_desc),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            // The tick answers the tap: it pops in on the card picked while
            // the one on the card left shrinks away.
            if (reduceMotion) {
                if (selected) check()
            } else {
                AnimatedVisibility(
                    visible = selected,
                    enter = scaleIn(tween(durationMillis = CardSelectMs), initialScale = 0.5f) +
                        fadeIn(tween(durationMillis = CardSelectMs)),
                    exit = scaleOut(tween(durationMillis = CardDeselectMs), targetScale = 0.5f) +
                        fadeOut(tween(durationMillis = CardDeselectMs)),
                ) { check() }
            }
        }
        if (onEdit != null || onExport != null || onDelete != null) {
            Row {
                if (onEdit != null) {
                    // Wrapped even with no route: a card that opens a copy
                    // still has to say which screen the next one was opened
                    // from, or the flight that lands there is keyed on nothing.
                    val edit = takeOffClick(onEdit)
                    IconButton(onClick = edit, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.theme_edit_desc, displayName),
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onExport != null) {
                    IconButton(onClick = onExport, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = stringResource(R.string.theme_export_desc, displayName),
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.theme_delete_desc, displayName),
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (panelShown != null && onTogglePanel != null) {
                    IconButton(onClick = onTogglePanel, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = stringResource(
                                if (panelShown) {
                                    R.string.theme_panel_shown_desc
                                } else {
                                    R.string.theme_panel_hidden_desc
                                },
                                displayName,
                            ),
                            modifier = Modifier.size(17.dp),
                            tint = if (panelShown) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.theme_delete_title)) },
            text = { Text(stringResource(R.string.theme_delete_message, displayName)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) {
                    Text(stringResource(CommonR.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

// ---- theme editor ----

/**
 * The family stored under [routeId], and the look [lookId] inside it — the
 * family itself once that look has gone. Null once the family has gone too.
 */
private fun openFamilyAndLook(
    settings: KeyboardSettings,
    routeId: String,
    lookId: String,
): Pair<ThemeSpec, ThemeSpec>? {
    val family = settings.customThemes.findThemeFamily(routeId) ?: return null
    return family to (family.selfAndVariants().find { it.id == lookId } ?: family)
}

/** What a row reads for the instant between its look being deleted and the editor closing. */
private val GoneLook = ThemeSpec(id = "", name = "")

/**
 * The look the theme editor has open, read a field at a time.
 *
 * Every edit rewrites the stored theme, so read as one object the look was new
 * on every write and every row of the editor recomposed to find its own field
 * unchanged. Through [watch] a row recomposes when the field it draws changes
 * and not otherwise.
 */
@Stable
private class OpenLook(
    private val settings: LiveSettings,
    /** The id the route named, which finds the family that stores it. */
    private val routeId: String,
    /** The look being edited: the family itself or one of its variants. */
    val id: String,
) {
    /** The family and the look as stored now, for a write; null once deleted. */
    fun now(): Pair<ThemeSpec, ThemeSpec>? = openFamilyAndLook(settings.value, routeId, id)

    /** [pick] of the look, recomposing the caller only when that changes. */
    @Composable
    fun <R> watch(pick: (ThemeSpec) -> R): R = watchWith { _, look -> pick(look) }

    /** [pick] of the settings and the look, for a value that falls back to a setting. */
    @Composable
    fun <R> watchWith(pick: (KeyboardSettings, ThemeSpec) -> R): R =
        settings.watch { pick(it, openFamilyAndLook(it, routeId, id)?.second ?: GoneLook) }
}

@Composable
fun ThemeEditorScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    themeId: String,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The route id can name a variant; the editor opens on that look, inside
    // the family entry that stores it. The family's id and the open look's are
    // what decide the screen; each row reads its own field of the look below.
    val familyId = settings.watch { it.customThemes.findThemeFamily(themeId)?.id }
    if (familyId == null) {
        Text(
            stringResource(R.string.theme_editor_missing_body),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // Which look is open. Survives process death; a look deleted meanwhile
    // falls back to the family itself.
    var editingId by rememberSaveable(familyId) { mutableStateOf(themeId) }
    val editing = editingId
    val lookId = settings.watch { openFamilyAndLook(it, themeId, editing)?.second?.id } ?: familyId
    val look = remember(settings, themeId, lookId) { OpenLook(settings, themeId, lookId) }
    // Every write goes through the family entry: editing a look must never
    // promote it to a top-level theme of its own.
    fun update(transform: (ThemeSpec) -> ThemeSpec) {
        val (family, _) = look.now() ?: return
        scope.launch { repository.upsertCustomTheme(family.replacingMember(look.id, transform)) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val (family, theme) = look.now() ?: return@rememberLauncherForActivityResult
            val keyOpacity = settings.value.photoBackground.keyOpacity
            scope.launch(Dispatchers.IO) {
                runCancellable {
                    val file = File(themeImagesDir(context), "${theme.id}_${System.currentTimeMillis()}.img")
                    context.contentResolver.requireInputStream(uri).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    theme.backgroundImage?.let { File(it).delete() }
                    // A GIF picked from the gallery plays, without the user
                    // having to find the switch below and wonder why their
                    // moving picture is a still. Blur is the other half of that
                    // pair and has to go, since the two exclude each other.
                    val animated = isAnimatedImageFile(file)
                    // Same treatment an online photo gets: the board goes
                    // see-through so the image shows, and the keys stop
                    // covering all of it. The user raises either back.
                    repository.upsertCustomTheme(
                        family.replacingMember(theme.id) {
                            theme.copy(
                                backgroundImage = file.absolutePath,
                                backgroundPhoto = null,
                                backgroundAnimated = animated,
                                backgroundImageBlur =
                                    if (animated) 0f else theme.backgroundImageBlur,
                                boardBackground = theme.boardBackground and 0x00FFFFFFL,
                                keyBackground = theme.keyBackground.softenedForPhoto(keyOpacity),
                                modifierKeyBackground = theme.modifierKeyBackground.softenedForPhoto(keyOpacity),
                            )
                        },
                    )
                    // A photo the user picked is worth keeping: it can then go
                    // on another theme, or into the rotation, without being
                    // hunted down in the gallery again.
                    PhotoBackgroundManager.addToCollection(context, file)
                }
            }
        }
    }
    // Separate picker for the landscape image: it only swaps that path, and
    // leaves the board alpha alone (the portrait picker already set the scrim).
    val imagePickerLandscape = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val (family, theme) = look.now() ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                runCancellable {
                    val file = File(
                        themeImagesDir(context),
                        "${theme.id}_land_${System.currentTimeMillis()}.img",
                    )
                    context.contentResolver.requireInputStream(uri).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    theme.backgroundImageLandscape?.let { File(it).delete() }
                    // Same as the portrait picker, except that the flag is one
                    // per theme: a landscape GIF turns it on, and a still one
                    // leaves whatever the portrait image asked for alone rather
                    // than stopping an animation the user can still see.
                    val animated = isAnimatedImageFile(file)
                    repository.upsertCustomTheme(
                        family.replacingMember(theme.id) {
                            theme.copy(
                                backgroundImageLandscape = file.absolutePath,
                                backgroundPhotoLandscape = null,
                                backgroundAnimated =
                                    theme.backgroundAnimated || animated,
                                backgroundImageBlur =
                                    if (animated) 0f else theme.backgroundImageBlur,
                            )
                        },
                    )
                    PhotoBackgroundManager.addToCollection(context, file)
                }
            }
        }
    }
    var cropOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var cropLandscapeOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var shapePickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var popupShapePickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var chipShapePickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var menuShapePickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var cardShapePickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var toolShapePickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var sourceDialogSlot by remember(lookId) { mutableStateOf<BackgroundSlot?>(null) }

    // The photo rows appear only once the user has started using photos.
    // Somebody who sets one picture from their gallery -- which is most people
    // -- never sees either, and the screen they do use stays shorter for it.
    val collection by produceState(initialValue = emptyList<PoolEntry>(), themeId) {
        value = PhotoBackgroundManager.readPool(context).entries
    }
    // What decides which rows the groups hold, and what their "?" says; each
    // row reads its own value.
    val rotateOn = settings.watch { it.photoBackground.rotateEnabled }
    val hasPhotoKeys = settings.watch {
        it.photoBackground.unsplashApiKey.isNotBlank() || it.photoBackground.pexelsApiKey.isNotBlank()
    }
    val showRotation = collection.isNotEmpty() || rotateOn
    val showServices = hasPhotoKeys || collection.any { it.credit != null }
    val popupsOn = settings.watch { it.popup.enabled }
    val soundOn = settings.watch { it.sound.enabled }
    val hasVariants = settings.watch { it.customThemes.findThemeFamily(themeId)?.variants?.isNotEmpty() == true }
    val hasImage = look.watch { it.backgroundImage != null }
    val hasLandscapeImage = look.watch { it.backgroundImageLandscape != null }
    val photoCredit = look.watch { it.backgroundPhoto }
    val landscapePhotoCredit = look.watch { it.backgroundPhotoLandscape }
    val hasOneHandedFill = look.watch { it.oneHandedPanelBackground != null }
    val hasKeyBorder = look.watch { it.keyBorderColor != null }
    val texturedSlots = look.watch { t -> KeyTextureSlot.entries.filter { it.pathIn(t) != null }.toSet() }
    val overrideIds = look.watch { it.keyOverrides.keys.sorted() }
    val decals = look.watch { it.decals }
    val hasPopupBorder = look.watch { it.popupBorderColor != null }
    val popupHighlight = look.watch { it.popupSelectedBackground }
    val hasToolBorder = look.watch { it.toolBorderColor != null }
    val hasChipBorder = look.watch { it.chipBorderColor != null }
    val chipRadiusShown = look.watch { t ->
        keyShapeKindOrNull(t.chipShape).let {
            it == null || it == KeyShapeKind.ROUNDED || it == KeyShapeKind.CUT
        }
    }
    val themeAnimates = look.watch { it.animation != ThemeAnimation.NONE }
    val effectKind = look.watch { keyEffectKindOrNull(it.keyEffect) }
    val effectImages = look.watch { it.keyEffectImages }
    val effectColourCustom = look.watch { keyEffectColorMode(it.keyEffectColor) == KeyEffectColorMode.CUSTOM }
    val scriptFontIds = look.watch { it.scriptFontIds }

    sourceDialogSlot?.let { slot ->
        val landscape = slot == BackgroundSlot.LANDSCAPE
        BackgroundSourceDialog(
            hasImage = if (landscape) hasLandscapeImage else hasImage,
            onDevice = {
                sourceDialogSlot = null
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (landscape) imagePickerLandscape.launch(request) else imagePicker.launch(request)
            },
            onOnline = {
                sourceDialogSlot = null
                onNavigate(photoBrowseRoute(look.id, slot))
            },
            onSaved = {
                sourceDialogSlot = null
                onNavigate(photoLibraryRoute(look.id, slot))
            },
            onRemove = {
                sourceDialogSlot = null
                scope.launch {
                    // The repository restores the board's opacity if applying a
                    // photo had zeroed it, so removal never leaves a
                    // see-through board.
                    repository.clearThemePhoto(look.id, landscape)?.let { File(it).delete() }
                }
            },
            onDismiss = { sourceDialogSlot = null },
        )
    }

    // Live preview, pinned under the bar so it stays put while the sections
    // below scroll (#43). The keyboard itself, not a mock-up (#148): a
    // miniature at real proportions here, and at actual size docked along the
    // bottom of the window when the floating button asks for it, where the
    // sections scroll above it the way an app does over the keyboard. One
    // typed buffer serves both, so switching between them loses nothing.
    val previewSandbox = remember(familyId) { mutableStateOf(ThemePreviewSandbox()) }
    var keyboardDocked by rememberSaveable(familyId) { mutableStateOf(false) }
    // Back takes the docked keyboard down first, as it would a real one.
    BackHandler(enabled = keyboardDocked) { keyboardDocked = false }
    val reduceMotion = settings.watch { it.reduceMotion }
    RegisterPinned {
        AnimatedVisibility(
            visible = !keyboardDocked,
            enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ThemeKeyboardPreview(
                    settings = settings.watch { it },
                    theme = look.watch { it },
                    sandbox = previewSandbox,
                    miniature = true,
                    // The gallery card's miniature grows into this one. Both
                    // draw the same theme at two sizes, which is what
                    // [wmSharedBounds] is for — the keys scale rather than
                    // being laid out again mid-flight.
                    modifier = Modifier.wmSharedBounds(landingKey("preview")),
                )
            }
        }
    }
    RegisterDock {
        AnimatedVisibility(
            visible = keyboardDocked,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                slideInVertically { it } + expandVertically(expandFrom = Alignment.Top)
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                slideOutVertically { it } + shrinkVertically(shrinkTowards = Alignment.Top)
            },
        ) {
            ThemeKeyboardPreview(
                settings = settings.watch { it },
                theme = look.watch { it },
                sandbox = previewSandbox,
                miniature = false,
                onHide = { keyboardDocked = false },
            )
        }
    }
    RegisterFab {
        FloatingActionButton(onClick = { keyboardDocked = !keyboardDocked }) {
            Icon(
                if (keyboardDocked) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
                contentDescription = stringResource(
                    if (keyboardDocked) {
                        R.string.theme_preview_keyboard_hide_desc
                    } else {
                        R.string.theme_preview_keyboard_show_desc
                    },
                ),
            )
        }
    }

    val untitledName = stringResource(R.string.theme_untitled_name)
    val offLabel = stringResource(CommonR.string.common_off)

    // The theme's looks: a chip per member, an add chip, and — when a
    // variant is open — a way to delete it. Everything below the row edits
    // the open look alone.
    var confirmDeleteVariant by remember(lookId) { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.theme_variant_section_title), foldKey = "theme/variant") {
        item {
            val members = settings.watch { s ->
                s.customThemes.findThemeFamily(themeId)?.selfAndVariants()?.map { it.id to it.name }.orEmpty()
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                members.forEach { (memberId, memberName) ->
                    FilterChip(
                        selected = memberId == lookId,
                        onClick = { editingId = memberId },
                        label = { Text(memberName.ifBlank { untitledName }) },
                    )
                }
                AssistChip(
                    onClick = {
                        scope.launch {
                            val (family, theme) = look.now() ?: return@launch
                            if (family.variants.size < MAX_THEME_VARIANTS) {
                                val vid = "${family.id}_v${System.currentTimeMillis()}"
                                // A copy of the open look. Image paths are
                                // shared, not copied: the sweep keeps a file
                                // as long as any member points at it.
                                val fresh = theme.copy(
                                    id = vid,
                                    name = context.getString(
                                        R.string.theme_duplicate_name,
                                        theme.name,
                                    ),
                                    variants = emptyList(),
                                    familyName = null,
                                )
                                repository.upsertCustomTheme(
                                    family.copy(variants = family.variants + fresh),
                                )
                                editingId = vid
                            }
                        }
                    },
                    enabled = members.size - 1 < MAX_THEME_VARIANTS,
                    label = { Text(stringResource(R.string.theme_variant_add_action)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(16.dp))
                    },
                )
                if (lookId != familyId) {
                    IconButton(
                        onClick = { confirmDeleteVariant = true },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.theme_variant_delete_action),
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (hasVariants) {
            item {
                // Same local-echo pattern as the name field below: the field
                // is the source of truth while the user types.
                val storedFamilyName = settings.watch { it.customThemes.findThemeFamily(themeId)?.familyName }
                var familyNameText by rememberSaveable(familyId) {
                    mutableStateOf(storedFamilyName.orEmpty())
                }
                OutlinedTextField(
                    value = familyNameText,
                    onValueChange = { text ->
                        familyNameText = text
                        scope.launch {
                            val (family, _) = look.now() ?: return@launch
                            repository.upsertCustomTheme(
                                family.copy(familyName = text.ifBlank { null }),
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.theme_family_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
    if (confirmDeleteVariant) {
        AlertDialog(
            onDismissRequest = { confirmDeleteVariant = false },
            title = { Text(stringResource(R.string.theme_variant_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.theme_variant_delete_message,
                        look.watch { it.name }.ifBlank { untitledName },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteVariant = false
                    val doomedId = lookId
                    editingId = familyId
                    scope.launch { repository.deleteCustomThemeVariant(familyId, doomedId) }
                }) {
                    Text(stringResource(CommonR.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteVariant = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    val storedName = look.watch { it.name }
    var name by rememberSaveable(lookId) { mutableStateOf(storedName) }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            update { t -> t.copy(name = it.ifBlank { untitledName }) }
        },
        label = { Text(stringResource(R.string.theme_name_label)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )

    val seedImageNote = stringResource(R.string.photo_seed_keeps_image_body)
        .takeIf { hasImage || hasLandscapeImage }
    SettingsGroup(
        stringResource(R.string.theme_seed_section_title),
        foldKey = "theme/seed",
        info = listOfNotNull(stringResource(R.string.theme_seed_section_body), seedImageNote)
            .joinToString("\n\n"),
    ) {
        // Changing the seed or the light/dark switch rebuilds every colour. The
        // board keeps how see-through it is, so the photo stays visible -- but
        // it is worth saying, because the colours around it do all change.
        item {
            // No `default`: the switch belongs to the theme being edited, and
            // a theme's own light/dark is whatever it was authored as. There is
            // no shipped value to reset it to.
            ToggleSetting(
                R.string.theme_editor_dark_title,
                stringResource(R.string.theme_editor_dark_subtitle),
                checked = look.watch { it.dark },
            ) { dark -> update { t -> t.reseeded(t.enterKeyBackground, dark) } }
        }
        item {
            // Issue #357. The theme keeps its own colours and the keyboard moves
            // them onto the wallpaper on the way to the screen, so this is a
            // switch rather than a one-way conversion: off gives them back.
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ToggleSetting(
                R.string.theme_follow_wallpaper_title,
                stringResource(
                    if (supported) {
                        R.string.theme_follow_wallpaper_subtitle
                    } else {
                        R.string.theme_follow_wallpaper_unsupported
                    },
                ),
                checked = look.watch { it.followWallpaper },
                info = stringResource(R.string.theme_follow_wallpaper_info),
                enabled = supported,
            ) { follow -> update { t -> t.copy(followWallpaper = follow) } }
        }
        item {
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SeedSwatches) { seed ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colorOf(seed))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { update { t -> t.reseeded(seed, t.dark) } },
                    )
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.theme_board_section_title),
        foldKey = "theme/board",
        info = stringResource(R.string.theme_background_image_alpha_body)
            .takeIf { hasImage },
    ) {
        item {
            ColorRow(
                R.string.theme_board_background_title,
                look.watch { it.boardBackground },
                supportsAlpha = true,
            ) {
                update { t -> t.copy(boardBackground = it) }
            }
        }
        item {
            val boardBackground = look.watch { it.boardBackground }
            val accent = look.watch { it.accent }
            GradientEditor(
                title = R.string.theme_board_gradient_title,
                subtitle = stringResource(R.string.theme_board_gradient_subtitle),
                gradient = look.watch { it.boardGradient },
                defaultGradient = GradientSpec(
                    colors = listOf(boardBackground or 0xFF000000L, accent),
                    type = GradientType.LINEAR,
                    angleDeg = 135f,
                ),
                onChange = { update { t -> t.copy(boardGradient = it) } },
            )
        }
        item {
            // Both bars follow the board until the theme says otherwise, so
            // their fallback swatch is the board's own colour and Auto is the
            // resting state (issue #109).
            NullableColorRow(
                R.string.theme_suggestion_bar_title,
                look.watch { it.suggestionBarBackground },
                fallback = look.watch { it.boardBackground },
                supportsAlpha = true,
                info = stringResource(R.string.theme_suggestion_bar_body),
                onChange = { update { t -> t.copy(suggestionBarBackground = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_navigation_bar_title,
                look.watch { it.navigationBarBackground },
                fallback = look.watch { it.boardBackground },
                supportsAlpha = true,
                info = stringResource(R.string.theme_navigation_bar_body),
                onChange = { update { t -> t.copy(navigationBarBackground = it) } },
            )
        }
        item {
            // The rail beside the board in one-handed mode. Auto leaves it
            // transparent, so the board runs on behind it.
            NullableColorRow(
                R.string.theme_one_handed_title,
                look.watch { it.oneHandedPanelBackground },
                fallback = look.watch { it.boardBackground },
                supportsAlpha = true,
                info = stringResource(R.string.theme_one_handed_body),
                onChange = { update { t -> t.copy(oneHandedPanelBackground = it) } },
            )
        }
        // Nested: the glyphs only need their own colour once the rail has a
        // fill of its own to sit on.
        if (hasOneHandedFill) {
            item {
                NullableColorRow(
                    R.string.theme_one_handed_icon_title,
                    look.watch { it.oneHandedPanelIcon },
                    fallback = look.watch { it.secondaryText ?: it.suggestionText ?: it.keyText },
                    onChange = { update { t -> t.copy(oneHandedPanelIcon = it) } },
                )
            }
        }
        item {
            val image = look.watch { it.backgroundImage }
            WmRow(
                title = stringResource(R.string.theme_background_image_title),
                subtitle = stringResource(
                    if (image == null) R.string.theme_background_image_none
                    else R.string.theme_background_image_replace,
                ),
                leading = { ImageThumb(image) },
                trailing = {
                    val existingImage = image
                    if (existingImage != null) {
                        TextButton(onClick = {
                            File(existingImage).delete()
                            update { t ->
                                // Give the board its alpha back if picking the
                                // image zeroed it, so removal does not leave a
                                // see-through board.
                                val board = if ((t.boardBackground ushr 24) == 0L) {
                                    t.boardBackground or 0xFF000000L
                                } else {
                                    t.boardBackground
                                }
                                t.copy(backgroundImage = null, boardBackground = board)
                            }
                        }) { Text(stringResource(CommonR.string.common_delete)) }
                    }
                },
                onClick = { sourceDialogSlot = BackgroundSlot.PORTRAIT },
            )
        }
        photoCredit?.let { credit ->
            // Both services make it necessary to name the photographer where
            // their photo is shown, so the credit sits on the row itself.
            item { PhotoCreditRow(credit) { url -> openLink(context, url) } }
        }
        if (hasImage) {
            item {
                NavRow(
                    R.string.theme_crop_image_title,
                    subtitle = stringResource(R.string.theme_crop_image_subtitle),
                ) { cropOpen = true }
            }
            item {
                SliderRow(
                    R.string.theme_image_opacity_title,
                    value = look.watch { it.backgroundImageOpacity },
                    range = 0f..1f,
                    display = { "${(it * 100).toInt()}%" },
                ) { update { t -> t.copy(backgroundImageOpacity = it) } }
            }
            item {
                SliderRow(
                    R.string.theme_image_blur_title,
                    value = look.watch { it.backgroundImageBlur },
                    range = 0f..25f,
                    display = { if (it < 0.5f) offLabel else it.toInt().toString() },
                    // Blurring an animation re-renders the effect every frame,
                    // so the two settings exclude each other: raising the blur
                    // stops the animation, and the switch below zeroes the blur.
                ) { update { t -> t.copy(backgroundImageBlur = it, backgroundAnimated = false) } }
            }
            if (!reduceMotion) item {
                ToggleSetting(
                    R.string.theme_background_animated_title,
                    stringResource(R.string.theme_background_animated_subtitle),
                    checked = look.watch { it.backgroundAnimated },
                ) { on ->
                    update { t ->
                        t.copy(
                            backgroundAnimated = on,
                            backgroundImageBlur = if (on) 0f else t.backgroundImageBlur,
                        )
                    }
                }
            }
        }
        item {
            val landscapeImage = look.watch { it.backgroundImageLandscape }
            WmRow(
                title = stringResource(R.string.theme_background_image_landscape_title),
                subtitle = stringResource(
                    if (landscapeImage == null) {
                        R.string.theme_background_image_landscape_none
                    } else {
                        R.string.theme_background_image_replace
                    },
                ),
                leading = { ImageThumb(landscapeImage) },
                trailing = {
                    val existingLandscapeImage = landscapeImage
                    if (existingLandscapeImage != null) {
                        TextButton(onClick = {
                            File(existingLandscapeImage).delete()
                            update { t -> t.copy(backgroundImageLandscape = null) }
                        }) { Text(stringResource(CommonR.string.common_delete)) }
                    }
                },
                onClick = { sourceDialogSlot = BackgroundSlot.LANDSCAPE },
            )
        }
        landscapePhotoCredit?.let { credit ->
            item { PhotoCreditRow(credit) { url -> openLink(context, url) } }
        }
        if (hasLandscapeImage) {
            item {
                NavRow(
                    R.string.theme_crop_landscape_title,
                    subtitle = stringResource(R.string.theme_crop_image_subtitle),
                ) { cropLandscapeOpen = true }
            }
        }
        // Only offered once there is something to rotate. Most people set one
        // photo and stop, and a row for a feature they have not started is
        // clutter in the one screen they do use.
        item(visible = showRotation) {
            NavRow(
                R.string.photo_rotation_title,
                subtitle = stringResource(R.string.photo_rotation_subtitle),
                value = stringResource(
                    if (rotateOn) {
                        CommonR.string.common_on
                    } else {
                        CommonR.string.common_off
                    },
                ),
                route = PHOTO_ROTATION_ROUTE,
            ) { onNavigate(PHOTO_ROTATION_ROUTE) }
        }
        // Likewise: somebody who never opens the online picker has no key to
        // manage. The picker's own "add a key" action reaches this screen.
        item(visible = showServices) {
            NavRow(
                R.string.photo_services_title,
                subtitle = stringResource(R.string.photo_services_subtitle),
                route = PHOTO_HUB_ROUTE,
            ) { onNavigate(PHOTO_HUB_ROUTE) }
        }
    }

    val cropSource = look.watch { it.backgroundImage }
    if (cropOpen && cropSource != null) {
        CropImageDialog(
            path = cropSource,
            onCropped = { newPath ->
                scope.launch {
                    File(cropSource).delete()
                    val (family, theme) = look.now() ?: return@launch
                    // The crop decodes one frame and writes it back, so an
                    // animation does not survive it. Whether the theme still
                    // animates is now down to the other orientation's image.
                    val stillAnimated = withContext(Dispatchers.IO) {
                        theme.backgroundImageLandscape?.let { isAnimatedImageFile(File(it)) } == true
                    }
                    repository.upsertCustomTheme(
                        family.replacingMember(theme.id) {
                            theme.copy(
                                backgroundImage = newPath,
                                backgroundAnimated = stillAnimated,
                            )
                        },
                    )
                }
                cropOpen = false
            },
            onDismiss = { cropOpen = false },
        )
    }
    val cropLandscapeSource = look.watch { it.backgroundImageLandscape }
    if (cropLandscapeOpen && cropLandscapeSource != null) {
        CropImageDialog(
            path = cropLandscapeSource,
            onCropped = { newPath ->
                scope.launch {
                    File(cropLandscapeSource).delete()
                    val (family, theme) = look.now() ?: return@launch
                    // As above: the cropped landscape image is a still now, so
                    // only the portrait one can keep the flag on.
                    val stillAnimated = withContext(Dispatchers.IO) {
                        theme.backgroundImage?.let { isAnimatedImageFile(File(it)) } == true
                    }
                    repository.upsertCustomTheme(
                        family.replacingMember(theme.id) {
                            theme.copy(
                                backgroundImageLandscape = newPath,
                                backgroundAnimated = stillAnimated,
                            )
                        },
                    )
                }
                cropLandscapeOpen = false
            },
            onDismiss = { cropLandscapeOpen = false },
        )
    }

    if (shapePickerOpen) {
        KeyShapePickerDialog(
            selected = look.watch { it.keyShape },
            radiusDp = look.watchWith { s, t -> t.keyCornerRadiusDp ?: s.keyCornerRadiusDp },
            onPick = { kind ->
                update { t -> t.copy(keyShape = kind) }
                shapePickerOpen = false
            },
            onDismiss = { shapePickerOpen = false },
            offerNone = true,
        )
    }
    if (popupShapePickerOpen) {
        KeyShapePickerDialog(
            selected = look.watchWith { s, t -> keyShapeKindOrNull(t.popupShape) ?: s.popup.shape },
            radiusDp = look.watchWith { s, t -> t.popupCornerRadiusDp ?: s.popup.cornerRadiusDp },
            onPick = { kind ->
                update { t -> t.copy(popupShape = kind.name) }
                popupShapePickerOpen = false
            },
            onDismiss = { popupShapePickerOpen = false },
            title = R.string.theme_popup_shape_title,
        )
    }
    if (toolShapePickerOpen) {
        KeyShapePickerDialog(
            selected = look.watchWith { s, t -> keyShapeKindOrNull(t.toolShape) ?: s.toolShape },
            radiusDp = look.watchWith { s, t -> t.toolCircleRadiusDp ?: s.toolCircleRadiusDp },
            onPick = { kind ->
                update { t -> t.copy(toolShape = kind.name) }
                toolShapePickerOpen = false
            },
            onDismiss = { toolShapePickerOpen = false },
            title = R.string.theme_tool_shape_title,
        )
    }
    if (chipShapePickerOpen) {
        KeyShapePickerDialog(
            selected = look.watch { keyShapeKindOrNull(it.chipShape) ?: KeyShapeKind.ROUNDED },
            radiusDp = look.watch { it.chipCornerRadiusDp ?: DefaultChipRadiusDp },
            onPick = { kind ->
                update { t -> t.copy(chipShape = kind.name) }
                chipShapePickerOpen = false
            },
            onDismiss = { chipShapePickerOpen = false },
            title = R.string.theme_chip_shape_title,
        )
    }
    if (menuShapePickerOpen) {
        KeyShapePickerDialog(
            selected = look.watch { keyShapeKindOrNull(it.menuShape) },
            radiusDp = look.watchWith { s, t -> t.popupCornerRadiusDp ?: s.popup.cornerRadiusDp },
            onPick = { kind ->
                update { t -> t.copy(menuShape = kind.name) }
                menuShapePickerOpen = false
            },
            onDismiss = { menuShapePickerOpen = false },
            title = R.string.theme_menu_shape_title,
            onAuto = {
                update { t -> t.copy(menuShape = null) }
                menuShapePickerOpen = false
            },
        )
    }
    if (cardShapePickerOpen) {
        KeyShapePickerDialog(
            selected = look.watch { keyShapeKindOrNull(it.cardShape) },
            radiusDp = look.watch { it.chipCornerRadiusDp ?: DefaultChipRadiusDp },
            onPick = { kind ->
                update { t -> t.copy(cardShape = kind.name) }
                cardShapePickerOpen = false
            },
            onDismiss = { cardShapePickerOpen = false },
            title = R.string.theme_card_shape_title,
            onAuto = {
                update { t -> t.copy(cardShape = null) }
                cardShapePickerOpen = false
            },
        )
    }

    SettingsGroup(stringResource(R.string.theme_keys_section_title), foldKey = "theme/keys") {
        item {
            // A row plus a dialog, not a segmented row: a dozen shapes never fit
            // side by side, and a name on its own ("Squircle", "Leaf") does not
            // say what the key will look like. The dialog draws each one.
            val keyShape = look.watch { it.keyShape }
            WmRow(
                title = stringResource(R.string.theme_key_shape_title),
                icon = SettingsRowIcons[R.string.theme_key_shape_title],
                subtitle = keyShapeName(keyShape),
                trailing = {
                    KeyShapeSwatch(
                        kind = keyShape,
                        radiusDp = look.watchWith { s, t -> t.keyCornerRadiusDp ?: s.keyCornerRadiusDp },
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { shapePickerOpen = true },
            )
        }
        item {
            ColorRow(
                R.string.theme_letter_keys_title,
                look.watch { it.keyBackground },
                supportsAlpha = true,
            ) {
                update { t -> t.copy(keyBackground = it) }
            }
        }
        item {
            GradientEditor(
                title = R.string.theme_key_gradient_title,
                subtitle = stringResource(R.string.theme_key_gradient_subtitle),
                gradient = look.watch { it.keyGradient },
                defaultGradient = GradientSpec(
                    colors = listOf(0x26FFFFFF, 0x00FFFFFF),
                    type = GradientType.LINEAR,
                    angleDeg = 90f,
                ),
                onChange = { update { t -> t.copy(keyGradient = it) } },
            )
        }
        item {
            ColorRow(R.string.theme_key_text_title, look.watch { it.keyText }) {
                update { t -> t.copy(keyText = it) }
            }
        }
        item {
            ColorRow(
                R.string.theme_modifier_keys_title,
                look.watch { it.modifierKeyBackground },
                supportsAlpha = true,
            ) {
                update { t -> t.copy(modifierKeyBackground = it) }
            }
        }
        item {
            NullableColorRow(
                R.string.theme_modifier_key_text_title,
                look.watch { it.modifierKeyText }, fallback = look.watch { it.keyText },
                onChange = { update { t -> t.copy(modifierKeyText = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_hint_text_title,
                look.watch { it.hintText }, fallback = look.watch { it.keyText },
                supportsAlpha = true,
                onChange = { update { t -> t.copy(hintText = it) } },
            )
        }
        item {
            ColorRow(R.string.theme_enter_key_title, look.watch { it.enterKeyBackground }) {
                update { t -> t.copy(enterKeyBackground = it) }
            }
        }
        item {
            ColorRow(R.string.theme_enter_key_icon_title, look.watch { it.enterKeyText }) {
                update { t -> t.copy(enterKeyText = it) }
            }
        }
        item {
            NullableColorRow(
                R.string.theme_pressed_key_title,
                look.watch { it.pressedKeyBackground }, fallback = look.watch { it.effectivePressed() },
                onChange = { update { t -> t.copy(pressedKeyBackground = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_key_border_title,
                look.watch { it.keyBorderColor }, fallback = look.watch { it.keyText },
                onChange = { update { t -> t.copy(keyBorderColor = it) } },
            )
        }
        if (hasKeyBorder) {
            item {
                SliderRow(
                    R.string.theme_border_width_title,
                    value = look.watch { it.keyBorderWidthDp },
                    range = 0f..3f,
                    display = { "%.1f dp".format(it) },
                ) { update { t -> t.copy(keyBorderWidthDp = (it * 10).toInt() / 10f) } }
            }
        }
        item {
            // A lift, not a colour: the shadow is drawn by the platform from
            // the key's own outline. Shapes that cannot cast one stay flat
            // however far this is pushed, and so does a see-through key.
            SliderRow(
                R.string.theme_key_elevation_title,
                value = look.watch { it.keyElevationDp },
                range = 0f..MAX_ELEVATION_DP,
                display = { "%.1f dp".format(it) },
                info = stringResource(R.string.theme_key_elevation_body),
            ) { update { t -> t.copy(keyElevationDp = (it * 10).toInt() / 10f) } }
        }
    }

    var texturePickerSlot by remember(lookId) { mutableStateOf<KeyTextureSlot?>(null) }
    val texturePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val slot = texturePickerSlot
        texturePickerSlot = null
        if (uri != null && slot != null) {
            val (family, theme) = look.now() ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                runCancellable {
                    val path = importKeyTexture(context, theme.id, slot, uri)
                    if (path != null) {
                        slot.pathIn(theme)?.let { File(it).delete() }
                        repository.upsertCustomTheme(
                            family.replacingMember(theme.id) { slot.withPath(theme, path) },
                        )
                    }
                }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.theme_texture_section_title),
        foldKey = "theme/texture",
        info = stringResource(R.string.theme_texture_section_body),
    ) {
        // The popup slot paints the preview bubble and nothing else, so it
        // drops out of the list while key popups are off.
        val slots = KeyTextureSlot.entries.filter {
            it != KeyTextureSlot.POPUP || popupsOn
        }
        for (slot in slots) {
            item {
                val path = look.watch { slot.pathIn(it) }
                WmRow(
                    title = stringResource(slot.titleRes),
                    subtitle = stringResource(
                        if (path != null) {
                            R.string.theme_texture_set_label
                        } else {
                            R.string.theme_texture_unset_label
                        },
                    ),
                    leading = { ImageThumb(path) },
                    trailing = {
                        if (path != null) {
                            IconButton(
                                onClick = {
                                    update { t ->
                                        slot.pathIn(t)?.let { File(it).delete() }
                                        slot.withPath(t, null)
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription =
                                        stringResource(CommonR.string.common_remove),
                                )
                            }
                        }
                    },
                    onClick = {
                        texturePickerSlot = slot
                        texturePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
            }
        }
        if (slots.any { it in texturedSlots }) {
            item {
                ChoiceControl(
                    options = KeyTextureScale.entries.map { mode ->
                        mode to stringResource(
                            when (mode) {
                                KeyTextureScale.CROP -> R.string.theme_texture_scale_crop_label
                                KeyTextureScale.STRETCH ->
                                    R.string.theme_texture_scale_stretch_label
                                KeyTextureScale.TILE -> R.string.theme_texture_scale_tile_label
                            },
                        )
                    },
                    selected = look.watch { keyTextureScaleOrDefault(it.keyTextureScale) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    label = stringResource(R.string.theme_texture_scale_label),
                    detail = { mode ->
                        ChoiceDetail(stringResource(textureScaleDescRes(mode)))
                    },
                ) { mode -> update { t -> t.copy(keyTextureScale = mode.name) } }
            }
            item {
                SliderRow(
                    R.string.theme_texture_opacity_title,
                    value = look.watch { it.keyTextureOpacity },
                    range = 0.1f..1f,
                    display = { "${(it * 100).toInt()}%" },
                ) { update { t -> t.copy(keyTextureOpacity = (it * 100).toInt() / 100f) } }
            }
        }
    }

    var overrideEditorId by rememberSaveable(lookId) { mutableStateOf<String?>(null) }
    var addOverrideOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    SettingsGroup(
        stringResource(R.string.theme_key_override_section_title),
        foldKey = "theme/key_override",
        info = stringResource(R.string.theme_key_override_section_body),
    ) {
        for (id in overrideIds) {
            item {
                WmRow(
                    title = keyOverrideDisplayName(id),
                    trailing = {
                        IconButton(
                            onClick = {
                                update { t -> t.copy(keyOverrides = t.keyOverrides - id) }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(CommonR.string.common_remove),
                            )
                        }
                    },
                    onClick = { overrideEditorId = id },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { addOverrideOpen = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.theme_key_override_add_action)) }
        }
    }
    if (addOverrideOpen) {
        AddKeyOverrideDialog(
            onAdd = { id ->
                addOverrideOpen = false
                update { t ->
                    t.copy(keyOverrides = t.keyOverrides + (id to (t.keyOverrides[id] ?: KeyOverride())))
                }
                overrideEditorId = id
            },
            onDismiss = { addOverrideOpen = false },
        )
    }
    overrideEditorId?.let { id ->
        KeyOverrideDialog(
            id = id,
            look = look,
            popupsShown = popupsOn,
            // The theme's own effect group hides itself under reduce motion,
            // and a per-key burst is the same burst.
            effectsShown = !reduceMotion,
            onChange = { changed ->
                update { t -> t.copy(keyOverrides = t.keyOverrides + (id to changed)) }
            },
            onDismiss = { overrideEditorId = null },
        )
    }

    var decalEditorId by rememberSaveable(lookId) { mutableStateOf<String?>(null) }
    val decalPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val (family, theme) = look.now() ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                runCancellable {
                    val decalId = "d${System.currentTimeMillis()}"
                    val path = importDecalImage(context, theme.id, decalId, uri)
                    if (path != null) {
                        repository.upsertCustomTheme(
                            family.replacingMember(theme.id) {
                                theme.copy(
                                    decals = theme.decals + DecalSpec(id = decalId, image = path),
                                )
                            },
                        )
                        decalEditorId = decalId
                    }
                }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.theme_decal_section_title),
        foldKey = "theme/decal",
        info = stringResource(R.string.theme_decal_section_body),
    ) {
        decals.forEachIndexed { index, decal ->
            item {
                WmRow(
                    title = stringResource(R.string.theme_decal_item_label, index + 1),
                    leading = { Icon(Icons.Outlined.Image, contentDescription = null) },
                    trailing = {
                        IconButton(
                            onClick = {
                                update { t ->
                                    decal.image?.let { File(it).delete() }
                                    t.copy(decals = t.decals.filterNot { it.id == decal.id })
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(CommonR.string.common_remove),
                            )
                        }
                    },
                    onClick = { decalEditorId = decal.id },
                )
            }
        }
        item(visible = decals.size < MAX_DECALS) {
            OutlinedButton(
                onClick = {
                    decalPicker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.theme_decal_add_action)) }
        }
    }
    decalEditorId?.let { id ->
        DecalDialog(
            look = look,
            decalId = id,
            onChange = { changed ->
                update { t ->
                    t.copy(decals = t.decals.map { if (it.id == id) changed else it })
                }
            },
            onDismiss = { decalEditorId = null },
        )
    }

    SettingsGroup(stringResource(R.string.theme_accent_section_title), foldKey = "theme/accent") {
        item {
            ColorRow(
                R.string.theme_accent_title,
                look.watch { it.accent },
                info = stringResource(R.string.theme_accent_body),
            ) {
                update { t -> t.copy(accent = it) }
            }
        }
        item {
            NullableColorRow(
                R.string.theme_gesture_trail_title,
                look.watch { it.gestureTrailColor }, fallback = look.watch { it.accent },
                supportsAlpha = true,
                onChange = { update { t -> t.copy(gestureTrailColor = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_popup_background_title,
                look.watch { it.popupBackground }, fallback = look.watch { it.effectivePopup() },
                supportsAlpha = true,
                onChange = { update { t -> t.copy(popupBackground = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_popup_text_title,
                look.watch { it.popupText }, fallback = look.watch { it.keyText },
                onChange = { update { t -> t.copy(popupText = it) } },
            )
        }
        item {
            // Beside the popup colours rather than down in Corners, where it
            // sat behind the custom-radii switch: the shape is not a radius,
            // and a theme that wants round popups on the standard radii had to
            // turn a slider group on to reach it.
            val popupShape = look.watchWith { s, t -> keyShapeKindOrNull(t.popupShape) ?: s.popup.shape }
            WmRow(
                title = stringResource(R.string.theme_popup_shape_title),
                icon = SettingsRowIcons[R.string.theme_popup_shape_title],
                subtitle = keyShapeName(popupShape),
                trailing = {
                    KeyShapeSwatch(
                        kind = popupShape,
                        radiusDp = look.watchWith { s, t -> t.popupCornerRadiusDp ?: s.popup.cornerRadiusDp },
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { popupShapePickerOpen = true },
            )
        }
        // The preview bubble is the only thing that reads the placement, and
        // no bubble is ever published while key popups are switched off. The
        // theme keeps whatever was authored here — the row is hidden, not
        // cleared — so an exported theme is unaffected.
        if (popupsOn) item {
            // Placement: whether the bubble grows out of the key or floats
            // detached above it; the first option leaves the global setting
            // in charge.
            ChoiceSetting(
                title = R.string.theme_popup_placement_title,
                options = listOf(
                    null to stringResource(R.string.theme_popup_placement_default_label),
                    "key" to stringResource(R.string.theme_popup_placement_key_label),
                    "float" to stringResource(R.string.theme_popup_placement_float_label),
                ),
                selected = look.watch { t ->
                    t.popupPlacement
                        ?.lowercase()
                        ?.takeIf { it == "key" || it == "float" }
                },
                detail = { value ->
                    ChoiceDetail(
                        stringResource(
                            when (value) {
                                "key" -> R.string.theme_popup_placement_key_desc
                                "float" -> R.string.theme_popup_placement_float_desc
                                else -> R.string.theme_popup_placement_default_desc
                            },
                        ),
                        when (value) {
                            "key" -> Icons.Outlined.Keyboard
                            "float" -> Icons.Outlined.Layers
                            else -> Icons.Outlined.Tune
                        },
                    )
                },
            ) { value -> update { t -> t.copy(popupPlacement = value) } }
        }
        item {
            NullableColorRow(
                R.string.theme_popup_border_title,
                look.watch { it.popupBorderColor }, fallback = look.watch { it.popupText ?: it.keyText },
                onChange = { update { t -> t.copy(popupBorderColor = it) } },
            )
        }
        if (hasPopupBorder) {
            item {
                SliderRow(
                    R.string.theme_border_width_title,
                    value = look.watch { it.popupBorderWidthDp },
                    range = 0f..3f,
                    display = { "%.1f dp".format(it) },
                ) { update { t -> t.copy(popupBorderWidthDp = (it * 10).toInt() / 10f) } }
            }
        }
        item {
            SliderRow(
                R.string.theme_popup_elevation_title,
                value = look.watch { it.popupElevationDp ?: DEFAULT_POPUP_ELEVATION_DP },
                range = 0f..MAX_ELEVATION_DP,
                display = { "%.1f dp".format(it) },
            ) { update { t -> t.copy(popupElevationDp = (it * 10).toInt() / 10f) } }
        }
        item {
            // The highlight under the alternate your finger is on, and under
            // the selected row of the language picker.
            NullableColorRow(
                R.string.theme_popup_selected_title,
                look.watch { it.popupSelectedBackground }, fallback = look.watch { it.accent },
                supportsAlpha = true,
                info = stringResource(R.string.theme_popup_selected_body),
                onChange = { update { t -> t.copy(popupSelectedBackground = it) } },
            )
        }
        popupHighlight?.let { highlight ->
            item {
                NullableColorRow(
                    R.string.theme_popup_selected_text_title,
                    look.watch { it.popupSelectedText },
                    fallback = onColorFor(highlight),
                    onChange = { update { t -> t.copy(popupSelectedText = it) } },
                )
            }
        }
        item {
            // The list menus (language picker, clipboard and emoji menus)
            // derive a safe shape from the popup shape unless named here — a
            // slanted bubble is charming, a slanted menu clips its rows.
            val menuShape = look.watch { keyShapeKindOrNull(it.menuShape) }
            val effective = menuShape ?: safeContainerKind(
                look.watchWith { s, t -> keyShapeKindOrNull(t.popupShape) ?: s.popup.shape },
            )
            WmRow(
                title = stringResource(R.string.theme_menu_shape_title),
                icon = SettingsRowIcons[R.string.theme_menu_shape_title],
                subtitle = if (menuShape == null) {
                    stringResource(
                        R.string.theme_shape_auto_safe_value,
                        keyShapeName(effective),
                    )
                } else {
                    keyShapeName(menuShape)
                },
                trailing = {
                    KeyShapeSwatch(
                        kind = effective,
                        radiusDp = look.watchWith { s, t -> t.popupCornerRadiusDp ?: s.popup.cornerRadiusDp },
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { menuShapePickerOpen = true },
            )
        }
    }

    SettingsGroup(stringResource(R.string.theme_toolbar_section_title), foldKey = "theme/toolbar") {
        item {
            val toolShape = look.watchWith { s, t -> keyShapeKindOrNull(t.toolShape) ?: s.toolShape }
            WmRow(
                title = stringResource(R.string.theme_tool_shape_title),
                icon = SettingsRowIcons[R.string.theme_tool_shape_title],
                subtitle = keyShapeName(toolShape),
                trailing = {
                    KeyShapeSwatch(
                        kind = toolShape,
                        radiusDp = look.watchWith { s, t -> t.toolCircleRadiusDp ?: s.toolCircleRadiusDp },
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { toolShapePickerOpen = true },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_tool_icons_title, look.watch { it.toolbarIcon },
                fallback = look.watch { t ->
                    colorOf(t.keyText).copy(alpha = 0.65f)
                        .compositeOver(colorOf(t.boardBackground)).argb()
                },
                onChange = { update { t -> t.copy(toolbarIcon = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_tool_circles_title,
                look.watch { it.toolCircleBackground }, fallback = look.watch { it.effectiveToolCircle() },
                supportsAlpha = true,
                onChange = { update { t -> t.copy(toolCircleBackground = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_tool_circle_active_title,
                look.watch { it.toolCircleActiveBackground }, fallback = look.watch { it.effectivePressed() },
                supportsAlpha = true,
                onChange = { update { t -> t.copy(toolCircleActiveBackground = it) } },
            )
        }
        item {
            // The glyph on an active tool. Its own row rather than a shade of
            // the fill: a FlorisBoard sheet states the two separately, and
            // deriving one from the other threw away a colour the theme set.
            NullableColorRow(
                R.string.theme_tool_circle_active_icon_title,
                look.watch { it.toolCircleActiveIcon },
                fallback = look.watch { it.toolCircleActiveBackground?.let(::onColorFor) ?: it.accent },
                onChange = { update { t -> t.copy(toolCircleActiveIcon = it) } },
            )
        }
        item {
            // Colour then width, the way the key border is set: the colour is
            // what turns the outline on, and the width row appears with it.
            NullableColorRow(
                R.string.theme_tool_border_title,
                look.watch { it.toolBorderColor }, fallback = look.watch { it.toolbarIcon ?: it.keyText },
                supportsAlpha = true,
                onChange = { update { t -> t.copy(toolBorderColor = it) } },
            )
        }
        if (hasToolBorder) {
            item {
                SliderRow(
                    R.string.theme_tool_border_width_title,
                    value = look.watch { it.toolBorderWidthDp },
                    range = 0f..3f,
                    display = { "%.1f dp".format(it) },
                ) { update { t -> t.copy(toolBorderWidthDp = (it * 10).toInt() / 10f) } }
            }
        }
        item {
            SliderRow(
                R.string.theme_tool_elevation_title,
                value = look.watch { it.toolElevationDp },
                range = 0f..MAX_ELEVATION_DP,
                display = { "%.1f dp".format(it) },
            ) { update { t -> t.copy(toolElevationDp = (it * 10).toInt() / 10f) } }
        }
    }

    SettingsGroup(stringResource(R.string.theme_panels_section_title), foldKey = "theme/panels") {
        item {
            NullableColorRow(
                R.string.theme_cards_title,
                look.watch { it.chipBackground }, fallback = look.watch { it.modifierKeyBackground },
                supportsAlpha = true,
                onChange = { update { t -> t.copy(chipBackground = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_suggestion_text_title,
                look.watch { it.suggestionText }, fallback = look.watch { it.keyText },
                onChange = { update { t -> t.copy(suggestionText = it) } },
            )
        }
        item {
            // The quieter line beside the main one, and the hairlines between
            // panel parts. Both used to be the suggestion colour at a fixed
            // alpha, which no theme could overrule.
            NullableColorRow(
                R.string.theme_secondary_text_title,
                look.watch { it.secondaryText },
                fallback = look.watch { it.suggestionText ?: it.keyText },
                supportsAlpha = true,
                info = stringResource(R.string.theme_secondary_text_body),
                onChange = { update { t -> t.copy(secondaryText = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_divider_title,
                look.watch { it.dividerColor },
                fallback = look.watch { it.suggestionText ?: it.keyText },
                supportsAlpha = true,
                info = stringResource(R.string.theme_divider_body),
                onChange = { update { t -> t.copy(dividerColor = it) } },
            )
        }
    }

    SettingsGroup(
        stringResource(R.string.theme_chips_section_title),
        foldKey = "theme/chips",
        info = stringResource(R.string.theme_chips_section_body),
    ) {
        item {
            NullableColorRow(
                R.string.theme_chip_text_title,
                look.watch { it.chipText }, fallback = look.watch { it.modifierKeyText ?: it.keyText },
                onChange = { update { t -> t.copy(chipText = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_chip_active_title,
                look.watch { it.chipActiveBackground },
                fallback = look.watch { it.toolCircleActiveBackground ?: it.effectivePressed() },
                supportsAlpha = true,
                onChange = { update { t -> t.copy(chipActiveBackground = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_chip_active_text_title,
                look.watch { it.chipActiveText }, fallback = look.watch { it.accent },
                onChange = { update { t -> t.copy(chipActiveText = it) } },
            )
        }
        item {
            NullableColorRow(
                R.string.theme_chip_border_title,
                look.watch { it.chipBorderColor }, fallback = look.watch { it.accent },
                onChange = { update { t -> t.copy(chipBorderColor = it) } },
            )
        }
        if (hasChipBorder) {
            item {
                SliderRow(
                    R.string.theme_border_width_title,
                    value = look.watch { it.chipBorderWidthDp },
                    range = 0f..3f,
                    display = { "%.1f dp".format(it) },
                ) { update { t -> t.copy(chipBorderWidthDp = (it * 10).toInt() / 10f) } }
            }
        }
        item {
            SliderRow(
                R.string.theme_card_elevation_title,
                value = look.watch { it.cardElevationDp },
                range = 0f..MAX_ELEVATION_DP,
                display = { "%.1f dp".format(it) },
            ) { update { t -> t.copy(cardElevationDp = (it * 10).toInt() / 10f) } }
        }
        item {
            val chipShape = look.watch { keyShapeKindOrNull(it.chipShape) ?: KeyShapeKind.ROUNDED }
            WmRow(
                title = stringResource(R.string.theme_chip_shape_title),
                icon = SettingsRowIcons[R.string.theme_chip_shape_title],
                subtitle = keyShapeName(chipShape),
                trailing = {
                    KeyShapeSwatch(
                        kind = chipShape,
                        radiusDp = look.watch { it.chipCornerRadiusDp ?: DefaultChipRadiusDp },
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { chipShapePickerOpen = true },
            )
        }
        if (chipRadiusShown) {
            item {
                SliderRow(
                    R.string.theme_chip_radius_title,
                    value = look.watch { it.chipCornerRadiusDp ?: DefaultChipRadiusDp }.toFloat(),
                    range = 0f..24f,
                    display = { "${it.toInt()} dp" },
                ) { update { t -> t.copy(chipCornerRadiusDp = it.toInt()) } }
            }
        }
        item {
            // The panel cards and search bars, under the same safety rule as
            // the menu shape: derived from the chip shape unless named.
            val cardShape = look.watch { keyShapeKindOrNull(it.cardShape) }
            val effective = cardShape ?: safeContainerKind(
                look.watch { keyShapeKindOrNull(it.chipShape) ?: KeyShapeKind.ROUNDED },
            )
            WmRow(
                title = stringResource(R.string.theme_card_shape_title),
                icon = SettingsRowIcons[R.string.theme_card_shape_title],
                subtitle = if (cardShape == null) {
                    stringResource(
                        R.string.theme_shape_auto_safe_value,
                        keyShapeName(effective),
                    )
                } else {
                    keyShapeName(cardShape)
                },
                trailing = {
                    KeyShapeSwatch(
                        kind = effective,
                        radiusDp = look.watch { it.chipCornerRadiusDp ?: DefaultChipRadiusDp },
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = { cardShapePickerOpen = true },
            )
        }
    }

    // Decides which rows the group holds; each row reads its own radius.
    val hasCustomRadii = look.watch { it.keyCornerRadiusDp != null }
    SettingsGroup(stringResource(R.string.theme_corners_section_title), foldKey = "theme/corners") {
        item {
            ToggleSetting(
                R.string.theme_custom_radii_title,
                stringResource(R.string.theme_custom_radii_subtitle),
                checked = hasCustomRadii,
            ) { enable ->
                val current = settings.value
                update { t ->
                    // Radii only. The popup and tool shapes live with their own
                    // colours now, and a theme that has picked one keeps it
                    // whether or not it also carries its own radii.
                    if (enable) {
                        t.copy(
                            keyCornerRadiusDp = current.keyCornerRadiusDp,
                            popupCornerRadiusDp = current.popup.cornerRadiusDp,
                            toolCircleRadiusDp = current.toolCircleRadiusDp,
                        )
                    } else {
                        t.copy(
                            keyCornerRadiusDp = null,
                            popupCornerRadiusDp = null,
                            toolCircleRadiusDp = null,
                        )
                    }
                }
            }
        }
        item(visible = hasCustomRadii) {
            SliderRow(
                R.string.theme_key_radius_title,
                value = look.watch { it.keyCornerRadiusDp ?: 8 }.toFloat(),
                range = 0f..28f,
                display = { "${it.toInt()} dp" },
            ) { update { t -> t.copy(keyCornerRadiusDp = it.toInt()) } }
        }
        item(visible = hasCustomRadii) {
            SliderRow(
                R.string.theme_popup_radius_title,
                value = look.watchWith { s, t -> t.popupCornerRadiusDp ?: s.popup.cornerRadiusDp }.toFloat(),
                range = 0f..40f,
                display = { "${it.toInt()} dp" },
            ) { update { t -> t.copy(popupCornerRadiusDp = it.toInt()) } }
        }
        item(visible = hasCustomRadii) {
            SliderRow(
                R.string.theme_tool_circle_radius_title,
                value = look.watch { it.toolCircleRadiusDp ?: 20 }.toFloat(),
                range = 0f..20f,
                display = { if (it.toInt() == 0) offLabel else "${it.toInt()} dp" },
            ) { update { t -> t.copy(toolCircleRadiusDp = it.toInt()) } }
        }
    }

    // The toolbar-height field doubles as the group's on/off sentinel: the
    // switch always seeds or clears all ten fields together, so any one of
    // them being set means the group is on.
    val hasLayoutOverrides = look.watch { it.toolbarHeightDp != null }
    SettingsGroup(
        stringResource(R.string.theme_layout_section_title),
        foldKey = "theme/layout",
        info = stringResource(R.string.theme_layout_section_body),
    ) {
        item {
            ToggleSetting(
                R.string.theme_custom_layout_title,
                stringResource(R.string.theme_custom_layout_subtitle),
                checked = hasLayoutOverrides,
            ) { enable ->
                val current = settings.value
                update { t ->
                    if (enable) {
                        t.copy(
                            toolWidthDp = current.toolbarBehavior.toolWidthDp,
                            toolbarHeightDp = current.toolbarHeightDp,
                            // Both styles' heights, not the one the setting is
                            // in: a single seed handed the floating height to
                            // the on-key bubble the moment the style flipped
                            // (#87).
                            popupHeightDp = current.popup.onKeyHeightDp,
                            popupFloatingHeightDp = current.popup.floatingHeightDp,
                            keyHeightDp = current.keyHeightDp,
                            keyGapScale = current.keyGapScale,
                            sidePadScale = current.layoutBehavior.sidePadLeftScale
                                .takeIf { it == current.layoutBehavior.sidePadRightScale },
                            sidePadLeftScale = current.layoutBehavior.sidePadLeftScale,
                            sidePadRightScale = current.layoutBehavior.sidePadRightScale,
                            fontScale = current.fontScale,
                            boldKeyLabels = current.accessibility.boldLabels,
                            hintFontScale = current.layoutBehavior.hintFontScale,
                            gestureTrailWidthDp = current.gesture.trailWidthDp,
                            gestureTrailOpacity = current.gesture.trailOpacity,
                        )
                    } else {
                        t.copy(
                            toolWidthDp = null,
                            toolbarHeightDp = null,
                            popupHeightDp = null,
                            popupFloatingHeightDp = null,
                            keyHeightDp = null,
                            keyGapScale = null,
                            sidePadScale = null,
                            sidePadLeftScale = null,
                            sidePadRightScale = null,
                            fontScale = null,
                            boldKeyLabels = null,
                            hintFontScale = null,
                            gestureTrailWidthDp = null,
                            gestureTrailOpacity = null,
                        )
                    }
                }
            }
        }
        if (hasLayoutOverrides) {
            item {
                SliderRow(
                    R.string.theme_tool_width_title,
                    value = look.watch { it.toolWidthDp ?: 38 }.toFloat(),
                    range = 38f..64f,
                    display = { "${it.toInt()} dp" },
                ) { update { t -> t.copy(toolWidthDp = it.toInt()) } }
            }
            item {
                SliderRow(
                    R.string.theme_toolbar_height_title,
                    value = look.watch { it.toolbarHeightDp ?: 44 }.toFloat(),
                    range = 32f..80f,
                    display = { "${it.toInt()} dp" },
                ) { update { t -> t.copy(toolbarHeightDp = it.toInt()) } }
            }
            if (popupsOn) item {
                // The height of the style in force for this theme — its own
                // placement, else the global one — the way the global slider
                // shows one style's value at a time. Each style keeps its own
                // field, so a height dialled in while the bubble floated is
                // never what the on-key bubble is drawn at (#87).
                val onKey = look.watchWith { s, t -> popupOnKeyOrNull(t.popupPlacement) ?: s.popup.onKey }
                val height = look.watchWith { s, t ->
                    (if (onKey) t.popupHeightDp else t.popupFloatingHeightDp) ?: s.popup.heightFor(onKey)
                }
                SliderRow(
                    R.string.theme_popup_height_title,
                    value = height.toFloat(),
                    range = 32f..160f,
                    display = { "${it.toInt()} dp" },
                ) { value ->
                    update { t ->
                        if (onKey) {
                            t.copy(popupHeightDp = value.toInt())
                        } else {
                            t.copy(popupFloatingHeightDp = value.toInt())
                        }
                    }
                }
            }
            item {
                SliderRow(
                    R.string.theme_key_height_title,
                    value = look.watch { it.keyHeightDp ?: 48 }.toFloat(),
                    range = 32f..100f,
                    display = { "${it.toInt()} dp" },
                ) { update { t -> t.copy(keyHeightDp = it.toInt()) } }
            }
            item {
                SliderRow(
                    R.string.theme_key_gap_title,
                    value = look.watch { it.keyGapScale ?: 1f },
                    range = 0f..2f,
                    display = { "%.2f×".format(it) },
                ) { update { t -> t.copy(keyGapScale = (it * 20).toInt() / 20f) } }
            }
            item {
                SliderRow(
                    R.string.theme_side_padding_left_title,
                    value = look.watch { it.sidePadLeftScale ?: it.sidePadScale ?: 0f },
                    range = SidePadScaleRange,
                    display = { "${(it * 100).toInt()} %" },
                ) { update { t -> t.withSidePad(left = (it * 100).toInt() / 100f) } }
            }
            item {
                SliderRow(
                    R.string.theme_side_padding_right_title,
                    value = look.watch { it.sidePadRightScale ?: it.sidePadScale ?: 0f },
                    range = SidePadScaleRange,
                    display = { "${(it * 100).toInt()} %" },
                ) { update { t -> t.withSidePad(right = (it * 100).toInt() / 100f) } }
            }
            item {
                SliderRow(
                    R.string.theme_font_scale_title,
                    value = look.watch { it.fontScale ?: 1f },
                    range = 0.7f..1.5f,
                    display = { "%.2f×".format(it) },
                ) { update { t -> t.copy(fontScale = (it * 20).toInt() / 20f) } }
            }
            item {
                ToggleSetting(
                    R.string.theme_bold_labels_title,
                    subtitle = null,
                    checked = look.watch { it.boldKeyLabels ?: false },
                ) { bold -> update { t -> t.copy(boldKeyLabels = bold) } }
            }
            item {
                SliderRow(
                    R.string.theme_hint_scale_title,
                    value = look.watch { it.hintFontScale ?: 1f },
                    range = 0.5f..2f,
                    display = { "%.2f×".format(it) },
                ) { update { t -> t.copy(hintFontScale = (it * 20).toInt() / 20f) } }
            }
            item {
                SliderRow(
                    R.string.theme_trail_width_title,
                    value = look.watch { it.gestureTrailWidthDp ?: 10f },
                    range = 2f..24f,
                    display = { "${it.toInt()} dp" },
                ) { update { t -> t.copy(gestureTrailWidthDp = it.toInt().toFloat()) } }
            }
            item {
                SliderRow(
                    R.string.theme_trail_opacity_title,
                    value = look.watch { it.gestureTrailOpacity ?: 0.55f },
                    range = 0.1f..1f,
                    display = { "${(it * 100).toInt()} %" },
                ) { update { t -> t.copy(gestureTrailOpacity = (it * 100).toInt() / 100f) } }
            }
        }
    }

    // Reduce motion zeroes the animation phase and the burst count outright,
    // so neither section can change anything the board draws.
    SettingsGroup(
        stringResource(R.string.theme_animation_section_title),
        foldKey = "theme/animation",
        info = stringResource(R.string.theme_animation_section_body),
    ) {
        if (reduceMotion) return@SettingsGroup
        item {
            ChoiceControl(
                options = ThemeAnimation.entries.map { anim ->
                    anim to when (anim) {
                        ThemeAnimation.NONE -> stringResource(CommonR.string.common_none)
                        ThemeAnimation.FLOW -> stringResource(R.string.theme_animation_flow_label)
                        ThemeAnimation.HUE_CYCLE ->
                            stringResource(R.string.theme_animation_hue_cycle_label)
                    }
                },
                selected = look.watch { it.animation },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                detail = { anim -> ChoiceDetail(stringResource(themeAnimationDescRes(anim))) },
            ) { anim -> update { t -> t.copy(animation = anim) } }
        }
        item(visible = themeAnimates) {
            SliderRow(
                R.string.theme_animation_speed_title,
                value = look.watch { it.animationSpeed },
                range = 0.25f..3f,
                display = { "%.2f×".format(it) },
            ) { update { t -> t.copy(animationSpeed = (it * 20).toInt() / 20f) } }
        }
    }

    val effectImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val (family, theme) = look.now() ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                runCancellable {
                    val path = importEffectImage(context, theme.id, uri)
                    if (path != null) {
                        repository.upsertCustomTheme(
                            family.replacingMember(theme.id) {
                                theme.copy(
                                    keyEffectImages =
                                        (theme.keyEffectImages + path).take(MAX_EFFECT_IMAGES),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    val effectImagesNote = stringResource(R.string.theme_effect_images_body)
        .takeIf { effectKind == KeyEffectKind.CUSTOM_IMAGE }
    SettingsGroup(
        stringResource(R.string.theme_effect_section_title),
        foldKey = "theme/effect",
        info = listOfNotNull(stringResource(R.string.theme_effect_section_body), effectImagesNote)
            .joinToString("\n\n"),
    ) {
        if (reduceMotion) return@SettingsGroup
        item {
            val current = effectKind
            ChoiceControl(
                options = listOf<KeyEffectKind?>(null).plus(KeyEffectKind.entries).map { kind ->
                    kind to when (kind) {
                        null -> stringResource(CommonR.string.common_none)
                        KeyEffectKind.STARS -> stringResource(R.string.theme_effect_stars_label)
                        KeyEffectKind.HEARTS -> stringResource(R.string.theme_effect_hearts_label)
                        KeyEffectKind.SPARKLE ->
                            stringResource(R.string.theme_effect_sparkle_label)
                        KeyEffectKind.CONFETTI ->
                            stringResource(R.string.theme_effect_confetti_label)
                        KeyEffectKind.EMOJI -> stringResource(R.string.theme_effect_emoji_label)
                        KeyEffectKind.CUSTOM_IMAGE ->
                            stringResource(R.string.theme_effect_images_label)
                    }
                },
                selected = current,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { kind -> update { t -> t.copy(keyEffect = kind?.name) } }
        }
        item(visible = effectKind == KeyEffectKind.EMOJI) {
            // Local state is the source of truth while typing: a field
            // bound straight to the async theme write scrambles input when
            // the DataStore emission echoes back mid-edit.
            val storedParam = look.watch { it.keyEffectParam }
            var emojiParam by remember(lookId) {
                mutableStateOf(storedParam.orEmpty())
            }
            OutlinedTextField(
                value = emojiParam,
                onValueChange = { text ->
                    val clipped = text.take(16)
                    emojiParam = clipped
                    update { t -> t.copy(keyEffectParam = clipped) }
                },
                singleLine = true,
                label = { Text(stringResource(R.string.theme_effect_emoji_field_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (effectKind == KeyEffectKind.CUSTOM_IMAGE) {
            effectImages.forEachIndexed { index, path ->
                item {
                    WmRow(
                        title = stringResource(
                            R.string.theme_effect_image_item_label,
                            index + 1,
                        ),
                        leading = { ImageThumb(path) },
                        trailing = {
                            IconButton(
                                onClick = {
                                    update { t ->
                                        File(path).delete()
                                        t.copy(
                                            keyEffectImages =
                                                t.keyEffectImages.filterIndexed { i, _ ->
                                                    i != index
                                                },
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription =
                                        stringResource(CommonR.string.common_remove),
                                )
                            }
                        },
                    )
                }
            }
            item(visible = effectImages.size < MAX_EFFECT_IMAGES) {
                OutlinedButton(
                    onClick = {
                        effectImagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.theme_effect_image_add_action)) }
            }
        }
        if (effectKind != null) {
            item {
                SliderRow(
                    R.string.theme_effect_intensity_title,
                    value = look.watch { it.keyEffectIntensity },
                    range = 0.4f..2.4f,
                    display = { "%.1f×".format(it) },
                ) { update { t -> t.copy(keyEffectIntensity = (it * 10).toInt() / 10f) } }
            }
            item {
                val mode = look.watch { keyEffectColorMode(it.keyEffectColor) }
                val keyText = look.watch { it.keyText }
                val accent = look.watch { it.accent }
                val trail = look.watch { it.gestureTrailColor ?: it.accent }
                val customColour = look.watch { it.keyEffectCustomColor ?: it.accent }
                // A swatch per option, since every one of these is a colour and
                // the words for them ("Trail", "Accent") name where the colour
                // comes from rather than what it looks like on this theme.
                val colourDetail = mapOf(
                    KeyEffectColorMode.NATURAL to
                        ChoiceDetail(stringResource(R.string.theme_effect_color_natural_desc)),
                    KeyEffectColorMode.KEY_TEXT to
                        ChoiceDetail(stringResource(R.string.theme_effect_color_key_text_desc)) {
                            Swatch(keyText)
                        },
                    KeyEffectColorMode.ACCENT to
                        ChoiceDetail(stringResource(R.string.theme_effect_color_accent_desc)) {
                            Swatch(accent)
                        },
                    KeyEffectColorMode.GESTURE_TRAIL to
                        ChoiceDetail(stringResource(R.string.theme_effect_color_trail_desc)) {
                            Swatch(trail)
                        },
                    KeyEffectColorMode.CUSTOM to
                        ChoiceDetail(stringResource(R.string.theme_effect_color_custom_desc)) {
                            Swatch(customColour)
                        },
                    KeyEffectColorMode.RANDOM to
                        ChoiceDetail(stringResource(R.string.theme_effect_color_random_desc)),
                )
                ChoiceSetting(
                    title = R.string.theme_effect_color_title,
                    info = stringResource(R.string.theme_effect_color_body),
                    detail = { colourDetail[it] },
                    options = KeyEffectColorMode.entries.map { option ->
                        option to when (option) {
                            KeyEffectColorMode.NATURAL ->
                                stringResource(R.string.theme_effect_color_natural_label)
                            KeyEffectColorMode.KEY_TEXT ->
                                stringResource(R.string.theme_effect_color_key_text_label)
                            KeyEffectColorMode.ACCENT ->
                                stringResource(R.string.theme_effect_color_accent_label)
                            KeyEffectColorMode.GESTURE_TRAIL ->
                                stringResource(R.string.theme_effect_color_trail_label)
                            KeyEffectColorMode.CUSTOM ->
                                stringResource(R.string.theme_effect_color_custom_label)
                            KeyEffectColorMode.RANDOM ->
                                stringResource(R.string.theme_effect_color_random_label)
                        }
                    },
                    selected = mode,
                ) { picked -> update { t -> t.copy(keyEffectColor = picked.name) } }
            }
            item(visible = effectColourCustom) {
                NullableColorRow(
                    R.string.theme_effect_color_custom_label,
                    look.watch { it.keyEffectCustomColor }, fallback = look.watch { it.accent },
                    onChange = { update { t -> t.copy(keyEffectCustomColor = it) } },
                )
            }
            item {
                SliderRow(
                    R.string.theme_effect_size_title,
                    value = look.watch { it.keyEffectSize },
                    range = EFFECT_SIZE_RANGE,
                    display = { "%.1f×".format(it) },
                ) { update { t -> t.copy(keyEffectSize = (it * 10).toInt() / 10f) } }
            }
            item {
                SliderRow(
                    R.string.theme_effect_speed_title,
                    value = look.watch { it.keyEffectSpeed },
                    range = EFFECT_SPEED_RANGE,
                    display = { "%.1f×".format(it) },
                ) { update { t -> t.copy(keyEffectSpeed = (it * 10).toInt() / 10f) } }
            }
            item {
                SliderRow(
                    R.string.theme_effect_spread_title,
                    value = look.watch { it.keyEffectSpread },
                    range = EFFECT_SPREAD_RANGE,
                    display = { "%d%%".format((it * 100).roundToInt()) },
                ) { update { t -> t.copy(keyEffectSpread = (it * 20).toInt() / 20f) } }
            }
            item {
                SliderRow(
                    R.string.theme_effect_gravity_title,
                    value = look.watch { it.keyEffectGravity },
                    range = EFFECT_GRAVITY_RANGE,
                    display = { "%.1f×".format(it) },
                    info = stringResource(R.string.theme_effect_gravity_body),
                ) { update { t -> t.copy(keyEffectGravity = (it * 10).toInt() / 10f) } }
            }
            item {
                SliderRow(
                    R.string.theme_effect_duration_title,
                    value = look.watch { it.keyEffectDurationMs }.toFloat(),
                    range = EFFECT_DURATION_RANGE.first.toFloat()..
                        EFFECT_DURATION_RANGE.last.toFloat(),
                    display = { "%d ms".format(it.roundToInt()) },
                ) { ms ->
                    update { t -> t.copy(keyEffectDurationMs = (ms / 50).roundToInt() * 50) }
                }
            }
        }
    }

    var fontPickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var soundPickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    var scriptPickerOpen by rememberSaveable(lookId) { mutableStateOf(false) }
    /** `ScriptId.name` of the per-script font row being edited, if any. */
    var scriptFontPicker by rememberSaveable(lookId) { mutableStateOf<String?>(null) }
    SettingsGroup(
        stringResource(R.string.theme_font_sound_section_title),
        foldKey = "theme/font_sound",
        info = stringResource(R.string.theme_font_sound_section_body),
    ) {
        item {
            NavRow(
                R.string.theme_font_title,
                value = look.watch { it.fontId }?.let { KeyboardFonts.displayName(context, it, "") }
                    ?: stringResource(R.string.theme_follow_settings_label),
            ) { fontPickerOpen = true }
        }
        // A theme's own font loses to every non-Latin script's automatic face,
        // so a display theme needs a matching face named per script or it simply
        // stops applying the moment the board switches to Bengali. Only the
        // scripts the theme has an answer for are listed; the rest keep Noto.
        for (script in KeyboardFonts.scriptFontChoices) {
            val key = script.script.name
            val fontId = scriptFontIds[key] ?: continue
            item {
                // The `String` form, because the row is named after a script
                // rather than after a resource of its own. The glyph and the
                // highlight key are handed over by name instead.
                NavRow(
                    title = stringResource(
                        R.string.theme_script_font_title,
                        stringResource(script.labelRes),
                    ),
                    value = KeyboardFonts.displayName(context, fontId, ""),
                    icon = SettingsRowIcons[R.string.theme_script_font_title],
                    highlightKey = R.string.theme_script_font_title,
                ) { scriptFontPicker = key }
            }
        }
        item {
            NavRow(
                R.string.theme_script_font_add_title,
                subtitle = stringResource(R.string.theme_script_font_add_body),
            ) { scriptPickerOpen = true }
        }
        // A theme's sound is resolved past the key-sound gate, so with key
        // sounds off there is nothing for it to replace.
        if (soundOn) item {
            NavRow(
                R.string.theme_sound_title,
                value = themeSoundLabel(look.watch { it.soundStyle }, look.watch { it.soundCustomId }),
            ) { soundPickerOpen = true }
        }
    }
    if (fontPickerOpen) {
        ThemeFontPickerDialog(
            current = look.watch { it.fontId },
            onPick = { id ->
                fontPickerOpen = false
                update { t -> t.copy(fontId = id) }
            },
            onDismiss = { fontPickerOpen = false },
        )
    }
    if (scriptPickerOpen) {
        ThemeScriptPickerDialog(
            taken = scriptFontIds.keys,
            onPick = { script ->
                scriptPickerOpen = false
                scriptFontPicker = script
            },
            onDismiss = { scriptPickerOpen = false },
        )
    }
    scriptFontPicker?.let { script ->
        val choices = KeyboardFonts.scriptFontChoices.firstOrNull { it.script.name == script }
        val label = choices?.let { stringResource(it.labelRes) }.orEmpty()
        ThemeFontPickerDialog(
            script = choices?.script,
            title = stringResource(R.string.theme_script_font_title, label),
            // Clearing this entry is what "follow the script's own font" means:
            // an absent key is the script keeping its automatic Noto face, which
            // is also what an uninstalled font id degrades to.
            defaultLabel = stringResource(R.string.theme_script_font_default_label),
            current = scriptFontIds[script],
            onPick = { id ->
                scriptFontPicker = null
                update { t ->
                    val next = t.scriptFontIds.toMutableMap()
                    if (id == null) next.remove(script) else next[script] = id
                    t.copy(scriptFontIds = next.toMap())
                }
            },
            onDismiss = { scriptFontPicker = null },
        )
    }
    if (soundPickerOpen) {
        ThemeSoundPickerDialog(
            currentStyle = look.watch { it.soundStyle },
            currentCustomId = look.watch { it.soundCustomId },
            onPick = { style, customId ->
                soundPickerOpen = false
                update { t -> t.copy(soundStyle = style, soundCustomId = customId) }
            },
            onDismiss = { soundPickerOpen = false },
        )
    }
    Spacer(Modifier.height(24.dp))
}

/** The sound row's current-value line: a style's name, or "follow settings". */
@Composable
private fun themeSoundLabel(soundStyle: String?, soundCustomId: String?): String {
    val context = LocalContext.current
    val style = soundStyle?.let { name ->
        KeySoundStyle.entries.firstOrNull { it.name == name }
    } ?: return stringResource(R.string.theme_follow_settings_label)
    // A theme names its sound in one field whichever kind it picked, so both
    // styles resolve the same id against their own store — by store id first
    // and then by display name, exactly as the player resolves it at play time.
    // A theme that arrived as an addon can only carry the name: the id is
    // minted per device at install time, so matching on it alone left every
    // distributed theme reading "Custom" here.
    if (style == KeySoundStyle.CUSTOM) {
        val name = remember(soundCustomId) {
            val wanted = soundCustomId.orEmpty()
            SoundStore.get(context).sounds()
                .firstOrNull { it.id == wanted || it.name.equals(wanted, ignoreCase = true) }
                ?.name
        }
        if (name != null) return name
    }
    if (style == KeySoundStyle.PACK) {
        val name = remember(soundCustomId) {
            val wanted = soundCustomId.orEmpty()
            SoundPackStore.get(context).packs()
                .firstOrNull { it.id == wanted || it.name.equals(wanted, ignoreCase = true) }
                ?.name
        }
        if (name != null) return name
    }
    // Custom and Pack are a pointer to an installed sound rather than a sound:
    // with nothing behind the pointer the theme has no sound of its own, which
    // is what it does at play time too ([ThemeSpec.keySound]).
    if (style == KeySoundStyle.CUSTOM || style == KeySoundStyle.PACK) {
        return stringResource(R.string.theme_follow_settings_label)
    }
    return stringResource(keySoundStyleLabelRes(style))
}

@StringRes
private fun keySoundStyleLabelRes(style: KeySoundStyle): Int = when (style) {
    KeySoundStyle.CLICK -> R.string.hardware_sound_style_click_label
    KeySoundStyle.STANDARD -> R.string.hardware_sound_style_standard_label
    KeySoundStyle.POP -> R.string.hardware_sound_style_pop_label
    KeySoundStyle.THOCK -> R.string.hardware_sound_style_thock_label
    KeySoundStyle.CHIME -> R.string.hardware_sound_style_chime_label
    KeySoundStyle.CUSTOM -> CommonR.string.common_custom
    KeySoundStyle.PACK -> R.string.hardware_sound_pack_style_label
}

/**
 * Picks a key font: follow the global setting, a Google face, or a font from the
 * installed library. Every row draws in its own face — the row is the preview,
 * the same trick the Fonts screen uses.
 *
 * Shared with the layout editor, which picks a font for one layout out of the
 * same three sources and against the same font ids, so the two screens offer the
 * same list and cannot drift apart. Which is why this is `internal` rather than
 * private to this file.
 */
@Composable
internal fun ThemeFontPickerDialog(
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.theme_font_title),
    defaultLabel: String = stringResource(R.string.theme_follow_settings_label),
    /**
     * The script this font is being picked for, when it is a per-script row.
     * Narrows both lists to faces that carry the script's glyphs — the whole
     * point of the row is a font that draws it, and the Latin display faces are
     * exactly what it is being picked to escape.
     */
    script: ScriptId? = null,
) {
    val context = LocalContext.current
    // A font that declares no languages makes no claim and stays offered, the
    // same rule the Fonts screen's pickers use.
    val installed = remember(script) {
        FontStore.get(context).textFonts().filter { font ->
            script == null || font.langIds.isEmpty() ||
                font.langIds.any { LanguageRegistry.byId(it).script == script }
        }
    }
    val googleNames = when {
        !PlayServices.hasFontProvider(context) -> emptyList()
        script == null -> KeyboardFonts.googleFonts
        else -> KeyboardFonts.scriptFontChoices(script)?.fonts.orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ThemeFontChoiceRow(
                    label = defaultLabel,
                    family = null,
                    selected = current == null,
                ) { onPick(null) }
                for (font in installed) {
                    val id = FontStore.fontIdFor(font.id)
                    ThemeFontChoiceRow(
                        label = font.name,
                        family = remember(id) { KeyboardFonts.family(context, id) },
                        selected = current == id,
                    ) { onPick(id) }
                }
                for (name in googleNames) {
                    val id = KeyboardFonts.googleId(name)
                    ThemeFontChoiceRow(
                        label = name,
                        family = remember(id) { KeyboardFonts.family(context, id) },
                        selected = current == id,
                    ) { onPick(id) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * Picks which script the next per-script font row is for. Offers the scripts the
 * font system has a curated picker for — the ones with a name to show and more
 * than one face to choose between — minus the ones this theme has already
 * answered, since editing those is what their own row is for.
 */
@Composable
private fun ThemeScriptPickerDialog(
    taken: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val available = KeyboardFonts.scriptFontChoices.filter { it.script.name !in taken }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_script_font_add_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (available.isEmpty()) {
                    Text(stringResource(R.string.theme_script_font_all_set_body))
                }
                for (choices in available) {
                    ThemeFontChoiceRow(
                        label = stringResource(choices.labelRes),
                        family = null,
                        selected = false,
                    ) { onPick(choices.script.name) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun ThemeFontChoiceRow(
    label: String,
    family: FontFamily?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Both lists this row builds — every installed and Google face, every sound
    // style plus every imported clip — are longer than the dialog, so the row
    // that is on brings the dialog to it.
    ScrollAnchor(selected) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        ) {
            Text(
                label,
                fontFamily = family,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Whether a theme's `soundCustomId` names this installed sound or pack.
 *
 * By store id or by display name, the same pair the player accepts: a theme
 * written on this device carries the id, while one that arrived as an addon can
 * only carry the name, because the id is minted per device at install time.
 */
private fun namesSound(themeId: String?, storeId: String, name: String): Boolean {
    val wanted = themeId.orEmpty()
    return wanted == storeId || wanted.equals(name, ignoreCase = true)
}

/**
 * Picks the theme's key sound. Tapping a row previews it right away — a sound
 * has to be heard to be chosen. Installed sounds and packs each get their own
 * row (they are the CUSTOM and PACK styles plus an id under the hood).
 */
@Composable
private fun ThemeSoundPickerDialog(
    currentStyle: String?,
    currentCustomId: String?,
    onPick: (style: String?, customId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val installed = remember { SoundStore.get(context).sounds() }
    val installedPacks = remember { SoundPackStore.get(context).packs() }
    // Custom and Pack are left out of the style rows and offered below as the
    // sounds and packs themselves. Neither is a sound on its own — both are a
    // pointer to something installed — so a row that set the style and named
    // nothing saved a theme that could only ever play the system click, and
    // did it in preference to whatever the user had picked in Settings.
    val styles = KeySoundStyle.entries.filter {
        it != KeySoundStyle.CUSTOM && it != KeySoundStyle.PACK
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_sound_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ThemeFontChoiceRow(
                    label = stringResource(R.string.theme_follow_settings_label),
                    family = null,
                    selected = currentStyle == null,
                ) { onPick(null, null) }
                for (style in styles) {
                    ThemeFontChoiceRow(
                        label = stringResource(keySoundStyleLabelRes(style)),
                        family = null,
                        selected = currentStyle == style.name && currentCustomId == null,
                    ) {
                        KeySoundPlayer.preview(context, style, PREVIEW_SOUND_VOLUME)
                        onPick(style.name, null)
                    }
                }
                for (sound in installed) {
                    ThemeFontChoiceRow(
                        label = sound.name,
                        family = null,
                        selected = currentStyle == KeySoundStyle.CUSTOM.name &&
                            namesSound(currentCustomId, sound.id, sound.name),
                    ) {
                        KeySoundPlayer.preview(
                            context, KeySoundStyle.CUSTOM, PREVIEW_SOUND_VOLUME, sound.id,
                        )
                        onPick(KeySoundStyle.CUSTOM.name, sound.id)
                    }
                }
                // Packs sit beside the sounds rather than behind a "Sound pack"
                // style, for the reason above: the pack *is* the choice. The
                // whole keystroke previews, since a pack that recorded the key
                // coming back up is only half itself on the way down.
                for (pack in installedPacks) {
                    ThemeFontChoiceRow(
                        label = pack.name,
                        family = null,
                        selected = currentStyle == KeySoundStyle.PACK.name &&
                            namesSound(currentCustomId, pack.id, pack.name),
                    ) {
                        KeySoundPlayer.previewStroke(
                            context, KeySoundStyle.PACK, PREVIEW_SOUND_VOLUME, pack.id,
                        )
                        onPick(KeySoundStyle.PACK.name, pack.id)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** Loud enough to judge by, without reading the user's live volume setting. */
private const val PREVIEW_SOUND_VOLUME = 0.6f

/**
 * The five texture slots of the editor's key-texture section, each knowing
 * which [ThemeSpec] field it reads and writes.
 */
private enum class KeyTextureSlot(@StringRes val titleRes: Int, val fileTag: String) {
    NORMAL(R.string.theme_texture_normal_title, "tex"),
    MODIFIER(R.string.theme_texture_modifier_title, "tex_mod"),
    ENTER(R.string.theme_texture_enter_title, "tex_enter"),
    SPACE(R.string.theme_texture_space_title, "tex_space"),
    PRESSED(R.string.theme_texture_pressed_title, "tex_press"),
    POPUP(R.string.theme_texture_popup_title, "tex_popup"),
    ;

    fun pathIn(theme: ThemeSpec): String? = when (this) {
        NORMAL -> theme.keyTexture
        MODIFIER -> theme.keyTextureModifier
        ENTER -> theme.keyTextureEnter
        SPACE -> theme.keyTextureSpace
        PRESSED -> theme.keyTexturePressed
        POPUP -> theme.popupTexture
    }

    fun withPath(theme: ThemeSpec, path: String?): ThemeSpec = when (this) {
        NORMAL -> theme.copy(keyTexture = path)
        MODIFIER -> theme.copy(keyTextureModifier = path)
        ENTER -> theme.copy(keyTextureEnter = path)
        SPACE -> theme.copy(keyTextureSpace = path)
        PRESSED -> theme.copy(keyTexturePressed = path)
        POPUP -> theme.copy(popupTexture = path)
    }
}

/**
 * The special keys the per-key style editor offers, with the id convention the
 * keyboard resolves (`keyOverrideId`): the action's name in uppercase.
 */
private val specialOverrideKeys = listOf(
    "ENTER", "SPACE", "SHIFT", "DELETE", "SYMBOLS", "EMOJI", "LANGUAGESWITCH",
)

/** How an override id reads in the editor list: `A` for a letter, `Enter` for a special. */
private fun keyOverrideDisplayName(id: String): String =
    if (id.length <= 2 && id.none { it.isUpperCase() }) {
        id.uppercase()
    } else {
        id.lowercase().replaceFirstChar { it.uppercase() }
    }

/** Picks which key gets its own style: type a letter, or choose a special key. */
@Composable
private fun AddKeyOverrideDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_key_override_add_action)) },
        text = {
            Column {
                Text(stringResource(R.string.theme_key_override_add_body))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.theme_key_override_letter_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                for (special in specialOverrideKeys) {
                    Text(
                        keyOverrideDisplayName(special),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAdd(special) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(text.trim().lowercase()) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(CommonR.string.common_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * One key's own style: its colours, its texture, the burst it throws and how
 * its label is drawn (issue #107).
 *
 * Every row is nullable and reads "Automatic" until it is set, because the
 * whole point of the sheet is that a key says what it does *differently* — a
 * dialog of filled-in values would be a second theme editor, and a key that
 * silently pinned the theme's current colour would stop following it.
 */
@Composable
private fun KeyOverrideDialog(
    id: String,
    look: OpenLook,
    /** Off while key popups are, which is what hides the two popup colours. */
    popupsShown: Boolean,
    /** Off under reduce motion, which is what hides the press effect. */
    effectsShown: Boolean,
    onChange: (KeyOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val override = look.watch { it.keyOverrides[id] ?: KeyOverride() }
    val theme = look.watch { it }
    var shapePickerOpen by rememberSaveable(id) { mutableStateOf(false) }
    val texturePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCancellable {
                    val path = importKeyOverrideTexture(context, theme.id, id, uri)
                    if (path != null) {
                        override.texture?.let { File(it).delete() }
                        onChange(override.copy(texture = path))
                    }
                }
            }
        }
    }
    if (shapePickerOpen) {
        // The board's radius still decides how round a rounded key is: one
        // number per theme is the shape the slider has, and the shapes that
        // need their own (circle, pill) read no radius at all.
        KeyShapePickerDialog(
            selected = keyShapeKindOrNull(override.shape),
            radiusDp = theme.keyCornerRadiusDp ?: 8,
            title = R.string.theme_key_override_shape_title,
            onAuto = {
                onChange(override.copy(shape = null))
                shapePickerOpen = false
            },
            onPick = {
                onChange(override.copy(shape = it.name))
                shapePickerOpen = false
            },
            onDismiss = { shapePickerOpen = false },
            offerNone = true,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(keyOverrideDisplayName(id)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                NullableColorRow(
                    R.string.theme_key_background_title,
                    override.background, fallback = theme.keyBackground,
                    supportsAlpha = true,
                    onChange = { onChange(override.copy(background = it)) },
                )
                NullableColorRow(
                    R.string.theme_key_text_title,
                    override.text, fallback = theme.keyText,
                    onChange = { onChange(override.copy(text = it)) },
                )
                NullableColorRow(
                    R.string.theme_hint_text_title,
                    override.hint, fallback = theme.hintText ?: theme.keyText,
                    onChange = { onChange(override.copy(hint = it)) },
                )
                NullableColorRow(
                    R.string.theme_key_border_title,
                    override.border, fallback = theme.keyBorderColor ?: theme.keyText,
                    onChange = { onChange(override.copy(border = it)) },
                )
                if (popupsShown) {
                    NullableColorRow(
                        R.string.theme_popup_background_title,
                        override.popupBackground,
                        fallback = theme.popupBackground ?: theme.keyBackground,
                        onChange = { onChange(override.copy(popupBackground = it)) },
                    )
                    NullableColorRow(
                        R.string.theme_popup_text_title,
                        override.popupText, fallback = theme.popupText ?: theme.keyText,
                        onChange = { onChange(override.copy(popupText = it)) },
                    )
                }
                WmRow(
                    title = stringResource(R.string.theme_key_override_texture_title),
                    subtitle = stringResource(
                        if (override.texture != null) {
                            R.string.theme_texture_set_label
                        } else {
                            R.string.theme_key_override_texture_unset
                        },
                    ),
                    leading = { ImageThumb(override.texture) },
                    trailing = {
                        if (override.texture != null) {
                            IconButton(
                                onClick = {
                                    override.texture?.let { File(it).delete() }
                                    onChange(override.copy(texture = null))
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription =
                                        stringResource(CommonR.string.common_remove),
                                )
                            }
                        }
                    },
                    onClick = {
                        texturePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                if (effectsShown) {
                    // Only the drawn kinds: a per-key image set would be a
                    // transport and memory cost out of proportion to the
                    // feature. See KeyOverride.effect.
                    val kinds = KeyEffectKind.entries.filter { it != KeyEffectKind.CUSTOM_IMAGE }
                    ChoiceControl(
                        options = listOf<KeyEffectKind?>(null).plus(kinds).map { kind ->
                            kind to when (kind) {
                                null -> stringResource(CommonR.string.common_auto)
                                KeyEffectKind.STARS ->
                                    stringResource(R.string.theme_effect_stars_label)
                                KeyEffectKind.HEARTS ->
                                    stringResource(R.string.theme_effect_hearts_label)
                                KeyEffectKind.SPARKLE ->
                                    stringResource(R.string.theme_effect_sparkle_label)
                                KeyEffectKind.CONFETTI ->
                                    stringResource(R.string.theme_effect_confetti_label)
                                else -> stringResource(R.string.theme_effect_emoji_label)
                            }
                        },
                        selected = override.effectKind,
                        modifier = Modifier.padding(vertical = 8.dp),
                        label = stringResource(R.string.theme_key_override_effect_label),
                    ) { kind -> onChange(override.copy(effect = kind?.name)) }
                    if (override.effectKind == KeyEffectKind.EMOJI) {
                        // Local state while typing, for the reason the theme's
                        // own emoji field keeps it: the DataStore echo would
                        // scramble the caret mid-edit.
                        var emoji by remember(id) { mutableStateOf(override.effectParam.orEmpty()) }
                        OutlinedTextField(
                            value = emoji,
                            onValueChange = { text ->
                                val clipped = text.take(16)
                                emoji = clipped
                                onChange(override.copy(effectParam = clipped))
                            },
                            singleLine = true,
                            label = {
                                Text(stringResource(R.string.theme_effect_emoji_field_label))
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                }
                WmRow(
                    title = stringResource(R.string.theme_key_override_shape_title),
                    icon = SettingsRowIcons[R.string.theme_key_override_shape_title],
                    subtitle = keyShapeKindOrNull(override.shape)?.let { keyShapeName(it) }
                        ?: stringResource(CommonR.string.common_auto),
                    onClick = { shapePickerOpen = true },
                )
                ChoiceControl(
                    options = listOf(
                        null to stringResource(CommonR.string.common_auto),
                        false to stringResource(CommonR.string.common_off),
                        true to stringResource(CommonR.string.common_on),
                    ),
                    selected = override.bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                    label = stringResource(R.string.theme_key_override_bold_label),
                ) { bold -> onChange(override.copy(bold = bold)) }
                ToggleSetting(
                    title = R.string.theme_key_override_label_size_title,
                    subtitle = override.labelScale?.let { "${(it * 100).toInt()}%" }
                        ?: stringResource(CommonR.string.common_auto),
                    checked = override.labelScale != null,
                ) { on -> onChange(override.copy(labelScale = if (on) 1f else null)) }
                override.labelScale?.let { scale ->
                    SliderRow(
                        R.string.theme_key_override_label_size_title,
                        value = scale,
                        range = KEY_OVERRIDE_LABEL_SCALE_RANGE,
                        display = { "${(it * 100).toInt()}%" },
                    ) { onChange(override.copy(labelScale = (it * 20).toInt() / 20f)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) }
        },
    )
}

/**
 * Copies a picked image into the theme-images folder as one key's texture.
 * The same downscale the class textures get — a key is a few dozen dp either
 * way — with the override's id in the filename so two keys never collide.
 */
private fun importKeyOverrideTexture(
    context: android.content.Context,
    themeId: String,
    overrideId: String,
    uri: android.net.Uri,
): String? {
    val tag = "keytex_" + overrideId.filter { it.isLetterOrDigit() }.ifEmpty { "k" }
    return importThemeImage(context, "${themeId}_$tag", uri)
}

/**
 * One sticker's placement, adjusted over a live preview: the miniature
 * keyboard at the top draws the decal exactly where the sliders put it.
 */
@Composable
private fun DecalDialog(
    look: OpenLook,
    decalId: String,
    onChange: (DecalSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    // The whole look, since the dialog draws it as its preview.
    val theme = look.watch { it }
    val decal = theme.decals.firstOrNull { it.id == decalId } ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_decal_section_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ThemePreview(theme, animatedBadge = false)
                Spacer(Modifier.height(8.dp))
                SliderRow(
                    R.string.theme_decal_x_title,
                    value = decal.x,
                    range = 0f..1f,
                    display = { "${(it * 100).toInt()}%" },
                ) { onChange(decal.copy(x = (it * 100).toInt() / 100f)) }
                SliderRow(
                    R.string.theme_decal_y_title,
                    value = decal.y,
                    range = 0f..1f,
                    display = { "${(it * 100).toInt()}%" },
                ) { onChange(decal.copy(y = (it * 100).toInt() / 100f)) }
                SliderRow(
                    R.string.theme_decal_size_title,
                    value = decal.scale,
                    range = 0.05f..0.8f,
                    display = { "${(it * 100).toInt()}%" },
                ) { onChange(decal.copy(scale = (it * 100).toInt() / 100f)) }
                SliderRow(
                    R.string.theme_decal_rotation_title,
                    value = decal.rotationDeg,
                    range = -180f..180f,
                    display = { "${it.toInt()}°" },
                ) { onChange(decal.copy(rotationDeg = it.toInt().toFloat())) }
                SliderRow(
                    R.string.theme_image_opacity_title,
                    value = decal.opacity,
                    range = 0.1f..1f,
                    display = { "${(it * 100).toInt()}%" },
                ) { onChange(decal.copy(opacity = (it * 100).toInt() / 100f)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) }
        },
    )
}

/**
 * Copies a picked sticker into the theme-images folder, downscaled like a key
 * texture and kept as PNG so its transparency survives.
 */
private fun importDecalImage(
    context: android.content.Context,
    themeId: String,
    decalId: String,
    uri: android.net.Uri,
): String? = runCatching {
    val source = context.contentResolver.requireInputStream(uri).use { input ->
        android.graphics.BitmapFactory.decodeStream(input)
    } ?: return null
    val longest = maxOf(source.width, source.height)
    val scaled = if (longest > KEY_TEXTURE_IMPORT_PX) {
        val scale = KEY_TEXTURE_IMPORT_PX.toFloat() / longest
        android.graphics.Bitmap.createScaledBitmap(
            source,
            maxOf(1, (source.width * scale).toInt()),
            maxOf(1, (source.height * scale).toInt()),
            true,
        )
    } else {
        source
    }
    val file = File(themeImagesDir(context), "${themeId}_decal_$decalId.img")
    file.outputStream().use { out ->
        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    }
    file.absolutePath
}.getOrNull()

/** Longest edge a press-effect particle image is stored at. */
private const val EFFECT_IMAGE_IMPORT_PX = 192

/**
 * Copies a picked particle image into the theme-images folder, small — a
 * particle is a few dozen dp — and as PNG so its transparency survives.
 */
private fun importEffectImage(
    context: android.content.Context,
    themeId: String,
    uri: android.net.Uri,
): String? = runCatching {
    val source = context.contentResolver.requireInputStream(uri).use { input ->
        android.graphics.BitmapFactory.decodeStream(input)
    } ?: return null
    val longest = maxOf(source.width, source.height)
    val scaled = if (longest > EFFECT_IMAGE_IMPORT_PX) {
        val scale = EFFECT_IMAGE_IMPORT_PX.toFloat() / longest
        android.graphics.Bitmap.createScaledBitmap(
            source,
            maxOf(1, (source.width * scale).toInt()),
            maxOf(1, (source.height * scale).toInt()),
            true,
        )
    } else {
        source
    }
    val file = File(
        themeImagesDir(context),
        "${themeId}_fx_${System.currentTimeMillis()}.img",
    )
    file.outputStream().use { out ->
        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    }
    file.absolutePath
}.getOrNull()

/** Longest edge a texture is stored at. A key never draws bigger than this. */
private const val KEY_TEXTURE_IMPORT_PX = 512

/**
 * Copies a picked texture into the theme-images folder, downscaled to at most
 * [KEY_TEXTURE_IMPORT_PX] on its longest edge — a camera photo per key would
 * bloat every export for pixels no key can show. Compressed as PNG so pixel
 * art (the most likely texture) and alpha both survive. Null when the image
 * cannot be read.
 */
private fun importKeyTexture(
    context: android.content.Context,
    themeId: String,
    slot: KeyTextureSlot,
    uri: android.net.Uri,
): String? = importThemeImage(context, "${themeId}_${slot.fileTag}", uri)

/**
 * Copies a picked image into the theme-images folder, downscaled to
 * [KEY_TEXTURE_IMPORT_PX] on its longest edge and kept as PNG so transparency
 * survives. [namePrefix] names the file; the timestamp after it is what keeps
 * a replacement from being read out of the bitmap cache.
 */
private fun importThemeImage(
    context: android.content.Context,
    namePrefix: String,
    uri: android.net.Uri,
): String? = runCatching {
    val source = context.contentResolver.requireInputStream(uri).use { input ->
        android.graphics.BitmapFactory.decodeStream(input)
    } ?: return null
    val longest = maxOf(source.width, source.height)
    val scaled = if (longest > KEY_TEXTURE_IMPORT_PX) {
        val scale = KEY_TEXTURE_IMPORT_PX.toFloat() / longest
        android.graphics.Bitmap.createScaledBitmap(
            source,
            maxOf(1, (source.width * scale).toInt()),
            maxOf(1, (source.height * scale).toInt()),
            true,
        )
    } else {
        source
    }
    val file = File(
        themeImagesDir(context),
        "${namePrefix}_${System.currentTimeMillis()}.img",
    )
    file.outputStream().use { out ->
        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    }
    file.absolutePath
}.getOrNull()

/** Opens a credit link in the browser. Failure is not worth a message. */
private fun openLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
    }
}

// ---- gradient editor ----

/**
 * Toggleable gradient block: on/off switch, gradient type, angle, and 2–4
 * color stops (each with alpha). A live strip previews the result.
 */
@Composable
private fun GradientEditor(
    @StringRes title: Int,
    subtitle: String,
    gradient: GradientSpec?,
    defaultGradient: GradientSpec,
    onChange: (GradientSpec?) -> Unit,
) {
    ToggleSetting(title = title, subtitle = subtitle, checked = gradient != null) { on ->
        onChange(if (on) defaultGradient else null)
    }
    if (gradient == null) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(gradient.brush()),
    )
    ChoiceControl(
        options = GradientType.entries.map { type ->
            type to when (type) {
                GradientType.LINEAR -> stringResource(R.string.theme_gradient_linear_label)
                GradientType.RADIAL -> stringResource(R.string.theme_gradient_radial_label)
                GradientType.SWEEP -> stringResource(R.string.theme_gradient_sweep_label)
            }
        },
        selected = gradient.type,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        label = stringResource(R.string.theme_gradient_type_label),
        detail = { type -> ChoiceDetail(stringResource(gradientTypeDescRes(type))) },
    ) { type -> onChange(gradient.copy(type = type)) }
    if (gradient.type != GradientType.RADIAL) {
        SliderRow(
            R.string.theme_gradient_angle_title,
            value = gradient.angleDeg,
            range = 0f..360f,
            display = { "${it.toInt()}°" },
        ) { onChange(gradient.copy(angleDeg = it.toInt().toFloat())) }
    }
    gradient.colors.forEachIndexed { index, stop ->
        val stopTitle = stringResource(R.string.theme_gradient_color_title, index + 1)
        ColorRow(
            stopTitle,
            stop,
            supportsAlpha = true,
            icon = SettingsRowIcons[R.string.theme_gradient_color_title],
        ) { picked ->
            onChange(
                gradient.copy(
                    colors = gradient.colors.toMutableList().also { it[index] = picked },
                )
            )
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (gradient.colors.size < 4) {
            TextButton(onClick = {
                onChange(gradient.copy(colors = gradient.colors + gradient.colors.last()))
            }) { Text(stringResource(R.string.theme_gradient_add_color_action)) }
        }
        if (gradient.colors.size > 2) {
            TextButton(onClick = {
                onChange(gradient.copy(colors = gradient.colors.dropLast(1)))
            }) { Text(stringResource(R.string.theme_gradient_delete_color_action)) }
        }
    }
}

// ---- image cropper ----

/**
 * Pinch/drag cropper. The frame is the crop; the image pans and zooms under
 * it (cover-scaled, so the frame is always filled). Confirming maps the
 * frame back into bitmap coordinates and writes a new image file.
 */
@Composable
private fun CropImageDialog(
    path: String,
    onCropped: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Downsample huge photos; a keyboard background never needs
                // more than ~2048 px on a side.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 2048 || bounds.outHeight / (sample * 2) >= 2048) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    var aspect by rememberSaveable { mutableFloatStateOf(2.4f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }

    fun coverScale(bmp: android.graphics.Bitmap): Float =
        if (frame == IntSize.Zero) 1f
        else max(frame.width / bmp.width.toFloat(), frame.height / bmp.height.toFloat())

    fun clampOffset(o: Offset, bmp: android.graphics.Bitmap, z: Float): Offset {
        val s = coverScale(bmp) * z
        val maxX = ((bmp.width * s - frame.width) / 2f).coerceAtLeast(0f)
        val maxY = ((bmp.height * s - frame.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_crop_image_title)) },
        text = {
            Column {
                val bmp = bitmap
                // Free resolves to the source image's own ratio, so the
                // crop frame matches the picture and nothing is cut.
                val effectiveAspect = if (aspect == FREE_CROP_ASPECT && bmp != null) {
                    bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
                } else if (aspect == FREE_CROP_ASPECT) {
                    2.4f
                } else {
                    aspect
                }
                if (bmp == null) {
                    Text(
                        stringResource(CommonR.string.common_loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(effectiveAspect)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .onGloballyPositioned {
                                if (frame != it.size) {
                                    frame = it.size
                                    offset = clampOffset(offset, bmp, zoom)
                                }
                            }
                            .pointerInput(bmp, effectiveAspect) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                    offset = clampOffset(offset + pan, bmp, zoom)
                                }
                            },
                    ) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val s = coverScale(bmp) * zoom
                            val w = (bmp.width * s).roundToInt()
                            val h = (bmp.height * s).roundToInt()
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(
                                    ((size.width - w) / 2f + offset.x).roundToInt(),
                                    ((size.height - h) / 2f + offset.y).roundToInt(),
                                ),
                                dstSize = IntSize(w, h),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ChoiceControl(
                        options = listOf(
                            2.4f to stringResource(R.string.theme_crop_ratio_keyboard_label),
                            1.7f to stringResource(R.string.theme_crop_ratio_wide_label),
                            1f to stringResource(R.string.theme_crop_ratio_square_label),
                            // A split or a floating keyboard is none of the
                            // three, so every preset cropped away something the
                            // user wanted. Free keeps the picture's own shape.
                            FREE_CROP_ASPECT to stringResource(R.string.theme_crop_ratio_free_label),
                        ),
                        selected = aspect,
                        label = stringResource(R.string.theme_crop_ratio_label),
                        detail = { ratio ->
                            ChoiceDetail(
                                icon = when (ratio) {
                                    FREE_CROP_ASPECT -> Icons.Outlined.CropFree
                                    1f -> Icons.Outlined.CropSquare
                                    2.4f -> Icons.Outlined.Keyboard
                                    else -> Icons.Outlined.Crop169
                                },
                            )
                        },
                    ) { value ->
                        aspect = value
                        zoom = 1f
                        offset = Offset.Zero
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bitmap != null && frame != IntSize.Zero && !saving,
                onClick = {
                    val bmp = bitmap ?: return@TextButton
                    saving = true
                    scope.launch(Dispatchers.IO) {
                        val saved = runCatching {
                            val s = coverScale(bmp) * zoom
                            val srcW = (frame.width / s).roundToInt().coerceIn(1, bmp.width)
                            val srcH = (frame.height / s).roundToInt().coerceIn(1, bmp.height)
                            val srcLeft = ((bmp.width - srcW) / 2f - offset.x / s)
                                .roundToInt().coerceIn(0, bmp.width - srcW)
                            val srcTop = ((bmp.height - srcH) / 2f - offset.y / s)
                                .roundToInt().coerceIn(0, bmp.height - srcH)
                            val cropped = android.graphics.Bitmap.createBitmap(bmp, srcLeft, srcTop, srcW, srcH)
                            val file = File(themeImagesDir(context), "crop_${System.currentTimeMillis()}.img")
                            file.outputStream().use { out ->
                                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                            }
                            if (cropped !== bmp) cropped.recycle()
                            file.absolutePath
                        }.getOrNull()
                        withContext(Dispatchers.Main) {
                            saving = false
                            if (saved != null) onCropped(saved) else onDismiss()
                        }
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (saving) R.string.theme_crop_saving_progress else R.string.theme_crop_action
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

// ---- small building blocks ----

/** Shared with the sticker editor, which needs the same throttled slider. */
@Composable
internal fun SliderRow(
    @StringRes title: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    info: String? = null,
    onChange: (Float) -> Unit,
) = SliderRow(stringResource(title), value, range, display, info, SettingsRowIcons[title], onChange)

/** [SliderRow] for a title that is not one fixed resource. */
@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    info: String? = null,
    icon: ImageVector? = null,
    onChange: (Float) -> Unit,
) {
    // One of the few sliders that keeps writing while the finger is down
    // (`live`): this row's own screen draws the theme it is editing, so the
    // preview above it has to follow the thumb. See rememberLiveSlider.
    SliderSetting(
        title = title,
        value = value,
        range = range,
        display = display,
        info = info,
        icon = icon,
        live = true,
        onChange = onChange,
    )
}

/** A required color: tap the swatch to edit. */
@Composable
private fun ColorRow(
    @StringRes title: Int,
    color: Long,
    supportsAlpha: Boolean = false,
    info: String? = null,
    onChange: (Long) -> Unit,
) = ColorRow(stringResource(title), color, supportsAlpha, info, SettingsRowIcons[title], onChange)

@Composable
private fun ColorRow(
    title: String,
    color: Long,
    supportsAlpha: Boolean = false,
    info: String? = null,
    icon: ImageVector? = null,
    onChange: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    WmRow(
        title = title,
        icon = icon,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (info != null) InfoButton(title = title, detail = info)
                Swatch(color)
            }
        },
        onClick = { open = true },
    )
    if (open) {
        ColorPickerDialog(
            title = title,
            initial = color,
            supportsAlpha = supportsAlpha,
            showReset = false,
            onPick = { onChange(it); open = false },
            onReset = {},
            onDismiss = { open = false },
        )
    }
}

/** An optional color: shows the derived fallback until overridden; resettable. */
@Composable
private fun NullableColorRow(
    @StringRes title: Int,
    color: Long?,
    fallback: Long,
    supportsAlpha: Boolean = false,
    info: String? = null,
    onChange: (Long?) -> Unit,
) = NullableColorRow(stringResource(title), color, fallback, supportsAlpha, info, SettingsRowIcons[title], onChange)

@Composable
// detekt (1.23, K1 frontend) reads `color ?: fallback` in this @Composable as
// having an unreachable right-hand side. `color` is a nullable parameter with no
// preceding narrowing, and the Kotlin 2.2 compiler reports nothing here.
@Suppress("UnreachableCode")
private fun NullableColorRow(
    title: String,
    color: Long?,
    fallback: Long,
    supportsAlpha: Boolean = false,
    info: String? = null,
    icon: ImageVector? = null,
    onChange: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    WmRow(
        title = title,
        icon = icon,
        supporting = if (color == null) {
            {
                Text(
                    stringResource(CommonR.string.common_auto),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            null
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (info != null) InfoButton(title = title, detail = info)
                Swatch(color ?: fallback)
            }
        },
        onClick = { open = true },
    )
    if (open) {
        ColorPickerDialog(
            title = title,
            initial = color ?: fallback,
            supportsAlpha = supportsAlpha,
            showReset = color != null,
            onPick = { onChange(it); open = false },
            onReset = { onChange(null); open = false },
            onDismiss = { open = false },
        )
    }
}

// The color picker dialog and its Swatch live in ColorPicker.kt.

/**
 * [SectionHeader] with the theme screens' own inset: their cards are not
 * inside a settings group, so the heading sits at the page margin.
 */
@Composable
fun SectionHeaderPublic(text: String, info: String? = null) {
    SectionHeader(
        text,
        info = info,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/**
 * Where a background image comes from.
 *
 * The row used to open the device photo picker outright. Now that a photo can
 * also come from a service or from the saved library, the row asks first.
 */
@Composable
private fun BackgroundSourceDialog(
    hasImage: Boolean,
    onDevice: () -> Unit,
    onOnline: () -> Unit,
    onSaved: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_background_source_title)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.photo_add_device_title)) },
                    supportingContent = { Text(stringResource(R.string.photo_add_device_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                    colors = transparentListColors(),
                    modifier = Modifier.clickable(onClick = onDevice),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.photo_find_title)) },
                    supportingContent = { Text(stringResource(R.string.photo_find_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    colors = transparentListColors(),
                    modifier = Modifier.clickable(onClick = onOnline),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.photo_library_title)) },
                    supportingContent = { Text(stringResource(R.string.photo_library_subtitle)) },
                    leadingContent = { Icon(Icons.Outlined.Collections, contentDescription = null) },
                    colors = transparentListColors(),
                    modifier = Modifier.clickable(onClick = onSaved),
                )
                if (hasImage) {
                    ListItem(
                        headlineContent = { Text(stringResource(CommonR.string.common_remove)) },
                        leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        colors = transparentListColors(),
                        modifier = Modifier.clickable(onClick = onRemove),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
