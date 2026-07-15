package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantImportMemoryPage(
    assistantId: String,
    isMain: Boolean,
    sessionCount: Int,
    vm: AssistantImportMemoryVM = koinViewModel { parametersOf(assistantId, isMain, sessionCount) }
) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("完善智能体记忆") },
                navigationIcon = { BackButton() }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Button(
                    onClick = {
                        vm.submitAll {
                            toaster.show("记忆同步完成")
                            navController.popBackStack()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !vm.isGeneratingL1
                ) {
                    if (vm.isGeneratingL1) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text("保存并完成导入", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. 关系档案 (L3)
            item {
                MemorySectionHeader(title = "关系档案 (L3)", icon = Icons.Rounded.AutoStories)
                OutlinedTextField(
                    value = vm.relationshipProfile,
                    onValueChange = { vm.relationshipProfile = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    placeholder = { Text("描述你们之间的羁绊、约定和核心身份标识...") },
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // 2. 里程碑 (仅主智能体)
            if (isMain) {
                item {
                    MemorySectionHeader(
                        title = "里程碑事件及长期约定",
                        icon = Icons.Rounded.Star,
                        action = {
                            TextButton(onClick = { vm.addMilestone() }) {
                                Icon(Icons.Rounded.Add, null)
                                Text("添加")
                            }
                        }
                    )
                }
                itemsIndexed(vm.milestones) { index, item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 第一行：标签和删除按钮
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = item.label,
                                    onValueChange = { item.label = it },
                                    label = { Text(stringResource(R.string.milestone_label)) },
                                    placeholder = { Text(stringResource(R.string.milestone_label_placeholder)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                IconButton(onClick = { vm.removeMilestone(index) }) {
                                    Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            // 第二行：时间
                            OutlinedTextField(
                                value = item.date,
                                onValueChange = { item.date = it },
                                label = { Text(stringResource(R.string.milestone_time)) },
                                placeholder = { Text(stringResource(R.string.milestone_time_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            // 第三行：内容
                            OutlinedTextField(
                                value = item.content,
                                onValueChange = { item.content = it },
                                label = { Text(stringResource(R.string.milestone_content)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 3. 核心记忆 (MemoryEntity)
            item {
                MemorySectionHeader(
                    title = "核心记忆 (Core Memory)",
                    icon = Icons.Rounded.Memory,
                    action = {
                        TextButton(onClick = { vm.addCoreMemory() }) {
                            Icon(Icons.Rounded.Add, null)
                            Text("添加")
                        }
                    }
                )
            }
            itemsIndexed(vm.coreMemories) { index, item ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = item.keywords,
                                onValueChange = { item.keywords = it },
                                label = { Text("关键词 (选填，逗号分隔)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(onClick = { vm.removeCoreMemory(index) }) {
                                Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        OutlinedTextField(
                            value = item.content,
                            onValueChange = { item.content = it },
                            label = { Text("记忆详情") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                }
            }

            // 4. 片段记忆 L1 (API 自动总结)
            item {
                MemorySectionHeader(title = "片段记忆生成 (L1)", icon = Icons.Rounded.History)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "本次一共导入了 ${sessionCount} 个会话。选择最新的 N 个会话生成片段记忆：",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Slider(
                                value = vm.selectedL1Count.toFloat(),
                                onValueChange = { vm.selectedL1Count = it.toInt() },
                                valueRange = 0f..sessionCount.toFloat(),
                                modifier = Modifier.weight(1f),
                                enabled = !vm.isGeneratingL1
                            )
                            Text(
                                vm.selectedL1Count.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (vm.isGeneratingL1) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(
                                    progress = { vm.l1Progress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("正在生成: ${vm.currentL1SessionName}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text("${(vm.l1Progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                                }
                                OutlinedButton(
                                    onClick = { vm.cancelL1Generation() },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Icon(Icons.Rounded.Stop, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("停止生成")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(100.dp)) }
        }
    }
}

@Composable
fun MemorySectionHeader(
    title: String,
    icon: ImageVector,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        action?.invoke()
    }
}
