package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.MenuBook
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Contacts
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DriveFileRenameOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HelpOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassEmpty
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LibraryAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Person
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhoneAndroid
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.prediction.WordFacts
import com.wasimaster.wmkeyboard.core.prediction.WordRanks
import com.wasimaster.wmkeyboard.core.settings.RankControl
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.WordCard
import com.wasimaster.wmkeyboard.ime.WordCardAction
import java.text.NumberFormat

/**
 * The word card (#99, #138): where a suggested word comes from, its place in
 * the lists that have it, every control over how it is spelled and how hard
 * it competes, and the actions the held-word menu offers, all in one place.
 *
 * It is what the held-word menu's "Edit" opens, and it is meant to spare a
 * trip to the personal dictionary screen: the same word can be respelled,
 * have its capitals kept, be weighted, ranked, deleted or banned from here.
 *
 * Rendered as a [Popup] over the whole keyboard window with a scrim, like
 * [FavouritesReorderPopup] — the keyboard cannot raise a Compose Dialog,
 * which needs a window token the IME does not hand out. The scrim is also
 * what keeps the keys under it from typing while the card is up.
 *
 * The rank control edits a working copy and hands the result over on Done,
 * so ten presses of a stepper are one lexicon rebuild rather than ten. The
 * actions apply at once; the service refreshes [card] after each, which is
 * how a deleted word's row disappears and a never-suggested word's button
 * turns into "suggest again".
 *
 * Inside the card, those refreshes move rather than jump: the sources cross-
 * fade from "loading" into the list and the list eases to its new height, a
 * stepper's value rolls up or down with the press, and the actions row eases
 * as buttons come and go. The card's own arrival is [GrowingPopupHost]'s.
 */
@Composable
internal fun WordCardPopup(card: WordCard?, onAction: (WordCardAction) -> Unit) {
    GrowingPopupHost(card) { shown, growth ->
        // On its way out the service has already closed the card; nothing on
        // it answers any more.
        WordCardWindow(shown, growth) { action -> if (!growth.leaving) onAction(action) }
    }
}

/**
 * The card's window. It grows in from the strip that opened it and folds back
 * the same way ([growth]), its scrim fading with it.
 */
@Composable
private fun WordCardWindow(card: WordCard, growth: PopupGrowth, onAction: (WordCardAction) -> Unit) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    // The working values of the rank control, reset when the service hands
    // over a refreshed card (a delete resets the weight to 0, say).
    var weight by remember(card.word, card.learnedCount) { mutableIntStateOf(card.learnedCount) }
    var offset by remember(card.word, card.rankOffset) { mutableIntStateOf(card.rankOffset) }
    val showsWeight = card.rankControl != RankControl.RANK_OFFSET
    val showsOffset = card.rankControl != RankControl.LEARNED_WEIGHT
    val done = {
        if (showsWeight && weight != card.learnedCount) {
            onAction(WordCardAction.SetLearnedWeight(weight))
        }
        if (showsOffset && offset != card.rankOffset) onAction(WordCardAction.SetOffset(offset))
        onAction(WordCardAction.Dismiss)
    }
    val dismiss = { onAction(WordCardAction.Dismiss) }

    Popup(onDismissRequest = dismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .popupScrim(growth)
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = kb.menuShape(),
                color = kb.popup,
                border = kb.popupSurfaceBorder(),
                shadowElevation = elevationFor(kb.menuShapeKind, 8.dp),
                modifier = Modifier
                    .padding(12.dp)
                    .popupGrow(growth)
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.94f)
                    // Taps inside the card must not reach the scrim.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = card.word,
                            style = MaterialTheme.typography.titleMedium,
                            color = kb.popupText,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = dismiss, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.ime_word_card_close_desc),
                                tint = kb.popupText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SectionTitle(stringResource(R.string.ime_word_card_sources_title), kb.popupText)
                        WordSources(card, kb.popupText, animate)
                        Spacer(Modifier.size(8.dp))
                        Spelling(card, onAction, kb.popupText, animate)
                        if (showsWeight) {
                            SectionTitle(stringResource(R.string.ime_word_card_weight_title), kb.popupText)
                            Stepper(
                                value = if (weight == 0) {
                                    stringResource(R.string.ime_word_card_weight_none)
                                } else {
                                    NumberFormat.getIntegerInstance().format(weight)
                                },
                                level = weight,
                                canLower = weight > 0,
                                canRaise = weight < UserLexicon.MAX_COUNT,
                                onLower = { weight = (weight - weightStep(weight - 1)).coerceAtLeast(0) },
                                onRaise = {
                                    weight = (weight + weightStep(weight)).coerceAtMost(UserLexicon.MAX_COUNT)
                                },
                                color = kb.popupText,
                                animate = animate,
                            )
                            Hint(stringResource(R.string.ime_word_card_weight_hint), kb.popupText)
                        }
                        if (showsOffset) {
                            SectionTitle(stringResource(R.string.ime_word_card_offset_title), kb.popupText)
                            Stepper(
                                value = if (offset == 0) {
                                    stringResource(R.string.ime_word_card_offset_normal)
                                } else {
                                    stringResource(R.string.ime_word_card_offset_value, offset)
                                },
                                level = offset,
                                canLower = offset > WordRanks.MIN_STEPS,
                                canRaise = offset < WordRanks.MAX_STEPS,
                                onLower = { offset-- },
                                onRaise = { offset++ },
                                color = kb.popupText,
                                animate = animate,
                            )
                            Hint(stringResource(R.string.ime_word_card_offset_hint), kb.popupText)
                        }
                    }
                    Actions(card, onAction, done, animate)
                }
            }
        }
    }
}

