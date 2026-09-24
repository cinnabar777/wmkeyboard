package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutKeyman
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutName
import com.wasimaster.wmkeyboard.core.layout.KeymanBinding
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository

/**
 * The route of a language's [MoreLayoutsScreen]. One function so the card that
 * opens it and the graph that declares it cannot drift apart.
 */
internal fun moreLayoutsRoute(langId: String): String = "language/$langId/more"

/**
 * One language's converted Keyman layouts, a tap off its detail screen: every
 * one of them as the same keyboard card the detail screen draws, in a grid, so
 * a layout is picked by what it looks like rather than by a name that means
 * little to anyone who has not used it.
 *
 * Under each card is the layout's typing-rules line: whether the rules are on
 * the device, with the one button that changes that. A layout that is on but
 * has no rules says so in the error colour, because that is the state in which
 * the keyboard types the wrong letters with no other sign why.
 *
 * Lists every Keyman grid the language has, not only the ones the detail screen
 * left behind: this page is the catalogue, and a layout vanishing from it the
 * moment it is switched on would read as the card having deleted something.
 * The order never changes under the reader either — switching a card on tints
 * it where it stands. "On" in the filter row is how to see only those.
 *
 * A lazy grid in the settings frame rather than a settings column, because a
 * column composes every card at once, and each card is a whole keyboard.
 *
 * Named "More layouts" rather than "Keyman layouts" because the name has to mean
 * something to a user who has never heard of Keyman, and the intro says where
 * they come from for the user who has.
 */
@Composable
internal fun MoreLayoutsScreen(
    anim: AnimatedVisibilityScope,
    langId: String,
    repository: SettingsRepository,
    settings: LiveSettings,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lang = LanguageRegistry.byId(langId)
    var rulesRefresh by remember { mutableStateOf(0) }
    val toggle = rememberLayoutToggle(settings, repository, scope) { rulesRefresh++ }

    // Resolved once per custom-layout list: every card needs its name and
    // binding, and the search reads the names on every letter typed.
    val customLayouts = settings.watch { it.customLayouts }
    val layouts = remember(lang, customLayouts) {
        // From the layout index, not the grids: a language can have a dozen
        // converted layouts of a megabyte each, and this list only names them.
        lang.layoutIds.mapNotNull { id ->
            resolveLayoutKeyman(customLayouts, id)
                ?.let { MoreLayout(id, resolveLayoutName(customLayouts, id), it) }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyOn by rememberSaveable { mutableStateOf(false) }
    // Which cards are listed under "On", and its count, depend on which layouts
    // are on; each card reads its own.
    val onIds = settings.watch { s -> layouts.filter { it.id in s.enabledLayoutIds }.mapTo(HashSet()) { it.id } }
    val onCount = onIds.size
    // The filter follows the setting: switching the last layout off under
    // "On" would otherwise leave the reader in an empty view they did not ask
    // for, with the chip that caused it still lit.
    val showingOn = onlyOn && onCount > 0
    val needle = query.trim().lowercase()
    val shown = layouts.filter { layout ->
        (!showingOn || layout.id in onIds) &&
            (needle.isEmpty() || layout.searchKey.contains(needle))
    }
    val searchable = layouts.size > SEARCH_FROM

    WmLazyScreen(
        anim = anim,
        title = stringResource(R.string.languages_more_layouts_title),
        onBack = onBack,
        route = moreLayoutsRoute(langId),
        // The heading says which language's catalogue this is: "More layouts"
        // on its own is the same title under all 843 of them.
        subtitle = lang.displayName,
        // Pinned, so the search box stays in reach and keeps its focus while
        // the cards scroll under it — a field inside the grid would be
        // disposed, keyboard and all, the moment it scrolled away.
        pinned = if (searchable || onCount > 0) {
            {
                MoreLayoutsControls(
                    query = query,
                    onQuery = { query = it },
                    searchable = searchable,
                    allCount = layouts.size,
                    onCount = onCount,
                    onlyOn = showingOn,
                    onOnlyOn = { onlyOn = it },
                )
            }
        } else {
            null
        },
    ) { padding ->
        // A stale deep link, or a build that dropped a language's converted
        // layouts, lands on a page with nothing to list: say so rather than
        // draw an intro to an empty grid.
        if (layouts.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                CaptionText(stringResource(R.string.languages_more_layouts_body))
            }
            return@WmLazyScreen
        }
        val layoutDirection = LocalLayoutDirection.current
        val gridState = rememberLazyGridState()
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = GridCellMin),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + GridEdge,
                end = padding.calculateEndPadding(layoutDirection) + GridEdge,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (needle.isEmpty() && !showingOn) {
                item(key = "intro", span = { GridItemSpan(maxLineSpan) }) { MoreLayoutsIntro() }
            }
            items(shown, key = { it.id }) { layout ->
                val on = settings.watch { layout.id in it.enabledLayoutIds }
                LayoutCard(
                    name = layout.name,
                    layoutId = layout.id,
                    on = on,
                    settings = settings,
                    onToggle = { enable -> toggle(layout.id, enable) },
                    footer = {
                        RulesLine(
                            binding = layout.binding,
                            layoutName = layout.name,
                            layoutOn = on,
                            refreshKey = rulesRefresh,
                        )
                    },
                )
            }
            if (shown.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (needle.isNotEmpty()) {
                            stringResource(R.string.languages_more_layouts_search_empty, query.trim())
                        } else {
                            stringResource(R.string.languages_more_layouts_on_empty)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** One converted layout, resolved once for the grid. */
private class MoreLayout(val id: String, val name: String, val binding: KeymanBinding) {
    /** The name and the Keyman id, lowercased once, for the search box. */
    val searchKey: String = "$name\n${binding.keyboardId}".lowercase()
}

/**
 * The strip pinned under the bar: a search box once there are enough layouts
 * to search, and the All / On filter once any of them is on.
 */
@Composable
private fun MoreLayoutsControls(
    query: String,
    onQuery: (String) -> Unit,
    searchable: Boolean,
    allCount: Int,
    onCount: Int,
    onlyOn: Boolean,
    onOnlyOn: (Boolean) -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GridEdge, vertical = 4.dp),
    ) {
        if (searchable) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.languages_more_layouts_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(CommonR.string.common_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onCount > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = if (searchable) 4.dp else 0.dp),
            ) {
                FilterChip(
                    selected = !onlyOn,
                    onClick = { onOnlyOn(false) },
                    label = { ChipLabel(stringResource(R.string.languages_more_layouts_filter_all), allCount) },
                )
                FilterChip(
                    selected = onlyOn,
                    onClick = { onOnlyOn(true) },
                    label = { ChipLabel(stringResource(R.string.languages_more_layouts_filter_on), onCount) },
                )
            }
        }
    }
}

