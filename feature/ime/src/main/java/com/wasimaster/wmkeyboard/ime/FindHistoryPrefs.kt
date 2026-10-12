package com.wasimaster.wmkeyboard.ime

import android.content.Context
import androidx.core.content.edit
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot

/**
 * The Find and replace panel's recent searches (#627), newest first.
 *
 * Its own `SharedPreferences` file, for [WordListOfferPrefs]' reasons: the
 * settings class is near its argument ceiling and a search history has no
 * business in a backup. Credential-protected storage rather than the
 * device-protected one, because what someone searched their own text for is
 * personal; before the first unlock the history reads empty and records
 * nothing.
 *
 * The settings app reads and writes it too (the tool's page has the switch and
 * the Clear row), so every call opens the file afresh instead of caching it.
 */
class FindHistoryPrefs(context: Context) {

    private val context = context.applicationContext

    private fun prefs() = context
        .takeIf { DirectBoot.isUserUnlocked(it) }
        ?.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Whether searches are remembered at all. On unless the user turned it off. */
    var enabled: Boolean
        get() = prefs()?.getBoolean(KEY_ENABLED, true) ?: false
        set(value) {
            prefs()?.edit {
                putBoolean(KEY_ENABLED, value)
                if (!value) remove(KEY_ENTRIES)
            }
        }

    fun entries(): List<String> {
        val p = prefs() ?: return emptyList()
        if (!p.getBoolean(KEY_ENABLED, true)) return emptyList()
        return p.getString(KEY_ENTRIES, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
    }

    /** Puts [query] at the front and answers the list as it now stands. */
    fun record(query: String): List<String> {
        val p = prefs() ?: return emptyList()
        if (!p.getBoolean(KEY_ENABLED, true)) return emptyList()
        val next = pushRecent(entries(), query)
        p.edit { putString(KEY_ENTRIES, next.joinToString(SEPARATOR)) }
        return next
    }

    fun clear() {
        prefs()?.edit { remove(KEY_ENTRIES) }
    }

    companion object {
        private const val FILE_NAME = "find_history"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENTRIES = "entries"

        /** A control character no one types into a search box, so entries need no escaping. */
        private const val SEPARATOR = "\u001F"

        const val MAX_ENTRIES = 10

        /** Longer than this is a paste, not a search worth offering again. */
        const val MAX_LENGTH = 100

        /**
         * [history] with [query] moved or added to the front, at most
         * [MAX_ENTRIES] long. Blank, overlong and multi-line queries leave it
         * as it was.
         */
        fun pushRecent(history: List<String>, query: String): List<String> {
            if (query.isBlank() || query.length > MAX_LENGTH || '\n' in query || SEPARATOR in query) return history
            return (listOf(query) + history.filter { it != query }).take(MAX_ENTRIES)
        }
    }
}
