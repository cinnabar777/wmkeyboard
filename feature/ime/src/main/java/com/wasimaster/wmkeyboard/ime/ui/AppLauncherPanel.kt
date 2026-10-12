package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fullscreen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureInPictureAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.settings.LauncherOpenMode
import com.wasimaster.wmkeyboard.core.settings.LauncherSplitCombo
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.LauncherIconShape
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LauncherActivity
import com.wasimaster.wmkeyboard.ime.LauncherApp
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The app-launcher panel's callbacks, bundled into one object rather than
 * seven parameters on [KeyboardScreen]: that function's argument list already
 * flirts with the JVM's 64K method-size ceiling at its call site, and the
 * launcher was the straw that crossed it.
 */
@androidx.compose.runtime.Immutable
class LauncherPanelCallbacks(
    val onAppTap: (LauncherApp) -> Unit = {},
    val onOpenDetail: (LauncherApp) -> Unit = {},
    val onActivityTap: (LauncherActivity) -> Unit = {},
    val onPinToggle: (String) -> Unit = {},
    val onAppInfo: (String) -> Unit = {},
    val onDetailClose: () -> Unit = {},
    val onHideToggle: (String) -> Unit = {},
    /** Resolves an app's icon through the service's LRU cache, off-main. */
    val iconFor: suspend (LauncherApp) -> ImageBitmap? = { null },
    /** Opens one app in an explicit mode, from the hold menu. */
    val onAppOpen: (LauncherApp, LauncherOpenMode) -> Unit = { _, _ -> },
    val onComboTap: (LauncherSplitCombo) -> Unit = {},
    val onComboAdd: (LauncherSplitCombo) -> Unit = {},
    val onComboRemove: (LauncherSplitCombo) -> Unit = {},
    /** Whether split-screen launches can work here; see `AppLaunchModes`. */
    val splitAvailable: () -> Boolean = { false },
    /** Whether the device has a freeform window mode at all. */
    val freeformAvailable: () -> Boolean = { false },
)

/** What the hold menu is open on: an app, or a saved split pair. */
private sealed interface LauncherMenuTarget {
    data class App(val app: LauncherApp) : LauncherMenuTarget
    data class Combo(val combo: LauncherSplitCombo) : LauncherMenuTarget
}

/**
 * The app-launcher tool: a searchable grid of every launchable app, a
 * pinned/recents row, a row of saved split-screen pairs, and (long-press, from
 * any of them) a menu: open normally, in a floating window or in split screen,
 * start a pair, pin, or go to the app's page with its hide and App-info
 * actions and the activities inside it. The service owns the catalog and the
 * launches; this panel renders
 * [KeyboardUiState.launcherApps]/[KeyboardUiState.launcherDetail]
 * and reports taps.
 *
 * The menu and the pick-a-second-app step of a new pair are local state and
 * drawn inside the panel rather than in a popup window: both are over the
 * moment the panel closes, and a window of their own would bring the IME
 * popup focus and touch rules along for nothing.
 *
 * [iconFor] resolves one app's icon off the main thread through the service's
 * LRU cache — cells draw a neutral placeholder until theirs lands, so a cold
 * open never decodes a hundred adaptive icons on the composition clock.
 */
