package me.rerere.rikkahub.ui.pages.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity
import me.rerere.rikkahub.core.data.repository.CallHistorySort
import me.rerere.rikkahub.ui.components.chat.CallRecordDeleteDialog
import me.rerere.rikkahub.ui.components.chat.formatCallDuration
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 智能体历史通话列表：默认最新优先，可切换 4 种排序；点击进入该记录的通话详情页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryCallPage(assistantId: String) {
    val vm: HistoryCallVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val navController = LocalNavController.current
    val records by vm.records.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()

    var sortMenuExpanded by remember { mutableStateOf(false) }
    var pendingDeleteRecord by remember { mutableStateOf<CallRecordEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史通话", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Rounded.Sort, null)
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            CallHistorySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(sortLabel(option)) },
                                    onClick = {
                                        vm.setSort(option)
                                        sortMenuExpanded = false
                                    },
                                    trailingIcon = {
                                        if (sort == option) {
                                            Text("✓", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "暂无通话记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    HistoryCallCard(
                        record = record,
                        onClick = { navController.navigate(Screen.CallDetail(record.id)) },
                        onLongClick = { pendingDeleteRecord = record }
                    )
                }
            }
        }
    }

    pendingDeleteRecord?.let { record ->
        CallRecordDeleteDialog(
            onDismissRequest = { pendingDeleteRecord = null },
            onConfirm = {
                vm.deleteCallRecord(record)
                pendingDeleteRecord = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCallCard(
    record: CallRecordEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isConnected = record.connectedAt != null || record.durationMs > 0L
    val icon = if (isConnected) Icons.Rounded.Call else Icons.Rounded.CallEnd
    val tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        shape = AppShapes.CardMedium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatCreateTime(record.createTime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isConnected) "通话时长 ${formatCallDuration(record.durationMs)}" else "已挂断",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                record.endReason?.takeIf { !isConnected }?.let { reason ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = callEndReasonLabel(reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun sortLabel(sort: CallHistorySort): String = when (sort) {
    CallHistorySort.LATEST -> "最新优先"
    CallHistorySort.OLDEST -> "最早优先"
    CallHistorySort.LONGEST -> "时长最长"
    CallHistorySort.SHORTEST -> "时长最短"
}

private fun formatCreateTime(timeMs: Long): String =
    java.time.Instant.ofEpochMilli(timeMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun callEndReasonLabel(reason: String): String = when (reason) {
    "rejected" -> "用户拒绝"
    "missed" -> "未接听（超时）"
    "permission_denied" -> "麦克风权限不足"
    "init_failed" -> "语音会话初始化失败"
    "user_hangup" -> "用户挂断"
    "agent_hangup" -> "助手挂断"
    "cancelled" -> "已取消"
    else -> reason
}
