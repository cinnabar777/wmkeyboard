package com.wasimaster.wmkeyboard.core.stickers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import com.wasimaster.wmkeyboard.core.media.AnimatedWebpWriter
import com.wasimaster.wmkeyboard.core.media.MediaMime
import com.wasimaster.wmkeyboard.core.stickers.telegram.TgsFrames
import com.wasimaster.wmkeyboard.core.stickers.telegram.WebmFrames
import java.io.ByteArrayOutputStream

/**
 * Turns an arbitrary picked image into sticker bytes.
 *
 * Still images become 512×512 WebP under 100 KB — WhatsApp's static sticker
 * spec, and the only shape that reliably gets accepted as an
 * `image/webp.wasticker` rather than a plain image (see
 * [com.wasimaster.wmkeyboard.core.media.MediaMime]).
 *
 * Animated GIF and WebP sources are stored byte for byte instead: re-encoding
 * one would cost a generation of quality for nothing. An animated WebP that
 * already meets the spec therefore still goes out as a sticker; an animated
 * GIF can only ever be sent as an image.
 *
 * An animated PNG is the exception, because nothing on Android plays one and
 * no app takes one as a sticker. Its frames are read with [ApngFrames], each
 * is encoded as a still WebP by the platform, and [AnimatedWebpWriter] wraps
 * those into one animated WebP, which is both.
 *
 * Telegram's two animated kinds go the same way: a `.tgs` (a gzipped Lottie
 * animation) is drawn frame by frame with [TgsFrames], and a `.webm` video
 * sticker is decoded, transparency and all, with [WebmFrames].
 */
object StickerImage {

    /** Largest source we'll even read; anything bigger is refused outright. */
    const val MAX_SOURCE_BYTES = 12L * 1024 * 1024

    /** Cap for animated files, which are stored as-is. */
    const val MAX_ANIMATED_BYTES = 2 * 1024 * 1024

    /** WhatsApp's sticker canvas. */
    const val TARGET_SIZE = 512

    /**
     * The largest sample [ImageDecoder] accepts, i.e. the cheapest decode it
     * will do — for the animation probe, which draws nothing.
     */
    private const val ANIMATION_PROBE_SAMPLE = 32

    /** WhatsApp's static sticker budget. */
    const val TARGET_BYTES = 100 * 1024

    /**
     * Long edge the editor decodes to. Four times the sticker canvas, so a
     * crop down to a corner of the picture still has pixels to spare, and
     * still only 4 MB of heap.
     */
    const val EDIT_SIZE = 1024

    /**
     * Long edge a kept original is stored at — the size the editor decodes to,
     * with room to spare for a later crop into a corner. A photo straight off
     * a camera is tens of megapixels and the editor can never use them, so an
     * original is stored re-encoded rather than byte for byte.
     */
    const val ORIGINAL_SIZE = 1536

    /** Quality a kept original is stored at; it is a source, not an output. */
    private const val ORIGINAL_QUALITY = 82

    private val QUALITY_LADDER = intArrayOf(90, 80, 70, 60, 50)

    /** WhatsApp's animated sticker budget, which an APNG is re-encoded towards. */
    const val ANIMATED_TARGET_BYTES = 500 * 1024

    /**
     * Each rung is a whole pass over the frames, so there are few of them and
     * they fall fast. An animated PNG is lossless and small to begin with; the
     * first rung is nearly always the only one.
     */
    private val ANIMATED_QUALITY_LADDER = intArrayOf(75, 50, 30)

    /**
     * More frames than this is not a sticker (Signal stops at three seconds),
     * and every frame is a 512×512 encode. Such a file is kept as a still.
     */
    private const val MAX_ANIMATED_FRAMES = 150

    sealed interface Result {
        data class Ok(val sticker: ProcessedSticker) : Result

        /** Animated and over [MAX_ANIMATED_BYTES], or source over [MAX_SOURCE_BYTES]. */
        data object TooLarge : Result

        /** Not an image, or an image nothing on this device can decode. */
        data object NotAnImage : Result
    }

