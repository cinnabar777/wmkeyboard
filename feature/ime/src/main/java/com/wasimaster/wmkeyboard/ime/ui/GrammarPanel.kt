package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDropDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Done
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FilterList
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.core.grammar.GrammarFix
import com.wasimaster.wmkeyboard.core.grammar.GrammarLint
import com.wasimaster.wmkeyboard.core.settings.GrammarCategory
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.GrammarLintKind
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.AiChatAction
import com.wasimaster.wmkeyboard.ime.DeepLWriteUi
import com.wasimaster.wmkeyboard.ime.aichat.AskAiContext
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.visibleGrammarLints
import kotlinx.coroutines.delay

/**
 * The colour dot each [GrammarCategory] wears on a card and in the filter.
 * The buckets themselves live in `:core:settings` beside [GrammarLintKind],
 * because the filter in settings sorts by them too; only the paint is here.
 */
private data class CategoryColors(val light: Color, val dark: Color)

private val CATEGORY_COLORS = mapOf(
    GrammarCategory.CORRECTNESS to CategoryColors(Color(0xFFD64545), Color(0xFFEF7070)),
    GrammarCategory.CLARITY to CategoryColors(Color(0xFF2D7FD6), Color(0xFF6AAFF5)),
    GrammarCategory.ENGAGEMENT to CategoryColors(Color(0xFF15845D), Color(0xFF43C593)),
    GrammarCategory.DELIVERY to CategoryColors(Color(0xFF8A56C9), Color(0xFFB68AF0)),
)

private fun GrammarCategory.color(isDark: Boolean): Color {
    val colors = CATEGORY_COLORS.getValue(this)
    return if (isDark) colors.dark else colors.light
}

/**
 * The bucket a lint's kind falls into. A kind the app does not know — a newer
 * engine's — is painted as [GrammarCategory.ENGAGEMENT] rather than dropped,
 * the same way the filter shows it rather than hiding it.
 */
private fun categoryFor(kind: String): GrammarCategory =
    GrammarLintKind.forKind(kind)?.category ?: GrammarCategory.ENGAGEMENT

/** "WordChoice" -> "Word choice"; the fallback for a kind with no label of its own. */
private fun prettifyKind(kind: String): String {
    if (kind.isBlank()) return ""
    return kind.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        .lowercase()
        .replaceFirstChar { it.uppercase() }
}

/** The name on the card header for a lint's kind, translated where we know it. */
@Composable
private fun kindLabel(kind: String): String {
    val known = GrammarLintKind.forKind(kind)
    return if (known != null) stringResource(known.labelRes) else prettifyKind(kind)
}

/**
 * The grammar panel's service callbacks, bundled into one [KeyboardScreen]
 * parameter for the reason [TranslateCallbacks] is: its caller sits against
 * the JVM's 64K method-size ceiling. This bundle replaced eight parameters.
 */
data class GrammarCallbacks(
    val onFix: (GrammarLint, GrammarFix) -> Unit = { _, _ -> },
    val onFixAll: () -> Unit = {},
    val onDismiss: (GrammarLint) -> Unit = {},
    val onDialect: (GrammarDialect) -> Unit = {},
    val onFocus: (GrammarLint) -> Unit = {},
    val onKindShown: (GrammarLintKind, Boolean) -> Unit = { _, _ -> },
    val onCategoryShown: (GrammarCategory, Boolean) -> Unit = { _, _ -> },
    val onShowAllKinds: () -> Unit = {},
    /** The DeepL Write chip: rewrite the field with DeepL (#331). */
    val onRephrase: () -> Unit = {},
    /** Replace on the DeepL Write card. */
    val onRephraseApply: () -> Unit = {},
    /** The X on the DeepL Write card. */
    val onRephraseDismiss: () -> Unit = {},
)

/**
 * Offline grammar strip (Harper engine): sits above the key rows, which stay
 * visible so issues can be fixed by typing too. Each issue is a Grammarly-style
 * card — category header, struck-through original, tappable fix chips and an
 * explanation — in a scrollable list. "Fix all" applies each issue's top
 * suggestion; the X on a card hides that issue until the text changes. The
 * dialect chip switches the English variant Harper checks, and the funnel
 * beside it picks which kinds of issue are worth showing at all.
 *
 * With DeepL Write set up in settings (#331), one more chip asks DeepL to
 * rewrite the whole field, and its answer takes the issue list's place until
 * Replace or its X. Without it the panel is exactly as it was.
 */
