package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TranslateEngine
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.translate.OfflineModelState
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateLanguages
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.core.translate.TranslateModuleState
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.TranslateUi

/**
 * The translate panel's service callbacks, bundled into one [KeyboardScreen]
 * parameter for the reason [ConverterCallbacks] is: its caller sits against
 * the JVM's 64K method-size ceiling, where every added parameter costs
 * bytecode. This bundle replaced three parameters with one.
 */
data class TranslateCallbacks(
    val onTarget: (String) -> Unit = {},
    /** A picker code, or "" for "detect the language". */
    val onSource: (String) -> Unit = {},
    val onEngine: (TranslateEngine) -> Unit = {},
    val onSwap: () -> Unit = {},
    /** Fetches the models the query is waiting for; cancels while they are coming. */
    val onDownload: () -> Unit = {},
    val onReplace: () -> Unit = {},
    val onInsert: () -> Unit = {},
)

/** How tall the panel stands while its text is typed into, keys underneath. */
internal val TranslateCompactHeight = 260.dp

/** The most the text box grows before it scrolls. */
private val TranslateQueryMaxHeight = 96.dp

/** Which of the header's menus is open. One at a time: they share the ring's RESULTS region. */
private enum class TranslateMenu { SOURCE, TARGET, ENGINE }

/** Key of the menu row that shows the languages the short list left out. */
private const val ALL_LANGUAGES_KEY = "\u0000all"

/** One row of a header menu. */
private data class TranslateMenuRow(
    val key: String,
    val label: String,
    val selected: Boolean,
    /** A second line under the label, for the engine menu. */
    val info: String? = null,
    /** A trailing word, for a language the on-device engine does not have. */
    val note: String? = null,
    /** Drawn after the label: the language's model is on the device. */
    val downloaded: Boolean = false,
    val dimmed: Boolean = false,
)

private val TranslateEngine.icon: ImageVector
    get() = when (this) {
        TranslateEngine.ONLINE -> Icons.Outlined.Cloud
        TranslateEngine.ON_DEVICE -> Icons.Outlined.PhoneAndroid
        TranslateEngine.AUTO -> Icons.Outlined.AutoMode
    }

/**
 * Which of the header's menus is open, shared by the panel's two halves: on a
 * short landscape screen the language chips sit in the tool's header and the
 * text in the body, and the ring's RESULTS region is the open menu's rows.
 */
@Stable
private class TranslateMenus {
    private var openMenu by mutableStateOf<TranslateMenu?>(null)

    /** "All languages" opens the rest of one menu, once: the next menu starts short again. */
    var showAll by mutableStateOf(false)

    var open: TranslateMenu?
        get() = openMenu
        set(value) {
            if (value != openMenu) showAll = false
            openMenu = value
        }
}

/**
 * The Translate tool: [TranslatePanel] in its [FullBleedTool] chrome.
 *
 * Upright, the panel stacks its text, its language chips, the result and its
 * actions. On a [shortLandscape] screen, where the panel over the keys is a
 * strip a line or two tall, that stack showed the text box and nothing under
 * it (issue #370), so the chips and actions move up into the header, which
 * has the width for them, and the text and its translation sit side by side.
 */
@Composable
internal fun TranslateTool(
    state: KeyboardUiState,
    callbacks: TranslateCallbacks,
    onClose: () -> Unit,
    /** Starts typing into the text box: the media-search key reroute. */
    onQueryTap: () -> Unit,
) {
    val menus = remember { TranslateMenus() }
    val sideBySide = shortLandscape()
    FullBleedTool(
        state,
        title = if (sideBySide) "" else stringResource(R.string.ime_tool_translate),
        onClose = onClose,
        compact = state.mediaSearchActive,
        // Translations run long, and the text being translated is a box of
        // several lines in the panel itself rather than the header's one-line
        // search bar: room for both over the keys.
        compactHeight = TranslateCompactHeight,
        headerActions = if (sideBySide) {
            {
                TranslateLanguageRow(state, callbacks, menus, Modifier.weight(1f))
                TranslateActionRow(
                    state,
                    callbacks,
                    Modifier.padding(start = 8.dp),
                    actionModifier = Modifier.widthIn(min = TranslateHeaderActionMinWidth),
                )
            }
        } else {
            null
        },
    ) {
        TranslatePanel(state, callbacks, onQueryTap, menus, sideBySide)
    }
}

