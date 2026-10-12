package com.wasimaster.wmkeyboard.ime.aichat

import android.annotation.SuppressLint
import android.content.Context
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.aichat.AiChatMessage
import com.wasimaster.wmkeyboard.core.aichat.AiChatStore
import com.wasimaster.wmkeyboard.core.localllm.ChatReplay
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiPhase
import com.wasimaster.wmkeyboard.core.tools.AiPrompts
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.core.tools.AiToolApproval
import com.wasimaster.wmkeyboard.core.tools.AiToolApprovalGate
import com.wasimaster.wmkeyboard.core.tools.AiToolApprover
import com.wasimaster.wmkeyboard.core.tools.AiToolLoop
import com.wasimaster.wmkeyboard.core.tools.AiToolProtocol
import com.wasimaster.wmkeyboard.core.tools.AiToolResult
import com.wasimaster.wmkeyboard.core.tools.AiToolRunner
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the AI chat's generations. An object with its own scope, like
 * LocalLlmDownloadManager, so a running answer survives every navigation and
 * the screen only ever renders state: the saved transcript from [store] plus
 * the one live [run].
 *
 * Two surfaces draw it: the chat screen in the settings app and the chat mode
 * of the keyboard's AI panel (#280). It lives in this module because both can
 * see it here, and the two share one process, so they share this object, its
 * store and its live run. That is the whole of how a chat begun on the keyboard
 * carries on in the app: there is nothing to hand over.
 *
 * The same stale-sequence pattern as the keyboard's AI panel: an on-device
 * generation is a blocking native call that outlives any cancellation, so a
 * bumped [runSeq] — not a cancelled job — is what retires it, and late
 * callbacks are dropped by comparing their captured sequence.
 */
object AiChatController {

    /** The model the chat screen sends to: a provider, or one local model. */
    data class ModelChoice(val provider: AiProvider, val localModelId: String = "") {
        val key: String
            get() = if (provider == AiProvider.ON_DEVICE) {
                AiChatStore.ON_DEVICE_KEY_PREFIX + localModelId
            } else {
                provider.name
            }
    }

    /** Everything that can answer right now, in the order the pickers draw it. */
    fun choices(context: Context, settings: AiSettings): List<ModelChoice> =
        downloadedLocalModels(context).map { ModelChoice(AiProvider.ON_DEVICE, it) } +
            AiClient.configuredRemoteProviders(settings).map { ModelChoice(it) }

    /** The model to preselect: last used if still available, else the first. */
    fun initialChoice(store: AiChatStore, available: List<ModelChoice>): ModelChoice? {
        val lastKey = store.lastModelKey()
        return available.firstOrNull { it.key == lastKey } ?: available.firstOrNull()
    }

    /** A local model's name as a picker shows it. */
    fun localModelLabel(id: String): String =
        LocalLlmCatalog.byId(id)?.displayName
            ?: id.removePrefix(LocalLlmStore.CUSTOM_PREFIX).substringBeforeLast('.')

