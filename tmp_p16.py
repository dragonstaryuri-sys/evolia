# -*- coding: utf-8 -*-
import re

p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioEpisodePage.kt'
s = open(p, encoding='utf-8').read()
orig = s

# ===== 1) 重新生成行：加 PlaylistAdd 按钮（右上角）=====
old = '''                        } else {
                            TextButton(onClick = onGenerate, enabled = !generating) {
                                Text("重新生成")
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
assert old in s, 'regen row'
s = s.replace(old, new, 1)

# ===== 2) PlayerCard 全部硬编码中文 → stringResource =====
pairs = [
 ('text = "正在生成音频…",\n                                style = MaterialTheme.typography.labelMedium,',
  'text = stringResource(R.string.radio_generating),\n                                style = MaterialTheme.typography.labelMedium,'),
 ('Text("终止")', 'Text(stringResource(R.string.radio_stop))'),
 ('contentDescription = "播放设置"', 'contentDescription = stringResource(R.string.radio_playback_settings)'),
 ('contentDescription = "后退15秒"', 'contentDescription = stringResource(R.string.radio_seek_back)'),
 ('contentDescription = if (isPlaying) "暂停" else "播放",',
  'contentDescription = stringResource(if (isPlaying) R.string.radio_pause else R.string.radio_play),'),
 ('contentDescription = "前进30秒"', 'contentDescription = stringResource(R.string.radio_seek_forward)'),
 ('contentDescription = if (liked) "取消点赞" else "点赞节目",',
  'contentDescription = stringResource(if (liked) R.string.radio_unlike_episode else R.string.radio_like_episode),'),
 ('text = "备用音频" + (backupExpiresAt?.let {\n                                    "（${formatDateTime(it)} 过期）"\n                                } ?: ""),',
  'text = backupExpiresAt?.let {\n                                    stringResource(R.string.radio_backup_audio) + stringResource(R.string.radio_backup_expires, formatDateTime(it))\n                                } ?: stringResource(R.string.radio_backup_audio),'),
 ('Text("恢复备用")', 'Text(stringResource(R.string.radio_restore_backup))'),
 ('text = "正在生成音频…",\n                            style = MaterialTheme.typography.bodyMedium,', 'text = stringResource(R.string.radio_generating),\n                            style = MaterialTheme.typography.bodyMedium,'),
 ('text = "暂无音频",', 'text = stringResource(R.string.radio_no_audio),'),
 ('Text("生成音频")', 'Text(stringResource(R.string.radio_generate_audio))'),
 ('Text("发布节目")', 'Text(stringResource(R.string.radio_publish_episode))'),
]
for old, new in pairs:
    assert old in s, f'NOT FOUND: {old[:60]}'
    s = s.replace(old, new, 1)

# ===== 3) 其余页面级文案（StatusCard/重命名/导出确认/ScriptCard/草稿卡/标题）=====
pairs2 = [
 ('text = if (episode?.status == RadioEpisodeStatus.PUBLISHED) "已发布" else "草稿",',
  'text = stringResource(if (episode?.status == RadioEpisodeStatus.PUBLISHED) R.string.radio_status_published else R.string.radio_status_draft),'),
 ('title = episode?.title ?: "节目详情",', 'title = episode?.title ?: stringResource(R.string.radio_episode_detail),'),
 ('Icon(Icons.Rounded.QueueMusic, contentDescription = "播放列表")',
  'Icon(Icons.Rounded.QueueMusic, contentDescription = stringResource(R.string.radio_playlist_title))'),
 ('Icon(Icons.Rounded.Edit, contentDescription = "修改标题")',
  'Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.radio_rename))'),
 ('title = "修改标题",', 'title = stringResource(R.string.radio_rename_title),'),
 ('title = "导出音频",', 'title = stringResource(R.string.radio_export_audio),'),
 ('text = "将节目音频复制导出到手机 Download/Evolia/radio/ 目录。导出的是独立副本，之后在应用内删除音频不会影响导出文件。",',
  'text = stringResource(R.string.radio_export_audio_confirm),'),
 ('confirmText = "导出",', 'confirmText = stringResource(R.string.radio_export),'),
 ('dismissText = "取消"', 'dismissText = stringResource(R.string.radio_cancel)'),
 ('text = "文字稿",', 'text = stringResource(R.string.radio_script),'),
 ('text = "（文字稿为空）",', 'text = stringResource(R.string.radio_script_empty),'),
 ('text = "草稿详情仅展示文字稿；音频生成与发布由 Agent 通过工具操作，发布后即可在这里收听。",',
  'text = stringResource(R.string.radio_draft_notice),'),
 ('contentDescription = "拖动排序"', 'contentDescription = stringResource(R.string.radio_drag_sort)'),
 ('contentDescription = "播放该集"', 'contentDescription = stringResource(R.string.radio_play_this)'),
 ('contentDescription = "从队列移除"', 'contentDescription = stringResource(R.string.radio_remove_from_queue)'),
]
for old, new in pairs2:
    assert old in s, f'NOT FOUND2: {old[:60]}'
    s = s.replace(old, new, 1)

# i18n imports
if 'import androidx.compose.ui.res.stringResource' not in s:
    s = s.replace('import androidx.compose.ui.unit.dp\n', 'import androidx.compose.ui.res.stringResource\nimport androidx.compose.ui.unit.dp\n', 1)
if 'import me.rerere.rikkahub.R\n' not in s:
    s = s.replace('import me.rerere.rikkahub.Screen\n', 'import me.rerere.rikkahub.R\nimport me.rerere.rikkahub.Screen\n', 1)

assert s != orig
open(p, 'w', encoding='utf-8').write(s)
print('EpisodePage rebuilt ok, radio_regenerate:', s.count('R.string.radio_regenerate'), ', PlaylistAdd:', s.count('PlaylistAdd'))
