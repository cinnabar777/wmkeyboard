package com.wasimaster.wmkeyboard.core.tools.offlinegif

import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import com.wasimaster.wmkeyboard.core.tools.GifSources
import com.wasimaster.wmkeyboard.core.tools.MediaCategory
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * GIF packs kept on the device, for a GIF panel that works with no network at
 * all ([GifSource.OFFLINE]).
 *
 * The format is CleverKeys' offline GIF pack, so the packs published at
 * [PACKS_PAGE] import as they are: a zip holding `manifest.json`
 * (`pack_id`, `name`, `gif_count`), `pack.db` (SQLite: `gifs` with
 * `gif_id`, `width`, `height`, `search_text`, plus `categories` and
 * `gif_category_map`), and the images under `full/<id / 1000>/<id>.webp`
 * (animated) and `thumbs/…` (80 px stills).
 *
 * Only a GIF with an animated file is kept. The thumbnail-only packs carry
 * nothing that can be sent, only an 80 px still of it, so a pack that is all
 * thumbnails is refused ([ImportResult.PreviewsOnly]) rather than installed
 * as a grid that does nothing when tapped. The stills are not kept either: the
 * grid previews with the animated file, as it does for the user's stickers.
 *
 * Each pack lives in `filesDir/gif_packs/<pack_id>/` with its `pack.db`
 * trimmed to the GIFs it really has, so a search never offers a file that is
 * not there. Process-wide: the keyboard searches and the settings page
 * imports, and they are one process.
 */
object OfflineGifPacks {

    /** CleverKeys' release of offline GIF packs. */
    const val PACKS_PAGE = "https://github.com/tribixbite/CleverKeys/releases/tag/CleverKeys-GIF"

    private const val DIR_NAME = "gif_packs"
    private const val MANIFEST = "manifest.json"
    private const val DATABASE = "pack.db"
    private const val ITEM_PREFIX = "offline:"
    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
    private val PACK_ID = Regex("[A-Za-z0-9._-]{1,64}")
    private val FULL_ENTRY = Regex("""full/(?:\d+/)?(\d+)\.webp""")

    private val json = Json { ignoreUnknownKeys = true }

    data class Pack(val id: String, val name: String, val gifCount: Int, val sizeBytes: Long)

    sealed interface ImportResult {
        data class Imported(val pack: Pack) : ImportResult

        /** A thumbnail pack: stills to look at, nothing to send. */
        data class PreviewsOnly(val name: String) : ImportResult
        data object NoSpace : ImportResult
        data object NotAPack : ImportResult
    }

    @Volatile
    private var root: File? = null

    @Volatile
    private var cached: List<Pack>? = null

    /** Open read-only handles, one per pack, kept for the next search. */
    private val databases = HashMap<String, SQLiteDatabase>()

    /**
     * Points the store at the app's files. Idempotent; the keyboard and the
     * settings screen both call it, whichever comes first.
     */
    fun attach(filesDir: File) {
        val next = File(filesDir, DIR_NAME)
        if (next == root) return
        root = next
        cached = null
        synchronized(databases) {
            databases.values.forEach { it.close() }
            databases.clear()
        }
    }

    /** Whether any pack is installed, for the GIF panel's source list. */
    val hasPacks: Boolean get() = packs().isNotEmpty()

    fun packs(): List<Pack> {
        cached?.let { return it }
        val dir = root ?: return emptyList()
        val found = dir.listFiles { file -> file.isDirectory && PACK_ID.matches(file.name) }
            .orEmpty()
            .mapNotNull { readPack(it) }
            .sortedBy { it.name.lowercase() }
        cached = found
        return found
    }

