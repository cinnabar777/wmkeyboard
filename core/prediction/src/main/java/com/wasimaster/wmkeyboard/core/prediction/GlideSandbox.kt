package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which words a swipe is allowed to answer with.
 *
 * A swipe is a far weaker signal than a typed prefix — it says which keys the
 * finger went near and roughly in what order — so the size of the vocabulary
 * it chooses from decides its accuracy in a way that tap typing never feels.
 * `GlideBeam.Tuning.vocabularyRank` already answers that on the frequency
 * axis, by taking the rare tail out of the search. This answers it on the
 * other axis: whose words they are.
 *
 * The observation behind it is that most people write with a few thousand
 * words, and once the keyboard has learned them, the dictionary is mostly
 * competition. A stroke that reads as `van` when the user has never written
 * `van` and writes `can` weekly is the decoder being asked a question it had
 * no business being asked.
 *
 * Three policies, from the one that changes nothing to the one that puts the
 * dictionary away entirely. [GlideSandboxLadder] climbs between them.
 */
enum class GlideSandboxPolicy {
    /**
     * Every source at its own weight — what the decoder has always done, and
     * still the default. Nothing below is on unless the user asks for it.
     */
    OFF,

    /**
     * Both decodes run. The learned words' answer is taken only when it
     * explains the stroke at least as well as the best word anywhere does —
     * see [GlideSandboxLadder.SANDBOX_MARGIN] — and the rung records, for
     * every stroke, whether the learned words could have answered it at all.
     *
     * **This rung is instrumentation, not accuracy.** That is worth being
     * blunt about, because the obvious reading of it — learned words first,
     * dictionary as a safety net — was measured and does not work.
     * `GlideSandboxEvalTest` sweeps every gate available on the decoder's own
     * shape cost and none of them separates "the sandbox holds the word the
     * user meant" from "the sandbox holds a different word that happens to fit
     * the stroke". A sandbox missing the intended word does not return
     * nothing; it returns its own best guess, tidily. The best gate measured
     * is worth +0.1 points of top-1 over [OFF], which is noise.
     *
     * What the rung is for is the question [LEARNED_ONLY] turns on, which no
     * amount of geometry can answer and only the user's own typing can: how
     * often do they swipe a word their keyboard has not learned? Running both
     * decodes and comparing answers measures exactly that, at no cost to
     * accuracy, and after [GlideSandboxLadder.WINDOW] strokes the ladder can
     * offer the next rung on evidence instead of on a guess.
     */
    PREFER_LEARNED,

    /**
     * The learned words are the whole vocabulary. The dictionary never runs.
     *
     * This is the policy the whole idea is for, and it is a large win on the
     * words it holds. Measured on a synthetic user whose vocabulary is a
     * 2,000-word frequency-weighted *sample* of the shipped list — sampled, so
     * it has the holes a person's has — and whose lexicon is that vocabulary,
     * over typical and sloppy strokes:
     *
     *     on words they write     OFF .9188    LEARNED_ONLY .9750
     *     on words they have not  OFF .9125    LEARNED_ONLY .0000
     *
     * Five and a half points of top-1 on everything the user actually writes,
     * which is more than the trie lattice itself was worth. It is bought by
     * competitors being absent rather than by any better reading of the
     * stroke, which is why weighting the user tier harder does not buy it:
     * that sweep trades the same points back on the other column, all the way
     * up (`GlideSandboxSweepTest.userWeightSweep`).
     *
     * The cost is total and not gradual — a word the lexicon does not hold is
     * unreachable, not merely unlikely — so the policy pays only while the
     * user's rate of genuinely new words stays under
     * [GlideSandboxLadder.ONLY_AT_NEW_WORD_RATE]. That is what makes it a
     * setting rather than a default, and what [PREFER_LEARNED] exists to
     * measure.
     *
     * The escape hatch is the deep retry already on the undo path —
     * backspacing the word a stroke gave re-decodes it against every word
     * there is — which is the "manual search" this policy is designed around
     * rather than a consolation for it.
     */
    LEARNED_ONLY,
}