/** How narrow Replace and Insert may get when they share the header with the chips. */
private val TranslateHeaderActionMinWidth = 96.dp

/**
 * Translation window: the query types into the panel's own search bar
 * (media-search key rerouting — the focused field is never read) and the
 * result follows live. Insert types the translation at the cursor; Replace
 * swaps the whole field for it.
 *
 * The header reads left to right as the sentence it is: *from* this language,
 * *to* that one, *using* this engine. The source chip starts on "detect" and
 * shows what was detected; the engine chip is only there in a build that has
 * an on-device engine to choose. When that engine needs a model it does not
 * have, the result area becomes the offer to download it, and the action row
 * becomes the button.
 *
 * [sideBySide] leaves the chips and the actions to the tool's header and puts
 * the text beside the result; see [TranslateTool].
 */
@Composable
private fun TranslatePanel(
    state: KeyboardUiState,
    callbacks: TranslateCallbacks,
    onQueryTap: () -> Unit,
    menus: TranslateMenus,
    sideBySide: Boolean,
) {
    PanelFocusTarget(
        panel = PanelMode.TRANSLATE,
        region = FocusRegion.SEARCH,
        count = 1,
        columns = 1,
        onActivate = { onQueryTap() },
    )
    val queryFocused = state.focusedIndex(FocusRegion.SEARCH) == 0
    // The panel is its own translation window: the text types into the box
    // at its top (field text is never read). The FullBleedTool wrapper
    // collapses the panel while typing — the keys sit right below and the
    // live result still fits above them.
    if (sideBySide) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TranslateQueryBox(
                state = state,
                focused = queryFocused,
                onQueryTap = onQueryTap,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            TranslateResult(
                state,
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
    ) {
        TranslateQueryBox(
            state = state,
            focused = queryFocused,
            onQueryTap = onQueryTap,
            modifier = Modifier.fillMaxWidth(),
        )
        TranslateLanguageRow(state, callbacks, menus)
        TranslateResult(
            state,
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        TranslateActionRow(
            state,
            callbacks,
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            actionModifier = Modifier.weight(1f),
        )
    }
}

/** Whether the on-device engine is short of something, so the result area offers the download. */
private fun TranslateUi.offersDownload(): Boolean = missingModels.isNotEmpty() || moduleMissing

/**
 * The language chips — from, swap, to, and the engine — with their menus, and
 * the ring's CHIPS and RESULTS regions that walk them.
 */
@Composable
private fun TranslateLanguageRow(
    state: KeyboardUiState,
    callbacks: TranslateCallbacks,
    menus: TranslateMenus,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val translate = state.translate
    val target = state.settings.translateTargetLang
    val engine = if (OnDeviceTranslator.AVAILABLE) state.settings.translate.engine else TranslateEngine.ONLINE
    val menu = menus.open
    val rows = menu?.let { translateMenuRows(it, translate, target, engine, state.settings, menus.showAll) }.orEmpty()
    val pick: (TranslateMenu, String) -> Unit = { which, key ->
        if (key == ALL_LANGUAGES_KEY) {
            menus.showAll = true
        } else {
            menus.open = null
            when (which) {
                TranslateMenu.SOURCE -> callbacks.onSource(key)
                TranslateMenu.TARGET -> callbacks.onTarget(key)
                TranslateMenu.ENGINE ->
                    TranslateEngine.entries.firstOrNull { it.name == key }?.let(callbacks.onEngine)
            }
        }
    }

    // The ring's regions. CHIPS is the header, in reading order; RESULTS is
    // the open menu's rows (zero rows closed, so Tab skips it); ACTIONS is
    // Replace/Insert (see [TranslateActionRow]).
    // Known v1 gap: Esc while a menu Popup is open closes the whole panel
    // (the open flag is composable-local, invisible to the service); Enter
    // on the CHIPS region reopens it cheaply.
    val headerChips = buildList {
        add(TranslateMenu.SOURCE)
        add(null) // swap
        add(TranslateMenu.TARGET)
        if (OnDeviceTranslator.AVAILABLE) add(TranslateMenu.ENGINE)
    }
    PanelFocusTarget(
        panel = PanelMode.TRANSLATE,
        region = FocusRegion.CHIPS,
        count = headerChips.size,
        columns = headerChips.size,
        onActivate = { index ->
            val opens = headerChips.getOrNull(index)
            if (opens != null) menus.open = opens else callbacks.onSwap()
        },
    )
    PanelFocusTarget(
        panel = PanelMode.TRANSLATE,
        region = FocusRegion.RESULTS,
        count = rows.size,
        columns = 1,
        onActivate = { index ->
            val open = menus.open
            val row = rows.getOrNull(index)
            if (open != null && row != null && !row.dimmed) pick(open, row.key)
        },
    )

    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            TranslateChip(
                label = sourceLabel(translate),
                strong = translate.sourceOverride.isNotBlank(),
                focused = focusedChip == headerChips.indexOf(TranslateMenu.SOURCE),
                description = stringResource(R.string.ime_translate_select_source_desc),
            ) { menus.open = TranslateMenu.SOURCE }
            if (menu == TranslateMenu.SOURCE) {
                TranslateMenuPopup(rows, focusedRow, { pick(TranslateMenu.SOURCE, it) }) { menus.open = null }
            }
        }
        val canSwap = translate.translated.isNotEmpty() &&
            TranslateClient.pickerCode(translate.detectedSource)
                ?.equals(target, ignoreCase = true) == false
        Icon(
            Icons.Outlined.SwapHoriz,
            contentDescription = stringResource(R.string.ime_translate_swap_desc),
            tint = if (canSwap) kb.toolbarIcon else kb.secondaryText.copy(alpha = 0.4f),
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .clip(kb.chipShape())
                .focusRing(focusedChip == 1, kb.chipShape())
                .clickable(enabled = canSwap) { callbacks.onSwap() }
                .padding(4.dp)
                .size(18.dp),
        )
        Box(modifier = Modifier.weight(1f, fill = false)) {
            TranslateChip(
                label = TranslateClient.languageName(target),
                strong = true,
                focused = focusedChip == headerChips.indexOf(TranslateMenu.TARGET),
                description = stringResource(R.string.ime_translate_select_language_desc),
            ) { menus.open = TranslateMenu.TARGET }
            if (menu == TranslateMenu.TARGET) {
                TranslateMenuPopup(rows, focusedRow, { pick(TranslateMenu.TARGET, it) }) { menus.open = null }
            }
        }
        Spacer(Modifier.weight(1f))
        // Says which service answered only when it is the one the user
        // brought (#331): DeepL falls back to the usual service for a
        // language it does not have, and that switch should not be silent.
        if (translate.viaDeepL && translate.translated.isNotEmpty()) {
            Text(
                stringResource(R.string.ime_translate_deepl_label),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (translate.translating) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(14.dp),
                strokeWidth = 2.dp,
                color = kb.accent,
            )
        }
        if (OnDeviceTranslator.AVAILABLE) {
            Box {
                TranslateChip(
                    label = stringResource(engine.labelRes),
                    strong = false,
                    focused = focusedChip == headerChips.indexOf(TranslateMenu.ENGINE),
                    description = stringResource(R.string.ime_translate_select_engine_desc),
                    // On Automatic the icon answers "which one was it this
                    // time?", which is the only thing the mode leaves open.
                    icon = when {
                        engine != TranslateEngine.AUTO || translate.translated.isEmpty() -> engine.icon
                        translate.onDevice -> TranslateEngine.ON_DEVICE.icon
                        else -> TranslateEngine.ONLINE.icon
                    },
                ) { menus.open = TranslateMenu.ENGINE }
                if (menu == TranslateMenu.ENGINE) {
                    TranslateMenuPopup(rows, focusedRow, { pick(TranslateMenu.ENGINE, it) }) { menus.open = null }
                }
            }
        }
    }
}

