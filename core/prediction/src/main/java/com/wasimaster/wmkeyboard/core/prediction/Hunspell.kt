package com.wasimaster.wmkeyboard.core.prediction

import java.nio.charset.Charset

/**
 * Hunspell spelling dictionaries: an `.aff` file of affix rules beside a `.dic`
 * file of stems. It is the format LibreOffice, Firefox, Chrome and macOS spell
 * with, so it is the one dictionary almost every language has, often long before
 * anyone counts a frequency list for it (#619, Bosnian).
 *
 * A keyboard needs words, not rules, so [expand] unfolds the pair the way
 * Hunspell's own `unmunch` does: every stem, with every prefix and suffix its
 * flags allow, including a prefix and a suffix together where both are marked
 * as combining, and one level of suffix continuation (`SFX A 0 ima/B`).
 * Compounds are not generated: they make an unbounded set, and the stems they
 * are built from are in the list anyway. Morphology fields, the REP, MAP and
 * KEY suggestion tables and the rest of the file are not read.
 *
 * The format carries no frequencies. A stem is listed at [STEM_FREQUENCY] and
 * a form made from it at [FORM_FREQUENCY], so the dictionary form of a word
 * completes ahead of its inflections, and both stay below anything counted.
 */
object Hunspell {

    /** Past this the list stops growing; stems come first, so it is the rarest forms that go. */
    const val MAX_WORDS = 600_000

    const val STEM_FREQUENCY = 2
    const val FORM_FREQUENCY = 1

    private val WHITESPACE = Regex("\\s+")
    private val UTF8_BOM = "﻿"

    /** `SET` names Hunspell accepts that Java spells differently. */
    private val CHARSET_ALIASES = mapOf(
        "microsoft-cp1251" to "windows-1251",
        "microsoft-cp1250" to "windows-1250",
        "tis620-2533" to "TIS-620",
    )

    fun isAff(name: String): Boolean = name.lowercase().endsWith(".aff")

