package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.addons.ImportLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the manifest claims of Telegram's links: the two sticker hosts of
 * `tg://` and nothing else.
 *
 * `tg://` is how Telegram opens a chat, a login code, a proxy and a payment.
 * A filter widened by accident would put a keyboard in the chooser for
 * somebody's login link. Android joins every `<data>` of one filter, so the
 * filter that names the scheme must name only these hosts.
 */
class TelegramLinkManifestTest {

    private val manifest: String by lazy { File("src/main/AndroidManifest.xml").readText() }

    private val tgFilters: List<String> by lazy {
        Regex("""<intent-filter\b.*?</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .map { it.value }
            .filter { it.contains("""android:scheme="tg"""") }
            .toList()
    }

    @Test
    fun `the tg scheme is claimed for sticker sets only`() {
        assertEquals("one tg filter", 1, tgFilters.size)
        val hosts = Regex("""android:host="([^"]+)"""").findAll(tgFilters.single()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("addstickers", "addemoji"), hosts)
        // No path or other scheme rides along in the same filter.
        val schemes = Regex("""android:scheme="([^"]+)"""").findAll(tgFilters.single()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("tg"), schemes)
    }

    @Test
    fun `telegram's web domains are not claimed`() {
        assertFalse(manifest.contains("""android:host="t.me""""))
        assertFalse(manifest.contains("""android:host="telegram.me""""))
    }

    @Test
    fun `what the filter lets in is what the link reader understands`() {
        assertEquals(
            ImportLink.Target.TelegramStickers("HotCherry"),
            ImportLink.resolve("tg://addstickers?set=HotCherry"),
        )
        assertEquals(
            ImportLink.Target.TelegramStickers("RestrictedEmoji"),
            ImportLink.resolve("tg://addemoji?set=RestrictedEmoji"),
        )
        assertTrue(ImportLink.resolve("tg://resolve?domain=durov") !is ImportLink.Target.TelegramStickers)
        assertTrue(ImportLink.resolve("tg://login?code=12345") !is ImportLink.Target.TelegramStickers)
    }
}
