package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaActionSound
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cameraswitch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashAuto
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ImageSearch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A photo sitting in the confirm step: the file on disk, its preview, and
 * where the visible region sat in panel coordinates so the confirm step
 * can draw it exactly where the viewfinder showed it.
 *
 * Both offsets are null for a whole-frame capture (the full-frame setting):
 * that photo has no viewfinder slice to line up with, so the confirm step
 * fits it into the panel box instead.
 */
private class PendingCapture(
    val file: File,
    val bitmap: Bitmap,
    val visibleOffsetY: Int?,
    val visibleHeight: Int?,
)

/**
 * Where the panel box sits on screen. The viewfinder overflows the box
 * (width-filling FILL_CENTER spill), so both the capture crop and the
 * overlay positions are computed against the window, not the box.
 */
private class PanelGeometry(
    val boxTopWindow: Float,
    val boxTopRoot: Float,
    val width: Int,
    val height: Int,
    val windowHeight: Int,
)

/**
 * In-keyboard camera. The live preview fills the tool viewbox and the
 * captured photo is centre-cropped to that exact aspect ratio — what the
 * viewfinder frames is what gets sent, unless the full-frame setting says
 * to send the whole 4:3 picture. Controls: shutter, front/back
 * switch, flash mode, self-timer; after a capture, retake or send (via
 * commitContent, like clipboard images), or search by the photo when the
 * Search button is on (#349). Shutter sound and haptics are per-tool settings.
 *
 * [onSend] takes the photo and whether it is a search rather than a send.
 */
