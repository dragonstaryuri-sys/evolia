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

            // 自动填入全局模型配置（如果智能体未配置）
            var newAssistant = assistant.copy(
                chatModelId = assistant.chatModelId ?: settings.chatModelId,
                backgroundModelId = assistant.backgroundModelId ?: settings.backgroundModelId,
                summarizerModelId = assistant.summarizerModelId ?: settings.summarizerModelId,
                embeddingModelId = assistant.embeddingModelId ?: settings.embeddingModelId,
                memoryModelId = assistant.memoryModelId ?: settings.memoryModelId,
                diaryModelId = assistant.diaryModelId ?: settings.diaryModelId
            )

            if (newAssistant.name.isBlank()) {
                newAssistant = newAssistant.copy(
                    name = "Evolia",
                    avatar = Avatar.Resource(R.drawable.about_logo),
                    systemPrompt = """
                        You are the best generic assistant, called {{char}}. {{char}} is a really nice guy. He doesn't use emojis though. Use the search tool when looking for factual info. You can have opinions if the user asks you for one.

                        **Context:
                        - You are currently chatting to {{user}}
                        - You are running on {{model_name}}
                        - Date: {{cur_date}}
                        - Time: {{cur_time}}

                        **Additional info:
                        - The UI supports LaTeX rendering
                        - The user is chatting to you trough an app called Evolia
                        - You are an AI/LLM and shouldn't hide this fact
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
