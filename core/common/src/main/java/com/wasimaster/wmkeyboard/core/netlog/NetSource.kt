package com.wasimaster.wmkeyboard.core.netlog

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/**
 * Which part of the keyboard made a network request, for the network activity
 * log.
 *
 * [id] is what the log file stores, so it is stable across renames: never store
 * [name]. The labels, the "why it happened" line and the "what was sent" note
 * live in `:app`, next to the screen that shows them.
 *
 * [tool] is the toolbar tool a source belongs to, when it belongs to one. The
 * screen draws that tool's icon in that tool's colour, so a row in the log looks
 * like the key that caused it. Sources that are not tools (downloads, backup,
 * updates) borrow the icon of the settings screen that owns them instead.
 *
 * [background] is the default for requests nobody asked for at that moment: a
 * link preview for a copied URL, refilling the photo pool, a scheduled backup.
 * A call site can override it, for the sources that happen both ways.
 */
enum class NetSource(
    val id: String,
    val tool: ToolbarTool? = null,
    val background: Boolean = false,
) {
    TRANSLATE("translate", ToolbarTool.TRANSLATE),

    /** DeepL Write, from the grammar panel or the selection bar. Its settings sit on the Translate page. */
    DEEPL_WRITE("deepl_write", ToolbarTool.TRANSLATE),
    GIF("gif", ToolbarTool.GIF),
    STICKER("sticker", ToolbarTool.STICKER),
    WEB_SEARCH("web_search", ToolbarTool.WEB_SEARCH),
    IMAGE_SEARCH("image_search", ToolbarTool.IMAGE_SEARCH),

    /** A camera photo uploaded to a reverse image search site (#349). */
    PHOTO_SEARCH("photo_search", ToolbarTool.CAMERA),
    AI("ai", ToolbarTool.AI),
    AI_CHAT("ai_chat", ToolbarTool.AI),

    /**
     * A search or a page the AI tool made on its own, because the model asked
     * for it (#470). Its own source rather than [WEB_SEARCH]: the user did not
     * run that search, and a log that said they had would be wrong about the
     * one thing it exists to record.
     */
    AI_TOOLS("ai_tools", ToolbarTool.AI),

    /**
     * A request to one of the MCP servers the user added (#573): listing its
     * tools on Connect, or a call a model made. Not [AI_TOOLS], because the
     * host is the user's choice and the request carries whatever the model
     * wrote as the tool's arguments.
     */
    AI_MCP("ai_mcp", ToolbarTool.AI),
    TRANSCRIPTION("transcription", ToolbarTool.VOICE),
    WIKIPEDIA("wikipedia", ToolbarTool.WIKIPEDIA),
    DICTIONARY("dictionary", ToolbarTool.DICTIONARY),
    VOCABULARY("vocabulary", ToolbarTool.VOCABULARY),

    /** Synonyms for a word held on the suggestion strip (#321). Its settings sit on the Suggestions page. */
    SYNONYMS("synonyms"),
    WEATHER("weather", ToolbarTool.WEATHER),
    CURRENCY("currency", ToolbarTool.CURRENCY),
    /** Searching photos is asked for; refilling the rotation pool passes `background` itself. */
    PHOTOS("photos"),
    LINK_PREVIEW("link_preview", ToolbarTool.CLIPBOARD, background = true),

    /** Thumbnails and animations drawn by the GIF, sticker and image panels. */
    MEDIA_IMAGES("media_images"),
    DOWNLOAD_WORDLIST("download_wordlist"),
    DOWNLOAD_NGRAM("download_ngram"),
    DOWNLOAD_CJK("download_cjk"),
    DOWNLOAD_EMOJI("download_emoji", ToolbarTool.EMOJI),
    DOWNLOAD_WHISPER("download_whisper", ToolbarTool.VOICE),
    DOWNLOAD_LLM("download_llm", ToolbarTool.AI),
    DOWNLOAD_VOCAB("download_vocab", ToolbarTool.VOCABULARY),
    DOWNLOAD_FONT("download_font"),
    DOWNLOAD_CUTOUT("download_cutout", ToolbarTool.STICKER),

    /** A photo the scan text tool sends to the user's online text reader (#469). */
    OCR_ONLINE("ocr_online", ToolbarTool.OCR),

    /** Tesseract language data for the scan text tool. */
    DOWNLOAD_OCR("download_ocr", ToolbarTool.OCR),
    ADDONS("addons"),
    KEYMAN("keyman"),
    SIGNAL_STICKERS("signal_stickers", ToolbarTool.STICKER),
    TELEGRAM_STICKERS("telegram_stickers", ToolbarTool.STICKER),

    /** The Rboard theme list and the packs picked from it, on the Themes screen. */
    RBOARD_THEMES("rboard_themes"),
    LINK_IMPORT("link_import"),
    BACKUP("backup"),
    UPDATES("updates", background = true),
    KDE_CONNECT("kde_connect", ToolbarTool.KDE_CONNECT),
    OTHER("other"),
    ;

    companion object {
        fun of(id: String): NetSource = entries.firstOrNull { it.id == id } ?: OTHER
    }
}
