package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.clipboard.clipPreviewText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.NoteTag
import com.wasimaster.wmkeyboard.core.notes.NoteTagPalette
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.NoteEdit
import com.wasimaster.wmkeyboard.ime.NoteEditField
import com.wasimaster.wmkeyboard.ime.NoteMarkdownAction
import com.wasimaster.wmkeyboard.ime.NotesConfirm
import com.wasimaster.wmkeyboard.ime.NotesFilter
import com.wasimaster.wmkeyboard.ime.NotesPanelUi
import com.wasimaster.wmkeyboard.ime.NotesUndo
import com.wasimaster.wmkeyboard.ime.NotesUndoKind
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The Notes panel's service callbacks (#560), bundled into one value for the
 * reason [VocabCallbacks] is: the callers sit against the JVM's 64K
 * method-size ceiling, so a new panel may not add parameters to them. The
 * bundle rides [ToolHoldCallbacks.notes].
 */
data class NotesCallbacks(
    val onFilter: (NotesFilter) -> Unit = {},
    val onSearchToggle: () -> Unit = {},
    val onSearchClear: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    /** Out of the editor, then out of the note, then out of the panel. */
    val onBack: () -> Unit = {},
    val onReadExpand: () -> Unit = {},
    /** A new note, born with this tag when one is given. */
    val onNew: (String?) -> Unit = {},
    /** Edit a note, the caret at the start of this source line when one is given. */
    val onEditStart: (Long, Int?) -> Unit = { _, _ -> },
    /** One of the editor's fields takes the keys, the caret at this offset when given. */
    val onEditFocus: (NoteEditField, Int?) -> Unit = { _, _ -> },
    val onEditDone: () -> Unit = {},
    val onEditCancel: () -> Unit = {},
    val onMarkdown: (NoteMarkdownAction) -> Unit = {},
    /** A tag on or off a note; the palette index is what a suggested tag is born with. */
    val onTagToggle: (Long, String, Int) -> Unit = { _, _, _ -> },
    val onTagPickerToggle: () -> Unit = {},
    val onTagTypeStart: () -> Unit = {},
    val onTagCommit: () -> Unit = {},
    val onTagTypeCancel: () -> Unit = {},
    val onInsert: (Long) -> Unit = {},
    val onCopy: (Long) -> Unit = {},
    val onShare: (Long) -> Unit = {},
    val onPin: (Long, Boolean) -> Unit = { _, _ -> },
    val onArchive: (Long, Boolean) -> Unit = { _, _ -> },
    val onLock: (Long) -> Unit = {},
    val onTrash: (Long) -> Unit = {},
    val onRestore: (Long) -> Unit = {},
    val onDeleteForever: (Long) -> Unit = {},
    val onEmptyTrash: () -> Unit = {},
    val onConfirmDismiss: () -> Unit = {},
    /** A task box tapped: the note, and the source line of the box. */
    val onTaskToggle: (Long, Int) -> Unit = { _, _ -> },
    /** A row long-pressed shows its action chips; null puts them away. */
    val onHold: (Long?) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onUndoDismiss: () -> Unit = {},
)

/** Settings routes the panel links to; `SettingsRoutesTest` scans these constants. */
internal const val NOTES_ROUTE = "notes"
internal const val NOTES_TAGS_ROUTE = "notes/tags"

/** Where the read view stops drawing a long note until Show more is tapped. */
private const val READ_INITIAL_CHARS = 6_000

/**
 * The Notes panel inside its full-bleed frame (#560): the list with its filter
 * chips, a note open to read, or the editor with the keys back beneath it.
 */
