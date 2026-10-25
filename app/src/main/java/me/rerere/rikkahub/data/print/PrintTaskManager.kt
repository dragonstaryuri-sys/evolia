package me.rerere.rikkahub.data.print

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 打印任务管理：任务创建、确认 / 自动打印、串行执行、取消、进度与结果管理。
 *
 * 状态机（集中流转，UI / 工具返回 / 持久化共用）：
 * PREPARING → AWAITING_CONFIRMATION → CONNECTING → SENDING → COMPLETED
 *                                        ↘ FAILED / CANCELLED / OUTCOME_UNKNOWN
 * - 自动打印开启时：PREPARING 后直接进入执行，不等待确认；
 * - 确认模式：工具立即返回，用户在任务卡片确认后才执行（不触发 AI 轮次）；
 * - 发送开始后取消只能停止后续数据（可能部分出纸）；发送中断且无法确认结果
 *   时进入 OUTCOME_UNKNOWN，绝不自动重试；
 * - 任务按 [executionMutex] 串行执行，状态原子转移保证同一任务不会重复执行。
 */
class PrintTaskManager(
    private val context: Context,
    private val printerManager: PrinterManager,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) {

    private val tasksMutex = Mutex()
    private val executionMutex = Mutex()    // 打印任务串行化：同一时刻只有一个任务占用打印机
    private val cancelRequested = ConcurrentHashMap<String, Boolean>()

    private val _tasks = MutableStateFlow<List<PrintTask>>(emptyList())
    val tasks: StateFlow<List<PrintTask>> = _tasks.asStateFlow()

    private val tasksFile: File = File(context.filesDir, "print_tasks").resolve("tasks.json")
    private val previewDir: File = File(context.filesDir, "print_previews")

    init {
        appScope.launch(Dispatchers.IO) { loadAndRecover() }
    }

    // ---------------- 任务创建 ----------------

    /**
     * 创建打印任务。确认模式下渲染预览后进入 AWAITING_CONFIRMATION；
     * 自动打印（或用户主动触发的测试打印）创建后直接进入执行队列。
     */
    suspend fun createPrintTask(
        title: String,
        markdown: String,
        bypassConfirmation: Boolean = false,
    ): CreatePrintTaskResult {
        val content = markdown.trim()
        if (content.isEmpty()) {
            return CreatePrintTaskResult.Rejected(PrintErrorCode.EMPTY_CONTENT, "打印内容为空")
        }
        val config = printerManager.printerConfig()
        if (!config.isConfigured) {
            return CreatePrintTaskResult.Rejected(
                PrintErrorCode.NOT_CONFIGURED,
                "尚未配置打印机，请先在打印机服务页面添加设备"
            )
        }
        val autoPrint = bypassConfirmation || config.autoPrint

        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val task = PrintTask(
            record = PrintTaskRecord(
                taskId = taskId,
                title = title.ifBlank { "打印任务" },
                status = PrintTaskStatus.PREPARING,
                createdAt = now,
                updatedAt = now,
                deviceName = config.name.takeIf { config.isConfigured },
                content = content,
            ),
        )
        tasksMutex.withLock {
            _tasks.value = listOf(task) + _tasks.value
        }
        persistSnapshot()

        if (autoPrint) {
            enqueueExecution(taskId)
            // 自动打印仍渲染预览供任务卡片展示
            appScope.launch(Dispatchers.Default) { renderPreviewFor(taskId) }
        } else {
            appScope.launch(Dispatchers.Default) {
                renderPreviewFor(taskId)
                markReadyForConfirmation(taskId)
            }
        }
        return CreatePrintTaskResult.Created(taskId = taskId, autoPrint = autoPrint)
    }

    /** 测试打印：固定短内容，用户主动点击时调用（视为已确认） */
    suspend fun createTestPrint(): CreatePrintTaskResult {
        return createPrintTask(
            title = "Evolia 打印测试",
            markdown = TEST_PRINT_CONTENT,
            bypassConfirmation = true,
        )
    }

    // ---------------- 确认 / 取消 ----------------

    /**
     * 用户确认打印。仅 AWAITING_CONFIRMATION 状态有效（幂等：
     * 重复点击、任务已执行/终态时返回 false，不产生第二次出纸）。
     */
    suspend fun confirm(taskId: String): Boolean {
        val task = findTask(taskId) ?: return false
        if (task.record.status != PrintTaskStatus.AWAITING_CONFIRMATION) return false
        enqueueExecution(taskId)
        return true
    }

    /**
     * 取消任务。等待确认/准备中 → 直接取消；发送中 → 请求协作式取消
     * （停止后续数据，可能部分出纸，由执行器落终态）。
     */
    suspend fun cancel(taskId: String): Boolean {
        val task = findTask(taskId) ?: return false
        return when (task.record.status) {
            PrintTaskStatus.PREPARING, PrintTaskStatus.AWAITING_CONFIRMATION -> {
                transitionStatus(taskId, PrintTaskStatus.CANCELLED)
                true
            }
            PrintTaskStatus.CONNECTING, PrintTaskStatus.SENDING -> {
                cancelRequested[taskId] = true
                tasksMutex.withLock {
                    _tasks.value = _tasks.value.map {
                        // 发送中取消：标记"取消中"，由执行器在数据块间协作停止后落终态
                        if (it.record.taskId == taskId) it.copy(cancelRequested = true) else it
                    }
                }
                true
            }
            else -> false
        }
    }

    // ---------------- 执行 ----------------

    private fun enqueueExecution(taskId: String) {
        appScope.launch {
            executionMutex.withLock {
                executeTask(taskId)
            }
        }
    }

    /**
     * 执行单个任务。入口处做原子状态转移（仅 AWAITING_CONFIRMATION / PREPARING
     * → CONNECTING 可触发；后者只出现在自动打印直接入队的场景），
     * 天然防止重复确认/重复回调导致的重复出纸。
     */
    private suspend fun executeTask(taskId: String) {
        val content = tasksMutex.withLock {
            val task = _tasks.value.find { it.record.taskId == taskId }
                ?: return
            when (task.record.status) {
                // 仅等待确认（或自动打印尚未开跑）的任务可被启动；终态任务禁止再次执行
                PrintTaskStatus.AWAITING_CONFIRMATION, PrintTaskStatus.PREPARING -> {
                    _tasks.value = _tasks.value.map {
                        if (it.record.taskId == taskId) {
                            it.copy(record = it.record.copy(status = PrintTaskStatus.CONNECTING, updatedAt = System.currentTimeMillis()))
                        } else it
                    }
                    task.record.content
                }
                else -> return
            }
        } ?: return

        persistSnapshot()
        try {
            // 连接（自动重连：需要时才连接）
            val connection = printerManager.ensureConnected()

            // 标记发送阶段
            updateRecord(taskId) { record ->
                record.copy(status = PrintTaskStatus.SENDING, updatedAt = System.currentTimeMillis())
            }

            // 每次打印读取当前配置：浓度等参数即时生效，无需重连
            val config = printerManager.printerConfig()
            val adapter = PrinterAdapterFactory.create(config.protocol, config.energyPercent)
            val shouldAbort = { cancelRequested.containsKey(taskId) }
            val outcome = withContext(Dispatchers.IO) {
                runInterruptible {
                    adapter.print(
                        connection = connection,
                        markdown = content,
                        shouldAbort = shouldAbort,
                        onRowsSent = { sentRows, totalRows ->
                            updateProgress(taskId, sentRows, totalRows)
                        },
                    )
                }
            }

            // 系统定义的完成：全部数据已发送（不代表物理出纸已确认）
            finalizeTask(taskId, PrintTaskStatus.COMPLETED, rowCount = outcome.totalRows)
        } catch (e: PrintException) {
            handleExecutionFailure(taskId, e)
        } catch (e: CancellationException) {
            // 协程被取消（应用关闭）：状态留在 CONNECTING/SENDING，
            // 下次启动由恢复逻辑判定为 OUTCOME_UNKNOWN
            throw e
        } catch (e: Exception) {
            finalizeTask(
                taskId = taskId,
                status = PrintTaskStatus.FAILED,
                errorCode = PrintErrorCode.RENDER_FAILED,
                errorMessage = "打印执行异常：${e.message ?: e.javaClass.simpleName}",
            )
        } finally {
            printerManager.releaseConnection()
        }
    }

    /** 执行失败分类：发送前失败 → FAILED（可安全重试）；发送中断 → OUTCOME_UNKNOWN */
    private suspend fun handleExecutionFailure(taskId: String, e: PrintException) {
        val sentRows = findTask(taskId)?.record?.sentRows ?: 0
        val status = when {
            e.errorCode == PrintErrorCode.CANCELLED_DURING_SEND -> PrintTaskStatus.CANCELLED
            sentRows > 0 && (e.errorCode == PrintErrorCode.DISCONNECTED ||
                e.errorCode == PrintErrorCode.WRITE_FAILED ||
                e.errorCode == PrintErrorCode.CONNECT_TIMEOUT) -> PrintTaskStatus.OUTCOME_UNKNOWN
            else -> PrintTaskStatus.FAILED
        }
        finalizeTask(
            taskId = taskId,
            status = status,
            errorCode = e.errorCode,
            errorMessage = humanizePrintError(e),
        )
    }

    // ---------------- 状态操作 ----------------

    private suspend fun transitionStatus(taskId: String, status: PrintTaskStatus) {
        tasksMutex.withLock {
            _tasks.value = _tasks.value.map {
                if (it.record.taskId == taskId) {
                    it.copy(record = it.record.copy(status = status, updatedAt = System.currentTimeMillis()))
                } else it
            }
        }
        persistSnapshot()
    }

    /**
     * 预览渲染完成后把任务置为待确认。仅在仍处于 PREPARING 时生效
     * （原子条件转移）：自动打印任务此时可能已进入执行，不得回退状态，
     * 否则会被用户重复确认造成二次出纸。
     */
    private suspend fun markReadyForConfirmation(taskId: String) {
        tasksMutex.withLock {
            _tasks.value = _tasks.value.map {
                if (it.record.taskId == taskId && it.record.status == PrintTaskStatus.PREPARING) {
                    it.copy(
                        record = it.record.copy(
                            status = PrintTaskStatus.AWAITING_CONFIRMATION,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } else it
            }
        }
        persistSnapshot()
    }

    private suspend fun finalizeTask(
        taskId: String,
        status: PrintTaskStatus,
        errorCode: PrintErrorCode? = null,
        errorMessage: String? = null,
        rowCount: Int? = null,
    ) {
        tasksMutex.withLock {
            _tasks.value = _tasks.value.map { task ->
                if (task.record.taskId == taskId) {
                    task.copy(
                        record = task.record.copy(
                            status = status,
                            errorCode = errorCode,
                            errorMessage = errorMessage,
                            rowCount = rowCount ?: task.record.rowCount,
                            updatedAt = System.currentTimeMillis(),
                            // 发送前失败的 FAILED 任务在内存中保留内容以支持"重试"；
                            // 其余终态任务清空内容控制体积（持久化时 FAILED 也不落内容）
                            content = if (status == PrintTaskStatus.FAILED) task.record.content else null,
                        ),
                        cancelRequested = false,
                    )
                } else task
            }
        }
        cancelRequested.remove(taskId)
        persistSnapshot()
    }

    /**
     * 重试失败任务。仅允许"发送前失败"（sentRows == 0，尚未出纸）的任务重试，
     * 防止重复出纸；重试关联原任务（不新建），走同一执行入口。
     */
    suspend fun retry(taskId: String): Boolean {
        val task = findTask(taskId) ?: return false
        val record = task.record
        if (record.status != PrintTaskStatus.FAILED) return false
        if (record.sentRows > 0) return false
        if (record.content.isNullOrBlank()) return false
        // 复位到等待确认状态，由 executeTask 的原子转移启动
        tasksMutex.withLock {
            _tasks.value = _tasks.value.map {
                if (it.record.taskId == taskId) {
                    it.copy(
                        record = it.record.copy(
                            status = PrintTaskStatus.AWAITING_CONFIRMATION,
                            errorCode = null,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                } else it
            }
        }
        enqueueExecution(taskId)
        return true
    }

    private suspend fun updateRecord(taskId: String, transform: (PrintTaskRecord) -> PrintTaskRecord) {
        tasksMutex.withLock {
            _tasks.value = _tasks.value.map {
                if (it.record.taskId == taskId) it.copy(record = transform(it.record)) else it
            }
        }
    }

    /** 进度更新仅改内存状态（高频，来自发送循环回调），原子 CAS 防止覆盖并发状态转移 */
    private fun updateProgress(taskId: String, sentRows: Int, totalRows: Int) {
        _tasks.update { list ->
            list.map { task ->
                if (task.record.taskId == taskId && !task.record.status.isTerminal) {
                    task.copy(record = task.record.copy(sentRows = sentRows, rowCount = totalRows))
                } else task
            }
        }
    }

    private fun findTask(taskId: String): PrintTask? {
        return _tasks.value.find { it.record.taskId == taskId }
    }

    // ---------------- 预览 ----------------

    private suspend fun renderPreviewFor(taskId: String) {
        val task = findTask(taskId) ?: return
        val content = task.record.content ?: return
        val outFile = previewFileOf(taskId)
        val rendered = runCatching {
            PrintContentRenderer.renderPreviewPng(content, outFile)
        }.getOrNull()
        if (rendered != null) {
            tasksMutex.withLock {
                _tasks.value = _tasks.value.map {
                    if (it.record.taskId == taskId) it.copy(previewFile = outFile) else it
                }
            }
        }
    }

    private fun previewFileOf(taskId: String): File = previewDir.resolve("$taskId.png")

    // ---------------- 持久化与恢复 ----------------

    /** 任务列表快照落盘（应用私有空间；仅未完成任务保留 content） */
    private suspend fun persistSnapshot() {
        val snapshot = _tasks.value
        withContext(Dispatchers.IO) {
            runCatching {
                tasksFile.parentFile?.mkdirs()
                val records = snapshot
                    .take(MAX_PERSISTED_TASKS)
                    .map { task ->
                        // 终态任务不保留内容（含 FAILED：重试仅在本次进程内可用）
                        if (task.record.status.isTerminal) {
                            task.record.copy(content = null)
                        } else task.record
                    }
                tasksFile.writeText(JsonInstant.encodeToString(PrintTaskStoreFile(records)))
            }
        }
    }

    /**
     * 启动恢复：
     * - AWAITING_CONFIRMATION：保留（content 已持久化，仍可确认执行）；
     * - PREPARING：未开始发送，恢复为等待确认（预览缺失时补渲染）；
     * - CONNECTING / SENDING：进程可能中断在发送中，无法确认结果 → OUTCOME_UNKNOWN；
     * - 终态：仅作为历史保留，禁止再次执行；超过保留期（7 天）的自动清理。
     */
    private suspend fun loadAndRecover() {
        val loaded = runCatching {
            JsonInstant.decodeFromString<PrintTaskStoreFile>(tasksFile.readText())
        }.getOrDefault(PrintTaskStoreFile(emptyList()))
        val now = System.currentTimeMillis()
        val retentionCutoff = now - TERMINAL_TASK_RETENTION_MS
        val recovered = loaded.tasks
            // 终态历史任务保留 7 天，超期清理；未终态任务不受影响
            .filterNot { it.status.isTerminal && it.updatedAt < retentionCutoff }
            .map { record ->
            val status = when (record.status) {
                PrintTaskStatus.PREPARING -> PrintTaskStatus.AWAITING_CONFIRMATION
                PrintTaskStatus.CONNECTING, PrintTaskStatus.SENDING -> PrintTaskStatus.OUTCOME_UNKNOWN
                else -> record.status
            }
            val keepContent = status == PrintTaskStatus.AWAITING_CONFIRMATION
            val message = if (status == PrintTaskStatus.OUTCOME_UNKNOWN &&
                record.status != PrintTaskStatus.OUTCOME_UNKNOWN
            ) {
                "应用在发送过程中中断，无法确认打印结果，请检查纸张是否已有部分输出"
            } else {
                record.errorMessage
            }
            PrintTask(
                record = record.copy(
                    status = status,
                    content = record.content?.takeIf { keepContent },
                    errorMessage = message,
                    updatedAt = now,
                ),
                previewFile = previewFileOf(record.taskId).takeIf { it.exists() && keepContent },
            )
        }
        tasksMutex.withLock {
            _tasks.value = recovered.sortedByDescending { it.record.createdAt }
        }
        persistSnapshot()
        cleanupOrphanPreviews()
        // 恢复的等待确认任务若无预览，补渲染
        recovered.filter { it.record.status == PrintTaskStatus.AWAITING_CONFIRMATION && it.previewFile == null }
            .forEach { task ->
                appScope.launch(Dispatchers.Default) { renderPreviewFor(task.record.taskId) }
            }
    }

    /** 删除已不在任务列表中的预览 PNG，防止 filesDir/print_previews 无限增长 */
    private suspend fun cleanupOrphanPreviews() {
        withContext(Dispatchers.IO) {
            runCatching {
                val activeIds = _tasks.value.map { it.record.taskId }.toSet()
                previewDir.listFiles()?.forEach { file ->
                    if (file.extension == "png" && file.nameWithoutExtension !in activeIds) {
                        file.delete()
                    }
                }
            }
        }
    }

    companion object {
        /** 任务列表持久化上限（含终态历史），超出按新到旧截断 */
        private const val MAX_PERSISTED_TASKS = 30

        /** 终态任务保留时长：启动恢复时清理超期历史 */
        private const val TERMINAL_TASK_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** 测试打印内容：覆盖中文/英文/数字/加粗/列表/复选框/分隔线，短小不费纸 */
        private val TEST_PRINT_CONTENT = """
            # Evolia 打印测试

            如果你能看到这行**加粗中文**与 English mixed 123，说明点阵方向与字符集正常。

            - 无序列表项一
            - 无序列表项二
            1. 有序列表项
            - [x] 已完成事项
            - [ ] 待办事项

            > 引用：与世界的碰撞中不断进化。

            ---
            测试完成，可以撕纸了。
        """.trimIndent()
    }
}

/** 任务持久化文件结构 */
@kotlinx.serialization.Serializable
private data class PrintTaskStoreFile(val tasks: List<PrintTaskRecord>)