/**
 * How the word is spelled, and whether it stays that way (#138).
 *
 * "Change" hands the keys to the spelling bar rather than opening a text box:
 * the card is a window over the whole keyboard, so there is nothing under it
 * to type with, and an IME cannot raise a field for itself either. The switch
 * is the personal dictionary's "keep these capitals" (#100) — the reason the
 * issue asked for a respell at all was a word learned with the wrong capital,
 * and pinning is what stops the vote putting it back.
 */
@Composable
private fun Spelling(card: WordCard, onAction: (WordCardAction) -> Unit, color: Color, animate: Boolean) {
    SectionTitle(stringResource(R.string.ime_word_card_spelling_title), color)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = card.word,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.weight(1f),
        )
        ActionButton(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.ime_word_card_respell)) {
            onAction(WordCardAction.EditSpelling)
        }
    }
    // Only the personal dictionary keeps a spelling, so there is nothing to
    // pin on a word it has not learned; respelling one is what learns it.
    val pinnable = card.learnedCount > 0
    val labelAlpha by animateFloatAsState(
        targetValue = if (pinnable) 1f else 0.5f,
        animationSpec = if (animate) tween(SECTION_MS) else snap(),
        label = "wordCardPinnable",
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ime_word_card_keep_capitals),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = labelAlpha },
        )
        Switch(
            checked = card.casePinned,
            enabled = pinnable,
            onCheckedChange = { onAction(WordCardAction.SetCasePinned(it)) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = color,
                checkedTrackColor = color.copy(alpha = 0.4f),
                uncheckedThumbColor = color.copy(alpha = 0.6f),
                uncheckedTrackColor = color.copy(alpha = 0.15f),
            ),
        )
    }
    Hint(stringResource(R.string.ime_word_card_keep_capitals_hint), color)
    Spacer(Modifier.size(8.dp))
}

/**
 * One press of the weight stepper: a single count under 10, a tenth of the
 * next power of ten above it — the personal dictionary screen's ladder, so
 * the two controls agree on what one press does.
 */
