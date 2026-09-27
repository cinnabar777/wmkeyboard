package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.layoutKind
import com.wasimaster.wmkeyboard.ime.R

/**
 * The panels that are layouts now (issue #63), each hosted here: the grid the
 * user laid out, filled to the key area's height, with the panel's own
 * components in its field cells and its search mode left exactly as it was.
 *
 * The callbacks ride in one object so `KeyboardBody` builds it once from the
 * lambdas it already has and nothing new travels through `KeyboardScreen`'s
 * argument list, which sits under the JVM's method-size ceiling.
 */
@Immutable
internal class PanelLayoutCallbacks(
    val onKey: (Key) -> Unit,
    val onText: (String) -> Unit,
    val onCursorMove: (Int) -> Unit,
    val onLayoutSelect: (String) -> Unit,
    val onPanelChange: (PanelMode) -> Unit,
    val emoji: EmojiFieldCallbacks,
    val clipboard: ClipboardFieldCallbacks,
    val trackpad: TrackpadFieldCallbacks,
)

/**
 * What the trackpad surface reaches (issue #39). Every caret move is an edit
 * key through [onKey], the path the text-editing panel's own keys take, so it
 * arrives at the service's `onTextEdit` with the selection mode it already
 * honours; the two-finger tap is a real space key for the same reason. The
 * hold rides the Selection mode tool's hold callback: on at the long press,
 * off at the release, the selection left standing. [onCaretDrag] brackets a
 * drag for the caret magnifier (discussion #303), the same way.
 */
@Immutable
internal class TrackpadFieldCallbacks(
    private val onKey: (Key) -> Unit,
    val onSelectionHold: (Boolean) -> Unit,
    val onCaretDrag: (Boolean) -> Unit = {},
) {
    fun onEdit(op: TextEditAction) = onKey(Key("", action = KeyAction.Edit(op)))
    fun onSpace() = onKey(Key(" ", action = KeyAction.Space))
}

/**
 * The layout the keyboard draws for [kind]: the typing layout's own grid when
 * it carries one (issue #63, per layout), else the user's shared panel layout,
 * else the shipped set. The one place this is decided; the service's
 * persistence check reads it too.
 */
internal fun KeyboardUiState.panelLayout(kind: PanelKind): PanelLayoutSpec {
    layouts.panels[kind]?.let { return it }
    val shared = panelLayouts[kind]
    // The shared map carries the shipped grid for a panel the user never
    // edited; only a grid of the user's own outranks what follows.
    if (shared != null && shared != BuiltInPanelLayouts.default(kind)) return shared
    if (kind == PanelKind.NUMPAD) {
        // Issue #55 kept: a layout that authored its own Number layer is drawn
        // on the pad. Below that, the shipped pad in the digit order the
        // Numpad tool's setting asks for.
        layouts.number?.let { number ->
            return PanelLayoutSpec(
                PanelKind.NUMPAD,
                LayerSpec(rows = number.rows, rowHeights = number.rowHeights),
                appearance = number.appearance,
            )
        }
        return BuiltInPanelLayouts.numpad(calculator = settings.numpadCalculatorLayout)
    }
    return shared ?: BuiltInPanelLayouts.default(kind)
}

/**
 * The theme the grid on screen asks for (issue #61, and #63's panels): with
 * no panel open, the typing grid's — a layer's, else its layout's — else
 * null for whatever the settings say. With a panel open, the panel layout's
 * own theme, else the *layout's*, never the typing layer's: a panel with no
 * theme of its own draws in its layout's, so a themed layout's panels match
 * it unless told otherwise, but a theme given to the letters layer alone is
 * that layer's and stops at its edge (issue #196). That is the rule the
 * editor's panel tab previews with, and the keyboard has to agree with it.
 *
 * [layout] is the typing grid when the caller already has it built; without
 * it the grid is built here.
 */
internal fun screenThemeId(state: KeyboardUiState, layout: KeyboardLayout? = null): String? {
    val panelKind = state.panel.layoutKind ?: return (layout ?: currentLayout(state)).themeId
    return state.panelLayout(panelKind).grid.themeId ?: state.layouts.themeId
}

/**
 * The first row of a panel layout when it is nothing but [strip] components —
 * the emoji panel's tabs and search pill, the clipboard's search pill and view
 * switch — the row full-bleed lifts into its header; or null.
 */
internal fun PanelLayoutSpec.leadingStrip(
    strip: Set<PanelFieldKind> = EmojiHeaderFields,
): List<Key>? {
    val row = grid.rows.firstOrNull() ?: return null
    if (grid.rows.size < 2 || row.isEmpty()) return null
    val isStrip = row.all { key -> (key.action as? KeyAction.Field)?.kind in strip }
    return if (isStrip) row else null
}

/** This layout without its first row, for when full-bleed has lifted that row into the header. */
private fun PanelLayoutSpec.withoutLeadingRow(): PanelLayoutSpec = copy(
    grid = grid.copy(
        rows = grid.rows.drop(1),
        rowHeights = grid.rowHeights?.drop(1),
    ),
)

