package com.wasimaster.wmkeyboard.core.tools

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One tool an MCP server offers (#573), as `tools/list` described it.
 *
 * [readOnly] and [destructive] are the server's own annotations
 * (`readOnlyHint`, `destructiveHint`). They are hints, and the spec says to
 * treat them as untrusted unless the server is. Here they decide only where a
 * tool *starts*: a read-only one runs without asking, until the user says
 * otherwise. The user's own choice always wins.
 */
@Serializable
data class McpTool(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
) {
    /** What a person reads: the server's title for the tool, or its name. */
    val label: String get() = title.trim().ifEmpty { name }
}

/**
 * Which generation of the protocol a server speaks. Found once, on Connect, and
 * kept with the server's tool list, so a run never spends a request finding out.
 */
@Serializable
enum class McpEra {
    /** 2026-07-28 and later: stateless, every request carries its version. */
    MODERN,

    /** 2025-03-26 to 2025-11-25: `initialize` first, then a session id on every request. */
    LEGACY_HTTP,

    /** 2024-11-05: a GET event stream, and POSTs to the address it names. */
    SSE,
}

/** A JSON-RPC answer to one request. */
sealed interface McpReply {
    data class Result(val result: JsonObject) : McpReply

    data class Error(val code: Int, val message: String, val data: JsonElement?) : McpReply
}

/** One server-sent event: its type and its whole data, lines joined. */
data class McpSseEvent(val event: String, val data: String)

/**
 * Reads a `text/event-stream` one line at a time. Both MCP transports use it:
 * the current one may answer a POST with a stream, and the old one sends every
 * answer down a GET stream.
 *
 * Only the two fields MCP uses are read; `id:` and `retry:` matter to a client
 * that resumes a stream, and this one never does.
 */
class McpSseReader {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false

    /** Takes one line without its line break; returns an event when the line ends one. */
    fun line(line: String): McpSseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        var value = if (':' in line) line.substringAfter(':') else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            else -> Unit
        }
        return null
    }

    /** The end of the stream: an event still being read counts as finished. */
    fun end(): McpSseEvent? = dispatch()

    private fun dispatch(): McpSseEvent? {
        val ready = if (hasData) McpSseEvent(event.ifEmpty { "message" }, data.toString()) else null
        event = ""
        data.setLength(0)
        hasData = false
        return ready
    }
}

/**
 * The wire format of the Model Context Protocol, as far as a client that only
 * calls tools needs it (#573). Pure: no network, no Android. `McpClient` in
 * `:feature:tools` moves the bytes.
 */
object McpProtocol {

    /** The version a modern request carries. */
    const val MODERN_VERSION = "2026-07-28"

    /** The version offered to a server that wants `initialize`. */
    const val LEGACY_VERSION = "2025-11-25"

    /** What the old HTTP+SSE transport speaks. */
    const val SSE_VERSION = "2024-11-05"

    const val CLIENT_NAME = "WM Keyboard"

    const val UNSUPPORTED_VERSION = -32022

    /** The error codes only a modern server sends; any other error means an old server. */
    private val MODERN_ERRORS = -32022..-32020

    /** Longest name every provider accepts for a function. */
    const val MAX_NAME = 64

    /** A tool description longer than this is cut: it is paid for in every prompt. */
    const val MAX_DESCRIPTION = 1_000

    /** Characters of one call's answer handed to the model. */
    const val MAX_RESULT = 8_000

    private val json = Json { ignoreUnknownKeys = true }

    // ---- requests ---------------------------------------------------------

