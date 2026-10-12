package com.wasimaster.wmkeyboard.core.stickers.telegram

/**
 * The frames of a WebM file, as they sit in the container: compressed, in
 * order, with their times and, when the video has one, the alpha channel that
 * goes with each.
 *
 * A Telegram video sticker is a VP9 WebM with transparency. VP9 itself has no
 * alpha, so the encoder writes a second VP9 stream whose luma plane is the
 * alpha and Matroska carries each of its frames beside the colour frame it
 * belongs to, as a `BlockAdditional` with id 1. Android's own extractor reads
 * the colour frames and drops those, which is the whole reason this exists:
 * decoded through the platform alone every sticker comes out on a black square.
 *
 * Read by hand, like Signal's manifest: a handful of element ids out of EBML,
 * and the app carries no Matroska library to read them with. Pure Kotlin, so
 * it is tested on the JVM; the decoding is [WebmFrames].
 */
class WebmVideo(
    val width: Int,
    val height: Int,
    /** Matroska's codec id, `V_VP9` or `V_VP8`. */
    val codec: String,
    /** True when the track says its frames carry alpha (`AlphaMode` 1). */
    val hasAlpha: Boolean,
    val frames: List<Frame>,
    /** How long the last frame shows, from the track or the file, or 0 when neither says. */
    val lastFrameMs: Int,
) {

    /** One frame: when it shows, its colour bitstream, and its alpha bitstream if it has one. */
    class Frame(val timeMs: Long, val data: ByteArray, val alpha: ByteArray?)

    /** How long each frame shows, in order, from the gap to the next one. */
    fun durationsMs(): IntArray {
        val out = IntArray(frames.size)
        for (i in frames.indices) {
            val gap = if (i + 1 < frames.size) frames[i + 1].timeMs - frames[i].timeMs else 0L
            out[i] = gap.toInt()
        }
        if (out.isNotEmpty()) {
            val previous = if (out.size > 1) out[out.size - 2] else 0
            out[out.size - 1] = lastFrameMs.takeIf { it > 0 } ?: previous
        }
        return out
    }

    companion object {

        private const val EBML = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val INFO = 0x1549A966L
        private const val TIMECODE_SCALE = 0x2AD7B1L
        private const val DURATION = 0x4489L
        private const val TRACKS = 0x1654AE6BL
        private const val TRACK_ENTRY = 0xAEL
        private const val TRACK_NUMBER = 0xD7L
        private const val TRACK_TYPE = 0x83L
        private const val CODEC_ID = 0x86L
        private const val DEFAULT_DURATION = 0x23E383L
        private const val VIDEO = 0xE0L
        private const val PIXEL_WIDTH = 0xB0L
        private const val PIXEL_HEIGHT = 0xBAL
        private const val ALPHA_MODE = 0x53C0L
        private const val CLUSTER = 0x1F43B675L
        private const val CLUSTER_TIMECODE = 0xE7L
        private const val SIMPLE_BLOCK = 0xA3L
        private const val BLOCK_GROUP = 0xA0L
        private const val BLOCK = 0xA1L
        private const val BLOCK_ADDITIONS = 0x75A1L
        private const val BLOCK_MORE = 0xA6L
        private const val BLOCK_ADD_ID = 0xEEL
        private const val BLOCK_ADDITIONAL = 0xA5L

        /** Elements whose body is more elements, walked into rather than skipped. */
        private val MASTERS = setOf(
            SEGMENT, INFO, TRACKS, TRACK_ENTRY, VIDEO, CLUSTER, BLOCK_GROUP, BLOCK_ADDITIONS, BLOCK_MORE,
        )

        private const val TRACK_TYPE_VIDEO = 1L
        private const val ALPHA_ADD_ID = 1L
        private const val DEFAULT_TIMECODE_SCALE_NS = 1_000_000L
        private const val NS_PER_MS = 1_000_000L
        private const val LACING_BITS = 0x06
        private const val BYTE = 0xFF

        /**
         * A sticker is three seconds; this is ten times that at 60 frames a
         * second. Past it the file is not a sticker, and its frames are not
         * worth holding.
         */
        private const val MAX_FRAMES = 1800

        /** The first video track of [bytes], or null when they are not a WebM this can read. */
        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun parse(bytes: ByteArray): WebmVideo? {
            val reader = EbmlReader(bytes)
            val first = reader.header() ?: return null
            if (first.id != EBML) return null
            reader.skip(first)

            var scaleNs = DEFAULT_TIMECODE_SCALE_NS
            var durationTicks = 0.0
            val tracks = ArrayList<Track>()
            var track: Track? = null
            var clusterTime = 0L
            val blocks = ArrayList<RawBlock>()
            var group: RawBlock? = null
            var addId = ALPHA_ADD_ID

            while (!reader.exhausted) {
                val element = reader.header() ?: break
                if (element.id in MASTERS) {
                    when (element.id) {
                        TRACK_ENTRY -> track = Track().also { tracks += it }
                        BLOCK_GROUP -> group = null
                        BLOCK_MORE -> addId = ALPHA_ADD_ID
                    }
                    reader.enter(element)
                    continue
                }
                if (!reader.fits(element)) break
                when (element.id) {
                    TIMECODE_SCALE -> scaleNs = reader.uint(element).takeIf { it > 0 } ?: scaleNs
                    DURATION -> durationTicks = reader.float(element)
                    TRACK_NUMBER -> track?.number = reader.uint(element)
                    TRACK_TYPE -> track?.type = reader.uint(element)
                    CODEC_ID -> track?.codec = reader.text(element)
                    DEFAULT_DURATION -> track?.defaultDurationNs = reader.uint(element)
                    PIXEL_WIDTH -> track?.width = reader.uint(element).toInt()
                    PIXEL_HEIGHT -> track?.height = reader.uint(element).toInt()
                    ALPHA_MODE -> track?.alphaMode = reader.uint(element)
                    CLUSTER_TIMECODE -> clusterTime = reader.uint(element)
                    SIMPLE_BLOCK -> block(bytes, reader.offset, element.size, clusterTime)?.let { blocks += it }
                    BLOCK -> group = block(bytes, reader.offset, element.size, clusterTime)?.also { blocks += it }
                    BLOCK_ADD_ID -> addId = reader.uint(element)
                    BLOCK_ADDITIONAL -> if (addId == ALPHA_ADD_ID) group?.alpha = reader.slice(element)
                }
                reader.skip(element)
                if (blocks.size > MAX_FRAMES) return null
            }

            val video = tracks.firstOrNull { it.type == TRACK_TYPE_VIDEO && it.width > 0 && it.height > 0 }
                ?: return null
            val frames = blocks.asSequence()
                .filter { it.track == video.number }
                .map { raw ->
                    Frame(
                        timeMs = raw.ticks * scaleNs / NS_PER_MS,
                        data = bytes.copyOfRange(raw.from, raw.until),
                        alpha = raw.alpha,
                    )
                }
                // Container order is decode order, and for VP9 that is also
                // display order: it has no frames shown out of turn.
                .toList()
            if (frames.isEmpty()) return null

            val fromTrack = (video.defaultDurationNs / NS_PER_MS).toInt()
            val fromFile = if (durationTicks > 0) {
                ((durationTicks * scaleNs / NS_PER_MS).toLong() - frames.last().timeMs).toInt()
            } else {
                0
            }
            return WebmVideo(
                width = video.width,
                height = video.height,
                codec = video.codec,
                hasAlpha = video.alphaMode == 1L || frames.any { it.alpha != null },
                frames = frames,
                lastFrameMs = fromTrack.takeIf { it > 0 } ?: fromFile.coerceAtLeast(0),
            )
        }

        /**
         * The block at [from], or null when it is laced: video is never laced,
         * and reading a laced block as one frame would hand the decoder junk.
         */
        private fun block(bytes: ByteArray, from: Int, size: Long, clusterTime: Long): RawBlock? {
            val end = from + size.toInt()
            val track = EbmlReader(bytes, from, end).vint(keepMarker = false) ?: return null
            val at = from + track.length
            if (at + 3 > end) return null
            val relative = ((bytes[at].toInt() shl 8) or (bytes[at + 1].toInt() and BYTE)).toShort().toLong()
            val flags = bytes[at + 2].toInt() and BYTE
            if (flags and LACING_BITS != 0) return null
            return RawBlock(track.value, clusterTime + relative, at + 3, end)
        }
    }

    private class Track {
        var number = 0L
        var type = 0L
        var codec = ""
        var width = 0
        var height = 0
        var alphaMode = 0L
        var defaultDurationNs = 0L
    }

    private class RawBlock(val track: Long, val ticks: Long, val from: Int, val until: Int) {
        var alpha: ByteArray? = null
    }
}

