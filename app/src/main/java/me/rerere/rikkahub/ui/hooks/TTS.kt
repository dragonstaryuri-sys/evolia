package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.model.PlaybackState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.datastore.isMuteTime
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.model.TTSVoice
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.MiniMaxSimpleVoice
import me.rerere.tts.controller.TtsController
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "TTS"

// Refined regex to match emojis without being too aggressive on standard full-width punctuation
private val EMOJI_REGEX = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]|[\\u2600-\\u27BF]")

/**
 * Composable function to remember and manage custom TTS state.
 * Now backed by a Koin singleton, so the TTS controller persists across page navigation
 * and survives background/foreground transitions.
 */
@Composable
fun rememberCustomTtsState(): CustomTtsState {
    return koinInject()
}

/**
 * Interface defining the public API of our custom TTS state holder.
 */
interface CustomTtsState {
    /** Flow indicating if the TTS provider is available and ready. */
    val isAvailable: StateFlow<Boolean>

    /** Flow indicating if the TTS is currently speaking. */
    val isSpeaking: StateFlow<Boolean>

    /** Flow holding any error message. */
    val error: StateFlow<String?>

    /** Flow indicating current chunk being processed (index) */
    val currentChunk: StateFlow<Int>

    /** Flow indicating total chunks in queue */
    val totalChunks: StateFlow<Int>

    /** Unified playback state (status, position, duration, speed, etc.) */
    val playbackState: StateFlow<PlaybackState>

    /**
     * Speaks the given text using the selected TTS provider.
     * Long texts will be automatically chunked and queued.
     */
    fun speak(text: String, flushCalled: Boolean = true, overrideSetting: TTSProviderSetting? = null)

    /** Stops the current speech and clears the queue. */
    fun stop()

    /** Pauses the current playback. */
    fun pause()

    /** Resumes the paused playback. */
    fun resume()

    /** Skips to the next chunk in the queue. */
    fun skipNext()

    /** Fast forward current playback by [ms]. */
    fun fastForward(ms: Long = 5_000)

    /** Set playback [speed]. */
    fun setSpeed(speed: Float)

    /** Get available voices for a provider. */
    suspend fun getVoices(providerSetting: TTSProviderSetting): List<TTSVoice>

    /** MiMo TTS 专用：从官方 API 获取包含 "tts" 的模型列表 */
    suspend fun listMimoModels(providerSetting: TTSProviderSetting.Mimo): List<String>

    // ================== MiniMax 音色设计 & 复刻 专用接口 ==================

    /** MiniMax 专用：上传音频文件（purpose = voice_clone / prompt_audio）*/
    suspend fun miniMaxUploadFile(
        providerSetting: TTSProviderSetting.MiniMax,
        file: java.io.File,
        purpose: String
    ): Long

    /** MiniMax 专用：音色设计，返回 Pair(voice_id, trial_audio_hex) */
    suspend fun miniMaxVoiceDesign(
        providerSetting: TTSProviderSetting.MiniMax
    ): Pair<String, String>

    /** MiniMax 专用：音色复刻，返回试听音频 URL（如有） */
    suspend fun miniMaxVoiceClone(
        providerSetting: TTSProviderSetting.MiniMax
    ): String

    /** MiniMax 专用：校验自定义 voice_id 格式 */
    fun miniMaxValidateVoiceId(voiceId: String): Result<Unit>

    /** MiniMax 专用：拉取用户账号下已有的【音色设计】音色列表（voice_generation，需先激活才会显示） */
    suspend fun miniMaxListVoiceGeneration(providerSetting: TTSProviderSetting.MiniMax): List<MiniMaxSimpleVoice>

    /** MiniMax 专用：拉取用户账号下已有的【音色复刻】音色列表（voice_cloning，需先激活才会显示） */
    suspend fun miniMaxListVoiceCloning(providerSetting: TTSProviderSetting.MiniMax): List<MiniMaxSimpleVoice>

    /**
     * 开始为指定会话自动朗读。
     * 在单例自己的协程作用域中运行，不依赖 UI 生命周期。
     * 即使退出对话页面或应用切到后台，朗读会持续到 AI 生成结束。
     * 切换到新会话时会自动取消旧会话的自动朗读。
     */
    fun startAutoRead(conversationId: Uuid, chatService: ChatService)

    /** 停止自动朗读监听。 */
    fun stopAutoRead()

    /**
     * 进入通话时暂停自动朗读：立即停掉当前正在播的句子，并且 [startAutoRead] 的 collect 循环
     * 会暂停消费新的 assistant 消息（但不推进 lastProcessed* 指针，恢复后从断点继续，不漏读）。
     * 允许重复调用（幂等）。
     */
    fun pauseAutoReadForCall()

