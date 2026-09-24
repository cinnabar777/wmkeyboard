package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.BlacklistScope
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.DictionarySort
import com.wasimaster.wmkeyboard.core.settings.DictionarySortKey
import com.wasimaster.wmkeyboard.core.settings.LEARNED_CORRECTIONS_FILE
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sortDictionaryWords
import com.wasimaster.wmkeyboard.core.prediction.CorrectionMemory
import com.wasimaster.wmkeyboard.core.prediction.PendingLearn
import com.wasimaster.wmkeyboard.core.prediction.SystemUserDictionary
import com.wasimaster.wmkeyboard.core.prediction.WordKey
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.prediction.WordRanks
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import androidx.compose.foundation.lazy.rememberLazyListState

// ---- personal dictionary ----

/**
 * Word count past which the personal dictionary grows a search field. Below
 * it the list is short enough to scan, and a search box would be furniture.
 */
private const val DICTIONARY_SEARCH_THRESHOLD = 12
/** Rows the word lists draw per page; the next page is a tap away. */
private const val WORD_LIST_PAGE = 100
/** The "Show N more" row at the foot of a paged word list. */
@Composable
private fun ShowMoreWordsRow(remaining: Int, onClick: () -> Unit) {
    WmRow(
        title = pluralStringResource(R.plurals.backup_word_list_show_more, remaining, remaining),
        icon = Icons.Outlined.ExpandMore,
        onClick = onClick,
    )
}
/**
 * The learned-words file, edited directly from the settings app. Every
 * change bumps the DataStore lexicon version so the IME (which holds its
 * own in-memory copy) reloads from disk instead of clobbering the edit.
 */
