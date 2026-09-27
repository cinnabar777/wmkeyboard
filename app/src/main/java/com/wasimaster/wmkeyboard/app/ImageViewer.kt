package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader

/**
 * A full-screen image viewer: swipe between images, pinch or double-tap to
 * zoom, drag to pan, tap or back to leave.
 *
 * Addon screenshots are shown at thumbnail size in two places — a two-column
 * catalogue card and a scrolling strip on the detail page — and a keyboard
 * screenshot at that size is a picture of a keyboard with unreadable keys.
 * Judging a theme means reading them, so the thumbnails are a way in rather
 * than the whole of it.
 *
 * The one subtle part is the gesture split with the pager, handled in
 * [zoomAndPan]: a one-finger drag at rest belongs to the pager, everything
 * else belongs to the image.
 */
@Composable
internal fun ImageViewerDialog(
    urls: List<String>,
    startIndex: Int,
    /** What a screen reader calls each image; null reads them as numbered screenshots. */
    description: String? = null,
    onDismiss: () -> Unit,
) {
    if (urls.isEmpty()) return
    val loader = rememberMediaImageLoader()
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, urls.lastIndex),
    ) { urls.size }

    // One zoom state for the viewer rather than one per page: a page can only
    // be zoomed while it is the only one on screen, because zooming is exactly
    // what stops the pager scrolling.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    fun clampOffset(candidate: Offset, atScale: Float): Offset {
        val slackX = (viewport.width * (atScale - 1f) / 2f).coerceAtLeast(0f)
        val slackY = (viewport.height * (atScale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(
            candidate.x.coerceIn(-slackX, slackX),
            candidate.y.coerceIn(-slackY, slackY),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VIEWER_SCRIM)
                .onSizeChanged { viewport = it },
        ) {
            HorizontalPager(
                state = pagerState,
                // Zoomed in, a horizontal drag is a pan. Letting the pager keep
                // it would make panning left flick to the next screenshot.
                userScrollEnabled = scale <= 1f + ZOOM_EPSILON,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = urls[page],
                    contentDescription = description ?: stringResource(
                        R.string.shell_image_viewer_page_desc,
                        page + 1,
                        urls.size,
                    ),
                    imageLoader = loader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomAndPan(
                            scale = scale,
                            onTransform = { zoomChange, panChange ->
                                val next = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
                                scale = next
                                offset = if (next <= 1f + ZOOM_EPSILON) {
                                    Offset.Zero
                                } else {
                                    clampOffset(offset + panChange, next)
                                }
                            },
                            onDoubleTap = {
                                if (scale > 1f + ZOOM_EPSILON) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                    offset = Offset.Zero
                                }
                            },
                            onTap = { if (scale <= 1f + ZOOM_EPSILON) onDismiss() },
                        )
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }

            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(CommonR.string.common_close),
                )
            }

            if (urls.size > 1) {
                Text(
                    stringResource(
                        R.string.shell_image_viewer_counter,
                        pagerState.currentPage + 1,
                        urls.size,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(VIEWER_SCRIM)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Black enough to read a screenshot against, translucent enough to place it. */
private val VIEWER_SCRIM = Color.Black.copy(alpha = 0.92f)

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

/** Float comparison slack, so "1.0000001×" still counts as not zoomed. */
private const val ZOOM_EPSILON = 0.01f

/**
 * Pinch-zoom, drag-to-pan, double-tap and tap, sharing the pointer stream with
 * a horizontal pager.
 *
 * [androidx.compose.foundation.gestures.detectTransformGestures] can't be used
 * here: it consumes every movement it sees, so the pager below would never get
 * a swipe and the viewer would be stuck on its first image. This loop consumes
 * only when the gesture is unambiguously the image's — two fingers down, or
 * already zoomed in — and lets a plain one-finger drag at rest fall through.
 */
@Composable
private fun Modifier.zoomAndPan(
    scale: Float,
    onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
    onDoubleTap: () -> Unit,
    onTap: () -> Unit,
): Modifier {
    // Read through rememberUpdatedState rather than keying the pointerInput on
    // them: scale changes on every frame of a pinch, and re-keying would tear
    // down the very gesture producing it.
    val zoomed by rememberUpdatedState(scale > 1f + ZOOM_EPSILON)
    val transform by rememberUpdatedState(onTransform)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val tap by rememberUpdatedState(onTap)
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                // Not requireUnconsumed: the tap detector below is the inner
                // node and has already taken the down, but this loop still
                // needs the drag that follows it.
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pinching = event.changes.count { it.pressed } > 1
                    if (!pinching && !zoomed) continue
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    if (zoomChange == 1f && panChange == Offset.Zero) continue
                    transform(zoomChange, panChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { doubleTap() }, onTap = { tap() })
        }
}
