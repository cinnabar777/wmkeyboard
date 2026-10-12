package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.FindOptions

/** Which of the panel's two fields the keys type into. */
enum class FindReplaceField { FIND, REPLACE }

/** The panel's three toggles. */
enum class FindOption { CASE, WHOLE_WORD, REGEX }

/**
 * The Find and replace panel, opened from its toolbar tool or the selection
 * bar's Replace chip.
 *
 * Both fields are buffers of the keyboard's own, the way a plugin's boxes
 * are: while the panel is up, every keystroke edits [focusedText] and none
 * reaches the app. [matches] are ranges into the field text the service last
 * extracted, [current] the one selected in the field, or -1.
 */
data class FindReplaceUi(
    val query: String = "",
    val replacement: String = "",
    val focused: FindReplaceField = FindReplaceField.FIND,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
    val matches: List<IntRange> = emptyList(),
    val current: Int = -1,
    /** The count stopped at the matcher's cap. */
    val truncated: Boolean = false,
    /** A bad pattern, a timed-out one, or a field that cannot be addressed by offset. */
    val error: String? = null,
    /** The field reports offsets, so matches can be selected and replaced in place. */
    val addressable: Boolean = true,
    val searching: Boolean = false,
    /** How many rewrites the panel's Undo can put back. */
    val undoDepth: Int = 0,
    /** The [current] match in its line, drawn in the panel (#627); null with no current match. */
    val preview: MatchPreview? = null,
    /** Recent searches, newest first, offered while the Find field is empty (#627). */
    val history: List<String> = emptyList(),
) {
    val focusedText: String
        get() = if (focused == FindReplaceField.FIND) query else replacement

    fun options(): FindOptions = FindOptions(matchCase = caseSensitive, wholeWord = wholeWord, regex = regex)
}
