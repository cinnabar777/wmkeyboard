package com.wasimaster.wmkeyboard.core.keyman

/**
 * One keystroke as a rule engine sees it: an identity, not text.
 *
 * [modifiers] is a Keyman mask ([KmxFormat.K_SHIFTFLAG] and friends), not
 * Android's `META_*`. The two overlap in meaning and not in value, so the
 * conversion belongs at the call site where the platform is known.
 */
data class ProcessorKey(val vkey: Int, val modifiers: Int)

/**
 * The modifier bits a [ProcessorKey] carries.
 *
 * Keyman's own values, exposed because the host has to build the mask and the
 * on-disk format constants are internal to this module. They are deliberately
 * not Android's `META_*`: the two mean similar things and share no values, so a
 * host that passed `META_SHIFT_ON` straight through would set Keyman's
 * left-control bit instead.
 */
object KmxModifiers {
    const val LEFT_CTRL: Int = 0x0001
    const val RIGHT_CTRL: Int = 0x0002
    const val LEFT_ALT: Int = 0x0004
    const val RIGHT_ALT: Int = 0x0008
    const val SHIFT: Int = 0x0010
    const val CTRL: Int = 0x0020
    const val ALT: Int = 0x0040
    const val CAPS: Int = 0x0100
}

/** What one keystroke did. Never partially applied. */
sealed interface ProcessorResult {

    /**
     * The engine declined — Keyman's `emit_keystroke`. The host should type this
     * key exactly as it would with no engine loaded.
     */
    data object Declined : ProcessorResult

    /**
     * [deleteBefore] is UTF-16 units of **visible** text, already resolved past
     * any invisible deadkey markers, so it can go straight to
     * `deleteSurroundingText`. It is never a code-point count and never includes
     * a marker.
     *
     * An edit with nothing to delete, nothing to insert and no layer change is a
     * pure deadkey press: the host must issue no `InputConnection` call at all,
     * or the editor sees an empty transaction and some of them scroll for it.
     */
    data class Edit(
        val deleteBefore: Int,
        val insert: String,
        val nextLayer: String? = null,
        val alert: Boolean = false,
    ) : ProcessorResult {
        val isNoOp: Boolean get() = deleteBefore == 0 && insert.isEmpty() && nextLayer == null
    }

    /** A budget was blown or the interpreter faulted. The host disables the engine. */
    data class Failed(val fault: KeymanFault) : ProcessorResult
}

/**
 * The seam between a rule engine and the IME.
 *
 * Deliberately **not** a `Composer`. A `Composer` is a stateless
 * `String -> String` called after the key has already been flattened to text,
 * and its only context hook sees a single preceding character. A rule engine
 * needs the key's identity before flattening, owns sixty-four units of mutable
 * state including markers that must never reach the field, and answers with
 * "delete this many, insert this" rather than with a string. Widening `Composer`
 * to fit would hand fifteen existing implementations a member none of them can
 * answer.
 */
interface KeyProcessor {

    /**
     * Could any rule match this key at all? A cheap prefilter, and wrong only
     * ever in the direction of costing time: a false positive runs the engine
     * for nothing, a false negative would lose a keystroke, so implementations
     * must err towards true.
     */
    fun matches(vkey: Int, modifiers: Int): Boolean

    /** Rebuilds the context from [before], dropping every deadkey. */
    fun resetContext(before: CharSequence)

    /** Keeps the context if it still agrees with [before], else rebuilds it. */
    fun syncContext(before: CharSequence): SyncDecision

    fun process(key: ProcessorKey): ProcessorResult

    /**
     * Keyman's `begin NewContext`: the caret moved, or a field was entered.
     * Produces no text — only store changes and, possibly, a layer to show.
     */
    fun onNewContext(before: CharSequence): String?

    /**
     * Keyman's `begin PostKeystroke`, run after every keystroke once its edit and
     * any layer switch have been applied — KeymanWeb's order. Same contract as
     * [onNewContext]: layer only, never text, and the context is left exactly
     * as it was. [newLayer] and [oldLayer] are what `&newLayer` and `&oldLayer`
     * read: the layer the keystroke switched to and the one it left, both
     * empty when it switched nothing.
     */
    fun onPostKeystroke(newLayer: String = "", oldLayer: String = ""): String?

    /** Whether the keyboard has a `begin PostKeystroke` group at all. */
    val hasPostKeystroke: Boolean get() = false

    /** Whether the keyboard has a `begin NewContext` group at all. */
    val hasNewContext: Boolean get() = false

    /**
     * The virtual key a touch layout's own key name (`T_KOKAI`, `U_05AB`) stands
     * for in this keyboard's rules, or null when no rule names it. Keyman numbers
     * these from 256 in the order of the keyboard's key dictionary.
     */
    fun keyForName(name: String): Int? = null

    /**
     * Text the host typed on the engine's behalf — a key's default output after
     * the rules passed on it — appended to the context as it is, deadkeys and
     * all, the way KeymanWeb's default output lands in its text store.
     */
    fun onTextTyped(text: CharSequence) {
        // A processor that keeps no context has nothing to add it to.
    }

    /**
     * The context as it stands, deadkeys included, for [restoreContext]. Null
     * from a processor that keeps no context, which has nothing to put back.
     */
    fun saveContext(): SavedContext? = null

    /**
     * Puts back a context [saveContext] took — for a multitap step, which acts
     * as if it were the first thing typed since the state before the run's
     * first tap, deadkeys and all, as KeymanWeb's multitap does.
     */
    fun restoreContext(saved: SavedContext) {
        // A processor that keeps no context has nothing to restore.
    }

    /** True while a deadkey is pending, for the strip's pending-accent hint. */
    val deadKeyPending: Boolean

    /**
     * The touch layer now on screen, in Keyman's own names (`default`,
     * `shift`, `numeric`, ...), for rules written `if(&layer = 'shift')`.
     */
    fun setLayer(name: String) {
        // A processor with no layer-dependent rules has nothing to track.
    }
}

/**
 * A rule engine's context at one moment, from [KeyProcessor.saveContext].
 * Opaque to the host but for [visible], the text the field held behind the
 * caret at that moment, and equal to another only when both hold the same text
 * and the same deadkeys in the same places.
 */
class SavedContext internal constructor(internal val raw: String) {

    /** The text, deadkey markers taken out: what the field showed. */
    val visible: String by lazy { KeymanContext().apply { restore(this@SavedContext.raw) }.visible() }

    override fun equals(other: Any?): Boolean = other is SavedContext && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = "SavedContext(${raw.length}u)"
}
