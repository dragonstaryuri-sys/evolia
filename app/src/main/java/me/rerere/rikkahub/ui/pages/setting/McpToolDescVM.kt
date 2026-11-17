package me.rerere.rikkahub.ui.pages.setting

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.tools.ToolDescriptionService

private const val TAG = "McpToolDescVM"

/** 编辑保存的防抖间隔 */
private const val SAVE_DEBOUNCE_MS = 500L

/** 批量简化时每批最大工具数 */
private const val BATCH_MAX_TOOLS = 5

/** 批量简化时每批输入描述总长度上限（字符），超出则提前分批 */
private const val BATCH_MAX_INPUT_CHARS = 8000

/** 描述编辑项的唯一键：server_id + tool_name，避免不同服务同名工具互相覆盖 */
internal fun mcpToolKey(serverId: Uuid, toolName: String) = "$serverId|$toolName"

data class McpToolDescItem(
    val serverId: Uuid,
    val serverName: String,
    val tool: McpTool,
    /** 编辑框当前内容（可能是用户未保存的输入） */
    val editContent: String,
) {
    val key: String = mcpToolKey(serverId, tool.name)

    /** 服务端原始描述 */
    val originalDescription: String get() = tool.description.orEmpty()

    /** 是否存在已保存的自定义描述 */
    val isCustomized: Boolean get() = tool.customDescription != null
}

/** 批量简化中单个工具的处理状态 */
data class McpSimplifyItemState(
    val key: String,
    val serverId: Uuid,
    val serverName: String,
    val toolName: String,
    val originalText: String,
    /** 简化成功的结果；null 表示未成功 */
    val simplifiedText: String? = null,
    /** 失败原因；成功时为 null */
    val error: String? = null,
) {
    val isSuccess: Boolean get() = simplifiedText != null
}

data class McpToolDescUiState(
    val loading: Boolean = true,
    val items: List<McpToolDescItem> = emptyList(),
    val searchQuery: String = "",
    val selectedServerId: Uuid? = null,
    /** 正在简化中的 key 集合（单个简化） */
    val simplifyingKeys: Set<String> = emptySet(),
    /** 批量模式是否激活（显示勾选框） */
    val batchSelectionMode: Boolean = false,
    /** 已勾选待批量简化的 key 集合 */
    val batchSelectedKeys: Set<String> = emptySet(),
    /** 批量简化运行中 */
    val batchRunning: Boolean = false,
    /** 批量简化结果（含成功与失败），非空时展示预览对话框 */
    val batchResults: List<McpSimplifyItemState> = emptyList(),
    /** 批量预览对话框中勾选待应用的 key 集合 */
    val batchCheckedKeys: Set<String> = emptySet(),
    /** 单个简化成功后的预览（key + 文本），非空时展示确认对话框 */
    val singlePreview: Pair<String, String>? = null,
)

