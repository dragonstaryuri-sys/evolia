package me.rerere.rikkahub.ui.pages.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.AppToasterState
import me.rerere.rikkahub.utils.applyPlaceholders
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DiaryVM(
    private val app: Application,
    private val settingsStore: SettingsStore,
    private val diaryRepo: DiaryRepository,
    private val conversationRepo: ConversationRepository,
    private val generationHandler: GenerationHandler,
) : AndroidViewModel(app) {
    val settings = settingsStore.settingsFlow

    val assistants = settings.map { it.assistants }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun getDiaries(assistantId: String?) = if (assistantId == null) {
        diaryRepo.getAllDiaries()
    } else {
        diaryRepo.getDiariesByAssistant(assistantId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    fun generateTodayDiary(assistantId: String?, toaster: AppToasterState? = null) {
        val currentSettings = settings.value
        val assistant = if (assistantId != null) {
            currentSettings.assistants.find { it.id.toString() == assistantId }
        } else {
            currentSettings.getCurrentAssistant()
        } ?: return

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        viewModelScope.launch {
            if (_isGenerating.value) return@launch

            // Check if already generated today
            val existing = diaryRepo.getDiaryByDate(assistant.id.toString(), today)
            if (existing != null) {
                toaster?.show(app.getString(R.string.diary_already_generated), type = ToastType.Info)
                return@launch
            }

            _isGenerating.value = true
            try {
                // 1. Get today's chat history for this assistant
                val conversations = conversationRepo.getConversationsOfAssistant(assistant.id).first()
                val todayConversations = conversations.filter {
                    val date = java.time.Instant.ofEpochMilli(it.updateAt.toEpochMilli())
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                    date == LocalDate.now()
                }

                if (todayConversations.isEmpty()) {
                    toaster?.show(app.getString(R.string.discover_page_diary_no_chat_today), type = ToastType.Error)
                    return@launch
                }

                val fullContent = todayConversations.joinToString("\n---\n") { conv ->
                    "Conversation: ${conv.title}\n" + conv.messageNodes.flatMap { node ->
                        node.messages.map { "${it.role}: ${it.toText()}" }
                    }.joinToString("\n")
                }

                // 2. Use AI to generate diary
                val diaryModelId = assistant.diaryModelId ?: currentSettings.diaryModelId
                val model = currentSettings.findModelById(diaryModelId)
                    ?: currentSettings.findModelById(currentSettings.chatModelId)
                    ?: error("No model available for diary generation")

                val prompt = currentSettings.diaryPrompt.applyPlaceholders(
                    "content" to fullContent,
                    "char" to assistant.name,
                    "user" to (currentSettings.displaySetting.userNickname.ifBlank { "User" })
                )

                var generatedContent = ""
                generationHandler.generateText(
                    settings = currentSettings,
                    model = model,
                    messages = listOf(UIMessage.user(prompt)),
                    assistant = assistant
                ).collect { chunk ->
                    when (chunk) {
                        is me.rerere.rikkahub.data.ai.GenerationChunk.Messages -> {
                            generatedContent = chunk.messages.lastOrNull()?.toText() ?: ""
                        }
                    }
                }

                if (generatedContent.isNotBlank()) {
                    // 3. Save to DB
                    val diary = AgentDiaryEntity(
                        assistantId = assistant.id.toString(),
                        content = generatedContent,
                        date = today
                    )
                    diaryRepo.insertDiary(diary)
                    toaster?.show(app.getString(R.string.discover_page_diary_generate_success), type = ToastType.Success)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toaster?.show(app.getString(R.string.diary_generate_failed, e.message), type = ToastType.Error)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun deleteDiary(id: String) {
        viewModelScope.launch {
            diaryRepo.deleteDiaryById(id)
        }
    }

    fun updateAssistantDiarySettings(assistantId: String, enableAuto: Boolean, autoTime: String) {
        viewModelScope.launch {
            val currentSettings = settings.value
            val updatedAssistants = currentSettings.assistants.map {
                if (it.id.toString() == assistantId) {
                    it.copy(enableAutoDiary = enableAuto, autoDiaryTime = autoTime)
                } else {
                    it
                }
            }
            settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
        }
    }
}