@Composable
internal fun GrammarPanel(
    state: KeyboardUiState,
    callbacks: GrammarCallbacks,
) {
    val onFix = callbacks.onFix
    val onFixAll = callbacks.onFixAll
    val onDismiss = callbacks.onDismiss
    val onFocus = callbacks.onFocus
    val kb = LocalKbTheme.current
    // Opt-in (Accessibility): an applied fix plays out on its card, the list
    // closes up around it, and the body cross-fades between its states.
    val animate = kb.motion.toolResults
    val grammar = state.grammar
    // Refused in a password field: the chip would send the whole field to
    // DeepL, so it is not offered there (the service refuses it too).
    val deeplWrite = state.settings.translate.deepl.writeActive && !state.secureField
    val rephrase = grammar.rephrase
    var pickerOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val hidden = state.settings.grammarHiddenKinds
    // What the panel is actually about: the cards, the count and "Fix all" all
    // read this, so a filtered-out issue is not one "Fix all" quietly rewrites.
    val lints = remember(grammar.lints, hidden) { state.visibleGrammarLints }
    // How many of each kind the last check found, filter or no filter — the
    // filter rows need the numbers they are hiding, not the ones left over.
    val countByKind = remember(grammar.lints) {
        grammar.lints.mapNotNull { GrammarLintKind.forKind(it.kind) }
            .groupingBy { it }
            .eachCount()
    }
    val fixable = lints.count { it.suggestions.isNotEmpty() }
    // The ring's regions. Seed-only (panelFocusSeedOnly): while the panel is
    // open the user is editing the field, so no arrow ever summons the ring —
    // only opening the tool from the leader does. CHIPS is the header row —
    // dialect, filter, and "Fix all" when there is anything to fix; RESULTS
    // activates a card the way "Fix all" would fix it (its top suggestion), or
    // jumps the caret to it when it has none.
    val chipCount = if (fixable > 0) 3 else 2
    PanelFocusTarget(
        panel = PanelMode.GRAMMAR,
        region = FocusRegion.CHIPS,
        count = chipCount,
        columns = chipCount,
    ) { index ->
        when (index) {
            0 -> pickerOpen = true
            1 -> filterOpen = true
            else -> onFixAll()
        }
    }
    PanelFocusTarget(
        panel = PanelMode.GRAMMAR,
        region = FocusRegion.RESULTS,
        count = lints.size,
        columns = 1,
    ) { index ->
        lints.getOrNull(index)?.let { lint ->
            val top = lint.suggestions.firstOrNull()
            if (top != null) onFix(lint, top) else onFocus(lint)
        }
    }
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedLint = state.focusedIndex(FocusRegion.RESULTS)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(146.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ime_grammar_title),
                color = kb.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Box {
                Row(
                    modifier = Modifier
                        .clip(kb.chipShape())
                        .background(kb.chip)
                        .chipBorder(kb, kb.chipShape())
                        .focusRing(focusedChip == 0, kb.chipShape())
                        .clickable { pickerOpen = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(state.settings.grammarDialect.labelRes),
                        color = kb.chipText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = stringResource(R.string.ime_grammar_dialect_desc),
                        modifier = Modifier.size(18.dp),
                        tint = kb.toolbarIcon,
                    )
                }
                if (pickerOpen) {
                    GrammarDialectPicker(
                        current = state.settings.grammarDialect,
                        onPick = {
                            pickerOpen = false
                            callbacks.onDialect(it)
                        },
                        onDismiss = { pickerOpen = false },
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Box {
                Icon(
                    Icons.Outlined.FilterList,
                    contentDescription = stringResource(R.string.ime_grammar_filter_desc),
                    modifier = Modifier
                        .clip(kb.chipShape())
                        .background(if (hidden.isEmpty()) kb.chip else kb.chipActive)
                        .chipBorder(kb, kb.chipShape())
                        .focusRing(focusedChip == 1, kb.chipShape())
                        .clickable { filterOpen = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .size(16.dp),
                    tint = if (hidden.isEmpty()) kb.toolbarIcon else kb.chipActiveText,
                )
                if (filterOpen) {
                    GrammarFilterPicker(
                        hidden = hidden,
                        countByKind = countByKind,
                        onCategory = callbacks.onCategoryShown,
                        onKind = callbacks.onKindShown,
                        onShowAll = callbacks.onShowAllKinds,
                        onDismiss = { filterOpen = false },
                    )
                }
            }
            if (lints.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                MotionCrossfade(lints.size, animate, label = "grammarCount") { count ->
                    Text(
                        pluralStringResource(
                            R.plurals.ime_grammar_issue_count,
                            count,
                            count,
                        ),
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            if (deeplWrite) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Outlined.EditNote,
                    contentDescription = stringResource(R.string.ime_grammar_deepl_action_desc),
                    modifier = Modifier
                        .clip(kb.chipShape())
                        .background(if (rephrase != null) kb.chipActive else kb.chip)
                        .chipBorder(kb, kb.chipShape())
                        .clickable(enabled = rephrase?.working != true) { callbacks.onRephrase() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .size(16.dp),
                    tint = if (rephrase != null) kb.chipActiveText else kb.toolbarIcon,
                )
            }
            Spacer(Modifier.weight(1f))
            if (grammar.checking || rephrase?.working == true) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = kb.accent,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (fixable > 0) {
                Row(
                    modifier = Modifier
                        .clip(kb.chipShape())
                        .background(kb.chipActive)
                        .chipBorder(kb, kb.chipShape())
                        .focusRing(focusedChip == 2, kb.chipShape())
                        .clickable { onFixAll() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Done,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = kb.chipActiveText,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.ime_grammar_fix_all_action, fixable),
                        color = kb.chipActiveText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (rephrase != null && !rephrase.working) {
            DeepLWriteCard(
                rephrase = rephrase,
                onApply = callbacks.onRephraseApply,
                onDismiss = callbacks.onRephraseDismiss,
            )
            Spacer(Modifier.height(6.dp))
        }
        val body = when {
            // The card holds the panel's body while it is up: 146dp has no
            // room for it and the issue list both, and it is what was asked for.
            rephrase != null && !rephrase.working -> GrammarBody.REPHRASE
            !grammar.available -> GrammarBody.UNAVAILABLE
            grammar.sourceText.isEmpty() && !grammar.checking -> GrammarBody.EMPTY
            lints.isEmpty() && grammar.checkedOnce && !grammar.checking -> GrammarBody.CLEAR
            else -> GrammarBody.LIST
        }
        if (body != GrammarBody.REPHRASE) {
            MotionCrossfade(
                body,
                animate,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "grammarBody",
            ) { shown ->
                // The state on its way out still takes taps until its fade
                // ends; a card fixed twice is two edits of the field.
                Box(Modifier.fillMaxSize().blockTouches(shown != body)) {
                    when (shown) {
                        GrammarBody.REPHRASE -> Unit
                        GrammarBody.UNAVAILABLE -> GrammarHint(
                            stringResource(R.string.ime_grammar_unavailable_info),
                        )
                        GrammarBody.EMPTY -> GrammarHint(
                            stringResource(R.string.ime_grammar_empty_hint),
                        )
                        GrammarBody.CLEAR -> GrammarClear(filtered = grammar.lints.isNotEmpty(), animate = animate)
                        GrammarBody.LIST -> {
                            val listState = rememberLazyListState()
                            ScrollFocusIntoView(focusedLint) { listState.animateScrollToItem(it) }
                            val keys = remember(lints) { grammarLintKeys(lints) }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemsIndexed(lints, key = { index, _ -> keys[index] }) { index, lint ->
                                    GrammarLintCard(
                                        lint, onFix, onDismiss, onFocus,
                                        focused = index == focusedLint,
                                        sourceText = grammar.sourceText,
                                        animate = animate,
                                        modifier = if (animate) {
                                            Modifier.animateItem(
                                                fadeInSpec = tween(GrammarFadeInMs, delayMillis = GrammarFadeInDelayMs),
                                                placementSpec = GrammarPlacement,
                                                fadeOutSpec = tween(GrammarFadeOutMs),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the grammar panel's body is showing, for the cross-fade between them. */
private enum class GrammarBody { REPHRASE, UNAVAILABLE, EMPTY, CLEAR, LIST }

/**
 * A key for each card that survives the field changing around it. Offsets will
 * not do: a fix early in the text shifts every issue after it, and a card keyed
 * on its offset would leave and arrive again instead of gliding up. The kind
 * and the words, numbered when the same pair turns up twice, stay put.
 */
internal fun grammarLintKeys(lints: List<GrammarLint>): List<String> {
    val seen = HashMap<String, Int>()
    return lints.map { lint ->
        val base = lint.kind + "\u0000" + lint.original
        val n = seen.getOrDefault(base, 0)
        seen[base] = n + 1
        "$base\u0000$n"
    }
}

/**
 * Nothing to show: either nothing to fix at all, or the filter hiding all of
 * it. The tick pops in when it is the first; the funnel never does, since
 * nothing was achieved.
 */
@Composable
private fun GrammarClear(filtered: Boolean, animate: Boolean) {
    val kb = LocalKbTheme.current
    val pop = remember { Animatable(if (animate && !filtered) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (pop.value < 1f) pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
    }
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (filtered) Icons.Outlined.FilterList else Icons.Outlined.Check,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    val p = pop.value
                    val s = 0.4f + 0.6f * p
                    scaleX = s
                    scaleY = s
                    alpha = p.coerceIn(0f, 1f)
                },
            tint = kb.accent,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(
                if (filtered) {
                    // Not "all clear": the issues are there, the filter is
                    // simply not showing them.
                    R.string.ime_grammar_all_filtered_empty
                } else {
                    R.string.ime_grammar_no_issues_empty
                },
            ),
            color = kb.secondaryText,
            fontSize = 13.sp,
        )
    }
}

/** A card's fade in once the cards around it have made room, and out first. */
private const val GrammarFadeInMs = 180
private const val GrammarFadeInDelayMs = 60
private const val GrammarFadeOutMs = 160

/** The cards under a fixed one gliding up into its place, without a wobble. */
private val GrammarPlacement = spring(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/**
 * The fix just applied, played out on its card for as long as the card is
 * still there: the old words dim under their strike, the new ones slide in
 * beside a tick. The next check takes the card away and the list closes up.
 */
@Composable
private fun AppliedFixRow(original: String, replacement: String, color: Color) {
    val kb = LocalKbTheme.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(AppliedFixMs, easing = FastOutSlowInEasing)) }
    val slide = with(LocalDensity.current) { 12.dp.toPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (original.isNotBlank()) {
            Text(
                original,
                color = color,
                fontSize = 13.sp,
                textDecoration = TextDecoration.LineThrough,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 120.dp)
                    .graphicsLayer { alpha = 1f - 0.55f * shown.value },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer {
                val p = shown.value
                alpha = p
                translationX = -slide * (1f - p)
            },
        ) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = kb.accent,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                replacement,
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val AppliedFixMs = 220

/**
 * How long a card holds its applied look if the next check keeps it anyway
 * (the fix did not satisfy the engine): back to its chips after this.
 */
private const val AppliedFixHoldMs = 1_500L

/**
 * DeepL Write's answer: its rewrite of the text sent, or why there is none.
 * Replace swaps the text sent for it; the X puts the issue list back.
 */
@Composable
private fun ColumnScope.DeepLWriteCard(
    rephrase: DeepLWriteUi,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val cardShape = kb.cardShape()
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .clip(cardShape)
            .background(kb.chip)
            .chipBorder(kb, cardShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.EditNote,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = kb.accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.ime_grammar_deepl_title),
                color = kb.secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            val unchanged = rephrase.error == null && rephrase.result == rephrase.source
            if (rephrase.error == null && rephrase.result.isNotEmpty() && !unchanged) {
                Text(
                    stringResource(R.string.ime_grammar_deepl_replace_action),
                    color = kb.chipActiveText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(kb.chipShape())
                        .background(kb.chipActive)
                        .chipBorder(kb, kb.chipShape())
                        .clickable { onApply() }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_grammar_deepl_dismiss_desc),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onDismiss() },
                tint = kb.secondaryText,
            )
        }
        Text(
            when {
                rephrase.error != null -> rephrase.error
                rephrase.result == rephrase.source -> stringResource(R.string.ime_grammar_deepl_unchanged)
                else -> rephrase.result
            },
            color = if (rephrase.error != null) kb.accent else kb.suggestionText,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun GrammarHint(text: String) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun GrammarLintCard(
    lint: GrammarLint,
    onFix: (GrammarLint, GrammarFix) -> Unit,
    onDismiss: (GrammarLint) -> Unit,
    onFocus: (GrammarLint) -> Unit,
    focused: Boolean = false,
    /** The field text the lint is about, for Ask AI's sentence (#352). */
    sourceText: String = "",
    animate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val tools = LocalSelectionTools.current
    val category = categoryFor(lint.kind)
    val catColor = category.color(kb.dark)
    val cardShape = kb.cardShape()
    // The label of the fix just tapped, while the card plays it out. Keyed on
    // the lint: a re-check that hands back a different issue under the same
    // key starts clean.
    var applied by remember(lint) { mutableStateOf<String?>(null) }
    LaunchedEffect(applied) {
        if (applied != null) {
            delay(AppliedFixHoldMs)
            applied = null
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // One fix per card: a second tap mid-play would edit the field twice.
            .blockTouches(applied != null)
            .clip(cardShape)
            .background(kb.chip)
            .chipBorder(kb, cardShape)
            .focusRing(focused, cardShape)
            // Tapping the card jumps the cursor to the issue in the field —
            // selecting the word for a swap, or parking at its end for a small
            // fix. The X and fix chips consume their own taps first.
            .clickable { onFocus(lint) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(catColor, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            val kind = kindLabel(lint.kind)
            val categoryLabel = stringResource(category.labelRes)
            Text(
                if (kind.isEmpty() || kind == categoryLabel) {
                    categoryLabel
                } else {
                    stringResource(R.string.ime_grammar_category_kind_label, categoryLabel, kind)
                },
                color = kb.secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tools.askAi) {
                // Ask AI why (#352): the checker's note, its fixes and the
                // sentence, so the answer can say whether the checker is right.
                val askLabel = stringResource(R.string.ime_ask_ai_label_grammar)
                val fixes = lint.suggestions.mapNotNull { fix -> fix.labelText?.takeIf { it.isNotBlank() } }
                val kindName = if (kind.isEmpty()) categoryLabel else kind
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = stringResource(R.string.ime_grammar_ask_ai_desc),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable {
                            tools.onAiChat(
                                AiChatAction.AskAbout(
                                    AskAiContext.grammar(lint, fixes, sourceText, kindName),
                                    askLabel,
                                    fromSelection = false,
                                ),
                            )
                        },
                    tint = kb.accent,
                )
                Spacer(Modifier.width(10.dp))
            }
            // Harper's explanation, to the clipboard (#348).
            if (lint.message.isNotBlank()) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(CommonR.string.common_copy),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { tools.onAiChat(AiChatAction.Copy(lint.message)) },
                    tint = kb.secondaryText,
                )
                Spacer(Modifier.width(10.dp))
            }
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_grammar_dismiss_desc),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onDismiss(lint) },
                tint = kb.secondaryText,
            )
        }
        val appliedLabel = applied
        if (appliedLabel != null) {
            AppliedFixRow(lint.original, appliedLabel, catColor)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (lint.original.isNotBlank()) {
                    Text(
                        lint.original,
                        color = catColor,
                        fontSize = 13.sp,
                        textDecoration = TextDecoration.LineThrough,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    if (lint.suggestions.isNotEmpty()) {
                        Text("→", color = kb.secondaryText, fontSize = 13.sp, maxLines = 1)
                    }
                }
                if (lint.suggestions.isEmpty()) {
                    Text(
                        lint.message,
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        lint.suggestions
                            .distinctBy { it.labelText?.trim() ?: it.labelRes }
                            .take(5)
                            .forEach { fix ->
                                // A fix with no text of its own (the delete fix)
                                // names itself with a resource instead.
                                val labelText = fix.labelText
                                val labelRes = fix.labelRes
                                val fixLabel = when {
                                    !labelText.isNullOrBlank() -> labelText
                                    labelRes != null -> stringResource(labelRes)
                                    else -> stringResource(R.string.ime_grammar_fix_action)
                                }
                                Text(
                                    fixLabel,
                                    color = kb.chipActiveText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(kb.chipShape())
                                        .background(kb.chipActive)
                                        .chipBorder(kb, kb.chipShape())
                                        .clickable {
                                            if (animate) applied = fixLabel
                                            onFix(lint, fix)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                    }
                }
            }
        }
        if (lint.suggestions.isNotEmpty() && lint.message.isNotBlank()) {
            Text(
                lint.message,
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Which kinds of issue the panel shows, two levels deep: the four categories,
 * each of which opens onto the kinds inside it.
 *
 * The filter is over kinds — a category row is a shortcut that sets every kind
 * inside it at once, and reads back as a tick when all of them are on, a dash
 * when only some are. That is why hiding "Engagement" and then re-showing
 * "Style" alone leaves the category dashed rather than silently re-hiding the
 * kind the user just asked for.
 *
 * Counts are of the last check, filter and all: a row has to say what it is
 * hiding, or turning a category off looks like it did nothing.
 */
@Composable
private fun GrammarFilterPicker(
    hidden: Set<GrammarLintKind>,
    countByKind: Map<GrammarLintKind, Int>,
    onCategory: (GrammarCategory, Boolean) -> Unit,
    onKind: (GrammarLintKind, Boolean) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    // One category open at a time: the popup is 260dp tall and four categories
    // of twenty kinds unfolded at once would be a scroll with no landmarks.
    var expanded by remember { mutableStateOf<GrammarCategory?>(null) }
    Popup(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 280.dp)
                .heightIn(max = 260.dp)
                .clip(kb.menuShape())
                .background(kb.popup)
                .popupBorder(kb, kb.menuShape())
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            GrammarCategory.entries.forEach { category ->
                val kinds = GrammarLintKind.of(category)
                val shown = kinds.count { it !in hidden }
                val open = expanded == category
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCategory(category, shown < kinds.size) }
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(category.color(kb.dark), CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(category.labelRes),
                        color = kb.suggestionText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    FilterCount(kinds.sumOf { countByKind[it] ?: 0 })
                    FilterMark(shown = shown, total = kinds.size)
                    Icon(
                        if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(R.string.ime_grammar_filter_expand_desc),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { expanded = if (open) null else category }
                            .padding(4.dp)
                            .size(16.dp),
                        tint = kb.secondaryText,
                    )
                }
                if (open) {
                    kinds.forEach { kind ->
                        val kindShown = kind !in hidden
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onKind(kind, !kindShown) }
                                .padding(start = 27.dp, end = 32.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(kind.labelRes),
                                color = kb.suggestionText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            FilterCount(countByKind[kind] ?: 0)
                            FilterMark(shown = if (kindShown) 1 else 0, total = 1)
                        }
                    }
                }
            }
            if (hidden.isNotEmpty()) {
                Text(
                    stringResource(R.string.ime_grammar_filter_show_all_action),
                    color = kb.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        // One write, not one per category: four edits of the
                        // same key would be four settings emissions for a
                        // button whose whole job is "back to the default".
                        .clickable { onShowAll() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** How many issues of this row's kind the last check found; blank at zero. */
@Composable
private fun FilterCount(count: Int) {
    if (count <= 0) return
    val kb = LocalKbTheme.current
    Text(
        count.toString(),
        color = kb.secondaryText,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier.padding(end = 6.dp),
    )
}

/** Tick for all shown, dash for some, nothing for none — in a fixed-width slot. */
@Composable
private fun FilterMark(shown: Int, total: Int) {
    val kb = LocalKbTheme.current
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when {
            shown == total -> Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = kb.accent,
            )
            shown > 0 -> Icon(
                Icons.Outlined.Remove,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = kb.secondaryText,
            )
        }
    }
}

@Composable
private fun GrammarDialectPicker(
    current: GrammarDialect,
    onPick: (GrammarDialect) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Popup(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 160.dp, max = 220.dp)
                .heightIn(max = 240.dp)
                .clip(kb.menuShape())
                .background(kb.popup)
                .popupBorder(kb, kb.menuShape())
                .padding(vertical = 4.dp),
        ) {
            GrammarDialect.entries.forEach { dialect ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(dialect) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(dialect.labelRes),
                        color = kb.suggestionText,
                        fontSize = 13.sp,
                        fontWeight = if (dialect == current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (dialect == current) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = kb.accent,
                        )
                    }
                }
            }
        }
    }
}
