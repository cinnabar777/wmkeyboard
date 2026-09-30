package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sync.Remembered
import com.wasimaster.wmkeyboard.core.settings.sync.SyncEntries
import com.wasimaster.wmkeyboard.core.settings.sync.SyncMerge
import com.wasimaster.wmkeyboard.core.settings.sync.SyncStatistics
import com.wasimaster.wmkeyboard.core.settings.sync.SyncTable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #447: two phones typing between syncs must both keep their counts. Each
 * syncs its own contribution; the screen adds them up.
 */
class SyncStatisticsTest {

    private val a = "aaaaaaaa"
    private val b = "bbbbbbbb"
    private val section = SyncStatistics.SECTION_ID

    private fun counts(chars: Long) = JsonObject(mapOf("totalChars" to JsonPrimitive(chars)))

    private fun chars(element: JsonElement?) =
        (element as JsonObject)["totalChars"].toString().toLong()

    /** One device's view of the world: its own counts and what it knows of the others. */
    private class Phone(val id: String) {
        var own: JsonObject? = null
        var others: JsonObject? = null
        var remembered: Map<String, Map<String, Remembered>>? = null
        var file: SyncTable = emptyMap()
    }

    private fun pass(phone: Phone, remote: Phone, nowMs: Long) {
        val hadOwn = phone.own != null
        val local = mapOf(
            section to SyncEntries.explode(
                SyncStatistics.byDevice(phone.own, phone.others, phone.id),
                deep = false,
            ),
        )
        val result = SyncMerge.merge(
            local = local,
            remembered = phone.remembered.orEmpty(),
            remotes = listOf(remote.file).filter { it.isNotEmpty() },
            me = phone.id,
            nowMs = nowMs,
            firstSync = phone.remembered == null,
            owned = { s, key -> SyncStatistics.owned(s, key, phone.id) },
        )
        result.changes[section]?.let {
            val entries = result.merged[section].orEmpty().filterValues { !it.deleted }.mapValues { it.value.value!! }
            val element = SyncEntries.implode(entries, rootIsArray = false) ?: JsonObject(emptyMap())
            val received = SyncStatistics.received(element.jsonObject, phone.id, hadOwn)
            phone.others = received.others
            if (received.clearOwn) phone.own = null
        }
        phone.remembered = result.remembered
        phone.file = result.merged
    }

    private fun total(phone: Phone): Long =
        (listOfNotNull(phone.own) + phone.others?.values.orEmpty()).sumOf { chars(it) }

    @Test
    fun `two phones typing between syncs keep both counts`() {
        val phoneA = Phone(a).apply { own = counts(10_000) }
        val phoneB = Phone(b).apply { own = counts(5_000) }
        pass(phoneA, phoneB, nowMs = 1_000)
        pass(phoneB, phoneA, nowMs = 2_000)
        pass(phoneA, phoneB, nowMs = 3_000)
        assertEquals(15_000, total(phoneA))
        assertEquals(15_000, total(phoneB))

        // Both type before either syncs again: the case that used to lose one.
        phoneA.own = counts(11_000)
        phoneB.own = counts(5_500)
        pass(phoneA, phoneB, nowMs = 4_000)
        pass(phoneB, phoneA, nowMs = 5_000)
        pass(phoneA, phoneB, nowMs = 6_000)
        assertEquals(16_500, total(phoneA))
        assertEquals(16_500, total(phoneB))

        // Syncing again adds nothing twice.
        pass(phoneB, phoneA, nowMs = 7_000)
        pass(phoneA, phoneB, nowMs = 8_000)
        assertEquals(16_500, total(phoneA))
        assertEquals("own counts never come from the other phone", 11_000, chars(phoneA.own))
    }

    @Test
    fun `a stale copy of a phone's own counts loses to the phone itself`() {
        val phoneA = Phone(a).apply { own = counts(100) }
        val phoneB = Phone(b).apply { own = counts(50) }
        pass(phoneA, phoneB, nowMs = 1_000)
        pass(phoneB, phoneA, nowMs = 2_000)
        phoneB.own = counts(80)
        pass(phoneB, phoneA, nowMs = 3_000)
        // A restores an old backup that remembered B at 50.
        phoneA.others = JsonObject(mapOf(b to counts(50)))
        pass(phoneA, phoneB, nowMs = 4_000)
        pass(phoneB, phoneA, nowMs = 5_000)
        pass(phoneA, phoneB, nowMs = 6_000)
        assertEquals(80, chars(phoneA.others!![b]))
        assertEquals(80, chars(phoneB.own))
    }

    @Test
    fun `deleting all statistics on one phone clears them on the other`() {
        val phoneA = Phone(a).apply { own = counts(100) }
        val phoneB = Phone(b).apply { own = counts(50) }
        pass(phoneA, phoneB, nowMs = 1_000)
        pass(phoneB, phoneA, nowMs = 2_000)
        pass(phoneA, phoneB, nowMs = 3_000)
        phoneA.own = null
        phoneA.others = null
        pass(phoneA, phoneB, nowMs = 4_000)
        pass(phoneB, phoneA, nowMs = 5_000)
        assertNull(phoneB.own)
        assertEquals(0, total(phoneB))
    }

    @Test
    fun `counts the keyboard saved during a pass are not taken for a deletion`() {
        val received = SyncStatistics.received(JsonObject(mapOf(b to counts(5))), a, hadOwn = false)
        assertFalse(received.clearOwn)
        assertTrue(SyncStatistics.received(JsonObject(emptyMap()), a, hadOwn = true).clearOwn)
    }

    @Test
    fun `a backup restores here as this phone's and elsewhere beside the other's`() {
        val section = SyncStatistics.backup(counts(100), JsonObject(mapOf(b to counts(40))), a)!!

        val here = SyncStatistics.restore(section, a, others = null)
        assertEquals(counts(100), here.own)
        assertEquals(setOf(b), here.others.keys)

        val elsewhere = SyncStatistics.restore(section, "cccccccc", others = null)
        assertNull("the new phone's own counts are left alone", elsewhere.own)
        assertEquals(counts(100), elsewhere.others[a])
        assertEquals(setOf(a, b), elsewhere.others.keys)

        val onB = SyncStatistics.restore(section, b, others = null)
        assertEquals("B's own counts, as the backup knew them", counts(40), onB.own)
        assertEquals(setOf(a), onB.others.keys)
    }

    @Test
    fun `a backup from before per-device statistics restores as this phone's`() {
        val restored = SyncStatistics.restore(counts(70), a, others = JsonObject(mapOf(b to counts(5))))
        assertEquals(counts(70), restored.own)
        assertEquals(setOf(b), restored.others.keys)
    }

    @Test
    fun `the shared total moves aside once`() {
        val moved = SyncStatistics.retire(counts(900), others = null)!!
        assertEquals(counts(900), moved[SyncStatistics.LEGACY])
        assertNull("already moved", SyncStatistics.retire(counts(3), moved))
        assertNull("nothing to move", SyncStatistics.retire(null, null))
    }
}
