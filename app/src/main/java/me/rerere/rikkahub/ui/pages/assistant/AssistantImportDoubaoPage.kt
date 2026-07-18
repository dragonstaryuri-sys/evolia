package me.rerere.rikkahub.ui.pages.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantImportDoubaoPage(
    vm: AssistantImportVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    val progress by vm.progress.collectAsStateWithLifecycle()
    val progressText by vm.progressText.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    // 页面进入时重置状态
    LaunchedEffect(Unit) {
        vm.reset()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.prepareImport(uri) { errorMsg ->
                toaster.show(errorMsg)
            }
        }
    }

    // 模板下载保存器
    val templateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(vm.generateTemplateJson().toByteArray())
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                if (success) {
                    toaster.show(context.getString(R.string.import_doubao_template_saved))
                } else {
                    toaster.show(context.getString(R.string.import_doubao_template_save_failed))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_doubao_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (vm.importLog.isNotBlank()) {
                        IconButton(onClick = {
                            val path = vm.saveLogToDisk(context)
                            if (path != null) {
                                toaster.show(context.getString(R.string.import_doubao_log_saved))
                            } else {
                                toaster.show("Error saving log")
                            }
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Download Log")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部配置区域
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 文件选择卡片
                    Card(
                        onClick = { if (!vm.isImporting) importLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Description, null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = vm.importData?.botInfo?.name ?: stringResource(R.string.import_doubao_select_file),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (vm.importData != null)
                                        stringResource(R.string.import_doubao_selected_count, vm.importData?.chatHistory?.size ?: 0)
                                    else stringResource(R.string.import_doubao_select_file_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (vm.importData != null) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = Color.Green)
                            }
                        }
                    }

                    // 下载模板按钮
                    if (vm.importData == null && !vm.isImporting) {
                        TextButton(
                            onClick = { templateLauncher.launch("doubao_template.json") },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.import_doubao_download_template))
                        }
                    }

                    // 导入配置选项
                    AnimatedVisibility(
                        visible = vm.importData != null && !vm.isImporting,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.import_doubao_options), style = MaterialTheme.typography.labelLarge)

                            ListItem(
                                headlineContent = { Text(stringResource(R.string.import_doubao_set_main)) },
                                supportingContent = { Text(stringResource(R.string.import_doubao_set_main_desc)) },
                                trailingContent = {
                                    Switch(checked = vm.isMainAgent, onCheckedChange = { vm.isMainAgent = it })
                                },
                                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        text = stringResource(R.string.import_doubao_rounds_label, vm.roundsPerSession),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Slider(
                                        value = vm.roundsPerSession.toFloat(),
                                        onValueChange = { vm.roundsPerSession = it.toInt() },
                                        valueRange = 1f..50f,
                                        steps = 49
                                    )
                                    Text(
                                        text = stringResource(R.string.import_doubao_rounds_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    vm.startImport { success, assistantId, sessionCount ->
                                        if (success && assistantId != null) {
                                            toaster.show(context.getString(R.string.import_doubao_success))
                                            // 跳转到记忆处理页面
                                            navController.navigate(
                                                Screen.AssistantImportMemory(
                                                    assistantId = assistantId,
                                                    isMain = vm.isMainAgent,
                                                    sessionCount = sessionCount
                                                )
                                            ) {
                                                popUpTo(Screen.AssistantImportDoubao) { inclusive = true }
                                            }
                                        } else if (!success) {
                                            toaster.show("导入失败或被用户终止")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.CloudUpload, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.import_doubao_start_button))
                            }
                        }
                    }
                }

                // 实时进度与日志展示
                AnimatedVisibility(
                    visible = vm.isImporting || vm.importLog.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.import_doubao_progress_title), style = MaterialTheme.typography.labelLarge)

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(status, style = MaterialTheme.typography.bodySmall)
                            Text(progressText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            val logScrollState = rememberScrollState()

                            LaunchedEffect(vm.importLog) {
                                logScrollState.animateScrollTo(Int.MAX_VALUE)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(logScrollState)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = vm.importLog,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 预览弹窗
            if (vm.previewConversation != null) {
                PreviewDialog(
                    conversation = vm.previewConversation!!,
                    botName = vm.botName,
                    onBotNameChange = { vm.botName = it },
                    botDescription = vm.botDescription,
                    onBotDescriptionChange = { vm.botDescription = it },
                    onConfirm = { vm.confirmPreview(true) },
                    onCancel = { vm.confirmPreview(false) }
                )
            }
        }
    }
}

@Composable
fun PreviewDialog(
    conversation: Conversation,
    botName: String,
    onBotNameChange: (String) -> Unit,
    botDescription: String,
    onBotDescriptionChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_doubao_preview_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.import_doubao_preview_desc), style = MaterialTheme.typography.bodySmall)

                // 编辑区域
                OutlinedTextField(
                    value = botName,
                    onValueChange = onBotNameChange,
                    label = { Text(stringResource(R.string.assistant_import_page_agent_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = botDescription,
                    onValueChange = onBotDescriptionChange,
                    label = { Text(stringResource(R.string.assistant_import_page_system_prompt_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                Text("会话预览 (第一个片段)", style = MaterialTheme.typography.labelLarge)

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conversation.messageNodes) { node ->
                        val msg = node.currentMessage
                        Column {
                            val roleText = if (msg.role.name == "ASSISTANT") {
                                stringResource(R.string.import_doubao_role_assistant)
                            } else {
                                stringResource(R.string.import_doubao_role_user)
                            }
                            Text(
                                text = roleText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(msg.toText(), style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 8.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.import_doubao_confirm_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.import_doubao_cancel_import)) }
        }
    )
}
