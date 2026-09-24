package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Which stretch of history a statistics view covers.
 */
enum class StatsPeriod { DAY, WEEK, MONTH }

/**
 * Running totals of the user's typing: characters, words, backspaces and
 * active time, kept per local calendar day plus lifetime sums.
 *
 * Only aggregate counts are ever stored — no text, no per-app split, no
 * timestamps finer than the day bucket and a lifetime hour-of-day histogram.
 * That is the whole privacy story of the Statistics screen, so nothing may be
 * added here that would let a reader of the file reconstruct what was typed
 * or where.
 *
 * What counts as typing is a keystroke that reaches the user's text field:
 * soft keys, hardware keys, braille and morse all funnel through the same
 * per-character path, and words picked from the suggestion strip or drawn by
 * a glide count as words. Voice, handwriting and panel inserts (emoji,
 * clipboard, translate) are insertions rather than typing and are left out on
 * purpose — they would also make the speed figures meaningless.
 *
 * Speed has no prompt to score against (unlike [scoreTypingTest]), so active
 * time is measured burst-wise: the gap between two keystrokes joins
 * `activeMs` only when it is at most [BURST_GAP_MS]. Words per minute is then
 * the usual chars/5 over that active time. Pauses — thinking, switching
 * apps, or recording being disabled — add neither characters nor active
 * time, so they can never dilute the average.
 *
 * Words are counted by a boundary detector on the keystroke stream itself
 * (a letter or digit arms `pendingWord`; a separator or flush counts it),
 * which works identically for composing and direct-commit scripts without a
 * hook in the word-commit path. [onWordsCommitted] clears the armed flag so
 * a half-typed word completed by a suggestion pick is counted exactly once.
 *
 * Storage follows the personal-store contract: nullable file (direct boot →
 * memory only), dirty-flag save on dismissal, [reload] for external edits by
 * the settings app. Lives in `stats/`, not `learning/` — "Delete learned
 * words" must not take the statistics with it.
 */
