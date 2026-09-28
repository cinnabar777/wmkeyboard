package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeySlot
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.LayoutAppearance
import com.wasimaster.wmkeyboard.core.layout.MaxRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.MinRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.drawnFontScale
import com.wasimaster.wmkeyboard.core.layout.gridWeightOf
import com.wasimaster.wmkeyboard.core.layout.panelFlexRows
import com.wasimaster.wmkeyboard.core.layout.panelRowTops
import com.wasimaster.wmkeyboard.core.layout.rowScaledKeyHeight
import com.wasimaster.wmkeyboard.core.layout.spanSlots
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.shiftCasesText
import kotlin.math.roundToInt

/**
 * A panel layout drawn into the key area: ordinary keys as real [KeyButton]s
 * — theme face, haptics, hold-to-repeat, alternates popup, TalkBack — and the
 * panel's components in their [KeyAction.Field] cells (issue #63).
 *
 * A hand-placed [Layout] in the shape of `KeyBand`, because a component and a
 * spanning key alike belong to more than one row. Row heights come from
 * [panelRowTops]: key rows at key height, component rows sharing the rest, so
 * the grid fills exactly the height its host gives it and the keyboard window
 * never moves when a panel opens. The host must give it a bounded height.
 *
 * None of the key grid's word gestures: no glide, no smart hit remap, no
 * handwriting, no modifier chord. Each key owns its cell, and a component owns
 * every gesture inside its own. The one board-level gesture a panel does run is
 * the layer drag off `?123` or `ABC` (issue #210), since a key that names a
 * layer means the same thing wherever the layout put it.
 */
@Composable
internal fun PanelLayoutGrid(
    state: KeyboardUiState,
    spec: PanelLayoutSpec,
    callbacks: PanelLayoutCallbacks,
    /** Leaves the panel; what a key bound to [KeyAction.Letters] does here. */
    onClose: () -> Unit,
    /** Draws the component for a field cell, filling the cell it is given. */
    fields: @Composable (PanelFieldKind) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Strip components with nothing to show right now — the fragment chips
     * when no clip has one, the search pill when there is no history to filter.
     * A row made only of these takes no height, so the panel does not keep a
     * blank row for a strip that is not there.
     */
    collapsedFields: Set<PanelFieldKind> = emptySet(),
    /**
     * Whether a drag off `?123` or `ABC` shows that layer over the grid. Off
     * for a grid that is one row of a panel rather than the whole of it: the
     * layer would be drawn into that row's height.
     */
    layerPeek: Boolean = true,
) {
    val settings = state.settings
    if (spec.grid.rows.isEmpty()) return

    // One lambda for the grid's life, so every key sees the same value and a
    // recomposition of the grid does not restart every key's pointer input.
    val onPanelKey = remember(callbacks, onClose) {
        { key: Key -> routePanelKey(key, callbacks.onKey, onClose) }
    }

    // Issue #210: a drag off a `?123` or `ABC` key on a panel looks through to
    // that layer the way the same key does on the typing grid (#108), and the
    // panel is back at the lift. The peeked layer is drawn *over* the panel's
    // own cells, which stay composed underneath with nothing painted: taking
    // them out would reset the emoji grid's scroll and the clipboard list with
    // it, and it would cancel the pointer loop that is running the peek.
    val peek = rememberLayerPeek()
    val peekLayout = peek.mode?.let { rememberCurrentLayout(state.peekedFromPanel(it)) }
    // The peeked layer as a panel grid of keys alone, so its rows share the
    // panel's height by their row heights and the keyboard never moves.
    val peekSpec = remember(peekLayout, spec.panel) {
        peekLayout?.let { PanelLayoutSpec(spec.panel, LayerSpec(rows = it.rows, rowHeights = it.rowHeights)) }
    }
    // Every key's cell in the grid's own space, one table per grid drawn. The
    // peek loop reads the one on screen, and a new table appearing is how it
    // knows the peeked layer has been laid out.
    val panelRects = remember(spec) { KeyRects() }
    val peekRects = remember(peekSpec) { KeyRects() }
    val liveRects = rememberUpdatedState(if (peekSpec != null) peekRects else panelRects)
    val liveMode = rememberUpdatedState(state.layoutMode)
    val hapticOn = rememberUpdatedState(settings.haptics.enabled)
    val pickerHaptic = LocalHapticFeedback.current
    val gesture = settings.gesture
    val trailMs = gesture.trailDurationMs.toLong()
    val trail = remember { GlideTrail() }
    // The band's fade after the lift, as on the typing grid.
    LaunchedEffect(trail.visible) {
        while (trail.visible) {
            withFrameMillis { now -> trail.tick(now, trailMs) }
        }
    }

    // The screen's bubbles when a frame is drawing them, this grid's own
    // otherwise — see [LocalKeyPreviewState].
    val hoistedPreview = LocalKeyPreviewState.current
    val ownPreview = remember { KeyPreviewState() }
    val keyPreview = hoistedPreview ?: ownPreview
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var boxWindow by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .padding(horizontal = KeyRowsPadHorizontal, vertical = KeyRowsPadVertical)
            .onGloballyPositioned {
                boxOrigin = it.positionInRoot()
                boxWindow = it.positionInWindow()
                boxSize = it.size
            }
            // After the padding, so a pointer arrives in the same space the
            // grids below record their cells in.
            .pointerInput(onPanelKey, trailMs, settings.longPressDelayMs, layerPeek) {
                if (!layerPeek) return@pointerInput
                detectLayerPeek(
                    peek = peek,
                    rects = { liveRects.value },
                    origin = { Offset.Zero },
                    window = { boxWindow },
                    peekFor = { source -> panelLayerDragMode(source, liveMode.value) },
                    trail = trail,
                    trailMs = trailMs,
                    dwellMs = settings.longPressDelayMs.toLong(),
                    onPopupOpen = { if (hapticOn.value) pickerHaptic() },
                    onKey = onPanelKey,
                )
            },
    ) {
        PanelKeyGrid(
            state, spec, callbacks, onPanelKey, fields, keyPreview, panelRects, collapsedFields,
            // Read in the layer block, so a peek starting and ending repaints
            // and never recomposes the panel.
            Modifier.graphicsLayer { alpha = if (peek.mode != null) 0f else 1f },
        )
        if (peekSpec != null) {
            PanelKeyGrid(state, peekSpec, callbacks, onPanelKey, { _ -> }, keyPreview, peekRects)
        }
        LayerPeekHighlight(peek, settings)
        if (trail.visible) {
            val kb = LocalKbTheme.current
            Canvas(modifier = Modifier.matchParentSize()) {
                drawTrailBand(trail, kb.gestureTrail, gesture.trailOpacity, gesture.trailWidthDp.dp.toPx(), trailMs)
            }
        }
        if (hoistedPreview == null) {
            KeyPreviewOverlay(
                keyPreview, settings, boxOrigin, boxSize,
                modifier = Modifier.matchParentSize(),
                virtualHeadroom = true,
            )
        }
        LayerPeekPopup(
            peek, settings.popup, onPanelKey, callbacks.onText,
            shifted = state.shiftCasesText(),
        )
    }
}