    /**
     * Whether [bytes] read as a `.dic`: the name says so, and the first line is
     * the word count Hunspell puts there. A plain list someone saved as `.dic`
     * starts with a word instead, and imports as the plain list it is.
     */
    fun looksLikeDic(name: String, bytes: ByteArray): Boolean {
        if (!name.lowercase().endsWith(".dic")) return false
        val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1)
        val first = head.removePrefix("ï»¿").lineSequence().firstOrNull()?.trim().orEmpty()
        return first.isNotEmpty() && first.all { it.isDigit() }
    }

    /** The stem a file pairs on: its path without the extension, case folded. */
    fun stem(name: String): String = name.substringBeforeLast('.').lowercase()

    /** The `LANG` the affix file names, when it names one. */
    fun language(aff: ByteArray): String? =
        decode(aff, charsetOf(aff)).lineSequence()
            .map { it.trim().split(WHITESPACE) }
            .firstOrNull { it.size >= 2 && it[0] == "LANG" }
            ?.get(1)

    /** Every word [aff] and [dic] spell, stems first, each with its frequency. */
    fun expand(aff: ByteArray, dic: ByteArray): List<Pair<String, Int>> {
        val charset = charsetOf(aff)
        val rules = Rules.parse(decode(aff, charset))
        val out = LinkedHashMap<String, Int>()
        val stems = decode(dic, charset).lineSequence().drop(1).mapNotNull { rules.entry(it) }.toList()
        // Stems first, all of them, then their forms: past the cap it is forms
        // that are dropped, never a whole word.
        for ((word, flags) in stems) {
            if (out.size >= MAX_WORDS) break
            if (rules.forbidden(flags) || rules.needsAffix(flags)) continue
            out[word] = STEM_FREQUENCY
        }
        for ((word, flags) in stems) {
            if (out.size >= MAX_WORDS) break
            if (rules.forbidden(flags)) continue
            rules.forms(word, flags) { form ->
                if (out.size < MAX_WORDS) out.putIfAbsent(form, FORM_FREQUENCY)
            }
        }
        return out.map { (word, frequency) -> word to frequency }
    }

    private fun charsetOf(aff: ByteArray): Charset {
        val name = String(aff, Charsets.ISO_8859_1).lineSequence()
            .map { it.trim().split(WHITESPACE) }
            .firstOrNull { it.size >= 2 && it[0] == "SET" }
            ?.get(1)
            ?: return Charsets.UTF_8
        val javaName = CHARSET_ALIASES[name.lowercase()] ?: name
        return runCatching { Charset.forName(javaName) }.getOrDefault(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray, charset: Charset): String =
        String(bytes, charset).removePrefix(UTF8_BOM)

    private enum class FlagType { CHAR, LONG, NUM, UTF8 }

    /**
     * One affix rule line: take [strip] off the word's edge, put [add] there,
     * when the edge matches [condition]. [continuation] is the flags the new
     * form carries itself.
     */
    private class Row(
        val strip: String,
        val add: String,
        val condition: Condition,
        val continuation: List<String>,
    )

    private class Affix(val prefix: Boolean, val cross: Boolean, val rows: List<Row>)

    /**
     * A rule's condition: Hunspell's small regex of plain characters, `.` and
     * `[...]` / `[^...]` sets, one position each, matched against the word's
     * start (prefix) or end (suffix).
     */
    private class Condition(private val units: List<Slot?>) {
        class Slot(val chars: String, val negated: Boolean)

        fun matchesEnd(word: String): Boolean {
            if (word.length < units.size) return false
            val offset = word.length - units.size
            return units.indices.all { matches(units[it], word[offset + it]) }
        }

        fun matchesStart(word: String): Boolean {
            if (word.length < units.size) return false
            return units.indices.all { matches(units[it], word[it]) }
        }

        private fun matches(unit: Slot?, c: Char): Boolean =
            unit == null || (c in unit.chars) != unit.negated

        companion object {
            val ANY = Condition(emptyList())

            fun parse(text: String): Condition {
                if (text == ".") return ANY
                val units = ArrayList<Slot?>()
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    when {
                        c == '.' -> units.add(null)
                        c == '[' -> {
                            val close = text.indexOf(']', i + 1)
                            if (close < 0) return ANY
                            val body = text.substring(i + 1, close)
                            val negated = body.startsWith("^")
                            units.add(Slot(if (negated) body.substring(1) else body, negated))
                            i = close
                        }
                        else -> units.add(Slot(c.toString(), false))
                    }
                    i++
                }
                return Condition(units)
            }
        }
    }

    private class Rules(
        private val flagType: FlagType,
        private val aliases: List<List<String>>,
        private val affixes: Map<String, Affix>,
        private val forbiddenFlag: String?,
        private val needAffixFlag: String?,
        private val onlyInCompoundFlag: String?,
        private val ignored: String,
    ) {
        fun forbidden(flags: List<String>): Boolean =
            (forbiddenFlag != null && forbiddenFlag in flags) ||
                (onlyInCompoundFlag != null && onlyInCompoundFlag in flags)

        fun needsAffix(flags: List<String>): Boolean = needAffixFlag != null && needAffixFlag in flags

        /** A `.dic` line as its word and flags, or null for a comment or a blank. */
        fun entry(line: String): Pair<String, List<String>>? {
            if (line.isEmpty() || line[0] == '\t' || line[0] == '#') return null
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            var slash = -1
            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '/' && i > 0) {
                    slash = i
                    break
                }
                if (c.isWhitespace()) break
                i++
            }
            val wordPart = if (slash >= 0) trimmed.substring(0, slash) else trimmed.substring(0, i)
            val word = clean(wordPart.replace("\\/", "/"))
            if (word.isEmpty()) return null
            val flags = if (slash < 0) {
                emptyList()
            } else {
                val field = trimmed.substring(slash + 1).split(WHITESPACE, limit = 2)[0]
                flags(field)
            }
            return word to flags
        }

        /** Every form of [word] its [flags] allow, the stem itself not included. */
        fun forms(word: String, flags: List<String>, emit: (String) -> Unit) {
            val suffixed = ArrayList<Pair<String, Boolean>>()
            for (flag in flags) {
                val affix = affixes[flag]?.takeIf { !it.prefix } ?: continue
                for (row in affix.rows) {
                    val form = apply(affix, row, word) ?: continue
                    if (!needsAffix(row.continuation)) emit(form)
                    suffixed.add(form to affix.cross)
                    // One level of continuation: a suffix that takes suffixes
                    // of its own (`SFX A 0 ima/B`).
                    for (next in row.continuation) {
                        val inner = affixes[next]?.takeIf { !it.prefix } ?: continue
                        for (innerRow in inner.rows) {
                            val twice = apply(inner, innerRow, form) ?: continue
                            emit(twice)
                            suffixed.add(twice to (affix.cross && inner.cross))
                        }
                    }
                }
            }
            for (flag in flags) {
                val affix = affixes[flag]?.takeIf { it.prefix } ?: continue
                for (row in affix.rows) {
                    apply(affix, row, word)?.takeIf { !needsAffix(row.continuation) }?.let(emit)
                    if (!affix.cross) continue
                    for ((form, cross) in suffixed) {
                        if (cross) apply(affix, row, form)?.let(emit)
                    }
                }
            }
        }

        private fun apply(affix: Affix, row: Row, word: String): String? = if (affix.prefix) {
            if (!word.startsWith(row.strip) || !row.condition.matchesStart(word)) {
                null
            } else {
                (row.add + word.substring(row.strip.length)).takeIf { it.isNotEmpty() }
            }
        } else {
            if (!word.endsWith(row.strip) || !row.condition.matchesEnd(word)) {
                null
            } else {
                (word.substring(0, word.length - row.strip.length) + row.add).takeIf { it.isNotEmpty() }
            }
        }

        fun flags(field: String): List<String> {
            if (field.isEmpty()) return emptyList()
            if (aliases.isNotEmpty() && field.all { it.isDigit() }) {
                return aliases.getOrNull(field.toInt() - 1).orEmpty()
            }
            return parseFlags(field, flagType)
        }

        fun clean(text: String): String =
            if (ignored.isEmpty()) text else text.filterNot { it in ignored }

        companion object {
            fun parse(text: String): Rules {
                val lines = text.lines().map { it.substringBefore("\t#").trim() }
                var flagType = FlagType.CHAR
                // FLAG governs how every other flag field reads, so it is found first.
                for (line in lines) {
                    val parts = line.split(WHITESPACE)
                    if (parts.size >= 2 && parts[0] == "FLAG") {
                        flagType = when (parts[1].lowercase()) {
                            "long" -> FlagType.LONG
                            "num" -> FlagType.NUM
                            "utf-8" -> FlagType.UTF8
                            else -> FlagType.CHAR
                        }
                    }
                }
                val aliases = ArrayList<List<String>>()
                val affixes = HashMap<String, Affix>()
                var forbidden: String? = null
                var needAffix: String? = null
                var onlyInCompound: String? = null
                var ignored = ""
                var aliasHeaderSeen = false
                var i = 0
                while (i < lines.size) {
                    val parts = lines[i].split(WHITESPACE)
                    i++
                    if (parts.size < 2) continue
                    when (parts[0]) {
                        // The first AF line is the table's length; every one after it is an alias.
                        "AF" -> if (aliasHeaderSeen) {
                            aliases.add(parseFlags(parts[1], flagType))
                        } else {
                            aliasHeaderSeen = true
                        }
                        "FORBIDDENWORD" -> forbidden = parseFlags(parts[1], flagType).firstOrNull()
                        "NEEDAFFIX", "PSEUDOROOT" -> needAffix = parseFlags(parts[1], flagType).firstOrNull()
                        "ONLYINCOMPOUND" -> onlyInCompound = parseFlags(parts[1], flagType).firstOrNull()
                        "IGNORE" -> ignored = parts[1]
                        "PFX", "SFX" -> {
                            val count = parts.getOrNull(3)?.toIntOrNull()
                            if (parts.size < 4 || count == null) continue
                            val prefix = parts[0] == "PFX"
                            val flag = parts[1]
                            val rows = ArrayList<Row>()
                            repeat(count) {
                                val row = lines.getOrNull(i)?.split(WHITESPACE) ?: return@repeat
                                i++
                                if (row.size < 4 || row[0] != parts[0] || row[1] != flag) return@repeat
                                val strip = row[2].takeIf { it != "0" }.orEmpty()
                                val addField = row[3]
                                val slash = addField.indexOf('/')
                                val add = (if (slash >= 0) addField.substring(0, slash) else addField)
                                    .takeIf { it != "0" }.orEmpty()
                                val continuation = if (slash >= 0) {
                                    val field = addField.substring(slash + 1)
                                    if (aliases.isNotEmpty() && field.all { it.isDigit() }) {
                                        aliases.getOrNull(field.toInt() - 1).orEmpty()
                                    } else {
                                        parseFlags(field, flagType)
                                    }
                                } else {
                                    emptyList()
                                }
                                val condition = row.getOrNull(4)?.let(Condition::parse) ?: Condition.ANY
                                rows.add(Row(strip, add, condition, continuation))
                            }
                            // A flag defined twice (some files split a long
                            // table) keeps every row.
                            val held = affixes[flag]
                            affixes[flag] = Affix(prefix, parts[2] == "Y", held?.rows.orEmpty() + rows)
                        }
                    }
                }
                val ignoredChars = ignored
                val cleaned = if (ignoredChars.isEmpty()) {
                    affixes
                } else {
                    affixes.mapValues { (_, affix) ->
                        Affix(
                            affix.prefix,
                            affix.cross,
                            affix.rows.map { row ->
                                Row(
                                    row.strip.filterNot { it in ignoredChars },
                                    row.add.filterNot { it in ignoredChars },
                                    row.condition,
                                    row.continuation,
                                )
                            },
                        )
                    }
                }
                return Rules(flagType, aliases, cleaned, forbidden, needAffix, onlyInCompound, ignoredChars)
            }

            private fun parseFlags(field: String, type: FlagType): List<String> = when (type) {
                FlagType.CHAR -> field.map { it.toString() }
                FlagType.LONG -> field.chunked(2)
                FlagType.NUM -> field.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                FlagType.UTF8 -> buildList {
                    var i = 0
                    while (i < field.length) {
                        val next = field.offsetByCodePoints(i, 1)
                        add(field.substring(i, next))
                        i = next
                    }
                }
            }
        }
    }
}
