package com.wasimaster.wmkeyboard.core.prediction

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.provider.UserDictionary

/**
 * Android's system personal dictionary ([UserDictionary]), both ways.
 *
 * Reading: [words] hands the whole list to the engine as a known-word source
 * and [shortcuts] its shortcut column. Writing: [add] mirrors words this
 * keyboard learns into it, so other keyboards and the platform spell checker
 * recognize them too. Writing is opt-in — the on-device [UserLexicon] already
 * covers this keyboard on its own — while reading is on by default: a word
 * the user put in the platform dictionary is a word they expect every
 * keyboard to know (#45).
 *
 * The provider only lets the *current* IME (or a spell checker / system app)
 * write, which is exactly the case while the user is typing on this keyboard,
 * so writes normally succeed. Every call is still wrapped defensively: a
 * SecurityException on some OEM build must never take the keyboard down.
 *
 * [UserDictionary.Words.addWord] does not deduplicate, so a session-lived set
 * of already-added words — seeded once from what the dictionary already holds —
 * keeps repeated typing from piling up duplicate rows.
 */
object SystemUserDictionary {

    /**
     * The platform dictionary as the engine consumes it: the searchable
     * [source] keyed the way every other store is keyed, plus the surface
     * [shapes] of the entries the user wrote with capitals.
     */
    data class Entries(
        val source: WordSource,
        /** key -> the spelling the row was written in; capitalized rows only. */
        val shapes: Map<String, String>,
    ) {
        companion object {
            val EMPTY = Entries(PackedTrie.EMPTY, emptyMap())
        }
    }

    /** Middle-of-the-road frequency; user-typed words aren't ranking-critical here. */
    private const val FREQUENCY = 250

    private val added = HashSet<String>()
    private var seeded = false

    /**
     * Adds [word] to the system dictionary if it isn't already there. Safe to
     * call from any thread, but does content-provider I/O, so callers should
     * run it off the main thread. No-ops on blank or single-character words.
     */
    fun add(context: Context, word: String) {
        val cleaned = word.trim()
        if (cleaned.length < 2) return
        val key = cleaned.lowercase()
        synchronized(this) {
            seed(context)
            if (!added.add(key)) return
        }
        runCatching {
            // locale = null makes the word valid for every locale, which suits a
            // multi-language keyboard where the same field mixes scripts.
            UserDictionary.Words.addWord(
                context,
                cleaned,
                FREQUENCY,
                null,
                null,
            )
        }
    }

