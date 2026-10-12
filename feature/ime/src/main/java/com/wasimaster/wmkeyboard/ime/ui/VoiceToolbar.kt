package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Menu
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.dictationLanguages
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.VoiceBarAction
import com.wasimaster.wmkeyboard.ime.VoiceStatus
import com.wasimaster.wmkeyboard.ime.clipBased
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The collapsed voice bar (Gboard's voice-typing toolbar): the keyboard gives
 * its whole window to a floating pill of dictation controls, so the app behind
 * gets the screen back while the user talks. The service keeps the touchable
 * region down to the pill itself — everything around it falls through.
 *
 * Both orientations share one placement engine: drag the pill anywhere; on
 * release the horizontal pill snaps to the nearest of three rests and keeps
 * its height, the vertical pill docks to the nearer screen edge and keeps its
 * position along it. The rest lives in local state first and persists behind
 * ([VoiceBarAction.SetRest]) — deriving it live from the settings made the
 * pill spring back to its old edge while the DataStore write was in flight.
 */
@Composable
internal fun VoiceBarLayer(
    state: KeyboardUiState,
    onToggle: () -> Unit,
    onUndo: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onRestoreKeyboard: () -> Unit,
    onAction: (VoiceBarAction) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(hasVoiceBarMicPermission(context)) }
    // The permission dialog lives in a trampoline activity; re-check when the
    // keyboard comes back to the foreground afterwards (same as the panel).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = hasVoiceBarMicPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val vertical = state.settings.voiceBar.vertical
    val kb = LocalKbTheme.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .windowInsetsPadding(navigationBarInsets())
            .padding(VoiceBarMargin),
    ) {
        val boxW = constraints.maxWidth
        val boxH = constraints.maxHeight
        // Seeded from the persisted settings once per orientation; drags move
        // it immediately and persist behind. Not re-read afterwards — see the
        // class doc for the jump-back this prevents.
        val rest = remember(vertical) { VoiceBarRest(state.settings.voiceBar) }
        val pillSize = remember { mutableStateOf(IntSize.Zero) }
        // Live drag position; null = resting (or settling toward the rest).
        val dragPos = remember { mutableStateOf<Offset?>(null) }
        val settle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        var settling by remember { mutableStateOf(false) }
        val drag = remember { VoiceBarDrag() }

        fun slackX() = (boxW - pillSize.value.width).coerceAtLeast(0).toFloat()
        fun slackY() = (boxH - pillSize.value.height).coerceAtLeast(0).toFloat()
        fun restOffset(): Offset = if (vertical) {
            Offset(if (rest.rightEdge) slackX() else 0f, rest.yBias * slackY())
        } else {
            val x = when (rest.snap) {
                VoiceBarSettings.SNAP_LEFT -> 0f
                VoiceBarSettings.SNAP_RIGHT -> slackX()
                else -> slackX() / 2f
            }
            Offset(x, rest.dockBias * slackY())
        }
        fun currentOffset(): Offset {
            val live = dragPos.value
            return when {
                live != null -> Offset(
                    live.x.coerceIn(0f, slackX()),
                    live.y.coerceIn(0f, slackY()),
                )
                settling -> settle.value
                else -> restOffset()
            }
        }

        // Keyed on the orientation: flipping it recreates the pill, so it
        // runs its entrance again at the new shape and place — an instant
        // reshape mid-screen read as a glitch, a fresh rise reads as intent.
        key(vertical) {
            VoiceBarPill(
                placed = pillSize.value != IntSize.Zero,
                modifier = Modifier
                    .then(if (vertical) Modifier else Modifier.widthIn(max = VoiceBarMaxWidth))
                    .offset {
                        val o = currentOffset()
                        IntOffset(o.x.roundToInt(), o.y.roundToInt())
                    }
                    .onGloballyPositioned { coords ->
                        pillSize.value = coords.size
                        drag.record(coords.positionInWindow(), coords.size)
                        // Publishing mid-gesture or mid-settle would force a decor
                        // layout pass per frame; the region cannot matter while the
                        // finger is captured, so only the resting pill publishes.
                        if (!drag.active && !settling) drag.publish(onAction)
                    }
                    .pointerInput(vertical) {
                        detectDragGestures(
                            onDragStart = {
                                drag.active = true
                                settling = false
                                dragPos.value = currentOffset()
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                val current = dragPos.value ?: return@detectDragGestures
                                dragPos.value = Offset(
                                    (current.x + delta.x).coerceIn(0f, slackX()),
                                    (current.y + delta.y).coerceIn(0f, slackY()),
                                )
                            },
                            onDragCancel = {
                                drag.active = false
                                dragPos.value = null
                            },
                            onDragEnd = {
                                drag.active = false
                                val end = dragPos.value ?: return@detectDragGestures
                                rest.settleFrom(
                                    end = end,
                                    vertical = vertical,
                                    pillWidth = pillSize.value.width,
                                    boxWidth = boxW,
                                    slackX = slackX(),
                                    slackY = slackY(),
                                )
                                onAction(
                                    VoiceBarAction.SetRest(
                                        snap = rest.snap,
                                        rightEdge = rest.rightEdge,
                                        yBias = rest.yBias,
                                        dockBias = rest.dockBias,
                                    ),
                                )
                                scope.launch {
                                    settling = true
                                    settle.snapTo(currentOffset())
                                    dragPos.value = null
                                    if (kb.reduceMotion) {
                                        settle.snapTo(restOffset())
                                    } else {
                                        settle.animateTo(restOffset(), VoiceBarSettleSpring)
                                    }
                                    settling = false
                                    drag.publish(onAction)
                                }
                            },
                        )
                    },
            ) {
                if (vertical) {
                    VerticalBarContent(
                        state = state,
                        hasPermission = hasPermission,
                        onToggle = onToggle,
                        onRequestPermission = onRequestPermission,
                        onOpenVoiceSettings = onOpenVoiceSettings,
                        onRestoreKeyboard = onRestoreKeyboard,
                        onAction = onAction,
                        onUndo = onUndo,
                    )
                } else {
                    HorizontalBarContent(
                        state = state,
                        hasPermission = hasPermission,
                        onToggle = onToggle,
                        onUndo = onUndo,
                        onRequestPermission = onRequestPermission,
                        onOpenVoiceSettings = onOpenVoiceSettings,
                        onRestoreKeyboard = onRestoreKeyboard,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

/** The pill's resting place, local-first. Written by drags, persisted behind. */
private class VoiceBarRest(seed: VoiceBarSettings) {
    var snap = seed.snap
    var rightEdge = seed.rightEdge
    var yBias = seed.yBias.coerceIn(0f, 1f)
    var dockBias = seed.dockBias.coerceIn(0f, 1f)
}

/** Where a released drag comes to rest, per orientation. */
private fun VoiceBarRest.settleFrom(
    end: Offset,
    vertical: Boolean,
    pillWidth: Int,
    boxWidth: Int,
    slackX: Float,
    slackY: Float,
) {
    if (vertical) {
        // Whichever half of the screen the pill's centre ends in is the
        // edge it docks to.
        rightEdge = end.x + pillWidth / 2f > boxWidth / 2f
        yBias = if (slackY > 0f) (end.y / slackY).coerceIn(0f, 1f) else 0.5f
    } else {
        snap = nearestSnap(end.x, slackX)
        dockBias = if (slackY > 0f) (end.y / slackY).coerceIn(0f, 1f) else 1f
    }
}

/** The closest of the horizontal pill's three rests to a released drag. */
private fun nearestSnap(x: Float, slackX: Float): Int = listOf(
    VoiceBarSettings.SNAP_LEFT to 0f,
    VoiceBarSettings.SNAP_CENTER to slackX / 2f,
    VoiceBarSettings.SNAP_RIGHT to slackX,
).minByOrNull { (_, anchor) -> abs(anchor - x) }?.first ?: VoiceBarSettings.SNAP_CENTER

/** Scratch state for one bar drag. Deliberately not snapshot state. */
private class VoiceBarDrag {
    /** A finger is moving the pill right now. */
    var active = false
    /** Latest measured pill rectangle, published when the pill is at rest. */
    var left = 0
    var top = 0
    var right = 0
    var bottom = 0
    var measured = false
}

private fun VoiceBarDrag.publish(onAction: (VoiceBarAction) -> Unit) {
    if (measured) onAction(VoiceBarAction.Bounds(left, top, right, bottom))
}

private fun VoiceBarDrag.record(position: Offset, size: IntSize) {
    left = position.x.roundToInt()
    top = position.y.roundToInt()
    right = left + size.width
    bottom = top + size.height
    measured = true
}

/**
 * The pill chrome both orientations share: entrance animation, theme
 * background (colour, gradient or photo — the same painter as the keyboard
 * board, clipped to the pill), border and shadow. Invisible until [placed] —
 * the first frame measures at a provisional offset, and flashing there reads
 * as the pill teleporting.
 */
@Composable
private fun VoiceBarPill(
    placed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    // Enter as one gesture: fade, rise and unshrink together. Draw-phase
    // reads only, so the entrance never moves the published touch bounds.
    val appear = remember { Animatable(if (kb.reduceMotion) 1f else 0f) }
    LaunchedEffect(placed) {
        if (placed && appear.value < 1f) {
            appear.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    val riseDistance = with(LocalDensity.current) { VoiceBarRise.toPx() }
    Surface(
        modifier = modifier.graphicsLayer {
            val seen = if (placed) appear.value else 0f
            alpha = seen
            translationY = (1f - seen) * riseDistance
            scaleX = 0.92f + 0.08f * seen
            scaleY = 0.92f + 0.08f * seen
        },
        shape = shape,
        // The theme paints the pill (colour + optional image); Surface just
        // supplies the shape, clip and shadow — same split as floating mode.
        color = Color.Transparent,
        shadowElevation = elevationFor(kb.cardShapeKind, 8.dp),
    ) {
        Box(modifier = Modifier.clip(shape).popupBorder(LocalKbTheme.current, shape)) {
            BoardBackground(LocalKbTheme.current)
            content()
        }
    }
}

@Composable
private fun HorizontalBarContent(
    state: KeyboardUiState,
    hasPermission: Boolean,
    onToggle: () -> Unit,
    onUndo: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onRestoreKeyboard: () -> Unit,
    onAction: (VoiceBarAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    // Two bar pages, Gboard style: the hamburger swaps the whole row for the
    // menu row and back. Cross-dissolved by hand — the house style animates
    // paint, never measured size (see the KeyboardScreen notes).
    var shownMenu by remember { mutableStateOf(false) }
    val pageAlpha = remember { Animatable(1f) }
    LaunchedEffect(menuOpen, kb.reduceMotion) {
        if (shownMenu != menuOpen) {
            if (!kb.reduceMotion) {
                pageAlpha.animateTo(0f, tween(VoiceBarPageFadeOutMs))
            }
            shownMenu = menuOpen
            if (kb.reduceMotion) {
                pageAlpha.snapTo(1f)
            } else {
                pageAlpha.animateTo(1f, tween(VoiceBarPageFadeInMs))
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(VoiceBarHeight)
            .graphicsLayer { alpha = pageAlpha.value }
            .padding(horizontal = 6.dp),
    ) {
        if (shownMenu) {
            HorizontalMenuPage(
                state = state,
                onBack = { menuOpen = false },
                onUndo = onUndo,
                onVertical = { onAction(VoiceBarAction.SetVertical(true)) },
                onOpenVoiceSettings = onOpenVoiceSettings,
                onAction = onAction,
                closeMenu = { menuOpen = false },
            )
        } else {
            VoiceBarIconButton(
                icon = Icons.Outlined.Menu,
                description = stringResource(R.string.ime_voice_bar_menu_desc),
            ) {
                feedback()
                menuOpen = true
            }
            VoiceBarStatus(
                state = state,
                hasPermission = hasPermission,
                onRequestPermission = onRequestPermission,
                onOpenVoiceSettings = onOpenVoiceSettings,
                modifier = Modifier.weight(1f),
            )
            if (hasPermission && state.voiceMicSwitchVisible()) {
                VoiceMicSwitch(state, onCycle = { onAction(VoiceBarAction.CycleMic) }, size = VoiceBarButton)
            }
            val voice = state.voice
            val idle = voice.status != VoiceStatus.LISTENING &&
                voice.status != VoiceStatus.FINISHING &&
                voice.status != VoiceStatus.TRANSCRIBING
            if (voice.canUndo && idle) {
                VoiceBarIconButton(
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    description = stringResource(R.string.ime_voice_strip_undo_desc),
                ) {
                    feedback()
                    onUndo()
                }
            }
            VoiceBarExitButton(state, onAction, onRestoreKeyboard)
            VoiceBarDeleteButton(onAction)
            VoiceBarMic(
                state = state,
                hasPermission = hasPermission,
                onToggle = onToggle,
                onRequestPermission = onRequestPermission,
            )
        }
    }
}

/** The hamburger's page: back, undo, language, upright toggle, settings. */
@Composable
private fun RowScope.HorizontalMenuPage(
    state: KeyboardUiState,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onVertical: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onAction: (VoiceBarAction) -> Unit,
    closeMenu: () -> Unit,
) {
    val feedback = LocalKeyPressFeedback.current
    VoiceBarIconButton(
        icon = Icons.AutoMirrored.Outlined.ArrowBack,
        description = stringResource(R.string.ime_voice_bar_menu_back_desc),
    ) {
        feedback()
        onBack()
    }
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceBarIconButton(
            icon = Icons.AutoMirrored.Outlined.Undo,
            description = stringResource(R.string.ime_voice_strip_undo_desc),
            enabled = state.voice.canUndo,
        ) {
            feedback()
            onUndo()
            closeMenu()
        }
        VoiceBarLanguageChip(state, onAction, onOpenVoiceSettings, closeMenu)
        VoiceBarIconButton(
            icon = Icons.Outlined.SwapVert,
            description = stringResource(R.string.ime_voice_bar_vertical_desc),
        ) {
            feedback()
            onVertical()
        }
        VoiceBarIconButton(
            icon = Icons.Outlined.Settings,
            description = stringResource(R.string.ime_voice_strip_settings_action),
        ) {
            feedback()
            onOpenVoiceSettings()
        }
    }
}

@Composable
private fun VerticalBarContent(
    state: KeyboardUiState,
    hasPermission: Boolean,
    onToggle: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onRestoreKeyboard: () -> Unit,
    onAction: (VoiceBarAction) -> Unit,
    onUndo: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var shownMenu by remember { mutableStateOf(false) }
    val pageAlpha = remember { Animatable(1f) }
    LaunchedEffect(menuOpen, kb.reduceMotion) {
        if (shownMenu != menuOpen) {
            if (!kb.reduceMotion) {
                pageAlpha.animateTo(0f, tween(VoiceBarPageFadeOutMs))
            }
            shownMenu = menuOpen
            if (kb.reduceMotion) {
                pageAlpha.snapTo(1f)
            } else {
                pageAlpha.animateTo(1f, tween(VoiceBarPageFadeInMs))
            }
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .width(VoiceBarHeight)
            .graphicsLayer { alpha = pageAlpha.value }
            .padding(vertical = 8.dp),
    ) {
        if (shownMenu) {
            VoiceBarIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.ime_voice_bar_menu_back_desc),
            ) {
                feedback()
                menuOpen = false
            }
            VoiceBarIconButton(
                icon = Icons.AutoMirrored.Outlined.Undo,
                description = stringResource(R.string.ime_voice_strip_undo_desc),
                enabled = state.voice.canUndo,
            ) {
                feedback()
                onUndo()
                menuOpen = false
            }
            VoiceBarLanguageChip(state, onAction, onOpenVoiceSettings) { menuOpen = false }
            VoiceBarIconButton(
                icon = Icons.Outlined.SwapHoriz,
                description = stringResource(R.string.ime_voice_bar_horizontal_desc),
            ) {
                feedback()
                onAction(VoiceBarAction.SetVertical(false))
            }
            VoiceBarIconButton(
                icon = Icons.Outlined.Settings,
                description = stringResource(R.string.ime_voice_strip_settings_action),
            ) {
                feedback()
                onOpenVoiceSettings()
            }
        } else {
            VoiceBarMic(
                state = state,
                hasPermission = hasPermission,
                onToggle = onToggle,
                onRequestPermission = onRequestPermission,
            )
            VoiceBarDeleteButton(onAction)
            VoiceBarExitButton(state, onAction, onRestoreKeyboard)
            // No status line on the upright bar, so the switch is the only
            // word on which microphone is in use (#581): its icon.
            if (hasPermission && state.voiceMicSwitchVisible()) {
                VoiceMicSwitch(state, onCycle = { onAction(VoiceBarAction.CycleMic) }, size = VoiceBarButton)
            }
            VoiceBarIconButton(
                icon = Icons.Outlined.Menu,
                description = stringResource(R.string.ime_voice_bar_menu_desc),
            ) {
                feedback()
                menuOpen = true
            }
        }
    }
}

/**
 * The bar's one exit control, chosen by how the bar was entered. An inline
 * visit (the panel's or strip's collapse button) shows the double-arrow-up
 * expand: back to the surface it replaced, undoing the inline switch. A bar
 * picked in settings shows the keyboard button: keys back, bar stays the
 * default.
 */
@Composable
private fun VoiceBarExitButton(
    state: KeyboardUiState,
    onAction: (VoiceBarAction) -> Unit,
    onRestoreKeyboard: () -> Unit,
) {
    val feedback = LocalKeyPressFeedback.current
    if (state.voice.barInline) {
        val returnMode = state.settings.voiceBar.returnMode
        VoiceBarIconButton(
            icon = Icons.Outlined.KeyboardDoubleArrowUp,
            description = if (returnMode == VoiceBarSettings.MODE_STRIP) {
                stringResource(R.string.ime_voice_bar_expand_strip_desc)
            } else {
                stringResource(R.string.ime_voice_bar_expand_panel_desc)
            },
        ) {
            feedback()
            onAction(VoiceBarAction.SwitchSurface(returnMode))
        }
    } else {
        VoiceBarIconButton(
            icon = Icons.Outlined.Keyboard,
            description = stringResource(R.string.ime_voice_bar_restore_desc),
            onClick = onRestoreKeyboard,
        )
    }
}

/**
 * The status line and its one remedy chip, exactly the strip's cascade so the
 * two dictation bars never disagree about the same session.
 */
@Composable
private fun VoiceBarStatus(
    state: KeyboardUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val voice = state.voice
    val listening = voice.status == VoiceStatus.LISTENING
    val transcribing = voice.status == VoiceStatus.TRANSCRIBING

    // Which *kind* of line is up. Fading keyed on this rather than the text
    // keeps a growing partial from strobing the line on every word.
    // The strip's rule (#581): the line says which microphone is in use
    // until the first words arrive.
    val micNotice = rememberVoiceMicNotice(state)
    val statusKind = when {
        voice.status == VoiceStatus.NEED_PERMISSION || !hasPermission -> 1
        voice.status == VoiceStatus.UNAVAILABLE -> 2
        voice.status == VoiceStatus.MIC_BLOCKED -> 10
        voice.whisperNeedsModel -> 3
        voice.serverNeedsSetup -> 11
        micNotice != null && voice.partial.isEmpty() && voice.status != VoiceStatus.ERROR -> 13
        listening && voice.clipBased && voice.secondsLeft > 0 -> 12
        listening && voice.clipBased -> 4
        listening -> 5
        transcribing -> 6
        voice.status == VoiceStatus.FINISHING -> 7
        voice.status == VoiceStatus.ERROR -> 8
        else -> 9
    }
    val statusAlpha = remember { Animatable(1f) }
    LaunchedEffect(statusKind, kb.reduceMotion) {
        if (!kb.reduceMotion) {
            statusAlpha.snapTo(0f)
            statusAlpha.animateTo(1f, tween(VoiceBarStatusFadeMs))
        } else {
            statusAlpha.snapTo(1f)
        }
    }

    val speakNow = stringResource(R.string.ime_voice_bar_speak_now)
    val statusText = when (statusKind) {
        1 -> stringResource(R.string.ime_voice_strip_permission)
        2 -> stringResource(R.string.ime_voice_strip_unavailable)
        10 -> stringResource(R.string.ime_voice_strip_mic_blocked)
        3 -> stringResource(R.string.ime_voice_strip_no_model)
        11 -> stringResource(R.string.ime_voice_strip_no_server)
        12 -> pluralStringResource(R.plurals.ime_voice_status_stops_in, voice.secondsLeft, voice.secondsLeft)
        13 -> micNotice.orEmpty()
        4 -> stringResource(R.string.ime_voice_strip_listening_hint)
        5 -> voice.partial.ifEmpty { speakNow }
        6 -> stringResource(voice.transcribingLabelRes())
        7 -> "…"
        8 -> voice.errorMessage ?: stringResource(R.string.ime_voice_status_error)
        else -> stringResource(R.string.ime_voice_bar_paused)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 6.dp),
    ) {
        Text(
            statusText,
            color = if (listening && voice.partial.isNotEmpty()) {
                kb.toolbarIcon
            } else {
                kb.secondaryText
            },
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .graphicsLayer { alpha = statusAlpha.value },
        )
        val action = when (statusKind) {
            1 -> stringResource(R.string.ime_voice_strip_allow_action) to onRequestPermission
            3, 11 -> stringResource(R.string.ime_voice_strip_settings_action) to onOpenVoiceSettings
            else -> null
        }
        if (action != null) {
            Text(
                action.first,
                color = kb.toolCircleActiveIcon,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(kb.toolShape())
                    .background(kb.toolCircleActive)
                    .clickable {
                        feedback()
                        action.second()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** The mic with its level-driven double pulse ring — the bar's main control. */
@Composable
private fun VoiceBarMic(
    state: KeyboardUiState,
    hasPermission: Boolean,
    onToggle: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val voice = state.voice
    val listening = voice.status == VoiceStatus.LISTENING
    val busy = voice.status == VoiceStatus.FINISHING ||
        voice.status == VoiceStatus.TRANSCRIBING
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(VoiceBarButton)) {
        // Two rings, the outer looser than the inner, so louder speech reads
        // as a swell rather than a single hard edge. Parked static under
        // reduce motion for the same reason as the panel's ring.
        val innerScale by animateFloatAsState(
            targetValue = when {
                !listening -> 0f
                kb.reduceMotion -> 1.1f
                else -> 1f + voice.level * 0.45f
            },
            animationSpec = if (kb.reduceMotion) snap() else spring(stiffness = 220f),
            label = "voiceBarPulseInner",
        )
        val outerScale by animateFloatAsState(
            targetValue = when {
                !listening -> 0f
                kb.reduceMotion -> 1.2f
                else -> 1f + voice.level * 0.85f
            },
            animationSpec = if (kb.reduceMotion) snap() else spring(stiffness = 140f),
            label = "voiceBarPulseOuter",
        )
        if (listening) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .scale(outerScale)
                    .background(kb.accent.copy(alpha = 0.12f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .scale(innerScale)
                    .background(kb.accent.copy(alpha = 0.25f), CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (listening) kb.toolCircleActive else kb.chip)
                .clickable(enabled = !busy) {
                    if (hasPermission) onToggle() else onRequestPermission()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = kb.accent,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = if (listening) {
                        stringResource(R.string.ime_voice_stop_desc)
                    } else {
                        stringResource(R.string.ime_voice_start_desc)
                    },
                    modifier = Modifier.size(20.dp),
                    tint = if (listening) kb.toolCircleActiveIcon else kb.secondaryText,
                )
            }
        }
    }
}

/** Backspace with the rail's press-and-hold repeat. */
@Composable
private fun VoiceBarDeleteButton(onAction: (VoiceBarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val scope = rememberCoroutineScope()
    val description = stringResource(CommonR.string.common_delete)
    Box(
        modifier = Modifier
            .size(VoiceBarButton)
            .clip(kb.toolShape())
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        feedback()
                        onAction(VoiceBarAction.RailKey(Key("⌫", action = KeyAction.Delete)))
                        val repeat: Job = scope.launch {
                            delay(400)
                            while (true) {
                                onAction(
                                    VoiceBarAction.RailKey(Key("⌫", action = KeyAction.Delete)),
                                )
                                delay(120)
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Backspace,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = kb.toolbarIcon,
        )
    }
}

/**
 * The language dictation listens for (#416), the panel chip's twin: shown
 * while there is a choice to make, and a tap opens the same menu of them.
 */
@Composable
private fun VoiceBarLanguageChip(
    state: KeyboardUiState,
    onAction: (VoiceBarAction) -> Unit,
    onOpenVoiceSettings: () -> Unit,
    closeMenu: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val chosen = state.settings.whisper.languages
    val enabled = state.settings.enabledLanguages.map { it.id }
    if (enabled.size < 2 && chosen.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Text(
            text = voiceLanguagesLabel(state.settings.whisper.dictationLanguages(state.language.id)),
            color = if (chosen.isEmpty()) kb.secondaryText else kb.toolbarIcon,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clip(kb.chipShape())
                .background(kb.chip)
                .chipBorder(kb, kb.chipShape())
                .clickable(onClickLabel = stringResource(R.string.ime_voice_language_menu_title)) {
                    feedback()
                    menuOpen = true
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        if (menuOpen) {
            StripMenuScrim(onDismiss = { menuOpen = false })
            VoiceLanguageMenu(
                chosen = chosen,
                enabled = enabled,
                onDismiss = { menuOpen = false },
                onPick = { ids ->
                    menuOpen = false
                    onAction(VoiceBarAction.PickLanguages(ids))
                    closeMenu()
                },
                onMore = {
                    menuOpen = false
                    feedback()
                    onOpenVoiceSettings()
                },
            )
        }
    }
}

/** One round bar button. Disabled draws at reduced alpha and swallows taps. */
@Composable
private fun VoiceBarIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .size(VoiceBarButton)
            .clip(kb.toolShape())
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
            tint = kb.toolbarIcon,
        )
    }
}

private fun hasVoiceBarMicPermission(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private val VoiceBarHeight = 56.dp
private val VoiceBarButton = 44.dp
private val VoiceBarMargin = 8.dp
private val VoiceBarMaxWidth = 420.dp

/** How far below its rest the pill starts its entrance. */
private val VoiceBarRise = 24.dp

// Page swaps reuse the strip-content rhythm: quick out, calm in.
private const val VoiceBarPageFadeOutMs = 110
private const val VoiceBarPageFadeInMs = 200
private const val VoiceBarStatusFadeMs = 180

/** The settle after a drag: firm, one soft overshoot at most. */
private val VoiceBarSettleSpring =
    spring<Offset>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
