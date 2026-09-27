package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.GifSource.COMMONS
import com.wasimaster.wmkeyboard.core.tools.GifSource.GIPHY
import com.wasimaster.wmkeyboard.core.tools.GifSource.KLIPY
import com.wasimaster.wmkeyboard.core.tools.GifSource.LOCAL
import com.wasimaster.wmkeyboard.core.tools.GifSource.OFFLINE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GIF panel's imported packs ([OFFLINE]) are placed the way the sticker
 * panel's own packs ([LOCAL]) always were: a grid of their own, never
 * interleaved with a provider, and a chip of their own in mixed mode.
 */
class GifSourcesOnDeviceTest {

    @Test
    fun `picking an on-device source is always its own grid`() {
        assertEquals(listOf(OFFLINE), GifSources.targets(listOf(KLIPY, GIPHY, OFFLINE), OFFLINE, tabs = false))
        assertEquals(listOf(OFFLINE), GifSources.targets(listOf(KLIPY, GIPHY, OFFLINE), OFFLINE, tabs = true))
        assertEquals(listOf(LOCAL), GifSources.targets(listOf(KLIPY, LOCAL), LOCAL, tabs = false))
    }

    @Test
    fun `mixed mode interleaves only the providers`() {
        assertEquals(listOf(KLIPY, GIPHY), GifSources.targets(listOf(KLIPY, GIPHY, OFFLINE), KLIPY, tabs = false))
    }

    @Test
    fun `offline packs alone are the whole panel`() {
        assertEquals(listOf(OFFLINE), GifSources.targets(listOf(OFFLINE), KLIPY, tabs = false))
        assertEquals(listOf(OFFLINE), GifSources.targets(listOf(OFFLINE), KLIPY, tabs = true))
        assertTrue(GifSources.chips(listOf(OFFLINE), tabs = false).isEmpty())
        assertTrue(GifSources.chips(listOf(OFFLINE), tabs = true).isEmpty())
    }

    @Test
    fun `mixed mode gives the offline packs a chip beside Online`() {
        val chips = GifSources.chips(listOf(KLIPY, GIPHY, OFFLINE), tabs = false)
        assertEquals(listOf(KLIPY, OFFLINE), chips.map { it.source })
        assertEquals(1, GifSources.selectedChip(chips, OFFLINE))
        assertEquals(0, GifSources.selectedChip(chips, GIPHY))
    }

    @Test
    fun `the sticker panel keeps its two chips`() {
        val chips = GifSources.chips(listOf(KLIPY, GIPHY, LOCAL), tabs = false)
        assertEquals(listOf(KLIPY, LOCAL), chips.map { it.source })
    }

    @Test
    fun `mixed mode with providers only has no chips`() {
        assertTrue(GifSources.chips(listOf(KLIPY, GIPHY, COMMONS), tabs = false).isEmpty())
    }

    @Test
    fun `only the files on the device count as on-device`() {
        assertEquals(setOf(LOCAL, OFFLINE), GifSource.entries.filter { it.onDevice }.toSet())
    }
}
