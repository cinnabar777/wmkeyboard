package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.util.MultilingExport
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Reading a layout made in Multiling O Keyboard.
 *
 * Multiling O lets its users build layouts out of text, and a large part of
 * what people share for it is exactly that: layouts for scripts nobody else
 * ships (Tifinagh, Javanese, Shavian), MessagEase-style swipe pads, split
 * grids. None of it is a file. A layout is a block of text copied out of the
 * keyboard and pasted into a forum post, a gist or a README, and it comes in
 * three wrappers (see [MultilingExport]):
 *
 * - **DIY**: a JSON object, `{"title": …, "onScreen": {"main": [rows], "sym":
 *   [rows], …}}`. Every row is one string, and the grid is the whole keyboard,
 *   bottom row included.
 * - **DIY+**: `OK_Layout_Begin`, a `Name:` line, rows of keys, `OK_Layout_End`.
 *   The rows come in blocks of four: the letters with their row of digits on
 *   top, then the same four shifted, then the symbols, the AltGr page, the
 *   shifted AltGr page and the shifted symbols. Shift, delete and the bottom row
 *   are the other keyboard's to add, as with HeliBoard's text format.
 * - **A settings export**, `OK_Settings_Begin`: every DIY and DIY+ layout the
 *   user made, each filed under the language it was made for. That is the one
 *   form that states a language, and the only one that holds more than one
 *   layout, which is why [convertAll] exists.
 *
 * **Written from the shape of the text, not from Multiling's code.** The rules
 * below were worked out from what the app writes and what its users post, the
 * same footing the HeliBoard and Keys Cafe readers stand on.
 *
 * ### A row is a string of keys
 *
 * Each character is a key, `[]` makes the key before it one key wider, a space
 * is a gap, and anything else in square brackets is a key with a job:
 *
 * - `[SHIFT]`, `[DEL]`, `[SPACE]`, `[ENTER]`, `[LEFT]`, `[COPY]` and about
 *   eighty more named keys, upper or mixed case;
 * - `[U+0915]`, one character by its code point;
 * - `[MC:text]` / `[MC:text:label]`, a key that types more than one character;
 * - `[XK:abc]`, `a` with `b` and `c` on its press and hold;
 * - `[4D:c←↑→↓↖↗↘↙]`, a key that types `c` on a tap and one of up to eight
 *   characters or named keys on a swipe — MessagEase's key. `[1+4:…]` is the
 *   four-way form of the same and `[3+2:←c→↑↓]` puts the tap in the middle;
 * - `[MT:abc]`, a key that cycles through its letters on repeated taps;
 * - `[SYM:.]`, `[ALTGR:,]`, `[123:x]`: a key that types its character on a tap
 *   and opens that page on a swipe.
 *
 * Everything a row says that this keyboard has no key for is dropped and named
 * in the notes, never kept as a key that does nothing.
 *
 * ### Pages
 *
 * Unlike every other foreign format, these files carry more than the letters.
 * The symbols and shifted-symbols pages land on this layout's own; the AltGr,
 * 123 and Edit pages become pages of this layout ([KeyAction.LayerSwitch]),
 * reached from the same keys that reached them over there. A shifted page laid
 * out key for key like the page it shifts is folded into that page's
 * [Key.shiftLabel]s, which is how this keyboard spells a shifted page.
 */
object MultilingLayouts {

    /**
     * True when [text] is one of these: it carries a DIY+ or settings marker,
     * or a DIY layout's `onScreen` object, encoded or not.
     *
     * Cheap and string-only, and decided before any JSON is parsed. Both
     * markers are too specific to turn up anywhere else; `"onScreen"` is a
     * JSON key no other layout format has.
     */
    fun looksLikeMultiling(text: String): Boolean {
        if (text.isEmpty() || text.length > MultilingExport.MAX_LENGTH) return false
        if (text.contains(MultilingExport.LAYOUT_BEGIN) && MultilingExport.layoutBlocks(text).isNotEmpty()) return true
        if (text.contains(MultilingExport.SETTINGS_BEGIN)) {
            return MultilingExport.settings(text).orEmpty()
                .any { (key, value) -> key.isLayoutList() && value.isNotBlank() }
        }
        if (text.contains(ON_SCREEN)) return true
        return MultilingExport.looksEncoded(text) && MultilingExport.percentDecode(text).contains(ON_SCREEN)
    }

    /** The first layout in [text]; see [convertAll]. */
    fun convert(text: String, name: String): ConvertedLayout? = convertAll(text, name).firstOrNull()

    /**
     * Every layout in [text], in the order the text lists them. Empty when
     * there are none.
     *
     * A settings export lists its layouts by language, and each one comes back
     * with that language as its [ConvertedLayout.guessedLangId] when this app
     * has it, so a whole export can be stored without asking about each.
     * Never throws.
     */
    fun convertAll(text: String, name: String): List<ConvertedLayout> = runCatching {
        if (text.length > MultilingExport.MAX_LENGTH) return emptyList()
        MultilingExport.settings(text)?.let { return fromSettings(it) }
        val blocks = MultilingExport.layoutBlocks(text)
        if (blocks.isNotEmpty()) return blocks.mapNotNull { convertBody(it, name, langCode = null) }
        // Past the two markers only a DIY layout's JSON is left. Anything else
        // is not one of these, and reading it as DIY+ rows would turn a page of
        // prose into a layout.
        val decoded = if (MultilingExport.looksEncoded(text)) MultilingExport.percentDecode(text) else text
        if (!decoded.contains(ON_SCREEN)) return emptyList()
        listOfNotNull(convertBody(decoded, name, langCode = null))
    }.getOrDefault(emptyList())

    // ---- the three wrappers ----

    /**
     * The layouts a settings export carries. `DIYs.<lang>` names them, one per
     * line, and each one's body sits under `<name><lang>`. The landscape lists
     * (`DIYs..L<lang>`) name landscape copies of the same layouts and are
     * skipped: this keyboard sizes one grid for both.
     */
    private fun fromSettings(settings: Map<String, String>): List<ConvertedLayout> {
        val out = mutableListOf<ConvertedLayout>()
        for ((key, value) in settings) {
            if (!key.isLayoutList()) continue
            val lang = key.removePrefix(LAYOUT_LIST_PREFIX)
            for (layoutName in value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
                val body = settings[layoutName + lang] ?: continue
                convertBody(body, layoutName, lang)?.let(out::add)
            }
        }
        return out
    }

