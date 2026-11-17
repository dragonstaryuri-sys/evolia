package me.rerere.rikkahub.core.data.model

import androidx.room.ColumnInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

@Serializable
data class AssistantUISettings(
    val showUserAvatar: Boolean? = null,
    val showAssistantAvatar: Boolean? = null,
    val showAssistantBubbles: Boolean? = null,
    val showTokenUsage: Boolean? = null,
    val autoCloseThinking: Boolean? = null,
    val showMessageJumper: Boolean? = null,
    val messageJumperOnLeft: Boolean? = null,
    val fontSizeRatio: Float? = null,
    val codeBlockAutoWrap: Boolean? = null,
    val codeBlockAutoCollapse: Boolean? = null,
    val showContextStacks: Boolean? = null,
    val chatInputStyle: String? = null,
    val wechatMode: Boolean? = null,
)

@Serializable
enum class MemoryRetrievalMode {
    OFF,
    SEMANTIC,
    KEYWORD,
    HYBRID
}

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null,
    val backgroundModelId: Uuid? = null,
    val searchMode: AssistantSearchMode = AssistantSearchMode.Off,
    val preferBuiltInSearch: Boolean = false,
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false,
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val referenceVariables: String = "",
    val languageStyleExamples: List<String> = emptyList(),
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokenUsage: Int = 81920,
    val contextPriority: ContextPriority = ContextPriority.BALANCED,
    val summarizerModelId: Uuid? = null,
    val memoryModelId: Uuid? = null,
    val diaryModelId: Uuid? = null,
    val suggestionModelId: Uuid? = null,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = true,
    val useRagMemoryRetrieval: Boolean = true,
    val memoryRetrievalMode: MemoryRetrievalMode = MemoryRetrievalMode.HYBRID,
    val ragSimilarityThreshold: Float = 0.4f,
    val ragLimit: Int = 3,
    val enableRecentChatsReference: Boolean = true,
    val enableRagLogging: Boolean = false,
    val enableMemoryConsolidation: Boolean = true,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val regexes: List<AssistantRegex> = emptyList(),
    val thinkingBudget: Int? = 1024,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.DeviceControl),

    /**
     * 助手级 Mini App 授权（详设 13.1 第三层权限）：已启用 Mini App 的 app_id 集合。
     *
     * 只影响 Mini App 内 Tools 的可调用性（第三层开关）；独立 Tool Extension 不受
     * 此字段限制（整体启用 + Tool 全局启用后对所有助手开放）。默认空集合兼容
     * 历史数据（JsonInstant 忽略未知 key，旧备份无缝反序列化）。
     */
    val enabledMiniAppIds: Set<String> = emptySet(),

    /**
     * 单次用户请求内允许的最大工具调用轮次（连续调用工具次数，一轮 = 一次包含工具调用的
     * 模型响应）。用户可在助手详情「工具与搜索」页配置（10~30）；历史数据/导入数据越界时
     * 由运行时收敛进合法区间（见 GenerationHandler）。
     */
    val maxToolRounds: Int = 16,
    val background: String? = null,
    val backgroundDim: Float = 0.6f,
    val useAssistantMaterialYouColors: Boolean = false,
    val learningMode: Boolean = false,
    val enableSpontaneous: Boolean = false,
    val spontaneousPrompt: String = "",
    val enabledLorebookIds: Set<Uuid> = emptySet(),

    val notificationStartHour: Int = 7,
    val notificationEndHour: Int = 22,
    val notificationFrequencyMinutes: Int = 240,
    val lastNotificationTime: Long = 0L,
    val lastNotificationContent: String = "",

    val maxHistoryMessages: Int? = null,
    val enableHistorySummarization: Boolean = false,
    val maxSearchResultsRetained: Int? = null,
    val enableContextRefresh: Boolean = false,
    val autoRegenerateSummary: Boolean = false,
    val maxTemporarySummariesToInclude: Int = 3,
    val enableScheduleAccess: Boolean = true,
    val uiSettings: AssistantUISettings = AssistantUISettings(),
    val lastConsolidationTime: Long = 0L,
    val lastConsolidationResult: String = "",

    val enableMasterMemory: Boolean = true,
    val masterMemoryContent: String = "",
    val masterMemoryPrompt: String = "",
    val lastMasterMemoryUpdate: Long = 0L,

    val enableAutoDiary: Boolean = false,
    val autoDiaryTime: String = "06:00",
    val lastAutoDiaryDate: String = "",
    val includeDiariesInContext: Boolean = false,
    val maxDiariesToInclude: Int = 5,

    val isMain: Boolean = false,

    @ColumnInfo(name = "last_conversation_id")
    val lastConversationId: String? = null,

    val enableDetailMemory: Boolean = true,
    val detailMemoryThreshold: Int = 20,

    /**
     * Agent 专属语音配置（可空 = 使用全局默认，即设置页选中的 Provider/音色）。
     *
     * - [voiceProviderId]：当前选用的全局 Provider（Settings.ttsProviders 中）；
     * - [voiceSettings]：**每个 Provider 各自的声音配置副本**（providerId →
     *   TTSProviderSetting 子类 JSON，音色/语速/模型可独立调整）。
     *   切换 Provider 后再切回来，之前为该 Provider 配置的信息直接复用，无需重填。
     *   运行时经 TtsVoiceResolver 合并：apiKey/baseUrl/region/groupId 等连接类字段
     *   始终取全局最新值（全局改 key 自动生效），声音字段取对应副本。
     */
    val voiceProviderId: Uuid? = null,
    val voiceSettings: Map<String, String> = emptyMap(),

    val hasExtendedState: Boolean = isMain,
    val includeUserProfile: Boolean = isMain,
)

@Serializable
data class QuickMessage(
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
    val keywords: String? = null,
    val type: Int = 0,
    val hasEmbedding: Boolean = false,
    val embeddingModelId: String? = null,
    val timestamp: Long = 0L,
    val significance: Int? = null,
    val score: Float? = null,
    val recallCount: Int = 0
)

@Serializable
enum class AssistantAffectScope { USER, ASSISTANT }

@Serializable
enum class ContextPriority { CHAT_HISTORY, BALANCED, MEMORIES }

@Serializable
sealed class AssistantSearchMode {
    @Serializable
    @SerialName("off")
    data object Off : AssistantSearchMode()

    @Serializable
    @SerialName("builtin")
    data object BuiltIn : AssistantSearchMode()

    @Serializable
    @SerialName("provider")
    data class Provider(val index: Int) : AssistantSearchMode()
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "",
    val replaceString: String = "",
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false,
)

@Serializable
sealed class PromptInjection {
    @Serializable
    @SerialName("mode")
    data class ModeInjection(val name: String, val priority: Int, val prompt: String) : PromptInjection()

    @Serializable
    @SerialName("regex")
    data class RegexInjection(val name: String, val regex: String) : PromptInjection()
}
