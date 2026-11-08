# -*- coding: utf-8 -*-
# 替换 UI 代码中的硬编码文案为 stringResource 引用
import re

R = 'me.rerere.rikkahub.R'

def sub_file(path, pairs, need_sr_import=True):
    s = open(path, encoding='utf-8').read()
    orig = s
    for old, new in pairs:
        assert old in s, f'{path}: NOT FOUND: {old[:60]}'
        s = s.replace(old, new, 1)
    if need_sr_import and 'import androidx.compose.ui.res.stringResource' not in s:
        s = s.replace('import androidx.compose.ui.unit.dp\n',
                      'import androidx.compose.ui.res.stringResource\nimport androidx.compose.ui.unit.dp\n', 1)
    if f'import {R}\n' not in s:
        s = s.replace('import me.rerere.rikkahub.Screen\n', f'import me.rerere.rikkahub.R\nimport me.rerere.rikkahub.Screen\n', 1)
    assert s != orig
    open(path, 'w', encoding='utf-8').write(s)
    print('done', path.split('/')[-1])

# ==================== RadioHomePage ====================
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioHomePage.kt', [
 ('title = "小机电台",', 'title = stringResource(R.string.radio_page_title),'),
 ('placeholder = {\n                                Text(\n                                    when (tab) {\n                                        RadioHomeVM.RadioHomeTab.CHANNELS -> "搜索全部节目"\n                                        RadioHomeVM.RadioHomeTab.LIKED -> "搜索喜欢的节目"\n                                        RadioHomeVM.RadioHomeTab.DRAFTS -> "搜索草稿"\n                                    }\n                                )\n                            },',
  'placeholder = {\n                                Text(\n                                    when (tab) {\n                                        RadioHomeVM.RadioHomeTab.CHANNELS -> stringResource(R.string.radio_search_all)\n                                        RadioHomeVM.RadioHomeTab.LIKED -> stringResource(R.string.radio_search_liked)\n                                        RadioHomeVM.RadioHomeTab.DRAFTS -> stringResource(R.string.radio_search_drafts)\n                                    }\n                                )\n                            },'),
 ('Icon(Icons.Rounded.Close, contentDescription = "退出搜索")', 'Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.radio_exit_search))'),
 ('Icon(Icons.Rounded.Search, contentDescription = "搜索节目")', 'Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.radio_search_episodes))'),
 ('Icon(Icons.Rounded.QueueMusic, contentDescription = "播放队列")', 'Icon(Icons.Rounded.QueueMusic, contentDescription = stringResource(R.string.radio_playback_queue))'),
 ('Icon(Icons.Rounded.Add, contentDescription = "创建栏目")', 'Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.radio_create_channel))'),
 ('text = "还没有栏目\\n点击右下角创建，或让 Agent 通过工具创建",', 'text = stringResource(R.string.radio_channels_empty),'),
 ('text = "全部节目",', 'text = stringResource(R.string.radio_all_episodes),'),
 ('text = "暂无节目",', 'text = stringResource(R.string.radio_no_episodes),'),
 ('text = if (likedAscending) "发布时间 旧→新" else "发布时间 新→旧",',
  'text = stringResource(if (likedAscending) R.string.radio_sort_oldest else R.string.radio_sort_newest),'),
 ('contentDescription = "切换排序"', 'contentDescription = stringResource(R.string.radio_toggle_sort)'),
 ('text = "还没有点赞的节目\\n在节目详情页点击点赞图标即可收藏",', 'text = stringResource(R.string.radio_liked_empty),'),
 ('text = "草稿箱是空的\\nAgent 保存的未发布节目会出现在这里",', 'text = stringResource(R.string.radio_drafts_empty),'),
 ('icon = { Icon(Icons.Rounded.Radio, contentDescription = "栏目") },', 'icon = { Icon(Icons.Rounded.Radio, contentDescription = stringResource(R.string.radio_tab_channels)) },'),
 ('contentDescription = "我的喜欢",', 'contentDescription = stringResource(R.string.radio_tab_liked),'),
 ('icon = { Icon(Icons.Rounded.Favorite, contentDescription = "我的喜欢") },', 'icon = { Icon(Icons.Rounded.Favorite, contentDescription = stringResource(R.string.radio_tab_liked)) },'),
 ('contentDescription = "草稿箱",', 'contentDescription = stringResource(R.string.radio_tab_drafts),'),
 ('icon = { Icon(Icons.Rounded.Description, contentDescription = "草稿箱") },', 'icon = { Icon(Icons.Rounded.Description, contentDescription = stringResource(R.string.radio_tab_drafts)) },'),
 ('Icon(Icons.Rounded.MoreVert, contentDescription = "栏目操作")', 'Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.radio_channel_actions))'),
 ('text = { Text("编辑栏目") },', 'text = { Text(stringResource(R.string.radio_edit_channel)) },'),
 ('text = { Text("删除栏目") },', 'text = { Text(stringResource(R.string.radio_delete_channel)) },'),
 ('title = "删除栏目",', 'title = stringResource(R.string.radio_delete_channel_title),'),
 ('text = "确定要删除栏目「${channel.name}」吗？栏目下的节目及其音频会一并删除，删除事件会记录审计。",',
  'text = stringResource(R.string.radio_delete_channel_confirm, channel.name),'),
 ('confirmText = "删除",', 'confirmText = stringResource(R.string.radio_delete),'),
 ('dismissText = "取消"', 'dismissText = stringResource(R.string.radio_cancel)'),
 ('text = if (channel == null) "创建栏目" else "编辑栏目",',
  'text = stringResource(if (channel == null) R.string.radio_create_channel_title else R.string.radio_edit_channel_title),'),
 ('label = { Text("栏目名称") },', 'label = { Text(stringResource(R.string.radio_channel_name)) },'),
 ('label = { Text("栏目介绍") },', 'label = { Text(stringResource(R.string.radio_channel_desc)) },'),
 ('label = { Text("创作提示词（指导 Agent 在该栏目下的创作风格）") },', 'label = { Text(stringResource(R.string.radio_channel_prompt)) },'),
 ('Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)', 'Text(stringResource(R.string.radio_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)'),
 ('Text("保存")', 'Text(stringResource(R.string.radio_save))'),
])

