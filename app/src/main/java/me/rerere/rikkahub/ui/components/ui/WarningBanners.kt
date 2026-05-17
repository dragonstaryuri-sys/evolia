package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/**
 * 提示设置默认聊天模型的横幅
 */
@Composable
fun ChatModelWarningBanner(navController: NavController) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        onClick = { navController.navigate(Screen.SettingModels) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Warning,
                null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.warning_no_default_chat_model),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * 提示配置 API 提供商的卡片 (原 SettingPage 里的)
 */
@Composable
fun ProviderConfigWarningCard(navController: NavController) {
    Surface(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        onClick = { navController.navigate(Screen.SettingProvider) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Cloud,
                null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    stringResource(R.string.setting_page_config_api_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    stringResource(R.string.setting_page_config_api_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * 记忆功能缺失嵌入模型的横幅
 */
@Composable
fun EmbeddingModelWarningBanner(onNavigateToModels: () -> Unit) {
    Surface(
        onClick = onNavigateToModels,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Rounded.Warning,
                null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.warning_no_embedding_model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
