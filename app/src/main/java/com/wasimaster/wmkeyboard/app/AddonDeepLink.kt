package com.wasimaster.wmkeyboard.app

import android.net.Uri
import com.wasimaster.wmkeyboard.core.addons.AddonRepoCodec
import com.wasimaster.wmkeyboard.core.stickers.signal.SignalStickerCrypto
import com.wasimaster.wmkeyboard.core.stickers.telegram.TelegramStickerSet
import java.net.URI
import java.net.URLDecoder

/**
 * `wmkeyboard://` links into the addon store, so a repository's README, a blog
 * post or a shared message can open an addon straight in the app.
 *
 * | Link | Lands on |
 * |---|---|
 * | `wmkeyboard://addons` | the Addons screen |
 * | `wmkeyboard://repo?url=<repo>` | Addons, with the add-repository sheet pre-filled |
 * | `wmkeyboard://addon?repo=<repo>&id=<addonId>` | that addon's detail page |
 * | `wmkeyboard://signalpack?id=<pack id>&key=<pack key>` | the preview of that Signal sticker pack |
 * | `wmkeyboard://telegramset?name=<set name>` | the preview of that Telegram sticker set |
 *
 * A custom scheme rather than https App Links: no domain to verify, no
 * `assetlinks.json` to host, and nothing about this needs a real web page to
 * exist at the other end.
 *
 * **A link never installs anything, and never adds a repository.** It is
 * untrusted input from a web page, so all it can do is navigate — the resulting
 * screen shows the repository address, the addon and its author, and the user
 * taps Install. Adding a repository likewise goes through the normal dialog,
 * which shows the resolved URL before anything is fetched.
 *
 * Parsing works on the raw string rather than [Uri] so it is testable off
 * device; [Uri] is a framework class with no behaviour in a JVM unit test.
 */
object AddonDeepLink {

    const val SCHEME = "wmkeyboard"

    /** Convenience for the intent's data. */
    fun routeFor(uri: Uri?): String? = routeFor(uri?.toString())

    /**
     * The in-app route [link] names, or null when it isn't one of ours or the
     * URL it carries isn't a resolvable https repository address.
     */
    fun routeFor(link: String?): String? {
        val text = link?.trim().orEmpty()
        if (text.isEmpty()) return null

        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null

        // "wmkeyboard://addons" puts the target in the authority; the opaque
        // "wmkeyboard:addons" form puts it in the scheme-specific part. Accept
        // both rather than making anyone care which they wrote.
        val body = if (uri.isOpaque) uri.schemeSpecificPart.orEmpty() else {
            uri.host ?: uri.path.orEmpty().trim('/')
        }
        val target = body.substringBefore('?').trim('/').lowercase()
        val query = text.substringAfter('?', "")

        return when (target) {
            "addons" -> "addons"

            "repo" -> {
                val pasted = query.param("url") ?: return "addons"
                val resolved = AddonRepoCodec.resolveManifestUrl(pasted) ?: return null
                // Pre-fills the add dialog; it is still the user who confirms.
                addonsAddRoute(resolved)
            }

            "addon" -> {
                val pasted = query.param("repo") ?: return null
                val id = query.param("id") ?: return null
                val resolved = AddonRepoCodec.resolveManifestUrl(pasted) ?: return null
                addonDetailRoute(resolved, id)
            }

            // Only ever sent by the app to itself, from the link importer: the
            // manifest declares no `signalpack` host, so nothing outside can
            // open it. Still read as untrusted, which costs nothing.
            SIGNAL_PACK_HOST -> {
                val id = query.param("id")?.let(SignalStickerCrypto::packIdOrNull) ?: return null
                val key = query.param("key")?.let(SignalStickerCrypto::packKeyOrNull) ?: return null
                signalPackRoute(id, key)
            }

            // The same, for a Telegram set. Its name is all it carries.
            TELEGRAM_SET_HOST -> {
                val name = query.param("name")?.takeIf(TelegramStickerSet::isSetName) ?: return null
                telegramPackRoute(name)
            }

            else -> null
        }
    }

    private const val SIGNAL_PACK_HOST = "signalpack"
    private const val TELEGRAM_SET_HOST = "telegramset"

    /** The link [routeFor] turns back into [signalPackRoute]. Both halves are hex. */
    fun signalPackLink(packId: String, packKey: String): String =
        "$SCHEME://$SIGNAL_PACK_HOST?id=$packId&key=$packKey"

    /** The link [routeFor] turns back into [telegramPackRoute]. A set name needs no encoding. */
    fun telegramSetLink(setName: String): String = "$SCHEME://$TELEGRAM_SET_HOST?name=$setName"

    /** One query parameter out of a raw `a=1&b=2` string, percent-decoded. */
    private fun String.param(name: String): String? = split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
}
