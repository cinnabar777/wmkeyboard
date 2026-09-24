package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.ln

/**
 * Trie-guided fuzzy search: corrections and completions in one best-first
 * walk. Instead of generating every edit of the typed word and probing the
 * dictionary (Norvig generate-and-test), the walk descends the trie itself,
 * so only dictionary-reachable strings are ever considered — which makes
 * two-edit corrections affordable, corrects mid-word prefixes while
 * completing them, and draws substitution candidates from the trie's actual
 * edge labels (any script, not just ASCII).
 *
 * Scoring is log-space: `score = sourceLogWeight + ln(1 + frequency) - editCost`.
 * Edit penalties are the negative logs of the legacy multiplicative weights,
 * so relative ranking matches the edits-1 engine it replaces. A word reachable
 * several ways keeps its best score (max-merge, matching the engine's
 * historical maxOf semantics).
 *
 * The expansion order is admissible best-first: a state's bound is
 * `sourceLogWeight + ln(1 + maxSubtree(node)) - cost`, which only ever
 * overestimates, so pruning against the current K-th best emitted score never
 * loses a top-K word.
 */
class FuzzyBeamSearch {

    /** One weighted trie participating in a search. */
    class WalkSource(
        val walker: TrieWalker,
        val logWeight: Double,
        val tier: Tier,
    )

    enum class Tier { DICTIONARY, USER }

    /**
     * Per-keystroke touch evidence: the layout's key-center model plus the
     * tap position for each composing character (null where unknown —
     * hardware keys, pasted text, re-armed words).
     */
    class TouchScoring(val model: KeyTouchModel, val points: List<TouchPoint?>) {
        val bestLogLikelihoods: DoubleArray = DoubleArray(points.size) { i ->
            val p = points[i]
            val best = p?.let { model.bestKey(it) }
            if (p != null && best != null) model.logLikelihood(p, best) else Double.NEGATIVE_INFINITY
        }
    }

    class ScoredCandidate(
        val word: String,
        val score: Double,
        val editCost: Double,
        val edits: Int,
        /** Characters appended beyond the typed length by completion descent. */
        val completedChars: Int,
        val tier: Tier,
        /** Best score contributed by DICTIONARY-tier sources (NEGATIVE_INFINITY if none). */
        val dictScore: Double = Double.NEGATIVE_INFINITY,
        /** Best score contributed by USER-tier sources (NEGATIVE_INFINITY if none). */
        val userScore: Double = Double.NEGATIVE_INFINITY,
        /**
         * Letters read as their accented form ([Accents]): `juz` reaching `już`
         * carries 1. These are not edits, so neither [edits] nor [editCost]
         * counts them.
         */
        val accents: Int = 0,
    )

    fun search(
        sources: List<WalkSource>,
        typed: CharSequence,
        proximity: KeyProximity,
        limit: Int,
        workspace: BeamWorkspace,
        maxEdits: Int = defaultMaxEdits(typed.length),
        touch: TouchScoring? = null,
        habits: EditHabits = EditHabits.NONE,
        keys: KeySets? = null,
    ): List<ScoredCandidate> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val k = maxOf(limit * 2, AUTOCORRECT_K)
        // Typo correction stacked on key ambiguity is speculation squared, and
        // it is what makes the frontier explode: a three-way key already fans
        // every position, and letting each of those fan again exhausts MAX_POPS
        // on a five-letter word before the real reading is ever emitted.
        val edits = if (keys?.isAmbiguous == true) minOf(maxEdits, AMBIGUOUS_MAX_EDITS) else maxEdits
        val results = HashMap<String, ScoredCandidate>()
        var floor = Double.NEGATIVE_INFINITY

        // Heaviest source first: its emissions raise the floor early, letting
        // lighter sources terminate after a handful of expansions.
        val ordered = if (sources.size <= 1) {
            sources
        } else {
            sources.sortedByDescending {
                it.logWeight + ln1p(it.walker.maxSubtree(it.walker.root))
            }
        }
        for (src in ordered) {
            val rootBound = src.logWeight + ln1p(src.walker.maxSubtree(src.walker.root))
            if (rootBound < floor - EPS) continue
            floor = searchOne(
                src, typed, proximity, edits, k, results, floor, workspace, touch, habits, keys,
            )
        }