# ==================== RadioChannelPage ====================
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioChannelPage.kt', [
 ('title = channel?.name ?: "栏目",', 'title = channel?.name ?: stringResource(R.string.radio_channel_title_fallback),'),
 ('placeholder = { Text("搜索本栏目节目") },', 'placeholder = { Text(stringResource(R.string.radio_search_channel)) },'),
 ('Icon(Icons.Rounded.Close, contentDescription = "退出搜索")', 'Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.radio_exit_search))'),
 ('Icon(Icons.Rounded.Search, contentDescription = "搜索节目")', 'Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.radio_search_episodes))'),
 ('Icon(Icons.Rounded.QueueMusic, contentDescription = "播放队列")', 'Icon(Icons.Rounded.QueueMusic, contentDescription = stringResource(R.string.radio_playback_queue))'),
 ('Icon(Icons.Rounded.PlayCircle, contentDescription = "播放全部")', 'Icon(Icons.Rounded.PlayCircle, contentDescription = stringResource(R.string.radio_play_all))'),
 ('Icon(Icons.Rounded.MoreVert, contentDescription = "节目操作")', 'Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.radio_episode_actions))'),
])

# ==================== EpisodeCard（含 ActionsSheet）====================
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/radio/EpisodeCard.kt', [
 ('Icon(Icons.Rounded.MoreVert, contentDescription = "节目操作")', 'Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.radio_episode_actions))'),
 ('Icon(Icons.Rounded.PlayArrow, contentDescription = "立即播放")', 'Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.radio_play_now))'),
 ('text = if (episode.status == RadioEpisodeStatus.PUBLISHED) "已发布" else "草稿",', 'text = stringResource(R.string.radio_status_draft),'),
 ('text = if (assistantName != null) "主播：$assistantName" else "主播：未知",',
  'text = if (assistantName != null) stringResource(R.string.radio_host, assistantName) else stringResource(R.string.radio_host_unknown),'),
 ('title = "下一集播放",', 'title = stringResource(R.string.radio_next_episode),'),
 ('subtitle = "插入当前播放队列，本集播完后自动播放",', 'subtitle = stringResource(R.string.radio_next_episode_desc),'),
 ('title = if (episode.liked == 1) "取消喜欢" else "喜欢",',
  'title = stringResource(if (episode.liked == 1) R.string.radio_unlike else R.string.radio_like),'),
 ('subtitle = "加入「我的喜欢」列表",', 'subtitle = stringResource(R.string.radio_like_desc),'),
 ('title = "导出到本地",', 'title = stringResource(R.string.radio_export),'),
 ('subtitle = "复制音频副本到 Download/Evolia/radio/",', 'subtitle = stringResource(R.string.radio_export_desc),'),
])

