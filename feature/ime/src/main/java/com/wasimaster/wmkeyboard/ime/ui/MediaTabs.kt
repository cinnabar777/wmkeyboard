package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.rowScaledKeyHeight
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MediaSwitcher
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode

/**
 * The switch between the emoji, GIF and sticker panels (issue #366): one
 * segment per panel, the open one lit, so the three read as the tabs of one
 * panel the way they do on most keyboards.
 *
 * The emoji panel carries it as a component of its layout
 * ([PanelFieldKind.MEDIA_TABS]); the GIF and sticker panels draw the row that
 * component sits in, or put the switch beside their search box, as
 * [KeyboardSettings.emoji]'s `mediaSwitcher` says.
 */

/** The panels the switch moves between, in the order it draws them. */
internal val MediaTabPanels: List<PanelMode> = listOf(PanelMode.EMOJI, PanelMode.GIF, PanelMode.STICKER)

/** The tool behind each of [MediaTabPanels]: its icon, its name, and whether it is switched on. */
private fun PanelMode.mediaTool(): ToolbarTool? = when (this) {
    PanelMode.EMOJI -> ToolbarTool.EMOJI
    PanelMode.GIF -> ToolbarTool.GIF
    PanelMode.STICKER -> ToolbarTool.STICKER
    else -> null
}

/**
 * The panels the switch offers. Emoji always: the emoji key opens that panel
 * with the tool off the toolbar. GIFs and stickers only while their tool is
 * switched on, shipped in this build and usable, the tests the toolbar makes
 * before it draws one.
 */
internal fun mediaTabs(settings: KeyboardSettings): List<PanelMode> = MediaTabPanels.filter { panel ->
    val tool = panel.mediaTool() ?: return@filter false
    panel == PanelMode.EMOJI ||
        (tool in settings.enabledTools && isSupportedTool(tool) && isUsableTool(tool, settings))
}

/**
 * Where the switch goes: the setting, or [MediaSwitcher.OFF] while there is
 * only the emoji panel to switch to, since a switch with one segment goes
 * nowhere and would only take room from the space bar.
 */
internal fun mediaSwitcherPlacement(settings: KeyboardSettings): MediaSwitcher =
    if (mediaTabs(settings).size < 2) MediaSwitcher.OFF else settings.emoji.mediaSwitcher

/**
 * The panel the emoji key and the emoji tool open, given the panel [open] now
 * and the one of the three that was open [last].
 *
 * Plain emoji until "open the last used" is on. Then the three are one panel:
 * with none of them up, the key brings back whichever was open last; with one
 * of them up, it names that one, which closes it, the way the key has always
 * closed the emoji panel. A last panel whose tool has since been switched off
 * falls back to emoji.
 */
internal fun mediaOpenerTarget(open: PanelMode, last: PanelMode, settings: KeyboardSettings): PanelMode {
    if (!settings.emoji.rememberMediaTab) return PanelMode.EMOJI
    if (open in MediaTabPanels) return open
    return last.takeIf { it in mediaTabs(settings) } ?: PanelMode.EMOJI
}

/**
 * The row the GIF and sticker panels draw under their grids: the emoji
 * panel's own row that holds the switch, so a bottom row the user rearranged
 * comes across with it, else the shipped one.
 *
 * The emoji panel's row is used only when it is keys and the switch alone.
 * One that also holds the tab strip, or that a key from the row above reaches
 * down into, belongs to the emoji panel, and lifted out it would draw a hole.
 */
internal fun mediaBottomRowSpec(emoji: PanelLayoutSpec): PanelLayoutSpec {
    val rows = emoji.grid.rows
    val index = rows.indexOfFirst { row -> row.any { it.isMediaTabs() } }
    val reachedInto = index > 0 && rows.take(index).withIndex().any { (r, row) ->
        row.any { key -> r + key.rowSpan > index }
    }
    val own = rows.getOrNull(index)?.takeIf { row ->
        !reachedInto && row.all { key -> key.rowSpan <= 1 && (key.action !is KeyAction.Field || key.isMediaTabs()) }
    }
    return PanelLayoutSpec(
        panel = PanelKind.EMOJI,
        grid = LayerSpec(
            rows = listOf(own ?: BuiltInPanelLayouts.mediaBottomRow),
            rowHeights = emoji.grid.rowHeights?.getOrNull(index)?.takeIf { own != null }?.let { listOf(it) },
            fontScale = emoji.grid.fontScale,
        ),
        appearance = emoji.appearance,
    )
}

