package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pasting another app's MCP configuration (#573). */
class McpConfigImportTest {

    @Test
    fun `claude desktop shape, with a local command skipped`() {
        val result = McpConfigImport.parse(
            """
            {"mcpServers": {
              "files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem"]},
              "remote": {"url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer abc"}}
            }}
            """,
        )!!
        val server = result.servers.single()
        assertEquals("remote", server.name)
        assertEquals("https://mcp.example.com/mcp", server.url)
        assertEquals(McpTransport.AUTO, server.transport)
        assertEquals(listOf(McpHeader("Authorization", "Bearer abc")), server.headers)
        assertEquals(McpConfigImport.Skipped("files", McpConfigImport.SkipReason.COMMAND), result.skipped.single())
    }

    @Test
    fun `vs code shape reads its type`() {
        val result = McpConfigImport.parse(
            """
            {"servers": {
              "a": {"type": "http", "url": "https://a.test/mcp"},
              "b": {"type": "sse", "url": "https://b.test/sse"}
            }}
            """,
        )!!
        assertEquals(listOf(McpTransport.STREAMABLE_HTTP, McpTransport.SSE), result.servers.map { it.transport })
    }

    @Test
    fun `a bare map and a single server both read`() {
        val bare = McpConfigImport.parse("""{"one": {"serverUrl": "https://one.test/sse"}}""")!!
        assertEquals("one", bare.servers.single().name)
        // No type given, but an address ending in /sse is the old transport.
        assertEquals(McpTransport.SSE, bare.servers.single().transport)
        val single = McpConfigImport.parse("""{"name": "solo", "url": "http://192.168.1.2:3000/mcp", "transport": "streamable-http"}""")!!
        assertEquals("solo", single.servers.single().name)
        assertEquals(McpTransport.STREAMABLE_HTTP, single.servers.single().transport)
    }

    @Test
    fun `addresses that are not http are refused`() {
        val result = McpConfigImport.parse(
            """{"mcpServers": {"x": {"url": "ws://x.test"}, "y": {"note": "nothing"}}}""",
        )!!
        assertTrue(result.servers.isEmpty())
        assertEquals(
            listOf(
                McpConfigImport.Skipped("x", McpConfigImport.SkipReason.BAD_URL),
                McpConfigImport.Skipped("y", McpConfigImport.SkipReason.NO_URL),
            ),
            result.skipped,
        )
    }

    @Test
    fun `not json, or not an object, is null`() {
        assertNull(McpConfigImport.parse("mcpServers"))
        assertNull(McpConfigImport.parse("[1, 2]"))
        assertTrue(McpConfigImport.parse("{}")!!.isEmpty)
    }
}