# ==================== RadioEpisodePage ====================
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioEpisodePage.kt', [
 ('title = episode?.title ?: "节目详情",', 'title = episode?.title ?: stringResource(R.string.radio_episode_detail),'),
 ('Icon(Icons.Rounded.QueueMusic, contentDescription = "播放列表")', 'Icon(Icons.Rounded.QueueMusic, contentDescription = stringResource(R.string.radio_playlist_title))'),
 ('Icon(Icons.Rounded.Edit, contentDescription = "修改标题")', 'Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.radio_rename))'),
 ('title = "修改标题",', 'title = stringResource(R.string.radio_rename_title),'),
 ('title = "导出音频",', 'title = stringResource(R.string.radio_export_audio),'),
 ('text = "将节目音频复制导出到手机 Download/Evolia/radio/ 目录。导出的是独立副本，之后在应用内删除音频不会影响导出文件。",',
  'text = stringResource(R.string.radio_export_audio_confirm),'),
 ('confirmText = "导出",', 'confirmText = stringResource(R.string.radio_export),'),
 ('dismissText = "取消"', 'dismissText = stringResource(R.string.radio_cancel)'),
 ('text = if (episode?.status == RadioEpisodeStatus.PUBLISHED) "已发布" else "草稿",',
  'text = stringResource(if (episode?.status == RadioEpisodeStatus.PUBLISHED) R.string.radio_status_published else R.string.radio_status_draft),'),
 ('text = "正在生成音频…",', 'text = stringResource(R.string.radio_generating),', 2),
 ('Text("重新生成")', 'Text(stringResource(R.string.radio_regenerate))'),
 ('contentDescription = "后退15秒"', 'contentDescription = stringResource(R.string.radio_seek_back)'),
 ('contentDescription = "前进30秒"', 'contentDescription = stringResource(R.string.radio_seek_forward)'),
 ('contentDescription = "播放设置"', 'contentDescription = stringResource(R.string.radio_playback_settings)'),
 ('contentDescription = if (liked) "取消点赞" else "点赞节目",',
  'contentDescription = stringResource(if (liked) R.string.radio_unlike_episode else R.string.radio_like_episode),'),
 ('contentDescription = if (isPlaying) "暂停" else "播放",', 'contentDescription = stringResource(if (isPlaying) R.string.radio_pause else R.string.radio_play),'),
 ('text = "备用音频" + (backupExpiresAt?.let {\n                                    "（${formatDateTime(it)} 过期）"\n                                } ?: ""),',
  'text = backupExpiresAt?.let { stringResource(R.string.radio_backup_audio) + stringResource(R.string.radio_backup_expires, formatDateTime(it)) }\n                                    ?: stringResource(R.string.radio_backup_audio),'),
 ('Text("恢复备用")', 'Text(stringResource(R.string.radio_restore_backup))'),
 ('text = "暂无音频",', 'text = stringResource(R.string.radio_no_audio),'),
 ('Text("生成音频")', 'Text(stringResource(R.string.radio_generate_audio))'),
 ('Text("发布节目")', 'Text(stringResource(R.string.radio_publish_episode))'),
 ('text = "倍速播放",', 'text = stringResource(R.string.radio_speed_title),'),
 ('text = "只应用到当前节目",', 'text = stringResource(R.string.radio_speed_only_current),'),
 ('text = "睡眠定时",', 'text = stringResource(R.string.radio_sleep_timer),'),
 ('sleepTimer.endOfEpisode -> "本集结束"', 'sleepTimer.endOfEpisode -> stringResource(R.string.radio_sleep_end)'),
 ('sleepTimer.minutes > 0 -> "${sleepTimer.minutes} 分钟"', 'sleepTimer.minutes > 0 -> stringResource(R.string.radio_sleep_minutes, sleepTimer.minutes)'),
 ('else -> "OFF"', 'else -> stringResource(R.string.radio_sleep_off)'),
 ('val label = if (minutes == 0) "关" else "$minutes"',
  'val label = if (minutes == 0) stringResource(R.string.radio_sleep_off_chip) else stringResource(R.string.radio_sleep_minutes, minutes)'),
 ('text = "定时到本集结束",', 'text = stringResource(R.string.radio_sleep_end_of_episode),'),
 ('text = "剩余 %02d:%02d".format(', 'text = stringResource(R.string.radio_sleep_remaining,'),
 ('text = "文字稿",', 'text = stringResource(R.string.radio_script),'),
 ('text = "（文字稿为空）",', 'text = stringResource(R.string.radio_script_empty),'),
 ('text = "草稿详情仅展示文字稿；音频生成与发布由 Agent 通过工具操作，发布后即可在这里收听。",',
  'text = stringResource(R.string.radio_draft_notice),'),
 ('contentDescription = "拖动排序"', 'contentDescription = stringResource(R.string.radio_drag_sort)'),
 ('contentDescription = "播放该集"', 'contentDescription = stringResource(R.string.radio_play_this)'),
 ('contentDescription = "从队列移除"', 'contentDescription = stringResource(R.string.radio_remove_from_queue)'),
])

