package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The GIF panel's two lists of its own (#597): the favourites the user starred
 * with a long press, and the GIFs they sent last.
 *
 * Both hold the provider's item as it came, so a favourite is the same GIF
 * the provider would send. A favourite can also carry a copy of its file on
 * the phone, when the tool's setting asks for one; the lists then hand the
 * panel that file instead of the provider's address, and the GIF shows and
 * sends with no connection. A recent GIF that is also a favourite borrows the
 * favourite's copy, so the two lists never keep two files for one GIF.
 *
 * One process-wide instance, for the same reason as the sticker pack store:
 * the keyboard writes it and the settings app clears its copies, in one
 * process. Every method reads or writes a small file, so call them off the
 * main thread where it matters.
 */
class GifCollections internal constructor(private val dir: File) {

    /** A stored GIF: the provider's item, and the name of its copy on the phone, if any. */
    private data class Entry(val item: GifItem, val local: String? = null)

    private val manifest = File(dir, MANIFEST)
    private val filesDir = File(dir, FILES)

    private var loaded = false
    private var favourites = ArrayList<Entry>()
    private var recents = ArrayList<Entry>()

    /** The favourites, newest first, each pointing at its copy on the phone when it has one. */
    @Synchronized
    fun favourites(): List<GifItem> {
        load()
        return favourites.map(::display)
    }

    /** The GIFs sent last, newest first. */
    @Synchronized
    fun recents(): List<GifItem> {
        load()
        return recents.map { entry -> favourites.firstOrNull { it.item.id == entry.item.id }?.let(::display) ?: entry.item }
    }

    @Synchronized
    fun favouriteIds(): Set<String> {
        load()
        return favourites.mapTo(HashSet()) { it.item.id }
    }

    /**
     * Adds [item] to the favourites, or takes it out if it is there already.
     * True when it is a favourite afterwards.
     */
    @Synchronized
    fun toggleFavourite(item: GifItem): Boolean {
        load()
        val index = favourites.indexOfFirst { it.item.id == item.id }
        if (index >= 0) {
            favourites.removeAt(index).local?.let { File(filesDir, it).delete() }
            save()
            return false
        }
        favourites.add(0, Entry(original(item)))
        while (favourites.size > MAX_FAVOURITES) {
            favourites.removeAt(favourites.lastIndex).local?.let { File(filesDir, it).delete() }
        }
        save()
        return true
    }

    /** Puts [item] at the front of the recent GIFs. */
    @Synchronized
    fun recordSent(item: GifItem) {
        load()
        val stored = original(item)
        recents.removeAll { it.item.id == stored.id }
        recents.add(0, Entry(stored))
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        save()
    }

    @Synchronized
    fun clearRecents() {
        load()
        if (recents.isEmpty()) return
        recents.clear()
        save()
    }

    /** Favourites that a provider still serves and that have no copy on the phone yet. */
    @Synchronized
    fun missingCopies(): List<GifItem> {
        load()
        return favourites.filter { it.local == null && !it.item.source.onDevice }.map { it.item }
    }

    /**
     * Files [file] as the copy of favourite [id]. Copied, not moved: [file] is
     * usually the media cache's, which a send of the same GIF reads again.
     * False when [id] is no longer a favourite or the copy failed.
     */
    @Synchronized
    fun keepCopy(id: String, file: File): Boolean {
        load()
        val index = favourites.indexOfFirst { it.item.id == id }
        if (index < 0) return false
        val entry = favourites[index]
        val name = fileName(entry.item)
        val target = File(filesDir, name)
        val ok = runCatching {
            filesDir.mkdirs()
            file.copyTo(target, overwrite = true)
            true
        }.getOrDefault(false)
        if (!ok) {
            target.delete()
            return false
        }
        favourites[index] = entry.copy(local = name)
        save()
        return true
    }

    /** Deletes every copy kept on the phone; the favourites stay, pointing at their providers again. */
    @Synchronized
    fun dropCopies() {
        load()
        favourites = favourites.mapTo(ArrayList()) { it.copy(local = null) }
        filesDir.deleteRecursively()
        save()
    }

