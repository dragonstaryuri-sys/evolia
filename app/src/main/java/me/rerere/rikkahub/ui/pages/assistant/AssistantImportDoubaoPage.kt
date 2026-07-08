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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            // 接入错误回调：处理格式错误或消息提取为空的情况
            vm.prepareImport(uri) { errorMsg ->
                toaster.show(errorMsg)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入豆包智能体") },
                navigationIcon = { BackButton() },
                actions = {
                    if (vm.importLog.isNotBlank()) {
                        IconButton(onClick = {
                            val path = vm.saveLogToDisk(context)
                            if (path != null) {
                                toaster.show("日志已导出至: $path")
                            } else {
                                toaster.show("导出日志失败")
                            }
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = "下载日志")
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
                    .padding(16.dp)
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
                                text = vm.importData?.botInfo?.name ?: "选择 JSON 导入文件",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (vm.importData != null) "已解析 ${vm.importData?.chatHistory?.size ?: 0} 条消息" else "点击选择从豆包导出的聊天记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (vm.importData != null) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color.Green)
                        }
                    }
                }

                // 导入配置选项
                AnimatedVisibility(
                    visible = vm.importData != null && !vm.isImporting,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("导入设置", style = MaterialTheme.typography.labelLarge)

                        ListItem(
                            headlineContent = { Text("设为主智能体") },
                            supportingContent = { Text("导入后将自动切换为当前激活的智能体") },
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
                                Text("消息切分: 每会话 ${vm.roundsPerSession} 轮", style = MaterialTheme.typography.bodyMedium)
                                Slider(
                                    value = vm.roundsPerSession.toFloat(),
                                    onValueChange = { vm.roundsPerSession = it.toInt() },
                                    valueRange = 1f..50f,
                                    steps = 49
                                )
                                Text("系统将按此频率自动切分会话，防止单会话过长", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Button(
                            onClick = {
                                vm.startImport { success ->
                                    if (success) {
                                        toaster.show("所有数据已同步完成")
                                        navController.popBackStack()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.CloudUpload, null)
                            Spacer(Modifier.width(8.dp))
                            Text("开始导入")
                        }
                    }
                }

                // 实时进度与日志展示
                AnimatedVisibility(visible = vm.isImporting || vm.importLog.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("运行日志", style = MaterialTheme.typography.labelLarge)

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(status, style = MaterialTheme.typography.bodySmall)
                            Text(progressText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 300.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(Modifier.padding(12.dp)) {
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
        title = { Text("数据预览与编辑") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("请确认或修改智能体信息，此时数据尚未入库。", style = MaterialTheme.typography.bodySmall)

                // 编辑区域
                OutlinedTextField(
                    value = botName,
                    onValueChange = onBotNameChange,
                    label = { Text("智能体名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = botDescription,
                    onValueChange = onBotDescriptionChange,
                    label = { Text("人物设定(后面再修改也行)") },
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
                            Text(
                                text = if(msg.role.name == "ASSISTANT") "智能体" else "用户",
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
            Button(onClick = onConfirm) { Text("确认并继续导入") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("终止导入") }
        }
    )
}