/** An element's id, the size of its body, and where that body starts. */
internal class EbmlElement(val id: Long, val size: Long, val bodyAt: Int) {
    /** EBML's "size not known", which a streamed segment or cluster may say. */
    val unknownSize: Boolean get() = size < 0
}

/** A variable-length integer and how many bytes it took. */
internal class Vint(val value: Long, val length: Int)

/**
 * Just enough EBML to walk a Matroska file: an element's id and size, and the
 * four kinds of value read here. Anything malformed stops the walk.
 */
internal class EbmlReader(
    private val data: ByteArray,
    start: Int = 0,
    private val end: Int = data.size,
) {
    var offset: Int = start
        private set

    val exhausted: Boolean get() = offset >= end

    fun header(): EbmlElement? {
        val id = vint(keepMarker = true) ?: return null
        val size = vint(keepMarker = false) ?: return null
        val unknown = size.value == (1L shl (size.length * 7)) - 1
        return EbmlElement(id.value, if (unknown) -1L else size.value, offset)
    }

    /** Steps into [element], so its children come next. */
    fun enter(element: EbmlElement) {
        offset = element.bodyAt
    }

    /** Steps past [element]'s body. */
    fun skip(element: EbmlElement) {
        offset = if (element.unknownSize) end else (element.bodyAt + element.size).coerceAtMost(end.toLong()).toInt()
    }

    fun fits(element: EbmlElement): Boolean =
        !element.unknownSize && element.bodyAt + element.size <= end

    fun uint(element: EbmlElement): Long {
        var value = 0L
        for (i in 0 until element.size.toInt().coerceAtMost(Long.SIZE_BYTES)) {
            value = (value shl Byte.SIZE_BITS) or (data[element.bodyAt + i].toLong() and BYTE_MASK)
        }
        return value
    }

    fun float(element: EbmlElement): Double = when (element.size) {
        4L -> java.lang.Float.intBitsToFloat(uint(element).toInt()).toDouble()
        8L -> java.lang.Double.longBitsToDouble(uint(element))
        else -> 0.0
    }

    fun text(element: EbmlElement): String =
        String(data, element.bodyAt, element.size.toInt(), Charsets.US_ASCII).trimEnd('\u0000')

    fun slice(element: EbmlElement): ByteArray =
        data.copyOfRange(element.bodyAt, element.bodyAt + element.size.toInt())

    /**
     * A variable-length integer at the current offset. An id keeps its length
     * marker (that is how ids are written down); a size or track number drops it.
     */
    fun vint(keepMarker: Boolean): Vint? {
        if (offset >= end) return null
        val first = data[offset].toInt() and BYTE
        if (first == 0) return null
        val length = Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - Byte.SIZE_BITS) + 1
        if (length > MAX_VINT || offset + length > end) return null
        var value = if (keepMarker) first.toLong() else (first and (BYTE shr length)).toLong()
        for (i in 1 until length) value = (value shl Byte.SIZE_BITS) or (data[offset + i].toLong() and BYTE_MASK)
        offset += length
        return Vint(value, length)
    }

    private companion object {
        const val BYTE = 0xFF
        const val BYTE_MASK = 0xFFL
        const val MAX_VINT = 8
    }
}
