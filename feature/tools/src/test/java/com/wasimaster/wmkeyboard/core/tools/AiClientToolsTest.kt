package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four wire shapes tool calling takes, and the turns that follow one. */
class AiClientToolsTest {

    private val config = AiClient.Config(AiProvider.OPENAI, "k", "m", "")
    private val tools = listOf(AiTools.webSearch)
    private val user = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "who won"))

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    // ---- tools are opt-in, field by field --------------------------------

    @Test
    fun `no tools means no tool field in any body`() {
        assertNull(body(AiClient.openAiCompatibleBody(config, "s", user, 100))["tools"])
        assertNull(body(AiClient.anthropicBody(config, "s", user, 100))["tools"])
        assertNull(body(AiClient.geminiBody("s", user, 100))["tools"])
        assertNull(body(AiClient.ollamaBody(config, "s", user, 100))["tools"])
    }

    @Test
    fun `openai wraps each tool as a function`() {
        val function = body(AiClient.openAiCompatibleBody(config, "s", user, 100, tools))["tools"]
            ?.jsonArray?.single()?.jsonObject
        assertEquals("function", function?.get("type")?.jsonPrimitive?.content)
        val declared = function?.get("function")?.jsonObject
        assertEquals(AiTools.WEB_SEARCH, declared?.get("name")?.jsonPrimitive?.content)
        assertEquals("object", declared?.get("parameters")?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `anthropic names the schema input_schema`() {
        val tool = body(AiClient.anthropicBody(config, "s", user, 100, tools))["tools"]
            ?.jsonArray?.single()?.jsonObject
        assertEquals(AiTools.WEB_SEARCH, tool?.get("name")?.jsonPrimitive?.content)
        assertTrue(tool?.containsKey("input_schema") == true)
        assertFalse(tool?.containsKey("parameters") == true)
    }

    @Test
    fun `gemini puts every declaration in one entry`() {
        val declarations = body(AiClient.geminiBody("s", user, 100, tools))["tools"]
            ?.jsonArray?.single()?.jsonObject?.get("functionDeclarations")?.jsonArray
        assertEquals(1, declarations?.size)
        assertEquals(AiTools.WEB_SEARCH, declarations?.single()?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini takes the schema as full json schema`() {
        val declared = body(AiClient.geminiBody("s", user, 100, tools))["tools"]
            ?.jsonArray?.single()?.jsonObject?.get("functionDeclarations")?.jsonArray?.single()?.jsonObject
        assertEquals("object", declared?.get("parametersJsonSchema")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(declared?.containsKey("parameters") == true)
    }

    // ---- the turns that carry a call and its answer ----------------------

    private val call = AiToolCall("call_1", AiTools.WEB_SEARCH, """{"query":"who won"}""")
    private val exchange = user + listOf(
        AiClient.ChatTurn(AiClient.ChatRole.ASSISTANT, "", toolCalls = listOf(call)),
        AiClient.ChatTurn(
            AiClient.ChatRole.TOOL,
            "Nobody.",
            toolResult = AiToolResult(call, "Nobody."),
        ),
    )

    @Test
    fun `openai sends a tool role quoting the call id`() {
        val messages = body(AiClient.openAiCompatibleBody(config, "s", exchange, 100, tools))["messages"]!!
            .jsonArray.map { it.jsonObject }
        val assistant = messages[2]
        val function = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", function["id"]?.jsonPrimitive?.content)
        // Quoted JSON, not an object: this one field is a string on the wire.
        assertEquals(
            """{"query":"who won"}""",
            function["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content,
        )
        val answer = messages[3]
        assertEquals("tool", answer["role"]?.jsonPrimitive?.content)
        assertEquals("call_1", answer["tool_call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `anthropic sends the answer as a user message of tool_result blocks`() {
        val messages = body(AiClient.anthropicBody(config, "s", exchange, 100, tools))["messages"]!!
            .jsonArray.map { it.jsonObject }
        val assistant = messages[1]
        val use = assistant["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_use", use["type"]?.jsonPrimitive?.content)
        // An object here, unlike OpenAI's quoted string.
        assertEquals("who won", use["input"]?.jsonObject?.get("query")?.jsonPrimitive?.content)
        val answer = messages[2]
        assertEquals("user", answer["role"]?.jsonPrimitive?.content)
        val result = answer["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", result["tool_use_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `anthropic gathers consecutive results into one user message`() {
        val second = AiToolCall("call_2", AiTools.WEB_FETCH, """{"url":"https://a.test"}""")
        val turns = user + listOf(
            AiClient.ChatTurn(
                AiClient.ChatRole.ASSISTANT, "",
                toolCalls = listOf(call, second),
            ),
            AiClient.ChatTurn(AiClient.ChatRole.TOOL, "one", toolResult = AiToolResult(call, "one")),
            AiClient.ChatTurn(AiClient.ChatRole.TOOL, "two", toolResult = AiToolResult(second, "two")),
        )
        val messages = body(AiClient.anthropicBody(config, "s", turns, 100, tools))["messages"]!!.jsonArray
        // user, assistant, one combined user — not two users in a row, which
        // Anthropic rejects outright.
        assertEquals(3, messages.size)
        assertEquals(2, messages[2].jsonObject["content"]!!.jsonArray.size)
    }

    @Test
    fun `gemini answers a call by name in a user part`() {
        val contents = body(AiClient.geminiBody("s", exchange, 100, tools))["contents"]!!
            .jsonArray.map { it.jsonObject }
        val model = contents[1]
        assertEquals("model", model["role"]?.jsonPrimitive?.content)
        val fn = model["parts"]!!.jsonArray.single().jsonObject["functionCall"]!!.jsonObject
        assertEquals(AiTools.WEB_SEARCH, fn["name"]?.jsonPrimitive?.content)
        val answer = contents[2]
        assertEquals("user", answer["role"]?.jsonPrimitive?.content)
        val response = answer["parts"]!!.jsonArray.single().jsonObject["functionResponse"]!!.jsonObject
        assertEquals(AiTools.WEB_SEARCH, response["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ollama sends object arguments`() {
        val messages = body(AiClient.ollamaBody(config, "s", exchange, 100, tools))["messages"]!!
            .jsonArray.map { it.jsonObject }
        val fn = messages[2]["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals("who won", fn["arguments"]?.jsonObject?.get("query")?.jsonPrimitive?.content)
    }

    @Test
    fun `arguments that do not parse become an empty object rather than a bad body`() {
        val broken = AiToolCall("x", AiTools.WEB_SEARCH, "{not json")
        val turns = user + AiClient.ChatTurn(AiClient.ChatRole.ASSISTANT, "", toolCalls = listOf(broken))
        val use = body(AiClient.anthropicBody(config, "s", turns, 100, tools))["messages"]!!
            .jsonArray[1].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals(0, use["input"]?.jsonObject?.size)
    }

    // ---- normalizing must not disturb a tool exchange --------------------

    @Test
    fun `a tool exchange survives normalizing`() {
        val normalized = AiClient.normalizedTurns(exchange)
        assertEquals(3, normalized.size)
        // The blank assistant turn is kept — it carries the call — and the
        // conversation is allowed to end on the tool answer.
        assertEquals(AiClient.ChatRole.ASSISTANT, normalized[1].role)
        assertEquals(AiClient.ChatRole.TOOL, normalized[2].role)
    }

    @Test
    fun `a plain trailing assistant turn is still dropped`() {
        val normalized = AiClient.normalizedTurns(
            user + AiClient.ChatTurn(AiClient.ChatRole.ASSISTANT, "half an answer"),
        )
        assertEquals(1, normalized.size)
    }

    // ---- which providers get the real thing ------------------------------

    @Test
    fun `brave and on-device fall back to the prompt protocol`() {
        assertFalse(AiClient.supportsNativeTools(AiProvider.BRAVE))
        assertFalse(AiClient.supportsNativeTools(AiProvider.ON_DEVICE))
        assertTrue(AiClient.supportsNativeTools(AiProvider.ANTHROPIC))
        assertTrue(AiClient.supportsNativeTools(AiProvider.GEMINI))
        assertTrue(AiClient.supportsNativeTools(AiProvider.OPENAI_COMPATIBLE))
    }
}
