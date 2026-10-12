package com.wasimaster.wmkeyboard.core.layout

/**
 * The shipped symbol pages with Japanese or Chinese punctuation in place of the
 * marks those languages have no use for (issue #610).
 *
 * None of the Japanese or Chinese layouts writes a symbols page of its own, so
 * every one of them — Romaji, Flick, Pinyin, Zhuyin, Cangjie, Stroke, T9,
 * handwriting, and any custom layout under those languages — borrows the
 * default's. That page is built for Latin text: `¿` and `¡` on hold, ASCII
 * quotes, and no corner brackets or title marks anywhere, so `「ありがとう」`
 * and `《西游记》` could not be typed without copying the marks in.
 *
 * Rewritten by label rather than by position, the way [withLocalCurrency] is,
 * so an edited default page still gets its marks wherever the keys moved to,
 * and a key the edit removed simply is not rewritten. A symbols page the layout
 * wrote itself is never touched.
 *
 * The ASCII mark every rewritten key displaced leads its hold: numbers, URLs and
 * mixed-language text still need `(`, `"`, `:` and `?`, just less often than the
 * wide forms here. The `,` and `.` beside the spacebar stay ASCII on this page
 * for the reason the full-stop swap leaves `.` alone off the letters page
 * (issue #489): this is where `1,000.5` is typed. The wide forms lead their
 * holds instead.
 */
internal fun withCjkPunctuation(rows: List<List<Key>>, layer: LayoutLayer, langId: String): List<List<Key>> {
    val marks = when (langId.substringBefore('_')) {
        "ja" -> JapaneseMarks
        "zh", "yue" -> ChineseMarks
        else -> return rows
    }
    val table = when (layer) {
        LayoutLayer.SYMBOLS -> marks.symbols
        LayoutLayer.SYMBOLS_SHIFTED -> marks.symbolsShifted
        else -> return rows
    }
    return rows.map { row ->
        row.map { key ->
            if (key.action != KeyAction.Text || key.output != null) return@map key
            val swap = table[key.label] ?: return@map key
            key.copy(
                label = swap.label,
                longPress = (swap.longPress + if (swap.ownHold) emptyList() else key.longPress)
                    .filter { it != swap.label && it !in marks.dropped }
                    .distinct(),
            )
        }
    }
}

/**
 * One rewritten key: what it shows, and what its hold leads with. [ownHold]
 * drops the shipped key's hold instead of appending it, for the quote keys,
 * whose hold is otherwise both halves of every Latin quote pair.
 */
private data class Mark(val label: String, val longPress: List<String> = emptyList(), val ownHold: Boolean = false)

private class CjkMarks(
    val symbols: Map<String, Mark>,
    val symbolsShifted: Map<String, Mark>,
    /** Holds of the shipped page that mean nothing in this language. */
    val dropped: Set<String>,
)

/** `¿ ¡`: Spanish. `„ ‚ « » ‹ ›`: European quotes, crowding out the ones these languages use. */
private val LatinOnly = setOf("¡", "¿", "„", "‚", "«", "»", "‹", "›")

/**
 * Japanese: corner brackets on the first page, where the ASCII quotes were,
 * since `「」` is how Japanese quotes; `『』` and the rest of the bracket family
 * on the second. The comma and full stop are `、` and `。`.
 */
private val JapaneseMarks = CjkMarks(
    symbols = mapOf(
        "*" to Mark("・", listOf("*", "･", "·")),
        "\"" to Mark("「", listOf("『", "“", "‘", "\""), ownHold = true),
        "'" to Mark("」", listOf("』", "”", "’", "'"), ownHold = true),
        ":" to Mark("、", listOf("，", ":", "：")),
        ";" to Mark("：", listOf(":", ";", "；")),
        "!" to Mark("！", listOf("!", "‼")),
        "?" to Mark("？", listOf("?", "⁉")),
        "(" to Mark("（", listOf("(", "［", "[", "{", "<")),
        ")" to Mark("）", listOf(")", "］", "]", "}", ">")),
        "-" to Mark("-", listOf("_", "ー", "〜", "…", "‥", "—", "――")),
        "," to Mark(",", listOf("、", "，")),
        "." to Mark(".", listOf("。", "．")),
    ),
    symbolsShifted = mapOf(
        "©" to Mark("『", listOf("〈", "《")),
        "®" to Mark("』", listOf("〉", "》")),
        "™" to Mark("【", listOf("〔", "［")),
        "✓" to Mark("】", listOf("〕", "］")),
        "[" to Mark("〜", listOf("[", "～")),
        "]" to Mark("…", listOf("]", "‥")),
        "¶" to Mark("¶", listOf("©", "®", "™", "〒", "※")),
        "√" to Mark("√", listOf("✓", "✔", "✗")),
    ),
    dropped = LatinOnly,
)

/**
 * Chinese, for every region: the curved quotes Mainland text uses on the first
 * page, and the corner brackets Taiwan and Hong Kong use on the second next to
 * the title marks, so neither convention is a hold away from the other's.
 */
private val ChineseMarks = CjkMarks(
    symbols = mapOf(
        "*" to Mark("、", listOf("*", "·", "•")),
        "\"" to Mark("“", listOf("‘", "「", "『", "\""), ownHold = true),
        "'" to Mark("”", listOf("’", "」", "』", "'"), ownHold = true),
        ":" to Mark("：", listOf(":")),
        ";" to Mark("；", listOf(";")),
        "!" to Mark("！", listOf("!", "‼")),
        "?" to Mark("？", listOf("?", "⁉")),
        "(" to Mark("（", listOf("(", "【", "［", "[", "{", "<")),
        ")" to Mark("）", listOf(")", "】", "］", "]", "}", ">")),
        "-" to Mark("-", listOf("_", "——", "……", "～", "—", "·")),
        "," to Mark(",", listOf("，", "、")),
        "." to Mark(".", listOf("。", "……")),
    ),
    symbolsShifted = mapOf(
        "©" to Mark("《", listOf("〈")),
        "®" to Mark("》", listOf("〉")),
        "™" to Mark("「", listOf("『", "【")),
        "✓" to Mark("」", listOf("』", "】")),
        "[" to Mark("——", listOf("[", "—")),
        "]" to Mark("……", listOf("]", "…")),
        "¶" to Mark("¶", listOf("©", "®", "™", "※")),
        "√" to Mark("√", listOf("✓", "✔", "✗")),
    ),
    dropped = LatinOnly,
)
