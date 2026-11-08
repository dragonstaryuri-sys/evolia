package me.rerere.rikkahub.ui.pages.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.common.ui.components.AppFab
import me.rerere.rikkahub.common.ui.components.ConfirmDialog
import me.rerere.rikkahub.common.ui.components.EmptyPlaceholder
import me.rerere.rikkahub.core.data.db.entity.RadioChannelEntity
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 小机电台首页：底部胶囊三 tab（栏目 / 我的喜欢 / 草稿箱）。
 * - 栏目 tab：栏目列表 + 下方「全部节目」分页列表（创建时间新→旧）；
 * - 我的喜欢：点赞节目（可切换升降序）；
 * - 草稿箱：全部草稿（详情只读文字稿，音频/发布由 Agent 工具操作）。
 * 各列表右上角搜索按钮，范围=当前列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioHomePage() {
    val vm: RadioHomeVM = koinViewModel()
    val player: me.rerere.rikkahub.service.media.MediaPlaybackController = koinInject()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val channels by vm.channels.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val assistantMap by vm.assistantMap.collectAsStateWithLifecycle()
    val likedEpisodes by vm.likedEpisodes.collectAsStateWithLifecycle()
    val likedAscending by vm.likedAscending.collectAsStateWithLifecycle()
    val draftEpisodes by vm.draftEpisodes.collectAsStateWithLifecycle()
    val allEpisodes by vm.allEpisodesSorted.collectAsStateWithLifecycle()
    val allAscending by vm.allAscending.collectAsStateWithLifecycle()

    var showChannelSheet by remember { mutableStateOf(false) }
    var editingChannel by remember { mutableStateOf<RadioChannelEntity?>(null) }
    var deletingChannel by remember { mutableStateOf<RadioChannelEntity?>(null) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var actionEpisodeId by remember { mutableStateOf<String?>(null) }

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
                            placeholder = {
                                Text(
                                    when (tab) {
                                        RadioHomeVM.RadioHomeTab.CHANNELS -> stringResource(R.string.radio_search_all)
                                        RadioHomeVM.RadioHomeTab.LIKED -> stringResource(R.string.radio_search_liked)
                                        RadioHomeVM.RadioHomeTab.DRAFTS -> stringResource(R.string.radio_search_drafts)
                                    }
                                )
                            },
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
                    title = stringResource(R.string.radio_page_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { BackButton() },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.radio_search_episodes))
                        }
                        IconButton(onClick = { showPlaylistSheet = true }) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = stringResource(R.string.radio_playback_queue))
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (tab == RadioHomeVM.RadioHomeTab.CHANNELS && !searching) {
                AppFab(onClick = {
                    editingChannel = null
                    showChannelSheet = true
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.radio_create_channel))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    RadioHomeVM.RadioHomeTab.CHANNELS -> {
                        val filteredChannels = channels.filter {
                            it.name.contains(searchQuery, ignoreCase = true) ||
                                it.description.contains(searchQuery, ignoreCase = true)
                        }
                        if (filteredChannels.isEmpty() && searchQuery.isBlank()) {
                            item(key = "channels_empty") {
                                EmptyPlaceholder(
                                    text = stringResource(R.string.radio_channels_empty),
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Icons.Rounded.Radio
                                )
                            }
                        }
                        items(filteredChannels, key = { "channel_${it.id}" }) { channel ->
                            ChannelCard(
                                channel = channel,
                                onOpen = { navController.navigate(Screen.RadioChannel(channelId = channel.id)) },
                                onEdit = {
                                    editingChannel = channel
                                    showChannelSheet = true
                                },
                                onDelete = { deletingChannel = channel }
                            )
                        }

                        // ===== 全部节目（分页 + 右上角排序，与我的喜欢 tab 同款组件）=====
                        item(key = "all_episodes_header") {
                            EpisodeSortHeader(
                                title = stringResource(R.string.radio_all_episodes),
                                ascending = allAscending,
                                onToggle = { vm.toggleAllOrder() },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        val filteredAll = allEpisodes.filter { it.title.contains(searchQuery, ignoreCase = true) }
                        if (filteredAll.isEmpty()) {
                            item(key = "all_episodes_empty") {
                                Text(
                                    text = stringResource(R.string.radio_no_episodes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                        itemsIndexed(filteredAll, key = { _, item -> "all_${item.id}" }) { _, episode ->
                            EpisodeCard(
                                episode = episode,
                                assistant = assistantMap[episode.creatorAgentId],
                                onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) },
                                onOpenActions = { actionEpisodeId = episode.id },
                                onPlayNow = { vm.playEpisodeNow(episode) },
                                liked = episode.liked == 1
                            )
                            if (episode == filteredAll.lastOrNull()) {
                                LaunchedEffect(filteredAll.size) {
                                    vm.loadMoreAllEpisodes()
                                }
                            }
                        }
                    }

                    RadioHomeVM.RadioHomeTab.LIKED -> {
                        item(key = "liked_header") {
                            EpisodeSortHeader(
                                title = null,
                                ascending = likedAscending,
                                onToggle = { vm.toggleLikedOrder() }
                            )
                        }
                        val filteredLiked = likedEpisodes.filter { it.title.contains(searchQuery, ignoreCase = true) }
                        if (filteredLiked.isEmpty()) {
                            item(key = "liked_empty") {
                                EmptyPlaceholder(
                                    text = stringResource(R.string.radio_liked_empty),
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Icons.Rounded.Favorite
                                )
                            }
                        }
                        items(filteredLiked, key = { "liked_${it.id}" }) { episode ->
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

                    RadioHomeVM.RadioHomeTab.DRAFTS -> {
                        val filteredDrafts = draftEpisodes.filter { it.title.contains(searchQuery, ignoreCase = true) }
                        if (filteredDrafts.isEmpty()) {
                            item(key = "drafts_empty") {
                                EmptyPlaceholder(
                                    text = stringResource(R.string.radio_drafts_empty),
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Icons.Rounded.Description
                                )
                            }
                        }
                        items(filteredDrafts, key = { "draft_${it.id}" }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                assistant = assistantMap[episode.creatorAgentId],
                                onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) }
                            )
                        }
                    }
                }
            }

            // 底部浮动胶囊 tab（参照 DiaryListPage 的浮动 tab bar）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, AppShapes.CardLarge)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RadioHomeTabIcon(
                        icon = { Icon(Icons.Rounded.Radio, contentDescription = stringResource(R.string.radio_tab_channels)) },
                        contentDescription = "栏目",
                        selected = tab == RadioHomeVM.RadioHomeTab.CHANNELS,
                        onClick = { vm.switchTab(RadioHomeVM.RadioHomeTab.CHANNELS) }
                    )
                    RadioHomeTabIcon(
                        icon = { Icon(Icons.Rounded.Favorite, contentDescription = stringResource(R.string.radio_tab_liked)) },
                        contentDescription = stringResource(R.string.radio_tab_liked),
                        selected = tab == RadioHomeVM.RadioHomeTab.LIKED,
                        onClick = { vm.switchTab(RadioHomeVM.RadioHomeTab.LIKED) }
                    )
                    RadioHomeTabIcon(
                        icon = { Icon(Icons.Rounded.Description, contentDescription = stringResource(R.string.radio_tab_drafts)) },
                        contentDescription = stringResource(R.string.radio_tab_drafts),
                        selected = tab == RadioHomeVM.RadioHomeTab.DRAFTS,
                        onClick = { vm.switchTab(RadioHomeVM.RadioHomeTab.DRAFTS) }
                    )
                }
            }
        }
    }

    if (showChannelSheet) {
        ChannelEditSheet(
            channel = editingChannel,
            onDismiss = { showChannelSheet = false },
            onSave = { name, description, prompt ->
                showChannelSheet = false
                val target = editingChannel
                if (target == null) {
                    vm.createChannel(name, description, prompt)
                } else {
                    vm.updateChannel(target.id, name, description, prompt)
                }
            }
        )
    }

    deletingChannel?.let { channel ->
        ConfirmDialog(
            onDismissRequest = { deletingChannel = null },
            onConfirm = {
                deletingChannel = null
                vm.deleteChannel(channel.id, deleteEpisodes = true)
            },
            title = stringResource(R.string.radio_delete_channel_title),
            text = stringResource(R.string.radio_delete_channel_confirm, channel.name),
            confirmText = stringResource(R.string.radio_delete),
            dismissText = stringResource(R.string.radio_cancel),
            dangerous = true
        )
    }

    if (showPlaylistSheet) {
        PlaylistSheet(controller = player, onDismiss = { showPlaylistSheet = false })
    }

    // 「更多」抽屉：episode 走响应式查询（喜欢状态即时刷新，与栏目详情页行为一致）
    val actionEpisodeFlow = remember(actionEpisodeId) {
        actionEpisodeId?.let { id -> vm.observeEpisodeById(id) }
    }
    actionEpisodeFlow?.let { flow ->
        val actionEpisode by flow.collectAsStateWithLifecycle(initialValue = null)
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
}

