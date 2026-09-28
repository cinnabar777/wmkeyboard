package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What an automatic backup is called, and which of them rotation may delete.
 *
 * Pure on purpose. The destination is a folder the user picked, which may well
 * be Downloads, and [rotation] hands its answer straight to
 * [BackupSink.delete] — so the rules about what counts as ours are the only
 * thing standing between a rotation pass and somebody's tax return. They are
 * kept here, away from any [android.content.ContentResolver], so they can be
 * tested.
 */
object AutoBackupNaming {

    /**
     * Distinct from the `wmkeyboard-backup-` that a hand-made export uses, so
     * rotation can never eat a file the user made deliberately.
     */
    const val PREFIX = "wmkeyboard-auto-"

    /** A backup that is still being written, or was being written when we died. */
    const val PART_SUFFIX = ".part"

    /**
     * [PART_SUFFIX] for a WebDAV upload. Nextcloud 30 and later refuse any
     * name ending in `.part` or `.filepart` with a 400, because they use those
     * for their own uploads, so a backup staged under [PART_SUFFIX] never
     * reached the server at all (#411).
     */
    const val UPLOAD_SUFFIX = ".upload"

    /**
     * [Locale.US] because a Thai-Buddhist locale stamps 2569 for 2026, and a
     * name whose job is to sort by date has to mean the same thing on every
     * device. Same format and same reason as the manual export's name.
     */
    private const val STAMP_FORMAT = "yyyyMMdd-HHmmss"

    /** Longest device part a name carries. A name is not the place for a paragraph. */
    private const val DEVICE_MAX = 32

    /**
     * The name for a backup taken at [stampMs].
     *
     * [installId] and [device] say whose backup it is, as
     * `wmkeyboard-auto-<stamp>_<installId>_<device>.<ext>`: one destination can
     * hold several phones' backups, and rotation must only ever count its own.
     * Both are optional so a name without them, which is what every backup
     * before them was called, still parses.
     *
     * [zone] is a parameter only so tests are not at the mercy of the machine
     * running them; every caller in the app takes the default.
     */
    fun name(
        stampMs: Long,
        encrypted: Boolean,
        zone: TimeZone = TimeZone.getDefault(),
        installId: String = "",
        device: String = "",
    ): String {
        val stamp = SimpleDateFormat(STAMP_FORMAT, Locale.US)
            .apply { timeZone = zone }
            .format(Date(stampMs))
        val extension =
            if (encrypted) ConfigBackup.ENCRYPTED_FILE_EXTENSION else ConfigBackup.FILE_EXTENSION
        val owner = buildString {
            if (installId.isNotEmpty()) {
                append('_').append(installId)
                val slug = deviceSlug(device)
                if (slug.isNotEmpty()) append('_').append(slug)
            }
        }
        return "$PREFIX$stamp$owner.$extension"
    }

    /**
     * [label] reduced to what every destination accepts in a name: ASCII
     * letters, digits and single hyphens. "Wasi's Pixel 8 (work)" becomes
     * `Wasi-s-Pixel-8-work`. A label with nothing left, a name in another
     * script for example, gives an empty slug and the name carries only the id.
     */
    fun deviceSlug(label: String): String =
        label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(DEVICE_MAX).trimEnd('-')

    /** What a name says about the backup inside it. */
    data class Parsed(
        /** When it was taken, or null if the stamp does not parse. */
        val stampMs: Long?,
        /** The installation that wrote it, or null for a backup from before ids. */
        val installId: String?,
        /** The device slug, hyphens and all, or null when the name has none. */
        val device: String?,
        val encrypted: Boolean,
    )

    private val SHAPE = Regex(
        "^" + Regex.escape(PREFIX) +
            "(\\d{8}-\\d{6})(?:_([0-9a-f]{8})(?:_([A-Za-z0-9-]{1,$DEVICE_MAX}))?)?" +
            "\\.(" + Regex.escape(ConfigBackup.FILE_EXTENSION) + "|" +
            Regex.escape(ConfigBackup.ENCRYPTED_FILE_EXTENSION) + ")$",
    )

    /**
     * Reads [displayName] back into its parts, or null when it is not a name
     * this app writes. [zone] as for [name].
     */
    fun parse(displayName: String, zone: TimeZone = TimeZone.getDefault()): Parsed? {
        val match = SHAPE.matchEntire(displayName) ?: return null
        val (stamp, id, device, extension) = match.destructured
        val stampMs = runCatching {
            SimpleDateFormat(STAMP_FORMAT, Locale.US).apply {
                timeZone = zone
                isLenient = false
            }.parse(stamp)?.time
        }.getOrNull()
        return Parsed(
            stampMs = stampMs,
            installId = id.ifEmpty { null },
            device = device.ifEmpty { null },
            encrypted = extension == ConfigBackup.ENCRYPTED_FILE_EXTENSION,
        )
    }

    /**
     * Whether [displayName] is a finished automatic backup of ours.
     *
     * Deliberately strict. A file we do not recognise is left alone forever,
     * which costs nothing; a file we wrongly claim is deleted.
     */
    fun isOurs(displayName: String): Boolean =
        displayName.startsWith(PREFIX) &&
            !displayName.endsWith(PART_SUFFIX) &&
            !displayName.endsWith(UPLOAD_SUFFIX) &&
            (
                displayName.endsWith(".${ConfigBackup.FILE_EXTENSION}") ||
                    displayName.endsWith(".${ConfigBackup.ENCRYPTED_FILE_EXTENSION}")
                )

    /**
     * Whether a sink's listing should return [displayName]: an automatic
     * backup or a sync file. Every caller then takes the kind it wants;
     * rotation and restore use [isOurs], sync uses
     * [com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming.isOurs].
     */
    fun isListed(displayName: String): Boolean =
        isOurs(displayName) ||
            com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming.isOurs(displayName)

    /** Whether [displayName] is one of our half-written files, backup or sync, ours to sweep. */
    fun isPart(displayName: String): Boolean =
        (displayName.startsWith(PREFIX) ||
            displayName.startsWith(com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming.PREFIX)) &&
            (displayName.endsWith(PART_SUFFIX) || displayName.endsWith(UPLOAD_SUFFIX))

    /**
     * The entries to delete so that at most [keep] remain, oldest first.
     *
     * Sorted by [SinkEntry.modifiedAtMs] with the name as tiebreaker, because
     * some providers report a coarse modified time and some report none at all
     * — the name embeds the second, so it is the better key of the two whenever
     * the times collide.
     *
     * [keep] is floored at 1. A rotation that empties the folder is never what
     * the user meant, whatever they typed in the box.
     *
     * With an [installId], only that installation's backups count, plus the
     * ones written before names carried an owner: those can only have come
     * from an install of this app that rotated them already, and without a
     * claim they would otherwise sit there forever. Another phone's backups
     * are never touched, however many there are.
     */
    fun rotation(entries: List<SinkEntry>, keep: Int, installId: String? = null): List<SinkEntry> {
        val mine = if (installId == null) {
            entries
        } else {
            entries.filter { entry ->
                val owner = parse(entry.name)?.installId
                owner == null || owner == installId
            }
        }
        val survivors = keep.coerceAtLeast(1)
        if (mine.size <= survivors) return emptyList()
        val oldestFirst = mine.sortedWith(compareBy({ it.modifiedAtMs }, { it.name }))
        return oldestFirst.take(mine.size - survivors)
    }
}