/** The translation, the error in its place, or the offer of the models it needs. */
@Composable
private fun TranslateResult(state: KeyboardUiState, modifier: Modifier) {
    val kb = LocalKbTheme.current
    val translate = state.translate
    if (translate.offersDownload()) {
        TranslateDownloadOffer(translate = translate, modifier = modifier)
        return
    }
    Text(
        text = when {
            translate.error != null -> translate.error
            translate.translated.isNotEmpty() -> translate.translated
            translate.translating -> stringResource(R.string.ime_translate_progress)
            else -> stringResource(R.string.ime_translate_idle)
        },
        color = when {
            translate.error != null -> kb.accent
            translate.translated.isEmpty() -> kb.secondaryText
            else -> kb.suggestionText
        },
        fontSize = if (state.mediaSearchActive) 14.sp else 16.sp,
        modifier = modifier
            .padding(vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
    )
}

/**
 * Replace and Insert, or the one download button while that is the offer,
 * with the ring's ACTIONS region over them. [actionModifier] sizes each button:
 * a share of the row under the result, a minimum width in the header.
 */
@Composable
private fun TranslateActionRow(
    state: KeyboardUiState,
    callbacks: TranslateCallbacks,
    modifier: Modifier,
    actionModifier: Modifier,
) {
    val kb = LocalKbTheme.current
    val translate = state.translate
    val offerDownload = translate.offersDownload()
    PanelFocusTarget(
        panel = PanelMode.TRANSLATE,
        region = FocusRegion.ACTIONS,
        count = if (offerDownload) 1 else 2,
        columns = if (offerDownload) 1 else 2,
        onActivate = { index ->
            when {
                offerDownload -> callbacks.onDownload()
                translate.translated.isEmpty() -> Unit
                index == 0 -> callbacks.onReplace()
                else -> callbacks.onInsert()
            }
        },
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
        if (offerDownload) {
            val downloading = translate.missingModels.any { translate.models[it] is OfflineModelState.Downloading }
            // Play cancels a module by session and the panel holds none,
            // so while the module is on its way the button only reports.
            val moduleComing = translate.moduleMissing && translate.module is TranslateModuleState.Installing
            TranslateAction(
                label = stringResource(
                    when {
                        moduleComing -> R.string.ime_translate_module_installing_action
                        downloading -> CommonR.string.common_cancel
                        translate.meteredAsk -> R.string.ime_metered_allow_action
                        else -> CommonR.string.common_download
                    },
                ),
                icon = if (downloading || moduleComing) null else Icons.Outlined.Download,
                enabled = !moduleComing,
                modifier = actionModifier.focusRing(focusedAction == 0, kb.chipShape()),
            ) { callbacks.onDownload() }
        } else {
            TranslateAction(
                label = stringResource(R.string.ime_translate_replace_action),
                icon = Icons.Outlined.SwapVert,
                enabled = translate.translated.isNotEmpty(),
                modifier = actionModifier.focusRing(focusedAction == 0, kb.chipShape()),
            ) { callbacks.onReplace() }
            TranslateAction(
                label = stringResource(R.string.ime_insert_action),
                icon = null,
                enabled = translate.translated.isNotEmpty(),
                modifier = actionModifier.focusRing(focusedAction == 1, kb.chipShape()),
            ) { callbacks.onInsert() }
        }
    }
}

/**
 * The text being translated: a box of several lines, where the header's search
 * bar held one. While it has the keys it is an editor of the capture ladder's
 * buffer, caret and all, the same as the AI chat's composer; otherwise a tap
 * gives it the keys.
 */
@Composable
private fun TranslateQueryBox(
    state: KeyboardUiState,
    focused: Boolean,
    onQueryTap: () -> Unit,
    modifier: Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val hint = stringResource(R.string.ime_translate_hint)
    Box(
        modifier = modifier
            .padding(top = 2.dp, bottom = 6.dp)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(enabled = !state.mediaSearchActive) { onQueryTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (state.mediaSearchActive) {
            ClipEditText(
                text = state.mediaQuery,
                placeholder = hint,
                textColor = kb.chipText,
                placeholderColor = kb.secondaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp, max = TranslateQueryMaxHeight),
            )
        } else {
            Text(
                state.mediaQuery.ifEmpty { hint },
                color = if (state.mediaQuery.isEmpty()) kb.secondaryText else kb.chipText,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What the source chip says: the user's pick, or what was detected, or the
 * invitation to let it detect. A guess off the keyboard's language carries a
 * "?", because that is what it is, and tapping the chip is how to correct it.
 */
@Composable
private fun sourceLabel(translate: TranslateUi): String = when {
    translate.sourceOverride.isNotBlank() -> TranslateClient.languageName(translate.sourceOverride)
    translate.detectedSource.isBlank() ||
        (translate.translated.isEmpty() && translate.missingModels.isEmpty()) ->
        stringResource(R.string.ime_translate_auto_detect_label)
    translate.sourceGuessed -> stringResource(
        R.string.ime_translate_source_guessed_label,
        TranslateClient.languageName(translate.detectedSource),
    )
    else -> TranslateClient.languageName(translate.detectedSource)
}

/**
 * The languages of one menu, as picker codes, in the order they are drawn.
 * Kept free of Compose so the rules can be tested alone.
 *
 * [ready] is a language the engine can take without a new download: its model
 * is on the device or on its way. [downloadedFirst] puts those at the top,
 * each group still in picker order. [shortList] leaves out the rest, except
 * what [keep] holds on to (the selection, the languages the user types in).
 */
internal fun arrangeTranslateLanguages(
    codes: List<String>,
    ready: (String) -> Boolean,
    keep: (String) -> Boolean,
    downloadedFirst: Boolean,
    shortList: Boolean,
): List<String> {
    val shown = if (shortList) codes.filter { ready(it) || keep(it) } else codes
    if (!downloadedFirst) return shown
    val (top, rest) = shown.partition(ready)
    return top + rest
}

@Composable
private fun translateMenuRows(
    menu: TranslateMenu,
    translate: TranslateUi,
    target: String,
    engine: TranslateEngine,
    settings: KeyboardSettings,
    showAll: Boolean,
): List<TranslateMenuRow> {
    if (menu == TranslateMenu.ENGINE) {
        return TranslateEngine.entries.map { entry ->
            TranslateMenuRow(
                key = entry.name,
                label = stringResource(entry.labelRes),
                selected = entry == engine,
                info = stringResource(
                    when (entry) {
                        TranslateEngine.ONLINE -> R.string.ime_translate_engine_online_info
                        TranslateEngine.ON_DEVICE -> R.string.ime_translate_engine_on_device_info
                        TranslateEngine.AUTO -> R.string.ime_translate_engine_auto_info
                    },
                ),
            )
        }
    }
    // The model marks only mean something where the on-device engine is in
    // play; on Online they would be noise about a thing the user is not using.
    val showModels = engine != TranslateEngine.ONLINE
    val onlineOnly = stringResource(R.string.ime_translate_online_only_label)
    val selected = if (menu == TranslateMenu.SOURCE) translate.sourceOverride else target
    val names = TranslateClient.languages.toMap()
    val downloaded: (String) -> Boolean = { code ->
        val model = OfflineTranslateLanguages.modelCode(code)
        model == OfflineTranslateLanguages.PIVOT || translate.models[model] is OfflineModelState.Downloaded
    }
    val typed = settings.enabledLanguages.mapNotNullTo(mutableSetOf()) { OfflineTranslateLanguages.modelCode(it.id) }
    // An empty map is "not looked yet", not "nothing downloaded": a short
    // list drawn off it would be English alone.
    val shortList = engine == TranslateEngine.ON_DEVICE && settings.translate.onlyDownloaded &&
        !showAll && translate.models.isNotEmpty()
    val codes = TranslateClient.languages.map { it.first }
    val arranged = arrangeTranslateLanguages(
        codes = codes,
        ready = { code ->
            downloaded(code) ||
                translate.models[OfflineTranslateLanguages.modelCode(code)] is OfflineModelState.Downloading
        },
        keep = { code ->
            code.equals(selected, ignoreCase = true) || OfflineTranslateLanguages.modelCode(code) in typed
        },
        downloadedFirst = showModels && settings.translate.downloadedFirst,
        shortList = shortList,
    )
    val languages = arranged.map { code ->
        val model = OfflineTranslateLanguages.modelCode(code)
        TranslateMenuRow(
            key = code,
            label = names[code] ?: code,
            selected = code.equals(selected, ignoreCase = true),
            note = onlineOnly.takeIf { showModels && model == null },
            downloaded = showModels && model != null && downloaded(code),
            // Only a dead end when nothing else can take it: Automatic hands
            // these to the online service.
            dimmed = engine == TranslateEngine.ON_DEVICE && model == null,
        )
    } + listOfNotNull(
        TranslateMenuRow(
            key = ALL_LANGUAGES_KEY,
            label = stringResource(R.string.ime_translate_all_languages_label),
            selected = false,
            info = stringResource(R.string.ime_translate_all_languages_info),
        ).takeIf { arranged.size < codes.size },
    )
    if (menu == TranslateMenu.TARGET) return languages
    val detect = TranslateMenuRow(
        key = "",
        label = stringResource(R.string.ime_translate_auto_detect_label),
        selected = translate.sourceOverride.isBlank(),
    )
    return listOf(detect) + languages
}

@Composable
private fun TranslateChip(
    label: String,
    strong: Boolean,
    focused: Boolean,
    description: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .clip(kb.chipShape())
            .background(kb.chip)
            .chipBorder(kb, kb.chipShape())
            .focusRing(focused, kb.chipShape())
            .clickable { onClick() }
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = kb.toolbarIcon)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = kb.chipText,
            fontSize = 12.sp,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Outlined.ArrowDropDown,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = kb.toolbarIcon,
        )
    }
}

/**
 * The result area while the on-device engine is short of a model: what it
 * needs and what that costs, then the download as it runs. The button itself
 * is the panel's action row, so it sits where Replace and Insert will be.
 */
@Composable
private fun TranslateDownloadOffer(translate: TranslateUi, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    if (translate.moduleMissing) {
        TranslateModuleOffer(translate, modifier)
        return
    }
    val states = translate.missingModels.map { translate.models[it] }
    val running = states.filterIsInstance<OfflineModelState.Downloading>()
    val waiting = translate.missingModels.filter { translate.models[it] !is OfflineModelState.Downloaded }
    val names = waiting.joinToString(", ") { TranslateClient.languageName(it) }
    Column(
        modifier = modifier
            .padding(vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (running.isNotEmpty()) {
            Text(
                stringResource(R.string.ime_translate_downloading_progress, names),
                color = kb.suggestionText,
                fontSize = 14.sp,
            )
            // One bar for the lot: two models are one wait, as far as the
            // person watching is concerned.
            val bytes = running.sumOf { it.bytes }
            val total = running.sumOf { it.totalBytes }
            if (total > 0L && running.all { it.totalBytes > 0L }) {
                LinearProgressIndicator(
                    progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = kb.accent,
                    trackColor = kb.chip,
                )
                Text(
                    stringResource(
                        R.string.ime_translate_download_of_total_progress,
                        Formatter.formatShortFileSize(context, bytes),
                        Formatter.formatShortFileSize(context, total),
                    ),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = kb.accent,
                    trackColor = kb.chip,
                )
            }
            return@Column
        }
        translate.error?.let { Text(it, color = kb.accent, fontSize = 13.sp) }
        Text(
            stringResource(
                R.string.ime_translate_need_models_body,
                names,
                Formatter.formatShortFileSize(
                    context,
                    OfflineTranslateLanguages.APPROX_MODEL_BYTES * waiting.size.coerceAtLeast(1),
                ),
            ),
            color = kb.suggestionText,
            fontSize = 14.sp,
        )
        when {
            translate.meteredAsk -> Text(
                stringResource(R.string.ime_metered_ask_body),
                color = kb.accent,
                fontSize = 13.sp,
            )
            states.any { (it as? OfflineModelState.Missing)?.failed == true } -> Text(
                stringResource(R.string.ime_translate_download_failed_error),
                color = kb.accent,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * The same offer one step earlier, on a Play install: the engine is an
 * on-demand module and has not been fetched. Play reports the real size once
 * the fetch starts, so none is promised before that.
 */
@Composable
private fun TranslateModuleOffer(translate: TranslateUi, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val module = translate.module
    Column(
        modifier = modifier
            .padding(vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        translate.error?.let { Text(it, color = kb.accent, fontSize = 13.sp) }
        Text(
            stringResource(R.string.ime_translate_module_missing_body),
            color = kb.suggestionText,
            fontSize = 14.sp,
        )
        when {
            module is TranslateModuleState.Installing -> {
                val fraction = if (module.totalBytes > 0L) {
                    (module.bytes.toFloat() / module.totalBytes).coerceIn(0f, 1f)
                } else {
                    null
                }
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = kb.accent,
                        trackColor = kb.chip,
                    )
                    Text(
                        stringResource(
                            R.string.ime_translate_download_of_total_progress,
                            Formatter.formatShortFileSize(context, module.bytes),
                            Formatter.formatShortFileSize(context, module.totalBytes),
                        ),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = kb.accent,
                        trackColor = kb.chip,
                    )
                }
            }
            translate.meteredAsk -> Text(
                stringResource(R.string.ime_metered_ask_body),
                color = kb.accent,
                fontSize = 13.sp,
            )
            (module as? TranslateModuleState.Missing)?.failed == true -> Text(
                stringResource(R.string.ime_translate_module_failed_error),
                color = kb.accent,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun TranslateAction(
    label: String,
    icon: ImageVector?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (enabled) kb.chipActiveText else kb.secondaryText,
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            color = if (enabled) kb.chipActiveText else kb.secondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TranslateMenuPopup(
    rows: List<TranslateMenuRow>,
    focused: Int?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val scroll = rememberScrollState()
    // Sixty languages behind a 260 dp window: the rail is the only thing
    // saying the list runs past the third one.
    val rail = rememberScrollRailState(scroll)
    val downloadedDesc = stringResource(R.string.ime_translate_downloaded_desc)
    Popup(onDismissRequest = onDismiss) {
        ScrollRail(
            state = rail,
            modifier = Modifier
                .widthIn(min = 200.dp, max = 260.dp)
                .heightIn(max = 260.dp)
                .clip(kb.menuShape())
                .background(kb.popup)
                .popupBorder(kb, kb.menuShape())
                .padding(vertical = 4.dp),
            fadeColor = kb.popup,
            colors = kbRailColors(kb),
        ) {
            for ((index, row) in rows.withIndex()) {
                val textColor = when {
                    row.selected -> kb.accent
                    row.dimmed -> kb.popupText.copy(alpha = 0.4f)
                    else -> kb.popupText
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRing(index == focused, RoundedCornerShape(0.dp))
                        .clickable(enabled = !row.dimmed) { onPick(row.key) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.label,
                            color = textColor,
                            fontWeight = if (row.selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        row.info?.let { Text(it, color = kb.secondaryText, fontSize = 11.sp) }
                    }
                    when {
                        row.downloaded -> Icon(
                            Icons.Outlined.DownloadDone,
                            contentDescription = downloadedDesc,
                            modifier = Modifier.size(16.dp),
                            tint = kb.secondaryText,
                        )
                        row.note != null -> Text(
                            row.note,
                            color = kb.secondaryText.copy(alpha = if (row.dimmed) 0.6f else 1f),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // Rows in one menu are one height, so the ring's offset is index arithmetic.
        ScrollFocusIntoView(focused) { index ->
            val row = if (rows.isEmpty()) 0 else scroll.maxValue / rows.size + 1
            scroll.animateScrollTo((index * row - row).coerceAtLeast(0))
        }
    }
}
