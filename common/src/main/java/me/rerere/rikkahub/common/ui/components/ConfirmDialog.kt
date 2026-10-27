package me.rerere.rikkahub.common.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.theme.AppShapes

/**
 * 通用确认弹窗。
 *
 * 样式基准（AGENTS 浮层规范）：container = surfaceContainerHigh，
 * 标题 = onSurface，正文 = onSurfaceVariant，取消按钮 = onSurfaceVariant。
 * [dangerous] 为 true 时（删除等破坏性操作）确认按钮使用 error 色，否则 primary。
 *
 * 字符串由调用方传入（common 不持有 app 的文案资源）。
 */
@Composable
fun ConfirmDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    confirmText: String,
    text: String? = null,
    dismissText: String? = null,
    dangerous: Boolean = false,
) {
    val confirmColor = if (dangerous) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = AppShapes.Dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(text = title)
        },
        text = if (text != null) {
            {
                Text(text = text)
            }
        } else null,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = confirmColor)
            ) {
                Text(text = confirmText)
            }
        },
        dismissButton = if (dismissText != null) {
            {
                TextButton(
                    onClick = onDismissRequest,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(text = dismissText)
                }
            }
        } else null,
    )
}
