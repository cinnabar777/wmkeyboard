package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.tasks.Task
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.clipboard.ClipLinks
import com.wasimaster.wmkeyboard.core.clipboard.LinkPreview
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.ocr.TesseractException
import com.wasimaster.wmkeyboard.core.ocr.TesseractOcr
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.tools.EggLinks
import com.wasimaster.wmkeyboard.core.tools.LinkPreviewClient
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardClipboard
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// ---- OCR (text recognition) tool ----

/** One recognized word, tappable in the result view. [id] is global. */
private class OcrWord(val id: Int, val text: String)

/**
 * A frozen capture with its recognized text, as lines of words. [errorRes]
 * is set when recognition failed, which is not the same as finding no text.
 */
private class OcrResult(
    val bitmap: Bitmap,
    val lines: List<List<OcrWord>>,
    @StringRes val errorRes: Int? = null,
) {
    val wordCount = lines.sumOf { it.size }
}

private sealed interface OcrStage {
    data object Viewfinder : OcrStage
    data class Recognizing(val bitmap: Bitmap) : OcrStage
    data class Done(val result: OcrResult) : OcrStage
}

/**
 * OCR tool: point the camera at printed text, capture, and get the words
 * back as tappable chips — deselect the parts you don't want, then insert
 * or copy just the rest. Reads with ML Kit's on-device Latin recognizer or
 * with Tesseract, which covers most other scripts once a language's data is
 * downloaded; the setting and the text's language pick which
 * ([OcrLanguages.tesseractPack]). Either way the photo never leaves the
 * phone. Unlike the other tool panels this one covers
 * the toolbar too ([keyRowsHeight] + [TopBarHeight]): reading text off a
 * photo needs all the room the keyboard has.
 *
 * Opened from an image clip's Extract text (#371), it reads that picture
 * ([KeyboardUiState.ocrImage]) instead: no camera and no permission, the
 * picture where the viewfinder would be, and the read starts on its own.
 */
@Composable
internal fun OcrPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
) {
    val height = keyRowsHeight(state) + fullBleedHiddenRows(state)
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    PermissionRecheck { hasPermission = hasCameraPermission(context) }

    // A picture from the clipboard needs no camera, so no camera permission.
    val canRead = hasPermission || state.ocrImage != null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (canRead) {
            OcrContent(
                state = state,
                onInsert = onInsert,
                onClose = onClose,
                autoSelect = state.settings.scanner.ocrAutoSelectWords,
            )
        } else {
            CameraPermissionPrompt(
                stringResource(R.string.ime_scanner_ocr_permission_body),
                onRequestPermission,
            )
        }
        if (!canRead) {
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                CameraChipButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    description = stringResource(R.string.ime_scanner_ocr_close_desc),
                    active = false,
                    onClick = onClose,
                )
            }
        }
    }
}

