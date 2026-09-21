package com.wasimaster.wmkeyboard.core.gesture

/**
 * Pooled state store, alignment columns and index heap for [GlideBeam].
 *
 * One instance per decoding thread. Everything a decode needs is allocated on
 * first use and reused forever after, so a swipe costs no garbage beyond the
 * words it emits — the same contract `BeamWorkspace` holds for the typing beam,
 * and it matters more here: a preview decodes several times per stroke.
 *
 * The one thing this workspace has that `BeamWorkspace` does not is [cols], the
 * dynamic-programming columns. Each state owns [SAMPLE_POINTS] floats recording,
 * for every position along the drawn path, the best cost of having spelled this
 * state's prefix by that position. That is what makes the search exact rather
 * than greedy, and it is also why [MAX_STATES] exists: a column is 192 bytes, so
 * an unbounded frontier would be megabytes per decode rather than a fixed
 * [CAPACITY_BYTES_NOTE].
 */
class GlideWorkspace {

    var node = IntArray(INITIAL); private set
    var parent = IntArray(INITIAL); private set
    var viaLabel = CharArray(INITIAL); private set

    /**
     * Code point of the last whole letter spelled on this path, 0 at the root.
     * [viaLabel] is one trie edge, and a letter outside the BMP is two edges —
     * the high surrogate, then the low — so a state that has consumed only the
     * first half carries its parent's letter here, and the doubled-letter test
     * compares letters rather than halves.
     */
    var letterCp = IntArray(INITIAL); private set

    /** Key index the last consumed character sits on; -1 at the root. */
    var lastKey = IntArray(INITIAL); private set

    /** Characters spelled so far, repeats included. */
    var length = ShortArray(INITIAL); private set

    /** Cost accumulated outside the alignment: doubled letters, for now. */
    var extra = FloatArray(INITIAL); private set

    /** Best (lowest) value in this state's column — the admissible remainder. */
    var floorCost = FloatArray(INITIAL); private set

    var bound = DoubleArray(INITIAL); private set

    /** [SAMPLE_POINTS] alignment costs per state, laid end to end. */
    var cols = FloatArray(INITIAL * SAMPLE_POINTS); private set

    private var size = 0
    private var heap = IntArray(INITIAL)
    var heapSize = 0; private set

    /** Resampled path: key-width coordinates and the sample clock. */
    val pathX = FloatArray(SAMPLE_POINTS)
    val pathY = FloatArray(SAMPLE_POINTS)
    val pathT = LongArray(SAMPLE_POINTS)

    /**
     * Arc length between consecutive samples, in key widths. Because the
     * resampling is by arc length this single number converts a gap in sample
     * indices into a distance the finger actually travelled, which is the whole
     * basis of the decoder's second cost term.
     */
    var arcStep = 0f

    /** `pointCost[i * keyCount + k]`, filled per decode by [GlideBeam]. */
    var pointCost = FloatArray(SAMPLE_POINTS * INITIAL_KEYS); private set

    /**
     * How long the finger lingered on each key, 0 (flew past) to 1 (stopped
     * dead). The one thing a stroke's shape cannot say is whether a letter was
     * written twice, and this is the only evidence there is.
     */
    var keyDwell = FloatArray(INITIAL_KEYS); private set

    /**
     * The pauses themselves, as events rather than per-key maxima: the sample
     * each hold sits on and how still the finger was there, 0 to 1. One event
     * per hold however many samples it stretches over. [keyDwell] answers "did
     * the finger pause on *this* key"; these answer the converse a finished
     * word is charged for — "which pauses did it fail to account for".
     */
    val pauseAt = IntArray(SAMPLE_POINTS)
    val pauseScore = FloatArray(SAMPLE_POINTS)
    var pauseCount = 0

    /**
     * Effective arc at each sample, in key widths: `j * arcStep`, except that
     * inside a loop window the arc is collapsed to the window's chord. The
     * alignment reads the finger's travel between two samples off this rather
     * than off their index gap, so a loop drawn on a key costs no word any
     * travel — see [GlideBeam.Tuning.loopExtent].
     */
    val arcAt = FloatArray(SAMPLE_POINTS)

