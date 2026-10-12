package com.wasimaster.wmkeyboard.core.vocab

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A Zstandard decompressor (RFC 8878), decode side only, in plain Kotlin.
 *
 * Anki 2.1.50 and later store the collection inside a deck export as
 * `collection.anki21b`, which is the SQLite file compressed with zstd, and
 * that is the only reason this exists. A native library would cost a
 * megabyte per ABI for one import path; this costs a few hundred lines and
 * runs once per import.
 *
 * Supports everything a stock encoder writes: raw, RLE and compressed blocks,
 * Huffman and treeless literals, predefined / RLE / FSE / repeat sequence
 * tables, repeat offsets, several frames and skippable frames. Dictionaries
 * are refused (no exporter uses one). The content checksum is skipped, not
 * verified: the SQLite file that comes out is checked by opening it.
 *
 * Output streams through a window-sized buffer, so memory is the window (a
 * few MB at the default levels) rather than the whole file.
 */
object Zstd {

    private const val MAGIC = 0xFD2FB528.toInt()
    private const val SKIPPABLE_MASK = 0xFFFFFFF0.toInt()
    private const val SKIPPABLE_MAGIC = 0x184D2A50
    private const val BLOCK_MAX = 128 * 1024

    /** The largest window accepted; `--long` frames past this are refused rather than OOMing the app. */
    const val MAX_WINDOW = 64 * 1024 * 1024

    /** Thrown once the output would pass the caller's limit. */
    class OutputLimitException : IOException("zstd output past the limit")

    /** Whether [head] starts with a zstd frame's magic number. */
    fun isZstd(head: ByteArray): Boolean =
        head.size >= 4 && readIntLe(head, 0) == MAGIC

    /**
     * Inflates every frame of [input] into [output]. Returns the number of
     * bytes written; throws [IOException] on corrupt data, and
     * [OutputLimitException] once more than [maxOutput] bytes would come out.
     */
    fun decompress(input: InputStream, output: OutputStream, maxOutput: Long = Long.MAX_VALUE): Long {
        val source = Source(input)
        var total = 0L
        while (true) {
            val magic = source.intLeOrNull() ?: break
            total += when {
                magic == MAGIC -> Frame(source, output, maxOutput - total).decode()
                magic and SKIPPABLE_MASK == SKIPPABLE_MAGIC -> {
                    source.skip(source.intLe().toLong() and 0xFFFFFFFFL)
                    0L
                }
                else -> throw IOException("Not a zstd frame")
            }
        }
        return total
    }

    /** Convenience for small inputs and tests. */
    fun decompress(data: ByteArray, maxOutput: Long = Long.MAX_VALUE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        decompress(data.inputStream(), out, maxOutput)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- input

    private class Source(private val input: InputStream) {
        fun byte(): Int {
            val b = input.read()
            if (b < 0) throw IOException("Truncated zstd data")
            return b
        }

        fun bytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(out, read, n - read)
                if (r < 0) throw IOException("Truncated zstd data")
                read += r
            }
            return out
        }

