package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.TextEdit

/**
 * The arithmetic between the matcher's ranges and the field's selection,
 * kept out of the service so it has a test.
 */
object FindReplaceGlue {

    /** The match that is exactly the selection [selStart]..[selEnd], or -1. */
    fun currentMatchIndex(matches: List<IntRange>, selStart: Int, selEnd: Int): Int =
        matches.indexOfFirst { it.first == selStart && it.last + 1 == selEnd }

    /**
     * The match [delta] steps from [current], wrapping. With no current match
     * the selection decides: forward goes to the first match at or after it,
     * back to the last one before it, each wrapping when there is none.
     */
    fun stepIndex(matches: List<IntRange>, current: Int, selStart: Int, delta: Int): Int {
        if (matches.isEmpty()) return -1
        if (current >= 0) return Math.floorMod(current + delta, matches.size)
        return if (delta > 0) {
            matches.indexOfFirst { it.first >= selStart }.let { if (it < 0) 0 else it }
        } else {
            matches.indexOfLast { it.first < selStart }.let { if (it < 0) matches.lastIndex else it }
        }
    }

    /**
     * The matches left after the one at [replaced] was swapped for text
     * [delta] characters longer: it is gone, and everything after it moved.
     */
    fun shiftMatches(matches: List<IntRange>, replaced: Int, delta: Int): List<IntRange> {
        val anchor = matches.getOrNull(replaced)?.first ?: return matches
        return matches.filterIndexed { index, _ -> index != replaced }
            .map { if (it.first > anchor) (it.first + delta)..(it.last + delta) else it }
    }

    /** Where [offset] in the text before [edits] sits in the text after them. */
    fun afterOffset(edits: List<TextEdit>, offset: Int): Int =
        offset + edits.filter { it.start < offset }.sumOf { it.text.length - (it.end - it.start) }

    /**
     * The current match in its surroundings, for the panel's preview line
     * (#627): up to [radius] characters either side, cut at a line break so
     * the line reads as the line the match is on, with an ellipsis where text
     * was left out. [MatchPreview.match] is where the match sits in it.
     */
    fun preview(text: String, range: IntRange, radius: Int = 24): MatchPreview? {
        if (range.first < 0 || range.last >= text.length || range.first > range.last) return null
        var start = (range.first - radius).coerceAtLeast(0)
        var end = (range.last + 1 + radius).coerceAtMost(text.length)
        text.lastIndexOf('\n', range.first - 1).let { if (it >= start) start = it + 1 }
        text.indexOf('\n', range.last + 1).let { if (it in 0 until end) end = it }
        val lead = if (start > 0 && text[start - 1] != '\n') "…" else ""
        val tail = if (end < text.length && text[end] != '\n') "…" else ""
        // A match that spans lines is drawn on one; the breaks become a mark.
        val body = text.substring(start, end).replace('\n', '⏎')
        val matchStart = lead.length + range.first - start
        return MatchPreview(lead + body + tail, matchStart until matchStart + range.last + 1 - range.first)
    }
}

/** One line of text around a match, and where the match sits in it. */
data class MatchPreview(val text: String, val match: IntRange)
