package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.entity.MemoryType
import me.rerere.rikkahub.core.data.db.entity.MilestoneEntity
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.MilestoneRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

class AssistantImportMemoryVM(
    private val assistantId: String,
    val isMain: Boolean,
    val totalSessions: Int,
    private val settingsStore: SettingsStore,
    private val memoryRepo: MemoryRepository,
    private val milestoneRepo: MilestoneRepository,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService
) : ViewModel() {

    // 1. 关系档案 (L3)
    var relationshipProfile by mutableStateOf("")

    // 2. 里程碑事件
    val milestones = mutableStateListOf<MilestoneItem>()

    // 3. 核心记忆 (对应 MemoryEntity)
    val coreMemories = mutableStateListOf<CoreMemoryItem>()

    // 4. 片段记忆 L1 相关
    var selectedL1Count by mutableIntStateOf(totalSessions.coerceAtMost(10))
    var l1Progress by mutableFloatStateOf(0f)
    var currentL1SessionName by mutableStateOf("")
    var isGeneratingL1 by mutableStateOf(false)
    private var l1GenerationJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.assistants.find { it.id.toString() == assistantId }
            relationshipProfile = assistant?.masterMemoryContent ?: ""

            // 默认给一个核心记忆输入框
            if (coreMemories.isEmpty()) {
                coreMemories.add(CoreMemoryItem())
            }
            // 默认给一个里程碑输入框
            if (isMain && milestones.isEmpty()) {
                milestones.add(MilestoneItem())
            }
        }
    }

    fun addMilestone() = milestones.add(MilestoneItem())
    fun removeMilestone(index: Int) = milestones.removeAt(index)

    fun addCoreMemory() = coreMemories.add(CoreMemoryItem())
    fun removeCoreMemory(index: Int) = coreMemories.removeAt(index)

    /**
     * 提交所有记忆设置
     */
    fun submitAll(onFinished: () -> Unit) {
        viewModelScope.launch {
            // A. 关系档案 (L3)
            val settings = settingsStore.settingsFlow.first()
            val updatedAssistants = settings.assistants.map {
                if (it.id.toString() == assistantId) {
                    it.copy(masterMemoryContent = relationshipProfile)
                } else it
            }
            settingsStore.update(settings.copy(assistants = updatedAssistants))

            // B. 里程碑 (仅主智能体)
            if (isMain) {
                milestones.filter { it.content.isNotBlank() }.forEach { item ->
                    milestoneRepo.addMilestone(
                        MilestoneEntity(
                            assistantId = assistantId,
                            time = item.date,
                            label = item.label.ifBlank { "重要里程碑" },
                            description = item.content
                        )
                    )
                }
            }

            // C. 核心记忆
            coreMemories.filter { it.content.isNotBlank() }.forEach { item ->
                memoryRepo.addMemory(
                    assistantId = assistantId,
                    content = item.content,
                    keywords = item.keywords.ifBlank { null },
                    type = MemoryType.CORE
                )
            }

            // D. L1 片段生成
            if (selectedL1Count > 0) {
                startL1Generation { onFinished() }
            } else {
                onFinished()
            }
        }
    }

    private fun startL1Generation(onComplete: () -> Unit) {
        if (isGeneratingL1) return
        isGeneratingL1 = true
        l1GenerationJob = viewModelScope.launch {
            try {
                // 从 Flow 中获取最新的会话列表并显式指定类型
                val convList: List<Conversation> = conversationRepo.getConversationsOfAssistant(Uuid.parse(assistantId))
                    .first()

                val targets = convList.sortedByDescending { it.updateAt }
                    .take(selectedL1Count)

                if (targets.isEmpty()) {
                    onComplete()
                    return@launch
                }

                targets.forEachIndexed { index, conv ->
                    currentL1SessionName = conv.title
                    // 调用 ChatService 的总结逻辑，确保它是 suspend 被调用的
                    chatService.summarizeAndRefresh(
                        id = conv.id,
                        onlySegments = true,
                        skipArchive = true
                    )
                    l1Progress = (index + 1).toFloat() / targets.size
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isGeneratingL1 = false
                onComplete()
            }
        }
    }

    fun cancelL1Generation() {
        l1GenerationJob?.cancel()
        isGeneratingL1 = false
    }
}

class MilestoneItem {
    var label by mutableStateOf("")
    var date by mutableStateOf("")
    var content by mutableStateOf("")
}

class CoreMemoryItem {
    var content by mutableStateOf("")
    var keywords by mutableStateOf("")
}
