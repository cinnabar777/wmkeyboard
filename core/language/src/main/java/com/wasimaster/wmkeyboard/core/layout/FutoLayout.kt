package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Reading a FUTO Keyboard layout.
 *
 * FUTO's layouts are YAML, and its own are published as a repository of nearly
 * a thousand files. The document is a name and a list of rows, each row a list
 * of keys, and a key may be a bare string, a `[key, …moreKeys]` list or an
 * object with a type.
 *
 * ```yaml
 * name: "QWERTY"
 * rows:
 *   - letters: q w e r t y u i o p
 *   - letters: [[a, ą], s, d]
 *   - letters: $shift z x c v b n m $delete
 *   - bottom:  $symbols $space $enter
 * ```
 *
 * **Written from the published format, not from FUTO's source.** FUTO Keyboard
 * is under its own source-available licence and this app is MIT; what is
 * transcribed here is a file format, the same footing as the FlorisBoard and
 * HeliBoard readers beside it. Test fixtures are hand-written for the same
 * reason.
 *
 * ### The key specs are the ones already read
 *
 * FUTO's keyboard descends from AOSP's, so its key specs are AOSP's: `a|b` for
 * a label that types something else, `!code/`, `!icon/`, and the same
 * `!autoColumnOrder!4` popup markers. [parseForeignLabel] reads all of it
 * already, and that is why this file is as short as it is.
 *
 * ### The layer boundary is the same as everywhere else
 *
 * Only the letters come across. The symbols and number layers belong to the app
 * that read the file, and an omitted layer inherits this one's, which is what
 * someone moving one grid over actually wants. FUTO's own `layoutSetOverrides`
 * names other layouts by id and is reported rather than followed: those ids mean
 * nothing here.
 */
object FutoLayouts {