    fun process(bytes: ByteArray): Result {
        if (bytes.size > MAX_SOURCE_BYTES) return Result.TooLarge
        if (bytes.isEmpty()) return Result.NotAnImage

        // Already a conforming sticker (a re-imported pack, or a provider
        // sticker that was built to the same spec): re-encoding it would only
        // cost a generation of lossy quality.
        conforming(bytes)?.let { return Result.Ok(it) }

        // An animated PNG, which becomes an animated WebP. When that fails the
        // file is still a PNG any decoder reads, so it falls through to the
        // still path below and is kept as its first frame, marked as such.
        val apng = ApngFrames.isAnimated(bytes)
        if (apng) {
            if (bytes.size > MAX_ANIMATED_BYTES) return Result.TooLarge
            animatedFromApng(bytes)?.let { return Result.Ok(it) }
        }

        // Telegram's animated (Lottie) and video (WebM) stickers. No decoder
        // reads either as a picture, so when the frames cannot be turned into
        // an animated WebP the first one is kept, marked as such.
        if (TgsFrames.isTgs(bytes)) {
            return framesToSticker { onFrame -> TgsFrames.forEach(bytes, TARGET_SIZE, MAX_ANIMATED_FRAMES, onFrame) }
        }
        if (WebmFrames.isWebm(bytes)) {
            return framesToSticker { onFrame -> WebmFrames.forEach(bytes, MAX_ANIMATED_FRAMES, onFrame) }
        }

        val animation = detectAnimation(bytes)
        if (animation != null) {
            if (bytes.size > MAX_ANIMATED_BYTES) return Result.TooLarge
            val size = boundsOf(bytes)
            return Result.Ok(
                ProcessedSticker(
                    bytes = bytes,
                    mime = animation,
                    animated = true,
                    aspectRatio = size?.let { it.first.toFloat() / it.second } ?: 1f,
                )
            )
        }

        val source = decodeStill(bytes) ?: return Result.NotAnImage
        val canvas = square(source)
        if (canvas !== source) source.recycle()
        val encoded = encodeWebp(canvas)
        canvas.recycle()
        return if (encoded == null) Result.NotAnImage else Result.Ok(
            ProcessedSticker(
                bytes = encoded,
                mime = MediaMime.WEBP,
                animated = false,
                aspectRatio = 1f,
                flattened = apng,
            )
        )
    }

    /**
     * [bytes], an animated PNG, as an animated WebP on the sticker canvas, or
     * null when it cannot be read or does not fit under [MAX_ANIMATED_BYTES]
     * at any quality.
     */
    private fun animatedFromApng(bytes: ByteArray): ProcessedSticker? {
        if (ApngFrames.frameCount(bytes) > MAX_ANIMATED_FRAMES) return null
        return animatedFrom { onFrame -> ApngFrames.forEach(bytes, onFrame) }
    }

    /**
     * Something that hands over the frames of an animation, each as a bitmap
     * valid only during the call, and returns how many it handed over. Called
     * once per quality rung, so it decodes from the start each time.
     */
    private fun interface FrameSource {
        fun forEach(onFrame: (frame: Bitmap, durationMs: Int) -> Unit): Int
    }

    /**
     * A sticker out of [frames]: animated when they can be encoded, else the
     * first frame as a still, marked flattened when there were more.
     */
    private fun framesToSticker(frames: FrameSource): Result {
        animatedFrom(frames)?.let { return Result.Ok(it) }
        var first: Bitmap? = null
        val count = frames.forEach { frame, _ ->
            if (first == null) first = frame.copy(Bitmap.Config.ARGB_8888, false)
        }
        val still = first ?: return Result.NotAnImage
        val canvas = square(still)
        if (canvas !== still) still.recycle()
        val encoded = encodeWebp(canvas)
        canvas.recycle()
        return if (encoded == null) Result.NotAnImage else Result.Ok(
            ProcessedSticker(
                bytes = encoded,
                mime = MediaMime.WEBP,
                animated = false,
                aspectRatio = 1f,
                flattened = count > 1,
            )
        )
    }