@Composable
internal fun AppLauncherPanel(
    state: KeyboardUiState,
    callbacks: LauncherPanelCallbacks,
    onQueryTap: () -> Unit,
) {
    var menu by remember { mutableStateOf<LauncherMenuTarget?>(null) }
    var pairing by remember { mutableStateOf<LauncherApp?>(null) }
    // Read once per panel open: both are device facts that a developer-option
    // flip is the only thing to change, and the next open picks that up.
    val splitAvailable = remember { callbacks.splitAvailable() }
    val freeformAvailable = remember { callbacks.freeformAvailable() }
    val detail = state.launcherDetail
    if (detail != null) {
        LauncherDetail(
            state, detail.app, callbacks.iconFor,
            callbacks.onActivityTap, callbacks.onPinToggle, callbacks.onAppInfo,
            callbacks.onHideToggle,
        )
        return
    }
    val pick: (LauncherApp) -> Unit = { app ->
        val first = pairing
        when {
            first == null -> callbacks.onAppTap(app)
            // The first app again backs out rather than pairing an app with itself.
            first.packageName == app.packageName -> pairing = null
            else -> {
                callbacks.onComboAdd(LauncherSplitCombo(first.packageName, app.packageName))
                pairing = null
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LauncherGrid(
            state = state,
            iconFor = callbacks.iconFor,
            onAppTap = pick,
            onAppLongPress = { app -> if (pairing != null) pick(app) else menu = LauncherMenuTarget.App(app) },
            onQueryTap = onQueryTap,
            combos = if (splitAvailable && pairing == null) state.settings.launcher.combos else emptyList(),
            onComboTap = callbacks.onComboTap,
            onComboLongPress = { menu = LauncherMenuTarget.Combo(it) },
            pairing = pairing,
            onCancelPairing = { pairing = null },
        )
        when (val target = menu) {
            is LauncherMenuTarget.App -> AppMenu(
                state = state,
                app = target.app,
                iconFor = callbacks.iconFor,
                splitAvailable = splitAvailable,
                freeformAvailable = freeformAvailable,
                onDismiss = { menu = null },
                onOpen = { mode ->
                    menu = null
                    callbacks.onAppOpen(target.app, mode)
                },
                onPair = {
                    menu = null
                    pairing = target.app
                },
                onPinToggle = {
                    menu = null
                    callbacks.onPinToggle(target.app.packageName)
                },
                onOpenPage = {
                    menu = null
                    callbacks.onOpenDetail(target.app)
                },
            )
            is LauncherMenuTarget.Combo -> ComboMenu(
                state = state,
                combo = target.combo,
                iconFor = callbacks.iconFor,
                onDismiss = { menu = null },
                onOpen = {
                    menu = null
                    callbacks.onComboTap(target.combo)
                },
                onRemove = {
                    menu = null
                    callbacks.onComboRemove(target.combo)
                },
            )
            null -> Unit
        }
    }
}

@Composable
private fun LauncherGrid(
    state: KeyboardUiState,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onAppTap: (LauncherApp) -> Unit,
    onAppLongPress: (LauncherApp) -> Unit,
    onQueryTap: () -> Unit,
    combos: List<LauncherSplitCombo>,
    onComboTap: (LauncherSplitCombo) -> Unit,
    onComboLongPress: (LauncherSplitCombo) -> Unit,
    pairing: LauncherApp?,
    onCancelPairing: () -> Unit,
) {
    val launcher = state.settings.launcher
    val query = state.mediaQuery
    val hidden = launcher.hidden.toSet()
    val iconShape = launcherIconShape(launcher.iconShape)
    // Hidden apps drop out of the browsing grid only. A search still finds
    // them, dimmed, so hiding never makes an app unreachable from here.
    val apps = AppCatalog.filterApps(state.launcherApps, query)
        .let { found -> if (query.isEmpty()) found.filter { it.packageName !in hidden } else found }
    val sorted = AppCatalog.sortApps(
        apps,
        recentFirst = launcher.sortOrder ==
            com.wasimaster.wmkeyboard.core.settings.AppSortOrder.RECENT_FIRST,
        pinned = launcher.pinned,
        recents = launcher.recents,
    )
    // Pinned first, then recent — deduplicated so a pinned app never shows
    // twice in the shortcut row. Hidden while searching: the grid is the answer.
    val shortcuts = if (query.isEmpty() && !state.mediaSearchActive) {
        val byPackage = state.launcherApps.associateBy { it.packageName }
        (launcher.pinned + if (launcher.recentsEnabled) launcher.recents else emptyList())
            .distinct()
            .filter { it !in hidden }
            .mapNotNull { byPackage[it] }
    } else {
        emptyList()
    }
    // A pair whose app was uninstalled drops out of the row but stays saved,
    // so reinstalling the app brings the pair back.
    val shownCombos = if (query.isEmpty() && !state.mediaSearchActive && combos.isNotEmpty()) {
        val byPackage = state.launcherApps.associateBy { it.packageName }
        combos.mapNotNull { combo ->
            val first = byPackage[combo.first] ?: return@mapNotNull null
            val second = byPackage[combo.second] ?: return@mapNotNull null
            Triple(combo, first, second)
        }
    } else {
        emptyList()
    }

    PanelFocusTarget(PanelMode.APP_LAUNCHER, 1, 1, FocusRegion.SEARCH) { onQueryTap() }
    PanelFocusTarget(PanelMode.APP_LAUNCHER, shortcuts.size, shortcuts.size, FocusRegion.CHIPS) {
        shortcuts.getOrNull(it)?.let(onAppTap)
    }
    // An Adaptive grid's true column count is a layout fact; six is close
    // enough for arrow-key math on every phone width this ships on. A fixed
    // count is exact.
    val fixedColumns = launcher.gridColumns
        .takeIf { it != com.wasimaster.wmkeyboard.core.settings.LauncherToolSettings.AUTO_COLUMNS }
    PanelFocusTarget(PanelMode.APP_LAUNCHER, sorted.size, fixedColumns ?: 6) {
        sorted.getOrNull(it)?.let(onAppTap)
    }

    // Above every state, empty results included: a search that finds nothing
    // mid-pick must still show what the next tap is for and how to back out.
    Column(Modifier.fillMaxSize()) {
        if (pairing != null) PairingBanner(pairing.label, onCancelPairing)
        LauncherGridBody(
            state, iconFor, onAppTap, onAppLongPress, sorted, shortcuts, shownCombos,
            onComboTap, onComboLongPress, fixedColumns, iconShape, hidden,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun LauncherGridBody(
    state: KeyboardUiState,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onAppTap: (LauncherApp) -> Unit,
    onAppLongPress: (LauncherApp) -> Unit,
    sorted: List<LauncherApp>,
    shortcuts: List<LauncherApp>,
    shownCombos: List<Triple<LauncherSplitCombo, LauncherApp, LauncherApp>>,
    onComboTap: (LauncherSplitCombo) -> Unit,
    onComboLongPress: (LauncherSplitCombo) -> Unit,
    fixedColumns: Int?,
    iconShape: Shape?,
    hidden: Set<String>,
) {
    val kb = LocalKbTheme.current
    val launcher = state.settings.launcher
    when {
        state.launcherLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(
                    stringResource(R.string.ime_launcher_loading),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        sorted.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .alpha(0.4f),
                    tint = kb.secondaryText,
                )
                Text(
                    stringResource(R.string.ime_launcher_empty),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            val motion = kb.motion.any
            // One clock for the grid's entrance, started in the composition
            // that first shows the grid so no cell draws a frame at full
            // strength first. Each cell reads it in its layer, offset by its
            // place in the stagger, so the entrance recomposes nothing.
            val entrance = remember { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(Unit) {
                entrance.animateTo(1f, tween(GRID_ENTRANCE_MS, easing = LinearEasing))
            }
            if (shownCombos.isNotEmpty()) {
                Text(
                    stringResource(R.string.ime_launcher_combos_label),
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowItemsIndexed(
                        shownCombos,
                        key = { _, (combo, _, _) -> "combo:${combo.first}/${combo.second}" },
                    ) { _, (combo, first, second) ->
                        ComboCell(
                            combo = combo,
                            first = first,
                            second = second,
                            shape = iconShape,
                            iconFor = iconFor,
                            onTap = { onComboTap(combo) },
                            onLongPress = { onComboLongPress(combo) },
                        )
                    }
                }
            }
            if (shortcuts.isNotEmpty()) {
                Text(
                    stringResource(
                        if (launcher.pinned.isEmpty()) R.string.ime_launcher_recents_label
                        else R.string.ime_launcher_pinned_label,
                    ),
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowItemsIndexed(shortcuts, key = { _, app -> app.packageName }) { _, app ->
                        ShortcutCell(
                            app = app,
                            pinned = app.packageName in launcher.pinned,
                            shape = iconShape,
                            iconFor = iconFor,
                            onTap = { onAppTap(app) },
                            onLongPress = { onAppLongPress(app) },
                        )
                    }
                }
            }
            LazyVerticalGrid(
                // Auto keeps the 68 dp cell of the default 42 dp icon and
                // widens it with a bigger icon, so labels keep their room.
                columns = if (fixedColumns != null) {
                    GridCells.Fixed(fixedColumns)
                } else {
                    GridCells.Adaptive(minSize = maxOf(68, launcher.iconSizeDp + 26).dp)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
            ) {
                itemsIndexed(sorted, key = { _, app -> app.packageName }) { index, app ->
                    AppCell(
                        // A search reflows the grid: the cells that stay glide
                        // to their new places, the rest fade.
                        modifier = if (motion) {
                            Modifier
                                .animateItem(
                                    fadeInSpec = tween(CELL_FADE_IN_MS),
                                    placementSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    ),
                                    fadeOutSpec = tween(CELL_FADE_OUT_MS),
                                )
                                .graphicsLayer {
                                    val t = cellEntrance(entrance.value, index)
                                    alpha = t
                                    translationY = (1f - t) * CELL_RISE_DP.dp.toPx()
                                }
                        } else {
                            Modifier
                        },
                        app = app,
                        showLabel = launcher.showLabels,
                        pinned = app.packageName in launcher.pinned,
                        hidden = app.packageName in hidden,
                        iconSizeDp = launcher.iconSizeDp,
                        shape = iconShape,
                        iconFor = iconFor,
                        onTap = { onAppTap(app) },
                        // One meaning for a hold everywhere in this panel: the
                        // app's menu, whose last item is the app's page, so
                        // hiding keeps its door.
                        onLongPress = { onAppLongPress(app) },
                    )
                }
            }
        }
    }
}

/**
 * How far one cell is through the grid's entrance, 0 to 1, from the shared
 * clock's [progress]. Cells start [CELL_STAGGER_MS] apart, the spread capped
 * at [GRID_STAGGER_CAP_MS] so a long list does not keep the last cell waiting.
 */
private fun cellEntrance(progress: Float, index: Int): Float {
    val elapsed = progress * GRID_ENTRANCE_MS
    val delay = minOf(index * CELL_STAGGER_MS, GRID_STAGGER_CAP_MS)
    return ((elapsed - delay) / CELL_ENTER_MS).coerceIn(0f, 1f)
}

private const val CELL_STAGGER_MS = 15
private const val GRID_STAGGER_CAP_MS = 150
private const val CELL_ENTER_MS = 150f
private const val GRID_ENTRANCE_MS = GRID_STAGGER_CAP_MS + 150
private const val CELL_RISE_DP = 8
private const val CELL_FADE_IN_MS = 150
private const val CELL_FADE_OUT_MS = 120

private val RoundedIconShape = RoundedCornerShape(percent = 24)

/** The clip for [LauncherIconShape.SYSTEM] is none: the icon keeps its own mask. */
private fun launcherIconShape(shape: LauncherIconShape): Shape? = when (shape) {
    LauncherIconShape.CIRCLE -> CircleShape
    LauncherIconShape.ROUNDED -> RoundedIconShape
    LauncherIconShape.SYSTEM -> null
}

/** One app's icon, resolved through the service cache, placeholder first. */
@Composable
private fun AppIcon(
    app: LauncherApp,
    sizeDp: Int,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
) {
    val kb = LocalKbTheme.current
    val icon by produceState<ImageBitmap?>(null, app.packageName, app.activityName) {
        value = iconFor(app)
    }
    // Capped at the size but square at any smaller width: eight columns of a
    // big icon on a narrow phone shrink the icon rather than stretching it
    // into an oval.
    val box = Modifier
        .sizeIn(maxWidth = sizeDp.dp, maxHeight = sizeDp.dp)
        .fillMaxWidth()
        .aspectRatio(1f)
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            modifier = if (shape != null) box.clip(shape) else box,
        )
    } else {
        val placeholder = shape ?: RoundedIconShape
        Box(
            modifier = box
                .clip(placeholder)
                .background(kb.chip, placeholder),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    app: LauncherApp,
    showLabel: Boolean,
    pinned: Boolean,
    hidden: Boolean,
    iconSizeDp: Int,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Column(
        modifier = modifier
            .alpha(if (hidden) 0.45f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(horizontal = 4.dp)) {
            AppIcon(app, sizeDp = iconSizeDp, shape = shape, iconFor = iconFor)
            if (pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = stringResource(R.string.ime_launcher_pinned_label),
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.TopEnd),
                    tint = kb.toolCircleActiveIcon,
                )
            }
            if (hidden) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(R.string.ime_launcher_hidden_label),
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.BottomEnd),
                    tint = kb.secondaryText,
                )
            }
        }
        if (showLabel) {
            Text(
                app.label,
                color = kb.suggestionText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 3.dp, start = 2.dp, end = 2.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutCell(
    app: LauncherApp,
    pinned: Boolean,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(app, sizeDp = 36, shape = shape, iconFor = iconFor)
            if (pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd),
                    tint = kb.toolCircleActiveIcon,
                )
            }
        }
        Text(
            app.label,
            color = kb.secondaryText,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** What the next tap does while a pair is being made, and the way out. */
@Composable
private fun PairingBanner(firstLabel: String, onCancel: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(kb.chip)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.VerticalSplit,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = kb.toolbarIcon,
        )
        Text(
            stringResource(R.string.ime_launcher_pair_prompt, firstLabel),
            color = kb.suggestionText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            stringResource(R.string.ime_launcher_pair_cancel),
            color = kb.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** A saved pair: the two icons overlapped, first in front, and its name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComboCell(
    combo: LauncherSplitCombo,
    first: LauncherApp,
    second: LauncherApp,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(50.dp).height(36.dp)) {
            Box(
                Modifier
                    .size(30.dp)
                    .align(Alignment.BottomEnd),
            ) { AppIcon(second, sizeDp = 30, shape = shape, iconFor = iconFor) }
            Box(
                Modifier
                    .size(30.dp)
                    .align(Alignment.TopStart),
            ) { AppIcon(first, sizeDp = 30, shape = shape, iconFor = iconFor) }
        }
        Text(
            comboLabel(combo, first, second),
            color = kb.secondaryText,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun comboLabel(combo: LauncherSplitCombo, first: LauncherApp, second: LauncherApp): String =
    combo.name.ifBlank { stringResource(R.string.ime_launcher_combo_label, first.label, second.label) }

/**
 * The hold menu on one app. The three open modes lead; split screen and
 * making a pair are left out where split screen cannot work, and the
 * floating window says so when the device has no freeform mode to put it in
 * (it still opens, full screen). The app's page — screens, hide, App info —
 * closes the list, so everything a hold used to reach is still one step away.
 */
@Suppress("LongParameterList")
@Composable
private fun AppMenu(
    state: KeyboardUiState,
    app: LauncherApp,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    splitAvailable: Boolean,
    freeformAvailable: Boolean,
    onDismiss: () -> Unit,
    onOpen: (LauncherOpenMode) -> Unit,
    onPair: () -> Unit,
    onPinToggle: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val launcher = state.settings.launcher
    val shape = launcherIconShape(launcher.iconShape)
    val pinned = app.packageName in launcher.pinned
    LauncherMenuSheet(
        header = { AppIcon(app, sizeDp = 28, shape = shape, iconFor = iconFor) },
        title = app.label,
        onDismiss = onDismiss,
    ) {
        LauncherMenuItem(
            Icons.Outlined.Fullscreen,
            stringResource(R.string.ime_launcher_open_action),
        ) { onOpen(LauncherOpenMode.NORMAL) }
        LauncherMenuItem(
            Icons.Outlined.PictureInPictureAlt,
            stringResource(R.string.ime_launcher_open_floating_action),
            note = if (freeformAvailable) null else stringResource(R.string.ime_launcher_floating_unsupported),
        ) { onOpen(LauncherOpenMode.FLOATING) }
        if (splitAvailable) {
            LauncherMenuItem(
                Icons.Outlined.VerticalSplit,
                stringResource(R.string.ime_launcher_open_split_action),
            ) { onOpen(LauncherOpenMode.SPLIT) }
            LauncherMenuItem(
                Icons.Outlined.Link,
                stringResource(R.string.ime_launcher_pair_action),
                onClick = onPair,
            )
        }
        LauncherMenuItem(
            Icons.Outlined.PushPin,
            stringResource(if (pinned) R.string.ime_launcher_unpin_action else R.string.ime_launcher_pin_action),
            onClick = onPinToggle,
        )
        LauncherMenuItem(
            Icons.Outlined.MoreHoriz,
            stringResource(
                if (launcher.activityDrilldown) R.string.ime_launcher_page_action
                else R.string.ime_launcher_page_no_screens_action,
            ),
            onClick = onOpenPage,
        )
    }
}

/** The hold menu on a saved pair: open it, or remove it. */
@Composable
private fun ComboMenu(
    state: KeyboardUiState,
    combo: LauncherSplitCombo,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val byPackage = state.launcherApps.associateBy { it.packageName }
    val first = byPackage[combo.first]
    val second = byPackage[combo.second]
    val shape = launcherIconShape(state.settings.launcher.iconShape)
    LauncherMenuSheet(
        header = { if (first != null) AppIcon(first, sizeDp = 28, shape = shape, iconFor = iconFor) },
        title = if (first != null && second != null) {
            comboLabel(combo, first, second)
        } else {
            combo.name.ifBlank { "${combo.first} + ${combo.second}" }
        },
        onDismiss = onDismiss,
    ) {
        LauncherMenuItem(
            Icons.Outlined.VerticalSplit,
            stringResource(R.string.ime_launcher_combo_open_action),
            onClick = onOpen,
        )
        LauncherMenuItem(
            Icons.Outlined.Delete,
            stringResource(R.string.ime_launcher_combo_remove_action),
            onClick = onRemove,
        )
    }
}

/**
 * A menu drawn inside the panel over a scrim that closes it. Scrolls when the
 * keyboard is short: the app menu runs to six rows.
 */
@Composable
private fun LauncherMenuSheet(
    header: @Composable () -> Unit,
    title: String,
    onDismiss: () -> Unit,
    items: @Composable ColumnScope.() -> Unit,
) {
    val kb = LocalKbTheme.current
    val closeLabel = stringResource(R.string.ime_launcher_menu_close)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(kb.board.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = closeLabel,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                // Swallows taps between rows so they do not reach the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp)) { header() }
                Text(
                    title,
                    color = kb.popupText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            items()
        }
    }
}

@Composable
private fun LauncherMenuItem(
    icon: ImageVector,
    label: String,
    note: String? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = kb.popupText)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, color = kb.popupText, fontSize = 13.sp)
            if (note != null) {
                Text(
                    note,
                    color = kb.popupText.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/**
 * The app's page: its header card (icon, label, package) with the pin, hide
 * and App-info actions, and — behind **Open screens inside apps** — every
 * activity it declares, exported first, the rest dimmed with a lock and shown
 * only behind a second setting. With the screens switch off the page is the
 * actions alone; it stays reachable either way, because hiding an app has no
 * other door.
 */
@Composable
private fun LauncherDetail(
    state: KeyboardUiState,
    app: LauncherApp,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onActivityTap: (LauncherActivity) -> Unit,
    onPinToggle: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    onHideToggle: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val detail = state.launcherDetail ?: return
    val launcher = state.settings.launcher
    val shown = if (launcher.activityDrilldown) {
        detail.activities.filter { it.exported || launcher.showNonExported }
    } else {
        emptyList()
    }

    PanelFocusTarget(PanelMode.APP_LAUNCHER, shown.size, 1) {
        shown.getOrNull(it)?.let(onActivityTap)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                app, sizeDp = 40, shape = launcherIconShape(launcher.iconShape), iconFor = iconFor,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    app.label,
                    color = kb.suggestionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val pinned = app.packageName in launcher.pinned
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = stringResource(
                    if (pinned) R.string.ime_launcher_unpin_action
                    else R.string.ime_launcher_pin_action,
                ),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onPinToggle(app.packageName) }
                    .padding(8.dp),
                tint = if (pinned) kb.toolCircleActiveIcon else kb.toolbarIcon,
            )
            val hidden = app.packageName in launcher.hidden
            Icon(
                if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = stringResource(
                    if (hidden) R.string.ime_launcher_unhide_action
                    else R.string.ime_launcher_hide_action,
                ),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onHideToggle(app.packageName) }
                    .padding(8.dp),
                tint = kb.toolbarIcon,
            )
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.ime_launcher_app_info_action),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onAppInfo(app.packageName) }
                    .padding(8.dp),
                tint = kb.toolbarIcon,
            )
        }
        if (!launcher.activityDrilldown) return@Column
        Text(
            stringResource(R.string.ime_launcher_activities_label),
            color = kb.secondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
        )
        if (detail.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            return@Column
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            rowItemsIndexed(shown, key = { _, a -> "${a.packageName}/${a.className}" }) { _, activity ->
                val launchable = activity.exported && activity.enabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onActivityTap(activity) }
                        .alpha(if (launchable) 1f else 0.45f)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            activity.label,
                            color = kb.suggestionText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            activity.className,
                            color = kb.secondaryText,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!launchable) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription =
                                stringResource(R.string.ime_launcher_not_exported_label),
                            modifier = Modifier
                                .size(14.dp)
                                .padding(start = 2.dp),
                            tint = kb.secondaryText,
                        )
                    }
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}
