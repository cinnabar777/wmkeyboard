package com.wasimaster.wmkeyboard.ime.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.wasimaster.wmkeyboard.ime.CaretAnchor
import com.wasimaster.wmkeyboard.ime.CaretDragSource
import com.wasimaster.wmkeyboard.ime.CaretMagnifierState
import com.wasimaster.wmkeyboard.ime.MagnifierLine
import com.wasimaster.wmkeyboard.ime.SelectionBeyond
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * The caret magnifier's two ends (discussion #303): the drag surfaces report
 * when they start and stop moving the caret, and the bubble draws whatever the
 * service says is under it. Rides [ToolHoldCallbacks] for the reason
 * [DictionaryBarCallbacks] does: `KeyboardScreen` cannot take another parameter.
 */
@Immutable
class CaretMagnifierSeam(
    val onDrag: (CaretDragSource, Boolean) -> Unit = { _, _ -> },
    val state: StateFlow<CaretMagnifierState?> = MutableStateFlow(null),
)

/**
 * [CaretMagnifierSeam.onDrag], for the spacebar deep in the key grid. A local
 * for the reason [LocalCursorMoveVertical] is one.
 */
internal val LocalCaretDrag = staticCompositionLocalOf<(CaretDragSource, Boolean) -> Unit> { { _, _ -> } }

private val MagnifierWidth = 176.dp
private val MagnifierHeight = 48.dp
private val MagnifierGap = 12.dp
private val MagnifierFontSize = 22.sp

/** How small the bubble starts as it springs in, and how it comes and goes. */
private const val MagnifierGrowFrom = 0.8f
private const val MagnifierOutMs = 110
private val MagnifierInSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

/**
 * How the bubble follows the caret: stiff and without overshoot, so it glides
 * between the characters a drag steps through instead of jumping, yet never
 * trails the caret by more than a frame or two.
 */
private val MagnifierFollowSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 2500f,
    visibilityThreshold = Offset.VisibilityThreshold,
)

/** Wide enough for the fade to read as the line going on, narrow enough to keep the caret's neighbours. */
private const val MagnifierFadeFraction = 0.18f

/**
 * A window that never takes a touch or the focus. It only exists while a
 * finger is dragging the caret on the keyboard, so it can never sit idle over
 * the app and swallow a tap there.
 */
private val MagnifierPopupProperties = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
)

/**
 * The bubble, composed only while [state] holds something. Collected here and
 * nowhere else, so a drag redraws the bubble and not the keyboard.
 */
@Composable
internal fun CaretMagnifierHost(state: StateFlow<CaretMagnifierState?>) {
    val current by state.collectAsState()
    val motion = LocalKbTheme.current.motion.any
    // The last thing the bubble showed, kept for its way out: the service
    // clears the state the moment the finger lifts.
    val held = remember { arrayOfNulls<CaretMagnifierState>(1) }
    val live = current
    if (live != null) held[0] = live
    val up = live != null
    val appear = remember { Animatable(0f) }
    LaunchedEffect(up, motion) {
        when {
            !motion -> appear.snapTo(if (up) 1f else 0f)
            up -> appear.animateTo(1f, MagnifierInSpring)
            else -> appear.animateTo(0f, tween(MagnifierOutMs, easing = FastOutLinearInEasing))
        }
    }
    // Only the end of the way out recomposes this; the frames between are
    // read by the bubble's layer alone.
    val gone by remember { derivedStateOf { appear.value == 0f } }
    // Where the bubble is on its way to the caret, and whether it has a place
    // to start from yet: a fresh drag puts it straight on its caret, and only
    // the steps after that are smoothed.
    val follow = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val seeded = remember { BooleanArray(1) }
    if (!up && (!motion || gone)) {
        held[0] = null
        seeded[0] = false
        return
    }
    val magnifier = held[0] ?: return
    val anchor = magnifier.anchor
    LaunchedEffect(anchor, motion) {
        if (anchor == null) {
            seeded[0] = false
            return@LaunchedEffect
        }
        val to = Offset(anchor.x, anchor.top)
        if (!motion || !seeded[0]) {
            follow.snapTo(to)
            seeded[0] = true
        } else {
            follow.animateTo(to, MagnifierFollowSpring)
        }
    }
    // Read in composition on purpose: the window's place is the popup's
    // position provider, which only a recomposition hands a new one. This
    // recomposes on every step of a drag already.
    val placed = if (anchor != null && motion && seeded[0]) {
        val at = follow.value
        CaretAnchor(x = at.x, top = at.y, bottom = at.y + (anchor.bottom - anchor.top))
    } else {
        anchor
    }
    val view = LocalView.current
    val density = LocalDensity.current
    // Where this window starts on screen. The editor reports its caret in
    // screen pixels, and a popup is placed in its parent window's. Read once
    // per appearance: the keyboard's window does not move under a drag, and
    // this recomposes on every step of one.
    val origin = remember(view) {
        IntArray(2).also { view.rootView.getLocationOnScreen(it) }.let { IntOffset(it[0], it[1]) }
    }
    val gapPx = with(density) { MagnifierGap.roundToPx() }
    val provider = remember(placed, origin, gapPx) {
        MagnifierPositionProvider(anchor = placed, windowOrigin = origin, gapPx = gapPx)
    }
    Popup(popupPositionProvider = provider, properties = MagnifierPopupProperties) {
        MagnifierBubble(magnifier.line) { if (motion) appear.value else 1f }
    }
}

