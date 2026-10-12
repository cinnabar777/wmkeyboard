package com.wasimaster.wmkeyboard.ime

/**
 * Opening bracket → its closer, for the auto-close rule
 * (`TextEditingSettings.autoCloseBrackets`): typing the opener types the
 * closer behind the caret as well, and the caret stays between them.
 *
 * Brackets only. Quotes are deliberately absent — the apostrophe in "don't"
 * and the quote key that closes a quotation share their character with the
 * opener, so a keyboard that closed them would be wrong more often than
 * right, and [WRAP_PAIRS]-style symmetric pairs have no way to tell an
 * opening `"` from a closing one without reading the whole line. `<` is
 * absent for the same kind of reason: outside code it is a less-than sign,
 * and "5 <" is nobody opening a bracket.
 */
val AUTO_CLOSE_PAIRS: Map<Char, String> = mapOf(
    '(' to ")", '[' to "]", '{' to "}",
    '（' to "）", '［' to "］", '｛' to "｝",
    '〈' to "〉", '《' to "》", '⟨' to "⟩", '⟦' to "⟧",
    '「' to "」", '『' to "』", '｢' to "｣",
    '【' to "】", '〔' to "〕", '〖' to "〗", '〘' to "〙", '〚' to "〛",
)

/** The closers of [AUTO_CLOSE_PAIRS], for the "type over it" test below. */
val AUTO_CLOSE_CLOSERS: Set<Char> = AUTO_CLOSE_PAIRS.values.mapTo(HashSet()) { it[0] }

/**
 * The closer to type behind the caret when [typed] lands, or null when this
 * keypress closes nothing. [after] is the text in front of the caret.
 *
 * A bracket typed in front of a word is being put *around* that word by hand —
 * "(" before "world" is on its way to "(world)" — and a closer dropped into the
 * middle of it would have to be deleted again, so the rule stands down. Only a
 * letter or a digit counts as "a word": a space, the end of the text, a mark or
 * another bracket all leave room for the pair.
 */
fun autoCloseCloserFor(typed: Char, after: CharSequence?): String? {
    val closer = AUTO_CLOSE_PAIRS[typed] ?: return null
    val next = after?.firstOrNull() ?: return closer
    return if (next.isLetterOrDigit()) null else closer
}

/**
 * Whether pressing [typed] should step the caret over the closer already in
 * front of it rather than typing a second one.
 *
 * Without this, the habit of typing both halves turns the pair the keyboard
 * just made into "())". Deliberately stateless — it does not ask whether *this*
 * closer was the keyboard's — because the alternative is tracking caret-relative
 * positions through every edit the app makes behind our back, and the cost of
 * being wrong is small and visible: the caret ends up past a closer that was
 * already there, which is where typing one would have put it anyway.
 */
fun typesOverCloser(typed: Char, after: CharSequence?): Boolean =
    typed in AUTO_CLOSE_CLOSERS && after?.firstOrNull() == typed

/**
 * Whether one backspace should take out both halves of an empty pair: the
 * caret sits between an opener and its own closer, which is exactly the state
 * auto-close leaves behind, so the press that undoes the opener undoes the
 * closer with it. A pair with anything inside it is left alone.
 */
fun deletesEmptyPair(before: CharSequence, after: CharSequence?): Boolean {
    val opener = before.lastOrNull() ?: return false
    val closer = AUTO_CLOSE_PAIRS[opener] ?: return false
    return after?.firstOrNull() == closer[0]
}

/**
 * The closing bracket that the automatic space after punctuation follows, the
 * way it follows a comma: "(see below)" then a word reads "(see below) word"
 * (#617). Only `)`. A `]` is as often half of a Markdown link, where
 * "[text](url)" must not come apart at the space, and `}` is code.
 */
const val SPACED_CLOSER: Char = ')'

/**
 * Whether a [SPACED_CLOSER] landing in front of [after] — the text after the
 * caret — ends the run of text it closes, so a space may follow it. Anything
 * but a blank, a line break or the end of the text means the closer is landing
 * mid-text: in front of another closer ("((a))"), a mark ("(a).") or a word,
 * none of which wants a space pushed in.
 */
fun closerTakesSpace(after: CharSequence?): Boolean =
    after.isNullOrEmpty() || after[0].isWhitespace()
