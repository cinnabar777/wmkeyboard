package com.wasimaster.wmkeyboard.core.vocab

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the notes out of an Anki deck (`.apkg`) or collection (`.colpkg`).
 *
 * Both are zips around an SQLite collection, in one of three spellings:
 * `collection.anki21b` (Anki 2.1.50+, zstd-compressed, schema 18),
 * `collection.anki21` (2.1.x "support older versions" exports) and
 * `collection.anki2` (2.0). A modern export also carries an `anki2` that
 * holds one placeholder note telling old clients to update, so the newest
 * spelling present wins and the placeholder is never read when a real
 * collection is there. Media entries are skipped without being written.
 *
 * Field names come from the `fields` table on schema 18 and from the `col`
 * row's `models` JSON before it. Anki declares its name columns with a
 * `unicase` collation that only Anki registers; every query here that
 * touches such a table says `NOT INDEXED` so SQLite never needs it.
 */
object AnkiPackage {

    /** Inflated collection size past which the deck is refused. */
    const val MAX_COLLECTION_BYTES = 256L * 1024 * 1024

    private val ENTRY_RANK = mapOf("collection.anki21b" to 3, "collection.anki21" to 2, "collection.anki2" to 1)

    private val json = Json { ignoreUnknownKeys = true }

    class TooLargeException : IOException("Anki collection too large")

    data class Deck(val name: String?, val notes: List<FlashcardImport.AnkiNote>, val fieldNames: Map<Long, List<String>>)

    /** Whether [head] is a zip's local-file header, as every Anki export starts. */
    fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2].toInt() == 3 && head[3].toInt() == 4

    /**
     * Unpacks the best collection in [stream] to [scratch] and reads it.
     * Returns null when the zip holds no Anki collection. [scratch] is
     * deleted before returning.
     */
    fun read(stream: InputStream, scratch: File): Deck? {
        try {
            val rank = extractCollection(stream, scratch) ?: return null
            return readDatabase(scratch, rank)
        } finally {
            scratch.delete()
            File(scratch.path + "-journal").delete()
            File(scratch.path + "-wal").delete()
            File(scratch.path + "-shm").delete()
        }
    }

    /** Writes the newest collection spelling to [target]; returns its rank, or null for none. */
    private fun extractCollection(stream: InputStream, target: File): Int? {
        var best = 0
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val rank = ENTRY_RANK[entry.name.substringAfterLast('/')] ?: continue
                if (rank <= best) continue
                target.outputStream().use { out ->
                    if (rank == 3) {
                        try {
                            Zstd.decompress(zip, out, MAX_COLLECTION_BYTES)
                        } catch (_: Zstd.OutputLimitException) {
                            throw TooLargeException()
                        }
                    } else {
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_COLLECTION_BYTES) throw TooLargeException()
                            out.write(buffer, 0, read)
                        }
                    }
                }
                best = rank
                // Anki writes the collection before the media, so once the
                // newest spelling is out there is nothing left worth reading.
                if (rank >= 2) break
            }
        }
        return best.takeIf { it > 0 }
    }

    private fun readDatabase(file: File, rank: Int): Deck? {
        val db = SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        return db.use {
            val tables = HashSet<String>()
            it.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            if ("notes" !in tables) return null
            val notes = ArrayList<FlashcardImport.AnkiNote>()
            it.rawQuery("SELECT mid, flds FROM notes ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    notes += FlashcardImport.AnkiNote(c.getLong(0), c.getString(1).orEmpty().split('\u001f'))
                }
            }
            // The placeholder an old-format reader is shown: one note asking
            // for a newer Anki. Only an `anki2` that is all there was gets here.
            if (rank == 1 && notes.size == 1 && notes[0].fields.firstOrNull().orEmpty().contains("update to the latest Anki")) {
                return null
            }
            val fieldNames = if ("fields" in tables) fieldsTable(it) else legacyModels(it)
            Deck(deckName(it, tables), notes, fieldNames)
        }
    }

    private fun fieldsTable(db: SQLiteDatabase): Map<Long, List<String>> {
        val byType = HashMap<Long, MutableList<Pair<Int, String>>>()
        db.rawQuery("SELECT ntid, ord, name FROM fields NOT INDEXED", null).use { c ->
            while (c.moveToNext()) {
                byType.getOrPut(c.getLong(0)) { ArrayList() } += c.getInt(1) to c.getString(2).orEmpty()
            }
        }
        return byType.mapValues { (_, fields) -> fields.sortedBy { it.first }.map { it.second } }
    }

    private fun legacyModels(db: SQLiteDatabase): Map<Long, List<String>> {
        val text = db.rawQuery("SELECT models FROM col LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return emptyMap()
        val models = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        val out = HashMap<Long, List<String>>()
        for ((id, model) in models) {
            val flds = runCatching { (model as JsonObject)["flds"]?.jsonArray }.getOrNull() ?: continue
            val names = flds.mapNotNull { f ->
                val o = f as? JsonObject ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                (o["ord"]?.jsonPrimitive?.intOrNull ?: 0) to name
            }.sortedBy { it.first }.map { it.second }
            id.toLongOrNull()?.let { out[it] = names }
        }
        return out
    }

    /** The deck most cards sit in, by its last path component; null when that is "Default". */
    private fun deckName(db: SQLiteDatabase, tables: Set<String>): String? {
        if ("cards" !in tables) return null
        val did = db.rawQuery("SELECT did FROM cards GROUP BY did ORDER BY COUNT(*) DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return null
        val full = if ("decks" in tables) {
            db.rawQuery("SELECT name FROM decks NOT INDEXED WHERE id = ?", arrayOf(did.toString())).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } else {
            val text = db.rawQuery("SELECT decks FROM col LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            text?.let {
                runCatching {
                    json.parseToJsonElement(it).jsonObject[did.toString()]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
        } ?: return null
        val name = full.replace("\u001f", "::").split("::").map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        return name.takeIf { !it.equals("Default", ignoreCase = true) }
    }
}