/** A filter chip's name with its count beside it, the count muted. */
@Composable
private fun ChipLabel(text: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text)
        Spacer(Modifier.width(6.dp))
        Text(
            count.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the page is, once, above the cards: where these keyboards come from and
 * why some of them download rules. One short paragraph, since the cards and the
 * button under each explain the rest by being there. Only on the unfiltered
 * view: a reader who is searching already knows what the page is.
 */
@Composable
private fun MoreLayoutsIntro() {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.languages_more_layouts_body),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/**
 * The typing-rules line under a card. Rules that are missing get a full-width
 * button, "Get typing rules", in the error colours when the layout is already
 * on and so typing the wrong letters right now; rules on the device get a
 * quiet "ready" with a remove button, which asks first, since it is the only
 * thing on the page that makes a working layout type the wrong letters.
 *
 * Kept out of the card's toggle: pressing the button must not also switch the
 * layout.
 */
@Composable
private fun RulesLine(
    binding: KeymanBinding,
    layoutName: String,
    layoutOn: Boolean,
    refreshKey: Int,
) {
    val rules = rememberKeymanRules(binding, layoutName, refreshKey)
    val colors = MaterialTheme.colorScheme
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.languages_keyman_remove_title, layoutName)) },
            text = { Text(stringResource(R.string.languages_keyman_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    rules.remove()
                }) { Text(stringResource(CommonR.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 5.dp, end = 5.dp, bottom = 5.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        when {
            rules.busy -> StatusRow(
                label = if (rules.progress > 0) {
                    stringResource(R.string.languages_keyman_rules_downloading, rules.progress)
                } else {
                    stringResource(R.string.languages_keyman_rules_checking)
                },
                tint = colors.onSurfaceVariant,
                glyph = {
                    CircularProgressIndicator(
                        progress = { rules.progress / 100f },
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(StatusGlyph),
                    )
                },
            )
            rules.installed -> StatusRow(
                label = stringResource(R.string.languages_keyman_card_rules_ready),
                tint = colors.primary,
                glyph = {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(StatusGlyph),
                    )
                },
                action = {
                    IconButton(onClick = { confirmRemove = true }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(
                                R.string.languages_keyman_card_rules_remove_desc,
                                layoutName,
                            ),
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
            else -> {
                // Missing, or a download that failed: either way the one thing
                // to do is fetch them, so that is the whole line.
                val urgent = layoutOn || rules.failed
                // Named for the layout, so TalkBack says which card's rules
                // the button fetches rather than a bare "Get typing rules".
                val description = stringResource(
                    if (rules.failed) {
                        R.string.languages_keyman_card_rules_retry_desc
                    } else {
                        R.string.languages_keyman_card_rules_download_desc
                    },
                    layoutName,
                )
                FilledTonalButton(
                    onClick = { rules.download() },
                    colors = if (urgent) {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.errorContainer,
                            contentColor = colors.onErrorContainer,
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .semantics { contentDescription = description },
                ) {
                    Icon(
                        if (rules.failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(StatusGlyph),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (rules.failed) {
                                R.string.languages_keyman_card_rules_retry
                            } else {
                                R.string.languages_keyman_card_rules_get
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A glyph, a few words and an optional action, on one line under a card. */
@Composable
private fun StatusRow(
    label: String,
    tint: Color,
    glyph: @Composable () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(start = 8.dp),
    ) {
        glyph()
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/** Fewest layouts that earn a search box; below this the grid is the search. */
private const val SEARCH_FROM = 6

/** Narrowest a grid cell gets before the grid drops a column. */
private val GridCellMin = 150.dp

private val GridGap = 8.dp
private val GridEdge = 16.dp
private val StatusGlyph = 16.dp
