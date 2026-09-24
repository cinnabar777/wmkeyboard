package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

/**
 * Per-word rank adjustments the user made from the word card (#99): a step
 * count per word, negative to sink it and positive to lift it, that the
 * engine turns into a log-score shift ([SuggestionEngine.rankOffsets]).
 *
 * Its own store rather than a tweak to the personal lexicon's counts because
 * it answers a different question. The lexicon says how often a word was
 * typed, and only knows words the user typed; this says where the user wants
 * a word to sit, and can say it about a word from a downloaded list they
 * never typed at all — including "lower", which no count can express.
 *
 * Persisted as JSON in the learning directory beside the lexicon, keyed
 * through [WordKey] like every other word store. [snapshot] is what the
 * engine reads: a published immutable map, swapped whole on every change,
 * so the suggestion coroutine never takes this lock.
 */
class WordRanks(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(val offsets: Map<String, Int> = emptyMap())

    private val offsets = HashMap<String, Int>()
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    @Volatile
    private var published: Map<String, Int> = emptyMap()

    init {
        load()
    }

    /** The steps stored for [word], 0 when it has none. */
    @Synchronized
    fun offsetOf(word: String): Int = offsets[WordKey.of(word)] ?: 0

    /**
     * Sets [word]'s adjustment, clamped to [MIN_STEPS]..[MAX_STEPS]; 0 removes
     * the entry, since "no adjustment" is the absence of one. Returns false
     * for a blank word. Past [MAX_WORDS] the smallest adjustment makes room:
     * the card is a hand-driven surface, so the cap is a safety net rather
     * than a ceiling anyone reaches.
     */
    @Synchronized
    fun set(word: String, steps: Int): Boolean {
        val key = WordKey.of(word.trim())
        if (key.isEmpty() || key.length > MAX_WORD_LENGTH) return false
        val clamped = steps.coerceIn(MIN_STEPS, MAX_STEPS)
        if (clamped == 0) {
            if (offsets.remove(key) == null) return true
        } else {
            offsets[key] = clamped
            if (offsets.size > MAX_WORDS) {
                offsets.entries.minByOrNull { abs(it.value) }?.let { offsets.remove(it.key) }
            }
        }
        publish()
        dirty = true
        return true
    }

    /** Drops [word]'s adjustment, if any. */
    fun remove(word: String) {
        set(word, 0)
    }

    /** Lock-free view for the engine; replaced whole on every change. */
    fun snapshot(): Map<String, Int> = published

    /** Every adjustment, strongest first, for the personal dictionary screen. */
    @Synchronized
    fun all(): List<Pair<String, Int>> = offsets.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { abs(it.value) }.thenBy { it.key })
        .map { it.key to it.value }

    @Synchronized
    fun isEmpty(): Boolean = offsets.isEmpty()

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(offsets.toMap())
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

    /** Re-reads the file after another process (the settings app) edited it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        offsets.clear()
        load()
        publish()
        dirty = false
    }

    @Synchronized
    fun clear() {
        offsets.clear()
        publish()
        // The delete is the write; stay dirty only if it failed, so the next
        // save overwrites the stale file with the empty snapshot.
        dirty = snapshotFile?.delete() == false
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            for ((key, steps) in snapshot.offsets) {
                val clamped = steps.coerceIn(MIN_STEPS, MAX_STEPS)
                if (key.isNotEmpty() && clamped != 0) offsets[WordKey.of(key)] = clamped
            }
        }
        publish()
    }

    private fun publish() {
        published = if (offsets.isEmpty()) emptyMap() else HashMap(offsets)
    }

    companion object {
        /** Furthest a word can be pushed down. */
        const val MIN_STEPS = -10
        /** Furthest a word can be pushed up. */
        const val MAX_STEPS = 10
        private const val MAX_WORDS = 2_000
        private const val MAX_WORD_LENGTH = UserLexicon.MAX_WORD_LENGTH
    }
}
