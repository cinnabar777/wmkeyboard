package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The tools each MCP server listed when it was last connected (#573), by
 * server id, in `files/ai/mcp_catalog.json`.
 *
 * A cache, not a setting: everything in it can be fetched again with the
 * Connect button, so it is left out of backups and kept out of the preference
 * store. A server's tool schemas can run to tens of kilobytes, and the
 * preference store hands the whole settings object to the keyboard on every
 * change.
 *
 * The keyboard and the settings screens run in one process, so one copy in
 * memory serves both; [version] ticks on every change so a screen can redraw.
 */
object McpCatalogStore {

    private const val FILE = "ai/mcp_catalog.json"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val serializer = MapSerializer(String.serializer(), McpCatalogEntry.serializer())

    private var cache: Map<String, McpCatalogEntry>? = null

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Synchronized
    fun all(context: Context): Map<String, McpCatalogEntry> {
        cache?.let { return it }
        val file = file(context)
        // Unreadable before the first unlock (the keyboard can start then), or
        // written by a version that shaped it differently: either way there is
        // nothing to offer until the user connects again.
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyMap()
        }.getOrDefault(emptyMap())
        cache = loaded
        return loaded
    }

    /** The entry for [server], or null when there is none or it was listed from another address. */
    fun entryFor(context: Context, server: McpServerSpec): McpCatalogEntry? =
        all(context)[server.id]?.takeIf { it.url == server.url.trim() }

    @Synchronized
    fun put(context: Context, id: String, entry: McpCatalogEntry) {
        write(context, all(context) + (id to entry))
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val current = all(context)
        if (id in current) write(context, current - id)
    }

    private fun write(context: Context, next: Map<String, McpCatalogEntry>) {
        cache = next
        _version.value++
        runCatching {
            val file = file(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(serializer, next))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}