@Composable
internal fun CameraPanel(
    state: KeyboardUiState,
    onSend: (File, Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
) {
    val height = keyRowsHeight(state)
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    // The permission dialog lives in a trampoline activity; re-check when
    // the keyboard comes back to the foreground afterwards.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = hasCameraPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Old captures pile up only if the process dies mid-confirm; keep the
    // last few so recently sent URIs stay resolvable for the target app.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            captureDir(context).listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(4)
                ?.forEach { it.delete() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (hasPermission) {
            CameraContent(state = state, onSend = onSend, onClose = onClose)
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.ime_camera_permission_body),
                    color = kb.toolbarIcon,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(kb.keyRadiusDp.dp))
                        .background(kb.toolCircleActive)
                        .pointerInput(Unit) { detectTapGestures { onRequestPermission() } }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(R.string.ime_camera_permission_action),
                        color = kb.toolCircleActiveIcon,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        // Back chip for the permission screen; with the camera running,
        // CameraContent draws its own at the top of the visible viewfinder.
        if (!hasPermission) {
            val keyFeedback = LocalKeyPressFeedback.current
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                CameraChipButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    description = stringResource(R.string.ime_camera_close_desc),
                    active = false,
                ) {
                    if (state.settings.camera.haptics) keyFeedback()
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun CameraContent(
    state: KeyboardUiState,
    onSend: (File, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val provider by produceState<ProcessCameraProvider?>(null) {
        value = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    }
    var frontFacing by remember { mutableStateOf(state.settings.camera.preferFront) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    // Seeded from the stored value and written back on every change, so a
    // habitual three-second user stops re-picking it on every open.
    //
    // Written straight to the repository rather than through a callback from
    // KeyboardScreen: that function's parameter list and ServiceKeyboardContent
    // are both at the 64K method limit, and one more hop through either is not
    // worth a self-timer. The DataStore behind this is a per-process singleton,
    // so this writes to the same store the service reads.
    val settingsRepo = remember(context) { SettingsRepository(context.applicationContext) }
    var timerSeconds by remember { mutableIntStateOf(state.settings.camera.timerSeconds) }
    var countdown by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // Current optical/digital zoom, shown as a pill and driven by pinch. Reset
    // when the camera rebinds (retake / lens switch both drop back to 1x).
    var zoomRatio by remember(camera) { mutableFloatStateOf(1f) }
    var geometry by remember { mutableStateOf<PanelGeometry?>(null) }
    val windowHeight = LocalView.current.rootView.height

    // Haptics on controls, ticks and shutter — the tool setting gates it;
    // the global haptic settings still shape the actual vibration.
    val keyFeedback = LocalKeyPressFeedback.current
    val feedback = { if (state.settings.camera.haptics) keyFeedback() }
    // The shutter's answer to a tap: the button dips and springs back, and
    // when the picture is actually taken (after any countdown) the frame
    // flashes white and fades into the still. Both are draw-time values, and
    // neither holds anything up — the capture runs at once underneath.
    val motion = kb.motion.any
    val shutterScale = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    val shutterSound = remember {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }
    DisposableEffect(Unit) { onDispose { shutterSound.release() } }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // The default SurfaceView mode ignores Compose bounds inside the
            // IME window — the viewfinder painted over the whole keyboard
            // (toolbar included) and looked bigger than the actual capture
            // box. TextureView composits like a normal view.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // Send the whole frame instead of the slice the viewfinder showed.
    val fullFrame = state.settings.camera.fullFrame
    val captureMaxPx = state.settings.camera.captureMaxPx
    val imageCapture = remember(fullFrame, captureMaxPx) {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // Chat photos don't need the sensor's full 12+ MP — a bounded
            // resolution captures and processes noticeably faster.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(captureMaxPx, captureMaxPx),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .apply {
                        // The cropped default takes whatever shape the sensor
                        // offers — it gets sliced anyway. The full-frame
                        // setting promises 4:3, so ask for it.
                        if (fullFrame) {
                            setAspectRatioStrategy(
                                AspectRatioStrategy(
                                    AspectRatio.RATIO_4_3,
                                    AspectRatioStrategy.FALLBACK_RULE_AUTO,
                                )
                            )
                        }
                    }
                    .build()
            )
            .build()
    }
    imageCapture.flashMode = flashMode

    val hasFront = provider?.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA) == true
    val hasBack = provider?.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA) == true
    val selector = when {
        frontFacing && hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
        hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
        hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> null
    }
    val usingFront = selector == CameraSelector.DEFAULT_FRONT_CAMERA

    // (Re)bind on open and on lens switch; release the camera as soon as
    // the panel closes so other apps can use it. No binding while the
    // confirm step is up — the frozen photo is the whole UI.
    DisposableEffect(provider, selector, imageCapture, pending == null) {
        val cameraProvider = provider
        if (cameraProvider != null && selector != null && pending == null) {
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            camera = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            }.getOrNull()
        }
        onDispose {
            camera = null
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    fun takePhoto() {
        if (capturing) return
        feedback()
        capturing = true
        if (motion) {
            scope.launch {
                shutterScale.snapTo(SHUTTER_DIP_SCALE)
                shutterScale.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                )
            }
        }
        scope.launch {
            if (timerSeconds > 0) {
                for (second in timerSeconds downTo 1) {
                    countdown = second
                    feedback()
                    delay(1000)
                }
                countdown = 0
            }
            if (state.settings.camera.shutterSound) {
                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            }
            if (motion) {
                launch {
                    flash.snapTo(FLASH_PEAK_ALPHA)
                    flash.animateTo(0f, tween(FLASH_FADE_MS))
                }
            }
            val mirror = usingFront && state.settings.camera.mirrorFront
            val geo = geometry
            val capture = withContext(Dispatchers.IO) {
                runCancellable {
                    val proxy = imageCapture.awaitCapture(context)
                    val upright = proxy.use { it.toFramedBitmap(mirror) }
                    // Crop to the part of the width-filling viewfinder that
                    // was actually on screen — what you saw is what you get.
                    // The full-frame setting skips that and keeps the lot.
                    val slice = if (fullFrame) null else upright.cropToVisible(geo)
                    val bitmap = slice?.first ?: upright
                    val file = File(captureDir(context), "IMG_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    PendingCapture(file, bitmap, slice?.second, slice?.third)
                }.getOrNull()
            }
            if (capture != null) pending = capture
            capturing = false
        }
    }

    // Top of the visible viewfinder in panel-local px: the preview spills
    // above the panel box, so overlays anchored here sit on the picture's
    // real top edge (clamped to the compose root — touches above it die).
    val viewfinderTop = geometry?.let { g ->
        val contentTop = g.boxTopWindow + g.height / 2f - (g.width * 4f / 3f) / 2f
        val rootTop = g.boxTopWindow - g.boxTopRoot
        (max(max(contentTop, rootTop), 0f) - g.boxTopWindow).roundToInt()
    } ?: 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                geometry = PanelGeometry(
                    boxTopWindow = coords.positionInWindow().y,
                    boxTopRoot = coords.positionInRoot().y,
                    width = coords.size.width,
                    height = coords.size.height,
                    windowHeight = windowHeight,
                )
            }
            // Over everything, controls included, the way a real flash
            // whites out the whole frame. Drawn, not a layer of its own, so
            // it takes no touches.
            .drawWithContent {
                drawContent()
                val a = flash.value
                if (a > 0f) drawRect(Color.White, alpha = a)
            },
        contentAlignment = Alignment.Center,
    ) {
        val captured = pending
        if (captured != null) {
            // The still and its actions fade in under the fading flash, so
            // viewfinder to confirm step is one soft change, not a cut.
            val stillAlpha = remember(captured) { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(captured) { stillAlpha.animateTo(1f, tween(STILL_FADE_MS)) }
            val stillLayer = Modifier.graphicsLayer { alpha = stillAlpha.value }
            val sliceHeight = captured.visibleHeight
            if (sliceHeight == null) {
                // A whole-frame capture is taller than the panel box and was
                // never framed by the viewfinder, so there is no region to
                // line up with: fit all of it inside the box instead.
                Image(
                    bitmap = captured.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.ime_camera_captured_photo_desc),
                    modifier = Modifier.fillMaxSize().then(stillLayer),
                    contentScale = ContentScale.Fit,
                )
            } else {
                // Drawn exactly where the viewfinder showed this region, so the
                // confirm step is indistinguishable from the live preview. The
                // slice is taller than the panel box (the preview spills over
                // it), so measure at its real size via a layout lambda — a
                // plain height() would be coerced to the box constraints and
                // FillBounds would squish the picture into the box.
                Image(
                    bitmap = captured.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.ime_camera_captured_photo_desc),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                Constraints.fixed(constraints.maxWidth, sliceHeight)
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(0, captured.visibleOffsetY ?: 0)
                            }
                        }
                        .then(stillLayer),
                    contentScale = ContentScale.FillBounds,
                )
            }
            // The fitted photo starts at the top of the box, so the chip sits
            // there too rather than on the viewfinder's spilled-over top edge.
            BackChip(
                offsetY = if (sliceHeight == null) 0 else viewfinderTop,
                feedback = feedback,
                onClose = onClose,
            )
            // Opened from image search, the photo is for searching and Send
            // would be a surprise; otherwise Search is there when the setting
            // adds it. Retake is always first.
            val actions = buildList {
                add(CaptureAction.RETAKE)
                if (!state.cameraSearchOnly) add(CaptureAction.SEND)
                if (state.cameraSearchOnly || state.settings.camera.searchButton) add(CaptureAction.SEARCH)
            }
            val act = { action: CaptureAction ->
                when (action) {
                    CaptureAction.RETAKE -> {
                        feedback()
                        scope.launch(Dispatchers.IO) { captured.file.delete() }
                        pending = null
                    }
                    // Send's vibration comes from the service handler.
                    CaptureAction.SEND -> {
                        pending = null
                        onSend(captured.file, false)
                    }
                    // The photo stays up: the service closes the panel once
                    // the search is on its way, and a search that could not
                    // start (data saver, a failed upload) leaves it to retry.
                    CaptureAction.SEARCH -> onSend(captured.file, true)
                }
            }
            // The ring exists only on this frozen still — the live viewfinder
            // below publishes nothing, so the count drops to zero on Retake.
            PanelFocusTarget(
                panel = PanelMode.CAMERA,
                region = FocusRegion.ACTIONS,
                count = actions.size,
                columns = actions.size,
            ) { index -> actions.getOrNull(index)?.let(act) }
            val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .then(stillLayer),
                horizontalArrangement = Arrangement.spacedBy(if (actions.size > 2) 10.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEachIndexed { index, action ->
                    CaptureActionButton(
                        icon = when (action) {
                            CaptureAction.RETAKE -> Icons.Outlined.Refresh
                            CaptureAction.SEND -> Icons.AutoMirrored.Outlined.Send
                            CaptureAction.SEARCH -> Icons.Outlined.ImageSearch
                        },
                        label = stringResource(
                            when (action) {
                                CaptureAction.RETAKE -> R.string.ime_camera_retake_action
                                CaptureAction.SEND -> R.string.ime_camera_send_action
                                CaptureAction.SEARCH -> R.string.ime_camera_search_action
                            },
                        ),
                        // Send stays the main action while it is there.
                        accent = action == CaptureAction.SEND ||
                            (action == CaptureAction.SEARCH && CaptureAction.SEND !in actions),
                        focused = focusedAction == index,
                    ) { act(action) }
                }
            }
            return@Box
        }

        if (selector == null) {
            PanelCenteredMessage(
                if (provider == null) {
                    stringResource(R.string.ime_camera_starting_progress)
                } else {
                    stringResource(R.string.ime_camera_no_camera_error)
                },
            )
            return@Box
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                // Tap to focus, like every camera app.
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory
                            .createPoint(offset.x, offset.y)
                        cam.cameraControl.startFocusAndMetering(
                            androidx.camera.core.FocusMeteringAction.Builder(point).build()
                        )
                    }
                }
                // Pinch to zoom, clamped to what the lens supports.
                .pointerInput(camera) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val info = cam.cameraInfo.zoomState.value
                        val minZoom = info?.minZoomRatio ?: 1f
                        val maxZoom = info?.maxZoomRatio ?: 1f
                        val current = info?.zoomRatio ?: zoomRatio
                        val next = (current * gestureZoom).coerceIn(minZoom, maxZoom)
                        cam.cameraControl.setZoomRatio(next)
                        zoomRatio = next
                    }
                },
        )

        if (zoomRatio > 1.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.ime_camera_zoom_label, zoomRatio),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        BackChip(offsetY = viewfinderTop, feedback = feedback, onClose = onClose)

        if (countdown > 0) {
            Text(
                countdown.toString(),
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                // Glyph shadow for contrast on bright scenes — a layer
                // shadow here draws an ugly rectangle around the digit.
                style = androidx.compose.ui.text.TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        blurRadius = 12f,
                    ),
                ),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Control rail: flash and timer left, shutter centre, lens right.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f)) {
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    CameraChipButton(
                        icon = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Outlined.FlashOn
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Outlined.FlashAuto
                            else -> Icons.Outlined.FlashOff
                        },
                        description = stringResource(R.string.ime_camera_flash_desc),
                        active = flashMode != ImageCapture.FLASH_MODE_OFF,
                    ) {
                        feedback()
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                CameraChipButton(
                    icon = if (timerSeconds == 0) Icons.Outlined.TimerOff else Icons.Outlined.Timer,
                    description = stringResource(R.string.ime_camera_timer_desc),
                    active = timerSeconds > 0,
                    label = if (timerSeconds > 0) {
                        stringResource(R.string.ime_camera_timer_seconds_label, timerSeconds)
                    } else {
                        null
                    },
                ) {
                    feedback()
                    val next = when (timerSeconds) {
                        0 -> 3
                        3 -> 10
                        else -> 0
                    }
                    timerSeconds = next
                    scope.launch { settingsRepo.setCameraTimerSeconds(next) }
                }
            }
            // Shutter: white ring with a fill that dims while busy. The
            // semantics lambda is not composable, so the label is read here.
            val shutterDescription = stringResource(R.string.ime_camera_shutter_desc)
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .graphicsLayer {
                        scaleX = shutterScale.value
                        scaleY = shutterScale.value
                    }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color.LightGray else Color.White)
                    .pointerInput(capturing) { detectTapGestures { takePhoto() } }
                    .semantics { contentDescription = shutterDescription },
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
            ) {
                if (hasFront && hasBack) {
                    CameraChipButton(
                        icon = Icons.Outlined.Cameraswitch,
                        description = stringResource(R.string.ime_camera_switch_desc),
                        active = usingFront,
                    ) {
                        feedback()
                        frontFacing = !usingFront
                    }
                }
            }
        }
    }
}