# ---- RadioEpisodePage 内嵌 PlaylistSheet 文案 + 内嵌 sleep 剩余格式化修正 ----
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioEpisodePage.kt'
s = open(p, encoding='utf-8').read()
s = s.replace('''                            stringResource(R.string.radio_sleep_remaining, sleepTimer.remainingSeconds / 60,
                                sleepTimer.remainingSeconds % 60
                            ),''', '''                            stringResource(
                                R.string.radio_sleep_remaining,
                                sleepTimer.remainingSeconds / 60,
                                sleepTimer.remainingSeconds % 60
                            ),''')
s = s.replace('''            Text(
                text = "播放列表",''', '''            Text(
                text = stringResource(R.string.radio_playlist_title),''')
s = s.replace('''                            imageVector = when (playerState.playMode) {
                                PlaybackMode.LIST_LOOP -> Icons.Rounded.Repeat
                                PlaybackMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
                                PlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
                                PlaybackMode.SEQUENTIAL -> Icons.Rounded.SwipeVertical
                            },
                            contentDescription = "播放模式"''', '''                            imageVector = when (playerState.playMode) {
                                PlaybackMode.LIST_LOOP -> Icons.Rounded.Repeat
                                PlaybackMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
                                PlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
                                PlaybackMode.SEQUENTIAL -> Icons.Rounded.SwipeVertical
                            },
                            contentDescription = stringResource(R.string.radio_playback_mode)''')
s = s.replace('''                                    Text(
                                        text = when (mode) {
                                            PlaybackMode.LIST_LOOP -> "列表循环"
                                            PlaybackMode.REPEAT_ONE -> "单集循环"
                                            PlaybackMode.SHUFFLE -> "随机播放"
                                            PlaybackMode.SEQUENTIAL -> "顺序停止"
                                        } + if (playerState.playMode == mode) " ✓" else ""
                                    )''', '''                                    Text(
                                        text = when (mode) {
                                            PlaybackMode.LIST_LOOP -> stringResource(R.string.radio_mode_list_loop)
                                            PlaybackMode.REPEAT_ONE -> stringResource(R.string.radio_mode_repeat_one)
                                            PlaybackMode.SHUFFLE -> stringResource(R.string.radio_mode_shuffle)
                                            PlaybackMode.SEQUENTIAL -> stringResource(R.string.radio_mode_sequential)
                                        }
                                    )''')
s = s.replace('''            Text(
                text = "当前模式：" + when (playerState.playMode) {
                    PlaybackMode.LIST_LOOP -> "列表循环（播完最后一集回到第一集）"
                    PlaybackMode.REPEAT_ONE -> "单集循环"
                    PlaybackMode.SHUFFLE -> "随机播放"
                    PlaybackMode.SEQUENTIAL -> "顺序播放（播完停止）"
                },''', '''            Text(
                text = stringResource(
                    R.string.radio_mode_current,
                    when (playerState.playMode) {
                        PlaybackMode.LIST_LOOP -> stringResource(R.string.radio_mode_list_loop)
                        PlaybackMode.REPEAT_ONE -> stringResource(R.string.radio_mode_repeat_one)
                        PlaybackMode.SHUFFLE -> stringResource(R.string.radio_mode_shuffle)
                        PlaybackMode.SEQUENTIAL -> stringResource(R.string.radio_mode_sequential)
                    }
                ),''')
s = s.replace('''                EmptyPlaceholder(
                    text = "播放队列为空\\n播放节目后可在这里查看队列",''', '''                EmptyPlaceholder(
                    text = stringResource(R.string.radio_queue_empty),''')
open(p, 'w', encoding='utf-8').write(s)
print('EpisodePage embedded done')
