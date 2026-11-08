# -*- coding: utf-8 -*-
import re

# ==================== 1) EpisodeCard：播放/暂停状态切换（内部 koinInject controller）====================
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/EpisodeCard.kt'
s = open(p, encoding='utf-8').read()
orig = s

# imports
for imp in ['androidx.compose.material.icons.rounded.Pause',
            'androidx.lifecycle.compose.collectAsStateWithLifecycle',
            'org.koin.compose.koinInject',
            'me.rerere.rikkahub.service.media.MediaPlaybackController',
            'me.rerere.rikkahub.service.media.PlaybackSourceType']:
    if f'import {imp}\n' not in s:
        s = s.replace('import androidx.compose.material3.Icon\n',
                      f'import {imp}\nimport androidx.compose.material3.Icon\n', 1)

# 状态 + 按钮逻辑
s = s.replace('''    val displayTitle = if (episode.title.length > EPISODE_TITLE_MAX_CHARS) {''',
'''    // 播放状态（与详情页播放器一致：在播显示暂停，暂停/未播显示播放）
    val player = org.koin.compose.koinInject<MediaPlaybackController>()
    val playerState by player.state.collectAsStateWithLifecycle()
    val isCurrent = playerState.currentItem?.sourceType == PlaybackSourceType.RADIO_EPISODE &&
        playerState.currentItem?.id == episode.id
    val isPlayingThis = isCurrent && playerState.isPlaying

    val displayTitle = if (episode.title.length > EPISODE_TITLE_MAX_CHARS) {''')

s = s.replace('''                if (onOpenActions != null) {
                    IconButton(onClick = { onPlayNow?.invoke() }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.radio_play_now))
                    }
                    IconButton(onClick = onOpenActions) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.radio_episode_actions))
                    }
                }''', '''                if (onOpenActions != null) {
                    IconButton(onClick = {
                        if (isCurrent) {
                            // 在播该集：切换播放/暂停
                            player.togglePlayPause()
                        } else {
                            // 未播该集：加入队列并立即跳播（不清空原队列）
                            onPlayNow?.invoke()
                        }
                    }) {
                        Icon(
                            imageVector = if (isPlayingThis) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (isPlayingThis) R.string.radio_pause else R.string.radio_play)
                        )
                    }
                    IconButton(onClick = onOpenActions) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.radio_episode_actions))
                    }
                }''')

assert s != orig
open(p, 'w', encoding='utf-8').write(s)
print('1 EpisodeCard ok')

# ==================== 2) PlayerCard 右上角加入队列按钮（重插，带验证）====================
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioEpisodePage.kt'
s = open(p, encoding='utf-8').read()
old = '''                        } else {
                            TextButton(onClick = onGenerate, enabled = !generating) {
                                Text(stringResource(R.string.radio_regenerate))
                            }
                        }
                    }'''
new = '''                        } else {
                            TextButton(onClick = onGenerate, enabled = !generating) {
                                Text(stringResource(R.string.radio_regenerate))
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = onAddToQueue) {
                                Icon(
                                    Icons.Rounded.PlaylistAdd,
                                    contentDescription = stringResource(R.string.radio_add_to_queue)
                                )
                            }
                        }
                    }'''
assert old in s, 'regenerate row'
s = s.replace(old, new, 1)
assert 'IconButton(onClick = onAddToQueue)' in s
open(p, 'w', encoding='utf-8').write(s)
print('2 PlayerCard queue btn ok')

# ==================== 3) 首页：喜欢 tab 卡片回调 + actionEpisode 覆盖 liked + toggle 后内存刷新 ====================
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioHomePage.kt'
s = open(p, encoding='utf-8').read()
orig = s

# 3a) 喜欢 tab 卡片补回调
old = '''                        items(filteredLiked, key = { "liked_${it.id}" }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                assistant = assistantMap[episode.creatorAgentId],
                                onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) }
                            )
                        }'''
new = '''                        items(filteredLiked, key = { "liked_${it.id}" }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                assistant = assistantMap[episode.creatorAgentId],
                                onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episode.id)) },
                                onOpenActions = { actionEpisodeId = episode.id },
                                onPlayNow = { vm.playEpisodeNow(episode) },
                                liked = episode.liked == 1
                            )
                        }'''
assert old in s, 'liked items'
s = s.replace(old, new, 1)

# 3b) actionEpisode 派生扩展到 likedEpisodes
old = '''    val actionEpisode = actionEpisodeId?.let { id -> allEpisodes.find { it.id == id } }'''
new = '''    val actionEpisode = actionEpisodeId?.let { id ->
        allEpisodes.find { it.id == id } ?: likedEpisodes.find { it.id == id }
    }'''
assert old in s, 'actionEpisode derive'
s = s.replace(old, new, 1)

# 3c) 草稿箱卡片保持现状（无按钮）✓

open(p, 'w', encoding='utf-8').write(s)
print('3 home liked ok')

# ==================== 4) RadioHomeVM.toggleEpisodeLiked 同步刷新 _allEpisodes 内存 ====================
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioVM.kt'
s = open(p, encoding='utf-8').read()
orig = s
# HomeVM 版本（156 行附近）：onSuccess 里同步 _allEpisodes 的 liked 字段
old = '''    /** 喜欢 / 取消喜欢节目。 */
    fun toggleEpisodeLiked(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { radioService.setEpisodeLiked(episode.id, episode.liked != 1) }
                .onSuccess {
                    _message.value = RadioMessage(context.getString(if (episode.liked == 1) R.string.radio_toast_unliked else R.string.radio_toast_liked))
                }
                .onFailure { showError(it) }
        }
    }'''
new = '''    /** 喜欢 / 取消喜欢节目（同步刷新全部节目列表的内存状态，抽屉即时更新）。 */
    fun toggleEpisodeLiked(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { radioService.setEpisodeLiked(episode.id, episode.liked != 1) }
                .onSuccess {
                    val newLiked = if (episode.liked == 1) 0 else 1
                    _allEpisodes.value = _allEpisodes.value.map {
                        if (it.id == episode.id) it.copy(liked = newLiked) else it
                    }
                    _message.value = RadioMessage(
                        context.getString(
                            if (newLiked == 1) R.string.radio_toast_liked else R.string.radio_toast_unliked
                        )
                    )
                }
                .onFailure { showError(it) }
        }
    }'''
assert old in s, 'HomeVM toggle'
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print('4 HomeVM toggle ok')