        return results.values.sortedWith(
            compareByDescending<ScoredCandidate> { it.score }.thenBy { it.word }
        ).take(k)
    }

    @Suppress("LongParameterList")
    private fun searchOne(
        src: WalkSource,
        typed: CharSequence,
        proximity: KeyProximity,
        maxEdits: Int,
        k: Int,
        results: HashMap<String, ScoredCandidate>,
        floorIn: Double,
        ws: BeamWorkspace,
        touch: TouchScoring?,
        habits: EditHabits,
        keys: KeySets?,
    ): Double {
        var floor = floorIn
        val walker = src.walker
        val n = typed.length
        ws.reset()
        ws.pushState(
            node = walker.root, pos = 0, cost = 0.0, editSpend = 0.0, edits = 0, comp = 0, accents = 0,
            parent = -1, viaLabel = BeamWorkspace.NO_LABEL,
            bound = src.logWeight + ln1p(walker.maxSubtree(walker.root)),
        )
        var pops = 0
        while (ws.heapSize > 0 && pops < MAX_POPS) {
            val s = ws.popBest()
            if (ws.bound[s] < floor - EPS) break
            pops++
            val node = ws.node[s]
            val pos = ws.pos[s].toInt()
            val cost = ws.cost[s]
            val editSpend = ws.editCost[s]
            val edits = ws.edits[s].toInt()
            val comp = ws.comp[s].toInt()
            val accents = ws.accents[s].toInt()

            if (pos == n) {
                if (walker.isWord(node)) {
                    val score = src.logWeight + ln1p(walker.frequency(node)) - cost
                    if (score > floor - EPS || results.size < k) {
                        emit(ws.materialize(s), score, editSpend, edits, comp, accents, src.tier, results)
                        if (results.size >= k) floor = kthBest(results, k, ws)
                    }
                }
                // Completion: descend at no cost on a clean prefix — the bound
                // (maxSubtree) steers toward the best words, exactly like
                // classic complete(). After an edit, each extra character is
                // charged like the old insert-at-end edit: an edited-then-
                // completed word is speculation stacked on speculation and
                // must not outrank the direct fix by raw frequency.
                val step = if (edits > 0) COST_COMPLETION_AFTER_EDIT else COMPLETION_STEP
                val count = walker.childrenInto(node, ws.children)
                for (i in 0 until count) {
                    val child = ws.children.nodes[i]
                    pushIfViable(
                        ws, src, walker, floor,
                        node = child, pos = n, cost = cost + step, editSpend = editSpend,
                        edits = edits, comp = comp + 1, accents = accents, parent = s,
                        viaLabel = ws.children.labels[i],
                    )
                }
                continue
            }

            val expected = typed[pos]
            // The letters this keystroke could have meant, on a keyboard that
            // puts several on a key; null on every ordinary board.
            val keySet = keys?.at(pos)
            // One read of the node's edges serves every branch below: the
            // accent step, the letters on an ambiguous key, and the edits.
            val count = walker.childrenInto(node, ws.children)
            if (keySet == null) {
                // Exact match of the next typed char. With touch evidence, an
                // off-center tap makes even the "match" slightly expensive —
                // which is exactly what lets the neighbouring key's word win.
                val matched = ws.children.find(expected, count)
                if (matched >= 0) {
                    pushIfViable(
                        ws, src, walker, floor,
                        node = matched, pos = pos + 1,
                        cost = cost + matchCost(touch, pos, expected),
                        editSpend = editSpend,
                        edits = edits, comp = comp, accents = accents, parent = s,
                        viaLabel = expected,
                    )
                }
                // The same key with its accent left off: `z` standing for `ż`
                // (#200). Not an edit, because nothing was mistyped. It still
                // has a price, so that at equal frequency the word spelled
                // exactly as typed leads its accented twin.
                for (i in 0 until count) {
                    val label = ws.children.labels[i]
                    if (!Accents.isAccentOf(label, expected)) continue
                    pushIfViable(
                        ws, src, walker, floor,
                        node = ws.children.nodes[i], pos = pos + 1,
                        cost = cost + matchCost(touch, pos, expected) + COST_ACCENT,
                        editSpend = editSpend,
                        edits = edits, comp = comp, accents = accents + 1, parent = s,
                        viaLabel = label,
                    )
                }
            } else {
                // Every letter on the pressed key matches, free and unedited.
                // Free is the honest price: the user pressed one key and all of
                // its letters are equally what they asked for, so nothing here
                // is a mistake to be charged for. What separates the readings
                // afterwards is the language model — frequency, the n-gram
                // context, the personal lexicon — which is exactly how a T9
                // phone ranked them, and why "good" beats "gone" beats "hood"
                // for one and the same run of keys.
                for (i in 0 until count) {
                    val label = ws.children.labels[i]
                    if (!keySet.contains(label)) continue
                    pushIfViable(
                        ws, src, walker, floor,
                        node = ws.children.nodes[i], pos = pos + 1,
                        cost = cost + matchCost(touch, pos, label),
                        editSpend = editSpend,
                        edits = edits, comp = comp, accents = accents, parent = s,
                        viaLabel = label,
                    )
                }
                // A letter on the key that the word list spells in several
                // characters (क़ as क + nukta): the same free match, walked
                // edge by edge, one keystroke for the lot.
                keys?.spellingsAt(pos)?.forEach { spelled ->
                    var at = node
                    var link = s
                    for (j in 0 until spelled.length - 1) {
                        at = walker.child(at, spelled[j])
                        if (at < 0) return@forEach
                        link = ws.pushRecord(
                            node = at, pos = pos, cost = cost,
                            editSpend = editSpend, edits = edits, comp = comp,
                            accents = accents, parent = link, viaLabel = spelled[j],
                        )
                    }
                    val last = walker.child(at, spelled[spelled.length - 1])
                    if (last >= 0) {
                        pushIfViable(
                            ws, src, walker, floor,
                            node = last, pos = pos + 1,
                            cost = cost + matchCost(touch, pos, spelled[0]),
                            editSpend = editSpend,
                            edits = edits, comp = comp, accents = accents, parent = link,
                            viaLabel = spelled[spelled.length - 1],
                        )
                    }
                }
            }

            if (edits < maxEdits && editSpend < MAX_EDIT_COST) {
                // Budget gates compare base edit costs; the second-edit
                // surcharge and touch/match adjustments are ranking signal,
                // not budget spend.
                val surcharge = (if (edits + 1 >= 2) SECOND_EDIT_SURCHARGE else 0.0) +
                    if (keys?.isAmbiguous == true) AMBIGUOUS_EDIT_SURCHARGE else 0.0
                // Deletion: the typed char was an extra keypress — skip it.
                // A char that doubles its neighbour ("helllo", key auto-repeat)
                // is the classic double-strike slip and costs far less than
                // deleting an arbitrary stray character.
                val delBase = if (
                    (pos + 1 < n && typed[pos + 1] == expected) ||
                    (pos > 0 && typed[pos - 1] == expected)
                ) {
                    COST_DELETE_DOUBLED
                } else {
                    COST_DELETION
                }
                val delCost = discounted(delBase, habits.deletion(expected))
                if (editSpend + delCost <= MAX_EDIT_COST) {
                    pushIfViable(
                        ws, src, walker, floor,
                        node = node, pos = pos + 1, cost = cost + delCost + surcharge,
                        editSpend = editSpend + delCost,
                        edits = edits + 1, comp = comp, accents = accents, parent = s,
                        viaLabel = BeamWorkspace.NO_LABEL,
                    )
                }
                // Substitution and insertion candidates come from the node's
                // actual children — any script the dictionary holds.
                for (i in 0 until count) {
                    val label = ws.children.labels[i]
                    val child = ws.children.nodes[i]
                    // A letter the pressed key carries was already taken as a
                    // match above. Charging it again as a substitution would
                    // enter the same word twice, once at an edit distance it
                    // never travelled — and the edited copy would be the one
                    // the "known word suppresses corrections" gate throws away.
                    val onKey = keySet != null && keySet.contains(label)
                    // Likewise a letter already taken as the typed one with its
                    // accent left off.
                    val accented = keySet == null && Accents.isAccentOf(label, expected)
                    if (label != expected && !onKey && !accented) {
                        val subCost = discounted(
                            substitutionCost(touch, pos, expected, label, proximity),
                            habits.substitution(expected, label),
                        )
                        if (editSpend + subCost <= MAX_EDIT_COST) {
                            pushIfViable(
                                ws, src, walker, floor,
                                node = child, pos = pos + 1, cost = cost + subCost + surcharge,
                                editSpend = editSpend + subCost,
                                edits = edits + 1, comp = comp, accents = accents, parent = s,
                                viaLabel = label,
                            )
                        }
                    }
                    // Insertion: the intended word has [label] here and the
                    // typed text missed it — consume the edge, hold position.
                    // A missed doubling ("aple" → "apple": the edge repeats
                    // the char just typed) is as forgivable as an adjacent
                    // slip; self-adjacency isn't in the proximity maps.
                    val adjacentToTyped = proximity.areAdjacent(expected, label) ||
                        (pos > 0 && (typed[pos - 1] == label ||
                            proximity.areAdjacent(typed[pos - 1], label)))
                    val insCost = discounted(
                        if (adjacentToTyped) COST_INSERT_ADJACENT else COST_INSERT_FAR,
                        habits.insertion(label),
                    )
                    if (editSpend + insCost <= MAX_EDIT_COST) {
                        pushIfViable(
                            ws, src, walker, floor,
                            node = child, pos = pos, cost = cost + insCost + surcharge,
                            editSpend = editSpend + insCost,
                            edits = edits + 1, comp = comp, accents = accents, parent = s,
                            viaLabel = label,
                        )
                    }
                }
                // Transposition of the next two typed chars. Skipped where
                // either keystroke is ambiguous: the characters in the buffer
                // there are anchors, not what the user typed, so swapping them
                // asks the trie about a pair of letters nobody chose. Swapping
                // the key *sets* is the meaningful operation and no word needs
                // it — a transposed pair on an ambiguous board is two keys the
                // decode already reads in both orders.
                //
                // Priced by the hands the two keys belong to. A transposition is
                // two keystrokes arriving out of order, and the hands are what
                // make that possible: one hand's fingers are sequenced by the
                // same muscle, while the two hands are only sequenced by
                // intention, so a fast typist's swaps are overwhelmingly the
                // cross-hand kind. Both stay well inside [MAX_EDIT_COST] — a
                // same-hand swap is still found, it just no longer outranks the
                // adjacent-key slip that explains the same buffer.
                val transposeCost = if (pos + 1 < n && proximity.sameHand(typed[pos], typed[pos + 1])) {
                    COST_TRANSPOSITION_SAME_HAND
                } else {
                    COST_TRANSPOSITION
                }
                if (pos + 1 < n && typed[pos] != typed[pos + 1] &&
                    keySet == null && keys?.at(pos + 1) == null &&
                    editSpend + transposeCost <= MAX_EDIT_COST
                ) {
                    val first = ws.children.find(typed[pos + 1], count)
                    if (first >= 0) {
                        val second = walker.child(first, typed[pos])
                        if (second >= 0) {
                            // Intermediate record links the first edge's label
                            // into the parent chain; it never enters the heap.
                            val link = ws.pushRecord(
                                node = first, pos = pos + 1, cost = cost,
                                editSpend = editSpend, edits = edits, comp = comp,
                                accents = accents, parent = s, viaLabel = typed[pos + 1],
                            )
                            pushIfViable(
                                ws, src, walker, floor,
                                node = second, pos = pos + 2,
                                cost = cost + transposeCost + surcharge,
                                editSpend = editSpend + transposeCost,
                                edits = edits + 1, comp = comp, accents = accents, parent = link,
                                viaLabel = typed[pos],
                            )
                        }
                    }
                }
            }
        }
        return floor
    }

    @Suppress("LongParameterList")
    private fun pushIfViable(
        ws: BeamWorkspace,
        src: WalkSource,
        walker: TrieWalker,
        floor: Double,
        node: Int,
        pos: Int,
        cost: Double,
        editSpend: Double,
        edits: Int,
        comp: Int,
        accents: Int,
        parent: Int,
        viaLabel: Char,
    ) {
        val bound = src.logWeight + ln1p(walker.maxSubtree(node)) - cost
        if (bound < floor - EPS) return
        ws.pushState(node, pos, cost, editSpend, edits, comp, accents, parent, viaLabel, bound)
    }

    /**
     * Rank cost of consuming [expected] as itself. Zero without touch data;
     * with a tap position, the likelihood gap to the tap's best key (capped).
     */
    private fun matchCost(touch: TouchScoring?, pos: Int, expected: Char): Double {
        val p = touch?.points?.getOrNull(pos) ?: return 0.0
        if (!touch.model.knows(expected)) return 0.0
        val bestScore = touch.bestLogLikelihoods.getOrElse(pos) { Double.NEGATIVE_INFINITY }
        if (bestScore == Double.NEGATIVE_INFINITY) return 0.0
        val gap = bestScore - touch.model.logLikelihood(p, expected)
        return gap.coerceIn(0.0, MATCH_CAP)
    }

    /**
     * Cost of assuming the user meant [label] where they typed [expected].
     * With a tap position, substituting toward the key the finger actually
     * landed near is nearly free; without one, the discrete adjacency weights.
     */
    private fun substitutionCost(
        touch: TouchScoring?,
        pos: Int,
        expected: Char,
        label: Char,
        proximity: KeyProximity,
    ): Double {
        val p = touch?.points?.getOrNull(pos)
        if (p == null || !touch.model.knows(expected) || !touch.model.knows(label)) {
            return if (proximity.areAdjacent(expected, label)) COST_SUB_ADJACENT else COST_SUB_FAR
        }
        val gap = touch.model.logLikelihood(p, expected) - touch.model.logLikelihood(p, label)
        return COST_SUB_ADJACENT + gap.coerceIn(0.0, COST_SUB_FAR - COST_SUB_ADJACENT)
    }

    /**
     * [base] with a learned habit's [shrink] taken off, never below the
     * cheapest edit tier. A slip this user makes every day is priced like
     * the adjacent slip it is for them; the floor keeps it from becoming
     * free, and an edit already at the floor is left exactly where it is.
     */
    private fun discounted(base: Double, shrink: Double): Double =
        if (shrink <= 0.0) base else maxOf(HABIT_FLOOR, base * (1.0 - shrink))

    private fun emit(
        word: String,
        score: Double,
        editCost: Double,
        edits: Int,
        completedChars: Int,
        accents: Int,
        tier: Tier,
        results: HashMap<String, ScoredCandidate>,
    ) {
        val existing = results[word]
        val dictScore = maxOf(
            existing?.dictScore ?: Double.NEGATIVE_INFINITY,
            if (tier == Tier.DICTIONARY) score else Double.NEGATIVE_INFINITY,
        )
        val userScore = maxOf(
            existing?.userScore ?: Double.NEGATIVE_INFINITY,
            if (tier == Tier.USER) score else Double.NEGATIVE_INFINITY,
        )
        results[word] = if (existing == null || score > existing.score) {
            ScoredCandidate(
                word, score, editCost, edits, completedChars, tier, dictScore, userScore, accents,
            )
        } else {
            ScoredCandidate(
                word, existing.score, existing.editCost, existing.edits,
                existing.completedChars, existing.tier, dictScore, userScore, existing.accents,
            )
        }
    }

    private fun kthBest(results: HashMap<String, ScoredCandidate>, k: Int, ws: BeamWorkspace): Double {
        val count = results.size
        if (count < k) return Double.NEGATIVE_INFINITY
        if (ws.scoreBuf.size < count) ws.scoreBuf = DoubleArray(maxOf(count, ws.scoreBuf.size * 2))
        val buf = ws.scoreBuf
        var i = 0
        for (c in results.values) buf[i++] = c.score
        buf.sort(0, count)
        return buf[count - k]
    }

    companion object {
        /** -ln of the legacy multiplicative edit weights (SuggestionEngine),
         * kept exact so ranking is precisely isomorphic to the edits-1 engine. */
        /** Two keys swapped across the hands: the ordinary racing-hands slip. */
        val COST_TRANSPOSITION = -ln(0.9)

        /**
         * The same swap within one hand, which is a rarer thing to do and a
         * worse explanation for the buffer. Priced with the adjacent insertion
         * and the deletion rather than with its cross-hand twin, so it loses to
         * a one-key slip of comparable frequency and wins over a far
         * substitution — which is where the evidence puts it.
         */
        val COST_TRANSPOSITION_SAME_HAND = -ln(0.7)
        val COST_SUB_ADJACENT = -ln(0.9)
        val COST_DELETION = -ln(0.7)

        /** Deleting a char that doubles its neighbour ("helllo", hold-repeat
         * noise): the cheapest edit tier, alongside transposition — a
         * double-strike explains itself. */
        val COST_DELETE_DOUBLED = -ln(0.9)
        val COST_INSERT_ADJACENT = -ln(0.7)
        val COST_INSERT_FAR = -ln(0.25)
        val COST_SUB_FAR = -ln(0.2)

        /**
         * A letter typed without its accent ([Accents]). Priced with the
         * adjacent slip, which is the cheapest edit, but outside the edit
         * budget: `zolw` for `żółw` leaves off three accents and is still one
         * word, not three mistakes.
         */
        val COST_ACCENT = -ln(0.9)
        const val COMPLETION_STEP = 0.0

        /** Per-character completion cost once the path holds an edit. As
         * expensive as a far insertion: an edited-then-completed word needs a
         * ~4x frequency advantage per extra character to outrank the direct
         * fix (otherwise "skills" buries "skill" for typed "skiml", and
         * deleted-prefix floods like "bwl" -> "bl" -> blue/black/blood push
         * the real fix out of the result set). */
        val COST_COMPLETION_AFTER_EDIT = -ln(0.25)

        /** Two far substitutions can never survive; two adjacent slips can.
         * Applies to the base edit costs, before [SECOND_EDIT_SURCHARGE]. */
        const val MAX_EDIT_COST = 2.0

        /**
         * Extra cost on the second edit: the legacy engine only ever reached
         * one edit, so a two-edit word must rank below any one-edit fix of
         * comparable frequency — it exists to catch typos nothing else
         * explains, not to outbid them. (Cheapest 2-edit total: ~2.21 nats,
         * above the most expensive single edit at 1.609.)
         */
        const val SECOND_EDIT_SURCHARGE = 2.0

        /** Autocorrect wants a deeper ranked list than the strip shows. */
        const val AUTOCORRECT_K = 8

        /** Cap on the rank cost an off-center tap adds to an exact match. */
        const val MATCH_CAP = 1.0

        /** No learned habit prices an edit under the adjacent-slip tier. */
        val HABIT_FLOOR = COST_SUB_ADJACENT

        /** Runaway-state backstop; floor pruning ends healthy walks long before. */
        const val MAX_POPS = 4096

        private const val EPS = 1e-9

        fun defaultMaxEdits(typedLength: Int): Int = if (typedLength >= 5) 2 else 1

        /**
         * Edits allowed on top of key ambiguity (see [KeySets]).
         *
         * One, not two, and the reason is the frontier rather than taste: a
         * three-letter key already branches every position, and a second edit
         * branches each of those again — a five-key word reaches [MAX_POPS]
         * while the walk is still exploring nonsense, and the backstop then
         * cuts it off before the reading the user meant is ever emitted. One
         * edit still catches the ordinary slip of hitting the key next door,
         * which on a keypad of eight big keys is the only slip there is.
         */
        const val AMBIGUOUS_MAX_EDITS = 1

        /**
         * Extra cost on any edit taken while decoding ambiguous keystrokes.
         *
         * The ordinary edit prices assume an edit competes against a handful of
         * readings — on a 1:1 board, the typed letters and nothing else. On a
         * board with three letters to a key it competes against every reading of
         * every key, and at those prices it wins far too often: four keys of
         * "home" would rather drop the first keystroke and answer "one", which
         * is a commoner word than "home" by more than a deletion costs.
         *
         * That is the wrong answer for a reason worth naming. A reading that
         * spends every keystroke exactly as it was pressed explains the input
         * completely; one that throws a keystroke away explains it by calling
         * the user wrong. The first should lose to the second only on
         * overwhelming evidence — which is what this is: about ln(400), so an
         * edited reading has to be some four hundred times commoner than the
         * best honest one before it leads.
         *
         * A hard rule ("never rank an edited reading above an unedited one")
         * was the alternative and does not survive the trip: the strip's own
         * context boosts re-sort by score afterwards, so an ordering imposed
         * here would simply be undone. Only a price travels.
         */
        const val AMBIGUOUS_EDIT_SURCHARGE = 6.0

        private fun ln1p(v: Int): Double = ln(1.0 + v)
    }
}

