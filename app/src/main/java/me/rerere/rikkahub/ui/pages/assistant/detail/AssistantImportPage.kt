package me.rerere.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.ui.DebouncedTextField
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook

@Composable
fun AssistantImportPage(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var showL3Dialog by remember { mutableStateOf(false) }
    var showCoreDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var pendingImportType by remember { mutableStateOf<ImportType?>(null) }

    // 样式参数
    val cardColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current)
        MaterialTheme.colorScheme.surfaceContainerLow
    else
        MaterialTheme.colorScheme.surfaceContainerHigh
    val cardShape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge

    // 导出模板 Launcher (XLSX)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null && pendingImportType != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val workbook = XSSFWorkbook()
                        val sheet = workbook.createSheet("Template")

                        // 1. 创建样式：灰色背景 + 锁定
                        val lockStyle = workbook.createCellStyle().apply {
                            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                            fillPattern = FillPatternType.SOLID_FOREGROUND
                            locked = true
                        }
                        // 2. 创建样式：普通可编辑 (未锁定)
                        val editStyle = workbook.createCellStyle().apply {
                            locked = false
                        }

                        when (pendingImportType) {
                            ImportType.L3 -> {
                                sheet.setDefaultColumnStyle(1, editStyle)
                                val data = listOf(
                                    "### 约定与待办" to "请修改此处：例如每周五一起看电影...",
                                    "### 情感现状" to "请修改此处：例如我们是恋人，感情很好..."
                                )
                                data.forEachIndexed { index, pair ->
                                    val row = sheet.createRow(index)
                                    row.createCell(0).apply { setCellValue(pair.first); setCellStyle(lockStyle) }
                                    row.createCell(1).apply { setCellValue(pair.second); setCellStyle(editStyle) }
                                }
                                sheet.setColumnWidth(0, 25 * 256)
                                sheet.setColumnWidth(1, 60 * 256)
                            }
                            ImportType.CORE -> {
                                sheet.setDefaultColumnStyle(0, editStyle)
                                sheet.setDefaultColumnStyle(1, editStyle)
                                val headerRow = sheet.createRow(0)
                                headerRow.createCell(0).apply { setCellValue("序号"); setCellStyle(lockStyle) }
                                headerRow.createCell(1).apply { setCellValue("记忆内容"); setCellStyle(lockStyle) }
                                val sampleRow1 = sheet.createRow(1)
                                sampleRow1.createCell(0).apply { setCellValue(1.0); setCellStyle(editStyle) }
                                sampleRow1.createCell(1).apply { setCellValue("请在此修改输入记忆内容（导入时会自动跳过示例）"); setCellStyle(editStyle) }
                                sheet.setColumnWidth(1, 80 * 256)
                            }
                            ImportType.HISTORY -> {
                                // 锁定表头
                                val headerRow = sheet.createRow(0)
                                headerRow.createCell(0).apply { setCellValue("序号"); setCellStyle(lockStyle) }
                                headerRow.createCell(1).apply { setCellValue("user"); setCellStyle(lockStyle) }
                                headerRow.createCell(2).apply { setCellValue("assistant"); setCellStyle(lockStyle) }

                                // 预置 30 行序号并锁定序号列，仅开启对话列编辑
                                for (i in 1..30) {
                                    val row = sheet.createRow(i)
                                    row.createCell(0).apply { setCellValue(i.toDouble()); setCellStyle(lockStyle) }
                                    row.createCell(1).apply { setCellStyle(editStyle) }
                                    row.createCell(2).apply { setCellStyle(editStyle) }

                                    // 第一行示例
                                    if (i == 1) {
                                        row.getCell(1).setCellValue("你好，小机！")
                                        row.getCell(2).setCellValue("你好，人类！")
                                    }
                                }
                                sheet.setColumnWidth(1, 50 * 256)
                                sheet.setColumnWidth(2, 50 * 256)
                            }
                            else -> {}
                        }

                        // 开启工作表保护，使锁定生效
                        sheet.protectSheet("")
                        context.contentResolver.openOutputStream(uri)?.use { workbook.write(it) }
                        workbook.close()
                    }
                    toaster.show(context.getString(R.string.export_success))
                } catch (e: Exception) {
                    toaster.show("导出模板失败: ${e.message}")
                }
                pendingImportType = null
            }
        }
    }

    // 导入文件 Launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && pendingImportType != null) {
            scope.launch {
                try {
                    when (pendingImportType) {
                        ImportType.HISTORY -> {
                            val messages = withContext(Dispatchers.IO) {
                                readExcelHistory(uri, context)
                            }
                            if (messages.isNotEmpty()) {
                                vm.importConversation("导入的历史会话", messages)
                            } else {
                                toaster.show("未检测到有效对话内容")
                            }
                        }
                        else -> {
                            val result = withContext(Dispatchers.IO) {
                                readExcel(uri, context, pendingImportType!!)
                            }
                            if (pendingImportType == ImportType.L3) {
                                if (result.isNotEmpty()) {
                                    onUpdate(assistant.copy(masterMemoryContent = result.first()))
                                    toaster.show(context.getString(R.string.assistant_import_page_import_success))
                                }
                            } else if (pendingImportType == ImportType.CORE) {
                                result.forEach { content ->
                                    vm.addMemory(AssistantMemory(id = 0, content = content, type = 0))
                                }
                                toaster.show(context.getString(R.string.assistant_import_page_import_success))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    toaster.show("导入失败: ${e.message}")
                }
                pendingImportType = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // 基本信息模块
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Surface(modifier = Modifier.fillMaxWidth(), color = cardColor, shape = cardShape) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.assistant_import_page_agent_name_label),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DebouncedTextField(
                            value = assistant.name,
                            onValueChange = { onUpdate(assistant.copy(name = it)) },
                            stateKey = "import_name_${assistant.id}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.assistant_import_page_system_prompt_label),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DebouncedTextField(
                            value = assistant.systemPrompt,
                            onValueChange = { onUpdate(assistant.copy(systemPrompt = it)) },
                            stateKey = "import_prompt_${assistant.id}",
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5,
                            maxLines = 15
                        )
                    }
                }
            }
        }

        // 记忆档案模块
        SettingsGroup(title = stringResource(R.string.assistant_import_page_memory_archive_group_title)) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "此模块用于放置机的记忆档案，详细模块可下载模板后查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { pendingImportType = ImportType.L3; showL3Dialog = true }, modifier = Modifier.fillMaxWidth()) { Text("导入记忆档案") }
            }
        }

        // 核心记忆模块
        SettingsGroup(title = stringResource(R.string.assistant_import_page_core_memories_group_title)) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "此模块用于迁移零散的碎片记忆（Core Memory）。内容将作为新条目插入，不会删除现有内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { pendingImportType = ImportType.CORE; showCoreDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("导入核心记忆条目") }
            }
        }

        // 会话历史模块
        SettingsGroup(title = stringResource(R.string.assistant_import_page_history_group_title)) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "建议导入最新几轮的会话历史消息（最多30轮）。导入后你就可以无缝和机在新平台对话啦！！", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { pendingImportType = ImportType.HISTORY; showHistoryDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("导入会话历史") }
            }
        }
    }

    // 弹窗逻辑 (L3)
    if (showL3Dialog) {
        AlertDialog(
            onDismissRequest = { showL3Dialog = false },
            title = { Text("导入记忆档案") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_import_page_memory_archive_hint))
                    Text("提示：灰色标题列已被锁定保护。您只需在右侧对应行修改内容。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { pendingImportType = ImportType.L3; exportLauncher.launch("memory_archive_template.xlsx") }, modifier = Modifier.fillMaxWidth()) { Text("导出 Excel 模板") }
                }
            },
            confirmButton = { Button(onClick = { showL3Dialog = false; pendingImportType = ImportType.L3; importLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }) { Text("选择文件并导入") } },
            dismissButton = { TextButton(onClick = { showL3Dialog = false }) { Text("取消") } }
        )
    }

    // 弹窗逻辑 (Core)
    if (showCoreDialog) {
        AlertDialog(
            onDismissRequest = { showCoreDialog = false },
            title = { Text("导入核心记忆") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_import_page_core_memories_hint))
                    Text("提示：请不要修改首行灰色表头。后续行均可自由添加记忆内容。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { pendingImportType = ImportType.CORE; exportLauncher.launch("core_memory_template.xlsx") }, modifier = Modifier.fillMaxWidth()) { Text("导出 Excel 模板") }
                }
            },
            confirmButton = { Button(onClick = { showCoreDialog = false; pendingImportType = ImportType.CORE; importLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }) { Text("选择文件并导入") } },
            dismissButton = { TextButton(onClick = { showCoreDialog = false }) { Text("取消") } }
        )
    }

    // 弹窗逻辑 (History)
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("导入会话历史") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_import_page_history_hint))
                    Text("提示：表头和序号列已锁定。最多支持 30 轮对话，请按 user 和 assistant 分列填写，系统会自动跳过示例行。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { pendingImportType = ImportType.HISTORY; exportLauncher.launch("history_import_template.xlsx") }, modifier = Modifier.fillMaxWidth()) { Text("导出 Excel 模板") }
                }
            },
            confirmButton = { Button(onClick = { showHistoryDialog = false; pendingImportType = ImportType.HISTORY; importLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }) { Text("选择文件并导入") } },
            dismissButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("取消") } }
        )
    }
}