    /** Bytes the copies on the phone take. */
    @Synchronized
    fun copiesSize(): Long = filesDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** The item the panel shows: the copy on the phone when there is one. */
    private fun display(entry: Entry): GifItem {
        val name = entry.local ?: return entry.item
        val file = File(filesDir, name)
        if (!file.isFile) return entry.item
        val url = "file://" + file.absolutePath
        return entry.item.copy(previewUrl = url, fullUrl = url)
    }

    /**
     * The provider's form of [item]. A favourite shown from its copy on the
     * phone comes back to the panel with a file address; what gets stored is
     * always the one the provider gave.
     */
    private fun original(item: GifItem): GifItem =
        favourites.firstOrNull { it.item.id == item.id }?.item ?: item

    private fun fileName(item: GifItem): String {
        val safe = item.id.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(MAX_NAME)
        val extension = when (item.mime) {
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/png" -> "png"
            else -> "bin"
        }
        return "$safe.$extension"
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = runCatching { manifest.readText() }.getOrNull() ?: return
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        favourites = readList(root["favourites"])
        recents = readList(root["recent"])
    }

    private fun readList(element: kotlinx.serialization.json.JsonElement?): ArrayList<Entry> {
        val array = runCatching { element?.jsonArray }.getOrNull() ?: return ArrayList()
        val out = ArrayList<Entry>(array.size)
        for (value in array) {
            val obj = runCatching { value.jsonObject }.getOrNull() ?: continue
            fun text(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
            val id = text("id") ?: continue
            val fullUrl = text("fullUrl") ?: continue
            val source = text("source")?.let { name -> GifSource.entries.firstOrNull { it.name == name } } ?: continue
            out += Entry(
                item = GifItem(
                    id = id,
                    previewUrl = text("previewUrl") ?: fullUrl,
                    fullUrl = fullUrl,
                    mime = text("mime") ?: "image/gif",
                    aspectRatio = obj["aspectRatio"]?.jsonPrimitive?.floatOrNull ?: 1f,
                    source = source,
                    title = text("title").orEmpty(),
                ),
                local = text("local"),
            )
        }
        return out
    }

    private fun save() {
        val root = JsonObject(
            mapOf(
                "favourites" to JsonArray(favourites.map(::write)),
                "recent" to JsonArray(recents.map(::write)),
            ),
        )
        runCatching {
            dir.mkdirs()
            val temp = File(dir, "$MANIFEST.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(manifest)) {
                manifest.writeText(root.toString())
                temp.delete()
            }
        }
    }

    private fun write(entry: Entry): JsonObject {
        val item = entry.item
        val fields = linkedMapOf(
            "id" to JsonPrimitive(item.id),
            "previewUrl" to JsonPrimitive(item.previewUrl),
            "fullUrl" to JsonPrimitive(item.fullUrl),
            "mime" to JsonPrimitive(item.mime),
            "aspectRatio" to JsonPrimitive(item.aspectRatio),
            "source" to JsonPrimitive(item.source.name),
            "title" to JsonPrimitive(item.title),
        )
        entry.local?.let { fields["local"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    companion object {
        /** Under filesDir, not the cache: the user chose these, so the system must not reclaim them. */
        private const val DIR = "gif_collections"
        private const val MANIFEST = "collections.json"
        private const val FILES = "files"
        private const val MAX_NAME = 80

        /** Enough to never feel like a limit, few enough that the list stays one quick read. */
        const val MAX_FAVOURITES = 500

        /** About the emoji panel's history: a few screens of the GIFs in use this week. */
        const val MAX_RECENTS = 48

        @Volatile
        private var instance: GifCollections? = null

        fun get(context: Context): GifCollections =
            instance ?: synchronized(this) {
                instance ?: GifCollections(File(context.applicationContext.filesDir, DIR)).also { instance = it }
            }
    }
}