/** What the confirm step can do with a photo. */
private enum class CaptureAction { RETAKE, SEND, SEARCH }

/** Back-to-keys chip pinned to the top-left of the visible viewfinder. */
@Composable
private fun BoxScope.BackChip(offsetY: Int, feedback: () -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset(0, offsetY) }
            .padding(8.dp),
    ) {
        CameraChipButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            description = stringResource(R.string.ime_camera_close_desc),
            active = false,
        ) {
            feedback()
            onClose()
        }
    }
}

/** Round translucent control over the viewfinder (flash, timer, lens). */
@Composable
internal fun CameraChipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    label: String? = null,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .focusRing(focused, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            // Flash off → auto → on and the timer's steps cross-fade from
            // one face to the next; the button and its tap stay where they are.
            MotionCrossfade(
                target = CameraChipFace(icon, active, label),
                animate = LocalKbTheme.current.motion.any,
                contentAlignment = Alignment.Center,
                label = "cameraChip",
            ) { face ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        face.icon,
                        contentDescription = description,
                        modifier = Modifier.size(if (face.label == null) 20.dp else 16.dp),
                        tint = if (face.active) Color(0xFFFFD54F) else Color.White,
                    )
                    if (face.label != null) {
                        Text(face.label, color = Color.White, fontSize = 9.sp, lineHeight = 9.sp)
                    }
                }
            }
        }
    }
}