/**
 * How mature this user's personal lexicon is, and how far up the sandbox
 * ladder they have agreed to climb.
 *
 * The automatic policy is not a schedule. Both rungs are earned against
 * something measured:
 *
 *  - **to [GlideSandboxPolicy.PREFER_LEARNED]** — the personal lexicon holds
 *    at least [PREFER_AT_WORDS] words. That is the only condition it needs,
 *    because the policy falls back: a lexicon too thin to answer a stroke
 *    simply hands it to the dictionary the way [GlideSandboxPolicy.OFF]
 *    would.
 *  - **to [GlideSandboxPolicy.LEARNED_ONLY]** — over the last
 *    [WINDOW] strokes decoded under `PREFER_LEARNED`, the lexicon answered
 *    without falling back at least [ONLY_AT_HIT_RATE] of the time. Dropping
 *    the dictionary is only safe once the sandbox is demonstrably answering
 *    on its own, and the previous rung is what measures that — which is why
 *    the ladder cannot skip it.
 *
 * Neither rung applies itself. [pending] names a rung that has been earned
 * and not yet answered; the keyboard offers it, and [accept] or [decline]
 * records what the user said. A declined rung is never offered again, since
 * a policy that keeps asking is worse than one that never does.
 *
 * Storage follows the personal-store contract used by [GlideOutcomes] and
 * [PendingLearn]: nullable file (direct boot → memory only), dirty-flag save
 * on dismissal, [reload] for external edits. Nothing here is text — a rung, a
 * count and a run of booleans.
 */