private fun weightStep(weight: Int): Int = when {
    weight < 10 -> 1
    weight < 100 -> 10
    weight < 1_000 -> 100
    weight < 10_000 -> 1_000
    weight < 100_000 -> 10_000
    else -> 100_000
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = color.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * The list of places the word was found, one row each; or why there are none.
 * The look-up cross-fades from its "loading" row into the list, and the list
 * eases to its new height when a refresh adds or drops a row.
 */
@Composable
private fun WordSources(card: WordCard, color: Color, animate: Boolean) {
    MotionCrossfade(
        target = card.facts != null,
        animate = animate,
        modifier = Modifier
            .fillMaxWidth()
            .easeSize(animate),
        label = "wordCardSources",
    ) { loaded ->
        val facts = card.facts
        Column {
            if (!loaded || facts == null) {
                SourceRow(Icons.Outlined.HourglassEmpty, stringResource(R.string.ime_word_card_loading), color)
            } else {
                WordSourceRows(card, facts, color)
            }
        }
    }
}

@Composable
private fun WordSourceRows(card: WordCard, facts: WordFacts, color: Color) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    facts.learned?.let { learned ->
        val lines = mutableListOf(
            if (learned.count >= ADDED_BY_HAND_COUNT) {
                stringResource(R.string.ime_word_card_learned_added)
            } else {
                pluralStringResource(R.plurals.ime_word_card_learned_seen, learned.count, learned.count)
            },
        )
        learned.langId?.takeIf { it.isNotBlank() }?.let { id ->
            lines += stringResource(R.string.ime_word_card_learned_language, card.packLabels[id] ?: id)
        }
        if (learned.casePinned) lines += stringResource(R.string.ime_word_card_case_pinned)
        SourceRow(Icons.Outlined.Person, lines.joinToString(" · "), color)
    }
    if (facts.swipeShapes > 0) {
        SourceRow(
            Icons.Outlined.Gesture,
            pluralStringResource(R.plurals.ime_word_card_swipe_shapes, facts.swipeShapes, facts.swipeShapes),
            color,
        )
    }
    if (facts.pendingSightings > 0) {
        SourceRow(
            Icons.Outlined.HourglassEmpty,
            pluralStringResource(R.plurals.ime_word_card_pending, facts.pendingSightings, facts.pendingSightings),
            color,
        )
    }
    for (pack in listOfNotNull(facts.primary) + facts.secondary) {
        val name = card.packLabels[pack.langId] ?: pack.langId
        val text = buildString {
            append(stringResource(R.string.ime_word_card_pack, name))
            if (pack.rank > 0 && pack.vocabularySize > 0) {
                append(" · ")
                append(
                    stringResource(
                        R.string.ime_word_card_pack_rank,
                        numbers.format(pack.rank),
                        numbers.format(pack.vocabularySize),
                    ),
                )
            }
        }
        SourceRow(Icons.AutoMirrored.Outlined.MenuBook, text, color)
    }
    if (facts.customFrequency > 0) {
        SourceRow(Icons.Outlined.Description, stringResource(R.string.ime_word_card_custom), color)
    }
    if (facts.system) {
        SourceRow(Icons.Outlined.PhoneAndroid, stringResource(R.string.ime_word_card_system), color)
    }
    if (facts.contact) SourceRow(Icons.Outlined.Contacts, stringResource(R.string.ime_word_card_contact), color)
    if (facts.app) SourceRow(Icons.Outlined.Apps, stringResource(R.string.ime_word_card_app), color)
    if (card.blacklisted) {
        SourceRow(Icons.Outlined.VisibilityOff, stringResource(R.string.ime_word_card_blacklisted), color)
    }
    if (facts.unknown && facts.pendingSightings == 0 && !card.blacklisted) {
        SourceRow(Icons.Outlined.HelpOutline, stringResource(R.string.ime_word_card_unknown), color)
    }
}

@Composable
private fun SourceRow(icon: ImageVector, text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * A −/+ pair around the value they change. [level] is the number behind
 * [value]: the value rolls up when it rises and down when it falls, and a
 * button reaching its end dims rather than blinking off.
 */
@Composable
private fun Stepper(
    value: String,
    level: Int,
    canLower: Boolean,
    canRaise: Boolean,
    onLower: () -> Unit,
    onRaise: () -> Unit,
    color: Color,
    animate: Boolean,
) {
    val spec: AnimationSpec<Float> = if (animate) tween(SECTION_MS) else snap()
    val lowerAlpha by animateFloatAsState(if (canLower) 1f else 0.35f, spec, label = "wordCardLower")
    val raiseAlpha by animateFloatAsState(if (canRaise) 1f else 0.35f, spec, label = "wordCardRaise")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onLower, enabled = canLower) {
            Icon(
                Icons.Outlined.Remove,
                contentDescription = stringResource(R.string.ime_word_card_step_down_desc),
                tint = color,
                modifier = Modifier.graphicsLayer { alpha = lowerAlpha },
            )
        }
        StepperValue(value, level, color, animate, Modifier.weight(1f))
        IconButton(onClick = onRaise, enabled = canRaise) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = stringResource(R.string.ime_word_card_step_up_desc),
                tint = color,
                modifier = Modifier.graphicsLayer { alpha = raiseAlpha },
            )
        }
    }
}