/** The emoji components a full-bleed header can carry. */
private val EmojiHeaderFields = setOf(PanelFieldKind.EMOJI_TABS, PanelFieldKind.EMOJI_SEARCH)

/** The clipboard components a full-bleed header can carry. */
private val ClipboardHeaderFields = setOf(PanelFieldKind.CLIPBOARD_SEARCH, PanelFieldKind.CLIPBOARD_VIEW)

/**
 * The emoji panel. Search mode keeps its compact form — the key rows return
 * beneath it, they are how the query is typed — and the layout draws the rest
 * of the time, inside the full-bleed chrome when that setting is on.
 */
@Composable
internal fun EmojiPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val session = rememberEmojiPanelSession(state)
    // Toggling the open panel closes it — back to the keys.
    val onClose = { callbacks.onPanelChange(PanelMode.EMOJI) }
    if (state.emojiSearchActive) {
        EmojiSearchPanel(state, session, callbacks.emoji, onClose)
    } else {
        val spec = state.panelLayout(PanelKind.EMOJI)
        val fields: @Composable (PanelFieldKind) -> Unit = { kind ->
            EmojiField(kind, state, session, callbacks.emoji)
        }
        if (state.settings.emojiFullBleed) {
            // Full-bleed spends the reclaimed toolbar row on the panel's own
            // strip, as it always has: a first row made only of the tabs and the
            // search pill moves up into the header beside the back button, and
            // the grid starts right under it. A layout that puts something else
            // in its first row keeps the plain header with the panel's name.
            val strip = spec.leadingStrip()
            if (strip != null) {
                val rest = spec.withoutLeadingRow()
                FullBleedTool(
                    state, title = "", onClose = onClose,
                    headerActions = {
                        for (key in strip) {
                            Box(
                                modifier = Modifier
                                    .weight(key.width)
                                    .fillMaxHeight()
                                    .padding(horizontal = 2.dp),
                            ) { fields((key.action as KeyAction.Field).kind) }
                        }
                    },
                ) {
                    PanelLayoutGrid(state, rest, callbacks, onClose, fields, Modifier.fillMaxSize())
                }
            } else {
                FullBleedTool(state, stringResource(R.string.ime_tool_emoji), onClose = onClose) {
                    PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
                }
            }
        } else {
            // The always-on emoji row hides while this panel is open; absorbing
            // its height keeps the keyboard from resizing on panel switches.
            val barCompensation =
                if (state.settings.emojiBarMode == EmojiBarMode.ALWAYS) EmojiBarHeight else 0.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(keyRowsHeight(state) + barCompensation),
            ) {
                PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
            }
        }
    }
    // A Popup overlay, so opening it never reflows the fixed-height panel.
    if (session.reorderOpen) {
        FavouritesReorderPopup(
            favourites = state.emojiFavourites,
            onConfirm = {
                session.reorderOpen = false
                callbacks.emoji.onFavouritesReorder(it)
            },
            onDismiss = { session.reorderOpen = false },
        )
    }
}

/**
 * The clipboard panel. Searching or editing a clip hands the key rows back
 * and steps out of full-bleed, as before; otherwise the layout fills the key
 * area, inside the full-bleed chrome when that setting is on.
 */
