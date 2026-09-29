package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntity
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipLinks
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.clipboard.clipEditable
import com.wasimaster.wmkeyboard.core.clipboard.clipPreviewText
import com.wasimaster.wmkeyboard.core.clipboard.expiresAt
import com.wasimaster.wmkeyboard.core.clipboard.matchesQuery
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.settings.ClipGridColumnsRange
import com.wasimaster.wmkeyboard.core.settings.ClipTimeLabel
import com.wasimaster.wmkeyboard.core.settings.ClipboardSettings
import com.wasimaster.wmkeyboard.core.settings.ClipboardView
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.ime.ClipEdit
import com.wasimaster.wmkeyboard.ime.ClipUndo
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clipOcrAvailable

/**
 * The clipboard panel's components, each in the cell its panel layout gives it
 * (issue #63): the search pill, the grid / list switch, the strip of fragments
 * lifted out of the clips, and the history itself — plus the clip editor,
 * which stands in for all of them while it is open.
 */

/**
 * The panel's own actions that reach the service through [ToolHoldCallbacks]
 * rather than as parameters of their own, because `KeyboardScreen`'s caller
 * sits against the JVM's 64K method-size ceiling.
 */
data class ClipboardPanelActions(
    /** Open this clip in the editor; the keys type into it until it closes. */
    val onEdit: (ClipItem) -> Unit = {},
    /** Save the editor's draft over its clip, and close it. */
    val onEditSave: () -> Unit = {},
    /** Close the editor, leaving the clip as it was. */
    val onEditCancel: () -> Unit = {},
    /** Flip the history between the two-column grid and the one-per-row list. */
    val onViewToggle: () -> Unit = {},
    /** The Undo bar's button: put back the clips just deleted (#327). */
    val onUndoDelete: () -> Unit = {},
    /** The Undo bar swiped away: the delete is final now, not at the timeout (#371). */
    val onUndoDismiss: () -> Unit = {},
    /** The clear button's question answered Delete: every unpinned clip goes (#371). */
    val onClearUnpinned: () -> Unit = {},
    /** A link clip's Open link: the address in the browser (#371). */
    val onOpenLink: (ClipItem) -> Unit = {},
    /** An image clip's View: the picture full screen (#371). */
    val onViewImage: (ClipItem) -> Unit = {},
    /** An image clip's Extract text: the OCR panel, reading the picture (#371). */
    val onExtractText: (ClipItem) -> Unit = {},
    /** The height bar let go: how many dp taller than the keyboard the panel opens (#414). */
    val onPanelHeight: (Int) -> Unit = {},
)

/** The history's two tabs, when [ClipboardSettings.pinnedTabs] is on (#371). */
internal enum class ClipTab { UNPINNED, PINNED }

/** Everything the clipboard components call back into the service with. */
@Immutable
internal class ClipboardFieldCallbacks(
    val onItem: (ClipItem) -> Unit,
    val onSticker: (ClipItem) -> Unit,
    val onPin: (ClipItem) -> Unit,
    val onDelete: (ClipItem) -> Unit,
    val onSearchToggle: () -> Unit,
    val onEntity: (ClipEntity) -> Unit,
    val actions: ClipboardPanelActions,
)

/** The derived views of the history the three cells share. */
@Stable
internal class ClipboardPanelSession(
    val shownItems: List<ClipItem>,
    val entities: List<ClipEntity>,
    /** The search pill is only offered once there is history to filter and the feature is on. */
    val showSearch: Boolean,
    val query: String,
    val gridState: LazyStaggeredGridState,
    /** One clip per row rather than two columns of cards. */
    val list: Boolean,
    /**
     * Each clip's number, by id, when numbering is on — its place in the whole
     * history, not in [shownItems], so a search never renumbers what it finds.
     * Empty when numbering is off.
     */
    val numbers: Map<Long, Int>,
    /**
     * The unpinned / pinned tabs are drawn and [shownItems] is one of them
     * (#371): the setting is on, there is history, and no search is running,
     * since a search looks through both.
     */
    val tabbed: Boolean,
    /** The open tab. Kept while the panel is, so a pin does not throw the user back. */
    val tab: MutableState<ClipTab>,
    val pinnedCount: Int,
    val unpinnedCount: Int,
    /** The bin beside the search pill (#371): the setting is on and something is unpinned. */
    val showClear: Boolean,
    /**
     * The bin's question is up. Its own state rather than a field of this
     * session, which is rebuilt whenever the history moves: a clip copied
     * meanwhile must not take the question away.
     */
    val clearAsking: MutableState<Boolean>,
    /** Image clips offer Extract text: the OCR tool is on in a build that has it. */
    val ocr: Boolean,
) {
    /** The question is on screen: asked, and there is still something to delete. */
    val confirmingClear: Boolean get() = showClear && clearAsking.value
}