/** The stepper's value, rolling in from below as [level] rises and from above as it falls. */
@Composable
private fun StepperValue(value: String, level: Int, color: Color, animate: Boolean, modifier: Modifier) {
    if (!animate) {
        StepperText(value, color, modifier)
        return
    }
    AnimatedContent(
        targetState = level to value,
        modifier = modifier,
        transitionSpec = {
            val up = if (targetState.first >= initialState.first) 1 else -1
            slideInVertically(tween(SECTION_MS, easing = FastOutSlowInEasing)) { h -> up * h / 2 } +
                fadeIn(tween(SECTION_MS)) togetherWith
                slideOutVertically(tween(STEP_OUT_MS)) { h -> -up * h / 2 } + fadeOut(tween(STEP_OUT_MS)) using null
        },
        contentAlignment = Alignment.Center,
        label = "wordCardStep",
    ) { (_, shown) -> StepperText(shown, color, Modifier.fillMaxWidth()) }
}

@Composable
private fun StepperText(value: String, color: Color, modifier: Modifier) {
    Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = modifier,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/**
 * The menu's actions again, plus Done. Each applies at once except Done.
 * The row eases to its new height as a refresh adds or drops a button, and
 * "never suggest" cross-fades into "suggest again" and back.
 */
@Composable
private fun Actions(card: WordCard, onAction: (WordCardAction) -> Unit, done: () -> Unit, animate: Boolean) {
    val facts = card.facts
    val deletable = facts != null &&
        (
            facts.learned != null || facts.system || facts.pendingSightings > 0 ||
                facts.rankOffset != 0 || facts.importedList
            )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .easeSize(animate),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            card.typed?.let { typed ->
                ActionButton(Icons.Outlined.LibraryAdd, stringResource(R.string.ime_word_menu_add, typed)) {
                    onAction(WordCardAction.Add)
                }
            }
            if (deletable) {
                ActionButton(Icons.Outlined.Delete, stringResource(CommonR.string.common_delete)) {
                    onAction(WordCardAction.Delete)
                }
            }
            if (facts != null && facts.swipeShapes > 0) {
                ActionButton(Icons.Outlined.Gesture, stringResource(R.string.ime_word_card_forget_shapes)) {
                    onAction(WordCardAction.ForgetShapes)
                }
            }
            MotionCrossfade(
                target = card.blacklisted,
                animate = animate,
                modifier = if (animate) Modifier.animateContentSize(tween(SECTION_MS)) else Modifier,
                label = "wordCardBan",
            ) { blacklisted ->
                // The one fading out has been answered already: a tap on it
                // would undo what was just done.
                Box(Modifier.blockTouches(blacklisted != card.blacklisted)) {
                    if (blacklisted) {
                        ActionButton(
                            Icons.Outlined.Visibility,
                            stringResource(R.string.ime_word_menu_allow_again, card.word),
                        ) { onAction(WordCardAction.AllowAgain) }
                    } else {
                        ActionButton(
                            Icons.Outlined.VisibilityOff,
                            stringResource(R.string.ime_suggestion_never_suggest, card.word),
                        ) { onAction(WordCardAction.NeverSuggest) }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = done) { Text(stringResource(CommonR.string.common_done)) }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

/** A section easing to its new height when a refresh changes it; as it is under reduce motion. */
private fun Modifier.easeSize(animate: Boolean): Modifier =
    if (animate) animateContentSize(tween(SECTION_MS, easing = FastOutSlowInEasing)) else this

/** [UserLexicon.addWord]'s default boost: at or past it, a word reads as added by hand. */
private const val ADDED_BY_HAND_COUNT = 200

/** A section easing to its new height, and a stepper's value rolling in; the old value leaves faster. */
private const val SECTION_MS = 180
private const val STEP_OUT_MS = 120
