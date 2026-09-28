package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupNamingTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun entry(name: String, modifiedAtMs: Long) =
        SinkEntry(id = name, name = name, sizeBytes = 1, modifiedAtMs = modifiedAtMs)

    @Test
    fun `a name stamps the second and carries the right extension`() {
        val stamp = 1_754_575_353_000L // 2025-08-07 14:02:33 UTC
        assertEquals(
            "wmkeyboard-auto-20250807-140233.wmconfig.json",
            AutoBackupNaming.name(stamp, encrypted = false, zone = utc),
        )
        assertEquals(
            "wmkeyboard-auto-20250807-140233.wmconfig.enc",
            AutoBackupNaming.name(stamp, encrypted = true, zone = utc),
        )
    }

    @Test
    fun `a generated name is recognised as ours`() {
        for (encrypted in listOf(false, true)) {
            val name = AutoBackupNaming.name(0L, encrypted, utc)
            assertTrue(name, AutoBackupNaming.isOurs(name))
            assertFalse(name, AutoBackupNaming.isPart(name))
        }
    }

    @Test
    fun `nothing else in the folder is ours`() {
        val strangers = listOf(
            // The user's own files, which the destination folder is full of.
            "tax-return-2025.pdf",
            "notes.wmconfig.json",
            "",
            // A hand-made export. Deliberate, so rotation must not eat it.
            "wmkeyboard-backup-20250807-140233.wmconfig.json",
            // Our prefix but not our format.
            "wmkeyboard-auto-20250807-140233.txt",
            "wmkeyboard-auto-20250807-140233.wmsettings.json",
            // Nearly right, and the near miss is the dangerous one.
            "wmkeyboard-auto-20250807-140233.wmconfig.json.bak",
        )
        for (name in strangers) assertFalse(name, AutoBackupNaming.isOurs(name))
    }

    @Test
    fun `a half-written file is ours to sweep but never ours to rotate`() {
        val part = AutoBackupNaming.name(0L, encrypted = false, zone = utc) +
            AutoBackupNaming.PART_SUFFIX
        assertTrue(AutoBackupNaming.isPart(part))
        assertFalse(AutoBackupNaming.isOurs(part))
        assertFalse(AutoBackupNaming.isPart("someone-elses.part"))
    }

    @Test
    fun `a WebDAV upload in flight is swept like a part file and never rotated`() {
        val upload = AutoBackupNaming.name(0L, encrypted = false, zone = utc) +
            AutoBackupNaming.UPLOAD_SUFFIX
        // Nextcloud 30+ answers 400 to any name ending in .part (#411).
        assertFalse(upload.endsWith(".part"))
        assertTrue(AutoBackupNaming.isPart(upload))
        assertFalse(AutoBackupNaming.isOurs(upload))
        assertFalse(AutoBackupNaming.isListed(upload))
        assertFalse(AutoBackupNaming.isPart("someone-elses.upload"))
    }

    @Test
    fun `rotation deletes the oldest and keeps the newest`() {
        val entries = listOf(
            entry("c", 300),
            entry("a", 100),
            entry("d", 400),
            entry("b", 200),
        )
        assertEquals(listOf("a", "b"), AutoBackupNaming.rotation(entries, keep = 2).map { it.name })
    }

    @Test
    fun `rotation is a no-op while there is room`() {
        val entries = listOf(entry("a", 100), entry("b", 200))
        assertTrue(AutoBackupNaming.rotation(entries, keep = 2).isEmpty())
        assertTrue(AutoBackupNaming.rotation(entries, keep = 9).isEmpty())
        assertTrue(AutoBackupNaming.rotation(emptyList(), keep = 1).isEmpty())
    }

    @Test
    fun `the name breaks a tie when a provider reports no modified time`() {
        // Several providers return 0 for COLUMN_LAST_MODIFIED. The stamp in the
        // name is then the only ordering left, and it has to be enough.
        val entries = listOf(
            entry(AutoBackupNaming.name(3_000L, false, utc), 0),
            entry(AutoBackupNaming.name(1_000L, false, utc), 0),
            entry(AutoBackupNaming.name(2_000L, false, utc), 0),
        )
        val deleted = AutoBackupNaming.rotation(entries, keep = 1).map { it.name }
        assertEquals(
            listOf(
                AutoBackupNaming.name(1_000L, false, utc),
                AutoBackupNaming.name(2_000L, false, utc),
            ),
            deleted,
        )
    }

    @Test
    fun `rotation never empties the folder`() {
        val entries = listOf(entry("a", 100), entry("b", 200))
        // A keep of 0 is not a request to delete everything, whatever the box says.
        assertEquals(listOf("a"), AutoBackupNaming.rotation(entries, keep = 0).map { it.name })
        assertEquals(listOf("a"), AutoBackupNaming.rotation(entries, keep = -5).map { it.name })
    }

    @Test
    fun `a name can say which installation and device wrote it`() {
        val stamp = 1_754_575_353_000L
        val name = AutoBackupNaming.name(
            stamp,
            encrypted = true,
            zone = utc,
            installId = "a1b2c3d4",
            device = "Wasi's Pixel 8 (work)",
        )
        assertEquals("wmkeyboard-auto-20250807-140233_a1b2c3d4_Wasi-s-Pixel-8-work.wmconfig.enc", name)
        assertTrue(AutoBackupNaming.isOurs(name))
        val parsed = AutoBackupNaming.parse(name, utc)!!
        assertEquals(stamp, parsed.stampMs)
        assertEquals("a1b2c3d4", parsed.installId)
        assertEquals("Wasi-s-Pixel-8-work", parsed.device)
        assertTrue(parsed.encrypted)
    }

    @Test
    fun `a name from before owners still parses, with no owner`() {
        val parsed = AutoBackupNaming.parse("wmkeyboard-auto-20250807-140233.wmconfig.json", utc)!!
        assertEquals(1_754_575_353_000L, parsed.stampMs)
        assertEquals(null, parsed.installId)
        assertEquals(null, parsed.device)
        assertFalse(parsed.encrypted)
    }

    @Test
    fun `a device name with nothing usable leaves just the id`() {
        assertEquals("", AutoBackupNaming.deviceSlug("ওয়াসির ফোন"))
        val name = AutoBackupNaming.name(0L, false, utc, installId = "0badf00d", device = "ওয়াসির ফোন")
        assertEquals("wmkeyboard-auto-19700101-000000_0badf00d.wmconfig.json", name)
        assertEquals("0badf00d", AutoBackupNaming.parse(name, utc)!!.installId)
    }

    @Test
    fun `a long device name is cut, not the id`() {
        val slug = AutoBackupNaming.deviceSlug("A".repeat(80))
        assertEquals(32, slug.length)
        val name = AutoBackupNaming.name(0L, false, utc, installId = "12345678", device = "A".repeat(80))
        assertEquals("12345678", AutoBackupNaming.parse(name, utc)!!.installId)
    }

    @Test
    fun `strangers do not parse`() {
        for (name in listOf(
            "wmkeyboard-backup-20250807-140233.wmconfig.json",
            "wmkeyboard-auto-20250807-140233_XYZ.wmconfig.json",
            "wmkeyboard-auto-20250807-140233.wmconfig.json.bak",
            "tax-return-2025.pdf",
        )) {
            assertEquals(name, null, AutoBackupNaming.parse(name, utc))
        }
    }

    @Test
    fun `rotation counts only this installation and the unowned ones`() {
        fun owned(id: String?, at: Long) = entry(
            AutoBackupNaming.name(at * 1000, false, utc, installId = id.orEmpty(), device = "Phone"),
            at,
        )
        val mine = (1L..4L).map { owned("aaaaaaaa", it) }
        val theirs = (5L..9L).map { owned("bbbbbbbb", it) }
        val legacy = listOf(owned(null, 0L))
        val doomed = AutoBackupNaming.rotation(mine + theirs + legacy, keep = 2, installId = "aaaaaaaa")
        // Oldest of mine plus the unowned one go; the other phone's five stay,
        // though every one of them is newer and there are more than two.
        assertEquals((legacy + mine.take(2)).map { it.name }, doomed.map { it.name })
        assertTrue(doomed.none { it in theirs })
    }

    @Test
    fun `the other phone rotates its own the same way`() {
        fun owned(id: String, at: Long) =
            entry(AutoBackupNaming.name(at * 1000, false, utc, installId = id), at)
        val all = (1L..3L).map { owned("aaaaaaaa", it) } + (4L..6L).map { owned("bbbbbbbb", it) }
        val doomed = AutoBackupNaming.rotation(all, keep = 1, installId = "bbbbbbbb")
        assertEquals(all.subList(3, 5).map { it.name }, doomed.map { it.name })
    }
}
