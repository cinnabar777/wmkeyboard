package com.wasimaster.wmkeyboard.core.stickers.telegram

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.io.Closeable

/**
 * The frames of a WebM video sticker, decoded, with their transparency.
 *
 * [WebmVideo] takes the file apart; this runs two of the platform's VP9
 * decoders side by side, one over the colour frames and one over the alpha
 * frames the container carries beside them, and puts each pair back together
 * as one ARGB picture. The alpha stream is an ordinary VP9 video whose
 * brightness is the transparency, so its luma plane is the alpha as it is.
 *
 * Software decoders are asked for first. A sticker is 512 pixels and three
 * seconds, which costs a software decoder nothing, and they are the ones that
 * reliably hand frames back as readable YUV planes; two hardware sessions at
 * once is also more than some devices allow.
 */
object WebmFrames {

    private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_POLLS = 300
    private const val US_PER_FRAME_INDEX = 1_000L
    private const val DEFAULT_FRAME_MS = 33
    private const val MIN_FRAME_MS = 20
    private const val OPAQUE = 0xFF
    private const val BYTE = 0xFF

    /** True when [bytes] start like a Matroska or WebM file. */
    fun isWebm(bytes: ByteArray): Boolean =
        bytes.size > EBML_MAGIC.size && EBML_MAGIC.indices.all { bytes[it] == EBML_MAGIC[it] }

    /**
     * Calls [onFrame] with each frame of the video in [bytes] and how long it
     * shows, in order. The bitmap is only valid during the call: it is reused
     * for the next frame. Returns the number of frames delivered, 0 when the
     * file could not be read, has more than [maxFrames] frames, or this
     * device has no decoder for it.
     *
     * Blocking, and not for the main thread.
     */
    fun forEach(bytes: ByteArray, maxFrames: Int, onFrame: (frame: Bitmap, durationMs: Int) -> Unit): Int =
        runCatching { decode(bytes, maxFrames, onFrame) }.getOrDefault(0)

    @Suppress("ReturnCount")
    private fun decode(bytes: ByteArray, maxFrames: Int, onFrame: (Bitmap, Int) -> Unit): Int {
        val video = WebmVideo.parse(bytes) ?: return 0
        val mime = when (video.codec) {
            "V_VP9" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "V_VP8" -> MediaFormat.MIMETYPE_VIDEO_VP8
            else -> return 0
        }
        if (video.frames.size > maxFrames) return 0
        // An alpha stream with a hole in it cannot be decoded past the hole:
        // every alpha frame after a key frame is a difference from the last.
        if (video.hasAlpha && video.frames.any { it.alpha == null }) return 0

        val color = Decoder.open(mime, video.width, video.height) ?: return 0
        color.use {
            val alpha = if (video.hasAlpha) Decoder.open(mime, video.width, video.height) ?: return 0 else null
            alpha.use {
                val run = Run(video.width, video.height, video.hasAlpha, video.durationsMs(), onFrame)
                try {
                    video.frames.forEachIndexed { index, frame ->
                        color.queue(index, frame.data) { i, image -> run.color(i, image) }
                        alpha?.queue(index, frame.alpha!!) { i, image -> run.alpha(i, image) }
                        run.emitReady()
                    }
                    color.finish { i, image -> run.color(i, image) }
                    alpha?.finish { i, image -> run.alpha(i, image) }
                    run.emitReady()
                    return run.flush()
                } finally {
                    run.recycle()
                }
            }
        }
    }

