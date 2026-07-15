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

    // 记录本次导入使用的智能体 ID
    private var targetAssistantId: Uuid? = null
    private var isOverwriteMode = false

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

    /**
     * 开始导入
     * @param onFinish 回调函数，参数为：(成功状态, assistantId, 会话数量)
     */
    fun startImport(onFinish: (Boolean, String?, Int) -> Unit) {
        val data = importData ?: return
        isImporting = true

        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            val existingMain = currentSettings.assistants.find { it.isMain }

            // 确定目标 ID 和是否为覆盖模式
            val finalTargetId: Uuid
            if (isMainAgent && existingMain != null) {
                finalTargetId = existingMain.id
                isOverwriteMode = true
            } else {
                finalTargetId = Uuid.random()
                isOverwriteMode = false
            }
            targetAssistantId = finalTargetId

            // 执行导入过程
            val sessionCount = importManager.performImport(
                data = data,
                assistantId = finalTargetId,
                roundsPerSession = roundsPerSession
            ) { preview ->
                previewConversation = preview
                previewDeferred = CompletableDeferred()

                val confirmed = previewDeferred!!.await()

                if (confirmed) {
                    val settings = settingsStore.settingsFlow.value

                    val updatedAssistants = if (isOverwriteMode) {
                        // 覆盖模式：更新现有智能体的信息
                        settings.assistants.map {
                            if (it.id == finalTargetId) {
                                it.copy(
                                    name = botName.ifBlank { data.botInfo.name },
                                    systemPrompt = botDescription.ifBlank { data.botInfo.description },
                                    avatar = if (data.botInfo.avatar.isNotBlank()) Avatar.Image(data.botInfo.avatar) else it.avatar,
                                    isMain = true
                                )
                            } else if (isMainAgent) {
                                // 如果设为主智能体，确保其他智能体不是主智能体
                                it.copy(isMain = false)
                            } else {
                                it
                            }
                        }
                    } else {
                        // 新建模式
                        val newAssistant = Assistant(
                            id = finalTargetId,
                            name = botName.ifBlank { data.botInfo.name },
                            systemPrompt = botDescription.ifBlank { data.botInfo.description },
                            avatar = if (data.botInfo.avatar.isNotBlank()) Avatar.Image(data.botInfo.avatar) else Avatar.Dummy,
                            isMain = isMainAgent,
                            chatModelId = settings.chatModelId,
                            embeddingModelId = settings.embeddingModelId
                        )
                        if (isMainAgent) {
                            settings.assistants.map { it.copy(isMain = false) } + newAssistant
                        } else {
                            settings.assistants + newAssistant
                        }
                    }
                    settingsStore.update(settings.copy(assistants = updatedAssistants))
                }

                confirmed
            }

            val success = sessionCount > 0

            // 如果导入失败且不是覆盖模式，则尝试清理新建的智能体
            if (!success && !isOverwriteMode) {
                val settings = settingsStore.settingsFlow.value
                if (settings.assistants.any { it.id == finalTargetId }) {
                    settingsStore.update(
                        settings.copy(
                            assistants = settings.assistants.filter { it.id != finalTargetId }
                        )
                    )
                    conversationRepo.deleteConversationOfAssistant(finalTargetId)
                }
            }

            isImporting = false
            onFinish(success, finalTargetId.toString(), sessionCount)
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
        targetAssistantId = null
        isOverwriteMode = false
    }

    override fun onCleared() {
        super.onCleared()
        importManager.clear()
    }
}