/** What a [CameraChipButton] shows; a change in any of it is a new face to fade to. */
private data class CameraChipFace(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val active: Boolean,
    val label: String?,
)

/** How far the shutter dips under the finger before springing back. */
private const val SHUTTER_DIP_SCALE = 0.86f

/** The capture flash: how white it starts, and how long it takes to clear. */
private const val FLASH_PEAK_ALPHA = 0.85f
private const val FLASH_FADE_MS = 220

/** The confirm step's still and buttons coming in under the flash. */
private const val STILL_FADE_MS = 180

/** Retake/Send pill under the frozen capture. */
@Composable
internal fun CaptureActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Boolean,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (accent) kb.accent else Color.Black.copy(alpha = 0.45f))
            .focusRing(focused, RoundedCornerShape(22.dp))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun PanelCenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = LocalKbTheme.current.toolbarIcon,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

internal fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun captureDir(context: Context): File =
    File(context.filesDir, "camera").apply { mkdirs() }

internal fun ProcessCameraProvider.hasCameraSafe(selector: CameraSelector): Boolean =
    runCatching { hasCamera(selector) }.getOrDefault(false)

/** Suspends over ImageCapture's callback API. */
internal suspend fun ImageCapture.awaitCapture(context: Context): ImageProxy =
    suspendCancellableCoroutine { continuation ->
        takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // If the capture scope was cancelled (panel closed / keyboard
                    // hidden) while the shot was in flight, the coroutine body that
                    // would close this ImageProxy never runs — close it here so the
                    // bounded capture buffer queue isn't exhausted. This callback
                    // and the scope's cancellation both run on the main executor,
                    // so the isActive check and resume can't race.
                    if (continuation.isActive) {
                        continuation.resume(image)
                    } else {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) {
                        continuation.cancel(exception)
                    }
                }
            },
        )
    }