@Composable
internal fun NotesPanelHost(
    state: KeyboardUiState,
    callbacks: NotesCallbacks,
    onPanelChange: (PanelMode) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val notes = state.notes
    val editing = notes.editing
    val open = notes.openNote()
    val reading = editing == null && open != null
    val lineHeightDp = textLineHeightDp()
    FullBleedTool(
        state,
        title = "",
        onClose = {
            when {
                editing != null -> callbacks.onEditDone()
                open != null -> callbacks.onBack()
                else -> onPanelChange(PanelMode.NOTES)
            }
        },
        // The read view may grow to the screen's share (#348's expand); the
        // frame caps it below the status bar.
        extraHeight = if (reading && notes.readExpanded) LocalConfiguration.current.screenHeightDp.dp else 0.dp,
        compact = state.notesTypingActive,
        compactHeight = when {
            editing != null -> notesEditCompactHeight(lineHeightDp)
            // Searching: the pill, and a few rows of results under it.
            else -> FullBleedHeaderHeight + lineHeightDp * 7
        },
        headerActions = {
            when {
                editing != null -> NotesEditHeader(state, editing.titleDraft, editing.focused == NoteEditField.TITLE, callbacks)
                open != null -> NotesReadHeader(state, open, callbacks)
                else -> NotesListHeader(state, callbacks)
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                editing != null -> NotesEditor(state, editing, callbacks, onOpenRoute)
                open != null -> NotesReadView(state, open, callbacks, onOpenRoute)
                else -> NotesList(state, callbacks, onOpenRoute)
            }
            NotesUndoBar(
                undo = notes.undo,
                reduceMotion = state.settings.reduceMotion,
                onUndo = callbacks.onUndo,
                onDismiss = callbacks.onUndoDismiss,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}

// ---- headers ----

@Composable
private fun RowScope.NotesListHeader(state: KeyboardUiState, callbacks: NotesCallbacks) {
    val notes = state.notes
    MediaHeaderSearchBar(
        state = state,
        placeholder = stringResource(R.string.ime_notes_search_hint),
        onQueryTap = callbacks.onSearchToggle,
        activePlaceholder = stringResource(R.string.ime_notes_search_active_hint),
        focused = state.focusedIndex(FocusRegion.SEARCH) == 0,
        onClear = callbacks.onSearchClear,
        query = notes.query,
        active = notes.searchActive,
    )
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.SEARCH, count = 1, columns = 1) {
        callbacks.onSearchToggle()
    }
    if (!notes.searchActive && notes.available) {
        NotesHeaderButton(Icons.Outlined.Add, stringResource(R.string.ime_notes_new_desc)) { callbacks.onNew(null) }
    }
}

@Composable
private fun RowScope.NotesReadHeader(state: KeyboardUiState, note: Note, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    Row(Modifier.weight(1f).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (note.locked) {
            Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.ime_notes_locked_label), tint = kb.secondaryText, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            note.displayTitle().ifEmpty { stringResource(R.string.ime_notes_untitled) },
            color = kb.secondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (!shortLandscape()) {
        NotesHeaderButton(
            if (state.notes.readExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
            stringResource(if (state.notes.readExpanded) R.string.ime_notes_collapse_desc else R.string.ime_notes_expand_desc),
            onClick = callbacks.onReadExpand,
        )
    }
    NotesHeaderButton(
        Icons.Outlined.PushPin,
        stringResource(if (note.pinned) R.string.ime_notes_unpin_action else R.string.ime_notes_pin_action),
        active = note.pinned,
    ) { callbacks.onPin(note.id, !note.pinned) }
    if (!note.locked && !note.inTrash && !state.notes.openTooLong()) {
        NotesHeaderButton(Icons.Outlined.EditNote, stringResource(R.string.ime_notes_edit_action)) {
            callbacks.onEditStart(note.id, null)
        }
    }
}

@Composable
private fun RowScope.NotesEditHeader(state: KeyboardUiState, title: String, titleFocused: Boolean, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(18.dp)
    val ringed = state.focusedIndex(FocusRegion.ACTIONS)
    NotesHeaderButton(Icons.Outlined.Check, stringResource(R.string.ime_notes_done_desc), active = true, ringed = ringed == 0) {
        callbacks.onEditDone()
    }
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 4.dp)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable { callbacks.onEditFocus(NoteEditField.TITLE, null) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = title,
            placeholder = stringResource(R.string.ime_notes_title_hint),
            active = titleFocused,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        stringResource(CommonR.string.common_cancel),
        color = kb.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .focusRing(ringed == 1, shape)
            .clip(shape)
            .clickable { callbacks.onEditCancel() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A round icon button in the header, the size of the tool circles beside it. */
@Composable
internal fun NotesHeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    ringed: Boolean = false,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .size(38.dp)
            .focusRing(ringed, CircleShape)
            .clip(CircleShape)
            .background(if (active) kb.accent.copy(alpha = if (kb.dark) 0.22f else 0.14f) else Color.Transparent)
            .clickable {
                feedback()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (active) kb.accent else kb.toolbarIcon, modifier = Modifier.size(20.dp))
    }
}

// ---- the list ----

@Composable
private fun NotesList(state: KeyboardUiState, callbacks: NotesCallbacks, onOpenRoute: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val notes = state.notes
    if (!notes.available) {
        NotesMessage(stringResource(R.string.ime_notes_unlock_info))
        return
    }
    val chips = filterChips(notes)
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.CHIPS, count = chips.size + 1, columns = chips.size + 1) { index ->
        if (index < chips.size) callbacks.onFilter(chips[index].filter) else onOpenRoute(NOTES_TAGS_ROUTE)
    }
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.RESULTS, count = notes.items.size, columns = 1) { index ->
        notes.items.getOrNull(index)?.let { callbacks.onOpen(it.id) }
    }
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.ACTIONS, count = 0, columns = 1) {}
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedRow = state.focusedIndex()
    val listState = rememberLazyListState()
    ScrollFocusIntoView(focusedRow) { listState.animateScrollToItem(it) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips.forEachIndexed { index, chip ->
                NoteTagChip(
                    label = chip.label,
                    colour = chip.colour,
                    filled = chip.filter == notes.filter,
                    focused = focusedChip == index,
                    onClick = { callbacks.onFilter(chip.filter) },
                    onLongClick = if (chip.filter is NotesFilter.Tag) ({ onOpenRoute(NOTES_TAGS_ROUTE) }) else null,
                )
            }
            ToolPanelChip(
                stringResource(R.string.ime_notes_manage_tags_desc),
                icon = Icons.Outlined.Label,
                modifier = Modifier.focusRing(focusedChip == chips.size, kb.chipShape()),
            ) { onOpenRoute(NOTES_TAGS_ROUTE) }
        }
        if (notes.filter == NotesFilter.Trash && notes.items.isNotEmpty()) {
            TrashHeader(state, callbacks)
        }
        if (notes.items.isEmpty()) {
            NotesEmpty(state, callbacks)
            return@Column
        }
        val now = System.currentTimeMillis()
        val tagColours = remember(notes.tags) { notes.tags.associate { it.name.lowercase() to it.colour } }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (notes.undo != null) UndoBarClearance else 8.dp),
        ) {
            items(notes.items.size, key = { notes.items[it].id }) { index ->
                val note = notes.items[index]
                NoteRow(
                    state = state,
                    note = note,
                    now = now,
                    tagColours = tagColours,
                    focused = focusedRow == index,
                    held = notes.held == note.id,
                    callbacks = callbacks,
                    onOpenRoute = onOpenRoute,
                )
            }
        }
    }
}