/**
 * One panel grid's cells: its keys and components laid out by [panelRowTops],
 * with every key's cell filed in [rects] for the layer drag.
 */
@Composable
private fun PanelKeyGrid(
    state: KeyboardUiState,
    spec: PanelLayoutSpec,
    callbacks: PanelLayoutCallbacks,
    onPanelKey: (Key) -> Unit,
    fields: @Composable (PanelFieldKind) -> Unit,
    keyPreview: KeyPreviewState,
    rects: KeyRects,
    collapsedFields: Set<PanelFieldKind> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val kb = LocalKbTheme.current
    val palette = remember(kb) { kb.keyPalette() }
    val rows = spec.grid.rows
    if (rows.isEmpty()) return
    val gridWeight = gridWeightOf(rows).takeIf { it > 0f } ?: 10f
    // The grid's own label size, over the layout-wide one, exactly as a typing
    // layer's is resolved.
    val fontScale = LayoutAppearance(
        fontId = spec.appearance?.fontId,
        fontScale = spec.grid.fontScale ?: spec.appearance?.fontScale,
    ).drawnFontScale()

    // Everything a key's face reads, as `rememberKeyGrid` lists it: a keystroke
    // that changes none of these leaves every key identical and skipped.
    val slots = remember(
        spec, palette, settings, fontScale,
        state.shiftState, state.modifiers, state.effectiveEnterAction,
        state.enterAction,
        state.enterActionLabel, state.language, state.script,
        state.composer.isClusterShaping, state.vowelForm, state.layoutId,
        state.activeFancyStyleId, state.selectingText,
    ) {
        spanSlots(rows, gridWeight).map { slot ->
            PanelSlot(
                slot = slot,
                field = (slot.key.action as? KeyAction.Field)?.kind,
                visual = if (slot.key.action is KeyAction.Field) null else keyVisual(slot.key, state, palette, fontScale),
            )
        }
    }
    val flex = remember(rows) { panelFlexRows(rows) }
    val weights = remember(spec) {
        FloatArray(rows.size) {
            (spec.grid.rowHeights?.getOrNull(it) ?: 1f).coerceIn(MinRowHeightScale, MaxRowHeightScale)
        }
    }
    val gapV = keyGapV(settings)
    val gapH = keyGapH(settings)
    val rowHeightsDp = remember(spec, settings.keyHeightDp) {
        IntArray(rows.size) { rowScaledKeyHeight(settings.keyHeightDp, spec.grid.rowHeights?.getOrNull(it)) }
    }
    val density = LocalDensity.current
    val fixedPx = remember(rowHeightsDp, gapV, density, collapsedFields) {
        with(density) {
            IntArray(rows.size) { r ->
                val collapsed = collapsedFields.isNotEmpty() && rows[r].all { key ->
                    (key.action as? KeyAction.Field)?.kind?.let { it in collapsedFields } == true
                }
                if (collapsed) 0 else (rowHeightsDp[r].dp + gapV * 2).roundToPx()
            }
        }
    }

    Layout(
        modifier = modifier,
        content = {
            for (panelSlot in slots) {
                val field = panelSlot.field
                if (field != null) {
                    // Inset by the key gaps so the component's edges line up
                    // with the faces of the keys beside it.
                    Box(Modifier.padding(horizontal = gapH, vertical = gapV)) { fields(field) }
                } else {
                    KeyButton(
                        visual = panelSlot.visual!!,
                        settings = settings,
                        onKey = onPanelKey,
                        onText = callbacks.onText,
                        onCursorMove = callbacks.onCursorMove,
                        onLayoutSelect = callbacks.onLayoutSelect,
                        // What this key would be on a row of its own; the
                        // measure policy below hands it the real height.
                        heightDp = rowHeightsDp[panelSlot.slot.row],
                        layoutId = state.layoutId,
                        keyPreview = keyPreview,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val tops = panelRowTops(fixedPx, weights, flex, constraints.maxHeight)
        val unit = if (gridWeight > 0f) width / gridWeight else 0f
        val lefts = IntArray(measurables.size)
        // A fresh token per pass: the first cell filed under it wipes the cells
        // a previous size left behind.
        val generation = Any()
        val placeables = measurables.mapIndexed { index, measurable ->
            val slot = slots[index].slot
            // Each edge rounded on its own, so neighbours stay flush.
            val left = (unit * slot.x).roundToInt()
            val right = (unit * slot.end).roundToInt()
            lefts[index] = left
            val last = (slot.row + slot.span).coerceAtMost(rows.size)
            if (slots[index].field == null) {
                rects.record(
                    generation,
                    slot.key,
                    Rect(left.toFloat(), tops[slot.row].toFloat(), right.toFloat(), tops[last].toFloat()),
                )
            }
            measurable.measure(
                Constraints.fixed(
                    width = (right - left).coerceIn(0, width),
                    height = (tops[last] - tops[slot.row]).coerceAtLeast(0),
                ),
            )
        }
        layout(width, tops.last()) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(lefts[index], tops[slots[index].slot.row])
            }
        }
    }
}

/** One cell of a panel grid: the key's resolved face, or the component it hosts. */
private class PanelSlot(
    val slot: KeySlot,
    val field: PanelFieldKind?,
    val visual: KeyVisual?,
)

/**
 * Where a panel key's press goes. `abc` ([KeyAction.Letters]) leaves the panel
 * — that is what it has always meant on the emoji panel's bottom row — rather
 * than reaching the service, whose Letters handler switches the typing grid's
 * layer and must keep doing exactly that under an emoji *search*, where the
 * real key rows sit below the panel. The other layer keys — ?123, Fn, "open a
 * layout" — leave the panel too and *then* reach the service, so the layer
 * they switch to is the one on screen rather than one hidden under a panel
 * that stayed up (issue #183). A component's cell never dispatches.
 */
internal fun routePanelKey(key: Key, onKey: (Key) -> Unit, onClose: () -> Unit) {
    when (key.action) {
        KeyAction.Letters -> onClose()
        KeyAction.Symbols, KeyAction.Fn, is KeyAction.Layout -> {
            onClose()
            onKey(key)
        }
        is KeyAction.Field -> Unit
        else -> onKey(key)
    }
}
