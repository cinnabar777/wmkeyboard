package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class TypingStatsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "stats/typing_stats.json")

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** A store pinned to UTC so a test's day math never depends on the machine. */
    private fun store(target: File? = file()): TypingStats =
        TypingStats(target) { utc }.also { it.enabled = true }

    // A moment safely inside a UTC day (12:00), and a keystroke cadence.
    private val noon = 1_754_000_000_000L - (1_754_000_000_000L % DAY) + DAY / 2

    private fun TypingStats.type(text: String, startUptime: Long = 10_000L): Long {
        var uptime = startUptime
        for (char in text) {
            uptime += CADENCE
            if (char == ' ') onSeparator(noon, uptime) else onTyped(char.toString(), noon, uptime)
        }
        return uptime
    }

    @Test
    fun `local epoch day follows the zone, not UTC`() {
        val dhaka = TimeZone.getTimeZone("Asia/Dhaka") // UTC+6, no DST
        // 20:00 UTC is already the next day in Dhaka.
        val evening = 100L * DAY + 20 * HOUR
        assertEquals(100, TypingStatsMath.localEpochDay(evening, utc))
        assertEquals(101, TypingStatsMath.localEpochDay(evening, dhaka))
        // 02:00 UTC is still the previous day in a UTC-5 zone.
        val night = 100L * DAY + 2 * HOUR
        assertEquals(99, TypingStatsMath.localEpochDay(night, TimeZone.getTimeZone("America/Bogota")))
        assertEquals(23, TypingStatsMath.hourOf(100L * DAY - HOUR, utc))
    }

    @Test
    fun `weeks align to Monday and months to the calendar`() {
        // Epoch day 0 was Thursday 1970-01-01; the Monday of that week is day -3.
        val thursday = 0
        assertEquals(TypingStatsMath.weekIndexOf(thursday), TypingStatsMath.weekIndexOf(-3))
        assertTrue(TypingStatsMath.weekIndexOf(-4) < TypingStatsMath.weekIndexOf(-3))
        // Day 3 was the following Sunday, day 4 the next Monday.
        assertEquals(TypingStatsMath.weekIndexOf(0), TypingStatsMath.weekIndexOf(3))
        assertTrue(TypingStatsMath.weekIndexOf(4) > TypingStatsMath.weekIndexOf(3))
        assertEquals(-3, TypingStatsMath.weekStartOf(TypingStatsMath.weekIndexOf(0)))

        // December 1970 and January 1971 are adjacent month keys.
        val december = TypingStatsMath.monthKeyOf(360)
        val january = TypingStatsMath.monthKeyOf(370)
        assertEquals(december + 1, january)
        assertEquals(365, TypingStatsMath.monthStartOf(january))
    }

    @Test
    fun `typed characters and separators count words by boundary`() {
        val stats = store(null)
        stats.type("hi there.")
        val totals = stats.lifetime()
        assertEquals(9, totals.chars)
        // "hi" at the space, "there" at the full stop.
        assertEquals(2, totals.words)
    }

    @Test
    fun `the trailing word is counted at save`() {
        val stats = store()
        stats.type("hello")
        assertEquals(0, stats.lifetime().words)
        stats.save()
        assertEquals(1, stats.lifetime().words)
        assertEquals(1, store().also { it.reload() }.lifetime().words)
    }

    @Test
    fun `a suggestion pick never double-counts the half-typed word`() {
        val stats = store(null)
        val uptime = stats.type("he")
        stats.onWordsCommitted(1, noon)
        stats.onSeparator(noon, uptime + CADENCE)
        assertEquals("the pick's word only", 1, stats.lifetime().words)
        stats.type("hi ", startUptime = uptime + 2 * CADENCE)
        assertEquals(2, stats.lifetime().words)
    }

    @Test
    fun `active time takes typing gaps and refuses pauses`() {
        val stats = store(null)
        var uptime = 10_000L
        stats.onTyped("a", noon, uptime)
        uptime += 1_000L
        stats.onTyped("b", noon, uptime)
        uptime += 1_000L
        stats.onTyped("c", noon, uptime)
        assertEquals(2_000L, stats.lifetime().activeMs)
        // A gap over the burst ceiling is thinking, not typing.
        uptime += TypingStats.BURST_GAP_MS + 1
        stats.onTyped("d", noon, uptime)
        assertEquals(2_000L, stats.lifetime().activeMs)
        assertEquals(60.0, TypingStatsMath.wpm(300, 60_000), 1e-9)
        assertEquals(0.0, TypingStatsMath.wpm(300, 0), 1e-9)
    }

    @Test
    fun `a disabled stretch adds nothing, not even a short one`() {
        val stats = store(null)
        stats.onTyped("a", noon, 10_000L)
        stats.onTyped("b", noon, 11_000L)
        stats.enabled = false
        stats.onTyped("x", noon, 12_000L)
        // Re-enabled two seconds later: under the burst ceiling, but the
        // pause must still not count as active typing time.
        stats.enabled = true
        stats.onTyped("c", noon, 13_000L)
        val totals = stats.lifetime()
        assertEquals("the paused keystroke is not counted", 3, totals.chars)
        assertEquals(1_000L, totals.activeMs)
        stats.onTyped("d", noon, 14_000L)
        assertEquals(2_000L, stats.lifetime().activeMs)
    }

    @Test
    fun `the snapshot survives the round trip`() {
        val first = store()
        first.type("hi there ")
        first.onBackspace(noon, 99_000L)
        first.save()
        val second = store()
        assertEquals(first.lifetime(), second.lifetime())
        assertEquals(first.dayEntries(), second.dayEntries())
        assertEquals(1, second.dayEntries().size)
        assertEquals(
            TypingStatsMath.localEpochDay(noon, utc),
            second.dayEntries().single().epochDay,
        )
    }

    @Test
    fun `a null file is memory only and clear deletes the real one`() {
        val memory = store(null)
        memory.type("abc ")
        memory.save()
        assertFalse(file().exists())

        val real = store()
        real.type("abc ")
        real.save()
        assertTrue(file().exists())
        real.clear()
        assertFalse(file().exists())
        assertEquals(0, real.lifetime().chars)
        assertEquals(0, store().lifetime().chars)
    }

    @Test
    fun `old day buckets are pruned but the lifetime totals survive`() {
        val stats = store()
        stats.onTyped("a", noon, 1_000L)
        val later = noon + (TypingStats.MAX_DAYS + 1L) * DAY
        stats.onTyped("b", later, 2_000L)
        stats.save()
        val reread = store()
        assertEquals(1, reread.dayEntries().size)
        assertEquals("pruning must not eat the totals", 2, reread.lifetime().chars)
    }

    @Test
    fun `unknown json keys are ignored`() {
        val target = file()
        target.parentFile?.mkdirs()
        target.writeText("""{"totalChars":7,"someFutureField":true}""")
        assertEquals(7, store().lifetime().chars)
    }

    @Test
    fun `day rollup zero-fills the window`() {
        val today = TypingStatsMath.localEpochDay(noon, utc)
        val entries = listOf(
            TypingStats.DayEntry(today, chars = 10, words = 2, backspaces = 1, activeMs = 1_000),
            TypingStats.DayEntry(today - 2, chars = 5, words = 1, backspaces = 0, activeMs = 500),
        )
        val buckets = TypingStatsMath.rollup(entries, StatsPeriod.DAY, today)
        assertEquals(TypingStatsMath.DAY_BUCKETS, buckets.size)
        assertEquals(today, buckets.last().startEpochDay)
        assertEquals(10, buckets.last().chars)
        assertEquals(5, buckets[buckets.size - 3].chars)
        assertEquals(0, buckets.first().chars)
    }

    @Test
    fun `week and month rollups sum their days`() {
        val today = TypingStatsMath.localEpochDay(noon, utc)
        val monday = TypingStatsMath.weekStartOf(TypingStatsMath.weekIndexOf(today))
        val entries = listOf(
            TypingStats.DayEntry(monday, chars = 3, words = 1, backspaces = 0, activeMs = 100),
            TypingStats.DayEntry(today, chars = 4, words = 1, backspaces = 0, activeMs = 100),
        )
        val weeks = TypingStatsMath.rollup(entries, StatsPeriod.WEEK, today)
        assertEquals(TypingStatsMath.WEEK_BUCKETS, weeks.size)
        assertEquals("both days land in the current week", 7, weeks.last().chars)

        val months = TypingStatsMath.rollup(entries, StatsPeriod.MONTH, today)
        assertEquals(TypingStatsMath.MONTH_BUCKETS, months.size)
        assertEquals(7, months.sumOf { it.chars })
    }

    @Test
    fun `other devices' counts add to this one's and are never saved as its own`() {
        val own = store()
        own.type("hi there ")
        own.onKeyTap("qwerty", "English", 1f, 1f, emptyList())
        own.save()

        val day = TypingStatsMath.localEpochDay(noon, utc)
        val devices = """{
            "bbbbbbbb": {"days": {"$day": {"chars": 100, "words": 20}}, "totalChars": 100, "totalWords": 20,
                         "hourHistogram": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5],
                         "heatmaps": {"qwerty": {"taps": 4, "cells": {"7": 4}}}},
            "legacy": {"totalChars": 1000, "glideWords": 3}
        }"""
        val view = store().also { it.absorbDevices(devices) }
        val totals = view.lifetime()
        assertEquals(9L + 100 + 1000, totals.chars)
        assertEquals(2L + 20, totals.words)
        assertEquals(3L, totals.glideWords)
        assertEquals("seven letters here at noon, five there", 7L + 5, totals.hourHistogram[12])
        assertEquals(9L + 100, view.dayEntries().single().chars)
        assertEquals("one board, both devices' taps", 5L, view.heatmaps().single().taps)

        assertEquals("the file still holds only this device's", 9L, store().lifetime().chars)
        view.absorbDevices("not json")
        assertEquals("a broken file adds nothing", totals, view.lifetime())
    }

    private companion object {
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        const val CADENCE = 200L
    }
}