/**
 * Pooled parallel-array state store + index heap for [FuzzyBeamSearch].
 * One instance per thread (the engine keeps them in a ThreadLocal): the
 * search allocates nothing per keystroke beyond emitted words.
 */
class BeamWorkspace(initialCapacity: Int = 256) {

    var node = IntArray(initialCapacity); private set
    var pos = ShortArray(initialCapacity); private set
    var cost = DoubleArray(initialCapacity); private set
    var editCost = DoubleArray(initialCapacity); private set
    var edits = ByteArray(initialCapacity); private set
    var comp = ByteArray(initialCapacity); private set
    var accents = ByteArray(initialCapacity); private set
    var parent = IntArray(initialCapacity); private set
    var viaLabel = CharArray(initialCapacity); private set
    var bound = DoubleArray(initialCapacity); private set
    private var size = 0

    private var heap = IntArray(initialCapacity)
    var heapSize = 0; private set

    val children = ChildBuffer()
    var scoreBuf = DoubleArray(64)
    private val sb = StringBuilder(24)

    fun reset() {
        size = 0
        heapSize = 0
    }

    /** Appends a state record without scheduling it for expansion. */
    @Suppress("LongParameterList")
    fun pushRecord(
        node: Int,
        pos: Int,
        cost: Double,
        editSpend: Double,
        edits: Int,
        comp: Int,
        accents: Int,
        parent: Int,
        viaLabel: Char,
    ): Int {
        ensure(size + 1)
        val id = size++
        this.node[id] = node
        this.pos[id] = pos.toShort()
        this.cost[id] = cost
        this.editCost[id] = editSpend
        this.edits[id] = edits.toByte()
        this.comp[id] = comp.toByte()
        this.accents[id] = accents.toByte()
        this.parent[id] = parent
        this.viaLabel[id] = viaLabel
        this.bound[id] = 0.0
        return id
    }

