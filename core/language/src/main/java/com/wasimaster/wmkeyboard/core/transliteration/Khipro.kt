package com.wasimaster.wmkeyboard.core.transliteration

import java.util.concurrent.ConcurrentHashMap

/**
 * Khipro (ক্ষিপ্র): Bengali typed compositionally from lowercase Latin keys
 * (issue #400, https://khipro.khiproteam.com).
 *
 * Not a phonetic scheme like Avro. Every keystroke has one meaning: `f` and
 * `/` are modifiers (ত→ট, ক→ক্ষ, and the slicer that breaks a conjunct or makes
 * ৎ and ঁ), `;` separates two parts a conjunct would otherwise fuse, and `?` or
 * `\` blind the method so the next run is literal Latin. So a word has exactly
 * one reading and there is nothing to rank: what [convert] returns is what the
 * user typed.
 *
 * Two editions exist. Classic joins consonants into conjuncts as they are
 * typed and uses `/` to break one apart. Dark (issue #631) leaves consonants
 * apart and joins them on demand: one, two or three `/` after a run of
 * consonants fuse its last two, three or four (`ntrz///` is ন্ত্র্য), which
 * is faster on a phone, where most everyday words have no conjunct at all.
 *
 * The method is defined as an m17n input method (`bn-khipro.mim`), a small
 * state machine over maps of key sequences. Rather than transcribe its ~200
 * conjunct rules into Kotlin, where they would drift from every upstream fix,
 * this runs the official spec itself: the `.mim` files ship verbatim under
 * `resources/khipro/` and [KhiproSpec] interprets them. The interpreter follows
 * KhiproTeam's own `khipro-library`, whose engine does the same thing — it
 * re-runs the whole roman tape through the spec on every key — and is
 * conformance-tested against the same `khipro-testcases` this module's tests
 * replay.
 *
 * Upstream: rank-coder/khipro-m17n, KhiproTeam/khipro-mim-touchscreen,
 * KhiproTeam/khipro-dark-m17n and KhiproTeam/khipro-dark-mim-touchscreen (all
 * MIT, © 2024 rank_coder); `scripts/update-khipro.sh` refreshes them.
 * Interpreter semantics from KhiproTeam/khipro-library (MIT, © 2026
 * KhiproTeam), and m17n-lib's own for the marker commands only Dark uses.
 */
object Khipro {

    /**
     * Which spec a keystroke is read against: the edition, and whether the key
     * came from a touchscreen or a hardware keyboard. Within an edition the two
     * differ only in what a phone keyboard already has keys for: the desktop
     * spec turns digits into Bengali digits, `.` into the danda and `$` into ৳,
     * the touchscreen one leaves all of that to the keys and spells ॥ as `।f`.
     */
    enum class Variant(internal val resource: String, val dark: Boolean, val desktop: Boolean) {
        TOUCHSCREEN("khipro/bn-khipro-touchscreen.mim", dark = false, desktop = false),
        DESKTOP("khipro/bn-khipro.mim", dark = false, desktop = true),
        DARK_TOUCHSCREEN("khipro/bn-khipro-dark-touchscreen.mim", dark = true, desktop = false),
        DARK_DESKTOP("khipro/bn-khipro-dark.mim", dark = true, desktop = true),
        ;

        companion object {
            /** The spec of edition [dark] for a key typed on a hardware keyboard or not. */
            fun of(dark: Boolean, desktop: Boolean): Variant =
                entries.first { it.dark == dark && it.desktop == desktop }
        }
    }

    private val specs = ConcurrentHashMap<Variant, KhiproSpec>()

    /** The Bengali [roman] spells under [variant]. Empty in, empty out. */
    fun convert(roman: String, variant: Variant = Variant.TOUCHSCREEN): String =
        if (roman.isEmpty()) "" else spec(variant).convert(roman)

    /**
     * Parses [variant]'s spec now rather than on the first keystroke, which
     * would otherwise pay for reading it out of the APK on the main thread.
     */
    fun warm(variant: Variant) {
        spec(variant)
    }

    private fun spec(variant: Variant): KhiproSpec = specs.getOrPut(variant) { load(variant) }