    /** Samples' worth of arc the loops removed; widens the alignment band's far edge. */
    var collapsedSamples = 0

    /**
     * The places the finger went round on itself — a loop or a back-and-forth
     * on one key — as events: the window of samples each covers, its centroid
     * in key widths, and how much of a doubled-letter mark it is, 0 to 1.
     */
    val loopFrom = IntArray(SAMPLE_POINTS)
    val loopTo = IntArray(SAMPLE_POINTS)
    val loopX = FloatArray(SAMPLE_POINTS)
    val loopY = FloatArray(SAMPLE_POINTS)
    val loopScore = FloatArray(SAMPLE_POINTS)
    /** How much of each loop's path its chord is — what the collapse leaves of it. */
    val loopShrink = FloatArray(SAMPLE_POINTS)
    var loopCount = 0

    /**
     * The stroke resampled finely, for the loop search alone: [fineCount]
     * points [fineStep] key widths apart. The alignment's samples are too
     * coarse for it — on a long word they sit half a key apart, and a loop
     * on a key is three of them.
     */
    var fineX = FloatArray(INITIAL_FINE); private set
    var fineY = FloatArray(INITIAL_FINE); private set
    var fineCount = 0
    var fineStep = 0f

    /** Grows the fine arrays to hold [count] points. */
    fun prepareFine(count: Int) {
        if (fineX.size < count) {
            fineX = FloatArray(count)
            fineY = FloatArray(count)
        }
    }

    /** Per key, the strongest loop event within reach of it; the twin of [keyDwell]. */
    var keyLoop = FloatArray(INITIAL_KEYS); private set

    /** Keys the drawn path passes close enough to be spelling. */
    var nearKey = BooleanArray(INITIAL_KEYS); private set

    /** Keys close enough to the stroke's first sample to start a word. */
    var startKey = BooleanArray(INITIAL_KEYS); private set

    /** Keys close enough to the stroke's last sample to end one. */
    var endKey = BooleanArray(INITIAL_KEYS); private set

    val children = com.wasimaster.wmkeyboard.core.prediction.ChildBuffer()
    val lookAheadChildren = com.wasimaster.wmkeyboard.core.prediction.ChildBuffer()

    /** Scratch score array for kthBest floor tracking without allocation. */
    var scoreScratch = DoubleArray(INITIAL)

    /**
     * Where a candidate column is built before anyone knows whether it is worth
     * keeping. The bound that decides that is read off the column itself, so
     * the alternative would be allocating a state to hold a column we then
     * throw away — which at this state count is most of them.
     */
    val scratch = FloatArray(SAMPLE_POINTS)

    /**
     * The drawn stroke and a candidate's ideal path, each translated to its own
     * centroid and scaled to its own size, for the shape rescore. Held here
     * rather than allocated per candidate: the rescore runs over a handful of
     * words on every preview event.
     */
    val drawnShapeX = FloatArray(SAMPLE_POINTS)
    val drawnShapeY = FloatArray(SAMPLE_POINTS)

    /** The drawn shape as the shape store keeps one, for comparing against learned shapes. */
    val drawnShape8 = ByteArray(2 * SAMPLE_POINTS)
    val idealShapeX = FloatArray(SAMPLE_POINTS)
    val idealShapeY = FloatArray(SAMPLE_POINTS)

    /** A candidate word's key centres, before resampling. */
    val idealX = FloatArray(MAX_IDEAL_POINTS)
    val idealY = FloatArray(MAX_IDEAL_POINTS)

    private val sb = StringBuilder(24)

    /** True once the state pool is full — the decode is running on a backstop. */
    var saturated = false; private set

    fun reset() {
        size = 0
        heapSize = 0
        saturated = false
    }

    /** Grows the per-key scratch to cover a grid of [keyCount] keys. */
    fun prepareKeys(keyCount: Int) {
        if (nearKey.size < keyCount) {
            nearKey = BooleanArray(keyCount)
            startKey = BooleanArray(keyCount)
            endKey = BooleanArray(keyCount)
            keyDwell = FloatArray(keyCount)
            keyLoop = FloatArray(keyCount)
        } else {
            nearKey.fill(false, 0, keyCount)
            startKey.fill(false, 0, keyCount)
            endKey.fill(false, 0, keyCount)
            keyDwell.fill(0f, 0, keyCount)
            keyLoop.fill(0f, 0, keyCount)
        }
        pauseCount = 0
        loopCount = 0
        val needed = SAMPLE_POINTS * keyCount
        if (pointCost.size < needed) pointCost = FloatArray(needed)
    }