    private fun String.isLayoutList(): Boolean =
        startsWith(LAYOUT_LIST_PREFIX) && !startsWith(LANDSCAPE_LIST_PREFIX)

    private fun convertBody(body: String, name: String, langCode: String?): ConvertedLayout? {
        val declared = langCode?.let { code ->
            LanguageRegistry.all.firstOrNull { it.id.equals(code, ignoreCase = true) }?.id
        }
        return if (body.contains(ON_SCREEN)) {
            fromJson(body.substring(body.indexOf('{').coerceAtLeast(0)), name, declared)
        } else {
            fromText(body, name, declared)
        }
    }

    // ---- DIY: the JSON layout ----

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
    }

    private fun fromJson(text: String, fallbackName: String, langId: String?): ConvertedLayout? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val screen = root[ON_SCREEN_KEY] as? JsonObject ?: return null
        val title = (root[TITLE_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.ifEmpty { null }

        fun rowsOf(vararg names: String): List<String>? = names.firstNotNullOfOrNull { name ->
            (screen[name] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.takeIf { rows -> rows.any { it.isNotBlank() } }
        }

        val pages = Page.entries.associateWith { rowsOf(*it.jsonNames) }
        val shifted = Page.entries.associateWith { rowsOf(*it.jsonShiftedNames) }
        if (pages[Page.MAIN] == null) return null
        val defined = pages.filterValues { it != null }.keys
        val report = Report()

        fun page(page: Page): List<List<Key>>? {
            val rows = pages[page] ?: return null
            val ctx = Ctx(page, defined, report, rows)
            val built = rows.map { parseRow(it, ctx) }
            val shiftRows = shifted[page]?.let { up -> up.map { parseRow(it, Ctx(page, defined, Report(), up)) } }
            return mergeShifted(built, shiftRows, report).filter { it.isNotEmpty() }
        }

        val letters = page(Page.MAIN)?.takeIf { it.isNotEmpty() } ?: return null
        val extra = LinkedHashMap<String, LayerSpec>()
        for (p in Page.entries) {
            if (p == Page.MAIN) continue
            val rows = page(p)?.takeIf { it.isNotEmpty() } ?: continue
            extra[p.layerKey] = LayerSpec(rows = if (p.named) withWayBack(rows) else rows, bottomRowAsLaidOut = true)
            if (p.named) report.pagesKept = true
        }
        // The file carries its own frame, the way a Keys Cafe grid does, so it
        // keeps it, unless the author took the space bar or the delete key out.
        val keys = letters.flatten()
        val complete = keys.any { it.action == KeyAction.Space } && keys.any { it.action == KeyAction.Delete }
        return ForeignLayouts.assemble(
            rows = letters,
            name = fallbackName,
            source = ForeignSource.MULTILING,
            report = report,
            langId = langId,
            houseFrame = !complete,
            extraLayers = extra,
        )?.named(title ?: fallbackName)
    }

    // ---- DIY+: the text layout ----

    private fun fromText(body: String, fallbackName: String, langId: String?): ConvertedLayout? {
        var name: String? = null
        val lines = body.lines().map { it.trimEnd('\r') }.toMutableList()
        // The header lines come first. Only the name means anything here: the
        // others pick Multiling's own key sizes and composing modes.
        while (lines.isNotEmpty()) {
            val head = lines.first()
            val header = HeaderPrefixes.firstOrNull { head.startsWith(it) } ?: break
            if (header == NAME_HEADER) name = head.removePrefix(NAME_HEADER).trim().ifEmpty { null }
            lines.removeAt(0)
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        if (lines.none { it.isNotBlank() }) return null

        fun block(index: Int): List<String>? =
            lines.drop(index * BLOCK).take(BLOCK).takeIf { rows -> rows.any { it.isNotBlank() } }

        val defined = buildSet {
            add(Page.MAIN)
            if (block(BLOCK_SYM) != null) add(Page.SYM)
            if (block(BLOCK_SYM_SHIFTED) != null) add(Page.SYM_SHIFTED)
            if (block(BLOCK_ALTGR) != null) add(Page.ALTGR)
        }
        val report = Report()
        fun parse(rows: List<String>?, page: Page): List<List<Key>>? =
            rows?.let { r -> r.map { parseRow(it, Ctx(page, defined, report, r)) } }

        val main = parse(block(BLOCK_MAIN), Page.MAIN) ?: return null
        // An empty shifted row means "the capitals of the row above", which is
        // what a key with no shiftLabel already does here.
        val mainShifted = parse(block(BLOCK_MAIN_SHIFTED), Page.MAIN)
        val merged = mergeShifted(main, mainShifted, report)

        // The top row is the row of digits that Multiling draws over the
        // letters, unless the author put letters there.
        val top = merged.first()
        val numberRow = top.takeIf { row -> row.isNotEmpty() && row.none { it.isLetterKey() } }
        val letterRows = (if (numberRow != null) merged.drop(1) else merged).filter { it.isNotEmpty() }
        if (letterRows.isEmpty()) return null
        if (numberRow != null) report.numberRowKept = true

        val extra = LinkedHashMap<String, LayerSpec>()
        val symbolsBottom = shippedSymbolsBottomRow()
        parse(block(BLOCK_SYM), Page.SYM)?.let { rows ->
            val toShifted = if (Page.SYM_SHIFTED in defined) symbolsKey(SHIFTED_SYMBOLS_LABEL) else null
            extra[LayoutLayer.SYMBOLS.key] = LayerSpec(textPage(rows, toShifted, symbolsBottom, digits = true))
        }
        parse(block(BLOCK_SYM_SHIFTED), Page.SYM_SHIFTED)?.let { rows ->
            extra[LayoutLayer.SYMBOLS_SHIFTED.key] =
                LayerSpec(textPage(rows, symbolsKey(SYMBOLS_LABEL), symbolsBottom, digits = false))
        }
        parse(block(BLOCK_ALTGR), Page.ALTGR)?.let { rows ->
            val withShift = mergeShifted(rows, parse(block(BLOCK_ALTGR_SHIFTED), Page.ALTGR), report)
            val shiftKey = if (withShift.flatten().any { it.shiftLabel != null }) houseShiftKey() else null
            extra[Page.ALTGR.layerKey] = LayerSpec(textPage(withShift, shiftKey, symbolsBottom, digits = false))
            report.pagesKept = true
        }

        // Shift, delete and the bottom row are the reading keyboard's to add,
        // so the house frame goes on, and then the comma key takes the swipe
        // to AltGr that Multiling's own comma key has.
        val framed = withHouseStructure(renormalizeWidths(letterRows)).let { rows ->
            if (Page.ALTGR in defined) withAltGrOnComma(rows) else rows
        }
        return ForeignLayouts.assemble(
            rows = framed,
            name = fallbackName,
            source = ForeignSource.MULTILING,
            report = report,
            langId = langId,
            numberRow = numberRow,
            houseFrame = false,
            extraLayers = extra,
            bottomRowAsLaidOut = false,
        )?.named(name ?: fallbackName)
    }

    /**
     * A DIY+ page that is not the letters: its four rows of characters, with a
     * delete key on the last of them and [shiftKey] in front of it, and this
     * keyboard's own symbols bottom row under them, the way Multiling adds its
     * own. [digits] puts the shipped row of digits on top of a symbols page
     * that has none, because Multiling's digits sit on its number row, which is
     * always on, and this keyboard's number row may not be.
     */
    private fun textPage(
        rows: List<List<Key>>,
        shiftKey: Key?,
        bottom: List<Key>,
        digits: Boolean,
    ): List<List<Key>> {
        var body = rows.filter { it.isNotEmpty() }
        if (body.isEmpty()) return listOf(bottom)
        val keys = body.flatten()
        val last = body.last()
        val framedLast = buildList {
            val hasShift = keys.any { it.action == KeyAction.Shift || it.action == KeyAction.Symbols }
            if (shiftKey != null && !hasShift) add(shiftKey)
            addAll(last)
            if (keys.none { it.action == KeyAction.Delete }) add(houseDeleteKey())
        }
        body = body.dropLast(1) + listOf(framedLast)
        if (digits && keys.none { key -> (key.output ?: key.label).any { it.isDigit() } }) {
            shippedSymbolsDigitRow()?.let { body = listOf(it) + body }
        }
        return (body + listOf(bottom)).take(MaxRowsPerLayer)
    }

    /**
     * The house bottom row's comma, given the swipe to the AltGr page that
     * Multiling's comma key has (its `[ALTGR:,]`). On its hold too, where a
     * swipe is hard to find.
     */
    private fun withAltGrOnComma(rows: List<List<Key>>): List<List<Key>> {
        val bottom = rows.lastOrNull() ?: return rows
        val index = bottom.indexOfFirst {
            it.action == KeyAction.Text && (it.role == KeyRole.Comma || it.label == ",")
        }
        if (index < 0) return rows
        val switch = KeyAlternate(KeyAction.LayerSwitch(Page.ALTGR.layerKey), ALTGR_LABEL)
        val comma = bottom[index].let {
            it.copy(
                flickActions = EdgeArms.associateWith { switch },
                actionAlternates = it.actionAlternates + switch,
            )
        }
        return rows.dropLast(1) + listOf(bottom.toMutableList().also { it[index] = comma })
    }

    /** A named page's way back to the letters, when the file gave it none. */
    private fun withWayBack(rows: List<List<Key>>): List<List<Key>> {
        val keys = rows.flatten()
        if (keys.any { it.action == KeyAction.Letters || it.action == KeyAction.Symbols }) return rows
        return rows.appendToLastRow(Key(LETTERS_LABEL, action = KeyAction.Letters, width = WIDE))
    }

    // ---- shifted pages ----

    /**
     * [base] with [shifted] folded in as shift labels, key for key. A shifted
     * page laid out any other way than its base cannot be folded in, and is
     * counted; the base page's keys then shift to capitals the usual way.
     */
    private fun mergeShifted(
        base: List<List<Key>>,
        shifted: List<List<Key>>?,
        report: Report,
    ): List<List<Key>> {
        if (shifted == null) return base
        val fits = shifted.size <= base.size && shifted.indices.all { i ->
            shifted[i].isEmpty() || shifted[i].size == base[i].size
        }
        if (!fits) {
            report.shiftedPagesUnmerged++
            return base
        }
        return base.mapIndexed { i, row ->
            val up = shifted.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return@mapIndexed row
            row.mapIndexed { j, key -> key.shiftedBy(up[j]) }
        }
    }

    private fun Key.shiftedBy(up: Key): Key {
        if (action != KeyAction.Text || up.action != KeyAction.Text) return this
        val text = output ?: label
        val upText = up.output ?: up.label
        val shift = upText.takeIf { it.isNotEmpty() && it != text.uppercase() }
        val flickUp = flick.mapNotNull { (direction, arm) ->
            up.flick[direction]?.takeIf { it.isNotEmpty() && it != arm.uppercase() }?.let { direction to it }
        }.toMap()
        if (shift == null && flickUp.isEmpty()) return this
        return copy(shiftLabel = shift ?: shiftLabel, flickShift = flickShift + flickUp)
    }

    // ---- rows ----

    /** What a page of the file is, and where it lands here. */
    private enum class Page(
        val layerKey: String,
        val named: Boolean,
        val jsonNames: Array<String>,
        val jsonShiftedNames: Array<String>,
    ) {
        MAIN(LayoutLayer.LETTERS.key, false, arrayOf("main"), arrayOf("shifted")),
        SYM(LayoutLayer.SYMBOLS.key, false, arrayOf("sym"), emptyArray()),
        SYM_SHIFTED(LayoutLayer.SYMBOLS_SHIFTED.key, false, arrayOf("symShifted"), emptyArray()),
        ALTGR("altgr", true, arrayOf("altGr"), arrayOf("altGrShifted")),

        // `123` is the page's old name; Multiling reads both.
        NUM("num", true, arrayOf("num", "123"), arrayOf("numShifted", "123Shifted")),
        EDIT("edit", true, arrayOf("edit"), arrayOf("editShifted")),
        FN(LayoutLayer.FN.key, false, arrayOf("fn"), arrayOf("fnShifted")),
    }

    /**
     * What a row is read against: the page it is on (a `[SYM]` key on the
     * symbols page goes back to the letters), the pages the file has (a key to
     * a page the file left out goes to this keyboard's nearest one), and the
     * page's raw rows, for the one key whose meaning depends on its
     * neighbours: see [toolKey].
     */
    private class Ctx(
        val page: Page,
        val defined: Set<Page>,
        val report: Report,
        rows: List<String>,
    ) {
        private val raw = rows.joinToString("\n").uppercase(Locale.ROOT)
        val hasSymbolsKey = raw.contains("[SYM]")
        val hasWayBack = hasSymbolsKey || raw.contains("[LOCK]")
    }

    private fun parseRow(row: String, ctx: Ctx): List<Key> {
        val keys = mutableListOf<Key>()
        for (item in tokenize(row)) {
            if (item is Item.Widen) {
                if (keys.isNotEmpty()) keys[keys.lastIndex] = keys.last().let { it.copy(width = it.width + 1f) }
                continue
            }
            keyOf(item, ctx)?.let(keys::add)
        }
        return keys
    }

    private fun keyOf(item: Item, ctx: Ctx): Key? = when (item) {
        Item.Widen -> null
        is Item.Text -> when (item.text) {
            "" -> null
            GAP -> gapKey()
            else -> textKey(item.text, ctx.report)
        }
        is Item.Token -> tokenKey(item, ctx)
    }

    private fun tokenKey(token: Item.Token, ctx: Ctx): Key? {
        val arg = token.arg
        return when (token.name) {
            "4D", "1+4" -> flickKey(arg.orEmpty(), FourWay, tapIndex = 0, ctx)
            "3+2" -> flickKey(arg.orEmpty(), ThreePlusTwo, tapIndex = 1, ctx)
            "XK" -> holdKey(arg.orEmpty(), ctx)
            "MT" -> multitapKey(arg.orEmpty())
            "MC" -> textOrActionOf(token, ctx)?.let { arm -> armKey(arm) }
            "SYM", "ALTGR", "123", "FN", "EDIT" -> if (arg == null) {
                actionKeyOf(token.name, ctx)
            } else {
                pageCharKey(PageNames.getValue(token.name), arg, ctx)
            }
            else -> if (arg == null) actionKeyOf(token.name, ctx) else parameterKey(token, ctx)
        }
    }

    /** A named key with a parameter that is not a page key's character. */
    private fun parameterKey(token: Item.Token, ctx: Ctx): Key? = when (token.name) {
        // The language and layout keys name a slot in Multiling's own lists,
        // which mean nothing here: the language key is the nearest thing.
        "LANG", "LAYOUT" -> {
            ctx.report.approximated++
            actionKey(KeyAction.LanguageSwitch)
        }
        "EMJ" -> actionKey(KeyAction.Emoji)
        // Text styles: the fancy-text tool is where this keyboard keeps them.
        "TR" -> {
            ctx.report.approximated++
            actionKey(KeyAction.Tool(ToolbarTool.FANCY))
        }
        else -> {
            ctx.report.droppedLabels += "[${token.name}:${token.arg}]"
            null
        }
    }

    /**
     * A `[SYM:.]`-style key: types its character on a tap and opens [page] on a
     * swipe. Here the swipe is every edge of the key, as over there, and the
     * page also sits on its hold, where it can be found.
     */
    private fun pageCharKey(page: Page, arg: String, ctx: Ctx): Key? {
        val items = tokenize(arg).filter { it !is Item.Widen }
        val text = items.singleOrNull() as? Item.Text
        // `[123:[MT]]` and friends: the tap is itself a named key, so the key
        // is the page key alone.
        if (text == null || text.text == GAP) return switchKey(page, ctx)
        val switch = switchOf(page, ctx)
        val key = textKey(text.text, ctx.report) ?: return null
        return key.copy(
            flickActions = EdgeArms.associateWith { switch },
            actionAlternates = listOf(switch),
        )
    }

    // ---- gesture keys ----

    /** One thing a key or a key's swipe does: type text, or run an action. */
    private sealed interface Arm {
        data class Text(val text: String, val label: String = text) : Arm
        data class Action(val alternate: KeyAlternate) : Arm
    }

    /** What a single item inside a gesture key means, or null for an empty slot. */
    private fun armOf(item: Item, ctx: Ctx): Arm? = when (item) {
        Item.Widen -> null
        is Item.Text -> if (item.text == GAP) null else Arm.Text(normalizeForScript(item.text))
        is Item.Token -> when {
            item.name == "MC" -> textOrActionOf(item, ctx)
            item.name in PageNames && item.arg != null ->
                tokenize(item.arg).firstOrNull()?.let { armOf(it, ctx) }
            item.arg == null -> actionOf(item.name, ctx)?.let { (action, label) ->
                Arm.Action(KeyAlternate(action, label))
            }
            else -> parameterKey(item, ctx)?.let { Arm.Action(KeyAlternate(it.action, it.label)) }
        }
    }

    /**
     * A swipe key. [order] names the direction each item after the tap goes
     * to; [tapIndex] is which item is the tap.
     *
     * A tap left empty (`[4D: [LEFT][UP][RIGHT][DOWN]]`, a cursor pad) repeats
     * the last swipe over there. Here a key has to do something on a tap, so
     * the first arm stands in for it.
     */
    private fun flickKey(arg: String, order: List<FlickDirection?>, tapIndex: Int, ctx: Ctx): Key? {
        val items = tokenize(arg).filter { it !is Item.Widen }
        val arms = items.map { armOf(it, ctx) }
        val tap = arms.getOrNull(tapIndex) ?: arms.firstOrNull { it != null } ?: return null
        val directed = order.zip(arms.filterIndexed { i, _ -> i != tapIndex })
            .mapNotNull { (direction, arm) -> if (direction != null && arm != null) direction to arm else null }
        val base = armKey(tap)
        val text = directed.mapNotNull { (d, arm) -> (arm as? Arm.Text)?.let { d to it.text } }.toMap()
        val actions = directed.mapNotNull { (d, arm) -> (arm as? Arm.Action)?.let { d to it.alternate } }.toMap()
        // Text arms only work on a key whose tap types text, and action arms
        // only where the key's own drag or hold is free; see Key.takesFlickActions.
        val keptText = if (base.action == KeyAction.Text) text else emptyMap()
        val keptActions = if (base.takesFlickActions()) actions else emptyMap()
        ctx.report.gesturesDropped += (text.size - keptText.size) + (actions.size - keptActions.size)
        return base.copy(flick = keptText, flickActions = keptActions)
    }

    /** `[XK:abc]`: the first item is the tap, the rest are its press and hold. */
    private fun holdKey(arg: String, ctx: Ctx): Key? {
        val arms = tokenize(arg).filter { it !is Item.Widen }.map { armOf(it, ctx) }
        val tap = arms.firstOrNull() ?: return null
        val rest = arms.drop(1).filterNotNull()
        return armKey(tap).copy(
            longPress = rest.mapNotNull { (it as? Arm.Text)?.text }.distinct(),
            actionAlternates = rest.mapNotNull { (it as? Arm.Action)?.alternate },
        )
    }

    /**
     * `[MT:abc2]`: the first tap types `a`, the next `b`, and so on round, the
     * feature-phone way. Drawn with all of its letters, which is what tells
     * the user the key cycles.
     */
    private fun multitapKey(arg: String): Key? {
        val letters = tokenize(arg).mapNotNull { (it as? Item.Text)?.text?.takeIf { t -> t != GAP } }
            .map(::normalizeForScript)
        val first = letters.firstOrNull() ?: return null
        val all = letters.joinToString("")
        return Key(
            label = all,
            output = first.takeIf { it != all },
            multitap = letters.drop(1),
        )
    }

    /**
     * `[MC:text]` or `[MC:text:label]`. Three things hide under it: text of
     * more than one character, a named key under a label of the author's own
     * (`[MC:[LANG:01]:🇺🇸]`), and a shortcut built from modifier keys
     * (`[MC:[CTRL]z:undo]`). The first two come across; a shortcut does when
     * it is one modifier set and one letter or digit, as a key press. Dates and
     * times (`{{MMM d}}`) are Multiling's own text macros and are dropped.
     */
    private fun textOrActionOf(token: Item.Token, ctx: Ctx): Arm? {
        val raw = token.arg ?: return null
        val split = lastTopLevelColon(raw)
        val body = if (split >= 0) raw.substring(0, split) else raw
        val label = if (split >= 0) raw.substring(split + 1).ifEmpty { null } else null
        if (body.contains(DATE_MACRO)) {
            ctx.report.droppedLabels += "[MC:$raw]"
            return null
        }
        val items = tokenize(body).filter { it !is Item.Widen }
        val tokens = items.filterIsInstance<Item.Token>()
        val text = items.filterIsInstance<Item.Text>().joinToString("") { it.text }
        if (tokens.isEmpty()) {
            if (text.isEmpty()) return null
            val clean = normalizeForScript(text)
            return Arm.Text(clean, label?.let(::normalizeForScript) ?: clean)
        }
        val modifiers = tokens.map { it.name }
        if (modifiers.all { it in ShortcutModifiers } && text.length == 1) {
            val code = keyCodeFor(text[0])
            if (code != null) {
                val meta = modifiers.fold(0) { acc, name -> acc or ShortcutModifiers.getValue(name) }
                return Arm.Action(KeyAlternate(KeyAction.SendKey(code, meta), label ?: text))
            }
        }
        val single = tokens.singleOrNull()
        if (single != null && text.isBlank()) {
            val arm = armOf(single, ctx) as? Arm.Action ?: return null
            return Arm.Action(arm.alternate.copy(label = label ?: arm.alternate.label))
        }
        ctx.report.droppedLabels += "[MC:$raw]"
        return null
    }

    private fun armKey(arm: Arm): Key = when (arm) {
        is Arm.Text -> Key(label = arm.label, output = arm.text.takeIf { it != arm.label })
        is Arm.Action -> Key(
            label = arm.alternate.label.ifEmpty { arm.alternate.action.fallbackLabel() },
            action = arm.alternate.action,
        )
    }

    // ---- named keys ----

    private fun actionKeyOf(name: String, ctx: Ctx): Key? {
        val (action, label) = actionOf(name, ctx) ?: return null
        return Key(label = label, action = action, width = 1f)
    }

    private fun actionKey(action: KeyAction): Key = Key(label = action.fallbackLabel(), action = action)

    /**
     * The action a named key means here, with its label, or null when there is
     * none. Counted as approximate where the action is the nearest thing
     * rather than the same thing; named in the notes where there is nothing.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun actionOf(name: String, ctx: Ctx): Pair<KeyAction, String>? {
        val report = ctx.report
        fun plain(action: KeyAction) = action to action.fallbackLabel()
        fun near(action: KeyAction): Pair<KeyAction, String> {
            report.approximated++
            return plain(action)
        }
        fun edit(op: TextEditAction) = plain(KeyAction.Edit(op))
        fun tool(tool: ToolbarTool) = plain(KeyAction.Tool(tool))
        return when (name) {
            "SHIFT" -> shiftOf(ctx)
            "CAPS" -> plain(KeyAction.CapsLock)
            "LOCK" -> if (ctx.page == Page.MAIN) {
                plain(KeyAction.CapsLock)
            } else {
                report.approximated++
                KeyAction.Letters to LETTERS_LABEL
            }
            "DEL" -> plain(KeyAction.Delete)
            // Word delete is a swipe on the delete key here.
            "WDEL", "|←DEL" -> near(KeyAction.Delete)
            "FDEL", "DEL→" -> plain(KeyAction.ForwardDelete)
            "SPACE" -> KeyAction.Space to " "
            "ENTER" -> plain(KeyAction.Enter)
            "TAB" -> plain(KeyAction.SendKey(KEYCODE_TAB))
            "ESC" -> plain(KeyAction.SendKey(KEYCODE_ESCAPE))
            "LEFT" -> edit(TextEditAction.LEFT)
            "RIGHT" -> edit(TextEditAction.RIGHT)
            "UP" -> edit(TextEditAction.UP)
            "DOWN" -> edit(TextEditAction.DOWN)
            "HOME" -> edit(TextEditAction.HOME)
            "END" -> edit(TextEditAction.END)
            "PGUP" -> edit(TextEditAction.PAGE_UP)
            "PGDN" -> edit(TextEditAction.PAGE_DOWN)
            "W.LEFT" -> edit(TextEditAction.WORD_LEFT)
            "W.RIGHT" -> edit(TextEditAction.WORD_RIGHT)
            "S.WORD" -> edit(TextEditAction.SELECT_WORD)
            "ALL" -> edit(TextEditAction.SELECT_ALL)
            "COPY" -> edit(TextEditAction.COPY)
            "PASTE" -> edit(TextEditAction.PASTE)
            "CUT" -> edit(TextEditAction.CUT)
            "UNDO" -> tool(ToolbarTool.UNDO)
            "REDO" -> tool(ToolbarTool.REDO)
            "CTRL" -> plain(KeyAction.Mod(ModifierKey.CTRL))
            "ALT" -> plain(KeyAction.Mod(ModifierKey.ALT))
            "INSERT" -> KeyAction.SendKey(KEYCODE_INSERT) to "Ins"
            "BREAK", "PAUSE" -> KeyAction.SendKey(KEYCODE_BREAK) to "Pause"
            "SCRLCK" -> KeyAction.SendKey(KEYCODE_SCROLL_LOCK) to "ScrLk"
            "NUMLCK" -> KeyAction.SendKey(KEYCODE_NUM_LOCK) to "NumLk"
            "SYSRQ" -> KeyAction.SendKey(KEYCODE_SYSRQ) to "SysRq"
            "EMOJI" -> plain(KeyAction.Emoji)
            "VOICE" -> tool(ToolbarTool.VOICE)
            "CLIPBOARD" -> tool(ToolbarTool.CLIPBOARD)
            "TRANSLATE" -> tool(ToolbarTool.TRANSLATE)
            "HIDE" -> tool(ToolbarTool.HIDE_KEYBOARD)
            "SIZE" -> tool(ToolbarTool.RESIZE)
            "SEARCH" -> near(KeyAction.Tool(ToolbarTool.WEB_SEARCH))
            "CURSOR" -> near(KeyAction.Tool(ToolbarTool.TRACKPAD))
            "CORRECT" -> near(KeyAction.Tool(ToolbarTool.AUTOCORRECT))
            "IME" -> plain(KeyAction.InputMethodPicker)
            "LANGS", "LANG" -> plain(KeyAction.LanguageSwitch)
            "LAYOUTS" -> near(KeyAction.LanguageSwitch)
            "SYM" -> switchPair(Page.SYM, ctx)
            "ALTGR" -> switchPair(Page.ALTGR, ctx)
            "123" -> switchPair(Page.NUM, ctx)
            "EDIT" -> switchPair(Page.EDIT, ctx)
            "FN" -> switchPair(Page.FN, ctx)
            "TOOL" -> toolKey(ctx)
            else -> {
                FunctionKeyNumbers[name]?.let { n -> return KeyAction.SendKey(KEYCODE_F1 + n - 1) to "F$n" }
                if (name in FancyTextKeys) return near(KeyAction.Tool(ToolbarTool.FANCY))
                report.droppedLabels += "[$name]"
                null
            }
        }
    }

    /**
     * What shift is on each page. On the letters and on the named pages it is
     * shift. The symbols pages have no shift here: their second page is
     * reached with the symbols key, which is what shift on Multiling's symbols
     * page does.
     */
    private fun shiftOf(ctx: Ctx): Pair<KeyAction, String> = when (ctx.page) {
        Page.SYM -> KeyAction.Symbols to SHIFTED_SYMBOLS_LABEL
        Page.SYM_SHIFTED -> KeyAction.Symbols to SYMBOLS_LABEL
        else -> KeyAction.Shift to KeyAction.Shift.fallbackLabel()
    }

    /**
     * Multiling's tool key opens its own menu of tools, which this keyboard
     * keeps on its toolbar instead. It sits where this keyboard puts ?123, so
     * that is what it becomes on a letters page with no other symbols key, and
     * the key back to the letters on a page with no other way back. Anywhere
     * else it is dropped.
     */
    private fun toolKey(ctx: Ctx): Pair<KeyAction, String>? = when {
        ctx.page == Page.MAIN && !ctx.hasSymbolsKey -> {
            ctx.report.approximated++
            KeyAction.Symbols to SYMBOLS_LABEL
        }
        ctx.page != Page.MAIN && !ctx.hasWayBack -> {
            ctx.report.approximated++
            KeyAction.Letters to LETTERS_LABEL
        }
        else -> {
            ctx.report.droppedLabels += "[TOOL]"
            null
        }
    }

    private fun switchKey(page: Page, ctx: Ctx): Key? =
        switchPair(page, ctx).let { (action, label) -> Key(label = label, action = action) }

    private fun switchOf(page: Page, ctx: Ctx): KeyAlternate =
        switchPair(page, ctx).let { (action, label) -> KeyAlternate(action, label) }

    /**
     * The key to [page] from the page [ctx] is on. A key to the page it is on
     * goes back to the letters, the way pressing a page key twice does over
     * there. A page the file does not have goes to this keyboard's nearest.
     */
    private fun switchPair(page: Page, ctx: Ctx): Pair<KeyAction, String> {
        val here = ctx.page
        if (page == here || (page == Page.SYM && here == Page.SYM_SHIFTED)) {
            return KeyAction.Letters to LETTERS_LABEL
        }
        return when (page) {
            Page.MAIN -> KeyAction.Letters to LETTERS_LABEL
            Page.SYM, Page.SYM_SHIFTED -> KeyAction.Symbols to SYMBOLS_LABEL
            Page.FN -> KeyAction.Fn to KeyAction.Fn.fallbackLabel()
            Page.ALTGR -> if (page in ctx.defined) {
                KeyAction.LayerSwitch(page.layerKey) to ALTGR_LABEL
            } else {
                // Multiling's built-in AltGr page of accents and signs; the
                // second symbols page is the nearest thing here.
                ctx.report.approximated++
                KeyAction.Symbols to SYMBOLS_LABEL
            }
            Page.NUM -> if (page in ctx.defined) {
                KeyAction.LayerSwitch(page.layerKey) to NUM_LABEL
            } else {
                KeyAction.Numpad to KeyAction.Numpad.fallbackLabel()
            }
            Page.EDIT -> if (page in ctx.defined) {
                KeyAction.LayerSwitch(page.layerKey) to EDIT_LABEL
            } else {
                KeyAction.Tool(ToolbarTool.TEXT_EDIT) to ""
            }
        }
    }

    // ---- plain keys ----

    private fun textKey(text: String, report: Report): Key? {
        if (text.isEmpty()) return null
        val clean = normalizeForScript(text)
        if (clean != text) report.normalized++
        return Key(label = clean)
    }

    /** A space in a row: a gap, the way Multiling draws it. */
    private fun gapKey(): Key = Key(label = "", action = KeyAction.None)

    private fun Key.isLetterKey(): Boolean =
        action == KeyAction.Text && (output ?: label).any { it.isLetter() }

    private fun symbolsKey(label: String): Key = Key(label, action = KeyAction.Symbols, width = WIDE)
    private fun houseShiftKey(): Key = Key("⇧", action = KeyAction.Shift, width = WIDE)
    private fun houseDeleteKey(): Key = Key("⌫", action = KeyAction.Delete, width = WIDE)

    private fun shippedSymbolsBottomRow(): List<Key> =
        BuiltInLayouts.default.layer(LayoutLayer.SYMBOLS)?.rows?.lastOrNull()
            ?: listOf(
                Key(LETTERS_LABEL, action = KeyAction.Letters, width = WIDE),
                Key(" ", action = KeyAction.Space, width = SPACE_WIDTH),
                Key("⏎", action = KeyAction.Enter, width = WIDE),
            )

    private fun shippedSymbolsDigitRow(): List<Key>? =
        BuiltInLayouts.default.layer(LayoutLayer.SYMBOLS)?.rows?.firstOrNull()
            ?.takeIf { row -> row.all { key -> (key.output ?: key.label).all { it.isDigit() } } }

    private fun ConvertedLayout.named(name: String): ConvertedLayout =
        copy(layout = layout.copy(name = name.trim().ifEmpty { layout.name }))

    // ---- the tokenizer ----

    /** One thing a row string says. */
    private sealed interface Item {
        /** One key's worth of text: a character, or a code point spelled out. */
        data class Text(val text: String) : Item

        /** `[NAME]` or `[NAME:arg]`, the name upper-cased. */
        data class Token(val name: String, val arg: String?) : Item

        /** `[]`: the key before is one key wider. */
        data object Widen : Item
    }

    /**
     * [row] as items. Brackets nest (`[4D:+[LEFT][UP]]`).
     *
     * Only what Multiling itself reads as a key name is one: anything else in
     * brackets is the characters it is made of, so a symbols row written
     * `[]()<>` is six keys, as it is over there. For the same reason `[]`
     * widens the key before it only straight after a closing bracket
     * (`[SPACE][][]`); anywhere else it is a `[` key and a `]` key.
     */
    private fun tokenize(row: String): List<Item> {
        val items = mutableListOf<Item>()
        var i = 0
        while (i < row.length) {
            val c = row[i]
            if (c == '[') {
                val close = matchingBracket(row, i)
                val inner = if (close > i) row.substring(i + 1, close) else null
                val item = when {
                    inner == null -> null
                    inner.isEmpty() -> Item.Widen.takeIf { i > 0 && row[i - 1] == ']' && items.isNotEmpty() }
                    else -> tokenItem(inner)
                }
                if (item != null) {
                    if (item != Skip) items += item
                    i = close + 1
                    continue
                }
            }
            val end = if (c.isHighSurrogate() && i + 1 < row.length) i + 2 else i + 1
            items += Item.Text(row.substring(i, end))
            i = end
        }
        return items
    }

    /**
     * What `[inner]` means: a key, a character spelled by its code point, or
     * [Skip] for the markers that draw nothing (`[CO:2]` picks Multiling's key
     * colour, `[Sep:]` separates nothing). Null when it is not a name Multiling
     * reads, and so is plain text.
     */
    private fun tokenItem(inner: String): Item? {
        val colon = firstTopLevelColon(inner)
        val name = (if (colon >= 0) inner.substring(0, colon) else inner).uppercase(Locale.ROOT)
        val arg = if (colon >= 0) inner.substring(colon + 1) else null
        if (name.startsWith(CODE_POINT_PREFIX) && arg == null) {
            val code = name.removePrefix(CODE_POINT_PREFIX).toIntOrNull(HEX)
            return if (code != null && Character.isValidCodePoint(code)) {
                Item.Text(String(Character.toChars(code)))
            } else {
                null
            }
        }
        return when {
            name == "SEP" || name == "CO" -> Skip
            arg == null && name in BracketKeys -> Item.Text(BracketKeys.getValue(name))
            arg == null && name == "COLON" -> Item.Text(":")
            arg == null && name in KnownNames -> Item.Token(name, null)
            arg != null && name in KnownPrefixes -> Item.Token(name, arg)
            else -> null
        }
    }

    /** A marker that takes its place in the row and makes no key. */
    private val Skip: Item = Item.Text("")

    private fun matchingBracket(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun firstTopLevelColon(text: String): Int = topLevelColons(text).firstOrNull() ?: -1
    private fun lastTopLevelColon(text: String): Int = topLevelColons(text).lastOrNull() ?: -1

    private fun topLevelColons(text: String): List<Int> {
        var depth = 0
        val out = mutableListOf<Int>()
        text.forEachIndexed { i, c ->
            when (c) {
                '[' -> depth++
                ']' -> depth = (depth - 1).coerceAtLeast(0)
                ':' -> if (depth == 0) out += i
            }
        }
        return out
    }

    /** The key code a shortcut letter or digit sends, or null for anything else. */
    private fun keyCodeFor(c: Char): Int? = when (val lower = c.lowercaseChar()) {
        in 'a'..'z' -> KEYCODE_A + (lower - 'a')
        in '0'..'9' -> KEYCODE_0 + (lower - '0')
        else -> null
    }

    // ---- constants ----

    private const val ON_SCREEN_KEY = "onScreen"
    private const val ON_SCREEN = "\"$ON_SCREEN_KEY\""
    private const val TITLE_KEY = "title"

    private const val LAYOUT_LIST_PREFIX = "DIYs."
    private const val LANDSCAPE_LIST_PREFIX = "DIYs.."

    private const val NAME_HEADER = "Name:"
    private val HeaderPrefixes = listOf(NAME_HEADER, "Type:", "Sub:", "BottomRow:", "BigKey", "DIY123")

    private const val BLOCK = 4
    private const val BLOCK_MAIN = 0
    private const val BLOCK_MAIN_SHIFTED = 1
    private const val BLOCK_SYM = 2
    private const val BLOCK_ALTGR = 3
    private const val BLOCK_ALTGR_SHIFTED = 4
    private const val BLOCK_SYM_SHIFTED = 5

    private const val GAP = " "
    private const val DATE_MACRO = "{{"
    private const val CODE_POINT_PREFIX = "U+"
    private const val HEX = 16

    private const val WIDE = 1.5f
    private const val SPACE_WIDTH = 4f

    // Key names, printed on keys rather than translated, like `?123`.
    private const val SYMBOLS_LABEL = "?123"
    private const val SHIFTED_SYMBOLS_LABEL = "=\\<"
    private const val LETTERS_LABEL = "ABC"
    private const val ALTGR_LABEL = "AltGr"
    private const val NUM_LABEL = "123"
    private const val EDIT_LABEL = "✎"

    private val PageNames = mapOf(
        "SYM" to Page.SYM,
        "ALTGR" to Page.ALTGR,
        "123" to Page.NUM,
        "FN" to Page.FN,
        "EDIT" to Page.EDIT,
    )

    private val BracketKeys = mapOf(
        "LB" to "[",
        "LEFTBRACKET" to "[",
        "RB" to "]",
        "RIGHTBRACKET" to "]",
    )

    /** The four edges, which is where a page key's swipe goes over there. */
    private val EdgeArms = listOf(FlickDirection.LEFT, FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN)

    /**
     * A `[4D:…]` key's items after the tap, in the order the format gives
     * them: the four edges anticlockwise from the left, then the corners.
     */
    private val FourWay = listOf(
        FlickDirection.LEFT, FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN,
        FlickDirection.UP_LEFT, FlickDirection.UP_RIGHT, FlickDirection.DOWN_RIGHT, FlickDirection.DOWN_LEFT,
    )

    /** `[3+2:←c→↑↓]`: left, (the tap), right, up, down. */
    private val ThreePlusTwo = listOf(FlickDirection.LEFT, FlickDirection.RIGHT, FlickDirection.UP, FlickDirection.DOWN)

    private val FunctionKeyNumbers: Map<String, Int> = (1..12).associateBy { "F$it" }

    /** Multiling's three text-style keys, spelled in the style they apply. */
    private val FancyTextKeys = setOf("εχ๑тïς", "ｗｉｄｅ", "ⓐⓑⓒ").map { it.uppercase(Locale.ROOT) }.toSet()

    /**
     * Every key name Multiling reads in brackets, upper-cased: the ones mapped
     * in [actionOf] and the ones it drops by name. A bracketed word that is
     * not one of these is text over there, and is text here.
     */
    private val KnownNames: Set<String> = setOf(
        "SHIFT", "CAPS", "LOCK", "DEL", "WDEL", "|←DEL", "FDEL", "DEL→", "SPACE", "ENTER", "TAB", "ESC",
        "LEFT", "RIGHT", "UP", "DOWN", "HOME", "END", "PGUP", "PGDN", "W.LEFT", "W.RIGHT", "S.WORD",
        "ALL", "COPY", "PASTE", "CUT", "UNDO", "REDO", "CTRL", "ALT", "INSERT", "BREAK", "PAUSE",
        "SCRLCK", "NUMLCK", "SYSRQ", "EMOJI", "VOICE", "CLIPBOARD", "TRANSLATE", "HIDE", "SIZE",
        "SEARCH", "CURSOR", "CORRECT", "IME", "LANGS", "LANG", "LAYOUTS", "SYM", "ALTGR", "123",
        "EDIT", "FN", "TOOL", "INFO", "SHIFT-X", "SYM-X", "PREDICT", "ACAP", "PUNCT", "INSP",
        "S.COLOR", "Q.IMPORT", "Q.EXPORT", "SAVE", "MT", "T9", "Ã→A",
    ) + FunctionKeyNumbers.keys + FancyTextKeys

    /** The names Multiling reads with a parameter after a colon. */
    private val KnownPrefixes: Set<String> = setOf(
        "4D", "1+4", "3+2", "XK", "MT", "MC", "SYM", "ALTGR", "123", "FN", "EDIT",
        "LANG", "LAYOUT", "EMJ", "APP", "TR",
    )

    private val ShortcutModifiers = mapOf(
        "CTRL" to META_CTRL_ON,
        "ALT" to META_ALT_ON,
        "SHIFT" to META_SHIFT_ON,
    )
}

// Android key codes and meta flags, as numbers: this module is layout data and
// takes no android.view import (see KeyActions.kt).
private const val KEYCODE_0 = 7
private const val KEYCODE_A = 29
private const val KEYCODE_TAB = 61
private const val KEYCODE_ESCAPE = 111
private const val KEYCODE_SCROLL_LOCK = 116
private const val KEYCODE_SYSRQ = 120
private const val KEYCODE_BREAK = 121
private const val KEYCODE_INSERT = 124
private const val KEYCODE_F1 = 131
private const val KEYCODE_NUM_LOCK = 143
private const val META_SHIFT_ON = 0x1
private const val META_ALT_ON = 0x2
private const val META_CTRL_ON = 0x1000
