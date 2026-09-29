package com.wasimaster.wmkeyboard.core.keyman

import com.wasimaster.wmkeyboard.core.layout.ConvertedLayout
import com.wasimaster.wmkeyboard.core.layout.ForeignSource
import com.wasimaster.wmkeyboard.core.layout.LayoutMessage
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.keyman.R

/**
 * Reads a `.keyman-touch-layout` a user picked, and hands back the same
 * [ConvertedLayout] the FlorisBoard and HeliBoard readers produce.
 *
 * Lives here rather than beside them in `ForeignLayouts` for a dependency
 * reason: the converter is in this module and this module depends on
 * `:core:language`, so the reverse call is not available. The import screen
 * dispatches instead, which costs one sniff and keeps the arrow pointing one
 * way.
 *
 * Imported layouts get **no** [LayoutSpec.keyman] binding. A binding says "the
 * rules for this keyboard are on the device", and for a file someone picked out
 * of their downloads they are not. The grid works and each key types its own
 * cap, which is stated in the import notes rather than left to be discovered.
 */
object KeymanImport {

    /**
     * Whether [text] is a Keyman touch layout rather than some other JSON.
     *
     * Checked by structure, not by file name: the format has no extension of its
     * own that a file manager reliably preserves, and the platform keys at the
     * root are unambiguous. FlorisBoard's layouts are an array, or an object
     * keyed `arrangement`/`rows`/`keys`/`layout`, so nothing overlaps.
     */
    fun looksLikeTouchLayout(text: String): Boolean =
        when (val parsed = KeymanTouchLayoutReader.parse(text)) {
            is KeymanResult.Success -> parsed.value.preferred() != null
            is KeymanResult.Failure -> false
        }

    /** Converts [text], or null when it is not a usable touch layout. */
    fun convert(text: String, name: String): ConvertedLayout? {
        val doc = (KeymanTouchLayoutReader.parse(text) as? KeymanResult.Success)?.value ?: return null
        val id = name.substringAfterLast('/').substringBefore('.').ifBlank { "keyman" }
        val converted = (TouchLayoutConverter.convert(doc, id, id) as? KeymanResult.Success)?.value
            ?: return null

        return ConvertedLayout(
            // The binding is deliberately absent; see the class comment.
            layout = converted.layout.copy(langId = ""),
            notes = notesFor(converted),
            unmapped = emptyList(),
            guessedLangId = guessLanguage(converted.layout),
            source = ForeignSource.KEYMAN_TOUCH_LAYOUT,
        )
    }

    private fun notesFor(converted: ConvertedKeymanLayout): List<LayoutMessage> = buildList {
        val report = converted.report
        // Always first: it is the one that changes what the user gets when they
        // type, rather than what the grid looks like.
        add(LayoutMessage(stringRes = R.string.core_keyman_rules_missing))
        if (report.droppedMultitaps > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_keyman_multitap_unconverted,
                    quantity = report.droppedMultitaps,
                    args = listOf(report.droppedMultitaps),
                ),
            )
        }
        if (report.droppedDiagonalFlicks > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_keyman_diagonal_flicks_dropped,
                    quantity = report.droppedDiagonalFlicks,
                    args = listOf(report.droppedDiagonalFlicks),
                ),
            )
        }
        val removed = report.droppedSpecialKeys + report.droppedBlankKeys
        if (removed > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_keyman_special_keys_dropped,
                    quantity = removed,
                    args = listOf(removed),
                ),
            )
        }
        if (report.keysWithoutVirtualKey > 0) {
            add(
                LayoutMessage(
                    pluralsRes = R.plurals.core_keyman_keys_type_their_labels,
                    quantity = report.keysWithoutVirtualKey,
                    args = listOf(report.keysWithoutVirtualKey),
                ),
            )
        }
        addAll(converted.repairNotes)
    }

    /**
     * A language for the grid, from the script its keys are mostly in.
     *
     * Only a starting point for the picker the import dialog puts in front of
     * the user, which is why a wrong guess is cheap here and why this does not
     * try harder. ASCII is ignored: nearly every grid carries some, and counting
     * it would answer "Latin" for all of them.
     */
    private fun guessLanguage(spec: LayoutSpec): String {
        val counts = mutableMapOf<ScriptId, Int>()
        for (layer in spec.layers.values) {
            for (row in layer.rows) {
                for (key in row) {
                    for (ch in (key.output ?: key.label)) {
                        if (ch.code < 0x80 || !ch.isLetter()) continue
                        val script = ScriptRegistry.all.firstOrNull { it.contains(ch.code) }
                            ?: continue
                        counts.merge(script.id, 1, Int::plus)
                    }
                }
            }
        }
        val dominant = counts.maxByOrNull { it.value }?.key ?: return "en"
        return LanguageRegistry.all.firstOrNull { it.script == dominant }?.id ?: "en"
    }
}
