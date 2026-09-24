package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which of the user's languages they write in, app by app, so a field can
 * start out leaning the right way before a word has been typed in it.
 *
 * [FieldLanguageMix] reads the field and is exact but starts every field from
 * nothing, which is precisely what an empty message box has. People are
 * creatures of habit about *where* they write what: Banglish in the chat
 * apps, English in mail. This keeps a decayed tally per app of the languages
 * the words committed there belonged to, and hands the field mix a
 * [FieldLanguageMix.Prior] built from it.
 *
 * Deliberately weak. The prior is worth at most [MAX_PRIOR_WEIGHT] words of
 * evidence, less for an app with little history, so the first word actually
 * typed outweighs it and the second settles the matter — an English message
 * in the Banglish chat app is never fought. Words every language of the mix
 * knows credit all of them and move the tally nowhere, the field's own rule.
 * Tallies decay per word so a changed habit is followed within a hundred
 * words or so, and the least recently used apps fall off past [MAX_APPS].
 *
 * Personal-store contract: nullable file for a locked device, dirty-flag save
 * on dismissal, [reload] for the Storage screen's delete. Package names are
 * the keys, so this stays on the device with the rest of the learning data.
 */
class AppLanguageMix(private val storageFile: File? = null) {

    @Serializable
    private data class Snapshot(
        /** package -> language id -> decayed credit. */
        val apps: Map<String, Map<String, Double>> = emptyMap(),
        /** package -> decayed count of classified words. */
        val evidence: Map<String, Double> = emptyMap(),
    )

    private val apps = object : LinkedHashMap<String, HashMap<String, Double>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HashMap<String, Double>>): Boolean {
            val drop = size > MAX_APPS
            if (drop) evidence.remove(eldest.key)
            return drop
        }
    }
    private val evidence = HashMap<String, Double>()
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    init {
        load()
    }

    /**
     * One word committed in [packageName], credited to every language in
     * [owners]. An unclassified word (empty [owners]) only decays what is
     * there, and is ignored entirely for an app with no tally yet, so a
     * monolingual keyboard never writes a file full of empty apps.
     */
    @Synchronized
    fun record(packageName: String, owners: Set<String>) {
        if (packageName.isEmpty()) return
        val existing = apps[packageName]
        if (existing == null && owners.isEmpty()) return
        val tally = existing ?: HashMap<String, Double>(4).also { apps[packageName] = it }
        val iterator = tally.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = entry.value * DECAY
            if (decayed < FLOOR) iterator.remove() else entry.setValue(decayed)
        }
        evidence[packageName] = (evidence[packageName] ?: 0.0) * DECAY
        if (owners.isNotEmpty()) {
            for (langId in owners) {
                if (langId.isNotEmpty()) tally.merge(langId, 1.0, Double::plus)
            }
            evidence[packageName] = (evidence[packageName] ?: 0.0) + 1.0
        }
        dirty = true
    }

    /**
     * How [packageName]'s fields usually start, or null with too little
     * history to say. The weight grows with the app's history towards
     * [MAX_PRIOR_WEIGHT] and never reaches it: a habit is a strong hint, not
     * a word the user typed.
     */
    @Synchronized
    fun prior(packageName: String): FieldLanguageMix.Prior? {
        val tally = apps[packageName] ?: return null
        val words = evidence[packageName] ?: return null
        if (words < MIN_WORDS) return null
        val total = tally.values.sum()
        if (total <= 0.0) return null
        val shares = HashMap<String, Double>(tally.size * 2)
        for ((langId, credit) in tally) shares[langId] = credit / total
        val weight = MAX_PRIOR_WEIGHT * words / (words + PRIOR_SAMPLE)
        return FieldLanguageMix.Prior(shares, weight)
    }

    /** Whether anything has been learned about [packageName]. */
    @Synchronized
    fun knows(packageName: String): Boolean = apps.containsKey(packageName)

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(apps.mapValues { it.value.toMap() }, evidence.toMap())
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

    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        apps.clear()
        evidence.clear()
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        apps.clear()
        evidence.clear()
        dirty = snapshotFile?.delete() == false
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            for ((pkg, tally) in snapshot.apps) {
                val clean = tally.filterValues { it > 0.0 && it.isFinite() }
                if (clean.isNotEmpty()) apps[pkg] = HashMap(clean)
            }
            for ((pkg, words) in snapshot.evidence) {
                if (words > 0.0 && words.isFinite() && apps.containsKey(pkg)) evidence[pkg] = words
            }
        }
    }

    companion object {
        /**
         * The most a well-known app's habit is worth, in typed words. One and
         * a half: the field mix ramps to full at two, so a habit alone never
         * reaches the full detection swing, and one real word decays it to
         * roughly even.
         */
        const val MAX_PRIOR_WEIGHT = 1.5

        /** Classified words at which the prior is worth half of the maximum. */
        const val PRIOR_SAMPLE = 20.0

        /**
         * Fewer classified words than this and the app has no opinion. Three
         * words, measured on the decayed count (each word decays what came
         * before it, so three of them tally just under three).
         */
        const val MIN_WORDS = 2.5

        /** Per-word decay: roughly the last hundred words in charge. */
        private const val DECAY = 0.99

        /** Below this a language is pruned from an app's tally. */
        private const val FLOOR = 0.01

        /** Apps remembered before the least recently typed-in one is dropped. */
        const val MAX_APPS = 64
    }
}
