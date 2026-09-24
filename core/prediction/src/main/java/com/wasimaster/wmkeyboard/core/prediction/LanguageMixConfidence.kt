package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

/**
 * Learns how much each language the user actually leans on when they mix
 * languages, and turns that into a per-language confidence multiplier for the
 * secondary-dictionary tier of [SuggestionEngine].
 *
 * The keyboard used to weight every secondary language identically, so a
 * bilingual typist who reaches for Spanish constantly and German once a month
 * got both crammed into the strip with equal force. Here each committed word is
 * attributed to the language whose dictionary owns it; the language you use most
 * in the mix earns the most trust, and one you barely touch is damped so its
 * words don't crowd the ones you want.
 *
 * Counts decay geometrically on every commit, so the confidence tracks *recent*
 * habit rather than the whole history — switch your daily second language and
 * the weighting follows within a few hundred words. Persisted as JSON in
 * app-private storage; all of it stays on device.
 */
class LanguageMixConfidence(private val storageFile: File? = null) {

    /** Decayed commit tally per language id (BCP-47, e.g. "es", "en"). */
    private val usage = HashMap<String, Double>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether anything has been recorded since the file last matched memory.
     * The same flag [com.wasimaster.wmkeyboard.core.input.composer.CjkUserHistory]
     * keeps, and for the same reason: [save] runs on the main thread every time
     * the keyboard is dismissed, and a dismissal with nothing typed has nothing
     * to write.
     */
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    init {
        load()
    }

    /**
     * Attribute one committed word to [langId]. Every language decays a touch
     * first so the tally leans on recent use, then the used language is
     * reinforced. Blank ids (no active language) are ignored.
     */
    @Synchronized
    fun record(langId: String) {
        if (langId.isEmpty()) return
        val iterator = usage.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = entry.value * DECAY
            // Drop languages that have faded to noise so the map can't grow
            // without bound as the user drifts across languages over months.
            if (decayed < FLOOR) iterator.remove() else entry.setValue(decayed)
        }
        usage.merge(langId, 1.0, Double::plus)
        dirty = true
    }

    /**
     * Confidence multiplier for [langId], to scale its secondary-dictionary
     * weight. [NEUTRAL] (1.0) until any usage is recorded, so an untrained
     * keyboard behaves exactly as the old flat weighting did. Once there is
     * data the busiest language approaches [MAX_CONFIDENCE] and a neglected one
     * bottoms out at [MIN_CONFIDENCE], on a square-root curve so moderate use
     * isn't punished as harshly as the raw share would.
     */
    @Synchronized
    fun confidenceFor(langId: String): Double {
        val max = usage.values.maxOrNull() ?: return NEUTRAL
        if (max <= 0.0) return NEUTRAL
        val share = (usage[langId] ?: 0.0) / max
        return MIN_CONFIDENCE + (MAX_CONFIDENCE - MIN_CONFIDENCE) * sqrt(share)
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(usage.toMap())
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

    /** Wipe all learned mixing habit (mirrors the personal-data reset). */
    @Synchronized
    fun clear() {
        usage.clear()
        // The delete is the write; a later save must not recreate the file.
        // A delete that FAILED leaves the wiped tally on disk, so stay dirty
        // and let the next save overwrite it with the empty snapshot.
        dirty = snapshotFile?.delete() == false
    }

    /**
     * Re-reads the file, dropping the in-memory tally. The settings app can
     * delete this from the Storage screen while the keyboard is alive, and the
     * next [save] would put it straight back.
     */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        usage.clear()
        load()
        // Memory matches the file again — saving would put back what the
        // Storage screen just deleted, which is what this reload exists to stop.
        dirty = false
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            usage.putAll(json.decodeFromString<Snapshot>(file.readText()).usage)
        }
    }

    @Serializable
    private data class Snapshot(val usage: Map<String, Double> = emptyMap())

    companion object {
        /** Multiplier before any usage exists — preserves the old flat weight. */
        const val NEUTRAL = 1.0

        /** A language rarely reached for in the mix is damped to this fraction. */
        const val MIN_CONFIDENCE = 0.5

        /** The most-used language in the mix is boosted up to this multiple. */
        const val MAX_CONFIDENCE = 2.0

        /**
         * Per-commit decay. ~0.999 keeps roughly the last thousand words in
         * play, so the weighting reflects a current habit while still forgetting
         * a language the user has moved on from.
         */
        private const val DECAY = 0.999

        /** Below this a language is pruned from the tally. */
        private const val FLOOR = 0.01
    }
}