    private fun load(variant: Variant): KhiproSpec {
        val stream = checkNotNull(Khipro::class.java.classLoader?.getResourceAsStream(variant.resource)) {
            "Khipro spec ${variant.resource} is missing from the build"
        }
        return KhiproSpec.parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}

/**
 * A parsed m17n input method, and the interpreter that runs a roman tape
 * through it.
 *
 * Only the subset of m17n the Khipro specs use: `map` blocks of
 * `(keys actions… "output")` entries, `state` blocks whose rules name a map
 * and list actions to run when one of its keys matches, and `macro` blocks of
 * named action lists, expanded where they are called. Actions are `set`,
 * `insert`, `delete` and `move` to a position (`@-N`, `@+N`, `@<`, `@>` or a
 * marker), `mark`, `shift`, `cond` over `=`/`<`/`>`/`<=`/`>=`/`&`/`|`,
 * `commit` (a no-op here: the composing buffer is the whole word) and `undo`.
 *
 * Markers move with the text the way m17n-lib moves them: an insertion shifts
 * every marker after the insertion point (one exactly at it stays put, which
 * is what lets Dark mark the boundary before the next consonant), and a
 * deletion pulls the markers inside it to its start.
 */
internal class KhiproSpec private constructor(
    private val maps: Map<String, List<Entry>>,
    private val states: Map<String, State>,
) {

    internal class Entry(val key: String, val actions: List<Action>)

    private class Rule(val map: String, val actions: List<Action>)

    private class State(val entry: List<Action>, val rules: List<Rule>)

    /** Where a `move` or `delete` goes. */
    internal sealed interface Position {
        /** [by] characters from the cursor, negative to the left. */
        class Relative(val by: Int) : Position
        data object Start : Position
        data object End : Position
        class Marker(val name: String) : Position
    }

    internal sealed interface Action {
        class SetVar(val name: String, val value: String) : Action
        class Insert(val text: String) : Action
        /** Deletes from the cursor to [to], on whichever side of it that is. */
        class Delete(val to: Position) : Action
        class Move(val to: Position) : Action
        class Mark(val name: String) : Action
        class Shift(val state: String) : Action
        data object Commit : Action
        data object Undo : Action
        class Cond(val branches: List<Pair<Condition, List<Action>>>) : Action
    }

    internal sealed interface Condition {
        data object Always : Condition
        /** [variable] against [value], a number or another variable, by [op]. */
        class Compare(val op: String, val variable: String, val value: String) : Condition
        class And(val left: Condition, val right: Condition) : Condition
        class Or(val left: Condition, val right: Condition) : Condition
    }

    /** The mutable run of one conversion. */
    private inner class Run {
        val out = StringBuilder()
        var cursor = 0
        val vars = HashMap<String, Int>()
        val marks = HashMap<String, Int>()
        var state = INITIAL

        fun enter(name: String) {
            state = name
            states[name]?.let { execute(it.entry) }
        }

        fun value(operand: String): Int = operand.toIntOrNull() ?: vars[operand] ?: 0

        fun resolve(position: Position): Int = when (position) {
            is Position.Relative -> cursor + position.by
            Position.Start -> 0
            Position.End -> out.length
            is Position.Marker -> marks[position.name] ?: 0
        }.coerceIn(0, out.length)

        fun insert(text: String) {
            if (text.isEmpty()) return
            cursor = cursor.coerceAtMost(out.length)
            out.insert(cursor, text)
            for (entry in marks.entries) if (entry.value > cursor) entry.setValue(entry.value + text.length)
            cursor += text.length
        }

        fun delete(to: Int) {
            val from = minOf(cursor, to).coerceIn(0, out.length)
            val end = maxOf(cursor, to).coerceIn(0, out.length)
            if (from == end) return
            out.delete(from, end)
            for (entry in marks.entries) {
                if (entry.value > end) {
                    entry.setValue(entry.value - (end - from))
                } else if (entry.value > from) {
                    entry.setValue(from)
                }
            }
            cursor = from
        }

        fun execute(actions: List<Action>) {
            for (action in actions) {
                when (action) {
                    is Action.SetVar -> vars[action.name] = value(action.value)
                    is Action.Insert -> insert(action.text)
                    is Action.Delete -> delete(resolve(action.to))
                    is Action.Move -> cursor = resolve(action.to)
                    is Action.Mark -> marks[action.name] = cursor.coerceIn(0, out.length)
                    is Action.Shift -> enter(action.state)
                    Action.Commit -> Unit
                    Action.Undo -> if (cursor > 0 && out.isNotEmpty()) delete(cursor - 1)
                    is Action.Cond -> action.branches.firstOrNull { holds(it.first) }?.let { execute(it.second) }
                }
            }
        }

        fun holds(condition: Condition): Boolean = when (condition) {
            Condition.Always -> true
            is Condition.Compare -> {
                val left = vars[condition.variable] ?: 0
                val right = value(condition.value)
                when (condition.op) {
                    "=" -> left == right
                    "<" -> left < right
                    ">" -> left > right
                    "<=" -> left <= right
                    else -> left >= right
                }
            }
            is Condition.And -> holds(condition.left) && holds(condition.right)
            is Condition.Or -> holds(condition.left) || holds(condition.right)
        }
    }

    fun convert(input: String): String {
        val run = Run()
        run.enter(INITIAL)
        var i = 0
        while (i < input.length) {
            // A blinder (`?`, `\`) and what it leaves Latin are the spec's
            // own `blind_state`, as in the library since it dropped its
            // hard-coded collapse of a doubled blinder.
            val state = states[run.state] ?: break
            var best: Entry? = null
            var bestRule: Rule? = null
            for (rule in state.rules) {
                for (entry in maps[rule.map] ?: continue) {
                    // Each map is sorted longest key first, so the first hit
                    // in it is its longest; a longer hit in a later map wins.
                    if (entry.key.length <= (best?.key?.length ?: 0)) break
                    if (input.startsWith(entry.key, i)) {
                        best = entry
                        bestRule = rule
                        break
                    }
                }
            }
            if (best == null || bestRule == null) {
                // Nothing in this state reads the character: it is written as
                // itself and the method starts afresh after it.
                val length = Character.charCount(input.codePointAt(i))
                run.insert(input.substring(i, i + length))
                i += length
                if (run.state != INITIAL) run.enter(INITIAL)
                continue
            }
            run.execute(best.actions)
            run.execute(bestRule.actions)
            run.cursor = run.cursor.coerceAtMost(run.out.length)
            i += best.key.length
        }
        return run.out.toString()
    }

    companion object {
        private const val INITIAL = "init"

        fun parse(text: String): KhiproSpec {
            val nodes = Sexp.parseAll(text).filterIsInstance<Sexp.Node>()
            // Macros first: the specs define them after the states that call them.
            val macros = HashMap<String, List<Sexp>>()
            for (node in nodes) {
                if (node.head() != "macro") continue
                for (macro in node.items.drop(1)) {
                    val list = macro as? Sexp.Node ?: continue
                    val name = list.head() ?: continue
                    macros[name] = list.items.drop(1)
                }
            }
            val parser = Parser(macros)
            val maps = HashMap<String, List<Entry>>()
            val states = HashMap<String, State>()
            for (node in nodes) {
                when (node.head()) {
                    "map" -> for (map in node.items.drop(1)) parser.map(map)?.let { (name, entries) -> maps[name] = entries }
                    "state" -> for (state in node.items.drop(1)) parser.state(state)?.let { (name, def) -> states[name] = def }
                }
            }
            return KhiproSpec(maps, states)
        }

        private fun Sexp.Node.head(): String? = (items.firstOrNull() as? Sexp.Atom)?.text

        private fun position(text: String): Position = when {
            text == "@<" -> Position.Start
            text == "@>" -> Position.End
            text == "@-" -> Position.Relative(-1)
            text == "@+" -> Position.Relative(1)
            text.startsWith("@-") -> Position.Relative(-(text.drop(2).toIntOrNull() ?: 1))
            text.startsWith("@+") -> Position.Relative(text.drop(2).toIntOrNull() ?: 1)
            else -> Position.Marker(text)
        }
    }

    private class Parser(private val macros: Map<String, List<Sexp>>) {

        fun map(node: Sexp): Pair<String, List<Entry>>? {
            val list = node as? Sexp.Node ?: return null
            val name = list.head() ?: return null
            val entries = list.items.drop(1).mapNotNull { item ->
                val entry = item as? Sexp.Node ?: return@mapNotNull null
                val key = when (val head = entry.items.firstOrNull()) {
                    is Sexp.Atom -> head.text
                    // (BackSpace) and the like: a key the tape never holds.
                    is Sexp.Node -> return@mapNotNull null
                    null -> return@mapNotNull null
                }
                if (key.isEmpty()) return@mapNotNull null
                // In the order written: Dark's `zf` resets its markers before
                // its text goes in.
                val actions = ArrayList<Action>()
                for (part in entry.items.drop(1)) {
                    when (part) {
                        is Sexp.Atom -> if (part.text.isNotEmpty()) actions += Action.Insert(part.text)
                        is Sexp.Node -> actions += action(part)
                    }
                }
                Entry(key, actions)
            }
            // Stable, so equal lengths keep the spec's own order.
            return name to entries.sortedByDescending { it.key.length }
        }

        fun state(node: Sexp): Pair<String, State>? {
            val list = node as? Sexp.Node ?: return null
            val name = list.head() ?: return null
            val entry = ArrayList<Action>()
            val rules = ArrayList<Rule>()
            for (item in list.items.drop(1)) {
                val rule = item as? Sexp.Node ?: continue
                val head = rule.head() ?: continue
                val actions = rule.items.drop(1).flatMap { action(it) }
                if (head == "t") entry += actions else rules += Rule(head, actions)
            }
            return name to State(entry, rules)
        }

        /** [node] as actions: one, none for what this subset skips, or a macro's whole body. */
        private fun action(node: Sexp, depth: Int = 0): List<Action> {
            val list = node as? Sexp.Node ?: return emptyList()
            val head = list.head() ?: return emptyList()
            val first = (list.items.getOrNull(1) as? Sexp.Atom)?.text
            val single: Action? = when (head) {
                "set" -> {
                    val value = (list.items.getOrNull(2) as? Sexp.Atom)?.text
                    if (first != null && value != null) Action.SetVar(first, value) else null
                }
                // `?্` is m17n's character literal: the character itself.
                "insert" -> first?.let { Action.Insert(it.removePrefix("?")) }
                "delete" -> first?.let { Action.Delete(position(it)) }
                "move" -> first?.let { Action.Move(position(it)) }
                "mark" -> first?.let { Action.Mark(it) }
                "shift" -> first?.let { Action.Shift(it) }
                "commit" -> Action.Commit
                "undo" -> Action.Undo
                "cond" -> list.items.drop(1).mapNotNull { branch ->
                    val parts = (branch as? Sexp.Node)?.items ?: return@mapNotNull null
                    val condition = condition(parts.firstOrNull() ?: return@mapNotNull null)
                        ?: return@mapNotNull null
                    condition to parts.drop(1).flatMap { action(it, depth) }
                }.takeIf { it.isNotEmpty() }?.let(Action::Cond)
                else -> {
                    val body = macros[head]
                    // A macro calling itself would never end; the specs never do.
                    return if (body == null || depth > MAX_MACRO_DEPTH) {
                        emptyList()
                    } else {
                        body.flatMap { action(it, depth + 1) }
                    }
                }
            }
            return listOfNotNull(single)
        }

        private fun condition(node: Sexp): Condition? {
            if (node is Sexp.Atom) return if (node.text == "1") Condition.Always else null
            val list = (node as Sexp.Node).items
            val head = (list.firstOrNull() as? Sexp.Atom)?.text ?: return null
            return when (head) {
                "1" -> Condition.Always
                "=", "<", ">", "<=", ">=" -> {
                    val variable = (list.getOrNull(1) as? Sexp.Atom)?.text ?: return null
                    val value = (list.getOrNull(2) as? Sexp.Atom)?.text ?: "0"
                    Condition.Compare(head, variable, value)
                }
                // The specs chain three or more operands under one `|`; fold
                // them left, as m17n does.
                "&", "|" -> list.drop(1).map { condition(it) ?: return null }
                    .takeIf { it.size >= 2 }
                    ?.reduce { left, right ->
                        if (head == "&") Condition.And(left, right) else Condition.Or(left, right)
                    }
                else -> null
            }
        }

        private fun Sexp.Node.head(): String? = (items.firstOrNull() as? Sexp.Atom)?.text

        companion object {
            private const val MAX_MACRO_DEPTH = 8
        }
    }
}

/** The S-expressions an m17n file is written in: `;;` comments, `"strings"`, atoms and lists. */
internal sealed interface Sexp {
    class Atom(val text: String) : Sexp
    class Node(val items: List<Sexp>) : Sexp

