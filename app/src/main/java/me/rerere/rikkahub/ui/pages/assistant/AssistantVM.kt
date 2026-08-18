package me.rerere.rikkahub.ui.pages.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.DiaryRepository

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val diaryRepo: DiaryRepository,
    private val appScope: me.rerere.rikkahub.AppScope
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value

            // 确保 ID 唯一，防止 LazyColumn 崩溃
            val assistantWithUniqueId = if (settings.assistants.any { it.id == assistant.id }) {
                assistant.copy(id = kotlin.uuid.Uuid.random())
            } else {
                assistant
            }

            // 自动填入全局模型配置（如果智能体未配置）
            var newAssistant = assistantWithUniqueId.copy(
                chatModelId = assistantWithUniqueId.chatModelId ?: settings.chatModelId,
                backgroundModelId = assistantWithUniqueId.backgroundModelId ?: settings.backgroundModelId,
                summarizerModelId = assistantWithUniqueId.summarizerModelId ?: settings.summarizerModelId,
                memoryModelId = assistantWithUniqueId.memoryModelId ?: settings.memoryModelId,
                diaryModelId = assistantWithUniqueId.diaryModelId ?: settings.diaryModelId
            )

            // 逻辑优化：如果是第一个智能体，默认设为主智能体并开启用户档案带入
            if (settings.assistants.isEmpty()) {
                newAssistant = newAssistant.copy(
                    isMain = true,
                    includeUserProfile = true,
                    hasExtendedState = true
                )
            }

            if (newAssistant.name.isBlank()) {
                newAssistant = newAssistant.copy(
                    name = "Evolia",
                    avatar = Avatar.Resource(R.drawable.about_logo),
                    systemPrompt = """
                        你是用户创造的ai， {{char}}。
                    """.trimIndent()
                )
            }
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(newAssistant)
                )
            )
        }
    }

    private val deletionJobs = java.util.concurrent.ConcurrentHashMap<kotlin.uuid.Uuid, kotlinx.coroutines.Job>()

    fun removeAssistant(assistant: Assistant) {
        // Cancel any existing job for this assistant
        deletionJobs[assistant.id]?.cancel()

        viewModelScope.launch {
            // Optimistic update: Remove from settings immediately
            val currentSettings = settings.value
            settingsStore.update(
                currentSettings.copy(
                    assistants = currentSettings.assistants.filter { it.id != assistant.id }
                )
            )
        }

        // Start delayed deletion of data
        val job = appScope.launch {
            kotlinx.coroutines.delay(4000) // 4 seconds to undo
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
            diaryRepo.deleteDiariesOfAssistant(assistant.id.toString())
            deletionJobs.remove(assistant.id)
        }
        deletionJobs[assistant.id] = job
    }

    fun undoRemoveAssistant(assistant: Assistant) {
        // Cancel deletion job if it exists
        deletionJobs[assistant.id]?.cancel()
        deletionJobs.remove(assistant.id)

        viewModelScope.launch {
            // Restore to settings
            val currentSettings = settings.value
            if (currentSettings.assistants.none { it.id == assistant.id }) {
                settingsStore.update(
                    currentSettings.copy(
                        assistants = currentSettings.assistants.plus(assistant)
                    )
                )
            }
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
                // 不复制记忆档案和相关状态
                masterMemoryContent = "",
                lastMasterMemoryUpdate = 0L,
                lastConsolidationTime = 0L,
                lastConsolidationResult = "",
                isMain = false, // 复制品默认不是主智能体
                includeUserProfile = false // 复制品默认关闭档案带入
            )
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(copiedAssistant)
                )
            )
        }
    }

    fun getMemories(assistant: Assistant) =
        memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
}
