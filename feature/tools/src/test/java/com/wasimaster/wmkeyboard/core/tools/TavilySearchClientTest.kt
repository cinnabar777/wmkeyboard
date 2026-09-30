package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SelfHostedSettings
import com.wasimaster.wmkeyboard.core.settings.WebSearchSettings
import com.wasimaster.wmkeyboard.core.settings.hasSearchKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tavily as a web and image search backend (#439). */
class TavilySearchClientTest {

    @Test
    fun `web results parse title, snippet and host`() {
        val body = """
            {"query":"messi","answer":null,"images":[],"results":[
              {"title":"Lionel Messi Facts | Britannica","url":"https://www.britannica.com/facts/Lionel-Messi",
               "content":"Born in 1987 [...] He spent most of his career at Barcelona","score":0.81,"raw_content":null},
              {"title":"no address"},
              {"url":"https://example.org/untitled","content":"text"}
            ],"response_time":"1.67"}
        """.trimIndent()
        val results = TavilySearchClient.parseWeb(body)
        assertEquals(2, results.size)
        assertEquals("Lionel Messi Facts | Britannica", results[0].title)
        assertEquals("www.britannica.com", results[0].displayUrl)
        // The chunk separator reads as an ellipsis rather than markup.
        assertEquals("Born in 1987 … He spent most of his career at Barcelona", results[0].snippet)
        // A hit without a title still has somewhere to go, so it is kept under its host.
        assertEquals("example.org", results[1].title)
    }

    @Test
    fun `images parse from bare addresses and from described objects`() {
        val bare = """{"results":[],"images":["https://a.example/cat.png","https://a.example/cat.png","",42]}"""
        val plain = TavilySearchClient.parseImages(bare)
        assertEquals(1, plain.size)
        assertEquals("https://a.example/cat.png", plain[0].imageUrl)
        assertEquals(plain[0].imageUrl, plain[0].thumbUrl)
        assertEquals("image/png", plain[0].mime)

        val described = """{"images":[{"url":"https://b.example/dog.jpg","description":"A dog"}]}"""
        val rich = TavilySearchClient.parseImages(described)
        assertEquals("A dog", rich.single().title)
        assertEquals("image/jpeg", rich.single().mime)
    }

    @Test
    fun `empty responses parse to empty lists`() {
        assertTrue(TavilySearchClient.parseWeb("{}").isEmpty())
        assertTrue(TavilySearchClient.parseImages("{}").isEmpty())
    }

    @Test
    fun `request asks for a one-credit search and clamps the count`() {
        val body = Json.parseToJsonElement(
            TavilySearchClient.requestBody("  cats ", count = 50, safe = true, images = true),
        ).jsonObject
        assertEquals("cats", body["query"]!!.jsonPrimitive.content)
        assertEquals("basic", body["search_depth"]!!.jsonPrimitive.content)
        assertEquals(20, body["max_results"]!!.jsonPrimitive.int)
        assertTrue(body["include_images"]!!.jsonPrimitive.boolean)
        assertTrue(body["safe_search"]!!.jsonPrimitive.boolean)
        assertFalse(body["include_answer"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tavily error bodies surface their own words`() {
        assertEquals(
            "Unauthorized: missing or invalid API key.",
            ToolHttp.apiErrorText("""{"detail":{"error":"Unauthorized: missing or invalid API key."}}"""),
        )
        assertEquals(
            "Your request has been blocked due to excessive requests.",
            ToolHttp.apiErrorText("""{"error":"Your request has been blocked due to excessive requests."}"""),
        )
        assertNull(ToolHttp.apiErrorText("""{"detail":"Not Found"}"""))
    }

    @Test
    fun `backend order is instance, then tavily, then brave`() {
        val both = KeyboardSettings(webSearch = WebSearchSettings(braveApiKey = "b", tavilyApiKey = "t"))
        assertEquals(SearchBackend.TAVILY, ToolApiKeys.searchBackend(both))
        val withInstance = both.copy(selfHosted = SelfHostedSettings(searxUrl = "https://searx.example"))
        assertEquals(SearchBackend.SEARXNG, ToolApiKeys.searchBackend(withInstance))
        val braveOnly = KeyboardSettings(webSearch = WebSearchSettings(braveApiKey = "b"))
        assertEquals(SearchBackend.BRAVE, ToolApiKeys.searchBackend(braveOnly))
    }

    @Test
    fun `a tavily key alone unlocks the search tools`() {
        val settings = KeyboardSettings(webSearch = WebSearchSettings(tavilyApiKey = "t"))
        assertTrue(hasSearchKey(settings))
        assertTrue(ToolApiKeys.hasSearchProvider(settings))
    }
}