    /**
     * Removes every row spelling [word] (any capitalisation) from the system
     * dictionary, for the strip's delete action (#99). Same access rule as
     * [add] — the current IME may write — and the same defensive wrapping:
     * an OEM that refuses just leaves the rows in place. Returns whether a
     * row went. Content-provider I/O; call it off the main thread.
     */
    fun remove(context: Context, word: String): Boolean {
        val key = word.trim().lowercase()
        if (key.isEmpty()) return false
        synchronized(this) { added.remove(key) }
        return runCatching {
            val ids = ArrayList<Long>()
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words._ID, UserDictionary.Words.WORD),
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(UserDictionary.Words._ID)
                val wordCol = cursor.getColumnIndex(UserDictionary.Words.WORD)
                if (idCol >= 0 && wordCol >= 0) {
                    while (cursor.moveToNext()) {
                        val row = cursor.getString(wordCol)?.trim()?.lowercase() ?: continue
                        if (row == key) ids.add(cursor.getLong(idCol))
                    }
                }
            }
            var deleted = 0
            for (id in ids) {
                deleted += context.contentResolver.delete(
                    ContentUris.withAppendedId(UserDictionary.Words.CONTENT_URI, id),
                    null,
                    null,
                )
            }
            deleted > 0
        }.getOrDefault(false)
    }

    /**
     * Every word in Android's personal dictionary as a [WordSource], so the
     * keyboard treats them as known: they complete, they are never
     * autocorrected away, and gliding one does not put an "add to
     * dictionary?" chip on the strip (#45). Reads the whole table each call
     * — the list is small and hand-curated — and never throws: an OEM that
     * hides the provider, or a locked boot, yields the empty source.
     *
     * Comes back with the spellings alongside the keys, so a capitalized
     * entry reaches the strip capitalized.
     *
     * Locale is ignored on purpose. The platform UI files most entries under
     * the device locale even when the word is a name or an acronym that is
     * valid everywhere, and a multi-language keyboard mixes scripts in one
     * field; a word the user went out of their way to add is a word in any
     * language they type.
     */
    fun words(context: Context): Entries = index(spellings(context))

    /** The rows [words] indexes, each as it was written. Empty when the provider will not answer. */
    fun spellings(context: Context): List<String> {
        val out = ArrayList<String>()
        runCatching {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD),
                null,
                null,
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndex(UserDictionary.Words.WORD)
                if (col >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(col)?.let { out.add(it) }
                    }
                }
            }
        }
        return out
    }

    /**
     * Builds the source [words] returns from raw dictionary rows. Keys are
     * normalised the way the personal lexicon keys its own words, so "AOSP"
     * answers `contains("aosp")` like every other known word; the stored
     * frequency is dropped in favour of a flat 1, because the engine weights
     * this source like the personal lexicon, where 1 means "a word the user
     * typed once" — a 250 (the platform UI's default) would outrank real
     * vocabulary by orders of magnitude. Multi-word entries index as their
     * parts: "on my way" is not a word anyone types as one token, but each
     * part is. Pure, so it is unit-testable off the device.
     *
     * Only a row that *is* one word hands over a spelling, though. The
     * capitals in "User Dictionary Manager" belong to the phrase — that is how
     * a title is written — and say nothing about how the user spells "user" on
     * its own, so lifting them onto the parts would capitalize three ordinary
     * words everywhere the keyboard writes them and make the case vote read
     * them as words that are never lower case (#221). The phrase itself is
     * still reachable as written: it is what the row's shortcut expands to.
     */
    fun index(words: Iterable<String>): Entries {
        val keys = LinkedHashSet<String>()
        val cases = HashMap<String, String>()
        for (raw in words) {
            val parts = raw.split(WHITESPACE).map { it.trim() }.filter { it.isNotEmpty() }
            val singleWord = parts.size == 1
            for (trimmed in parts) {
                val key = WordKey.of(trimmed)
                if (key.length < 2) continue
                keys.add(key)
                // The capital the user typed into the platform's dictionary is
                // the whole reason they went there ("Boston", "AOSP", an email
                // address), so it is kept beside the key and put back on
                // anything the keyboard offers (#44). First spelling wins: two
                // rows differing only in case are one word, and re-deciding it
                // per row would make the answer depend on cursor order.
                if (singleWord && trimmed != key) {
                    cases.putIfAbsent(key, WordKey.surface(trimmed))
                }
            }
        }
        if (keys.isEmpty()) return Entries.EMPTY
        return Entries(PackedTrie.of(keys.map { it to 1 }), cases)
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * The shortcut → expansion entries in Android's personal dictionary (the
     * SHORTCUT column the platform "Personal dictionary" UI fills in, e.g.
     * "omw" → "on my way"). Lowercased shortcuts. Reads the whole table each
     * call — cheap, small, and the caller caches it — and never throws: an OEM
     * that hides the provider just yields an empty map. Powers
     * [com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings.expandUserDictShortcuts].
     */
    fun shortcuts(context: Context): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT),
                null,
                null,
                null,
            )?.use { cursor ->
                val wordCol = cursor.getColumnIndex(UserDictionary.Words.WORD)
                val shortcutCol = cursor.getColumnIndex(UserDictionary.Words.SHORTCUT)
                if (wordCol >= 0 && shortcutCol >= 0) {
                    while (cursor.moveToNext()) {
                        val shortcut = cursor.getString(shortcutCol)?.trim()
                        val word = cursor.getString(wordCol)?.trim()
                        if (!shortcut.isNullOrEmpty() && !word.isNullOrEmpty()) {
                            out[shortcut.lowercase()] = word
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * Whether Android will share its dictionary with this app right now, for
     * the settings app's import and export (#174). Since Android 10 the
     * provider serves only the current keyboard or spell checker, and it
     * answers anyone else with an *empty* list rather than an error, so an
     * empty read cannot tell "nothing there" from "not allowed". Asking which
     * keyboard is current can.
     */
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull() ?: return false
        return current.substringBefore('/') == context.packageName
    }

    /**
     * Adds each of [words] not already in the dictionary and returns how many
     * rows were really written, for the settings app's export (#174). The same
     * de-duplication as [add]; a row the provider refuses is not counted, and
     * is not remembered as added either, so a later try can still write it.
     * Content-provider I/O; call it off the main thread.
     */
    fun addAll(context: Context, words: Collection<String>): Int {
        var written = 0
        for (word in words) {
            val cleaned = word.trim()
            if (cleaned.length < 2) continue
            val key = cleaned.lowercase()
            val fresh = synchronized(this) {
                seed(context)
                added.add(key)
            }
            if (!fresh) continue
            val ok = runCatching {
                val values = ContentValues().apply {
                    put(UserDictionary.Words.WORD, cleaned)
                    put(UserDictionary.Words.FREQUENCY, FREQUENCY)
                    putNull(UserDictionary.Words.LOCALE)
                    put(UserDictionary.Words.APP_ID, 0)
                }
                context.contentResolver.insert(UserDictionary.Words.CONTENT_URI, values) != null
            }.getOrDefault(false)
            if (ok) written++ else synchronized(this) { added.remove(key) }
        }
        return written
    }

    /** Loads the words already in the dictionary once, so we don't re-add them. */
    private fun seed(context: Context) {
        if (seeded) return
        seeded = true
        runCatching {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD),
                null,
                null,
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndex(UserDictionary.Words.WORD)
                if (col >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(col)?.let { added.add(it.lowercase()) }
                    }
                }
            }
        }
    }
}
