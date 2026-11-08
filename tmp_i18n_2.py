# -*- coding: utf-8 -*-
# i18n 第二波：PlaylistSheet / RadioShareCard / Discover / 语音配置 / 工具开关
import re

def sub_file(path, pairs, add_r_import=True):
    s = open(path, encoding='utf-8').read()
    orig = s
    for old, new in pairs:
        assert old in s, f'{path}: NOT FOUND: {old[:70]}'
        s = s.replace(old, new, 1)
    if 'import androidx.compose.ui.res.stringResource' not in s:
        s = s.replace('import androidx.compose.ui.unit.dp\n',
                      'import androidx.compose.ui.res.stringResource\nimport androidx.compose.ui.unit.dp\n', 1)
    if add_r_import and 'import me.rerere.rikkahub.R\n' not in s:
        s = s.replace('import me.rerere.rikkahub.Screen\n', 'import me.rerere.rikkahub.R\nimport me.rerere.rikkahub.Screen\n', 1)
    assert s != orig
    open(path, 'w', encoding='utf-8').write(s)
    print('done', path.split('/')[-1])

# ---- PlaylistSheet ----
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/radio/PlaylistSheet.kt', [
 ('''            Text(
                text = "播放列表",''', '''            Text(
                text = stringResource(R.string.radio_playlist_title),'''),
 ('                            contentDescription = "播放模式"', '                            contentDescription = stringResource(R.string.radio_playback_mode)'),
 ('''                                    Text(
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
                                    )'''),
 ('''            Text(
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
                ),'''),
 ('''                EmptyPlaceholder(
                    text = "播放队列为空\\n播放节目后可在这里查看队列",''', '''                EmptyPlaceholder(
                    text = stringResource(R.string.radio_queue_empty),'''),
 ('''                                Icon(
                                    imageVector = Icons.Rounded.DragIndicator,
                                    contentDescription = "拖动排序",''', '''                                Icon(
                                    imageVector = Icons.Rounded.DragIndicator,
                                    contentDescription = stringResource(R.string.radio_drag_sort),'''),
 ('''                                    Icon(
                                        imageVector = if (isCurrent && playerState.isPlaying) {
                                            Icons.Rounded.Pause
                                        } else {
                                            Icons.Rounded.PlayArrow
                                        },
                                        contentDescription = "播放该集"
                                    )''', '''                                    Icon(
                                        imageVector = if (isCurrent && playerState.isPlaying) {
                                            Icons.Rounded.Pause
                                        } else {
                                            Icons.Rounded.PlayArrow
                                        },
                                        contentDescription = stringResource(R.string.radio_play_this)
                                    )'''),
 ('''                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "从队列移除",''', '''                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.radio_remove_from_queue),'''),
])

# ---- RadioShareCard ----
sub_file('app/src/main/java/me/rerere/rikkahub/ui/components/chat/RadioShareCard.kt', [
 ('text = "电台节目 · $displayTitle",', 'text = stringResource(R.string.radio_share_card_title, displayTitle),'),
 ('''                    text = when (playable) {
                        false -> "音频暂不可用，请让 Agent 重新生成"
                        true -> if (isPlayingThis) "正在播放 · 点击查看节目详情" else "点击播放按钮收听 · 点击卡片查看详情"
                        null -> "加载中…"
                    },''', '''                    text = when (playable) {
                        false -> stringResource(R.string.radio_share_unavailable)
                        true -> if (isPlayingThis) stringResource(R.string.radio_share_playing) else stringResource(R.string.radio_share_tap_play)
                        null -> stringResource(R.string.radio_share_loading)
                    },'''),
 ('contentDescription = if (isPlayingThis) "暂停" else "播放",',
  'contentDescription = stringResource(if (isPlayingThis) R.string.radio_pause else R.string.radio_play),'),
])

# ---- DiscoverPage 入口 ----
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/discover/DiscoverPage.kt', [
 ('title = "小机电台",', 'title = stringResource(R.string.radio_discover_title),'),
 ('description = "Agent 为你创作的专属电台节目",', 'description = stringResource(R.string.radio_discover_desc),'),
])

