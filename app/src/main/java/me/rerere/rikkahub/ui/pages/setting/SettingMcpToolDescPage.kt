package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingMcpToolDescPage(vm: McpToolDescVM = koinViewModel()) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val errorEvent by vm.errorEvent.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 重置确认弹窗目标（工具 key）
    var pendingResetKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(errorEvent) {
        errorEvent?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeError()
        }
    }

    val visibleItems = remember(
        uiState.items, uiState.selectedServerId, uiState.searchQuery
    ) {
        val query = uiState.searchQuery.trim()
        uiState.items.filter { item ->
            val serverMatched =
                uiState.selectedServerId == null || item.serverId == uiState.selectedServerId
            val queryMatched = query.isBlank() ||
                item.tool.name.contains(query, ignoreCase = true) ||
                item.editContent.contains(query, ignoreCase = true)
            serverMatched && queryMatched
        }
    }
    val pendingResetItem = pendingResetKey?.let { key -> uiState.items.firstOrNull { it.key == key } }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.mcp_tool_desc_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    McpToolDescTopBarActions(
                        uiState = uiState,
                        onToggleBatchMode = { vm.setBatchSelectionMode(it) },
                        onStartBatch = { vm.startBatchSimplify() }
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        if (uiState.items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.mcp_tool_desc_empty))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                McpToolDescHintCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { vm.updateSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text(stringResource(R.string.mcp_tool_desc_search_hint)) },
                    singleLine = true,
                    shape = AppShapes.ButtonPill,
                )
                McpToolDescServerFilterRow(
                    items = uiState.items,
                    selectedServerId = uiState.selectedServerId,
                    onSelect = { vm.selectServer(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 24.dp, top = 4.dp
                    )
                ) {
                    // 按 server 分组渲染，组头带服务名，天然区分不同服务的同名工具
                    val grouped = visibleItems.groupBy { it.serverId }
                    grouped.forEach { (serverId, groupItems) ->
                        item(key = "header_$serverId") {
                            Text(
                                text = groupItems.firstOrNull()?.serverName.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(groupItems, key = { it.key }) { item ->
                            McpToolDescItemCard(
                                item = item,
                                batchMode = uiState.batchSelectionMode,
                                batchSelected = item.key in uiState.batchSelectedKeys,
                                simplifying = item.key in uiState.simplifyingKeys,
                                onToggleSelect = { vm.toggleBatchSelect(item.key) },
                                onEdit = { vm.updateDescription(item, it) },
                                onSimplify = { vm.simplifySingle(item) },
                                onReset = { pendingResetKey = item.key },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    // 单个简化结果预览（可微调，确认后才落库）
    uiState.singlePreview?.let { (_, previewText) ->
        var editingPreview by remember(previewText) { mutableStateOf(previewText) }
        AlertDialog(
            onDismissRequest = { vm.dismissSinglePreview() },
            title = { Text(stringResource(R.string.mcp_tool_desc_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.mcp_tool_desc_preview_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editingPreview,
                        onValueChange = { editingPreview = it },
                        minLines = 3,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            dismissButton = {
                TextButton(
                    onClick = { vm.dismissSinglePreview() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateSinglePreviewText(editingPreview)
                        vm.confirmSinglePreview()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    // 批量简化结果预览（逐项确认 + 失败重试）
    if (uiState.batchResults.isNotEmpty()) {
        val successResults = uiState.batchResults.filter { it.isSuccess }
        AlertDialog(
            onDismissRequest = { if (!uiState.batchRunning) vm.dismissBatchResults() },
            title = { Text(stringResource(R.string.mcp_tool_desc_batch_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.mcp_tool_desc_batch_preview_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.batchResults, key = { it.key }) { result ->
                            McpBatchResultRow(
                                result = result,
                                checked = result.key in uiState.batchCheckedKeys,
                                running = uiState.batchRunning,
                                onToggle = { vm.toggleBatchChecked(result.key) },
                                onRetry = { vm.retryBatchItem(result) }
                            )
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            dismissButton = {
                TextButton(
                    onClick = { vm.dismissBatchResults() },
                    enabled = !uiState.batchRunning,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.applyBatchResults() },
                    enabled = successResults.any { it.key in uiState.batchCheckedKeys }
                ) {
                    Text(
                        stringResource(
                            R.string.mcp_tool_desc_batch_apply_selected,
                            successResults.count { it.key in uiState.batchCheckedKeys }
                        )
                    )
                }
            }
        )
    }

    // 重置确认
    pendingResetItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingResetKey = null },
            title = { Text(stringResource(R.string.mcp_tool_desc_reset_confirm_title)) },
            text = {
                Text(stringResource(R.string.mcp_tool_desc_reset_confirm_text))
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            dismissButton = {
                TextButton(
                    onClick = { pendingResetKey = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.resetDescription(item)
                        pendingResetKey = null
                    }
                ) {
                    Text(
                        stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }
}

@Composable
private fun McpToolDescTopBarActions(
    uiState: McpToolDescUiState,
    onToggleBatchMode: (Boolean) -> Unit,
    onStartBatch: () -> Unit
) {
    if (uiState.batchSelectionMode) {
        TextButton(
            onClick = onStartBatch,
            enabled = !uiState.batchRunning && uiState.batchSelectedKeys.isNotEmpty()
        ) {
            if (uiState.batchRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    R.string.mcp_tool_desc_batch_simplify_selected,
                    uiState.batchSelectedKeys.size
                )
            )
        }
        IconButton(onClick = { onToggleBatchMode(false) }) {
            Icon(Icons.Rounded.Close, null)
        }
    } else {
        IconButton(onClick = { onToggleBatchMode(true) }) {
            Icon(Icons.Rounded.Checklist, null)
        }
    }
}

@Composable
private fun McpToolDescHintCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = AppShapes.CardMedium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Text(
            text = stringResource(R.string.mcp_tool_desc_page_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun McpToolDescServerFilterRow(
    items: List<McpToolDescItem>,
    selectedServerId: kotlin.uuid.Uuid?,
    onSelect: (kotlin.uuid.Uuid?) -> Unit,
    modifier: Modifier = Modifier
) {
    // 按 items 顺序去重得到服务列表
    val servers = remember(items) {
        items.distinctBy { it.serverId }.map { it.serverId to it.serverName }
    }
    LazyRow(modifier = modifier) {
        item {
            FilterChip(
                selected = selectedServerId == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.mcp_tool_desc_server_all)) }
            )
        }
        items(servers.size, key = { servers[it].first.toString() }) { index ->
            val (serverId, serverName) = servers[index]
            FilterChip(
                selected = selectedServerId == serverId,
                onClick = { onSelect(serverId) },
                label = { Text(serverName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}



@Composable
private fun McpToolDescItemCard(
    item: McpToolDescItem,
    batchMode: Boolean,
    batchSelected: Boolean,
    simplifying: Boolean,
    onToggleSelect: () -> Unit,
    onEdit: (String) -> Unit,
    onSimplify: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (batchMode) {
                    Checkbox(checked = batchSelected, onCheckedChange = { onToggleSelect() })
                } else {
                    Spacer(Modifier.width(4.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.tool.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 明确告知工具名称只读，避免误解为可改名
                    Text(
                        text = stringResource(R.string.mcp_tool_desc_readonly_name_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                if (item.isCustomized) {
                    Tag(type = TagType.INFO) {
                        Text(
                            text = stringResource(R.string.mcp_tool_desc_customized_tag),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            OutlinedTextField(
                value = item.editContent,
                onValueChange = onEdit,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10,
                enabled = !batchMode
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSimplify,
                    enabled = !simplifying && !batchMode
                ) {
                    if (simplifying) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.mcp_tool_desc_simplify_btn))
                }
                TextButton(
                    onClick = onReset,
                    enabled = !batchMode,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.mcp_tool_desc_reset_btn))
                }
            }
            // 简化使用全局后台模型，而非当前对话模型
            Text(
                text = stringResource(R.string.mcp_tool_desc_simplify_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun McpBatchResultRow(
    result: McpSimplifyItemState,
    checked: Boolean,
    running: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        if (result.isSuccess) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${result.serverName} / ${result.toolName}",
                style = MaterialTheme.typography.titleSmall
            )
            if (result.isSuccess) {
                Text(
                    text = result.simplifiedText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = result.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry, enabled = !running) {
                    Text(stringResource(R.string.mcp_tool_desc_batch_retry))
                }
            }
        }
    }
}
