package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.keyman.KeyProcessor
import com.wasimaster.wmkeyboard.core.keyman.KeymanLayers
import com.wasimaster.wmkeyboard.core.keyman.KmxModifiers
import com.wasimaster.wmkeyboard.core.keyman.ProcessorKey
import com.wasimaster.wmkeyboard.core.keyman.ProcessorResult
import com.wasimaster.wmkeyboard.core.keyman.SavedContext
import com.wasimaster.wmkeyboard.core.keyman.SyncDecision
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer

/**
 * The decisions the Keyman seam makes, pulled out of [WMKeyboardService] so they
 * can be checked without an `InputConnection` — the same reason `ComposingResume`
 * exists, and for the same kind of logic: small, easy to get subtly wrong, and
 * impossible to exercise from an instrumented test at the rate a unit test can.
 */
object KeymanSeam {

    /**
     * The caret position the context describes after an edit is applied.
     *
     * Arithmetic, never a read: `onUpdateSelection` fires on every keystroke and
     * a `getTextBeforeCursor` in it would reintroduce exactly the binder
     * round-trip that `expectedSelStart` exists to avoid.
     */
    fun anchorAfter(anchor: Int, edit: ProcessorResult.Edit): Int =
        if (anchor < 0) anchor else anchor + edit.insert.length - edit.deleteBefore

    /**
     * Whether a selection report is the echo of our own edit rather than the
     * user or the app moving the caret.
     *
     * Only a collapsed caret exactly where the last edit left it counts. Anything
     * else marks the context stale, and stale resolves to a re-read at the next
     * keystroke rather than immediately — one read per caret move, none per key.
     */
    fun isOwnEcho(newSelStart: Int, newSelEnd: Int, anchor: Int): Boolean =
        anchor >= 0 && newSelStart == newSelEnd && newSelStart == anchor

    /**
     * The layer on screen, as its key in the layout's layers — what `&layer`
     * reads once [com.wasimaster.wmkeyboard.core.keyman.KeymanLayers.keymanId]
     * has turned it back into Keyman's name.
     *
     * Shift is state here, not a place, so a shifted letters page is Keyman's
     * `shift` layer whether or not the layout drew one of its own, and caps lock
     * is `caps` only where the layout has a caps page to show — KeymanWeb has no
     * caps lock on a touch layout that does not.
     */
    fun activeLayerKey(
        mode: LayoutMode,
        shift: ShiftState,
        namedLayer: String?,
        hasCapsGrid: Boolean,
    ): String = when (mode) {
        LayoutMode.SYMBOLS -> LayoutLayer.SYMBOLS.key
        LayoutMode.SYMBOLS_SHIFTED -> LayoutLayer.SYMBOLS_SHIFTED.key
        LayoutMode.NAMED -> namedLayer ?: LayoutLayer.LETTERS.key
        else -> when {
            shift == ShiftState.CAPS_LOCK && hasCapsGrid -> KeymanLayers.CAPS
            shift != ShiftState.OFF -> KeymanLayers.SHIFT
            else -> LayoutLayer.LETTERS.key
        }
    }

    /**
     * Modifiers a key is pressed with that its own grid does not already say.
     *
     * A key on a Keyman grid carries the modifiers of the layer it sits on, so
     * a key on the drawn `shift` page already matches `[SHIFT K_x]`. Only a
     * layout with no shift page of its own — shift then re-cases the letters
     * page rather than replacing it — needs shift and caps added at the press.
     *
     * Deliberately narrow otherwise: our soft keyboard has no ctrl or alt of
     * its own, and inventing them would let a rule written for `[CTRL K_A]`
     * fire on a plain `a`.
     */
    fun runtimeModifiers(mode: LayoutMode, shift: ShiftState, hasShiftGrid: Boolean): Int {
        if (mode != LayoutMode.LETTERS || hasShiftGrid) return 0
        var mask = 0
        if (shift != ShiftState.OFF) mask = mask or KmxModifiers.SHIFT
        if (shift == ShiftState.CAPS_LOCK) mask = mask or KmxModifiers.CAPS
        return mask
    }

    /**
     * The one edit that takes a multitap step back out of the field: what
     * turns the text behind the caret after [edits] back into [before], the
     * text there before them.
     *
     * Worked on the whole of [before] rather than on the engine's context
     * after the step, because that context is a window: a step that pushes
     * its front out would make the two disagree about where the text starts.
     * Null when an edit deletes more than [before] holds, which the engine
     * cannot do, and which would leave nothing to put back.
     */
    fun multitapUndo(before: String, edits: List<ProcessorResult.Edit>): ProcessorResult.Edit? {
        var text = before
        for (edit in edits) {
            if (edit.deleteBefore > text.length) return null
            text = text.dropLast(edit.deleteBefore) + edit.insert
        }
        var common = text.commonPrefixWith(before).length
        // Never split a surrogate pair: half of one is not a character to keep.
        if (common > 0 && Character.isHighSurrogate(before[common - 1])) common--
        return ProcessorResult.Edit(deleteBefore = text.length - common, insert = before.substring(common))
    }
}