        fun intLeOrNull(): Int? {
            val b0 = input.read()
            if (b0 < 0) return null
            return b0 or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)
        }

        fun intLe(): Int = intLeOrNull() ?: throw IOException("Truncated zstd data")

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    byte()
                    left--
                }
            }
        }
    }

    // --------------------------------------------------------------- output

    /** History buffer: keeps at least the window behind the write position, flushing the rest. */
    private class Window(windowSize: Int, private val out: OutputStream, private val maxOutput: Long) {
        private val keep = windowSize
        val buf = ByteArray(windowSize + 2 * BLOCK_MAX)
        var len = 0
        private var flushed = 0L

        /** Makes room for one block's output. */
        fun reserve() {
            if (len + BLOCK_MAX <= buf.size) return
            val drop = len - keep
            out.write(buf, 0, drop)
            flushed += drop
            System.arraycopy(buf, drop, buf, 0, keep)
            len = keep
        }

        fun check(extra: Int) {
            if (flushed + len + extra > maxOutput) throw OutputLimitException()
            if (len + extra > buf.size) throw IOException("Corrupt zstd block")
        }

        fun put(b: Int) {
            buf[len++] = b.toByte()
        }

        fun put(src: ByteArray, off: Int, n: Int) {
            System.arraycopy(src, off, buf, len, n)
            len += n
        }

        fun copyMatch(offset: Int, length: Int) {
            if (offset <= 0 || offset > flushed + len) throw IOException("zstd offset out of range")
            if (offset > len) throw IOException("zstd offset past the window")
            var from = len - offset
            if (offset >= length) {
                System.arraycopy(buf, from, buf, len, length)
                len += length
            } else {
                repeat(length) { buf[len++] = buf[from++] }
            }
        }

        fun finish(): Long {
            out.write(buf, 0, len)
            flushed += len
            len = 0
            return flushed
        }
    }

    // ------------------------------------------------------------ bitstreams

    /** Forward little-endian bit reader, for FSE table descriptions. */
    private class ForwardBits(private val data: ByteArray, private val start: Int, private val end: Int) {
        var bitPos = 0L

        fun read(n: Int): Int {
            var value = 0
            for (i in 0 until n) {
                val p = bitPos + i
                val index = start + (p ushr 3).toInt()
                if (index >= end) throw IOException("Corrupt zstd table")
                val bit = (data[index].toInt() ushr (p and 7).toInt()) and 1
                value = value or (bit shl i)
            }
            bitPos += n
            return value
        }

        fun peek(n: Int): Int {
            val saved = bitPos
            var value = 0
            for (i in 0 until n) {
                val p = saved + i
                val index = start + (p ushr 3).toInt()
                val bit = if (index < end) (data[index].toInt() ushr (p and 7).toInt()) and 1 else 0
                value = value or (bit shl i)
            }
            return value
        }

        fun skip(n: Int) {
            bitPos += n
        }

        val bytesUsed: Int get() = ((bitPos + 7) ushr 3).toInt()
    }

    /**
     * Backward bit reader: the stream is read from its last byte towards its
     * first, most significant bit first, past a 1-bit end marker. Reads past
     * the start return zeros; [overflowed] says whether that happened.
     */
    private class BackwardBits(private val data: ByteArray, private val start: Int, end: Int) {
        var remaining: Int

        init {
            if (end <= start) throw IOException("Empty zstd bitstream")
            val last = data[end - 1].toInt() and 0xFF
            if (last == 0) throw IOException("Corrupt zstd bitstream")
            remaining = (end - start - 1) * 8 + (31 - Integer.numberOfLeadingZeros(last))
        }

        val overflowed: Boolean get() = remaining < 0

        fun peek(n: Int): Int {
            if (n == 0) return 0
            val hi = remaining
            val lo = hi - n
            if (hi <= 0) return 0
            val from = maxOf(lo, 0)
            var acc = 0L
            var byte = (hi - 1) ushr 3
            val first = from ushr 3
            while (byte >= first) {
                acc = (acc shl 8) or (data[start + byte].toLong() and 0xFF)
                byte--
            }
            acc = acc ushr (from and 7)
            val width = hi - from
            acc = acc and ((1L shl width) - 1)
            if (lo < 0) acc = acc shl -lo
            return acc.toInt()
        }

        fun read(n: Int): Int {
            val v = peek(n)
            remaining -= n
            return v
        }

        /** For offsets, which can be up to 31 bits. */
        fun readLong(n: Int): Long {
            if (n <= 24) return read(n).toLong()
            val high = read(n - 24).toLong()
            return (high shl 24) or read(24).toLong()
        }
    }

    // ------------------------------------------------------------------ FSE

    private class FseTable(val log: Int, val symbol: IntArray, val bits: IntArray, val base: IntArray) {
        companion object {
            /** A table that always yields [symbol] and reads nothing. */
            fun rle(symbol: Int) = FseTable(0, intArrayOf(symbol), intArrayOf(0), intArrayOf(0))

            fun build(norm: IntArray, symbols: Int, log: Int): FseTable {
                val size = 1 shl log
                val symbol = IntArray(size)
                val next = IntArray(symbols)
                var high = size - 1
                for (s in 0 until symbols) {
                    if (norm[s] == -1) {
                        symbol[high--] = s
                        next[s] = 1
                    } else {
                        next[s] = norm[s]
                    }
                }
                val step = (size ushr 1) + (size ushr 3) + 3
                val mask = size - 1
                var pos = 0
                for (s in 0 until symbols) {
                    repeat(maxOf(norm[s], 0)) {
                        symbol[pos] = s
                        do {
                            pos = (pos + step) and mask
                        } while (pos > high)
                    }
                }
                if (pos != 0) throw IOException("Corrupt zstd FSE table")
                val bits = IntArray(size)
                val base = IntArray(size)
                for (u in 0 until size) {
                    val s = symbol[u]
                    val state = next[s]++
                    val nb = log - (31 - Integer.numberOfLeadingZeros(state))
                    bits[u] = nb
                    base[u] = (state shl nb) - size
                }
                return FseTable(log, symbol, bits, base)
            }

            /** Reads a table description at [offset]; returns the table and the bytes it took. */
            fun read(data: ByteArray, offset: Int, end: Int, maxLog: Int, maxSymbol: Int): Pair<FseTable, Int> {
                val bits = ForwardBits(data, offset, end)
                val log = bits.read(4) + 5
                if (log > maxLog) throw IOException("zstd FSE accuracy too high")
                val norm = IntArray(maxSymbol + 1)
                var remaining = (1 shl log) + 1
                var threshold = 1 shl log
                var nbBits = log + 1
                var symbol = 0
                while (remaining > 1 && symbol <= maxSymbol) {
                    val max = (2 * threshold - 1) - remaining
                    val low = bits.peek(nbBits - 1)
                    var count: Int
                    if (low < max) {
                        count = low
                        bits.skip(nbBits - 1)
                    } else {
                        count = bits.peek(nbBits) and (2 * threshold - 1)
                        if (count >= threshold) count -= max
                        bits.skip(nbBits)
                    }
                    count--
                    remaining -= if (count < 0) -count else count
                    norm[symbol++] = count
                    if (count == 0) {
                        while (true) {
                            val repeat = bits.read(2)
                            repeat(repeat) { if (symbol <= maxSymbol) norm[symbol++] = 0 }
                            if (repeat != 3) break
                        }
                    }
                    while (remaining < threshold) {
                        nbBits--
                        threshold = threshold ushr 1
                    }
                }
                if (remaining != 1) throw IOException("Corrupt zstd FSE counts")
                return build(norm, symbol, log) to bits.bytesUsed
            }
        }
    }

    private class FseState(private val table: FseTable, stream: BackwardBits) {
        private var state = stream.read(table.log)
        val symbol: Int get() = table.symbol[state]

        fun update(stream: BackwardBits) {
            state = table.base[state] + stream.read(table.bits[state])
        }
    }

    // -------------------------------------------------------------- Huffman

    private class Huffman(val maxBits: Int, val symbol: IntArray, val length: IntArray) {
        fun decode(stream: BackwardBits): Int {
            val index = stream.peek(maxBits)
            stream.remaining -= length[index]
            return symbol[index]
        }

        companion object {
            private const val MAX_BITS = 11

            /** Reads a tree description at [offset]; returns the table and the bytes it took. */
            fun read(data: ByteArray, offset: Int, end: Int): Pair<Huffman, Int> {
                val header = data[offset].toInt() and 0xFF
                val weights = IntArray(256)
                var count: Int
                val used: Int
                if (header >= 128) {
                    count = header - 127
                    used = 1 + (count + 1) / 2
                    if (offset + used > end) throw IOException("Corrupt zstd Huffman header")
                    for (i in 0 until count) {
                        val b = data[offset + 1 + i / 2].toInt() and 0xFF
                        weights[i] = if (i % 2 == 0) b ushr 4 else b and 0xF
                    }
                } else {
                    used = 1 + header
                    if (offset + used > end) throw IOException("Corrupt zstd Huffman header")
                    val (table, tableBytes) = FseTable.read(data, offset + 1, offset + used, 6, 255)
                    val stream = BackwardBits(data, offset + 1 + tableBytes, offset + used)
                    val s1 = FseState(table, stream)
                    val s2 = FseState(table, stream)
                    count = 0
                    while (true) {
                        if (count > 254) throw IOException("Corrupt zstd Huffman weights")
                        weights[count++] = s1.symbol
                        s1.update(stream)
                        if (stream.overflowed) {
                            weights[count++] = s2.symbol
                            break
                        }
                        weights[count++] = s2.symbol
                        s2.update(stream)
                        if (stream.overflowed) {
                            weights[count++] = s1.symbol
                            break
                        }
                    }
                }
                var sum = 0
                for (i in 0 until count) {
                    if (weights[i] > MAX_BITS) throw IOException("Corrupt zstd Huffman weight")
                    if (weights[i] > 0) sum += 1 shl (weights[i] - 1)
                }
                if (sum == 0) throw IOException("Corrupt zstd Huffman weights")
                val maxBits = 32 - Integer.numberOfLeadingZeros(sum)
                val rest = (1 shl maxBits) - sum
                if (rest and (rest - 1) != 0) throw IOException("Corrupt zstd Huffman weights")
                weights[count++] = 32 - Integer.numberOfLeadingZeros(rest)
                if (maxBits > MAX_BITS) throw IOException("zstd Huffman tree too deep")

                val size = 1 shl maxBits
                val symbol = IntArray(size)
                val length = IntArray(size)
                var pos = 0
                for (w in 1..maxBits) {
                    for (s in 0 until count) {
                        if (weights[s] != w) continue
                        val span = 1 shl (w - 1)
                        val bits = maxBits + 1 - w
                        for (k in 0 until span) {
                            symbol[pos + k] = s
                            length[pos + k] = bits
                        }
                        pos += span
                    }
                }
                return Huffman(maxBits, symbol, length) to used
            }
        }
    }

    // ---------------------------------------------------------------- frame

    private val LL_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
        8192, 16384, 32768, 65536,
    )
    private val LL_BITS = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
        13, 14, 15, 16,
    )
    private val ML_BASE = IntArray(53) { if (it < 32) it + 3 else 0 }.also {
        val tail = intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)
        tail.copyInto(it, 32)
    }
    private val ML_BITS = IntArray(53).also {
        val tail = intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        tail.copyInto(it, 32)
    }
    private val LL_DEFAULT = FseTable.build(
        intArrayOf(
            4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
            -1, -1, -1, -1,
        ),
        36,
        6,
    )
    private val ML_DEFAULT = FseTable.build(
        intArrayOf(
            1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
            -1, -1, -1, -1, -1,
        ),
        53,
        6,
    )
    private val OF_DEFAULT = FseTable.build(
        intArrayOf(
            1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1,
        ),
        29,
        5,
    )

    private class Frame(private val source: Source, private val output: OutputStream, private val maxOutput: Long) {
        private lateinit var window: Window
        private var huffman: Huffman? = null
        private var llTable: FseTable? = null
        private var ofTable: FseTable? = null
        private var mlTable: FseTable? = null
        private val rep = intArrayOf(1, 4, 8)
        private var literals = ByteArray(BLOCK_MAX)
        private var literalCount = 0

        fun decode(): Long {
            val descriptor = source.byte()
            val fcsFlag = descriptor ushr 6
            val singleSegment = (descriptor ushr 5) and 1 == 1
            val checksum = (descriptor ushr 2) and 1 == 1
            val dictFlag = descriptor and 3
            if ((descriptor ushr 3) and 1 == 1) throw IOException("zstd reserved bit set")
            var windowSize = 0L
            if (!singleSegment) {
                val w = source.byte()
                val log = 10 + (w ushr 3)
                if (log > 31) throw IOException("zstd window too large")
                val base = 1L shl log
                windowSize = base + (base / 8) * (w and 7)
            }
            val dictBytes = intArrayOf(0, 1, 2, 4)[dictFlag]
            var dictId = 0L
            for (i in 0 until dictBytes) dictId = dictId or (source.byte().toLong() shl (8 * i))
            if (dictId != 0L) throw IOException("zstd dictionaries are not supported")
            val fcsBytes = when (fcsFlag) {
                0 -> if (singleSegment) 1 else 0
                1 -> 2
                2 -> 4
                else -> 8
            }
            var contentSize = 0L
            for (i in 0 until fcsBytes) contentSize = contentSize or (source.byte().toLong() shl (8 * i))
            if (fcsBytes == 2) contentSize += 256
            if (singleSegment) windowSize = contentSize
            if (windowSize > MAX_WINDOW) throw IOException("zstd window too large")
            window = Window(maxOf(windowSize.toInt(), 1), output, maxOutput)

            while (true) {
                val header = source.byte() or (source.byte() shl 8) or (source.byte() shl 16)
                val last = header and 1 == 1
                val type = (header ushr 1) and 3
                val size = header ushr 3
                window.reserve()
                when (type) {
                    0 -> {
                        if (size > BLOCK_MAX) throw IOException("Corrupt zstd block")
                        window.check(size)
                        window.put(source.bytes(size), 0, size)
                    }
                    1 -> {
                        if (size > BLOCK_MAX) throw IOException("Corrupt zstd block")
                        window.check(size)
                        val b = source.byte()
                        repeat(size) { window.put(b) }
                    }
                    2 -> {
                        if (size > BLOCK_MAX) throw IOException("Corrupt zstd block")
                        compressedBlock(source.bytes(size))
                    }
                    else -> throw IOException("Corrupt zstd block type")
                }
                if (last) break
            }
            if (checksum) source.bytes(4)
            return window.finish()
        }

        private fun compressedBlock(data: ByteArray) {
            val pos = readLiterals(data)
            readSequences(data, pos)
        }

        /** Decodes the literals section into [literals]; returns where the sequences start. */
        private fun readLiterals(data: ByteArray): Int {
            val b0 = data[0].toInt() and 0xFF
            val type = b0 and 3
            val sizeFormat = (b0 ushr 2) and 3
            fun at(i: Int) = if (i < data.size) data[i].toInt() and 0xFF else throw IOException("Corrupt zstd literals")
            when (type) {
                0, 1 -> {
                    val (size, headerBytes) = when (sizeFormat) {
                        0, 2 -> (b0 ushr 3) to 1
                        1 -> ((b0 ushr 4) + (at(1) shl 4)) to 2
                        else -> ((b0 ushr 4) + (at(1) shl 4) + (at(2) shl 12)) to 3
                    }
                    if (size > BLOCK_MAX) throw IOException("Corrupt zstd literals")
                    if (type == 0) {
                        if (headerBytes + size > data.size) throw IOException("Corrupt zstd literals")
                        System.arraycopy(data, headerBytes, literals, 0, size)
                        literalCount = size
                        return headerBytes + size
                    }
                    literals.fill(at(headerBytes).toByte(), 0, size)
                    literalCount = size
                    return headerBytes + 1
                }
                else -> {
                    val streams: Int
                    val headerBytes: Int
                    val regenerated: Int
                    val compressed: Int
                    when (sizeFormat) {
                        0, 1 -> {
                            val h = b0 or (at(1) shl 8) or (at(2) shl 16)
                            streams = if (sizeFormat == 0) 1 else 4
                            headerBytes = 3
                            regenerated = (h ushr 4) and 0x3FF
                            compressed = (h ushr 14) and 0x3FF
                        }
                        2 -> {
                            val h = b0 or (at(1) shl 8) or (at(2) shl 16) or (at(3) shl 24)
                            streams = 4
                            headerBytes = 4
                            regenerated = (h ushr 4) and 0x3FFF
                            compressed = (h ushr 18) and 0x3FFF
                        }
                        else -> {
                            val h = (b0.toLong()) or (at(1).toLong() shl 8) or (at(2).toLong() shl 16) or
                                (at(3).toLong() shl 24) or (at(4).toLong() shl 32)
                            streams = 4
                            headerBytes = 5
                            regenerated = ((h ushr 4) and 0x3FFFF).toInt()
                            compressed = ((h ushr 22) and 0x3FFFF).toInt()
                        }
                    }
                    if (regenerated > BLOCK_MAX) throw IOException("Corrupt zstd literals")
                    val end = headerBytes + compressed
                    if (end > data.size) throw IOException("Corrupt zstd literals")
                    var start = headerBytes
                    if (type == 2) {
                        val (tree, used) = Huffman.read(data, start, end)
                        huffman = tree
                        start += used
                    }
                    val tree = huffman ?: throw IOException("zstd treeless literals without a tree")
                    if (streams == 1) {
                        decodeStream(tree, data, start, end, 0, regenerated)
                    } else {
                        if (start + 6 > end) throw IOException("Corrupt zstd literals")
                        val s1 = (data[start].toInt() and 0xFF) or ((data[start + 1].toInt() and 0xFF) shl 8)
                        val s2 = (data[start + 2].toInt() and 0xFF) or ((data[start + 3].toInt() and 0xFF) shl 8)
                        val s3 = (data[start + 4].toInt() and 0xFF) or ((data[start + 5].toInt() and 0xFF) shl 8)
                        val p1 = start + 6
                        val p2 = p1 + s1
                        val p3 = p2 + s2
                        val p4 = p3 + s3
                        if (p4 > end) throw IOException("Corrupt zstd literals")
                        val quarter = (regenerated + 3) / 4
                        decodeStream(tree, data, p1, p2, 0, quarter)
                        decodeStream(tree, data, p2, p3, quarter, quarter)
                        decodeStream(tree, data, p3, p4, 2 * quarter, quarter)
                        decodeStream(tree, data, p4, end, 3 * quarter, regenerated - 3 * quarter)
                    }
                    literalCount = regenerated
                    return end
                }
            }
        }

        private fun decodeStream(tree: Huffman, data: ByteArray, start: Int, end: Int, at: Int, count: Int) {
            if (count < 0) throw IOException("Corrupt zstd literals")
            val stream = BackwardBits(data, start, end)
            for (i in 0 until count) literals[at + i] = tree.decode(stream).toByte()
            if (stream.remaining != 0) throw IOException("Corrupt zstd literal stream")
        }

        private fun readSequences(data: ByteArray, begin: Int) {
            var pos = begin
            fun at(i: Int) = if (i < data.size) data[i].toInt() and 0xFF else throw IOException("Corrupt zstd sequences")
            val b0 = at(pos)
            val count = when {
                b0 == 0 -> 0
                b0 < 128 -> b0.also { pos += 1 }
                b0 < 255 -> (((b0 - 128) shl 8) + at(pos + 1)).also { pos += 2 }
                else -> (at(pos + 1) + (at(pos + 2) shl 8) + 0x7F00).also { pos += 3 }
            }
            if (count == 0) {
                window.check(literalCount)
                window.put(literals, 0, literalCount)
                return
            }
            val modes = at(pos++)
            if (modes and 3 != 0) throw IOException("zstd reserved bits set")
            val (ll, llUsed) = table((modes ushr 6) and 3, data, pos, LL_DEFAULT, llTable, 9, 35)
            pos += llUsed
            val (of, ofUsed) = table((modes ushr 4) and 3, data, pos, OF_DEFAULT, ofTable, 8, 31)
            pos += ofUsed
            val (ml, mlUsed) = table((modes ushr 2) and 3, data, pos, ML_DEFAULT, mlTable, 9, 52)
            pos += mlUsed
            llTable = ll
            ofTable = of
            mlTable = ml

            val stream = BackwardBits(data, pos, data.size)
            val llState = FseState(ll, stream)
            val ofState = FseState(of, stream)
            val mlState = FseState(ml, stream)
            var literalPos = 0
            for (i in 0 until count) {
                val ofCode = ofState.symbol
                val llCode = llState.symbol
                val mlCode = mlState.symbol
                if (ofCode > 31 || llCode > 35 || mlCode > 52) throw IOException("Corrupt zstd sequence")
                val offsetValue = (1L shl ofCode) + stream.readLong(ofCode)
                val matchLength = ML_BASE[mlCode] + stream.read(ML_BITS[mlCode])
                val literalLength = LL_BASE[llCode] + stream.read(LL_BITS[llCode])
                if (i != count - 1) {
                    llState.update(stream)
                    mlState.update(stream)
                    ofState.update(stream)
                }

                val offset: Int
                if (offsetValue > 3) {
                    offset = (offsetValue - 3).toInt()
                    rep[2] = rep[1]
                    rep[1] = rep[0]
                    rep[0] = offset
                } else {
                    val index = offsetValue.toInt() - 1 + if (literalLength == 0) 1 else 0
                    when (index) {
                        0 -> offset = rep[0]
                        1 -> {
                            offset = rep[1]
                            rep[1] = rep[0]
                            rep[0] = offset
                        }
                        2 -> {
                            offset = rep[2]
                            rep[2] = rep[1]
                            rep[1] = rep[0]
                            rep[0] = offset
                        }
                        else -> {
                            offset = rep[0] - 1
                            if (offset <= 0) throw IOException("Corrupt zstd offset")
                            rep[2] = rep[1]
                            rep[1] = rep[0]
                            rep[0] = offset
                        }
                    }
                }

                if (literalPos + literalLength > literalCount) throw IOException("Corrupt zstd literal length")
                window.check(literalLength + matchLength)
                window.put(literals, literalPos, literalLength)
                literalPos += literalLength
                window.copyMatch(offset, matchLength)
            }
            if (stream.remaining != 0) throw IOException("Corrupt zstd sequence stream")
            val tail = literalCount - literalPos
            window.check(tail)
            window.put(literals, literalPos, tail)
        }

        private fun table(
            mode: Int,
            data: ByteArray,
            pos: Int,
            default: FseTable,
            previous: FseTable?,
            maxLog: Int,
            maxSymbol: Int,
        ): Pair<FseTable, Int> = when (mode) {
            0 -> default to 0
            1 -> {
                if (pos >= data.size) throw IOException("Corrupt zstd sequences")
                FseTable.rle(data[pos].toInt() and 0xFF) to 1
            }
            2 -> FseTable.read(data, pos, data.size, maxLog, maxSymbol)
            else -> (previous ?: throw IOException("zstd repeat table without a table")) to 0
        }
    }

    private fun readIntLe(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
