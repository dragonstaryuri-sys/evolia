package me.rerere.rikkahub.ui.pages.assistant

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.DoubaoImportData
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.DoubaoImportManager
import java.io.File
import kotlin.uuid.Uuid

class AssistantImportVM(
    private val settingsStore: SettingsStore,
    private val importManager: DoubaoImportManager,
    private val conversationRepo: ConversationRepository
) : ViewModel() {
    val progress: StateFlow<Float> = importManager.progress
    val progressText: StateFlow<String> = importManager.progressText
    val status: StateFlow<String> = importManager.status

    // 配置状态
    var isMainAgent by mutableStateOf(false)
    var roundsPerSession by mutableIntStateOf(50)
    var importData by mutableStateOf<DoubaoImportData?>(null)

    // 可编辑的智能体信息
    var botName by mutableStateOf("")
    var botDescription by mutableStateOf("")

    // 运行状态
    var isImporting by mutableStateOf(false)
    var previewConversation by mutableStateOf<Conversation?>(null)
    private var previewDeferred: CompletableDeferred<Boolean>? = null

    // 记录本次创建的智能体 ID
    private var currentAssistantId: Uuid? = null

    val importLog: String get() = importManager.getLog()

    fun prepareImport(uri: Uri, onError: (String) -> Unit) {
        viewModelScope.launch {
            val data = importManager.parseData(uri)
            if (data == null) {
                onError("文件解析失败，请确保导入的是标准豆包 JSON 格式文档")
                return@launch
            }

            val validCount = importManager.getValidMessageCount(data)
            if (validCount == 0) {
                onError("该文档中未检测到有效的对话记录，请检查文档内容")
                return@launch
            }

            importData = data
            // 初始化可编辑字段
            botName = data.botInfo.name
            botDescription = data.botInfo.description
        }
    }

    fun confirmPreview(confirm: Boolean) {
        previewDeferred?.complete(confirm)
        previewConversation = null
    }

    fun startImport(onFinish: (Boolean) -> Unit) {
        val data = importData ?: return
        isImporting = true

        viewModelScope.launch {
            val initialSettings = settingsStore.settingsFlow.value
            val newAssistantId = Uuid.random()
            currentAssistantId = newAssistantId

            // 执行导入过程
            val success = importManager.performImport(
                data = data,
                assistantId = newAssistantId,
                roundsPerSession = roundsPerSession
            ) { preview ->
                previewConversation = preview
                previewDeferred = CompletableDeferred()

                val confirmed = previewDeferred!!.await()

                if (confirmed) {
                    // 使用用户修改后的名称和描述
                    val assistant = Assistant(
                        id = newAssistantId,
                        name = botName.ifBlank { data.botInfo.name },
                        systemPrompt = botDescription.ifBlank { data.botInfo.description },
                        avatar = if (data.botInfo.avatar.isNotBlank()) Avatar.Image(data.botInfo.avatar) else Avatar.Dummy,
                        isMain = isMainAgent,
                        chatModelId = initialSettings.chatModelId,
                        embeddingModelId = initialSettings.embeddingModelId
                    )

                    val currentSettings = settingsStore.settingsFlow.value
                    val updatedAssistants = if (isMainAgent) {
                        currentSettings.assistants.map { it.copy(isMain = false) } + assistant
                    } else {
                        currentSettings.assistants + assistant
                    }
                    settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
                }

                confirmed
            }

            if (!success) {
                val currentSettings = settingsStore.settingsFlow.value
                if (currentSettings.assistants.any { it.id == newAssistantId }) {
                    settingsStore.update(
                        currentSettings.copy(
                            assistants = currentSettings.assistants.filter { it.id != newAssistantId }
                        )
                    )
                    conversationRepo.deleteConversationOfAssistant(newAssistantId)
                }
            }

            isImporting = false
            onFinish(success)
        }
    }

    fun saveLogToDisk(context: Context): String? {
        val logContent = importLog
        if (logContent.isBlank()) return null

        val fileName = "doubao_import_${System.currentTimeMillis()}.txt"
        return try {
            val file = File(context.getExternalFilesDir(null), fileName)
            file.writeText(logContent)
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun reset() {
        importManager.clear()
        importData = null
        botName = ""
        botDescription = ""
        isMainAgent = false
        roundsPerSession = 50
        isImporting = false
        previewConversation = null
        currentAssistantId = null
    }

    override fun onCleared() {
        super.onCleared()
        importManager.clear()
    }
}
