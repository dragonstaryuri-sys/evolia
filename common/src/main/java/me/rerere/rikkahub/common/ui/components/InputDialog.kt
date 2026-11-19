package me.rerere.rikkahub.common.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import me.rerere.rikkahub.ui.components.ime.DialogImeResizeEffect
import me.rerere.rikkahub.ui.theme.AppShapes

/**
 * 通用文本输入弹窗（标题 + 输入框 + 确认/取消）。
 *
 * 收敛自「改标题 / 填写要求 / 添加标签」等各页手写实现。
 * onDismissRequest 由调用方决定语义（取消或保存），本组件不强制。
 * 内置 DialogImeResizeEffect：弹窗窗口随键盘 RESIZE，输入框不会被
 * 键盘遮挡裁切（2026-11-20 统一键盘避让，ImeAware 组件族）。
 */
@Composable
fun InputDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmText: String,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    placeholder: String? = null,
    label: String? = null,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    DialogImeResizeEffect()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = AppShapes.Dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(text = title)
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = if (placeholder != null) {
                    {
                        Text(text = placeholder)
                    }
                } else null,
                label = if (label != null) {
                    {
                        Text(text = label)
                    }
                } else null,
                singleLine = singleLine,
                shape = AppShapes.InputField,
                keyboardOptions = keyboardOptions,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
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
