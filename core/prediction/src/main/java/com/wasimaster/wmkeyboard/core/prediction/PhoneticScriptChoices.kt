package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which script the user has overruled a spelling into, on a phonetic layout
 * that may commit either ([PhoneticScriptVerdict]).
 *
 * The verdict is a guess about words two languages share, and the same few
 * come up again and again: whoever writes `to` for তো writes it twenty times a
 * day. Being wrong about it once is the price of guessing; being wrong about
 * it the twentieth time is not. So every time the user takes the other script
 * instead — the chip, the backspace that flips it — the spelling is nudged
 * that way, and after [FIXED] nudges the question is closed for that spelling
 * until they nudge it back.
 *
 * Only overrulings are recorded, never agreement: a commit left alone says
 * nothing the verdict did not already know, and counting it would let habit
 * drown a correction out.
 *
 * Personal-store contract, as [AppLanguageMix]: nullable file for a locked
 * device, dirty-flag save on dismissal, [reload] for the Storage screen.
 */
class PhoneticScriptChoices(private val storageFile: File? = null) {

    @Serializable
    private data class Snapshot(
        /** `language:spelling` -> signed count, positive for Latin. */
        val choices: Map<String, Int> = emptyMap(),
    )

    private val choices = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > MAX_SPELLINGS
    }
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    init {
        load()
    }

    /** What is known about [spelling] on [languageId]'s layout: positive for Latin, 0 for nothing. */
    @Synchronized
    fun choiceFor(languageId: String, spelling: String): Int =
        choices[key(languageId, spelling)] ?: 0

    /** The user took [script] for [spelling] over what the verdict had chosen. */
    @Synchronized
    fun record(languageId: String, spelling: String, script: PhoneticScript) {
        if (spelling.isBlank()) return
        val key = key(languageId, spelling)
        val step = if (script == PhoneticScript.LATIN) 1 else -1
        val next = ((choices[key] ?: 0) + step).coerceIn(-LIMIT, LIMIT)
        if (next == 0) choices.remove(key) else choices[key] = next
        dirty = true
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(choices.toMap())
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
        choices.clear()
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        choices.clear()
        dirty = snapshotFile?.delete() == false
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            for ((key, count) in json.decodeFromString<Snapshot>(file.readText()).choices) {
                if (count != 0) choices[key] = count.coerceIn(-LIMIT, LIMIT)
            }
        }
    }

    private fun key(languageId: String, spelling: String) = "$languageId:${spelling.trim().lowercase()}"

    companion object {
        /** Overrulings after which the spelling's script is no longer weighed at all. */
        const val FIXED = 2

        /** How far a count runs, so that many nudges one way take only a few to undo. */
        private const val LIMIT = 3

        /** Spellings remembered before the least recently consulted one is dropped. */
        const val MAX_SPELLINGS = 512
    }
}
