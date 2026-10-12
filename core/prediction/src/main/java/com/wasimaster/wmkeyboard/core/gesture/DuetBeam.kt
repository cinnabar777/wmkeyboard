package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.TrieWalker
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Decoder for a word typed with both thumbs at once (issue #638): any mix of
 * taps and swipes, from either thumb, overlapping in time, read as ONE word.
 *
 * Left thumb taps `s`, right thumb swipes `o→m`, left taps `e`, right swipes
 * `t→h→i→n→g`: "something". A swipe `h→e→l→o` with a tap on `l` landing while
 * it is still going: "hello".
 *
 * **What it models.** Every token gives the word its letters in its own order:
 * a tap gives one letter, a swipe gives two or more, placed along its path
 * exactly the way [GlideBeam] places a stroke's letters (location cost plus the
 * travel term, anchored at both ends). What a single stroke never has to decide
 * is how the tokens' letters *interleave*, and that is the whole new problem.
 *
 * **Interleaving is searched, not guessed.** The trie walk decides, letter by
 * letter, which token the next letter comes from. A state is `(trie node,
 * tokens finished, up to two open swipes each with its own alignment column)`,
 * so `s|om|e|thing`, `so|m...` and every other order is a path through one
 * lattice, and the dictionary settles which ones spell words. That is the
 * difference from enumerating candidate orders up front and decoding each.
 *
 * **What keeps the interleavings honest is the clock.** Two tokens that do not
 * overlap in time came in that order and keep it: one thumb is one pointer at
 * a time, so tokens overlap only when two fingers are down, and there is no
 * need to know which thumb made which token. Concretely, with a slack of
 * [Duet.orderSlackMs] for two fingers landing together:
 *
 *  - a token may give a letter only once every token that *ended* before it
 *    *started* has given all of its letters;
 *  - and only once every token that started before it has started;
 *  - a tap that lands while a swipe is open sits where the swipe's own clock
 *    says it was at that moment: the swipe's letters so far must lie before
 *    that point, give or take [Duet.tapWindowMs], and the rest after it. That
 *    is what puts the `l` tap inside `h→e→l→o`.
 *
 * Two swipes overlapping each other are ordered only by those first two rules;
 * inside the overlap the dictionary decides.
 *
 * **Why the bound stays admissible.** Every cost is non-negative and an open
 * column's floor is a minimum over its possible completions, so a state's
 * `settled + Σ open floors` never exceeds what any word under it finishes at —
 * the [GlideBeam] argument, summed over the open swipes.
 *
 * **Not here yet**, unlike a single stroke: pause, loop and wiggle marks, the
 * shape channel, learned shapes and look-ahead. A word with a single swipe and
 * nothing else should go to [GlideBeam], which has all of them; the caller
 * routes.
 */
