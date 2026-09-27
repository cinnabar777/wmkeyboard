package com.wasimaster.wmkeyboard.core.ocr

import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Tesseract language data on disk, and its downloads.
 *
 * Packs live in `filesDir/ocr/tessdata/<pack>.traineddata`, which is the
 * directory handed to Tesseract as its data path. A download goes to a `.part`
 * file and is renamed into place only when complete, so a pack that exists is
 * whole. Packs are a few megabytes, so an interrupted one starts again rather
 * than resuming.
 *
 * Process-wide like the Whisper downloader: the keyboard panel and the
 * settings page both collect [states], and a download started in one shows in
 * the other.
 */
object OcrPacks {

    sealed interface Status {
        data object NotDownloaded : Status

        /** [total] is the expected size, from the server or the pack table. */
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /**
         * [messageRes] is the text to show. [messageArg] is its one format
         * argument, or "" when it takes none.
         */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard model downloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Pack name -> status, for every pack looked at so far. */
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    /** The directory Tesseract reads packs from. */
    fun dataDir(filesDir: File): File = File(filesDir, "ocr/tessdata")

    fun file(filesDir: File, pack: String): File = File(dataDir(filesDir), "$pack.traineddata")

    fun isDownloaded(filesDir: File, pack: String): Boolean = file(filesDir, pack).isFile

    /** Every pack on disk, whether or not an enabled language still uses it. */
    fun downloaded(filesDir: File): List<String> =
        dataDir(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".traineddata") }
            ?.map { it.name.removeSuffix(".traineddata") }
            ?.sorted()
            .orEmpty()

    /** Seeds [states] from disk for [packs]; call when a list of packs appears. */
    fun refresh(filesDir: File, packs: Collection<String>) {
        _states.update { current ->
            current + packs.associateWith { pack ->
                val active = current[pack]
                when {
                    synchronized(jobs) { jobs[pack]?.isActive == true } && active != null -> active
                    isDownloaded(filesDir, pack) -> Status.Downloaded
                    else -> Status.NotDownloaded
                }
            }
        }
    }

    fun start(filesDir: File, pack: String) {
        synchronized(jobs) {
            if (jobs[pack]?.isActive == true) return
            set(pack, Status.Downloading(0, OcrLanguages.sizeOf(pack)))
            jobs[pack] = scope.launch {
                try {
                    download(filesDir, pack)
                    set(pack, Status.Downloaded)
                } catch (e: CancellationException) {
                    set(pack, Status.NotDownloaded)
                    throw e
                } catch (e: FailedException) {
                    set(pack, Status.Failed(e.messageRes, e.messageArg))
                } catch (_: Exception) {
                    set(pack, Status.Failed(CommonR.string.common_error_network))
                } finally {
                    partFile(filesDir, pack).delete()
                }
            }
        }
    }

    /** The pack a `<pack>.traineddata` file named [name] is, or null for any other file. */
    fun packOf(name: String): String? =
        name.removeSuffix(".traineddata").takeIf { name.endsWith(".traineddata") && it in OcrLanguages.allPacks }

    /**
     * Installs [pack] from a copy the user fetched some other way (the same
     * `tessdata_fast` file the download fetches). Nothing can check it before
     * Tesseract opens it; a file Tesseract will not load is caught there and
     * [markBroken], as a bad download would be. Runs on the caller's thread.
     */
    fun install(filesDir: File, pack: String, source: File) {
        cancel(pack)
        val part = partFile(filesDir, pack)
        part.parentFile?.mkdirs()
        try {
            source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val target = file(filesDir, pack)
            target.delete()
            check(part.renameTo(target)) { "could not move the pack into place" }
            set(pack, Status.Downloaded)
        } finally {
            part.delete()
        }
    }

    fun cancel(pack: String) {
        synchronized(jobs) { jobs[pack]?.cancel() }
    }

    fun delete(filesDir: File, pack: String) {
        cancel(pack)
        file(filesDir, pack).delete()
        set(pack, Status.NotDownloaded)
    }

    /**
     * For a pack that is on disk but that Tesseract would not load: deletes
     * it and reports it failed, so the scanner and the settings page offer
     * the download again instead of reading nothing with it.
     */
    fun markBroken(filesDir: File, pack: String) {
        file(filesDir, pack).delete()
        set(pack, Status.Failed(R.string.core_intel_ocr_pack_error_broken))
    }

    private fun set(pack: String, status: Status) {
        _states.update { it + (pack to status) }
    }

    private fun partFile(filesDir: File, pack: String): File =
        File(dataDir(filesDir), "$pack.traineddata.part")

    /** A failure that already knows its message; see [Status.Failed]. */
    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") :
        IOException()

    private suspend fun download(filesDir: File, pack: String) {
        val dir = dataDir(filesDir).apply { mkdirs() }
        val expected = OcrLanguages.sizeOf(pack)
        if (StatFs(dir.path).availableBytes < expected + SPACE_MARGIN_BYTES) {
            val neededMb = ((expected + SPACE_MARGIN_BYTES) / 1e6).roundToInt()
            throw FailedException(R.string.core_intel_ocr_download_error_no_space, neededMb.toString())
        }

        val url = OcrLanguages.downloadUrl(pack)
        val part = partFile(filesDir, pack)
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_OCR, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw FailedException(
                    R.string.core_intel_ocr_download_error_http,
                    connection.responseCode.toString(),
                )
            }
            val length = connection.contentLengthLong
            val total = if (length > 0) length else expected
            var written = 0L
            var lastUpdate = 0L
            netCall.countIn(connection.inputStream).use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            set(pack, Status.Downloading(written, total))
                        }
                    }
                }
            }
            if (length > 0 && written != length) {
                throw FailedException(R.string.core_intel_ocr_download_error_interrupted)
            }
            check(part.renameTo(file(filesDir, pack))) { "could not move the finished download into place" }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }
}