private class NotesFilterChip(val filter: NotesFilter, val label: String, val colour: Int?)

@Composable
private fun filterChips(notes: NotesPanelUi): List<NotesFilterChip> {
    @Composable
    fun counted(labelRes: Int, count: Int): String {
        val label = stringResource(labelRes)
        return if (count > 0) stringResource(R.string.ime_notes_filter_count, label, count) else label
    }
    return buildList {
        add(NotesFilterChip(NotesFilter.All, stringResource(R.string.ime_notes_filter_all), null))
        add(NotesFilterChip(NotesFilter.Pinned, counted(R.string.ime_notes_filter_pinned, notes.counts.pinned), null))
        for (tag in notes.tags) add(NotesFilterChip(NotesFilter.Tag(tag.name), tag.name, tag.colour))
        add(NotesFilterChip(NotesFilter.Archived, counted(R.string.ime_notes_filter_archived, notes.counts.archived), null))
        add(NotesFilterChip(NotesFilter.Trash, counted(R.string.ime_notes_filter_trash, notes.counts.trash), null))
    }
}

/** The trash's retention line and its two-tap Empty trash chip. */
@Composable
private fun TrashHeader(state: KeyboardUiState, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    val notes = state.notes
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            retentionText(state.settings.notes.trashDays),
            color = kb.secondaryText,
            fontSize = 11.sp,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        ConfirmChip(
            label = stringResource(R.string.ime_notes_empty_trash_action),
            armed = notes.confirm == NotesConfirm.EmptyTrash,
            onClick = callbacks.onEmptyTrash,
        )
    }
}

@Composable
private fun retentionText(days: Int): String =
    if (days > 0) stringResource(R.string.ime_notes_trash_retention, days) else stringResource(R.string.ime_notes_trash_kept)

