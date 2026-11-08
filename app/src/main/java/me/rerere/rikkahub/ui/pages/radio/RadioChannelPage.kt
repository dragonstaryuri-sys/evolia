package me.rerere.rikkahub.ui.pages.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import me.rerere.rikkahub.service.media.MediaPlaybackController
import org.koin.compose.koinInject
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.common.ui.components.EmptyPlaceholder
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.service.media.PlaybackItem
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 栏目详情页：节目列表 + 按栏目顺序播放全部已发布节目。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioChannelPage(channelId: String) {
    val vm: RadioChannelVM = koinViewModel(parameters = { parametersOf(channelId) })
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val channel by vm.channel.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    // 节目卡片「下一集播放 / 导出到本地」抽屉（episode 跟随 DB 刷新，喜欢状态即时更新）
    var actionEpisodeId by remember { mutableStateOf<String?>(null) }
    val actionEpisode = actionEpisodeId?.let { id -> episodes.find { it.id == id } }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val player: MediaPlaybackController = koinInject()
    val assistantMap by vm.assistantMap.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            toaster.show(message = it.text, type = if (it.isError) ToastType.Error else ToastType.Success)
            vm.clearMessage()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (searching) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.radio_search_channel)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = { BackButton() },
                    actions = {
                        IconButton(onClick = {
                            searching = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.radio_exit_search))
                        }
                    }
                )
            } else {
            OneUITopAppBar(
                title = channel?.name ?: stringResource(R.string.radio_channel_title_fallback),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.radio_search_episodes))
                    }
                    IconButton(onClick = { showPlaylistSheet = true }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "播放队列")
                    }
                    IconButton(onClick = { vm.playAllPublished() }) {
                        Icon(Icons.Rounded.PlayCircle, contentDescription = stringResource(R.string.radio_play_all))
                    }
                }
            )
            }
        }
    ) { padding ->
        // 栏目详情只显示已发布节目（草稿仅在草稿箱 tab 展示）
        val filteredEpisodes = episodes.filter {
            it.status == RadioEpisodeStatus.PUBLISHED && it.title.contains(searchQuery, ignoreCase = true)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (channel?.description?.isNotBlank() == true) {
                item {
                    Text(
                        text = channel?.description.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (episodes.isEmpty()) {
                item {
                    EmptyPlaceholder(
                        text = "本栏目还没有节目\n和 Agent 聊聊，让它创作一期节目吧",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        icon = Icons.Rounded.GraphicEq
                    )
                }
            }
            items(filteredEpisodes, key = { it.id }) { episode ->
                EpisodeCard(
                    episode = episode,
                    assistant = assistantMap[episode.creatorAgentId],
                    onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) },
                    onOpenActions = { actionEpisodeId = episode.id },
                    onPlayNow = { vm.playEpisodeNow(episode) },
                    liked = episode.liked == 1
                )
            }
        }
    }

    if (showPlaylistSheet) {
        PlaylistSheet(controller = player, onDismiss = { showPlaylistSheet = false })
    }

    // 节目操作抽屉：下一集播放 / 导出到本地
    actionEpisode?.let { episode ->
        EpisodeActionsSheet(
            episode = episode,
            onDismiss = { actionEpisodeId = null },
            onPlayNext = {
                actionEpisodeId = null
                vm.playEpisodeNext(episode)
            },
            onToggleLiked = { vm.toggleEpisodeLiked(episode) },
            onExport = {
                actionEpisodeId = null
                vm.exportEpisodeAudio(episode)
            }
        )
    }
}

internal fun formatDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}
