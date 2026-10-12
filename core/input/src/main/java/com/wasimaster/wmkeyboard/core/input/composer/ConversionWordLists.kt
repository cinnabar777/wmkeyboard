package com.wasimaster.wmkeyboard.core.input.composer

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Reads the word lists a user imported for a conversion language as rows of its
 * conversion table, so they reach the candidate strip (#633).
 *
 * An imported list used to land only in the word tries the Latin strip reads,
 * which a conversion IME never asks: a Chinese list did nothing at all, and
 * without the pinyin pack Chinese still converted nothing. A conversion table
 * needs the reading as well as the word, so what a list is worth here depends
 * on whether its lines carry one. Every format people actually have for these
 * languages does:
 *
 * - CC-CEDICT and CC-Canto: `繁體 简体 [pin1 yin1] {jyut6 ping3} /gloss/`;
 * - Rime's `.dict.yaml`: `word<TAB>reading<TAB>weight`, the reading spaced
 *   into syllables, the weight a count or a percentage, below a YAML header;
 * - this app's own pack format, `reading<TAB>word<TAB>freq`;
 * - Mozc and Google Japanese Input's user dictionaries: `よみ<TAB>単語<TAB>品詞`.
 *
 * So rather than a parser per format, each line is read by what its fields
 * look like: the word is the field written in the language's own script, the
 * reading is whatever spells a valid reading in the table's alphabet, and the
 * frequency is the first number. A line with no reading is skipped, which is
 * also what drops a YAML header.
 */
object ConversionWordLists {

    /** The alphabet a conversion table's readings are spelled in. */
    enum class Reading { PINYIN, JYUTPING, KANA }

    /** The languages whose imported lists join a conversion table: one per [Reading]. */
    val languages: List<String> = listOf("zh", "yue", "ja")

    /** The reading space [langId]'s imported lists convert in; null for a language that does not convert. */
    fun readingFor(langId: String): Reading? = when (langId) {
        "zh" -> Reading.PINYIN
        "yue" -> Reading.JYUTPING
        "ja" -> Reading.KANA
        else -> null
    }

    /**
     * Where an imported row's frequency lands on the table it joins: a row
     * with no count of its own takes [typical], and a list's own counts are
     * spread up to [top]. Read off the pack the rows are merged into, since
     * the packs count on very different scales.
     */
    class Scale(val typical: Int, val top: Int) {
        companion object {
            /** For a language with no pack on the device: the pinyin pack's own shape. */
            val DEFAULT = Scale(typical = 40, top = 1000)

            fun of(table: ConversionDictionary): Scale {
                if (table.isEmpty) return DEFAULT
                val top = table.frequencyQuantile(1.0).coerceAtLeast(1)
                return Scale(table.frequencyQuantile(0.5).coerceIn(1, top), top)
            }
        }
    }

    /** One line read, its count not yet scaled. */
    private class Parsed(val reading: String, val word: String, val count: Long?, val percent: Int?)

    /** The rows of one list, scaled onto [scale]. */
    fun rows(lines: Sequence<String>, reading: Reading, scale: Scale = Scale.DEFAULT): List<ConversionDictionary.Row> {
        val parsed = ArrayList<Parsed>()
        var maxCount = 0L
        for (raw in lines) {
            val line = parse(raw, reading) ?: continue
            parsed.add(line)
            line.count?.let { maxCount = maxOf(maxCount, it) }
        }
        val spread = ln(1.0 + maxCount)
        return parsed.map { line ->
            val count = line.count
            when {
                // A list's own counts keep their order, compressed onto the
                // table's range so its commonest word sits with the pack's.
                count != null && spread > 0 -> ConversionDictionary.Row(
                    line.reading, line.word,
                    (scale.top * ln(1.0 + count) / spread).roundToInt().coerceIn(1, scale.top),
                    raises = true,
                )
                // Rime's percentages say how often a character takes this
                // reading rather than how common it is: 的 is `de 99%` and
                // `di 1%`. They scale the typical row down, never up.
                line.percent != null -> ConversionDictionary.Row(
                    line.reading, line.word, (scale.typical * line.percent / PERCENT).coerceAtLeast(1),
                )
                else -> ConversionDictionary.Row(line.reading, line.word, scale.typical)
            }
        }
    }

    /** How many rows [lines] hold, for the settings screen's count. */
    fun count(lines: Sequence<String>, reading: Reading): Int = lines.count { parse(it, reading) != null }

    /** One line, or null when it carries no word with a reading. */
    private fun parse(raw: String, reading: Reading): Parsed? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        cedict(line, reading)?.let { return it }
        // Tabs when the line has them, since a Rime reading is spaced into
        // syllables; whitespace otherwise.
        val fields = if ('\t' in line) line.split('\t') else line.split(WHITESPACE)
        var word: String? = null
        val spelled = StringBuilder()
        var count: Long? = null
        var percent: Int? = null
        for (field in fields.map { it.trim() }) {
            if (field.isEmpty()) continue
            val number = field.toLongOrNull()
            if (number != null) {
                if (count == null && percent == null) count = number.coerceAtLeast(0)
                continue
            }
            if (field.endsWith('%')) {
                val pct = field.dropLast(1).toDoubleOrNull()
                if (pct != null) {
                    if (count == null && percent == null) percent = pct.roundToInt().coerceIn(0, PERCENT)
                    continue
                }
            }
            // In kana both columns are kana, and the reading comes first.
            val asReading = if (reading == Reading.KANA && spelled.isNotEmpty()) null else readingOf(field, reading)
            if (asReading != null && (reading != Reading.KANA || word == null)) {
                spelled.append(asReading)
                continue
            }
            if (word == null && isWord(field)) word = field
        }
        val w = word ?: return null
        if (spelled.isEmpty()) return null
        return Parsed(spelled.toString(), w, count, percent)
    }

    /**
     * A CC-CEDICT or CC-Canto line: Traditional, Simplified, the pinyin in
     * brackets and, in CC-Canto, the Jyutping in braces.
     *
     * Pinyin takes the Simplified form, the one the pinyin pack lists — the
     * Traditional output setting turns it into the other — and Jyutping takes
     * the Traditional one its own pack is written in.
     */
    private fun cedict(line: String, reading: Reading): Parsed? {
        val match = CEDICT.find(line) ?: return null
        val (traditional, simplified, pinyin, jyutping) = match.destructured
        val word = if (reading == Reading.JYUTPING) traditional else simplified
        val spelled = when (reading) {
            Reading.PINYIN -> readingOf(pinyin, reading)
            Reading.JYUTPING -> jyutping.takeIf { it.isNotBlank() }?.let { readingOf(it, reading) }
            Reading.KANA -> null
        } ?: return null
        if (!isWord(word)) return null
        return Parsed(spelled, word, null, null)
    }

    /**
     * [field] as a reading in [reading]'s alphabet, or null when it is not
     * one. Pinyin loses its tone digits and tone marks and spells ü as `v`, the
     * way the packs do; Jyutping loses its tone digits; katakana is read as
     * the hiragana the kana pack is keyed by. Syllable spaces and apostrophes
     * go — the packs key a reading as one run of letters.
     */
    private fun readingOf(field: String, reading: Reading): String? = when (reading) {
        Reading.PINYIN -> latinReading(
            field.lowercase()
                .replace("u:", "v")
                .replace(Regex("[üǖǘǚǜ]"), "v"),
        )
        Reading.JYUTPING -> latinReading(field.lowercase())
        Reading.KANA -> kanaReading(field)
    }

    private fun latinReading(field: String): String? {
        val bare = Normalizer.normalize(field, Normalizer.Form.NFD)
        val out = StringBuilder(bare.length)
        for (c in bare) {
            when {
                c in 'a'..'z' -> out.append(c)
                // Tone digits, tone marks and the separators between syllables.
                c.isDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c == ' ' || c == '\'' || c == '-' || c == '·' || c == '・' || c == ',' || c == '，' -> Unit
                else -> return null
            }
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    private fun kanaReading(field: String): String? {
        val out = StringBuilder(field.length)
        for (c in field) {
            when (c) {
                in HIRAGANA, 'ー', 'ゔ' -> out.append(c)
                in KATAKANA -> out.append(c - KATAKANA_TO_HIRAGANA)
                ' ', '　' -> Unit
                else -> return null
            }
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /** A word in a language with its own script: something past ASCII in it, and short enough to be one. */
    private fun isWord(field: String): Boolean =
        field.length <= MAX_WORD_LENGTH && field.any { it.code > ASCII_END } && field.none { it.isWhitespace() }

    private val CEDICT = Regex("""^(\S+)\s+(\S+)\s+\[([^\]]*)]\s*(?:\{([^}]*)})?""")
    private val WHITESPACE = Regex("\\s+")
    private val HIRAGANA = 'ぁ'..'ゖ'
    private val KATAKANA = 'ァ'..'ヶ'
    private const val KATAKANA_TO_HIRAGANA = 0x60
    private const val ASCII_END = 0x7F
    private const val MAX_WORD_LENGTH = 32
    private const val PERCENT = 100
}