    /** One in-flight generation, as the screen renders it. */
    data class ChatRun(
        val conversationId: Long,
        val seq: Int,
        /** The user message being answered. */
        val userText: String,
        /** Raw accumulated response, think block included. */
        val partial: String = "",
        val phase: AiPhase = AiPhase.PREPARING,
        /** Model streams reasoning with no opening tag (Qwen3 style). */
        val implicitThink: Boolean = false,
        val choice: ModelChoice,
        /**
         * The tools called so far, oldest first; the newest may still be
         * [AiToolActivity.running]. Lands in the stored answer at the end.
         */
        val tools: List<AiToolActivity> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _run = MutableStateFlow<ChatRun?>(null)
    val run: StateFlow<ChatRun?> = _run

    /** Bumped after every store mutation so the screen re-reads [store]. */
    private val _storeVersion = MutableStateFlow(0L)
    val storeVersion: StateFlow<Long> = _storeVersion

    private var storeInstance: AiChatStore? = null
    private var runSeq = 0
    private var job: Job? = null

    // The one live on-device session and the inputs it was built for. A new
    // conversation, model or backend closes it and opens another; the session
    // itself survives engine eviction by replaying its transcript.
    // The session's context is the application one — chatSession() rebinds —
    // so this static hold keeps no Activity alive.
    @SuppressLint("StaticFieldLeak")
    private var session: LocalLlmEngine.ChatSession? = null
    private var sessionKey: String? = null

    @Synchronized
    fun store(context: Context): AiChatStore = storeInstance ?: AiChatStore(
        File(context.applicationContext.filesDir, AiChatStore.FILE_PATH),
    ).also { storeInstance = it }

    /**
     * Points the store at the user's "keep conversations" answer.
     *
     * Called from the screens that open the store rather than read inside it:
     * `:core:content` knows nothing about settings, and the value is a flow
     * while `store` is synchronous. The flag is only consulted when a chat is
     * saved, which happens on send — long after this has landed.
     */
    suspend fun applyPersistSetting(context: Context, keep: Boolean) {
        val target = store(context)
        if (target.persist == keep) return
        target.persist = keep
        // Switching it off has to take what is already on disk with it.
        if (!keep) withContext(Dispatchers.IO) { target.save() }
    }

    /** Public form of [touchStore], for settings screens that mutate the store. */
    fun bumpStoreVersion() {
        _storeVersion.value++
    }

    private fun touchStore() {
        _storeVersion.value++
    }

    val isGenerating: Boolean get() = _run.value != null

    /** Ids of local models that are on disk right now, catalog order. */
    fun downloadedLocalModels(context: Context): List<String> {
        if (!BuildConfig.ENABLE_LOCAL_LLM) return emptyList()
        val filesDir = context.applicationContext.filesDir
        val catalog = LocalLlmCatalog.models
            .filter { LocalLlmStore.isDownloaded(filesDir, it) }
            .map { it.id }
        val custom = LocalLlmStore.customModels(filesDir)
            .map { LocalLlmStore.CUSTOM_PREFIX + it.name }
        return catalog + custom
    }

    /**
     * Sends one user message in [conversationId] and streams the answer.
     * No-op while a generation is already running — the screen disables Send.
     */
    fun send(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        text: String,
        /** Text quoted along with the message: a selection, or a field's text. */
        attachment: String = "",
    ) {
        if (isGenerating) return
        val appContext = context.applicationContext
        val store = store(appContext)
        val user = text.trim()
        if (user.isEmpty()) return

        val now = System.currentTimeMillis()
        val message = AiChatMessage(
            role = AiChatMessage.ROLE_USER,
            content = user,
            timestamp = now,
            attachment = attachment.trim(),
        )
        val stored = store.appendMessage(conversationId, message)?.messages?.lastOrNull() ?: return
        store.setLastModelKey(choice.key)
        store.save()
        touchStore()
        launchGeneration(appContext, settings, conversationId, choice, stored.promptText())
    }

    /**
     * Reruns the user message whose answer failed. The failed bubble makes
     * way — the message it answers is already in the transcript, so unlike
     * [send] nothing is appended before generating.
     */
    fun retry(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
    ) {
        if (isGenerating) return
        val appContext = context.applicationContext
        val store = store(appContext)
        store.dropLastFailedMessage(conversationId)
        val user = store.get(conversationId)?.messages
            ?.lastOrNull { it.role == AiChatMessage.ROLE_USER } ?: return
        store.save()
        touchStore()
        launchGeneration(appContext, settings, conversationId, choice, user.promptText())
    }

    /**
     * Writes the newest answer again. The old answer leaves the transcript
     * first, and the on-device session with it: its native cache still holds
     * the answer that is going, so the next send builds a session from the
     * transcript as it now stands.
     */
    fun regenerate(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
    ) {
        if (isGenerating) return
        val appContext = context.applicationContext
        val store = store(appContext)
        val messages = store.get(conversationId)?.messages ?: return
        val lastUser = messages.indexOfLast { it.role == AiChatMessage.ROLE_USER }
        // Nothing after the question means there is no answer to write again.
        if (lastUser < 0 || lastUser == messages.lastIndex) return
        closeSessionFor(conversationId)
        store.dropFrom(conversationId, lastUser + 1)
        store.setLastModelKey(choice.key)
        store.save()
        touchStore()
        launchGeneration(appContext, settings, conversationId, choice, messages[lastUser].promptText())
    }

    /**
     * Takes the user's newest message back out of the transcript, with whatever
     * answered it, and returns it for the composer: edit, then send again.
     * Null when there is nothing to take back or an answer is still forming.
     */
    fun editLast(context: Context, conversationId: Long): AiChatMessage? {
        if (isGenerating) return null
        val store = store(context.applicationContext)
        val messages = store.get(conversationId)?.messages ?: return null
        val lastUser = messages.indexOfLast { it.role == AiChatMessage.ROLE_USER }
        if (lastUser < 0) return null
        closeSessionFor(conversationId)
        store.dropFrom(conversationId, lastUser)
        store.save()
        touchStore()
        return messages[lastUser]
    }

    fun deleteConversation(context: Context, conversationId: Long) {
        val store = store(context.applicationContext)
        closeSessionFor(conversationId)
        store.delete(conversationId)
        store.save()
        touchStore()
    }

    private fun launchGeneration(
        appContext: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        user: String,
    ) {
        val seq = ++runSeq
        val implicitThink =
            choice.provider == AiProvider.ON_DEVICE && isReasoningModel(choice.localModelId)
        _run.value = ChatRun(
            conversationId = conversationId,
            seq = seq,
            userText = user,
            implicitThink = implicitThink,
            choice = choice,
        )

        job = scope.launch {
            val result = runCatching {
                if (choice.provider == AiProvider.ON_DEVICE) {
                    runOnDevice(appContext, settings, conversationId, choice, user, seq)
                } else {
                    runRemote(appContext, settings, conversationId, choice, user, seq)
                }
            }
            finishRun(appContext, conversationId, choice, user, seq, result)
        }
    }

    /**
     * Retires the current generation. The remote stream drops its socket via
     * `isActive`; the on-device one runs to completion in the background —
     * a blocking native call cannot be interrupted — and [finishRun] then
     * mirrors its full text into the session so a later replay matches what
     * the model remembers. Either way the transcript keeps the partial,
     * marked stopped.
     */
    fun stop(context: Context) {
        val current = _run.value ?: return
        runSeq++
        val store = store(context.applicationContext)
        store.appendMessage(
            current.conversationId,
            AiChatMessage(
                role = AiChatMessage.ROLE_ASSISTANT,
                content = AiThinking.split(current.partial, current.implicitThink).output.trim(),
                timestamp = System.currentTimeMillis(),
                provider = current.choice.provider.name,
                model = modelName(current.choice),
                stopped = true,
                toolUses = AiToolActivity.settle(
                    current.tools,
                    context.getString(R.string.ime_ai_chat_stopped),
                ),
            ),
        )
        store.save()
        touchStore()
        _run.value = null
    }

    /** Called when the chat screen for [conversationId] goes away for good. */
    fun closeSessionFor(conversationId: Long) {
        synchronized(this) {
            if (sessionKey?.startsWith("$conversationId|") == true) {
                session?.close()
                session = null
                sessionKey = null
            }
        }
    }

    private fun runOnDevice(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        user: String,
        seq: Int,
    ): String {
        val modelFile = LocalLlmStore.selectedModelFile(context.filesDir, choice.localModelId)
            ?: throw java.io.IOException(
                context.getString(R.string.ime_ai_chat_error_no_model),
            )
        val runner = AiToolRunner(context, settings, toolApprover(seq))
        val tools = runner.tools()
        // A local model has no function calling, so the sentinel protocol has
        // to be part of the session's own system prompt — the session is built
        // once and keeps it, which is why it is woven in here rather than per
        // round. The one cost: the last round is still told about tools, so a
        // model that will not stop calling them answers with nothing. The
        // round limit is what keeps that rare.
        val sessionSystem = AiPrompts.chatPrompt() + AiToolProtocol.instructions(tools)
        val chat = obtainSession(context, settings.ai, conversationId, choice, modelFile, sessionSystem)
        var lastPartialAt = 0L
        return AiToolLoop.run(
            // The round below ignores this: the session was built with its own
            // prompt above and keeps it. Passing the bare one here is what
            // stops the loop appending the protocol to it a second time.
            system = AiPrompts.chatPrompt(),
            turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, user)),
            tools = tools,
            native = false,
            executor = runner::run,
            maxRounds = settings.ai.toolMaxRounds,
            onPhase = { phase -> postPhase(seq, phase) },
            onPartial = { partial ->
                val now = System.currentTimeMillis()
                if (seq == runSeq && now - lastPartialAt >= AI_PARTIAL_INTERVAL_MS) {
                    lastPartialAt = now
                    postPartial(seq, partial)
                }
            },
            onToolCall = { call -> postToolStarted(seq, runner.activityFor(call)) },
            onToolResult = { result -> postToolResult(seq, result) },
            isActive = { seq == runSeq },
        ) { _, roundTurns, _, roundPartial ->
            // The session remembers every earlier round itself, so only the
            // newest message is sent: the user's on round one, the tool
            // results on the rounds after it.
            AiClient.Completion(chat.sendMessage(roundTurns.last().text, roundPartial))
        }.text
    }

    /**
     * The live session for this conversation and model, replacing one built
     * for anything else. A replaced or resumed session is seeded with the
     * saved transcript so the model keeps the context.
     */
    @Suppress("UnusedParameter")
    private fun obtainSession(
        context: Context,
        settings: AiSettings,
        conversationId: Long,
        choice: ModelChoice,
        modelFile: File,
        system: String,
    ): LocalLlmEngine.ChatSession = synchronized(this) {
        // The system prompt is part of the key: turning a tool on changes it,
        // and a session built before that would never hear about the tool.
        val key = "$conversationId|${modelFile.path}|${settings.localBackend}|" +
            "${settings.localContextTokens}|${system.hashCode()}"
        session?.takeIf { sessionKey == key }?.let { return it }
        session?.close()
        val fresh = LocalLlmEngine.chatSession(
            context,
            modelFile,
            settings.localBackend,
            settings.localContextTokens,
            system,
        )
        fresh.seed(transcriptOf(context, conversationId))
        session = fresh
        sessionKey = key
        fresh
    }

    /**
     * The saved conversation as replay turns: failed turns dropped, and the
     * trailing user message too — that is the message about to be *sent*, so
     * seeding it as well would put it in front of the model twice.
     */
    private fun transcriptOf(context: Context, conversationId: Long): List<ChatReplay.Turn> {
        val messages = store(context).get(conversationId)?.messages.orEmpty()
            .filter { !it.failed }
        val settled = if (messages.lastOrNull()?.role == AiChatMessage.ROLE_USER) {
            messages.dropLast(1)
        } else {
            messages
        }
        return settled.map {
            ChatReplay.Turn(fromUser = it.role == AiChatMessage.ROLE_USER, text = it.promptText())
        }
    }

    @Suppress("UnusedParameter")
    private fun runRemote(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        user: String,
        seq: Int,
    ): String {
        // The picker's provider stands in for the settings one, so chatting
        // with a different model never rewrites the keyboard's own selection.
        val chosen = settings.ai.copy(provider = choice.provider)
        val config = AiClient.config(chosen).copy(netSource = NetSource.AI_CHAT)
        val turns = store(context).get(conversationId)?.messages.orEmpty()
            .filter { !it.failed }
            .map {
                AiClient.ChatTurn(
                    if (it.role == AiChatMessage.ROLE_USER) {
                        AiClient.ChatRole.USER
                    } else {
                        AiClient.ChatRole.ASSISTANT
                    },
                    it.promptText(),
                )
            }
        var lastPartialAt = 0L
        val runner = AiToolRunner(context, settings, toolApprover(seq))
        val completion = AiClient.completeWithTools(
            config = config,
            system = AiPrompts.chatPrompt(),
            turns = turns,
            maxTokens = AiClient.effectiveMaxTokens(chosen),
            tools = runner.tools(),
            executor = runner::run,
            maxRounds = settings.ai.toolMaxRounds,
            onPhase = { phase -> postPhase(seq, phase) },
            onPartial = { partial ->
                val now = System.currentTimeMillis()
                if (seq == runSeq && now - lastPartialAt >= AI_PARTIAL_INTERVAL_MS) {
                    lastPartialAt = now
                    postPartial(seq, partial)
                }
            },
            onToolCall = { call -> postToolStarted(seq, runner.activityFor(call)) },
            onToolResult = { result -> postToolResult(seq, result) },
            isActive = { seq == runSeq },
        )
        return completion.text
    }

    private fun postPhase(seq: Int, phase: AiPhase) {
        if (seq != runSeq) return
        _run.value = _run.value?.takeIf { it.seq == seq }?.copy(phase = phase)
    }

    private fun postPartial(seq: Int, partial: String) {
        _run.value = _run.value?.takeIf { it.seq == seq && seq == runSeq }?.copy(partial = partial)
    }

    /** A tool call has begun: the chat says what it is doing until it ends. */
    private fun postToolStarted(seq: Int, started: AiToolActivity) {
        _run.update { run ->
            if (run == null || run.seq != seq || seq != runSeq) run
            else run.copy(tools = run.tools + started)
        }
    }

    /** The running call's line changed: it is asking to be allowed, or is done asking. */
    private fun postToolShown(seq: Int, shown: AiToolActivity) {
        _run.update { run ->
            if (run == null || run.seq != seq || seq != runSeq) run
            else run.copy(tools = AiToolActivity.replaceRunning(run.tools, shown))
        }
    }

    /**
     * Where a chat's MCP call waits while the chat asks the user to allow it
     * (#573). One for both chats, the keyboard's and the app's, since they
     * share this controller and its one run.
     */
    private val toolGate = AiToolApprovalGate()

    private fun toolApprover(seq: Int): AiToolApprover = toolGate.approver(
        isActive = { seq == runSeq },
        show = { ask -> postToolShown(seq, ask) },
        shown = { done -> postToolShown(seq, done) },
    )

    /**
     * The user's answer to the call asking now. False when none is, which
     * the keyboard uses to tell its own panel's question from the chat's.
     */
    fun answerTool(choice: AiToolApproval): Boolean = toolGate.answer(choice)

    private fun postToolResult(seq: Int, result: AiToolResult) {
        _run.update { run ->
            if (run == null || run.seq != seq || seq != runSeq) run
            else run.copy(tools = AiToolActivity.record(run.tools, result))
        }
    }

    /**
     * Lands the finished (or failed) generation in the store — unless the run
     * was retired by Stop, in which case only the on-device session's replay
     * mirror still wants the full answer.
     */
    private fun finishRun(
        context: Context,
        conversationId: Long,
        choice: ModelChoice,
        user: String,
        seq: Int,
        result: Result<String>,
    ) {
        val raw = result.getOrNull()
        if (seq != runSeq) {
            // Retired by Stop (or a newer send). The native call still ran to
            // its end; keep the session's mirror faithful to the KV cache.
            if (raw != null && choice.provider == AiProvider.ON_DEVICE) {
                synchronized(this) { session }
                    ?.takeIf { sessionKey?.startsWith("$conversationId|") == true }
                    ?.recordStoppedTurn(user, raw)
            }
            return
        }
        val store = store(context)
        val implicitThink =
            choice.provider == AiProvider.ON_DEVICE && isReasoningModel(choice.localModelId)
        // Kept with the answer — or with the failure, since a run that died
        // after searching still searched.
        val toolUses = AiToolActivity.settle(
            _run.value?.takeIf { it.seq == seq }?.tools.orEmpty(),
            context.getString(R.string.ime_ai_chat_error_generic),
        )
        val message = if (raw != null) {
            val stripped = AiThinking.split(raw, implicitThink).output.trim()
                .ifBlank { raw.trim() }
            AiChatMessage(
                role = AiChatMessage.ROLE_ASSISTANT,
                content = stripped,
                timestamp = System.currentTimeMillis(),
                provider = choice.provider.name,
                model = modelName(choice),
                toolUses = toolUses,
            )
        } else {
            val failure = result.exceptionOrNull()
            AiChatMessage(
                role = AiChatMessage.ROLE_ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                provider = choice.provider.name,
                model = modelName(choice),
                error = errorText(context, failure),
                toolUses = toolUses,
            )
        }
        store.appendMessage(conversationId, message)
        store.save()
        touchStore()
        if (_run.value?.seq == seq) _run.value = null
    }

    private fun errorText(context: Context, t: Throwable?): String {
        val message = t?.message
        return when {
            t is ToolHttpException -> ToolHttp.friendlyMessage(context, t)
            !message.isNullOrBlank() -> message
            else -> context.getString(R.string.ime_ai_chat_error_generic)
        }
    }

    private fun modelName(choice: ModelChoice): String =
        if (choice.provider == AiProvider.ON_DEVICE) choice.localModelId else ""

    /** Same sniff as the keyboard: catalog flag, else the model's name. */
    private fun isReasoningModel(modelId: String?): Boolean {
        if (modelId.isNullOrEmpty()) return false
        LocalLlmCatalog.byId(modelId)?.let { return it.reasoning }
        val name = modelId.removePrefix(LocalLlmStore.CUSTOM_PREFIX).lowercase()
        return "qwen3" in name || "deepseek" in name
    }

    private const val AI_PARTIAL_INTERVAL_MS = 120L
}