@Composable
internal fun DictionarySettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val file = remember { java.io.File(context.filesDir, "learning/user_lexicon.json") }
    // UserLexicon's constructor reads and JSON-parses the whole learned-words
    // file, so it (and every save) runs on Dispatchers.IO, never in composition
    // or on a click handler. The list draws empty for a moment then fills in.
    var lexicon by remember { mutableStateOf<UserLexicon?>(null) }
    var words by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    // Words whose capitals are pinned (#100), by the spelling the row shows.
    var pinned by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Words the user added themselves (#164), the same way.
    var added by remember { mutableStateOf<Set<String>>(emptySet()) }
    // When each word joined the dictionary (#194), for the date-added order.
    var born by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    // The rank adjustments made from the keyboard's word card (#99): its own
    // file, read the same way, listed under the words so they can be undone.
    val ranksFile = remember { java.io.File(context.filesDir, "learning/word_ranks.json") }
    var ranks by remember { mutableStateOf<WordRanks?>(null) }
    var rankEntries by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var showTidy by remember { mutableStateOf(false) }
    // The row being edited (#47): its spelling and weight, as they are now.
    var editing by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var androidPicker by remember { mutableStateOf<AndroidWordPicker?>(null) }

    fun pinnedIn(lex: UserLexicon, all: List<Pair<String, Int>>): Set<String> =
        all.mapNotNullTo(HashSet()) { (word, _) -> word.takeIf { lex.isCasePinned(it) } }
    fun addedIn(lex: UserLexicon, all: List<Pair<String, Int>>): Set<String> =
        all.mapNotNullTo(HashSet()) { (word, _) -> word.takeIf { lex.isAddedByHand(it) } }
    fun bornIn(lex: UserLexicon, all: List<Pair<String, Int>>): Map<String, Long> =
        all.mapNotNull { (word, _) -> lex.addedGeneration(word)?.let { word to it } }.toMap()

    LaunchedEffect(Unit) {
        val lex = withContext(Dispatchers.IO) { UserLexicon(file) }
        val all = lex.allWords()
        words = all
        pinned = pinnedIn(lex, all)
        added = addedIn(lex, all)
        born = bornIn(lex, all)
        lexicon = lex
        val adjustments = withContext(Dispatchers.IO) { WordRanks(ranksFile) }
        rankEntries = adjustments.all()
        ranks = adjustments
    }

    fun resetRank(word: String) {
        val adjustments = ranks ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                adjustments.remove(word)
                adjustments.save()
            }
            rankEntries = adjustments.all()
            repository.bumpLexiconVersion()
        }
    }

    fun persist(mutate: (UserLexicon) -> Unit) {
        val lex = lexicon ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val before = lex.allWords().mapTo(HashSet()) { it.first }
                mutate(lex)
                lex.save()
                // A word deleted here must not walk back in on the sightings
                // it had already collected: the waiting room is its own file,
                // so it is told separately (#48).
                val gone = before - lex.allWords().mapTo(HashSet()) { it.first }
                if (gone.isNotEmpty()) {
                    PendingLearn(java.io.File(context.filesDir, "learning/pending_learn.json")).apply {
                        for (word in gone) forget(word)
                        save()
                    }
                }
            }
            val all = lex.allWords()
            words = all
            pinned = pinnedIn(lex, all)
            added = addedIn(lex, all)
            born = bornIn(lex, all)
            repository.bumpLexiconVersion()
        }
    }

    // Import from and export to Android's personal dictionary (#174): the
    // words missing on the other side, offered as a checklist first. Android
    // splits nothing, so a multi-word row ("on my way") offers its parts, the
    // way the keyboard already reads it.
    fun openAndroidPicker(export: Boolean) {
        val lex = lexicon ?: return
        scope.launch {
            val offered = withContext(Dispatchers.IO) {
                if (!SystemUserDictionary.available(context)) return@withContext null
                val android = SystemUserDictionary.spellings(context)
                    .flatMap { it.split(WHITESPACE_RUN) }
                    .map { it.trim() }
                    .filter { it.length >= 2 }
                if (export) {
                    val theirs = android.mapTo(HashSet()) { WordKey.of(it) }
                    lex.allWords().sortedByDescending { it.second }.map { it.first }.filter { WordKey.of(it) !in theirs }
                } else {
                    val mine = lex.allWords().mapTo(HashSet()) { WordKey.of(it.first) }
                    android.filter { WordKey.of(it) !in mine }.distinctBy { WordKey.of(it) }
                }
            }
            androidPicker = AndroidWordPicker(export, offered)
        }
    }

    // Words seen exactly once. Older versions learned every word the first time
    // it was committed, so for anyone upgrading this is where the swipe
    // misfires and mistyped words are — the clean-out the dictionary needed and
    // had no way to do short of deleting entries one at a time.
    // Words the user added by hand are never in here (#164). They start at a
    // weight of one, so the count cannot tell them apart: they are left out by
    // name, which is what the dialog promises.
    val seenOnce = remember(words, added) {
        words.filter { it.second <= 1 && it.first !in added }.map { it.first }
    }
    RegisterAddFab(stringResource(R.string.backup_add_word_action)) { showAdd = true }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (seenOnce.isNotEmpty()) {
            OutlinedButton(onClick = { showTidy = true }) {
                Text(stringResource(R.string.backup_tidy_words_action))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.backup_android_import_title),
                subtitle = stringResource(R.string.backup_android_import_subtitle),
                icon = Icons.Outlined.Download,
                onClick = { openAndroidPicker(export = false) },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.backup_android_export_title),
                subtitle = stringResource(R.string.backup_android_export_subtitle),
                icon = Icons.Outlined.Upload,
                onClick = { openAndroidPicker(export = true) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    // The lexicon holds up to 10,000 words and used to render as one flat
    // count-sorted list, which made finding a single word to delete a scroll
    // through everything the keyboard has ever learned.
    var query by remember { mutableStateOf("") }
    if (words.size > DICTIONARY_SEARCH_THRESHOLD || query.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(CommonR.string.common_search)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(CommonR.string.common_clear),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    // The list's order (#194): a key, and under it a button that names the
    // direction and reverses it. The direction is not a second press on the
    // key, so the current order is always written out on the screen.
    val sort = settings.watch { it.appUi.dictionarySort }
    if (words.size > 1) {
        ChoiceControl(
            options = listOf(
                DictionarySortKey.WEIGHT to stringResource(R.string.backup_dictionary_sort_weight),
                DictionarySortKey.NAME to stringResource(R.string.backup_dictionary_sort_name),
                DictionarySortKey.ADDED to stringResource(R.string.backup_dictionary_sort_added),
            ),
            selected = sort.key,
            label = stringResource(R.string.backup_dictionary_sort_label),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            onChange = { key -> scope.launch { repository.setDictionarySort(DictionarySort.of(key)) } },
        )
        TextButton(
            onClick = { scope.launch { repository.setDictionarySort(sort.reversed()) } },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Icon(
                Icons.Outlined.SwapVert,
                contentDescription = stringResource(R.string.backup_dictionary_sort_reverse),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(sortDirectionLabel(sort)))
        }
    }
    val sorted = remember(words, born, sort) { sortDictionaryWords(words, sort, born) }
    val shown = remember(sorted, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) sorted else sorted.filter { needle in it.first.lowercase() }
    }
    if (words.isEmpty()) {
        CaptionText(stringResource(R.string.backup_dictionary_empty))
    } else if (shown.isEmpty()) {
        CaptionText(stringResource(R.string.backup_dictionary_no_matches, query))
    }
    // Drawn in pages. The screen is not a lazy list (see WmScreen), so every
    // row here is composed at once, and a dictionary of thousands of words
    // ran the app out of memory on the way in (#75). Keyed on the query and
    // not on the list: a new search starts over at page one, but deleting or
    // respelling a word must not fold everything the user had expanded (#85).
    var visible by remember(query, sort) { mutableIntStateOf(WORD_LIST_PAGE) }
    SettingsGroup {
        for ((word, count) in shown.take(visible)) {
            item {
                // Every row carries its count (#165); a word the user added
                // says so in front of it rather than instead of it.
                val seen = pluralStringResource(R.plurals.backup_dictionary_seen_count, count, count)
                val standing = if (word in added) {
                    stringResource(R.string.backup_dictionary_added_subtitle, seen)
                } else {
                    seen
                }
                WmRow(
                    title = word,
                    subtitle = if (word in pinned) {
                        stringResource(R.string.backup_dictionary_pinned_subtitle, standing)
                    } else {
                        standing
                    },
                    trailing = {
                        IconButton(onClick = { persist { it.forget(word) } }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.backup_delete_word_desc, word),
                            )
                        }
                    },
                    onClick = { editing = word to count },
                )
            }
        }
        if (shown.size > visible) {
            item { ShowMoreWordsRow(shown.size - visible) { visible += WORD_LIST_PAGE } }
        }
    }

    editing?.let { (word, count) ->
        EditWordDialog(
            word = word,
            weight = count,
            pinned = word in pinned,
            onDismiss = { editing = null },
            onConfirm = { newWord, newWeight, keepCase ->
                persist { lex ->
                    // Respell first, so the weight lands on the word that is
                    // left. A respelling that merges into an existing word
                    // ends at the typed weight rather than the sum, which is
                    // the number the dialog showed as the outcome.
                    val target = if (lex.rename(word, newWord)) newWord else word
                    lex.setCount(target, newWeight)
                    // Last: a respelling pins on its own, and the switch is
                    // the user's final word on that (#100).
                    lex.pinCase(target, keepCase)
                }
                editing = null
            },
        )
    }

    // Rank adjustments (#99) live under the words: few, and undone one at a
    // time — there is nothing to edit but "no longer".
    if (rankEntries.isNotEmpty()) {
        SettingsGroup(title = stringResource(R.string.backup_rank_adjustments_title)) {
            for ((word, steps) in rankEntries) {
                item {
                    WmRow(
                        title = word,
                        subtitle = stringResource(
                            if (steps > 0) {
                                R.string.backup_rank_adjustment_up_subtitle
                            } else {
                                R.string.backup_rank_adjustment_down_subtitle
                            },
                            kotlin.math.abs(steps),
                        ),
                        trailing = {
                            IconButton(onClick = { resetRank(word) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.backup_rank_reset_desc, word),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.backup_add_word_title)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.backup_word_field_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        persist { it.addWord(input.trim()) }
                        showAdd = false
                    },
                ) { Text(stringResource(CommonR.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    androidPicker?.let { picker ->
        AndroidWordPickerDialog(
            picker = picker,
            onDismiss = { androidPicker = null },
            onConfirm = { chosen ->
                androidPicker = null
                if (picker.export) {
                    scope.launch {
                        val written = withContext(Dispatchers.IO) { SystemUserDictionary.addAll(context, chosen) }
                        Toast.makeText(
                            context,
                            context.resources.getQuantityString(R.plurals.backup_android_exported_toast, written, written),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    // A capital the user wrote into Android's list is kept (#44).
                    persist { lex -> for (word in chosen) lex.addWord(word, caseEvidence = word != word.lowercase()) }
                }
            },
        )
    }

    if (showTidy) {
        AlertDialog(
            onDismissRequest = { showTidy = false },
            title = { Text(stringResource(R.string.backup_tidy_words_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.backup_tidy_words_body,
                        seenOnce.size,
                        seenOnce.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        persist { it.forgetAll(seenOnce) }
                        showTidy = false
                    },
                ) { Text(stringResource(CommonR.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showTidy = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/** Regex for the gaps inside a multi-word Android dictionary row. */
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * The words the Android import or export would move (#174); [words] is null
 * when Android would not share its dictionary with the app.
 */
private data class AndroidWordPicker(val export: Boolean, val words: List<String>?)

/** A checklist of [AndroidWordPicker.words], all checked; confirming hands back the checked ones. */
@Composable
private fun AndroidWordPickerDialog(
    picker: AndroidWordPicker,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val words = picker.words
    val title = stringResource(
        when {
            words == null -> R.string.backup_android_unavailable_title
            picker.export -> R.string.backup_android_export_dialog_title
            else -> R.string.backup_android_import_dialog_title
        },
    )
    if (words.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Text(
                    stringResource(
                        when {
                            words == null -> R.string.backup_android_unavailable_body
                            picker.export -> R.string.backup_android_nothing_to_export_body
                            else -> R.string.backup_android_nothing_to_import_body
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
        return
    }
    var unchecked by remember(words) { mutableStateOf(emptySet<String>()) }
    val count = words.size - unchecked.size
    val list = rememberLazyListState()
    val rail = rememberScrollRailState(list)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextButton(onClick = { unchecked = if (unchecked.isEmpty()) words.toSet() else emptySet() }) {
                    Text(
                        stringResource(
                            if (unchecked.isEmpty()) R.string.backup_android_select_none_action else R.string.backup_android_select_all_action,
                        ),
                    )
                }
                ScrollRailBox(state = rail, modifier = Modifier.heightIn(max = 360.dp)) { rows ->
                    LazyColumn(state = list, modifier = rows) {
                        items(words) { word ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        unchecked =
                                            if (word in unchecked) unchecked - word else unchecked + word
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = word !in unchecked, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Text(word)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = count > 0, onClick = { onConfirm(words.filter { it !in unchecked }) }) {
                Text(
                    stringResource(
                        if (picker.export) R.string.backup_android_export_action else R.string.backup_android_import_action,
                        count,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/** The words on the personal dictionary's direction button (#194). */
private fun sortDirectionLabel(sort: DictionarySort): Int = when (sort) {
    DictionarySort.MOST_USED_FIRST -> R.string.backup_dictionary_sort_most_used
    DictionarySort.LEAST_USED_FIRST -> R.string.backup_dictionary_sort_least_used
    DictionarySort.A_TO_Z -> R.string.backup_dictionary_sort_a_to_z
    DictionarySort.Z_TO_A -> R.string.backup_dictionary_sort_z_to_a
    DictionarySort.NEWEST_FIRST -> R.string.backup_dictionary_sort_newest
    DictionarySort.OLDEST_FIRST -> R.string.backup_dictionary_sort_oldest
}
/**
 * Size of one press of the weight stepper: a single count under 10, a tenth
 * of the next power of ten above it. Ten presses cover each decade, so a
 * word can go from one sighting to "added" territory without a keyboard.
 */
private fun weightStep(weight: Int): Int = when {
    weight < 10 -> 1
    weight < 100 -> 10
    weight < 1_000 -> 100
    weight < 10_000 -> 1_000
    weight < 100_000 -> 10_000
    else -> 100_000
}
/**
 * The edit dialog a personal dictionary row opens (#47): respell the word,
 * and raise or lower its weight. The weight is the same count the row's
 * subtitle reads out, typed directly or stepped a decade at a time. [onConfirm]
 * gets the trimmed spelling and a weight already inside the lexicon's bounds.
 */
@Composable
private fun EditWordDialog(
    word: String,
    weight: Int,
    /** Whether the word's capitals are pinned against the case vote (#100). */
    pinned: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (word: String, weight: Int, keepCase: Boolean) -> Unit,
) {
    var spelling by remember(word) { mutableStateOf(word) }
    var weightText by remember(weight) { mutableStateOf(weight.toString()) }
    var keepCase by remember(word, pinned) { mutableStateOf(pinned) }
    val parsed = weightText.trim().toIntOrNull()
    val weightValid = parsed != null && parsed in 1..UserLexicon.MAX_COUNT
    // A respelling that folds to a blank is not a word; one over the length
    // cap would be dropped on the floor by the lexicon, so refuse it here
    // rather than closing as though it worked.
    val spellingValid = spelling.isNotBlank() && spelling.trim().length <= UserLexicon.MAX_WORD_LENGTH
    fun step(direction: Int) {
        val now = parsed ?: weight
        val size = if (direction < 0) weightStep(now - 1) else weightStep(now)
        weightText = (now + direction * size).coerceIn(1, UserLexicon.MAX_COUNT).toString()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_edit_word_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = spelling,
                    onValueChange = { spelling = it },
                    label = { Text(stringResource(R.string.backup_word_field_label)) },
                    singleLine = true,
                    isError = !spellingValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { step(-1) },
                        enabled = (parsed ?: weight) > 1,
                    ) {
                        Icon(
                            Icons.Outlined.Remove,
                            contentDescription = stringResource(R.string.backup_weight_lower_desc),
                        )
                    }
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.backup_word_weight_label)) },
                        singleLine = true,
                        isError = !weightValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { step(1) },
                        enabled = (parsed ?: weight) < UserLexicon.MAX_COUNT,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.backup_weight_raise_desc),
                        )
                    }
                }
                Text(
                    if (weightValid) {
                        stringResource(R.string.backup_word_weight_info)
                    } else {
                        stringResource(R.string.backup_word_weight_error, UserLexicon.MAX_COUNT)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (weightValid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.backup_word_keep_case_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.backup_word_keep_case_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = keepCase, onCheckedChange = { keepCase = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = spellingValid && weightValid,
                onClick = { onConfirm(spelling.trim(), parsed ?: weight, keepCase) },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}
// ---- suggestion blacklist ----

/**
 * One row of the blacklist editor: a word and the language it is blocked in,
 * or null for the list that applies everywhere (#136).
 */
private data class BlacklistEntry(val word: String, val languageId: String?)

/**
 * The never-suggest word list, stored in settings. A blacklisted word is kept
 * out of the suggestion strip and never used as an autocorrect target, but can
 * still be typed and committed normally. Matched case-insensitively.
 *
 * Since #136 a word can be blocked in one language only. The editor lists the
 * global words and the per-language ones together, each language-bound row
 * saying so under the word, and the Add dialog asks which list a new word
 * goes on. The choice row at the top is for the keyboard's own "Never
 * suggest", which cannot ask.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlacklistSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // The lists decide which rows the groups hold; the scope row reads its own value.
    val blacklist = settings.watch { it.suggestionSources.blacklist }
    val blacklistByLanguage = settings.watch { it.suggestionSources.blacklistByLanguage }
    val entries = remember(blacklist, blacklistByLanguage) {
        buildList {
            for (word in blacklist) add(BlacklistEntry(word, null))
            for ((language, words) in blacklistByLanguage) {
                for (word in words) add(BlacklistEntry(word, language))
            }
        }.sortedWith(compareBy({ it.word }, { it.languageId.orEmpty() }))
    }
    var showAdd by remember { mutableStateOf(false) }

    RegisterAddFab(stringResource(R.string.backup_add_word_action)) { showAdd = true }
    SettingsGroup {
        item {
            ChoiceSetting(
                R.string.backup_blacklist_scope_title,
                subtitle = stringResource(R.string.backup_blacklist_scope_subtitle),
                info = stringResource(R.string.backup_blacklist_scope_info),
                options = listOf(
                    BlacklistScope.ALL_LANGUAGES to
                        stringResource(R.string.backup_blacklist_scope_all_label),
                    BlacklistScope.CURRENT_LANGUAGE to
                        stringResource(R.string.backup_blacklist_scope_current_label),
                ),
                selected = settings.watch { it.suggestionSources.blacklistScope },
                default = SettingsDefaults.suggestionSources.blacklistScope,
                detail = { scope ->
                    ChoiceDetail(
                        stringResource(
                            if (scope == BlacklistScope.CURRENT_LANGUAGE) {
                                R.string.backup_blacklist_scope_current_desc
                            } else {
                                R.string.backup_blacklist_scope_all_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setSuggestionBlacklistScope(it) } }
        }
    }
    // Same shape as the personal dictionary above it: a search box once the
    // list is long enough to need one, and pages rather than every row at
    // once — hundreds of blacklisted words composed in one go ran the app
    // out of memory (#75).
    var query by remember { mutableStateOf("") }
    if (entries.size > DICTIONARY_SEARCH_THRESHOLD || query.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(CommonR.string.common_search)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(CommonR.string.common_clear),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    val shown = remember(entries, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) entries else entries.filter { needle in it.word }
    }
    // Keyed on the query, not the list, so a deletion keeps the pages open (#85).
    var visible by remember(query) { mutableIntStateOf(WORD_LIST_PAGE) }
    if (entries.isEmpty()) {
        CaptionText(stringResource(R.string.backup_blacklist_empty))
    } else if (shown.isEmpty()) {
        CaptionText(stringResource(R.string.backup_blacklist_no_matches, query))
    } else {
        // Per-row deletion is the only other way out of here, and the list is
        // DataStore-backed so the Storage screen has nothing to offer either.
        SettingsGroup {
            item {
                ActionRow(
                    title = R.string.backup_blacklist_clear_title,
                    subtitle = pluralStringResource(
                        R.plurals.backup_blacklist_clear_subtitle,
                        entries.size,
                        entries.size,
                    ),
                    action = stringResource(CommonR.string.common_clear),
                    confirm = stringResource(R.string.backup_blacklist_clear_confirm),
                    lock = AppLockTargets["action_clear_blacklist"],
                ) { scope.launch { repository.clearSuggestionBlacklist() } }
            }
        }
    }
    SettingsGroup {
        for (entry in shown.take(visible)) {
            item {
                val word = entry.word
                WmRow(
                    title = word,
                    // A language-bound word says so; a global one needs no line.
                    subtitle = entry.languageId?.let { id ->
                        stringResource(
                            R.string.backup_blacklist_language_word_subtitle,
                            LanguageRegistry.byId(id).displayName,
                        )
                    },
                    trailing = {
                        IconButton(onClick = {
                            scope.launch { repository.removeSuggestionBlacklistWord(word, entry.languageId) }
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.backup_delete_word_desc, word),
                            )
                        }
                    },
                )
            }
        }
        if (shown.size > visible) {
            item { ShowMoreWordsRow(shown.size - visible) { visible += WORD_LIST_PAGE } }
        }
    }

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        // Which list the word goes on: null is the global one. The chips are
        // the enabled languages, which is every language the strip can be
        // typing in; a word for a language not in the list has no strip to
        // stay out of.
        var language by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.backup_add_word_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.backup_word_field_label)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.backup_blacklist_language_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = language == null,
                            onClick = { language = null },
                            label = { Text(stringResource(R.string.backup_blacklist_all_languages_label)) },
                        )
                        for (lang in settings.watch { it.enabledLanguages }) {
                            FilterChip(
                                selected = language == lang.id,
                                onClick = { language = lang.id },
                                label = { Text(lang.displayName) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val target = language
                        scope.launch { repository.addSuggestionBlacklistWord(input, target) }
                        showAdd = false
                    },
                ) { Text(stringResource(CommonR.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
// ---- clipboard phone formats ----

/**
 * The phone-number shapes the clipboard detector keeps.
 *
 * With no format in the list every number-shaped run of digits becomes a chip,
 * which is the only thing the detector can do before it knows where the user
 * lives, and where its false positives come from: an invoice total and a
 * tracking id have the same shape as a phone number. One format ends that.
 *
 * A format is a mask, and the user writes it by giving a number they copy
 * often. The dial code stays literal and every other digit becomes an X, which
 * they can type back over to pin a digit their numbers always have.
 */
@Composable
internal fun PhoneFormatSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val formats = settings.watch { it.clipboard.phoneFormats.sorted() }
    val masks = remember(formats) { PhoneFormats.parseAll(formats) }
    var showAdd by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf("") }

    RegisterAddFab(stringResource(R.string.phoneformats_add_action)) { showAdd = true }
    if (formats.isEmpty()) {
        CaptionText(stringResource(R.string.phoneformats_empty))
    }
    SettingsGroup {
        for (format in formats) {
            item {
                WmRow(
                    title = format,
                    icon = Icons.Outlined.Phone,
                    trailing = {
                        IconButton(onClick = {
                            scope.launch { repository.removeClipboardPhoneFormat(format) }
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(
                                    R.string.phoneformats_delete_desc,
                                    format,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
    // A format is a promise about numbers the user cannot see from here, so the
    // screen lets them put one in and watch the answer.
    Text(
        stringResource(R.string.phoneformats_test_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
    )
    OutlinedTextField(
        value = sample,
        onValueChange = { sample = it },
        label = { Text(stringResource(R.string.phoneformats_test_field_label)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (sample.isNotBlank()) {
        val kept = PhoneFormats.matches(sample, masks)
        Text(
            stringResource(
                when {
                    masks.isEmpty() -> R.string.phoneformats_test_all
                    kept -> R.string.phoneformats_test_match
                    else -> R.string.phoneformats_test_no_match
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (kept || masks.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    Spacer(Modifier.height(16.dp))

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        val mask = remember(input) { phoneMaskFrom(input) }
        val previewFormat = stringResource(R.string.phoneformats_preview)
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.phoneformats_add_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.phoneformats_add_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.phoneformats_field_label)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (mask != null) {
                            previewFormat.format(mask)
                        } else {
                            stringResource(R.string.phoneformats_preview_none)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = mask != null,
                    onClick = {
                        val value = mask ?: return@TextButton
                        scope.launch { repository.addClipboardPhoneFormat(value) }
                        showAdd = false
                    },
                ) { Text(stringResource(CommonR.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/**
 * The mask [raw] stands for, from one field that takes both spellings: a
 * format if the user wrote one (it has an X in it), and otherwise a number to
 * make a format out of. Null while the field holds neither yet.
 */
private fun phoneMaskFrom(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val written = trimmed.any { it == 'X' || it == 'x' || it == '#' }
    return if (written) PhoneFormats.canonical(trimmed) else PhoneFormats.fromExample(trimmed)
}

// ---- learned corrections ----

/**
 * What autocorrect has learned from the user's own fixes: the typo → fix pairs
 * they taught it, and the slips those fixes were made of. File-backed like the
 * personal dictionary; every edit rewrites the file and bumps the corrections
 * version, so a running keyboard reloads its copy without dropping the words
 * it is still waiting to settle.
 */
@Composable
internal fun LearnedCorrectionsSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val file = remember { java.io.File(context.filesDir, LEARNED_CORRECTIONS_FILE) }
    var pairs by remember { mutableStateOf<List<Pair<String, CorrectionMemory.Taught>>>(emptyList()) }
    var habits by remember { mutableStateOf<List<CorrectionMemory.Habit>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // Re-read whenever the keyboard or this screen changes the file.
    LaunchedEffect(settings.watch { it.suggestionStrip.correctionsVersion }) {
        val memory = withContext(Dispatchers.IO) { CorrectionMemory(file) }
        pairs = memory.pairs()
        habits = memory.habitSummary()
        loaded = true
    }
    fun persist(edit: (CorrectionMemory) -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val memory = CorrectionMemory(file)
                edit(memory)
                memory.save()
            }
            repository.bumpCorrectionsVersion()
        }
    }
    if (!loaded) return

    var query by remember { mutableStateOf("") }
    if (pairs.size > DICTIONARY_SEARCH_THRESHOLD || query.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(CommonR.string.common_search)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(CommonR.string.common_clear),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    val shown = remember(pairs, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) pairs else pairs.filter { needle in it.first || needle in it.second.fixed }
    }
    // Keyed on the query, not the list, so a deletion keeps the pages open (#85).
    var visible by remember(query) { mutableIntStateOf(WORD_LIST_PAGE) }
    if (pairs.isEmpty() && habits.isEmpty()) {
        CaptionText(stringResource(R.string.typing_learned_corrections_empty))
        return
    }
    if (pairs.isNotEmpty()) {
        SettingsGroup {
            item {
                ActionRow(
                    title = R.string.typing_learned_corrections_clear_title,
                    subtitle = pluralStringResource(
                        R.plurals.typing_learned_corrections_clear_subtitle,
                        pairs.size,
                        pairs.size,
                    ),
                    action = stringResource(CommonR.string.common_clear),
                    confirm = stringResource(R.string.typing_learned_corrections_clear_confirm),
                    lock = AppLockTargets["action_clear_learned_corrections"],
                ) { scope.launch { repository.forgetLearnedCorrections() } }
            }
        }
    }
    if (shown.isEmpty() && query.isNotEmpty()) {
        CaptionText(stringResource(R.string.typing_learned_corrections_no_matches, query))
    } else if (shown.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.typing_learned_corrections_pairs_title)) {
            for ((typed, taught) in shown.take(visible)) {
                item {
                    WmRow(
                        title = "$typed → ${taught.fixed}",
                        subtitle = pluralStringResource(
                            R.plurals.typing_learned_corrections_times,
                            taught.count,
                            taught.count,
                        ),
                        trailing = {
                            IconButton(onClick = { persist { it.forget(typed) } }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.typing_learned_corrections_delete_desc, typed,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            if (shown.size > visible) {
                item { ShowMoreWordsRow(shown.size - visible) { visible += WORD_LIST_PAGE } }
            }
        }
    }
    if (habits.isNotEmpty() && query.isEmpty()) {
        SettingsGroup(
            stringResource(R.string.typing_habits_title),
            info = stringResource(R.string.typing_habits_info),
        ) {
            for (habit in habits.take(HABITS_SHOWN)) {
                item {
                    WmRow(
                        title = habitLabel(habit),
                        subtitle = pluralStringResource(R.plurals.typing_habit_times, habit.count, habit.count),
                    )
                }
            }
        }
    }
}

/** Slips listed on the learned-corrections screen; the long tail is noise. */
private const val HABITS_SHOWN = 12

@Composable
private fun habitLabel(habit: CorrectionMemory.Habit): String {
    fun shown(c: Char?): String = when (c) {
        null -> ""
        ' ' -> "␣"
        else -> c.toString()
    }
    return when (habit.kind) {
        CorrectionMemory.HabitKind.SUBSTITUTION ->
            stringResource(R.string.typing_habit_substitution, shown(habit.from), shown(habit.to))
        CorrectionMemory.HabitKind.MISSING -> stringResource(R.string.typing_habit_missing, shown(habit.to))
        CorrectionMemory.HabitKind.STRAY -> stringResource(R.string.typing_habit_stray, shown(habit.from))
        CorrectionMemory.HabitKind.SWAP ->
            stringResource(R.string.typing_habit_swap, shown(habit.from), shown(habit.to))
        CorrectionMemory.HabitKind.SPACE_SLIP -> stringResource(R.string.typing_habit_space_slip, shown(habit.from))
        CorrectionMemory.HabitKind.MISSED_SPACE -> stringResource(R.string.typing_habit_missed_space)
    }
}
