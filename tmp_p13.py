# -*- coding: utf-8 -*-
import re

# ============ 1) 语音配置 sheet 横向边距 ============
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantVoicePage.kt'
s = open(p, encoding='utf-8').read()
old = '''            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {'''
new = '''            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {'''
assert old in s, 'sheet lazy'
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print('1 sheet padding ok')

# ============ 2) EpisodeCard 按钮右对齐（标题 fill）============
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/EpisodeCard.kt'
s = open(p, encoding='utf-8').read()
old = '''                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )'''
new = '''                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )'''
assert old in s, 'card title'
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print('2 card align ok')

# ============ 3) 栏目详情只显示已发布 ============
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioChannelPage.kt'
s = open(p, encoding='utf-8').read()
old = '        val filteredEpisodes = episodes.filter { it.title.contains(searchQuery, ignoreCase = true) }'
new = '''        // 栏目详情只显示已发布节目（草稿仅在草稿箱 tab 展示）
        val filteredEpisodes = episodes.filter {
            it.status == RadioEpisodeStatus.PUBLISHED && it.title.contains(searchQuery, ignoreCase = true)
        }'''
assert old in s, 'channel filter'
s = s.replace(old, new, 1)

# 栏目页卡片加 onPlayNow
old = '''            items(filteredEpisodes, key = { it.id }) { episode ->
                EpisodeCard(
                    episode = episode,
                    assistant = assistantMap[episode.creatorAgentId],
                    onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) },
                    onOpenActions = { actionEpisode = episode }
                )
            }'''
new = '''            items(filteredEpisodes, key = { it.id }) { episode ->
                EpisodeCard(
                    episode = episode,
                    assistant = assistantMap[episode.creatorAgentId],
                    onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) },
                    onOpenActions = { actionEpisode = episode },
                    onPlayNow = { vm.playEpisodeNow(episode) },
                    liked = episode.liked == 1
                )
            }'''
assert old in s, 'channel card'
s = s.replace(old, new, 1)

# 抽屉「喜欢」即时刷新：actionEpisode 由 episodes 流派生
old = '''    // 节目卡片「下一集播放 / 导出到本地」抽屉
    var actionEpisode by remember { mutableStateOf<RadioEpisodeEntity?>(null) }'''
new = '''    // 节目卡片「下一集播放 / 导出到本地」抽屉（episode 跟随 DB 刷新，喜欢状态即时更新）
    var actionEpisodeId by remember { mutableStateOf<String?>(null) }
    val actionEpisode = actionEpisodeId?.let { id -> episodes.find { it.id == id } }'''
assert old in s, 'action state'
s = s.replace(old, new, 1)
s = s.replace('onOpenActions = { actionEpisode = episode },', 'onOpenActions = { actionEpisodeId = episode.id },', 1)
s = s.replace('''            onDismiss = { actionEpisode = null },''', '''            onDismiss = { actionEpisodeId = null },''', 1)
s = s.replace('''            onPlayNext = {
                actionEpisode = null
                vm.playEpisodeNext(episode)
            },''', '''            onPlayNext = {
                actionEpisodeId = null
                vm.playEpisodeNext(episode)
            },''', 1)
s = s.replace('''            onExport = {
                actionEpisode = null
                vm.exportEpisodeAudio(episode)
            }''', '''            onExport = {
                actionEpisodeId = null
                vm.exportEpisodeAudio(episode)
            }''', 1)

# ChannelVM 加 playEpisodeNow
s = s.replace('''    /** 从栏目页直接导出该节目音频到公共目录。 */''', '''    /** 「立即播放」：加入队列当前节目之后并立即跳播该节目。 */
    fun playEpisodeNow(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val asset = radioService.getCurrentAudioAsset(episode.id)
                    ?: throw RadioServiceException("该节目还没有音频，请先生成音频")
                val file = radioService.resolveAssetFile(asset)
                if (!file.exists()) throw RadioServiceException("音频文件不存在，请重新生成音频")
                MediaPlaybackController.radioEpisode(
                    id = episode.id,
                    filePath = file.absolutePath,
                    title = episode.title,
                    channelName = channel.value?.name.orEmpty()
                )
            }.onSuccess { item ->
                mediaPlaybackController.playNextNow(item)
            }.onFailure { showError(it) }
        }
    }

    /** 从栏目页直接导出该节目音频到公共目录。 */''', 1)
open(p, 'w', encoding='utf-8').write(s)
print('3 channel ok')

