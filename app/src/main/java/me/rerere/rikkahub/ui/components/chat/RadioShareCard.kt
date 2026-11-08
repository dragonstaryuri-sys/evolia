package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeEntity
import me.rerere.rikkahub.service.media.MediaPlaybackController
import me.rerere.rikkahub.service.media.PlaybackSourceType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject

/**
 * share_episode 电台分享卡片（消息流内，随消息滚动）：
 * - 播放/暂停按钮：控制该节目音频（不触发 AI 轮次）；
 * - 点击卡片整体：跳转到该节目的详情页；
 * - 自行校验节目存在与音频可用（不可用时按钮禁用并提示）。
 */
@Composable
fun RadioShareCard(
    episodeId: String,
    modifier: Modifier = Modifier,
) {
    val player = koinInject<MediaPlaybackController>()
    val radioService = koinInject<me.rerere.rikkahub.data.radio.RadioService>()
    val appScope = koinInject<AppScope>()
    val navController = LocalNavController.current
    val playerState by player.state.collectAsStateWithLifecycle()

    var episode by remember(episodeId) { mutableStateOf<RadioEpisodeEntity?>(null) }
    var playable by remember(episodeId) { mutableStateOf<Boolean?>(null) } // null = 加载中

    LaunchedEffect(episodeId) {
        withContext(Dispatchers.IO) {
            episode = runCatching { radioService.getEpisode(episodeId) }.getOrNull()
            playable = episode?.let { radioService.buildPlaybackItem(episodeId) != null } ?: false
        }
    }

    val isCurrent = playerState.currentItem?.sourceType == PlaybackSourceType.RADIO_EPISODE &&
        playerState.currentItem?.id == episodeId
    val isPlayingThis = isCurrent && playerState.isPlaying
    val displayTitle = episode?.title ?: "电台节目"

    Surface(
        onClick = { navController.navigate(me.rerere.rikkahub.Screen.RadioEpisode(episodeId = episodeId)) },
        shape = AppShapes.CardMedium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = AppShapes.ListItemSmall,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Radio,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.radio_share_card_title, displayTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (playable) {
                        false -> stringResource(R.string.radio_share_unavailable)
                        true -> if (isPlayingThis) stringResource(R.string.radio_share_playing) else stringResource(R.string.radio_share_tap_play)
                        null -> stringResource(R.string.radio_share_loading)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = {
                    if (isCurrent) {
                        player.togglePlayPause()
                    } else {
                        appScope.launch(Dispatchers.IO) {
                            val item = radioService.buildPlaybackItem(episodeId) ?: return@launch
                            player.playSingleItem(item)
                        }
                    }
                },
                enabled = playable == true || (isCurrent && playable != false)
            ) {
                Icon(
                    imageVector = if (isPlayingThis) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (isPlayingThis) R.string.radio_pause else R.string.radio_play),
                    tint = if (playable == false) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}
