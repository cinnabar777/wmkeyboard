package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One tool the AI tool may let a model call (#470).
 *
 * [parameters] is a JSON Schema object, the shape every provider's function
 * calling takes — OpenAI, Anthropic, Gemini and Ollama all spell the wrapper
 * differently but all read the same schema inside it, so it is stored once
 * here and re-wrapped per provider in `AiClient`.
 */
data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * One call a model asked for.
 *
 * [id] is the provider's own handle for the call, which the result has to
 * quote back. Gemini matches results by tool name instead and the prompt
 * fallback has no ids at all, so it is blank for both.
 *
 * [arguments] stays the raw JSON object text rather than a parsed map: a model
 * that writes a number where a string belongs, or an extra field nobody asked
 * for, should reach the tool that can complain about it rather than fail to
 * parse on the way.
 */
data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    /** One named argument, or null when it is absent or is not a string. */
    fun argument(key: String): String? =
        runCatching {
            (Json.parseToJsonElement(arguments).jsonObject[key] as? JsonPrimitive)
                ?.content?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
}

/**
 * What running one produced, ready to hand back to the model.
 *
 * [content] is the model's to read. [sources] and [error] are the user's: the
 * pages the model was handed and, when the call did not work, why — in words
 * meant for a person rather than the instructions [content] carries to the
 * model. Together they make the [activity] the chat shows under an answer.
 */
data class AiToolResult(
    val call: AiToolCall,
    val content: String,
    val sources: List<AiToolSource> = emptyList(),
    /** Not empty means the call failed, and says why. */
    val error: String = "",
    /**
     * How the call was shown while it ran, when that is more than its name:
     * an MCP call carries its server and the tool's own title (#573). Null
     * for our own tools, whose record is built from the call alone.
     */
    val display: AiToolActivity? = null,
) {
    val activity: AiToolActivity
        get() = (display ?: AiToolActivity.started(call)).copy(
            running = false,
            sources = sources,
            error = error,
            awaitingApproval = false,
            details = "",
        )
}

/** What the user answered when a call asked first (#573). */
enum class AiToolApproval {
    /** Run this call. */
    ONCE,

    /** Run this call, and every later call of the same tool without asking. */
    ALWAYS,

    /** Do not run it; the model is told so. */
    DENY,

    /** The run was stopped while the question was showing. */
    CANCELLED,
}

/**
 * Asks the user whether a call may run, and waits for the answer. [ask] is
 * the call as the user is to see it, with [AiToolActivity.awaitingApproval]
 * set and its arguments in [AiToolActivity.details].
 *
 * Blocking: it is called from the tool loop's own thread, which has nothing
 * to do until the user answers.
 */
fun interface AiToolApprover {
    fun approve(ask: AiToolActivity): AiToolApproval
}

/** One page a tool call put in front of the model: a search result, or the page it read. */
@Serializable
data class AiToolSource(
    val title: String = "",
    val url: String = "",
) {
    /** The site, for a line too short for the whole address: `example.com`. */
    val domain: String get() = AiToolActivity.domainOf(url)
}

/**
 * One tool call as the user is shown it: what the model asked for, what came
 * back, and whether it worked.
 *
 * Without this a run that searched the web looked exactly like one that did
 * not, and the user had no way to tell an answer built on a page from one made
 * up. It is what the chat draws while a tool runs ("Searching the web for …")
 * and keeps under the answer afterwards, and it is stored with the message, so
 * strings rather than enums for the same reason as the rest of the chat store:
 * a record has to outlive a tool being renamed.
 */
@Serializable
data class AiToolActivity(
    /** [AiTools.WEB_SEARCH], [AiTools.WEB_FETCH], or a name the model made up. */
    val tool: String,
    /** The query searched for, or the address read. Empty when the model left it out. */
    val target: String = "",
    /** Still running. Never true in a stored message. */
    val running: Boolean = false,
    /** Search results the model was given, or the one page it read. */
    val sources: List<AiToolSource> = emptyList(),
    /** Not empty means the call failed, and says why. */
    val error: String = "",
    /**
     * The MCP server the tool belongs to (#573), by the name the user gave it.
     * Empty for our own tools. When set, [tool] is the tool's title rather
     * than a name from [AiTools].
     */
    val server: String = "",
    /** The call is waiting for the user to allow it. Never true in a stored message. */
    val awaitingApproval: Boolean = false,
    /** The call's arguments in full, for the approval card. Empty once it has run. */
    val details: String = "",
) {
    val failed: Boolean get() = error.isNotEmpty()

    /** A tool from one of the user's MCP servers rather than one of ours. */
    val isMcp: Boolean get() = server.isNotEmpty()

    companion object {
        /** A call the model just asked for, before it has run. */
        fun started(call: AiToolCall): AiToolActivity = AiToolActivity(
            tool = call.name,
            target = when (call.name) {
                AiTools.WEB_SEARCH -> call.argument("query")
                AiTools.WEB_FETCH -> call.argument("url")
                else -> null
            }.orEmpty(),
            running = true,
        )

        /**
         * [trace] with [result] in place of the call it answers. Calls run one
         * at a time, so that is the newest one still running; a result with no
         * started call to replace is added at the end.
         */
        fun record(trace: List<AiToolActivity>, result: AiToolResult): List<AiToolActivity> {
            val at = trace.indexOfLast { it.running }
            if (at < 0) return trace + result.activity
            return trace.toMutableList().also { it[at] = result.activity }
        }

        /**
         * [trace] as it is kept once the run is over: a call the run ended in
         * the middle of (Stop) is marked with [reason] rather than left
         * looking as if it were still going, or as if it had worked.
         */
        fun settle(trace: List<AiToolActivity>, reason: String): List<AiToolActivity> =
            trace.map {
                if (it.running) it.copy(running = false, error = reason, awaitingApproval = false, details = "") else it
            }

        /**
         * [trace] with the newest running call replaced by [shown]: the same
         * call, now asking to be allowed, or done asking. Added at the end
         * when nothing is running, as [record] does.
         */
        fun replaceRunning(trace: List<AiToolActivity>, shown: AiToolActivity): List<AiToolActivity> {
            val at = trace.indexOfLast { it.running }
            if (at < 0) return trace + shown
            return trace.toMutableList().also { it[at] = shown }
        }

        /**
         * The host of [url] without a leading `www.`, or [url] itself when it
         * has none — what a one-line record has room for.
         */
        fun domainOf(url: String): String {
            val host = url.trim().substringAfter("://").substringBefore('/')
                .substringBefore('?').substringBefore('#').substringAfterLast('@')
            return host.removePrefix("www.").ifEmpty { url.trim() }
        }

        /**
         * [url] when a tap may open it: http and https only. The address came
         * from a model or a search result, so an `intent:` or `file:` one is
         * offered for copying and never handed to a browser.
         */
        fun openableUrl(url: String): String? {
            val trimmed = url.trim()
            val scheme = trimmed.substringBefore("://", "").lowercase()
            return trimmed.takeIf { (scheme == "http" || scheme == "https") && domainOf(it).isNotEmpty() }
        }
    }
}

/**
 * The tools the AI tool can offer, and the catalogue the settings rows switch
 * on. Deliberately a very short list: every tool costs tokens in the system
 * prompt of every single run, whether it is ever called or not, and a small
 * on-device model reading a long catalogue answers worse than one reading none.
 */
object AiTools {

    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"

    /** Rounds of tool calls one run may take before the model has to answer. */
    const val DEFAULT_MAX_ROUNDS = 3

    /**
     * Search the web. The query is the model's, not the user's: it rewrites
     * the request into something a search engine answers well, which is most
     * of the value of giving it the tool rather than searching for it.
     */
    val webSearch = AiToolSpec(
        name = WEB_SEARCH,
        description = "Search the web for current information. " +
            "Use it for anything you do not know or that may have changed since training.",
        parameters = schema(
            "query" to "What to search for, written as a search engine query.",
        ),
    )

    /**
     * Read one page. Kept independent of [webSearch] — the issue that asked
     * for these wanted a user who pastes a link to be able to say "summarise
     * this" without paying for a search backend as well.
     */
    val webFetch = AiToolSpec(
        name = WEB_FETCH,
        description = "Fetch one web page and read its text. " +
            "Use it on a link the user gave you, or on a result from $WEB_SEARCH.",
        parameters = schema(
            "url" to "The full address of the page, including https://.",
        ),
    )

    /** The enabled tools, in the order a model sees them. */
    fun enabled(search: Boolean, fetch: Boolean): List<AiToolSpec> = buildList {
        if (search) add(webSearch)
        if (fetch) add(webFetch)
    }

    /** A one-object schema whose every named property is a required string. */
    private fun schema(vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, description) in properties) {
                putJsonObject(name) {
                    put("type", "string")
                    put("description", description)
                }
            }
        }
        put("required", buildJsonArray { for ((name, _) in properties) add(JsonPrimitive(name)) })
    }
}

