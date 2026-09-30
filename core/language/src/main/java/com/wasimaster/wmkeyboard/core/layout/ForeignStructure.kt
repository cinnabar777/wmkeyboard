package com.wasimaster.wmkeyboard.core.layout

/**
 * A converted grid, given the frame every layout this app ships has.
 *
 * None of the three foreign formats carries that frame. HeliBoard's text format
 * cannot spell a shift, delete, space or enter key at all; FlorisBoard's
 * character grids and HeliBoard's JSON leave all four to the keyboard that reads
 * them; FUTO writes them only when the file moves them, and its bottom row, when
 * it has one, is `?123 , ␣ . ⏎` with no language key. Left to [LayoutSpec.repair]
 * alone, an import came out with no shift key, no ?123 and no 🌐, and a bare
 * `⌫ ␣ ⏎` appended to the end of the last letter row: a grid that typed, and that
 * nobody would keep.
 *
 * So the grid is finished the way the shipped ones are (see
 * `ba_bashkir.wmlayout.json`):
 *
 * - the last letter row is `⇧ … ⌫`, both 1.5 wide;
 * - the bottom row is `?123 , 🌐 ␣ . ⏎`, the spacebar 4 wide.
 *
 * What the file said is kept wherever it said something. A file that placed its
 * own delete key keeps it where it is, and gets no shift key added: FUTO's own
 * rule is that placing either one is how a layout says it wants no automatic
 * pair, so a grid with a delete and no shift asked for no shift. A bottom row the
 * file wrote is read rather than thrown away: its comma and full stop keep their
 * glyphs and their press-and-hold letters (a Bengali `।`, an Urdu `۔`, an Arabic
 * `،`), a key the house row has no slot for keeps its place by the spacebar, and
 * a letter on it moves up to the last letter row so it stays reachable. A
 * functional key the letter rows already have (a PC grid's enter on the home
 * row) is not added a second time.
 *
 * A grid this cannot read safely is handed back untouched, and [LayoutSpec.repair]
 * still guarantees it types: a key spanning rows (moving keys would slide them
 * under it), a spacebar anywhere but the last row, and a last row with more
 * letters on it than a bottom row carries.
 */
internal fun withHouseStructure(rows: List<List<Key>>): List<List<Key>> {
    if (rows.isEmpty() || rows.any { row -> row.any { it.rowSpan > 1 } }) return rows
    val spaceRows = rows.indices.filter { i -> rows[i].any { it.action == KeyAction.Space } }
    val authoredBottom = when {
        spaceRows.isEmpty() -> null
        spaceRows == listOf(rows.lastIndex) -> rows.last()
        else -> return rows
    }
    val letters = if (authoredBottom == null) rows else rows.dropLast(1)
    if (letters.isEmpty()) return rows
    val slots = if (authoredBottom == null) {
        BottomSlots()
    } else {
        bottomSlotsOf(authoredBottom) ?: return rows
    }
    // Room for the bottom row is the one limit this can hit on a sane file: a
    // grid already at the row cap keeps its shape and repair does the rest.
    if (authoredBottom == null && rows.size >= MaxRowsPerLayer) return rows

    val framed = withShiftAndDelete(
        letters = withKeysOnLastRow(letters, slots.letters),
        elsewhere = slots.others,
    )
    return framed + listOf(houseBottomRow(framed.flatten(), slots))
}

/**
 * What a file's own bottom row holds, sorted into the house row's slots.
 *
 * [comma] and [period] are the file's keys for those two slots, or null for
 * the house ones. [letters] move up to the last letter row. [others] are the
 * functional keys the house row has no slot for, which keep a seat by the
 * spacebar.
 */
private class BottomSlots(
    val comma: Key? = null,
    val period: Key? = null,
    val letters: List<Key> = emptyList(),
    val others: List<Key> = emptyList(),
)

/**
 * Sorts [row] into [BottomSlots], or null when it holds more letters than a
 * bottom row does and is better left as the file wrote it.
 *
 * A key tagged as the comma or full stop takes that slot. Otherwise the first
 * punctuation key before the spacebar is the comma and the last one after it is
 * the full stop, which is where every one of these formats puts them: FUTO's
 * `$symbols , $space . $enter`, a Lakota row with an apostrophe before its
 * full stop, a Uyghur one with a ZWNJ key there.
 */
private fun bottomSlotsOf(row: List<Key>): BottomSlots? {
    val space = row.indexOfFirst { it.action == KeyAction.Space }
    val text = row.indices.filter { row[it].action == KeyAction.Text }
    fun untagged(i: Int) = row[i].role == null && !row[i].isLetter()
    val comma = text.firstOrNull { row[it].role == KeyRole.Comma }
        ?: text.firstOrNull { it < space && untagged(it) }
    val period = text.lastOrNull { row[it].role == KeyRole.Period && it != comma }
        ?: text.lastOrNull { it > space && untagged(it) && it != comma }
    val letters = text.filter { it != comma && it != period }.map { row[it] }
    if (letters.size > MAX_BOTTOM_ROW_LETTERS) return null
    return BottomSlots(
        comma = comma?.let { row[it] },
        period = period?.let { row[it] },
        letters = letters,
        // One key wide, like every seat on the house row: the file's width is
        // a share of a row that no longer exists.
        others = row
            .filter { it.action != KeyAction.Text && it.action !in HouseRowActions }
            .map { it.copy(width = 1f) },
    )
}

