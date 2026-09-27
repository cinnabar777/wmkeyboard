package com.wasimaster.wmkeyboard.core.transliteration

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
 * The method is defined as an m17n input method (`bn-khipro.mim`), a small
 * state machine over maps of key sequences. Rather than transcribe its ~200
 * conjunct rules into Kotlin, where they would drift from every upstream fix,
 * this runs the official spec itself: the two `.mim` files ship verbatim under
 * `resources/khipro/` and [KhiproSpec] interprets them. The interpreter follows
 * KhiproTeam's own `khipro-library`, whose engine does the same thing — it
 * re-runs the whole roman tape through the spec on every key — and is
 * conformance-tested against the same `khipro-testcases` this module's tests
 * replay.
 *
 * Upstream: spec tag v36.5.0 of rank-coder/khipro-m17n (MIT, © 2024 rank_coder)
 * and KhiproTeam/khipro-mim-touchscreen; interpreter semantics from
 * KhiproTeam/khipro-library (MIT, © 2026 KhiproTeam).
 */
object Khipro {

    /**
     * Which spec a keystroke is read against. The two differ only in what a
     * phone keyboard already has keys for: the desktop spec turns digits into
     * Bengali digits, `.` into the danda and `$` into ৳, the touchscreen one
     * leaves all of that to the keys and spells ॥ as `।f`.
     */
    enum class Variant(internal val resource: String) {
        TOUCHSCREEN("khipro/bn-khipro-touchscreen.mim"),
        DESKTOP("khipro/bn-khipro.mim"),
    }

    @Volatile private var touchscreen: KhiproSpec? = null
    @Volatile private var desktop: KhiproSpec? = null

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