private fun Key.isMediaTabs(): Boolean = (action as? KeyAction.Field)?.kind == PanelFieldKind.MEDIA_TABS

/**
 * Where the GIF and sticker panels draw the switch: [top] at the end of the
 * search row, or [bottom], the row under the grid. At most one is set.
 */
internal class MediaSwitchSlots(
    val top: (@Composable () -> Unit)?,
    val bottom: (@Composable () -> Unit)?,
)

/**
 * The GIF or sticker panel's switch slots for [state]. Neither while a search
 * has the keys: they come back under the panel then, and the switch would sit
 * on top of them.
 */
internal fun mediaSwitchSlots(state: KeyboardUiState, callbacks: PanelLayoutCallbacks): MediaSwitchSlots {
    val placement = if (state.mediaSearchActive) MediaSwitcher.OFF else mediaSwitcherPlacement(state.settings)
    return MediaSwitchSlots(
        top = if (placement == MediaSwitcher.TOP) {
            { MediaTabsPill(state, callbacks.onPanelChange) }
        } else {
            null
        },
        bottom = if (placement == MediaSwitcher.BOTTOM) {
            { MediaBottomRow(state, callbacks, onClose = { callbacks.onPanelChange(state.panel) }) }
        } else {
            null
        },
    )
}

/**
 * The switch's row under the GIF or sticker grid: real keys either side of it,
 * at exactly the height and inset the emoji panel's bottom row has, so the
 * row stays put as the panels swap underneath it.
 */
@Composable
internal fun MediaBottomRow(state: KeyboardUiState, callbacks: PanelLayoutCallbacks, onClose: () -> Unit) {
    val emoji = state.panelLayout(PanelKind.EMOJI)
    val spec = remember(emoji) { mediaBottomRowSpec(emoji) }
    PanelLayoutGrid(
        state, spec, callbacks, onClose,
        fields = { kind ->
            if (kind == PanelFieldKind.MEDIA_TABS) MediaTabsField(state, callbacks.onPanelChange)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(mediaBottomRowHeight(state, spec)),
        // A drag off abc would show the whole letters layer squeezed into
        // this one row; the emoji panel's own grid has the height for it.
        layerPeek = false,
    )
}

/**
 * The height of [MediaBottomRow]: its keys at the key height its row asks
 * for, plus the gaps and the inset the emoji panel's grid puts around them.
 */
internal fun mediaBottomRowHeight(
    state: KeyboardUiState,
    spec: PanelLayoutSpec = mediaBottomRowSpec(state.panelLayout(PanelKind.EMOJI)),
): Dp {
    val settings = state.settings
    return rowScaledKeyHeight(settings.keyHeightDp, spec.grid.rowHeights?.firstOrNull()).dp +
        keyGapV(settings) * 2 + KeyRowsPadVertical * 2
}

/**
 * The switch as a fixed-size pill for a search row: in the full-bleed
 * header, or at the end of the GIF and sticker search bar.
 */
@Composable
internal fun MediaTabsPill(state: KeyboardUiState, onPanelChange: (PanelMode) -> Unit) {
    val count = mediaTabs(state.settings).size
    if (count < 2) return
    MediaTabsField(
        state, onPanelChange,
        Modifier
            .width(MediaPillSegmentWidth * count)
            .height(MediaPillHeight),
    )
}

private val MediaPillSegmentWidth = 36.dp
private val MediaPillHeight = 32.dp

/**
 * The switch itself, filling what it is given. A segment for the open panel
 * does nothing when pressed: the switch changes panels, it never closes one,
 * which is what abc and the back button are for.
 */
@Composable
internal fun MediaTabsField(
    state: KeyboardUiState,
    onPanelChange: (PanelMode) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val tabs = mediaTabs(state.settings)
    if (tabs.size < 2) return
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (panel in tabs) {
            val tool = panel.mediaTool() ?: continue
            val selected = panel == state.panel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(if (selected) kb.chipActive else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab) {
                        if (!selected) onPanelChange(panel)
                    },
                contentAlignment = Alignment.Center,
            ) {
                SlotIcon(
                    IconSlots.forTool(tool),
                    contentDescription = toolLabel(tool),
                    modifier = Modifier.size(18.dp),
                    tint = if (selected) kb.chipActiveText else kb.chipText,
                )
            }
        }
    }
}