/**
 * [keys] onto the end of the last row, ahead of a delete key that ends it, so a
 * letter moved up from the bottom row does not land on the far side of ⌫.
 */
private fun withKeysOnLastRow(rows: List<List<Key>>, keys: List<Key>): List<List<Key>> {
    if (keys.isEmpty()) return rows
    val last = rows.last()
    val row = if (last.lastOrNull()?.action == KeyAction.Delete) {
        last.dropLast(1) + keys.map { it.copy(width = 1f) } + last.last()
    } else {
        last + keys.map { it.copy(width = 1f) }
    }
    return rows.dropLast(1) + listOf(row)
}

/**
 * The last letter row as `⇧ … ⌫`, unless the file placed a delete key itself.
 * [elsewhere] is the rest of the grid, which counts for "placed" too.
 */
private fun withShiftAndDelete(letters: List<List<Key>>, elsewhere: List<Key>): List<List<Key>> {
    val all = letters.flatten() + elsewhere
    if (all.any { it.action == KeyAction.Delete }) return letters
    val hasShift = all.any { it.action == KeyAction.Shift || it.action == KeyAction.CapsLock }
    val row = listOfNotNull(if (hasShift) null else houseShift()) + letters.last() + houseDelete()
    return letters.dropLast(1) + listOf(row)
}

/**
 * `?123 , 🌐 ␣ . ⏎`, with the file's own comma and full stop in their slots and
 * whatever else its bottom row had just before the spacebar. A key [above]
 * already has is not drawn twice: a functional key by its action, and the
 * comma and full stop by their slot or their character, unless the file put
 * its own in the bottom row.
 */
private fun houseBottomRow(above: List<Key>, slots: BottomSlots): List<Key> {
    val present = above.map { it.action }.toSet()
    fun has(label: String, role: KeyRole) =
        above.any { it.action == KeyAction.Text && (it.role == role || (it.output ?: it.label) == label) }
    return buildList {
        if (KeyAction.Symbols !in present) add(Key("?123", action = KeyAction.Symbols, width = WIDE_KEY))
        if (slots.comma != null || !has(",", KeyRole.Comma)) {
            add(slotKey(slots.comma, ",", KeyRole.Comma, CommaAlternates))
        }
        if (KeyAction.LanguageSwitch !in present) add(Key("🌐", action = KeyAction.LanguageSwitch))
        addAll(slots.others)
        add(
            Key(
                " ",
                action = KeyAction.Space,
                width = (SPACE_WIDTH - slots.others.size).coerceAtLeast(MIN_SPACE_WIDTH),
            ),
        )
        if (slots.period != null || !has(".", KeyRole.Period)) {
            add(slotKey(slots.period, ".", KeyRole.Period, PeriodAlternates))
        }
        if (KeyAction.Enter !in present) add(Key("⏎", action = KeyAction.Enter, width = WIDE_KEY))
    }
}

/** The file's key for a punctuation slot, or the house one, tagged and one wide. */
private fun slotKey(given: Key?, label: String, role: KeyRole, alternates: List<String>): Key {
    val key = given ?: Key(label)
    return key.copy(
        role = role,
        width = 1f,
        longPress = key.longPress.ifEmpty { alternates.filter { it != (key.output ?: key.label) } },
    )
}

private fun Key.isLetter(): Boolean =
    (output ?: label).any { Character.isLetter(it) || Character.getType(it) in MarkTypes }

private fun houseShift() = Key("⇧", action = KeyAction.Shift, width = WIDE_KEY)

private fun houseDelete() = Key("⌫", action = KeyAction.Delete, width = WIDE_KEY)

/**
 * The bottom-row keys the house row supplies itself, so a file's own copy is
 * not kept beside it. A gap goes too: the house row has no hole in it.
 */
private val HouseRowActions: Set<KeyAction> = setOf(
    KeyAction.Symbols,
    KeyAction.Letters,
    KeyAction.LanguageSwitch,
    KeyAction.Space,
    KeyAction.Enter,
    KeyAction.None,
)

private val MarkTypes = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
)

// The alternates `ba_bashkir.wmlayout.json` gives the two slots. The shipped
// grids add a script's own full stop to the period's list; an import does not
// know its language yet, so it gets the ones every script shares.
private val CommaAlternates = listOf("!", "?")
private val PeriodAlternates = listOf("…", ",", "?", "!", ":", ";")

private const val WIDE_KEY = 1.5f
private const val SPACE_WIDTH = 4f
private const val MIN_SPACE_WIDTH = 2f

/** More letters than this and the last row is a letter row with a spacebar in it. */
private const val MAX_BOTTOM_ROW_LETTERS = 3
