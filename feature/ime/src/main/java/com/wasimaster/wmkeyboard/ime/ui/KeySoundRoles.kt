package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.feedback.KeySoundRole
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction

/**
 * Which sound-pack role a key belongs to.
 *
 * A pack may carry a separate set of recordings per role, because a spacebar
 * with stabilisers under it genuinely does not sound like a letter key. Only
 * [com.wasimaster.wmkeyboard.core.feedback.KeySoundStyle.PACK] can act on this,
 * and only for a pack that filled the role — every other style, and every pack
 * that fills none, plays one sound for the whole board.
 *
 * The grouping is by *what the key sounds like under a finger*, not by what it
 * does. Emoji, language switch and the layer keys are all
 * [KeySoundRole.MODIFIER] because they sit in the same bottom-row furniture as
 * shift and ?123, and a pack that recorded that row recorded them together.
 * [KeyAction.ForwardDelete] joins backspace for the same reason.
 */
fun Key.keySoundRole(): KeySoundRole = when (action) {
    KeyAction.Space -> KeySoundRole.SPACE
    // A newline key is the enter key by another name as far as a pack is
    // concerned: same place on the board, same recording.
    KeyAction.Enter, KeyAction.Newline, KeyAction.EditorAction -> KeySoundRole.ENTER
    KeyAction.Delete, KeyAction.ForwardDelete -> KeySoundRole.DELETE
    KeyAction.Shift,
    KeyAction.Symbols,
    KeyAction.Letters,
    KeyAction.LanguageSwitch,
    KeyAction.InputMethodPicker,
    is KeyAction.SwitchInputMethod,
    KeyAction.Emoji,
    KeyAction.Numpad,
    KeyAction.Fn,
    is KeyAction.Mod,
    -> KeySoundRole.MODIFIER
    // Text, the raw-key-event keys, the notation-layout keys and anything a
    // newer build introduces all land on the default set. A role guessed wrong
    // is worse than the default: it plays a spacebar sample under a letter.
    else -> KeySoundRole.DEFAULT
}