class TypingStats(
    private val storageFile: File?,
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
) {

    /** One day of typing, in reading order for the settings screen. */
    data class DayEntry(
        val epochDay: Int,
        val chars: Long,
        val words: Long,
        val backspaces: Long,
        val activeMs: Long,
    )

    /** Lifetime sums; unaffected by day-bucket pruning. */
    data class Totals(
        val chars: Long,
        val words: Long,
        val backspaces: Long,
        val activeMs: Long,
        val hourHistogram: List<Long>,
    )

    @Serializable
    private data class DayStat(
        val chars: Long = 0,
        val words: Long = 0,
        val backspaces: Long = 0,
        val activeMs: Long = 0,
    )

    @Serializable
    private data class Snapshot(
        val days: Map<Int, DayStat> = emptyMap(),
        val totalChars: Long = 0,
        val totalWords: Long = 0,
        val totalBackspaces: Long = 0,
        val totalActiveMs: Long = 0,
        val hourHistogram: List<Long> = emptyList(),
    )

    private class DayAcc(
        var chars: Long = 0,
        var words: Long = 0,
        var backspaces: Long = 0,
        var activeMs: Long = 0,
    )

    private val days = HashMap<Int, DayAcc>()
    private var totalChars = 0L
    private var totalWords = 0L
    private var totalBackspaces = 0L
    private var totalActiveMs = 0L
    private val hourHistogram = LongArray(HOURS)
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /** The day the most recent event landed on, so a flush with no clock in
     * hand (from [save]) can attribute the trailing word somewhere sane. */
    private var lastDayKey = 0

    /** Uptime of the previous counted keystroke; 0 means "no previous", so
     * the next keystroke starts a burst instead of closing a gap. */
    private var lastUptimeMs = 0L

    /** A letter or digit has been typed and its word not yet counted. */
    private var pendingWord = false

    /**
     * The single recording gate: the service folds the settings toggle,
     * incognito and power saving into this one flag. Turning it back on
     * forgets the previous keystroke's uptime, so the paused stretch can
     * never leak into [Totals.activeMs] — not even a pause shorter than
     * [BURST_GAP_MS].
     */
    var enabled: Boolean = false
        set(value) {
            if (value && !field) lastUptimeMs = 0L
            field = value
        }

    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /** A keystroke's text reached the field (directly or via the composing
     * buffer). Multi-character texts (ligature keys) count their full length
     * but skip the rhythm and hour bookkeeping, same as the prediction
     * engine's keystroke timing. */
    @Synchronized
    fun onTyped(text: String, nowMillis: Long, uptimeMs: Long) {
        if (!enabled || text.isEmpty()) return
        val day = dayOf(nowMillis)
        day.chars += text.length
        totalChars += text.length
        if (text.length == 1) {
            accrueActive(day, uptimeMs)
            hourHistogram[TypingStatsMath.hourOf(nowMillis, zone())]++
        }
        if (text.last().isLetterOrDigit()) {
            pendingWord = true
        } else {
            countPendingWord(day)
        }
        dirty = true
    }

    /** A space or enter landed in the field: a character for the chars/5
     * arithmetic, and the boundary that counts the pending word. */
    @Synchronized
    fun onSeparator(nowMillis: Long, uptimeMs: Long) {
        if (!enabled) return
        val day = dayOf(nowMillis)
        day.chars += 1
        totalChars += 1
        accrueActive(day, uptimeMs)
        countPendingWord(day)
        dirty = true
    }

    /** Backspace (a word-delete swipe is one event). Counts and keeps the
     * rhythm going but never decrements — the statistic is work done, not
     * text surviving. */
    @Synchronized
    fun onBackspace(nowMillis: Long, uptimeMs: Long) {
        if (!enabled) return
        val day = dayOf(nowMillis)
        day.backspaces += 1
        totalBackspaces += 1
        accrueActive(day, uptimeMs)
        dirty = true
    }

    /** [n] whole words landed without being typed out — a suggestion pick or
     * a glide. Clears the armed half-word so it is not counted again at the
     * next separator; adds no characters or active time, which would
     * inflate the speed figures. */
    @Synchronized
    fun onWordsCommitted(n: Int, nowMillis: Long) {
        if (!enabled || n <= 0) return
        val day = dayOf(nowMillis)
        day.words += n
        totalWords += n
        pendingWord = false
        dirty = true
    }

    @Synchronized
    fun dayEntries(): List<DayEntry> =
        days.entries.sortedBy { it.key }.map { (key, acc) ->
            DayEntry(key, acc.chars, acc.words, acc.backspaces, acc.activeMs)
        }

    @Synchronized
    fun lifetime(): Totals =
        Totals(totalChars, totalWords, totalBackspaces, totalActiveMs, hourHistogram.toList())

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            // The trailing word: "hello" then keyboard dismissed is a word.
            days[lastDayKey]?.let { countPendingWord(it) }
            prune()
            dirty = false
            file.ticket() to Snapshot(
                days = days.mapValues { (_, acc) ->
                    DayStat(acc.chars, acc.words, acc.backspaces, acc.activeMs)
                },
                totalChars = totalChars,
                totalWords = totalWords,
                totalBackspaces = totalBackspaces,
                totalActiveMs = totalActiveMs,
                hourHistogram = hourHistogram.toList(),
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    /** The settings app edited or deleted the file: drop the in-memory copy
     * for the disk state, otherwise the next save would clobber it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        resetInMemory()
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        resetInMemory()
        dirty = snapshotFile?.delete() == false
    }

    private fun resetInMemory() {
        days.clear()
        totalChars = 0
        totalWords = 0
        totalBackspaces = 0
        totalActiveMs = 0
        hourHistogram.fill(0)
        pendingWord = false
        lastUptimeMs = 0
    }

    private fun dayOf(nowMillis: Long): DayAcc {
        val key = TypingStatsMath.localEpochDay(nowMillis, zone())
        lastDayKey = key
        return days.getOrPut(key) { DayAcc() }
    }

    private fun accrueActive(day: DayAcc, uptimeMs: Long) {
        val last = lastUptimeMs
        lastUptimeMs = uptimeMs
        if (last == 0L) return
        val gap = uptimeMs - last
        if (gap in 1..BURST_GAP_MS) {
            day.activeMs += gap
            totalActiveMs += gap
        }
    }

    private fun countPendingWord(day: DayAcc) {
        if (!pendingWord) return
        day.words += 1
        totalWords += 1
        pendingWord = false
    }

    /** Day buckets past [MAX_DAYS] are dropped (relative to the newest day
     * recorded, so a device with a wrong clock cannot eat real history).
     * The lifetime totals are separate fields, so pruning loses nothing the
     * top of the screen shows. */
    private fun prune() {
        val newest = days.keys.maxOrNull() ?: return
        days.keys.removeAll { it < newest - MAX_DAYS }
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            for ((key, stat) in snapshot.days) {
                days[key] = DayAcc(stat.chars, stat.words, stat.backspaces, stat.activeMs)
            }
            totalChars = snapshot.totalChars
            totalWords = snapshot.totalWords
            totalBackspaces = snapshot.totalBackspaces
            totalActiveMs = snapshot.totalActiveMs
            snapshot.hourHistogram.take(HOURS).forEachIndexed { hour, count ->
                hourHistogram[hour] = count
            }
        }
    }

    companion object {
        /** Where the file lives under filesDir — shared by the keyboard and
         * the Statistics screen so the two can never drift apart. */
        const val FILE_PATH = "stats/typing_stats.json"

        /** A gap between keystrokes longer than this is a pause, not typing. */
        const val BURST_GAP_MS = 5_000L

        /** Below this much recorded active time a speed figure is noise, and
         * the screen shows a placeholder instead. */
        const val MIN_ACTIVE_MS = 30_000L

        /** Day buckets kept: a year of history plus a little slack. */
        const val MAX_DAYS = 370

        private const val HOURS = 24
    }
}