    /**
     * Appends a state and schedules it for expansion, returning its id — or -1
     * when the pool is full, which the caller treats as "do not explore this".
     */
    @Suppress("LongParameterList")
    fun push(
        node: Int,
        parent: Int,
        viaLabel: Char,
        lastKey: Int,
        length: Int,
        extra: Float,
        floorCost: Float,
        bound: Double,
        letterCp: Int,
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
        this.lastKey[id] = lastKey
        this.length[id] = length.toShort()
        this.extra[id] = extra
        this.floorCost[id] = floorCost
        this.bound[id] = bound
        heapPush(id)
        return id
    }

    /** Start of state [id]'s column within [cols]. */
    fun columnOf(id: Int): Int = id * SAMPLE_POINTS

    /** Commits [scratch] as state [id]'s column. */
    fun storeScratch(id: Int) {
        System.arraycopy(scratch, 0, cols, id * SAMPLE_POINTS, SAMPLE_POINTS)
    }

    /** Copies one state's column onto another — a repeated letter reuses it. */
    fun copyColumn(from: Int, to: Int) {
        System.arraycopy(cols, from * SAMPLE_POINTS, cols, to * SAMPLE_POINTS, SAMPLE_POINTS)
    }

    fun popBest(): Int {
        val top = heap[0]
        heapSize--
        if (heapSize > 0) {
            heap[0] = heap[heapSize]
            siftDown(0)
        }
        return top
    }

    /**
     * Rebuilds the word for state [s] from its parent chain.
     *
     * The chain is walked leaf to root and reversed by hand, one UTF-16 unit at
     * a time. `StringBuilder.reverse()` will not do: it keeps any high-low pair
     * it finds together, and the leaf-first sequence of a word outside the BMP
     * is full of *low-high* pairs that it reads as the pairs of the next letter
     * along — every Osage or Warang Citi word came out with a stray surrogate
     * at each end and its letters' halves swapped.
     */
    fun materialize(s: Int): String {
        sb.setLength(0)
        var cur = s
        while (cur >= 0) {
            val label = viaLabel[cur]
            if (label != NO_LABEL) sb.append(label)
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
        lastKey = lastKey.copyOf(capacity)
        length = length.copyOf(capacity)
        extra = extra.copyOf(capacity)
        floorCost = floorCost.copyOf(capacity)
        bound = bound.copyOf(capacity)
        cols = cols.copyOf(capacity * SAMPLE_POINTS)
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
        /**
         * Path samples the drawn stroke is resampled to. The alignment is
         * O(SAMPLE_POINTS) per trie edge and a column is 4 bytes a sample, so
         * this is the decoder's one real size dial.
         */
        const val SAMPLE_POINTS = 48

        /**
         * Frontier cap, worth ~590 KB of columns once the pool has grown into
         * it (MAX_STATES × SAMPLE_POINTS × 4 bytes). Best-first order means the
         * states that matter are expanded first, so hitting this is a backstop
         * rather than a bound anyone should reach; [saturated] records when it
         * happened so the harness can see it rather than reading a quietly
         * worse number.
         */
        const val MAX_STATES = 3072

        /** Longest key sequence a candidate's ideal path can have. */
        const val MAX_IDEAL_POINTS = 32

        /** Most points the loop search's fine resample holds; a longer stroke is spaced out to fit. */
        const val MAX_FINE_POINTS = 1024

        private const val INITIAL_FINE = 256

        private const val INITIAL = 256
        private const val INITIAL_KEYS = 48

        /** Stands in for "unreachable" in a column without overflowing on add. */
        const val UNREACHABLE = Float.MAX_VALUE / 4f

        /** Sentinel for the root, which consumed no trie edge. */
        val NO_LABEL: Char = 0.toChar()
    }
}
