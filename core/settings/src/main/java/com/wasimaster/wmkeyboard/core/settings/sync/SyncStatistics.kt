package com.wasimaster.wmkeyboard.core.settings.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Typing statistics as sync and backups carry them: each device's own counts
 * side by side, never one shared total (#447).
 *
 * Counts are the one store where "the newest change wins" loses data. Two
 * phones typing on the same day both change that day's `chars`, and whichever
 * synced last threw the other's typing away. So every device writes only its
 * own contribution, under its installation id, and the others' arrive beside
 * it; the Statistics screen adds them up. Syncing again replaces a device's
 * contribution rather than adding a second copy of it, so nothing is counted
 * twice either.
 *
 * On disk this device's counts stay where the keyboard keeps them
 * (`TypingStats.FILE_PATH`), and the others' go in a file of their own
 * (`TypingStats.DEVICES_FILE_PATH`) that the keyboard never reads.
 */
object SyncStatistics {

    /**
     * The section's name in a sync file. Not the backup section's
     * `statistics`: a device still on an older build writes that one as a
     * shared total, and under one name the two would read each other's
     * entries as deletions and wipe each other's counts. Under two they pass
     * by, and the older device keeps its numbers until it updates.
     */
    const val SECTION_ID = "statistics_by_device"

    /**
     * Where a device puts the counts it held while it synced the shared total
     * before #447. Those were already a blend of every device's, so they can
     * be nobody's own: under one shared name, each device that updates offers
     * the same blend, and sync keeps one copy of it.
     */
    const val LEGACY = "legacy"

    /** In a backup, beside the counts: the installation they belong to. */
    private const val DEVICE = "device"

    /** In a backup, beside the counts: the other devices' counts, by id. */
    private const val DEVICES = "devices"

    private val EMPTY = JsonObject(emptyMap())

    /** What this device syncs: its own counts under [me], the others' under theirs. */
    fun byDevice(own: JsonObject?, others: JsonObject?, me: String): JsonObject =
        JsonObject(others.orEmpty() - me + listOfNotNull(own?.let { me to it }))

    /**
     * What to write after a pass. [others] replaces the other devices' file.
     * [clearOwn] is Delete all statistics pressed on another device: this
     * device's own entry was deleted, and only a deletion can do that, since
     * this device's counts never come from anywhere else.
     */
    data class Received(val others: JsonObject, val clearOwn: Boolean)

    /**
     * [hadOwn] says the pass read this device's own counts. Without it, a file
     * the keyboard saved for the first time while the pass ran would look
     * deleted elsewhere.
     */
    fun received(byDevice: JsonObject, me: String, hadOwn: Boolean): Received =
        Received(JsonObject(byDevice - me), clearOwn = hadOwn && me !in byDevice)

    /**
     * The backup section: this device's counts as they always were, so an
     * older build restores them unchanged, plus whose they are and the other
     * devices' counts. Null when there is nothing to back up.
     */
    fun backup(own: JsonObject?, others: JsonObject?, me: String): JsonObject? {
        val rest = others?.takeIf { it.isNotEmpty() }
        if (own == null && rest == null) return null
        return JsonObject(
            own.orEmpty() + (DEVICE to JsonPrimitive(me)) + listOfNotNull(rest?.let { DEVICES to it }),
        )
    }

    /** What a restore writes: this device's counts (null leaves them alone), and the others'. */
    data class Restored(val own: JsonObject?, val others: JsonObject)

    /**
     * A backup made here restores this device's counts. One made on another
     * device adds that device's counts beside these instead of writing over
     * them: they are its typing, not this one's, and once both devices sync
     * they would otherwise be counted twice. A bundle from before #447 names
     * no device and restores as this one's, as it always did.
     */
    fun restore(section: JsonObject, me: String, others: JsonObject?): Restored {
        val from = (section[DEVICE] as? JsonPrimitive)?.contentOrNull
        val theirs = section[DEVICES] as? JsonObject ?: EMPTY
        val counts = JsonObject(section - DEVICE - DEVICES)
        val merged = LinkedHashMap<String, JsonElement>(others.orEmpty())
        merged.putAll(theirs)
        val own = if (from == null || from == me) {
            counts
        } else {
            merged[from] = counts
            theirs[me] as? JsonObject
        }
        merged.remove(me)
        return Restored(own, JsonObject(merged))
    }

    /**
     * The other devices' counts with this device's shared-era total moved in
     * under [LEGACY], or null when there is nothing to move. Null too when a
     * legacy entry is already there: the move happened, and what this device
     * holds now is its own typing since.
     */
    fun retire(own: JsonObject?, others: JsonObject?): JsonObject? {
        if (own == null || others?.containsKey(LEGACY) == true) return null
        return JsonObject(others.orEmpty() + (LEGACY to own))
    }

    /** Whether [key] in [section] is this device's own counts, which only this device changes. */
    fun owned(section: String, key: String, me: String): Boolean = section == SECTION_ID && key == me
}