# ============ 4) 首页全部节目：回调 + 排序显示 ============
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioHomePage.kt'
s = open(p, encoding='utf-8').read()
old = '''                        itemsIndexed(filteredAll, key = { _, item -> "all_${item.id}" }) { _, episode ->
                            EpisodeCard(
                                episode = episode,
                                assistant = assistantMap[episode.creatorAgentId],
                                onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) }
                            )
                            if (episode == filteredAll.lastOrNull()) {
                                LaunchedEffect(filteredAll.size) {
                                    vm.loadMoreAllEpisodes()
                                }
                            }
                        }'''
new = '''                        itemsIndexed(filteredAll, key = { _, item -> "all_${item.id}" }) { _, episode ->
                            EpisodeCard(
                                episode = episode,
                                assistant = assistantMap[episode.creatorAgentId],
                                onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) },
                                onOpenActions = {
                                    actionEpisodeId = episode.id
                                    actionSource = "all"
                                },
                                onPlayNow = { vm.playEpisodeNow(episode) },
                                liked = episode.liked == 1
                            )
                            if (episode == filteredAll.lastOrNull()) {
                                LaunchedEffect(filteredAll.size) {
                                    vm.loadMoreAllEpisodes()
                                }
                            }
                        }'''
assert old in s, 'home card'
s = s.replace(old, new, 1)

# 首页 actionEpisode 同样联动刷新 + 全部节目排序状态暴露
s = s.replace('''    var actionEpisode by remember { mutableStateOf<RadioEpisodeEntity?>(null) }
    var actionSource by remember { mutableStateOf("all") }''', '''    var actionEpisodeId by remember { mutableStateOf<String?>(null) }
    var actionSource by remember { mutableStateOf("all") }
    val actionEpisode = actionEpisodeId?.let { id -> allEpisodes.find { it.id == id } }''', 1)
s = s.replace('''            onDismiss = { actionEpisode = null },''', '''            onDismiss = { actionEpisodeId = null },''', 1)
s = s.replace('''            onPlayNext = {
                actionEpisode = null
                vm.playEpisodeNext(episode)
            },''', '''            onPlayNext = {
                actionEpisodeId = null
                vm.playEpisodeNext(episode)
            },''', 1)
s = s.replace('''            onToggleLiked = { vm.toggleEpisodeLiked(episode) },''', '''            onToggleLiked = { vm.toggleEpisodeLiked(episode) },''', 1)
s = s.replace('''            onExport = {
                actionEpisode = null
                vm.exportEpisodeAudio(episode)
            }''', '''            onExport = {
                actionEpisodeId = null
                vm.exportEpisodeAudio(episode)
            }''', 1)

# RadioHomeVM：allAscending 暴露给 UI
s = s.replace('''    private val allAscending = MutableStateFlow(false)

    /** 全部节目（含排序方向）。 */
    val allEpisodesSorted: StateFlow<List<RadioEpisodeEntity>> =''', '''    private val _allAscending = MutableStateFlow(false)

    /** 全部节目排序方向（false=新→旧）。 */
    val allAscending: StateFlow<Boolean> = _allAscending.asStateFlow()

    /** 全部节目（含排序方向）。 */
    val allEpisodesSorted: StateFlow<List<RadioEpisodeEntity>> =''', 1)
s = s.replace('''        kotlinx.coroutines.flow.combine(_allEpisodes, allAscending) { list, ascending ->
            if (ascending) list.reversed() else list
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleAllOrder() {
        allAscending.value = !allAscending.value
    }''', '''        kotlinx.coroutines.flow.combine(_allEpisodes, _allAscending) { list, ascending ->
            if (ascending) list.reversed() else list
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleAllOrder() {
        _allAscending.value = !_allAscending.value
    }''', 1)

# 首页 actions 抽屉喜欢操作（RadioHomeVM.toggleEpisodeLiked 已存在 ✓）
open(p, 'w', encoding='utf-8').write(s)
print('4 home ok')

# ============ 5) sleep endOfEpisode：AUTOMATIC transition（重加，带 assert）============
p = 'app/src/main/java/me/rerere/rikkahub/service/media/MediaPlaybackController.kt'
s = open(p, encoding='utf-8').read()
assert 'MEDIA_ITEM_TRANSITION_REASON_AUTOMATIC' not in s
old = '''        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 「只应用到当前节目」：一次性生效——切节目（非单曲循环重播）时
            // 倍速恢复 1.0x 并自动关闭该开关；关闭开关时切节目保持当前倍速
            if (_speedOnlyCurrent.value && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                controller?.setPlaybackSpeed(1f)
                _speedOnlyCurrent.value = false
            }
            syncStateFromPlayer()
        }'''