/**
 * Tool calling for the providers that have no tool calling: a marker the model
 * writes into its answer, pulled back out of the stream before anyone sees it.
 *
 * This is what lets an on-device model and Brave's Answers API take part at
 * all — neither has a function-calling API, and Brave takes one user message
 * with no system role, so everything either of them will ever know about tools
 * has to travel inside the text. A model that supports native tool calling
 * should never be sent down this path.
 *
 * The reader here is deliberately loose. Asked for `<tool_call>{…}</tool_call>`,
 * a local model writes whatever its own fine-tune taught it, and the angle
 * brackets are the worst part of it: `tool_call` is a *special token* in
 * several chat templates, so the template mangles the very string we asked
 * for. Gemma 4 E2B alone produced `TOOL_CALL{{…}}<tool_call|>` and
 * `<|tool_call>call:tool_call{{…}}<tool_call|>` — no opener in the first,
 * pipes on the wrong side in both, doubled braces in both. Matching only the
 * exact string meant none of it parsed *and* none of it was hidden, so the
 * user got raw JSON in the answer bubble: the worst of both outcomes.
 *
 * So [Filter] looks for a marker in any of the shapes those templates produce,
 * takes the next balanced `{…}` after it however many layers of braces it is
 * wrapped in, and — this is the part that matters for what the user sees —
 * hides the whole span whether or not it ends up parsing. Machinery the model
 * wrote for us is never the user's to read, even when it is malformed.
 */
