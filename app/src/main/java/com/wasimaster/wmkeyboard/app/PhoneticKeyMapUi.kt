package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.transliteration.KeyMapEntry
import com.wasimaster.wmkeyboard.core.transliteration.KeyMapWhere
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticKeyMap
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticKeyMaps

/**
 * The key map of [langId]'s phonetic layout as a fold on its language page:
 * vowels, consonants, then the joins and signs, one card each, and the docs
 * guide last for the rules a table cannot hold. Nothing when the language has
 * no map.
 */
@Composable
internal fun PhoneticKeyMapGroup(langId: String, onOpenGuide: ((String) -> Unit)?) {
    val map = PhoneticKeyMaps.forLanguage(langId) ?: return
    SettingsGroup(
        stringResource(R.string.languages_keymap_title),
        foldKey = "keymap",
        foldSummary = { stringResource(R.string.languages_keymap_summary) },
    ) {
        item { VowelCard(map) }
        item {
            // Avro joins the clusters Bangla writes; Hindi phonetic guesses, because
            // spoken Hindi drops the vowel that would say which.
            val note = if (langId == "hi") {
                R.string.languages_keymap_consonants_note_guessed
            } else {
                R.string.languages_keymap_consonants_note
            }
            KeyMapCard(stringResource(R.string.languages_keymap_consonants), note) {
                CellFlow(map.consonants) { KeyCell(it.text, it.keys) }
            }
        }
        item { SignCard(map.signs) }
        val url = phoneticGuideUrl(langId)
        if (url != null && onOpenGuide != null) {
            item {
                ActionRow(
                    R.string.languages_phonetic_guide_title,
                    subtitle = stringResource(R.string.languages_phonetic_guide_subtitle),
                    action = stringResource(R.string.languages_phonetic_guide_action),
                ) { onOpenGuide(url) }
            }
        }
    }
}

/**
 * The docs section with the key map for [langId]'s phonetic layout, or null
 * for a language that has no such section.
 */
internal fun phoneticGuideUrl(langId: String): String? = when (langId) {
    "bn" -> "$DOCS_URL/languages/bengali/#avro-key-map"
    "hi" -> "$DOCS_URL/languages/hindi/#hindi-phonetic-key-map"
    else -> null
}

@Composable
private fun VowelCard(map: PhoneticKeyMap) {
    KeyMapCard(stringResource(R.string.languages_keymap_vowels), R.string.languages_keymap_vowels_note) {
        CellFlow(map.vowels) { entry ->
            // The inherent vowel writes nothing after a consonant, so it has
            // no second form to show.
            val sign = entry.sign?.takeIf { it.isNotEmpty() }?.let(::onCarrier)
            KeyCell(if (sign == null) entry.text else "${entry.text}  $sign", entry.keys)
        }
    }
}

@Composable
private fun SignCard(signs: List<KeyMapEntry>) {
    KeyMapCard(stringResource(R.string.languages_keymap_signs), note = null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (entry in signs) SignRow(entry)
        }
    }
}

@Composable
private fun SignRow(entry: KeyMapEntry) {
    val where = entry.where?.let { stringResource(whereRes(it)) }
    val example = entry.example?.let { (roman, script) -> "$roman → $script" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            entry.keys.joinToString("  "),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(88.dp),
        )
        Text(
            // A backquote that only keeps two letters apart writes nothing.
            entry.text.takeIf { it.isNotEmpty() }?.let(::onCarrier) ?: "·",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            if (where != null) Text(where, style = MaterialTheme.typography.bodyMedium)
            if (example != null) {
                Text(
                    example,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KeyMapCard(title: String, note: Int?, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (note != null) {
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
private fun CellFlow(entries: List<KeyMapEntry>, cell: @Composable (KeyMapEntry) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (entry in entries) cell(entry)
    }
}

/** One letter over the keys that type it, read out as "k: ক". */
@Composable
private fun KeyCell(text: String, keys: List<String>) {
    val keyText = keys.joinToString("  ")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clearAndSetSemantics { contentDescription = "$keyText: $text" },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.titleLarge)
            Text(
                keyText,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * [mark] on a dotted circle when it is a combining mark, the way script
 * charts draw one: a kar or a hasant on its own either renders as a stray
 * blob or attaches to whatever glyph happens to sit before it.
 */
private fun onCarrier(mark: String): String {
    val type = Character.getType(mark.codePointAt(0))
    val combining = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    return if (combining) "◌$mark" else mark
}

private fun whereRes(where: KeyMapWhere): Int = when (where) {
    KeyMapWhere.AFTER_CONSONANT -> R.string.languages_keymap_after_consonant
    KeyMapWhere.ELSEWHERE -> R.string.languages_keymap_elsewhere
    KeyMapWhere.BEFORE_CONSONANT -> R.string.languages_keymap_before_consonant
    KeyMapWhere.BEFORE_VOWEL -> R.string.languages_keymap_before_vowel
    KeyMapWhere.AFTER_T -> R.string.languages_keymap_after_t
    KeyMapWhere.BETWEEN_CONSONANTS -> R.string.languages_keymap_between_consonants
    KeyMapWhere.WORD_START -> R.string.languages_keymap_word_start
}