@Composable
internal fun rememberClipboardPanelSession(state: KeyboardUiState): ClipboardPanelSession {
    val showSearch = state.settings.clipboard.search && state.clipboardItems.isNotEmpty()
    val query = state.clipboardQuery.trim()
    val tab = remember { mutableStateOf(ClipTab.UNPINNED) }
    val tabbed = state.settings.clipboard.pinnedTabs && !state.clipboardSearchActive &&
        state.clipboardItems.isNotEmpty()
    val pinnedCount = state.clipboardItems.count { it.pinned }
    val unpinnedCount = state.clipboardItems.size - pinnedCount
    val inTab = if (tabbed) {
        val pinned = tab.value == ClipTab.PINNED
        state.clipboardItems.filter { it.pinned == pinned }
    } else {
        state.clipboardItems
    }
    val shownItems = if (query.isEmpty()) inTab else inTab.filter { it.matchesQuery(query) }
    val showClear = state.settings.clipboard.clearButton && unpinnedCount > 0
    val clearAsking = remember { mutableStateOf(false) }
    // A question left standing while there was nothing to clear would pop
    // back up, unasked, with the next copy.
    LaunchedEffect(showClear) { if (!showClear) clearAsking.value = false }
    val ocr = clipOcrAvailable(state.settings)
    // Scanning every clip with three regexes is not free, so it happens once
    // per history change rather than on every recomposition.
    val phoneFormats = state.settings.clipboard.phoneFormats
    val phoneMasks = remember(phoneFormats) { PhoneFormats.parseAll(phoneFormats) }
    val allEntities = if (state.settings.clipboard.detectEntities) {
        remember(state.clipboardItems, phoneMasks) { ClipEntities.entitiesIn(state.clipboardItems, phoneMasks) }
    } else {
        emptyList()
    }
    // While searching the panel is only a couple of rows tall — the keys have
    // taken the rest — so the fragment strip stands down rather than eating one.
    val entities = if (state.clipboardSearchActive) {
        emptyList()
    } else {
        allEntities.filter { query.isEmpty() || it.value.contains(query, ignoreCase = true) }
    }
    val gridState = rememberLazyStaggeredGridState()
    val list = state.settings.clipboard.view == ClipboardView.LIST
    val showNumbers = state.settings.clipboard.showNumbers
    val numbers = remember(state.clipboardItems, showNumbers) {
        if (showNumbers) clipNumbers(state.clipboardItems) else emptyMap()
    }
    return remember(
        shownItems, entities, showSearch, query, gridState, list, numbers,
        tabbed, pinnedCount, unpinnedCount, showClear, ocr,
    ) {
        ClipboardPanelSession(
            shownItems, entities, showSearch, query, gridState, list, numbers,
            tabbed, tab, pinnedCount, unpinnedCount, showClear, clearAsking, ocr,
        )
    }
}

/** The clipboard component for [kind]; a kind of another panel draws nothing. */
@Composable
internal fun ClipboardField(
    kind: PanelFieldKind,
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    when (kind) {
        PanelFieldKind.CLIPBOARD_SEARCH -> ClipboardSearchFieldCell(state, session, callbacks)
        PanelFieldKind.CLIPBOARD_ENTITIES -> ClipboardEntitiesField(state, session, callbacks)
        PanelFieldKind.CLIPBOARD_LIST -> ClipboardListField(state, session, callbacks)
        PanelFieldKind.CLIPBOARD_VIEW -> ClipboardViewField(state, session, callbacks)
        else -> Unit
    }
}

/**
 * The search pill with the clear button at its end when that is on (#371), or
 * an empty cell while there is nothing to filter or clear. The bin rides the
 * pill's cell rather than a cell of its own, so it turns up in every panel
 * layout the user already has, beside the search bar the way it was asked for.
 */
