package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** The protocol version this engine speaks, and the oldest it will talk to. */
const val KDE_PROTOCOL_VERSION = 8
const val KDE_PROTOCOL_MIN = 7

enum class KdeDeviceType(val wire: String) {
    DESKTOP("desktop"), LAPTOP("laptop"), PHONE("phone"), TABLET("tablet"), TV("tv");

    companion object {
        /** Unknown strings read as a desktop, which is what the Android client does. */
        fun fromWire(value: String?): KdeDeviceType = when (value?.lowercase()) {
            "laptop" -> LAPTOP
            "phone", "smartphone" -> PHONE
            "tablet" -> TABLET
            "tv" -> TV
            else -> DESKTOP
        }
    }
}

/** What a device says about itself in its identity packet. */
data class KdeDeviceInfo(
    val id: String,
    val name: String,
    val type: KdeDeviceType,
    val protocolVersion: Int = KDE_PROTOCOL_VERSION,
    val incoming: Set<String> = emptySet(),
    val outgoing: Set<String> = emptySet(),
) {
    /**
     * The identity packet. [tcpPort] goes on the UDP announcement; the two
     * target fields go on the plaintext line a dialler opens a TCP connection
     * with (protocol 8: the listener checks them, so a spoofed UDP source
     * cannot point a third party's connection at it).
     */
    fun toPacket(tcpPort: Int? = null, targetId: String? = null, targetVersion: Int? = null): KdePacket =
        kdePacket(KdeTypes.IDENTITY) {
            put("deviceId", id)
            put("deviceName", name)
            put("deviceType", type.wire)
            put("protocolVersion", protocolVersion)
            put("incomingCapabilities", JsonArray(incoming.sorted().map(::JsonPrimitive)))
            put("outgoingCapabilities", JsonArray(outgoing.sorted().map(::JsonPrimitive)))
            if (tcpPort != null) put("tcpPort", tcpPort)
            if (targetId != null) put("targetDeviceId", targetId)
            if (targetVersion != null) put("targetProtocolVersion", targetVersion)
        }

    companion object {
        /**
         * Reads an identity packet, or null when it is not one a link may be
         * built on: wrong type, a device id outside the agreed alphabet, a name
         * with nothing left after sanitising, or a protocol we do not speak.
         */
        fun fromPacket(packet: KdePacket): KdeDeviceInfo? {
            if (packet.type != KdeTypes.IDENTITY) return null
            val id = packet.string("deviceId") ?: return null
            if (!KdeDeviceIds.isValid(id)) return null
            val name = KdeDeviceNames.sanitize(packet.string("deviceName").orEmpty())
            if (name.isEmpty()) return null
            val version = packet.int("protocolVersion") ?: return null
            if (version < KDE_PROTOCOL_MIN) return null
            return KdeDeviceInfo(
                id = id,
                name = name,
                type = KdeDeviceType.fromWire(packet.string("deviceType")),
                // A newer peer is spoken to in our dialect; it knows how.
                protocolVersion = version.coerceAtMost(KDE_PROTOCOL_VERSION),
                incoming = packet.strings("incomingCapabilities").toSet(),
                outgoing = packet.strings("outgoingCapabilities").toSet(),
            )
        }
    }
}

object KdeDeviceIds {
    private val VALID = Regex("^[a-zA-Z0-9_-]{32,38}$")

    /** A UUIDv4 without its hyphens: what the specification asks new ids to be. */
    fun generate(): String = UUID.randomUUID().toString().replace("-", "")

    /** Hyphens and underscores are accepted for the ids older clients minted. */
    fun isValid(id: String): Boolean = VALID.matches(id)
}

object KdeDeviceNames {
    const val MAX_LENGTH = 32
    private val FORBIDDEN = Regex("[\"',;:.!?()\\[\\]<>]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * A name every client will show as written: the forbidden punctuation
     * dropped, trimmed, at most 32 characters. Peers sanitise rather than
     * reject, but a name that sanitises to nothing invalidates the whole
     * identity packet, so callers must fall back to something non-blank.
     */
    fun sanitize(raw: String): String = cut(raw.replace(FORBIDDEN, "").replace(WHITESPACE, " ").trim(), MAX_LENGTH)

    /**
     * [base] with the app's name after it, so a phone that also runs the KDE
     * Connect app shows up on the computer as two names that can be told
     * apart. The long tag when it fits in [MAX_LENGTH], else the short one,
     * else [base] cut to make room. Brackets are forbidden, hence the hyphen.
     */
    fun branded(base: String, tag: String = TAG, shortTag: String = SHORT_TAG): String {
        val name = sanitize(base)
        if (name.isEmpty()) return tag
        for (t in listOf(tag, shortTag)) {
            val full = "$name$SEPARATOR$t"
            if (full.length <= MAX_LENGTH) return full
        }
        return cut(name, MAX_LENGTH - SEPARATOR.length - shortTag.length) + SEPARATOR + shortTag
    }

    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        // Never cut a surrogate pair in half.
        var end = max
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trim()
    }

    const val TAG = "WM Keyboard"
    const val SHORT_TAG = "WMK"
    private const val SEPARATOR = " - "
}

/**
 * The packet types this engine announces. A peer silently drops any type that
 * is missing from these lists, so a plugin added to the engine and forgotten
 * here simply never works — [KdeConnectEngine] builds the lists from its
 * plugins instead of keeping a second copy.
 */
data class KdeCapabilities(val incoming: Set<String>, val outgoing: Set<String>)
