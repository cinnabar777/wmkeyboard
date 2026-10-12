package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.tools.feature.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One-shot chat completion against the AI tool's configured provider —
 * Anthropic, OpenAI, Gemini, Brave's web-grounded answers, or a self-hosted
 * Ollama / LM Studio server (OpenAI-compatible). Bring-your-own-key: keys and base URLs live in the
 * tool's settings; nothing is sent anywhere until the user runs an action.
 */
object AiClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** Resolved connection details for one provider, from settings. */
    data class Config(
        val provider: AiProvider,
        val apiKey: String,
        val model: String,
        val baseUrl: String,
        /** What the network activity log files this request under: the panel or the chat. */
        val netSource: NetSource = NetSource.AI,
    )

    /**
     * One prior message of a multi-turn chat, oldest first.
     *
     * [toolCalls] is set on the assistant turn that ended by asking for tools,
     * and [toolResult] on the [ChatRole.TOOL] turn carrying one answer back.
     * Both are empty for every ordinary message, which is every message until
     * the user turns a tool on (#470).
     */
    data class ChatTurn(
        val role: ChatRole,
        val text: String,
        val toolCalls: List<AiToolCall> = emptyList(),
        val toolResult: AiToolResult? = null,
    ) {
        /**
         * Part of a tool exchange rather than something a person wrote. These
         * turns are passed through [normalizedTurns] untouched: they may be
         * blank, they may repeat a role, and the conversation may end on one.
         */
        val isToolExchange: Boolean get() = toolCalls.isNotEmpty() || toolResult != null
    }

    /**
     * [TOOL] is not a role any provider spells the same way — OpenAI has a
     * `tool` message, Anthropic a user message of `tool_result` blocks, Gemini
     * a user part of `functionResponse`. It is one role here and becomes each
     * of those in the body builders.
     */
    enum class ChatRole { USER, ASSISTANT, TOOL }

    /**
     * One finished response.
     *
     * [truncated] means the provider stopped writing because it hit the token
     * ceiling, not because the answer was finished. Without it a cut-off answer
     * is indistinguishable from a complete one, which is how a long "Improve"
     * used to come back missing its last paragraphs with nothing to say so.
     */
    data class Completion(
        val text: String,
        val truncated: Boolean = false,
        /** What the model asked to call instead of, or as well as, answering. */
        val toolCalls: List<AiToolCall> = emptyList(),
    )

    /**
     * Model each provider falls back to when its settings field is blank.
     * Kept in one place because the settings screen shows the same strings as
     * "Blank = …" hints — they must not drift apart.
     */
    object DefaultModels {
        const val ANTHROPIC = "claude-sonnet-5"
        const val OPENAI = "gpt-5.6-luna"
        const val GEMINI = "gemini-3.5-flash"
        const val OLLAMA = "qwen3"
        const val XAI = "grok-4.5"
        const val DEEPSEEK = "deepseek-v4-flash"

        /** Brave's Answers API has one model, and this is its name. */
        const val BRAVE = "brave"
    }

    /**
     * Addresses of the services that speak the OpenAI chat-completions shape:
     * their own hosts, unless the F-Droid build has one pointed elsewhere (see
     * `ServiceEndpoint`). [AiProvider.OPENAI_COMPATIBLE] is absent on purpose:
     * its address is the one the user supplies.
     */
    private object Endpoints {
        val OPENAI: String get() = ServiceEndpoints.base(ServiceEndpoint.OPENAI) + CHAT_COMPLETIONS
        val XAI: String get() = ServiceEndpoints.base(ServiceEndpoint.XAI) + CHAT_COMPLETIONS
        val DEEPSEEK: String get() = ServiceEndpoints.base(ServiceEndpoint.DEEPSEEK) + CHAT_COMPLETIONS
        const val CHAT_COMPLETIONS = "/v1/chat/completions"
    }

    /**
     * The chat-completions address for a provider whose request shape is
     * OpenAI's. The three self-hosted or user-supplied entries build it from the
     * address in settings; the rest are fixed.
     */
    internal fun openAiCompatibleUrl(config: Config): String = when (config.provider) {
        AiProvider.OPENAI -> Endpoints.OPENAI
        AiProvider.XAI -> Endpoints.XAI
        AiProvider.DEEPSEEK -> Endpoints.DEEPSEEK
        AiProvider.LM_STUDIO -> "${config.baseUrl.trimEnd('/')}/v1/chat/completions"
        // The user gives the address up to and including any version segment,
        // because those differ between services (/v1, /openai/v1, /api/v1).
        AiProvider.OPENAI_COMPATIBLE -> "${config.baseUrl.trimEnd('/')}/chat/completions"
        else -> error("${config.provider} does not use the OpenAI request shape")
    }

    fun config(settings: AiSettings): Config = when (settings.provider) {
        AiProvider.ANTHROPIC -> Config(
            AiProvider.ANTHROPIC, settings.anthropicKey,
            settings.anthropicModel.ifBlank { DefaultModels.ANTHROPIC }, "",
        )
        AiProvider.OPENAI -> Config(
            AiProvider.OPENAI, settings.openAiKey,
            settings.openAiModel.ifBlank { DefaultModels.OPENAI }, "",
        )
        AiProvider.GEMINI -> Config(
            AiProvider.GEMINI, settings.geminiKey,
            settings.geminiModel.ifBlank { DefaultModels.GEMINI }, "",
        )
        AiProvider.OLLAMA -> Config(
            AiProvider.OLLAMA, "",
            settings.ollamaModel.ifBlank { DefaultModels.OLLAMA },
            settings.ollamaUrl,
        )
        AiProvider.LM_STUDIO -> Config(
            AiProvider.LM_STUDIO, "",
            settings.lmStudioModel,
            settings.lmStudioUrl,
        )
        AiProvider.XAI -> Config(
            AiProvider.XAI, settings.xaiKey,
            settings.xaiModel.ifBlank { DefaultModels.XAI }, "",
        )
        AiProvider.DEEPSEEK -> Config(
            AiProvider.DEEPSEEK, settings.deepSeekKey,
            settings.deepSeekModel.ifBlank { DefaultModels.DEEPSEEK }, "",
        )
        // The key of the web search tool stands in when this one is blank:
        // one Brave account can hold both plans, and the issue that asked for
        // this provider asked for exactly that.
        AiProvider.BRAVE -> Config(
            AiProvider.BRAVE, settings.braveKey.ifBlank { settings.braveSearchKey },
            DefaultModels.BRAVE, "",
        )
        AiProvider.OPENAI_COMPATIBLE -> Config(
            AiProvider.OPENAI_COMPATIBLE, settings.compatibleKey,
            settings.compatibleModel,
            settings.compatibleUrl,
        )
        AiProvider.ON_DEVICE -> Config(
            AiProvider.ON_DEVICE, "",
            settings.localModelId, "",
        )
    }

    /**
     * Substrings that mark a model as one that reasons before answering.
     * Matched against the lowercased model id — deliberately loose, since the
     * cost of a false positive is a slightly larger token ceiling and the cost
     * of a false negative is an answer that never arrives.
     */
    private val REASONING_MODEL_HINTS = listOf(
        "thinking", "reason", "-r1", "qwq", "magistral",
        "gpt-5", "o1-", "o3", "o4-", "qwen3", "qwen-3",
        "grok-4", "deepseek-v4",
    )

    /** Reasoning models get this much more room than the user's setting. */
    private const val REASONING_HEADROOM = 4
    private const val MAX_TOKENS_CEILING = 131_072

    /**
     * [AiSettings.maxTokens] value that means "do not send a ceiling at all, and
     * let the service apply its own". Zero rather than null because the setting
     * is one flat integer preference.
     */
    const val PROVIDER_MAXIMUM = 0

    /**
     * What to send Anthropic for [PROVIDER_MAXIMUM]. Alone among the providers
     * it makes `max_tokens` required, so "no ceiling" has to become a number.
     * The real ceiling is per model, and asking for more than a model allows is
     * a hard 400 rather than a clamp — so this is deliberately optimistic and
     * [anthropicModelCeiling] reads the real limit back out of that error.
     */
    private const val ANTHROPIC_PROVIDER_MAXIMUM = 64_000

    /**
     * The output ceiling named in an Anthropic "max_tokens too large" error,
     * e.g. `max_tokens: 64000 > 8192, which is the maximum…`. Anthropic is the
     * only provider that rejects an over-large ceiling instead of clamping it,
     * and the model that decides the number is the user's to choose, so the
     * limit cannot be known ahead of the request. Reading it back turns a failed
     * request into one retry that works.
     */
    private val ANTHROPIC_CEILING = Regex("""max_tokens:\s*\d+\s*>\s*(\d+)""")

    private fun anthropicModelCeiling(error: ToolHttpException): Int? =
        error.apiMessage?.let { ANTHROPIC_CEILING.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * Whether the selected model is expected to reason before answering. A
     * guess from the model id — see [REASONING_MODEL_HINTS] — so callers must
     * treat a `false` as "probably not" rather than proof.
     */
    fun expectsReasoning(settings: AiSettings): Boolean {
        val model = config(settings).model.lowercase()
        return REASONING_MODEL_HINTS.any { it in model }
    }

    /**
     * The token ceiling to send for one request. A reasoning model spends most
     * of its budget on the think block before writing a word of the answer, so
     * the user's "max response length" — a number they picked thinking about
     * the *answer* — buys them nothing but truncated reasoning. Multiply it for
     * those models instead of making the user discover the problem.
     */
    fun effectiveMaxTokens(settings: AiSettings): Int? {
        if (settings.maxTokens == PROVIDER_MAXIMUM) return null
        if (!expectsReasoning(settings)) return settings.maxTokens
        return (settings.maxTokens.toLong() * REASONING_HEADROOM)
            .coerceAtMost(MAX_TOKENS_CEILING.toLong())
            .toInt()
    }

    /** Whether the selected provider has what it needs to make a request. */
    fun isConfigured(settings: AiSettings): Boolean {
        val config = config(settings)
        return when (config.provider) {
            AiProvider.OLLAMA, AiProvider.LM_STUDIO -> config.baseUrl.isNotBlank()
            // A key is optional here: a gateway on the user's own network often
            // wants none. The model is not, because there is no sensible default
            // for a service we know nothing about.
            AiProvider.OPENAI_COMPATIBLE ->
                config.baseUrl.isNotBlank() && config.model.isNotBlank()
            AiProvider.ON_DEVICE -> config.model.isNotBlank()
            else -> config.apiKey.isNotBlank()
        }
    }

    /**
     * The cloud/server providers that are ready to use right now — the AI
     * panel's model picker only offers these. ON_DEVICE is excluded: its
     * choices are per-model and need file checks the caller owns.
     */
    fun configuredRemoteProviders(settings: AiSettings): List<AiProvider> =
        AiProvider.displayOrder.filter { provider ->
            when (provider) {
                AiProvider.ANTHROPIC -> settings.anthropicKey.isNotBlank()
                AiProvider.OPENAI -> settings.openAiKey.isNotBlank()
                AiProvider.GEMINI -> settings.geminiKey.isNotBlank()
                AiProvider.OLLAMA -> settings.ollamaUrl.isNotBlank()
                AiProvider.LM_STUDIO -> settings.lmStudioUrl.isNotBlank()
                AiProvider.XAI -> settings.xaiKey.isNotBlank()
                AiProvider.DEEPSEEK -> settings.deepSeekKey.isNotBlank()
                // The web search key counts only once Brave is the chosen
                // provider. Otherwise everyone with their own search key would
                // find a Brave chip in the pickers they never asked for, and a
                // key with only the Search plan cannot answer anyway.
                AiProvider.BRAVE -> settings.braveKey.isNotBlank() ||
                    (settings.provider == AiProvider.BRAVE && settings.braveSearchKey.isNotBlank())
                AiProvider.OPENAI_COMPATIBLE ->
                    settings.compatibleUrl.isNotBlank() && settings.compatibleModel.isNotBlank()
                AiProvider.ON_DEVICE -> false
            }
        }

    /**
     * Whether this provider has real function calling, as opposed to being
     * told about tools in prose (#470).
     *
     * Everything that speaks the OpenAI shape counts, including the user's own
     * gateway: carrying `tools` is most of what "OpenAI-compatible" means, and
     * a server that ignores the field simply never calls anything, which is
     * the same place the prose fallback ends up for a model that cannot
     * follow it. The two that are out are out for structural reasons —
     * Brave's Answers API takes a single user message and nothing else, and an
     * on-device model is not reached over HTTP at all.
     */
    fun supportsNativeTools(provider: AiProvider): Boolean = when (provider) {
        AiProvider.ANTHROPIC, AiProvider.GEMINI, AiProvider.OLLAMA,
        AiProvider.OPENAI, AiProvider.LM_STUDIO, AiProvider.XAI,
        AiProvider.DEEPSEEK, AiProvider.OPENAI_COMPATIBLE,
        -> true
        AiProvider.BRAVE, AiProvider.ON_DEVICE -> false
    }

    /**
     * Runs one system+user exchange as a *stream*, returning the assembled
     * text. [onPhase] reports how far the request has got and [onPartial] the
     * response so far, so the panel can show the answer forming instead of a
     * spinner. Both may be called from the calling (IO) thread many times.
     *
     * Reasoning that a provider sends on a side channel is folded into the
     * returned text wrapped in `<think>…</think>` — the same shape on-device
     * models emit inline — so [AiThinking], the panel's gray-out and the "show
     * reasoning" setting all keep working with no second code path.
     *
     * A null [maxTokens] means "send no ceiling", so the service applies its own
     * default. Anthropic is the exception: it requires the field.
     */
    fun completeStreaming(
        config: Config,
        system: String,
        user: String,
        maxTokens: Int?,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean = { true },
    ): Completion = completeStreaming(
        config, system, listOf(ChatTurn(ChatRole.USER, user)),
        maxTokens, emptyList(), onPhase, onPartial, isActive,
    )

    /**
     * The multi-turn form: [turns] is the whole conversation oldest first,
     * ending with the user message to answer. Assistant turns should already
     * be think-stripped — resending reasoning wastes the window and some
     * services echo it back.
     */
    fun completeStreaming(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean = { true },
    ): Completion = completeStreaming(
        config, system, turns, maxTokens, emptyList(), onPhase, onPartial, isActive,
    )

    /**
     * The form that offers [tools]. An empty list is an ordinary request, so
     * nothing about a run with tools turned off changes at all — no field is
     * added to the body and no provider is told that tools exist.
     *
     * One round only: the model may answer, or it may come back asking for a
     * tool, and [Completion.toolCalls] is how it says which. Driving that to
     * an answer is [AiToolLoop]'s job, not this function's.
     */
    fun completeStreaming(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec>,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean = { true },
    ): Completion {
        val chat = normalizedTurns(turns)
        require(chat.isNotEmpty()) { "No user message to answer" }
        return when (config.provider) {
            AiProvider.ANTHROPIC ->
                anthropicStream(config, system, chat, maxTokens, tools, onPhase, onPartial, isActive)
            AiProvider.GEMINI ->
                geminiStream(config, system, chat, maxTokens, tools, onPhase, onPartial, isActive)
            AiProvider.OLLAMA ->
                ollamaStream(config, system, chat, maxTokens, tools, onPhase, onPartial, isActive)
            // Brave takes one user message and no tool field; see braveStream.
            AiProvider.BRAVE ->
                braveStream(config, system, chat, maxTokens, onPhase, onPartial, isActive)
            AiProvider.OPENAI, AiProvider.LM_STUDIO, AiProvider.XAI,
            AiProvider.DEEPSEEK, AiProvider.OPENAI_COMPATIBLE,
            -> openAiCompatibleStream(
                openAiCompatibleUrl(config),
                config, system, chat, maxTokens, tools, onPhase, onPartial, isActive,
            )
            AiProvider.ON_DEVICE ->
                error("On-device models run locally, not over HTTP")
        }
    }

    /**
     * [completeStreaming], driven round after round until the model stops
     * asking for tools and answers (#470).
     *
     * This is the one call site worth using when tools are on: it picks the
     * native or the sentinel mechanism from the provider, keeps the rounds
     * bounded, and streams the whole run — earlier rounds' text included — as
     * one growing answer, so the panel never appears to restart.
     */
    @Suppress("LongParameterList")
    fun completeWithTools(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec>,
        executor: AiToolLoop.Executor,
        maxRounds: Int = AiTools.DEFAULT_MAX_ROUNDS,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        onToolCall: (AiToolCall) -> Unit = {},
        onToolResult: (AiToolResult) -> Unit = {},
        isActive: () -> Boolean = { true },
    ): Completion = AiToolLoop.run(
        system = system,
        turns = turns,
        tools = tools,
        native = supportsNativeTools(config.provider),
        executor = executor,
        maxRounds = maxRounds,
        onPhase = onPhase,
        onPartial = onPartial,
        onToolCall = onToolCall,
        onToolResult = onToolResult,
        isActive = isActive,
    ) { roundSystem, roundTurns, roundTools, roundPartial ->
        completeStreaming(
            config, roundSystem, roundTurns, maxTokens, roundTools,
            onPhase, roundPartial, isActive,
        )
    }

    /**
     * Puts a conversation into the shape every provider accepts: no blank
     * messages, no leading assistant turn, and no two same-role messages in a
     * row (a failed turn that was dropped leaves its user messages adjacent).
     * Anthropic rejects all three outright; the rest merely tolerate them.
     */
    internal fun normalizedTurns(turns: List<ChatTurn>): List<ChatTurn> {
        val result = ArrayList<ChatTurn>(turns.size)
        for (turn in turns) {
            // A tool exchange is machinery, not conversation: it may be blank
            // (a model that only called a tool wrote no text), it may repeat a
            // role, and it must stay exactly where it is or the ids stop
            // lining up with the calls they answer.
            if (turn.isToolExchange) {
                result.add(turn)
                continue
            }
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            if (result.isEmpty() && turn.role != ChatRole.USER) continue
            val last = result.lastOrNull()
            if (last != null && last.role == turn.role && !last.isToolExchange) {
                result[result.lastIndex] = last.copy(text = last.text + "\n\n" + text)
            } else {
                result.add(turn.copy(text = text))
            }
        }
        // A conversation must end on the message being answered; a trailing
        // assistant turn would ask the model to continue itself. A trailing
        // tool exchange is the opposite: it is precisely what the next
        // response has to read.
        while (result.isNotEmpty() &&
            result.last().role == ChatRole.ASSISTANT &&
            !result.last().isToolExchange
        ) {
            result.removeAt(result.lastIndex)
        }
        return result
    }

    internal fun parseAnthropic(body: String): String =
        json.parseToJsonElement(body).jsonObject["content"]?.jsonArray
            ?.firstNotNullOfOrNull { block ->
                block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.content == "text" }
                    ?.get("text")?.jsonPrimitive?.content
            }.orEmpty().trim()

    internal fun parseOpenAi(body: String): String =
        json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content.orEmpty().trim()

    internal fun parseGemini(body: String): String =
        json.parseToJsonElement(body).jsonObject["candidates"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            ?.joinToString("").orEmpty().trim()

    internal fun parseOllama(body: String): String =
        json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
            ?.get("content")?.jsonPrimitive?.content.orEmpty().trim()

    // ---- "the answer was cut off" ----

    /**
     * Whether a whole (non-streamed) response body says the model stopped
     * because it ran out of room. Used only on the fallback path, where a proxy
     * ignored `stream: true` and answered with one ordinary body; the streaming
     * path reads the same signal event by event in the `apply*Event` functions.
     *
     * Each provider spells it differently and none of them is an error, so a
     * missing or unknown value always reads as "not truncated".
     */
    internal fun anthropicTruncated(body: String): Boolean =
        body.stopReason("stop_reason") == "max_tokens"

    internal fun openAiTruncated(body: String): Boolean =
        runCatching {
            json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.text("finish_reason")
        }.getOrNull() == "length"

    internal fun geminiTruncated(body: String): Boolean =
        runCatching {
            val root = json.parseToJsonElement(body)
            val chunks = (root as? JsonArray) ?: JsonArray(listOf(root))
            chunks.any { chunk ->
                chunk.jsonObject["candidates"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.text("finishReason") == "MAX_TOKENS"
            }
        }.getOrDefault(false)

    internal fun ollamaTruncated(body: String): Boolean =
        body.lineSequence().any { it.stopReason("done_reason") == "length" }

    private fun String.stopReason(field: String): String? =
        runCatching { json.parseToJsonElement(this).jsonObject.text(field) }.getOrNull()

    // ---- streaming ----

    /**
     * Assembles a streamed response, wrapping reasoning that arrives on a
     * separate channel in `<think>…</think>`. See [completeStreaming] for why.
     */
    internal class StreamBuffer {
        private val text = StringBuilder()
        private var inThink = false

        /**
         * Calls whose arguments stream in fragment by fragment, keyed by the
         * index the provider numbers them with. OpenAI and Anthropic both work
         * this way: the name arrives first and the JSON of the arguments
         * arrives in pieces that mean nothing until the last one lands.
         */
        private val streamedCalls = LinkedHashMap<Int, PartialCall>()

        /** Calls that arrive whole in one event: Gemini and Ollama. */
        private val wholeCalls = ArrayList<AiToolCall>()

        private class PartialCall(var id: String, var name: String) {
            val arguments = StringBuilder()
        }

        /**
         * The provider said it stopped because it ran out of room. Set from a
         * stop-reason event rather than guessed from the text, because a
         * sentence that ends mid-word and one that ends on a full stop are
         * equally likely to have been cut.
         */
        var truncated = false
            private set

        fun markTruncated() {
            truncated = true
        }

        fun reasoning(chunk: String) {
            if (chunk.isEmpty()) return
            if (!inThink) {
                text.append(THINK_OPEN)
                inThink = true
            }
            text.append(chunk)
        }

        fun answer(chunk: String) {
            if (chunk.isEmpty()) return
            if (inThink) {
                text.append(THINK_CLOSE)
                inThink = false
            }
            text.append(chunk)
        }

        /** A call announced ahead of its arguments; see [streamedCalls]. */
        fun toolCallStart(index: Int, id: String, name: String) {
            val call = streamedCalls.getOrPut(index) { PartialCall(id, name) }
            if (id.isNotEmpty()) call.id = id
            if (name.isNotEmpty()) call.name = name
        }

        /** The next fragment of one call's argument JSON. */
        fun toolCallArguments(index: Int, fragment: String) {
            if (fragment.isEmpty()) return
            streamedCalls.getOrPut(index) { PartialCall("", "") }.arguments.append(fragment)
        }

        /** A call that came complete in one event. */
        fun toolCall(id: String, name: String, arguments: String) {
            if (name.isEmpty()) return
            wholeCalls.add(AiToolCall(id, name, arguments.ifBlank { "{}" }))
        }

        /**
         * The calls so far. A provider uses one mechanism or the other, never
         * both, so concatenating them cannot interleave anything.
         */
        val toolCalls: List<AiToolCall>
            get() = wholeCalls + streamedCalls.values.mapNotNull { call ->
                call.name.takeIf { it.isNotEmpty() }?.let {
                    AiToolCall(call.id, it, call.arguments.toString().ifBlank { "{}" })
                }
            }

        val hasToolCalls: Boolean get() = wholeCalls.isNotEmpty() || streamedCalls.isNotEmpty()

        /** The stream so far — an unclosed think block reads as "still thinking". */
        val partial: String get() = text.toString()

        val isEmpty: Boolean get() = text.isEmpty()

        /**
         * Final text, closing an unterminated think block so it parses. A
         * response that was *all* reasoning therefore strips to nothing, which
         * is what the caller reports as "spent its whole response reasoning".
         */
        fun finish(): Completion {
            if (inThink) {
                text.append(THINK_CLOSE)
                inThink = false
            }
            return Completion(text.toString(), truncated, toolCalls)
        }

        private companion object {
            const val THINK_OPEN = "<think>"
            const val THINK_CLOSE = "</think>"
        }
    }

    /**
     * The JSON payload of one SSE line, or null for anything not worth parsing
     * — `event:`/`id:`/comment lines, the blank separators between events, and
     * the `[DONE]` sentinel OpenAI-compatible servers end with.
     */
    internal fun sseData(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        return payload.takeIf { it.isNotEmpty() && it != "[DONE]" }
    }

    /**
     * Runs a streaming request and returns the assembled text, falling back to
     * the provider's plain (non-streamed) response shape when nothing streamed.
     * A proxy or older server that ignores `stream: true` answers with one
     * ordinary JSON body, which would otherwise read as an empty response —
     * [fallback] parses the collected body instead of costing a second request.
     */
    private fun runStream(
        source: NetSource,
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean,
        apply: (String, StreamBuffer) -> Unit,
        fallback: (String) -> Completion,
    ): Completion {
        val buffer = StreamBuffer()
        val collected = StringBuilder()
        onPhase(AiPhase.CONNECTING)
        ToolHttp.postJsonStream(
            url = url,
            body = body,
            timeoutMs = timeoutMs,
            headers = headers,
            onRequestSent = { onPhase(AiPhase.WAITING) },
            source = source,
            // Provider paths name the model at most, never the prompt.
            route = NetLog.pathOf(url),
        ) { line ->
            collected.append(line).append('\n')
            val before = buffer.partial
            apply(line, buffer)
            if (buffer.partial != before) onPartial(buffer.partial)
            isActive()
        }
        // A round that asked for a tool and wrote nothing else is a real,
        // complete response: without the second test it would read as "nothing
        // streamed" and be re-parsed as a plain body, losing the calls.
        if (!buffer.isEmpty || buffer.hasToolCalls) return buffer.finish()
        return runCatching { fallback(collected.toString()) }.getOrDefault(Completion(""))
    }

    private fun anthropicStream(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec>,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean,
    ): Completion {
        // Anthropic is the one provider that makes the ceiling mandatory, so
        // "let the service decide" has to become a number here.
        val asked = maxTokens ?: ANTHROPIC_PROVIDER_MAXIMUM
        return try {
            anthropicStreamOnce(config, system, turns, asked, tools, onPhase, onPartial, isActive)
        } catch (e: ToolHttpException) {
            // Asking for more output than the chosen model allows is a hard 400
            // rather than a clamp, and the error names the real ceiling. Retry
            // at that number: nothing has streamed yet, because the status is
            // read before the first byte of the body.
            val ceiling = anthropicModelCeiling(e)?.takeIf { it < asked } ?: throw e
            anthropicStreamOnce(config, system, turns, ceiling, tools, onPhase, onPartial, isActive)
        }
    }

    internal fun anthropicBody(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int,
        tools: List<AiToolSpec> = emptyList(),
    ): String = buildJsonObject {
        put("model", config.model)
        put("max_tokens", maxTokens)
        put("system", system)
        put("stream", true)
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (tool in tools) {
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        // Anthropic's name for the schema every other provider
                        // calls "parameters".
                        put("input_schema", tool.parameters)
                    })
                }
            })
        }
        put("messages", anthropicMessages(turns))
    }.toString()

    /**
     * Anthropic's messages, where a tool exchange is content blocks rather
     * than roles of its own: the model's request is a `tool_use` block on its
     * assistant message, and the answers come back as `tool_result` blocks on
     * a *user* message. Consecutive results are therefore gathered into one
     * message — two user messages in a row is one of the three things
     * Anthropic rejects outright.
     */
    private fun anthropicMessages(turns: List<ChatTurn>): JsonArray = buildJsonArray {
        var index = 0
        while (index < turns.size) {
            val turn = turns[index]
            if (turn.role == ChatRole.TOOL) {
                val group = ArrayList<ChatTurn>()
                while (index < turns.size && turns[index].role == ChatRole.TOOL) {
                    group.add(turns[index])
                    index++
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        for (result in group) {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", result.toolResult?.call?.id.orEmpty())
                                put("content", result.text)
                            })
                        }
                    })
                })
                continue
            }
            index++
            if (turn.toolCalls.isEmpty()) {
                add(buildJsonObject {
                    put("role", if (turn.role == ChatRole.USER) "user" else "assistant")
                    put("content", turn.text)
                })
                continue
            }
            add(buildJsonObject {
                put("role", "assistant")
                put("content", buildJsonArray {
                    if (turn.text.isNotBlank()) {
                        add(buildJsonObject { put("type", "text"); put("text", turn.text) })
                    }
                    for (call in turn.toolCalls) {
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            put("input", argumentsObject(call))
                        })
                    }
                })
            })
        }
    }

    private fun anthropicStreamOnce(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int,
        tools: List<AiToolSpec>,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean,
    ): Completion {
        val body = anthropicBody(config, system, turns, maxTokens, tools)
        return runStream(
            source = config.netSource,
            url = ServiceEndpoints.base(ServiceEndpoint.ANTHROPIC) + "/v1/messages",
            body = body,
            headers = mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            timeoutMs = 120_000,
            onPhase = onPhase,
            onPartial = onPartial,
            isActive = isActive,
            apply = { line, buffer -> sseData(line)?.let { applyAnthropicEvent(it, buffer) } },
            fallback = { Completion(parseAnthropic(it), anthropicTruncated(it)) },
        )
    }

    /** Folds one Anthropic SSE payload into [buffer]; ignores lifecycle events. */
    internal fun applyAnthropicEvent(data: String, buffer: StreamBuffer) {
        val event = data.asJsonObject() ?: return
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            // A tool_use block names the call up front; its arguments then
            // arrive as input_json_delta fragments against the same index.
            "content_block_start" -> {
                val block = event["content_block"]?.jsonObject ?: return
                if (block.text("type") != "tool_use") return
                buffer.toolCallStart(event.index(), block.text("id"), block.text("name"))
            }
            "content_block_delta" -> {
                val delta = event["delta"]?.jsonObject ?: return
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> buffer.answer(delta.text("text"))
                    "thinking_delta" -> buffer.reasoning(delta.text("thinking"))
                    "input_json_delta" ->
                        buffer.toolCallArguments(event.index(), delta.text("partial_json"))
                }
            }
            // The stop reason rides on the final message_delta, not on any
            // content block.
            "message_delta" -> {
                val delta = event["delta"]?.jsonObject ?: return
                if (delta.text("stop_reason") == "max_tokens") buffer.markTruncated()
            }
            // A failure mid-stream arrives as an event, not an HTTP status, so
            // it has to be raised from here or the answer silently truncates.
            "error" -> throw streamFailure(event["error"]?.jsonObject?.text("message"))
        }
    }

    internal fun openAiCompatibleBody(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec> = emptyList(),
    ): String = buildJsonObject {
        if (config.model.isNotBlank()) put("model", config.model)
        // Left out entirely for "provider maximum": every OpenAI-shaped
        // service then applies its own default, which is what the user asked
        // for. Sending a huge number instead would be rejected by some.
        if (maxTokens != null) put("max_tokens", maxTokens)
        put("stream", true)
        if (tools.isNotEmpty()) put("tools", openAiTools(tools))
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            for (turn in turns) add(openAiMessage(turn))
        })
    }.toString()

    /** The `tools` array OpenAI, Ollama and everything OpenAI-shaped reads. */
    private fun openAiTools(tools: List<AiToolSpec>): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                }
            })
        }
    }

    /**
     * One message in the OpenAI shape. A tool answer is its own `tool` role
     * quoting the call's id; the assistant turn that asked carries the calls
     * alongside whatever text it wrote first.
     */
    private fun openAiMessage(turn: ChatTurn): JsonObject = buildJsonObject {
        when {
            turn.role == ChatRole.TOOL -> {
                put("role", "tool")
                put("tool_call_id", turn.toolResult?.call?.id.orEmpty())
                put("name", turn.toolResult?.call?.name.orEmpty())
                put("content", turn.text)
            }
            turn.toolCalls.isNotEmpty() -> {
                put("role", "assistant")
                put("content", turn.text)
                put("tool_calls", buildJsonArray {
                    for (call in turn.toolCalls) {
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                // A string of JSON, not an object: this one
                                // field is quoted in OpenAI's wire format.
                                put("arguments", call.arguments)
                            }
                        })
                    }
                })
            }
            else -> {
                put("role", if (turn.role == ChatRole.USER) "user" else "assistant")
                put("content", turn.text)
            }
        }
    }

    private fun openAiCompatibleStream(
        url: String,
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec>,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean,
    ): Completion {
        val body = openAiCompatibleBody(config, system, turns, maxTokens, tools)
        val headers = if (config.apiKey.isNotBlank()) {
            mapOf("Authorization" to "Bearer ${config.apiKey}")
        } else {
            emptyMap()
        }
        return runStream(
            source = config.netSource,
            url = url,
            body = body,
            headers = headers,
            timeoutMs = 120_000,
            onPhase = onPhase,
            onPartial = onPartial,
            isActive = isActive,
            apply = { line, buffer -> sseData(line)?.let { applyOpenAiEvent(it, buffer) } },
            fallback = { Completion(parseOpenAi(it), openAiTruncated(it)) },
        )
    }

    /** Folds one OpenAI-compatible SSE payload into [buffer]. */
    internal fun applyOpenAiEvent(data: String, buffer: StreamBuffer) {
        val event = data.asJsonObject() ?: return
        event["error"]?.jsonObject?.let { error ->
            throw streamFailure(error.text("message").ifEmpty { error.text("detail") })
        }
        val delta = event["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject ?: return
        // Reasoning models behind an OpenAI-compatible server put the think
        // stream on a side field; vLLM and DeepSeek name it reasoning_content,
        // others just reasoning.
        buffer.reasoning(delta.text("reasoning_content").ifEmpty { delta.text("reasoning") })
        buffer.answer(delta.text("content"))
        // Tool calls stream as fragments keyed by index: the id and name come
        // on the first chunk for a given index, the argument JSON in pieces
        // after it. A fragment on its own is not valid JSON, which is why the
        // buffer has to hold them rather than parse as it goes.
        delta["tool_calls"]?.jsonArray?.forEach { element ->
            val entry = element.jsonObject
            val index = (entry["index"] as? JsonPrimitive)?.intOrNull ?: 0
            val function = entry["function"]?.jsonObject
            buffer.toolCallStart(index, entry.text("id"), function?.text("name").orEmpty())
            function?.text("arguments")?.let { buffer.toolCallArguments(index, it) }
        }
        // The last chunk of a cut-off answer carries this instead of "stop".
        val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        if (choice?.text("finish_reason") == "length") buffer.markTruncated()
    }

    internal fun geminiBody(
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec> = emptyList(),
    ): String = buildJsonObject {
        putJsonObject("system_instruction") {
            put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
        }
        if (tools.isNotEmpty()) {
            // One entry holding every declaration, which is how Gemini spells
            // it — not one entry per tool.
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        for (tool in tools) {
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                // The full JSON Schema field, not `parameters`:
                                // that one takes only an OpenAPI subset and
                                // refuses `$schema`, `additionalProperties` and
                                // the rest of what an MCP server's tool
                                // schemas use (#573).
                                put("parametersJsonSchema", tool.parameters)
                            })
                        }
                    })
                })
            })
        }
        put("contents", buildJsonArray {
            for (turn in turns) add(geminiContent(turn))
        })
        // Left out entirely for "provider maximum", so Gemini writes up to
        // the model's own output limit. This is the setting that used to cut
        // a long answer off in the middle with nothing to say so.
        if (maxTokens != null) {
            putJsonObject("generationConfig") { put("maxOutputTokens", maxTokens) }
        }
    }.toString()

    /**
     * One Gemini content. A tool answer is a *user* part of `functionResponse`
     * — Gemini has no tool role — and is matched to its call by name rather
     * than by an id, which is why [AiToolCall.id] is left blank here.
     */
    private fun geminiContent(turn: ChatTurn): JsonObject = buildJsonObject {
        when {
            turn.role == ChatRole.TOOL -> {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", turn.toolResult?.call?.name.orEmpty())
                            putJsonObject("response") { put("result", turn.text) }
                        }
                    })
                })
            }
            turn.toolCalls.isNotEmpty() -> {
                put("role", "model")
                put("parts", buildJsonArray {
                    if (turn.text.isNotBlank()) {
                        add(buildJsonObject { put("text", turn.text) })
                    }
                    for (call in turn.toolCalls) {
                        add(buildJsonObject {
                            putJsonObject("functionCall") {
                                put("name", call.name)
                                put("args", argumentsObject(call))
                            }
                        })
                    }
                })
            }
            else -> {
                // Gemini's name for the assistant role is "model".
                put("role", if (turn.role == ChatRole.USER) "user" else "model")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", turn.text) }) })
            }
        }
    }

    private fun geminiStream(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec>,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean,
    ): Completion {
        val body = geminiBody(system, turns, maxTokens, tools)
        return runStream(
            source = config.netSource,
            url = ServiceEndpoints.base(ServiceEndpoint.GEMINI) + "/v1beta/models/" +
                "${config.model}:streamGenerateContent?alt=sse",
            body = body,
            headers = mapOf("x-goog-api-key" to config.apiKey),
            timeoutMs = 120_000,
            onPhase = onPhase,
            onPartial = onPartial,
            isActive = isActive,
            apply = { line, buffer -> sseData(line)?.let { applyGeminiEvent(it, buffer) } },
            fallback = { Completion(parseGeminiChunks(it), geminiTruncated(it)) },
        )
    }

    /** Folds one Gemini SSE payload into [buffer]. */
    internal fun applyGeminiEvent(data: String, buffer: StreamBuffer) {
        val event = data.asJsonObject() ?: return
        event["error"]?.jsonObject?.let { error ->
            throw streamFailure(error.text("message"))
        }
        val candidate = event["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        if (candidate?.text("finishReason") == "MAX_TOKENS") buffer.markTruncated()
        val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return
        for (element in parts) {
            val part = element.jsonObject
            // Gemini sends a whole call in one part, arguments already parsed
            // — no fragments to reassemble, unlike OpenAI and Anthropic.
            part["functionCall"]?.jsonObject?.let { call ->
                buffer.toolCall(
                    id = "",
                    name = call.text("name"),
                    arguments = (call["args"] as? JsonObject)?.toString().orEmpty(),
                )
            }
            val text = part.text("text")
            if (text.isEmpty()) continue
            // Gemini flags a reasoning part rather than sending it separately.
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                buffer.reasoning(text)
            } else {
                buffer.answer(text)
            }
        }
    }

    /**
     * A `streamGenerateContent` body that came back as one blob rather than an
     * event stream: a JSON array of the same chunks (what you get when a proxy
     * drops the `alt=sse` query). Concatenates their text; a lone object — the
     * plain `generateContent` shape — parses through [parseGemini].
     */
    internal fun parseGeminiChunks(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return ""
        val chunks = root as? JsonArray ?: return parseGemini(body)
        return chunks.joinToString("") { chunk ->
            val parts = chunk.jsonObject["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            parts?.joinToString("") { it.jsonObject.text("text") }.orEmpty()
        }.trim()
    }

    internal fun ollamaBody(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec> = emptyList(),
    ): String = buildJsonObject {
        put("model", config.model)
        put("stream", true)
        if (tools.isNotEmpty()) put("tools", openAiTools(tools))
        // Ollama spells the ceiling num_predict, and it sits under options
        // rather than at the top level. Left out for "provider maximum",
        // which is what this request always did before the setting reached
        // it at all.
        if (maxTokens != null) {
            putJsonObject("options") { put("num_predict", maxTokens) }
        }
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            // Ollama reads the OpenAI message shape, with one difference the
            // builder below handles: its tool_calls carry the arguments as an
            // object rather than as a string of JSON.
            for (turn in turns) add(ollamaMessage(turn))
        })
    }.toString()

    /** [openAiMessage], but with object-valued tool arguments. */
    private fun ollamaMessage(turn: ChatTurn): JsonObject = when {
        turn.role == ChatRole.TOOL -> buildJsonObject {
            put("role", "tool")
            put("tool_name", turn.toolResult?.call?.name.orEmpty())
            put("content", turn.text)
        }
        turn.toolCalls.isNotEmpty() -> buildJsonObject {
            put("role", "assistant")
            put("content", turn.text)
            put("tool_calls", buildJsonArray {
                for (call in turn.toolCalls) {
                    add(buildJsonObject {
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", argumentsObject(call))
                        }
                    })
                }
            })
        }
        else -> buildJsonObject {
            put("role", if (turn.role == ChatRole.USER) "user" else "assistant")
            put("content", turn.text)
        }
    }

    private fun ollamaStream(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        tools: List<AiToolSpec>,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean,
    ): Completion {
        val body = ollamaBody(config, system, turns, maxTokens, tools)
        return runStream(
            source = config.netSource,
            url = "${config.baseUrl.trimEnd('/')}/api/chat",
            body = body,
            headers = emptyMap(),
            timeoutMs = 120_000,
            onPhase = onPhase,
            onPartial = onPartial,
            isActive = isActive,
            // Ollama streams newline-delimited JSON, not SSE — every line is a
            // whole object, with no `data:` prefix to strip.
            apply = ::applyOllamaEvent,
            fallback = { Completion(parseOllama(it), ollamaTruncated(it)) },
        )
    }

    /** Folds one line of Ollama's newline-delimited JSON stream into [buffer]. */
    internal fun applyOllamaEvent(line: String, buffer: StreamBuffer) {
        val event = line.asJsonObject() ?: return
        event["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            throw streamFailure(it)
        }
        // Rides on the final object, alongside "done": true.
        if (event.text("done_reason") == "length") buffer.markTruncated()
        val message = event["message"]?.jsonObject ?: return
        buffer.reasoning(message.text("thinking"))
        buffer.answer(message.text("content"))
        message["tool_calls"]?.jsonArray?.forEach { element ->
            val function = element.jsonObject["function"]?.jsonObject ?: return@forEach
            buffer.toolCall(
                id = "",
                name = function.text("name"),
                // An object here, not the quoted JSON OpenAI sends.
                arguments = when (val args = function["arguments"]) {
                    null -> ""
                    is JsonPrimitive -> args.content
                    else -> args.toString()
                },
            )
        }
    }

    // ---- Brave ----

    /**
     * Brave's Answers API speaks the OpenAI chat-completions shape, with three
     * differences that matter here. It searches the web before it answers, so
     * every request costs a search as well as tokens. It takes exactly one user
     * message: no system role and no history, so both are folded into that one
     * message by [foldedPrompt]. And it writes metadata into the answer
     * text itself as `<usage>…</usage>` (and `<citation>…</citation>` when
     * citations are on), which [BraveTagFilter] strips before anyone sees it.
     */
    private fun braveStream(
        config: Config,
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        onPhase: (AiPhase) -> Unit,
        onPartial: (String) -> Unit,
        isActive: () -> Boolean,
    ): Completion {
        val filter = BraveTagFilter()
        return runStream(
            source = config.netSource,
            url = ServiceEndpoints.base(ServiceEndpoint.BRAVE_SEARCH) + "/res/v1/chat/completions",
            body = braveBody(system, turns, maxTokens),
            headers = mapOf("X-Subscription-Token" to config.apiKey),
            timeoutMs = 120_000,
            onPhase = onPhase,
            onPartial = onPartial,
            isActive = isActive,
            apply = { line, buffer ->
                // A tag prefix held back at the very end is released here.
                if (line.startsWith("data:") && line.removePrefix("data:").trim() == "[DONE]") {
                    buffer.answer(filter.flush())
                }
                sseData(line)?.let { applyBraveEvent(it, filter, buffer) }
            },
            fallback = { Completion(stripBraveTags(parseOpenAi(it)).trim(), openAiTruncated(it)) },
        )
    }

    internal fun braveBody(
        system: String,
        turns: List<ChatTurn>,
        maxTokens: Int?,
    ): String = buildJsonObject {
        put("model", DefaultModels.BRAVE)
        put("stream", true)
        // Brave's spelling; it does not read max_tokens.
        if (maxTokens != null) put("max_completion_tokens", maxTokens)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", foldedPrompt(system, turns))
            })
        })
    }.toString()

    /**
     * The whole exchange as one message, for the two places that can only
     * take one: Brave's Answers API, which has no system role and no history,
     * and an on-device model mid-way through a tool loop, whose engine call
     * takes a single user string.
     *
     * A one-turn request is the instructions followed by the text, so nothing
     * about an ordinary run changes; a longer exchange is laid out as a
     * transcript, so the model can still tell what was already said from what
     * it is being asked now. Pass a blank [system] when the caller has its own
     * way to send one.
     */
    fun foldedPrompt(system: String, turns: List<ChatTurn>): String {
        val last = turns.last().text
        val earlier = turns.dropLast(1)
        return buildString {
            if (system.isNotBlank()) append(system.trim()).append("\n\n")
            if (earlier.isNotEmpty()) {
                append("Conversation so far:\n\n")
                for (turn in earlier) {
                    append(if (turn.role == ChatRole.USER) "User: " else "Assistant: ")
                    append(turn.text).append("\n\n")
                }
                append("Reply to this latest message:\n\n")
            }
            append(last)
        }
    }

    /** Folds one Brave SSE payload into [buffer], through [filter]. */
    internal fun applyBraveEvent(data: String, filter: BraveTagFilter, buffer: StreamBuffer) {
        val event = data.asJsonObject() ?: return
        event["error"]?.jsonObject?.let { error ->
            throw streamFailure(error.text("message").ifEmpty { error.text("detail") })
        }
        val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
        choice["delta"]?.jsonObject?.let { buffer.answer(filter.feed(it.text("content"))) }
        val finish = choice.text("finish_reason")
        if (finish.isNotEmpty()) buffer.answer(filter.flush())
        if (finish == "length") buffer.markTruncated()
    }

    /** The tags Brave writes into the answer text that are not answer. */
    private val BRAVE_TAGS = listOf("usage", "citation")

    internal fun stripBraveTags(text: String): String {
        val filter = BraveTagFilter()
        return filter.feed(text) + filter.flush()
    }

    /**
     * Removes Brave's inline metadata tags from a stream whose chunks can cut
     * a tag anywhere, `<us` in one event and `age>{…}</usage>` in the next.
     * Text that might still turn into a tag is held back until it either does
     * or cannot; everything else passes through at once, so the answer still
     * appears as it streams.
     */
    internal class BraveTagFilter {
        private val pending = StringBuilder()

        /** Takes the next chunk, returns the text now known to be answer. */
        fun feed(chunk: String): String {
            pending.append(chunk)
            return drain(final = false)
        }

        /**
         * The end of the stream: a partial tag name is released as text, and a
         * tag that never closed is dropped, because what it held is metadata.
         */
        fun flush(): String = drain(final = true)

        private fun drain(final: Boolean): String {
            val out = StringBuilder()
            var i = 0
            while (i < pending.length) {
                val open = pending.indexOf("<", i)
                if (open < 0) {
                    out.append(pending, i, pending.length)
                    i = pending.length
                    break
                }
                out.append(pending, i, open)
                i = open
                val tag = BRAVE_TAGS.firstOrNull { pending.startsWith("<$it>", i) }
                if (tag != null) {
                    val close = pending.indexOf("</$tag>", i)
                    if (close < 0) {
                        if (final) i = pending.length
                        break
                    }
                    i = close + tag.length + CLOSING_TAG_EXTRA
                    continue
                }
                val rest = pending.substring(i)
                if (!final && BRAVE_TAGS.any { "<$it>".startsWith(rest) }) break
                out.append('<')
                i++
            }
            pending.delete(0, i)
            return out.toString()
        }

        private companion object {
            /** The `</` and `>` around a tag name. */
            const val CLOSING_TAG_EXTRA = 3
        }
    }

    /**
     * The failure to raise for a provider error that arrives inside the stream.
     * The provider's own words pass through when it sent any, exactly as an
     * HTTP error body does; our own wording stands in when it sent none.
     * [ToolHttp.friendlyMessage] renders either one for the panel.
     */
    private fun streamFailure(apiMessage: String?): ToolHttpException =
        ToolHttpException(
            R.string.ftools_ai_stream_error,
            apiMessage = apiMessage?.takeIf { it.isNotBlank() },
        )

    /**
     * Parses one stream line, tolerating anything that isn't a JSON object —
     * keep-alive comments, partially flushed lines and provider-specific noise
     * are normal in a stream and must not abort a response mid-flight.
     */
    private fun String.asJsonObject(): JsonObject? =
        runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()

    /** A string member, or "" when absent, null, or not a string. */
    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** The `index` an Anthropic stream event names its content block by. */
    private fun JsonObject.index(): Int = (this["index"] as? JsonPrimitive)?.intOrNull ?: 0

    /**
     * A call's arguments as an object, for the providers that want one rather
     * than a string of JSON. A model that wrote something that does not parse
     * sends an empty object: the tool then reports its argument missing, which
     * the model can act on, where a malformed body would only 400.
     */
    private fun argumentsObject(call: AiToolCall): JsonObject =
        runCatching { json.parseToJsonElement(call.arguments).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
}
