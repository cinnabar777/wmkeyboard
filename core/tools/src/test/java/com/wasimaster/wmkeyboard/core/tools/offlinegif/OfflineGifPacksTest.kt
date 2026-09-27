package com.wasimaster.wmkeyboard.core.tools.offlinegif

import android.database.sqlite.SQLiteDatabase
import com.wasimaster.wmkeyboard.core.tools.GifSource
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowStatFs

/**
 * Importing and searching CleverKeys-format GIF packs.
 *
 * Every fixture is built here from the published format (the manifest's
 * fields, the three tables' columns, the `full/<id / 1000>/<id>.webp` layout),
 * never taken from a real pack.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineGifPacksTest {

    private lateinit var filesDir: File
    private lateinit var work: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        filesDir = File(context.filesDir, "giftest").apply { deleteRecursively(); mkdirs() }
        work = File(context.cacheDir, "giftest").apply { deleteRecursively(); mkdirs() }
        // Robolectric's StatFs reports nothing free unless told otherwise.
        ShadowStatFs.registerStats(File(filesDir, "gif_packs").apply { mkdirs() }, 1_000_000, 1_000_000, 1_000_000)
        OfflineGifPacks.attach(filesDir)
    }

    @After
    fun tearDown() {
        OfflineGifPacks.packs().forEach { OfflineGifPacks.delete(it.id) }
    }

    private data class Row(val id: Long, val text: String, val category: String?, val animated: Boolean)

    private fun pack(id: String, rows: List<Row>, name: String = "Test $id"): File {
        val db = File(work, "$id.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(db, null).use { database ->
            database.execSQL("CREATE TABLE categories (category_id INTEGER PRIMARY KEY, name TEXT, icon TEXT, sort_order INTEGER)")
            database.execSQL(
                "CREATE TABLE gifs (gif_id INTEGER PRIMARY KEY, width INTEGER, height INTEGER, " +
                    "duration_ms INTEGER, file_size INTEGER, pack_id INTEGER, search_text TEXT, created_at INTEGER)",
            )
            database.execSQL("CREATE TABLE gif_category_map (category_id INTEGER, gif_id INTEGER, PRIMARY KEY (category_id, gif_id))")
            val categories = rows.mapNotNull { it.category }.distinct()
            categories.forEachIndexed { index, category ->
                database.execSQL("INSERT INTO categories VALUES (?, ?, '', ?)", arrayOf<Any>(index + 1, category, index + 1))
            }
            for (row in rows) {
                database.execSQL(
                    "INSERT INTO gifs VALUES (?, 200, 100, 0, 0, 0, ?, 0)",
                    arrayOf<Any>(row.id, row.text),
                )
                row.category?.let {
                    database.execSQL(
                        "INSERT INTO gif_category_map VALUES (?, ?)",
                        arrayOf<Any>(categories.indexOf(it) + 1, row.id),
                    )
                }
            }
        }
        val zip = File(work, "$id.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write("""{"pack_id":"$id","name":"$name","version":1,"gif_count":${rows.size}}""".toByteArray())
            out.putNextEntry(ZipEntry("pack.db"))
            out.write(db.readBytes())
            for (row in rows) {
                val path = "%03d/%06d.webp".format(row.id / 1000, row.id)
                out.putNextEntry(ZipEntry("thumbs/$path"))
                out.write(byteArrayOf(1, 2, 3))
                if (row.animated) {
                    out.putNextEntry(ZipEntry("full/$path"))
                    out.write(byteArrayOf(4, 5, 6))
                }
            }
        }
        return zip
    }

    @Test
    fun `keeps only the GIFs that have an animated file`() {
        val zip = pack(
            "discord-test",
            listOf(
                Row(1001, "cat happy dance", "Happy", animated = true),
                Row(1002, "dog sad rain", "Sad", animated = true),
                Row(1003, "still only", "Sad", animated = false),
            ),
        )
        assertTrue(ZipFile(zip).use { OfflineGifPacks.isPack(it) })
        val result = OfflineGifPacks.import(filesDir, zip)
        assertTrue(result is OfflineGifPacks.ImportResult.Imported)
        assertEquals(2, (result as OfflineGifPacks.ImportResult.Imported).pack.gifCount)
        assertTrue(OfflineGifPacks.hasPacks)
        assertEquals(listOf(1001L, 1002L), OfflineGifPacks.search("", 10).map { it.id.substringAfterLast(':').toLong() })
    }

    @Test
    fun `a thumbnail-only pack is refused and leaves nothing behind`() {
        val zip = pack("thumbs-test", listOf(Row(5, "wave", null, animated = false)))
        val result = OfflineGifPacks.import(filesDir, zip)
        assertTrue(result is OfflineGifPacks.ImportResult.PreviewsOnly)
        assertFalse(OfflineGifPacks.hasPacks)
        assertEquals(listOf("gif_packs"), filesDir.list()!!.toList())
        assertEquals(0, File(filesDir, "gif_packs").list()!!.size)
    }

    @Test
    fun `search needs every word, or a category name`() {
        OfflineGifPacks.import(
            filesDir,
            pack(
                "discord-search",
                listOf(
                    Row(1, "cat happy dance", "Happy", animated = true),
                    Row(2, "cat sleeping", null, animated = true),
                    Row(3, "dog party", "Happy", animated = true),
                ),
            ),
        )
        assertEquals(listOf(1L, 2L), ids(OfflineGifPacks.search("cat", 10)))
        assertEquals(listOf(1L), ids(OfflineGifPacks.search("Cat DANCE", 10)))
        assertEquals(listOf(1L, 3L), ids(OfflineGifPacks.search("happy", 10)))
        assertEquals(emptyList<Long>(), ids(OfflineGifPacks.search("100%", 10)))
        assertEquals(listOf("happy"), OfflineGifPacks.categories().map { it.term })
    }

    @Test
    fun `results point at the animated file and come from the offline source`() {
        OfflineGifPacks.import(filesDir, pack("discord-files", listOf(Row(42, "wave", null, animated = true))))
        val item = OfflineGifPacks.search("wave", 10).single()
        assertEquals(GifSource.OFFLINE, item.source)
        assertEquals("image/webp", item.mime)
        assertEquals(2f, item.aspectRatio)
        val file = File(java.net.URI(item.fullUrl))
        assertTrue(file.isFile)
        assertEquals(listOf<Byte>(4, 5, 6), file.readBytes().toList())
    }

    @Test
    fun `importing again replaces the pack, and delete removes it`() {
        OfflineGifPacks.import(filesDir, pack("discord-again", listOf(Row(1, "one", null, animated = true))))
        OfflineGifPacks.import(
            filesDir,
            pack("discord-again", listOf(Row(1, "one", null, true), Row(2, "two", null, true)), name = "Renamed"),
        )
        val packs = OfflineGifPacks.packs()
        assertEquals(1, packs.size)
        assertEquals("Renamed", packs.single().name)
        assertEquals(2, packs.single().gifCount)
        OfflineGifPacks.delete("discord-again")
        assertFalse(OfflineGifPacks.hasPacks)
        assertEquals(emptyList<Any>(), OfflineGifPacks.search("", 10))
    }

    @Test
    fun `a zip without a manifest and database is not a pack`() {
        val zip = File(work, "other.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("data/en/en_full.txt.gz"))
            out.write(byteArrayOf(1))
        }
        assertFalse(ZipFile(zip).use { OfflineGifPacks.isPack(it) })
        assertEquals(OfflineGifPacks.ImportResult.NotAPack, OfflineGifPacks.import(filesDir, zip))
    }

    private fun ids(items: List<com.wasimaster.wmkeyboard.core.tools.GifItem>) =
        items.map { it.id.substringAfterLast(':').toLong() }
}