@Composable
private fun OcrContent(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onClose: () -> Unit,
    autoSelect: Boolean,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val feedback = LocalKeyPressFeedback.current

    var stage by remember { mutableStateOf<OcrStage>(OcrStage.Viewfinder) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // A clip's picture (#371) stands in for the camera, which is then never
    // bound at all. Null while it loads; a failure when it will not decode.
    val clipImage = state.ocrImage
    val clipBitmap by produceState<Result<Bitmap>?>(null, clipImage) {
        value = clipImage?.let { path ->
            withContext(Dispatchers.IO) { runCatching { decodeClipImage(File(path)) } }
        }
    }
    val provider by produceState<ProcessCameraProvider?>(null, clipImage) {
        if (clipImage == null) {
            value = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        }
    }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(Unit) {
        onDispose {
            recognizer.close()
            TesseractOcr.release()
        }
    }

    // The text's language, which picks the engine and, for Tesseract, the
    // pack. Starts as the keyboard's language; the chip steps through the
    // other enabled languages without switching the keyboard.
    val filesDir = context.filesDir
    val engine = state.settings.scanner.ocrEngine
    val chipLanguages = remember(state.settings.enabledLanguages, engine) {
        OcrLanguages.chipLanguages(engine, state.settings.enabledLanguages, TesseractOcr.available)
    }
    var ocrLanguage by remember(chipLanguages) {
        mutableStateOf(
            if (OcrLanguages.packFor(state.language) != null) state.language
            else chipLanguages.firstOrNull() ?: state.language
        )
    }
    val pack = OcrLanguages.tesseractPack(engine, ocrLanguage, TesseractOcr.available)
    val packStates by OcrPacks.states.collectAsState()
    LaunchedEffect(pack) { if (pack != null) OcrPacks.refresh(filesDir, listOf(pack)) }
    val packStatus = pack?.let { packStates[it] }
    val packReady = remember(pack, packStatus) {
        pack == null || packStatus == OcrPacks.Status.Downloaded ||
            (packStatus == null && OcrPacks.isDownloaded(filesDir, pack))
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // See CameraPanel: SurfaceView ignores Compose bounds in the IME
            // window; TextureView composits like a normal view.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // Small print needs more pixels than chat photos, but the full
            // sensor is still overkill and slow to process.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(2048, 2048),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
            )
            .build()
    }

    // Bind only while the viewfinder is up; the frozen capture and its
    // words are the whole UI afterwards, so release the camera.
    val scanning = stage is OcrStage.Viewfinder && packReady && clipImage == null
    DisposableEffect(provider, scanning, viewSize) {
        val cameraProvider = provider
        if (cameraProvider != null && scanning && viewSize != IntSize.Zero) {
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            // Shared ViewPort = WYSIWYG: the capture is cropped to exactly
            // what the viewfinder framed.
            val viewPort = ViewPort.Builder(
                Rational(viewSize.width, viewSize.height),
                previewView.display?.rotation ?: Surface.ROTATION_0,
            ).build()
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(imageCapture)
                .build()
            camera = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, backOrFrontSelector(cameraProvider) ?: return@runCatching null, group,
                )
            }.getOrNull()
            camera?.cameraControl?.enableTorch(torchOn)
        }
        onDispose {
            camera = null
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    fun capture(auto: Boolean = false) {
        if (stage !is OcrStage.Viewfinder || viewSize == IntSize.Zero || !packReady) return
        if (!auto) feedback()
        val readPack = pack
        scope.launch {
            val bitmap = if (clipImage != null) {
                clipBitmap?.getOrNull()
            } else {
                withContext(Dispatchers.IO) {
                    runCancellable {
                        imageCapture.awaitCapture(context).use { it.toFramedBitmap(mirror = false) }
                    }.getOrNull()
                }
            } ?: return@launch
            stage = OcrStage.Recognizing(bitmap)
            val read = withContext(Dispatchers.Default) {
                runCancellable {
                    if (readPack != null) {
                        readWithTesseract(filesDir, readPack, bitmap)
                    } else {
                        var id = 0
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                            .textBlocks
                            .flatMap { block ->
                                block.lines.map { line ->
                                    line.elements.map { OcrWord(id++, it.text) }
                                }
                            }
                    }
                }
            }
            // A failed read is an error on screen, never "no text found":
            // that is what hid a broken engine from the user.
            val failure = read.exceptionOrNull()
            stage = when {
                failure == null -> OcrStage.Done(OcrResult(bitmap, read.getOrThrow()))
                // The pack went missing or would not load (and was deleted):
                // back to the viewfinder, which now offers the download.
                failure is TesseractException && readPack != null &&
                    failure.reason == TesseractException.Reason.PACK_MISSING -> {
                    OcrPacks.refresh(filesDir, listOf(readPack))
                    OcrStage.Viewfinder
                }
                failure is TesseractException &&
                    failure.reason == TesseractException.Reason.PACK_BROKEN -> OcrStage.Viewfinder
                else -> {
                    Log.w(OCR_LOG_TAG, "text recognition failed", failure)
                    OcrStage.Done(OcrResult(bitmap, emptyList(), R.string.ime_scanner_ocr_read_error))
                }
            }
        }
    }

    // A clip's picture is read as soon as it and the language's data are
    // there, once: after that, Scan again waits for the shutter, so the
    // language can be changed first.
    var autoRead by remember(clipImage) { mutableStateOf(clipImage != null) }
    LaunchedEffect(clipBitmap, packReady, viewSize) {
        if (autoRead && clipBitmap?.isSuccess == true && packReady && viewSize != IntSize.Zero) {
            autoRead = false
            capture(auto = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        when (val current = stage) {
            is OcrStage.Done -> {
                OcrResultView(
                    state = state,
                    result = current.result,
                    autoSelect = autoSelect,
                    onInsert = onInsert,
                    onRescan = { stage = OcrStage.Viewfinder },
                    onClose = onClose,
                )
                return@Box
            }
            is OcrStage.Recognizing -> {
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.ime_scanner_ocr_capture_desc),
                    modifier = Modifier.fillMaxSize(),
                    // A copied picture is shown whole: it was not framed for this panel.
                    contentScale = if (clipImage != null) ContentScale.Fit else ContentScale.Crop,
                )
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ime_scanner_ocr_reading_progress),
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
                return@Box
            }
            OcrStage.Viewfinder -> Unit
        }

        val activeProvider = provider
        if (clipImage == null && activeProvider != null && backOrFrontSelector(activeProvider) == null) {
            PanelCenteredMessage(stringResource(R.string.ime_scanner_no_camera))
            return@Box
        }
        if (clipImage != null && clipBitmap?.isFailure == true) {
            PanelCenteredMessage(stringResource(R.string.ime_scanner_ocr_image_error))
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                CameraChipButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    description = stringResource(R.string.ime_scanner_ocr_close_desc),
                    active = false,
                ) {
                    feedback()
                    onClose()
                }
            }
            return@Box
        }

        val nextLanguage: (() -> Unit)? = if (chipLanguages.size > 1) {
            {
                feedback()
                fun route(l: LanguageDef) = OcrLanguages.tesseractPack(engine, l, TesseractOcr.available)
                val at = chipLanguages.indexOfFirst { route(it) == route(ocrLanguage) }
                ocrLanguage = chipLanguages[(at + 1).mod(chipLanguages.size)]
            }
        } else {
            null
        }

        if (!packReady && pack != null) {
            OcrPackPrompt(state, ocrLanguage, pack, packStatus, filesDir)
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                CameraChipButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    description = stringResource(R.string.ime_scanner_ocr_close_desc),
                    active = false,
                ) {
                    feedback()
                    onClose()
                }
            }
            nextLanguage?.let {
                OcrLanguageChip(ocrLanguage, it, Modifier.align(Alignment.BottomStart).padding(12.dp))
            }
            return@Box
        }

        if (clipImage != null) {
            clipBitmap?.getOrNull()?.let { picture ->
                Image(
                    bitmap = remember(picture) { picture.asImageBitmap() },
                    contentDescription = stringResource(R.string.ime_scanner_ocr_capture_desc),
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        Text(
            stringResource(if (clipImage != null) R.string.ime_scanner_ocr_image_hint else R.string.ime_scanner_ocr_hint),
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            CameraChipButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.ime_scanner_ocr_close_desc),
                active = false,
            ) {
                feedback()
                onClose()
            }
        }
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                CameraChipButton(
                    icon = if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    description = stringResource(R.string.ime_scanner_torch_desc),
                    active = torchOn,
                ) {
                    feedback()
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                }
            }
        }
        // Shutter, same look as the camera tool's.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(54.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f))
                .padding(5.dp)
                .clip(CircleShape)
                .background(Color.White)
                // Keyed on the pack: capture() reads it, and a Unit key would
                // keep the first language's capture after the chip moved on.
                .pointerInput(pack, packReady) { detectTapGestures { capture() } },
        )
        nextLanguage?.let {
            OcrLanguageChip(ocrLanguage, it, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}

/** The name of [language] in the app's own language, for the panel's text. */
private fun ocrLanguageName(language: LanguageDef): String =
    Locale.forLanguageTag(language.localeTag).displayLanguage.ifEmpty { language.englishName }

/** The text's language over the viewfinder; tapping steps to the next one. */
@Composable
private fun OcrLanguageChip(language: LanguageDef, onClick: () -> Unit, modifier: Modifier) {
    val name = ocrLanguageName(language)
    val description = stringResource(R.string.ime_scanner_ocr_language_desc, name)
    Text(
        name,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Stands in for the viewfinder while the language's Tesseract data is not on
 * the phone: what it costs, the button that fetches it, then its progress.
 * Data saver is asked here, on the button, the way the translate panel does:
 * the first press explains, the second is the yes.
 */
@Composable
private fun OcrPackPrompt(
    state: KeyboardUiState,
    language: LanguageDef,
    pack: String,
    status: OcrPacks.Status?,
    filesDir: File,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val feedback = LocalKeyPressFeedback.current
    var meteredAsked by remember(pack) { mutableStateOf(false) }
    var meteredBlocked by remember(pack) { mutableStateOf(false) }
    val name = ocrLanguageName(language)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (status is OcrPacks.Status.Downloading) {
            if (status.total > 0L) {
                CircularProgressIndicator(
                    progress = { (status.bytes.toFloat() / status.total).coerceIn(0f, 1f) },
                    color = kb.accent,
                )
            } else {
                CircularProgressIndicator(color = kb.accent)
            }
            Text(
                stringResource(R.string.ime_scanner_ocr_downloading_progress, name),
                color = kb.secondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (status.total > 0L) {
                Text(
                    stringResource(
                        R.string.ime_scanner_ocr_download_of_total_progress,
                        Formatter.formatShortFileSize(context, status.bytes),
                        Formatter.formatShortFileSize(context, status.total),
                    ),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                )
            }
            OcrPromptButton(Icons.Outlined.Close, stringResource(CommonR.string.common_cancel)) {
                feedback()
                OcrPacks.cancel(pack)
            }
            return@Column
        }

        Text(
            when {
                meteredBlocked -> stringResource(R.string.ime_metered_off_body)
                meteredAsked -> stringResource(R.string.ime_metered_ask_body)
                status is OcrPacks.Status.Failed ->
                    if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                    else stringResource(status.messageRes, status.messageArg)
                else -> stringResource(
                    R.string.ime_scanner_ocr_need_pack_body,
                    name,
                    Formatter.formatShortFileSize(context, OcrLanguages.sizeOf(pack)),
                )
            },
            color = kb.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (!meteredBlocked) {
            OcrPromptButton(
                Icons.Outlined.FileDownload,
                stringResource(
                    when {
                        meteredAsked -> R.string.ime_metered_allow_action
                        status is OcrPacks.Status.Failed -> CommonR.string.common_retry
                        else -> CommonR.string.common_download
                    },
                ),
            ) {
                feedback()
                when (state.dataSaver.decide(MeteredFeature.DOWNLOADS)) {
                    MeteredDecision.ALLOWED -> OcrPacks.start(filesDir, pack)
                    MeteredDecision.BLOCKED -> meteredBlocked = true
                    MeteredDecision.ASK ->
                        if (meteredAsked) OcrPacks.start(filesDir, pack) else meteredAsked = true
                }
            }
        }
    }
}

@Composable
private fun OcrPromptButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
            .background(kb.toolCircleActive)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = kb.toolCircleActiveIcon)
        Text(
            label,
            color = kb.toolCircleActiveIcon,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private val WHITESPACE = Regex("\\s+")

/** The longest side a clip's picture is read at: past it, more pixels only slow the read. */
private const val CLIP_OCR_MAX_SIDE = 4096

/**
 * A clip's picture (#371), halved until its longest side fits
 * [CLIP_OCR_MAX_SIDE]. Throws when the file is gone or is not a picture.
 */
private fun decodeClipImage(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > CLIP_OCR_MAX_SIDE) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalArgumentException("not a picture: ${file.name}")
}

private const val OCR_LOG_TAG = "WMKB-OCR"

/**
 * Tesseract's text as the panel's lines of words. Throws
 * [TesseractException] when the read failed rather than found nothing.
 */
private suspend fun readWithTesseract(filesDir: File, pack: String, bitmap: Bitmap): List<List<OcrWord>> {
    val text = TesseractOcr.recognize(filesDir, pack, bitmap)
    var id = 0
    return text.lines()
        .map { line -> line.split(WHITESPACE).filter { it.isNotEmpty() } }
        .filter { it.isNotEmpty() }
        .map { words -> words.map { OcrWord(id++, it) } }
}

/**
 * The recognized words as chips, grouped by line. With [autoSelect] on
 * (the default setting) everything starts selected and tapping a word
 * toggles it off, so copying "only that one number" is a couple of taps
 * instead of a fiddly text selection; with it off the words start blank
 * and get picked one by one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OcrResultView(
    state: KeyboardUiState,
    result: OcrResult,
    autoSelect: Boolean,
    onInsert: (String) -> Unit,
    onRescan: () -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val feedback = LocalKeyPressFeedback.current
    var selected by remember(result, autoSelect) {
        mutableStateOf(
            if (autoSelect) result.lines.flatten().map { it.id }.toSet() else emptySet()
        )
    }

    fun selectedText(): String = result.lines
        .map { line -> line.filter { it.id in selected }.joinToString(" ") { it.text } }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    fun copySelected() {
        val text = selectedText()
        if (text.isNotEmpty()) {
            copyPlainText(context, text)
            Toast.makeText(context, R.string.ime_scanner_copied_toast, Toast.LENGTH_SHORT).show()
        }
    }

    // The ring exists only on this frozen result — the viewfinder publishes
    // nothing. Fixed ACTIONS slots (Scan again, select-all toggle, Copy,
    // Insert; the last three no-op with no words), and the word chips as
    // RESULTS in reading order.
    val allWords = result.lines.flatten()
    PanelFocusTarget(
        panel = PanelMode.OCR,
        region = FocusRegion.ACTIONS,
        count = if (result.wordCount > 0) 4 else 1,
        columns = 4,
    ) { index ->
        when (index) {
            0 -> { feedback(); onRescan() }
            1 -> {
                feedback()
                selected = if (selected.size == result.wordCount) emptySet()
                else allWords.map { it.id }.toSet()
            }
            2 -> { feedback(); copySelected() }
            3 -> selectedText().takeIf { it.isNotEmpty() }?.let(onInsert)
        }
    }
    PanelFocusTarget(
        panel = PanelMode.OCR,
        region = FocusRegion.RESULTS,
        count = allWords.size,
        columns = allWords.size.coerceAtLeast(1),
    ) { index ->
        allWords.getOrNull(index)?.let { word ->
            feedback()
            selected = if (word.id in selected) selected - word.id else selected + word.id
        }
    }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val focusedWord = state.focusedIndex(FocusRegion.RESULTS)
        ?.let { allWords.getOrNull(it)?.id }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraChipButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.ime_scanner_ocr_close_desc),
                active = false,
            ) {
                feedback()
                onClose()
            }
            CameraChipButton(
                icon = Icons.Outlined.Refresh,
                description = stringResource(R.string.ime_scanner_scan_again_action),
                active = false,
                focused = focusedAction == 0,
            ) {
                feedback()
                onRescan()
            }
            Text(
                if (result.errorRes != null) {
                    stringResource(R.string.ime_scanner_ocr_failed_label)
                } else if (result.wordCount == 0) {
                    stringResource(R.string.ime_scanner_ocr_no_text_label)
                } else {
                    pluralStringResource(
                        R.plurals.ime_scanner_ocr_word_count,
                        result.wordCount,
                        selected.size,
                        result.wordCount,
                    )
                },
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (result.wordCount > 0) {
                val allSelected = selected.size == result.wordCount
                CameraChipButton(
                    icon = if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                    description = if (allSelected) {
                        stringResource(R.string.ime_scanner_ocr_deselect_all_desc)
                    } else {
                        stringResource(CommonR.string.common_select_all)
                    },
                    active = false,
                    focused = focusedAction == 1,
                ) {
                    feedback()
                    selected =
                        if (allSelected) emptySet()
                        else allWords.map { it.id }.toSet()
                }
            }
        }

        if (result.wordCount == 0) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(result.errorRes ?: R.string.ime_scanner_ocr_empty),
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (line in result.lines) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (word in line) {
                            val on = word.id in selected
                            Text(
                                word.text,
                                color = if (on) kb.toolCircleActiveIcon else kb.suggestionText,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (on) kb.toolCircleActive else kb.chip)
                                    .focusRing(word.id == focusedWord)
                                    .pointerInput(word.id) {
                                        detectTapGestures {
                                            feedback()
                                            selected = if (on) selected - word.id else selected + word.id
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                CaptureActionButton(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(CommonR.string.common_copy),
                    accent = false,
                    focused = focusedAction == 2,
                ) {
                    feedback()
                    copySelected()
                }
                CaptureActionButton(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    label = stringResource(R.string.ime_scanner_insert_action),
                    accent = true,
                    focused = focusedAction == 3,
                ) {
                    val text = selectedText()
                    if (text.isNotEmpty()) onInsert(text)
                }
            }
        }
    }
}

// ---- barcode / QR scanner tool ----

/** A decoded barcode frozen on screen, awaiting insert/copy/rescan. */
private class ScannedCode(val value: String, @StringRes val formatLabelRes: Int)

/**
 * Barcode & QR scanner: a live viewfinder that decodes continuously
 * (ML Kit, on-device); the first hit freezes into a result card with
 * insert and copy actions. Insert types the barcode's text into the
 * editor, like any other key press.
 */
@Composable
internal fun QrScanPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
) {
    // Full-bleed like the OCR tool: the toolbar hides, the viewfinder
    // absorbs its height and the back button sits at the very top instead
    // of below a blank strip.
    val height = keyRowsHeight(state) + fullBleedHiddenRows(state)
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    PermissionRecheck { hasPermission = hasCameraPermission(context) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (hasPermission) {
            QrScanContent(
                state = state,
                onInsert = onInsert,
                onOpenUrl = onOpenUrl,
                haptics = state.settings.scanner.qrScanHaptics,
                autoInsert = state.settings.scanner.qrScanAutoInsert,
                linkPreviews = state.settings.scanner.qrScanLinkPreviews,
            )
        } else {
            CameraPermissionPrompt(
                stringResource(R.string.ime_scanner_qr_permission_body),
                onRequestPermission,
            )
        }
        val feedback = LocalKeyPressFeedback.current
        Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            CameraChipButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.ime_scanner_qr_close_desc),
                active = false,
            ) {
                feedback()
                onClose()
            }
        }
    }
}