    /**
     * [frames] as an animated WebP on the sticker canvas, or null when they
     * cannot be read or do not fit under [MAX_ANIMATED_BYTES] at any quality.
     */
    private fun animatedFrom(frames: FrameSource): ProcessedSticker? {
        var best: ByteArray? = null
        for (quality in ANIMATED_QUALITY_LADDER) {
            val encoded = encodeFramesAt(frames, quality) ?: return null
            best = encoded
            if (encoded.size <= ANIMATED_TARGET_BYTES) break
        }
        return best?.takeIf { it.size <= MAX_ANIMATED_BYTES }?.let {
            ProcessedSticker(bytes = it, mime = MediaMime.WEBP, animated = true, aspectRatio = 1f)
        }
    }

    /** One pass over [frames] at [quality]. Null when any frame fails. */
    private fun encodeFramesAt(frames: FrameSource, quality: Int): ByteArray? {
        val writer = AnimatedWebpWriter(TARGET_SIZE, TARGET_SIZE)
        var failed = false
        val delivered = frames.forEach { frame, durationMs ->
            if (failed) return@forEach
            // The frame belongs to the decoder, which draws the next one over
            // it; only a canvas made here is this function's to recycle.
            val canvas = square(frame)
            val still = encodeWebpAt(canvas, quality)
            if (canvas !== frame) canvas.recycle()
            if (still == null || !writer.addFrame(still, durationMs)) failed = true
        }
        return if (failed || delivered < 2 || writer.frameCount != delivered) null else writer.build()
    }

    /**
     * Whether [bytes] hold an animation, which the editor cannot touch: an
     * animated source is stored frame for frame, and re-encoding it would
     * flatten it to one frame.
     */
    fun isAnimatedSource(bytes: ByteArray): Boolean =
        detectAnimation(bytes) != null || ApngFrames.isAnimated(bytes) ||
            TgsFrames.isTgs(bytes) || WebmFrames.isWebm(bytes)

    /**
     * Decodes a still for the editor, at up to [maxSide] on its long edge.
     *
     * Bigger than [process] decodes at, on purpose: the editor crops, and a
     * crop of a picture already resampled down to 512 would be soft. Callers
     * recycle it as soon as a crop is applied.
     */
    fun decodeForEditing(bytes: ByteArray, maxSide: Int = EDIT_SIZE): Bitmap? =
        decodeStill(bytes, maxSide)

