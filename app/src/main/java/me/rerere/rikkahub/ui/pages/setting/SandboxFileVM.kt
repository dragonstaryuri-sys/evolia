package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sandboxfile.SandboxFileInfo
import me.rerere.rikkahub.data.sandboxfile.SandboxFileRepository
import me.rerere.rikkahub.data.sandboxfile.SandboxFileRules
import kotlin.uuid.Uuid

/**
 * Agent 文件管理页 UI 状态。
 */
data class SandboxFileUiState(
    /** 首次扫描加载中 */
    val loading: Boolean = true,
    /** 扫描失败（区别于空列表，避免用户误以为文件被清空） */
    val scanError: Boolean = false,
    /** 当前文件列表（已按最后修改时间倒序） */
    val files: List<SandboxFileInfo> = emptyList(),
    /** 分组键 → 展示标签（助手名 / 会话标题 / 生图 / 回退短名） */
    val groupLabels: Map<String, String> = emptyMap(),
    /** 多选模式 */
    val selectionMode: Boolean = false,
    /** 已选文件 id 集合（稳定相对路径） */
    val selectedIds: Set<String> = emptySet(),
    /** 删除/导出等耗时操作进行中 */
    val operating: Boolean = false,
    /** 当前预览的图片文件（null = 关闭预览） */
    val previewFile: SandboxFileInfo? = null,
)

/**
 * Agent 文件管理 VM：页面文件列表状态、分组标签解析、多选状态、扫描/删除/导出/复制路径操作编排。
 * UI 不直接操作文件系统，全部经 [SandboxFileRepository]。
 */
