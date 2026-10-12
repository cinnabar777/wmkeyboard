package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Gboard's personal dictionary, as its own Export writes it.
 *
 * Gboard → Settings → Dictionary → Personal dictionary → ⋮ → Export gives a
 * `PersonalDictionary.zip` holding one `dictionary.txt`:
 *
 * ```
 * # Gboard Dictionary version:1
 * omw	on my way	en-US
 * 	Wasimaster	en-US
 * ```
 *
 * One entry per line, three tab-separated fields: the shortcut (often empty),
 * the word or phrase, and the locale it was added under (empty for "all
 * languages"). The same file, unzipped or zipped, is what Gboard's Import takes,
 * which is why community packs (LaTeX symbols, emoji codes) ship in it.
 *
 * It holds two different things for this app. An entry with a shortcut is a
 * text expansion, so it becomes a [Snippet]. An entry without one is a word
 * the user taught their keyboard, so it belongs in the personal dictionary.
 * This reader only separates them; the settings app decides where each goes.
 */
object GboardDictionary {

    /** The first line of every export. */
    const val HEADER = "# Gboard Dictionary version:"

    /** The one entry inside the exported ZIP. */
    const val ENTRY_NAME = "dictionary.txt"

    /** What Gboard names the ZIP; used for the default snippet folder too. */
    const val FOLDER_NAME = "Gboard"

    /** Far past any personal dictionary; a file past this is not one. */
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    private const val MAX_FIELD = 1_000

    /** The snippet caps every other importer uses. */
    private const val MAX_SNIPPETS = 500
    private const val LABEL_MAX = 60

    /** A word shorter than this is not worth offering, the rule Android's import uses too. */
    private const val MIN_WORD_LENGTH = 2

    private val WHITESPACE_RUN = Regex("\\s+")

    /** One line of the file. [shortcut] is empty for a plain word. */
    data class Entry(val shortcut: String, val word: String, val locale: String)

    /** What one file held. */
    data class Contents(val entries: List<Entry>) {

        /** The entries that carry a shortcut, and so become snippets. */
        val shortcuts: List<Entry> get() = entries.filter { it.shortcut.isNotEmpty() }

        /**
         * The plain words, for the personal dictionary.
         *
         * A phrase is offered as its separate words, the way the keyboard
         * already reads a multi-word entry from Android's own dictionary.
         * Spelled the way the file spells them, first spelling wins.
         */
        val words: List<String>
            get() {
                val seen = HashSet<String>()
                val out = ArrayList<String>()
                for (entry in entries) {
                    if (entry.shortcut.isNotEmpty()) continue
                    for (part in entry.word.split(WHITESPACE_RUN)) {
                        val word = part.trim()
                        if (word.length < MIN_WORD_LENGTH) continue
                        if (seen.add(word.lowercase(Locale.ROOT))) out += word
                    }
                }
                return out
            }
    }

    /** True when [text] starts the way every Gboard export does. */
    fun looksLikeGboard(text: String): Boolean =
        text.removePrefix("﻿").trimStart().startsWith(HEADER)

    /** True when a ZIP's entry names include the one Gboard writes. */
    fun looksLikeGboardZip(names: List<String>): Boolean =
        names.any { it.substringAfterLast('/').equals(ENTRY_NAME, ignoreCase = true) }

    /** Reads the text of a `dictionary.txt`, or null when it is not one. */
    fun read(text: String): Contents? {
        if (!looksLikeGboard(text)) return null
        val entries = ArrayList<Entry>()
        for (line in text.removePrefix("﻿").lineSequence()) {
            if (entries.size >= MAX_ENTRIES) break
            val clean = line.trimEnd('\r')
            if (clean.isBlank() || clean.startsWith("#")) continue
            val fields = clean.split('\t')
            // A line with no tab is a bare word, which Gboard's own import
            // accepts too.
            val shortcut = if (fields.size >= 2) fields[0].trim() else ""
            val word = (if (fields.size >= 2) fields[1] else fields[0]).trim()
            if (word.isEmpty() || word.length > MAX_FIELD || shortcut.length > MAX_FIELD) continue
            entries += Entry(shortcut, word, fields.getOrNull(2)?.trim().orEmpty())
        }
        return Contents(entries)
    }

    /**
     * Reads the `dictionary.txt` inside a ZIP, or null when the archive has
     * none or it is not a Gboard dictionary. Does not close [input].
     */
    fun readZip(input: InputStream): Contents? {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (entry.isDirectory) continue
            if (!entry.name.substringAfterLast('/').equals(ENTRY_NAME, ignoreCase = true)) continue
            val bytes = readCapped(zip) ?: return null
            return read(bytes.decodeToString())
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    /** What the shortcuts become, and what had to change on the way. */
    data class Converted(val snippets: List<Snippet>, val notes: List<ContentText>)

    /**
     * The shortcuts as snippets.
     *
     * Gboard never types a shortcut out on its own: it offers the word on the
     * suggestion strip and waits for a tap. So every snippet here asks first
     * ([Snippet.confirm]), which is the same behaviour rather than a stronger
     * one. A shortcut Gboard maps to several words becomes one snippet with
     * those words as its expansions, all offered as chips, which is again
     * what Gboard shows.
     */
    fun toSnippets(contents: Contents): Converted {
        val notes = ArrayList<ContentText>()
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (entry in contents.shortcuts) {
            val words = grouped.getOrPut(entry.shortcut) { ArrayList() }
            if (entry.word !in words) words += entry.word
        }
        val snippets = ArrayList<Snippet>()
        var badTriggers = 0
        for ((shortcut, words) in grouped) {
            if (snippets.size >= MAX_SNIPPETS) {
                notes += ContentText(
                    pluralsRes = R.plurals.core_content_snippet_repair_kept_first,
                    quantity = MAX_SNIPPETS,
                    args = listOf(MAX_SNIPPETS),
                )
                break
            }
            val trigger = shortcut.takeIf(SnippetMatcher::isMatchableTrigger)
            if (trigger == null) badTriggers++
            val first = words.first()
            snippets += Snippet(
                id = 0,
                label = first.lineSequence().first().take(LABEL_MAX),
                text = first,
                trigger = trigger,
                confirm = true,
                alternates = words.drop(1).take(SnippetStore.MAX_ALTERNATES),
                multiExpand = if (words.size > 1) MultiExpand.CHIPS_ONLY else MultiExpand.DEFAULT,
            )
        }
        if (badTriggers > 0) {
            notes += ContentText(
                pluralsRes = R.plurals.core_content_snippet_repair_triggers_removed,
                quantity = badTriggers,
                args = listOf(badTriggers),
            )
        }
        return Converted(snippets, notes)
    }
}