class McpToolDescVM(
    savedStateHandle: SavedStateHandle,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) : ViewModel() {

    private val initialServerId: Uuid? = savedStateHandle.get<String>("serverId")
        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    private val _uiState = MutableStateFlow(McpToolDescUiState())
    val uiState: StateFlow<McpToolDescUiState> = _uiState.asStateFlow()

    /**
     * 保存专用 scope：脱离 viewModelScope 生命周期，保证离开页面时
     * 防抖中的最后一次编辑仍能可靠落库（onCleared 中 join 等待完成）。
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingSaves = mutableMapOf<String, Job>()

    /** 每个工具当前的编辑内容，独立于 settings 流，避免保存回写时覆盖正在输入的文本 */
    private val editContents = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                rebuildItems(settings)
            }
        }
    }

    private fun rebuildItems(settings: Settings) {
        val servers = settings.mcpServers
        val newItems = mutableListOf<McpToolDescItem>()
        servers.forEach { serverConfig ->
            serverConfig.commonOptions.tools.forEach { tool ->
                val key = mcpToolKey(serverConfig.id, tool.name)
                // 编辑中的内容优先（可能尚未落库），否则以自定义/原始描述初始化
                val editContent = editContents[key]
                    ?: tool.customDescription
                    ?: tool.description
                    ?: ""
                newItems.add(
                    McpToolDescItem(
                        serverId = serverConfig.id,
                        serverName = serverConfig.commonOptions.name,
                        tool = tool,
                        editContent = editContent
                    )
                )
            }
        }
        // 清理已不存在的工具的编辑状态，避免内存泄漏与僵尸防抖任务
        val validKeys = newItems.map { it.key }.toSet()
        editContents.keys.retainAll(validKeys)
        _uiState.update { state ->
            // 首次加载时应用入口带来的初始服务过滤
            val selected = when {
                state.selectedServerId != null -> state.selectedServerId
                state.loading -> initialServerId
                else -> null
            }
            state.copy(
                loading = false,
                items = newItems,
                selectedServerId = selected,
                // 同步清理批量勾选中已失效的 key
                batchSelectedKeys = state.batchSelectedKeys intersect validKeys,
                batchCheckedKeys = state.batchCheckedKeys intersect validKeys
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectServer(serverId: Uuid?) {
        _uiState.update { it.copy(selectedServerId = serverId) }
    }

    fun setBatchSelectionMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                batchSelectionMode = enabled,
                batchSelectedKeys = if (enabled) it.batchSelectedKeys else emptySet()
            )
        }
    }

    fun toggleBatchSelect(key: String) {
        _uiState.update {
            val newSelection = if (key in it.batchSelectedKeys) {
                it.batchSelectedKeys - key
            } else {
                it.batchSelectedKeys + key
            }
            it.copy(batchSelectedKeys = newSelection)
        }
    }

    /**
     * 用户编辑描述。立即更新编辑状态并调度防抖保存。
     */
    fun updateDescription(item: McpToolDescItem, newContent: String) {
        editContents[item.key] = newContent
        _uiState.update { state ->
            state.copy(items = state.items.map {
                if (it.key == item.key) it.copy(editContent = newContent) else it
            })
        }
        scheduleSave(item.serverId, item.tool.name, newContent)
    }

    /**
     * 重置：清除自定义描述，编辑框恢复为 MCP 当前返回的原始描述。
     * 必须先取消该工具的待保存任务，否则旧的防抖保存会在重置后又把旧文本写回。
     */
    fun resetDescription(item: McpToolDescItem) {
        pendingSaves.remove(item.key)?.cancel()
        editContents[item.key] = item.originalDescription
        _uiState.update { state ->
            state.copy(items = state.items.map {
                if (it.key == item.key) it.copy(editContent = item.originalDescription) else it
            })
        }
        // 重置为未自定义状态，立即保存，不走防抖
        saveScope.launch {
            saveCustomDescription(item.serverId, item.tool.name, item.originalDescription, forceReset = true)
        }
    }

    /**
     * 调度防抖保存。
     */
    private fun scheduleSave(serverId: Uuid, toolName: String, newContent: String) {
        val key = mcpToolKey(serverId, toolName)
        pendingSaves.remove(key)?.cancel()
        pendingSaves[key] = saveScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            pendingSaves.remove(key)
            saveCustomDescription(serverId, toolName, newContent)
        }
    }

    /**
     * 「读取当前 settings → 计算 custom → 写回」走 SettingsStore 的原子变换事务。
     *
     * 事务性由 SettingsStore.update(transform) 内部的串行化锁保证：
     * 批量简化应用触发的并发保存、以及与 McpManager 后台 sync 的并发写入，
     * 都会在锁内基于彼此的最新结果增量修改，不会用陈旧快照互相覆盖。
     */
    private suspend fun saveCustomDescription(
        serverId: Uuid,
        toolName: String,
        newContent: String,
        forceReset: Boolean = false
    ) {
        runCatching {
            settingsStore.update { current ->
                val original = current.mcpServers
                    .firstOrNull { it.id == serverId }
                    ?.commonOptions?.tools?.firstOrNull { it.name == toolName }
                    ?.description
                // 编辑内容与原始描述一致或为空时存 null（视为未自定义），保持数据干净
                val custom = if (forceReset) {
                    null
                } else {
                    newContent.takeIf { it.isNotBlank() && it != original }
                }
                val newServers = current.mcpServers.map { serverConfig ->
                    if (serverConfig.id != serverId) return@map serverConfig
                    val newTools = serverConfig.commonOptions.tools.map { tool ->
                        if (tool.name != toolName) return@map tool
                        tool.copy(customDescription = custom)
                    }
                    serverConfig.clone(
                        commonOptions = serverConfig.commonOptions.copy(tools = newTools)
                    )
                }
                current.copy(mcpServers = newServers)
            }
        }.onFailure {
            Log.e(TAG, "saveCustomDescription failed for $toolName", it)
        }
    }

    /**
     * 单个简化：调用全局后台模型生成简化建议，结果经确认后写入。
     */
    fun simplifySingle(item: McpToolDescItem) {
        _uiState.update { it.copy(simplifyingKeys = it.simplifyingKeys + item.key) }
        viewModelScope.launch {
            val result: Result<String> = try {
                Result.success(
                    simplifyWithBackgroundModel(
                        buildSingleSimplifyPrompt(item.originalDescription.ifBlank { item.editContent })
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
            _uiState.update { state ->
                state.copy(simplifyingKeys = state.simplifyingKeys - item.key)
            }
            result
                .onSuccess { simplified ->
                    _uiState.update { state ->
                        state.copy(singlePreview = item.key to simplified)
                    }
                }
                .onFailure { error ->
                    emitError(error.message ?: "简化失败，请重试")
                }
        }
    }

    /** 用户在单个简化预览对话框中微调文本（只更新预览，确认后才落库） */
    fun updateSinglePreviewText(text: String) {
        _uiState.update { state ->
            state.singlePreview?.let { (key, _) -> state.copy(singlePreview = key to text) } ?: state
        }
    }

    /** 用户在单个简化预览中点击确认 */
    fun confirmSinglePreview() {
        val preview = _uiState.value.singlePreview ?: return
        val (key, simplified) = preview
        val item = _uiState.value.items.firstOrNull { it.key == key } ?: run {
            _uiState.update { it.copy(singlePreview = null) }
            return
        }
        editContents[key] = simplified
        _uiState.update { state ->
            state.copy(
                singlePreview = null,
                items = state.items.map {
                    if (it.key == key) it.copy(editContent = simplified) else it
                }
            )
        }
        scheduleSave(item.serverId, item.tool.name, simplified)
    }

    fun dismissSinglePreview() {
        _uiState.update { it.copy(singlePreview = null) }
    }

    /**
     * 批量简化所选工具。分批调用模型，全部完成后展示预览供逐项确认。
     * 用户中途离开页面会取消未完成的简化，但已确认落库的编辑不受影响。
     */
    fun startBatchSimplify() {
        val state = _uiState.value
        if (state.batchRunning) return
        val selectedItems = state.items.filter { it.key in state.batchSelectedKeys }
        if (selectedItems.isEmpty()) return

        _uiState.update { it.copy(batchRunning = true, batchResults = emptyList()) }
        viewModelScope.launch {
            val results = mutableListOf<McpSimplifyItemState>()
            selectedItems.chunkedBySize(BATCH_MAX_TOOLS, BATCH_MAX_INPUT_CHARS).forEach { batch ->
                val batchResults = runBatch(batch)
                results.addAll(batchResults)
                // 每批完成后立即更新中间状态，供进度展示
                _uiState.update { s ->
                    s.copy(batchResults = results.toList())
                }
            }
            _uiState.update { s ->
                s.copy(
                    batchRunning = false,
                    batchResults = results.toList(),
                    batchCheckedKeys = results.filter { it.isSuccess }.map { it.key }.toSet()
                )
            }
        }
    }

    private suspend fun runBatch(batch: List<McpToolDescItem>): List<McpSimplifyItemState> {
        // 单项批退化为单工具请求（纯文本输出，解析更可靠）
        return if (batch.size == 1) {
            val item = batch.first()
            val result = runCatching {
                simplifyWithBackgroundModel(buildSingleSimplifyPrompt(item.originalDescription))
            }
            listOf(item.toSimplifyState(result))
        } else {
            val result = runCatching {
                simplifyBatchWithBackgroundModel(batch)
            }
            result.fold(
                onSuccess = { simplifiedMap ->
                    batch.map { item ->
                        val simplified = simplifiedMap[item.tool.name]
                        if (simplified.isNullOrBlank()) {
                            item.toSimplifyState(Result.failure(IllegalStateException("模型未返回该工具的结果")))
                        } else {
                            item.toSimplifyState(Result.success(simplified))
                        }
                    }
                },
                onFailure = { error ->
                    batch.map { item ->
                        item.toSimplifyState(Result.failure(IllegalStateException(error.message ?: "批量简化失败")))
                    }
                }
            )
        }
    }

    private fun McpToolDescItem.toSimplifyState(result: Result<String>): McpSimplifyItemState {
        return result.fold(
            onSuccess = { text ->
                McpSimplifyItemState(
                    key = key,
                    serverId = serverId,
                    serverName = serverName,
                    toolName = tool.name,
                    originalText = originalDescription,
                    simplifiedText = text
                )
            },
            onFailure = { error ->
                McpSimplifyItemState(
                    key = key,
                    serverId = serverId,
                    serverName = serverName,
                    toolName = tool.name,
                    originalText = originalDescription,
                    error = error.message ?: "简化失败"
                )
            }
        )
    }

    /** 单独重试批量结果中失败的项（逐个请求，避免整批重跑） */
    fun retryBatchItem(state: McpSimplifyItemState) {
        if (_uiState.value.batchRunning) return
        viewModelScope.launch {
            _uiState.update { s -> s.copy(batchRunning = true) }
            val item = _uiState.value.items.firstOrNull { it.key == state.key }
            if (item == null) {
                _uiState.update { s -> s.copy(batchRunning = false) }
                return@launch
            }
            val newResult = runBatch(listOf(item)).first()
            _uiState.update { s ->
                s.copy(
                    batchRunning = false,
                    batchResults = s.batchResults.map {
                        if (it.key == newResult.key) newResult else it
                    },
                    batchCheckedKeys = if (newResult.isSuccess) {
                        s.batchCheckedKeys + newResult.key
                    } else {
                        s.batchCheckedKeys - newResult.key
                    }
                )
            }
        }
    }

    fun toggleBatchChecked(key: String) {
        _uiState.update {
            val newChecked = if (key in it.batchCheckedKeys) {
                it.batchCheckedKeys - key
            } else {
                it.batchCheckedKeys + key
            }
            it.copy(batchCheckedKeys = newChecked)
        }
    }

    /** 应用批量预览中勾选的简化结果：写入编辑状态并按自动保存机制落库 */
    fun applyBatchResults() {
        val state = _uiState.value
        val toApply = state.batchResults.filter {
            it.isSuccess && it.key in state.batchCheckedKeys
        }
        toApply.forEach { result ->
            editContents[result.key] = result.simplifiedText!!
        }
        _uiState.update { s ->
            s.copy(
                items = s.items.map { item ->
                    val applied = toApply.firstOrNull { it.key == item.key }
                    if (applied != null) item.copy(editContent = applied.simplifiedText!!) else item
                },
                batchResults = emptyList(),
                batchCheckedKeys = emptySet(),
                batchSelectionMode = false,
                batchSelectedKeys = emptySet()
            )
        }
        toApply.forEach { result ->
            scheduleSave(result.serverId, result.toolName, result.simplifiedText!!)
        }
    }

    fun dismissBatchResults() {
        _uiState.update { it.copy(batchResults = emptyList(), batchCheckedKeys = emptySet()) }
    }

    private val errorEvents = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = errorEvents.asStateFlow()

    fun consumeError() {
        errorEvents.value = null
    }

    private fun emitError(message: String) {
        errorEvents.value = message
    }

    // ==================== 全局后台模型调用 ====================

    private suspend fun simplifyWithBackgroundModel(prompt: String): String =
        // 2026-11-16：抽取为 MCP / Extensions 共用的 ToolDescriptionService（详设 18.5）
        ToolDescriptionService.generateWithBackgroundModel(settingsStore, providerManager, prompt)

    private suspend fun simplifyBatchWithBackgroundModel(
        batch: List<McpToolDescItem>
    ): Map<String, String> {
        val input = JsonArray(batch.map { item ->
            buildJsonObject {
                put("name", JsonPrimitive(item.tool.name))
                put("description", JsonPrimitive(item.originalDescription))
            }
        })
        val prompt = buildBatchSimplifyPrompt(input.toString())
        val raw = simplifyWithBackgroundModel(prompt)
        return parseBatchSimplifyResponse(raw, batch)
    }

    private fun parseBatchSimplifyResponse(
        raw: String,
        batch: List<McpToolDescItem>
    ): Map<String, String> {
        return parseBatchSimplifyResponse(raw, batch.map { it.tool.name }.toSet())
    }

    override fun onCleared() {
        // 等待尚未完成的防抖保存落库，保证离开页面（含退出应用）时最后一次编辑不丢失
        val pending = synchronized(pendingSaves) { pendingSaves.values.toList() }
        runBlocking {
            pending.joinAll()
        }
        saveScope.cancel()
        super.onCleared()
    }
}

/**
 * 按数量与输入总长度双重限制分批，避免超出模型上下文。
 */
internal fun List<McpToolDescItem>.chunkedBySize(
    maxTools: Int,
    maxChars: Int
): List<List<McpToolDescItem>> {
    val batches = mutableListOf<List<McpToolDescItem>>()
    var current = mutableListOf<McpToolDescItem>()
    var currentChars = 0
    forEach { item ->
        val itemChars = item.originalDescription.length
        val wouldExceed = current.isNotEmpty() &&
            (current.size >= maxTools || currentChars + itemChars > maxChars)
        if (wouldExceed) {
            batches.add(current)
            current = mutableListOf()
            currentChars = 0
        }
        current.add(item)
        currentChars += itemChars
    }
    if (current.isNotEmpty()) batches.add(current)
    return batches
}

internal fun buildSingleSimplifyPrompt(originalDescription: String): String =
    ToolDescriptionService.buildSingleSimplifyPrompt(originalDescription)

internal fun buildBatchSimplifyPrompt(inputJson: String): String =
    ToolDescriptionService.buildBatchSimplifyPrompt(inputJson)

internal fun parseBatchSimplifyResponse(
    raw: String,
    expectedNames: Set<String>
): Map<String, String> = ToolDescriptionService.parseBatchSimplifyResponse(raw, expectedNames)

