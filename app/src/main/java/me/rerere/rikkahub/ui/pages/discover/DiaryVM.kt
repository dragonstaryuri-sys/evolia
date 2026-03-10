package me.rerere.rikkahub.ui.pages.discover

import androidx.lifecycle.ViewModel
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
import kotlin.uuid.Uuid

class DiaryVM(
    private val settingsStore: SettingsStore,
    private val diaryRepo: DiaryRepository,
    private val conversationRepo: ConversationRepository,
    private val generationHandler: GenerationHandler,
) : ViewModel() {
    val settings = settingsStore.settingsFlow

    val diaries = settings.flatMapLatest {
        diaryRepo.getDiariesByAssistant(it.assistantId.toString())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    fun generateTodayDiary(toaster: AppToasterState? = null) {
        val currentSettings = settings.value
        val assistant = currentSettings.getCurrentAssistant()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        viewModelScope.launch {
            if (_isGenerating.value) return@launch

            // Check if already generated today
            val existing = diaryRepo.getDiaryByDate(assistant.id.toString(), today)
            if (existing != null) {
                toaster?.show("Today's diary already exists", type = ToastType.Info)
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
                    toaster?.show("No chat records found for today", type = ToastType.Error)
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
                    toaster?.show("Diary generated successfully", type = ToastType.Success)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toaster?.show("Failed to generate diary: ${e.message}", type = ToastType.Error)
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
}