private enum class ImportType { L3, CORE, HISTORY }

private fun readExcel(uri: Uri, context: android.content.Context, type: ImportType): List<String> {
    val results = mutableListOf<String>()
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val workbook = WorkbookFactory.create(inputStream)
        val sheet = workbook.getSheetAt(0)
        if (type == ImportType.L3) {
            val sb = StringBuilder()
            for (i in 0..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val key = row.getCell(0)?.toString()?.trim() ?: ""
                val value = row.getCell(1)?.toString()?.trim() ?: ""
                if (key.isNotEmpty() && value.isNotEmpty() && !value.contains("请修改此处")) {
                    sb.append("${key}: ${value}\n")
                }
            }
            if (sb.isNotEmpty()) results.add(sb.toString().trim())
        } else {
            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val content = row.getCell(1)?.toString()?.trim() ?: ""
                if (content.isNotEmpty() && !content.contains("请在此修改") && !content.contains("例如：")) {
                    results.add(content)
                }
            }
        }
        workbook.close()
    }
    return results
}

private fun readExcelHistory(uri: Uri, context: android.content.Context): List<UIMessage> {
    val messages = mutableListOf<UIMessage>()
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val workbook = WorkbookFactory.create(inputStream)
        val sheet = workbook.getSheetAt(0)
        // 限制只读取预置的 30 行数据范围
        for (i in 1..30) {
            val row = sheet.getRow(i) ?: continue
            val userText = row.getCell(1)?.toString()?.trim() ?: ""
            val assistantText = row.getCell(2)?.toString()?.trim() ?: ""

            // 过滤示例行
            if (i == 1 && userText == "你好，小机！") continue

            if (userText.isNotEmpty()) messages.add(UIMessage.user(userText))
            if (assistantText.isNotEmpty()) messages.add(UIMessage.assistant(assistantText))
        }
        workbook.close()
    }
    return messages
}
