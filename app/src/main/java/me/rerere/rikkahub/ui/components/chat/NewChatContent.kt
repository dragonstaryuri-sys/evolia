package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.menu.TimeLabel

/**
 * Stats data for new chat widgets
 */
data class NewChatStats(
    val dailyStreak: Int = 0,
    val totalChats: Int = 0,
    val timeLabel: TimeLabel = TimeLabel.DAYTIME_CHATTER,
    val hasChattedToday: Boolean = false,
    // Per-assistant stats (when viewing specific assistant)
    val assistantChats: Int = 0,
    val mostUsedModelName: String? = null
)

/**
 * New chat content shown when there are no preset messages.
 * Now simplified as customization is removed.
 */
@Composable
fun NewChatContent(
    assistant: Assistant,
    stats: NewChatStats,
    hasBackgroundImage: Boolean = false,
    onTemplateClick: (String) -> Unit,
    onNavigateToImageGen: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 目前逻辑下，只有虚拟世界模式会显示此组件
    if (assistant.isVirtualWorldMode) {
        VirtualWorldWelcome(assistant)
        return
    }

    // 如果以后普通模式也要显示，可以在这里添加简单的默认布局
}

@Composable
private fun VirtualWorldWelcome(assistant: Assistant) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(32.dp).fillMaxWidth()
    ) {
        UIAvatar(
            name = assistant.name,
            value = assistant.avatar,
            modifier = Modifier.size(100.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "您已进入 ${assistant.name} 的世界",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "在这里，一切皆有可能。你们可以接触到彼此，感受真实的互动。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Rounded.Public,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "虚拟世界模式已开启",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
