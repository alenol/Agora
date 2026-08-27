package com.newoether.agora.api.local

import com.newoether.agora.api.*

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.util.ThinkingParser
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.newoether.agora.viewmodel.GenerationCancelHandle

/**
 * Lifecycle state of the single on-device chat engine owned by this provider.
 *
 * The UI (Settings ▸ Local Models) renders a Load/Unload button whose label flips based on
 * this state, and shows a status line. The cancel semantics requested by the user map to:
 *   - Loading  → show "Unload" (cancels the in-flight load)
 *   - Unloading→ show "Load"   (re-loads immediately)
 */
sealed class LocalModelLoadState {
    object Idle : LocalModelLoadState()
    data class Loading(val modelId: String) : LocalModelLoadState()
    data class Loaded(val modelId: String) : LocalModelLoadState()
    data class Unloading(val modelId: String) : LocalModelLoadState()
    data class Error(val message: String) : LocalModelLoadState()
}

class LocalProvider(
    private val context: Context,
    private val settings: SettingsRepository
) : LlmProvider {

    companion object {
        private const val TAG = "LocalProvider"
        private const val CONTEXT_EXCEEDED_PREFIX = "LOCAL_CONTEXT_EXCEEDED:"

        /** Generation watchdog: if the native backend emits no token for this long, we cancel it.
         *  Fixes the "stuck at sending" symptom caused by a wedged llama.cpp sampling loop. */
        private const val STALL_CHECK_MS = 5_000L
        private const val STALL_TIMEOUT_MS = 90_000L
    }

    override val name: String = Constants.PROVIDER_LOCAL
    override val defaultBaseUrl: String = ""

    private var currentEngine: LlamaChatEngine? = null
    private val engineLock = Mutex()

    private val _loadState = MutableStateFlow<LocalModelLoadState>(LocalModelLoadState.Idle)
    /** Observable load/unload state for the Settings UI. */
    val loadState: StateFlow<LocalModelLoadState> = _loadState.asStateFlow()

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val chatModels = settings.localChatModels.first()
        val modelConfig = chatModels.find { it.modelId == config.modelId }
        if (modelConfig == null) {
            emit(StreamEvent.Error(GenerationError.LocalModel("Local model not found: ${config.modelId}")))
            return@flow
        }

        val engine = ensureEngineLoaded(modelConfig)
        if (engine == null) {
            emit(StreamEvent.Error(GenerationError.LocalModel("Failed to load model: ${modelConfig.alias}")))
            return@flow
        }

        // Build template messages, collecting images per-message with <__media__> markers
        val imagePaths = mutableListOf<String>()
        val templateMessages = buildTemplateMessages(
            prepareMessages(messages, config.maxContextWindow),
            config.systemPrompt,
            imagePaths,
        )
        val hasImages = imagePaths.isNotEmpty()

        // Try native chat template first, fall back to ChatML
        val prompt = engine.applyTemplate(templateMessages, addAss = true)
            ?: buildPrompt(templateMessages)
        val promptLength = prompt.length
        val imageCount = imagePaths.size
        if (hasImages) {
            DebugLog.d(TAG, "Generated multimodal prompt ($promptLength chars, $imageCount images)")
        } else {
            DebugLog.d(TAG, "Generated prompt ($promptLength chars)")
        }

        // Generate tokens with unified thinking parsing
        var totalTokens = 0
        var stopped = false
        var rawBuf = ""
        val STOP_PATTERNS = listOf("<|im_end|>", "<|im_start|>")
        val thinkParser = ThinkingParser()
        try {
            val tokenFlow = if (hasImages) {
                engine.generateWithImages(
                    prompt = prompt,
                    imagePaths = imagePaths,
                    temperature = config.temperature ?: modelConfig.temperature,
                    topP = config.topP ?: modelConfig.topP,
                    maxTokens = config.maxTokens ?: modelConfig.maxTokens
                )
            } else {
                engine.generate(
                    prompt = prompt,
                    temperature = config.temperature ?: modelConfig.temperature,
                    topP = config.topP ?: modelConfig.topP,
                    maxTokens = config.maxTokens ?: modelConfig.maxTokens
                )
            }
            // Serialize on-device model work process-wide: a resident chat model plus a
            // concurrently-loaded embedding model can OOM the native heap. Replaces the
            // former global GenerationQueue for the local path (remote generation no
            // longer takes any global slot). Held only across the native sampling loop,
            // not the whole generation, and withLock is cancellable so Stop releases it
            // immediately.
            // INVARIANT: local models never emit tool calls (no tool definitions are sent,
            // and this provider parses none), so one generation acquires this mutex exactly
            // once. If local tool-calling is ever added, the release between rounds would
            // let another conversation's model load interleave — revisit the locking scope
            // (see the matching note on LocalModelSerializer).
            com.newoether.agora.api.LocalModelSerializer.mutex.withLock {
                // Register while still holding the process-wide local-model mutex. This makes the
                // handle generation-specific: it is removed before another conversation can begin
                // native sampling on the shared engine.
                val streamScope = HttpClient.boundStreamScope()
                val nativeCancel = GenerationCancelHandle { engine.cancel() }
                streamScope?.register(nativeCancel)
                try {
                    var lastActivity = System.currentTimeMillis()
                    var stalled = false
                    // Watchdog: a wedged native backend would otherwise hang the UI at "Sending…"
                    // forever. Cancelling the engine makes the callbackFlow close (onError/onDone),
                    // releasing LocalModelSerializer.mutex and surfacing a clear error to the user.
                    // Runs in a detached child scope because flow { } has no CoroutineScope receiver.
                    val watchdogScope = CoroutineScope(coroutineContext + Dispatchers.IO)
                    val stallWatcher = watchdogScope.launch {
                        while (isActive) {
                            delay(STALL_CHECK_MS)
                            if (System.currentTimeMillis() - lastActivity > STALL_TIMEOUT_MS) {
                                DebugLog.e(TAG, "Local generation stalled ${STALL_TIMEOUT_MS}ms with no output; cancelling native backend")
                                engine.cancel()
                                stalled = true
                                break
                            }
                        }
                    }
                    try {
                        tokenFlow.collect { token ->
                            lastActivity = System.currentTimeMillis()
                            if (!coroutineContext.isActive) {
                                engine.cancel()
                                return@collect
                            }
                            if (stopped) return@collect
                            totalTokens++

                            // Check for stop patterns in the rolling buffer
                            rawBuf += token
                            val hit = STOP_PATTERNS.firstOrNull { p -> rawBuf.contains(p) }
                            if (hit != null) {
                                // Strip the stop pattern and anything after it, then stop
                                val cleanEnd = rawBuf.substringBefore(hit)
                                if (cleanEnd.isNotEmpty()) {
                                    thinkParser.feed(
                                        content = cleanEnd,
                                        thinkingEnabled = config.thinkingEnabled,
                                        onText = { emit(StreamEvent.TextChunk(it)) },
                                        onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                                    )
                                }
                                engine.cancel()
                                stopped = true
                                return@collect
                            }

                            // Keep buffer bounded — only as much as longest stop pattern
                            val maxPatLen = STOP_PATTERNS.maxOf { it.length }
                            if (rawBuf.length > maxPatLen * 2) {
                                val emitPart = rawBuf.substring(0, rawBuf.length - maxPatLen)
                                thinkParser.feed(
                                    content = emitPart,
                                    thinkingEnabled = config.thinkingEnabled,
                                    onText = { emit(StreamEvent.TextChunk(it)) },
                                    onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                                )
                                rawBuf = rawBuf.substring(rawBuf.length - maxPatLen)
                            }
                        }
                    } finally {
                        stallWatcher.cancel()
                        watchdogScope.cancel()
                    }
                    if (stalled) {
                        emit(
                            StreamEvent.Error(
                                GenerationError.LocalModel(
                                    "本地模型生成超时（${STALL_TIMEOUT_MS / 1000} 秒无输出）。" +
                                        "请在「本地模型」设置中卸载后重新加载，或减小上下文窗口大小。"
                                )
                            )
                        )
                        return@flow
                    }
                } finally {
                    streamScope?.unregister(nativeCancel)
                }
            }
            // Flush remaining buffer (no stop pattern found)
            if (!stopped && rawBuf.isNotEmpty()) {
                thinkParser.feed(
                    content = rawBuf,
                    thinkingEnabled = config.thinkingEnabled,
                    onText = { emit(StreamEvent.TextChunk(it)) },
                    onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                )
            }
            thinkParser.flush(
                onText = { emit(StreamEvent.TextChunk(it)) },
                onThought = { emit(StreamEvent.ThoughtChunk(it)) }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            engine.cancel()
            emit(StreamEvent.Error(GenerationError.Cancelled))
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Generation failed", e)
            emit(StreamEvent.Error(GenerationError.LocalModel(formatGenerationError(e, modelConfig))))
            return@flow
        }

        emit(
            StreamEvent.UsageUpdate(
                TokenUsage(
                    totalTokenCount = totalTokens.coerceAtLeast(0),
                    outputTokenCount = totalTokens.coerceAtLeast(0),
                )
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun formatGenerationError(
        error: Exception,
        model: com.newoether.agora.data.LocalChatModelConfig
    ): String {
        val message = error.message ?: "Unknown error"
        if (message.startsWith(CONTEXT_EXCEEDED_PREFIX)) {
            val parts = message.removePrefix(CONTEXT_EXCEEDED_PREFIX).split(":")
            val promptTokens = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val contextTokens = parts.getOrNull(1)?.toIntOrNull() ?: model.nCtx
            return context.getString(R.string.local_context_exceeded, promptTokens, contextTokens)
        }
        return "Generation failed: $message"
    }

    private suspend fun ensureEngineLoaded(model: com.newoether.agora.data.LocalChatModelConfig): LlamaChatEngine? {
        return engineLock.withLock {
            val existing = currentEngine
            if (existing != null && existing.modelPath == model.localFilePath) {
                existing.resetContext()
                // Load or unload mmproj based on current config
                if (model.mmprojPath.isNotBlank()) {
                    existing.loadMmproj(model.mmprojPath)
                } else {
                    existing.unloadMmproj()
                }
                _loadState.value = LocalModelLoadState.Loaded(model.modelId)
                existing
            } else {
                existing?.close()
                currentEngine = null
                val engine = LlamaChatEngine(model.localFilePath, model.nCtx)
                if (engine.load()) {
                    if (model.mmprojPath.isNotBlank()) {
                        val loaded = engine.loadMmproj(model.mmprojPath)
                        DebugLog.d(TAG, "mmproj load: $loaded")
                    }
                    currentEngine = engine
                    _loadState.value = LocalModelLoadState.Loaded(model.modelId)
                    engine
                } else {
                    _loadState.value =
                        LocalModelLoadState.Error("Failed to load model: ${model.alias} (${model.localFilePath})")
                    null
                }
            }
        }
    }

    private fun buildTemplateMessages(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        imagePathsOut: MutableList<String>? = null
    ): List<ChatTemplateMessage> {
        val result = mutableListOf<ChatTemplateMessage>()

        if (!systemPrompt.isNullOrBlank()) {
            result.add(ChatTemplateMessage(role = "system", content = systemPrompt))
        }

        for (msg in messages) {
            if (msg.participant == Participant.ERROR) continue

            // Tool call messages: treat as assistant
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        result.add(ChatTemplateMessage(
                            role = "assistant",
                            content = "Tool call: ${seg.toolName}\nArguments: ${seg.toolArgs}"
                        ))
                    }
                } else if (msg.toolCall != null) {
                    result.add(ChatTemplateMessage(
                        role = "assistant",
                        content = "Tool call: ${msg.toolCall.toolName}\nArguments: ${msg.toolCall.arguments}"
                    ))
                }
                continue
            }

            // Tool result messages: treat as user (tool results)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        result.add(ChatTemplateMessage(
                            role = "user",
                            content = "Tool result: ${seg.toolResult ?: ""}"
                        ))
                    }
                } else if (msg.toolCall != null) {
                    result.add(ChatTemplateMessage(
                        role = "user",
                        content = "Tool result: ${msg.toolCall.result}"
                    ))
                }
                continue
            }

            // Normal messages
            val role = when (msg.participant) {
                Participant.USER -> "user"
                Participant.MODEL -> "assistant"
                Participant.ERROR -> "user"
            }

            val images = msg.images.filter { it.isNotBlank() }
            val content = if (role == "user" && images.isNotEmpty() && imagePathsOut != null) {
                imagePathsOut.addAll(images)
                images.joinToString("\n") { "<__media__>" } + "\n" + msg.text
            } else {
                msg.text
            }

            result.add(ChatTemplateMessage(role = role, content = content))
        }

        return result
    }

    private fun buildPrompt(messages: List<ChatTemplateMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> {
        return settings.localChatModels.first().map { it.modelId }
    }

    // ── Public local-model control surface (used by Settings ▸ Local Models) ──

    /** Pre-load (warm) a local chat model. Updates [loadState] to Loading → Loaded/Error. */
    suspend fun loadModel(model: LocalChatModelConfig): Boolean {
        return engineLock.withLock {
            when (_loadState.value) {
                is LocalModelLoadState.Loading,
                is LocalModelLoadState.Unloading -> {
                    // Ignore re-entrant requests while a transition is in flight.
                    false
                }
                else -> {
                    _loadState.value = LocalModelLoadState.Loading(model.modelId)
                    currentEngine?.close()
                    currentEngine = null
                    val engine = LlamaChatEngine(model.localFilePath, model.nCtx)
                    if (engine.load()) {
                        if (model.mmprojPath.isNotBlank()) engine.loadMmproj(model.mmprojPath)
                        currentEngine = engine
                        _loadState.value = LocalModelLoadState.Loaded(model.modelId)
                        true
                    } else {
                        _loadState.value = LocalModelLoadState.Error(
                            "Failed to load model: ${model.alias} (${model.localFilePath})"
                        )
                        false
                    }
                }
            }
        }
    }

    /** Unload the resident engine and return to Idle. Safe to call while Loading (cancels it). */
    suspend fun unloadModel() {
        engineLock.withLock {
            val activePath = currentEngine?.modelPath ?: ""
            _loadState.value = LocalModelLoadState.Unloading(activePath)
            currentEngine?.close()
            currentEngine = null
            _loadState.value = LocalModelLoadState.Idle
        }
    }

    /** Cancel an in-flight load (used by the "Unload" button shown while Loading). */
    suspend fun cancelLoad() {
        unloadModel()
    }

    fun close() {
        currentEngine?.close()
        currentEngine = null
        _loadState.value = LocalModelLoadState.Idle
    }

    suspend fun releaseEngine() {
        engineLock.withLock {
            currentEngine?.close()
            currentEngine = null
            _loadState.value = LocalModelLoadState.Idle
        }
    }

    fun releaseEngineBlocking() {
        kotlinx.coroutines.runBlocking { releaseEngine() }
    }
}