object AiToolProtocol {

    /** The marker the instructions ask for; readers accept far more. */
    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"

    /** How a tool's answer is introduced in the user turn that carries it. */
    const val RESULT_PREFIX = "TOOL RESULT"

    /**
     * What to append to the system prompt (or, for Brave, to the one user
     * message) so the model knows the marker.
     *
     * Short on purpose — every word is paid for on every run, including the
     * runs where nothing is ever called — but two instructions earn their
     * place. "Do not ask the user to narrow" is there because a small model's
     * first move, given a tool, is to ask which — Gemma 4 E2B asked twice
     * before calling anything for a request it could have searched as
     * written. "Exactly this and nothing else" is there because the reader
     * hides a whole marker span, so a model that wraps its call in
     * explanation loses the explanation too.
     */
    fun instructions(tools: List<AiToolSpec>): String {
        if (tools.isEmpty()) return ""
        val list = tools.joinToString("\n") { tool ->
            val args = tool.parameters["properties"]?.jsonObject?.keys.orEmpty().joinToString(", ")
            "- ${tool.name}($args): ${tool.description}"
        }
        return "\n\nYou can call tools. To call one, write exactly this and nothing else:\n" +
            OPEN + """{"name":"TOOL","arguments":{"ARG":"VALUE"}}""" + CLOSE + "\n" +
            "The answer comes back as a message starting $RESULT_PREFIX, and you then " +
            "reply to the user normally. Call a tool whenever you need something you " +
            "do not already know — do not ask the user to narrow a request you could " +
            "look up as they wrote it. Never mention the tools or this format to the " +
            "user.\nTools:\n" + list
    }

    /** The user message carrying one finished call's answer. */
    fun resultMessage(result: AiToolResult): String =
        "$RESULT_PREFIX ${result.call.name}\n${result.content}"

    /**
     * Pulls markers out of a stream whose chunks can cut one anywhere — `<tool`
     * in one event and `_call>{…}` in the next.
     *
     * Same hold-back shape as `AiClient.BraveTagFilter`: text that might still
     * turn into a marker is kept back until it either does or cannot, and
     * everything else passes straight through, so the answer still appears as
     * it streams. Here the hold can start on a bare `t` as well as on a `<`,
     * because a marker need not have brackets at all — which costs at most a
     * word of lag at a chunk boundary, and never shows a half-written marker.
     */
    class Filter {
        private val pending = StringBuilder()
        private val found = ArrayList<AiToolCall>()

        /** The calls seen so far, oldest first. */
        val calls: List<AiToolCall> get() = found

        /** Takes the next chunk, returns the text now known to be answer. */
        fun feed(chunk: String): String {
            pending.append(chunk)
            return drain(final = false)
        }