/**
 * Above the caret's line, or below it when the line is too near the top of
 * the screen, centred on the caret and kept on screen. With no caret to go by,
 * centred just above the keyboard.
 */
private class MagnifierPositionProvider(
    private val anchor: CaretAnchor?,
    private val windowOrigin: IntOffset,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        if (anchor == null) {
            return IntOffset(
                anchorBounds.left + (anchorBounds.width - width) / 2,
                anchorBounds.top - height - gapPx,
            )
        }
        val maxX = (windowSize.width - width).coerceAtLeast(0)
        val x = (anchor.x - windowOrigin.x - width / 2f).roundToInt().coerceIn(0, maxX)
        val above = (anchor.top - windowOrigin.y).roundToInt() - gapPx - height
        // A screen-space test: the top of the screen is where the room runs out,
        // wherever this window happens to start.
        val y = if (above + windowOrigin.y >= gapPx) {
            above
        } else {
            (anchor.bottom - windowOrigin.y).roundToInt() + gapPx
        }
        // Never over the keys: a caret the app left behind the keyboard would
        // otherwise put the bubble on top of the finger that is dragging it.
        return IntOffset(x, minOf(y, anchorBounds.top - height - gapPx))
    }
}

/**
 * The magnified line, scrolled so the caret is always in the middle, fading
 * out at both ends. Drawn in the key preview bubble's colours, so it reads as
 * part of the keyboard rather than a piece of the app.
 */
@Composable
private fun MagnifierBubble(line: MagnifierLine, appear: () -> Float) {
    val kb = LocalKbTheme.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(color = kb.popupText, fontSize = MagnifierFontSize))
    val shape = RoundedCornerShape(50)
    val textColor = kb.popupText
    val selectionColor = textColor.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            // Springs up out of the caret as the drag starts and drops back
            // into it as it ends. Per draw op, so the shadow is not cut off.
            .graphicsLayer {
                val p = appear()
                val scale = MagnifierGrowFrom + (1f - MagnifierGrowFrom) * p
                alpha = p.coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .shadow(6.dp, shape, clip = false)
            .background(kb.popup, shape)
            .clip(shape)
            .size(MagnifierWidth, MagnifierHeight)
            // Its own layer, so the fade below cuts into the text alone and
            // leaves the bubble's fill under it whole.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val layout = measurer.measure(
                    AnnotatedString(line.text),
                    style = style,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    maxLines = 1,
                )
                val caretX = layout.getHorizontalPosition(line.caret, usePrimaryDirection = true)
                val shiftX = size.width / 2 - caretX
                val shiftY = (size.height - layout.size.height) / 2
                val selection = if (line.selEnd > line.selStart) {
                    layout.getPathForRange(line.selStart, line.selEnd)
                } else {
                    null
                }
                // A selection too long to read runs from the caret off one
                // edge; the bubble shows it as a tint over that half.
                val beyond = when (line.selectedBeyond) {
                    SelectionBeyond.NONE -> null
                    SelectionBeyond.BEFORE -> 0f to size.width / 2
                    SelectionBeyond.AFTER -> size.width / 2 to size.width
                }
                val caretWidth = 2.dp.toPx()
                val caretTop = shiftY + 2.dp.toPx()
                val caretBottom = shiftY + layout.size.height - 2.dp.toPx()
                val fade = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    MagnifierFadeFraction to Color.Black,
                    1f - MagnifierFadeFraction to Color.Black,
                    1f to Color.Transparent,
                )
                onDrawBehind {
                    beyond?.let { (from, to) ->
                        drawRect(
                            selectionColor,
                            topLeft = Offset(from, shiftY),
                            size = Size(to - from, layout.size.height.toFloat()),
                        )
                    }
                    translate(shiftX, shiftY) {
                        selection?.let { drawPath(it, selectionColor) }
                        drawText(layout)
                    }
                    drawLine(
                        textColor,
                        Offset(size.width / 2, caretTop),
                        Offset(size.width / 2, caretBottom),
                        strokeWidth = caretWidth,
                    )
                    drawRect(fade, blendMode = BlendMode.DstIn)
                }
            },
    )
}