@Composable
private fun ClipboardSearchFieldCell(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val clearIndex = if (session.showSearch) 1 else 0
    // Published even when hidden, at zero, so a stale count left behind cannot
    // let Tab land on a pill nothing is drawing. The bin is the region's second
    // item: Enter on it asks, and Enter again, with the question up, deletes.
    val count = (if (session.showSearch) 1 else 0) + (if (session.showClear) 1 else 0)
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.SEARCH,
        count = count,
        columns = count.coerceAtLeast(1),
        onActivate = { index ->
            when {
                index < clearIndex -> callbacks.onSearchToggle()
                session.confirmingClear -> {
                    session.clearAsking.value = false
                    callbacks.actions.onClearUnpinned()
                }
                else -> session.clearAsking.value = true
            }
        },
    )
    if (count == 0) return
    val focused = state.focusedIndex(FocusRegion.SEARCH)
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (session.showSearch) {
            ClipboardSearchField(
                state = state,
                onToggle = callbacks.onSearchToggle,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .focusRing(focused == 0, RoundedCornerShape(18.dp)),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (session.showClear) {
            ClipboardClearButton(
                asking = session.confirmingClear,
                focused = focused == clearIndex,
                // A second tap takes the question back down.
                onClick = { session.clearAsking.value = !session.clearAsking.value },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

/**
 * The clear button: a square in the view switch's style, in the error colour
 * while its question is up so the two read as one thing.
 */
@Composable
private fun ClipboardClearButton(
    asking: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Box(
        modifier = modifier
            .heightIn(max = 36.dp)
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(shape)
            .background(if (asking) MaterialTheme.colorScheme.errorContainer else kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.DeleteSweep,
            contentDescription = stringResource(R.string.ime_clipboard_clear_desc),
            modifier = Modifier.size(18.dp),
            tint = if (asking) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The fragments strip — codes, numbers, links — or an empty cell when there are none. */
@Composable
private fun ClipboardEntitiesField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val entities = session.entities
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CHIPS,
        count = entities.size,
        columns = entities.size.coerceAtLeast(1),
        onActivate = { index -> entities.getOrNull(index)?.let(callbacks.onEntity) },
    )
    if (entities.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))) {
        ClipEntityStrip(
            entities = entities,
            focused = state.focusedIndex(FocusRegion.CHIPS),
            onPaste = callbacks.onEntity,
        )
    }
}

/**
 * The history, with the Undo bar over its bottom edge after a delete. The bar
 * sits on the history rather than in a cell of its own so it is there however
 * the panel layout is arranged, and in the search panel too, and it outlives
 * the last clip: deleting that one is when the empty placeholder shows.
 */
@Composable
private fun ClipboardListField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    // Published at zero with the tabs off, for the reason the search pill is.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CATEGORIES,
        count = if (session.tabbed) ClipTab.entries.size else 0,
        columns = ClipTab.entries.size,
        onActivate = { index -> ClipTab.entries.getOrNull(index)?.let { session.tab.value = it } },
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (session.tabbed) {
                ClipTabRow(
                    selected = session.tab.value,
                    unpinned = session.unpinnedCount,
                    pinned = session.pinnedCount,
                    focused = state.focusedIndex(FocusRegion.CATEGORIES),
                    onSelect = { session.tab.value = it },
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ClipboardHistory(state, session, callbacks)
            }
        }
        ClipClearQuestion(
            // Not over the search's few rows: the bin that asked is not drawn there.
            count = if (session.confirmingClear && !state.clipboardSearchActive) session.unpinnedCount else null,
            reduceMotion = state.settings.reduceMotion,
            onCancel = { session.clearAsking.value = false },
            onDelete = {
                session.clearAsking.value = false
                callbacks.actions.onClearUnpinned()
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        ClipUndoBar(
            undo = state.clipboardUndo,
            reduceMotion = state.settings.reduceMotion,
            onUndo = callbacks.actions.onUndoDelete,
            onDismiss = callbacks.actions.onUndoDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * The Unpinned and Pinned tabs over the history (#371), each with its count,
 * so a pin moving a clip across is visible as a number changing.
 */
@Composable
private fun ClipTabRow(
    selected: ClipTab,
    unpinned: Int,
    pinned: Int,
    focused: Int?,
    onSelect: (ClipTab) -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((index, tab) in ClipTab.entries.withIndex()) {
            val on = tab == selected
            val color = if (on) kb.toolCircleActiveIcon else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(ClipTabHeight)
                    .clip(shape)
                    .background(if (on) kb.toolCircleActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(focused == index, shape)
                    .semantics {
                        role = Role.Tab
                        this.selected = on
                    }
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (tab == ClipTab.PINNED) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = color,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(
                        if (tab == ClipTab.PINNED) R.string.ime_clipboard_tab_pinned else R.string.ime_clipboard_tab_unpinned,
                    ),
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = color,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    (if (tab == ClipTab.PINNED) pinned else unpinned).toString(),
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = color.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/**
 * The clear button's question, in the Undo bar's popup style, over the top of
 * the history where the bin that asked it is (#371). [count] is how many clips
 * would go, or null while nothing is being asked.
 */
@Composable
private fun ClipClearQuestion(
    count: Int?,
    reduceMotion: Boolean,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastCount by remember { mutableIntStateOf(1) }
    if (count != null) lastCount = count
    AnimatedVisibility(
        visible = count != null,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(0)) else slideInVertically(tween(180)) { -it } + fadeIn(tween(180)),
        exit = if (reduceMotion) fadeOut(tween(0)) else slideOutVertically(tween(160)) { -it } + fadeOut(tween(160)),
    ) {
        val kb = LocalKbTheme.current
        Surface(
            shape = kb.menuShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp)) {
                Text(
                    pluralStringResource(R.plurals.ime_clip_clear_question, lastCount, lastCount),
                    fontSize = 13.sp,
                    color = kb.popupText,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text(stringResource(CommonR.string.common_cancel)) }
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(CommonR.string.common_delete),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Clip deleted · Undo", in the keyboard's popup style. A snackbar in all but
 * name: Compose's own needs a Scaffold, and a keyboard panel has none.
 *
 * It keeps the count it last showed while it slides away, so the text does
 * not blank out under the exit animation once the service clears the state.
 */
@Composable
private fun ClipUndoBar(
    undo: ClipUndo?,
    reduceMotion: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastCount by remember { mutableIntStateOf(1) }
    // The bar last shown, kept while it slides away, so the swipe below starts
    // afresh for each new delete but not for its own exit animation.
    var shown by remember { mutableStateOf(undo) }
    if (undo != null) {
        lastCount = undo.items.size
        shown = undo
    }
    val dismissLabel = stringResource(R.string.ime_clip_undo_dismiss)
    AnimatedVisibility(
        visible = undo != null,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(0)) else slideInVertically(tween(180)) { it } + fadeIn(tween(180)),
        exit = if (reduceMotion) fadeOut(tween(0)) else slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
    ) {
        val kb = LocalKbTheme.current
        // A sideways swipe puts the bar away and ends the Undo there and then
        // (#371), the way a snackbar goes; the clips it held are gone for good.
        key(shown) {
            SwipeToDeleteCard(onDelete = onDismiss) {
                Surface(
                    shape = kb.menuShape(),
                    color = kb.popup,
                    border = kb.popupSurfaceBorder(),
                    shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true })
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(R.plurals.ime_clip_deleted, lastCount, lastCount),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = kb.popupText,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = onUndo) {
                            Text(
                                stringResource(R.string.ime_clip_undo_delete),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The history — two columns of cards packed independently, or one clip per
 * row — with the empty and no-match placeholders where the clips would be.
 *
 * Both views are the same lazy grid with a different column count, so the
 * switch keeps the scroll position, the swipe-to-delete and the item
 * animations, and the focus ring moves the way the clips are drawn.
 */
@Composable
private fun ClipboardHistory(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val shownItems = session.shownItems
    val clipboard = state.settings.clipboard
    val columns = if (session.list) 1 else clipboard.gridColumns.coerceIn(ClipGridColumnsRange)
    // 0 is the view's own: six lines on a card, three in a row, where a list
    // is for scanning many clips.
    val lines = clipboard.previewLines.takeIf { it > 0 } ?: if (session.list) 3 else 6
    // The clock the time labels read. Ticks only while they are shown, and
    // only twice a minute: they count in minutes.
    val timeLabel = clipboard.timeLabel
    // With the swipe off (#344), or the buttons (#414), the hold popup
    // carries the delete instead; without the buttons, the pin as well.
    val swipe = clipboard.swipeToDelete
    val buttons = clipboard.cardButtons
    val now by produceState(System.currentTimeMillis(), timeLabel) {
        if (timeLabel == ClipTimeLabel.OFF) return@produceState
        while (true) {
            delay(ClipTimeTickMs)
            value = System.currentTimeMillis()
        }
    }
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        count = shownItems.size,
        columns = columns,
        onActivate = { index -> shownItems.getOrNull(index)?.let(callbacks.onItem) },
    )
    if (state.clipboardItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.ime_clipboard_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (shownItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    session.query.isNotEmpty() -> stringResource(R.string.ime_clipboard_no_match, session.query)
                    session.tab.value == ClipTab.PINNED -> stringResource(R.string.ime_clipboard_tab_pinned_empty)
                    else -> stringResource(R.string.ime_clipboard_tab_unpinned_empty)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val focused = state.focusedIndex()
    val gridState = session.gridState
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    // Staggered, not a fixed grid: each column packs independently, so a tall
    // image sits next to two or three stacked text clips.
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        // Room under the last clips for the Undo bar while it is up, so the
        // bottom row can still be scrolled clear of it.
        contentPadding = PaddingValues(
            start = 6.dp,
            top = 6.dp,
            end = 6.dp,
            bottom = if (state.clipboardUndo != null) UndoBarClearance else 6.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalItemSpacing = 6.dp,
    ) {
        itemsIndexed(shownItems, key = { _, item -> item.id }) { index, item ->
            // Deleting fades the card out and slides the survivors up into the
            // gap; pinning re-sorts the list, so the card glides to the front.
            // Under Reduce motion the list simply redraws in its new order.
            SwipeToDeleteCard(
                onDelete = { callbacks.onDelete(item) },
                enabled = swipe,
                modifier = if (state.settings.reduceMotion) {
                    Modifier
                } else {
                    Modifier.animateItem(
                        fadeInSpec = tween(160),
                        placementSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                        fadeOutSpec = tween(140),
                    )
                },
            ) {
                val number = session.numbers[item.id]
                val time = clipTimeText(item, timeLabel, clipboard, now)
                val hold = ClipHold(holdDelete = !swipe || !buttons, holdPin = !buttons, ocr = session.ocr)
                val outlined = clipboard.outlinePinned && item.pinned
                if (session.list) {
                    ClipRow(item, number, lines, time, focused = index == focused, outlined, buttons, hold, callbacks)
                } else {
                    ClipCard(item, number, lines, time, focused = index == focused, outlined, buttons, hold, callbacks)
                }
            }
        }
    }
}

/**
 * The panel while its search bar is capturing the keys: the pill and a couple
 * of rows of matches, the key rows returning underneath. The layout's own grid
 * stands down until the search is closed. Unchanged from before the panel
 * became a layout.
 */
@Composable
internal fun ClipboardSearchPanel(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
    modifier: Modifier = Modifier,
) {
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.SEARCH,
        count = 1,
        columns = 1,
        onActivate = { callbacks.onSearchToggle() },
    )
    // No fragment strip or view switch while searching; publish their regions
    // empty so a stale count cannot let Tab land on something nothing is drawing.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CHIPS,
        count = 0,
        columns = 1,
        onActivate = {},
    )
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = 0,
        columns = 1,
        onActivate = {},
    )
    Column(modifier = modifier) {
        ClipboardSearchField(
            state = state,
            onToggle = callbacks.onSearchToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                .focusRing(state.focusedIndex(FocusRegion.SEARCH) == 0, RoundedCornerShape(18.dp)),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ClipboardListField(state, session, callbacks)
        }
    }
}

/**
 * Each clip's number by id: its place in [items], counted from 1. The history
 * as the panel orders it, so the numbers read down the page in order, and a
 * filter that hides some clips leaves the rest with the numbers they had.
 */
internal fun clipNumbers(items: List<ClipItem>): Map<Long, Int> =
    items.withIndex().associate { (index, item) -> item.id to index + 1 }

/**
 * The tap, the hold and the focus ring every clip shares, card or row.
 * Composable only because [focusRing] reads the theme.
 */
@Composable
private fun Modifier.clipSurface(
    kb: KbTheme,
    item: ClipItem,
    focused: Boolean,
    outlined: Boolean,
    callbacks: ClipboardFieldCallbacks,
    onHold: () -> Unit,
): Modifier {
    val shape = kb.cardShape()
    return this
        .clip(shape)
        .background(kb.chip)
        .chipBorder(kb, shape)
        // A pinned clip's outline, when that is on (#371): the pin circle's
        // own accent, around the whole clip.
        .then(if (outlined) Modifier.border(PinnedOutlineWidth, MaterialTheme.colorScheme.primary, shape) else Modifier)
        .focusRing(focused, shape)
        .pointerInput(item.id) {
            detectTapGestures(
                onTap = { callbacks.onItem(item) },
                onLongPress = { onHold() },
            )
        }
}

/**
 * What a clip's press-and-hold popup offers beyond its kind: a Delete when
 * [holdDelete] (the swipe or the bin that would otherwise delete is off), Pin
 * when [holdPin] (the clip has no pin button, #414), and Extract text on a
 * picture when [ocr] (#371).
 */
@Immutable
private class ClipHold(val holdDelete: Boolean, val holdPin: Boolean, val ocr: Boolean)

/**
 * The press-and-hold popup for a clip, with the actions its kind allows: View
 * full text for text (#414), Edit for text, Open link for a bare address, View
 * and Extract text for a picture (#371), and Pin and Delete when [hold] asks.
 * View full text turns the popup into [ClipFullTextPopup].
 */
@Composable
private fun ClipHoldPopup(
    item: ClipItem,
    hold: ClipHold,
    callbacks: ClipboardFieldCallbacks,
    onDismiss: () -> Unit,
) {
    var fullText by remember { mutableStateOf(false) }
    if (fullText) {
        ClipFullTextPopup(
            item,
            onPaste = { onDismiss(); callbacks.onItem(item) },
            onDismiss = onDismiss,
        )
        return
    }
    val image = item.kind == ClipKind.IMAGE
    // Not a secret's: the panel never shows one's text, and the browser would.
    val link = item.kind.isTextual && !item.sensitive && ClipLinks.asUrl(item.text) != null
    ClipInfoPopup(
        item,
        onViewText = if (item.kind.isTextual && !item.sensitive && item.text.isNotEmpty()) {
            { fullText = true }
        } else null,
        onTogglePin = if (hold.holdPin) {
            { onDismiss(); callbacks.onPin(item) }
        } else null,
        onSendSticker = if (image) {
            { callbacks.onSticker(item); onDismiss() }
        } else null,
        onEdit = if (item.clipEditable) {
            { onDismiss(); callbacks.actions.onEdit(item) }
        } else null,
        onOpenLink = if (link) {
            { onDismiss(); callbacks.actions.onOpenLink(item) }
        } else null,
        onView = if (image) {
            { onDismiss(); callbacks.actions.onViewImage(item) }
        } else null,
        onExtractText = if (image && hold.ocr) {
            { onDismiss(); callbacks.actions.onExtractText(item) }
        } else null,
        onDelete = if (hold.holdDelete) {
            { onDismiss(); callbacks.onDelete(item) }
        } else null,
        onDismiss = onDismiss,
    )
}

/**
 * A clip's whole text (#414), in place of its hold popup: a card shows a few
 * lines, and the rest of a long clip could only be read by pasting it or by
 * opening the editor. The text scrolls under a fixed ceiling, in the chunks
 * [clipTextChunks] cuts, so a clip the size of a document lays out a screenful
 * at a time rather than whole. Paste does what a tap on the clip does.
 */
@Composable
private fun ClipFullTextPopup(item: ClipItem, onPaste: () -> Unit, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    val chunks = remember(item.text) { clipTextChunks(item.text) }
    val length = item.text.length
    Popup(
        popupPositionProvider = rememberAboveAnchorPopup(),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = kb.menuShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .widthIn(max = ClipFullTextMaxWidth)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    pluralStringResource(R.plurals.ime_clip_character_count, length, length),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = kb.popupText.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ClipFullTextMaxHeight),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    items(chunks) { chunk ->
                        Text(chunk, fontSize = 13.sp, color = kb.popupText)
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 4.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
                    TextButton(onClick = onPaste) {
                        Text(stringResource(CommonR.string.common_paste), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * [text] cut into pieces of at most [max] characters for [ClipFullTextPopup]'s
 * lazy list: at the last line break inside the limit, else the last space,
 * else at the limit itself, one character early rather than between the two
 * halves of a surrogate pair. The break a piece ends on is dropped, since the
 * next piece starts on a line of its own anyway. Empty text is one empty piece.
 */
internal fun clipTextChunks(text: String, max: Int = ClipTextChunkChars): List<String> {
    if (text.length <= max) return listOf(text)
    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + max, text.length)
        var next = end
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            val space = text.lastIndexOf(' ', end - 1)
            when {
                newline > start -> { end = newline; next = newline + 1 }
                space > start -> { end = space; next = space + 1 }
                Character.isHighSurrogate(text[end - 1]) -> { end--; next = end }
            }
        }
        out += text.substring(start, end)
        start = next
    }
    return out
}

/** How much text one item of [ClipFullTextPopup]'s list holds. */
internal const val ClipTextChunkChars = 2_000

/** [ClipFullTextPopup]'s size: most of a phone's width, and a few paragraphs tall. */
private val ClipFullTextMaxWidth = 440.dp
private val ClipFullTextMaxHeight = 320.dp

/** A clip's body by kind; [maxLines] bounds plain text, the one body that can run on. */
@Composable
private fun ClipBody(item: ClipItem, maxLines: Int) {
    when {
        // A masked secret outranks every other body: the point is that its
        // content is not on screen.
        item.sensitive && item.kind.isTextual -> ClipSensitiveBody(item)
        item.kind == ClipKind.IMAGE -> ClipThumbnail(item)
        item.kind == ClipKind.VIDEO -> ClipVideoBody(item)
        item.kind == ClipKind.FILE || item.kind == ClipKind.FOLDER -> ClipFileBody(item)
        item.kind == ClipKind.LINK -> ClipLinkBody(item)
        // Cut before layout: a paragraph is measured whole however few lines
        // are drawn, and one clip can be a whole document.
        else -> Text(
            text = remember(item.text, maxLines) { clipPreviewText(item.text, maxLines) },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The pin and delete circles, in that order. */
@Composable
private fun ClipActions(item: ClipItem, callbacks: ClipboardFieldCallbacks) {
    ClipActionCircle(
        icon = if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
        description = stringResource(if (item.pinned) R.string.ime_clip_unpin else R.string.ime_clip_pin),
        tint = if (item.pinned) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) { callbacks.onPin(item) }
    ClipActionCircle(
        icon = Icons.Outlined.Delete,
        description = stringResource(CommonR.string.common_delete),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    ) { callbacks.onDelete(item) }
}

/**
 * A clip's number, as a small filled tag. Filled with the accent so it reads
 * as a label the keyboard put there and never as part of the clip's text.
 */
@Composable
private fun ClipNumberBadge(number: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.ime_clip_number_desc, number)
    Box(
        modifier = modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * One history card: body by kind, then the number, the pin and the delete
 * circle along the bottom. The number rides the row the circles already
 * take, so turning numbering on never makes a card taller. With [buttons] off
 * (#414) the row goes too, on any card it would have left empty.
 */
@Composable
private fun ClipCard(
    item: ClipItem,
    number: Int?,
    lines: Int,
    time: String?,
    focused: Boolean,
    outlined: Boolean,
    buttons: Boolean,
    hold: ClipHold,
    callbacks: ClipboardFieldCallbacks,
) {
    var showInfo by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clipSurface(LocalKbTheme.current, item, focused, outlined, callbacks) { showInfo = true }
            // An image card insets less: the picture is the content.
            .padding(if (item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO) 5.dp else 10.dp),
    ) {
        if (showInfo) ClipHoldPopup(item, hold, callbacks) { showInfo = false }
        ClipBody(item, maxLines = lines)
        // Per card: one clip may still have a number or a time to show while
        // the next, pinned and unnumbered, has nothing left for the row.
        val footer = buttons || number != null || time != null || item.kind == ClipKind.HTML
        if (footer) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (number != null) ClipNumberBadge(number)
            if (item.kind == ClipKind.HTML) {
                Text(
                    stringResource(R.string.ime_clip_type_rich_text),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (time != null) ClipTimeText(time, Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            if (buttons) ClipActions(item, callbacks)
        }
    }
}

/**
 * One history row of the list view: the number, the clip across the full
 * width, and the pin and delete circles at the end ([buttons], #414). Text
 * gets three lines rather than a card's six — a list is for scanning many
 * clips — and a picture is a thumbnail at the start of the row rather than the
 * whole row.
 */
@Composable
private fun ClipRow(
    item: ClipItem,
    number: Int?,
    lines: Int,
    time: String?,
    focused: Boolean,
    outlined: Boolean,
    buttons: Boolean,
    hold: ClipHold,
    callbacks: ClipboardFieldCallbacks,
) {
    var showInfo by remember { mutableStateOf(false) }
    val visual = item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clipSurface(LocalKbTheme.current, item, focused, outlined, callbacks) { showInfo = true }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showInfo) ClipHoldPopup(item, hold, callbacks) { showInfo = false }
        if (number != null) ClipNumberBadge(number)
        if (visual) {
            // Width-bound, so the picture keeps its own shape at thumbnail
            // size, and height-capped so a portrait shot stays a row.
            Box(
                modifier = Modifier
                    .width(ListThumbnailWidth)
                    .heightIn(max = ListThumbnailMaxHeight)
                    .clip(RoundedCornerShape(8.dp)),
            ) { ClipBody(item, maxLines = lines) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (item.kind == ClipKind.IMAGE) R.string.ime_clip_type_image else R.string.ime_clip_type_video,
                    ),
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (time != null) ClipTimeText(time, Modifier.padding(top = 2.dp))
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                ClipBody(item, maxLines = lines)
                if (item.kind == ClipKind.HTML) {
                    Text(
                        stringResource(R.string.ime_clip_type_rich_text),
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (time != null) ClipTimeText(time, Modifier.padding(top = 2.dp))
            }
        }
        if (buttons) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ClipActions(item, callbacks) }
    }
}

/** A clip's time label, in the rich-text tag's small muted type. */
@Composable
private fun ClipTimeText(time: String, modifier: Modifier = Modifier) {
    Text(
        time,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The time [item] shows under [label] at [now], or null for none: how long ago
 * it was copied, or how long it has left by the same rule the store prunes by
 * ([expiresAt]). A pinned clip, or one history keeps forever, has no time left
 * to show.
 */
@Composable
private fun clipTimeText(item: ClipItem, label: ClipTimeLabel, clipboard: ClipboardSettings, now: Long): String? =
    when (label) {
        ClipTimeLabel.OFF -> null
        ClipTimeLabel.COPIED -> android.text.format.DateUtils.getRelativeTimeSpanString(
            item.timestamp,
            now,
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
        ClipTimeLabel.EXPIRES -> {
            val sensitiveLeash = if (clipboard.sensitiveHandling == SensitiveClipHandling.SHORT_LIVED) {
                clipboard.sensitiveExpiryMinutes * MinuteMs
            } else {
                0L
            }
            item.expiresAt(clipboard.expiryHours * 60 * MinuteMs, sensitiveLeash)?.let { at ->
                val minutes = ((at - now).coerceAtLeast(0L) + MinuteMs - 1) / MinuteMs
                when {
                    minutes < 60 -> minutes.coerceAtLeast(1L).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_minutes, it, it)
                    }
                    minutes < 48 * 60 -> (minutes / 60).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_hours, it, it)
                    }
                    else -> (minutes / (24 * 60)).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_days, it, it)
                    }
                }
            }
        }
    }

private const val MinuteMs = 60_000L

/** How often the time labels are brought up to date while the panel is open. */
private const val ClipTimeTickMs = 30_000L

/** The history's bottom padding while the Undo bar covers its last few dp. */
private val UndoBarClearance = 64.dp

/** How big a picture is drawn in a list row. */
private val ListThumbnailWidth = 96.dp
private val ListThumbnailMaxHeight = 80.dp

/** A pinned clip's outline, when that setting is on: thin, a hairline over the card's own edge. */
private val PinnedOutlineWidth = 1.5.dp

/** The unpinned / pinned tabs' height. */
private val ClipTabHeight = 32.dp

/**
 * The grid / list switch. Its icon is the view a tap switches *to*, the way a
 * toolbar's view toggle reads, and it stands down with the history: with no
 * clips there is nothing to lay out either way.
 */
@Composable
private fun ClipboardViewField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val shown = state.clipboardItems.isNotEmpty()
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = if (shown) 1 else 0,
        columns = 1,
        onActivate = { callbacks.actions.onViewToggle() },
    )
    if (!shown) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ClipboardViewToggle(
            list = session.list,
            focused = state.focusedIndex(FocusRegion.ACTIONS) == 0,
            onToggle = callbacks.actions.onViewToggle,
        )
    }
}

/** The switch itself, a square in the search pill's own card style. */
@Composable
internal fun ClipboardViewToggle(
    list: Boolean,
    focused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Box(
        modifier = modifier
            // Square, and no taller than the pill beside it: a strip row is
            // shorter than 36 dp at small key heights, and the cell wins.
            .heightIn(max = 36.dp)
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (list) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
            contentDescription = stringResource(
                if (list) R.string.ime_clipboard_view_grid_desc else R.string.ime_clipboard_view_list_desc,
            ),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The clip editor: a dialog in the panel's place, the keys back beneath it.
 *
 * Not a real text field — an input method has none of its own to raise — so
 * the keys type into the service's draft the way they type into the search
 * pill, and this draws that draft with its caret. Enter is a line break;
 * Save and Cancel are the dialog's own, and Back cancels too.
 */
@Composable
internal fun ClipEditDialog(
    state: KeyboardUiState,
    edit: ClipEdit,
    actions: ClipboardPanelActions,
    modifier: Modifier = Modifier,
) {
    // Nothing else in the panel is on screen, so no other region may keep a
    // ring on a card or a chip that is not drawn.
    for (region in listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.CATEGORIES, FocusRegion.RESULTS)) {
        PanelFocusTarget(panel = PanelMode.CLIPBOARD, region = region, count = 0, columns = 1, onActivate = {})
    }
    // Cancel and Save, for a physical keyboard, whose Enter is a line break here.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = 2,
        columns = 2,
        onActivate = { index -> if (index == 0) actions.onEditCancel() else actions.onEditSave() },
    )
    val focused = state.focusedIndex(FocusRegion.ACTIONS)
    val kb = LocalKbTheme.current
    Box(modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Surface(
            shape = kb.menuShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = kb.popupText.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.ime_clip_edit_title),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            color = kb.popupText,
                        )
                        // Only once the draft has moved: until then nothing is lost.
                        if (edit.rich && edit.draft.trim() != edit.original) {
                            Text(
                                stringResource(R.string.ime_clip_edit_rich_note),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = kb.popupText.copy(alpha = 0.6f),
                            )
                        }
                    }
                    TextButton(
                        onClick = actions.onEditCancel,
                        modifier = Modifier.focusRing(focused == 0, RoundedCornerShape(18.dp)),
                    ) { Text(stringResource(CommonR.string.common_cancel)) }
                    TextButton(
                        onClick = actions.onEditSave,
                        enabled = edit.draft.isNotBlank(),
                        modifier = Modifier.focusRing(focused == 1, RoundedCornerShape(18.dp)),
                    ) { Text(stringResource(R.string.ime_clip_edit_save), fontWeight = FontWeight.SemiBold) }
                }
                val fieldShape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(fieldShape)
                        .background(kb.chip)
                        .chipBorder(kb, fieldShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    ClipEditText(
                        text = edit.draft,
                        placeholder = stringResource(R.string.ime_clip_edit_placeholder),
                        textColor = MaterialTheme.colorScheme.onSurface,
                        placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The draft with its caret, wrapped over as many lines as it takes and
 * scrolled to keep the caret in sight. A tap puts the caret under the finger.
 *
 * The one-line [SearchQueryText] asks the layout for an x; this asks it for
 * the caret's whole rectangle, which is what finds the right line. Both only
 * ever ask a layout of *this* text, never one a frame stale (#252).
 *
 * Shared with the AI panel's chat composer (#280), the keyboard's other
 * free-text field. That one passes a [modifier] that wraps its lines up to a
 * cap, where the clip editor fills the room it is given.
 */
@Composable
internal fun ClipEditText(
    text: String,
    placeholder: String,
    textColor: Color,
    placeholderColor: Color,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val handle = LocalCaptureCaret.current
    val caret = handle.at.coerceIn(0, text.length)
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val fontSize = 14.sp
    // A selection in the draft (#352), as the one-line fields have it.
    val overlay = LocalSelectionOverlay.current
    val owner = remember { SelectionAnchor() }
    val selecting = handle.hasSelection && handle.selectionEnd <= text.length
    val latestHandle by rememberUpdatedState(handle)
    // Paste offered at the caret by a long press on no word, or on the empty
    // draft (#434).
    var caretBar by remember(text) { mutableStateOf(false) }
    Box(modifier = modifier.verticalScroll(scroll)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(text) {
                    detectTapGestures(
                        onLongPress = { position ->
                            caretBar = !fieldLongPress(text, layout, position, latestHandle)
                        },
                    ) { position ->
                        caretBar = false
                        layout?.takeIf { it.layoutInput.text.text == text }
                            ?.let { latestHandle.onCaretTap(it.getOffsetForPosition(position)) }
                    }
                },
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                onTextLayout = {
                    layout = it
                    overlay?.moved(owner)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        owner.coordinates = it
                        overlay?.moved(owner)
                    }
                    .selectionHighlight(
                        text,
                        if (selecting) handle.selectionStart else 0,
                        if (selecting) handle.selectionEnd else 0,
                        LocalKbTheme.current.accent.copy(alpha = HighlightAlpha),
                    ) { layout },
            )
            PublishFieldSelection(
                owner = owner,
                text = text,
                active = true,
                handle = handle,
                coordinates = { owner.coordinates },
                layout = { layout?.takeIf { it.layoutInput.text.text == text } },
                caretBar = caretBar,
                onCaretBarDismiss = { caretBar = false },
            )
            if (text.isEmpty()) {
                Text(
                    text = placeholder,
                    color = placeholderColor,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            val result = layout?.takeIf { it.layoutInput.text.text == text }
            // No caret while a span is selected: the highlight marks where typing lands.
            if (result != null && !selecting) {
                val rect = result.getCursorRect(caret.coerceIn(0, result.layoutInput.text.length))
                Box(
                    Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) },
                ) {
                    EditCaret(
                        color = textColor,
                        height = with(density) { rect.height.toDp() },
                        restartKey = caret to text,
                    )
                }
                // Keep the caret's line in view: typing at the end of a long
                // clip, or a newline, would otherwise run off the bottom.
                LaunchedEffect(rect.top, rect.bottom, scroll.viewportSize, scroll.maxValue) {
                    val viewport = scroll.viewportSize
                    if (viewport <= 0) return@LaunchedEffect
                    val margin = with(density) { 4.dp.toPx() }
                    val want = when {
                        rect.top - margin < scroll.value -> rect.top - margin
                        rect.bottom + margin > scroll.value + viewport -> rect.bottom + margin - viewport
                        else -> return@LaunchedEffect
                    }
                    scroll.scrollTo(want.roundToInt().coerceIn(0, scroll.maxValue))
                }
            }
        }
    }
}

/** The editor's blinking bar; held solid under reduce motion, like the search caret. */
@Composable
private fun EditCaret(color: Color, height: androidx.compose.ui.unit.Dp, restartKey: Any?) {
    val reduceMotion = LocalKbTheme.current.reduceMotion
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(restartKey, reduceMotion) {
        visible = true
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            delay(500)
            visible = !visible
        }
    }
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(height)
            .background(if (visible) color else Color.Transparent),
    )
}