    companion object {
        fun parseAll(text: String): List<Sexp> {
            val reader = Reader(text)
            val out = ArrayList<Sexp>()
            while (reader.skipSpace()) out += reader.read()
            return out
        }
    }

    private class Reader(private val text: String) {
        private var at = 0

        /** Skips blanks and comments; false at the end of the text. */
        fun skipSpace(): Boolean {
            while (at < text.length) {
                val c = text[at]
                when {
                    c.isWhitespace() -> at++
                    c == ';' && text.startsWith(";;", at) -> {
                        while (at < text.length && text[at] != '\n') at++
                    }
                    else -> return true
                }
            }
            return false
        }

        fun read(): Sexp {
            check(skipSpace()) { "Unexpected end of m17n text" }
            return when (text[at]) {
                '(' -> {
                    at++
                    val items = ArrayList<Sexp>()
                    while (skipSpace() && text[at] != ')') items += read()
                    check(at < text.length) { "Unclosed list in m17n text" }
                    at++
                    Node(items)
                }
                '"' -> Atom(readString())
                else -> {
                    val start = at
                    while (at < text.length && !text[at].isWhitespace() && text[at] != '(' && text[at] != ')') at++
                    Atom(text.substring(start, at))
                }
            }
        }

        private fun readString(): String {
            at++
            val out = StringBuilder()
            while (at < text.length && text[at] != '"') {
                if (text[at] == '\\' && at + 1 < text.length) {
                    out.append(
                        when (val next = text[at + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> next
                        },
                    )
                    at += 2
                } else {
                    out.append(text[at++])
                }
            }
            check(at < text.length) { "Unterminated string in m17n text" }
            at++
            return out.toString()
        }
    }
}