    /** What the import picker accepts. `.yaml`, and whatever a provider calls it. */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/yaml",
        "text/yaml",
        "text/x-yaml",
        "text/plain",
        "application/octet-stream",
    )

    /** Well above the longest layout in FUTO's own repository. */
    const val MAX_LENGTH = 512 * 1024

    /**
     * True when [text] looks like one of these rather than the JSON or plain
     * text the other readers take.
     *
     * The import screen dispatches on this, and it has to be decided before
     * anything is parsed: a YAML parser accepts a JSON document happily, so
     * asking it first would take every FlorisBoard layout as well.
     */
    fun looksLikeFutoLayout(text: String): Boolean {
        var sawName = false
        var sawRows = false
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.startsWith("name:")) sawName = true
            if (line.startsWith("rows:")) sawRows = true
            if (sawName && sawRows) return true
        }
        return false
    }

    /**
     * Converts [text]. Null when it is not one of these, or holds no keys.
     *
     * Never throws: the parser is bounded and every shape that is not a key is
     * skipped rather than rejected, because a layout with one odd row is still
     * worth importing.
     */
    fun convert(text: String, name: String): ConvertedLayout? {
        if (text.length > MAX_LENGTH) return null
        val root = asMap(load(text)) ?: return null
        val rowsValue = root["rows"] as? List<*> ?: return null
        val report = Report()
        val overrides = customWidths(root["overrideWidths"])
        val numberRowAlways = string(root["numberRowMode"])
            .equals(NUMBER_ROW_ALWAYS, ignoreCase = true)

        val rows = mutableListOf<List<Key>>()
        for (rowValue in rowsValue) {
            val row = asMap(rowValue) ?: continue
            val kind = RowKind.entries.firstOrNull { row.containsKey(it.field) } ?: continue
            // This app draws its own number row from a setting, so a row the
            // file leaves optional would be a second one. A layout that
            // *insists* on its own (the PC arrangements, which put backtick and
            // the bracket keys up there) keeps it.
            if (kind == RowKind.NUMBERS && !numberRowAlways) {
                report.numberRowDropped = true
                continue
            }
            val keys = keysOf(row[kind.field], report, overrides, rowAttributes(row))
            if (keys.isNotEmpty()) rows += keys
        }
        val sized = distributeGrowth(rows)
        return ForeignLayouts.assemble(
            rows = sized,
            name = string(root["name"]).ifBlank { name },
            source = ForeignSource.FUTO_YAML,
            report = report,
            langId = declaredLanguage(root["languages"]),
        )
    }

    // ---- rows ----

    private enum class RowKind(val field: String) {
        NUMBERS("numbers"),
        LETTERS("letters"),
        BOTTOM("bottom"),
    }

    /**
     * A row's keys, from either shape it may take: a space-separated string, or
     * a list of key entries.
     */
    private fun keysOf(
        value: Any?,
        report: Report,
        overrides: Map<String, Float>,
        rowAttributes: Map<String, Any?>,
    ): List<Key> {
        val entries: List<Any?> = when (value) {
            is String -> value.split(' ', '\t').filter { it.isNotEmpty() }
            is List<*> -> value
            else -> return emptyList()
        }
        return entries.mapNotNull { keyOf(it, report, overrides, rowAttributes) }
    }

    /**
     * One key.
     *
     * Three shapes, all of which the format's own examples use side by side: a
     * bare spec, `[spec, …moreKeys]`, and an object with a `type`.
     */
    private fun keyOf(
        value: Any?,
        report: Report,
        overrides: Map<String, Float>,
        inherited: Map<String, Any?>,
    ): Key? = when (value) {
        is String -> specKey(value, emptyList(), inherited, report, overrides)
        is List<*> -> {
            val head = value.firstOrNull()
            val moreKeys = value.drop(1).mapNotNull { it?.toString() }
            when (head) {
                is String -> specKey(head, moreKeys, inherited, report, overrides)
                // A nested object with its own popup list is legal and rare;
                // the object's own moreKeys win, because they are the more
                // specific statement.
                else -> keyOf(head, report, overrides, inherited)
            }
        }
        else -> asMap(value)?.let { objectKey(it, report, overrides, inherited) }
    }

    /** A key written as an AOSP spec, with whatever popup list came with it. */
    private fun specKey(
        spec: String,
        moreKeys: List<String>,
        attributes: Map<String, Any?>,
        report: Report,
        overrides: Map<String, Float>,
    ): Key? {
        Templates[spec]?.let { template ->
            return templateKey(template, attributes, report, overrides)
        }
        if (spec.startsWith(TEMPLATE_PREFIX)) {
            // A template this build does not know: named, never silently
            // turned into a key that types its own name.
            report.droppedLabels += spec
            return null
        }
        val parsed = parseForeignLabel(spec)
        if (parsed.dropped) {
            report.droppedLabels += spec
            return null
        }
        if (parsed.approximate) report.approximated++
        if (parsed.restyled) report.restyled++
        val popups = popupsOf(moreKeys)
        val label = parsed.label.ifEmpty { parsed.output.orEmpty() }
        if (label.isEmpty() && parsed.action == KeyAction.Text) return null
        return Key(
            label = if (parsed.action == KeyAction.Text) normalizeForScript(label) else "",
            output = parsed.output?.let(::normalizeForScript)?.takeIf { it != label },
            action = parsed.action,
            role = parsed.role,
            longPress = popups.letters,
            alternateColumns = popups.columns,
        ).withAttributes(attributes, overrides, report)
    }

    /** A key written as an object: a type, and whatever that type takes. */
    private fun objectKey(
        obj: Map<String, Any?>,
        report: Report,
        overrides: Map<String, Float>,
        inherited: Map<String, Any?>,
    ): Key? {
        val attributes = inherited + asMap(obj["attributes"]).orEmpty()
        val type = string(obj["type"]).lowercase()
        // The types with a body of their own are read first, and only then is
        // the template table asked. The three keys that take a `fallbackKey`
        // are spelled the same as the templates that have none, so asking the
        // table first turned `{type: contextual, fallbackKey: ","}` — a key
        // that is perfectly convertible — into a dropped one.
        return when (type) {
            TYPE_BASE, "" -> {
                val spec = string(obj["spec"])
                val moreKeys = moreKeysOf(obj["moreKeys"])
                specKey(spec, moreKeys, attributes, report, overrides)
            }
            // The shift states of one key. This keyboard holds two, so the
            // resting one and the shifted one are kept and the rest counted,
            // exactly as the FlorisBoard reader does for its selectors.
            //
            // `shiftedManually` is the shifted one when `shifted` is absent.
            // It is how a caseless script writes its second layer (Korean ㅂ
            // over ㅃ, Khmer, Myanmar, Bengali Akkhor): a layout that does not
            // auto-shift has only the manual state, and reading `shifted`
            // alone lost that whole layer.
            TYPE_CASE -> {
                val resting = CaseBranches.firstNotNullOfOrNull { obj[it] }
                    ?: return null
                val key = keyOf(resting, report, overrides, attributes) ?: return null
                val shifted = (obj[CASE_SHIFTED] ?: obj[CASE_SHIFTED_MANUALLY])
                    ?.takeIf { it !== resting }
                    ?.let { keyOf(it, report, overrides, attributes) }
                    ?.takeIf { it.action == KeyAction.Text }
                if (obj.keys.count { it != "type" && it != "attributes" } > CASE_KEPT_BRANCHES) {
                    report.selectors++
                }
                key.copy(
                    shiftLabel = shifted?.label
                        ?.takeIf { it.isNotEmpty() && it != key.label.uppercase() },
                )
            }
            // "/" in a URL field, "@" in an email one: this app's own comma
            // slot, which does the same job from the same signal.
            TYPE_CONTEXTUAL, TYPE_ACTION, TYPE_OPTIONAL_ZWNJ -> {
                val fallback = obj[FIELD_FALLBACK_KEY]
                    ?: return null.also { report.droppedLabels += TEMPLATE_PREFIX + type }
                val key = keyOf(fallback, report, overrides, attributes) ?: return null
                if (type == TYPE_CONTEXTUAL) key.copy(role = KeyRole.Comma) else key
            }
            TYPE_GAP -> Key(label = "", action = KeyAction.None)
                .withAttributes(attributes, overrides, report)
            // An alternate page is a whole second grid this app has no slot
            // for, and a key that switched to nothing would strand the user.
            TYPE_ALT -> null.also { report.droppedLabels += TEMPLATE_PREFIX + type }
            else -> Templates[TEMPLATE_PREFIX + type]
                ?.let { templateKey(it, attributes, report, overrides) }
                ?: null.also { report.droppedLabels += type }
        }
    }

    /** One of the `$shift`-style keys, with the attributes of wherever it sat. */
    private fun templateKey(
        template: Template,
        attributes: Map<String, Any?>,
        report: Report,
        overrides: Map<String, Float>,
    ): Key? {
        if (template.action == null && template.text == null) {
            report.droppedLabels += template.spelling
            return null
        }
        val action = template.action ?: KeyAction.Text
        return Key(
            label = if (action == KeyAction.Text) template.text.orEmpty() else action.fallbackLabel(),
            action = action,
            role = template.role,
            width = template.width,
        ).withAttributes(attributes, overrides, report)
    }

    // ---- attributes ----

    private fun Key.withAttributes(
        attributes: Map<String, Any?>,
        overrides: Map<String, Float>,
        report: Report,
    ): Key {
        if (attributes.isEmpty()) return this
        val widthToken = string(attributes["width"])
        val width = widthFor(widthToken, overrides) ?: width
        val span = (attributes["rowSpan"] as? Number)?.toInt()?.coerceAtLeast(1)
        val scale = labelScaleOf(asMap(attributes["labelFlags"]), report)
        return copy(
            width = width,
            rowSpan = span ?: rowSpan,
            labelScale = scale ?: labelScale,
        )
    }

    /**
     * A width token as this app's own unit.
     *
     * The two units are not the same: over there a width is a token resolved
     * against the row, here it is a weight against the ten-key row every
     * shipped grid is built on. A regular key is 1 either way; the functional
     * keys are the minimum the format states, scaled the same way;
     * [GROW_MARKER] is resolved once the whole row is known.
     */
    private fun widthFor(token: String, overrides: Map<String, Float>): Float? = when {
        token.isEmpty() -> null
        token.equals(WIDTH_REGULAR, ignoreCase = true) -> 1f
        token.equals(WIDTH_FUNCTIONAL, ignoreCase = true) -> FUNCTIONAL_WIDTH
        token.equals(WIDTH_GROW, ignoreCase = true) -> GROW_MARKER
        else -> overrides[token.lowercase()]
    }

    /** `overrideWidths` as fractions of the keyboard, on this app's grid. */
    private fun customWidths(value: Any?): Map<String, Float> {
        val map = asMap(value) ?: return emptyMap()
        return buildMap {
            for ((token, width) in map) {
                val fraction = (width as? Number)?.toFloat() ?: continue
                if (fraction > 0f) put(token.lowercase(), fraction * GRID_COLUMNS)
            }
        }
    }

    /**
     * The three flags that set a label size, as this app's label scale.
     *
     * The same three the HeliBoard reader maps, and to the same sizes: the
     * formats are cousins and a key that asks for the letter ratio means the
     * same thing in both.
     */
    private fun labelScaleOf(flags: Map<String, Any?>?, report: Report): Float? {
        if (flags.isNullOrEmpty()) return null
        fun on(name: String) = flags[name] == true
        val scale = when {
            on(FLAG_LARGE_LETTER_RATIO) -> LARGE_LETTER_SCALE
            on(FLAG_LETTER_RATIO) -> 1f
            on(FLAG_LABEL_RATIO) -> LABEL_SCALE
            else -> null
        }
        if (scale == null && flags.values.any { it == true }) report.restyled++
        return scale
    }

    // ---- popups ----

    private fun moreKeysOf(value: Any?): List<String> = when (value) {
        // "a,b,c" is the documented shorthand. The escape is the format's own:
        // a comma inside a key is written `\,`.
        is String -> value.split(UNESCAPED_COMMA).map { it.replace("\\,", ",") }
        is List<*> -> value.mapNotNull { it?.toString() }
        else -> emptyList()
    }

    /**
     * The press-and-hold letters, with the markers and the automatic-key
     * placeholder taken out.
     *
     * `%` says where this keyboard's *own* extra letters should be dropped into
     * the list. There is no such list here, so the placeholder goes; left in it
     * would be a key that types a percent sign.
     */
    private fun popupsOf(moreKeys: List<String>): Popups {
        var columns = 0
        val letters = mutableListOf<String>()
        for (entry in moreKeys) {
            val text = entry.trim()
            val marker = parseForeignPopupMarker(text)
            when {
                marker != null -> if (marker.columns != 0) columns = marker.columns
                text == AUTOMATIC_MORE_KEYS -> Unit
                text.isEmpty() -> Unit
                else -> {
                    val parsed = parseForeignLabel(text)
                    val letter = parsed.output ?: parsed.label
                    if (letter.isNotEmpty()) letters += normalizeForScript(letter)
                }
            }
        }
        return Popups(letters.distinct(), columns)
    }

    private class Popups(val letters: List<String>, val columns: Int)

    // ---- widths across a row ----

    /**
     * Resolves the grow keys, now that every row's fixed width is known.
     *
     * A grow key takes what is left of its row, which cannot be known until the
     * row is built, and "what is left" is measured against the widest row of
     * real keys rather than against a constant: a ten-key layout and a
     * twelve-key one both have to end up with a spacebar that spans the middle.
     */
    private fun distributeGrowth(rows: List<List<Key>>): List<List<Key>> {
        if (rows.isEmpty()) return rows
        val target = rows
            .filter { row -> row.none { it.width == GROW_MARKER } }
            .maxOfOrNull { row -> row.sumOf { it.width.toDouble() } }
            ?.toFloat()
            ?: GRID_COLUMNS
        return rows.map { row ->
            val growing = row.count { it.width == GROW_MARKER }
            if (growing == 0) return@map row
            val fixed = row.filter { it.width != GROW_MARKER }.sumOf { it.width.toDouble() }.toFloat()
            val each = ((target - fixed) / growing).coerceAtLeast(MIN_GROW_WIDTH)
            row.map { if (it.width == GROW_MARKER) it.copy(width = each) else it }
        }
    }

    // ---- the document ----

    private fun declaredLanguage(value: Any?): String? {
        val tags = when (value) {
            is String -> listOf(value)
            is List<*> -> value.mapNotNull { it?.toString() }
            else -> return null
        }
        // The file states a language, which the other two formats never do, so
        // the picker can start from a fact rather than from a guess at the
        // script. Only a language this app knows counts.
        return tags.firstNotNullOfOrNull { tag ->
            val id = tag.replace('-', '_').lowercase()
            LanguageRegistry.all.firstOrNull { it.id.equals(id, ignoreCase = true) }?.id
                ?: LanguageRegistry.all.firstOrNull {
                    it.id.equals(id.substringBefore('_'), ignoreCase = true)
                }?.id
        }
    }

    private fun rowAttributes(row: Map<String, Any?>): Map<String, Any?> =
        asMap(row["attributes"]).orEmpty()

    private fun load(text: String): Any? {
        val options = LoaderOptions().apply {
            codePointLimit = MAX_LENGTH
            maxAliasesForCollections = MAX_ALIASES
            setAllowRecursiveKeys(false)
            nestingDepthLimit = MAX_DEPTH
        }
        // SafeConstructor for the reason `:core:content` uses it: the default
        // one instantiates whatever class a `!!` tag names, and every file that
        // reaches here was written by somebody else.
        return runCatching { Yaml(SafeConstructor(options)).load<Any?>(text) }.getOrNull()
    }

    private fun asMap(value: Any?): Map<String, Any?>? {
        val map = value as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, Any?>(map.size)
        for ((key, entry) in map) if (key is String) out[key] = entry
        return out
    }

    private fun string(value: Any?): String = (value as? String)?.trim().orEmpty()

    /** One of the `$`-prefixed keys the format supplies. */
    private class Template(
        val spelling: String,
        val action: KeyAction? = null,
        val text: String? = null,
        val role: KeyRole? = null,
        val width: Float = 1f,
    )

    private val Templates: Map<String, Template> = buildMap {
        fun add(name: String, template: Template) = put(TEMPLATE_PREFIX + name, template)
        add("shift", Template("\$shift", KeyAction.Shift, width = FUNCTIONAL_WIDTH))
        add("delete", Template("\$delete", KeyAction.Delete, width = FUNCTIONAL_WIDTH))
        add("space", Template("\$space", KeyAction.Space, width = GROW_MARKER))
        add("enter", Template("\$enter", KeyAction.Enter, width = FUNCTIONAL_WIDTH))
        add("symbols", Template("\$symbols", KeyAction.Symbols, width = FUNCTIONAL_WIDTH))
        add("alphabet", Template("\$alphabet", KeyAction.Letters, width = FUNCTIONAL_WIDTH))
        add("number", Template("\$number", KeyAction.Numpad, width = FUNCTIONAL_WIDTH))
        add("period", Template("\$period", text = ".", role = KeyRole.Period))
        add("zwnj", Template("\$zwnj", text = "‌"))
        add("gap", Template("\$gap", KeyAction.None))
        // Everything below has no equivalent here and is dropped by name. The
        // user-set action key and the contextual key are only keys at all when
        // they carry a fallback, which is the object form, handled above.
        add("action", Template("\$action"))
        add("contextual", Template("\$contextual"))
        add("optionalzwnj", Template("\$optionalzwnj"))
        add("alt0", Template("\$alt0"))
        add("alt1", Template("\$alt1"))
        add("alt2", Template("\$alt2"))
    }

    private const val TEMPLATE_PREFIX = "$"
    private const val TYPE_BASE = "base"
    private const val TYPE_CASE = "case"
    private const val TYPE_CONTEXTUAL = "contextual"
    private const val TYPE_ACTION = "action"
    private const val TYPE_OPTIONAL_ZWNJ = "optionalzwnj"
    private const val TYPE_GAP = "gap"
    private const val TYPE_ALT = "alt"
    private const val FIELD_FALLBACK_KEY = "fallbackKey"
    private const val CASE_SHIFTED = "shifted"
    private const val CASE_SHIFTED_MANUALLY = "shiftedManually"
    private val CaseBranches = listOf("normal", CASE_SHIFTED, CASE_SHIFTED_MANUALLY, "shiftLocked")

    /** The resting branch and one shifted one both land, so only a third is a loss. */
    private const val CASE_KEPT_BRANCHES = 2

    private const val NUMBER_ROW_ALWAYS = "AlwaysEnabled"
    private const val WIDTH_REGULAR = "Regular"
    private const val WIDTH_FUNCTIONAL = "FunctionalKey"
    private const val WIDTH_GROW = "Grow"
    private const val AUTOMATIC_MORE_KEYS = "%"

    private const val FLAG_LARGE_LETTER_RATIO = "followKeyLargeLetterRatio"
    private const val FLAG_LETTER_RATIO = "followKeyLetterRatio"
    private const val FLAG_LABEL_RATIO = "followKeyLabelRatio"
    private const val LARGE_LETTER_SCALE = 1.15f
    private const val LABEL_SCALE = 0.68f

    /** The width this file format's own minimum works out to on a ten-key row. */
    private const val FUNCTIONAL_WIDTH = 1.25f

    /** Stands in for "take what is left of the row" until the row is built. */
    private const val GROW_MARKER = -1f
    private const val MIN_GROW_WIDTH = 1f
    private const val GRID_COLUMNS = 10f

    private const val MAX_ALIASES = 64
    private const val MAX_DEPTH = 32

    private val UNESCAPED_COMMA = Regex("(?<!\\\\),")
}
