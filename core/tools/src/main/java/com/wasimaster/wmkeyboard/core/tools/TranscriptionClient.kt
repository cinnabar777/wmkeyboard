package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sends a recorded clip to a speech-to-text server the user runs, and returns
 * the text it heard (wasi-master/wmkeyboard#286).
 *
 * There is no protocol of our own. The request is OpenAI's
 * `POST /v1/audio/transcriptions`, which every self-hostable server already
 * speaks: speaches (faster-whisper), LocalAI, whisper.cpp's `whisper-server`
 * (natively on `/inference`, same fields), and the Groq and OpenAI clouds.
 *
 * Blocking; call on an IO dispatcher.
 */
object TranscriptionClient {

    private const val READ_TIMEOUT_MS = 60_000

    /**
     * The address to POST to. The user types the server's API root the way the
     * AI tool's "Other service" asks for it (`http://host:8000/v1`), and the
     * endpoint is added here. An address that already names an endpoint —
     * `…/audio/transcriptions`, or whisper.cpp's own `…/inference` — is used as
     * it stands. A bare `http://host:8000` gets the standard `/v1` root.
     *
     * A server on a route of its own (#388, e.g. `POST /v1/transcribe`) is
     * reached through [path]: when it is filled in, it is added to the address
     * as typed, with no guessing and no `/v1`. A [path] that is a whole
     * address replaces [url] outright.
     */
    fun endpoint(url: String, path: String = ""): String {
        val base = url.trim().trimEnd('/')
        val route = path.trim()
        if (route.contains("://")) return route
        if (route.trim('/').isNotEmpty()) return "$base/${route.trimStart('/')}"
        val path = base.substringAfter("://", base).substringAfter('/', "").substringBefore('?')
        return when {
            path.endsWith("transcriptions") || path.endsWith("inference") -> base
            path.isEmpty() -> "$base/v1/audio/transcriptions"
            else -> "$base/audio/transcriptions"
        }
    }

    /**
     * Uploads [wav] and returns the transcript, trimmed. [model] and
     * [language] (an ISO-639-1 code) are left out of the request when blank,
     * so the server picks its default model and detects the language itself.
     *
     * [prompt] is OpenAI's `prompt` field (#305): text the model treats as
     * what came before the clip, which is how names and jargon get spelled
     * right. Whisper models keep only its last 224 tokens. Left out when null.
     *
     * [path] is the server's own route, when it has one; see [endpoint].
     */
    fun transcribe(
        url: String,
        apiKey: String,
        model: String,
        language: String?,
        wav: ByteArray,
        prompt: String? = null,
        path: String = "",
    ): String {
        val endpoint = endpoint(url, path)
        val fields = buildList {
            if (model.isNotBlank()) add("model" to model.trim())
            if (!language.isNullOrBlank()) add("language" to language)
            if (!prompt.isNullOrBlank()) add("prompt" to prompt)
            add("response_format" to "json")
        }
        val headers = if (apiKey.isNotBlank()) {
            mapOf("Authorization" to "Bearer ${apiKey.trim()}")
        } else {
            emptyMap()
        }
        val body = ToolHttp.postMultipart(
            url = endpoint,
            fields = fields,
            file = ToolHttp.FilePart("file", "speech.wav", "audio/wav", wav),
            timeoutMs = READ_TIMEOUT_MS,
            headers = headers,
            source = NetSource.TRANSCRIPTION,
            route = NetLog.pathOf(endpoint),
        )
        return parseText(body)
    }

    /**
     * The transcript out of a response body: `{"text": …}` from every
     * OpenAI-shaped server, or the body itself from a server that answered in
     * plain text anyway.
     */
    internal fun parseText(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            Json.parseToJsonElement(trimmed).jsonObject["text"]?.jsonPrimitive?.content
        }.getOrNull()?.trim().orEmpty()
    }
}
