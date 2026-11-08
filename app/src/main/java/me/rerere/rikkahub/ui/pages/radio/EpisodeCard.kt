package me.rerere.rikkahub.ui.pages.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.rounded.Pause
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import me.rerere.rikkahub.service.media.MediaPlaybackController
import me.rerere.rikkahub.service.media.PlaybackSourceType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.AppShapes

/** 节目标题在列表卡片中的最大字符数（超出省略；详情页展示完整名）。 */
private const val EPISODE_TITLE_MAX_CHARS = 13

/**
 * 电台节目列表卡片（首页全部节目/栏目节目列表/喜欢/草稿箱共用）：
 * - 标题行：主播头像(20dp) + 标题(≤13字省略) + 状态 Tag（右侧）；
 * - 底部行：「主播：XXX」+ 创建时间；
 * - [onOpenActions] 非空时显示右侧三点按钮（栏目页：下一集播放/导出）。
 */
@Composable
fun EpisodeCard(
    episode: RadioEpisodeEntity,
    assistant: Assistant?,
    onClick: () -> Unit,
    onOpenActions: (() -> Unit)? = null,
    onPlayNow: (() -> Unit)? = null,
    liked: Boolean = false
) {
    // 播放状态（与详情页播放器一致：在播显示暂停，暂停/未播显示播放）
    val player = org.koin.compose.koinInject<MediaPlaybackController>()
    val playerState by player.state.collectAsStateWithLifecycle()
    val isCurrent = playerState.currentItem?.sourceType == PlaybackSourceType.RADIO_EPISODE &&
        playerState.currentItem?.id == episode.id
    val isPlayingThis = isCurrent && playerState.isPlaying

    val displayTitle = if (episode.title.length > EPISODE_TITLE_MAX_CHARS) {
        episode.title.take(EPISODE_TITLE_MAX_CHARS) + "…"
    } else {
        episode.title
    }
    val assistantName = assistant?.name?.ifBlank { null }

    Surface(
        onClick = onClick,
        shape = AppShapes.CardLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                assistant?.let { a ->
                    AssistantAvatar(assistant = a, size = 20.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 已发布是常态不标注；仅草稿显示 Tag
                if (episode.status == RadioEpisodeStatus.DRAFT) {
                    Tag(type = TagType.INFO) {
                        Text(
                            text = "草稿",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (onOpenActions != null) {
                    IconButton(onClick = { onPlayNow?.invoke() }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "立即播放")
                    }
                    IconButton(onClick = onOpenActions) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "节目操作")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (assistantName != null) "主播：$assistantName" else "主播：未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatDateTime(episode.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onOpenActions == null) {
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }
        }
    }
}

/**
 * 节目操作抽屉：选择下一集播放（加入播放队列）/ 导出到本地。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeActionsSheet(
    episode: RadioEpisodeEntity,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleLiked: () -> Unit,
    onExport: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = AppShapes.BottomSheet
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ActionRow(
                icon = { Icon(Icons.Rounded.PlaylistPlay, contentDescription = null) },
                title = "下一集播放",
                subtitle = "插入当前播放队列，本集播完后自动播放",
                onClick = onPlayNext
            )
            ActionRow(
                icon = {
                    Icon(
                        imageVector = if (episode.liked == 1) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = if (episode.liked == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                },
                title = if (episode.liked == 1) "取消喜欢" else "喜欢",
                subtitle = "加入「我的喜欢」列表",
                onClick = onToggleLiked
            )
            ActionRow(
                icon = { Icon(Icons.Rounded.IosShare, contentDescription = null) },
                title = "导出到本地",
                subtitle = "复制音频副本到 Download/Evolia/radio/",
                onClick = onExport
            )
        }
    }
}

@Composable
fun ActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = AppShapes.ListItemSmall,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