    private fun readPack(dir: File): Pack? {
        val db = File(dir, DATABASE)
        val manifest = runCatching { json.parseToJsonElement(File(dir, MANIFEST).readText()) as JsonObject }
            .getOrNull() ?: return null
        if (!db.isFile) return null
        val name = manifest["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: dir.name
        val count = manifest["gif_count"]?.jsonPrimitive?.intOrNull ?: 0
        val size = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return Pack(dir.name, name, count, size)
    }

    /**
     * Whether [zip] is a GIF pack at all — its manifest names a pack and a
     * database sits beside it — so an importer that sees many kinds of zip can
     * ask before handing it over.
     */
    fun isPack(zip: ZipFile): Boolean = zip.getEntry(MANIFEST) != null && zip.getEntry(DATABASE) != null &&
        runCatching { manifestOf(zip)["pack_id"] }.getOrNull() != null

    private fun manifestOf(zip: ZipFile): JsonObject =
        zip.getInputStream(zip.getEntry(MANIFEST)).use { input ->
            json.parseToJsonElement(input.readBytes().decodeToString()) as JsonObject
        }

    /**
     * Installs the pack in [zipFile], replacing one with the same `pack_id`.
     * Nothing is visible until the whole pack is in place: it is unpacked
     * beside the others and renamed in at the end.
     */
    @Synchronized
    fun import(filesDir: File, zipFile: File): ImportResult {
        attach(filesDir)
        val base = root!!.apply { mkdirs() }
        ZipFile(zipFile).use { zip ->
            if (!isPack(zip)) return ImportResult.NotAPack
            val manifest = manifestOf(zip)
            val id = manifest["pack_id"]?.jsonPrimitive?.content?.takeIf { PACK_ID.matches(it) }
                ?: return ImportResult.NotAPack
            val name = manifest["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: id
            val animated = zip.entries().asSequence().filter { !it.isDirectory && FULL_ENTRY.matches(it.name) }.toList()
            if (animated.isEmpty()) return ImportResult.PreviewsOnly(name)
            val needed = animated.sumOf { it.size.coerceAtLeast(0) } + zip.getEntry(DATABASE).size
            if (StatFs(base.path).availableBytes < needed + SPACE_MARGIN_BYTES) return ImportResult.NoSpace

            val staging = File(base, ".$id.part").apply { deleteRecursively() }
            try {
                staging.mkdirs()
                copyEntry(zip, MANIFEST, File(staging, MANIFEST))
                copyEntry(zip, DATABASE, File(staging, DATABASE))
                val ids = HashSet<Long>(animated.size)
                for (entry in animated) {
                    val gifId = FULL_ENTRY.matchEntire(entry.name)!!.groupValues[1].toLong()
                    // Our own path from the id, never the entry's name: a
                    // crafted `full/../../x.webp` cannot match FULL_ENTRY, but
                    // the file lands where fileFor() will look regardless.
                    zip.getInputStream(entry).use { input ->
                        fullFile(staging, gifId).apply { parentFile?.mkdirs() }.outputStream().use { input.copyTo(it) }
                    }
                    ids += gifId
                }
                val kept = trimTo(File(staging, DATABASE), ids)
                if (kept == 0) return ImportResult.PreviewsOnly(name)
                // The count the panel shows is what is really here.
                File(staging, MANIFEST).writeText(
                    JsonObject(manifest + ("gif_count" to kotlinx.serialization.json.JsonPrimitive(kept))).toString(),
                )
                close(id)
                val target = File(base, id)
                target.deleteRecursively()
                if (!staging.renameTo(target)) throw IOException("could not move the pack into place")
                cached = null
                return ImportResult.Imported(readPack(target)!!)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun copyEntry(zip: ZipFile, name: String, target: File) {
        zip.getInputStream(zip.getEntry(name)).use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    /** Drops every row in [db] that has no animated file, and returns how many remain. */
    private fun trimTo(db: File, ids: Set<Long>): Int {
        val database = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val missing = ArrayList<Long>()
            database.rawQuery("SELECT gif_id FROM gifs", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val gifId = cursor.getLong(0)
                    if (gifId !in ids) missing += gifId
                }
            }
            database.beginTransaction()
            try {
                for (gifId in missing) {
                    val args = arrayOf<Any>(gifId)
                    database.execSQL("DELETE FROM gifs WHERE gif_id = ?", args)
                    runCatching { database.execSQL("DELETE FROM gif_category_map WHERE gif_id = ?", args) }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            return database.rawQuery("SELECT COUNT(*) FROM gifs", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } finally {
            database.close()
        }
    }

    @Synchronized
    fun delete(id: String) {
        val base = root ?: return
        if (!PACK_ID.matches(id)) return
        close(id)
        File(base, id).deleteRecursively()
        cached = null
    }

    private fun close(id: String) {
        synchronized(databases) { databases.remove(id)?.close() }
    }

    private fun database(id: String): SQLiteDatabase? {
        val base = root ?: return null
        synchronized(databases) {
            databases[id]?.let { return it }
            val file = File(File(base, id), DATABASE)
            if (!file.isFile) return null
            return runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            }.getOrNull()?.also { databases[id] = it }
        }
    }

    private fun fullFile(packDir: File, gifId: Long): File =
        File(File(File(packDir, "full"), "%03d".format(gifId / 1000)), "%06d.webp".format(gifId))

    /**
     * Up to [limit] GIFs for [query], taken from the packs in turn so one large
     * pack cannot fill the grid on its own; a blank query lists each pack in
     * its own order. Every word of the query has to appear in a GIF's search
     * text, or the whole query has to name one of its categories, which is how
     * a category chip (a search for its name) finds its GIFs.
     */
    fun search(query: String, limit: Int): List<GifItem> {
        val base = root ?: return emptyList()
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val perPack = ArrayList<List<GifItem>>()
        for (pack in packs()) {
            val results = ArrayList<GifItem>()
            perPack += results
            val db = database(pack.id) ?: continue
            val sql = StringBuilder("SELECT gif_id, width, height, search_text FROM gifs")
            val args = ArrayList<String>()
            if (words.isNotEmpty()) {
                sql.append(" WHERE (")
                words.forEachIndexed { index, word ->
                    if (index > 0) sql.append(" AND ")
                    sql.append("lower(search_text) LIKE ? ESCAPE '\\'")
                    args += "%" + escapeLike(word) + "%"
                }
                sql.append(") OR gif_id IN (SELECT m.gif_id FROM gif_category_map m")
                sql.append(" JOIN categories c ON c.category_id = m.category_id WHERE lower(c.name) = ?)")
                args += words.joinToString(" ")
            }
            sql.append(" ORDER BY rowid LIMIT ").append(limit)
            runCatching {
                db.rawQuery(sql.toString(), args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val gifId = cursor.getLong(0)
                        val width = cursor.getInt(1)
                        val height = cursor.getInt(2)
                        val url = "file://" + fullFile(File(base, pack.id), gifId).absolutePath
                        results += GifItem(
                            id = "$ITEM_PREFIX${pack.id}:$gifId",
                            previewUrl = url,
                            fullUrl = url,
                            mime = "image/webp",
                            aspectRatio = if (width > 0 && height > 0) width.toFloat() / height else 1f,
                            source = GifSource.OFFLINE,
                            title = cursor.getString(3).orEmpty().split(' ').take(TITLE_WORDS).joinToString(" "),
                        )
                    }
                }
            }
        }
        return GifSources.interleave(perPack).take(limit)
    }

    /** Categories that hold at least one GIF, across every pack, in the packs' own order. */
    fun categories(): List<MediaCategory> {
        val seen = LinkedHashMap<String, MediaCategory>()
        for (pack in packs()) {
            val db = database(pack.id) ?: continue
            runCatching {
                db.rawQuery(
                    "SELECT c.name FROM categories c WHERE EXISTS " +
                        "(SELECT 1 FROM gif_category_map m WHERE m.category_id = c.category_id) " +
                        "ORDER BY c.sort_order",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val label = cursor.getString(0) ?: continue
                        seen.getOrPut(label.lowercase()) { MediaCategory(term = label.lowercase(), label = label) }
                    }
                }
            }
        }
        return seen.values.toList()
    }

    private fun escapeLike(word: String): String =
        word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private const val TITLE_WORDS = 4
}