/** A destructive chip that asks for a second tap: armed, it says so in the accent. */
@Composable
private fun ConfirmChip(label: String, armed: Boolean, enabled: Boolean = true, focused: Boolean = false, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    ToolPanelChip(
        if (armed) stringResource(R.string.ime_notes_confirm_action) else label,
        selected = armed,
        enabled = enabled,
        modifier = Modifier.focusRing(focused, kb.chipShape()),
        onClick = onClick,
    )
}

@Composable
private fun NotesEmpty(state: KeyboardUiState, callbacks: NotesCallbacks) {
    val notes = state.notes
    if (notes.query.isNotBlank()) {
        NotesMessage(stringResource(R.string.ime_notes_no_match, notes.query.trim()), stringResource(CommonR.string.common_clear)) {
            callbacks.onSearchClear()
        }
        return
    }
    when (val filter = notes.filter) {
        NotesFilter.All -> NotesMessage(stringResource(R.string.ime_notes_empty_all), stringResource(R.string.ime_notes_new_action)) {
            callbacks.onNew(null)
        }
        NotesFilter.Pinned -> NotesMessage(stringResource(R.string.ime_notes_empty_pinned))
        is NotesFilter.Tag -> NotesMessage(
            stringResource(R.string.ime_notes_empty_tag, filter.name),
            stringResource(R.string.ime_notes_new_tagged_action, filter.name),
        ) { callbacks.onNew(filter.name) }
        NotesFilter.Archived -> NotesMessage(stringResource(R.string.ime_notes_empty_archived))
        NotesFilter.Trash -> NotesMessage(stringResource(R.string.ime_notes_empty_trash) + "\n" + retentionText(state.settings.notes.trashDays))
    }
}

@Composable
internal fun NotesMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val kb = LocalKbTheme.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = kb.toolbarIcon, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            ToolPanelChip(action, selected = true, onClick = onAction)
        }
    }
}

/**
 * One note in the list: its title, a line or two of the body, and when it
 * was last touched with its tag dots. A long press swaps the meta line for
 * the note's action chips.
 */
@Composable
private fun NoteRow(
    state: KeyboardUiState,
    note: Note,
    now: Long,
    tagColours: Map<String, Int>,
    focused: Boolean,
    held: Boolean,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val title = note.displayTitle().ifEmpty { stringResource(R.string.ime_notes_untitled) }
    val previewLines = state.settings.notes.previewLines
    val preview = when {
        note.locked -> stringResource(R.string.ime_notes_locked_label)
        previewLines <= 0 -> ""
        else -> remember(note.body, note.title, previewLines) { clipPreviewText(note.previewBody(), previewLines) }
    }
    val relative = if (note.inTrash) {
        stringResource(R.string.ime_notes_deleted_ago, DateUtils.getRelativeTimeSpanString(note.deletedAt ?: now, now, DateUtils.MINUTE_IN_MILLIS))
    } else {
        DateUtils.getRelativeTimeSpanString(note.updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val progress = remember(note.body) { if (note.locked) null else note.checklist().takeIf { !it.isEmpty } }
    val lockedLabel = stringResource(R.string.ime_notes_locked_label)
    val pinnedLabel = stringResource(R.string.ime_notes_pin_action)
    val description = remember(title, preview, note.pinned, note.locked, note.tags, progress) {
        buildList {
            add(title)
            if (preview.isNotEmpty()) add(preview)
            if (note.pinned) add(pinnedLabel)
            if (note.locked) add(lockedLabel)
            if (note.tags.isNotEmpty()) add(note.tags.joinToString(", "))
            progress?.let { add("${it.done}/${it.total}") }
        }.joinToString(". ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .focusRing(focused, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(if (held) kb.chip else Color.Transparent)
            .combinedClickable(
                onClick = { if (held) callbacks.onHold(null) else callbacks.onOpen(note.id) },
                onLongClick = {
                    feedback()
                    callbacks.onHold(if (held) null else note.id)
                },
            )
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .semantics { contentDescription = description },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = kb.modifierKeyText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (note.pinned) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.PushPin, contentDescription = null, tint = kb.accent, modifier = Modifier.size(14.dp))
            }
            if (note.locked) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(14.dp))
            }
        }
        if (preview.isNotEmpty()) {
            Text(
                preview,
                color = kb.toolbarIcon,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = previewLines.coerceAtLeast(1),
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (held) {
            NoteActionChips(state, note, callbacks, onOpenRoute, modifier = Modifier.padding(top = 4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(relative, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
                if (note.archived && state.notes.filter != NotesFilter.Archived) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ime_notes_archived_badge), color = kb.secondaryText, fontSize = 10.sp)
                }
                if (progress != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Checklist, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(R.string.ime_notes_tasks_progress, progress.done, progress.total), color = kb.secondaryText, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                for (tag in note.tags.take(3)) {
                    Spacer(Modifier.width(3.dp))
                    TagDot(noteTagColour(kb, tagColours[tag.lowercase()] ?: 0))
                }
                if (note.tags.size > 3) {
                    Spacer(Modifier.width(3.dp))
                    Text("+${note.tags.size - 3}", color = kb.secondaryText, fontSize = 10.sp)
                }
            }
        }
    }
}

