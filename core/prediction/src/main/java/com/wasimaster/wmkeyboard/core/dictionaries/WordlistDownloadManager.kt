package com.wasimaster.wmkeyboard.core.dictionaries

import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.prediction.AospScores
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrieCodec
import com.wasimaster.wmkeyboard.prediction.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
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
 * Downloads [DictionaryCatalog] wordlists and converts them to `.wmdict`
 * binaries in [DictionaryStore]'s layout — the `.gz` never touches disk.
 *
 * Pipeline: HTTP stream -> gzip inflate -> parse the first N most frequent
 * words (the lists are sorted, so the connection is aborted early — a 41 MB
 * Thai file costs only the first few MB of transfer) -> pack into a trie ->
 * write `main.wmdict.part` -> atomic rename. Presence of the final file means
 * the whole pipeline finished, exactly like `LocalLlmStore`'s contract.
 *
 * Modeled on `WhisperDownloadManager` (process singleton, own IO scope,
 * StateFlow of per-entry status) minus Range resume: a capped download is
 * cheap enough to restart, and a partially-consumed gzip stream cannot be
 * checksummed anyway — the gzip CRC of the blocks we do read plus the trie
 * build itself validate the payload.
 */
object WordlistDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus

        /** [totalBytes] is the compressed size of the FULL file — an upper
         * bound; a capped download completes well before reaching it. */
        data class Downloading(val bytes: Long, val totalBytes: Long) : DownloadStatus

        /** Transfer finished; packing entries into the binary trie. */
        data object Processing : DownloadStatus

        /**
         * [size] is the tier the file on disk was fetched at, so the UI can
         * offer the others. Read from the marker beside the file, or, for a
         * file written before that marker existed, the smallest tier that
         * could have produced [wordCount].
         */
        data class Downloaded(
            val wordCount: Int,
            val sizeBytes: Long,
            val size: DictionaryCatalog.DictionarySize,
        ) : DownloadStatus

        /**
         * [messageRes] is the text to show the user. [messageArg] is the one
         * format argument that text takes, or "" when it takes none. The UI
         * resolves the pair together:
         * `if (messageArg.isEmpty()) stringResource(messageRes)`
         * `else stringResource(messageRes, messageArg)`.
         */
        data class Failed(
            val reason: FailReason,
            @StringRes val messageRes: Int,
            val messageArg: String = "",
            /**
             * Whether the list this download was replacing is still on the
             * device. A failed *re-download* at another size leaves the old
             * file untouched — it is only ever replaced by the atomic rename
             * at the very end — so the row keeps its checkbox and its Delete
             * beside the error.
             */
            val kept: Boolean = false,
        ) : DownloadStatus
    }

    enum class FailReason { NETWORK, NO_SPACE, MALFORMED, OTHER }

    /** Frequencies below this are subtitle/scrape noise, not vocabulary. */
    private const val MIN_FREQUENCY = 2

    /** Smallest array a flat list grows to, for a cap that clamped to almost nothing. */
    private const val MIN_GROWTH = 1024

    /** Guards against pathological lines masquerading as words. */
    private const val MAX_WORD_LENGTH = 48

    /** Rough on-disk bytes per stored word (measured ~40 B on the bundled lists). */
    private const val BYTES_PER_WORD = 64L

    private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard dictionary downloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeId: String? = null

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** Entry id -> status for every catalog entry touched so far. */
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    val isBusy: Boolean get() = activeJob?.isActive == true

    /** Seeds [states] from disk; call when a dictionary UI appears. */
    fun refresh(filesDir: File) {
        _states.update { current ->
            DictionaryCatalog.entries.associate { entry ->
                entry.id to when {
                    entry.id == activeId && isBusy && current[entry.id] != null ->
                        current.getValue(entry.id)
                    downloadedByThisEntry(filesDir, entry) -> downloadedStatus(filesDir, entry)
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    fun start(filesDir: File, entry: DictionaryEntry, size: DictionaryCatalog.DictionarySize) {
        if (isBusy) return
        activeId = entry.id
        set(entry.id, DownloadStatus.Downloading(0, entry.approxGzBytes))
        activeJob = scope.launch {
            val part = DictionaryStore.partFile(filesDir, entry.languageId)
            try {
                // Clamped here, not inside: ALL's cap is Int.MAX_VALUE, which
                // would ask the free-space check for 137 GB.
                val list = fetchEntries(entry, DictionaryCatalog.wordCap(entry, size), part)
                set(entry.id, DownloadStatus.Processing)
                val trie = PackedTrie.of(list.words, list.frequencies, list.count)
                part.outputStream().use { PackedTrieCodec.write(trie, it) }
                val final = DictionaryStore.downloadedFile(filesDir, entry.languageId)
                if (!part.renameTo(final)) {
                    throw FailedException(FailReason.OTHER, R.string.core_pred_wordlist_save_error)
                }
                DictionaryStore.writeSourceEntryId(filesDir, entry.languageId, entry.id)
                DictionaryStore.writeDownloadedSize(filesDir, entry.languageId, size.name)
                set(entry.id, DownloadStatus.Downloaded(trie.wordCount, final.length(), size))
                // A language can have several catalog entries (pt); the one
                // just replaced must stop claiming the file.
                refresh(filesDir)
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                // Nothing was replaced — the rename is the last step — so the
                // list this one was going to replace is still there.
                activeId = null
                refresh(filesDir)
                throw e
            } catch (e: FailedException) {
                part.delete()
                set(
                    entry.id,
                    DownloadStatus.Failed(
                        e.reason,
                        e.messageRes,
                        e.messageArg,
                        kept = downloadedByThisEntry(filesDir, entry),
                    ),
                )
            } catch (_: Exception) {
                part.delete()
                set(
                    entry.id,
                    DownloadStatus.Failed(
                        FailReason.NETWORK,
                        CommonR.string.common_error_network,
                        kept = downloadedByThisEntry(filesDir, entry),
                    ),
                )
            } finally {
                activeId = null
            }
        }
    }

    /**
     * Builds [entry]'s dictionary from a copy of its list the user fetched
     * some other way, [file], which is the repo's `.txt.gz` or the same list
     * inflated. The same parse and the same trie as a download, run on the
     * caller's thread, so a build with no internet permission can still have
     * every language's words. Returns the words kept.
     *
     * Refused while a download is running, for the same reason a second
     * download is: both write the one `.part` beside the list.
     */
    fun install(filesDir: File, entry: DictionaryEntry, size: DictionaryCatalog.DictionarySize, file: File): Int {
        check(!isBusy) { "a download is running" }
        val part = DictionaryStore.partFile(filesDir, entry.languageId)
        part.parentFile?.mkdirs()
        activeId = entry.id
        try {
            set(entry.id, DownloadStatus.Processing)
            val list = openList(file).use { input ->
                parse(entry, DictionaryCatalog.wordCap(entry, size), input) {}
            }
            val trie = PackedTrie.of(list.words, list.frequencies, list.count)
            part.outputStream().use { PackedTrieCodec.write(trie, it) }
            val final = DictionaryStore.downloadedFile(filesDir, entry.languageId)
            if (!part.renameTo(final)) throw IOException("could not move the list into place")
            DictionaryStore.writeSourceEntryId(filesDir, entry.languageId, entry.id)
            DictionaryStore.writeDownloadedSize(filesDir, entry.languageId, size.name)
            return trie.wordCount
        } finally {
            part.delete()
            activeId = null
            refresh(filesDir)
        }
    }

    /** [file] inflated when it is gzip (the repo's form), as it is otherwise. */
    private fun openList(file: File): InputStream {
        val raw = file.inputStream().buffered()
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        return if (gzip) GZIPInputStream(raw, 32 * 1024) else raw
    }

    fun cancel() {
        activeJob?.cancel()
    }

    fun delete(filesDir: File, entry: DictionaryEntry) {
        if (entry.id == activeId) cancel()
        DictionaryStore.delete(filesDir, entry.languageId)
        refresh(filesDir)
    }

    private fun downloadedByThisEntry(filesDir: File, entry: DictionaryEntry): Boolean {
        if (!DictionaryStore.isDownloaded(filesDir, entry.languageId)) return false
        val source = DictionaryStore.sourceEntryId(filesDir, entry.languageId)
        return source == entry.id || (source == null && entry.id == entry.languageId)
    }

    private fun downloadedStatus(filesDir: File, entry: DictionaryEntry): DownloadStatus {
        val file = DictionaryStore.downloadedFile(filesDir, entry.languageId)
        val words = runCatching { PackedTrieCodec.readHeader(file).wordCount }.getOrDefault(0)
        return DownloadStatus.Downloaded(words, file.length(), sizeOf(filesDir, entry, words))
    }

    /**
     * The tier [entry]'s file on disk holds: what the download recorded, or,
     * for one written before the marker existed, the smallest tier whose cap
     * could have produced [words]. The guess errs small on purpose — a list cut
     * short by the frequency floor has fewer words than its tier promised, so
     * the first tier that fits is the one to offer stepping up from.
     */
    private fun sizeOf(filesDir: File, entry: DictionaryEntry, words: Int): DictionaryCatalog.DictionarySize {
        val recorded = DictionaryStore.downloadedSize(filesDir, entry.languageId)
        return DictionaryCatalog.DictionarySize.entries.firstOrNull { it.name == recorded }
            ?: DictionaryCatalog.DictionarySize.entries.firstOrNull {
                DictionaryCatalog.wordCap(entry, it) >= words
            }
            ?: DictionaryCatalog.DictionarySize.ALL
    }

    private fun set(id: String, status: DownloadStatus) {
        _states.update { it + (id to status) }
    }

    /**
     * A download failure that already knows which message the UI should show.
     * The exception's own `message` stays null: the text lives in [messageRes]
     * so it follows the app language.
     */
    private class FailedException(
        val reason: FailReason,
        @StringRes val messageRes: Int,
        val messageArg: String = "",
    ) : IOException()

    /** An InputStream that counts what passes through, for progress against
     * the compressed size (the gzip stream's consumption, not inflated bytes). */
    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        @Volatile
        var bytesRead = 0L

        override fun read(): Int = wrapped.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun close() = wrapped.close()
    }

    /**
     * The first [count] slots of two parallel arrays: word `i` and its
     * frequency. Arrays rather than a `List<Pair>` because an "everything"
     * list runs to millions of entries, where a `Pair` and a boxed `Integer`
     * apiece is tens of MB the trie build then has to squeeze in beside (#203).
     */
    private class Wordlist(val words: Array<String?>, val frequencies: IntArray, val count: Int)

    /**
     * Streams the remote gzip list and returns its first [wordCap] usable
     * entries, aborting the transfer once the cap is hit. A flat list, one
     * with no frequencies to be sorted by, comes back whole.
     */
    private suspend fun fetchEntries(
        entry: DictionaryEntry,
        wordCap: Int,
        part: File,
    ): Wordlist {
        part.parentFile?.mkdirs()
        val free = StatFs(part.parentFile!!.path).availableBytes
        if (free < wordCap * BYTES_PER_WORD + SPACE_MARGIN_BYTES) {
            throw FailedException(FailReason.NO_SPACE, R.string.core_pred_wordlist_no_space_error)
        }

        val connection = URL(entry.url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_WORDLIST, "GET", entry.url, route = NetLog.pathOf(entry.url))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            netCall.status = connection.responseCode
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw FailedException(
                    FailReason.OTHER,
                    R.string.core_pred_wordlist_http_error,
                    status.toString(),
                )
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: entry.approxGzBytes

            val counting = CountingInputStream(netCall.countIn(connection.inputStream))
            var lastUpdate = 0L
            return GZIPInputStream(counting, 32 * 1024).use { input ->
                parse(entry, wordCap, input) {
                    currentCoroutineContext().ensureActive()
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        lastUpdate = now
                        set(entry.id, DownloadStatus.Downloading(counting.bytesRead, total))
                    }
                }
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    /**
     * The first [wordCap] usable entries of an inflated `word count` list, the
     * way [fetchEntries] and [install] both read it. [onLine] runs once per
     * line, for progress and cancellation.
     */
    private inline fun parse(entry: DictionaryEntry, wordCap: Int, input: InputStream, onLine: () -> Unit): Wordlist {
        // wordCap is already clamped to the list's length, and a sorted
        // list stops at it, so only a flat one (below) ever grows these.
        var words = arrayOfNulls<String>(wordCap)
        var frequencies = IntArray(wordCap)
        var count = 0
        // Whether the noise cut below applies at all, decided by the first
        // usable line. A few repo lists are bare wordlists wearing the
        // frequency format — Bengali's 451,348 words all say `1` — and on
        // those the cut fires on line one, empties the list and reports it
        // as unreadable. A list whose *most frequent* word is already under
        // the floor has no frequencies to rank by, so there is no noise
        // tail to trim and every word is kept.
        //
        // An AOSP list has no noise tail to cut either: it is curated, and
        // its bottom is real words AOSP rated rare, down to 0.
        //
        // A flat list has no order for the word cap to choose by either.
        // Bengali's is alphabetical, so its first 300k lines stopped at
        // বিশ্ব… and every word from ভ to হ was missing — সরাসরি among
        // them, which then lost to a sibling from the kept half. A flat
        // list is taken whole, whatever size was asked for.
        val aosp = entry.source == WordlistSource.AOSP
        var ranked: Boolean? = if (aosp) false else null
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                onLine()
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val separator = trimmed.lastIndexOf(' ')
                if (separator <= 0) continue
                val word = trimmed.substring(0, separator).trim()
                val raw = trimmed.substring(separator + 1).toIntOrNull() ?: continue
                val frequency = if (aosp) AospScores.listCount(raw) else raw
                if (ranked == null) ranked = frequency >= MIN_FREQUENCY
                // Sorted desc, so on a ranked list only noise follows.
                if (ranked == true && frequency < MIN_FREQUENCY) break
                if (word.length > MAX_WORD_LENGTH || ' ' in word) continue
                words[count] = word
                frequencies[count] = frequency
                count++
                if (count >= words.size) {
                    if (ranked == true || aosp) break
                    words = words.copyOf(maxOf(words.size * 2, MIN_GROWTH))
                    frequencies = frequencies.copyOf(words.size)
                }
            }
        }
        if (count == 0) {
            throw FailedException(FailReason.MALFORMED, R.string.core_pred_wordlist_malformed_error)
        }
        return Wordlist(words, frequencies, count)
    }
}
