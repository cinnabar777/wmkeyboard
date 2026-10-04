package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The waiting room in front of the [UserLexicon]: words the user has typed
 * that no dictionary knows, counted but not yet learned.
 *
 * A word only reaches the personal lexicon after it has been *settled* the
 * required number of times (see [LearningBuffer] for what settling means).
 * Until then it is invisible to everything — it is never suggested, never
 * shields itself from autocorrect, and never shows up in the personal
 * dictionary screen. That is the whole point: the old behaviour learned a
 * misspelling the first time it was committed, which both filled the personal
 * dictionary with rubbish and permanently exempted the typo from the
 * autocorrect that would have fixed it.
 *
 * Deliberately its own file (`learning/pending_learn.json`) rather than part
 * of the lexicon snapshot: the settings app rewrites the lexicon wholesale
 * when the user edits their personal dictionary, and half-earned sightings
 * must survive that.
 *
 * Storage follows the personal-store contract used by [UserLexicon] and
 * [CorrectionStats]: nullable file (direct boot → memory only), dirty-flag
 * save on dismissal, [reload] for external edits.
 */
class PendingLearn(private val storageFile: File?) {

    @Serializable
    private data class Sighting(val count: Int, val gen: Long, val lang: String = "")

    @Serializable
    private data class Snapshot(
        val words: Map<String, Sighting> = emptyMap(),
        /** Words the user answered "no" to on the add-word chip. */
        val declined: Set<String> = emptySet(),
        val generation: Long = 0L,
    )

    private val words = HashMap<String, Sighting>()
    private val declined = HashSet<String>()
    private var generation = 0L
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /**
     * Records one settled use of [word] and answers how many it now has.
     *
     * [weight] grades the evidence. An ordinary settled commit is worth one;
     * undoing an autocorrect back to the typed word is worth more, because
     * the user reached for backspace to say the keyboard was wrong about it.
     *
     * Returns 0 for a word that is not a learning candidate at all (too short,
     * too long, or already declined), so callers can treat 0 as "never
     * promote".
     */
    @Synchronized
    fun sight(word: String, langId: String = "", weight: Int = 1): Int {
        val key = WordKey.of(word)
        if (key.length < 2 || key.length > MAX_WORD_LENGTH || weight <= 0) return 0
        // The waiting room takes what the lexicon would (#185): a token with
        // a symbol glued on is not a candidate however often it is seen.
        if (!WordContext.isLearnableWord(key)) return 0
        if (key in declined) return 0
        val existing = words[key]
        val count = ((existing?.count ?: 0) + weight).coerceAtMost(MAX_COUNT)
        words[key] = Sighting(count, generation, langId.ifBlank { existing?.lang.orEmpty() })
        dirty = true
        return count
    }

    /** Settled sightings recorded for [word], 0 when it has none. */
    @Synchronized
    fun sightings(word: String): Int = words[WordKey.of(word)]?.count ?: 0

    /** Language the word was last sighted under, blank when untagged. */
    @Synchronized
    fun languageOf(word: String): String = words[WordKey.of(word)]?.lang.orEmpty()

    /**
     * Takes [word] out of the waiting room. Called when it graduates into the
     * lexicon, and when the user deletes it from the personal dictionary — a
     * word they threw away must not walk back in on its old sightings.
     */
    @Synchronized
    fun forget(word: String) {
        if (words.remove(WordKey.of(word)) != null) dirty = true
    }

    /**
     * The user said no on the add-word chip: stop counting the word and stop
     * asking about it. Unlike the suggestion blacklist this says nothing about
     * whether the word may be *offered* — it is only about learning it, and it
     * is quietly forgotten again if it ever ages out.
     */
    @Synchronized
    fun decline(word: String) {
        val key = WordKey.of(word)
        if (key.isEmpty()) return
        words.remove(key)
        if (declined.add(key) && declined.size > MAX_DECLINED) {
            declined.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
        }
        dirty = true
    }

    @Synchronized
    fun isDeclined(word: String): Boolean = WordKey.of(word) in declined

    /** Snapshot of everything waiting, for the personal dictionary screen. */
    @Synchronized
    fun waiting(): List<Pair<String, Int>> = words.map { it.key to it.value.count }

    @Synchronized
    fun clear() {
        words.clear()
        declined.clear()
        // The delete is the write; if it failed the file still holds what this
        // call was meant to wipe, so stay dirty and overwrite on the next save.
        dirty = snapshotFile?.delete() == false
    }

    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        words.clear()
        declined.clear()
        load()
        dirty = false
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            generation++
            expire()
            dirty = false
            file.ticket() to Snapshot(
                words = words.toMap(),
                declined = declined.toSet(),
                generation = generation,
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

    /**
     * Sightings go stale. A word typed once in March and once in September is
     * not a word the user has adopted, it is the same slip twice — so an entry
     * untouched for [EXPIRE_GENERATIONS] saves loses a sighting, and one that
     * runs out is dropped entirely. Without this the store would slowly
     * promote every typo the user ever made, which is the bug it exists to
     * fix, only slower.
     */
    private fun expire() {
        val stale = words.filterValues { generation - it.gen > EXPIRE_GENERATIONS }
        for ((key, sighting) in stale) {
            if (sighting.count <= 1) {
                words.remove(key)
            } else {
                words[key] = sighting.copy(count = sighting.count - 1, gen = generation)
            }
        }
        if (words.size > MAX_WORDS) {
            // Fewest sightings first, oldest breaking the tie: the entries
            // least likely to ever graduate.
            val evictable = words.entries.sortedWith(
                compareBy({ it.value.count }, { it.value.gen }),
            )
            for (entry in evictable.take(words.size - MAX_WORDS)) words.remove(entry.key)
        }
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            words.putAll(snapshot.words)
            declined.addAll(snapshot.declined)
            generation = snapshot.generation
        }
    }

    private companion object {
        const val MAX_WORD_LENGTH = 32
        const val MAX_COUNT = 100
        const val MAX_WORDS = 2_000
        const val MAX_DECLINED = 500

        /** Saves, not days: the keyboard saves once per dismissal. */
        const val EXPIRE_GENERATIONS = 120L
    }
}
