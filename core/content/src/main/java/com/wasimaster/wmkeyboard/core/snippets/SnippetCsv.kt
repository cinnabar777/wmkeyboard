package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText
import java.util.Locale

/**
 * Reads a table of snippets: CSV, TSV or semicolon-separated, the one shape
 * every other text expander and every spreadsheet can write.
 *
 * Two ways in. A file with a header row is read by its column names, in any
 * order and with any extra columns: `trigger`/`shortcut`/`abbreviation` for the
 * trigger, `text`/`expansion`/`phrase`/`content` for what it types, and
 * optionally `label`/`name`, `folder`/`group` and `tags`. A file without one is
 * read by position — trigger, text, then an optional label — which is the order
 * TextExpander, aText and Texpand all export in. Position is a guess, so a
 * headerless file is only read when the caller says a table is expected: the
 * user picked the CSV row, or the file is called `.csv`/`.tsv`.
 *
 * Same rule as the other snippet readers: repair and report, never refuse a
 * whole file over one bad row. The quoting is RFC 4180's: a field in double
 * quotes may hold the separator, line breaks and `""` for a quote, which is how
 * a multi-line snippet survives the trip through a spreadsheet.
 */
object SnippetCsv {

    /** A table, read. [hasHeader] says which of the two ways in was used. */
    data class Read(
        val snippets: List<Snippet>,
        val folders: List<SnippetFolder>,
        val notes: List<ContentText>,
        val hasHeader: Boolean,
    )

    /** File extensions a snippet table is found under. */
    val EXTENSIONS = listOf("csv", "tsv")

    /** MIME types a provider reports for a table, added to the import picker. */
    val MIME_TYPES = arrayOf("text/csv", "text/comma-separated-values", "text/tab-separated-values")

    /** The same caps as [SnippetFile], for the same reasons. */
    private const val MAX_SNIPPETS = 500
    private const val MAX_TEXT_LENGTH = 20_000
    private const val MAX_FOLDERS = 100
    private const val LABEL_MAX = 60

    /** Rows parsed before giving up; well past [MAX_SNIPPETS], so the note is honest. */
    private const val MAX_ROWS = 5_000

    private enum class Column { TRIGGER, TEXT, LABEL, FOLDER, TAGS }

    /**
     * Header words, lowercased with everything but letters taken out, so
     * `Expands To`, `expands_to` and `EXPANDSTO` are the same column.
     */
    private val ALIASES: Map<String, Column> = buildMap {
        for (w in listOf("trigger", "shortcut", "abbreviation", "abbrev", "abbr", "keyword", "shortcode", "hotstring")) {
            put(w, Column.TRIGGER)
        }
        for (
            w in listOf(
                "text", "expansion", "expand", "expandsto", "replacement", "replace", "phrase",
                "content", "contents", "snippet", "snippettext", "body", "output", "value", "plaintext",
            )
        ) {
            put(w, Column.TEXT)
        }
        for (w in listOf("label", "name", "title", "description", "desc")) put(w, Column.LABEL)
        for (w in listOf("folder", "group", "category", "collection")) put(w, Column.FOLDER)
        for (w in listOf("tags", "tag")) put(w, Column.TAGS)
    }

    private val TAG_SPLIT = Regex("[,;|]")

    /**
     * True when [name] is a table's file name. The extension is the evidence a
     * headerless file is meant to be read by position at all.
     */
    fun isTableName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS

    /**
     * Reads [text], or returns null when it is not a table of snippets.
     *
     * [allowHeaderless] lets a file with no recognised header through, read by
     * position. Without it, only a header naming a text column is evidence
     * enough, which is what keeps a contacts export or any other table from
     * turning into snippets.
     */
    fun read(text: String, allowHeaderless: Boolean): Read? {
        val body = text.removePrefix("﻿")
        if (body.isBlank()) return null
        val delimiter = sniffDelimiter(body) ?: return null
        val rows = parseRows(body, delimiter).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return null

        val header = headerOf(rows.first())
        val columns: Map<Column, Int>
        val data: List<List<String>>
        if (header != null) {
            columns = header
            data = rows.drop(1)
        } else {
            if (!allowHeaderless) return null
            // A header that names columns but no text column is some other
            // table, a contacts export say, and not one without a header.
            if (rows.first().all { it.isNotBlank() } && rows.first().any { aliasOf(it) != null }) return null
            // By position. Every row needs a trigger and a text cell, so a file
            // that is mostly one column is a list of something else.
            val wide = rows.count { it.size >= 2 }
            if (wide * 2 < rows.size) return null
            columns = buildMap {
                put(Column.TRIGGER, 0)
                put(Column.TEXT, 1)
                if (rows.any { it.size >= 3 }) put(Column.LABEL, 2)
            }
            data = rows
        }
        return convert(data, columns, hasHeader = header != null)
    }

    /**
     * The header's columns, or null when the first row is not a header.
     *
     * A header has to name the text and one of the trigger or the label: one
     * matching word on its own could be a headerless row whose first trigger
     * happens to be `key` or `name`.
     */
    private fun headerOf(row: List<String>): Map<Column, Int>? {
        val out = HashMap<Column, Int>()
        row.forEachIndexed { index, cell ->
            val column = aliasOf(cell) ?: return@forEachIndexed
            // First one wins: "Name" and "Description" both mean label.
            out.putIfAbsent(column, index)
        }
        if (Column.TEXT !in out) return null
        if (Column.TRIGGER !in out && Column.LABEL !in out) return null
        return out
    }

