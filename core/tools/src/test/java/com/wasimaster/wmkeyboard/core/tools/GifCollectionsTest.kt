package com.wasimaster.wmkeyboard.core.tools

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GIF panel's favourites and recent GIFs (#597). */
class GifCollectionsTest {

    private fun dir(): File = Files.createTempDirectory("gifcollections").toFile()

    private fun gif(id: String) = GifItem(
        id = id,
        previewUrl = "https://example.com/$id-small.gif",
        fullUrl = "https://example.com/$id.gif",
        mime = "image/gif",
        aspectRatio = 1.5f,
        source = GifSource.KLIPY,
        title = "Title $id",
    )

    @Test
    fun `a toggle adds, a second toggle removes`() {
        val store = GifCollections(dir())
        assertTrue(store.toggleFavourite(gif("a")))
        assertEquals(setOf("a"), store.favouriteIds())
        assertFalse(store.toggleFavourite(gif("a")))
        assertTrue(store.favourites().isEmpty())
    }

    @Test
    fun `favourites and recent GIFs are newest first and survive a reload`() {
        val root = dir()
        val store = GifCollections(root)
        store.toggleFavourite(gif("a"))
        store.toggleFavourite(gif("b"))
        store.recordSent(gif("x"))
        store.recordSent(gif("y"))
        store.recordSent(gif("x"))
        val again = GifCollections(root)
        assertEquals(listOf("b", "a"), again.favourites().map { it.id })
        assertEquals(listOf("x", "y"), again.recents().map { it.id })
        assertEquals(gif("a"), again.favourites().last())
    }

    @Test
    fun `recent GIFs stop at the cap`() {
        val store = GifCollections(dir())
        repeat(GifCollections.MAX_RECENTS + 5) { store.recordSent(gif("g$it")) }
        val recents = store.recents()
        assertEquals(GifCollections.MAX_RECENTS, recents.size)
        assertEquals("g${GifCollections.MAX_RECENTS + 4}", recents.first().id)
    }

    @Test
    fun `a kept copy stands in for the provider, in favourites and in recent`() {
        val root = dir()
        val store = GifCollections(root)
        store.toggleFavourite(gif("a"))
        store.recordSent(gif("a"))
        val download = File(root, "download.gif").apply { writeText("GIF89a") }
        assertTrue(store.keepCopy("a", download))
        assertTrue(store.missingCopies().isEmpty())
        val shown = store.favourites().single()
        assertTrue(shown.fullUrl.startsWith("file://"))
        assertEquals(shown.fullUrl, shown.previewUrl)
        assertEquals(shown, store.recents().single())
        // Sending the copy records the provider's GIF, not the file: once the
        // favourite and its copy are gone, recent still has the GIF.
        store.recordSent(shown)
        store.toggleFavourite(shown)
        assertEquals(listOf(gif("a")), GifCollections(root).recents())
    }

    @Test
    fun `dropping the copies keeps the favourites`() {
        val root = dir()
        val store = GifCollections(root)
        store.toggleFavourite(gif("a"))
        store.keepCopy("a", File(root, "download.gif").apply { writeText("GIF89a") })
        store.dropCopies()
        assertEquals(listOf(gif("a")), store.favourites())
        assertEquals(listOf(gif("a")), store.missingCopies())
        assertEquals(0L, store.copiesSize())
    }

    @Test
    fun `removing a favourite deletes its copy`() {
        val root = dir()
        val store = GifCollections(root)
        store.toggleFavourite(gif("a"))
        store.keepCopy("a", File(root, "download.gif").apply { writeText("GIF89a") })
        store.toggleFavourite(gif("a"))
        assertEquals(0L, store.copiesSize())
    }

    @Test
    fun `a pack GIF on the phone needs no copy`() {
        val store = GifCollections(dir())
        store.toggleFavourite(gif("p").copy(source = GifSource.OFFLINE, fullUrl = "file:///x.gif"))
        assertTrue(store.missingCopies().isEmpty())
    }

    @Test
    fun `a broken file reads as empty lists`() {
        val root = dir()
        File(root, "collections.json").writeText("{not json")
        val store = GifCollections(root)
        assertTrue(store.favourites().isEmpty())
        assertTrue(store.recents().isEmpty())
    }
}