@Composable
private fun QrScanContent(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    haptics: Boolean,
    autoInsert: Boolean,
    linkPreviews: Boolean,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val feedback = LocalKeyPressFeedback.current

    var result by remember { mutableStateOf<ScannedCode?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    // Current zoom, driven by pinch and shown as a pill. Zooming into a
    // small or distant code is often the difference between a decode and
    // not. Resets to 1x whenever the camera rebinds (e.g. after Rescan).
    var zoomRatio by remember(camera) { mutableFloatStateOf(1f) }

    val provider by produceState<ProcessCameraProvider?>(null) {
        value = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    }
    val scanner = remember { BarcodeScanning.getClient() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Decode frames only while there's no result on screen. A frame is
    // skipped when the previous one is still being decoded (busy flag)
    // instead of queueing — barcodes don't need every frame.
    val scanning = result == null
    DisposableEffect(provider, scanning) {
        val cameraProvider = provider
        var busy = false
        if (cameraProvider != null && scanning) {
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            )
                        )
                        .build()
                )
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                if (busy || result != null) {
                    proxy.close()
                    return@setAnalyzer
                }
                busy = true
                // toBitmap + rotation instead of the mediaImage path: one
                // copy per analyzed frame, but no experimental CameraX API.
                val bitmap = proxy.toBitmap()
                val rotation = proxy.imageInfo.rotationDegrees
                proxy.close()
                scanner.process(InputImage.fromBitmap(bitmap, rotation))
                    .addOnSuccessListener { barcodes ->
                        val hit = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                        val raw = hit?.rawValue
                        if (hit != null && raw != null && result == null) {
                            if (haptics) feedback()
                            // Freeze into the result card either way — with
                            // auto-insert the text is already committed and
                            // the card shows what went in (Rescan re-arms).
                            result = ScannedCode(raw, barcodeFormatLabelRes(hit.format))
                            if (autoInsert) onInsert(raw)
                        }
                    }
                    .addOnCompleteListener { busy = false }
            }
            camera = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    backOrFrontSelector(cameraProvider) ?: return@runCatching null,
                    preview,
                    analysis,
                )
            }.getOrNull()
            camera?.cameraControl?.enableTorch(torchOn)
        }
        onDispose {
            camera = null
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val code = result
        if (code != null) {
            // A scanned code that is a bare URL gets an Open button and,
            // when link previews are on, its page title/description below.
            val url = remember(code) { ClipLinks.asUrl(code.value) }
            var preview by remember(code) { mutableStateOf<LinkPreview?>(null) }
            if (linkPreviews && url != null) {
                LaunchedEffect(url) {
                    preview = withContext(Dispatchers.IO) { LinkPreviewClient.fetch(url) }
                }
            }
            // The ring lives on this result card only; the viewfinder below
            // publishes nothing. Open leads when the code is a URL.
            PanelFocusTarget(
                panel = PanelMode.QR_SCAN,
                region = FocusRegion.ACTIONS,
                count = if (url != null) 4 else 3,
                columns = 4,
            ) { index ->
                val slot = if (url != null) index else index + 1
                when (slot) {
                    0 -> url?.let { feedback(); onOpenUrl(it) }
                    1 -> { feedback(); result = null }
                    2 -> {
                        feedback()
                        copyPlainText(context, code.value)
                        Toast.makeText(
                            context,
                            R.string.ime_scanner_copied_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    3 -> onInsert(code.value)
                }
            }
            val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
                ?.let { if (url != null) it else it + 1 }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LinkPreviewCard(linkPreviews && url != null, preview)
                // Easter egg: the one link every QR scanner eventually meets.
                // Matched on the raw payload, not on `url` — ClipLinks.asUrl
                // only accepts a bare URL and a rickroll deserves recognition
                // even when it travels with company.
                if (remember(code) { EggLinks.isRickroll(code.value) }) {
                    Text(
                        stringResource(R.string.ime_qr_rickroll_info),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    code.value,
                    color = kb.suggestionText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(code.formatLabelRes),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                // Open (URLs only) gets its own row above the rest, so the
                // primary action isn't crowded in with Rescan/Copy/Insert.
                if (url != null) {
                    CaptureActionButton(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        label = stringResource(R.string.ime_scanner_qr_open_action),
                        accent = false,
                        focused = focusedAction == 0,
                    ) {
                        feedback()
                        onOpenUrl(url)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(
                        12.dp, Alignment.CenterHorizontally,
                    ),
                ) {
                    CaptureActionButton(
                        icon = Icons.Outlined.Refresh,
                        label = stringResource(R.string.ime_scanner_scan_again_action),
                        accent = false,
                        focused = focusedAction == 1,
                    ) {
                        feedback()
                        result = null
                    }
                    CaptureActionButton(
                        icon = Icons.Outlined.ContentCopy,
                        label = stringResource(CommonR.string.common_copy),
                        accent = false,
                        focused = focusedAction == 2,
                    ) {
                        feedback()
                        copyPlainText(context, code.value)
                        Toast.makeText(
                            context,
                            R.string.ime_scanner_copied_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    CaptureActionButton(
                        icon = Icons.AutoMirrored.Outlined.Send,
                        label = stringResource(R.string.ime_scanner_insert_action),
                        accent = true,
                        focused = focusedAction == 3,
                    ) { onInsert(code.value) }
                }
            }
            return@Box
        }

        val activeProvider = provider
        if (activeProvider != null && backOrFrontSelector(activeProvider) == null) {
            PanelCenteredMessage(stringResource(R.string.ime_scanner_no_camera))
            return@Box
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
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
                    stringResource(R.string.ime_scanner_qr_zoom_label, zoomRatio),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Text(
            stringResource(R.string.ime_scanner_qr_hint),
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                CameraChipButton(
                    icon = if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    description = stringResource(R.string.ime_scanner_torch_desc),
                    active = torchOn,
                ) {
                    feedback()
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                }
            }
        }
    }
}

/**
 * The fetched page title/site/description for a scanned link, shown above
 * the raw URL. A small spinner stands in while the fetch is in flight;
 * a failed or empty fetch renders nothing (the URL alone still shows).
 */
@Composable
private fun LinkPreviewCard(enabled: Boolean, preview: LinkPreview?) {
    if (!enabled) return
    val kb = LocalKbTheme.current
    if (preview == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(
                color = kb.secondaryText,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.ime_scanner_qr_link_progress),
                color = kb.secondaryText,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        return
    }
    if (preview.failed || preview.isEmpty) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (preview.title.isNotBlank()) {
            Text(
                preview.title,
                color = kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (preview.siteName.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(preview.siteName, color = kb.secondaryText, fontSize = 11.sp)
        }
        if (preview.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                preview.description,
                color = kb.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ---- shared bits ----

/**
 * Re-runs [onResume] when the keyboard comes back to the foreground — the
 * camera-permission dialog lives in a trampoline activity, so the panel
 * has to re-check the permission once it regains focus.
 */
@Composable
private fun PermissionRecheck(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** The camera-permission explainer + "Allow the camera" button. */
@Composable
private fun CameraPermissionPrompt(message: String, onRequestPermission: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            color = kb.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
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
                stringResource(R.string.ime_scanner_permission_action),
                color = kb.toolCircleActiveIcon,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Back camera, else front, else null — scanners never need a lens switch. */
private fun backOrFrontSelector(provider: ProcessCameraProvider): CameraSelector? = when {
    provider.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
    provider.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
    else -> null
}

private fun copyPlainText(context: Context, text: String) {
    KeyboardClipboard.copy(context, text, "scanned text")
}

@StringRes
private fun barcodeFormatLabelRes(format: Int): Int = when (format) {
    Barcode.FORMAT_QR_CODE -> R.string.ime_scanner_format_qr_code
    Barcode.FORMAT_AZTEC -> R.string.ime_scanner_format_aztec
    Barcode.FORMAT_CODABAR -> R.string.ime_scanner_format_codabar
    Barcode.FORMAT_CODE_39 -> R.string.ime_scanner_format_code_39
    Barcode.FORMAT_CODE_93 -> R.string.ime_scanner_format_code_93
    Barcode.FORMAT_CODE_128 -> R.string.ime_scanner_format_code_128
    Barcode.FORMAT_DATA_MATRIX -> R.string.ime_scanner_format_data_matrix
    Barcode.FORMAT_EAN_8 -> R.string.ime_scanner_format_ean_8
    Barcode.FORMAT_EAN_13 -> R.string.ime_scanner_format_ean_13
    Barcode.FORMAT_ITF -> R.string.ime_scanner_format_itf
    Barcode.FORMAT_PDF417 -> R.string.ime_scanner_format_pdf417
    Barcode.FORMAT_UPC_A -> R.string.ime_scanner_format_upc_a
    Barcode.FORMAT_UPC_E -> R.string.ime_scanner_format_upc_e
    else -> R.string.ime_scanner_format_generic
}

/**
 * Suspends over a Play Services [Task]. A failed task throws its exception:
 * cancelling the continuation instead would unwind the capture as if the
 * panel had closed, and leave it on the reading spinner.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