@Composable
internal fun ClipboardPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val session = rememberClipboardPanelSession(state)
    val onClose = { callbacks.onPanelChange(PanelMode.CLIPBOARD) }
    val edit = state.clipEdit
    if (edit != null && state.clipEditActive) {
        // The keys come back underneath, as for the search, but the editor
        // keeps the history's own height rather than the search's few rows
        // (#371): a clip is read and changed here, and a note needs the room.
        ClipEditDialog(
            state, edit, callbacks.clipboard.actions,
            modifier = Modifier
                .fillMaxWidth()
                .height(clipEditPanelHeight(state)),
        )
        return
    }
    if (state.clipboardSearchActive) {
        // The panel shrinks to its search field plus a couple of result rows.
        // The toolbar row is hidden too (see KeyboardBody), so the panel
        // absorbs its height the way the emoji panel's search mode does.
        ClipboardSearchPanel(
            state, session, callbacks.clipboard,
            modifier = Modifier
                .fillMaxWidth()
                // Less the row the query's own suggestion strip takes below
                // the panel (#161), for the same reason.
                .height(clipboardSearchPanelHeight(state)),
        )
        return
    }
    val spec = state.panelLayout(PanelKind.CLIPBOARD)
    val fields: @Composable (PanelFieldKind) -> Unit = { kind ->
        ClipboardField(kind, state, session, callbacks.clipboard)
    }
    // The strips only exist when they have something to show, as before: a
    // row holding nothing but an empty strip takes no height.
    val collapsed = buildSet {
        if (!session.showSearch && !session.showClear) add(PanelFieldKind.CLIPBOARD_SEARCH)
        if (session.entities.isEmpty()) add(PanelFieldKind.CLIPBOARD_ENTITIES)
        // Nothing to lay out either way until there is a clip.
        if (state.clipboardItems.isEmpty()) add(PanelFieldKind.CLIPBOARD_VIEW)
    }
    if (state.settings.clipboard.fullBleed) {
        // Full-bleed: the toolbar row becomes the back header and the
        // reclaimed rows go to the history. A first row of nothing but the
        // search pill and the view switch moves up into that header, as the
        // emoji panel's tabs do, so the history starts right under it. With
        // the pill hidden the header keeps the panel's name, and the switch
        // sits at its end.
        val strip = spec.leadingStrip(ClipboardHeaderFields)
        if (strip != null) {
            val shown = strip.filter { (it.action as KeyAction.Field).kind !in collapsed }
            val searchShown = shown.any { (it.action as KeyAction.Field).kind == PanelFieldKind.CLIPBOARD_SEARCH }
            FullBleedTool(
                state,
                title = if (searchShown) "" else stringResource(R.string.ime_tool_clipboard),
                onClose = onClose,
                headerActions = if (shown.isEmpty()) null else {
                    {
                        for (key in shown) {
                            val kind = (key.action as KeyAction.Field).kind
                            // The pill takes its share of the width; the switch
                            // is a fixed square beside it, or alone at the end,
                            // and so is the clear button with the pill hidden.
                            val cell = if (kind == PanelFieldKind.CLIPBOARD_SEARCH && session.showSearch) {
                                Modifier.weight(key.width)
                            } else {
                                Modifier.width(HeaderToggleWidth)
                            }
                            Box(
                                modifier = cell
                                    .fillMaxHeight()
                                    .padding(horizontal = 2.dp),
                            ) { fields(kind) }
                        }
                    }
                },
            ) {
                PanelLayoutGrid(
                    state, spec.withoutLeadingRow(), callbacks, onClose, fields, Modifier.fillMaxSize(), collapsed,
                )
            }
        } else {
            FullBleedTool(state, stringResource(R.string.ime_tool_clipboard), onClose = onClose) {
                PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize(), collapsed)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyRowsHeight(state)),
        ) {
            PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize(), collapsed)
        }
    }
}

/** The text-editing pad: keys only, filling the key area. */
@Composable
internal fun TextEditPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val onClose = { callbacks.onPanelChange(PanelMode.TEXT_EDIT) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.TEXT_EDIT), callbacks, onClose,
            fields = { _ -> },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The Numpad tool's pad, a keys-only panel layout like the text-editing pad
 * (issue #63): the layout's own tab, the shared pad, the layout's Number
 * layer or the shipped pad, in that order — see [panelLayout].
 */
@Composable
internal fun NumpadPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val onClose = { callbacks.onPanelChange(PanelMode.NUMPAD) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.NUMPAD), callbacks, onClose,
            fields = { _ -> },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The trackpad panel (issue #39): the pointing surface over whatever keys the
 * layout puts beside it, filling the key area. Never full-bleed, because the
 * toolbar has to stay for the hold gesture that opened it.
 */
@Composable
internal fun TrackpadPanelHost(state: KeyboardUiState, callbacks: PanelLayoutCallbacks) {
    val onClose = { callbacks.onPanelChange(PanelMode.TRACKPAD) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state)),
    ) {
        PanelLayoutGrid(
            state, state.panelLayout(PanelKind.TRACKPAD), callbacks, onClose,
            fields = { kind ->
                if (kind == PanelFieldKind.TRACKPAD) TrackpadField(state.settings.trackpad, callbacks.trackpad)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The clipboard's view switch in a full-bleed header: its square plus a little air. */
private val HeaderToggleWidth = 44.dp

/** Panel height while the clipboard search bar is capturing the keys. */
internal val ClipboardSearchHeight = 132.dp

/**
 * The search's height: [ClipboardSearchHeight] plus the toolbar row it stands
 * in for, less the query's own strip below (#161), and fitted to the screen
 * with the keys underneath it (#333).
 */
@Composable
private fun clipboardSearchPanelHeight(state: KeyboardUiState): Dp = toolPanelHeight(
    state,
    wanted = ClipboardSearchHeight + topBarHeight(state.settings) - captureStripHeight(state),
    floor = FullBleedHeaderHeight,
)

/**
 * The clip editor's height (#371): the key area the history filled plus the
 * toolbar row, less the editor's strip below, so the editor opens as tall as
 * the panel it replaces and the keys push the keyboard up underneath it. The
 * same fit to the screen as the search's (#333) keeps a phone held sideways
 * from losing its keys to it, and it is never shorter than the search.
 */
@Composable
private fun clipEditPanelHeight(state: KeyboardUiState): Dp {
    val strip = captureStripHeight(state)
    val bar = topBarHeight(state.settings)
    return toolPanelHeight(
        state,
        wanted = maxOf(keyRowsHeight(state), ClipboardSearchHeight) + bar - strip,
        floor = FullBleedHeaderHeight,
    )
}