    /**
     * [bytes] re-encoded as the copy the editor re-opens later, or null when
     * there is nothing worth keeping: an animation (which the editor cannot
     * touch), an unreadable file, or one over [MAX_SOURCE_BYTES].
     *
     * This is a source and never an output, so it is stored at
     * [ORIGINAL_SIZE] rather than at the sticker canvas: a cut-out redone
     * from a 512-pixel copy would be a cut-out of a thumbnail. It never
     * leaves the device — see [StickerPackStore] for where it lives and what
     * export writes instead.
     */
    fun encodeOriginal(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) return null
        if (isAnimatedSource(bytes)) return null
        val source = decodeStill(bytes, ORIGINAL_SIZE) ?: return null
        val encoded = encodeWebpAt(source, ORIGINAL_QUALITY)
        source.recycle()
        return encoded
    }

    /**
     * Encodes an edited 512×512 bitmap as sticker bytes. Anything smaller (or
     * off-square) is letterboxed first, so the editor may hand over whatever
     * it has.
     */
    fun encodeStill(bitmap: Bitmap): ProcessedSticker? {
        val canvas = square(bitmap)
        val encoded = encodeWebp(canvas)
        if (canvas !== bitmap) canvas.recycle()
        return encoded?.let {
            ProcessedSticker(bytes = it, mime = MediaMime.WEBP, animated = false, aspectRatio = 1f)
        }
    }

    /**
     * [bytes] as a sticker if they already meet the spec — a still 512×512
     * WebP inside the size budget — or null when they need re-encoding.
     */
    private fun conforming(bytes: ByteArray): ProcessedSticker? {
        if (bytes.size > TARGET_BYTES) return null
        if (!isWebp(bytes) || isAnimatedWebp(bytes)) return null
        val (width, height) = boundsOf(bytes) ?: return null
        if (width != TARGET_SIZE || height != TARGET_SIZE) return null
        return ProcessedSticker(bytes, MediaMime.WEBP, animated = false, aspectRatio = 1f)
    }

    /**
     * The MIME of an animated source, or null when the bytes are a still.
     *
     * WebP animation is a header flag, so it's exact. GIF frame counting isn't
     * worth the parser: below API 28 (no [ImageDecoder]) every GIF is assumed
     * animated, which only costs a rare single-frame GIF its sticker MIME.
     */
    private fun detectAnimation(bytes: ByteArray): String? {
        if (isGif(bytes)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return MediaMime.GIF
            val animated = runCatching {
                val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                // Sampled as hard as the decoder allows: the frame count is the
                // whole question and nothing here is drawn. Left unsampled this
                // decoded an imported GIF at full size — every frame of it — to
                // answer a yes-or-no. Same idiom as `gifIsAnimated` in
                // AnimatedImages, and as the sampling [decodeStill] already does.
                ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                    decoder.setTargetSampleSize(ANIMATION_PROBE_SAMPLE)
                } is AnimatedImageDrawable
            }.getOrDefault(true)
            return if (animated) MediaMime.GIF else null
        }
        if (isAnimatedWebp(bytes)) return MediaMime.WEBP
        return null
    }

    private fun isGif(b: ByteArray): Boolean =
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    private fun isWebp(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val riff = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
        val webp = b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        return riff && webp
    }

    /** RIFF/WEBP with a VP8X chunk whose animation flag (0x02) is set. */
    private fun isAnimatedWebp(b: ByteArray): Boolean {
        if (b.size < 21 || !isWebp(b)) return false
        val vp8x = b[12] == 'V'.code.toByte() && b[13] == 'P'.code.toByte() &&
            b[14] == '8'.code.toByte() && b[15] == 'X'.code.toByte()
        return vp8x && (b[20].toInt() and 0x02) != 0
    }

    private fun boundsOf(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    /**
     * Decodes a still, downsampling huge sources so a 50-megapixel photo can't
     * blow the heap. [ImageDecoder] is preferred on API 28+ because it applies
     * EXIF rotation; [BitmapFactory] below it does not.
     */
    private fun decodeStill(bytes: ByteArray, maxSide: Int = TARGET_SIZE): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > maxSide) {
                        val scale = maxSide.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
            }
        }
        val bounds = boundsOf(bytes) ?: return null
        var sample = 1
        while (maxOf(bounds.first, bounds.second) / sample > maxSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Fits [source] inside a transparent 512×512 canvas, preserving aspect
     * ratio. Small sources are scaled up so the sticker doesn't arrive as a
     * postage stamp floating in an empty frame.
     */
    private fun square(source: Bitmap): Bitmap {
        if (source.width == TARGET_SIZE && source.height == TARGET_SIZE) return source
        val scale = TARGET_SIZE.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceIn(1, TARGET_SIZE)
        val height = (source.height * scale).toInt().coerceIn(1, TARGET_SIZE)
        val out = Bitmap.createBitmap(TARGET_SIZE, TARGET_SIZE, Bitmap.Config.ARGB_8888)
        val left = (TARGET_SIZE - width) / 2
        val top = (TARGET_SIZE - height) / 2
        Canvas(out).drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(left, top, left + width, top + height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    private fun encodeWebp(bitmap: Bitmap): ByteArray? {
        var last: ByteArray? = null
        for (quality in QUALITY_LADDER) {
            last = encodeWebpAt(bitmap, quality) ?: return last
            if (last.size <= TARGET_BYTES) return last
        }
        return last
    }

    private fun encodeWebpAt(bitmap: Bitmap, quality: Int): ByteArray? {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, out)) return null
        return out.toByteArray()
    }
}
