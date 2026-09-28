package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.text.Graphemes
import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes

/**
 * Fixed Brahmic-script layouts. Backspace removes a whole grapheme cluster —
 * consonant(s) joined by virama, plus vowel signs and modifiers — as one unit,
 * and a vowel key takes its contextual form.
 *
 * Bengali delegates to the existing, tested [BengaliGraphemes] so Probhat/Jatiya
 * behaviour is unchanged. Every other Indic script uses a general virama-aware
 * walk driven by the script's [ScriptDef.unicodeRange] and virama, so adding
 * Devanagari or Tamil is data, not code.
 */
class IndicClusterComposer(private val script: ScriptDef) : Composer {

    override val isClusterShaping: Boolean get() = true

    override fun deleteLength(before: CharSequence): Int = when (script.id) {
        ScriptId.BENGALI -> BengaliGraphemes.clusterDeleteLength(before)
        else -> clusterDeleteLength(before, script.unicodeRange, viramaFor(script.id))
    }

    override fun contextualForm(text: String, before: Char?): String = when (script.id) {
        ScriptId.BENGALI -> {
            val kar = text.singleOrNull()
            if (kar == null) {
                text
            } else {
                BengaliGraphemes.vowelKeyText(kar, BengaliGraphemes.vowelFormAfter(before)) ?: text
            }
        }
        // Other scripts type their vowel signs directly; no contextual form yet.
        else -> text
    }
}

/** The virama (halant) that joins consonants into conjuncts, per script. */
internal fun viramaFor(id: ScriptId): Char? = when (id) {
    ScriptId.DEVANAGARI -> '्'
    ScriptId.BENGALI -> '্'
    ScriptId.GURMUKHI -> '੍'
    ScriptId.GUJARATI -> '્'
    ScriptId.ORIYA -> '୍'
    ScriptId.TAMIL -> '்'
    ScriptId.TELUGU -> '్'
    ScriptId.KANNADA -> '್'
    ScriptId.MALAYALAM -> '്'
    ScriptId.SINHALA -> '්'
    ScriptId.MYANMAR -> '္'
    ScriptId.KHMER -> '្'
    // Meetei Mayek's Apun Iyek is a virama-equivalent killer stroke.
    ScriptId.MEETEI_MAYEK -> '꯭'
    // Thai and Lao stack no conjuncts; they fall back to grapheme deletion.
    else -> null
}

/**
 * Generic Brahmic cluster deletion: swallow trailing combining marks, then a
 * base, then any preceding (virama + base) pairs. Falls back to the ordinary
 * backspace rule ([Graphemes.backspaceLength]) when the text does not end in
 * [range]. Bengali does not use
 * this — it has its own tested path — so this is exercised by the scripts added
 * in Phase 5.
 */
internal fun clusterDeleteLength(text: CharSequence, range: IntRange, virama: Char?): Int {
    if (text.isEmpty()) return 0
    val last = text.length - 1
    fun inScript(c: Char) = c.code in range
    if (!inScript(text[last])) return Graphemes.backspaceLength(text)
    var i = last
    while (i >= 0 && inScript(text[i]) && isCombiningMark(text[i])) i--
    if (i < 0) return text.length
    if (virama != null) {
        // Only consume a (virama + base) pair when an in-script base actually
        // precedes the virama. Without this guard a dangling/leading virama
        // drives i to -1 (delete length > input) or swallows an unrelated
        // preceding character. Mirrors the Bengali path's consonant check.
        while (i - 1 >= 0 && text[i - 1] == virama && i - 2 >= 0 && inScript(text[i - 2])) {
            i -= 2
            while (i >= 0 && inScript(text[i]) && isCombiningMark(text[i])) i--
        }
    }
    return text.length - i
}

private fun isCombiningMark(c: Char): Boolean = when (Character.getType(c)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    -> true
    else -> false
}