/**
 * What can be done with a note, as chips: under a held row and under the
 * read view. The trash offers Restore and Delete forever; a locked note
 * offers only what does not show its text.
 */
@Composable
internal fun NoteActionChips(
    state: KeyboardUiState,
    note: Note,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Register the chips as the panel's ACTIONS focus region; the read view does, a held row does not. */
    focusRegion: Boolean = false,
    includeEdit: Boolean = true,
) {
    val kb = LocalKbTheme.current
    val confirm = state.notes.confirm
    val tooLong = note.body.length > NoteEdit.EDITOR_MAX_LENGTH
    val actions: List<Pair<String, () -> Unit>> = buildList {
        if (note.inTrash) {
            add(stringResource(R.string.ime_notes_restore_action) to { callbacks.onRestore(note.id) })
            return@buildList
        }
        if (!note.locked) {
            add(stringResource(R.string.ime_notes_insert_action) to { callbacks.onInsert(note.id) })
            add(stringResource(CommonR.string.common_copy) to { callbacks.onCopy(note.id) })
            if (includeEdit) {
                if (tooLong) {
                    add(stringResource(R.string.ime_notes_edit_in_settings_action) to { onOpenRoute(NOTES_ROUTE) })
                } else {
                    add(stringResource(R.string.ime_notes_edit_action) to { callbacks.onEditStart(note.id, null) })
                }
            }
            add(stringResource(CommonR.string.common_share) to { callbacks.onShare(note.id) })
        }
        add(
            stringResource(if (note.pinned) R.string.ime_notes_unpin_action else R.string.ime_notes_pin_action) to
                { callbacks.onPin(note.id, !note.pinned) },
        )
        add(
            stringResource(if (note.archived) R.string.ime_notes_unarchive_action else R.string.ime_notes_archive_action) to
                { callbacks.onArchive(note.id, !note.archived) },
        )
        if (!note.locked) add(stringResource(R.string.ime_notes_lock_action) to { callbacks.onLock(note.id) })
        add(stringResource(CommonR.string.common_delete) to { callbacks.onTrash(note.id) })
    }
    val total = actions.size + if (note.inTrash) 1 else 0
    if (focusRegion) {
        PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.ACTIONS, count = total, columns = total) { index ->
            if (index < actions.size) actions[index].second() else callbacks.onDeleteForever(note.id)
        }
    }
    val focusedIndex = if (focusRegion) state.focusedIndex(FocusRegion.ACTIONS) else null
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, (label, action) ->
            ToolPanelChip(
                label,
                selected = index == 0 && !note.inTrash,
                modifier = Modifier.focusRing(focusedIndex == index, kb.chipShape()),
                onClick = action,
            )
        }
        if (note.inTrash) {
            ConfirmChip(
                label = stringResource(R.string.ime_notes_delete_forever_action),
                armed = (confirm as? NotesConfirm.DeleteForever)?.id == note.id,
                focused = focusedIndex == actions.size,
            ) { callbacks.onDeleteForever(note.id) }
        }
    }
}

// ---- the read view ----