    @Suppress("LongParameterList")
    fun pushState(
        node: Int,
        pos: Int,
        cost: Double,
        editSpend: Double,
        edits: Int,
        comp: Int,
        accents: Int,
        parent: Int,
        viaLabel: Char,
        bound: Double,
    ): Int {
        val id = pushRecord(node, pos, cost, editSpend, edits, comp, accents, parent, viaLabel)
        this.bound[id] = bound
        heapPush(id)
        return id
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
     * Reversed one UTF-16 unit at a time rather than with
     * `StringBuilder.reverse()`, which keeps any high-low surrogate pair it
     * finds together: the leaf-first chain of a word outside the BMP (Osage,
     * Adlam, Warang Citi) is a run of *low-high* units that it reads as the
     * pairs of the neighbouring letters, so every such suggestion came back
     * with its letters' halves swapped and a stray surrogate at each end.
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
        node = node.copyOf(capacity)
        pos = pos.copyOf(capacity)
        cost = cost.copyOf(capacity)
        editCost = editCost.copyOf(capacity)
        edits = edits.copyOf(capacity)
        comp = comp.copyOf(capacity)
        accents = accents.copyOf(capacity)
        parent = parent.copyOf(capacity)
        viaLabel = viaLabel.copyOf(capacity)
        bound = bound.copyOf(capacity)
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
        /** Sentinel for "this state consumed no trie edge" (deletion links).
         * NUL can never be a dictionary edge label. */
        val NO_LABEL: Char = 0.toChar()
    }
}
