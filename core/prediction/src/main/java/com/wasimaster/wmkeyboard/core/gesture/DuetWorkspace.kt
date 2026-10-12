package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.ChildBuffer

/**
 * Pooled state store and per-token tables for [DuetBeam]: the two-thumb
 * counterpart of [GlideWorkspace], one instance per decoding thread.
 *
 * A state carries up to [SLOTS] open swipes, each with its own alignment
 * column of up to [MAX_SAMPLES] floats, because two thumbs can be mid-swipe
 * at once. Everything is allocated on first use and reused after, so a
 * re-decode on every finger lift costs no garbage beyond the words it emits.
 */
class DuetWorkspace {

    // ---- per-token tables, filled once per decode ----

    var tokenCount = 0
    var keyCount = 0
    val isSwipe = BooleanArray(MAX_TOKENS)
    val tStart = LongArray(MAX_TOKENS)
    val tEnd = LongArray(MAX_TOKENS)

    /** Samples each swipe was resampled to; 1 for a tap. */
    val samples = IntArray(MAX_TOKENS)

    /** Arc length between two of a swipe's samples, in key widths. */
    val arcStep = FloatArray(MAX_TOKENS)

    /** Resampled swipe clocks, [MAX_SAMPLES] per token. */
    val sampleT = LongArray(MAX_TOKENS * MAX_SAMPLES)

    /** Resampled swipe positions in key widths; a tap keeps its point in slot 0. */
    val sampleX = FloatArray(MAX_TOKENS * MAX_SAMPLES)
    val sampleY = FloatArray(MAX_TOKENS * MAX_SAMPLES)

    /** `cost[(token * MAX_SAMPLES + sample) * keyCount + key]`. A tap uses sample 0. */
    var cost = FloatArray(MAX_TOKENS * MAX_SAMPLES * INITIAL_KEYS); private set

    /** `near[token * keyCount + key]`: the token went close enough to that key to spell it. */
    var near = BooleanArray(MAX_TOKENS * INITIAL_KEYS); private set

    /** Keys a swipe could have started on. */
    var startKey = BooleanArray(MAX_TOKENS * INITIAL_KEYS); private set

    /** Keys a swipe could have ended on. */
    var endKey = BooleanArray(MAX_TOKENS * INITIAL_KEYS); private set

    /** Tokens that must be finished before this one may give a letter. */
    val mustBeDone = IntArray(MAX_TOKENS)

    /** Tokens that must at least have started before this one may. */
    val mustBeBegun = IntArray(MAX_TOKENS)

    /**
     * `tapHi[tap * MAX_TOKENS + swipe]`: the last sample of that swipe its
     * letters before the tap may sit on — the tap's moment along the swipe,
     * plus the window. -1 when the swipe had not reached its first sample yet.
     */
    val tapHi = IntArray(MAX_TOKENS * MAX_TOKENS)

    /** The first sample the swipe's letters after the tap may sit on: its moment, less the window. */
    val tapLo = IntArray(MAX_TOKENS * MAX_TOKENS)

    /** Grows the per-key tables for a grid of [keys] keys. */
    fun prepareKeys(keys: Int) {
        keyCount = keys
        val flags = MAX_TOKENS * keys
        if (near.size < flags) {
            near = BooleanArray(flags)
            startKey = BooleanArray(flags)
            endKey = BooleanArray(flags)
        } else {
            near.fill(false, 0, flags)
            startKey.fill(false, 0, flags)
            endKey.fill(false, 0, flags)
        }
        val costs = MAX_TOKENS * MAX_SAMPLES * keys
        if (cost.size < costs) cost = FloatArray(costs)
    }

    // ---- search states ----

    var node = IntArray(INITIAL); private set
    var parent = IntArray(INITIAL); private set
    var viaLabel = CharArray(INITIAL); private set
    var letterCp = IntArray(INITIAL); private set

    /** Token that gave the last letter, -1 at the root. */
    var lastTok = IntArray(INITIAL); private set
    var lastKey = IntArray(INITIAL); private set
    var length = ShortArray(INITIAL); private set

    /** Bit per token that has given all its letters. */
    var done = IntArray(INITIAL); private set

    /** Cost already settled: closed swipes, taps, doubled letters. */
    var settled = FloatArray(INITIAL); private set
    var bound = DoubleArray(INITIAL); private set

    /** Per state and slot: the open swipe's token (-1 for a free slot). */
    var slotTok = IntArray(INITIAL * SLOTS); private set

    /** Per state and slot: the key the open swipe's last letter sits on. */
    var slotKey = IntArray(INITIAL * SLOTS); private set

    /** Per state and slot: the first sample the swipe's next letter may sit on. */
    var slotMin = IntArray(INITIAL * SLOTS); private set

    /** Per state and slot: the lowest value in the open swipe's column. */
    var slotFloor = FloatArray(INITIAL * SLOTS); private set

    /** Per state and slot, [MAX_SAMPLES] alignment costs. */
    var cols = FloatArray(INITIAL * SLOTS * MAX_SAMPLES); private set

    private var size = 0
    private var heap = IntArray(INITIAL)
    var heapSize = 0; private set

    /** A child's columns, built before anyone knows whether it is worth keeping. */
    val scratch = FloatArray(SLOTS * MAX_SAMPLES)
    val scratchTok = IntArray(SLOTS)
    val scratchKey = IntArray(SLOTS)
    val scratchMin = IntArray(SLOTS)
    val scratchFloor = FloatArray(SLOTS)

    val children = ChildBuffer()
    var scoreScratch = DoubleArray(INITIAL)