        /**
         * The end of the stream. A partial marker is released as the text it
         * turned out to be; a call that never closed is read anyway when what
         * there is of it parses, because a model that ran out of room
         * mid-marker still said what it wanted.
         */
        fun flush(): String = drain(final = true)

        private fun drain(final: Boolean): String {
            val out = StringBuilder()
            var i = 0
            while (i < pending.length) {
                val trigger = AiToolProtocol.nextTrigger(pending, i)
                if (trigger < 0) {
                    out.append(pending, i, pending.length)
                    i = pending.length
                    break
                }
                out.append(pending, i, trigger)
                i = trigger
                when (val scan = AiToolProtocol.readCall(pending, i, final)) {
                    Scan.NeedMore -> break
                    Scan.NotHere -> {
                        out.append(pending[i])
                        i++
                    }
                    is Scan.Found -> {
                        scan.call?.let(found::add)
                        i = scan.end
                    }
                }
            }
            pending.delete(0, i)
            return out.toString()
        }
    }

    /**
     * Every marker in one finished body. For the non-streaming path and for
     * reading a model's answer back in a test; the streaming path uses
     * [Filter], which has to do the same job a chunk at a time.
     */
    fun parseAll(text: String): List<AiToolCall> {
        val filter = Filter()
        filter.feed(text)
        filter.flush()
        return filter.calls
    }

    /** [text] with every marker taken out, which is what the user may see. */
    fun stripped(text: String): String {
        val filter = Filter()
        return (filter.feed(text) + filter.flush()).trim()
    }

    // ---- the reader -------------------------------------------------------

    private sealed interface Scan {
        /** More of the stream is needed before this can be decided. */
        object NeedMore : Scan

        /** Not a marker after all; the character is ordinary text. */
        object NotHere : Scan

        /** A marker ran to [end]; [call] is null when it did not parse. */
        data class Found(val call: AiToolCall?, val end: Int) : Scan
    }

    /**
     * The next place a marker could begin: an angle or square bracket, or the
     * word itself, since several templates drop the brackets entirely.
     */
    private fun nextTrigger(text: CharSequence, from: Int): Int {
        for (i in from until text.length) {
            val c = text[i]
            if (c == '<' || c == '[' || c == 't' || c == 'T') return i
        }
        return -1
    }

    /**
     * Reads one marker and the call it introduces, starting at [start].
     *
     * A bare marker with no object after it is still consumed rather than
     * shown: the model wrote it for us, and "<tool_call>" in the middle of an
     * answer helps nobody.
     */
    private fun readCall(text: CharSequence, start: Int, final: Boolean): Scan {
        val marker = matchMarker(text, start)
        if (marker < 0) {
            return if (!final && couldBecomeMarker(text, start)) Scan.NeedMore else Scan.NotHere
        }
        val body = skipFiller(text, marker, final) ?: return needMore(final, text.length)
        if (body >= text.length) return needMore(final, text.length)
        if (text[body] != '{') {
            // A marker and then prose. Swallow only the marker itself and let
            // the scan carry on through what follows as ordinary text.
            return Scan.Found(null, marker)
        }
        val close = matchBraces(text, body)
        if (close < 0) {
            if (!final) return Scan.NeedMore
            return Scan.Found(parse(text.substring(body)), text.length)
        }
        val call = parse(text.substring(body, close))
        val end = skipClosing(text, close, final) ?: return Scan.NeedMore
        return Scan.Found(call, end)
    }

    private fun needMore(final: Boolean, end: Int): Scan =
        if (final) Scan.Found(null, end) else Scan.NeedMore

    /**
     * The end of a marker at [start], or -1. Written as a regex over a bounded
     * window rather than a hand-rolled walk, because the shapes are many and
     * all of them are trivial.
     */
    private fun matchMarker(text: CharSequence, start: Int): Int {
        val window = text.subSequence(start, minOf(text.length, start + MARKER_WINDOW))
        val match = MARKER.find(window)?.takeIf { it.range.first == 0 } ?: return -1
        return start + match.range.last + 1
    }

    /**
     * Whether what is left of the buffer could still grow into a marker. A
     * prefix of any canonical spelling counts; so does a bracket on its own,
     * which is how every bracketed spelling starts.
     */
    private fun couldBecomeMarker(text: CharSequence, start: Int): Boolean {
        val tail = text.subSequence(start, minOf(text.length, start + MARKER_WINDOW)).toString()
        if (tail.length >= MARKER_WINDOW) return false
        return MARKER_SHAPES.any { it.startsWith(tail, ignoreCase = true) }
    }