new = '''        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 「定时到本集结束」：本集自然播完（自动切集）即暂停并重置开关。
            // 列表循环/单集循环下 STATE_ENDED 永不触发，必须用切集事件；
            // 用户手动 seek/切集（SEEK）不算播完。
            if (_sleepTimer.value.endOfEpisode && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTOPLAY) {
                ensureController()?.pause()
                _sleepTimer.value = SleepTimerState(triggered = true)
            }
            // 「只应用到当前节目」：一次性生效——切节目（非单曲循环重播）时
            // 倍速恢复 1.0x 并自动关闭该开关；关闭开关时切节目保持当前倍速
            if (_speedOnlyCurrent.value && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                controller?.setPlaybackSpeed(1f)
                _speedOnlyCurrent.value = false
            }
            syncStateFromPlayer()
        }'''
assert old in s, 'transition anchor'
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print('5 sleep ok')

# ============ 6) 详情页播放器右上角加入队列 ============
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioEpisodePage.kt'
s = open(p, encoding='utf-8').read()

# PlayerCard 参数加 onAddToQueue + 右上角图标（重新生成行改成 Row：重新生成 + 加入队列图标）
s = s.replace('''    onGenerate: () -> Unit,
    onCancelGeneration: () -> Unit,''', '''    onGenerate: () -> Unit,
    onCancelGeneration: () -> Unit,
    onAddToQueue: () -> Unit,''')
s = s.replace('''                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (generating) {
                            InlineSpinner(size = 16.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.radio_generating),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCancelGeneration) {
                                Text(stringResource(R.string.radio_stop))
                            }
                        } else {
                            TextButton(onClick = onGenerate, enabled = !generating) {
                                Text(stringResource(R.string.radio_regenerate))
                            }
                        }
                    }''', '''                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (generating) {
                            InlineSpinner(size = 16.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.radio_generating),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCancelGeneration) {
                                Text(stringResource(R.string.radio_stop))
                            }
                        } else {
                            TextButton(onClick = onGenerate, enabled = !generating) {
                                Text(stringResource(R.string.radio_regenerate))
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onAddToQueue) {
                            Icon(
                                Icons.Rounded.PlaylistAdd,
                                contentDescription = stringResource(R.string.radio_add_to_queue)
                            )
                        }
                    }''')

# 调用处传参
s = s.replace('''                    onCancelGeneration = { vm.cancelAudioGeneration() },''',
'''                    onCancelGeneration = { vm.cancelAudioGeneration() },
                    onAddToQueue = { vm.addToQueue() },''')

# imports
if 'import androidx.compose.material.icons.rounded.PlaylistAdd\n' not in s:
    s = s.replace('import androidx.compose.material.icons.rounded.PlayArrow\n',
                  'import androidx.compose.material.icons.rounded.PlaylistAdd\nimport androidx.compose.material.icons.rounded.PlayArrow\n', 1)
if 'import androidx.compose.material.icons.rounded.PlaylistAdd' not in s:
    raise SystemExit('PlaylistAdd import failed')

# RadioEpisodeVM.addToQueue（加在 togglePlay 后）
s = s.replace('''    fun seekTo(positionMs: Long) {''', '''    /** 把当前节目加入播放队列末尾（不改变当前播放）。 */
    fun addToQueue() {
        val asset = currentAsset.value ?: run {
            _message.value = RadioMessage(stringResource(R.string.radio_toast_no_audio_export), isError = true)
            return
        }
        val ep = episode.value ?: return
        val file = RadioAudioGenerator.resolveAudioFile(context, asset.filePath)
        if (!file.exists()) {
            _message.value = RadioMessage(stringResource(R.string.radio_toast_audio_missing_regen), isError = true)
            return
        }
        mediaPlaybackController.playNext(
            MediaPlaybackController.radioEpisode(
                id = ep.id,
                filePath = file.absolutePath,
                title = ep.title,
                channelName = ""
            )
        )
    }

    fun seekTo(positionMs: Long) {''', 1)

# i18n 资源补两条
extra = [
 ("radio_add_to_queue", "Add to queue", "加入播放队列", "加入播放隊列"),
 ("radio_stop", "Stop", "终止", "終止"),
]
for p, lang_idx in [('app/src/main/res/values/strings.xml', 1), ('app/src/main/res/values-zh/strings.xml', 2), ('app/src/main/res/values-zh-rTW/strings.xml', 3)]:
    s = open(p, encoding='utf-8').read()
    add = ''
    for item in extra:
        if f'name="{item[0]}"' not in s:
            val = item[lang_idx].replace("'", "\\'")
            add += f'  <string name="{item[0]}">{val}</string>\n'
    s = s.replace('</resources>', add + '</resources>')
    open(p, 'w', encoding='utf-8').write(s)
print('6 queue btn ok')

# ============ 7) 草稿不进全部节目/喜欢（已在 5 做全部节目；喜欢天然只有点赞的）============
# ============ 8) EpisodeCard 播放按钮/三点右对齐已由 2 完成（Row weight(1f) 标题填充）============
print('all done')
