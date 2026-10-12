package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * How the keyboard talks to one MCP server (#573).
 *
 * [AUTO] is what a pasted address gets: the client tries the current
 * Streamable HTTP transport first and falls back to the old HTTP+SSE one when
 * the server answers like an old server. [SSE] skips straight to the old one,
 * for a server whose POST endpoint answers something misleading.
 */
@Serializable
enum class McpTransport { AUTO, STREAMABLE_HTTP, SSE }

/**
 * What a model may do with one MCP tool.
 *
 * A tool can send mail or delete a file, and a prompt hidden in the text the
 * user is editing could steer a model into calling it. So a tool the server
 * does not mark read-only starts on [ASK], which stops the run and shows the
 * user the call before it goes anywhere. [OFF] keeps the tool out of the
 * prompt altogether, which also saves the tokens its description costs.
 */
@Serializable
enum class McpToolPolicy {
    ALLOW,
    ASK,
    OFF,
    ;

    companion object {
        /** The policy of a tool the user has not set: read-only runs, the rest ask. */
        fun defaultFor(tool: McpTool): McpToolPolicy = if (tool.readOnly && !tool.destructive) ALLOW else ASK
    }
}

/** One header sent with every request to a server: `Authorization: Bearer …`, an API key. */
@Serializable
data class McpHeader(
    val name: String = "",
    val value: String = "",
)

/**
 * One MCP server the user added.
 *
 * Its headers are not here. They usually carry a token, so they are stored
 * under their own preference, which a settings export leaves out unless asked
 * (see `SettingsBackup.SECRET_KEYS`). A backup without secrets then still
 * brings the servers back, and only the tokens have to be typed in again.
 *
 * [toolPolicies] holds only what the user changed. A tool with no entry gets
 * [McpToolPolicy.defaultFor], decided from what the server says about it, so a
 * server that later marks a tool read-only is believed without a migration.
 */
@Serializable
data class McpServerSpec(
    val id: String,
    val name: String = "",
    val url: String = "",
    val transport: McpTransport = McpTransport.AUTO,
    val enabled: Boolean = true,
    val toolPolicies: Map<String, McpToolPolicy> = emptyMap(),
) {
    fun policyFor(tool: McpTool): McpToolPolicy = toolPolicies[tool.name] ?: McpToolPolicy.defaultFor(tool)

    /** What the user sees: the name they gave it, or the server's host. */
    val label: String get() = name.trim().ifEmpty { AiToolActivity.domainOf(url) }

    companion object {
        /** Prefix of a server's id, followed by the time it was added. */
        const val ID_PREFIX = "mcp_"
    }
}

/** The server list and the header map as preference strings. */
object McpServerCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        // An enum value a newer version wrote falls back to the field's
        // default rather than dropping the whole list.
        coerceInputValues = true
        encodeDefaults = false
    }

    private val headersSerializer =
        MapSerializer(String.serializer(), ListSerializer(McpHeader.serializer()))

    fun encodeServers(servers: List<McpServerSpec>): String = json.encodeToString(servers)

    fun decodeServers(value: String): List<McpServerSpec> {
        // Blank on almost every device: nothing is stored until a server is added.
        if (value.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<McpServerSpec>>(value) }.getOrDefault(emptyList())
    }

    fun encodeHeaders(headers: Map<String, List<McpHeader>>): String =
        json.encodeToString(headersSerializer, headers.filterValues { it.isNotEmpty() })

    fun decodeHeaders(value: String): Map<String, List<McpHeader>> {
        if (value.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString(headersSerializer, value) }.getOrDefault(emptyMap())
    }
}
