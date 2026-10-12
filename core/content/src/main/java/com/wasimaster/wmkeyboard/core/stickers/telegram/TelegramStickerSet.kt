package com.wasimaster.wmkeyboard.core.stickers.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What a Telegram sticker is made of, which decides how it is read. */
enum class TelegramStickerFormat {
    /** A WebP picture. */
    STATIC,

    /** A `.tgs` file: a gzipped Lottie animation. See [TgsFrames]. */
    ANIMATED,

    /** A VP9 `.webm` video with transparency. See [WebmFrames]. */
    VIDEO,
}

/**
 * One sticker as the Bot API lists it. [fileId] is what the file is asked for
 * by, and changes; [uniqueId] is the same for the same file for ever, so it is
 * what the cache is keyed on. [thumbId] is a small still of it, when Telegram
 * made one.
 */
data class TelegramSticker(
    val fileId: String,
    val uniqueId: String,
    val format: TelegramStickerFormat,
    val emoji: String = "",
    val thumbId: String = "",
    val thumbUniqueId: String = "",
)

/**
 * A Telegram sticker set, from the Bot API's `getStickerSet`.
 *
 * ```
 * { "ok": true, "result": { "name": "…", "title": "…", "sticker_type": "regular",
 *   "stickers": [ { "file_id": "…", "file_unique_id": "…", "is_animated": false,
 *                   "is_video": false, "emoji": "😀", "thumbnail": { "file_id": … } } ] } }
 * ```
 *
 * A set's [name] is its address (`t.me/addstickers/<name>`); [title] is what
 * it is called.
 */
data class TelegramStickerSet(
    val name: String,
    val title: String,
    /** `regular`, `mask` or `custom_emoji`. All three are pictures; only the size differs. */
    val type: String,
    val stickers: List<TelegramSticker>,
) {
    companion object {

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * The longest a set name can be. Telegram allows 1 to 64 letters,
         * digits and underscores, and ends a bot's set names in `_by_<bot>`.
         */
        const val MAX_NAME_LENGTH = 64

        private val NAME = Regex("[A-Za-z0-9_]{1,$MAX_NAME_LENGTH}")

        /**
         * A bot token as @BotFather hands it out: the bot's numeric id, a
         * colon, and a secret of 35 characters. The length of the secret is
         * left open, in case Telegram ever changes it.
         */
        private val TOKEN = Regex("""\d{5,20}:[A-Za-z0-9_-]{30,64}""")

        /** True for a string that can be a set name. */
        fun isSetName(name: String): Boolean = NAME.matches(name)

        /** [token] trimmed, or null when it is not shaped like a bot token. */
        fun tokenOrNull(token: String): String? = token.trim().takeIf { TOKEN.matches(it) }

        /** The set in a `getStickerSet` answer, or null when [body] is not one. */
        fun parse(body: String): TelegramStickerSet? {
            val result = runCatching { JSON.parseToJsonElement(body) }.getOrNull()
                ?.objectOrNull()?.get("result")?.objectOrNull() ?: return null
            val name = result.text("name")
            if (!isSetName(name)) return null
            val stickers = (result["stickers"] as? JsonArray).orEmpty()
                .mapNotNull { it.objectOrNull()?.let(::sticker) }
                // Listed twice is downloaded twice and added twice.
                .distinctBy { it.uniqueId }
            return TelegramStickerSet(
                name = name,
                title = result.text("title").trim(),
                type = result.text("sticker_type").ifBlank { "regular" },
                stickers = stickers,
            )
        }

        /** The `file_path` in a `getFile` answer, or null when there is none. */
        fun filePath(body: String): String? {
            val result = runCatching { JSON.parseToJsonElement(body) }.getOrNull()
                ?.objectOrNull()?.get("result")?.objectOrNull() ?: return null
            // A relative path under the bot's file root, which this app puts
            // into an address: nothing that could climb out of it.
            return result.text("file_path").takeIf { path ->
                path.isNotBlank() && !path.startsWith("/") && path.split('/').none { it == ".." || it.isEmpty() }
            }
        }

        private fun sticker(json: JsonObject): TelegramSticker? {
            val fileId = json.text("file_id")
            val uniqueId = json.text("file_unique_id")
            if (fileId.isBlank() || !isSafeId(uniqueId)) return null
            val format = when {
                json.flag("is_video") -> TelegramStickerFormat.VIDEO
                json.flag("is_animated") -> TelegramStickerFormat.ANIMATED
                else -> TelegramStickerFormat.STATIC
            }
            // "thumb" is what the Bot API called it before 6.6.
            val thumb = (json["thumbnail"] ?: json["thumb"])?.objectOrNull()
            val thumbUnique = thumb?.text("file_unique_id").orEmpty()
            return TelegramSticker(
                fileId = fileId,
                uniqueId = uniqueId,
                format = format,
                emoji = json.text("emoji").trim(),
                thumbId = thumb?.text("file_id").orEmpty().takeIf { isSafeId(thumbUnique) }.orEmpty(),
                thumbUniqueId = thumbUnique.takeIf(::isSafeId).orEmpty(),
            )
        }

        /**
         * A unique id names a file in the cache, so it is held to the
         * characters Telegram uses for one: base64url, nothing that is a path.
         */
        private fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,$MAX_NAME_LENGTH}")

        private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

        private fun JsonObject.text(key: String): String =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

        private fun JsonObject.flag(key: String): Boolean =
            runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false

        /** The Bot API's own error code in an `ok: false` answer, or null. */
        fun errorCode(body: String?): Int? = body?.let {
            runCatching { JSON.parseToJsonElement(it) }.getOrNull()?.objectOrNull()
                ?.let { o -> runCatching { o["error_code"]?.jsonPrimitive?.intOrNull }.getOrNull() }
        }
    }
}