class SandboxFileVM(
    private val context: Context,
    private val repository: SandboxFileRepository,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SandboxFileUiState())
    val state: StateFlow<SandboxFileUiState> = _state.asStateFlow()

    /** 一次性操作反馈（Toast 展示，Dialog 之上也可见） */
    private val _messages = Channel<String>(capacity = 16)
    val messages = _messages.receiveAsFlow()

    init {
        refresh()
    }

    /** 重新扫描文件系统（进入页面 / 用户主动刷新 / 删除完成后同步） */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, scanError = false) }
            applyScan(repository.scanFiles())
        }
    }

    private suspend fun applyScan(result: SandboxFileRepository.ScanResult) {
        when (result) {
            is SandboxFileRepository.ScanResult.Success -> {
                val labels = resolveGroupLabels(result.files)
                _state.update { current ->
                    val validIds = result.files.map { it.id }.toSet()
                    current.copy(
                        loading = false,
                        scanError = false,
                        files = result.files,
                        groupLabels = labels,
                        // 清理已不存在文件的选择状态，防止选择错位
                        selectedIds = current.selectedIds.intersect(validIds),
                        // 预览中的文件被删除时关闭预览
                        previewFile = current.previewFile?.takeIf { it.id in validIds }
                    )
                }
            }

            is SandboxFileRepository.ScanResult.Failure -> {
                _state.update { it.copy(loading = false, scanError = true) }
                showMessage(context.getString(R.string.sandbox_file_scan_failed))
            }
        }
    }

    /**
     * 解析分组展示标签。
     * 优先级：生图固定组 > 助手名（新沙箱布局）> 会话标题（旧布局，轻量查询）> 回退短名。
     */
    private suspend fun resolveGroupLabels(files: List<SandboxFileInfo>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val keys = files.map { SandboxFileRules.groupKeyOf(it) }.toSet()
            val assistantNames = settingsStore.settingsFlow.value.assistants
                .associate { it.id.toString() to it.name }
            val titleCache = mutableMapOf<String, String?>()
            keys.associate { key ->
                val label = when (key) {
                    SandboxFileRules.GROUP_KEY_GENERATED_IMAGES ->
                        context.getString(R.string.sandbox_file_group_generated_images)
                    SandboxFileRules.GROUP_KEY_SANDBOX_ROOT ->
                        context.getString(R.string.sandbox_file_group_root)
                    else -> {
                        val dirName = key.removePrefix("sandbox:")
                        assistantNames[dirName]
                            ?: resolveConversationTitle(dirName, titleCache)
                            ?: context.getString(R.string.sandbox_file_group_unknown_dir, dirName.take(8))
                    }
                }
                key to label
            }
        }

    /**
     * 旧会话布局目录 → 会话标题（单列轻量查询，结果缓存；查不到 = 会话已删除）。
     */
    private suspend fun resolveConversationTitle(
        dirName: String,
        cache: MutableMap<String, String?>
    ): String? {
        if (!isUuidLike(dirName)) return null
        if (cache.containsKey(dirName)) return cache[dirName]
        val title = runCatching {
            conversationRepository.getConversationTitleById(Uuid.parse(dirName))
        }.getOrNull()
        cache[dirName] = title
        return title
    }

    private fun isUuidLike(value: String): Boolean {
        return runCatching { Uuid.parse(value) }.isSuccess
    }

    // ===== 多选 =====

    fun enterSelectionMode(file: SandboxFileInfo) {
        _state.update { it.copy(selectionMode = true, selectedIds = setOf(file.id)) }
    }

    fun toggleSelected(file: SandboxFileInfo) {
        _state.update { current ->
            val selected = if (file.id in current.selectedIds) {
                current.selectedIds - file.id
            } else {
                current.selectedIds + file.id
            }
            current.copy(
                selectedIds = selected,
                // 取消到零个时自动退出多选模式
                selectionMode = if (selected.isEmpty()) false else current.selectionMode
            )
        }
    }

    /** 全选 / 取消全选（多选模式顶栏） */
    fun toggleSelectAll() {
        _state.update { current ->
            val allIds = current.files.map { it.id }.toSet()
            if (current.selectedIds.containsAll(allIds)) {
                current.copy(selectedIds = emptySet(), selectionMode = false)
            } else {
                current.copy(selectedIds = allIds)
            }
        }
    }

    fun exitSelectionMode() {
        _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    // ===== 删除 =====

    /**
     * 删除选中的文件（批量入口已做二次确认）。
     * 完成后重新扫描同步列表；部分失败时逐类反馈成功/失败数量。
     */
    fun deleteSelected() {
        val snapshot = _state.value
        val targets = snapshot.files.filter { it.id in snapshot.selectedIds }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(operating = true) }
            val results = targets.map { target ->
                target to repository.deleteFile(target)
            }
            applyScan(repository.scanFiles())
            _state.update { it.copy(operating = false, selectionMode = false, selectedIds = emptySet()) }
            reportDeleteResult(results)
        }
    }

    /**
     * 删除单个文件（预览页入口，调用前已做二次确认）。
     */
    fun deleteSingle(file: SandboxFileInfo) {
        viewModelScope.launch {
            _state.update { it.copy(operating = true) }
            val result = repository.deleteFile(file)
            applyScan(repository.scanFiles())
            _state.update { it.copy(operating = false, previewFile = null) }
            reportDeleteResult(listOf(file to result))
        }
    }

    private fun reportDeleteResult(results: List<Pair<SandboxFileInfo, SandboxFileRepository.DeleteResult>>) {
        val deleted = results.count { it.second == SandboxFileRepository.DeleteResult.DELETED }
        val notFound = results.count { it.second == SandboxFileRepository.DeleteResult.NOT_FOUND }
        val failed = results.count {
            it.second == SandboxFileRepository.DeleteResult.FAILED ||
                it.second == SandboxFileRepository.DeleteResult.OUTSIDE_SANDBOX
        }
        val message = when {
            deleted > 0 && failed > 0 ->
                context.getString(R.string.sandbox_file_delete_partial, deleted, failed)
            deleted > 0 && notFound > 0 ->
                context.getString(R.string.sandbox_file_delete_with_missing, deleted, notFound)
            deleted > 0 ->
                context.getString(R.string.sandbox_file_delete_success, deleted)
            failed > 0 ->
                context.getString(R.string.sandbox_file_delete_failed)
            notFound > 0 ->
                context.getString(R.string.sandbox_file_already_missing)
            else -> return
        }
        showMessage(message)
    }

    // ===== 导出 =====

    /**
     * 单文件导出到用户选择的位置（ACTION_CREATE_DOCUMENT 返回的 Uri）。
     * 保存到本地不删除沙箱原文件，也不从列表移除。
     */
    fun exportSingle(file: SandboxFileInfo, target: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(operating = true) }
            val success = repository.exportFile(file, target)
            _state.update { it.copy(operating = false) }
            showMessage(
                if (success) {
                    context.getString(R.string.sandbox_file_save_success)
                } else {
                    context.getString(R.string.sandbox_file_save_failed, file.name)
                }
            )
        }
    }

    /**
     * 批量导出选中文件到用户选择的目录（ACTION_OPEN_DOCUMENT_TREE）。
     * 逐文件记录结果，明确反馈成功/失败数量，不以最后一个文件判定整批。
     */
    fun exportSelected(treeUri: android.net.Uri) {
        val snapshot = _state.value
        val targets = snapshot.files.filter { it.id in snapshot.selectedIds }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(operating = true) }
            var success = 0
            var failed = 0
            targets.forEach { target ->
                if (repository.exportFileToDirectory(target, treeUri)) {
                    success++
                } else {
                    failed++
                }
            }
            _state.update { it.copy(operating = false) }
            showMessage(
                if (success > 0 && failed > 0) {
                    context.getString(R.string.sandbox_file_save_partial, success, failed)
                } else if (success > 0) {
                    context.getString(R.string.sandbox_file_save_success)
                } else {
                    context.getString(R.string.sandbox_file_save_all_failed)
                }
            )
        }
    }

    // ===== 复制路径 =====

    /**
     * 复制文件的绝对路径到剪贴板（供分享、备份提取，或让 Agent 在 Python 代码里以绝对路径访问）。
     */
    fun copyPath(file: SandboxFileInfo) {
        val absPath = repository.resolveFile(file)?.absolutePath
        if (absPath == null) {
            showMessage(context.getString(R.string.sandbox_file_file_missing, file.name))
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            showMessage(context.getString(R.string.sandbox_file_copy_path_failed))
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("evolia_agent_file_path", absPath))
        showMessage(context.getString(R.string.sandbox_file_path_copied))
    }

    // ===== 预览 =====

    fun openPreview(file: SandboxFileInfo) {
        _state.update { it.copy(previewFile = file) }
    }

    fun closePreview() {
        _state.update { it.copy(previewFile = null) }
    }

    // ===== 系统查看器打开其他文件 =====

    /**
     * 通过系统文件查看器打开文件（FileProvider + ACTION_VIEW）。
     * 无可处理该类型的应用时提示改用「保存到本地」。
     */
    fun openWithViewer(file: SandboxFileInfo) {
        val uri = repository.contentUriFor(file)
        if (uri == null) {
            showMessage(context.getString(R.string.sandbox_file_file_missing, file.name))
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType ?: "application/octet-stream")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure { _ ->
            showMessage(context.getString(R.string.sandbox_file_open_failed))
        }
    }

    private fun showMessage(message: String) {
        _messages.trySend(message)
    }
}
