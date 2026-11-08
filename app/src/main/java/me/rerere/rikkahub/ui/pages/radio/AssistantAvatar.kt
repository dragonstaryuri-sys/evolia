package me.rerere.rikkahub.ui.pages.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Avatar

/**
 * 助手小头像（节目卡片主播标识，类似节目封面角标）：
 * Resource/Image → 图片；Emoji → emoji；Dummy → 名字首字母。
 */
@Composable
fun AssistantAvatar(assistant: Assistant, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        when (val avatar = assistant.avatar) {
            is Avatar.Image -> {
                AsyncImage(
                    model = avatar.url,
                    contentDescription = assistant.name,
                    modifier = Modifier.size(size),
                    contentScale = ContentScale.Crop
                )
            }

            is Avatar.Emoji -> {
                Text(text = avatar.content, fontSize = (size.value * 0.6f).sp)
            }

            is Avatar.Resource -> {
                AsyncImage(
                    model = avatar.id,
                    contentDescription = assistant.name,
                    modifier = Modifier.size(size),
                    contentScale = ContentScale.Crop
                )
            }

            else -> {
                Text(
                    text = assistant.name.take(1).uppercase().ifBlank { "E" },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = (size.value * 0.55f).sp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