/**
 * The calendar and rollup arithmetic for the Statistics screen, pure and
 * separate from the store so it is testable with fixed inputs.
 *
 * All day math is done on the *local* epoch day — the millis shifted by the
 * zone offset first, so a bucket is the user's calendar day, not UTC's. No
 * `java.time` on purpose: desugaring is not enabled and minSdk is 24.
 */
object TypingStatsMath {

    /** One rollup bucket; [startEpochDay] identifies the day/week/month for labeling. */
    data class Bucket(
        val startEpochDay: Int,
        val chars: Long,
        val words: Long,
        val backspaces: Long,
        val activeMs: Long,
    )

    /** The user's calendar day for [nowMillis], as days since 1970-01-01 local. */
    fun localEpochDay(nowMillis: Long, tz: TimeZone): Int =
        Math.floorDiv(nowMillis + tz.getOffset(nowMillis), DAY_MS).toInt()

    /** The user's hour of day (0..23) for [nowMillis]. */
    fun hourOf(nowMillis: Long, tz: TimeZone): Int =
        Math.floorMod((nowMillis + tz.getOffset(nowMillis)) / HOUR_MS, HOURS.toLong()).toInt()

    /** Standard words-per-minute: chars/5 over the active minutes. */
    fun wpm(chars: Long, activeMs: Long): Double =
        if (activeMs <= 0L) 0.0 else (chars / CHARS_PER_WORD) / (activeMs / MINUTE_MS)

    /** Monday-aligned week index (epoch day 0 was a Thursday, hence the +3). */
    fun weekIndexOf(epochDay: Int): Int = Math.floorDiv(epochDay + THURSDAY_SHIFT, DAYS_PER_WEEK)

    /** The Monday an indexed week starts on, as an epoch day. */
    fun weekStartOf(weekIndex: Int): Int = weekIndex * DAYS_PER_WEEK - THURSDAY_SHIFT

    /**
     * Months since year 0 for [epochDay]. The epoch day is already local, so
     * a UTC calendar reads the right year and month fields back out.
     */
    fun monthKeyOf(epochDay: Int): Int {
        val calendar = utcCalendar()
        calendar.timeInMillis = epochDay * DAY_MS
        return calendar.get(GregorianCalendar.YEAR) * MONTHS_PER_YEAR +
            calendar.get(GregorianCalendar.MONTH)
    }

    /** The first day of a [monthKeyOf] month, as an epoch day. */
    fun monthStartOf(monthKey: Int): Int {
        val calendar = utcCalendar()
        calendar.clear()
        calendar.set(
            Math.floorDiv(monthKey, MONTHS_PER_YEAR),
            Math.floorMod(monthKey, MONTHS_PER_YEAR),
            1,
        )
        return (calendar.timeInMillis / DAY_MS).toInt()
    }

    /**
     * Rolls the day entries up into the fixed window the charts draw: the
     * last [DAY_BUCKETS] days, [WEEK_BUCKETS] weeks or [MONTH_BUCKETS]
     * months ending today, zero-filled so a quiet day is a visible gap
     * rather than a missing bar.
     */
    fun rollup(
        entries: List<TypingStats.DayEntry>,
        period: StatsPeriod,
        todayEpochDay: Int,
    ): List<Bucket> = when (period) {
        StatsPeriod.DAY -> {
            val byDay = entries.associateBy { it.epochDay }
            ((todayEpochDay - DAY_BUCKETS + 1)..todayEpochDay).map { day ->
                bucketOf(day, listOfNotNull(byDay[day]))
            }
        }
        StatsPeriod.WEEK -> {
            val byWeek = entries.groupBy { weekIndexOf(it.epochDay) }
            val thisWeek = weekIndexOf(todayEpochDay)
            ((thisWeek - WEEK_BUCKETS + 1)..thisWeek).map { week ->
                bucketOf(weekStartOf(week), byWeek[week].orEmpty())
            }
        }
        StatsPeriod.MONTH -> {
            val byMonth = entries.groupBy { monthKeyOf(it.epochDay) }
            val thisMonth = monthKeyOf(todayEpochDay)
            ((thisMonth - MONTH_BUCKETS + 1)..thisMonth).map { month ->
                bucketOf(monthStartOf(month), byMonth[month].orEmpty())
            }
        }
    }

    private fun bucketOf(startEpochDay: Int, entries: List<TypingStats.DayEntry>): Bucket =
        Bucket(
            startEpochDay = startEpochDay,
            chars = entries.sumOf { it.chars },
            words = entries.sumOf { it.words },
            backspaces = entries.sumOf { it.backspaces },
            activeMs = entries.sumOf { it.activeMs },
        )

    private fun utcCalendar() = GregorianCalendar(TimeZone.getTimeZone("UTC"))

    const val DAY_BUCKETS = 14
    const val WEEK_BUCKETS = 12
    const val MONTH_BUCKETS = 12

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000.0
    private const val CHARS_PER_WORD = 5.0
    private const val HOURS = 24
    private const val DAYS_PER_WEEK = 7
    private const val THURSDAY_SHIFT = 3
    private const val MONTHS_PER_YEAR = 12
}
