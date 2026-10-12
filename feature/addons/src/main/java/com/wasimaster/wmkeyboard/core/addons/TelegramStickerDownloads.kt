package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackAdoption
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.telegram.TelegramSticker
import com.wasimaster.wmkeyboard.core.stickers.telegram.TelegramStickerSet
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches Telegram sticker sets through the Bot API.
 *
 * Telegram keeps its stickers behind its own protocol, and the one door that
 * opens without signing in to a Telegram account is a bot's: `getStickerSet`
 * lists a set, `getFile` turns one sticker into a path, and the file is read
 * from under the bot's file root. A bot is free and takes a minute to make
 * with @BotFather, and its token is the user's own, so the requests are theirs
 * as well. The token sits in every address; the network log shows `{token}`
 * in its place.
 *
 * Downloaded stickers are kept under `cacheDir/telegram_stickers/<set>/`,
 * named by the file's unique id, so that the preview and the import that
 * follows it download each file once. Trimmed by [trimCache].
 */
object TelegramStickerDownloads {

    sealed interface SetOutcome {
        data class Ok(val set: TelegramStickerSet) : SetOutcome

        /** Telegram has no set by that name. */
        data object NotFound : SetOutcome

        /** Telegram refused the token: mistyped, or revoked in @BotFather. */
        data object BadToken : SetOutcome

        data class Failed(val error: Throwable) : SetOutcome
    }

    /** What [import] is doing, for the progress line. */
    enum class Phase { DOWNLOADING, ADDING }

    private const val CACHE_DIR = "telegram_stickers"
    private const val MAX_STICKER_BYTES = 3L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    private const val PARALLEL_DOWNLOADS = 4
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_NOT_FOUND = 404

    private fun base(): String = ServiceEndpoints.base(ServiceEndpoint.TELEGRAM_BOT_API)

    /** The address a link's confirm names: where the requests go. Carries no token. */
    fun hostUrl(): String = base()

    private fun setUrl(token: String, name: String): String =
        "${base()}/bot$token/getStickerSet?name=${ToolHttp.encode(name)}"

    private fun fileInfoUrl(token: String, fileId: String): String =
        "${base()}/bot$token/getFile?file_id=${ToolHttp.encode(fileId)}"

    private fun fileUrl(token: String, path: String): String = "${base()}/file/bot$token/$path"

    /** Asks Telegram for the set called [name]. Blocking. */
    fun stickerSet(token: String, name: String): SetOutcome {
        val key = TelegramStickerSet.tokenOrNull(token) ?: return SetOutcome.BadToken
        if (!TelegramStickerSet.isSetName(name)) return SetOutcome.NotFound
        return try {
            val body = ToolHttp.get(
                setUrl(key, name),
                source = NetSource.TELEGRAM_STICKERS,
                route = "/bot{token}/getStickerSet",
            )
            TelegramStickerSet.parse(body)?.let { SetOutcome.Ok(it) } ?: SetOutcome.NotFound
        } catch (e: ToolHttpException) {
            // The Bot API answers a set it does not have with 400
            // (STICKERSET_INVALID), a wrong token with 401, and a token that
            // is not even shaped like one with 404.
            when (e.status) {
                HTTP_BAD_REQUEST -> SetOutcome.NotFound
                HTTP_UNAUTHORIZED, HTTP_NOT_FOUND -> SetOutcome.BadToken
                else -> SetOutcome.Failed(e)
            }
        } catch (e: IOException) {
            SetOutcome.Failed(e)
        }
    }

    /** One sticker of [setName], from the cache or the network. Blocking. */
    @Throws(IOException::class)
    fun sticker(token: String, setName: String, sticker: TelegramSticker, cacheDir: File): File? =
        file(token, setName, sticker.fileId, sticker.uniqueId, cacheDir)

    /** The small still Telegram made of [sticker], or null when it made none. Blocking. */
    @Throws(IOException::class)
    fun thumbnail(token: String, setName: String, sticker: TelegramSticker, cacheDir: File): File? {
        if (sticker.thumbId.isBlank() || sticker.thumbUniqueId.isBlank()) return null
        return file(token, setName, sticker.thumbId, "thumb_${sticker.thumbUniqueId}", cacheDir)
    }