@Composable
private fun NotesReadView(state: KeyboardUiState, note: Note, callbacks: NotesCallbacks, onOpenRoute: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val tagColours = remember(state.notes.tags) { state.notes.tags.associate { it.name.lowercase() to it.colour } }
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.SEARCH, count = 0, columns = 1) {}
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.CHIPS, count = note.tags.size, columns = note.tags.size.coerceAtLeast(1)) { index ->
        note.tags.getOrNull(index)?.let { callbacks.onFilter(NotesFilter.Tag(it)); callbacks.onBack() }
    }
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.RESULTS, count = 0, columns = 1) {}
    var showAll by rememberSaveable(note.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            if (note.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    for (tag in note.tags) {
                        NoteTagChip(tag, tagColours[tag.lowercase()] ?: 0, filled = false, focused = false, onClick = {
                            callbacks.onFilter(NotesFilter.Tag(tag))
                            callbacks.onBack()
                        })
                    }
                }
            }
            when {
                note.locked -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = kb.toolbarIcon, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ime_notes_locked_info), color = kb.toolbarIcon, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    ToolPanelChip(stringResource(R.string.ime_notes_unlock_settings_action), selected = true) { onOpenRoute(NOTES_ROUTE) }
                }
                note.body.isBlank() -> Text(
                    stringResource(R.string.ime_notes_body_hint),
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { if (!note.inTrash) callbacks.onEditStart(note.id, null) },
                )
                else -> {
                    val body = remember(note.body, showAll) { if (showAll) note.body else readPrefix(note.body) }
                    val editable = !note.inTrash && note.body.length <= NoteEdit.EDITOR_MAX_LENGTH
                    if (state.settings.notes.renderMarkdown) {
                        ChatMarkdown(
                            body,
                            ChatMarkdownColors(text = kb.modifierKeyText, dim = kb.secondaryText, codeBackground = kb.chip),
                            fontSize = 13.sp,
                            onTaskToggle = if (note.inTrash) null else ({ task -> callbacks.onTaskToggle(note.id, task.line) }),
                            onBlockTap = if (editable) ({ block -> callbacks.onEditStart(note.id, block.line) }) else null,
                        )
                    } else {
                        Text(
                            body,
                            color = kb.modifierKeyText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = editable) { callbacks.onEditStart(note.id, null) },
                        )
                    }
                    if (!showAll && body.length < note.body.length) {
                        Spacer(Modifier.height(6.dp))
                        ToolPanelChip(stringResource(R.string.ime_notes_show_more_action)) { showAll = true }
                    }
                }
            }
        }
        NoteActionChips(
            state,
            note,
            callbacks,
            onOpenRoute,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            focusRegion = true,
        )
    }
}

/** The first [READ_INITIAL_CHARS] of a long body, cut at a paragraph break. */
private fun readPrefix(body: String): String {
    if (body.length <= READ_INITIAL_CHARS) return body
    val cut = body.lastIndexOf("\n\n", READ_INITIAL_CHARS).takeIf { it > READ_INITIAL_CHARS / 2 } ?: READ_INITIAL_CHARS
    return body.substring(0, cut)
}

// ---- shared pieces ----

/** The colour a tag's dot wears: the theme's accent for palette index 0, else the palette's own. */
internal fun noteTagColour(kb: KbTheme, index: Int): Color {
    val safe = NoteTagPalette.clamp(index)
    return if (safe == 0) kb.accent else Color(NoteTagPalette.ARGB[safe])
}

@Composable
internal fun TagDot(colour: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(colour))
}

/** A filter or tag chip: [VocabChip]'s shape with a colour dot in front when [colour] is given. */
@Composable
internal fun NoteTagChip(
    label: String,
    colour: Int?,
    filled: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    Row(
        modifier = Modifier
            .focusRing(focused, shape)
            .clip(shape)
            .background(if (filled) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { { feedback(); it() } },
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (colour != null) {
            TagDot(noteTagColour(kb, colour))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (filled) kb.chipActiveText else kb.chipText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun NotesUndoBar(
    undo: NotesUndo?,
    reduceMotion: Boolean,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var last by remember { mutableStateOf(NotesUndo(NotesUndoKind.TRASHED, listOf(0L))) }
    if (undo != null) last = undo
    val count = last.ids.size
    PanelUndoBar(
        text = when (last.kind) {
            NotesUndoKind.TRASHED -> pluralStringResource(R.plurals.ime_notes_trashed, count, count)
            NotesUndoKind.ARCHIVED -> pluralStringResource(R.plurals.ime_notes_archived, count, count)
        },
        visible = undo != null,
        key = undo,
        reduceMotion = reduceMotion,
        onUndo = onUndo,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/** One text line's height in dp at the user's font scale, for the compact heights. */
@Composable
internal fun textLineHeightDp(): androidx.compose.ui.unit.Dp =
    with(androidx.compose.ui.platform.LocalDensity.current) { 19.sp.toDp() }

/** Whether [tag] is one of [tags], ignoring case. */
internal fun List<NoteTag>.hasTag(tag: String): Boolean = any { it.name.equals(tag, ignoreCase = true) }