    private fun aliasOf(cell: String): Column? =
        ALIASES[cell.lowercase(Locale.ROOT).filter { it.isLetter() }]

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun convert(rows: List<List<String>>, columns: Map<Column, Int>, hasHeader: Boolean): Read? {
        fun List<String>.at(column: Column): String = columns[column]?.let { getOrNull(it) }.orEmpty()

        val notes = ArrayList<ContentText>()
        val snippets = ArrayList<Snippet>()
        val folders = LinkedHashMap<String, SnippetFolder>()
        var noText = 0
        var badTriggers = 0
        var overflow = false
        for (row in rows) {
            val body = row.at(Column.TEXT)
            if (body.isBlank()) {
                noText++
                continue
            }
            if (snippets.size >= MAX_SNIPPETS) {
                overflow = true
                break
            }
            val rawTrigger = row.at(Column.TRIGGER).trim()
            val trigger = rawTrigger.takeIf { it.isNotEmpty() && SnippetMatcher.isMatchableTrigger(it) }
            if (rawTrigger.isNotEmpty() && trigger == null) badTriggers++
            val label = row.at(Column.LABEL).trim()
                .ifEmpty { body.lineSequence().first().trim().take(LABEL_MAX) }
                .ifEmpty { body.trim().take(LABEL_MAX) }
            val kept = if (body.length > MAX_TEXT_LENGTH) {
                notes += ContentText(
                    pluralsRes = R.plurals.core_content_snippet_repair_shortened,
                    quantity = MAX_TEXT_LENGTH,
                    args = listOf(label.take(30), MAX_TEXT_LENGTH),
                )
                body.take(MAX_TEXT_LENGTH)
            } else {
                body
            }
            val folderName = row.at(Column.FOLDER).trim()
            val folderId = if (folderName.isEmpty()) {
                0L
            } else {
                folders[folderName.lowercase(Locale.ROOT)]?.id
                    ?: if (folders.size < MAX_FOLDERS) {
                        SnippetFolder(id = folders.size + 1L, name = folderName)
                            .also { folders[folderName.lowercase(Locale.ROOT)] = it }
                            .id
                    } else {
                        0L
                    }
            }
            snippets += Snippet(
                id = 0,
                label = label,
                text = kept,
                trigger = trigger,
                folderId = folderId,
                tags = row.at(Column.TAGS).split(TAG_SPLIT)
                    .mapNotNull { tag -> tag.trim().take(SnippetStore.MAX_TAG_LENGTH).takeIf { it.isNotEmpty() } }
                    .distinct()
                    .take(SnippetStore.MAX_TAGS),
            )
        }
        if (snippets.isEmpty() && noText == 0) return null
        if (overflow) {
            notes += ContentText(
                pluralsRes = R.plurals.core_content_snippet_repair_kept_first,
                quantity = MAX_SNIPPETS,
                args = listOf(MAX_SNIPPETS),
            )
        }
        if (noText > 0) {
            notes += ContentText(
                pluralsRes = R.plurals.core_content_snippet_repair_rows_no_text,
                quantity = noText,
                args = listOf(noText),
            )
        }
        if (badTriggers > 0) {
            notes += ContentText(
                pluralsRes = R.plurals.core_content_snippet_repair_triggers_removed,
                quantity = badTriggers,
                args = listOf(badTriggers),
            )
        }
        return Read(
            snippets = snippets,
            folders = folders.values.toList(),
            notes = notes,
            hasHeader = hasHeader,
        )
    }

    /**
     * The separator the first line uses, counted outside quotes: tab, comma or
     * semicolon (what a spreadsheet writes where the comma is the decimal
     * mark). Null when the line has none of them, which a table cannot be.
     */
    internal fun sniffDelimiter(text: String): Char? {
        val counts = IntArray(CANDIDATES.size)
        var quoted = false
        for (c in text) {
            if (c == '"') quoted = !quoted
            if (quoted) continue
            if (c == '\n' || c == '\r') break
            val at = CANDIDATES.indexOf(c)
            if (at >= 0) counts[at]++
        }
        var best = -1
        for (i in CANDIDATES.indices) {
            if (counts[i] > 0 && (best < 0 || counts[i] > counts[best])) best = i
        }
        return if (best < 0) null else CANDIDATES[best]
    }

    /** In order of preference when two are counted the same. */
    private val CANDIDATES = charArrayOf('\t', ',', ';')

    /**
     * RFC 4180 rows. A quote opens a quoted field only at the start of a field;
     * anywhere else it is an ordinary character, which is how hand-written
     * files that never quote anything still read as written.
     */
    internal fun parseRows(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var atFieldStart = true
        var i = 0
        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            atFieldStart = true
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = ArrayList()
        }
        while (i < text.length && rows.size < MAX_ROWS) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && atFieldStart -> {
                    quoted = true
                    atFieldStart = false
                }
                c == delimiter -> endField()
                c == '\r' -> {
                    if (text.getOrNull(i + 1) == '\n') i++
                    endRow()
                }
                c == '\n' -> endRow()
                else -> {
                    field.append(c)
                    atFieldStart = false
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