    /**
     * A file by [fileId], cached under [cacheName]. Throws [IOException] when
     * the network is what failed, and returns null when Telegram had no path
     * for it.
     */
    @Throws(IOException::class)
    private fun file(token: String, setName: String, fileId: String, cacheName: String, cacheDir: File): File? {
        val key = TelegramStickerSet.tokenOrNull(token) ?: return null
        val dir = setDir(cacheDir, setName) ?: return null
        val cached = File(dir, cacheName)
        if (cached.isFile && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis())
            return cached
        }
        val info = ToolHttp.get(
            fileInfoUrl(key, fileId),
            source = NetSource.TELEGRAM_STICKERS,
            route = "/bot{token}/getFile",
        )
        val path = TelegramStickerSet.filePath(info) ?: return null
        // Named per request: the preview grid and an import may want the same
        // file at the same moment.
        val part = File(dir, "$cacheName.${System.nanoTime()}.part")
        return try {
            ToolHttp.download(
                fileUrl(key, path), part, maxBytes = MAX_STICKER_BYTES,
                source = NetSource.TELEGRAM_STICKERS, route = "/file/bot{token}/{file}",
            )
            if (part.renameTo(cached) || cached.isFile) cached else null
        } finally {
            part.delete()
        }
    }

    /**
     * Downloads every sticker of [set] and registers them with [store] as one
     * pack. Animated and video stickers are turned into animated WebP on the
     * way in, which is the slow part.
     *
     * Throws [IOException] when not one sticker could be fetched and the
     * network is why.
     */
    @Throws(IOException::class)
    suspend fun import(
        store: StickerPackStore,
        token: String,
        set: TelegramStickerSet,
        defaultName: String,
        cacheDir: File,
        onProgress: (phase: Phase, done: Int, total: Int) -> Unit,
    ): StickerImportResult = withContext(Dispatchers.IO) {
        val wanted = set.stickers
        val fetching = minOf(wanted.size, StickerPackStore.MAX_STICKERS_PER_PACK)
        val files = download(token, set.name, wanted, cacheDir) { done ->
            onProgress(Phase.DOWNLOADING, done, fetching)
        }
        ensureActive()
        val incoming = wanted.mapIndexed { index, sticker ->
            StickerPackAdoption.Incoming(
                label = "${sticker.emoji} #${index + 1}".trim(),
                emojis = listOfNotNull(sticker.emoji.takeIf { it.isNotBlank() }),
                read = { files[sticker.uniqueId]?.takeIf { it.isFile }?.readBytes() },
            )
        }
        StickerPackAdoption.adopt(
            store = store,
            name = set.title.ifBlank { defaultName },
            incoming = incoming,
            source = StickerPack.telegramSource(set.name),
            onProgress = { done, total -> onProgress(Phase.ADDING, done, total) },
        )
    }

    private suspend fun download(
        token: String,
        setName: String,
        wanted: List<TelegramSticker>,
        cacheDir: File,
        onDone: (Int) -> Unit,
    ): Map<String, File> = coroutineScope {
        val gate = Semaphore(PARALLEL_DOWNLOADS)
        val done = AtomicInteger(0)
        val networkFailure = AtomicReference<IOException?>(null)
        onDone(0)
        val fetched = wanted.take(StickerPackStore.MAX_STICKERS_PER_PACK).map { sticker ->
            async {
                gate.withPermit {
                    ensureActive()
                    val file = try {
                        sticker(token, setName, sticker, cacheDir)
                    } catch (e: IOException) {
                        networkFailure.set(e)
                        null
                    }
                    onDone(done.incrementAndGet())
                    file?.let { sticker.uniqueId to it }
                }
            }
        }.awaitAll().filterNotNull().toMap()
        if (fetched.isEmpty()) networkFailure.get()?.let { throw it }
        fetched
    }

    private fun setDir(cacheDir: File, setName: String): File? {
        if (!TelegramStickerSet.isSetName(setName)) return null
        return File(File(cacheDir, CACHE_DIR), setName.lowercase()).apply { mkdirs() }
    }

    /** Deletes the least recently used cached files once the cache passes its cap. */
    fun trimCache(cacheDir: File) {
        val files = File(cacheDir, CACHE_DIR).walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }
}
