package com.wasimaster.wmkeyboard.core.tools

import com.sun.net.httpserver.HttpServer
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** The translate tool's own OpenAI-compatible server (#435). */
class TranslateServerClientTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private var lastPath = ""
    private var lastAuth: String? = null
    private var lastBody = ""
    private var reply = ""
    private var replyType = "text/event-stream"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastPath = exchange.requestURI.path
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.readBytes().decodeToString()
            val bytes = reply.toByteArray()
            exchange.responseHeaders.add("Content-Type", replyType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        NetLog.clear()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun sse(vararg chunks: String): String = buildString {
        for (chunk in chunks) {
            val escaped = JsonPrimitive(chunk).toString()
            append("data: {\"choices\":[{\"delta\":{\"content\":$escaped}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    @Test
    fun `streamed answer comes back as the translation`() {
        reply = sse("Bon", "jour")
        val t = TranslateServerClient.translate("Hello", "fr", "$base/v1", "hunyuan-mt", apiKey = "sk-1")
        assertEquals("Bonjour", t.text)
        assertTrue(t.viaServer)
        assertEquals("", t.detectedSource)
        assertEquals("/v1/chat/completions", lastPath)
        assertEquals("Bearer sk-1", lastAuth)
        val body = Json.parseToJsonElement(lastBody).jsonObject
        assertEquals("hunyuan-mt", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("French (fr)"))
        assertEquals("Hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(NetSource.TRANSLATE, NetLog.rows().first().source)
    }

    @Test
    fun `a server that ignores stream and answers once still works`() {
        replyType = "application/json"
        reply = """{"choices":[{"message":{"role":"assistant","content":"Hallo"}}]}"""
        val t = TranslateServerClient.translate("Hello", "de", base, model = "", source = "en")
        assertEquals("Hallo", t.text)
        // The picked source is all the panel can show: the server names none.
        assertEquals("en", t.detectedSource)
        assertEquals("/v1/chat/completions", lastPath)
        assertEquals(null, lastAuth)
        assertFalse(Json.parseToJsonElement(lastBody).jsonObject.containsKey("model"))
    }

    @Test
    fun `the address is read however it was pasted`() {
        assertEquals("https://llm.example.org/v1", TranslateServerClient.apiBase("llm.example.org"))
        assertEquals("http://10.0.0.2:8080/v1", TranslateServerClient.apiBase("http://10.0.0.2:8080/"))
        assertEquals("http://10.0.0.2:8080/v1", TranslateServerClient.apiBase("http://10.0.0.2:8080/v1"))
        assertEquals(
            "http://10.0.0.2:11434/v1",
            TranslateServerClient.apiBase(" http://10.0.0.2:11434/v1/chat/completions "),
        )
        assertEquals("https://openrouter.ai/api/v1", TranslateServerClient.apiBase("https://openrouter.ai/api/v1"))
        assertThrows(IllegalArgumentException::class.java) { TranslateServerClient.apiBase("ftp://x") }
        assertThrows(IllegalArgumentException::class.java) { TranslateServerClient.apiBase("  ") }
    }

    @Test
    fun `the instruction names both languages when the source is picked`() {
        val auto = TranslateServerClient.systemPrompt("zh-CN", TranslateClient.AUTO)
        assertTrue(auto.contains("into Chinese (Simplified) (zh-CN)"))
        assertTrue(auto.contains("from the language it is written in"))
        val picked = TranslateServerClient.systemPrompt("en", "bn")
        assertTrue(picked.contains("from Bengali (bn) into English (en)"))
    }

    @Test
    fun `reasoning and wrapping quotes are removed`() {
        assertEquals("Hola", TranslateServerClient.cleaned("<think>Spanish.</think>\n\"Hola\"", "Hello"))
        assertEquals("Hola", TranslateServerClient.cleaned("reasoning</think>Hola", "Hello"))
        // Quoted source keeps its quotes; two quoted parts are not one pair.
        assertEquals("\"Hola\"", TranslateServerClient.cleaned("\"Hola\"", "\"Hello\""))
        assertEquals("\"a\" y \"b\"", TranslateServerClient.cleaned("\"a\" y \"b\"", "say \"a\" and \"b\""))
        assertEquals("「こんにちは」", TranslateServerClient.cleaned("「こんにちは」", "「Hello」"))
        assertEquals("こんにちは", TranslateServerClient.cleaned("「こんにちは」", "Hello"))
    }
}
