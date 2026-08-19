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
    val localTools: List<LocalToolOption> = emptyList(),
    val background: String? = null,
    val backgroundDim: Float = 0.6f,
    val useAssistantMaterialYouColors: Boolean = false,
    val learningMode: Boolean = false,
    val enableSpontaneous: Boolean = false,
    val spontaneousPrompt: String = "",
    val enabledLorebookIds: Set<Uuid> = emptySet(),

    val notificationStartHour: Int = 7,
    val notificationEndHour: Int = 22,
    val notificationFrequencyHours: Int = 4,
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
    val consolidationDelayMinutes: Int = 30,
    val lastConsolidationTime: Long = 0L,
    val lastConsolidationResult: String = "",

    val enableMasterMemory: Boolean = true,
    val masterMemoryContent: String = "",
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