/**
 * How much text behind the caret the engine is given when its context has to be
 * rebuilt. Keyman's own window, in UTF-16 code units.
 */
const val KEYMAN_CONTEXT_UNITS = 64

/**
 * One input session's worth of engine state: the processor, and how far the
 * context can be trusted.
 *
 * Holds no `Context` and touches no `InputConnection`, so the service can drive
 * it and a test can too. The service supplies the text behind the caret via a
 * lambda, which is what keeps the expensive read at the call site where it can
 * be skipped.
 */
class KeymanSession(val processor: KeyProcessor) {

    /** Caret position the cached context describes, or -1 when unknown. */
    var anchor: Int = -1
        private set

    /** True when the context may no longer match the field. */
    var stale: Boolean = true
        private set

    /** Set after the engine faults; the session is done and types nothing. */
    var disabled: Boolean = false
        private set

    fun markStale() {
        stale = true
    }

    fun reset(before: CharSequence, at: Int) {
        processor.resetContext(before)
        anchor = at
        stale = false
    }

    /**
     * Brings the context into line with the field if it has gone stale.
     *
     * [readBefore] is only called when it is actually needed, which is once per
     * caret move rather than once per keystroke.
     */
    fun syncIfNeeded(at: Int, readBefore: () -> CharSequence): SyncDecision? {
        if (!stale) return null
        val decision = processor.syncContext(readBefore())
        anchor = at
        stale = false
        return decision
    }

    /**
     * Runs one key. Returns null when the session is disabled, so the caller
     * falls back to the ordinary path.
     */
    fun process(key: ProcessorKey): ProcessorResult? {
        if (disabled) return null
        return when (val result = processor.process(key)) {
            is ProcessorResult.Failed -> {
                // A blown budget is deterministic, so retrying it would stall
                // every subsequent key. The session gives up and the layout goes
                // back to typing its own caps for the rest of the field.
                disabled = true
                null
            }
            else -> result
        }
    }

    /** Records an applied edit. Call only after the edit reached the field. */
    fun onEdited(edit: ProcessorResult.Edit) {
        anchor = KeymanSeam.anchorAfter(anchor, edit)
        tapEdits?.add(edit)
    }

    /** The context a multitap step started from; see [beginTap]. */
    private var tapBefore: SavedContext? = null

    /** The edits the step being recorded has made, in order. */
    private var tapEdits: MutableList<ProcessorResult.Edit>? = null

    /**
     * Starts recording one tap of a multitap run, from a context the caller
     * has already brought into line with the field. Null from a processor
     * that keeps no context: there is nothing to rewind a later tap to.
     */
    fun beginTap(): Boolean {
        val before = processor.saveContext() ?: return false
        tapBefore = before
        tapEdits = mutableListOf()
        return true
    }

    /**
     * Ends the recording [beginTap] started: what the tap typed, and what to
     * put back before the run's next tap. Null when nothing was recording.
     */
    fun endTap(): KeymanTap? {
        val before = tapBefore
        val edits = tapEdits
        tapBefore = null
        tapEdits = null
        if (before == null || edits == null) return null
        val after = processor.saveContext() ?: return null
        val undo = KeymanSeam.multitapUndo(before.visible, edits) ?: return null
        return KeymanTap(before, after, undo)
    }

    /**
     * Takes [tap] back out of the engine: its context goes back to what it was
     * before the tap, deadkeys included, as KeymanWeb rewinds a multitap to the
     * text before its first tap. Returns the edit that does the same to the
     * field, which the caller applies and then reports through [onEdited].
     *
     * Null, touching nothing, when the context no longer ends where [tap] left
     * it — the caret moved, or something else typed in between — so the tap
     * is not what sits behind the caret any more. [readBefore] is called only
     * when the context has gone stale, as in [syncIfNeeded].
     */
    fun takeBack(tap: KeymanTap, at: Int, readBefore: () -> CharSequence): ProcessorResult.Edit? {
        if (disabled) return null
        syncIfNeeded(at, readBefore)
        if (processor.saveContext() != tap.after) return null
        processor.restoreContext(tap.before)
        return tap.undo
    }

    /** Handles a selection report, marking the context stale unless it is ours. */
    fun onSelectionReported(newSelStart: Int, newSelEnd: Int) {
        if (!KeymanSeam.isOwnEcho(newSelStart, newSelEnd, anchor)) markStale()
    }
}

/**
 * One tap of a multitap run as the engine typed it: the context before and
 * after it, and the edit that takes it back out of the field. From
 * [KeymanSession.endTap].
 */
class KeymanTap(val before: SavedContext, val after: SavedContext, val undo: ProcessorResult.Edit)
