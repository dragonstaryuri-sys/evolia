package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.common.ui.components.InlineSpinner
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.AppShapes
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
    val successMessage = stringResource(R.string.import_memory_success_toast)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.import_memory_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Button(
                    onClick = {
                        vm.submitAll {
                            toaster.show(successMessage)
                            navController.popBackStack()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                    shape = AppShapes.CardSmall,
                    enabled = !vm.isGeneratingL1
                ) {
                    if (vm.isGeneratingL1) {
                        InlineSpinner(size = 24.dp, strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.import_memory_submit_button), fontWeight = FontWeight.Bold)
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
                MemorySectionHeader(title = stringResource(R.string.import_memory_l3_title), icon = Icons.Rounded.AutoStories)
                OutlinedTextField(
                    value = vm.relationshipProfile,
                    onValueChange = { vm.relationshipProfile = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    placeholder = { Text(stringResource(R.string.import_memory_l3_placeholder)) },
                    shape = AppShapes.CardSmall
                )
            }

            // 2. 里程碑 (仅主智能体)
            if (isMain) {
                item {
                    MemorySectionHeader(
                        title = stringResource(R.string.import_memory_milestone_title),
                        icon = Icons.Rounded.Star,
                        action = {
                            TextButton(onClick = { vm.addMilestone() }) {
                                Icon(Icons.Rounded.Add, null)
                                Text(stringResource(R.string.import_memory_add))
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
                    title = stringResource(R.string.import_memory_core_title),
                    icon = Icons.Rounded.Memory,
                    action = {
                        TextButton(onClick = { vm.addCoreMemory() }) {
                            Icon(Icons.Rounded.Add, null)
                            Text(stringResource(R.string.import_memory_add))
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
                                label = { Text(stringResource(R.string.import_memory_core_keywords_label)) },
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
                            label = { Text(stringResource(R.string.import_memory_core_content_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                }
            }

            // 4. 片段记忆 L1 (API 自动总结)
            item {
                MemorySectionHeader(title = stringResource(R.string.import_memory_l1_title), icon = Icons.Rounded.History)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.import_memory_l1_desc, sessionCount),
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
                                    Text(stringResource(R.string.import_memory_l1_generating, vm.currentL1SessionName), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text("${(vm.l1Progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                                }
                                OutlinedButton(
                                    onClick = { vm.cancelL1Generation() },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Icon(Icons.Rounded.Stop, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.import_memory_l1_stop))
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