# ---- AssistantDetailPage 入口 ----
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailPage.kt', [
 ('''            NavigationCard(
                icon = Icons.Rounded.RecordVoiceOver,
                title = "语音配置",
                description = "为该智能体设置专属音色、语速与合成模型，不配置时使用全局默认",
                onClick = onNavigateToVoice
            )''', '''            NavigationCard(
                icon = Icons.Rounded.RecordVoiceOver,
                title = stringResource(R.string.assistant_voice_title),
                description = stringResource(R.string.assistant_voice_entry_desc),
                onClick = onNavigateToVoice
            )'''),
])

# ---- AssistantToolsSubPage 电台开关 ----
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantToolsSubPage.kt', [
 ('''            SettingGroupItem(
                title = "小机电台",
                subtitle = "允许 Agent 创建/管理电台栏目与节目、生成音频、发布节目（工具按需加载，不常驻上下文）",''',
  '''            SettingGroupItem(
                title = stringResource(R.string.assistant_tools_radio_title),
                subtitle = stringResource(R.string.assistant_tools_radio_desc),'''),
])

# ---- AssistantVoicePage ----
sub_file('app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantVoicePage.kt', [
 ('''            OneUITopAppBar(
                title = "语音配置",''', '''            OneUITopAppBar(
                title = stringResource(R.string.assistant_voice_title),'''),
 ('''                    Text(
                        text = "API key 请前往 设置 → 语音服务 页设置，此处仅为该智能体设置声音，连接信息会自动使用全局配置。若不配置，将使用设置页选中的音色作为默认音色。",''',
  '''                    Text(
                        text = stringResource(R.string.assistant_voice_hint),'''),
 ('SettingsGroup(title = "音色来源") {', 'SettingsGroup(title = stringResource(R.string.assistant_voice_source)) {'),
 ('''                            Text(
                                text = "跟随全局默认",''', '''                            Text(
                                text = stringResource(R.string.assistant_voice_follow_global),'''),
 ('''                                } ?: "（未配置全局语音服务）",''', '''                                } ?: stringResource(R.string.assistant_voice_no_global),'''),
 ('SettingsGroup(title = "为该智能体单独设置声音") {', 'SettingsGroup(title = stringResource(R.string.assistant_voice_custom_section)) {'),
 ('''                                Text(
                                    text = provider.name.ifBlank { "语音服务" },''', '''                                Text(
                                    text = provider.name.ifBlank { stringResource(R.string.assistant_voice_provider_fallback) },'''),
 ('''                                    text = if (isSelected && customSummary != null) {
                                        "已自定义 · $customSummary"
                                    } else if (isSelected) {
                                        "已选择（使用全局声音参数）"
                                    } else {
                                        "点击设置声音"
                                    },''', '''                                    text = when {
                                        isSelected && customSummary != null -> stringResource(R.string.assistant_voice_customized, customSummary)
                                        isSelected -> stringResource(R.string.assistant_voice_selected_global)
                                        else -> stringResource(R.string.assistant_voice_tap_set)
                                    },'''),
 ('''                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "当前使用",''', '''                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.assistant_voice_current_cd),'''),
 ('text = "设置声音 · ${globalRef.name.ifBlank { "语音服务" }}",',
  'text = stringResource(R.string.assistant_voice_sheet_title, globalRef.name.ifBlank { stringResource(R.string.assistant_voice_provider_fallback) }),'),
 ('Text("试听")', 'Text(stringResource(R.string.assistant_voice_preview))'),
 ('Text("清除配置", color = MaterialTheme.colorScheme.onSurfaceVariant)',
  'Text(stringResource(R.string.assistant_voice_clear), color = MaterialTheme.colorScheme.onSurfaceVariant)'),
 ('''                TextButton(onClick = onDismiss) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }''', '''                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.radio_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }'''),
 ('Text("保存")', 'Text(stringResource(R.string.radio_save))'),
])

# ---- AssistantVoicePage toast 消息（VM 内）----
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantVoicePage.kt'
s = open(p, encoding='utf-8').read()
s = s.replace('toaster.show(message = "已清除该智能体的专属语音，恢复全局默认", type = ToastType.Normal)',
              'toaster.show(message = androidx.compose.ui.res.stringResource(R.string.assistant_voice_cleared_toast), type = ToastType.Normal)')
