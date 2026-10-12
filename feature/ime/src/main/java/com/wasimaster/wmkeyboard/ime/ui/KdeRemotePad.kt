package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeClick
import com.wasimaster.wmkeyboard.core.kdeconnect.PointerAcceleration
import com.wasimaster.wmkeyboard.core.kdeconnect.ScrollAccumulator
import com.wasimaster.wmkeyboard.core.settings.KdeConnectSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the pad reports. Plain lambdas: a drag calls [onMove] every frame. */
internal class KdePadCallbacks(
    val onMove: (Double, Double) -> Unit,
    val onScroll: (Double, Double) -> Unit,
    val onClick: (KdeClick) -> Unit,
    /** The left button went down ([Boolean] true) or came up: the two ends of a drag. */
    val onHold: (Boolean) -> Unit,
)

/**
 * The computer's touchpad (issue #285).
 *
 * The same raw gesture loop as [TrackpadField], for the same reasons — one
 * `awaitEachGesture` over every pointer, the centroid re-anchored when a finger
 * arrives or leaves — but it reports *distance*, not caret steps, and it speaks
 * a laptop touchpad's vocabulary:
 *
 * - one finger drags: move the pointer
 * - tap: click · two-finger tap: right click · three-finger tap: middle click
 * - two fingers drag: scroll
 * - hold still, then drag — or tap and drag straight after — to drag with the
 *   button held, and let go to drop
 *
 * Nothing here reads touch state in composition. The finger's dot is two floats
 * and a version the draw lambda alone observes; so is the ring a tap-click
 * throws out from where it landed, the pad's word that the click went out.
 */
