package me.rerere.rikkahub.core.data.model

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
    val newChatHeaderStyle: String? = null,
    val newChatContentStyle: String? = null,
    val newChatShowAvatar: Boolean? = null,
)

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null,
    val backgroundModelId: Uuid? = null,
    val searchMode: AssistantSearchMode = AssistantSearchMode.Off,
    val preferBuiltInSearch: Boolean = false,
    val embeddingModelId: Uuid? = null,
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false,
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokenUsage: Int = 81920,
    val contextPriority: ContextPriority = ContextPriority.BALANCED,
    val summarizerModelId: Uuid? = null,
    val diaryModelId: Uuid? = null,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useRagMemoryRetrieval: Boolean = true,
    val ragSimilarityThreshold: Float = 0.45f,
    val ragLimit: Int = 5,
    val enableRecentChatsReference: Boolean = false,
    val ragIncludeEpisodes: Boolean = true,
    val ragIncludeCore: Boolean = true,
    val enableRagLogging: Boolean = false,
    val enableMemoryConsolidation: Boolean = false,
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

    // Spontaneous Messaging
    val notificationStartHour: Int = 7,
    val notificationEndHour: Int = 22,
    val notificationFrequencyHours: Int = 4,
    val lastNotificationTime: Long = 0L,
    val lastNotificationContent: String = "",

    // Context Management
    val maxHistoryMessages: Int? = null,
    val enableHistorySummarization: Boolean = false,
    val maxSearchResultsRetained: Int? = null,
    val enableContextRefresh: Boolean = false,
    val autoRegenerateSummary: Boolean = false,
    val maxTemporarySummariesToInclude: Int = 3,
    val fullSummaryPrompt: String = "",
    val temporarySummaryPrompt: String = "",

    val uiSettings: AssistantUISettings = AssistantUISettings(),

    // Memory Consolidation
    val consolidationDelayMinutes: Int = 30,
    val lastConsolidationTime: Long = 0L,
    val lastConsolidationResult: String = "",

    // Master Memory (Memory Archive)
    val enableMasterMemory: Boolean = false,
    val masterMemoryPrompt: String = "",
    val masterMemoryContent: String = "",
    val lastMasterMemoryUpdate: Long = 0L,
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
    val type: Int = 0,
    val hasEmbedding: Boolean = false,
    val embeddingModelId: String? = null,
    val timestamp: Long = 0L,
    val significance: Int? = null
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

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            try {
                acc.replace(
                    regex = Regex(regex.findRegex),
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                acc
            }
        } else {
            acc
        }
    }
}

@Serializable
sealed class PromptInjection {
    @Serializable
    @SerialName("mode")
    data class ModeInjection(val name: String, val priority: Int, val prompt: String) : PromptInjection()

    @Serializable
    @SerialName("regex")
    data class RegexInjection(val name: String, val regex: String) : PromptInjection()
}
