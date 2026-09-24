package com.wasimaster.wmkeyboard.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun target(): File = File(temp.root, "learning/store.json")

    @Test
    fun writesTheTextAndLeavesNoPartFileBehind() {
        val file = SnapshotFile(target())
        assertTrue(file.write(file.ticket()) { "one" })
        assertEquals("one", target().readText())
        assertFalse(File(target().parentFile, "store.json.part").exists())
    }

    @Test
    fun anOlderSnapshotFinishingLastDoesNotOverwriteANewerOne() {
        val file = SnapshotFile(target())
        val older = file.ticket()
        val newer = file.ticket()
        assertTrue(file.write(newer) { "newer" })
        var encoded = false
        assertTrue(file.write(older) { encoded = true; "older" })
        assertEquals("newer", target().readText())
        assertFalse("a stale snapshot is not even encoded", encoded)
    }

    @Test
    fun deleteRetiresEverySnapshotDrawnBeforeIt() {
        val file = SnapshotFile(target())
        assertTrue(file.write(file.ticket()) { "kept" })
        val beforeClear = file.ticket()
        assertTrue(file.delete())
        assertTrue(file.write(beforeClear) { "stale" })
        assertFalse("the delete must stick", target().exists())
        assertTrue(file.write(file.ticket()) { "after" })
        assertEquals("after", target().readText())
    }

    @Test
    fun supersedeDropsWritesOfMemoryThatWasReloaded() {
        val file = SnapshotFile(target())
        assertTrue(file.write(file.ticket()) { "on disk" })
        val stale = file.ticket()
        file.supersede()
        assertTrue(file.write(stale) { "stale" })
        assertEquals("on disk", target().readText())
    }

    @Test
    fun aFailedWriteReportsFalseAndKeepsTheOldFile() {
        val file = SnapshotFile(target())
        assertTrue(file.write(file.ticket()) { "good" })
        assertFalse(file.write(file.ticket()) { error("encoding failed") })
        assertEquals("good", target().readText())
    }
}