/**
 * Crops an upright capture to the part of the viewfinder that was on
 * screen. The preview fills the panel width, overflowing vertically; the
 * visible slice runs from the window top (or the picture's own top) down
 * to the window bottom. Returns the cropped bitmap plus the slice's
 * position and height in panel-local px, for the confirm overlay. Without
 * geometry (never laid out — shouldn't happen) the full frame passes
 * through, centred.
 */
private fun Bitmap.cropToVisible(geo: PanelGeometry?): Triple<Bitmap, Int, Int> {
    if (geo == null || width == 0 || geo.width == 0) {
        return Triple(this, 0, height)
    }
    val scale = geo.width.toFloat() / width
    val contentHeight = height * scale
    val contentTop = geo.boxTopWindow + geo.height / 2f - contentHeight / 2f
    val visibleTop = max(contentTop, 0f)
    val visibleBottom = min(contentTop + contentHeight, geo.windowHeight.toFloat())
    if (visibleBottom <= visibleTop) return Triple(this, 0, height)
    val sourceTop = ((visibleTop - contentTop) / scale).roundToInt().coerceIn(0, height - 1)
    val sourceHeight = ((visibleBottom - visibleTop) / scale).roundToInt()
        .coerceIn(1, height - sourceTop)
    val cropped = if (sourceTop == 0 && sourceHeight == height) this else {
        Bitmap.createBitmap(this, 0, sourceTop, width, sourceHeight).also {
            if (it != this) recycle()
        }
    }
    return Triple(
        cropped,
        (visibleTop - geo.boxTopWindow).roundToInt(),
        (visibleBottom - visibleTop).roundToInt(),
    )
}

/**
 * Decodes, rotates upright and optionally mirrors in one createBitmap
 * call. The crop rect is normally the full frame — the tool sends what the
 * sensor saw, matching the width-filling viewfinder.
 */
internal fun ImageProxy.toFramedBitmap(mirror: Boolean): Bitmap {
    val source = toBitmap()
    // cropRect is in buffer coordinates (pre-rotation). Fall back to the
    // full frame if it is degenerate or stale for this buffer.
    val rect = android.graphics.Rect(cropRect)
    if (rect.isEmpty || rect.left < 0 || rect.top < 0 ||
        rect.right > source.width || rect.bottom > source.height
    ) {
        rect.set(0, 0, source.width, source.height)
    }
    val rotation = imageInfo.rotationDegrees
    val matrix = Matrix().apply {
        if (rotation != 0) postRotate(rotation.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    val framed = Bitmap.createBitmap(
        source, rect.left, rect.top, rect.width(), rect.height(), matrix, true,
    )
    if (framed != source) source.recycle()
    return framed
}
