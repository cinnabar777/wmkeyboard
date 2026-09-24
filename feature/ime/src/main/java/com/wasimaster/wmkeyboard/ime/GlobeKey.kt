package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.ClipboardKeyAction
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.LongPressLetterActions

/**
 * Whether a 🌐 tap at [now] is ignored because a key typed into the field at
 * [lastTypedAt], less than [guardMs] before it. The globe sits between the
 * comma and the spacebar, and a thumb reaching for either mid-word lands on it;
 * a deliberate switch comes after a pause. [guardMs] of 0 or less is off.
 *
 * Uptime millis both, so the clock cannot run backwards between them; a
 * [lastTypedAt] of 0 (nothing typed yet) is never within the window of a real
 * uptime, and a clock earlier than [lastTypedAt] is not trusted to guard.
 */
internal fun globeTapGuarded(now: Long, lastTypedAt: Long, guardMs: Int): Boolean {
    if (guardMs <= 0 || lastTypedAt <= 0L) return false
    val since = now - lastTypedAt
    return since in 0 until guardMs
}

/**
 * Whether a key puts something into the field or takes it out, which is what
 * "straight out of typing" means for [globeTapGuarded]. Shift, the mode keys
 * and the tools change the board, not the text, and a 🌐 tap after one of them
 * is as deliberate as any.
 */
internal fun KeyAction.typesIntoField(): Boolean = when (this) {
    KeyAction.Text, is KeyAction.KeymanKey, KeyAction.Space, KeyAction.Delete,
    KeyAction.ForwardDelete, KeyAction.Enter, KeyAction.Newline, KeyAction.EditorAction,
    -> true
    else -> false
}

/**
 * The action a drag from 🌐 onto [target] runs, or null when [target] carries
 * none: the key that types one of [letters]' six letters (`a c v x z y` unless
 * rebound) runs the same select-all, copy, paste, cut, undo or redo its long
 * press offers.
 *
 * Read off the letter the key types rather than its place on the grid, which
 * is how the long press finds its keys too: an AZERTY `a` is where QWERTY has
 * `q`, and it is still the `a` that selects everything. On a board with no
 * Latin letters the six are whatever [LongPressLetterActions.letters] says.
 */
internal fun globeDragAction(target: Key, letters: LongPressLetterActions): ClipboardKeyAction? {
    if (target.action != KeyAction.Text) return null
    val index = letters.actionFor(target.output ?: target.label)
    return ClipboardKeyAction.entries.getOrNull(index)
}

/** Whether a drag off this key is the 🌐 shortcut drag, when it is switched on. */
internal fun Key?.startsGlobeDrag(enabled: Boolean): Boolean =
    enabled && this?.action == KeyAction.LanguageSwitch