    private val sb = StringBuilder(24)

    var saturated = false; private set

    fun reset() {
        size = 0
        heapSize = 0
        saturated = false
    }

    /** Loads state [s]'s slots into the scratch, for a child to modify. */
    fun loadScratch(s: Int) {
        System.arraycopy(slotTok, s * SLOTS, scratchTok, 0, SLOTS)
        System.arraycopy(slotKey, s * SLOTS, scratchKey, 0, SLOTS)
        System.arraycopy(slotMin, s * SLOTS, scratchMin, 0, SLOTS)
        System.arraycopy(slotFloor, s * SLOTS, scratchFloor, 0, SLOTS)
        System.arraycopy(cols, s * SLOTS * MAX_SAMPLES, scratch, 0, SLOTS * MAX_SAMPLES)
    }

    /**
     * Appends a state whose slots are the current scratch, returning its id, or
     * -1 when the pool is full.
     */
    @Suppress("LongParameterList")
    fun push(
        node: Int,
        parent: Int,
        viaLabel: Char,
        letterCp: Int,
        lastTok: Int,
        lastKey: Int,
        length: Int,
        done: Int,
        settled: Float,
        bound: Double,
    ): Int {
        if (size >= MAX_STATES) {
            saturated = true
            return -1
        }
        ensure(size + 1)
        val id = size++
        this.node[id] = node
        this.parent[id] = parent
        this.viaLabel[id] = viaLabel
        this.letterCp[id] = letterCp
        this.lastTok[id] = lastTok
        this.lastKey[id] = lastKey
        this.length[id] = length.toShort()
        this.done[id] = done
        this.settled[id] = settled
        this.bound[id] = bound
        System.arraycopy(scratchTok, 0, slotTok, id * SLOTS, SLOTS)
        System.arraycopy(scratchKey, 0, slotKey, id * SLOTS, SLOTS)
        System.arraycopy(scratchMin, 0, slotMin, id * SLOTS, SLOTS)
        System.arraycopy(scratchFloor, 0, slotFloor, id * SLOTS, SLOTS)
        System.arraycopy(scratch, 0, cols, id * SLOTS * MAX_SAMPLES, SLOTS * MAX_SAMPLES)
        heapPush(id)
        return id
    }

    /** Start of state [s]'s column for [slot] within [cols]. */
    fun columnOf(s: Int, slot: Int): Int = (s * SLOTS + slot) * MAX_SAMPLES

    fun popBest(): Int {
        val top = heap[0]
        heapSize--
        if (heapSize > 0) {
            heap[0] = heap[heapSize]
            siftDown(0)
        }
        return top
    }

    /** Rebuilds state [s]'s word from its parent chain; see [GlideWorkspace.materialize]. */
    fun materialize(s: Int): String {
        sb.setLength(0)
        var cur = s
        while (cur >= 0) {
            val label = viaLabel[cur]
            if (label != GlideWorkspace.NO_LABEL) sb.append(label)
            cur = parent[cur]
        }
        val n = sb.length
        val chars = CharArray(n)
        for (i in 0 until n) chars[i] = sb[n - 1 - i]
        return String(chars)
    }

    private fun ensure(needed: Int) {
        if (node.size >= needed) return
        var capacity = node.size
        while (capacity < needed) capacity *= 2
        capacity = minOf(capacity, MAX_STATES)
        node = node.copyOf(capacity)
        parent = parent.copyOf(capacity)
        viaLabel = viaLabel.copyOf(capacity)
        letterCp = letterCp.copyOf(capacity)
        lastTok = lastTok.copyOf(capacity)
        lastKey = lastKey.copyOf(capacity)
        length = length.copyOf(capacity)
        done = done.copyOf(capacity)
        settled = settled.copyOf(capacity)
        bound = bound.copyOf(capacity)
        slotTok = slotTok.copyOf(capacity * SLOTS)
        slotKey = slotKey.copyOf(capacity * SLOTS)
        slotMin = slotMin.copyOf(capacity * SLOTS)
        slotFloor = slotFloor.copyOf(capacity * SLOTS)
        cols = cols.copyOf(capacity * SLOTS * MAX_SAMPLES)
    }

    private fun heapPush(id: Int) {
        if (heapSize == heap.size) heap = heap.copyOf(heap.size * 2)
        heap[heapSize] = id
        var i = heapSize++
        while (i > 0) {
            val up = (i - 1) ushr 1
            if (bound[heap[up]] >= bound[heap[i]]) break
            val tmp = heap[up]
            heap[up] = heap[i]
            heap[i] = tmp
            i = up
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        while (true) {
            val left = i * 2 + 1
            if (left >= heapSize) break
            val right = left + 1
            var best = left
            if (right < heapSize && bound[heap[right]] > bound[heap[left]]) best = right
            if (bound[heap[i]] >= bound[heap[best]]) break
            val tmp = heap[i]
            heap[i] = heap[best]
            heap[best] = tmp
            i = best
        }
    }

    companion object {
        /** Gestures one word may be built from. A token is one bit of an Int. */
        const val MAX_TOKENS = 24

        /** Swipes that can be open at once: one per thumb. */
        const val SLOTS = 2

        /** Most samples one swipe is resampled to; short swipes take fewer. */
        const val MAX_SAMPLES = GlideWorkspace.SAMPLE_POINTS

        /** Frontier cap: ~1.5 MB of columns once grown into. A backstop, as in [GlideWorkspace]. */
        const val MAX_STATES = 4096

        private const val INITIAL = 256
        private const val INITIAL_KEYS = 48
    }
}
