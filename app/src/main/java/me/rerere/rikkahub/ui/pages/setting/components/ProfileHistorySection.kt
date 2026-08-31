package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.ProfileHistoryEntity
import me.rerere.rikkahub.utils.writeClipboardText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单字段历史版本展示区
 *
 * 嵌入到每个档案字段（如 appearance / preferences / personality）的输入框下方，
 * 显示该字段历次 AI update_profile 调用前的旧值，最多保留
 * [ProfileHistoryEntity.MAX_KEEP_VERSIONS] 个版本。
 *
 * UI 行为：
 * - 默认收起，点击标题行可展开
 * - 不显示字段名（已在外层字段标签上体现）
 * - 不显示新值 / 不显示旧→新演变箭头
 * - 每条历史值右侧提供复制按钮，点击复制该版本内容到系统剪贴板
 *
 * @param records 同一字段的历史记录列表（按时间倒序），可为空
 */
@Composable
fun FieldHistorySection(
    records: List<ProfileHistoryEntity>,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxWidth()) {
        // 标题行（可点击折叠/展开）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(R.string.profile_history_field_title, records.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                records.forEach { record ->
                    HistoryValueRow(
                        value = record.oldValue,
                        timeText = dateFormatter.format(Date(record.createdAt)),
                        onCopy = { context.writeClipboardText(record.oldValue) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryValueRow(
    value: String,
    timeText: String,
    onCopy: () -> Unit
) {
    val valueDisplay = value.ifBlank { "（空）" }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = valueDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable { onCopy() }
                    .padding(2.dp)
            )
        }
    }
}

/**
 * 把扁平的 ProfileHistoryEntity 列表按 fieldKey 分组，便于 UI 端按字段渲染。
 * 返回值 Map 的 value 已按时间倒序排列（最近变更在前）。
 */
fun groupByField(records: List<ProfileHistoryEntity>): Map<String, List<ProfileHistoryEntity>> {
    return records.groupBy { it.fieldKey }
}