class DuetBeam(
    private val tuning: GlideBeam.Tuning = GlideBeam.Tuning(),
    private val duet: Duet = Duet(),
) {

    /** The two-thumb weights, on top of the stroke weights in [GlideBeam.Tuning]. */
    data class Duet(
        /** Two tokens closer than this in time are treated as simultaneous. */
        val orderSlackMs: Long = 60L,
        /**
         * How far either side of a tap's moment, along the other thumb's
         * swipe, the swipe's letters may still fall on the "wrong" side of
         * it: reaction time between two thumbs is not zero.
         */
        val tapWindowMs: Long = 120L,
        /** Tap scatter around the key meant, in key widths: the typing model's. */
        val tapSigma: Float = 0.5f,
        /** Farthest a tap may land from a key and still spell it, in key widths. */
        val tapNearRadius: Float = 1.3f,
        /** Ceiling on one tap's cost, in nats. */
        val tapMaxCost: Float = 6f,
        /** Charge for reading one tap as a doubled letter (`tutti` off one `t`), in nats. */
        val tapRepeatCost: Float = 1.2f,
        /** Samples per key width a swipe is resampled to, before the clamp. */
        val samplesPerKey: Float = 6f,
        /** Fewest samples a swipe is resampled to. */
        val minSamples: Int = 8,
    ) {
        internal val tapInvTwoSigmaSq: Float get() = 1f / (2f * tapSigma * tapSigma)
    }

    /**
     * Decodes [tokens] (pixel coordinates, keys [keyWidth] wide) into up to
     * [limit] words, best first. Empty when there is nothing to decode, more
     * than [DuetWorkspace.MAX_TOKENS] tokens, or no word explains them.
     *
     * Tokens with no clock (every time 0) are taken in list order.
     *
     * [partial] reads the gestures as the *start* of a word instead: every
     * result is a spelling some dictionary word begins with, scored by the
     * commonest word under it. That is what a word still being built shows
     * while its gestures so far finish no word (issue #640) — `s` tapped and
     * `o→m` swiped are "som", on the way to "something".
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun decode(
        tokens: List<GlideToken>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: DuetWorkspace,
        limit: Int = 4,
        partial: Boolean = false,
    ): List<GlideBeam.Candidate> {
        if (tokens.isEmpty() || tokens.size > DuetWorkspace.MAX_TOKENS) return emptyList()
        if (keyWidth <= 0f || keys.keyCount == 0 || sources.isEmpty() || limit <= 0) return emptyList()
        prepare(tokens, keys, keyWidth, ws)

        val k = maxOf(limit * 2, GlideBeam.RESULT_K)
        val results = HashMap<String, GlideBeam.Candidate>()
        var floor = Double.NEGATIVE_INFINITY
        val ordered = sources.sortedByDescending {
            it.logWeight + ln1p(it.walker.maxSubtree(it.walker.root))
        }
        for (src in ordered) {
            val rootBound = src.logWeight + ln1p(src.walker.maxSubtree(src.walker.root))
            if (rootBound < floor - EPS) continue
            floor = searchOne(src, keys, ws, k, results, floor, partial)
        }
        return results.values
            .sortedWith(compareByDescending<GlideBeam.Candidate> { it.score }.thenBy { it.word })
            .take(limit)
    }

    // ---- preparation ----

    private fun prepare(tokens: List<GlideToken>, keys: GlideKeyMap, keyWidth: Float, ws: DuetWorkspace) {
        val count = tokens.size
        ws.tokenCount = count
        ws.prepareKeys(keys.keyCount)
        // Without a clock nothing can be ordered by time, so the list order is
        // the order: a token apiece, none overlapping.
        val clockless = tokens.all { it.tStart == 0L && it.tEnd == 0L }
        // Sorted by start, which is how the user sequenced them; ties keep
        // their list order.
        val sorted = if (clockless) tokens else tokens.sortedBy { it.tStart }
        for (i in 0 until count) {
            val token = sorted[i]
            val swipe = token as? GlideToken.Swipe
            val prepared = swipe != null && prepareSwipe(i, swipe.points, keys, keyWidth, ws)
            if (!prepared) {
                val (x, y) = when (token) {
                    is GlideToken.Tap -> token.x to token.y
                    is GlideToken.Swipe -> token.points.first().x to token.points.first().y
                }
                prepareTap(i, x / keyWidth, y / keyWidth, keys, ws)
            }
            if (clockless) {
                ws.tStart[i] = i * CLOCKLESS_SPACING
                ws.tEnd[i] = i * CLOCKLESS_SPACING + CLOCKLESS_SPACING / 2
            } else {
                ws.tStart[i] = token.tStart
                ws.tEnd[i] = if (ws.isSwipe[i]) token.tEnd else token.tStart
            }
        }
        if (clockless) {
            // Spread each swipe's samples over its synthetic span so a tap's
            // place inside it is computable, even though none will land there.
            for (i in 0 until count) {
                if (!ws.isSwipe[i]) continue
                val n = ws.samples[i]
                val span = ws.tEnd[i] - ws.tStart[i]
                for (j in 0 until n) {
                    ws.sampleT[i * MS + j] = ws.tStart[i] + span * j / maxOf(1, n - 1)
                }
            }
        }
        val slack = duet.orderSlackMs
        for (i in 0 until count) {
            var mustDone = 0
            var mustBegun = 0
            for (j in 0 until count) {
                if (j == i) continue
                if (ws.tEnd[j] + slack < ws.tStart[i]) mustDone = mustDone or (1 shl j)
                if (ws.tStart[j] + slack < ws.tStart[i]) mustBegun = mustBegun or (1 shl j)
            }
            ws.mustBeDone[i] = mustDone
            ws.mustBeBegun[i] = mustBegun
        }
        for (t in 0 until count) {
            if (ws.isSwipe[t]) continue
            val at = ws.tStart[t]
            for (s in 0 until count) {
                if (!ws.isSwipe[s]) continue
                val n = ws.samples[s]
                ws.tapHi[t * MT + s] = lastSampleAtOrBefore(ws, s, n, at + duet.tapWindowMs)
                ws.tapLo[t * MT + s] = firstSampleAtOrAfter(ws, s, n, at - duet.tapWindowMs)
            }
        }
    }

    private fun lastSampleAtOrBefore(ws: DuetWorkspace, s: Int, n: Int, time: Long): Int {
        var j = n - 1
        while (j >= 0 && ws.sampleT[s * MS + j] > time) j--
        return j
    }

    private fun firstSampleAtOrAfter(ws: DuetWorkspace, s: Int, n: Int, time: Long): Int {
        var j = 0
        while (j < n && ws.sampleT[s * MS + j] < time) j++
        return j
    }

    private fun prepareTap(i: Int, x: Float, y: Float, keys: GlideKeyMap, ws: DuetWorkspace) {
        ws.isSwipe[i] = false
        ws.samples[i] = 1
        ws.arcStep[i] = 0f
        ws.sampleX[i * MS] = x
        ws.sampleY[i * MS] = y
        val kc = keys.keyCount
        val base = i * MS * kc
        val nearSq = duet.tapNearRadius * duet.tapNearRadius
        // In the alignment's units, so one `settled` sum carries both kinds of
        // evidence: nats divided by the weight the walk multiplies back.
        val toUnits = (1.0 / tuning.shapeWeight).toFloat()
        for (k in 0 until kc) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            val d = dx * dx + dy * dy
            val nats = minOf(d * duet.tapInvTwoSigmaSq, duet.tapMaxCost)
            ws.cost[base + k] = nats * toUnits
            ws.near[i * kc + k] = d <= nearSq
        }
    }

    /** Resamples and costs swipe [i]; false when it is too short to be a swipe at all. */
    private fun prepareSwipe(
        i: Int,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        ws: DuetWorkspace,
    ): Boolean {
        if (path.size < 2) return false
        var totalPx = 0f
        for (p in 1 until path.size) totalPx += distance(path[p - 1], path[p])
        val totalKw = totalPx / keyWidth
        if (totalKw < MIN_STROKE_LENGTH) return false
        val n = ceil(totalKw * duet.samplesPerKey).toInt().plus(1)
            .coerceIn(duet.minSamples, MS)
        ws.isSwipe[i] = true
        ws.samples[i] = n
        ws.arcStep[i] = totalKw / (n - 1)
        resample(path, keyWidth, totalPx / (n - 1), n, i * MS, ws)

        val kc = keys.keyCount
        val invTwoSigmaSq = tuning.invTwoSigmaSq
        val maxPointCost = tuning.maxPointCost
        val nearCost = tuning.nearCost
        for (j in 0 until n) {
            val base = (i * MS + j) * kc
            val px = ws.sampleX[i * MS + j]
            val py = ws.sampleY[i * MS + j]
            for (k in 0 until kc) {
                val dx = px - keys.keyX[k]
                val dy = py - keys.keyY[k]
                val scaled = (dx * dx + dy * dy) * invTwoSigmaSq
                ws.cost[base + k] = if (scaled > maxPointCost) maxPointCost else scaled
                if (scaled <= nearCost) ws.near[i * kc + k] = true
            }
        }
        anchor(ws.sampleX[i * MS], ws.sampleY[i * MS], keys, ws.startKey, i * kc, tuning.startRadiusSq)
        anchor(
            ws.sampleX[i * MS + n - 1], ws.sampleY[i * MS + n - 1], keys, ws.endKey, i * kc,
            tuning.endRadiusSq,
        )
        return true
    }

    /** [GlideBeam]'s arc-length resample, to [n] samples written from [offset]. */
    @Suppress("LongParameterList")
    private fun resample(
        path: List<GesturePoint>,
        keyWidth: Float,
        stepPx: Float,
        n: Int,
        offset: Int,
        ws: DuetWorkspace,
    ) {
        val first = path.first()
        ws.sampleX[offset] = first.x / keyWidth
        ws.sampleY[offset] = first.y / keyWidth
        ws.sampleT[offset] = first.t
        var out = 1
        var index = 0
        var cx = first.x
        var cy = first.y
        var ct = first.t
        var accumulated = 0f
        while (out < n - 1 && index < path.size - 1) {
            val next = path[index + 1]
            val segment = sqrt((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy))
            if (accumulated + segment >= stepPx && segment > 0f) {
                val fraction = (stepPx - accumulated) / segment
                cx += fraction * (next.x - cx)
                cy += fraction * (next.y - cy)
                ct += ((next.t - ct) * fraction).toLong()
                ws.sampleX[offset + out] = cx / keyWidth
                ws.sampleY[offset + out] = cy / keyWidth
                ws.sampleT[offset + out] = ct
                out++
                accumulated = 0f
            } else {
                accumulated += segment
                cx = next.x
                cy = next.y
                ct = next.t
                index++
            }
        }
        val last = path.last()
        while (out < n) {
            ws.sampleX[offset + out] = last.x / keyWidth
            ws.sampleY[offset + out] = last.y / keyWidth
            ws.sampleT[offset + out] = last.t
            out++
        }
    }

    @Suppress("LongParameterList")
    private fun anchor(
        x: Float,
        y: Float,
        keys: GlideKeyMap,
        out: BooleanArray,
        offset: Int,
        radiusSq: Float,
    ) {
        for (k in 0 until keys.keyCount) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            out[offset + k] = dx * dx + dy * dy <= radiusSq
        }
    }

    // ---- the walk ----

    @Suppress(
        "LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth",
        "CognitiveComplexMethod", "LongMethod",
    )
    private fun searchOne(
        src: FuzzyBeamSearch.WalkSource,
        keys: GlideKeyMap,
        ws: DuetWorkspace,
        k: Int,
        results: HashMap<String, GlideBeam.Candidate>,
        floorIn: Double,
        partial: Boolean,
    ): Double {
        var floor = floorIn
        val walker = src.walker
        val kc = keys.keyCount
        val count = ws.tokenCount
        val allDone = if (count >= Int.SIZE_BITS) -1 else (1 shl count) - 1
        val minFrequency = walker.frequencyAtRank(tuning.vocabularyRank)
        val weight = tuning.shapeWeight
        val eligible = IntArray(count)

        ws.reset()
        for (slot in 0 until SLOTS) {
            ws.scratchTok[slot] = -1
            ws.scratchKey[slot] = -1
            ws.scratchMin[slot] = 0
            ws.scratchFloor[slot] = 0f
        }
        ws.push(
            node = walker.root, parent = -1, viaLabel = GlideWorkspace.NO_LABEL, letterCp = 0,
            lastTok = -1, lastKey = -1, length = 0, done = 0, settled = 0f,
            bound = src.logWeight + ln1p(walker.maxSubtree(walker.root)),
        )

        var pops = 0
        while (ws.heapSize > 0 && pops < GlideBeam.MAX_POPS) {
            val s = ws.popBest()
            if (ws.bound[s] < floor - EPS) break
            pops++

            val node = ws.node[s]
            val length = ws.length[s].toInt()
            val done = ws.done[s]
            val settled = ws.settled[s]
            var openMask = 0
            var freeSlot = -1
            for (slot in 0 until SLOTS) {
                val tok = ws.slotTok[s * SLOTS + slot]
                if (tok >= 0) openMask = openMask or (1 shl tok) else if (freeSlot < 0) freeSlot = slot
            }

            // A prefix counts by the commonest word under it; a word by its own
            // frequency. Either way only once every gesture has given its letters.
            val emits = openMask == 0 && done == allDone && if (partial) {
                length >= 1 && walker.maxSubtree(node) >= maxOf(1, minFrequency)
            } else {
                length >= MIN_WORD_LENGTH && walker.isWord(node) && walker.frequency(node) >= minFrequency
            }
            if (emits) {
                val frequency = if (partial) walker.maxSubtree(node) else walker.frequency(node)
                val score = src.logWeight + ln1p(frequency) - weight * settled
                if (score > floor - EPS || results.size < k) {
                    val word = ws.materialize(s)
                    if (partial ||
                        !(src.tier == FuzzyBeamSearch.Tier.DICTIONARY && GlideJoiners.isNonWord(word))
                    ) {
                        emit(word, score, settled.toDouble(), src.tier, results)
                        if (results.size >= k) floor = kthBest(results, k, ws)
                    }
                }
            }

            // Closing an open swipe: its last letter is on its last sample,
            // under its end anchor. No trie step; the swipe's cost settles.
            for (slot in 0 until SLOTS) {
                val tok = ws.slotTok[s * SLOTS + slot]
                if (tok < 0) continue
                val end = ws.cols[ws.columnOf(s, slot) + ws.samples[tok] - 1]
                if (end >= UNREACHABLE) continue
                if (!ws.endKey[tok * kc + ws.slotKey[s * SLOTS + slot]]) continue
                ws.loadScratch(s)
                ws.scratchTok[slot] = -1
                ws.scratchKey[slot] = -1
                ws.scratchMin[slot] = 0
                ws.scratchFloor[slot] = 0f
                val childSettled = settled + end
                val bound = src.logWeight + ln1p(walker.maxSubtree(node)) -
                    weight * (childSettled + openFloors(ws))
                if (bound < floor - EPS) continue
                val id = ws.push(
                    node = node, parent = s, viaLabel = GlideWorkspace.NO_LABEL,
                    letterCp = ws.letterCp[s], lastTok = ws.lastTok[s], lastKey = ws.lastKey[s],
                    length = length, done = done or (1 shl tok), settled = childSettled, bound = bound,
                )
                if (id < 0) break
            }
            if (length >= MAX_WORD_LENGTH) continue

            // Which tokens may give the next letter, by the clock rules.
            var eligibleCount = 0
            val begun = done or openMask
            for (t in 0 until count) {
                val bit = 1 shl t
                if (begun and bit != 0) continue
                if (ws.mustBeDone[t] and done.inv() != 0) continue
                if (ws.mustBeBegun[t] and begun.inv() != 0) continue
                if (ws.isSwipe[t] && freeSlot < 0) continue
                eligible[eligibleCount++] = t
            }
            val lastTok = ws.lastTok[s]
            val tapRepeatable = lastTok >= 0 && !ws.isSwipe[lastTok]
            if (eligibleCount == 0 && openMask == 0 && !tapRepeatable) continue

            val childCount = walker.childrenInto(node, ws.children)
            val pendingHigh = ws.viaLabel[s].takeIf { it.isHighSurrogate() }
            for (c in 0 until childCount) {
                val label = ws.children.labels[c]
                val child = ws.children.nodes[c]
                val subtree = walker.maxSubtree(child)
                if (subtree < minFrequency) continue
                val head = src.logWeight + ln1p(subtree)

                val codePoint: Int
                if (pendingHigh != null) {
                    if (!label.isLowSurrogate()) continue
                    codePoint = Character.toCodePoint(pendingHigh, label)
                } else if (label.isHighSurrogate()) {
                    if (!silent(s, child, label, settled, head, floor, ws)) break
                    continue
                } else {
                    codePoint = label.code
                }

                val key = keys.keyIndex(codePoint)
                if (key < 0 && length > 0 && GlideJoiners.isJoiner(codePoint) &&
                    !GlideJoiners.isJoiner(ws.viaLabel[s].code)
                ) {
                    if (!silent(s, child, label, settled + tuning.joinerCost, head, floor, ws)) break
                    continue
                }
                if (key < 0 || key >= kc) continue

                val repeat = length > 0 && codePoint == ws.letterCp[s]
                if (repeat && lastTok >= 0) {
                    // A doubled letter off one gesture: a swipe crosses the key
                    // once whether the word has it once or twice, and one tap
                    // may stand for both.
                    val charge = if (ws.isSwipe[lastTok]) {
                        if (openMask and (1 shl lastTok) == 0) Float.NaN
                        else tuning.repeatCost + tuning.unloopedRepeat
                    } else {
                        duet.tapRepeatCost * (1.0 / weight).toFloat()
                    }
                    if (!charge.isNaN()) {
                        ws.loadScratch(s)
                        val childSettled = settled + charge
                        val bound = head - weight * (childSettled + openFloors(ws))
                        if (bound >= floor - EPS) {
                            val id = ws.push(
                                node = child, parent = s, viaLabel = label, letterCp = codePoint,
                                lastTok = lastTok, lastKey = key, length = length + 1, done = done,
                                settled = childSettled, bound = bound,
                            )
                            if (id < 0) break
                        }
                    }
                }

                // A tap.
                for (e in 0 until eligibleCount) {
                    val t = eligible[e]
                    if (ws.isSwipe[t] || !ws.near[t * kc + key]) continue
                    ws.loadScratch(s)
                    if (!placeTapAmongOpen(t, ws)) continue
                    val childSettled = settled + ws.cost[t * MS * kc + key]
                    val bound = head - weight * (childSettled + openFloors(ws))
                    if (bound < floor - EPS) continue
                    val id = ws.push(
                        node = child, parent = s, viaLabel = label, letterCp = codePoint,
                        lastTok = t, lastKey = key, length = length + 1, done = done or (1 shl t),
                        settled = childSettled, bound = bound,
                    )
                    if (id < 0) break
                }

                // The first letter of a swipe, on its first sample.
                if (freeSlot >= 0) {
                    for (e in 0 until eligibleCount) {
                        val t = eligible[e]
                        if (!ws.isSwipe[t]) continue
                        if (!ws.startKey[t * kc + key] || !ws.near[t * kc + key]) continue
                        ws.loadScratch(s)
                        val base = freeSlot * MS
                        val first = ws.cost[t * MS * kc + key]
                        ws.scratch[base] = first
                        for (j in 1 until MS) ws.scratch[base + j] = UNREACHABLE
                        ws.scratchTok[freeSlot] = t
                        ws.scratchKey[freeSlot] = key
                        ws.scratchMin[freeSlot] = 1
                        ws.scratchFloor[freeSlot] = first
                        val bound = head - weight * (settled + openFloors(ws))
                        if (bound < floor - EPS) continue
                        val id = ws.push(
                            node = child, parent = s, viaLabel = label, letterCp = codePoint,
                            lastTok = t, lastKey = key, length = length + 1, done = done,
                            settled = settled, bound = bound,
                        )
                        if (id < 0) break
                    }
                }

                // The next letter along an open swipe.
                for (slot in 0 until SLOTS) {
                    val tok = ws.slotTok[s * SLOTS + slot]
                    if (tok < 0 || !ws.near[tok * kc + key]) continue
                    val prevKey = ws.slotKey[s * SLOTS + slot]
                    // The same key twice running inside one swipe is a doubled
                    // letter, which the repeat above already offers.
                    if (prevKey == key) continue
                    ws.loadScratch(s)
                    val min = advance(ws, s, slot, tok, prevKey, key, keys)
                    if (min >= UNREACHABLE) continue
                    ws.scratchKey[slot] = key
                    ws.scratchFloor[slot] = min
                    val bound = head - weight * (settled + openFloors(ws))
                    if (bound < floor - EPS) continue
                    val id = ws.push(
                        node = child, parent = s, viaLabel = label, letterCp = codePoint,
                        lastTok = tok, lastKey = key, length = length + 1, done = done,
                        settled = settled, bound = bound,
                    )
                    if (id < 0) break
                }
            }
        }
        return floor
    }

    /**
     * Fits tap [t] into the swipes open in the scratch: each one's letters so
     * far must lie before the tap's moment along it (within the window), and
     * its later letters after. False when some open swipe cannot oblige.
     */
    private fun placeTapAmongOpen(t: Int, ws: DuetWorkspace): Boolean {
        for (slot in 0 until SLOTS) {
            val tok = ws.scratchTok[slot]
            if (tok < 0) continue
            val n = ws.samples[tok]
            val hi = ws.tapHi[t * MT + tok]
            val base = slot * MS
            var best = UNREACHABLE
            for (j in 0 until n) {
                if (j > hi) ws.scratch[base + j] = UNREACHABLE
                val v = ws.scratch[base + j]
                if (v < best) best = v
            }
            if (best >= UNREACHABLE) return false
            ws.scratchFloor[slot] = best
            val lo = ws.tapLo[t * MT + tok]
            if (lo > ws.scratchMin[slot]) ws.scratchMin[slot] = lo
        }
        return true
    }

    /**
     * One letter further along the swipe open in [slot], written into the
     * scratch: [GlideBeam]'s banded DP over this swipe's own samples, never
     * placing the letter before the slot's minimum sample. Returns the new
     * column's lowest value.
     */
    @Suppress("LongParameterList")
    private fun advance(
        ws: DuetWorkspace,
        s: Int,
        slot: Int,
        tok: Int,
        prevKey: Int,
        key: Int,
        keys: GlideKeyMap,
    ): Float {
        val n = ws.samples[tok]
        val kc = keys.keyCount
        val step = ws.arcStep[tok]
        val span = keys.distance(prevKey, key)
        val expected = span / step
        val lo = maxOf(1, (expected - tuning.gapWindow).toInt())
        val hi = maxOf(lo, (expected + tuning.gapWindow).toInt() + 1)
        val prev = ws.columnOf(s, slot)
        val out = slot * MS
        val first = maxOf(1, ws.scratchMin[slot])
        for (j in 0 until minOf(first, n)) ws.scratch[out + j] = UNREACHABLE
        var best = UNREACHABLE
        for (j in first until n) {
            val from = maxOf(0, j - hi)
            val to = j - lo
            var bestPrev = UNREACHABLE
            var i = from
            while (i <= to) {
                val at = ws.cols[prev + i]
                if (at < UNREACHABLE) {
                    val gap = (j - i) * step - span
                    val v = at + tuning.gapWeight * (if (gap < 0f) -gap else gap)
                    if (v < bestPrev) bestPrev = v
                }
                i++
            }
            val value = if (bestPrev >= UNREACHABLE) {
                UNREACHABLE
            } else {
                bestPrev + ws.cost[(tok * MS + j) * kc + key]
            }
            ws.scratch[out + j] = value
            if (value < best) best = value
        }
        return best
    }

    /**
     * Steps over a trie edge the gestures say nothing about — the high half of
     * a surrogate pair, or a joiner — with every slot carried over. False when
     * the pool saturated.
     */
    @Suppress("LongParameterList")
    private fun silent(
        s: Int,
        child: Int,
        label: Char,
        settled: Float,
        head: Double,
        floor: Double,
        ws: DuetWorkspace,
    ): Boolean {
        ws.loadScratch(s)
        val bound = head - tuning.shapeWeight * (settled + openFloors(ws))
        if (bound < floor - EPS) return true
        return ws.push(
            node = child, parent = s, viaLabel = label, letterCp = ws.letterCp[s],
            lastTok = ws.lastTok[s], lastKey = ws.lastKey[s], length = ws.length[s].toInt(),
            done = ws.done[s], settled = settled, bound = bound,
        ) >= 0
    }

    private fun openFloors(ws: DuetWorkspace): Float {
        var sum = 0f
        for (slot in 0 until SLOTS) if (ws.scratchTok[slot] >= 0) sum += ws.scratchFloor[slot]
        return sum
    }

    private fun emit(
        word: String,
        score: Double,
        cost: Double,
        tier: FuzzyBeamSearch.Tier,
        results: HashMap<String, GlideBeam.Candidate>,
    ) {
        val existing = results[word]
        if (existing == null || score > existing.score) {
            results[word] = GlideBeam.Candidate(word, score, cost, tier)
        }
    }

    private fun kthBest(results: HashMap<String, GlideBeam.Candidate>, k: Int, ws: DuetWorkspace): Double {
        val size = results.size
        if (size < k) return Double.NEGATIVE_INFINITY
        var scores = ws.scoreScratch
        if (scores.size < size) {
            scores = DoubleArray(size)
            ws.scoreScratch = scores
        }
        var i = 0
        for (c in results.values) scores[i++] = c.score
        java.util.Arrays.sort(scores, 0, size)
        return scores[size - k]
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val MS = DuetWorkspace.MAX_SAMPLES
        private const val MT = DuetWorkspace.MAX_TOKENS
        private const val SLOTS = DuetWorkspace.SLOTS
        private const val UNREACHABLE = GlideWorkspace.UNREACHABLE

        /** Strokes shorter than this are taps, in key widths: [GlideBeam]'s threshold. */
        private const val MIN_STROKE_LENGTH = 0.5f
        private const val MIN_WORD_LENGTH = 2
        private const val MAX_WORD_LENGTH = 24

        /** Synthetic spacing for tokens with no clock, so list order is strict order. */
        private const val CLOCKLESS_SPACING = 1_000L
        private const val EPS = 1e-9

        private fun ln1p(v: Int): Double = ln(1.0 + v)

        /**
         * Which decoder a word's gestures belong to. One swipe alone is a
         * stroke and goes to [GlideBeam], which also reads its pauses and loops;
         * taps alone are typing. Anything else is this decoder's.
         */
        fun needsDuet(tokens: List<GlideToken>): Boolean {
            val swipes = tokens.count { it is GlideToken.Swipe }
            return swipes >= 1 && tokens.size >= 2
        }
    }
}
