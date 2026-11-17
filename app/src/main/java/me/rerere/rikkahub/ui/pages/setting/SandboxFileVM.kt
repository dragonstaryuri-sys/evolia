package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sandboxfile.SandboxFileInfo
import me.rerere.rikkahub.data.sandboxfile.SandboxFileRepository

/**
 * 沙箱文件管理页 UI 状态。
 */
data class SandboxFileUiState(
    /** 首次扫描加载中 */
    val loading: Boolean = true,
    /** 扫描失败（区别于空列表，避免用户误以为文件被清空） */
    val scanError: Boolean = false,
    /** 当前文件列表（已按最后修改时间倒序） */
    val files: List<SandboxFileInfo> = emptyList(),
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
 * 沙箱文件管理 VM：页面文件列表状态、多选状态、扫描/删除/导出操作编排。
 * UI 不直接操作文件系统，全部经 [SandboxFileRepository]。
 */
class SandboxFileVM(
    private val context: Context,
    private val repository: SandboxFileRepository,
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
                _state.update { current ->
                    val validIds = result.files.map { it.id }.toSet()
                    current.copy(
                        loading = false,
                        scanError = false,
                        files = result.files,
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
    fun exportSingle(file: SandboxFileInfo, target: Uri) {
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
    fun exportSelected(treeUri: Uri) {
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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure { error ->
            if (error is ActivityNotFoundException) {
                showMessage(context.getString(R.string.sandbox_file_open_failed))
            } else {
                showMessage(context.getString(R.string.sandbox_file_open_failed))
            }
        }
    }

    private fun showMessage(message: String) {
        _messages.trySend(message)
    }
}