s = s.replace('toaster.show(message = "已保存该智能体的语音配置", type = ToastType.Success)',
              'toaster.show(message = androidx.compose.ui.res.stringResource(R.string.assistant_voice_saved_toast), type = ToastType.Success)')
open(p, 'w', encoding='utf-8').write(s)
print('AssistantVoicePage toasts ok')

# ---- RadioVM toasts（部分高频，用 context.getString）----
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioVM.kt'
s = open(p, encoding='utf-8').read()
pairs = [
 ('_message.value = RadioMessage("栏目「${it.name}」已创建")',
  '_message.value = RadioMessage(context.getString(R.string.radio_toast_channel_created, it.name))'),
 ('_message.value = RadioMessage("栏目已更新")',
  '_message.value = RadioMessage(context.getString(R.string.radio_toast_channel_updated))'),
 ('_message.value = RadioMessage(\n                    if (deletedEpisodes > 0) "栏目已删除（连同 $deletedEpisodes 期节目）" else "栏目已删除"\n                )',
  '_message.value = RadioMessage(\n                    if (deletedEpisodes > 0) context.getString(R.string.radio_toast_channel_deleted_with, deletedEpisodes)\n                    else context.getString(R.string.radio_toast_channel_deleted)\n                )'),
 ('_message.value = RadioMessage("已加入播放队列（下一集播放）")',
  '_message.value = RadioMessage(context.getString(R.string.radio_toast_queued_next))'),
 ('_message.value = RadioMessage("已加入我的喜欢")',
  '_message.value = RadioMessage(context.getString(R.string.radio_toast_liked))'),
 ('_message.value = RadioMessage("已取消喜欢")',
  '_message.value = RadioMessage(context.getString(R.string.radio_toast_unliked))'),
 ('_message.value = RadioMessage("已恢复备用音频")',
  '_message.value = RadioMessage(context.getString(R.string.radio_toast_backup_restored))'),
 ('_message.value = RadioMessage("标题已更新")',
  '_message.value = RadioMessage(context.getString(R.string.radio_toast_title_updated))'),
 ('_message.value = RadioMessage("节目已发布")',
  '_message.value = RadioMessage(context.getString(R.string.radio_toast_published))'),
]
for old, new in pairs:
    assert old in s, f'VM toast: {old[:50]}'
    s = s.replace(old, new, 1)
if 'import me.rerere.rikkahub.R\n' not in s:
    s = s.replace('import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity\n',
                  'import me.rerere.rikkahub.R\nimport me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity\n', 1)
open(p, 'w', encoding='utf-8').write(s)
print('RadioVM toasts ok')

# ---- RadioVM 用到的 toast 资源补齐 ----
extra = [
 ("radio_toast_channel_created", "Channel \"%1$s\" created", "栏目「%1$s」已创建", "欄目「%1$s」已創建"),
 ("radio_toast_channel_updated", "Channel updated", "栏目已更新", "欄目已更新"),
 ("radio_toast_channel_deleted", "Channel deleted", "栏目已删除", "欄目已刪除"),
 ("radio_toast_channel_deleted_with", "Channel deleted (with %1$d episodes)", "栏目已删除（连同 %1$d 期节目）", "欄目已刪除（連同 %1$d 期節目）"),
 ("radio_toast_queued_next", "Added to queue (play next)", "已加入播放队列（下一集播放）", "已加入播放隊列（下一集播放）"),
 ("radio_toast_liked", "Added to Liked", "已加入我的喜欢", "已加入我的喜歡"),
 ("radio_toast_unliked", "Removed from Liked", "已取消喜欢", "已取消喜歡"),
 ("radio_toast_backup_restored", "Backup audio restored", "已恢复备用音频", "已恢復備用音頻"),
 ("radio_toast_title_updated", "Title updated", "标题已更新", "標題已更新"),
 ("radio_toast_published", "Episode published", "节目已发布", "節目已發佈"),
 ("assistant_voice_cleared_toast", "Voice config cleared, back to global default", "已清除该智能体的专属语音，恢复全局默认", "已清除該智能體的專屬語音，恢復全局默認"),
 ("assistant_voice_saved_toast", "Voice config saved", "已保存该智能体的语音配置", "已保存該智能體的語音配置"),
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
print('extra resources ok')
