package me.rerere.rikkahub.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.sumUsage
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageSource
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.truncate
import me.rerere.ai.ui.limitContext
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.service.notification.NotificationSoundManager
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.ChatMessageEntity
import me.rerere.rikkahub.core.data.db.entity.ChatUnreadMessageEntity
import kotlinx.coroutines.job
import me.rerere.rikkahub.core.data.db.entity.ChatSegmentEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantSearchMode
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.ConversationMessageDeletion
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.core.data.model.toMessageNode
import me.rerere.rikkahub.core.data.model.normalizeMessageNodes
import me.rerere.rikkahub.core.data.model.removeInvalidMessages
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.SearchRetryGuard
import me.rerere.rikkahub.data.ai.isTransientNetworkError
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_FULL_SUMMARY_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_TEMP_SUMMARY_PROMPT_TEMPLATE
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.TemporarySessionToolRules
import me.rerere.rikkahub.data.ai.tools.buildBackgroundTaskTools
import me.rerere.rikkahub.data.ai.tools.buildExtensionAgentTools
import me.rerere.rikkahub.data.ai.tools.buildMcpAgentTools
import me.rerere.rikkahub.data.ai.tools.buildRadioAgentTools
import me.rerere.rikkahub.data.radio.RadioService
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.uuid.Uuid
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.core.data.utils.KeywordExtractor
import me.rerere.rikkahub.core.data.utils.VectorUtils
import me.rerere.rikkahub.data.ai.TtsVoiceResolver
import me.rerere.rikkahub.data.ai.transformers.AudioToTextTransformer
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import android.net.Uri
import android.media.MediaPlayer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.LocalDateTime

private const val TAG = "ChatService"

/**
 * 后台任务回调投递结果（BackgroundTaskManager 据此更新 callback_state）：
 * - Delivered：指令已注入/锚定并开始独立生成轮次（COMPLETED 在轮次结束时写入）
 * - Busy：会话出现竞态忙碌，回 PENDING 由生成结束钩子重投
 * - AgentMissing / ConversationMissing：目标不可达 → ABANDONED + 兜底通知（任务结果保留）
 */
enum class CallbackDeliveryResult {
    Delivered, Busy, AgentMissing, ConversationMissing
}

/**
 * 微信模式下每个分句转为语音消息的触发概率（10%）。
 * 每个分句独立判定：命中概率 或 分句带 [WECHAT_VOICE_MESSAGE_MARKER] 标记时，
 * 该分句会被 TTS 合成为语音条发送，其余分句仍为文字。
 */
internal const val WECHAT_VOICE_MESSAGE_PROBABILITY = 0.1f

/**
 * 微信模式下 agent 主动触发语音消息的标记。写在某个分句开头时，
 * 系统会移除标记并将该分句转为语音条发送。也是存储时拼接在转文字内容前的前缀。
 * 支持半角 () 和全角 （） 括号，AI 可能使用任一形式。
 */
internal const val WECHAT_VOICE_MESSAGE_MARKER = "(语音消息)"
internal const val WECHAT_VOICE_MESSAGE_MARKER_FULLWIDTH = "（语音消息）"

/**
 * 标记检测：兼容半角 () 和全角 （） 括号的 (语音消息) 标记。
 * 返回去除标记后的文本，若不含标记则返回 null。
 */
private fun stripVoiceMessageMarker(sentence: String): String? {
    val s = sentence.trim()
    if (s.startsWith(WECHAT_VOICE_MESSAGE_MARKER)) {
        return s.removePrefix(WECHAT_VOICE_MESSAGE_MARKER).trim().ifBlank { null }
    }
    if (s.startsWith(WECHAT_VOICE_MESSAGE_MARKER_FULLWIDTH)) {
        return s.removePrefix(WECHAT_VOICE_MESSAGE_MARKER_FULLWIDTH).trim().ifBlank { null }
    }
    return null
}

/**
 * 微信模式分句中的 (语音消息) 标记处理结果。
 *
 * @property text 去除标记后的纯文本（已去除前后空白）
 * @property forcedVoice true 表示该段紧跟在 (语音消息) 标记之后，必须转成语音；
 *   false 表示该段在标记之前或句中无标记，由调用方按概率判定。
 */
internal data class WechatVoiceSegment(
    val text: String,
    val forcedVoice: Boolean
)

/**
 * 按 (语音消息) 标记拆分单个分句，返回若干个 [WechatVoiceSegment]。
 *
 * 支持半角 () 和全角 （） 括号，且标记可出现在分句的任意位置：
 * - 分句以标记开头（如 "(语音消息)我好想你呀"）：整段 forcedVoice=true。
 * - 标记在分句中间（如 "你好（语音消息）世界"）：拆为 "你好"(forcedVoice=false) + "世界"(forcedVoice=true)。
 * - 分句中无标记：返回单段 forcedVoice=false。
 *
 * 标记会被完全移除，不会出现在返回的 text 中。
 */
internal fun splitByVoiceMarkers(sentence: String): List<WechatVoiceSegment> {
    val s = sentence.trim()
    if (s.isBlank()) return emptyList()

    val halfMarker = WECHAT_VOICE_MESSAGE_MARKER
    val fullMarker = WECHAT_VOICE_MESSAGE_MARKER_FULLWIDTH

    // 收集所有标记的 (start, endExclusive) 位置
    val markerPositions = mutableListOf<Pair<Int, Int>>()
    var idx = 0
    while (idx < s.length) {
        val halfIdx = s.indexOf(halfMarker, idx)
        val fullIdx = s.indexOf(fullMarker, idx)
        val nextIdx = when {
            halfIdx < 0 -> fullIdx
            fullIdx < 0 -> halfIdx
            else -> minOf(halfIdx, fullIdx)
        }
        if (nextIdx < 0) break
        val markerLen = if (nextIdx == halfIdx) halfMarker.length else fullMarker.length
        markerPositions.add(nextIdx to nextIdx + markerLen)
        idx = nextIdx + markerLen
    }

    if (markerPositions.isEmpty()) {
        return listOf(WechatVoiceSegment(s, forcedVoice = false))
    }

    val segments = mutableListOf<WechatVoiceSegment>()
    var cursor = 0
    var afterMarker = false // 当前 cursor 是否处于某个标记之后
    for ((start, end) in markerPositions) {
        if (start > cursor) {
            val piece = s.substring(cursor, start).trim()
            if (piece.isNotBlank()) {
                segments.add(WechatVoiceSegment(piece, forcedVoice = afterMarker))
            }
        }
        afterMarker = true
        cursor = end
    }
    val tail = s.substring(cursor).trim()
    if (tail.isNotBlank()) {
        segments.add(WechatVoiceSegment(tail, forcedVoice = true))
    }
    return segments
}

/**
 * UI 显示用：移除 (语音消息) / （语音消息） 前缀（半角/全角兼容）。
 * 不含标记时原样返回。
 */
internal fun removeVoiceMessageMarkerForDisplay(text: String): String {
    val s = text.trimStart()
    return when {
        s.startsWith(WECHAT_VOICE_MESSAGE_MARKER) ->
            s.removePrefix(WECHAT_VOICE_MESSAGE_MARKER).trimStart()
        s.startsWith(WECHAT_VOICE_MESSAGE_MARKER_FULLWIDTH) ->
            s.removePrefix(WECHAT_VOICE_MESSAGE_MARKER_FULLWIDTH).trimStart()
        else -> text
    }
}

/**
 * L1 segment 自动总结的连续失败熔断阈值。达到后停止自动触发 summarizeAndRefresh，
 * 等待用户手动重试（重置计数）或根本性问题解决后再次满足触发条件。
 */
private const val SEGMENT_FAILURE_THRESHOLD = 3

// 手动整合（L2 episode）分 chunk 体积上限：单批字符 / 条数双上限。
// previous_summary 随批滚动增长，限批防止单次 LLM 请求超上下文；单条超限消息不截断、独立成批。
private const val EPISODE_ARCHIVE_MAX_CHUNK_CHARS = 12_000
private const val EPISODE_ARCHIVE_MAX_CHUNK_MESSAGES = 60

// 手动整合保留在 AI 上下文里的最近消息数（与原 Worker manualConsolidate 语义一致）
private const val MANUAL_ARCHIVE_KEEP_RECENT = 10

// ------------------------------------------------------------------
//  安全解码 UIMessage：损坏的消息只记日志 + 抛 null，绝不闪退
// ------------------------------------------------------------------
internal fun decodeUIMessageOrNull(json: String, tagExtra: Any? = null): UIMessage? =
    runCatching { JsonInstant.decodeFromString<UIMessage>(json) }.getOrNull()
        ?: run {
            Log.e(TAG, "decodeUIMessageOrNull: 损坏消息, 上下文=$tagExtra; 预览: ${json.take(150)}…")
            null
        }

/**
 * 【问题4 修复】解码 ChatMessageEntity 为可拼接的 summary 行。
 * 当 contentJson 损坏时不再简单 return@forEach "跳过且不可见"，
 * 而是输出 "[损坏消息占位] ..." 的兜底行，保证：
 *   1. 该消息仍然被 AI 总结"看见"（至少以占位形式出现在上下文里）；
 *   2. 它不再是"时间戳已推进、内容永久黑洞"的幽灵记录。
 */
internal fun summarizeContentLine(entity: ChatMessageEntity, tagExtra: Any? = null): String? {
    val uiMsg = decodeUIMessageOrNull(entity.contentJson, tagExtra)
    if (uiMsg != null) {
        // ✨ 仅 user / assistant 角色的内容参与总结文本
        if (uiMsg.role != MessageRole.USER && uiMsg.role != MessageRole.ASSISTANT) return null
        return "${uiMsg.role}: ${uiMsg.toContentText()}"
    }
    // 解码失败：输出占位行，防止时间戳推进后"永久不可见"
    val preview = entity.contentJson.take(80).replace("\n", " ")
    return "[损坏消息占位 id=${entity.id.take(8)}…]: <无法解码 JSON> 预览=$preview"
}


internal fun selectMessagesForGeneration(
    messageNodes: List<MessageNode>,
    contextEndNodeId: Uuid?,
    lastArchivedMessageTime: Long
): List<UIMessage> {
    // 时间戳游标方案：找第一个 timelineCreatedAt 严格大于 lastArchivedMessageTime 的节点作为起点。
    // 与原 truncateIndex（数组索引）方案相比，不受 loadMoreHistory 前插节点影响。
    val rangeStart = if (lastArchivedMessageTime <= 0L) {
        0
    } else {
        val firstUnarchivedIndex = messageNodes.indexOfFirst { node ->
            node.timelineCreatedAt > lastArchivedMessageTime
        }
        if (firstUnarchivedIndex < 0) return emptyList()
        firstUnarchivedIndex
    }
    val rangeEndExclusive = if (contextEndNodeId == null) {
        messageNodes.size
    } else {
        val contextEndIndex = messageNodes.indexOfFirst { node -> node.id == contextEndNodeId }
        if (contextEndIndex < 0) return emptyList()
        contextEndIndex + 1
    }
    if (rangeStart >= rangeEndExclusive) return emptyList()

    return messageNodes
        .subList(rangeStart, rangeEndExclusive)
        .mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex) ?: node.messages.lastOrNull()
        }
}


