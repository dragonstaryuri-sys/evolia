# -*- coding: utf-8 -*-
import re
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantVoicePage.kt'
s = open(p, encoding='utf-8').read()
pattern = re.compile(
    r'text = "API key [^"]*" \+\n[^\n]*",', re.M)
s2 = pattern.sub('text = stringResource(R.string.assistant_voice_hint),', s, count=1)
assert s2 != s, 'hint regex'
s = s2

pairs = [
 ('OneUITopAppBar(\n                title = "语音配置",', 'OneUITopAppBar(\n                title = stringResource(R.string.assistant_voice_title),'),
 ('SettingsGroup(title = "音色来源") {', 'SettingsGroup(title = stringResource(R.string.assistant_voice_source)) {'),
 ('text = "跟随全局默认",', 'text = stringResource(R.string.assistant_voice_follow_global),'),
 ('} ?: "（未配置全局语音服务）",', '} ?: stringResource(R.string.assistant_voice_no_global),'),
 ('SettingsGroup(title = "为该智能体单独设置声音") {', 'SettingsGroup(title = stringResource(R.string.assistant_voice_custom_section)) {'),
 ('text = provider.name.ifBlank { "语音服务" },', 'text = provider.name.ifBlank { stringResource(R.string.assistant_voice_provider_fallback) },'),
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
 ('contentDescription = "当前使用",', 'contentDescription = stringResource(R.string.assistant_voice_current_cd),'),
 ('text = "设置声音 · ${globalRef.name.ifBlank { "语音服务" }}",',
  'text = stringResource(R.string.assistant_voice_sheet_title, globalRef.name.ifBlank { stringResource(R.string.assistant_voice_provider_fallback) }),'),
 ('Text("试听")', 'Text(stringResource(R.string.assistant_voice_preview))'),
 ('Text("清除配置", color = MaterialTheme.colorScheme.onSurfaceVariant)',
  'Text(stringResource(R.string.assistant_voice_clear), color = MaterialTheme.colorScheme.onSurfaceVariant)'),
 ('Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)',
  'Text(stringResource(R.string.radio_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)', 2),
 ('Text("保存")', 'Text(stringResource(R.string.radio_save))'),
 ('toaster.show(message = "已清除该智能体的专属语音，恢复全局默认", type = ToastType.Normal)',
  'toaster.show(message = stringResource(R.string.assistant_voice_cleared_toast), type = ToastType.Normal)'),
 ('toaster.show(message = "已保存该智能体的语音配置", type = ToastType.Success)',
  'toaster.show(message = stringResource(R.string.assistant_voice_saved_toast), type = ToastType.Success)'),
]
for item in pairs:
    old, new = item[0], item[1]
    cnt = item[2] if len(item) > 2 else 1
    assert old in s, f'NOT FOUND: {old[:60]}'
    s = s.replace(old, new, cnt)

if 'import androidx.compose.ui.res.stringResource' not in s:
    s = s.replace('import androidx.compose.ui.text.font.FontWeight\n',
                  'import androidx.compose.ui.res.stringResource\nimport androidx.compose.ui.text.font.FontWeight\n', 1)
if 'import me.rerere.rikkahub.R\n' not in s:
    s = s.replace('import me.rerere.rikkahub.core.data.model.Assistant\n',
                  'import me.rerere.rikkahub.R\nimport me.rerere.rikkahub.core.data.model.Assistant\n', 1)
open(p, 'w', encoding='utf-8').write(s)
print('AssistantVoicePage done')

# ---- RadioVM toasts ----
p = 'app/src/main/java/me/rerere/rikkahub/ui/pages/radio/RadioVM.kt'
s = open(p, encoding='utf-8').read()
for old, new in [
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
]:
    assert old in s, old[:40]
    s = s.replace(old, new, 1)
if 'import me.rerere.rikkahub.R\n' not in s:
    s = s.replace('import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity\n',
                  'import me.rerere.rikkahub.R\nimport me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity\n', 1)
open(p, 'w', encoding='utf-8').write(s)
print('RadioVM toasts ok')

# ---- 资源 ----
extra = [
 ("radio_toast_channel_created", "Channel created: %1$s", "栏目「%1$s」已创建", "欄目「%1$s」已創建"),
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
