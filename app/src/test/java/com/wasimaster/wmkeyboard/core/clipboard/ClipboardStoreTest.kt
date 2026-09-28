package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClipboardStoreTest {

    @Test fun addAndOrder() {
        val store = ClipboardStore(null)
        store.add("first", now = 1000)
        store.add("second", now = 2000)
        assertEquals(listOf("second", "first"), store.items(now = 3000).map { it.text })
    }

    @Test fun duplicateMovesToTop() {
        val store = ClipboardStore(null)
        store.add("a", now = 1000)
        store.add("b", now = 2000)
        store.add("a", now = 3000)
        val texts = store.items(now = 4000).map { it.text }
        assertEquals(listOf("a", "b"), texts)
        assertEquals(2, texts.size)
    }

    @Test fun pinnedFirstAndSurvivesExpiry() {
        val store = ClipboardStore(null, expiryMillis = 100)
        val old = store.add("keep me", now = 0)!!
        store.setPinned(old.id, true)
        store.add("fresh", now = 500)
        val texts = store.items(now = 550).map { it.text }
        assertEquals(listOf("keep me", "fresh"), texts)
    }

    @Test fun pinnedLastReversesGroupOrderNotRecency() {
        val store = ClipboardStore(null, pinnedLast = true)
        val pin = store.add("pinned", now = 1000)!!
        store.setPinned(pin.id, true)
        store.add("older", now = 2000)
        store.add("newer", now = 3000)
        // Unpinned group leads (newest-first), pinned trails.
        assertEquals(listOf("newer", "older", "pinned"), store.items(now = 4000).map { it.text })
    }

    @Test fun pinnedLastToggleFlipsLiveStore() {
        val store = ClipboardStore(null)
        val pin = store.add("pinned", now = 1000)!!
        store.setPinned(pin.id, true)
        store.add("fresh", now = 2000)
        assertEquals(listOf("pinned", "fresh"), store.items(now = 3000).map { it.text })
        store.pinnedLast = true
        assertEquals(listOf("fresh", "pinned"), store.items(now = 3000).map { it.text })
    }

    @Test fun editKeepsIdPlaceAndTimestamp() {
        val store = ClipboardStore(null)
        val a = store.add("teh answer", now = 1000)!!
        store.add("newer", now = 2000)
        val edited = store.editText(a.id, "  the answer \n")!!
        assertEquals(a.id, edited.id)
        assertEquals(1000, edited.timestamp)
        assertEquals("the answer", edited.text)
        assertEquals(listOf("newer", "the answer"), store.items(now = 3000).map { it.text })
    }

    @Test fun editRejectsBlankMissingAndNonText() {
        val store = ClipboardStore(null)
        val a = store.add("hello", now = 1000)!!
        assertNull(store.editText(a.id, "   "))
        assertNull(store.editText(999, "x"))
        assertEquals("hello", store.items(now = 2000).single().text)
        val file = store.addUri(
            "content://files/1", "report.pdf", "application/pdf", isDirectory = false, now = 1500,
        )!!
        assertNull(store.editText(file.id, "renamed"))
        assertEquals("report.pdf", store.items(now = 2000).first { it.id == file.id }.text)
    }

    @Test fun editDropsMarkupAndRedecidesLink() {
        val store = ClipboardStore(null)
        val rich = store.addHtml("bold", "<b>bold</b>", now = 1000)!!
        // Unchanged text keeps the markup: saving without an edit loses nothing.
        assertEquals(ClipKind.HTML, store.editText(rich.id, "bold")!!.kind)
        val plain = store.editText(rich.id, "https://example.com")!!
        assertEquals(ClipKind.LINK, plain.kind)
        assertNull(plain.htmlText)
        assertEquals("text/plain", plain.mimeType)
        assertEquals(ClipKind.TEXT, store.editText(rich.id, "no longer a link")!!.kind)
    }

    @Test fun editOntoAnotherClipMerges() {
        val store = ClipboardStore(null)
        val keep = store.add("one", now = 1000)!!
        val other = store.add("two", now = 2000)!!
        store.setPinned(other.id, true)
        val merged = store.editText(keep.id, "two")!!
        assertEquals(keep.id, merged.id)
        assertTrue(merged.pinned)
        assertEquals(listOf(keep.id), store.items(now = 3000).map { it.id })
    }

    @Test fun editKeepsSensitivity() {
        val store = ClipboardStore(null)
        val secret = store.add("hunter2", now = 1000, sensitive = true)!!
        assertTrue(store.editText(secret.id, "hunter3")!!.sensitive)
    }

    @Test fun clearSensitiveUnmasksAndReportsChange() {
        val store = ClipboardStore(null)
        store.add("hunter2", now = 1000, sensitive = true)
        store.add("plain", now = 2000)
        assertTrue(store.clearSensitive())
        assertTrue(store.items(now = 3000).none { it.sensitive })
        assertFalse(store.clearSensitive())
    }

    @Test fun panelOffersEditingForVisibleTextOnly() {
        val store = ClipboardStore(null)
        assertTrue(store.add("plain", now = 1000)!!.clipEditable)
        assertTrue(store.add("https://example.com", now = 1000)!!.clipEditable)
        assertFalse(store.add("hunter2", now = 1000, sensitive = true)!!.clipEditable)
        assertFalse(
            store.addUri("content://f/1", "a.pdf", "application/pdf", isDirectory = false, now = 1000)!!.clipEditable,
        )
    }

    @Test fun unpinnedExpires() {
        val store = ClipboardStore(null, expiryMillis = 100)
        store.add("ephemeral", now = 0)
        assertTrue(store.items(now = 200).isEmpty())
    }

    @Test fun searchFiltersCaseInsensitive() {
        val store = ClipboardStore(null, expiryMillis = 0)
        store.add("Hello World", now = 1000)
        store.add("other", now = 2000)
        assertEquals(1, store.search("hello").size)
    }

    @Test fun clearUnpinnedKeepsPins() {
        val store = ClipboardStore(null)
        val pin = store.add("pinned", now = 1000)!!
        store.setPinned(pin.id, true)
        store.add("gone", now = 2000)
        store.clearUnpinned()
        assertEquals(listOf("pinned"), store.items(now = 3000).map { it.text })
    }

    @Test fun detachUnpinnedKeepsPinsAndCanComeBack() {
        val store = ClipboardStore(null)
        val pin = store.add("pinned", now = 1000)!!
        store.setPinned(pin.id, true)
        store.add("older", now = 2000)
        store.add("newer", now = 3000)
        val removed = store.detachUnpinned(now = 4000)
        assertEquals(listOf("newer", "older"), removed.map { it.text })
        assertEquals(listOf("pinned"), store.items(now = 4000).map { it.text })
        removed.forEach { store.reattach(it, now = 5000) }
        assertEquals(listOf("pinned", "newer", "older"), store.items(now = 5000).map { it.text })
    }

    @Test fun detachUnpinnedKeepsImageFilesUntilDiscarded() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, imagesDir = dir)
        val file = tempImage(dir, "a.png")
        store.addImage(file, "image/png", now = 1000)
        val removed = store.detachUnpinned(now = 2000)
        assertEquals(1, removed.size)
        assertTrue(file.exists())
        removed.forEach(store::discard)
        assertFalse(file.exists())
    }

    private fun tempImage(dir: File, name: String): File =
        File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun addImageStoresKindAndPath() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, imagesDir = dir)
        val file = tempImage(dir, "a.png")
        val item = store.addImage(file, "image/png", now = 1000)!!
        assertEquals(ClipKind.IMAGE, item.kind)
        assertEquals(file.absolutePath, item.imagePath)
        assertEquals("image/png", item.mimeType)
    }

    @Test fun removingImageItemDeletesFile() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, imagesDir = dir)
        val file = tempImage(dir, "a.png")
        val item = store.addImage(file, "image/png", now = 1000)!!
        store.remove(item.id)
        assertFalse(file.exists())
    }

    @Test fun expiredImageDeletesFile() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, expiryMillis = 100, imagesDir = dir)
        val file = tempImage(dir, "a.png")
        store.addImage(file, "image/png", now = 0)
        assertTrue(store.items(now = 200).isEmpty())
        assertFalse(file.exists())
    }

    @Test fun htmlClipKeepsMarkupAndDedupes() {
        val store = ClipboardStore(null)
        store.add("hello", now = 1000)
        val item = store.addHtml("hello", "<b>hello</b>", now = 2000)!!
        assertEquals(ClipKind.HTML, item.kind)
        assertEquals("<b>hello</b>", item.htmlText)
        assertEquals(1, store.items(now = 3000).size)
    }

    @Test fun latestTextSkipsImages() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, imagesDir = dir)
        store.add("words", now = 1000)
        store.addImage(tempImage(dir, "a.png"), "image/png", now = 2000)
        assertEquals("words", store.latestText(now = 3000))
    }

    @Test fun blankSearchReturnsEverything() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, expiryMillis = 0, imagesDir = dir)
        store.addImage(tempImage(dir, "a.png"), "image/png", now = 1000)
        store.add("hello", now = 2000)
        assertEquals(2, store.search("").size)
    }

    @Test fun searchOnTextSkipsImages() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, expiryMillis = 0, imagesDir = dir)
        store.addImage(tempImage(dir, "a.png"), "image/png", now = 1000)
        store.add("hello", now = 2000)
        assertEquals(listOf("hello"), store.search("hell").map { it.text })
    }

    /** An image clip has no text of its own; its kind and format stand in. */
    @Test fun searchFindsImagesByKindAndFormat() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, expiryMillis = 0, imagesDir = dir)
        store.addImage(tempImage(dir, "a.png"), "image/png", now = 1000)
        store.add("hello", now = 2000)
        assertEquals(1, store.search("image").size)
        assertEquals(1, store.search("png").size)
    }

    @Test fun searchMatchesFileNameAndSourceApp() {
        val store = ClipboardStore(null, expiryMillis = 0)
        store.addUri("content://x/1", "report.pdf", "application/pdf", isDirectory = false, now = 1000)
        store.add("plain", sourceApp = "Firefox", now = 2000)
        assertEquals(listOf("report.pdf"), store.search("report").map { it.text })
        assertEquals(listOf("plain"), store.search("firefox").map { it.text })
    }

    @Test fun legacySnapshotWithoutNewFieldsLoads() {
        val file = Files.createTempFile("history", ".json").toFile()
        file.writeText("""{"items":[{"id":1,"text":"old","timestamp":1000}]}""")
        val store = ClipboardStore(file, expiryMillis = 0)
        val items = store.items(now = 2000)
        assertEquals(1, items.size)
        assertEquals(ClipKind.TEXT, items[0].kind)
        assertNull(items[0].imagePath)
    }

    @Test fun orphanImageFilesCleanedOnLoad() {
        val dir = Files.createTempDirectory("clips").toFile()
        val orphan = tempImage(dir, "orphan.png")
        val storageFile = Files.createTempFile("history", ".json").toFile()

        val first = ClipboardStore(storageFile, expiryMillis = 0, imagesDir = dir)
        val kept = tempImage(dir, "kept.png")
        first.addImage(kept, "image/png", now = 1000)
        first.save()

        val second = ClipboardStore(storageFile, expiryMillis = 0, imagesDir = dir)
        assertNotNull(second)
        assertTrue(kept.exists())
        assertFalse(orphan.exists())
    }

    @Test fun reattachPutsAClipBackInItsOwnSlot() {
        val store = ClipboardStore(null, expiryMillis = 0)
        store.add("a", now = 1000)
        val b = store.add("b", now = 2000)!!
        store.setPinned(b.id, true)
        store.add("c", now = 3000)
        val removed = store.detach(b.id)!!
        assertEquals(listOf("c", "a"), store.items(now = 4000).map { it.text })
        val back = store.reattach(removed, now = 4000)!!
        assertEquals(b.id, back.id)
        assertTrue(back.pinned)
        assertEquals(listOf("b", "c", "a"), store.items(now = 4000).map { it.text })
        // Undo is spent: a second one finds nothing to put back.
        assertNull(store.reattach(removed, now = 4000))
    }

    @Test fun detachedImageKeepsItsFileUntilDiscarded() {
        val dir = Files.createTempDirectory("clips").toFile()
        val store = ClipboardStore(null, expiryMillis = 0, imagesDir = dir)
        val file = tempImage(dir, "a.png")
        val item = store.addImage(file, "image/png", now = 1000)!!
        val removed = store.detach(item.id)!!
        assertTrue(store.items(now = 2000).isEmpty())
        assertTrue(file.exists())
        store.discard(removed)
        assertFalse(file.exists())
        assertNull(store.reattach(removed, now = 2000))
    }

    @Test fun detachedImageSurvivesAReloadAndComesBack() {
        val dir = Files.createTempDirectory("clips").toFile()
        val storageFile = Files.createTempFile("history", ".json").toFile()
        val store = ClipboardStore(storageFile, expiryMillis = 0, imagesDir = dir)
        val file = tempImage(dir, "a.png")
        val item = store.addImage(file, "image/png", now = 1000)!!
        store.save()
        val removed = store.detach(item.id)!!
        store.save()
        // The panel reloads on open; its orphan sweep must not take the file.
        store.reload()
        assertTrue(file.exists())
        // Nor may a clip copied meanwhile take the detached clip's id.
        val fresh = store.add("fresh", now = 1500)!!
        assertTrue(fresh.id != removed.id)
        assertNotNull(store.reattach(removed, now = 2000))
        assertEquals(listOf(ClipKind.TEXT, ClipKind.IMAGE), store.items(now = 2000).map { it.kind })
    }

    @Test fun reattachSkipsAClipCopiedAgainMeanwhile() {
        val store = ClipboardStore(null, expiryMillis = 0)
        val a = store.add("a", now = 1000)!!
        val removed = store.detach(a.id)!!
        store.add("a", now = 2000)
        assertNull(store.reattach(removed, now = 3000))
        assertEquals(listOf("a"), store.items(now = 3000).map { it.text })
    }

    @Test fun reattachHonoursExpiry() {
        val store = ClipboardStore(null, expiryMillis = 100)
        val a = store.add("a", now = 0)!!
        val removed = store.detach(a.id)!!
        assertNull(store.reattach(removed, now = 500))
        assertTrue(store.items(now = 500).isEmpty())
    }

    @Test fun maxItemsCapsUnpinnedHistory() {
        val store = ClipboardStore(null, expiryMillis = 0, maxItems = 3)
        repeat(6) { store.add("clip $it", now = 1000L + it) }
        val texts = store.items(now = 9000).map { it.text }
        assertEquals(listOf("clip 5", "clip 4", "clip 3"), texts)
    }

    @Test fun zeroMaxItemsKeepsEveryClip() {
        val store = ClipboardStore(null, expiryMillis = 0, maxItems = 0)
        repeat(600) { store.add("clip $it", now = 1000L + it) }
        assertEquals(600, store.items(now = 9000).size)
    }

    @Test fun pinnedItemsDoNotCountAgainstTheCap() {
        val store = ClipboardStore(null, expiryMillis = 0, maxItems = 2)
        val pinned = store.add("keep me", now = 1000)!!
        store.setPinned(pinned.id, true)
        repeat(4) { store.add("clip $it", now = 2000L + it) }
        val texts = store.items(now = 9000).map { it.text }
        assertEquals(listOf("keep me", "clip 3", "clip 2"), texts)
    }

    @Test fun sensitiveClipsExpireOnTheirOwnShorterTimer() {
        // History keeps everything; the sensitive leash still has to hold.
        val store = ClipboardStore(null, expiryMillis = 0, sensitiveExpiryMillis = 500)
        store.add("secret", now = 1000, sensitive = true)
        store.add("ordinary", now = 1000)
        assertEquals(listOf("ordinary", "secret"), store.items(now = 1400).map { it.text })
        assertEquals(listOf("ordinary"), store.items(now = 1600).map { it.text })
    }

    @Test fun pinningKeepsASensitiveClip() {
        val store = ClipboardStore(null, expiryMillis = 0, sensitiveExpiryMillis = 500)
        val secret = store.add("secret", now = 1000, sensitive = true)!!
        store.setPinned(secret.id, true)
        assertEquals(listOf("secret"), store.items(now = 99_000).map { it.text })
    }

    @Test fun reCopyingOnlyEverTightensSensitivity() {
        val store = ClipboardStore(null, expiryMillis = 0)
        store.add("hunter2", now = 1000, sensitive = true)
        val again = store.add("hunter2", now = 2000, sensitive = false)
        assertTrue(again!!.sensitive)
    }

    @Test fun videoUrisBecomeVideoClips() {
        val store = ClipboardStore(null, expiryMillis = 0)
        val clip = store.addUri(
            uriString = "content://media/external/video/1",
            displayName = "holiday.mp4",
            mimeType = "video/mp4",
            isDirectory = false,
            size = 4096,
            durationMs = 64_000,
            now = 1000,
        )
        assertEquals(ClipKind.VIDEO, clip!!.kind)
        assertEquals(64_000, clip.durationMs)
        assertTrue(clip.kind.isUriBacked)
        assertFalse(clip.kind.isTextual)
    }

    @Test fun noTextLimitByDefault() {
        val store = ClipboardStore(null)
        val long = "x".repeat(200_000)
        assertEquals(long, store.add(long, now = 1)!!.text)
    }

    @Test fun textLimitCutsALongCopy() {
        val store = ClipboardStore(null, maxTextChars = 10)
        assertEquals("0123456789", store.add("0123456789abcdef", now = 1)!!.text)
    }

    @Test fun textLimitDropsMarkupThatNoLongerMatches() {
        val store = ClipboardStore(null, maxTextChars = 5)
        val cut = store.addHtml("bold words", "<b>bold</b> words", now = 1)!!
        assertEquals("bold", cut.text)
        assertEquals(ClipKind.TEXT, cut.kind)
        assertNull(cut.htmlText)
        val whole = store.addHtml("hi", "<b>hi</b>", now = 2)!!
        assertEquals(ClipKind.HTML, whole.kind)
    }

    @Test fun textLimitNeverSplitsASurrogatePair() {
        assertEquals("ab", capClipText("ab😀", 3))
        assertEquals("ab😀", capClipText("ab😀", 4))
        assertEquals("ab😀", capClipText("ab😀", 0))
    }

    @Test fun editsAreCutToo() {
        val store = ClipboardStore(null, maxTextChars = 3)
        val clip = store.add("abc", now = 1)!!
        assertEquals("xyz", store.editText(clip.id, "xyzzy")!!.text)
    }

    @Test fun previewKeepsOneLineMoreThanShown() {
        assertEquals("a\nb\nc", clipPreviewText("a\nb\nc\nd\ne", lines = 2))
        assertEquals("a\nb", clipPreviewText("a\nb", lines = 2))
        assertEquals("one", clipPreviewText("one", lines = 1))
    }

    @Test fun previewIsCappedOnOneEndlessLine() {
        val text = "y".repeat(CLIP_PREVIEW_CHAR_CAP * 3)
        assertEquals(CLIP_PREVIEW_CHAR_CAP, clipPreviewText(text, lines = 20).length)
    }

    @Test fun previewOfALeadingBreakIsNotTheWholeText() {
        val text = "\n\n\n" + "z".repeat(10)
        assertEquals("\n", clipPreviewText(text, lines = 1))
    }

    @Test fun expiresAtTakesTheSoonerOfTheTwoTimers() {
        val clip = ClipItem(id = 1, text = "t", timestamp = 1_000)
        assertEquals(1_000L + 500, clip.expiresAt(expiryMillis = 500, sensitiveExpiryMillis = 100))
        assertEquals(1_000L + 100, clip.copy(sensitive = true).expiresAt(500, 100))
        assertEquals(1_000L + 100, clip.copy(sensitive = true).expiresAt(0, 100))
        assertNull(clip.expiresAt(0, 100))
        assertNull(clip.copy(pinned = true, sensitive = true).expiresAt(500, 100))
    }
}