private val _isAiTypingMap = MutableStateFlow<Map<Uuid, Boolean>>(emptyMap())
private val inputTransformers by lazy {
    listOf(
        PlaceholderTransformer,
        me.rerere.rikkahub.data.ai.transformers.UnsupportedFileTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        me.rerere.rikkahub.data.ai.transformers.AudioToTextTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val embeddingService: EmbeddingService,
    private val ttsManager: TTSManager,
    private val radioService: RadioService,
    private val mediaPlaybackController: me.rerere.rikkahub.service.media.MediaPlaybackController,
    private val backgroundTaskManager: me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager,
    private val temporarySessions: TemporaryChatSessionRegistry,
    private val extensionRegistry: me.rerere.rikkahub.data.extensions.ExtensionRegistry,
    private val extensionToolRuntime: me.rerere.rikkahub.data.extensions.ExtensionToolRuntime,
    ) {
    data class ContextRefreshResult(
        val success: Boolean,
        val summary: String = "",
        val messagesSummarized: Int = 0,
        val tokensSaved: Int = 0,
        val errorMessage: String? = null
    )

    private val conversations = ConcurrentHashMap<Uuid, MutableStateFlow<Conversation>>()
    private val conversationReferences = ConcurrentHashMap<Uuid, Int>()
    // 通话中的会话集合：标记后跳过主路径的 L1 自动摘要, 改由 VoiceCallManager 的 25 分钟定时器驱动
    private val callModeConversations = ConcurrentHashMap.newKeySet<Uuid>()
    // 已销毁的临时会话墓碑：销毁后到达的异步残留（ASR 回调、流式增量保存等）不得
    // 在该 UUID 上重建会话或落库（remove 后 isTemporary 已为 false，需要墓碑兜底）
    private val destroyedTemporaryIds = ConcurrentHashMap.newKeySet<Uuid>()
    // 通话中会话 → 通话 callId 注册表：由 VoiceCallManager 接通时注册、挂断收尾时注销。
    // 自动化任务（executeAgentTask）据此判断目标会话是否在通话中，
    // 命中时回复打标 MessageSource.CALL + callId，作为通话转写的一部分存储。
    private val activeCallInfo = ConcurrentHashMap<Uuid, String>()

    /**
     * 注册/注销会话的活跃通话信息（由 VoiceCallManager 在接通/挂断时调用）。
     * callId 为 null 或 active=false 表示注销。
     */
    fun setActiveCallInfo(conversationId: Uuid, callId: String?, active: Boolean) {
        if (active && callId != null) {
            activeCallInfo[conversationId] = callId
        } else {
            activeCallInfo.remove(conversationId)
        }
    }
    private val _generationJobs = MutableStateFlow<Map<Uuid, Job?>>(emptyMap())
    private val generationJobs: StateFlow<Map<Uuid, Job?>> = _generationJobs.asStateFlow()
    private val _errorFlow = MutableSharedFlow<Throwable>()
    val errorFlow: SharedFlow<Throwable> = _errorFlow.asSharedFlow()
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    // 用户当前正在前台查看的会话（ChatPage / TemporaryChatPage 组合时设置、离开组合时清除）。
    // 未读判定的唯一状态源：AI 回复完成时据此决定是否计入未读，禁止在页面/Service
    // 中另立互相独立的判断变量。临时会话 id 也会登记（用户在临时会话内时，其他正式
    // 会话的回复应计入未读），但临时会话自身永不产生未读记录。
    private val _activeViewingConversationId = MutableStateFlow<Uuid?>(null)
    val activeViewingConversationId: StateFlow<Uuid?> = _activeViewingConversationId.asStateFlow()

    /** 由 ChatPage / TemporaryChatPage 在进入组合时设置、离开组合时清除（传 null）。 */
    fun setActiveViewingConversation(id: Uuid?) {
        _activeViewingConversationId.value = id
    }

    /**
     * 会话可见性判定（未读计不计入的依据）：App 前台 && 用户正在查看该会话。
     * 与 ProcessLifecycleOwner 的 isForeground 观察共用一套机制（ON_START/ON_STOP）。
     */
    fun isConversationBeingViewed(conversationId: Uuid): Boolean =
        _activeViewingConversationId.value == conversationId && _isForeground.value

    /**
     * 用户进入/恢复查看正式会话时清零该会话未读记录（幂等）。
     * 临时会话无未读记录，直接跳过。
     */
    suspend fun markConversationAsRead(conversationId: Uuid) {
        if (temporarySessions.isTemporary(conversationId)) return
        runCatching { conversationRepo.markConversationAsRead(conversationId) }
            .onFailure { Log.e(TAG, "清除未读失败: conv=$conversationId", it) }
    }

    private val _syncingConversationIds = MutableStateFlow<Set<Uuid>>(emptySet())

    private val _generatingNodeIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val generatingNodeIds: StateFlow<Set<Uuid>> = _generatingNodeIds.asStateFlow()
    suspend fun loadMoreHistory(conversationId: Uuid) {
        val currentConv = conversations[conversationId]?.value ?: return

        // 1. 获取已解析内容的 ID 集合
        val loadedContentIds = currentConv.messageNodes
            .filter { it.messages.isNotEmpty() }
            .map { it.id }.toSet()

        // 2. 获取内存中已存在的节点 ID 集合 (含占位符)
        val existingNodeIds = currentConv.messageNodes.map { it.id }.toSet()

        // 3. 触发数据库加载
        val moreNodes = conversationRepo.loadMoreMessages(conversationId, loadedContentIds)

        if (moreNodes.isNotEmpty()) {
            updateConversation(conversationId) { old ->
                val newMap = moreNodes.associateBy { it.id }

                // A. 更新内存中已有的占位节点内容（将空节点替换为有内容的节点）
                val updatedNodes = old.messageNodes.map { node ->
                    newMap[node.id] ?: node
                }

                // B. 找出那些完全不在内存里的历史节点（更早之前的消息）
                val nodesToPrepend = moreNodes.filter { it.id !in existingNodeIds }

                // C. 合并：[新的历史节点] + [更新后的现有节点]
                // 注意：这里需要保持 orderIndex 升序，所以直接 Prepend
                val finalNodes = (nodesToPrepend + updatedNodes).distinctBy { it.id }
                old.copy(messageNodes = finalNodes)
            }
        }
    }

    val syncingConversationIds: StateFlow<Set<Uuid>> = _syncingConversationIds.asStateFlow()

    private var lastConversationId: Uuid? = null

    // 微信模式：消息合并发送的计时器
    private val wechatDebounceJobs = ConcurrentHashMap<Uuid, Job>()

    private val promptPlaceholderRegex = Regex("\\{\\{(\\w+)\\}\\}")

    // 并发防抖锁集合
    private val archivingConversations = ConcurrentHashMap.newKeySet<Uuid>()
    private val summarizingConversations = ConcurrentHashMap.newKeySet<Uuid>()

    // ---- L2 episode 归档的 UI 状态源（遮罩 / 进度 / 结果） ----
    // episodeArchivingIds：任意会话的 episode 归档进行中。切换钩子路径（archiveConversation）
    // 与手动整合路径（manualArchiveEpisode）统一在此登记，ChatPage 遮罩以此为准——
    // ChatService 是 Koin single，退出页面重进后遮罩自动恢复，不以 VM 内存态为准。
    private val _episodeArchivingIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val episodeArchivingIds: StateFlow<Set<Uuid>> = _episodeArchivingIds.asStateFlow()
    private val _episodeArchiveProgress = MutableStateFlow<Map<Uuid, EpisodeArchiveProgress>>(emptyMap())
    val episodeArchiveProgress: StateFlow<Map<Uuid, EpisodeArchiveProgress>> = _episodeArchiveProgress.asStateFlow()
    private val _episodeArchiveResults = MutableSharedFlow<EpisodeArchiveResult>(extraBufferCapacity = 8)
    val episodeArchiveResults: SharedFlow<EpisodeArchiveResult> = _episodeArchiveResults.asSharedFlow()
    // 手动归档 Job（取消入口）；切换钩子路径随其宿主协程生命周期，不在此登记
    private val episodeArchiveJobs = ConcurrentHashMap<Uuid, Job>()

    // 会话级别的互斥锁，确保同一时间只有一个生成任务在处理网络/状态更新
    private val conversationMutexes = ConcurrentHashMap<Uuid, Mutex>()
    // 数据库写入单独串行化，避免发送、刷新、分页切换和后台任务交错覆盖。
    private val persistenceMutexes = ConcurrentHashMap<Uuid, Mutex>()

    /** 生成期纯流式文本增量落库的节流间隔（新节点出现即立即落库，不受此限制）。 */
    private val INCREMENTAL_SAVE_INTERVAL_MS = 2_000L
    // getConversationFlow may create a lightweight fallback. Cache presence alone does
    // not mean that the real database conversation has been loaded.
    private val initializedConversationIds = ConcurrentHashMap.newKeySet<Uuid>()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        _generationJobs.value.values.forEach { it?.cancel() }
    }

    private fun fillPrompt(template: String, placeholders: Map<String, String>): String {
        return promptPlaceholderRegex.replace(template) { match ->
            placeholders[match.groupValues[1]] ?: match.value
        }
    }

    private suspend fun <T> retryIO(
        times: Int = 2, initialDelay: Long = 2000, block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(times) {
            try {
                return block()
            } catch (e: Exception) {
                // 取消必须立刻穿透：不得落入下方网络重试分支（取消后 delay 也会立即抛出，
                // 但显式 rethrow 保证语义清晰且不受异常消息措辞影响）
                if (e is kotlinx.coroutines.CancellationException) throw e
                val isNetworkError = e is java.io.IOException || e.message?.contains(
                    "timeout",
                    ignoreCase = true
                ) == true || e.message?.contains("canceled", ignoreCase = true) == true
                if (isNetworkError) {
                    Log.w(TAG, "网络异常，正在重试 (第 ${it + 1} 次): ${e.message}")
                    delay(currentDelay)
                    currentDelay *= 2
                } else {
                    throw e
                }
            }
        }
        return block()
    }

    fun addConversationReference(conversationId: Uuid) {
        conversationReferences[conversationId] = conversationReferences.getOrDefault(conversationId, 0) + 1
    }

    fun removeConversationReference(conversationId: Uuid) {
        val currentCount = conversationReferences[conversationId] ?: 0
        if (currentCount <= 1) {
            conversationReferences.remove(conversationId)
            appScope.launch {
                delay(100)
                if (!hasReference(conversationId)) {
                    cleanupConversation(conversationId)
                }
            }
        } else {
            conversationReferences[conversationId] = currentCount - 1
        }
    }

    suspend fun executeAgentTask(task: me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity) {
        val data = JsonInstant.parseToJsonElement(task.taskData) as? JsonObject ?: return

        val instruction = data["instruction"]?.jsonPrimitive?.contentOrNull ?: ""
        val settings = settingsStore.settingsFlow.first()
        val originalAssistantId = Uuid.parse(task.assistantId)
        val originalAssistant = settings.getAssistantById(originalAssistantId) ?: return

        // 任务模型覆盖：若 task.model 非空且能解析为有效 Uuid，则用该模型执行此次定时任务；
        // 否则沿用 assistant 当前对话模型。仅影响这一条定时任务消息，不污染正常对话。
        val overrideAssistant = runCatching {
            task.model
                ?.takeIf { it.isNotBlank() }
                ?.let { modelIdStr ->
                    val modelId = Uuid.parse(modelIdStr)
                    settings.findModelById(modelId)?.let { originalAssistant.copy(chatModelId = modelId) }
                }
        }.getOrNull() ?: originalAssistant

        // 目标会话候选必须排除临时/销毁中/已销毁（墓碑）会话：临时会话开着时它引用在册且
        // updateAt 最新，不过滤的话定时任务回复会被注入临时会话、随会话销毁而蒸发（历史 bug）。
        // 排除后落点 = 用户正打开的正式会话（临时页下方，引用在册）→ DB 最新正式会话 → 新建。
        val candidateConversations = conversationReferences.keys
            .mapNotNull { id -> conversations[id]?.value }
            .filter { conversation ->
                conversation.assistantId == originalAssistantId &&
                    !isTemporaryOrDestroyed(conversation.id)
            }
        val activeConvId = candidateConversations
            .maxByOrNull { it.updateAt }
            ?.id

        val conversationId = if (activeConvId != null) {
            activeConvId
        } else {
            // 轻量查询最新会话 ID（禁止为找一条 ID 而 getAllConversations 全量水合——历史 OOM 根因之一）
            conversationRepo.getLatestConversationIdOfAssistant(originalAssistantId) ?: Uuid.random()
        }

        // 任务触发时用户正处于临时会话（任意助手）：回复落入普通会话在 UI 上不可见，
        // 且 App 前台时常规「生成完成」通知被 isForeground 门控拦截。完成后强制补发通知，
        // 用户点击通知经 RouteActivity 的「退出临时会话确认」流程进入普通会话查看。
        val replyHiddenInTempSession = temporarySessions.activeSessionIds().isNotEmpty()

        val monitorMsg = buildString {
            append("【系统自动化指令 - 任务触发】\n")
            when (task.taskType) {
                "EMAIL" -> {
                    val to = data["to"]?.jsonPrimitive?.contentOrNull
                    val subject = data["subject"]?.jsonPrimitive?.contentOrNull
                    append("类型: 邮件自动化\n")
                    if (!to.isNullOrBlank()) append("目标收件人: $to\n")
                    if (!subject.isNullOrBlank()) append("预设主题: $subject\n")
                }

                "NOTIFICATION" -> {
                    val title = data["title"]?.jsonPrimitive?.contentOrNull
                    append("类型: 定时提醒\n")
                    if (!title.isNullOrBlank()) append("提醒主题: $title\n")
                }

                "MONITOR_TRIGGER" -> {
                    val monitorName = data["monitor_name"]?.jsonPrimitive?.contentOrNull
                    append("类型: 实时监控触发\n")
                    if (!monitorName.isNullOrBlank()) append("监控器名称: $monitorName\n")
                }

                "APP_LOCK_TRIGGER" -> {
                    // 应用锁定触发：用户打开了被锁定的应用
                    val appName = data["app_name"]?.jsonPrimitive?.contentOrNull ?: "未知应用"
                    val unlockCondition = data["unlock_condition"]?.jsonPrimitive?.contentOrNull
                    // 解析 time_periods：数组（多时段）或单个对象（单时段）；
                    // 兼容 AI 直接传 start/end 顶层字段（理论上 EvoliaMonitorService 已经归一，
                    // 这里仍兜底处理一次，避免任何路径漏改导致消息文案错乱）
                    val timePeriodsElement = data["time_periods"]
                    val periodsList: List<JsonObject> = when {
                        timePeriodsElement is JsonArray -> timePeriodsElement.mapNotNull { it as? JsonObject }
                        timePeriodsElement is JsonObject -> listOf(timePeriodsElement)
                        else -> emptyList()
                    }
                    append("类型: 应用锁定触发\n")
                    append("应用名称: $appName\n")
                    // 拼接约定内容：优先使用解锁条件，没有则用时间窗口
                    val agreement = if (!unlockCondition.isNullOrBlank()) {
                        "你和user约定了要等条件「$unlockCondition」达成后才能解锁。"
                    } else if (periodsList.isNotEmpty()) {
                        // 多时段拼接：13:00 到 14:00、20:00 到 23:00
                        val periodsDesc = periodsList.joinToString("、") { p ->
                            val s = (p["start"] as? JsonPrimitive)?.contentOrNull ?: ""
                            val e = (p["end"] as? JsonPrimitive)?.contentOrNull ?: ""
                            "$s 到 $e"
                        }
                        "你和user约定了在 $periodsDesc 之间不能打开 $appName。"
                    } else {
                        "你和user约定了该应用被锁定，暂未记录具体解锁条件。"
                    }
                    append("约定内容: $agreement\n")
                    // 直接注入用户要求的系统提示作为指令内容
                    val systemHint = buildString {
                        append("[系统提示]：user刚才打开了你锁定的应用 $appName，")
                        append("被你设置的遮罩挡住了，")
                        append(agreement)
                    }
                    append("\n指令内容：$systemHint\n")
                    append("\n注意：这是一条由自动化引擎触发的【隐形指令】，用户不会在消息列表中看到它。")
                    append("你可以根据上述信息决定如何回应——例如在下次对话中关心一下user的状态、")
                    append("鼓励user遵守约定，或就当没发生（如果只是偶尔触发）。不要直接重复系统提示原文。")
                    return@buildString
                }
            }
            append("\n指令内容：$instruction\n\n注意：这是一条由自动化引擎触发的【隐形指令】，用户不会在消息列表中看到它。请根据上述指令要求直接采取行动或给出回应。")
        }

        appScope.launch {
            try {
                var retry = 0
                while (generationJobs.value[conversationId] != null && retry < 30) {
                    delay(100)
                    retry++
                }

                if (generationJobs.value[conversationId] != null) {
                    Log.w(TAG, "会话 $conversationId 忙碌，强行接管执行自动化任务。")
                    generationJobs.value[conversationId]?.cancel()
                    delay(200)
                }

                // 通话感知：目标会话正在通话中时，回复打标为通话消息（CALL 源 + callId），
                // 作为通话转写的一部分存储并由通话侧朗读；同时跳过 initializeConversation 的
                // 内存重载（防止 DB 快照覆盖通话中未落库的流式节点、使朗读锚点失效）。
                val activeCallIdForTask = activeCallInfo[conversationId]
                val isCallConversation = activeCallIdForTask != null
                if (isCallConversation) {
                    Log.i(TAG, "自动化任务命中通话中会话 $conversationId (callId=$activeCallIdForTask)，回复将以 CALL 源入库")
                }

                initializeConversation(
                    conversationId,
                    targetAssistantId = originalAssistantId,
                    skipMemoryReload = isCallConversation
                )
                updateConversation(conversationId, normalizeNodes = true) { old ->
                    val newNode = UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(monitorMsg)),
                        skipContext = true,
                        messageSource = MessageSource.AGENT_TASK // 自动化任务来源：完整带入不截断
                    ).toMessageNode(conversationId)

                    old.copy(
                        messageNodes = old.messageNodes + newNode,
                        updateAt = Instant.now()
                    )
                }
                mutateConversationAndSave(conversationId) { current -> current }

                val job = launch {
                    _isAiTypingMap.update { it + (conversationId to true) }
                    try {
                        handleMessageComplete(
                            conversationId = conversationId,
                            assistantOverride = overrideAssistant,
                            skipContextForResponse = isCallConversation,
                            includeSkipContextMessages = true,
                            responseMessageSource = if (isCallConversation) MessageSource.CALL else MessageSource.NORMAL,
                            callId = activeCallIdForTask
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "自动化任务生成失败", e)
                    } finally {
                        // 竞态防护：同 triggerAIResponse，避免旧 job 的 finally 清除新 job 的 typing 状态
                        val isStillActiveJob = _generationJobs.value[conversationId] == currentCoroutineContext().job
                        if (isStillActiveJob) {
                            _isAiTypingMap.update { it - conversationId }
                        }
                    }
                }

                setGenerationJob(conversationId, job)
                job.invokeOnCompletion {
                    _generationJobs.update { current ->
                        if (current[conversationId] == job) current - conversationId else current
                    }
                    // 临时会话期间触发的任务回复：常规通知被前台门控拦截时在此补发
                    //（仍尊重通知总开关 sendGenerationDoneNotification 内部的 notificationAiReplyEnabled）。
                    // 用户此时仍停留在临时会话才需要提醒；已退出临时会话则回复在普通会话页直接可见。
                    if (replyHiddenInTempSession && isForeground.value && hasActiveTemporarySession()) {
                        sendGenerationDoneNotification(conversationId)
                    }
                    appScope.launch { delay(500); checkAllConversationsReferences() }
                }

            } catch (e: Exception) {
                Log.e(TAG, "后台任务调度失败", e)
            }
        }
    }

    // ====================================================================
    // 后台任务回调唤起（通用后台任务机制，AGENTS.md 专用章节）
    // ====================================================================

    /** 会话是否有活跃生成轮（回调投递的忙判定；Manager 据此延后投递，不无条件打断用户当前对话）。 */
    fun isConversationBusy(conversationId: Uuid): Boolean =
        _generationJobs.value[conversationId] != null || (_isAiTypingMap.value[conversationId] == true)

    /**
     * INJECTED 状态恢复判定：回调消息之后是否已产生 ASSISTANT 响应、回调消息是否仍是末节点。
     * 基于会话内容而非时间租约——回调生成轮可能长于任何合理租约，无需心跳续期。
     */
    suspend fun resolveInjectedCallbackRecovery(
        task: me.rerere.rikkahub.core.data.db.entity.BackgroundTaskEntity
    ): me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.InjectedRecovery {
        val conversationId = task.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.InjectedRecovery.MARK_COMPLETED
        val callbackMessageId = me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.callbackMessageId(task.id)
        val mutex = conversationMutexes.computeIfAbsent(conversationId) { Mutex() }
        return mutex.withLock {
            val conversation = conversations[conversationId]?.value
                ?: conversationRepo.getConversationById(conversationId)
                ?: return@withLock me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.InjectedRecovery.MARK_COMPLETED
            val nodes = conversation.messageNodes
            val callbackNodeIndex = nodes.indexOfFirst { node -> node.messages.any { it.id == callbackMessageId } }
            if (callbackNodeIndex < 0) {
                // 回调消息不在快照中（异常路径）：保守标记完成，结果可通过 get_background_task 查询
                return@withLock me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.InjectedRecovery.MARK_COMPLETED
            }
            val hasAssistantResponse = nodes.drop(callbackNodeIndex + 1).any { node ->
                node.messages.any { it.role == MessageRole.ASSISTANT }
            }
            val isCallbackMessageLastNode = callbackNodeIndex == nodes.lastIndex
            me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.decideInjectedRecovery(
                hasAssistantResponseAfterCallback = hasAssistantResponse,
                isCallbackMessageLastNode = isCallbackMessageLastNode
            )
        }
    }

    /**
     * 后台任务完成回调 → 唤起 Agent（独立新轮次，不伪装原 Turn 仍在执行）。
     *
     * fresh 模式：注入隐藏指令消息（skipContext=true + MessageSource.AGENT_TASK：
     * 用户不可见〔ChatList 按 skipContext 过滤〕、上下文白名单完整带入不截断），
     * 消息 ID 由 task_id 确定性派生——幂等，同一任务事件永不重复注入。
     * resume 模式：进程崩溃后 INJECTED 恢复——不重复注入，以既有消息为锚直接续跑生成。
     */
    suspend fun executeBackgroundTaskCallback(
        task: me.rerere.rikkahub.core.data.db.entity.BackgroundTaskEntity,
        resume: Boolean = false
    ): CallbackDeliveryResult {
        // 1. 目标 Agent 解析（已删除 → ABANDONED，结果保留在任务记录）
        val agentId = task.agentId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return CallbackDeliveryResult.AgentMissing
        val assistant = settingsStore.settingsFlow.value.getAssistantById(agentId)
            ?: return CallbackDeliveryResult.AgentMissing

        // 2. 目标会话解析（任务记录的会话 → 助手最新会话兜底；不存在 → ABANDONED，不新建）
        val conversationId = task.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: conversationRepo.getLatestConversationIdOfAssistant(agentId)
            ?: return CallbackDeliveryResult.ConversationMissing

        // 3. 忙判定（竞态防护）：变忙则回 PENDING，由生成结束钩子重投（不强行打断）
        if (isConversationBusy(conversationId)) return CallbackDeliveryResult.Busy

        val callbackMessageId = me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.callbackMessageId(task.id)

        if (resume) {
            // resume：指令消息必须仍是会话末节点（双检；状态已变化则回 PENDING 重判）
            initializeConversation(conversationId, targetAssistantId = agentId)
            val conversation = conversations[conversationId]?.value
                ?: return CallbackDeliveryResult.ConversationMissing
            val lastNode = conversation.messageNodes.lastOrNull()
            val instructionMsg = lastNode?.messages?.firstOrNull { it.id == callbackMessageId }
            if (instructionMsg == null) return CallbackDeliveryResult.Busy
            val parsed = me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.parseCallbackInstruction(
                instructionMsg.toContentText()
            )
            if (parsed == null || parsed.taskId != task.id) return CallbackDeliveryResult.Busy
        } else {
            // fresh：构造指令并注入（幂等——已存在的回调消息不重复插入）
            val payload = me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskCodec.decodePayload(task.payloadJson)
            val instruction = me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.buildCallbackInstruction(
                taskId = task.id,
                taskType = task.taskType,
                refKey = task.refKey,
                status = task.status,
                completionPolicy = task.completionPolicy,
                resultJson = task.resultJson,
                errorMessage = task.errorMessage,
                followUpInstruction = payload?.followUpInstruction,
            )

            initializeConversation(conversationId, targetAssistantId = agentId)
            updateConversation(conversationId, normalizeNodes = true) { old ->
                val alreadyInjected = old.messageNodes.any { node -> node.messages.any { it.id == callbackMessageId } }
                if (alreadyInjected) {
                    old
                } else {
                    old.copy(
                        messageNodes = old.messageNodes + UIMessage(
                            id = callbackMessageId,
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text(instruction)),
                            skipContext = true,
                            messageSource = MessageSource.AGENT_TASK
                        ).toMessageNode(conversationId),
                        updateAt = Instant.now()
                    )
                }
            }
            mutateConversationAndSave(conversationId) { current -> current }
            backgroundTaskManager.markCallbackInjected(task.id)
        }

        // 4. 独立生成轮次（与 executeAgentTask 同模式注册 generationJob）
        val startedAt = System.currentTimeMillis()
        var generationError: Throwable? = null
        _isAiTypingMap.update { it + (conversationId to true) }
        val job = appScope.launch {
            try {
                handleMessageComplete(
                    conversationId = conversationId,
                    includeSkipContextMessages = true,
                    responseMessageSource = MessageSource.NORMAL
                )
            } catch (e: Exception) {
                Log.e(TAG, "后台任务回调生成失败: task=${task.id}", e)
                generationError = e
            } finally {
                // 竞态防护：同 triggerAIResponse，避免旧 job 的 finally 清除新 job 的 typing 状态
                val isStillActiveJob = _generationJobs.value[conversationId] == currentCoroutineContext().job
                if (isStillActiveJob) {
                    _isAiTypingMap.update { it - conversationId }
                }
            }
        }
        setGenerationJob(conversationId, job)
        job.invokeOnCompletion { error ->
            _generationJobs.update { current ->
                if (current[conversationId] == job) current - conversationId else current
            }
            if (error != null && generationError == null) generationError = error
            appScope.launch {
                runCatching {
                    backgroundTaskManager.markCallbackCompleted(
                        task.id,
                        error = generationError?.message
                    )
                }
                // 回调轮次结束：消化该会话积压的延后回调与 INJECTED 恢复
                backgroundTaskManager.dispatchCallbacks(conversationId)
                delay(500)
                checkAllConversationsReferences()
            }
        }
        Log.i(TAG, "background task callback ${if (resume) "resumed" else "delivered"}: task=${task.id} conv=$conversationId (${System.currentTimeMillis() - startedAt}ms)")
        return CallbackDeliveryResult.Delivered
    }

    private fun hasReference(conversationId: Uuid): Boolean =
        conversationReferences.containsKey(conversationId) || _generationJobs.value.containsKey(conversationId)

    fun checkAllConversationsReferences() {
        conversations.keys.forEach { id ->
            val hasReference = hasReference(id)
            val isTemporary = temporarySessions.isTemporary(id)
            // 临时会话绝不清理缓存：消息仅存在于内存 StateFlow，被逐出后
            // 下一次写入会经 getConversationFlow 的 computeIfAbsent 重建空会话，
            // 用户消息"消失"、AI 上下文清空（历史 bug：第一条消息回复后 500ms 被清，
            // 第二条消息起全部异常）。UI 持有的旧 StateFlow 仍在显示，问题不易察觉。
            if (!hasReference && !isTemporary) cleanupConversation(id)
        }
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        val settings = settingsStore.settingsFlow.value
        val currentAssistant = settings.getCurrentAssistant()

        if (!conversations.containsKey(conversationId) && conversations.size >= 5) {
            val toRemove = conversations.keys.firstOrNull { !hasReference(it) && !temporarySessions.isTemporary(it) }
            toRemove?.let { cleanupConversation(it) }
        }

        return conversations.computeIfAbsent(conversationId) {
            MutableStateFlow(
                Conversation.ofId(
                    id = conversationId,
                    assistantId = currentAssistant.id,
                )
            )
        }
    }

    /**
     * 只读快照：内存中该会话当前归属的 Assistant。
     * 统一语音选择（TtsVoiceResolver）按此解析 Agent 专属音色；
     * 会话不在内存（极少：语音调用路径必然活跃）时回退当前选中助手。
     */
    fun getConversationAssistant(conversationId: Uuid?): Assistant? {
        val settings = settingsStore.settingsFlow.value
        val assistantId = conversationId?.let { conversations[it]?.value?.assistantId }
        val assistant = assistantId?.let { settings.getAssistantById(it) }
        return assistant ?: settings.getCurrentAssistant()
    }

    /**
     * 只读窥视内存缓存中的会话（不创建占位、不触发淘汰）。
     * 仅供低频诊断日志使用，避免诊断本身改变缓存状态。
     */
    fun peekConversation(conversationId: Uuid): Conversation? =
        conversations[conversationId]?.value

    /**
     * 无副作用地从 DB 预热会话到内存缓存。
     * 仅当缓存未命中且 DB 中存在该会话时写入缓存，不触发归档、不改变 lastConversationId、不切换 currentAssistant。
     * 用于后台流程（如 DiaryVM）在调用 sendMessage 前确保缓存中是 DB 真实数据，
     * 防止 getConversationFlow fallback 用 currentAssistant.id 创建错误占位会话。
     * @return true 表示缓存已就绪（命中缓存或 DB 加载成功）；false 表示 DB 中不存在该会话，调用方应中止后续操作。
     */
    suspend fun ensureConversationLoaded(conversationId: Uuid): Boolean {
        if (
            conversationId in initializedConversationIds &&
            conversations.containsKey(conversationId)
        ) {
            return true
        }
        // 临时会话仅存在于内存（绝不写 DB）：注册表确认身份 + 内存流存在即为已加载，
        // 否则下方 DB 查询返回 false 会导致 mutateConversationAndSave 等路径静默丢弃内存更新。
        if (temporarySessions.isTemporary(conversationId)) {
            return conversations.containsKey(conversationId)
        }
        val fromDb = conversationRepo.getConversationById(conversationId) ?: return false
        val flow = conversations.computeIfAbsent(conversationId) { MutableStateFlow(fromDb) }
        flow.value = fromDb
        initializedConversationIds += conversationId
        return true
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> = generationJobs.map { it[conversationId] }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = generationJobs

    private fun setGenerationJob(conversationId: Uuid, job: Job?) {
        if (job == null) {
            removeGenerationJob(conversationId)
            return
        }
        _generationJobs.update { it + (conversationId to job) }
    }

    private fun removeGenerationJob(conversationId: Uuid) {
        _generationJobs.update { it - conversationId }
    }

    /** 判断指定会话是否正在生成 AI 回复（用于 AI 挂断前等待流式生成结束） */
    fun isGenerating(conversationId: Uuid): Boolean {
        val job = _generationJobs.value[conversationId]
        return job != null && job.isActive
    }

    suspend fun initializeConversation(
        conversationId: Uuid,
        targetAssistantId: Uuid? = null,
        skipAutoArchive: Boolean = false, // ✨ 新增：支持跳过离场归档逻辑
        skipMemoryReload: Boolean = false // 通话中传 true：禁止用 DB 快照覆盖内存会话（会抹掉未落库的流式节点并使通话朗读锚点失效）
    ) {
        // 已销毁的临时会话：禁止以正式会话路径重建占位（防止孤儿会话缓存与后续落库）
        if (destroyedTemporaryIds.contains(conversationId)) return
        // 临时会话：不参与 lastConversationId 追踪、离场归档、DB 重载（无 DB 记录）。
        // 仅确保内存会话存在（缓存被逐出时以注册表中的助手身份重建空会话）。
        if (temporarySessions.isTemporary(conversationId)) {
            if (!conversations.containsKey(conversationId)) {
                val sessionAssistantId = targetAssistantId
                    ?: temporarySessions.get(conversationId)?.assistantId
                    ?: settingsStore.settingsFlow.value.getCurrentAssistant().id
                conversations.computeIfAbsent(conversationId) {
                    MutableStateFlow(Conversation.ofId(conversationId, sessionAssistantId))
                }
            }
            initializedConversationIds += conversationId
            return
        }
        val currentConvInDb = conversationRepo.getConversationById(conversationId)

        val currentJob = currentCoroutineContext().job
        val registeredJob = _generationJobs.value[conversationId]
        val isGenerating = registeredJob != null && registeredJob.isActive && registeredJob != currentJob
        val currentConv = conversations[conversationId]?.value ?: currentConvInDb

        val currentAssistantId = targetAssistantId
            ?: currentConv?.assistantId
            ?: settingsStore.settingsFlow.value.getCurrentAssistant().id

        lastConversationId?.let { oldId ->
            if (oldId != conversationId && !skipAutoArchive) { // ✨ 在跳转搜索消息时不执行重归档逻辑
                val isNewConversation = currentConvInDb == null ||
                    currentConvInDb.currentMessages.none { it.role == MessageRole.USER }

                if (isNewConversation) {
                    val oldConv = conversationRepo.getConversationById(oldId)

                    if (oldConv != null && oldConv.assistantId == currentAssistantId) {
                        val settings = settingsStore.settingsFlow.first()
                        val assistant = settings.getAssistantById(oldConv.assistantId) ?: settings.getCurrentAssistant()

                        if (assistant.enableMemory) {
                            appScope.launch {
                                if (archivingConversations.contains(oldId)) return@launch
                                if (assistant.enableRecentChatsReference) {
                                    _syncingConversationIds.update { it + conversationId }
                                    try {
                                        if (assistant.enableDetailMemory) {
                                            launch {
                                                summarizeAndRefresh(
                                                    oldId,
                                                    onlySegments = true,
                                                    skipArchive = true
                                                )
                                            }
                                        }
                                        // 新建会话等同于手动开启新话题：episode 归档成功后
                                        // 需同步更新截断游标，避免用户切回旧会话时 AI 仍能看到已归档消息
                                        val episodeEndTime = archiveConversation(oldId, force = true)
                                        if (episodeEndTime != null) {
                                            conversationRepo.updateLastArchivedMessageTime(oldId, episodeEndTime)
                                        }
                                    } finally {
                                        _syncingConversationIds.update { it - conversationId }
                                    }
                                } else {
                                    if (assistant.enableDetailMemory) {
                                        // skipArchive=true：避免 summarizeAndRefresh 结束时自动调 archiveConversation，
                                        // 与下方显式调用的 archiveConversation 重复
                                        summarizeAndRefresh(oldId, onlySegments = true, skipArchive = true)
                                        val episodeEndTime = archiveConversation(oldId, force = true)
                                        if (episodeEndTime != null) {
                                            conversationRepo.updateLastArchivedMessageTime(oldId, episodeEndTime)
                                        }
                                    } else if (assistant.enableMemoryConsolidation) {
                                        val episodeEndTime = archiveConversation(oldId, force = true)
                                        if (episodeEndTime != null) {
                                            conversationRepo.updateLastArchivedMessageTime(oldId, episodeEndTime)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Log.v(TAG, "切换到已有会话或返回主页，跳过自动归档逻辑。")
                }
            }
        }

        lastConversationId = conversationId
        if (currentConvInDb != null) {
            val currentInMem = conversations[conversationId]?.value
            if (skipMemoryReload) {
                Log.v(TAG, "会话 $conversationId 处于通话中，跳过内存重置以保护通话流式状态")
            } else if (isGenerating && currentInMem != null && currentInMem.messageNodes.isNotEmpty()) {
                Log.v(TAG, "会话 $conversationId 正在后台生成中，跳过内存重置以保护当前生成状态")
            } else {
                updateConversation(conversationId) { currentConvInDb }
            }
            if (targetAssistantId == null) {
                settingsStore.updateAssistant(currentConvInDb.assistantId)
            }
        } else {
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.getAssistantById(currentAssistantId) ?: settings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId, assistantId = assistant.id
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId) { newConversation }
        }
        initializedConversationIds += conversationId
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun archiveConversation(
        conversationId: Uuid, force: Boolean = false, skipEmbedding: Boolean = false
    ): Long? {
        // 临时会话禁止 episode 归档（执行层守卫）
        if (temporarySessions.isTemporary(conversationId)) return null
        if (!archivingConversations.add(conversationId)) return null
        _episodeArchivingIds.update { it + conversationId }

        var archivedEndTime: Long? = null
        try {
            val conv = conversationRepo.getConversationById(conversationId) ?: return null
            val messages = conv.currentMessages
            val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conversationId.toString())
            val episodeSignificance = existingEpisode?.significance ?: 0
            val increment = conversationRepo.countNewMessages(conversationId.toString(), existingEpisode?.endTime ?: 0L)
            if (!force) {
                if (existingEpisode != null) {
                    if (!force && increment < 4) return null
                } else if (messages.size < 4) {
                    return null
                }
            }

            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()

            if (!assistant.enableMemoryConsolidation && !force) return null

            val baseSummary = existingEpisode?.content
            val newMessagesEntities = conversationRepo.getMessagesForSummary(
                conversationId.toString(),
                existingEpisode?.endTime ?: 0L,
                limit = 300
            )
            val newMessages = newMessagesEntities.mapNotNull {
                decodeUIMessageOrNull(it.contentJson, tagExtra = "archive:$conversationId")
            }

            if (newMessages.isEmpty() && baseSummary != null && !force) {
                return null
            }

            val modelId = assistant.summarizerModelId ?: settings.summarizerModelId
            val model = settings.findModelById(modelId)
                ?: assistant.chatModelId?.let { settings.findModelById(it) }
                ?: settings.getCurrentChatModel()
                ?: return null
            val provider = model.findProvider(settings.providers) ?: return null
            val handler = providerManager.getProviderByType(provider)

            val backgroundModelId = assistant.backgroundModelId ?: settings.backgroundModelId
            val backgroundModel = settings.findModelById(backgroundModelId) ?: model
            val backgroundProvider = backgroundModel.findProvider(settings.providers) ?: provider
            val backgroundHandler = providerManager.getProviderByType(backgroundProvider)

            val summary = if (newMessages.isEmpty() && baseSummary != null) {
                baseSummary
            } else {
                val messagesText = StringBuilder().apply {
                    newMessages.forEach { msg ->
                        // 给每条消息附上日期时间戳，便于 AI 在跨天对话中区分事件发生的日期
                        append("[").append(formatEpisodeTimestamp(msg.createdAt)).append("] ")
                            .append(msg.role).append(": ").append(msg.toContentText()).append("\n")
                    }
                }.toString()

                val locale = Locale.getDefault().displayName

                val prompt = fillPrompt(
                    DEFAULT_FULL_SUMMARY_PROMPT, mapOf(
                        "previous_summary" to (baseSummary?.removePrefix("虚拟世界：") ?: "None"),
                        "new_messages" to messagesText,
                        "locale" to locale,
                        "char" to assistant.name
                    )
                )

                val providerHandler = handler as Provider<ProviderSetting>
                val resp = retryIO(times = 2) {
                    providerHandler.generateText(
                        provider,
                        listOf(UIMessage.user(prompt)),
                        TextGenerationParams(model, 0.3f, 1.0f, thinkingBudget = 0)
                    )
                }
                resp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
                resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
            }

            if (summary.isNotBlank() && (!newMessages.isEmpty() || force)) {

                val episode = ChatEpisodeEntity(
                    id = existingEpisode?.id ?: 0,
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    content = summary,
                    keywords = "",
                    embedding = null,
                    embeddingModelId = null,
                    startTime = newMessagesEntities.firstOrNull()?.createdAt ?: conv.createAt.toEpochMilli(),
                    endTime = newMessagesEntities.lastOrNull()?.createdAt ?: conv.updateAt.toEpochMilli(),
                    significance = messages.size,
                    lastAccessedAt = System.currentTimeMillis()
                )
                memoryRepository.saveEpisode(episode)
                archivedEndTime = episode.endTime
                Log.i(TAG, "Archived L2 memory for $conversationId. force=$force, endTime=$archivedEndTime")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive conversation $conversationId", e)
        } finally {
            archivingConversations.remove(conversationId)
            _episodeArchivingIds.update { it - conversationId }
            _episodeArchiveProgress.update { it - conversationId }
        }
        return archivedEndTime
    }

    // ---- 手动整合（L2 episode，进程内协程） ----

    /**
     * 启动手动整合（聊天页「整合」按钮入口，替代原 WorkManager Worker 路径）。
     *
     * @return false 表示已在归档中（幂等拒绝，UI 提示「正在整合中」）
     */
    fun startManualEpisodeArchive(conversationId: Uuid, keepRecent: Int = MANUAL_ARCHIVE_KEEP_RECENT): Boolean {
        if (episodeArchiveJobs.containsKey(conversationId)) return false
        if (archivingConversations.contains(conversationId)) return false
        val job = appScope.launch {
            val result = manualArchiveEpisode(conversationId, keepRecent)
            _episodeArchiveResults.tryEmit(result)
        }
        episodeArchiveJobs[conversationId] = job
        job.invokeOnCompletion { episodeArchiveJobs.remove(conversationId, job) }
        return true
    }

    /** 取消进行中的手动整合（遮罩取消按钮入口）。已持久化 chunk 保留，endTime 停在最后完成的批。 */
    fun cancelManualEpisodeArchive(conversationId: Uuid) {
        episodeArchiveJobs[conversationId]?.cancel()
    }

    /**
     * 手动整合主流程：分 chunk 滚动摘要，逐 chunk 持久化并推进 endTime（断点续归）。
     *
     * 进度语义（对齐 7.5.2 日记 chunk 惯例）：
     * - 每个 chunk 生成成功且 saveEpisode 落库后才推进 endTime / 截断游标；
     * - chunk 失败（异常 / 空白返回）或取消 → 立即停止，游标不推进，已存 chunk 全保留；
     * - 下次归档以 episode.endTime 为锚点严格大于查询，未归档消息绝不进已归档范围。
     */
    private suspend fun manualArchiveEpisode(
        conversationId: Uuid,
        keepRecent: Int
    ): EpisodeArchiveResult {
        if (isTemporaryOrDestroyed(conversationId)) {
            return EpisodeArchiveResult(
                conversationId, EpisodeArchiveStatus.SKIPPED, 0, null, EpisodeArchiveReasons.TEMPORARY_SESSION
            )
        }
        // 与切换钩子（archiveConversation）共享同一把锁：并发时一方跳过，锚点保证增量留给下次
        if (!archivingConversations.add(conversationId)) {
            return EpisodeArchiveResult(
                conversationId, EpisodeArchiveStatus.SKIPPED, 0, null, EpisodeArchiveReasons.IN_PROGRESS
            )
        }
        _episodeArchivingIds.update { it + conversationId }
        var archivedCount = 0
        try {
            val conv = conversationRepo.getConversationById(conversationId)
                ?: return EpisodeArchiveResult(
                    conversationId, EpisodeArchiveStatus.SKIPPED, 0, null, EpisodeArchiveReasons.CONVERSATION_MISSING
                )
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
            val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conversationId.toString())
            val anchorTime = existingEpisode?.endTime ?: 0L

            conversationRepo.markAsNotConsolidated(conversationId)

            val visibleMessages = conv.currentMessages.filter { it.createdAtMillis() > anchorTime }
            if (visibleMessages.isEmpty()) {
                // 锚点之后没有任何新消息：会话已全部归档，直接落定整合状态
                conversationRepo.markAsConsolidated(conversationId)
                syncConversationCursorsFromDb(conversationId)
                return EpisodeArchiveResult(
                    conversationId, EpisodeArchiveStatus.SKIPPED, 0, null, EpisodeArchiveReasons.NO_NEW_MESSAGES
                )
            }
            val messagesToKeep = visibleMessages.limitContext(keepRecent)
            val messagesToProcess = visibleMessages.dropLast(messagesToKeep.size)
            // 保留最近 keepRecent 条的产品规则：本轮实际归档数必须 ≥ keepRecent（与原 Worker 一致）
            if (messagesToProcess.size < keepRecent) {
                return EpisodeArchiveResult(
                    conversationId, EpisodeArchiveStatus.SKIPPED, 0, null, EpisodeArchiveReasons.INSUFFICIENT_MESSAGES
                )
            }

            val modelId = assistant.summarizerModelId ?: settings.summarizerModelId
            val model = settings.findModelById(modelId)
                ?: assistant.chatModelId?.let { settings.findModelById(it) }
                ?: settings.getCurrentChatModel()
                ?: return EpisodeArchiveResult(conversationId, EpisodeArchiveStatus.FAILED, 0, "没有找到可用模型")
            val provider = model.findProvider(settings.providers)
                ?: return EpisodeArchiveResult(conversationId, EpisodeArchiveStatus.FAILED, 0, "没有找到可用服务商")
            val providerHandler = providerManager.getProviderByType(provider) as Provider<ProviderSetting>
            val locale = Locale.getDefault().displayName

            val chunks = splitEpisodeArchiveChunks(
                messagesToProcess, EPISODE_ARCHIVE_MAX_CHUNK_CHARS, EPISODE_ARCHIVE_MAX_CHUNK_MESSAGES
            )
            var rollingSummary = existingEpisode?.content
            var significance = existingEpisode?.significance ?: 0
            // 复用已有 episode 时保留其原始 startTime（覆盖范围起点），不回退为末批起点
            var episodeStartTime = existingEpisode?.startTime
                ?: messagesToProcess.first().createdAtMillis()
            _episodeArchiveProgress.update {
                it + (conversationId to EpisodeArchiveProgress(conversationId, 0, chunks.size))
            }

            for ((index, chunk) in chunks.withIndex()) {
                _episodeArchiveProgress.update {
                    it + (conversationId to EpisodeArchiveProgress(conversationId, index, chunks.size))
                }
                val messagesText = buildString {
                    chunk.forEach { msg ->
                        append("[").append(formatEpisodeTimestamp(msg.createdAt)).append("] ")
                            .append(msg.role).append(": ").append(msg.toContentText()).append("\n")
                    }
                }
                val prompt = fillPrompt(
                    DEFAULT_FULL_SUMMARY_PROMPT, mapOf(
                        "previous_summary" to (rollingSummary?.removePrefix("虚拟世界：") ?: "None"),
                        "new_messages" to messagesText,
                        "locale" to locale,
                        "char" to assistant.name
                    )
                )
                val resp = retryIO(times = 2) {
                    providerHandler.generateText(
                        provider,
                        listOf(UIMessage.user(prompt)),
                        TextGenerationParams(model, 0.3f, 1.0f, thinkingBudget = 0)
                    )
                }
                val chunkSummary = resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
                if (chunkSummary.isBlank()) {
                    // 空白返回视为失败：停止后续 chunk，游标不推进，已存内容全部保留
                    val status = if (archivedCount > 0) EpisodeArchiveStatus.PARTIAL else EpisodeArchiveStatus.FAILED
                    return EpisodeArchiveResult(conversationId, status, archivedCount, "部分批次摘要为空")
                }

                val chunkEndTime = chunk.last().createdAtMillis()
                rollingSummary = chunkSummary
                significance += chunk.size
                memoryRepository.saveEpisode(
                    ChatEpisodeEntity(
                        id = existingEpisode?.id ?: 0,
                        assistantId = assistant.id.toString(),
                        conversationId = conversationId.toString(),
                        content = chunkSummary,
                        keywords = "",
                        embedding = null,
                        embeddingModelId = null,
                        startTime = episodeStartTime,
                        endTime = chunkEndTime,
                        significance = significance,
                        lastAccessedAt = System.currentTimeMillis()
                    )
                )
                // 截断游标与 endTime 同值推进，保持「已归档 ⇒ 上下文可截断」一致
                conversationRepo.updateLastArchivedMessageTime(conversationId, chunkEndTime)
                syncConversationCursorsFromDb(conversationId)
                archivedCount += chunk.size
            }
            conversationRepo.markAsConsolidated(conversationId)
            syncConversationCursorsFromDb(conversationId)
            return EpisodeArchiveResult(conversationId, EpisodeArchiveStatus.COMPLETED, archivedCount, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户取消：in-flight chunk 丢弃，已持久化 chunk 保留（endTime 停在最后完成的批）。
            // 吞掉取消让 Job 正常收尾（fire-and-forget 场景），finally 里做无挂起清理。
            val status = if (archivedCount > 0) EpisodeArchiveStatus.PARTIAL else EpisodeArchiveStatus.SKIPPED
            return EpisodeArchiveResult(conversationId, status, archivedCount, null)
        } catch (e: Exception) {
            Log.e(TAG, "Manual episode archive failed for $conversationId", e)
            val status = if (archivedCount > 0) EpisodeArchiveStatus.PARTIAL else EpisodeArchiveStatus.FAILED
            return EpisodeArchiveResult(conversationId, status, archivedCount, e.message)
        } finally {
            archivingConversations.remove(conversationId)
            _episodeArchivingIds.update { it - conversationId }
            _episodeArchiveProgress.update { it - conversationId }
        }
    }

    /**
     * 把 DB 侧归档相关游标同步进内存会话：内存态保留旧值会在后续 saveConversation 时
     * 把 DB 里正确的游标覆盖回旧值（历史 bug，见原 ChatVM 整合同步注释）。
     * 仅纯内存更新 + 持久化既有列，不触碰消息节点。
     */
    private suspend fun syncConversationCursorsFromDb(conversationId: Uuid) {
        val updated = conversationRepo.getConversationById(conversationId) ?: return
        mutateConversationAndSave(conversationId) { current ->
            current.copy(
                isConsolidated = updated.isConsolidated,
                lastSummarizedMessageTime = updated.lastSummarizedMessageTime,
                lastSummarizedMessageId = updated.lastSummarizedMessageId,
                lastPruneTime = updated.lastPruneTime,
                lastPruneMessageCount = updated.lastPruneMessageCount,
                lastRefreshTime = updated.lastRefreshTime,
                lastArchivedMessageTime = updated.lastArchivedMessageTime
            )
        }
    }

    /** episode 归档 prompt 用的「yyyy-MM-dd HH:mm」时间戳（与 archiveConversation 同款格式）。 */
    private fun formatEpisodeTimestamp(ts: LocalDateTime): String = buildString {
        append(ts.year).append('-')
        append(ts.monthNumber.toString().padStart(2, '0')).append('-')
        append(ts.dayOfMonth.toString().padStart(2, '0')).append(' ')
        append(ts.hour.toString().padStart(2, '0')).append(':')
        append(ts.minute.toString().padStart(2, '0'))
    }

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        predefinedUserNode: MessageNode? = null,
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = true, // 普通对话也包含 skipContext 消息，由 messageSource 控制截断策略
        responseMessageSource: MessageSource = MessageSource.NORMAL, // AI 回复的消息来源标识
        callId: String? = null,
        chatModelOverrideId: Uuid? = null, // 临时会话内存态模型切换（不写回助手配置）
        assistantOverride: Assistant? = null // 临时会话内存态助手配置覆盖（世界书/推理/搜索/UI模式等，不持久化）
    ) {
        // 临时会话身份由 TemporaryChatSessionRegistry 在会话创建时统一注册，
        // 不再依赖调用方逐个传递 isTemporary 标记；销毁中/已销毁的会话拒绝接收新消息。
        if (temporarySessions.isDestroying(conversationId) || destroyedTemporaryIds.contains(conversationId)) {
            Log.w(TAG, "sendMessage ignored: temporary session is destroying/destroyed conv=$conversationId")
            return
        }

        val oldJob = _generationJobs.value[conversationId]
        if (oldJob != null) {
            oldJob.cancel()
            removeGenerationJob(conversationId)
            // 仅在不触发 AI 回复时清除 typing 状态（如 sendMessageWithoutAnswer）
            // answer=true 时由 triggerAIResponse 管理状态，避免 true→false→true 闪烁
            if (!answer) {
                _isAiTypingMap.update { it - conversationId }
            }
        }
        wechatDebounceJobs[conversationId]?.cancel()
        val newNode = predefinedUserNode ?: UIMessage(role = MessageRole.USER, parts = content).toMessageNode(conversationId)
        updateConversation(conversationId, normalizeNodes = true) { old ->
            old.copy(
                messageNodes = old.messageNodes + newNode,
                updateAt = Instant.now()
            )
        }
        appScope.launch {
            // Capture the latest state only after entering the serialized persistence path.
            // A previously captured snapshot can otherwise erase the first streamed chunk.
            mutateConversationAndSave(conversationId) { current -> current }
            // 临时会话不计入每日活跃统计（避免污染正式数据）
            if (!temporarySessions.isTemporary(conversationId)) {
                conversationRepo.recordDailyActivity()
            }
        }

        if (answer) {
            triggerAIResponse(
                conversationId = conversationId,
                skipContextForResponse = skipContextForResponse,
                includeSkipContextMessages = includeSkipContextMessages,
                responseMessageSource = responseMessageSource,
                callId = callId,
                chatModelOverrideId = chatModelOverrideId,
                assistantOverride = assistantOverride
            )
        }
    }

    /**
     * 单独触发 AI 回复（不添加用户消息）。
     *
     * 用于需要先把用户消息入列显示、再异步准备 context 后触发 AI 生成的场景，
     * 例如语音消息：先入列显示语音条 → 后台 ASR 转写 → 更新消息 metadata → 调用此方法触发 AI。
     */
    fun triggerAIResponse(
        conversationId: Uuid,
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = true,
        responseMessageSource: MessageSource = MessageSource.NORMAL,
        callId: String? = null,
        chatModelOverrideId: Uuid? = null,
        assistantOverride: Assistant? = null
    ) {
        // 取消已存在的生成任务，防止出现孤儿任务继续流式存储未保存的分句。
        // sendMessage 已取消一次，但 maybeTriggerAIAfterAsr 等调用方可能未取消，
        // 两次 triggerAIResponse 调用会导致第一个任务成为孤儿（不在任何 Map 中），
        // 继续持有 mutex 流式写入分句，用户发新消息时无法被打断。
        // 已销毁/销毁中的临时会话拒绝触发：防止异步残留回调在空会话上白白生成并重建会话
        if (destroyedTemporaryIds.contains(conversationId) || temporarySessions.isDestroying(conversationId)) return
        _generationJobs.value[conversationId]?.cancel()
        wechatDebounceJobs[conversationId]?.cancel()

        val settings = settingsStore.settingsFlow.value
        val wechatMode = settings.getEffectiveDisplaySetting(settings.getCurrentAssistant()).wechatMode

        // 普通模式下不清除 typing 状态，避免 true→false→true 闪烁导致终止按钮短暂消失
        // 微信模式下需要清除，因为要 5 秒延迟后才重新设置（前 5 秒不显示终止按钮）
        if (wechatMode) {
            _isAiTypingMap.update { it - conversationId }
        }

        val debounceJob = appScope.launch {
            if (wechatMode) {
                delay(5000)
            }
            _isAiTypingMap.update { it + (conversationId to true) }

            val timeoutMinutes = settings.chatGenerationTimeoutMinutes
            val timeoutJob = launch {
                delay(timeoutMinutes * 60 * 1000L)
                // 超时后取消整个生成任务并提示用户
                _errorFlow.emit(java.net.SocketTimeoutException(context.getString(R.string.chat_generation_timeout, timeoutMinutes)))
                cancel()
            }
            try {
                handleMessageComplete(
                    conversationId = conversationId,
                    skipContextForResponse = skipContextForResponse,
                    includeSkipContextMessages = includeSkipContextMessages,
                    responseMessageSource = responseMessageSource,
                    callId = callId,
                    chatModelOverrideId = chatModelOverrideId,
                    assistantOverride = assistantOverride
                )
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _errorFlow.emit(translateError(e))
                }
            } finally {
                timeoutJob.cancel()
                // 竞态防护：只在当前 job 仍是活跃的 generation job 时才清除 typing 状态
                // 避免被新 job 替换后，旧 job 的 finally 异步执行清除了新 job 的 typing 状态
                // （这是"多次工具调用时终止按钮消失"的根本原因）
                val isStillActiveJob = _generationJobs.value[conversationId] == currentCoroutineContext().job
                if (isStillActiveJob) {
                    _isAiTypingMap.update { it - conversationId }
                }
            }
        }

        setGenerationJob(conversationId, debounceJob)
        wechatDebounceJobs[conversationId] = debounceJob

        debounceJob.invokeOnCompletion {
            _generationJobs.update { current ->
                if (current[conversationId] == debounceJob) current - conversationId else current
            }
            wechatDebounceJobs.compute(conversationId) { _, current ->
                if (current == debounceJob) null else current
            }
            appScope.launch { delay(500); checkAllConversationsReferences() }
        }
    }

    suspend fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
        forceWipe: Boolean = false,
        requirement: String? = null,
        includeSkipContextMessages: Boolean = true,
        chatModelOverrideId: Uuid? = null, // 临时会话内存态模型覆盖（不传 = 正式会话按助手/全局配置）
        assistantOverride: Assistant? = null // 临时会话内存态助手配置覆盖
    ) {
        // 取消旧任务
        val oldJob = _generationJobs.value[conversationId]
        oldJob?.cancelAndJoin()
        removeGenerationJob(conversationId)

        var targetMsgId: Uuid? = null
        var contextEndNodeId: Uuid? = null

        // Build the replacement from the latest state while holding the persistence
        // lock. A serialized write of a snapshot captured before the lock can still
        // overwrite a newer mutation.
        val preparedReplacement = withTimeoutOrNull(5000) {
            initializeConversation(conversationId)
            // 目标消息可能在空占位节点(超出默认水合深度的老消息),先定向水合再查找
            replaceConversationMessages(conversationId, targetMessageIds = setOf(message.id)) { conversation ->
                if (message.role == MessageRole.USER) {
                    val indexAt = conversation.messageNodes.indexOfFirst { node ->
                        node.messages.any { candidate -> candidate.id == message.id }
                    }
                    if (indexAt < 0) return@replaceConversationMessages null

                    val nodesToDelete = conversation.messageNodes.drop(indexAt + 1)
                    return@replaceConversationMessages ConversationMessageDeletion(
                        conversation = conversation.copy(
                            messageNodes = conversation.messageNodes.take(indexAt + 1)
                        ),
                        deletedNodeIds = nodesToDelete.mapTo(linkedSetOf()) { node -> node.id },
                        deletedMessageIds = emptySet()
                    )
                }

                if (!regenerateAssistantMsg) return@replaceConversationMessages null

                val clickedNode = conversation.getMessageNodeByMessageId(message.id)
                val clickedIndex = conversation.messageNodes.indexOf(clickedNode)
                if (clickedIndex < 0) return@replaceConversationMessages null
                val lastUserIndex = conversation.messageNodes.take(clickedIndex + 1)
                    .indexOfLast { node ->
                        node.messages.isNotEmpty() && node.role == MessageRole.USER
                    }
                if (lastUserIndex < 0) return@replaceConversationMessages null

                contextEndNodeId = conversation.messageNodes[lastUserIndex].id
                val firstAssistantIndex = lastUserIndex + 1
                val nextUserOffset = conversation.messageNodes
                    .drop(firstAssistantIndex)
                    .indexOfFirst { node ->
                        node.messages.isNotEmpty() && node.role == MessageRole.USER
                    }
                val turnEndIndex = if (nextUserOffset < 0) {
                    conversation.messageNodes.size
                } else {
                    firstAssistantIndex + nextUserOffset
                }
                val turnNodes = conversation.messageNodes.slice(firstAssistantIndex until turnEndIndex)
                val hasToolInteraction = turnNodes.any { node ->
                    node.messages.any { turnMessage ->
                        turnMessage.parts.any { part ->
                            part is UIMessagePart.ToolCall || part is UIMessagePart.ToolResult
                        }
                    }
                }

                if (forceWipe || hasToolInteraction) {
                    val placeholderMessage = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList()
                    )
                    targetMsgId = placeholderMessage.id
                    val replacementNodes = conversation.messageNodes
                        .take(lastUserIndex + 1)
                        .toMutableList()
                    replacementNodes += placeholderMessage.toMessageNode(conversation.id)
                    if (turnEndIndex < conversation.messageNodes.size) {
                        replacementNodes += conversation.messageNodes.drop(turnEndIndex)
                    }
                    ConversationMessageDeletion(
                        conversation = conversation.copy(messageNodes = replacementNodes),
                        deletedNodeIds = turnNodes.mapTo(linkedSetOf()) { node -> node.id },
                        deletedMessageIds = emptySet()
                    )
                } else {
                    val versionTag = Uuid.random().toString()
                    val newMessage = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                        versionTag = versionTag
                    )
                    targetMsgId = newMessage.id
                    val replacementNodes = conversation.messageNodes.toMutableList()
                    val nodeToUpdate = replacementNodes[clickedIndex]
                    val newMessages = nodeToUpdate.messages + newMessage
                    replacementNodes[clickedIndex] = nodeToUpdate.copy(
                        messages = newMessages,
                        selectIndex = newMessages.lastIndex
                    )
                    ConversationMessageDeletion(
                        conversation = conversation.copy(messageNodes = replacementNodes),
                        deletedNodeIds = emptySet(),
                        deletedMessageIds = emptySet()
                    )
                }
            }
        } ?: return

        if (preparedReplacement.deletedNodeIds.isNotEmpty()) {
            Log.v(TAG, "Regeneration removed ${preparedReplacement.deletedNodeIds.size} nodes")
        }

        val job = appScope.launch {
                _isAiTypingMap.update { it + (conversationId to true) }
                try {
                    handleMessageComplete(
                        conversationId = conversationId,
                        contextEndNodeId = contextEndNodeId,
                        assistantOverride = assistantOverride,
                        requirement = requirement,
                        targetMessageId = targetMsgId,
                        includeSkipContextMessages = includeSkipContextMessages,
                        chatModelOverrideId = chatModelOverrideId
                    )
                    _generationDoneFlow.emit(conversationId)
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        _errorFlow.emit(translateError(e))
                    }
                }finally {
                    // 竞态防护：同 triggerAIResponse，避免旧 job 的 finally 清除新 job 的 typing 状态
                    val isStillActiveJob = _generationJobs.value[conversationId] == currentCoroutineContext().job
                    if (isStillActiveJob) {
                        _isAiTypingMap.update { it - conversationId }
                    }
                }
        }
        setGenerationJob(conversationId, job)
        job.invokeOnCompletion {
            _generationJobs.update { current ->
                if (current[conversationId] == job) current - conversationId else current
            }
            appScope.launch { delay(500); checkAllConversationsReferences() }
        }
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        contextEndNodeId: Uuid? = null,
        assistantOverride: Assistant? = null,
        skipContextForResponse: Boolean = false,
        includeSkipContextMessages: Boolean = true,
        responseMessageSource: MessageSource = MessageSource.NORMAL,
        callId: String? = null,
        requirement: String? = null,
        targetMessageId: Uuid? = null,
        chatModelOverrideId: Uuid? = null // 临时会话内存态模型切换，null = 按助手/全局配置解析
    ) {
            val mutex = conversationMutexes.computeIfAbsent(conversationId) { Mutex() }
            mutex.withLock {
                checkInvalidMessages(conversationId)
                val currentJob = currentCoroutineContext().job
                val settings = settingsStore.settingsFlow.first()
            var currentConversation = conversations[conversationId]?.value
                ?: conversationRepo.getConversationById(conversationId)
                ?: return@withLock
            // 未读基线快照：生成开始时「已有可见内容」的节点集合。AI 回复的首条消息会复用
            // 生成前已存在的空占位节点 id（processMessageIds[0] = placeholderId），不能以
            // 「全部节点 id 差集」判定新消息——以「是否已有可见内容」为界：占位节点生成后
            // 变为可见 → 计入未读；刷新回复是在已有可见内容的节点上追加版本 → 已在基线内，
            // 不重复计数（未读以节点为单位，一个节点至多一条记录）。
            val baselineVisibleNodeIds = currentConversation.messageNodes
                .filter { UnreadRules.isVisibleAssistantNode(it) }
                .mapTo(mutableSetOf()) { it.id }
            // 已做过未读决策的节点 id（微信模式逐句 / 整条语音路径在落库时决策），
            // 防止 onSuccess 汇总时对同一批节点重复决策，覆盖用户中途查看的已读状态
            val unreadHandledNodeIds = mutableSetOf<Uuid>()
            val processMessageIds = mutableMapOf<Int, Uuid>()
            val placeholderId = targetMessageId ?: currentConversation.currentMessages.lastOrNull()?.let {
                if (it.role == MessageRole.ASSISTANT && it.toContentText()
                        .isBlank() && it.parts.isEmpty()
                ) it.id else null
            }
            if (placeholderId != null) {
                // 强制让 AI 生成的第一条消息复用这个占位符的 ID
                processMessageIds[0] = placeholderId
                _generatingNodeIds.update { it + placeholderId }
            }

            // 修复：每次生成全新重置句子计数器

            var lastTotalFullText = ""
            var lastAiId: Uuid? = null

            // Token 记账基线：生成开始前已存在的 assistant 消息 usage（历史轮次已入账）。
            // onSuccess 时只统计「新增 id」的 assistant 消息 usage 并求和入账：
            // 多步工具调用每步的 usage 各挂在当步 assistant 消息上；regenerate 与占位符复用
            // 场景产生的消息为全新 id（占位符 id 已从基线排除），不会与旧消息已入账 usage 重复。
            var priorAssistantUsages: Map<Uuid, TokenUsage> = emptyMap()

            // 微信模式分句状态：流式过程中累积文本，按标点切句，逐句存为独立节点
            val wechatSentenceBuffer = StringBuilder()
            var wechatProcessedTextLen = 0
            var wechatStoredSentenceCount = 0
            var wechatFirstNodeNonTextParts: List<UIMessagePart>? = null
            var wechatLastSentenceLength = 0 // 上一句的长度，用于计算下一句的 delay 节奏（与原 UI 打字动画一致）
            var wechatOriginalAISnapshot: UIMessage? = null // 微信模式下保存原始 AI 消息快照（用于迁移 usage 和上下文来源到分句节点）

            // 微信模式标记触发语音：AI 主动在回复开头加 (语音消息) 标记时，
            // 整条回复合成一个语音条发送，不分句（概率触发仍逐句判定）
            var wechatMarkerTriggered = false
            val wechatFullReplyBuffer = StringBuilder() // 标记触发时累积整条回复文本

            runCatching {
                currentConversation = currentConversation.copy(chatSuggestions = emptyList())
                updateConversation(conversationId) { old ->
                    old.copy(chatSuggestions = emptyList()).also { currentConversation = it }
                }
                val assistant = assistantOverride ?: settings.getAssistantById(currentConversation.assistantId)
                ?: settings.getCurrentAssistant()
                val modelId = chatModelOverrideId ?: assistant.chatModelId ?: settings.chatModelId
                val model = settings.findModelById(modelId) ?: settings.getCurrentChatModel() ?: return@runCatching
                var firstTokenTime: Long? = null
                // 增量节流落库计时（新节点/工具边界立即落库，纯文本流按间隔节流）
                var lastIncrementalSaveAt = 0L
                if (!model.abilities.contains(ModelAbility.TOOL)) {
                    val hasExternalTools =
                        (assistant.searchMode !is AssistantSearchMode.Off) || mcpManager.getAllAvailableTools()
                            .isNotEmpty()
                    if (hasExternalTools) _errorFlow.emit(IllegalStateException(context.getString(R.string.tools_warning)))
                }
                val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode
                val wechatVoiceEnabled = wechatMode && settings.getEffectiveDisplaySetting(assistant).aiVoiceMessageEnabled

                checkInvalidMessages(conversationId)
                currentConversation = getConversationFlow(conversationId).value
                val retrievedMemories = withContext(Dispatchers.IO) {
                    // 临时会话允许【读取】已有记忆（基于角色记忆对话），仅禁止写入（工具层拦截）
                    if (assistant.enableMemory && assistant.memoryRetrievalMode != MemoryRetrievalMode.OFF
                    ) {
                        withTimeoutOrNull(4000) {
                            if (assistant.useRagMemoryRetrieval) {
                                val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode
                                val rawUserMsg = if (wechatMode) {
                                    val messages = currentConversation.currentMessages
                                    val lastNonUser = messages.indexOfLast { it.role != MessageRole.USER }
                                    messages.subList(lastNonUser + 1, messages.size)
                                        .joinToString(" ") { it.toText() }
                                } else {
                                    currentConversation.currentMessages.lastOrNull { it.role == MessageRole.USER }
                                        ?.toText()
                                        ?: ""
                                }
                                val lastUserMsg = rawUserMsg.replace("\n", " ").trim()
                                if (lastUserMsg.isNotBlank()) {
                                    val results = memoryRepository.retrieveRelevantMemoriesWithScores(
                                        assistantId = assistant.id.toString(),
                                        query = lastUserMsg,
                                        limit = assistant.ragLimit,
                                        similarityThreshold = assistant.ragSimilarityThreshold,
                                        mode = assistant.memoryRetrievalMode,
                                        excludeConversationId = conversationId.toString()
                                    )
                                    val memories = results.map { it.first }
                                    if (settings.enableRagLogging) {
                                        results.forEach { (mem, score) ->
                                        }
                                    }
                                    memories
                                } else {
                                    emptyList()
                                }
                            } else memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                        } ?: emptyList()
                    } else emptyList()
                }

                val currentEpisode = chatEpisodeDAO.getEpisodeByConversationId(conversationId.toString())

                val baseMessages = selectMessagesForGeneration(
                    messageNodes = currentConversation.messageNodes,
                    contextEndNodeId = contextEndNodeId,
                    lastArchivedMessageTime = currentConversation.lastArchivedMessageTime
                )
                    .filter { msg ->
                        msg.role != MessageRole.ASSISTANT ||
                            msg.toContentText().isNotBlank() ||
                            msg.parts.any { it is UIMessagePart.ToolCall } ||
                            msg.parts.any { it is UIMessagePart.Audio }
                    }

                // 临时处理优化要求：仅针对发送给 AI 的上下文做拼接，不修改 baseMessages (用于 UI 同步)
                val messagesForModel = if (!requirement.isNullOrBlank()) {
                    val list = baseMessages.toMutableList()
                    val lastUserIdx = list.indexOfLast { it.role == MessageRole.USER }
                    if (lastUserIdx != -1) {
                        val lastUserMsg = list[lastUserIdx]
                        val updatedParts = lastUserMsg.parts.toMutableList()
                        val lastTextIdx = updatedParts.indexOfLast { it is UIMessagePart.Text }
                        if (lastTextIdx != -1) {
                            val textPart = updatedParts[lastTextIdx] as UIMessagePart.Text
                            updatedParts[lastTextIdx] = textPart.copy(text = textPart.text + " (回复要求：$requirement)")
                        } else {
                            updatedParts.add(UIMessagePart.Text("(回复要求：$requirement)"))
                        }
                        list[lastUserIdx] = lastUserMsg.copy(parts = updatedParts)
                    }
                    list
                } else baseMessages

                val finalContextMessages = if (wechatMode) {
                    val messages = messagesForModel.toMutableList()
                    val lastUserGroupStart = messages.indexOfLast { it.role != MessageRole.USER } + 1
                    if (lastUserGroupStart in messages.indices && messages.size - lastUserGroupStart > 1) {
                        val userMessages = messages.subList(lastUserGroupStart, messages.size)
                        val allParts =
                            userMessages.flatMap { it.parts }.distinctBy { (it as? UIMessagePart.Image)?.url ?: it }

                        val combinedMsg = UIMessage(
                            role = MessageRole.USER,
                            parts = allParts,
                            createdAt = userMessages.last().createdAt
                        )
                        messages.subList(lastUserGroupStart, messages.size).clear()
                        messages.add(combinedMsg)
                    }
                    messages
                } else messagesForModel

                // 记账基线快照：必须排除占位符 id——AI 生成的首条消息会复用该 id
                //（见 placeholderId 逻辑），若留在基线内会导致首条 assistant 消息漏计
                val baselineExcludedIds = placeholderId?.let { setOf(it) } ?: emptySet()
                priorAssistantUsages = currentConversation.currentMessages
                    .filter { msg ->
                        val isAssistant = msg.role == MessageRole.ASSISTANT
                        val isBaseline = msg.id !in baselineExcludedIds
                        isAssistant && isBaseline
                    }
                    .mapNotNull { msg -> msg.usage?.let { msg.id to it } }
                    .toMap()

                generationHandler.generateText(
                        settings = settings,
                        model = model,
                        messages = finalContextMessages,
                        assistant = assistant,
                        memories = retrievedMemories,
                        inputTransformers = buildList { addAll(inputTransformers); add(templateTransformer) },
                        outputTransformers = outputTransformers,
                        isCallMode = { callModeConversations.contains(conversationId) },
                        isTemporarySession = { temporarySessions.isTemporary(conversationId) },
                        tools = buildList {
                            val isMain = assistant.isMain


                            val supportsBuiltIn =
                                model.tools.isNotEmpty() || ModelRegistry.GEMINI_SERIES.match(model.modelId)
                            val useBuiltIn = assistant.preferBuiltInSearch && supportsBuiltIn
                            val searchMode = assistant.searchMode

                            if (searchMode is AssistantSearchMode.Provider && !useBuiltIn) {
                                addAll(
                                    createSearchTool(
                                        settings,
                                        assistant,
                                        searchMode.index,
                                    ))
                            }

                            val rawOptions = if (isMain) {
                                // 1. 获取当前数据库里的工具配置
                                // 2026-11-19：移除 Release 强制注入 coreTools 的逻辑——
                                // 未开启的工具不得把任何信息带入上下文，本地工具在所有
                                // 构建下一律严格跟随助手工具开关（首装默认模板已在
                                // PreferencesStore 补齐核心工具）
                                val list = assistant.localTools.toMutableList()

                                if (assistant.enableMemory) {
                                    if (list.none { it is LocalToolOption.MilestoneManagement }) {
                                        list.add(LocalToolOption.MilestoneManagement)
                                    }
                                } else {
                                    list.removeAll { it is LocalToolOption.MilestoneManagement }
                                }
                                list
                            } else {
                                // 普通助手逻辑保持不变
                                assistant.localTools.filter {
                                    it is LocalToolOption.TimeSense ||
                                        it is LocalToolOption.EmailService ||
                                        it is LocalToolOption.ImageGeneration ||
                                        it is LocalToolOption.DeviceControl ||
                                        it is LocalToolOption.PrintContent ||
                                        it is LocalToolOption.RadioStation ||
                                        @Suppress("DEPRECATION") it is LocalToolOption.CameraCapture
                                }
                            }

                            // 临时会话工具白名单（TemporarySessionToolRules，有单测）：
                            // 过滤写正式数据/现实副作用的选项——与记忆联动的
                            // MilestoneManagement 先组装完再统一过滤，
                            // RadioAgentTools 注入以 targetOptions 判定，过滤后自然跳过
                            val targetOptions = if (temporarySessions.isTemporary(conversationId)) {
                                TemporarySessionToolRules.filterOptions(rawOptions)
                            } else rawOptions

                            addAll(
                                localTools.getTools(
                                    options = targetOptions,
                                    assistantId = assistant.id,
                                    conversationId = currentConversation.id,
                                    userImageUrls = currentConversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.parts?.filterIsInstance<UIMessagePart.Image>()
                                        ?.map { it.url } ?: emptyList()))


                            // MCP 工具按需加载：不逐工具注入完整 Schema，只注册两个固定函数
                            // （查询 Schema + 执行工具），工具目录（名称+有效描述）由
                            // get_mcp_tool_schema 的 systemPrompt 注入系统提示
                            addAll(buildMcpAgentTools(mcpManager))

                            // 通用后台任务工具（常驻注册，Schema 极小）：主动查询是回调机制的兜底能力
                            addAll(buildBackgroundTaskTools(backgroundTaskManager, assistant.id, currentConversation.id))

                            // 小机电台工具按需加载（AGENTS.md 12.4）：同样只注册两个固定函数，
                            // 需助手开启 LocalToolOption.RadioStation。
                            // 上下文透传：会话/锚点信息供后台任务创建关联使用
                            if (targetOptions.contains(LocalToolOption.RadioStation)) {
                                addAll(buildRadioAgentTools(
                                    radioService = radioService,
                                    assistant = assistant,
                                    mediaPlaybackController = mediaPlaybackController,
                                    conversationId = currentConversation.id,
                                    sourceTurnId = currentConversation.currentMessages.lastOrNull()?.id?.toString(),
                                    backgroundTaskManager = backgroundTaskManager,
                                ))
                            }

                            // 扩展工具按需加载（AGENTS.md Extensions）：同样只注册两个固定函数，
                            // 工具目录（systemPrompt）与执行路径使用同一可用性判定
                            // （ExtensionRules.isExtensionToolAvailable）：Mini App 内 Tool 看
                            // enabledMiniAppIds，独立 Tool Extension 看 enabledToolExtensionIds，
                            // 未启用的扩展工具任何信息不进入上下文；无可用扩展时不注册固定
                            // 工具与目录。临时会话由 TemporarySessionToolRules 双层拦截。
                            addAll(buildExtensionAgentTools(extensionRegistry, extensionToolRuntime, assistant))

                        },
                        lastArchivedMessageTime = 0L,
                        enabledModeIds = currentConversation.enabledModeIds,
                        contextSummary = currentEpisode?.content?.removePrefix("虚拟世界："),
                        temporarySummaries = emptyList(),
                        skipContextForResponse = skipContextForResponse,
                        includeSkipContextMessages = includeSkipContextMessages,
                        responseMessageSource = responseMessageSource,
                        conversationId = conversationId,
                        callId = callId
                    ).onCompletion {
                        _generatingNodeIds.update { old ->
                            old - currentConversation.messageNodes.map { it.id }.toSet()
                        }
                        val duration = firstTokenTime?.let { System.currentTimeMillis() - it }
                        updateConversation(conversationId) { old ->
                            val finalUpdated = old.copy(
                                messageNodes = old.messageNodes.mapIndexed { idx, node ->
                                    val isLast = idx == old.messageNodes.lastIndex
                                    node.copy(messages = node.messages.map { msg ->
                                        val finished = msg.finishReasoning()
                                        if (isLast && finished.role == MessageRole.ASSISTANT && finished.generationDurationMs == null) {
                                            finished.copy(generationDurationMs = duration)
                                        } else {
                                            finished
                                        }
                                    })
                                },
                                chatSuggestions = emptyList(),
                                updateAt = Instant.now()
                            )
                            currentConversation = finalUpdated
                            finalUpdated
                        }
                        // 微信模式：改为逐分句通知（见 storeWechatSentenceNode / 标记触发存储点），此处不再统一发送
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && !wechatMode) {
                            sendGenerationDoneNotification(conversationId)
                        }
                    }.collect { chunk ->
                        if (firstTokenTime == null) firstTokenTime = System.currentTimeMillis()
                        if (chunk is GenerationChunk.Messages) {
                            var conversationSnapshot = currentConversation
                            val finalMessages = chunk.messages
                            val newMessages = finalMessages.drop(finalContextMessages.size).mapIndexed { index, msg ->
                                msg.copy(id = processMessageIds.getOrPut(index) { msg.id })
                            }
                            if (newMessages.isEmpty()) {
                                // 流结束后 GenerationHandler 会再 emit 一次以附加 usedMemories 等上下文来源
                                // 此时无新消息但 lastAI 已更新，需同步到快照，否则上下文来源会丢失
                                finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.let {
                                    wechatOriginalAISnapshot = it
                                }
                                return@collect
                            }
                            val nodesBeingGenerated = conversationSnapshot.messageNodes
                                .filter { n -> newMessages.any { m -> n.messages.any { nm -> nm.id == m.id } } }
                                .map { it.id }.toSet()
                            _generatingNodeIds.update { it + nodesBeingGenerated }

                            val lastAI = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                            val lastAiMsg = newMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                            val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode

                            // 1.提前 definition fullText
                            val fullText = lastAI?.toContentText() ?: ""

                            // 2. 检查是否有任何消息（包括工具结果、AI 最终回答）尚未加入会话
                            val anyNewMessages = newMessages.any { nm ->
                                conversationSnapshot.messageNodes.none { node ->
                                    node.messages.any { m -> m.id == nm.id }
                                }
                            }

                            // 3. 如果 AI 消息 ID 变了（比如从工具调用切换到了最终回答），重置微信模式的分句状态
                            if (lastAI != null && lastAI.id != lastAiId) {
                                lastAiId = lastAI.id
                                wechatSentenceBuffer.clear()
                                wechatProcessedTextLen = 0
                                wechatStoredSentenceCount = 0
                                wechatFirstNodeNonTextParts = null
                                wechatLastSentenceLength = 0
                                wechatOriginalAISnapshot = null
                                wechatMarkerTriggered = false
                                wechatFullReplyBuffer.clear()
                            }

                            // 4. 微信模式 + 有最终文本：按标点分句，逐句存为独立节点（含标点，非文本 parts 放第一个节点）
                            if (wechatMode && lastAI != null && fullText.isNotBlank()) {
                                // 保存原始 AI 消息快照，用于流结束后把 usage/上下文来源迁移到最后一个分句节点
                                wechatOriginalAISnapshot = lastAI
                                // 4a. 同步非最终 AI 的消息（工具调用、工具结果等）
                                // 微信模式下去重：含 Text 的 ASSISTANT 已被分句存储，只保留 nonTextParts
                                val otherMessages = newMessages
                                    .filter { it.id != lastAI.id }
                                    .forWechatSync()
                                if (otherMessages.isNotEmpty()) {
                                    val nextState = conversationSnapshot.updateCurrentMessages(baseMessages + otherMessages)
                                        .copy(chatSuggestions = emptyList())
                                    currentConversation = nextState
                                    updateConversation(conversationId) { nextState }
                                    conversationSnapshot = nextState
                                }

                                // 4b. 记录第一句节点的非文本 parts（工具调用图标等）
                                // 微信模式下不存储深度思考内容（Reasoning/Thinking），避免重复且符合口语化场景
                                val nonTextParts = lastAI.parts.filter {
                                    it !is UIMessagePart.Text &&
                                        it !is UIMessagePart.Reasoning &&
                                        it !is UIMessagePart.Thinking
                                }
                                if (nonTextParts.isNotEmpty()) {
                                    wechatFirstNodeNonTextParts = nonTextParts
                                }

                                // 4c. 标记触发检测：回复以 (语音消息) / （语音消息） 开头时，
                                // 整条回复作为一个语音条发送，跳过分句（概率触发仍逐句判定）
                                if (!wechatMarkerTriggered && wechatStoredSentenceCount == 0) {
                                    val markerText = stripVoiceMessageMarker(fullText)
                                    // 只有当标记后已有实际内容时才确认触发，避免误判
                                    if (markerText != null && markerText.isNotBlank()) {
                                        wechatMarkerTriggered = true
                                        Log.d(TAG, "WeChat voice marker triggered (full reply): '${markerText.take(30)}...'")
                                    }
                                }

                                if (wechatMarkerTriggered) {
                                    // 标记触发模式：累积全部文本，不分句，留到流结束合成整条语音
                                    wechatProcessedTextLen = fullText.length
                                    wechatFullReplyBuffer.apply {
                                        clear()
                                        // 移除回复中所有 (语音消息) 标记（开头/中间/全角/半角），
                                        // 避免 TTS 把标记文字读出来
                                        val cleaned = fullText
                                            .replace(WECHAT_VOICE_MESSAGE_MARKER, "")
                                            .replace(WECHAT_VOICE_MESSAGE_MARKER_FULLWIDTH, "")
                                        append(cleaned)
                                    }
                                } else if (fullText.length > wechatProcessedTextLen) {
                                    // 4d. 概率触发模式：逐句判定（10% 概率转语音，其余文字；带 (语音消息) 标记的句子强制转语音）
                                    val increment = fullText.substring(wechatProcessedTextLen)
                                    wechatSentenceBuffer.append(increment)
                                    wechatProcessedTextLen = fullText.length

                                    // 排除 ![ 和 ！[ 开头的图片 markdown 语法，避免图片链接被切断
                                    val sentenceRegex = Regex("[，。？~\\n]|[,?~\\n]|！(?!\\[)|!(?!\\[)")
                                    while (true) {
                                        val match = sentenceRegex.find(wechatSentenceBuffer) ?: break
                                        // 如果匹配到 !/！ 且在缓冲区末尾，可能后续还有 [（图片语法），等更多文本到达
                                        if ((match.value == "!" || match.value == "！") &&
                                            match.range.last == wechatSentenceBuffer.lastIndex
                                        ) {
                                            break
                                        }
                                        val sentenceEnd = match.range.last + 1
                                        val rawSentence = wechatSentenceBuffer.substring(0, sentenceEnd).trim()
                                        wechatSentenceBuffer.delete(0, sentenceEnd)

                                        // 去掉中英文逗号和中文句号（微信聊天更口语化，保留感叹号/问号等有情感的标点）
                                        val sentence = rawSentence
                                            .replace("，", "")
                                            .replace(",", "")
                                            .replace("。", "")
                                            .trim()
                                        if (sentence.isBlank()) continue

                                        // 逐句拆分 (语音消息) 标记：标记可出现在句首或句中
                                        val segments = splitByVoiceMarkers(sentence)
                                        for (segment in segments) {
                                            if (segment.text.isBlank()) continue

                                            // 标记强制转语音；否则按概率独立判定
                                            // 当语音合成服务关闭时，强制 toVoice=false，不执行 TTS
                                            val toVoice = if (!wechatVoiceEnabled) {
                                                false
                                            } else if (segment.forcedVoice) {
                                                true
                                            } else {
                                                val voiceRoll = Random.nextFloat()
                                                voiceRoll < WECHAT_VOICE_MESSAGE_PROBABILITY
                                            }
                                            Log.d(
                                                TAG,
                                                "WeChat voice check: forced=${segment.forcedVoice}, toVoice=$toVoice, sentence='${segment.text.take(30)}'"
                                            )

                                            // delay 节奏控制：基于上一句长度（与原 UI 延迟打字公式一致，第一句默认 500ms）
                                            val delayTime =
                                                (wechatLastSentenceLength * 200L + 100L).coerceIn(500L, 3000L)
                                            delay(delayTime)

                                            val isFirstSentence = wechatStoredSentenceCount == 0
                                            currentConversation = storeWechatSentenceNode(
                                                conversationId = conversationId,
                                                text = segment.text,
                                                isVoice = toVoice,
                                                isFirstSentence = isFirstSentence,
                                                nonTextParts = wechatFirstNodeNonTextParts,
                                                currentConversation = currentConversation,
                                                handledUnreadIds = unreadHandledNodeIds
                                            )
                                            wechatStoredSentenceCount++
                                            wechatLastSentenceLength = segment.text.length
                                        }
                                    }
                                }
                            } else {
                                // 非微信模式 或 无最终文本：原同步逻辑
                                // 微信模式下通过 forWechatSync 去重：
                                // - 含 Text 的 ASSISTANT 已被分句存储，只保留 nonTextParts
                                // - 仅含思考过程 + 空白文本的流式 AI 消息被过滤
                                val messagesToSync = if (wechatMode) {
                                    newMessages.forWechatSync()
                                } else newMessages

                                // 保留唯一的一次 update：updateCurrentMessages 内部既会创建新节点
                                // （anyNewMessages=true 的场景）也会增量更新已存在节点的内容。
                                // 连续 update 两次反而会让下游 StateFlow/distinctUntilChanged 可能
                                // 合并中间的流式 delta，造成用户侧感知"整段跳出来"。
                                if (messagesToSync.isNotEmpty()) {
                                    val toUpdate = baseMessages + messagesToSync
                                    val nextState = conversationSnapshot.updateCurrentMessages(toUpdate)
                                        .copy(chatSuggestions = emptyList())
                                    currentConversation = nextState
                                    updateConversation(conversationId) { nextState }

                                    // ===== 增量节流持久化（修复「中断/退出页面后已流式显示的内容丢失」）=====
                                    // 根因：原实现生成期零持久化（仅用户消息与整轮结束两次写库），TTS 等长耗时
                                    // 工具阻塞期间 partial 只存在于内存，退出页面重进即丢失。
                                    // 策略：新节点出现（ToolCall/ToolResult 边界）立即落库；纯流式文本按间隔节流；
                                    // 保存协程在 per-conversation persistenceMutex 内读最新快照，防旧覆盖新。
                                    val changedNodeIds = nextState.messageNodes
                                        .filter { node -> messagesToSync.any { m -> node.messages.any { it.id == m.id } } }
                                        .map { it.id }
                                        .toSet()
                                    val isToolBoundary = newMessages.any { it.role == MessageRole.TOOL }
                                    val nowTs = System.currentTimeMillis()
                                    val shouldPersistNow =
                                        anyNewMessages || isToolBoundary ||
                                            nowTs - lastIncrementalSaveAt >= INCREMENTAL_SAVE_INTERVAL_MS
                                    if (shouldPersistNow && changedNodeIds.isNotEmpty()) {
                                        lastIncrementalSaveAt = nowTs
                                        launchIncrementalNodeSave(conversationId, changedNodeIds)
                                    }
                                }
                            }

                        }
                    }

                    // 微信模式标记触发：流式结束后，整条回复合成 TTS 存为语音条
                    // 注意：用户打断时 collect 抛 CancellationException，不会执行到这里
                    if (wechatMode && wechatMarkerTriggered && wechatFullReplyBuffer.isNotBlank()) {
                        val fullReplyText = wechatFullReplyBuffer.toString().trim()
                        if (fullReplyText.isNotBlank()) {
                            // 模拟打字延迟
                            val delayTime = (wechatLastSentenceLength * 200L + 100L).coerceIn(500L, 3000L)
                            delay(delayTime)

                            // 语音合成服务关闭时跳过 TTS 合成，直接以文本存储
                            val ttsSetting = TtsVoiceResolver.resolveForAssistant(
                                settings, getConversationAssistant(conversationId)
                            ).providerSetting
                            val saved = if (wechatVoiceEnabled) {
                                val response = ttsSetting?.let { synthesizeTextToResponse(fullReplyText, it) }
                                response?.let { resp ->
                                    if (resp.audioData.isNotEmpty()) {
                                        saveVoiceMessageAudio(resp.audioData, resp.format, resp.sampleRate)
                                    } else null
                                }
                            } else null

                            if (saved != null) {
                                val (fileUri, durationMs) = saved
                                val audioPart = UIMessagePart.Audio(
                                    url = fileUri.toString(),
                                    metadata = buildJsonObject {
                                        put(AudioToTextTransformer.METADATA_DURATION_MS, durationMs)
                                        put(AudioToTextTransformer.METADATA_TRANSCRIPTION,
                                            "$WECHAT_VOICE_MESSAGE_MARKER$fullReplyText")
                                    }
                                )
                                val parts = (wechatFirstNodeNonTextParts ?: emptyList()) + audioPart
                                val newMsg = UIMessage(role = MessageRole.ASSISTANT, parts = parts)
                                val newNode = newMsg.toMessageNode(conversationId)
                                currentConversation = currentConversation.copy(
                                    messageNodes = currentConversation.messageNodes + newNode
                                )
                                updateConversation(conversationId, normalizeNodes = true) { currentConversation }
                                // 整条语音条未读决策：与落库同一事务写入（用户正在查看则视为已读跳过）
                                val unreadNodes = decideUnreadForNewNodes(
                                    conversationId = conversationId,
                                    nodes = listOf(newNode),
                                    handledIds = unreadHandledNodeIds
                                )
                                mutateConversationAndSave(conversationId, unreadNodes = unreadNodes) { current -> current }
                                sendWechatSentenceNotification(conversationId)
                                wechatStoredSentenceCount++
                                Log.d(TAG, "WeChat voice full reply: ${fullReplyText.take(30)}... duration=${durationMs}ms")
                            } else {
                                // TTS 失败回退文本 / 语音合成服务已关闭
                                if (!wechatVoiceEnabled) {
                                    Log.d(TAG, "WeChat voice disabled, store full reply as text")
                                } else {
                                    Log.w(TAG, "WeChat voice TTS failed (full reply), fallback to text")
                                }
                                val parts = (wechatFirstNodeNonTextParts ?: emptyList()) +
                                    UIMessagePart.Text(fullReplyText)
                                val newMsg = UIMessage(role = MessageRole.ASSISTANT, parts = parts)
                                val newNode = newMsg.toMessageNode(conversationId)
                                currentConversation = currentConversation.copy(
                                    messageNodes = currentConversation.messageNodes + newNode
                                )
                                updateConversation(conversationId, normalizeNodes = true) { currentConversation }
                                // TTS 失败回退文本未读决策：与落库同一事务写入（用户正在查看则视为已读跳过）
                                val unreadNodes = decideUnreadForNewNodes(
                                    conversationId = conversationId,
                                    nodes = listOf(newNode),
                                    handledIds = unreadHandledNodeIds
                                )
                                mutateConversationAndSave(conversationId, unreadNodes = unreadNodes) { current -> current }
                                sendWechatSentenceNotification(conversationId)
                                wechatStoredSentenceCount++
                            }
                        }
                        wechatFullReplyBuffer.clear()
                    } else if (wechatMode && wechatSentenceBuffer.isNotEmpty()) {
                    // 微信模式：流式结束后处理缓冲区中剩余的未成句文本（作为最后一句存入）
                    // 注意：用户打断时 collect 抛 CancellationException，不会执行到这里，符合"未存的不继续"
                        // 去掉中英文逗号和中文句号
                        val lastSentence = wechatSentenceBuffer.toString()
                            .replace("，", "")
                            .replace(",", "")
                            .replace("。", "")
                            .trim()
                        if (lastSentence.isNotBlank()) {
                            // 按 (语音消息) 标记拆分剩余文本，逐段存储
                            val segments = splitByVoiceMarkers(lastSentence)
                            for (segment in segments) {
                                if (segment.text.isBlank()) continue

                                val toVoice = if (!wechatVoiceEnabled) {
                                    false
                                } else if (segment.forcedVoice) {
                                    true
                                } else {
                                    val voiceRoll = Random.nextFloat()
                                    voiceRoll < WECHAT_VOICE_MESSAGE_PROBABILITY
                                }
                                Log.d(
                                    TAG,
                                    "WeChat voice check (flush): forced=${segment.forcedVoice}, toVoice=$toVoice, sentence='${segment.text.take(30)}'"
                                )

                                val delayTime =
                                    (wechatLastSentenceLength * 200L + 100L).coerceIn(500L, 3000L)
                                delay(delayTime)

                                val isFirstSentence = wechatStoredSentenceCount == 0
                                currentConversation = storeWechatSentenceNode(
                                    conversationId = conversationId,
                                    text = segment.text,
                                    isVoice = toVoice,
                                    isFirstSentence = isFirstSentence,
                                    nonTextParts = wechatFirstNodeNonTextParts,
                                    currentConversation = currentConversation,
                                    handledUnreadIds = unreadHandledNodeIds
                                )
                                wechatStoredSentenceCount++
                                wechatLastSentenceLength = segment.text.length
                            }
                        }
                        wechatSentenceBuffer.clear()
                    }

                    // 微信模式：所有分句已存储完毕并显示给用户，后续的 usage 迁移和数据库保存
                    // 只是内部处理，不应让用户继续等待"正在输入"指示器
                    if (wechatMode) {
                        _isAiTypingMap.update { it - conversationId }
                    }

                    // 微信模式：把原始 AI 消息的 usage 和上下文来源元数据迁移到最后一个分句节点
                    // 分句存储时新建了 UIMessage 节点，未继承原始 AI 消息的 usage/usedMemories 等字段
                    // 这里补齐，使切换回普通模式后能正常显示 token 消耗和上下文来源
                    if (wechatMode) {
                        wechatOriginalAISnapshot?.let { snapshot ->
                            val reasonUsageMissing = snapshot.usage != null
                            val reasonContextSourcesMissing = snapshot.usedLorebookEntries != null ||
                                snapshot.usedModes != null ||
                                snapshot.usedMemories != null
                            val hasMetadata = reasonUsageMissing || reasonContextSourcesMissing
                            if (hasMetadata) {
                                val nodes = currentConversation.messageNodes
                                val lastAssistantIdx = nodes.indexOfLast { node ->
                                    node.messages.any { it.role == MessageRole.ASSISTANT }
                                }
                                if (lastAssistantIdx >= 0) {
                                    currentConversation = currentConversation.copy(
                                        messageNodes = nodes.mapIndexed { idx, node ->
                                            if (idx == lastAssistantIdx) {
                                                node.copy(messages = node.messages.map { msg ->
                                                    if (msg.role == MessageRole.ASSISTANT) {
                                                        msg.copy(
                                                            usage = snapshot.usage,
                                                            usedLorebookEntries = snapshot.usedLorebookEntries,
                                                            usedModes = snapshot.usedModes,
                                                            usedMemories = snapshot.usedMemories,
                                                            modelId = snapshot.modelId
                                                        )
                                                    } else msg
                                                })
                                            } else node
                                        }
                                    )
                                    updateConversation(conversationId) { currentConversation }
                                }
                            }
                        }
                    }

            }.onFailure { e ->
                val isCancellation = e is kotlinx.coroutines.CancellationException

                // ===== 取消（用户点打断/停止生成 / 微信模式用户抢发新消息）时的兜底 =====
                // 根因：runCatching 主体在 `.collect { ... }` 抛出 CancellationException 后，
                // collect 之后的"微信 flush + usage 迁移 + mutateConversationAndSave 入库"
                // 都不会执行。修复前 onFailure 对 CancellationException 直接 return，
                // 导致普通模式下已流式显示给用户的 partial text 没写进 DB，UI 刷新就丢消息。
                //
                // 这里做两件事，**并且严格保留微信模式原有的"未打字文本丢弃"语义**：
                //   A) 刷新 currentConversation 为 Flow 的最新内存态（保证拿到 onCompletion
                //      中 finishReasoning() 写回的"已结束生成"状态）。
                //      注意：微信模式下不主动 flush wechatSentenceBuffer，保留原作者注释
                //      "collect 抛 CancellationException，未存的不继续" 的语义——
                //      用户在打字动画还没打完时抢先发下一条，"还没打出来的后半句"直接丢弃。
                //   B) 统一调用 mutateConversationAndSave 把内存快照入库。
                //      对普通模式 = 修复丢消息。
                //      对微信模式 = 只入库已经"逐句存过的完整句"，缓冲区尾巴丢弃（与修改前一致）。
                if (isCancellation) {
                    val latestSettings = settingsStore.settingsFlow.value
                    val latestConvSnapshot = getConversationFlow(conversationId).value
                        .takeIf { it.id == conversationId } ?: currentConversation
                    currentConversation = latestConvSnapshot
                    val latestAssistant = assistantOverride
                        ?: latestSettings.getAssistantById(currentConversation.assistantId)
                        ?: latestSettings.getCurrentAssistant()
                    val wechatModeNow = latestSettings.getEffectiveDisplaySetting(latestAssistant).wechatMode

                    // 微信模式：usage / 上下文来源迁移（若 generateText 的 onCompletion 已在
                    // wechatOriginalAISnapshot 里回传了这些元数据）。
                    // 这一步只更新"已经逐句存为节点的那些分句"的元信息，不触碰分句缓冲，
                    // 也不改变"丢弃未打字文本"的用户可见行为。
                    if (wechatModeNow) {
                        wechatOriginalAISnapshot?.let { snapshot ->
                            val hasUsage = snapshot.usage != null
                            val hasContextSources = snapshot.usedLorebookEntries != null ||
                                snapshot.usedModes != null ||
                                snapshot.usedMemories != null
                            if (hasUsage || hasContextSources) {
                                val nodes = currentConversation.messageNodes
                                val lastAssistantIdx = nodes.indexOfLast { node ->
                                    node.messages.any { it.role == MessageRole.ASSISTANT }
                                }
                                if (lastAssistantIdx >= 0) {
                                    currentConversation = currentConversation.copy(
                                        messageNodes = nodes.mapIndexed { idx, node ->
                                            if (idx == lastAssistantIdx) {
                                                node.copy(messages = node.messages.map { msg ->
                                                    if (msg.role == MessageRole.ASSISTANT) {
                                                        msg.copy(
                                                            usage = snapshot.usage,
                                                            usedLorebookEntries = snapshot.usedLorebookEntries,
                                                            usedModes = snapshot.usedModes,
                                                            usedMemories = snapshot.usedMemories,
                                                            modelId = snapshot.modelId
                                                        )
                                                    } else msg
                                                })
                                            } else node
                                        }
                                    )
                                    updateConversation(conversationId) { currentConversation }
                                }
                            }
                        }
                    }
                }

                // 生成结束（异常或用户主动取消）时，若目标占位消息仍为空，填充提示文本。
                // 这样消息气泡与下方的操作按钮（重新生成/版本切换）才能正常显示，
                // 用户可切换查看历史版本，或继续点击重新生成。
                if (!isCancellation) {
                    val friendlyError = translateError(e)
                    _errorFlow.emit(friendlyError)
                    Logging.log(TAG, "handleMessageComplete: $friendlyError")
                }

                placeholderId?.let { targetMsgId ->
                    val isEmpty = currentConversation.messageNodes.any { node ->
                        node.messages.any { msg ->
                            msg.id == targetMsgId && msg.parts.isEmpty()
                        }
                    }
                    if (isEmpty) {
                        val placeholderText = if (isCancellation) {
                            context.getString(R.string.regenerate_cancelled_placeholder)
                        } else {
                            context.getString(R.string.regenerate_failed_placeholder)
                        }
                        val updatedNodes = currentConversation.messageNodes.map { node ->
                            val msgIdx = node.messages.indexOfFirst { it.id == targetMsgId }
                            if (msgIdx == -1) return@map node
                            val targetMsg = node.messages[msgIdx]
                            node.copy(
                                messages = node.messages.toMutableList().also { list ->
                                    list[msgIdx] = targetMsg.copy(
                                        parts = listOf(UIMessagePart.Text(placeholderText))
                                    )
                                }
                            )
                        }
                        currentConversation = currentConversation.copy(messageNodes = updatedNodes)
                        updateConversation(conversationId) { currentConversation }
                    }
                }

                // 统一持久化当前内存快照到 DB（取消/异常两种出口都走这里）。
                // 快照修复：同步捕获局部 currentConversation（Conversation/UIMessage 均为不可变 data class，
                // 捕获引用即冻结快照），再异步直存捕获对象——不再经 mutateConversationAndSave 的
                // ensureConversationLoaded 从 DB 重载。若生成期间内存被逐出（中断后 500ms 的引用检查），
                // 旧实现会保存"无 partial 的 DB 快照"导致已流式内容彻底丢失；现直存捕获快照消除该竞态。
                // 保存仍走 persistenceMutex 串行，防止打断后用户立即发新消息造成旧覆盖新。
                val finalConv = currentConversation
                val interruptedNodesChanged = if (isCancellation) {
                    // 中断语义：仅停止生成，保留已生成内容；对最后一条流式 ASSISTANT 消息标记 interrupted
                    //（随消息 JSON 持久化，重进会话显示「已停止」，不会被误识别为生成中）
                    val marked = finalConv.markLastStreamingAssistantInterrupted()
                    if (marked != finalConv) {
                        currentConversation = marked
                        updateConversation(conversationId) { marked }
                    }
                    marked
                } else {
                    finalConv
                }
                appScope.launch {
                    val mutex = persistenceMutexes.computeIfAbsent(conversationId) { Mutex() }
                    mutex.withLock {
                        // 保存同步捕获的快照（异常路径 interrupted 标记已更新在快照内）
                        // 临时会话禁止落库。必须用墓碑判定：本协程执行时点在 destroyTemporarySession
                        // 的 remove() 之后，isTemporary 已归 false，只查它会把临时消息落库成幽灵会话
                        if (!isTemporaryOrDestroyed(conversationId)) {
                            runCatching { conversationRepo.saveConversation(interruptedNodesChanged) }
                                .onFailure { Log.e(TAG, "中断/异常兜底保存失败: conv=$conversationId", it) }
                        }
                    }
                    if (!isTemporaryOrDestroyed(conversationId)) {
                        settingsStore.update { settings ->
                            settings.copy(
                                assistants = settings.assistants.map {
                                    if (it.id == finalConv.assistantId) it.copy(lastConversationId = conversationId.toString()) else it
                                }
                            )
                        }
                    }
                }
            }.onSuccess {
                if (!currentJob.isActive) return@onSuccess
                val finalConv = currentConversation
                // 未读汇总决策：本轮新增的可见 ASSISTANT 节点（微信模式逐句已决策的节点已被
                // unreadHandledNodeIds 排除）。用户正在查看该会话 → 全部视为已读不写入；
                // 否则与最终落库同一事务写入（saveConversationWithUnread），消息保存与未读
                // 记录原子生效。INSERT IGNORE 幂等，重复完成回调不会重复计数。
                val isViewingConversation = isConversationBeingViewed(conversationId)
                val unreadCandidates = if (!isTemporaryOrDestroyed(conversationId) && !isViewingConversation) {
                    UnreadRules.pickUnreadCandidates(
                        conversation = finalConv,
                        baselineVisibleNodeIds = baselineVisibleNodeIds,
                        excludeNodeIds = unreadHandledNodeIds
                    )
                } else emptyList()
                mutateConversationAndSave(conversationId, unreadNodes = unreadCandidates) { current -> current }
                // 本轮记账：多步工具调用循环中每步 usage 挂在当步 assistant 消息上，
                // 对新增 id 的消息逐条求和（跨步骤累计必须用 sum，禁止 merge 的非零覆盖语义）
                val roundUsage = finalConv.currentMessages
                    .filter { msg ->
                        val isNewMessage = msg.id !in priorAssistantUsages
                        val isAssistant = msg.role == MessageRole.ASSISTANT
                        isAssistant && isNewMessage
                    }
                    .mapNotNull { it.usage }
                    .sumUsage()
                if (roundUsage.promptTokens > 0 || roundUsage.completionTokens > 0) {
                    appScope.launch {
                        conversationRepo.recordTokenUsage(finalConv.assistantId.toString(), roundUsage)
                    }
                }
                appScope.launch {
                    // onSuccess 执行时点可能晚于 destroyTemporarySession（生成完成瞬间退出临时会话），
                    // 同样必须用墓碑判定，禁止把 lastConversationId 写成已销毁临时会话 id
                    if (!isTemporaryOrDestroyed(conversationId)) {
                        settingsStore.update { settings ->
                            settings.copy(
                                assistants = settings.assistants.map {
                                    if (it.id == finalConv.assistantId) it.copy(lastConversationId = conversationId.toString()) else it
                                }
                            )
                        }
                    }
                }
                addConversationReference(conversationId)
                appScope.launch {
                    coroutineScope {
                        launch { generateSuggestion(conversationId, finalConv) }
                        // 通话中的会话跳过自动 L1 摘要, 由 VoiceCallManager 的 25 分钟定时器驱动
                        if (!callModeConversations.contains(conversationId)) {
                            launch { checkAndAutoSummarize(conversationId, finalConv, settings) }
                        }
                    }
                }.invokeOnCompletion { removeConversationReference(conversationId) }
            }
        }

        // 生成轮次结束钩子（成功/失败/取消三路径统一）：消化该会话积压的延后回调
        // （任务完成时会话忙而延后的 PENDING）与 INJECTED 恢复（崩溃后未完成的回调轮次）
        runCatching { backgroundTaskManager.dispatchCallbacks(conversationId) }
    }

    /**
     * 将文本通过 TTS 合成为音频 [TTSResponse]。
     * 供微信模式语音消息功能使用：整条回复合成后存为语音条。
     */
    private suspend fun synthesizeTextToResponse(
        text: String,
        setting: TTSProviderSetting
    ): TTSResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val flow = ttsManager.generateSpeech(setting, TTSRequest(text = text))
            var format: AudioFormat? = null
            var sampleRate: Int? = null
            val output = ByteArrayOutputStream()
            flow.collect { chunk ->
                if (format == null) format = chunk.format
                if (sampleRate == null) sampleRate = chunk.sampleRate
                output.write(chunk.data)
            }
            TTSResponse(
                audioData = output.toByteArray(),
                format = format ?: AudioFormat.MP3,
                sampleRate = sampleRate
            )
        }.getOrNull()
    }

    /**
     * 将 TTS 合成的音频字节保存为语音消息文件，返回 (fileUri, durationMs)。
     * PCM 格式会被包装为 WAV 容器以保证 ExoPlayer 兼容性。
     */
    private suspend fun saveVoiceMessageAudio(
        audioData: ByteArray,
        format: AudioFormat,
        sampleRate: Int?
    ): Pair<Uri, Long>? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "voice_messages").apply { if (!exists()) mkdirs() }

        val (ext, finalData) = when (format) {
            AudioFormat.PCM -> {
                val sr = sampleRate ?: 24000
                "wav" to wrapPcmInWav(audioData, sr)
            }
            AudioFormat.WAV -> "wav" to audioData
            AudioFormat.MP3 -> "mp3" to audioData
            AudioFormat.OGG -> "ogg" to audioData
            AudioFormat.AAC -> "aac" to audioData
            AudioFormat.OPUS -> "opus" to audioData
        }

        val file = File(dir, "tts_${Uuid.random()}.$ext")
        file.writeBytes(finalData)

        val durationMs = runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            val dur = mp.duration.toLong()
            mp.release()
            dur
        }.getOrDefault(0L)

        Pair(Uri.fromFile(file), durationMs)
    }

    /**
     * 将裸 PCM 16-bit mono 数据包装为标准 WAV 文件（44 字节 header + PCM data）。
     */
    private fun wrapPcmInWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataLength = pcmData.size
        val chunkSize = 36 + dataLength

        val header = ByteArrayOutputStream(44)
        header.write("RIFF".toByteArray())
        header.write(intToLittleEndian(chunkSize))
        header.write("WAVE".toByteArray())
        header.write("fmt ".toByteArray())
        header.write(intToLittleEndian(16))
        header.write(shortToLittleEndian(1))
        header.write(shortToLittleEndian(numChannels))
        header.write(intToLittleEndian(sampleRate))
        header.write(intToLittleEndian(byteRate))
        header.write(shortToLittleEndian(blockAlign))
        header.write(shortToLittleEndian(bitsPerSample))
        header.write("data".toByteArray())
        header.write(intToLittleEndian(dataLength))

        val result = ByteArrayOutputStream(44 + dataLength)
        result.write(header.toByteArray())
        result.write(pcmData)
        return result.toByteArray()
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    /**
     * 存储微信模式下的单个分句节点（语音或文本）。
     *
     * 语音分支会尝试 TTS 合成；合成失败时自动回退为文本节点。
     * 非文本 parts（如工具调用图标）仅在 [isFirstSentence] 为 true 时附加到节点前面。
     *
     * @return 更新后的 Conversation，便于调用方刷新本地 currentConversation 变量。
     */
    /**
     * 为生成过程中新持久化的节点做未读决策（微信模式逐句路径 / 整条语音回复路径）。
     *
     * 决策结果（无论是否实际写入）登记进 [handledIds]，防止 onSuccess 汇总时对同一批
     * 节点重复决策（重复插入或覆盖用户中途查看产生的已读状态）。
     * 返回应与本次落库同事务写入的未读记录：用户正在查看该会话时为空（视为已读），
     * 临时会话为空（永不落库）。
     */
    private fun decideUnreadForNewNodes(
        conversationId: Uuid,
        nodes: List<MessageNode>,
        handledIds: MutableSet<Uuid>
    ): List<ChatUnreadMessageEntity> {
        val candidates = nodes.filter { UnreadRules.isVisibleAssistantNode(it) }
        if (candidates.isEmpty()) return emptyList()
        handledIds += candidates.map { it.id }
        if (isTemporaryOrDestroyed(conversationId)) return emptyList()
        if (isConversationBeingViewed(conversationId)) return emptyList()
        return candidates.map { UnreadRules.toUnreadEntity(it, conversationId) }
    }

    /**
     * 为不经生成链路（handleMessageComplete）直接落库的 ASSISTANT 节点做未读决策，
     * 返回应与落库同事务写入的未读记录（调用方经 mutateConversationAndSave 的
     * unreadNodes 参数传入）。用户正在查看该会话 → 空列表（视为已读）；
     * 临时会话 → 空列表（永不落库）。典型调用方：自发消息 SpontaneousWorker。
     */
    fun decideUnreadForExternalNodes(
        conversationId: Uuid,
        nodes: List<MessageNode>
    ): List<ChatUnreadMessageEntity> = decideUnreadForNewNodes(
        conversationId = conversationId,
        nodes = nodes,
        handledIds = mutableSetOf()
    )

    private suspend fun storeWechatSentenceNode(
        conversationId: Uuid,
        text: String,
        isVoice: Boolean,
        isFirstSentence: Boolean,
        nonTextParts: List<UIMessagePart>?,
        currentConversation: Conversation,
        handledUnreadIds: MutableSet<Uuid>
    ): Conversation {
        val prefixParts = if (isFirstSentence && nonTextParts != null) nonTextParts else emptyList()

        val parts: List<UIMessagePart> = if (isVoice) {
            val ttsSetting = TtsVoiceResolver.resolveForAssistant(
                settingsStore.settingsFlow.value, getConversationAssistant(conversationId)
            ).providerSetting
            val response = ttsSetting?.let { synthesizeTextToResponse(text, it) }
            val saved = response?.let { resp ->
                if (resp.audioData.isNotEmpty()) {
                    saveVoiceMessageAudio(resp.audioData, resp.format, resp.sampleRate)
                } else null
            }
            if (saved != null) {
                val (fileUri, durationMs) = saved
                val audioPart = UIMessagePart.Audio(
                    url = fileUri.toString(),
                    metadata = buildJsonObject {
                        put(AudioToTextTransformer.METADATA_DURATION_MS, durationMs)
                        put(
                            AudioToTextTransformer.METADATA_TRANSCRIPTION,
                            "$WECHAT_VOICE_MESSAGE_MARKER$text"
                        )
                    }
                )
                Log.d(TAG, "WeChat voice sentence: ${text.take(30)}... duration=${durationMs}ms")
                prefixParts + audioPart
            } else {
                Log.w(TAG, "WeChat voice TTS failed, fallback to text")
                prefixParts + UIMessagePart.Text(text)
            }
        } else {
            prefixParts + UIMessagePart.Text(text)
        }

        val newMsg = UIMessage(role = MessageRole.ASSISTANT, parts = parts)
        val newNode = newMsg.toMessageNode(conversationId)
        val updated = currentConversation.copy(
            messageNodes = currentConversation.messageNodes + newNode
        )
        updateConversation(conversationId, normalizeNodes = true) { updated }
        // 微信模式逐句未读决策：与该句落库同一事务写入（用户正在查看则视为已读跳过）
        val unreadNodes = decideUnreadForNewNodes(
            conversationId = conversationId,
            nodes = listOf(newNode),
            handledIds = handledUnreadIds
        )
        mutateConversationAndSave(conversationId, unreadNodes = unreadNodes) { it }
        sendWechatSentenceNotification(conversationId)
        return updated
    }

    /**
     * 微信模式下同步消息到会话时的去重处理。
     *
     * 含 Text 的 ASSISTANT 消息已通过分句存储为独立节点（每句一个新 ID 的 UIMessage），
     * 这里只保留其 nonTextParts（如 ToolCall），避免 Text 被重复存储为完整节点。
     *
     * 同时过滤掉"仅含思考过程 + 空白文本"的流式 AI 消息，
     * 避免推理阶段保存的节点与后续分句节点重复存储思考内容。
     */
    private fun List<UIMessage>.forWechatSync(): List<UIMessage> = mapNotNull { msg ->
        when {
            // 非 ASSISTANT 消息（如 TOOL result、USER）原样保留
            msg.role != MessageRole.ASSISTANT -> msg

            // 含 Text 的 ASSISTANT：Text 已被分句存储，只保留 nonTextParts（ToolCall 等）
            msg.toContentText().isNotBlank() -> {
                val nonText = msg.parts.filter {
                    it !is UIMessagePart.Text &&
                        it !is UIMessagePart.Reasoning &&
                        it !is UIMessagePart.Thinking
                }
                if (nonText.isEmpty()) null else msg.copy(parts = nonText)
            }

            // 含 ToolCall 但无 Text 的 ASSISTANT：按完整消息同步（ToolCall 需与 ToolResult 配对）
            msg.parts.any { it is UIMessagePart.ToolCall } -> msg

            // 仅含思考过程 + 空白文本：跳过
            else -> null
        }
    }

    private fun translateError(e: Throwable): Throwable {
        val message = e.message ?: ""
        return when {
            message.contains("Unexpected JSON token") && message.contains("< instead") -> {
                IllegalStateException(context.getString(R.string.api_error_html), e)
            }

            message.contains("400 Bad Request", ignoreCase = true) -> {
                IllegalStateException("请求参数错误 (400)，请确认 API Key 或模型名称。", e)
            }

            message.contains("401 Unauthorized", ignoreCase = true) -> {
                IllegalStateException(context.getString(R.string.api_error_401), e)
            }

            message.contains("404 Not Found", ignoreCase = true) -> {
                IllegalStateException(context.getString(R.string.api_error_404), e)
            }

            // 智谱 GLM 等纯文本模型不支持多模态输入时, API 会返回 content.type 参数非法错误
            // 典型错误信息: "message.content.type 参数非法，取值范围['text']"
            message.contains("content", ignoreCase = true) &&
                message.contains("type", ignoreCase = true) &&
                message.contains("text", ignoreCase = true) &&
                (message.contains("非法") || message.contains("invalid")) -> {
                IllegalStateException("该模型只支持文字输入，请前往模型设置关闭图片/音频输入模态。", e)
            }

            // 网络层错误（连接被系统/代理掐断、重置、DNS 失败、连接超时等）：底层异常原文
            // （如 "Software caused connection abort"）对用户无意义，翻译为友好提示。
            // 瞬态中断的重试已在 GenerationHandler 内先行处理，走到这里的通常是重试后仍失败。
            e is java.net.UnknownHostException -> {
                java.io.IOException(context.getString(R.string.api_error_host_unreachable), e)
            }

            e is java.net.SocketTimeoutException -> {
                java.io.IOException(context.getString(R.string.api_error_connection_timeout), e)
            }

            isTransientNetworkError(e) -> {
                java.io.IOException(context.getString(R.string.api_error_network_interrupted), e)
            }

            else -> e
        }
    }

    private fun createSearchTool(
        settings: Settings,
        assistant: Assistant,
        providerIndex: Int? = null,
    ): Set<Tool> {
        val idx = providerIndex ?: settings.searchServiceSelected
        // 空结果重试守卫（本次生成请求内有效）：空结果时引导换关键词重搜，
        // 连续无结果达上限后不再实际执行搜索，直接要求 AI 终止搜索并告知用户，
        // 防止「一直搜一直搜」的空转循环消耗 Token（createSearchTool 每轮生成调用一次，
        // 闭包计数天然按请求隔离）。
        val searchRetryGuard = SearchRetryGuard()
        return buildSet {
            add(Tool(name = "search_web", description = "联网检索实时信息", parameters = {
                val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                SearchService.getService(opt).parameters
            }, execute = { jsonElement ->
                // 注意：这里不再硬性限制"每请求仅搜索一次"。
                // 异常重复调用由 GenerationHandler.ToolLoopGuard 统一保护
                // （工具调用轮次上限 + 相同工具/相同参数连续重复检测），
                // 换用不同关键词的连续搜索是合理行为，不应被拦截；
                // 连续空结果则由 searchRetryGuard 兜底终止。
                if (searchRetryGuard.shouldBlockSearch()) {
                    buildJsonObject {
                        put("error", JsonPrimitive(searchRetryGuard.blockedMessage()))
                    }
                } else {
                    val opt = settings.searchServices.getOrElse(idx) { SearchServiceOptions.DEFAULT }
                    val resultSize = 6
                    val commonOptions = settings.searchCommonOptions.copy(resultSize = resultSize)
                    val input = jsonElement as? JsonObject ?: JsonObject(emptyMap())
                    val searchResult = SearchService.getService(opt).search(input, commonOptions, opt).getOrThrow()
                    searchResult.items.forEachIndexed { i, item ->
                        Log.v(
                            TAG,
                            "Raw Item [$i]: ${item.title} (${item.url})"
                        )
                    }
                    val htmlRegex = Regex("<[^>]*>")
                    val maxCharsPerItem = (12000 / resultSize.coerceAtLeast(1)).coerceIn(1500, 4000)
                    val cleanedItems = searchResult.items.take(resultSize).map { item ->
                        val cleanedText = item.text.replace(htmlRegex, "").trim()
                        item.copy(text = cleanedText.take(maxCharsPerItem))
                    }
                    if (cleanedItems.isEmpty()) {
                        buildJsonObject {
                            put("items", JsonArray(emptyList()))
                            put("results", JsonArray(emptyList()))
                            put("note", JsonPrimitive(searchRetryGuard.noteEmptyResult()))
                        }
                    } else {
                        searchRetryGuard.noteResultsFound()
                        buildJsonObject {
                            put("items", JsonArray(cleanedItems.map { item ->
                                buildJsonObject {
                                    put("title", item.title)
                                    put("url", item.url)
                                    put("text", item.text)
                                    put("link", item.url)
                                    put("snippet", item.text)
                                }
                            }))
                            put("results", JsonArray(cleanedItems.map { item ->
                                buildJsonObject {
                                    put("title", item.title)
                                    put("link", item.url)
                                    put("snippet", item.text)
                                }
                            }))
                        }
                    }
                }
            }, systemPrompt = { _, _ ->
                "## tool: search_web\n" +
                    "优先整合已有搜索结果作答；如果搜索结果已足够回答用户，无需再次搜索。" +
                    "确有必要时（如首次结果与目标明显不符、或需要不同角度的信息）可以换用不同关键词再次检索，" +
                    "但避免以完全相同的参数重复调用。" +
                    "若搜索无结果，请更换关键词后重新搜索试试；连续多次无结果时应停止搜索，" +
                    "直接告知用户未找到相关信息，不要无限重试。"
            }))
        }
    }

    /**
     * 【问题2 修复】自动触发 L1 总结阈值检查。
     * ✨ 不再受 200 条 LIMIT 的限制：使用 while 循环 + 复合游标分页持续拉取计数，
     *    直到达到 max 条核心对话（USER/ASSISTANT）或耗尽未总结消息。
     *    解决 tool 消息密集时"前 200 条 core 不足、阈值一直不触发"的卡滞问题。
     */
    private suspend fun checkAndAutoSummarize(id: Uuid, conv: Conversation, settings: Settings) {
        // 临时会话禁止进入自动总结（执行层守卫：除调用入口外，真正执行处再次校验）
        if (temporarySessions.isTemporary(id)) return
        val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
        if (!assistant.enableMemory || !assistant.enableDetailMemory) return
        // 熔断检查：连续失败次数达到阈值后，停止自动触发，避免无限重试循环。
        // 用户手动调用 summarizeAndRefresh 成功后会自动重置计数（在 try 块末尾）。
        if (conv.segmentFailureCount >= SEGMENT_FAILURE_THRESHOLD) {
            Log.w(TAG, "Segment auto-summarize skipped for $id: circuit-broken (failed ${conv.segmentFailureCount} times in a row)")
            return
        }
        val wechatMode = settings.getEffectiveDisplaySetting(assistant).wechatMode
        val max = if (wechatMode) (assistant.detailMemoryThreshold * 2).toInt() else assistant.detailMemoryThreshold

        // 自动触发时排除"最后一条可刷新消息"：先用游标确定上界，避免统计时把最后一条算进去
        val upperCursor = conversationRepo.getLatestSelectedMessageCursor(id.toString())
        if (upperCursor == null) return
        val upperTime = upperCursor.createdAt
        val upperId = upperCursor.id

        // 使用复合游标分批拉取，循环统计核心消息数，直到触达阈值或数据耗尽
        var cursorTime = conv.lastSummarizedMessageTime
        var cursorId = conv.lastSummarizedMessageId
        var coreMessageCount = 0

        while (coreMessageCount < max) {
            val batch = conversationRepo.getMessagesForSegmentSummary(
                convId = id.toString(),
                lastSummarizedTime = cursorTime,
                lastSummarizedId = cursorId,
                limit = 200,
                upperTime = upperTime,
                upperId = upperId
            )
            if (batch.isEmpty()) break
            batch.forEach { entity ->
                val uiMsg = decodeUIMessageOrNull(entity.contentJson, tagExtra = "checkSummary:$id")
                if (uiMsg != null && (uiMsg.role == MessageRole.USER || uiMsg.role == MessageRole.ASSISTANT)) {
                    coreMessageCount++
                }
            }
            val last = batch.last()
            cursorTime = last.createdAt
            cursorId = last.id
            if (batch.size < 200) break
        }

        if (coreMessageCount >= max) summarizeAndRefresh(id, skipArchive = true, excludeLatestMessage = true)
    }
    @SuppressLint("SuspiciousIndentation")
    suspend fun summarizeAndRefresh(
        id: Uuid, onlySegments: Boolean = false, skipArchive: Boolean = false,
        excludeLatestMessage: Boolean = false
    ): ContextRefreshResult = withContext(Dispatchers.IO) {
        // 临时会话禁止总结/Segments（执行层守卫：同时覆盖「立即整合」「刷新上下文」等所有入口）
        if (temporarySessions.isTemporary(id)) {
            return@withContext ContextRefreshResult(false, errorMessage = "临时会话不支持整合操作")
        }
        if (!summarizingConversations.add(id)) {
            return@withContext ContextRefreshResult(false, errorMessage = "正在总结中...请勿重复操作")
        }
        var totalSummarized = 0
        try {
            // 自动触发（excludeLatestMessage=true）时，计算最后一条选中版本消息的游标作为上界，
            // 留出"可刷新的最后一条"给下一轮归档，避免 segment 已包含旧版本却已越过时间锚点。
            val upperCursor = if (excludeLatestMessage) {
                conversationRepo.getLatestSelectedMessageCursor(id.toString())
            } else null
            // 游标为空说明会话无可见消息，直接结束
            if (excludeLatestMessage && upperCursor == null) {
                return@withContext ContextRefreshResult(true, "No messages to summarize")
            }
            while (true) {
                val settings = settingsStore.settingsFlow.first()
                val conv = conversationRepo.getConversationById(id) ?: break
                val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
                // 【问题1 修复】使用「时间戳 + 消息 ID」复合游标分页，避免同毫秒 created_at 组边界遗漏
                // 【版本污染修复】DAO 层 JOIN 节点表按 order_index = select_index 只取当前选中版本
                // 【归档范围修复】excludeLatestMessage=true 时传入 upperTime/upperId 排除最后一条
                val toSummarizeEntities = conversationRepo.getMessagesForSegmentSummary(
                    convId = id.toString(),
                    lastSummarizedTime = conv.lastSummarizedMessageTime,
                    lastSummarizedId = conv.lastSummarizedMessageId,
                    limit = 100,
                    upperTime = upperCursor?.createdAt ?: 0L,
                    upperId = upperCursor?.id ?: ""
                )
                if (toSummarizeEntities.size < 2) break
                val lastMsg = toSummarizeEntities.last()
                val lastMsgTime = lastMsg.createdAt
                val lastMsgId = lastMsg.id
                val modelId = assistant.summarizerModelId ?: settings.summarizerModelId
                val model = settings.findModelById(modelId)
                    ?: assistant.chatModelId?.let { settings.findModelById(it) }
                    ?: settings.getCurrentChatModel()
                    ?: return@withContext ContextRefreshResult(false, errorMessage = "没有找到可用模型")
                val provider = model.findProvider(settings.providers) ?: return@withContext ContextRefreshResult(false)
                val handler = providerManager.getProviderByType(provider)
                // 【问题4 修复】使用 summarizeContentLine 解码，损坏消息输出占位兜底而不是静默跳过
                val text = StringBuilder().apply {
                    toSummarizeEntities.forEach { entity ->
                        val line = summarizeContentLine(entity, tagExtra = "summarize:$id")
                            ?: return@forEach
                        append(line).append("\n")
                    }
                }.toString()
                if (text.isBlank()) {
                    // 如果这一批全是 tool 消息 / 空内容，虽然没有核心内容可总结，
                    // 但我们仍然需要推进复合游标，否则会卡在这里死循环
                    mutateConversationAndSave(id) { current ->
                        current.copy(
                            lastSummarizedMessageTime = lastMsgTime,
                            lastSummarizedMessageId = lastMsgId
                        )
                    }
                    totalSummarized += toSummarizeEntities.size
                    if (toSummarizeEntities.size < 100) break
                    continue
                }
                val locale = Locale.getDefault().displayName
                val tempPrompt = fillPrompt(
                    DEFAULT_TEMP_SUMMARY_PROMPT_TEMPLATE, mapOf(
                        "guidelines" to settings.tempSummaryGuidelines,
                        "new_messages" to text,
                        "locale" to locale,
                        "char" to assistant.name
                    )
                )
                val providerHandler = handler as Provider<ProviderSetting>
                val summarizeTimeoutMs = settings.chatGenerationTimeoutMinutes * 60 * 1000L
                val tempResp = withTimeoutOrNull(summarizeTimeoutMs) {
                    providerHandler.generateText(
                        provider,
                        listOf(UIMessage.user(tempPrompt)),
                        TextGenerationParams(model, 0.3f, 1.0f, thinkingBudget = 0)
                    )
                }
                if (tempResp == null) {
                    Log.w(TAG, "L1 segment summarization timed out after ${settings.chatGenerationTimeoutMinutes} minutes")
                    break
                }
                tempResp.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
                val aiResponse = tempResp.choices.firstOrNull()?.message?.toContentText() ?: ""
                if (aiResponse.isNotBlank()) {
                    val backgroundRegex = Regex("""\[(?:Background|背景)][:：]?\s*(.*)""", RegexOption.IGNORE_CASE)
                    val keywordsRegex = Regex("""\[(?:Keywords|关键词)][:：]?\s*(.*)""", RegexOption.IGNORE_CASE)
                    val backgroundMatch = backgroundRegex.find(aiResponse)?.groupValues?.get(1)?.trim()
                    val keywordsMatch = keywordsRegex.find(aiResponse)?.groupValues?.get(1)?.trim()
                    val finalBackground =
                        backgroundMatch ?: aiResponse.lines().firstOrNull { it.isNotBlank() && !it.startsWith("[") }
                        ?: aiResponse
                    val aiKeywords = keywordsMatch ?: ""
                    val fullContextualContent = """
                        [Background]: $finalBackground
                        [Original Text]:
                        $text
                    """.trimIndent()
                    val localKeywords = KeywordExtractor.extract(finalBackground)
                    val keywords = mergeKeywords(aiKeywords, localKeywords)
                    val embeddingResult = try {
                        embeddingService.embedWithModelId(fullContextualContent, assistant.id.toString())
                    } catch (e: Exception) {
                        null
                    }
                    val segment = ChatSegmentEntity(
                        assistantId = assistant.id.toString(),
                        conversationId = id.toString(),
                        content = finalBackground,
                        keywords = keywords,
                        startMessageIndex = -1,
                        endMessageIndex = -1,
                        startTime = toSummarizeEntities.first().createdAt,
                        endTime = lastMsgTime,
                        embedding = embeddingResult?.embeddings?.firstOrNull()?.let { VectorUtils.fromList(it) },
                        embeddingModelId = embeddingResult?.modelId
                    )
                    memoryRepository.saveSegment(segment)
                }
                mutateConversationAndSave(id) { current ->
                    current.copy(
                        lastSummarizedMessageTime = lastMsgTime,
                        lastSummarizedMessageId = lastMsgId,
                        lastRefreshTime = System.currentTimeMillis()
                    )
                }
                totalSummarized += toSummarizeEntities.size
                if (toSummarizeEntities.size < 100) break
            }
            if (!skipArchive) {
                archiveConversation(id, force = true, skipEmbedding = true)
            }
            // 成功完成：重置失败计数为 0，避免下次因历史失败被熔断
            mutateConversationAndSave(id) { current ->
                if (current.segmentFailureCount == 0) current
                else current.copy(segmentFailureCount = 0)
            }
            ContextRefreshResult(true, "Segments updated", totalSummarized)
        } catch (e: Exception) {
            Log.e(TAG, "summarizeAndRefresh failed for $id", e)
            // 失败：递增失败计数。达到阈值后 checkAndAutoSummarize 会停止自动触发，
            // 避免根本性问题（如 summarizer 模型不可用）下每次发消息都重试的循环。
            val currentCount = runCatching {
                conversationRepo.getConversationById(id)?.segmentFailureCount ?: 0
            }.getOrDefault(0)
            val newCount = currentCount + 1
            runCatching {
                mutateConversationAndSave(id) { current ->
                    current.copy(segmentFailureCount = newCount)
                }
            }
            if (newCount >= SEGMENT_FAILURE_THRESHOLD) {
                Log.w(TAG, "Segment summary has failed $newCount times in a row for $id, auto-trigger is now circuit-broken")
            }
            ContextRefreshResult(false, errorMessage = e.message)
        } finally {
            summarizingConversations.remove(id)
        }
    }


    private fun mergeKeywords(ai: String, local: String): String {
        val aiList = ai.split(Regex("[,，、；;]")).map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (aiList.isNotEmpty()) return aiList.distinct().joinToString(",")
        val localList = local.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return localList.distinct().joinToString(",")
    }

    /**
     * 截断当前 conversation 里"最新一条 ASSISTANT 消息"的文字内容。
     * 用于通话模式手动打断 AI 回复：只保留"已经 TTS 念过的前缀"，删掉没念完的尾巴。
     *
     * - 每条 UIMessage 里可能有多个 Text part：按它们在 parts 列表中的顺序
     *   逐个拼接文本长度进行截断。非 Text part（图片、附件等）原样保留。
     * - 如果 maxTextLen <= 0：删除整个消息体（为空 UIMessage.parts = emptyList()）。
     * - 如果 maxTextLen >= 总文字长度：什么都不做。
     */
    suspend fun truncateLastAssistantMessage(conversationId: Uuid, maxTextLen: Int) {
        if (maxTextLen < 0) return
        mutateConversationAndSave(conversationId) { conv ->
            val nodes = conv.messageNodes
            val idx = nodes.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (idx == -1) return@mutateConversationAndSave conv
            val oldNode = nodes[idx]
            val curMsg = oldNode.currentMessage
            if (curMsg.parts.isEmpty()) return@mutateConversationAndSave conv
            if (curMsg.role != MessageRole.ASSISTANT) return@mutateConversationAndSave conv

            val allTextLen = curMsg.parts.sumOf { (it as? UIMessagePart.Text)?.text?.length ?: 0 }
            if (maxTextLen >= allTextLen) return@mutateConversationAndSave conv

            val newParts = mutableListOf<UIMessagePart>()
            var remaining = maxTextLen
            for (part in curMsg.parts) {
                when (part) {
                    is UIMessagePart.Text -> {
                        if (remaining <= 0) continue
                        val t = part.text
                        if (t.length <= remaining) {
                            newParts.add(part); remaining -= t.length
                        } else {
                            val cut = t.substring(0, remaining.coerceAtMost(t.length))
                            newParts.add(part.copy(text = cut))
                            remaining = 0
                        }
                    }
                    else -> newParts.add(part)
                }
            }

            val newMsg = curMsg.copy(parts = newParts)
            val newNodeMessages = oldNode.messages.mapIndexed { i, m ->
                if (i == oldNode.selectIndex) newMsg else m
            }
            val newNode = oldNode.copy(messages = newNodeMessages)
            conv.copy(messageNodes = nodes.toMutableList().apply { set(idx, newNode) })
        }
    }

    /**
     * 中断标记：对最后一条流式中的 ASSISTANT 消息（最后一个含 ASSISTANT 的节点的当前选中消息）
     * 置 interrupted=true。不可变快照上的纯变换；节点/消息无匹配时原样返回。
     */
    private fun Conversation.markLastStreamingAssistantInterrupted(): Conversation {
        val nodes = messageNodes
        val lastAssistantNodeIndex = nodes.indexOfLast { it.currentMessage.role == MessageRole.ASSISTANT }
        if (lastAssistantNodeIndex < 0) return this
        val hasMarked = nodes.indexOfFirst { node ->
            node.messages.any { it.role == MessageRole.ASSISTANT && it.interrupted }
        }
        if (hasMarked >= 0) return this // 已有标记（重入的兜底路径），不重复标记
        return copy(messageNodes = nodes.mapIndexed { idx, node ->
            if (idx != lastAssistantNodeIndex) {
                node
            } else {
                node.copy(messages = node.messages.mapIndexed { mIdx, msg ->
                    if (mIdx == node.selectIndex && msg.role == MessageRole.ASSISTANT && !msg.interrupted) {
                        msg.copy(interrupted = true)
                    } else {
                        msg
                    }
                })
            }
        })
    }

    /**
     * 生成期增量落库（fire-and-forget）：把指定节点从最新内存快照差量 upsert 进 DB
     * （不删除任何行，分页窗口外数据安全）。在 per-conversation persistenceMutex 内
     * 重新读取最新快照，保证保存内容与全量保存串行且不会用旧快照覆盖新快照。
     */
    private fun launchIncrementalNodeSave(conversationId: Uuid, nodeIds: Set<Uuid>) {
        if (nodeIds.isEmpty()) return
        // 临时会话禁止任何落库：消息仅存内存，销毁即消失；
        // 已销毁（墓碑）的临时会话同样拒绝，防止残留流式回调把消息写入 DB
        if (temporarySessions.isTemporary(conversationId) || destroyedTemporaryIds.contains(conversationId)) return
        appScope.launch(Dispatchers.IO) {
            val mutex = persistenceMutexes.computeIfAbsent(conversationId) { Mutex() }
            mutex.withLock {
                if (!ensureConversationLoaded(conversationId)) return@withLock
                val conversation = getConversationFlow(conversationId).value
                runCatching { conversationRepo.saveNodesForConversation(conversation, nodeIds) }
                    .onFailure { Log.e(TAG, "增量落库失败: conv=$conversationId", it) }
            }
        }
    }

    /** Apply a field/node mutation to the latest in-memory value, then persist it. */
    suspend fun mutateConversationAndSave(
        id: Uuid,
        unreadNodes: List<ChatUnreadMessageEntity> = emptyList(),
        transform: (Conversation) -> Conversation
    ): Conversation? {
        val mutex = persistenceMutexes.computeIfAbsent(id) { Mutex() }
        return mutex.withLock {
            if (!ensureConversationLoaded(id)) return@withLock null
            val previous = getConversationFlow(id).value
            val updated = normalizeConversationForSave(
                id = id,
                conversation = transform(previous),
                expectedAssistantId = previous.assistantId
            )
            val filesToDelete = findRemovedFiles(previous, updated)
            updateConversation(id) { updated }
            if (!temporarySessions.isTemporary(id)) {
                try {
                    // 未读记录与消息落库同一事务原子生效（空列表时等价 saveConversation）
                    conversationRepo.saveConversationWithUnread(updated, unreadNodes)
                    deleteFilesAsync(id, filesToDelete)
                } catch (error: Throwable) {
                    updateConversation(id) { current -> if (current == updated) previous else current }
                    throw error
                }
            } else {
                deleteFilesAsync(id, filesToDelete)
            }
            updated
        }
    }

    suspend fun replaceConversationMessages(
        conversationId: Uuid,
        targetMessageIds: Set<Uuid> = emptySet(),
        transform: (Conversation) -> ConversationMessageDeletion?
    ): ConversationMessageDeletion? {
        val mutex = persistenceMutexes.computeIfAbsent(conversationId) { Mutex() }
        return mutex.withLock {
            if (!ensureConversationLoaded(conversationId)) return@withLock null
            val snapshot = getConversationFlow(conversationId).value
            // 目标消息可能落在超出默认水合深度(助手全局最新 200 节点)的空占位节点中,
            // 不先定向水合的话 transform 在内存里找不到目标 → 静默失败(删除/重生成无效果)。
            val previous = hydrateTargetNodes(conversationId, snapshot, targetMessageIds)
            val replacement = transform(previous) ?: return@withLock null
            val safe = normalizeConversationForSave(
                id = conversationId,
                conversation = replacement.conversation,
                expectedAssistantId = previous.assistantId
            )
            val normalizedReplacement = replacement.copy(conversation = safe)
            val filesToDelete = findRemovedFiles(previous, safe)
            updateConversation(conversationId) { safe }
            if (temporarySessions.isTemporary(conversationId)) {
                deleteFilesAsync(conversationId, filesToDelete)
                return@withLock normalizedReplacement
            }
            try {
                conversationRepo.replaceConversationMessages(
                    conversation = safe,
                    deletedNodeIds = normalizedReplacement.deletedNodeIds,
                    deletedMessageIds = normalizedReplacement.deletedMessageIds
                )
                deleteFilesAsync(conversationId, filesToDelete)
            } catch (error: Throwable) {
                updateConversation(conversationId) { current -> if (current == safe) previous else current }
                throw error
            }
            normalizedReplacement
        }
    }

    /**
     * 定向水合删除 / 重生成的目标消息所在节点(必须持 [persistenceMutexes] 对应锁调用)。
     *
     * 内存会话默认只水合助手全局最新 200 个节点(`fetchFullConversations` 的 loadLimit),
     * 更早的历史只留空占位节点。目标消息全部已水合时原样返回;存在缺失时取全局深度
     * 最大的缺失消息为锚点,复用搜索跳转的 depth 加载机制一次覆盖全部目标,并按节点 id
     * 只合并空占位节点(见 [ConversationHydrationRules.mergePlaceholderNodes])。
     */
    private suspend fun hydrateTargetNodes(
        conversationId: Uuid,
        snapshot: Conversation,
        targetMessageIds: Set<Uuid>
    ): Conversation {
        if (targetMessageIds.isEmpty()) return snapshot
        if (temporarySessions.isTemporary(conversationId)) return snapshot // 临时会话纯内存,无占位
        val missing = ConversationHydrationRules.findUnloadedTargetIds(snapshot, targetMessageIds)
        if (missing.isEmpty()) return snapshot
        // 以全局深度最大的缺失消息为锚点(单个 targetMessageId 的加载范围覆盖它到最新),
        // 一次加载即可覆盖所有缺失目标;深度查询失败按 0 处理,不影响候选资格。
        val assistantIdStr = snapshot.assistantId.toString()
        val anchorMessageId = missing.maxByOrNull { id ->
            runCatching {
                conversationRepo.chatMessageDAO.getMessageGlobalDepth(assistantIdStr, id.toString())
            }.getOrDefault(0)
        }?.toString() ?: return snapshot
        val hydrated = conversationRepo.getConversationById(conversationId, anchorMessageId) ?: return snapshot
        return ConversationHydrationRules.mergePlaceholderNodes(snapshot, hydrated)
    }

    private fun normalizeConversationForSave(
        id: Uuid,
        conversation: Conversation,
        expectedAssistantId: Uuid
    ): Conversation {
        require(conversation.id == id) {
            "Conversation id ${conversation.id} does not match persistence target $id"
        }
        val normalized = conversation.normalizeMessageNodes()
        return if (expectedAssistantId != normalized.assistantId) {
            normalized.copy(assistantId = expectedAssistantId)
        } else {
            normalized
        }
    }

    fun getAiTypingFlow(id: Uuid): Flow<Boolean> = _isAiTypingMap.map { it[id] ?: false }

    fun cleanupConversation(id: Uuid) {
        _generationJobs.value[id]?.cancel()
        removeGenerationJob(id)
        conversations.remove(id)
        initializedConversationIds.remove(id)
        conversationMutexes.remove(id)
    }

    /**
     * 清除内存缓存中属于指定智能体的所有会话。
     *
     * 用于"一键清空对话及片段记忆"功能：DB 层已通过 [ConversationRepository.clearConversationsAndSegmentMemory]
     * 删除了 conversationentity（及级联的 chat_message_nodes / chat_messages），但 ChatService 的
     * [conversations] ConcurrentHashMap 仍持有旧的 StateFlow，导致 ChatVM 的 conversation 流继续
     * 推送旧消息，UI 不刷新。
     *
     * 此方法遍历缓存，将 assistantId 匹配的会话 StateFlow 更新为空会话（清空 messageNodes），
     * 让正在订阅的 ChatVM 立即感知到数据变更。
     */
    fun clearConversationsOfAssistantFromCache(assistantId: Uuid) {
        conversations.forEach { (convId, flow) ->
            val conv = flow.value
            if (conv.assistantId == assistantId) {
                // 更新 StateFlow 值为空会话，保留 id/assistantId 但清空消息
                // currentMessages 是派生属性，messageNodes 清空后自动返回空列表
                flow.value = conv.copy(
                    messageNodes = emptyList(),
                    chatSuggestions = emptyList(),
                    lastSummarizedMessageTime = 0L,
                    lastSummarizedMessageId = "",
                    contextSummaryUpToIndex = -1,
                    lastArchivedMessageTime = 0L
                )
                _generationJobs.value[convId]?.cancel()
                removeGenerationJob(convId)
            }
        }
        // 通知 ChatVM 重新加载分页管理器（清空 _currentNodes 并从 DB 重新加载）
        _conversationsCleared.tryEmit(assistantId)
    }

    /** 会话被清空时发出事件，ChatVM 监听以重置分页缓存 */
    private val _conversationsCleared = MutableSharedFlow<Uuid>(extraBufferCapacity = 4)
    val conversationsCleared: SharedFlow<Uuid> = _conversationsCleared

    /**
     * 仅停止指定会话的生成任务（不清理会话状态）。
     * 用于通话打断等场景：需要立即取消 AI 生成但保留会话上下文。
     */
    fun stopGeneration(conversationId: Uuid) {
        _generationJobs.value[conversationId]?.cancel()
        removeGenerationJob(conversationId)
        _isAiTypingMap.update { it - conversationId }
    }

    // ===== 临时会话（Temporary Chat）生命周期管理 =====
    // 身份判定统一走 temporarySessions 注册表；临时会话不写任何正式数据库表，
    // 消息仅存在于 conversations 内存 StateFlow，进程死亡自然消失。

    fun isTemporarySession(conversationId: Uuid): Boolean =
        temporarySessions.isTemporary(conversationId)

    /**
     * 临时会话完整身份判定：活跃 / 销毁中 / 已销毁（墓碑）三者任一命中即为临时会话。
     * 注册表 remove() 后 isTemporary / isDestroying 同时归 false，唯一残留标记是
     * destroyedTemporaryIds 墓碑（内存态）；任何「执行时点晚于 destroyTemporarySession」
     * 的异步路径（兜底保存、lastConversationId 写入、任务目标会话选择等）必须用本判定，
     * 只查 isTemporary 会在销毁完成后放行（历史 bug：临时消息被兜底落库成幽灵会话）。
     */
    private fun isTemporaryOrDestroyed(conversationId: Uuid): Boolean =
        temporarySessions.isTemporary(conversationId) ||
            temporarySessions.isDestroying(conversationId) ||
            destroyedTemporaryIds.contains(conversationId)

    /** 当前是否存在活跃临时会话（供通知点击拦截等 UI 层判断「用户正处于临时会话」）。 */
    fun hasActiveTemporarySession(): Boolean =
        temporarySessions.activeSessionIds().isNotEmpty()

    /**
     * 创建临时会话：生成运行期 UUID、注册身份、初始化仅存于内存的 Conversation。
     * 不创建正式 Conversation 记录、不改动 lastConversationId / 当前助手等任何正式状态。
     */
    fun createTemporarySession(assistantId: Uuid): Uuid {
        val sessionId = Uuid.random()
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(assistantId) ?: settings.getCurrentAssistant()
        destroyedTemporaryIds.remove(sessionId)
        temporarySessions.create(sessionId, assistant.id)
        // 预置消息仅作展示（与正式新会话一致），不入库
        val conversation = Conversation.ofId(
            id = sessionId,
            assistantId = assistant.id
        ).updateCurrentMessages(assistant.presetMessages)
        conversations[sessionId] = MutableStateFlow(conversation)
        initializedConversationIds += sessionId
        return sessionId
    }

    /**
     * 销毁临时会话（退出确认后的统一出口）：
     * 1. 标记销毁中，阻止新消息进入（sendMessage 入口检查 destroying）
     * 2. 取消 AI 生成任务与微信模式 debounce 任务
     * 3. 注销注册表身份 + 清理内存会话缓存（消息随内存消失）
     * 4. 删除本次会话产生的临时附件文件（仅限该会话引用的文件）
     *
     * 幂等：会话不存在时为 no-op，可安全重复调用。
     */
    fun destroyTemporarySession(conversationId: Uuid) {
        if (!temporarySessions.isTemporary(conversationId)) return
        temporarySessions.markDestroying(conversationId)
        destroyedTemporaryIds.add(conversationId)
        stopGeneration(conversationId)
        wechatDebounceJobs[conversationId]?.cancel()
        wechatDebounceJobs.remove(conversationId)
        // 释放临时页存续期间持有的引用（与 TemporaryChatVM.onCleared 的 remove 配对，幂等）
        conversationReferences.remove(conversationId)
        // 删除该临时会话引用的附件文件（会话内存对象即将移除，先取引用）
        val tempConversation = conversations[conversationId]?.value
        if (tempConversation?.files?.isNotEmpty() == true) {
            appScope.launch(Dispatchers.IO) {
                runCatching { context.deleteChatFiles(tempConversation.files) }
                    .onFailure { Log.w(TAG, "清理临时会话附件失败: conv=$conversationId", it) }
            }
        }
        temporarySessions.remove(conversationId)
        cleanupConversation(conversationId)
        Log.i(TAG, "Temporary session destroyed: conv=$conversationId")
    }

    /**
     * 标记/取消会话的"通话模式"。
     * - 启用后, 主对话路径将跳过每次 AI 响应后的 L1 自动摘要；
     * - 改由 [VoiceCallManager] 内部的 25 分钟定时器调用 [summarizeForCallIfNeeded] 主动触发。
     * - 用于避免实时通话中频繁触发 AI 摘要调用占用并发槽位、增加端到端延迟。
     */
    fun setCallMode(conversationId: Uuid, active: Boolean) {
        if (active) callModeConversations.add(conversationId)
        else callModeConversations.remove(conversationId)
    }

    /**
     * 通话定时器入口：检查并按需触发该会话的 L1 自动摘要。
     * 内部直接复用主路径的 [checkAndAutoSummarize] 逻辑（含阈值判断, 未达阈值会快速返回）。
     */
    suspend fun summarizeForCallIfNeeded(conversationId: Uuid) {
        if (!callModeConversations.contains(conversationId)) return
        val settings = settingsStore.settingsFlow.value
        val conv = getConversationFlow(conversationId).value
        checkAndAutoSummarize(conversationId, conv, settings)
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        if (_generationJobs.value.containsKey(conversationId)) return
        updateConversation(conversationId) { conversation ->
            conversation.removeInvalidMessages()
        }
    }

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val responseAnchorId = conversation.currentMessages.lastOrNull()?.id ?: return
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
            // Uuid.NIL 作为 sentinel：该智能体明确禁用聊天建议，不回退全局
            val modelId: Uuid = when {
                assistant.suggestionModelId == Uuid.NIL -> return
                assistant.suggestionModelId != null -> assistant.suggestionModelId!!
                else -> settings.suggestionModelId ?: return
            }
            val model = settings.findModelById(modelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val result = (providerManager.getProviderByType(provider) as Provider<ProviderSetting>).generateText(
                provider, listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .filter { it.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() > conversation.lastArchivedMessageTime }
                                .takeLast(8)
                                .joinToString("\n") { it.summaryAsText() })
                    )
                ), TextGenerationParams(model, 1.0f, 1.0f, thinkingBudget = 0)
            )
            result.usage?.let { conversationRepo.recordTokenUsage(assistant.id.toString(), it) }
            val suggestions = result.choices.firstOrNull()?.message?.toContentText()
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            mutateConversationAndSave(conversationId) { current ->
                if (current.currentMessages.lastOrNull()?.id == responseAnchorId) {
                    current.copy(chatSuggestions = suggestions)
                } else {
                    current
                }
            }
        }
    }

    private val conversationDeletionJobs = ConcurrentHashMap<Uuid, Job>()
    private val recentlyDeletedConversations = ConcurrentHashMap<Uuid, Conversation>()
    private val _recentlyRestoredIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val recentlyRestoredIds: StateFlow<Set<Uuid>> = _recentlyRestoredIds

    fun deleteConversation(conversation: Conversation) {
        appScope.launch {
            val full = conversationRepo.getConversationById(conversation.id) ?: return@launch
            conversationDeletionJobs[conversation.id]?.cancel()
            conversationRepo.deleteConversation(full, false)
            recentlyDeletedConversations[conversation.id] = full
            conversationDeletionJobs[conversation.id] = appScope.launch {
                delay(4000); context.deleteChatFiles(full.files); recentlyDeletedConversations.remove(conversation.id)
            }
        }
    }

    fun undoDeleteConversation(conversationId: Uuid) {
        conversationDeletionJobs[conversationId]?.cancel()
        recentlyDeletedConversations.remove(conversationId)?.let { conv ->
            appScope.launch {
                conversationRepo.insertConversation(conv); _recentlyRestoredIds.value += conversationId; delay(1000); _recentlyRestoredIds.value -= conversationId
            }
        }
    }

    private fun sendGenerationDoneNotification(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.value
        // AI 回复通知开关（SET）：关闭则不再推送
        if (!settings.notificationAiReplyEnabled) return
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val lastMsg = conversation.currentMessages.lastOrNull()
        // skipContext=true 的消息是隐形对话（日记评论、agent_task 等），不推送系统通知避免泄露内容
        if (lastMsg == null || lastMsg.skipContext) return
        val msg = lastMsg.toPreviewText()?.take(50) ?: ""
        // AI 消息提醒统一使用 ai_messages 渠道（渠道类别已精简）
        val channelId = NotificationSoundManager.AI_MESSAGES_CHANNEL_ID
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(assistant.name)
            .setContentText(msg)
            .setSmallIcon(R.drawable.about_logo)
            .setAutoCancel(true)
            .setContentIntent(getPendingIntent(context, conversationId))
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(1, notification.build())
        }
    }

    /**
     * 微信模式：单个分句（语音条/文字句）存储完成后发送通知。
     * 复用 sendGenerationDoneNotification，每次取会话最后一条消息作为正文。
     */
    private fun sendWechatSentenceNotification(conversationId: Uuid) {
        val settings = settingsStore.settingsFlow.value
        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
            sendGenerationDoneNotification(conversationId)
        }
    }

    private fun getPendingIntent(context: Context, id: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", id.toString())
        }
        return PendingIntent.getActivity(
            context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateConversation(
        id: Uuid,
        normalizeNodes: Boolean = false,
        block: (Conversation) -> Conversation
    ) {
        val flow = (conversations[id] ?: getConversationFlow(id)) as MutableStateFlow<Conversation>
        flow.update { old ->
            val transformed = block(old)
            val new = if (normalizeNodes) transformed.normalizeMessageNodes() else transformed
            if (new.id != id) return@update old
            new
        }
    }

    private fun findRemovedFiles(previous: Conversation, updated: Conversation): List<Uri> {
        val updatedFiles = updated.files.toSet()
        return previous.files.filterNot { file -> file in updatedFiles }.distinct()
    }

    private fun deleteFilesAsync(conversationId: Uuid, filesToDelete: List<Uri>) {
        if (filesToDelete.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                // Keep message-level undo recoverable and re-check references before
                // performing the irreversible file operation.
                delay(5000)
                val latestConversation = conversations[conversationId]?.value
                    ?: conversationRepo.getConversationById(conversationId)
                val referencedFiles = latestConversation?.files?.toSet().orEmpty()
                val confirmedOrphans = filesToDelete.filterNot { file -> file in referencedFiles }
                if (confirmedOrphans.isNotEmpty()) {
                    runCatching { context.deleteChatFiles(confirmedOrphans) }
                }
            }
        }
    }
}