@Composable
internal fun KdeRemotePad(
    settings: KdeConnectSettings,
    callbacks: KdePadCallbacks,
    modifier: Modifier = Modifier,
    hint: String = "",
    subHint: String = "",
    description: String = "",
) {
    val kb = LocalKbTheme.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val tick = LocalHapticFeedback.current
    val current = rememberUpdatedState(callbacks)
    val fingerX = remember { mutableFloatStateOf(Float.NaN) }
    val fingerY = remember { mutableFloatStateOf(0f) }
    val held = remember { mutableIntStateOf(0) }
    val accent = kb.accent
    // The click ripple: where, and how far through (1 = spent, nothing drawn).
    val motion = rememberUpdatedState(kb.motion.any)
    val rippleX = remember { mutableFloatStateOf(0f) }
    val rippleY = remember { mutableFloatStateOf(0f) }
    val ripple = remember { Animatable(1f) }
    Box(
        modifier = modifier
            .background(kb.modifierKey.copy(alpha = 0.35f), kb.keyShape())
            .semantics { contentDescription = description }
            .pointerInput(
                settings.padSensitivity, settings.padAcceleration, settings.scrollSpeed,
                settings.naturalScroll, settings.tapToClick, settings.padHaptics,
            ) {
                val acceleration = PointerAcceleration(settings.padSensitivity, settings.padAcceleration)
                val scroll = ScrollAccumulator(speed = settings.scrollSpeed, natural = settings.naturalScroll)
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                var lastTapUpMs = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    scroll.reset()
                    val downPos = down.position
                    var last = downPos
                    var lastTime = down.uptimeMillis
                    var fingers = 1
                    var maxFingers = 1
                    var moved = false
                    var dragging = false
                    // A press that lands right after a tap is the start of a
                    // tap-and-drag, if it then moves.
                    val afterTap = down.uptimeMillis - lastTapUpMs < doubleTapMs
                    fingerX.floatValue = downPos.x
                    fingerY.floatValue = downPos.y
                    fun startDrag() {
                        if (dragging) return
                        dragging = true
                        held.intValue = 1
                        if (settings.padHaptics) tick()
                        current.value.onHold(true)
                    }
                    val timer = scope.launch {
                        delay(longPressMs)
                        if (!moved && maxFingers == 1) startDrag()
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            for (change in event.changes) change.consume()
                            val count = pressed.size
                            if (count > maxFingers) maxFingers = count
                            var cx = 0f
                            var cy = 0f
                            for (change in pressed) {
                                cx += change.position.x
                                cy += change.position.y
                            }
                            cx /= count
                            cy /= count
                            val now = pressed[0].uptimeMillis
                            if (count != fingers) {
                                // A finger arriving or leaving jumps the
                                // centroid; that jump is not travel.
                                fingers = count
                                last = Offset(cx, cy)
                                lastTime = now
                                scroll.reset()
                                if (count >= 2 && !dragging) timer.cancel()
                                continue
                            }
                            val dx = cx - last.x
                            val dy = cy - last.y
                            if (!moved) {
                                if ((Offset(cx, cy) - downPos).getDistance() <= slop) continue
                                moved = true
                                if (!dragging) timer.cancel()
                                if (afterTap && maxFingers == 1) startDrag()
                                // The slop is not travel either: start from here.
                                last = Offset(cx, cy)
                                lastTime = now
                                continue
                            }
                            last = Offset(cx, cy)
                            val elapsed = (now - lastTime).coerceAtLeast(1)
                            lastTime = now
                            fingerX.floatValue = cx
                            fingerY.floatValue = cy
                            if (maxFingers >= 2 && !dragging) {
                                scroll.add(dx / density.toDouble(), dy / density.toDouble())
                                    ?.let { (sx, sy) -> current.value.onScroll(sx, sy) }
                            } else {
                                val (mx, my) = acceleration.apply(dx / density.toDouble(), dy / density.toDouble(), elapsed)
                                current.value.onMove(mx, my)
                            }
                        }
                    } finally {
                        timer.cancel()
                        fingerX.floatValue = Float.NaN
                        if (dragging) {
                            held.intValue = 0
                            current.value.onHold(false)
                        } else if (!moved && settings.tapToClick) {
                            val click = when {
                                maxFingers >= 3 -> KdeClick.MIDDLE
                                maxFingers == 2 -> KdeClick.RIGHT
                                else -> KdeClick.LEFT
                            }
                            if (settings.padHaptics) tick()
                            current.value.onClick(click)
                            if (motion.value) {
                                rippleX.floatValue = last.x
                                rippleY.floatValue = last.y
                                scope.launch {
                                    ripple.snapTo(0f)
                                    ripple.animateTo(1f, tween(RIPPLE_MS, easing = LinearOutSlowInEasing))
                                }
                            }
                            if (click == KdeClick.LEFT) lastTapUpMs = android.os.SystemClock.uptimeMillis()
                        }
                    }
                }
            }
            .drawWithCache {
                val ring = Stroke(width = 2.dp.toPx())
                val radius = 18.dp.toPx()
                val rippleReach = RIPPLE_REACH.dp.toPx()
                onDrawBehind {
                    val p = ripple.value
                    if (p < 1f) {
                        val at = Offset(rippleX.floatValue, rippleY.floatValue)
                        val r = radius + (rippleReach - radius) * p
                        drawCircle(accent.copy(alpha = 0.22f * (1f - p)), r, at)
                        drawCircle(accent.copy(alpha = 0.6f * (1f - p)), r, at, style = ring)
                    }
                    val x = fingerX.floatValue
                    if (x.isNaN()) return@onDrawBehind
                    val center = Offset(x, fingerY.floatValue)
                    if (held.intValue == 1) {
                        drawCircle(accent.copy(alpha = 0.35f), radius, center)
                    }
                    drawCircle(accent.copy(alpha = 0.7f), radius, center, style = ring)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hint.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .graphicsLayer { alpha = if (fingerX.floatValue.isNaN()) 0.6f else 0.2f },
            ) {
                Icon(Icons.Outlined.Mouse, contentDescription = null, tint = kb.modifierKeyText, modifier = Modifier.size(24.dp))
                Text(hint, color = kb.modifierKeyText, fontSize = 12.sp, textAlign = TextAlign.Center)
                if (subHint.isNotEmpty()) {
                    Text(subHint, color = kb.modifierKeyText.copy(alpha = 0.75f), fontSize = 10.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * The slideshow pointer: press and drag to show the computer's red dot and move
 * it, let go to put it away. Deltas are fractions of the pad, which the desktop
 * reads as fractions of its screen — and it takes the dot down after half a
 * second of silence, so a finger held still keeps saying "still here".
 */
@Composable
internal fun KdePointerPad(
    onMove: (Double, Double) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
) {
    val kb = LocalKbTheme.current
    val scope = rememberCoroutineScope()
    val move = rememberUpdatedState(onMove)
    val stop = rememberUpdatedState(onStop)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(kb.modifierKey.copy(alpha = 0.35f), kb.keyShape())
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last = down.position
                    val keepAlive = scope.launch {
                        while (true) {
                            move.value(0.0, 0.0)
                            delay(POINTER_KEEPALIVE_MS)
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            change.consume()
                            val dx = (change.position.x - last.x) / size.width
                            val dy = (change.position.y - last.y) / size.height
                            last = change.position
                            if (dx != 0f || dy != 0f) move.value(dx * POINTER_GAIN, dy * POINTER_GAIN)
                        }
                    } finally {
                        keepAlive.cancel()
                        stop.value()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (hint.isNotEmpty()) Text(hint, color = kb.modifierKeyText.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

private const val POINTER_KEEPALIVE_MS = 250L

/** The tap-click ripple: how long it runs, and how far (dp) it spreads. */
private const val RIPPLE_MS = 380
private const val RIPPLE_REACH = 56

/** The pad is a fraction of the phone; one sweep of it should cross most of the screen. */
private const val POINTER_GAIN = 1.2