    /**
     * Past whatever a template wedged between the marker and the object:
     * whitespace, a `call:` label, a code fence, and repeats of the marker
     * itself. Null means the buffer ran out mid-filler.
     */
    private fun skipFiller(text: CharSequence, from: Int, final: Boolean): Int? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace() || c == '`') {
                i++
                continue
            }
            val marker = matchMarker(text, i)
            if (marker > i) {
                i = marker
                continue
            }
            val label = FILLER.find(text.subSequence(i, minOf(text.length, i + MARKER_WINDOW)))
                ?.takeIf { it.range.first == 0 }
            if (label != null) {
                i += label.range.last + 1
                continue
            }
            return i
        }
        return if (final) i else null
    }

    /**
     * The index just past the `}` that closes the object opened at [from], or
     * -1 when it never closes. Quoted strings are skipped whole, so a brace
     * inside a search query cannot end the object early.
     */
    private fun matchBraces(text: CharSequence, from: Int): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
                else -> Unit
            }
            i++
        }
        return -1
    }

    /** Past a closing marker and any code fence after the object. */
    private fun skipClosing(text: CharSequence, from: Int, final: Boolean): Int? {
        var i = from
        while (i < text.length && (text[i] == '`' || text[i] == ' ')) i++
        val marker = matchMarker(text, i)
        if (marker > i) {
            i = marker
            while (i < text.length && text[i] == '`') i++
            return i
        }
        // The closer may simply not have arrived yet.
        return if (final || !couldBecomeMarker(text, i)) from else null
    }

    /**
     * One payload as a call, or null when it is not one.
     *
     * `arguments` is accepted both as an object and as a string holding one —
     * models trained against OpenAI's wire format write the string form about
     * as often as the object form — and `parameters` is read as an alias.
     * A payload wrapped in extra braces is unwrapped a layer at a time, which
     * is what `{{"name":…}}` needs.
     */
    private fun parse(payload: String): AiToolCall? {
        var body = payload.trim()
        repeat(MAX_BRACE_LAYERS) {
            read(body)?.let { parsed -> return parsed }
            if (!body.startsWith("{{") || !body.endsWith("}}")) return null
            body = body.substring(1, body.length - 1).trim()
        }
        return null
    }

    private fun read(payload: String): AiToolCall? = runCatching {
        val root = Json.parseToJsonElement(payload).jsonObject
        val name = (root["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (name.isEmpty()) return null
        val args = root["arguments"] ?: root["parameters"]
        val arguments = when (args) {
            null -> "{}"
            is JsonPrimitive -> args.content.trim().ifEmpty { "{}" }
            else -> args.toString()
        }
        AiToolCall(id = "", name = name, arguments = arguments)
    }.getOrNull()

    /**
     * Every marker spelling seen in the wild, as one pattern: optional
     * bracket, optional pipes either side, optional slash for a closer, the
     * word with any separator, and the matching close bracket.
     *
     * Unbracketed, only `tool_call` counts, and the underscore is what makes
     * it count. The pattern ignores case, so allowing the spaced form here
     * would make the words "tool call" in an ordinary sentence disappear out
     * of the answer — a far worse failure than missing one odd spelling,
     * since the bracketed form already covers every template that writes it
     * that way.
     */
    private val MARKER = Regex(
        """^(?:[<\[]\s*\|?\s*/?\s*tool[_ ]?call\s*\|?\s*[>\]]?|/?tool_call)""",
        RegexOption.IGNORE_CASE,
    )

    /** `call:`, `json`, and the other labels templates put before the object. */
    private val FILLER = Regex("""^(?:call|json|arguments|args|function)\s*[:=]?""", RegexOption.IGNORE_CASE)

    /** Canonical spellings, for deciding whether a cut-off tail could grow into one. */
    private val MARKER_SHAPES = listOf(
        "<tool_call>", "</tool_call>", "<|tool_call|>", "<|tool_call>", "<tool_call|>",
        "[tool_call]", "[/tool_call]", "tool_call",
    )

    /** Longest marker worth looking at; keeps every scan bounded. */
    private const val MARKER_WINDOW = 20

    /** How many layers of stray braces a payload may be wrapped in. */
    private const val MAX_BRACE_LAYERS = 3
}