    /** 通话结束后恢复自动朗读消费。允许重复调用（幂等）。 */
    fun resumeAutoReadAfterCall()

    /** Cleanup resources. */
    fun cleanup()
}

/**
 * Koin singleton implementation of CustomTtsState.
 * Lives for the entire app process, decoupled from any Composable / ViewModel lifecycle.
 */
class CustomTtsStateImpl(
    private val context: Context,
    private val settingsStore: SettingsStore
) : CustomTtsState, KoinComponent {

    private val ttsManager by inject<TTSManager>()
    private val controller by lazy { TtsController(context, ttsManager) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // --- 自动朗读状态 ---
    private var autoReadJob: Job? = null
    private val autoReadMutex = Mutex()
    private var lastProcessedMessageId: Uuid? = null
    private var lastProcessedIndex = 0
    // 记录最后一次检测到生成任务活跃的时间戳。
    // 用于区分"打开已有会话时的历史 assistant 消息"（应跳过）和
    // "新生成但 collect 时 job 已恰好变 null 的 assistant 消息"（应朗读）。
    private var lastJobActiveTimeMs: Long = 0L
    // 通话期间挂起自动朗读消费（true 时 startAutoRead 不推进指针、不调用 speak）
    private val autoReadPaused = AtomicBoolean(false)

    override val isAvailable: StateFlow<Boolean> get() = controller.isAvailable
    override val isSpeaking: StateFlow<Boolean> get() = controller.isSpeaking
    override val error: StateFlow<String?> get() = controller.error
    override val currentChunk: StateFlow<Int> get() = controller.currentChunk
    override val totalChunks: StateFlow<Int> get() = controller.totalChunks
    override val playbackState: StateFlow<PlaybackState> get() = controller.playbackState

    init {
        // 监听 settings 变化，自动更新 TTS provider
        scope.launch {
            settingsStore.settingsFlow.collect { settings ->
                controller.setProvider(settings.getSelectedTTSProvider())
            }
        }
    }

    override fun speak(text: String, flushCalled: Boolean, overrideSetting: TTSProviderSetting?) {
        Log.d(TAG, "[Speak] Raw input: \"$text\"")

        // Step 1: Apply TTS filters (Robust matching for all bracket types)
        val filtered = applyTtsTextFilters(text)

        // Step 2: Strip Markdown syntax
        val processed = filtered.stripMarkdown()

        if (processed.isBlank()) {
            Log.d(TAG, "[Speak] Text fully filtered, nothing to speak")
            return
        }

        if (overrideSetting != null) {
            Log.d(TAG, "[Speak] Speaking with override: \"$processed\"")
            controller.speakWithProvider(processed, overrideSetting, flushCalled)
        } else {
            Log.d(TAG, "[Speak] Speaking: \"$processed\"")
            controller.speak(processed, flushCalled)
        }
    }

    /**
     * Apply TTS text filter rules to the text.
     */
    private fun applyTtsTextFilters(text: String): String {
        Log.d(TAG, "[Filter] Start. Input: \"$text\"")
        val settings = settingsStore.settingsFlow.value
        val rules = settings.displaySetting.ttsTextFilterRules.filter { it.enabled }
        val filterEmojis = settings.displaySetting.filterEmojis

        var result = text

        // Helper to get flexible regex pattern for single char or escaped string
        fun toFlex(s: String): String {
            return when (val trimmed = s.trim()) {
                "(", "（" -> "[\\(\\（]"
                ")", "）" -> "[\\)\\）]"
                "[", "【" -> "[\\[\\【]"
                "]", "】" -> "[\\]\\】]"
                "{", "｛" -> "[\\{\\｛]"
                "}", "｝" -> "[\\}\\｝]"
                "<", "《" -> "[\\<\\《]"
                ">", "》" -> "[\\>\\》]"
                else -> Regex.escape(trimmed)
            }
        }

        // Helper to resolve start/end patterns intelligently
        fun resolvePatterns(rule: me.rerere.rikkahub.data.datastore.TtsTextFilterRule): Pair<String, String> {
            val p = rule.pattern.trim()
            val ep = rule.endPattern?.trim()?.takeIf { it.isNotBlank() }

            var startPart = p
            var endPart = ep ?: p

            if (ep == null) {
                // If single bracket char, find its pair
                if (p.length == 1) {
                    endPart = when (p) {
                        "(" -> ")"; "（" -> "）"; "[" -> "]"; "【" -> "】"
                        "{" -> "}"; "｛" -> "｝"; "<" -> ">"; "《" -> "》"
                        else -> p
                    }
                }
                // If pair string like "()" or "（ ）" or "( )"
                else if (p.contains(" ") || p.length == 2) {
                    val parts = if (p.contains(" ")) p.split(Regex("\\s+")) else listOf(p[0].toString(), p[1].toString())
                    if (parts.size == 2) {
                        startPart = parts[0]
                        endPart = parts[1]
                    }
                }
            }

            return toFlex(startPart) to toFlex(endPart)
        }

        // 1. Apply ONLY_READ rules first (while brackets are still present)
        val onlyReadRules = rules.filter { it.mode == me.rerere.rikkahub.data.datastore.TtsFilterMode.ONLY_READ }
        if (onlyReadRules.isNotEmpty()) {
            val extracted = StringBuilder()
            for (rule in onlyReadRules) {
                val (s, e) = resolvePatterns(rule)
                val regex = Regex("$s(.*?)$e", RegexOption.DOT_MATCHES_ALL)
                val matches = regex.findAll(result).toList()
                Log.d(TAG, "[Filter] ONLY_READ Rule '${rule.pattern}': matched ${matches.size} times")
                matches.forEach { match ->
                    if (extracted.isNotEmpty()) extracted.append(" ")
                    extracted.append(match.groupValues[1])
                }
            }
            if (extracted.isNotEmpty()) {
                result = extracted.toString()
                Log.d(TAG, "[Filter] Result after ONLY_READ: \"$result\"")
            } else if (onlyReadRules.any { it.enabled }) {
                result = ""
                Log.d(TAG, "[Filter] ONLY_READ matched nothing, clearing text")
            }
        }

        // 2. Apply SKIP rules (while brackets are still present)
        val skipRules = rules.filter { it.mode == me.rerere.rikkahub.data.datastore.TtsFilterMode.SKIP }
        for (rule in skipRules) {
            val (s, e) = resolvePatterns(rule)
            val regex = Regex("$s.*?$e", RegexOption.DOT_MATCHES_ALL)
            var lastResult: String
            var count = 0
            do {
                lastResult = result
                result = result.replace(regex, "")
                if (result != lastResult) count++
            } while (result != lastResult)
            if (count > 0) {
                Log.d(TAG, "[Filter] SKIP Rule '${rule.pattern}': applied $count times. Current result: \"$result\"")
            }
        }

        // 3. Filter emojis LAST. This ensures brackets are intact for rules matching above.
        if (filterEmojis) {
            val beforeEmoji = result
            result = result.replace(EMOJI_REGEX, "")
            if (beforeEmoji != result) {
                Log.d(TAG, "[Filter] After Emoji removal: \"$result\"")
            }
        }

        Log.d(TAG, "[Filter] Final output: \"${result.trim()}\"")
        return result.trim()
    }

    override fun stop() {
        controller.stop()
    }

    override fun pause() {
        controller.pause()
    }

    override fun resume() {
        controller.resume()
    }

    override fun skipNext() {
        controller.skipNext()
    }

    override fun fastForward(ms: Long) {
        controller.fastForward(ms)
    }

    override fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
    }

    override suspend fun getVoices(providerSetting: TTSProviderSetting): List<TTSVoice> {
        return ttsManager.getVoices(providerSetting)
    }

    override suspend fun listMimoModels(providerSetting: TTSProviderSetting.Mimo): List<String> {
        return ttsManager.listMimoModels(providerSetting)
    }

    // ================== MiniMax 音色设计 & 复刻 接口实现 ==================

    override suspend fun miniMaxUploadFile(
        providerSetting: TTSProviderSetting.MiniMax,
        file: java.io.File,
        purpose: String
    ): Long {
        return ttsManager.miniMaxUploadFile(providerSetting, file, purpose)
    }

    override suspend fun miniMaxVoiceDesign(
        providerSetting: TTSProviderSetting.MiniMax
    ): Pair<String, String> {
        return ttsManager.miniMaxVoiceDesign(providerSetting)
    }

    override suspend fun miniMaxVoiceClone(
        providerSetting: TTSProviderSetting.MiniMax
    ): String {
        return ttsManager.miniMaxVoiceClone(providerSetting)
    }

    override fun miniMaxValidateVoiceId(voiceId: String): Result<Unit> {
        return ttsManager.miniMaxValidateVoiceId(voiceId)
    }

    override suspend fun miniMaxListVoiceGeneration(
        providerSetting: TTSProviderSetting.MiniMax
    ): List<MiniMaxSimpleVoice> {
        return ttsManager.miniMaxListVoiceGeneration(providerSetting)
    }

    override suspend fun miniMaxListVoiceCloning(
        providerSetting: TTSProviderSetting.MiniMax
    ): List<MiniMaxSimpleVoice> {
        return ttsManager.miniMaxListVoiceCloning(providerSetting)
    }

    override fun startAutoRead(conversationId: Uuid, chatService: ChatService) {
        // 取消之前的自动朗读
        autoReadJob?.cancel()
        lastProcessedMessageId = null
        lastProcessedIndex = 0
        lastJobActiveTimeMs = 0L

        val convFlow = chatService.getConversationFlow(conversationId)
        val jobFlow = chatService.getGenerationJobStateFlow(conversationId)
        val autoPlayFlow = settingsStore.settingsFlow.map { it.autoPlayTts }

        autoReadJob = scope.launch {
            combine(convFlow, jobFlow, autoPlayFlow) { conv, job, autoPlay ->
                Triple(conv, job, autoPlay)
            }.collect { (conv, job, autoPlay) ->
                // 每次 AI 准备说下一句话之前，都先看一眼 settings 里的时间
                if (settingsStore.settingsFlow.value.isMuteTime()) {
                    Log.i(TAG, "[AutoRead] 当前处于静音时段，已跳过播放")
                    return@collect // 发现是静音时间，直接 return，不执行下面的 speak
                }
                // 通话中：暂停自动朗读消费，但不推进 lastProcessed* 指针
                // 挂断恢复后，从通话开始前的断点继续朗读，保证不漏读也不重复读
                if (autoReadPaused.get()) {
                    return@collect
                }

                // 记录生成任务活跃时间，用于后续判断 assistant 消息是否为新生成的
                if (job != null) {
                    lastJobActiveTimeMs = System.currentTimeMillis()
                }

                if (!autoPlay) {
                    controller.stop()
                    val lastMsg = conv.currentMessages.lastOrNull()
                    if (lastMsg?.role == MessageRole.ASSISTANT) {
                        val rawContent = lastMsg.parts.filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text }
                        lastProcessedMessageId = lastMsg.id
                        lastProcessedIndex = rawContent.length
                    } else {
                        lastProcessedMessageId = null
                        lastProcessedIndex = 0
                    }
                    return@collect
                }

                val lastMsg = conv.currentMessages.lastOrNull()
                if (lastMsg?.role == MessageRole.ASSISTANT) {
                    val rawContent = lastMsg.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }

                    if (lastProcessedMessageId != lastMsg.id) {
                        // 10 秒内有生成活动则认为这条 assistant 消息是新生成的，
                        // 即使 collect 时 job 已恰好变 null（Flow 异步时序竞争）也不跳过。
                        val recentlyGenerating = lastJobActiveTimeMs > 0L &&
                            System.currentTimeMillis() - lastJobActiveTimeMs < 10_000
                        if (lastProcessedMessageId == null && job == null && !recentlyGenerating) {
                            lastProcessedMessageId = lastMsg.id
                            lastProcessedIndex = rawContent.length
                        } else {
                            lastProcessedMessageId = lastMsg.id
                            lastProcessedIndex = 0
                        }
                    }
                    val terminators = charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';')
                    var i = lastProcessedIndex
                    while (i < rawContent.length) {
                        if (rawContent[i] in terminators) {
                            val sentence = rawContent.substring(lastProcessedIndex, i + 1).trim()
                            if (sentence.isNotEmpty()) {
                                scope.launch {
                                    autoReadMutex.withLock {
                                        speak(sentence, flushCalled = false)
                                    }
                                }
                            }
                            lastProcessedIndex = i + 1
                        }
                        i++
                    }

                    if (job == null && lastProcessedIndex < rawContent.length) {
                        val remaining = rawContent.substring(lastProcessedIndex).trim()
                        if (remaining.isNotEmpty()) {
                            scope.launch {
                                autoReadMutex.withLock {
                                    speak(remaining, flushCalled = false)
                                }
                            }
                        }
                        lastProcessedIndex = rawContent.length
                    }
                } else {
                    lastProcessedMessageId = null
                    lastProcessedIndex = 0
                }
            }
        }
    }

    override fun stopAutoRead() {
        autoReadJob?.cancel()
        autoReadJob = null
        lastProcessedMessageId = null
        lastProcessedIndex = 0
        lastJobActiveTimeMs = 0L
    }

    override fun pauseAutoReadForCall() {
        val wasPaused = autoReadPaused.getAndSet(true)
        if (!wasPaused) {
            // 立刻停掉自动朗读正在播的这句话，防止和通话 TTS 重叠
            scope.launch { autoReadMutex.withLock { controller.stop() } }
            Log.d(TAG, "[AutoRead] PAUSED by voice call")
        }
    }

    override fun resumeAutoReadAfterCall() {
        if (autoReadPaused.getAndSet(false)) {
            Log.d(TAG, "[AutoRead] RESUMED after voice call")
        }
    }

    override fun cleanup() {
        autoReadJob?.cancel()
        controller.dispose()
    }
}
