package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tavily Search API client, the third backend for the web and image search
 * tools (#439), beside [BraveSearchClient] and [SearxClient].
 *
 * Tavily is built for AI agents rather than people, but its `/search` answers
 * with the same title, address and snippet a results list needs. The free plan
 * gives 1,000 credits a month, and a `basic` search costs one. There is no
 * built-in key: nothing here runs until the user pastes their own.
 *
 * There is no separate image endpoint. `include_images` adds the pictures
 * Tavily found on the result pages, as bare addresses, so image search gets
 * fewer results than Brave gives and no page to link each one back to.
 */
object TavilySearchClient {

    private const val PATH = "/search"

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun webSearch(query: String, apiKey: String, count: Int, safe: Boolean): List<WebResult> =
        parseWeb(post(requestBody(query, count, safe, images = false), apiKey, NetSource.WEB_SEARCH))

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun imageSearch(query: String, apiKey: String, count: Int, safe: Boolean): List<ImageResult> =
        parseImages(post(requestBody(query, count, safe, images = true), apiKey, NetSource.IMAGE_SEARCH))
            .take(count.coerceIn(1, MAX_RESULTS))

    private fun post(body: String, apiKey: String, source: NetSource): String =
        ToolHttp.postJson(
            url = ServiceEndpoints.base(ServiceEndpoint.TAVILY) + PATH,
            body = body,
            timeoutMs = 20_000,
            headers = mapOf(
                "Authorization" to "Bearer ${apiKey.trim()}",
                "Accept" to "application/json",
            ),
            source = source,
            route = PATH,
        )

    /**
     * `basic` depth, the one-credit search. `safe_search` is sent either way:
     * Tavily refuses it only at the `fast` depths, which this never asks for.
     */
    internal fun requestBody(query: String, count: Int, safe: Boolean, images: Boolean): String =
        buildJsonObject {
            put("query", query.trim())
            put("search_depth", "basic")
            put("max_results", count.coerceIn(1, MAX_RESULTS))
            put("include_answer", false)
            put("include_images", images)
            put("safe_search", safe)
        }.toString()

    internal fun parseWeb(body: String): List<WebResult> {
        val results = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.text("url") ?: return@mapNotNull null
            WebResult(
                title = item.text("title") ?: SearxClient.hostOf(url),
                // Tavily joins the chunks it quotes from one page with "[...]".
                snippet = item.text("content").orEmpty().replace(" [...] ", " … "),
                url = url,
                displayUrl = SearxClient.hostOf(url),
            )
        }
    }

    /**
     * The top-level `images` list: bare addresses, or `{url, description}`
     * objects when descriptions were asked for. Both shapes are read, so a
     * change of request can never empty the grid.
     */
    internal fun parseImages(body: String): List<ImageResult> {
        val images = Json.parseToJsonElement(body).jsonObject["images"]?.jsonArray.orEmpty()
        return images.mapNotNull { element ->
            val url: String
            val title: String
            when (element) {
                is JsonPrimitive -> {
                    url = element.content.takeIf { element.isString && it.isNotBlank() } ?: return@mapNotNull null
                    title = ""
                }
                is JsonObject -> {
                    url = element.text("url") ?: return@mapNotNull null
                    title = element.text("description").orEmpty()
                }
                else -> return@mapNotNull null
            }
            ImageResult(
                title = title,
                thumbUrl = url,
                imageUrl = url,
                mime = BraveSearchClient.mimeFromUrl(url),
                contextUrl = "",
            )
        }.distinctBy { it.imageUrl }
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** Tavily's own ceiling for `max_results`. */
    private const val MAX_RESULTS = 20
}