    /**
     * Pairs colour frames with alpha frames as the two decoders hand them
     * back, and passes each finished picture on in order.
     *
     * Decoders return frames in the order they went in, but may keep a few
     * back, and may drop one they could not decode. A frame index lower than
     * the highest one a decoder has returned and still missing from it was
     * dropped, and is skipped, its time added to the frame before it.
     */
    private class Run(
        private val width: Int,
        private val height: Int,
        private val hasAlpha: Boolean,
        private val durations: IntArray,
        private val onFrame: (Bitmap, Int) -> Unit,
    ) {
        private val colors = HashMap<Int, IntArray>()
        private val alphas = HashMap<Int, ByteArray>()
        private var colorHigh = -1
        private var alphaHigh = -1
        private var next = 0
        private var delivered = 0
        private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val pixels = IntArray(width * height)

        /** The picture waiting to go out, held back until the next one says how long it lasts. */
        private var held = false
        private var heldMs = 0

        fun color(index: Int, image: Image) {
            colors[index] = rgbOf(image, width, height)
            colorHigh = maxOf(colorHigh, index)
        }

        fun alpha(index: Int, image: Image) {
            alphas[index] = lumaOf(image, width, height)
            alphaHigh = maxOf(alphaHigh, index)
        }

        fun emitReady() {
            while (next < durations.size) {
                val color = colors[next]
                val alpha = if (hasAlpha) alphas[next] else null
                val ready = color != null && (!hasAlpha || alpha != null)
                val dropped = !ready && (
                    (color == null && colorHigh > next) || (hasAlpha && alpha == null && alphaHigh > next)
                    )
                when {
                    ready -> {
                        release()
                        compose(color!!, alpha)
                        held = true
                        heldMs = durations[next]
                    }
                    dropped -> heldMs += durations[next]
                    else -> return
                }
                colors.remove(next)
                alphas.remove(next)
                next++
            }
        }

        /** Hands the held picture on. The bitmap is overwritten straight after. */
        private fun release() {
            if (!held) return
            onFrame(bitmap, heldMs.takeIf { it >= MIN_FRAME_MS } ?: DEFAULT_FRAME_MS)
            delivered++
            held = false
        }

        /** Whatever the decoders never returned is dropped; the last picture goes out. */
        fun flush(): Int {
            while (next < durations.size) {
                if (colors[next] != null && (!hasAlpha || alphas[next] != null)) {
                    emitReady()
                    continue
                }
                heldMs += durations[next]
                next++
            }
            release()
            return delivered
        }

        fun recycle() = bitmap.recycle()

        private fun compose(rgb: IntArray, alpha: ByteArray?) {
            for (i in pixels.indices) {
                val a = alpha?.let { it[i].toInt() and BYTE } ?: OPAQUE
                pixels[i] = (a shl 24) or (rgb[i] and 0xFFFFFF)
            }
            // Unpremultiplied in, which is what setPixels expects.
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /** One platform decoder, fed frame by frame and drained as it goes. */
    private class Decoder(private val codec: MediaCodec) : Closeable {

        private val info = MediaCodec.BufferInfo()
        private var ended = false

        fun queue(index: Int, data: ByteArray, onImage: (Int, Image) -> Unit) {
            repeat(MAX_IDLE_POLLS) {
                val slot = codec.dequeueInputBuffer(TIMEOUT_US)
                if (slot >= 0) {
                    val buffer = codec.getInputBuffer(slot) ?: error("no input buffer")
                    buffer.clear()
                    buffer.put(data)
                    codec.queueInputBuffer(slot, 0, data.size, index * US_PER_FRAME_INDEX, 0)
                    drain(0L, onImage)
                    return
                }
                // Full: the decoder wants its output taken before it takes more.
                drain(0L, onImage)
            }
            error("decoder stalled")
        }

        /** Ends the stream and takes every frame the decoder still holds. */
        fun finish(onImage: (Int, Image) -> Unit) {
            repeat(MAX_IDLE_POLLS) {
                val slot = codec.dequeueInputBuffer(TIMEOUT_US)
                if (slot >= 0) {
                    codec.queueInputBuffer(slot, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    var idle = 0
                    while (!ended && idle < MAX_IDLE_POLLS) {
                        if (!drain(TIMEOUT_US, onImage)) idle++ else idle = 0
                    }
                    return
                }
                drain(0L, onImage)
            }
        }

        /** Takes what output there is. True when anything came out. */
        private fun drain(timeoutUs: Long, onImage: (Int, Image) -> Unit): Boolean {
            var any = false
            while (!ended) {
                val slot = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    slot >= 0 -> {
                        any = true
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                        if (info.size > 0 || !ended) {
                            codec.getOutputImage(slot)?.use { image ->
                                onImage((info.presentationTimeUs / US_PER_FRAME_INDEX).toInt(), image)
                            }
                        }
                        codec.releaseOutputBuffer(slot, false)
                    }
                    slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> any = true
                    else -> return any
                }
                if (timeoutUs > 0) return any
            }
            return any
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
        }

        companion object {
            /** A started decoder for [mime], software first, or null when none will start. */
            fun open(mime: String, width: Int, height: Int): Decoder? {
                for (name in decoderNames(mime)) {
                    val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
                    val started = runCatching {
                        val format = MediaFormat.createVideoFormat(mime, width, height)
                        format.setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                        )
                        codec.configure(format, null, null, 0)
                        codec.start()
                    }.isSuccess
                    if (started) return Decoder(codec)
                    codec.release()
                }
                return null
            }

            private fun decoderNames(mime: String): List<String> =
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                    .sortedBy { if (isSoftware(it)) 0 else 1 }
                    .map { it.name }

            private fun isSoftware(info: MediaCodecInfo): Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isSoftwareOnly
                } else {
                    info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
                }
        }
    }

    /**
     * The picture in [image] as RGB, BT.601 at video range, which is what
     * VP9 stickers are encoded at. The top byte is left for the alpha.
     */
    private fun rgbOf(image: Image, width: Int, height: Int): IntArray {
        val crop = image.cropRect
        val (yPlane, uPlane, vPlane) = image.planes
        val y = yPlane.buffer
        val u = uPlane.buffer
        val v = vPlane.buffer
        val out = IntArray(width * height)
        val rows = minOf(height, crop.height())
        val cols = minOf(width, crop.width())
        for (row in 0 until rows) {
            val yRow = (crop.top + row) * yPlane.rowStride
            val cRow = (crop.top + row) / 2
            for (col in 0 until cols) {
                val luma = y.get(yRow + (crop.left + col) * yPlane.pixelStride).toInt() and BYTE
                val cCol = (crop.left + col) / 2
                val cb = (u.get(cRow * uPlane.rowStride + cCol * uPlane.pixelStride).toInt() and BYTE) - 128
                val cr = (v.get(cRow * vPlane.rowStride + cCol * vPlane.pixelStride).toInt() and BYTE) - 128
                val c = 298 * (luma - 16)
                val r = ((c + 409 * cr + 128) shr 8).coerceIn(0, BYTE)
                val g = ((c - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, BYTE)
                val b = ((c + 516 * cb + 128) shr 8).coerceIn(0, BYTE)
                out[row * width + col] = (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** The luma plane of [image], byte for byte: for an alpha frame, that is the alpha. */
    private fun lumaOf(image: Image, width: Int, height: Int): ByteArray {
        val crop = image.cropRect
        val plane = image.planes[0]
        val y = plane.buffer
        val out = ByteArray(width * height)
        val rows = minOf(height, crop.height())
        val cols = minOf(width, crop.width())
        for (row in 0 until rows) {
            val from = (crop.top + row) * plane.rowStride
            for (col in 0 until cols) {
                out[row * width + col] = y.get(from + (crop.left + col) * plane.pixelStride)
            }
        }
        return out
    }
}
