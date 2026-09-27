package com.wasimaster.wmkeyboard.core.stickers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One sticker inside a user pack.
 *
 * [fileName] is always derived from [id] and the MIME type — never from
 * anything the user or an imported file supplied — so a hostile pack can
 * never point an entry outside its own pack directory.
 */
@Serializable
data class CustomSticker(
    val id: String,
    val fileName: String,
    /** image/webp or image/gif; see [StickerImage] for why nothing else survives. */
    val mime: String,
    /**
     * The sticker's title: what a screen reader calls it, and a word that
     * offers it while typing once it is typed out in full (see
     * [StickerTriggerIndex]).
     */
    val name: String = "",
    /**
     * Words, phrases and emoji the sticker is found by — in the panel's search
     * box, and while typing, where a keyword may be any shorter handle for it.
     *
     * Stored under `emojis`, the name this list had when it only held emoji
     * tags: every `.wmstickers` file and every pack an older build wrote says
     * that, and a Signal pack's per-sticker emoji arrives here as well.
     */
    @SerialName("emojis")
    val keywords: List<String> = emptyList(),
    val animated: Boolean = false,
    /** width/height, for grid cell sizing. Static stickers are always 1f. */
    val aspectRatio: Float = 1f,
    val addedAt: Long = 0L,
)

/** A user-created sticker pack: a name and an ordered list of stickers. */
@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val stickers: List<CustomSticker> = emptyList(),
    val createdAt: Long = 0L,
    /**
     * Where the pack was imported from, or blank for one the user built.
     * `signal:<pack id>` for a Signal pack, which is what lets the Signal
     * browser mark the packs already added. Never the pack key. `whatsapp`
     * for a pack read out of a `.wastickers` file or WhatsApp's own sticker
     * folder, which carry no id of their own.
     */
    val source: String = "",
) {
    companion object {
        const val MAX_SOURCE_LENGTH = 64

        fun signalSource(packId: String): String = "signal:$packId"

        fun whatsappSource(): String = "whatsapp"
    }
}

/**
 * Sticker bytes ready to be written to disk, as produced by [StickerImage].
 *
 * Lives here rather than next to the encoder so [StickerPackStore] and its
 * unit tests never have to touch android.graphics.
 */
data class ProcessedSticker(
    val bytes: ByteArray,
    val mime: String,
    val animated: Boolean,
    val aspectRatio: Float,
    /**
     * True when the source was an animation and these bytes are only its
     * first frame: an animated PNG whose frames could not be re-encoded. The
     * sticker is good, so this is a thing to tell the user, not a failure.
     */
    val flattened: Boolean = false,
) {
    // Identity equality is what callers want (these wrap a fresh byte array
    // each time); the data-class default would compare arrays by reference
    // anyway, so spell it out to silence the warning.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Why a sticker couldn't be added, so the caller can say something useful. */
sealed interface StickerAddResult {
    data class Added(val sticker: CustomSticker) : StickerAddResult
    data object PackMissing : StickerAddResult
    data object PackFull : StickerAddResult
    data object WriteFailed : StickerAddResult
}
