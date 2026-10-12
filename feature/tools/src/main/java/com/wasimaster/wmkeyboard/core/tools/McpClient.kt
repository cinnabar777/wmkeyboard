package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.feature.R
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One server's tools as they were last listed, and what it takes to call them:
 * which generation of the protocol it speaks and at which version. Kept by
 * [McpCatalogStore], so a run offers the tools and calls them without first
 * asking the server who it is.
 *
 * [url] is the address the list came from. When the user edits the server's
 * address the entry no longer describes it, and [McpCatalogStore.entryFor]
 * treats it as missing.
 */
@Serializable
data class McpCatalogEntry(
    val url: String,
    val era: McpEra,
    val version: String,
    val serverName: String = "",
    val serverVersion: String = "",
    val tools: List<McpTool> = emptyList(),
    val fetchedAt: Long = 0L,
)

/** An MCP failure with words of our own; see [McpClient.message]. */
class McpException(
    @StringRes val messageRes: Int,
    /** The server's own words, for the messages that quote them. */
    val detail: String = "",
) : IOException(detail.ifEmpty { "MCP failure" })

/**
 * Talks to the MCP servers the user added (#573). Blocking; call on an IO
 * dispatcher.
 *
 * Only what calling tools needs: find out how a server speaks, list its tools,
 * call one. Three generations of the protocol are in use and a phone cannot
 * pick which server it is pointed at, so all three are spoken:
 *
 * - **Modern** (2026-07-28): stateless. Every POST names its version in `_meta`
 *   and in headers. Found by `server/discover`.
 * - **Legacy Streamable HTTP** (2025-03-26 to 2025-11-25): `initialize` first,
 *   then the session id the server hands back on every request.
 * - **HTTP+SSE** (2024-11-05): a GET event stream that names a POST address,
 *   with every answer coming back down the stream.
 *
 * No connection is kept open between calls. A keyboard is hidden and shown all
 * day and its process can be killed at any moment, so a held socket would
 * mostly be a dead one. An old HTTP server's session id is remembered in memory,
 * because asking for a new one costs two requests per call.
 */
object McpClient {

    private const val ACCEPT_BOTH = "application/json, text/event-stream"
    private const val SESSION_HEADER = "Mcp-Session-Id"
    private const val VERSION_HEADER = "MCP-Protocol-Version"
    private const val AUTH_HEADER = "WWW-Authenticate"
    private val WANT = setOf(SESSION_HEADER, AUTH_HEADER)

    /** `tools/list` pages read at most; a server with more tools than this has more than any prompt can hold. */
    private const val MAX_PAGES = 10

    private const val TIMEOUT_MS = 60_000

    private val ids = AtomicInteger(1)

    /** Old HTTP servers' sessions, by server id. */
    private val sessions = ConcurrentHashMap<String, String>()

    /** How one server is spoken to, once found out. */
    private data class Link(val era: McpEra, val version: String)

    /** A status outside 2xx with no JSON-RPC answer in its body. */
    private class HttpStatus(val status: Int, val apiMessage: String?) : IOException("HTTP $status")

    // ---- public -----------------------------------------------------------

