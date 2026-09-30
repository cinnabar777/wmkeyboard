package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A finished translation plus the language the source was detected as. */
data class Translation(
    val text: String,
    /** ISO 639-1 code of the detected source language, or "" when unknown. */
    val detectedSource: String,
    /** DeepL answered, rather than the tool's usual service. */
    val viaDeepL: Boolean = false,
    /** The user's own translation server answered (issue #435). */
    val viaServer: Boolean = false,
)

/**
 * Translation client with two providers behind one call:
 *  - no API key: Google's free public web endpoint (translate.googleapis.com),
 *    the same one the translate extensions use — fine for personal keyboard
 *    volumes, not an SLA;
 *  - with a key: the official Cloud Translation v2 API.
 * Source language is auto-detected unless the caller names one; both providers
 * report what they detected so the panel can show "Bengali → English".
 */
object TranslateClient {

    /** Longest text we send; keyboard fields beyond this get truncated. */
    const val MAX_CHARS = 2500

    /** The source code that means "work it out": every provider's own default. */
    const val AUTO = "auto"

    /**
     * The languages offered by the pickers: English and Bengali first, as they
     * always were, then by name. Every language the on-device engine has a
     * model for is here, plus the ones only the online services know.
     */
    val languages: List<Pair<String, String>> = listOf(
        "en" to "English",
        "bn" to "Bengali",
        "af" to "Afrikaans",
        "sq" to "Albanian",
        "ar" to "Arabic",
        "be" to "Belarusian",
        "bg" to "Bulgarian",
        "ca" to "Catalan",
        "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)",
        "hr" to "Croatian",
        "cs" to "Czech",
        "da" to "Danish",
        "nl" to "Dutch",
        "eo" to "Esperanto",
        "et" to "Estonian",
        "tl" to "Filipino",
        "fi" to "Finnish",
        "fr" to "French",
        "gl" to "Galician",
        "ka" to "Georgian",
        "de" to "German",
        "el" to "Greek",
        "gu" to "Gujarati",
        "ht" to "Haitian Creole",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "is" to "Icelandic",
        "id" to "Indonesian",
        "ga" to "Irish",
        "it" to "Italian",
        "ja" to "Japanese",
        "kn" to "Kannada",
        "ko" to "Korean",
        "lv" to "Latvian",
        "lt" to "Lithuanian",
        "mk" to "Macedonian",
        "ms" to "Malay",
        "ml" to "Malayalam",
        "mt" to "Maltese",
        "mr" to "Marathi",
        "ne" to "Nepali",
        "no" to "Norwegian",
        "fa" to "Persian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "pa" to "Punjabi",
        "ro" to "Romanian",
        "ru" to "Russian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "es" to "Spanish",
        "sw" to "Swahili",
        "sv" to "Swedish",
        "ta" to "Tamil",
        "te" to "Telugu",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "ur" to "Urdu",
        "vi" to "Vietnamese",
        "cy" to "Welsh",
    )

    /**
     * Codes the services report for a language the list spells another way:
     * the on-device engine's bare `zh`, and the legacy ISO codes Google's
     * endpoint still answers with.
     */
    private val NAME_ALIASES = mapOf(
        "zh" to "zh-CN",
        "zh-hans" to "zh-CN",
        "zh-hant" to "zh-TW",
        "iw" to "he",
        "in" to "id",
        "fil" to "tl",
        "nb" to "no",
    )

    /**
     * The picker's own code for whatever a service called a language, or null
     * when it is not a language the pickers list. What the swap button needs:
     * a detected `zh` has to become the `zh-CN` the target chip can hold.
     */
    fun pickerCode(code: String): String? {
        val wanted = NAME_ALIASES[code.lowercase()] ?: code
        return languages.firstOrNull { it.first.equals(wanted, ignoreCase = true) }?.first
            ?: languages.firstOrNull { it.first.equals(wanted.substringBefore('-'), ignoreCase = true) }?.first
    }

    fun languageName(code: String): String {
        val wanted = NAME_ALIASES[code.lowercase()] ?: code
        return languages.firstOrNull { it.first.equals(wanted, ignoreCase = true) }?.second
            ?: languages.firstOrNull { it.first.equals(wanted.substringBefore('-'), ignoreCase = true) }?.second
            ?: code
    }

    /** Blocking; call on an IO dispatcher. Throws on any failure. */
    fun translate(
        text: String,
        targetLang: String,
        apiKey: String,
        sourceLang: String = AUTO,
    ): Translation {
        val trimmed = text.take(MAX_CHARS)
        val source = sourceLang.ifBlank { AUTO }
        return if (apiKey.isBlank()) translateFree(trimmed, targetLang, source)
        else translateOfficial(trimmed, targetLang, apiKey, source)
    }

    private fun translateFree(text: String, targetLang: String, sourceLang: String): Translation {
        val url = ServiceEndpoints.base(ServiceEndpoint.TRANSLATE_GOOGLE) + "/translate_a/single" +
            "?client=gtx&sl=${ToolHttp.encode(sourceLang)}&tl=${ToolHttp.encode(targetLang)}" +
            "&dt=t&ie=UTF-8&oe=UTF-8&q=${ToolHttp.encode(text)}"
        return parseFree(ToolHttp.get(url, source = NetSource.TRANSLATE, route = "/translate_a/single"))
            .withSourceIfUnknown(sourceLang)
    }

    private fun translateOfficial(
        text: String,
        targetLang: String,
        apiKey: String,
        sourceLang: String,
    ): Translation {
        val form = buildMap {
            put("q", text)
            put("target", targetLang)
            put("format", "text")
            // Cloud Translation detects when `source` is absent; "auto" is not
            // a language it knows.
            if (sourceLang != AUTO) put("source", sourceLang)
        }
        val body = ToolHttp.postForm(
            ServiceEndpoints.base(ServiceEndpoint.TRANSLATE_CLOUD) + "/language/translate/v2?key=${ToolHttp.encode(apiKey)}",
            form,
            source = NetSource.TRANSLATE,
            route = "/language/translate/v2",
        )
        return parseOfficial(body).withSourceIfUnknown(sourceLang)
    }

    /**
     * A service told the source reports no *detected* source, because it
     * detected nothing. The panel still wants a name to show.
     */
    private fun Translation.withSourceIfUnknown(sourceLang: String): Translation =
        if (detectedSource.isBlank() && sourceLang != AUTO) copy(detectedSource = sourceLang) else this

    /**
     * The free endpoint returns a bare nested array:
     * `[[["Hello","হ্যালো",…], ["…","…",…]], null, "bn", …]` — element 0 is
     * the list of translated segments, element 2 the detected language.
     */
    fun parseFree(body: String): Translation {
        val root = Json.parseToJsonElement(body).jsonArray
        val translated = root[0].jsonArray.joinToString("") { segment ->
            segment.jsonArray[0].jsonPrimitive.content
        }
        val detected = root.getOrNull(2)?.jsonPrimitive?.content.orEmpty()
        return Translation(translated, detected)
    }

    fun parseOfficial(body: String): Translation {
        val first = Json.parseToJsonElement(body).jsonObject
            .getValue("data").jsonObject
            .getValue("translations").jsonArray[0].jsonObject
        return Translation(
            text = first.getValue("translatedText").jsonPrimitive.content,
            detectedSource = first["detectedSourceLanguage"]?.jsonPrimitive?.content.orEmpty(),
        )
    }
}
