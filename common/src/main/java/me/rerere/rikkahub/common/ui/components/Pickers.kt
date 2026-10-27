package me.rerere.rikkahub.common.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.AppShapes

/**
 * 通用日期选择弹窗（M3 DatePickerDialog + 显式配色）。
 * 禁止使用 android.app.DatePickerDialog（脱离 Compose 主题，深色模式不受控）。
 */
@Composable
fun EvoliaDatePickerDialog(
    state: DatePickerState,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String,
    dismissText: String? = null,
) {
    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        shape = AppShapes.Dialog,
        colors = DatePickerDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
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
    ) {
        DatePicker(state = state)
    }
}

/**
 * 通用时间选择弹窗（AlertDialog 包裹 M3 TimePicker + 显式配色）。
 * 禁止使用 android.app.TimePickerDialog（脱离 Compose 主题，深色模式不受控）。
 */
@Composable
fun EvoliaTimePickerDialog(
    state: TimePickerState,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String,
    title: String? = null,
    dismissText: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = AppShapes.Dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = if (title != null) {
            {
                Text(text = title)
            }
        } else null,
        text = {
            TimePicker(state = state)
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