    /**
     * Finds out how [server] speaks and lists its tools. What the settings
     * screen's Connect button runs, and the only place a server is probed.
     */
    fun connect(server: McpServerSpec, headers: List<McpHeader>): McpCatalogEntry = guarded {
        val sent = headerMap(headers)
        sessions.remove(server.id)
        val url = server.url.trim()
        if (server.transport == McpTransport.SSE) return@guarded sseConnect(url, sent)
        val found = try {
            probe(server.id, url, sent)
        } catch (e: HttpStatus) {
            if (server.transport == McpTransport.AUTO && e.status in OLD_TRANSPORT_STATUSES) {
                return@guarded sseConnect(url, sent)
            }
            throw e
        }
        val tools = listTools { method, params -> httpCall(server.id, url, sent, found.link, method, params) }
        McpCatalogEntry(
            url = url,
            era = found.link.era,
            version = found.link.version,
            serverName = found.info.first,
            serverVersion = found.info.second,
            tools = tools,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Calls one tool. [entry] says how the server speaks; when that turns out
     * to be out of date (the server was upgraded, its session expired), the
     * call finds out again and is tried once more.
     */
    fun call(
        server: McpServerSpec,
        headers: List<McpHeader>,
        entry: McpCatalogEntry,
        tool: String,
        argumentsJson: String,
    ): McpProtocol.Outcome = guarded {
        val sent = headerMap(headers)
        val url = server.url.trim()
        val params = McpProtocol.callParams(tool, argumentsJson)
        if (entry.era == McpEra.SSE) {
            return@guarded outcomeOf(sseSession(url, sent) { rpc -> rpc("tools/call", params) })
        }
        val link = Link(entry.era, entry.version)
        val reply = try {
            httpCall(server.id, url, sent, link, "tools/call", params)
        } catch (e: HttpStatus) {
            if (e.status !in OLD_TRANSPORT_STATUSES) throw e
            // The server no longer speaks the way it did when its tools were
            // listed. Find out how it speaks now, once.
            sessions.remove(server.id)
            val fresh = try {
                probe(server.id, url, sent).link
            } catch (again: HttpStatus) {
                if (server.transport == McpTransport.STREAMABLE_HTTP) throw again
                return@guarded outcomeOf(sseSession(url, sent) { rpc -> rpc("tools/call", params) })
            }
            httpCall(server.id, url, sent, fresh, "tools/call", params)
        }
        outcomeOf(reply)
    }

    /** Runs [block], turning a bare status failure into one with words. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: HttpStatus) {
        throw statusFailure(e)
    }

    /** A line for the user about [failure]: ours for an MCP failure, [ToolHttp]'s for the rest. */
    fun message(context: Context, failure: Throwable): String = when (failure) {
        is McpException -> if (failure.detail.isEmpty()) {
            context.getString(failure.messageRes)
        } else {
            context.getString(failure.messageRes, failure.detail)
        }
        else -> ToolHttp.friendlyMessage(context, failure)
    }

    /** Forgets every remembered session, for when servers are edited or removed. */
    fun forget(serverId: String) {
        sessions.remove(serverId)
    }

    // ---- finding out how a server speaks ----------------------------------

    private class Probe(val link: Link, val info: Pair<String, String>)

    /**
     * The modern probe first, as the spec asks. A modern server answers it;
     * an old one answers with an error of its own invention, or not as
     * JSON-RPC at all, and is then asked to `initialize`.
     */
    private fun probe(serverId: String, url: String, sent: Map<String, String>): Probe {
        val modern = Link(McpEra.MODERN, McpProtocol.MODERN_VERSION)
        val discover = try {
            post(url, sent, modern, null, "server/discover", JsonObject(emptyMap()))
        } catch (e: HttpStatus) {
            // A plain 404 or 405 can be an old HTTP server too, which is why
            // this asks `initialize` before giving up on the address.
            if (e.status !in OLD_TRANSPORT_STATUSES) throw e
            null
        }
        when (discover) {
            is McpReply.Result -> {
                val versions = McpProtocol.discoveredVersions(discover.result)
                if (versions.isEmpty() || McpProtocol.MODERN_VERSION in versions) {
                    return Probe(modern, McpProtocol.serverInfo(discover.result))
                }
            }
            is McpReply.Error -> if (McpProtocol.isModernError(discover)) {
                val versions = McpProtocol.supportedVersions(discover)
                if (versions.isNotEmpty() && versions.none { it < McpProtocol.MODERN_VERSION }) {
                    throw McpException(R.string.ftools_mcp_error_no_version)
                }
            }
            null -> Unit
        }
        return initialize(serverId, url, sent)
    }

    /** `initialize` and `notifications/initialized`, the old handshake. */
    private fun initialize(serverId: String, url: String, sent: Map<String, String>): Probe {
        val id = ids.getAndIncrement()
        val body = McpProtocol.request(
            id,
            "initialize",
            McpProtocol.initializeParams(McpProtocol.LEGACY_VERSION, BuildConfig.VERSION_NAME),
        )
        var session: String? = null
        val reply = ToolHttp.exchange(
            url, "POST", body,
            headers = sent + ("Accept" to ACCEPT_BOTH),
            wantHeaders = WANT,
            timeoutMs = TIMEOUT_MS,
            source = NetSource.AI_MCP,
        ) { exchange ->
            session = exchange.headers[SESSION_HEADER]
            answer(exchange, id)
        }
        val result = when (reply) {
            is McpReply.Result -> reply.result
            is McpReply.Error -> throw McpException(R.string.ftools_mcp_error_server, reply.message)
        }
        val version = McpProtocol.negotiatedVersion(result) ?: McpProtocol.LEGACY_VERSION
        val given = session
        if (given != null) sessions[serverId] = given else sessions.remove(serverId)
        val link = Link(McpEra.LEGACY_HTTP, version)
        // Answered with 202 and no body; a server that says anything else
        // has still been told, and the next request is the real test.
        runCatching {
            ToolHttp.exchange(
                url, "POST", McpProtocol.notification("notifications/initialized"),
                headers = legacyHeaders(serverId, sent, link),
                timeoutMs = TIMEOUT_MS,
                source = NetSource.AI_MCP,
            ) { }
        }
        return Probe(link, McpProtocol.serverInfo(result))
    }

    // ---- requests over HTTP ------------------------------------------------

    /**
     * One request over the current transport, re-doing the old handshake once
     * when an old server says the session it gave out is gone (a 404).
     */
    private fun httpCall(
        serverId: String,
        url: String,
        sent: Map<String, String>,
        link: Link,
        method: String,
        params: JsonObject,
    ): McpReply {
        if (link.era == McpEra.MODERN) return post(url, sent, link, serverId, method, params)
        if (sessions[serverId] == null) initialize(serverId, url, sent)
        return try {
            post(url, sent, link, serverId, method, params)
        } catch (e: HttpStatus) {
            if (e.status != HTTP_NOT_FOUND || sessions[serverId] == null) throw e
            sessions.remove(serverId)
            initialize(serverId, url, sent)
            post(url, sent, link, serverId, method, params)
        }
    }

    private fun post(
        url: String,
        sent: Map<String, String>,
        link: Link,
        serverId: String?,
        method: String,
        params: JsonObject,
    ): McpReply {
        val id = ids.getAndIncrement()
        val modern = link.era == McpEra.MODERN
        val body = McpProtocol.request(
            id, method, params,
            modern = if (modern) link.version else null,
            clientVersion = BuildConfig.VERSION_NAME,
        )
        val headers = if (modern) {
            buildMap {
                putAll(sent)
                put("Accept", ACCEPT_BOTH)
                put(VERSION_HEADER, link.version)
                put("Mcp-Method", method)
                // Required for a call. A name that is not plain ASCII cannot
                // go in a header as it is; the spec's encoded form is left to
                // a server that names its tools that way, which none does yet.
                (params["name"] as? JsonPrimitive)?.content
                    ?.takeIf { name -> name.all { it.code in 0x20..0x7e } }
                    ?.let { put("Mcp-Name", it) }
            }
        } else {
            legacyHeaders(serverId.orEmpty(), sent, link)
        }
        return ToolHttp.exchange(
            url, "POST", body,
            headers = headers,
            wantHeaders = WANT,
            timeoutMs = TIMEOUT_MS,
            source = NetSource.AI_MCP,
        ) { exchange -> answer(exchange, id) }
    }

    private fun legacyHeaders(serverId: String, sent: Map<String, String>, link: Link): Map<String, String> =
        buildMap {
            putAll(sent)
            put("Accept", ACCEPT_BOTH)
            put(VERSION_HEADER, link.version)
            sessions[serverId]?.let { put(SESSION_HEADER, it) }
        }

    /**
     * The answer to request [id] in one response, whether the server sent it
     * as one JSON object or down an event stream after some notifications.
     */
    private fun answer(exchange: ToolHttp.Exchange, id: Int): McpReply {
        val status = exchange.status
        if (status == HTTP_UNAUTHORIZED) throw unauthorized(exchange)
        if (status !in 200..299) {
            val text = exchange.body?.readText().orEmpty()
            McpProtocol.reply(text, id)?.let { return it }
            McpProtocol.anonymousError(text)?.let { return it }
            throw HttpStatus(status, ToolHttp.apiErrorText(text))
        }
        val reader = exchange.body ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
        if (exchange.contentType == "text/event-stream") {
            return streamed(reader, id) ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
        }
        val text = reader.readText()
        return McpProtocol.reply(text, id)
            ?: McpProtocol.anonymousError(text)
            ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
    }

    /** Reads events off [reader] until one carries the answer to [id]. */
    private fun streamed(reader: BufferedReader, id: Int): McpReply? {
        val sse = McpSseReader()
        while (true) {
            val line = reader.readLine()
            val event = if (line == null) sse.end() else sse.line(line)
            if (event != null && event.event == "message") {
                McpProtocol.reply(event.data, id)?.let { return it }
            }
            if (line == null) return null
        }
    }

    /**
     * A 401. A server that names an OAuth authorisation server wants a
     * sign-in this keyboard cannot do yet, which deserves saying plainly
     * rather than as "your key was rejected" to someone who never gave a key.
     */
    private fun unauthorized(exchange: ToolHttp.Exchange): IOException {
        val challenge = exchange.headers[AUTH_HEADER].orEmpty()
        return if (challenge.startsWith("Bearer", ignoreCase = true) && "resource_metadata" in challenge) {
            McpException(R.string.ftools_mcp_error_sign_in)
        } else {
            ToolHttp.httpFailure(HTTP_UNAUTHORIZED, exchange.body?.readText())
        }
    }

    private fun statusFailure(e: HttpStatus): IOException =
        if (e.status == HTTP_NOT_FOUND || e.status == HTTP_BAD_METHOD) {
            McpException(R.string.ftools_mcp_error_not_mcp)
        } else {
            ToolHttpException(ToolHttp.statusMessageRes(e.status), e.status, e.apiMessage)
        }

    // ---- the old HTTP+SSE transport ----------------------------------------

    private fun sseConnect(url: String, sent: Map<String, String>): McpCatalogEntry {
        var info = "" to ""
        var version = McpProtocol.SSE_VERSION
        val tools = sseSession(url, sent, onReady = { result ->
            info = McpProtocol.serverInfo(result)
            McpProtocol.negotiatedVersion(result)?.let { version = it }
        }) { rpc -> listTools(rpc) }
        return McpCatalogEntry(
            url = url,
            era = McpEra.SSE,
            version = version,
            serverName = info.first,
            serverVersion = info.second,
            tools = tools,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Opens the event stream, waits for the address to POST to, runs the
     * handshake, then hands [work] a way to make requests whose answers come
     * back down the same stream. The stream closes when [work] returns.
     */
    private fun <T> sseSession(
        url: String,
        sent: Map<String, String>,
        onReady: (JsonObject) -> Unit = {},
        work: ((String, JsonObject) -> McpReply) -> T,
    ): T = ToolHttp.exchange(
        url, "GET",
        headers = sent + ("Accept" to "text/event-stream"),
        wantHeaders = WANT,
        timeoutMs = TIMEOUT_MS,
        source = NetSource.AI_MCP,
    ) { stream ->
        if (stream.status == HTTP_UNAUTHORIZED) throw unauthorized(stream)
        if (stream.status !in 200..299) {
            val text = stream.body?.readText()
            throw statusFailure(HttpStatus(stream.status, ToolHttp.apiErrorText(text)))
        }
        val reader = stream.body ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
        val events = McpSseReader()
        fun nextEvent(): McpSseEvent? {
            while (true) {
                val line = reader.readLine() ?: return events.end()
                events.line(line)?.let { return it }
            }
        }
        fun awaitReply(id: Int): McpReply {
            while (true) {
                val event = nextEvent() ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
                if (event.event == "message") McpProtocol.reply(event.data, id)?.let { return it }
            }
        }
        var endpoint: String? = null
        while (endpoint == null) {
            val event = nextEvent() ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
            if (event.event == "endpoint") endpoint = McpProtocol.endpointUrl(url, event.data)
        }
        val target: String = endpoint ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
        val rpc: (String, JsonObject) -> McpReply = { method, params ->
            val id = ids.getAndIncrement()
            val direct = ToolHttp.exchange(
                target, "POST", McpProtocol.request(id, method, params),
                headers = sent + ("Accept" to ACCEPT_BOTH),
                wantHeaders = WANT,
                timeoutMs = TIMEOUT_MS,
                source = NetSource.AI_MCP,
            ) { exchange ->
                if (exchange.status == HTTP_UNAUTHORIZED) throw unauthorized(exchange)
                // Normally 202 and nothing else; the answer is on the stream.
                // A server that answers in the POST itself is believed too.
                val text = exchange.body?.readText().orEmpty()
                McpProtocol.reply(text, id) ?: if (exchange.status !in 200..299) {
                    throw statusFailure(HttpStatus(exchange.status, ToolHttp.apiErrorText(text)))
                } else {
                    null
                }
            }
            direct ?: awaitReply(id)
        }
        val init = rpc("initialize", McpProtocol.initializeParams(McpProtocol.SSE_VERSION, BuildConfig.VERSION_NAME))
        when (init) {
            is McpReply.Result -> onReady(init.result)
            is McpReply.Error -> throw McpException(R.string.ftools_mcp_error_server, init.message)
        }
        runCatching {
            ToolHttp.exchange(
                target, "POST", McpProtocol.notification("notifications/initialized"),
                headers = sent,
                timeoutMs = TIMEOUT_MS,
                source = NetSource.AI_MCP,
            ) { }
        }
        work(rpc)
    }

    // ---- shared --------------------------------------------------------------

    private fun listTools(rpc: (String, JsonObject) -> McpReply): List<McpTool> {
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = when (val reply = rpc("tools/list", McpProtocol.listParams(cursor))) {
                is McpReply.Result -> McpProtocol.toolPage(reply.result)
                is McpReply.Error -> throw McpException(R.string.ftools_mcp_error_server, reply.message)
            }
            tools += page.tools
            cursor = page.nextCursor ?: return tools.distinctBy { it.name }
        }
        return tools.distinctBy { it.name }
    }

    private fun outcomeOf(reply: McpReply): McpProtocol.Outcome = when (reply) {
        is McpReply.Error -> throw McpException(R.string.ftools_mcp_error_server, reply.message)
        is McpReply.Result -> {
            if (McpProtocol.needsInput(reply.result)) throw McpException(R.string.ftools_mcp_error_needs_input)
            McpProtocol.outcome(reply.result)
        }
    }

    /**
     * The user's headers, cleaned: no blank names, and none of the ones the
     * protocol itself sets, which a pasted config sometimes carries.
     */
    private fun headerMap(headers: List<McpHeader>): Map<String, String> =
        headers.filter { it.name.isNotBlank() && it.name.trim().lowercase() !in RESERVED }
            .associate { it.name.trim() to it.value.trim() }

    private val RESERVED = setOf(
        "accept", "content-type", "content-length", "host",
        "mcp-session-id", "mcp-protocol-version", "mcp-method", "mcp-name",
    )

    /** What an old server answers a POST it does not understand with. */
    private val OLD_TRANSPORT_STATUSES = setOf(400, 404, 405)

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_BAD_METHOD = 405
}
