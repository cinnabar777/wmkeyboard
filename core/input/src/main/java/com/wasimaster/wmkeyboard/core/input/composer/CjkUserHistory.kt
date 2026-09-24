package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the user actually picked, per reading, so the same reading offers it
 * first next time.
 *
 * This is the one thing every shipping CJK IME does that this one did not: a
 * conversion commit taught the keyboard nothing, so the eightieth-ranked
 * character you use daily stayed eightieth forever.
 *
 * **Deliberately not `UserLexicon`.** That store drops any key shorter than two
 * characters, which is the single most common CJK commit; it is keyed by surface
 * word where conversion ranking is keyed by reading; and its contents are fed to
 * the Latin suggestion ranker and the gesture decoder, where Hanzi is pollution.
 * Its file is also rewritten whole, and mixing CJK churn into it would multiply
 * that cost for a user who types both.
 *
 * ### Namespaces are reading spaces, not languages
 *
 * `zh` alone has six ways of spelling a reading — pinyin letters, T9 digits,
 * bopomofo, Cangjie letters, Quick letters and stroke digits. A pinyin `ni` and a
 * Cangjie `ni` have nothing to do with each other, so a per-language split would
 * have them overwrite one another. Keying on the reading space instead gives the
 * zh/ja/yue separation for free and the intra-Chinese one as well.
 */
class CjkUserHistory(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        /** namespace → reading → word → times chosen. */
        val picks: Map<String, Map<String, Map<String, Int>>> = emptyMap(),
    )

    private val picks = HashMap<String, HashMap<String, HashMap<String, Int>>>()

    /** Whether anything has changed since the last [save]. */
    @Volatile
    var dirty: Boolean = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)
        private set

    init {
        load()
    }

    /** Records that [word] was chosen for [reading]. */
    @Synchronized
    fun learn(namespace: String, reading: String, word: String) {
        if (reading.isEmpty() || word.isEmpty()) return
        val byReading = picks.getOrPut(namespace) { HashMap() }
        val counts = byReading.getOrPut(reading) { HashMap() }
        counts[word] = (counts[word] ?: 0) + 1
        dirty = true
        CjkDictionaries.invalidate()
    }

    /** How many times [word] has been chosen for [reading]. */
    @Synchronized
    fun countFor(namespace: String, reading: String, word: String): Int =
        picks[namespace]?.get(reading)?.get(word) ?: 0

    /** True when nothing has ever been learned for [namespace]. */
    @Synchronized
    fun isEmptyFor(namespace: String): Boolean = picks[namespace].isNullOrEmpty()

    @Synchronized
    fun clear() {
        if (picks.isEmpty()) return
        picks.clear()
        dirty = true
        CjkDictionaries.invalidate()
    }

    @Synchronized
    fun clear(namespace: String) {
        if (picks.remove(namespace) == null) return
        dirty = true
        CjkDictionaries.invalidate()
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                picks = picks.mapValues { (_, readings) ->
                    readings.mapValues { (_, words) -> words.toMap() }
                },
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { JSON.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    @Synchronized
    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = JSON.decodeFromString<Snapshot>(file.readText())
            for ((namespace, byReading) in snapshot.picks) {
                val target = picks.getOrPut(namespace) { HashMap() }
                for ((reading, counts) in byReading) target[reading] = HashMap(counts)
            }
        }
        dirty = false
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The process-wide learned-pick store, mirroring [CjkDictionaries]: the composers
 * stay parameter-less singletons and the service swaps the real store in once it
 * has a files directory. Null until then, and null is a working state — nothing
 * is learned and nothing is re-ranked.
 */
object CjkLearning {

    @Volatile
    var store: CjkUserHistory? = null

    /**
     * Whether picks may be recorded at all. The service mirrors the same gate the
     * Latin lexicon uses (learn-from-typing off, incognito, or a field that
     * forbids typing intelligence), so both stores go quiet together.
     */
    @Volatile
    var enabled: Boolean = true

    fun learn(namespace: String, reading: String, word: String) {
        if (!enabled) return
        store?.learn(namespace, reading, word)
    }

    /**
     * [items] with previously-chosen candidates first, most-chosen first among
     * them, and everything else left in exactly the order it arrived.
     *
     * A *stable partition* rather than a score adjustment on purpose. The pack's
     * frequency column has no documented scale, so any arithmetic blending it
     * with a pick count is a guess that behaves differently per pack; sorting
     * keeps the decoder's ranking intact underneath and makes the promise the
     * user actually expects — the character you chose for this reading is the one
     * offered next time — exact rather than probable. Clearing the history
     * restores the original order precisely.
     */
    fun <T> rank(
        namespace: String,
        items: List<T>,
        text: (T) -> String,
        reading: (T) -> String,
    ): List<T> {
        val history = store ?: return items
        if (items.size < 2 || history.isEmptyFor(namespace)) return items
        var seen = false
        val counts = items.map { item ->
            history.countFor(namespace, reading(item), text(item)).also { if (it > 0) seen = true }
        }
        if (!seen) return items
        return items.indices.sortedByDescending { counts[it] }.map { items[it] }
    }
}