/**
 * 列表头部排序控件（我的喜欢 / 全部节目共用）：
 * 左侧可选标题，右侧「从旧→新 / 从新→旧」文字 + 升降序箭头按钮。
 */
@Composable
private fun EpisodeSortHeader(
    title: String?,
    ascending: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(if (ascending) R.string.radio_sort_oldest else R.string.radio_sort_newest),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                contentDescription = stringResource(R.string.radio_toggle_sort)
            )
        }
    }
}

/** 浮动胶囊 tab 的单个图标圆底（参照 DiaryViewTab）。 */
@Composable
private fun RadioHomeTabIcon(
    icon: @Composable () -> Unit,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = {
                if (!selected) {
                    haptics.perform(HapticPattern.Tick)
                    onClick()
                }
            },
            enabled = !selected
        ) {
            icon()
        }
    }
}

@Composable
private fun ChannelCard(
    channel: RadioChannelEntity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        shape = AppShapes.CardLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = AppShapes.ListItemSmall,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Radio,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (channel.description.isNotBlank()) {
                    Text(
                        text = channel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.radio_channel_actions))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = AppShapes.ButtonSquared
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.radio_edit_channel)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.radio_delete_channel)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/** 栏目创建/编辑底部表单（多字段，故用 BottomSheet 而非 InputDialog）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelEditSheet(
    channel: RadioChannelEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, systemPrompt: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember(channel) { mutableStateOf(channel?.name.orEmpty()) }
    var description by remember(channel) { mutableStateOf(channel?.description.orEmpty()) }
    var systemPrompt by remember(channel) { mutableStateOf(channel?.systemPrompt.orEmpty()) }
    val nameValid = name.trim().isNotBlank()

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(if (channel == null) R.string.radio_create_channel_title else R.string.radio_edit_channel_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.radio_channel_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.radio_channel_desc)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.radio_channel_prompt)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.radio_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    enabled = nameValid,
                    onClick = { onSave(name.trim(), description.trim(), systemPrompt.trim()) }
                ) {
                    Text(stringResource(R.string.radio_save))
                }
            }
        }
    }
}
