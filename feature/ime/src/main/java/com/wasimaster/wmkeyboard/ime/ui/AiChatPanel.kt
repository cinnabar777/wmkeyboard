package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AttachFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.aichat.AiChatConversation
import com.wasimaster.wmkeyboard.core.aichat.AiChatMessage
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.ime.AiChatAction
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController.ModelChoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.common.R as CommonR

/*
 * The AI panel's chat mode (#280): a conversation with the model, on the
 * keyboard, next to the field the answer is for.
 *
 * What it draws is AiChatController's — the saved transcript and the one live
 * answer — read here exactly as the chat screen in the settings app reads them,
 * so the two are one chat on two surfaces. What it adds is what only a keyboard
 * has: a composer its own keys write into, the field behind it to quote from,
 * and Insert, which is the point of chatting here at all.
 *
 * No SelectionContainer anywhere. Selection handles and the copy toolbar are
 * popup windows, and a popup over an IME window is the untrusted-touch trap;
 * a whole answer, or one code block, is copied by a button instead.
 */

/**
 * How tall the panel asks to stand while the composer has the keys under it.
 * More than the other panels ask for: a conversation is read, not glanced at
 * (#352). What the screen cannot spare comes off the panel, never off the
 * keys: the body clamps it to the room left above them.
 */
internal val AiChatCompactHeight = 320.dp

/** How far the chat grows the keyboard while nothing is being typed; was 120dp before #352. */
internal val AiChatExtraHeight = 200.dp

/** The header's mode switch. [base] indices 0 and 1 of the ACTIONS region are these two. */
@Composable
internal fun AiModeSwitch(state: KeyboardUiState, focusedAction: Int?, onChat: (AiChatAction) -> Unit) {
    ToolPanelChip(
        stringResource(R.string.ime_ai_mode_actions),
        selected = !state.aiChat.open,
        modifier = Modifier.focusRing(focusedAction == 0),
    ) { onChat(AiChatAction.SetMode(chat = false)) }
    Spacer(Modifier.width(4.dp))
    ToolPanelChip(
        stringResource(R.string.ime_ai_mode_chat),
        selected = state.aiChat.open,
        modifier = Modifier.focusRing(focusedAction == 1),
    ) { onChat(AiChatAction.SetMode(chat = true)) }
    Spacer(Modifier.width(6.dp))
}

/** How many ACTIONS slots [AiChatHeaderActions] draws, after the mode switch. */
internal const val AiChatHeaderActionCount = 3

/**
 * The rest of the header in chat mode: which conversation this is (a tap opens
 * the list), a new chat, and the way over to the app. Slots [base]..[base]+2 of
 * the ACTIONS region; [activateAiChatHeader] is the same three for Enter.
 */
