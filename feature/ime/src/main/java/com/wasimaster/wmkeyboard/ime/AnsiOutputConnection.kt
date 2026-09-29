package com.wasimaster.wmkeyboard.ime

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.wasimaster.wmkeyboard.core.transliteration.BijoyAnsi

/**
 * The field's connection while a Bengali layout writes ANSI (Bijoy-era text,
 * আ as `Av`; see [BijoyAnsi]). Every piece of Bengali the keyboard commits is
 * converted on its way into the field, whichever path sent it: a word off the
 * space bar, a strip pick, a glide, a dictated phrase, a paste from the
 * clipboard panel.
 *
 * The word being typed stays Unicode while it is composing and turns into
 * ANSI when it commits. ANSI puts glyphs in visual order (ি goes in front of
 * its consonant), so a word is only right once it is whole, and the keyboard's
 * composing machinery reads its own region back and has to find the text it
 * put there.
 *
 * A layout that commits letter by letter, with nothing composing, is handled
 * by keeping the Unicode of the Bengali run just written ([run]): each new
 * letter rewrites that run whole, so কি comes out `wK` and not `Kw`. The run is
 * only trusted while the field still ends with what was written for it.
 */
internal class AnsiOutputConnection(
    val base: InputConnection,
    val version: BijoyAnsi.Version,
) : InputConnectionWrapper(base, true) {

    /** Length of the live composing region the keyboard set, or -1 for none. */
    private var composingLength = -1

    /** The Bengali run last committed outside a composing region, in Unicode. */
    private var run = ""

    /** What [run] was written as. */
    private var runAnsi = ""

    /** The character in front of [run] when it started, for the word-start glyphs. */
    private var runBefore = ""

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null || !BijoyAnsi.needsConversion(text)) {
            forgetRun()
            composingLength = -1
            return super.commitText(text, newCursorPosition)
        }
        val unicode = text.toString()
        val replacing = composingLength
        if (replacing < 0 && newCursorPosition == 1 && extendRun(unicode)) return true
        val before = textBefore(replacing)
        val ansi = BijoyAnsi.convert(unicode, version, before)
        composingLength = -1
        val done = super.commitText(ansi, newCursorPosition)
        if (newCursorPosition == 1 && unicode.all(::isRunChar)) {
            run = unicode
            runAnsi = ansi
            runBefore = before
        } else {
            forgetRun()
        }
        return done
    }

    /**
     * Adds [unicode] to the run just written, rewriting the part of it that
     * changes. False when there is no run to add to, or the field no longer
     * ends with it (the caret moved, the app edited the text), and the text
     * then goes in on its own.
     */
    private fun extendRun(unicode: String): Boolean {
        if (run.isEmpty() || !unicode.all(::isRunChar) || run.length + unicode.length > MAX_RUN) return false
        if (super.getTextBeforeCursor(runAnsi.length, 0)?.toString() != runAnsi) {
            forgetRun()
            return false
        }
        val merged = run + unicode
        val ansi = BijoyAnsi.convert(merged, version, runBefore)
        val kept = runAnsi.commonPrefixWith(ansi).length
        super.beginBatchEdit()
        if (runAnsi.length > kept) super.deleteSurroundingText(runAnsi.length - kept, 0)
        super.commitText(ansi.substring(kept), 1)
        super.endBatchEdit()
        run = merged
        runAnsi = ansi
        return true
    }

    /**
     * The character in front of the text about to be committed: in front of
     * the composing region the commit replaces, when there is one. Empty at
     * the start of the field, and when the field will not say.
     */
    private fun textBefore(replacing: Int): String {
        val want = if (replacing >= 0) replacing + 1 else 1
        val read = super.getTextBeforeCursor(want, 0) ?: return ""
        return if (read.length == want) read.subSequence(0, 1).toString() else ""
    }

    private fun forgetRun() {
        run = ""
        runAnsi = ""
        runBefore = ""
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        forgetRun()
        composingLength = text?.length ?: -1
        return super.setComposingText(text, newCursorPosition)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        forgetRun()
        composingLength = (end - start).takeIf { it >= 0 } ?: -1
        return super.setComposingRegion(start, end)
    }

    /**
     * Leaves the region as the Unicode it holds. The keyboard finishes a
     * composition when the caret has already gone elsewhere, and ahead of a
     * delete counted in the characters it composed, and a conversion here
     * would land in the wrong place in the first case and break the count in
     * the second.
     */
    override fun finishComposingText(): Boolean {
        forgetRun()
        composingLength = -1
        return super.finishComposingText()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        forgetRun()
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        forgetRun()
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        forgetRun()
        return super.setSelection(start, end)
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        forgetRun()
        return super.sendKeyEvent(event)
    }

    override fun performContextMenuAction(id: Int): Boolean {
        forgetRun()
        return super.performContextMenuAction(id)
    }

    private companion object {
        /** A run longer than any word stops being rewritten whole. */
        const val MAX_RUN = 48

        /** A letter, sign or digit of the Bengali block, or a joiner: what a word is made of. */
        fun isRunChar(c: Char): Boolean = c in 'ঀ'..'৿' || c == '‌' || c == '‍'
    }
}