    /**
     * One request. [modern] is the version a modern request names in its
     * `_meta`; an old server gets no `_meta` at all, since it never asked for one.
     */
    fun request(
        id: Int,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        modern: String? = null,
        clientVersion: String = "",
    ): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", if (modern == null) params else withMeta(params, modern, clientVersion))
    }.toString()

    /** One notification: no id, and nothing comes back. */
    fun notification(method: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }.toString()

    private fun withMeta(params: JsonObject, version: String, clientVersion: String): JsonObject {
        val meta = buildJsonObject {
            (params["_meta"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put("io.modelcontextprotocol/protocolVersion", version)
            putJsonObject("io.modelcontextprotocol/clientInfo") {
                put("name", CLIENT_NAME)
                put("version", clientVersion.ifBlank { "1" })
            }
            putJsonObject("io.modelcontextprotocol/clientCapabilities") {}
        }
        return JsonObject(params + ("_meta" to meta))
    }

    /** What an old server's `initialize` takes. */
    fun initializeParams(version: String, clientVersion: String): JsonObject = buildJsonObject {
        put("protocolVersion", version)
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") {
            put("name", CLIENT_NAME)
            put("version", clientVersion.ifBlank { "1" })
        }
    }

    fun listParams(cursor: String?): JsonObject = buildJsonObject {
        if (!cursor.isNullOrEmpty()) put("cursor", cursor)
    }

    /**
     * What `tools/call` takes. [argumentsJson] came from a model, so anything
     * that is not a JSON object is sent as no arguments rather than refused
     * here: the server's own validation error says more than ours would.
     */
    fun callParams(tool: String, argumentsJson: String): JsonObject = buildJsonObject {
        put("name", tool)
        put("arguments", callArguments(argumentsJson))
    }

    internal fun callArguments(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())

    // ---- answers ----------------------------------------------------------

    /**
     * The answer to request [id] inside one message, or null when the message
     * is something else: a notification, a request from the server, or the
     * answer to another request. A JSON array is read as a batch, which old
     * servers were allowed to send.
     */
    fun reply(message: String, id: Int): McpReply? {
        val root = runCatching { json.parseToJsonElement(message) }.getOrNull() ?: return null
        val candidates = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        for (candidate in candidates) {
            if ((candidate["id"] as? JsonPrimitive)?.contentOrNull != id.toString()) continue
            (candidate["error"] as? JsonObject)?.let { return error(it) }
            (candidate["result"] as? JsonObject)?.let { return McpReply.Result(it) }
        }
        return null
    }

    /** An error with no `id` at all, which a server sends when it could not read the request. */
    fun anonymousError(message: String): McpReply.Error? {
        val root = runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return null
        if (root["jsonrpc"] == null) return null
        return (root["error"] as? JsonObject)?.let(::error)
    }

    private fun error(obj: JsonObject) = McpReply.Error(
        code = (obj["code"] as? JsonPrimitive)?.intOrNull ?: 0,
        message = (obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        data = obj["data"],
    )

    /** Whether only a modern server would send [error]. */
    fun isModernError(error: McpReply.Error): Boolean = error.code in MODERN_ERRORS

    /** The versions a server listed when it refused ours, or empty. */
    fun supportedVersions(error: McpReply.Error): List<String> =
        ((error.data as? JsonObject)?.get("supported") as? JsonArray).strings()

    /** The versions a `server/discover` result lists. */
    fun discoveredVersions(result: JsonObject): List<String> =
        (result["supportedVersions"] as? JsonArray).strings()

    private fun JsonArray?.strings(): List<String> =
        this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /**
     * Whether a modern result is asking the client for something before it
     * can finish (a question for the user, a model completion). This client
     * has nothing to answer those with, so the call is reported as failed.
     */
    fun needsInput(result: JsonObject): Boolean {
        val type = (result["resultType"] as? JsonPrimitive)?.contentOrNull ?: return false
        return type != "complete"
    }

    /** The server's own name and version, from either generation's answer. */
    fun serverInfo(result: JsonObject): Pair<String, String> {
        val info = (result["serverInfo"] as? JsonObject)
            ?: ((result["_meta"] as? JsonObject)?.get("io.modelcontextprotocol/serverInfo") as? JsonObject)
        val name = (info?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val version = (info?.get("version") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return name to version
    }

    /** The version an old server agreed to in its `initialize` answer. */
    fun negotiatedVersion(result: JsonObject): String? =
        (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull

    /**
     * The address the old transport's `endpoint` event names. Usually a path
     * with a session query, relative to the stream's own address.
     */
    fun endpointUrl(base: String, data: String): String? {
        val target = data.trim()
        if (target.isEmpty()) return null
        return runCatching { URI(base).resolve(target).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    // ---- tools ------------------------------------------------------------

    /** One page of `tools/list`. */
    data class ToolPage(val tools: List<McpTool>, val nextCursor: String?)

    fun toolPage(result: JsonObject): ToolPage {
        val tools = (result["tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::tool) }
        val cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        return ToolPage(tools, cursor)
    }

    private fun tool(obj: JsonObject): McpTool? {
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) return null
        val annotations = obj["annotations"] as? JsonObject
        val title = (obj["title"] as? JsonPrimitive)?.contentOrNull
            ?: (annotations?.get("title") as? JsonPrimitive)?.contentOrNull
        return McpTool(
            name = name,
            title = title.orEmpty().trim(),
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim(),
            inputSchema = obj["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            readOnly = annotations.flag("readOnlyHint") == true,
            // The spec's default for an unmarked tool is destructive, but
            // that matters only for a tool that is not read-only, which asks
            // first anyway. So only an explicit true counts here.
            destructive = annotations.flag("destructiveHint") == true,
        )
    }

    private fun JsonObject?.flag(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    /**
     * The name a model is shown for [tool] on [server]: both, so two servers
     * can each have a `search`, and so no MCP tool can take the name of one of
     * ours. Only letters, digits, `_` and `-`, starting with a letter, and at
     * most [MAX_NAME] characters, which is the strictest of what the providers
     * accept. [taken] holds the names already handed out in this run.
     */
    fun exposedName(server: String, tool: String, taken: Set<String>): String {
        val prefix = slug(server).take(SERVER_SLUG).ifEmpty { "mcp" }.let {
            if (it.first().isLetter()) it else "m$it"
        }
        val base = (prefix + "__" + slug(tool).ifEmpty { "tool" }).take(MAX_NAME)
        if (base !in taken) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take(MAX_NAME - suffix.length) + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    private fun slug(text: String): String =
        text.trim().map { c -> if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') c else '_' }
            .joinToString("")
            .replace(UNDERSCORES, "_")
            .trim('_')

    /**
     * [schema] in the shape every provider accepts for a function's
     * parameters: an object, with properties. A server may send `{}` for a
     * tool that takes nothing, which OpenAI refuses.
     */
    fun patchSchema(schema: JsonObject): JsonObject {
        var out = schema
        if (out["type"] == null) out = JsonObject(out + ("type" to JsonPrimitive("object")))
        if (out["properties"] !is JsonObject) out = JsonObject(out + ("properties" to JsonObject(emptyMap())))
        return out
    }

    /** [tool] as the spec a model is offered, under [exposedName]. */
    fun spec(exposedName: String, serverLabel: String, tool: McpTool): AiToolSpec {
        val about = tool.description.ifEmpty { tool.label }
        val description = "$about (from the MCP server \"$serverLabel\")"
        return AiToolSpec(
            name = exposedName,
            description = if (description.length > MAX_DESCRIPTION) {
                description.take(MAX_DESCRIPTION - 1).trimEnd() + "…"
            } else {
                description
            },
            parameters = patchSchema(tool.inputSchema),
        )
    }

    // ---- call results ------------------------------------------------------

    /**
     * What one call gave back, ready for the model. [omitted] names the parts
     * that are not text (`image`, `audio`, `resource_link`), which the runner
     * mentions so the model knows something was there.
     */
    data class Outcome(
        val text: String,
        val isError: Boolean,
        val omitted: List<String>,
        val truncated: Boolean,
    )

    fun outcome(result: JsonObject, maxChars: Int = MAX_RESULT): Outcome {
        val parts = (result["content"] as? JsonArray).orEmpty()
        val texts = ArrayList<String>()
        val omitted = ArrayList<String>()
        for (part in parts) {
            val obj = part as? JsonObject ?: continue
            when (val type = (obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()) {
                "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull?.let(texts::add)
                "resource" -> {
                    // An embedded resource may carry text of its own, which
                    // is as readable as a text part.
                    val text = ((obj["resource"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                    if (text != null) texts.add(text) else omitted.add(type)
                }
                else -> omitted.add(type.ifEmpty { "unknown" })
            }
        }
        var text = texts.joinToString("\n\n").trim()
        if (text.isEmpty()) {
            // A tool that answers only with structured data is still an answer.
            result["structuredContent"]?.let { text = it.toString() }
        }
        val cut = text.length > maxChars
        return Outcome(
            text = if (cut) text.take(maxChars) else text,
            isError = (result["isError"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true,
            omitted = omitted.distinct(),
            truncated = cut,
        )
    }

    /**
     * [argumentsJson] as one short line, for the record under an answer. The
     * whole of it is shown only while the user is being asked to allow it.
     */
    fun preview(argumentsJson: String, max: Int = PREVIEW_CHARS): String {
        val args = callArguments(argumentsJson)
        if (args.isEmpty()) return ""
        val line = args.entries.joinToString(", ") { (key, value) ->
            val shown = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
            "$key: $shown"
        }.replace('\n', ' ')
        return if (line.length > max) line.take(max - 1).trimEnd() + "…" else line
    }

    /** [argumentsJson] indented, for the approval card. */
    fun pretty(argumentsJson: String): String {
        val args = callArguments(argumentsJson)
        if (args.isEmpty()) return ""
        return PRETTY.encodeToString(JsonObject.serializer(), args)
    }

    private val PRETTY = Json { prettyPrint = true }
    private val UNDERSCORES = Regex("_+")
    private const val SERVER_SLUG = 20
    private const val PREVIEW_CHARS = 80
}

/**
 * Reads an MCP configuration someone copied from another app's settings
 * (#573), so a server can be added by pasting rather than typed field by field.
 *
 * Takes the shapes in use: Claude Desktop and Cursor's
 * `{"mcpServers": {name: {...}}}`, VS Code's `{"servers": {name: {...}}}`,
 * the inner map on its own, and one server object. A server that is started
 * as a local command (`"command": "npx"`) cannot run on a phone, so it is
 * listed as skipped with that reason rather than silently dropped.
 */
object McpConfigImport {

    data class Server(
        val name: String,
        val url: String,
        val transport: McpTransport,
        val headers: List<McpHeader>,
    )

    enum class SkipReason {
        /** Started as a local program; Android has nothing to start it with. */
        COMMAND,

        /** No address at all. */
        NO_URL,

        /** An address that is not http or https. */
        BAD_URL,
    }

    data class Skipped(val name: String, val reason: SkipReason)

    data class Result(val servers: List<Server>, val skipped: List<Skipped>) {
        val isEmpty: Boolean get() = servers.isEmpty() && skipped.isEmpty()
    }

    /** Null when [text] is not JSON at all, or not an object. */
    fun parse(text: String): Result? {
        val root = runCatching { Json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull() ?: return null
        val map = (root["mcpServers"] as? JsonObject)
            ?: (root["servers"] as? JsonObject)
            ?: (root["mcp"] as? JsonObject)?.let { it["servers"] as? JsonObject }
        val entries: List<Pair<String, JsonObject>> = when {
            map != null -> map.entries.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to it } }
            looksLikeServer(root) -> listOf(stringOf(root, "name").orEmpty() to root)
            // A bare map of servers, with no wrapper.
            else -> root.entries.mapNotNull { (name, value) ->
                (value as? JsonObject)?.takeIf(::looksLikeServer)?.let { name to it }
            }
        }
        val servers = ArrayList<Server>()
        val skipped = ArrayList<Skipped>()
        for ((name, body) in entries) {
            val url = stringOf(body, "url") ?: stringOf(body, "serverUrl") ?: stringOf(body, "endpoint")
            when {
                url == null && body["command"] != null -> skipped.add(Skipped(name, SkipReason.COMMAND))
                url == null -> skipped.add(Skipped(name, SkipReason.NO_URL))
                !isHttp(url) -> skipped.add(Skipped(name, SkipReason.BAD_URL))
                else -> servers.add(Server(name.trim(), url.trim(), transportOf(body, url), headersOf(body)))
            }
        }
        return Result(servers, skipped)
    }

    private fun looksLikeServer(obj: JsonObject): Boolean =
        obj["url"] is JsonPrimitive || obj["serverUrl"] is JsonPrimitive || obj["command"] is JsonPrimitive

    private fun stringOf(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun isHttp(url: String): Boolean {
        val lower = url.trim().lowercase()
        val rest = when {
            lower.startsWith("https://") -> lower.removePrefix("https://")
            lower.startsWith("http://") -> lower.removePrefix("http://")
            else -> return false
        }
        return rest.substringBefore('/').isNotEmpty()
    }

    private fun transportOf(body: JsonObject, url: String): McpTransport {
        val type = (stringOf(body, "type") ?: stringOf(body, "transport"))?.lowercase()
        return when {
            type == null -> if (url.trimEnd('/').endsWith("/sse")) McpTransport.SSE else McpTransport.AUTO
            "sse" in type -> McpTransport.SSE
            "http" in type || "stream" in type -> McpTransport.STREAMABLE_HTTP
            else -> McpTransport.AUTO
        }
    }

    private fun headersOf(body: JsonObject): List<McpHeader> =
        (body["headers"] as? JsonObject)?.entries?.mapNotNull { (name, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { McpHeader(name, it) }
        }.orEmpty()
}
