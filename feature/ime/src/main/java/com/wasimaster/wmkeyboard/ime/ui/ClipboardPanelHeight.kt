package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.ime.R

/**
 * The clipboard panel's height (#414): a bar on top of the panel that drags
 * it taller than the keyboard, so there is less to scroll, and back down. What
 * the drag lands on is [ClipboardSettings.panelExtraHeightDp], the same value
 * the settings screen's slider sets.
 *
 * The panel grows the IME window, and a window that changes height on every
 * frame of a drag re-lays out the app under it each time and moves the bar
 * under the finger — the resize tool's jitter ([resizeHeadroom]). So for the
 * length of a drag the frame holds the window at the tallest the panel can
 * get, through the bar rows' own [RowRevealHeadroom]: [hold] is short of that
 * by exactly what the panel is short of it, so the window changes height once
 * when the drag starts and once when it ends, and the panel grows and shrinks
 * inside a frame that holds still.
 *
 * The composition half is [liveDp] and [pendingDp]; the rest is scratch the
 * gesture and the panel's measure write, and nothing composes from it (the
 * floating frame's `FloatingGesture` trick).
 */
@Stable
internal class ClipPanelResize(private val headroom: RowRevealHeadroom?) {
    /** The height being dragged to, in dp over the keyboard's; null between drags. */
    var liveDp by mutableStateOf<Float?>(null)

    /**
     * What the last drag wrote, shown until the setting comes back with it, so
     * letting go never flashes the panel at its old height for the DataStore's
     * round trip.
     */
    var pendingDp by mutableStateOf<Int?>(null)

    /** The frame's hold while a drag is on, in the frame's headroom. */
    val hold = RowReveal()
    private var held = false
    private var startDp = 0f
    private var travelDp = 0f

    /** The panel's height as last measured, and at its tallest, in px. */
    var panelPx = 0
    var maxPanelPx = 0

    fun shownDp(setting: Int): Float = liveDp ?: pendingDp?.toFloat() ?: setting.toFloat()

    fun begin(fromDp: Float) {
        startDp = fromDp
        travelDp = 0f
        liveDp = fromDp
        if (headroom != null && !held) {
            hold.shortfallPx = (maxPanelPx - panelPx).coerceAtLeast(0)
            headroom.track(hold)
            held = true
        }
    }

    /** [dyDp] as the finger moved it: up, negative, makes the panel taller. */
    fun drag(dyDp: Float, maxDp: Float) {
        travelDp += dyDp
        liveDp = (startDp - travelDp).coerceIn(0f, maxDp)
    }

    fun end(onCommit: (Int) -> Unit) {
        val dp = liveDp ?: return release()
        val landed = dp.roundToInt()
        pendingDp = landed
        liveDp = null
        onCommit(landed)
        release()
    }

    /** Lets go of the window: it settles at whatever the panel is now, in one resize. */
    fun release() {
        if (!held) return
        headroom?.untrack(hold)
        hold.shortfallPx = 0
        held = false
    }

    /** The panel's measure, reporting its height and, mid-drag, what the hold has to cover. */
    fun measured(heightPx: Int) {
        panelPx = heightPx
        if (held) hold.shortfallPx = (maxPanelPx - heightPx).coerceAtLeast(0)
    }
}

@Composable
internal fun rememberClipPanelResize(setting: Int): ClipPanelResize {
    val headroom = LocalRowRevealHeadroom.current
    val resize = remember(headroom) { ClipPanelResize(headroom) }
    // The setting came back from the drag's write, or was changed on the
    // settings screen: either way it is the truth again.
    LaunchedEffect(resize, setting) { resize.pendingDp = null }
    // A panel closed mid-drag must not leave the window held open.
    DisposableEffect(resize) { onDispose { resize.release() } }
    return resize
}

/**
 * The panel's own measure, for [ClipPanelResize.measured]. Remember it: a
 * `layout {}` modifier built in composition is a new modifier each time, and
 * Compose re-measures the node for it.
 */
internal fun Modifier.clipPanelHeightProbe(resize: ClipPanelResize): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    resize.measured(placeable.height)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The bar itself: a short pill across the top of the panel, the whole band
 * its hit target. [extra] is the height over the keyboard's the panel has now,
 * [maxExtra] the most the screen allows; a screen reader gets Taller and
 * Shorter, a row's worth at a time, in place of the drag.
 */
@Composable
internal fun ClipPanelHeightBar(
    resize: ClipPanelResize,
    extra: Dp,
    maxExtra: Dp,
    onCommit: (Int) -> Unit,
) {
    val kb = LocalKbTheme.current
    val current by rememberUpdatedState(extra.value)
    val max by rememberUpdatedState(maxExtra.value)
    val commit by rememberUpdatedState(onCommit)
    val description = stringResource(R.string.ime_clipboard_height_desc)
    val taller = stringResource(R.string.ime_clipboard_height_taller)
    val shorter = stringResource(R.string.ime_clipboard_height_shorter)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ClipPanelBarHeight)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(taller) {
                        commit((current + ClipPanelHeightStepDp).coerceAtMost(max).roundToInt()); true
                    },
                    CustomAccessibilityAction(shorter) {
                        commit((current - ClipPanelHeightStepDp).coerceAtLeast(0f).roundToInt()); true
                    },
                )
            }
            .pointerInput(resize) {
                detectVerticalDragGestures(
                    onDragStart = { resize.begin(current.coerceIn(0f, max)) },
                    onDragEnd = { resize.end(commit) },
                    onDragCancel = { resize.end(commit) },
                ) { change, dy ->
                    change.consume()
                    resize.drag(dy.toDp().value, max)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(kb.secondaryText.copy(alpha = 0.5f)),
        )
    }
}

/** The bar's band, taken out of the panel's height rather than added to it. */
internal val ClipPanelBarHeight = 16.dp

/** How far a screen reader's Taller or Shorter moves the panel. */
private const val ClipPanelHeightStepDp = 48f