class GlideSandboxLadder(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = VERSION,
        /** The rung the user has accepted; never above what was offered. */
        val accepted: String = GlideSandboxPolicy.OFF.name,
        /** Rungs the user turned down, and which are therefore never re-offered. */
        val declined: Set<String> = emptySet(),
        /** Strokes in the current window, oldest first; true where the sandbox answered. */
        val window: List<Boolean> = emptyList(),
    )

    private var accepted = GlideSandboxPolicy.OFF
    private val declined = HashSet<GlideSandboxPolicy>()
    private val window = ArrayDeque<Boolean>()
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /**
     * A rung that has been earned and not yet answered, or null.
     *
     * Asked on the keyboard's thread after a stroke commits, which is the
     * moment both inputs are freshest and the moment a chip has somewhere to
     * go.
     */
    @Synchronized
    fun pending(lexiconWords: Int): GlideSandboxPolicy? = when (accepted) {
        GlideSandboxPolicy.OFF ->
            GlideSandboxPolicy.PREFER_LEARNED.takeIf {
                it !in declined && lexiconWords >= PREFER_AT_WORDS
            }
        GlideSandboxPolicy.PREFER_LEARNED ->
            GlideSandboxPolicy.LEARNED_ONLY.takeIf {
                it !in declined && window.size >= WINDOW && hitRate() >= ONLY_AT_HIT_RATE
            }
        GlideSandboxPolicy.LEARNED_ONLY -> null
    }

    /**
     * One stroke decoded under [GlideSandboxPolicy.PREFER_LEARNED]:
     * [answered] is whether the learned words produced the word that was
     * committed — that is, whether [GlideSandboxPolicy.LEARNED_ONLY] would
     * have served this stroke too.
     *
     * That is the measurement the second rung turns on, and it is why the
     * first rung exists at all. Only recorded there: under
     * [GlideSandboxPolicy.OFF] there is no sandbox decode to have an opinion,
     * and under [GlideSandboxPolicy.LEARNED_ONLY] every stroke is answered by
     * the sandbox by definition, so a window collected on that rung would read
     * as a perfect score however badly the policy was serving the user.
     */
    @Synchronized
    fun observe(answered: Boolean) {
        if (accepted != GlideSandboxPolicy.PREFER_LEARNED) return
        window.addLast(answered)
        while (window.size > WINDOW) window.removeFirst()
        dirty = true
    }

    /** The user took the offered rung. */
    @Synchronized
    fun accept(policy: GlideSandboxPolicy) {
        if (policy == accepted) return
        accepted = policy
        // The window measured the rung just left; it says nothing about the
        // one being entered, and leaving it in place would let a single
        // stroke on the new rung tip a stale average over the next threshold.
        window.clear()
        dirty = true
    }

    /** The user turned the offered rung down, and is not asked about it again. */
    @Synchronized
    fun decline(policy: GlideSandboxPolicy) {
        if (declined.add(policy)) dirty = true
    }

    /** The rung the user is on, for the settings screen to show. */
    @Synchronized
    fun accepted(): GlideSandboxPolicy = accepted

    /**
     * Puts the ladder back at the bottom with nothing declined — what the
     * settings screen's reset does, and what switching the automatic policy
     * off and on again should mean.
     */
    @Synchronized
    fun reset() {
        accepted = GlideSandboxPolicy.OFF
        declined.clear()
        window.clear()
        dirty = true
    }

    @Synchronized
    fun isEmpty(): Boolean =
        accepted == GlideSandboxPolicy.OFF && declined.isEmpty() && window.isEmpty()

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                accepted = accepted.name,
                declined = declined.map { it.name }.toSet(),
                window = window.toList(),
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    /** Re-reads the file after the settings app deleted or replaced it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        accepted = GlideSandboxPolicy.OFF
        declined.clear()
        window.clear()
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        accepted = GlideSandboxPolicy.OFF
        declined.clear()
        window.clear()
        // The delete is the write; stay dirty only if it failed, so the next
        // save overwrites the stale file with the empty snapshot.
        dirty = snapshotFile?.delete() == false
    }

    /** Share of the window the sandbox answered on its own. */
    private fun hitRate(): Double =
        if (window.isEmpty()) 0.0 else window.count { it } / window.size.toDouble()

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        val snapshot = runCatching {
            json.decodeFromString<Snapshot>(file.readText())
        }.getOrNull() ?: return
        if (snapshot.version != VERSION) return
        accepted = runCatching { GlideSandboxPolicy.valueOf(snapshot.accepted) }
            .getOrDefault(GlideSandboxPolicy.OFF)
        snapshot.declined.forEach { name ->
            runCatching { GlideSandboxPolicy.valueOf(name) }.getOrNull()?.let { declined.add(it) }
        }
        snapshot.window.takeLast(WINDOW).forEach { window.addLast(it) }
    }

    companion object {
        private const val VERSION = 1

        /**
         * How much worse than the best word anywhere the learned words' answer
         * may fit, and still be the one taken under
         * [GlideSandboxPolicy.PREFER_LEARNED], in
         * `GlideBeam.Candidate.shapeCost` units.
         *
         * Zero, meaning the learned word is taken when it explains the stroke
         * at least as well — never when it explains it worse. Swept in
         * `GlideSandboxEvalTest`; every positive margin loses more on words
         * outside the lexicon than it gains on words inside it, and the whole
         * curve is flat enough that this rung is worth no accuracy either way.
         * It is here as a named zero rather than an inlined one because it is
         * the seam a future signal — one that can actually tell the two cases
         * apart — would move.
         *
         * Cost rather than score, because the two decodes' scores are not
         * comparable: the user tier carries a ln(500) weight the dictionary
         * tier does not, so every learned word would win on score alone. Cost
         * is pure geometry and means the same thing in both.
         */
        const val SANDBOX_MARGIN = 0.0

        /**
         * Words in the personal lexicon before the first rung is offered.
         *
         * The top 1,000 words of English carry most of what anyone writes, so
         * a lexicon this size is already a plausible vocabulary rather than a
         * handful of leftovers — and the rung it unlocks falls back anyway, so
         * being early costs accuracy nothing.
         */
        const val PREFER_AT_WORDS = 800

        /** Strokes the hit-rate window holds before the second rung is judged. */
        const val WINDOW = 200

        /**
         * The rate of swipes for words the lexicon does not hold at which
         * [GlideSandboxPolicy.LEARNED_ONLY] stops paying for itself.
         *
         * Not a taste setting — it falls out of the two measured columns on
         * [GlideSandboxPolicy.LEARNED_ONLY]. Dropping the dictionary is worth
         * `(ONLY_in - OFF_in)` on every word the lexicon holds and costs
         * `OFF_out` on every word it does not, so it wins exactly while
         *
         *     rate < (ONLY_in - OFF_in) / (OFF_out + ONLY_in - OFF_in)
         *
         * which on the settled-user model is .0581
         * (`GlideSandboxSweepTest.settledUserSweep` prints it). Rounded down,
         * because the model's lexicon is a clean sample of a word list and a
         * real one carries names, jargon and settled misspellings that make the
         * true rate worse rather than better.
         */
        const val ONLY_AT_NEW_WORD_RATE = 0.05

        /**
         * Share of a full window the sandbox must have answered before
         * dropping the dictionary is offered — the other side of
         * [ONLY_AT_NEW_WORD_RATE].
         */
        const val ONLY_AT_HIT_RATE = 1.0 - ONLY_AT_NEW_WORD_RATE
    }
}
