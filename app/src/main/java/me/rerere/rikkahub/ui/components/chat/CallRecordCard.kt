package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.common.ui.components.ConfirmDialog
import me.rerere.rikkahub.core.data.db.entity.CallInitiator
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.AppShapes

/**
 * 聊天时间线中的「通话卡片」。
 *
 * 排布方式与普通消息气泡一致：
 * - AI 发起的通话：左侧显示 AI 头像，右侧紧跟通话卡片
 * - 用户发起的通话：右侧显示通话卡片，再右侧是用户头像
 *
 * - 成功接通：显示「通话时长 mm:ss」，点击进入通话详情页。
 * - 未接通（拒绝 / 未接 / 失败 / 已取消）：显示「已挂断」，点击重拨。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallRecordCard(
    record: CallRecordEntity,
    assistant: Assistant?,
    userAvatar: Avatar,
    wechatMode: Boolean,
    onClick: () -> Unit,
    onRedial: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isConnected = record.connectedAt != null || record.durationMs > 0L
    val label = if (isConnected) "通话时长 ${formatCallDuration(record.durationMs)}" else "已挂断"
    val icon = if (isConnected) Icons.Rounded.Call else Icons.Rounded.CallEnd
    val tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val isAgentInitiated = record.initiator == CallInitiator.AGENT
    val haptic = LocalHapticFeedback.current

    val card = @Composable {
        Card(
            shape = AppShapes.CardMedium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.combinedClickable(
                onClick = { if (isConnected) onClick() else onRedial() },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = if (isConnected) "查看详情" else "重拨",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isAgentInitiated) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        if (isAgentInitiated) {
            if (wechatMode) {
                UIAvatar(
                    name = assistant?.name?.ifEmpty { null } ?: "Assistant",
                    value = assistant?.avatar ?: Avatar.Dummy,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(top = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            card()
        } else {
            card()
            if (wechatMode) {
                Spacer(Modifier.width(8.dp))
                UIAvatar(
                    name = "我",
                    value = userAvatar,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

/** 毫秒时长格式化为 mm:ss。 */
fun formatCallDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * 「删除通话记录」确认弹窗。
 *
 * 对话页时间线卡片与历史通话列表共用；样式统一委托 common 的 ConfirmDialog
 * （dangerous = true → 确认按钮 error 色），保持原函数签名与调用点兼容。
 */
@Composable
fun CallRecordDeleteDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    ConfirmDialog(
        onDismissRequest = onDismissRequest,
        onConfirm = onConfirm,
        title = "删除通话记录",
        text = "确定要删除这条通话记录及其关联的聊天消息吗？此操作不可撤销。",
        confirmText = "删除",
        dismissText = stringResource(R.string.cancel),
        dangerous = true,
    )
}