    private fun spec(variant: Variant): KhiproSpec = when (variant) {
        Variant.TOUCHSCREEN -> touchscreen ?: load(variant).also { touchscreen = it }
        Variant.DESKTOP -> desktop ?: load(variant).also { desktop = it }
    }

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
 * `(keys actions… "output")` entries, and `state` blocks whose rules name a map
 * and list actions to run when one of its keys matches. Actions are `set`,
 * `insert`, `delete @-N`, `move @-/@>`, `shift`, `cond` over `=`/`&`/`|`,
 * `commit` (a no-op here: the composing buffer is the whole word) and `undo`.
 */
internal class KhiproSpec private constructor(
    private val maps: Map<String, List<Entry>>,
    private val states: Map<String, State>,
) {

    internal class Entry(val key: String, val actions: List<Action>)

    private class Rule(val map: String, val actions: List<Action>)

    private class State(val entry: List<Action>, val rules: List<Rule>)

    internal sealed interface Action {
        class SetVar(val name: String, val value: String) : Action
        class Insert(val text: String) : Action
        class Delete(val count: Int) : Action
        /** [toEnd] moves to the end of the text, else by [by] (negative is left). */
        class Move(val by: Int, val toEnd: Boolean) : Action
        class Shift(val state: String) : Action
        data object Commit : Action
        data object Undo : Action
        class Cond(val branches: List<Pair<Condition, List<Action>>>) : Action
    }

    internal sealed interface Condition {
        data object Always : Condition
        class Equals(val variable: String, val value: Int) : Condition
        class And(val left: Condition, val right: Condition) : Condition
        class Or(val left: Condition, val right: Condition) : Condition
    }

    /** The mutable run of one conversion. */
    private inner class Run {
        val out = StringBuilder()
        var cursor = 0
        val vars = HashMap<String, Int>()
        var state = INITIAL

        fun enter(name: String) {
            state = name
            states[name]?.let { execute(it.entry) }
        }

        fun execute(actions: List<Action>) {
            for (action in actions) {
                when (action) {
                    is Action.SetVar -> vars[action.name] = vars[action.value] ?: action.value.toIntOrNull() ?: 0
                    is Action.Insert -> {
                        out.insert(cursor, action.text)
                        cursor += action.text.length
                    }
                    is Action.Delete -> if (cursor > 0 && action.count > 0) {
                        val start = (cursor - action.count).coerceAtLeast(0)
                        out.delete(start, cursor.coerceAtMost(out.length))
                        cursor = start
                    }
                    is Action.Move -> cursor = if (action.toEnd) {
                        out.length
                    } else {
                        (cursor + action.by).coerceIn(0, out.length)
                    }
                    is Action.Shift -> enter(action.state)
                    Action.Commit -> Unit
                    Action.Undo -> if (cursor > 0 && out.isNotEmpty()) {
                        out.deleteCharAt(cursor - 1)
                        cursor--
                    }
                    is Action.Cond -> action.branches.firstOrNull { holds(it.first) }?.let { execute(it.second) }
                }
            }
        }

        fun holds(condition: Condition): Boolean = when (condition) {
            Condition.Always -> true
            is Condition.Equals -> (vars[condition.variable] ?: 0) == condition.value
            is Condition.And -> holds(condition.left) && holds(condition.right)
            is Condition.Or -> holds(condition.left) || holds(condition.right)
        }
    }

    fun convert(input: String): String {
        val run = Run()
        run.enter(INITIAL)
        val blinders = maps[BLINDER].orEmpty()
        var i = 0
        while (i < input.length) {
            val state = states[run.state] ?: break
            // A doubled blinder ("??", "\\") is one literal blinder, and
            // composing carries on in the state it was in. The library does
            // this for every blinder key, not only the backslash the desktop
            // spec spells out.
            val escape = blinders.firstOrNull { input.startsWith(it.key + it.key, i) }
            if (escape != null) {
                run.execute(escape.actions)
                run.cursor = run.cursor.coerceAtMost(run.out.length)
                i += escape.key.length * 2
                continue
            }
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
                run.out.insert(run.cursor, input, i, i + length)
                run.cursor += length
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
        private const val BLINDER = "blinder"

        fun parse(text: String): KhiproSpec {
            val maps = HashMap<String, List<Entry>>()
            val states = HashMap<String, State>()
            for (node in Sexp.parseAll(text)) {
                val list = node as? Sexp.Node ?: continue
                when ((list.items.firstOrNull() as? Sexp.Atom)?.text) {
                    "map" -> for (map in list.items.drop(1)) parseMap(map)?.let { (name, entries) -> maps[name] = entries }
                    "state" -> for (state in list.items.drop(1)) parseState(state)?.let { (name, def) -> states[name] = def }
                }
            }
            return KhiproSpec(maps, states)
        }

        private fun parseMap(node: Sexp): Pair<String, List<Entry>>? {
            val list = node as? Sexp.Node ?: return null
            val name = (list.items.firstOrNull() as? Sexp.Atom)?.text ?: return null
            val entries = list.items.drop(1).mapNotNull { item ->
                val entry = item as? Sexp.Node ?: return@mapNotNull null
                val key = when (val head = entry.items.firstOrNull()) {
                    is Sexp.Atom -> head.text
                    // (BackSpace) and the like: a key the tape never holds.
                    is Sexp.Node -> return@mapNotNull null
                    null -> return@mapNotNull null
                }
                if (key.isEmpty()) return@mapNotNull null
                var output = ""
                val actions = ArrayList<Action>()
                for (part in entry.items.drop(1)) {
                    when (part) {
                        is Sexp.Atom -> output = part.text
                        is Sexp.Node -> parseAction(part)?.let(actions::add)
                    }
                }
                if (output.isNotEmpty()) actions += Action.Insert(output)
                Entry(key, actions)
            }
            // Stable, so equal lengths keep the spec's own order.
            return name to entries.sortedByDescending { it.key.length }
        }

        private fun parseState(node: Sexp): Pair<String, State>? {
            val list = node as? Sexp.Node ?: return null
            val name = (list.items.firstOrNull() as? Sexp.Atom)?.text ?: return null
            val entry = ArrayList<Action>()
            val rules = ArrayList<Rule>()
            for (item in list.items.drop(1)) {
                val rule = item as? Sexp.Node ?: continue
                val head = (rule.items.firstOrNull() as? Sexp.Atom)?.text ?: continue
                val actions = rule.items.drop(1).mapNotNull(::parseAction)
                if (head == "t") entry += actions else rules += Rule(head, actions)
            }
            return name to State(entry, rules)
        }

        private fun parseAction(node: Sexp): Action? {
            val list = node as? Sexp.Node ?: return null
            val head = (list.items.firstOrNull() as? Sexp.Atom)?.text ?: return null
            val first = (list.items.getOrNull(1) as? Sexp.Atom)?.text
            return when (head) {
                "set" -> {
                    val value = (list.items.getOrNull(2) as? Sexp.Atom)?.text ?: return null
                    Action.SetVar(first ?: return null, value)
                }
                // `?্` is m17n's character literal: the character itself.
                "insert" -> Action.Insert((first ?: return null).removePrefix("?"))
                "delete" -> first?.takeIf { it.startsWith("@-") }
                    ?.let { Action.Delete(it.drop(2).ifEmpty { "1" }.toIntOrNull() ?: 1) }
                "move" -> when {
                    first == "@>" -> Action.Move(0, toEnd = true)
                    first == "@-" -> Action.Move(-1, toEnd = false)
                    first != null && first.startsWith("@-") ->
                        Action.Move(-(first.drop(2).toIntOrNull() ?: 1), toEnd = false)
                    else -> null
                }
                "shift" -> Action.Shift(first ?: return null)
                "commit" -> Action.Commit
                "undo" -> Action.Undo
                "cond" -> list.items.drop(1).mapNotNull { branch ->
                    val parts = (branch as? Sexp.Node)?.items ?: return@mapNotNull null
                    val condition = parseCondition(parts.firstOrNull() ?: return@mapNotNull null)
                        ?: return@mapNotNull null
                    condition to parts.drop(1).mapNotNull(::parseAction)
                }.takeIf { it.isNotEmpty() }?.let(Action::Cond)
                else -> null
            }
        }

        private fun parseCondition(node: Sexp): Condition? {
            if (node is Sexp.Atom) return if (node.text == "1") Condition.Always else null
            val list = (node as Sexp.Node).items
            val head = (list.firstOrNull() as? Sexp.Atom)?.text ?: return null
            return when (head) {
                "1" -> Condition.Always
                "=" -> {
                    val variable = (list.getOrNull(1) as? Sexp.Atom)?.text ?: return null
                    val value = (list.getOrNull(2) as? Sexp.Atom)?.text?.toIntOrNull() ?: 0
                    Condition.Equals(variable, value)
                }
                // The specs chain three or more operands under one `|`; fold
                // them left, as m17n does.
                "&", "|" -> list.drop(1).map { parseCondition(it) ?: return null }
                    .takeIf { it.size >= 2 }
                    ?.reduce { left, right ->
                        if (head == "&") Condition.And(left, right) else Condition.Or(left, right)
                    }
                else -> null
            }
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