@Composable
internal fun RowScope.AiChatHeaderActions(
    state: KeyboardUiState,
    focusedAction: Int?,
    base: Int,
    onChat: (AiChatAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val version by AiChatController.storeVersion.collectAsState()
    val title = remember(version, state.aiChat.conversationId) {
        AiChatController.store(context).get(state.aiChat.conversationId)?.title.orEmpty()
    }.ifBlank { stringResource(R.string.ime_ai_chat_untitled) }
    val shape = kb.chipShape()
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(end = 4.dp)
            .clip(shape)
            .focusRing(focusedAction == base)
            .background(if (state.aiChat.showSessions) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable { onChat(AiChatAction.ToggleSessions) }
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (state.aiChat.showSessions) kb.chipActiveText else kb.chipText
        Text(
            title,
            color = ink,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = stringResource(R.string.ime_ai_chat_sessions_desc),
            tint = ink,
            modifier = Modifier.size(16.dp),
        )
    }
    ChatIconButton(
        Icons.Outlined.Add,
        stringResource(R.string.ime_ai_chat_new),
        modifier = Modifier.focusRing(focusedAction == base + 1, CircleShape),
    ) { onChat(AiChatAction.NewChat) }
    Spacer(Modifier.width(4.dp))
    ChatIconButton(
        Icons.AutoMirrored.Outlined.OpenInNew,
        stringResource(R.string.ime_ai_chat_open_in_app_desc),
        modifier = Modifier.focusRing(focusedAction == base + 2, CircleShape),
    ) { onChat(AiChatAction.OpenInApp()) }
    Spacer(Modifier.width(4.dp))
}

/** Enter on slot [index] (0-based within the chat's own three) of the header. */
internal fun activateAiChatHeader(index: Int, onChat: (AiChatAction) -> Unit) {
    when (index) {
        0 -> onChat(AiChatAction.ToggleSessions)
        1 -> onChat(AiChatAction.NewChat)
        2 -> onChat(AiChatAction.OpenInApp())
    }
}

@Composable
internal fun AiChatPanel(
    state: KeyboardUiState,
    onChat: (AiChatAction) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
) {
    val context = LocalContext.current
    val chat = state.aiChat
    val store = AiChatController.store(context)
    val version by AiChatController.storeVersion.collectAsState()
    val run by AiChatController.run.collectAsState()
    // Cheap file stats, keyed on the store so a model that finished
    // downloading while the panel was up is offered without a reopen.
    val choices = remember(version, state.settings.ai, chat.showSessions) {
        AiChatController.choices(context, state.settings.ai)
    }
    val choice = remember(choices, chat.modelKey, version) {
        choices.firstOrNull { it.key == chat.modelKey } ?: AiChatController.initialChoice(store, choices)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        when {
            choices.isEmpty() -> {
                ChatFocusTargets(emptyList(), listOf { onOpenToolSettings(ToolbarTool.AI) })
                NoBackend(state) { onOpenToolSettings(ToolbarTool.AI) }
            }
            chat.showSessions -> {
                val conversations = remember(version) { store.items() }
                Sessions(state, conversations, onChat)
            }
            else -> {
                val conversation = remember(version, chat.conversationId) { store.get(chat.conversationId) }
                val live = run?.takeIf { it.conversationId == chat.conversationId }
                // The picker steps aside while typing: every dp there belongs
                // to the conversation and the composer.
                if (!chat.composing && choices.size > 1) {
                    ModelRow(choices, choice, enabled = run == null, onChat)
                }
                Transcript(
                    state = state,
                    messages = conversation?.messages.orEmpty(),
                    live = live,
                    busy = run != null,
                    onChat = onChat,
                    modifier = Modifier.weight(1f),
                )
                Composer(
                    state = state,
                    generating = run != null,
                    canSend = choice != null && chat.draft.isNotBlank() && run == null,
                    onChat = onChat,
                )
            }
        }
    }
}

/**
 * The ring's CHIPS and RESULTS regions, published from one place whatever the
 * panel is showing, for the reason [AiPanel]'s own publisher gives: the last
 * SideEffect always describes what is on screen.
 */
@Composable
private fun ChatFocusTargets(chips: List<() -> Unit>, results: List<() -> Unit>) {
    PanelFocusTarget(
        panel = PanelMode.AI,
        region = FocusRegion.CHIPS,
        count = chips.size,
        columns = chips.size.coerceAtLeast(1),
    ) { index -> chips.getOrNull(index)?.invoke() }
    PanelFocusTarget(
        panel = PanelMode.AI,
        region = FocusRegion.RESULTS,
        count = results.size,
        columns = results.size.coerceAtLeast(1),
    ) { index -> results.getOrNull(index)?.invoke() }
}

@Composable
private fun ModelRow(
    choices: List<ModelChoice>,
    choice: ModelChoice?,
    enabled: Boolean,
    onChat: (AiChatAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "label") {
            Text(
                stringResource(R.string.ime_ai_model_label),
                color = kb.secondaryText,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
        // The one in use first, as the actions' picker has it: the row
        // scrolls, and the choice in force should never be the one off screen.
        items(choices.sortedByDescending { it == choice }, key = { it.key }) { option ->
            ToolPanelChip(
                modelLabel(option),
                selected = option == choice,
                enabled = enabled,
                modifier = Modifier.animateItem(),
            ) { onChat(AiChatAction.PickModel(option.key)) }
        }
    }
}

@Composable
private fun modelLabel(choice: ModelChoice): String =
    if (choice.provider == AiProvider.ON_DEVICE) {
        AiChatController.localModelLabel(choice.localModelId)
    } else {
        stringResource(choice.provider.labelRes)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transcript(
    state: KeyboardUiState,
    messages: List<AiChatMessage>,
    live: AiChatController.ChatRun?,
    busy: Boolean,
    onChat: (AiChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = state.aiChat
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Which bubble has its actions out. The newest answer always has; a tap
    // brings out any other's. Forgotten with the conversation.
    var selected by remember(chat.conversationId) { mutableStateOf<Int?>(null) }
    var copied by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPIED_FLASH_MS)
            copied = null
        }
    }

    // reverseLayout: index 0 is the newest, at the bottom.
    val away by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48 }
    }
    // A new message is always gone to. A growing answer is followed only by a
    // reader who is already at the bottom: scrolling up to reread is a request
    // to be left there.
    LaunchedEffect(messages.size, live != null) { listState.scrollToItem(0) }
    LaunchedEffect(live?.partial?.length) { if (!away) listState.scrollToItem(0) }

    val last = messages.lastOrNull()
    val lastAnswer = messages.lastIndex.takeIf {
        !busy && last != null && last.role == AiChatMessage.ROLE_ASSISTANT
    }
    val copy: (Int, String) -> Unit = { index, text ->
        onChat(AiChatAction.Copy(text))
        copied = index
    }
    ChatFocusTargets(
        chips = composerTargets(state, busy, onChat),
        results = when {
            lastAnswer == null || last == null -> emptyList()
            last.failed -> listOf { onChat(AiChatAction.Retry) }
            else -> listOf(
                { onChat(AiChatAction.Insert(last.content)) },
                { copy(messages.lastIndex, last.content) },
                { onChat(AiChatAction.Regenerate) },
            )
        },
    )
    val focusedResult = state.focusedIndex(FocusRegion.RESULTS)

    Box(modifier = modifier.fillMaxWidth()) {
        if (messages.isEmpty() && live == null) {
            EmptyChat(state, onChat)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // A tap on the conversation puts the keys away, the way a
                    // tap outside a field does anywhere else.
                    .clickable(
                        enabled = chat.composing,
                        indication = null,
                        interactionSource = null,
                    ) { onChat(AiChatAction.BlurComposer) },
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (live != null) {
                    item(key = "live") { StreamingBubble(live, state.settings.ai.showThinking, onChat) }
                }
                itemsIndexed(
                    messages.asReversed(),
                    // The position in the stored transcript: stable while the
                    // conversation only grows, which is all it does under a
                    // reader's eyes.
                    key = { reversed, _ -> messages.lastIndex - reversed },
                ) { reversed, message ->
                    val index = messages.lastIndex - reversed
                    val newest = index == messages.lastIndex
                    MessageBubble(
                        message = message,
                        actionsOut = !busy && (selected == index || (newest && index == lastAnswer)),
                        copied = copied == index,
                        // Only the newest turn can be redone or taken back:
                        // doing either mid-transcript would answer out of order.
                        canRegenerate = newest && !busy,
                        canEdit = !busy && message.role == AiChatMessage.ROLE_USER &&
                            index == messages.indexOfLast { it.role == AiChatMessage.ROLE_USER },
                        focusedAction = focusedResult.takeIf { newest },
                        onTap = { selected = if (selected == index) null else index },
                        onCopy = { copy(index, it) },
                        onReport = { onChat(AiChatAction.Report(index)) },
                        onChat = onChat,
                    )
                }
            }
            if (away) {
                ToolPanelChip(
                    "↓ " + stringResource(R.string.ime_ai_chat_latest),
                    selected = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp),
                ) { scope.launch { listState.animateScrollToItem(0) } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    message: AiChatMessage,
    actionsOut: Boolean,
    copied: Boolean,
    canRegenerate: Boolean,
    canEdit: Boolean,
    focusedAction: Int?,
    onTap: () -> Unit,
    onCopy: (String) -> Unit,
    onReport: () -> Unit,
    onChat: (AiChatAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val fromUser = message.role == AiChatMessage.ROLE_USER
    val ink = if (fromUser) kb.chipActiveText else kb.chipText
    val colors = ChatMarkdownColors(
        text = ink,
        dim = ink.copy(alpha = ink.alpha * 0.6f),
        codeBackground = ink.copy(alpha = 0.10f),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        Row {
            if (fromUser) Spacer(Modifier.width(BUBBLE_INSET))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(bubbleShape(fromUser))
                    .background(if (fromUser) kb.chipActive else kb.chip)
                    .combinedClickable(onClick = onTap, onLongClick = { onCopy(message.content) })
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                // What the answer was built on, over the answer.
                if (message.toolUses.isNotEmpty()) {
                    ToolUses(message.toolUses, colors, onChat, Modifier.padding(bottom = 4.dp))
                }
                when {
                    message.failed -> Text(message.error, color = kb.accent, fontSize = 13.sp, lineHeight = 18.sp)
                    // The user's own words are drawn as written: an asterisk
                    // they typed is an asterisk.
                    fromUser -> Text(message.content, color = ink, fontSize = 13.sp, lineHeight = 18.sp)
                    else -> ChatMarkdown(message.content, colors) { block ->
                        CodeAction(stringResource(CommonR.string.common_copy), colors) { onCopy(block.code) }
                        CodeAction(stringResource(R.string.ime_ai_insert), colors) {
                            onChat(AiChatAction.Insert(block.code, raw = true))
                        }
                    }
                }
                if (message.attachment.isNotEmpty()) {
                    Caption(
                        pluralStringResource(
                            R.plurals.ime_ai_chat_attachment_sent,
                            message.attachment.length,
                            message.attachment.length,
                        ),
                        colors.dim,
                    )
                }
                if (message.stopped) Caption(stringResource(R.string.ime_ai_chat_stopped), colors.dim)
            }
            if (!fromUser) Spacer(Modifier.width(BUBBLE_INSET))
        }
        if (actionsOut) {
            MessageActions(message, copied, canRegenerate, canEdit, focusedAction, onCopy, onReport, onChat)
        }
    }
}

/** What can be done with one message, under its bubble. The indices mirror [Transcript]'s RESULTS list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageActions(
    message: AiChatMessage,
    copied: Boolean,
    canRegenerate: Boolean,
    canEdit: Boolean,
    focusedAction: Int?,
    onCopy: (String) -> Unit,
    onReport: () -> Unit,
    onChat: (AiChatAction) -> Unit,
) {
    val fromUser = message.role == AiChatMessage.ROLE_USER
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (message.failed) {
            if (canRegenerate) {
                ToolPanelChip(
                    stringResource(R.string.ime_ai_chat_retry),
                    selected = true,
                    modifier = Modifier.focusRing(focusedAction == 0),
                ) { onChat(AiChatAction.Retry) }
            }
            return@FlowRow
        }
        // Insert leads on an answer, which is what the user is here to take
        // away. Their own message has no use for it.
        if (!fromUser) {
            ToolPanelChip(
                stringResource(R.string.ime_ai_insert),
                selected = true,
                modifier = Modifier.focusRing(focusedAction == 0),
            ) { onChat(AiChatAction.Insert(message.content)) }
        }
        ToolPanelChip(
            stringResource(if (copied) R.string.ime_ai_chat_copied else CommonR.string.common_copy),
            modifier = Modifier.focusRing(!fromUser && focusedAction == 1),
        ) { onCopy(message.content) }
        if (!fromUser && canRegenerate) {
            ToolPanelChip(
                stringResource(R.string.ime_ai_chat_regenerate),
                modifier = Modifier.focusRing(focusedAction == 2),
            ) { onChat(AiChatAction.Regenerate) }
        }
        if (canEdit) {
            ToolPanelChip(stringResource(CommonR.string.common_edit)) { onChat(AiChatAction.EditLast) }
        }
        // Play asks for a way to report generated content on every surface that
        // shows it; the other channels have no such rule, as on the chat screen.
        if (!fromUser && BuildConfig.ENABLE_PLAY_STORE && message.content.isNotBlank()) {
            ToolPanelChip(stringResource(R.string.ime_ai_chat_report_action)) { onReport() }
        }
    }
}

@Composable
private fun CodeAction(label: String, colors: ChatMarkdownColors, onClick: () -> Unit) {
    Text(
        label,
        color = colors.text,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun Caption(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 3.dp))
}

/** [ChatToolUses] in the bubble's colours, its links opened and copied the keyboard's way. */
@Composable
private fun ToolUses(
    uses: List<AiToolActivity>,
    colors: ChatMarkdownColors,
    onChat: (AiChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ChatToolUses(
        uses = uses,
        colors = colors,
        errorColor = LocalKbTheme.current.accent,
        onOpen = { openToolLink(context, it) },
        onCopy = { onChat(AiChatAction.Copy(it)) },
        modifier = modifier,
        // Only a running call ever asks, so a stored answer's record never
        // shows the buttons.
        onApproval = { onChat(AiChatAction.ToolApproval(it)) },
        animate = LocalKbTheme.current.motion.any,
    )
}

/** The answer forming: its text as it arrives, or what the model is doing until there is some. */
@Composable
private fun StreamingBubble(run: AiChatController.ChatRun, showThinking: Boolean, onChat: (AiChatAction) -> Unit) {
    val kb = LocalKbTheme.current
    val split = remember(run.partial, run.implicitThink) { AiThinking.split(run.partial, run.implicitThink) }
    val colors = ChatMarkdownColors(
        text = kb.chipText,
        dim = kb.chipText.copy(alpha = kb.chipText.alpha * 0.6f),
        codeBackground = kb.chipText.copy(alpha = 0.10f),
    )
    Row(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(bubbleShape(fromUser = false))
                .background(kb.chip)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            // The calls so far, the running one saying what it is doing.
            val toolRunning = run.tools.any { it.running }
            if (run.tools.isNotEmpty()) ToolUses(run.tools, colors, onChat, Modifier.padding(bottom = 4.dp))
            when {
                showThinking && run.partial.isNotEmpty() -> Text(
                    // Dimmed by alpha, as the actions' result is: some themes
                    // draw their secondary text in the same white.
                    grayThinking(run.partial, kb.chipText.copy(alpha = 0.45f)),
                    color = kb.chipText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                split.output.isNotBlank() -> ChatMarkdown(split.output, colors)
                // The line over this one already says which tool is running
                // and on what; "Using a tool" under it would only repeat it.
                toolRunning && !split.thinking -> Unit
                else -> {
                    Text(
                        stringResource(
                            if (split.thinking) R.string.ime_ai_chat_thinking else run.phase.labelRes,
                        ),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.size(5.dp))
                    LinearProgressIndicator(color = kb.accent, modifier = Modifier.width(96.dp))
                }
            }
        }
        Spacer(Modifier.width(BUBBLE_INSET))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(state: KeyboardUiState, onChat: (AiChatAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = state.aiChat.composing,
                indication = null,
                interactionSource = null,
            ) { onChat(AiChatAction.BlurComposer) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Text handed over by another tool (#352) is what this chat is about.
        val fromTool = state.aiChat.attachment?.label?.isNotEmpty() == true
        Text(
            stringResource(if (fromTool) R.string.ime_ai_chat_ask_hint else R.string.ime_ai_chat_empty_hint),
            color = kb.secondaryText,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (fromTool) {
            // Offered while typing too: the composer takes the keys the moment
            // the text arrives, and these are the questions most asked of it.
            val explain = stringResource(R.string.ime_ai_chat_ask_explain_prompt)
            val summary = stringResource(R.string.ime_ai_chat_ask_summary_prompt)
            val words = stringResource(R.string.ime_ai_chat_ask_words_prompt)
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ToolPanelChip(stringResource(R.string.ime_ai_chat_ask_explain)) {
                    onChat(AiChatAction.Starter(explain, attach = false))
                }
                ToolPanelChip(stringResource(R.string.ime_ai_chat_ask_summary)) {
                    onChat(AiChatAction.Starter(summary, attach = false))
                }
                ToolPanelChip(stringResource(R.string.ime_ai_chat_ask_words)) {
                    onChat(AiChatAction.Starter(words, attach = false))
                }
            }
        } else if (!state.aiChat.composing) {
            // Openings, for the blank page. Not while typing: the user has begun.
            val reply = stringResource(R.string.ime_ai_chat_starter_reply_prompt)
            val explain = stringResource(R.string.ime_ai_chat_starter_explain_prompt)
            val write = stringResource(R.string.ime_ai_chat_starter_write_prompt)
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // The two about "this" need a this: text in the field to quote.
                if (state.aiHasText && !state.secureField) {
                    ToolPanelChip(stringResource(R.string.ime_ai_chat_starter_reply)) {
                        onChat(AiChatAction.Starter(reply, attach = true))
                    }
                    ToolPanelChip(stringResource(R.string.ime_ai_chat_starter_explain)) {
                        onChat(AiChatAction.Starter(explain, attach = true))
                    }
                }
                ToolPanelChip(stringResource(R.string.ime_ai_chat_starter_write)) {
                    onChat(AiChatAction.Starter(write, attach = false))
                }
            }
        }
    }
}

/** What Enter does on each of the composer's four ring slots. Mirrors [Composer]. */
private fun composerTargets(
    state: KeyboardUiState,
    generating: Boolean,
    onChat: (AiChatAction) -> Unit,
): List<() -> Unit> = listOf(
    { onChat(AiChatAction.ToggleAttachment) },
    { onChat(AiChatAction.Paste) },
    { onChat(if (state.aiChat.composing) AiChatAction.BlurComposer else AiChatAction.FocusComposer) },
    { onChat(if (generating) AiChatAction.Stop else AiChatAction.Send) },
)

@Composable
private fun Composer(
    state: KeyboardUiState,
    generating: Boolean,
    canSend: Boolean,
    onChat: (AiChatAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val chat = state.aiChat
    val focused = state.focusedIndex(FocusRegion.CHIPS)
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ChatIconButton(
            Icons.Outlined.AttachFile,
            stringResource(
                if (chat.attachment != null) {
                    R.string.ime_ai_chat_attach_remove_desc
                } else {
                    R.string.ime_ai_chat_attach_desc
                },
            ),
            active = chat.attachment != null,
            // Nothing to quote from an empty field, and never from a password.
            enabled = chat.attachment != null || (state.aiHasText && !state.secureField),
            modifier = Modifier.focusRing(focused == 0, CircleShape),
        ) { onChat(AiChatAction.ToggleAttachment) }
        Spacer(Modifier.width(4.dp))
        ChatIconButton(
            Icons.Outlined.ContentPaste,
            stringResource(CommonR.string.common_paste),
            modifier = Modifier.focusRing(focused == 1, CircleShape),
        ) { onChat(AiChatAction.Paste) }
        Spacer(Modifier.width(6.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .focusRing(focused == 2, shape)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .clickable(enabled = !chat.composing) { onChat(AiChatAction.FocusComposer) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            chat.attachment?.let { attachment ->
                Text(
                    when {
                        // A tool's text is named by the tool it came from (#352).
                        attachment.label.isNotEmpty() -> stringResource(
                            if (attachment.fromSelection) {
                                R.string.ime_ai_chat_attached_tool_selection
                            } else {
                                R.string.ime_ai_chat_attached_tool
                            },
                            attachment.label,
                        )
                        else -> pluralStringResource(
                            if (attachment.fromSelection) {
                                R.plurals.ime_ai_chat_attached_selection
                            } else {
                                R.plurals.ime_ai_chat_attached_field
                            },
                            attachment.text.length,
                            attachment.text.length,
                        )
                    },
                    color = kb.accent,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            val hint = stringResource(R.string.ime_ai_chat_composer_hint)
            if (chat.composing) {
                // The caret, and a tap that moves it, come from the capture
                // ladder's own editor; this field is one of its buffers.
                ClipEditText(
                    text = chat.draft,
                    placeholder = hint,
                    textColor = kb.chipText,
                    placeholderColor = kb.secondaryText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 20.dp, max = COMPOSER_MAX_HEIGHT),
                )
            } else {
                Text(
                    chat.draft.ifEmpty { hint },
                    color = if (chat.draft.isEmpty()) kb.secondaryText else kb.chipText,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        ChatIconButton(
            if (generating) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
            stringResource(if (generating) R.string.ime_ai_chat_stop else R.string.ime_ai_chat_send),
            active = true,
            enabled = generating || canSend,
            size = 36.dp,
            modifier = Modifier.focusRing(focused == 3, CircleShape),
        ) { onChat(if (generating) AiChatAction.Stop else AiChatAction.Send) }
    }
}

/** The conversation list, in place of the transcript. */
@Composable
private fun Sessions(
    state: KeyboardUiState,
    conversations: List<AiChatConversation>,
    onChat: (AiChatAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    // Delete is two taps on the same chip, not a dialog: a dialog over an IME
    // window is a popup, and the list has nowhere to put one.
    var doomed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(doomed) {
        if (doomed != null) {
            delay(DELETE_CONFIRM_MS)
            doomed = null
        }
    }
    ChatFocusTargets(
        chips = emptyList(),
        results = listOf<() -> Unit>(
            { onChat(AiChatAction.NewChat) },
            { onChat(AiChatAction.OpenInApp(list = true)) },
        ) + conversations.map { { onChat(AiChatAction.Open(it.id)) } },
    )
    val focused = state.focusedIndex(FocusRegion.RESULTS)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        item(key = "top") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ToolPanelChip(
                    stringResource(R.string.ime_ai_chat_new),
                    selected = true,
                    modifier = Modifier.focusRing(focused == 0),
                ) { onChat(AiChatAction.NewChat) }
                ToolPanelChip(
                    stringResource(R.string.ime_ai_chat_open_in_app),
                    modifier = Modifier.focusRing(focused == 1),
                ) { onChat(AiChatAction.OpenInApp(list = true)) }
            }
        }
        if (conversations.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.ime_ai_chat_list_empty),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
        itemsIndexed(conversations, key = { _, conversation -> conversation.id }) { index, conversation ->
            val current = conversation.id == state.aiChat.conversationId
            val shape = kb.cardShape()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clip(shape)
                    .focusRing(focused == index + 2, shape)
                    .background(kb.chip)
                    .chipBorder(kb, shape)
                    .clickable { onChat(AiChatAction.Open(conversation.id)) }
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        conversation.title.ifBlank { stringResource(R.string.ime_ai_chat_untitled) },
                        color = if (current) kb.accent else kb.chipText,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString(),
                        color = kb.secondaryText,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                    )
                }
                val confirming = doomed == conversation.id
                ToolPanelChip(
                    stringResource(
                        if (confirming) R.string.ime_ai_chat_delete_confirm else CommonR.string.common_delete,
                    ),
                    selected = confirming,
                ) {
                    if (confirming) {
                        doomed = null
                        onChat(AiChatAction.Delete(conversation.id))
                    } else {
                        doomed = conversation.id
                    }
                }
            }
        }
    }
}

/** Neither a downloaded model nor a provider: the way to the settings that fix it. */
@Composable
private fun NoBackend(state: KeyboardUiState, onOpenSettings: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.ime_ai_chat_no_backend_body),
            color = kb.secondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.size(8.dp))
        ToolPanelChip(
            stringResource(R.string.ime_ai_open_settings_action),
            modifier = Modifier.focusRing(state.focusedIndex(FocusRegion.RESULTS) == 0),
        ) { onOpenSettings() }
    }
}

/** A round icon button in the chip colours: the composer's and the header's. */
@Composable
private fun ChatIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    // Scaled from the theme's own alpha, as ToolPanelChip dims: some themes
    // draw chips translucent to begin with.
    fun Color.dim() = if (enabled) this else copy(alpha = alpha * 0.4f)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background((if (active) kb.chipActive else kb.chip).dim())
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = (if (active) kb.chipActiveText else kb.chipText).dim(),
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

private fun bubbleShape(fromUser: Boolean) = RoundedCornerShape(
    topStart = 14.dp,
    topEnd = 14.dp,
    bottomStart = if (fromUser) 14.dp else 4.dp,
    bottomEnd = if (fromUser) 4.dp else 14.dp,
)

/** Kept clear on a bubble's far side, so whose turn it is reads from the margin. */
private val BUBBLE_INSET = 36.dp
private val COMPOSER_MAX_HEIGHT = 78.dp
private const val COPIED_FLASH_MS = 1200L
private const val DELETE_CONFIRM_MS = 3000L
