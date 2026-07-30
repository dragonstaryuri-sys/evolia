package me.rerere.rikkahub.ui.pages.discover

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryCommentEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryDetailPage(
    diaryId: String,
    vm: DiaryVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val diaryState by remember(diaryId) { vm.getDiaryById(diaryId) }.collectAsStateWithLifecycle(null)
    val comments by vm.getComments(diaryId).collectAsStateWithLifecycle(emptyList())
    val settings by vm.settings.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.diary_detail_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = {
                        haptics.perform(HapticPattern.Pop)
                        navController.navigate(Screen.DiaryEditor(diaryId))
                    }) {
                        Icon(Icons.Rounded.Edit, null)
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        if (diaryState == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val diary = diaryState!!
            var replyingTo by remember { mutableStateOf<DiaryCommentEntity?>(null) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        item {
                            Column {
                                DetailAuthorHeader(diary, settings)
                                Spacer(Modifier.height(20.dp))
                                MarkdownBlock(content = diary.content, style = MaterialTheme.typography.bodyLarge)
                            }
                        }

                        if (comments.isNotEmpty()) {
                            item {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Text(
                                    text = "评论 (${comments.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(top = 16.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(comments, key = { it.id }) { comment ->
                                CommentItemDetailed(
                                    comment = comment,
                                    allComments = comments,
                                    settings = settings,
                                    onDelete = { vm.deleteComment(comment.id) },
                                    onReply = { replyingTo = comment }
                                )
                            }
                        }
                    }
                }

                // 底部评论输入区
                CommentInputAreaDetailed(
                    diary = diary,
                    replyingTo = replyingTo,
                    settings = settings,
                    onCancelReply = { replyingTo = null },
                    onSend = { senderId, text ->
                        val capturedReplyingTo = replyingTo
                        if (capturedReplyingTo != null) {
                            vm.replyToComment(diary, capturedReplyingTo, text, toaster)
                            replyingTo = null
                        } else {
                            vm.addComment(diary, senderId, text, toaster)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DetailAuthorHeader(diary: AgentDiaryEntity, settings: me.rerere.rikkahub.data.datastore.Settings) {
    val defaultUserStr = stringResource(R.string.diary_filter_user)
    val authorName = if (diary.assistantId == "USER") {
        if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname
    } else settings.assistants.find { it.id.toString() == diary.assistantId }?.name ?: "Unknown"

    Row(verticalAlignment = Alignment.CenterVertically) {
        UIAvatar(
            name = authorName,
            value = if (diary.assistantId == "USER") settings.displaySetting.userAvatar
                    else settings.assistants.find { it.id.toString() == diary.assistantId }?.avatar ?: me.rerere.rikkahub.core.data.model.Avatar.Dummy,
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(authorName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(diary.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CommentItemDetailed(
    comment: DiaryCommentEntity,
    allComments: List<DiaryCommentEntity>,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onDelete: () -> Unit,
    onReply: () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    val defaultUserStr = stringResource(R.string.diary_filter_user)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val name = if (comment.senderId == "USER") {
        if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname
    } else settings.assistants.find { it.id.toString() == comment.senderId }?.name ?: "Unknown"

    val avatar = if (comment.senderId == "USER") settings.displaySetting.userAvatar
                 else settings.assistants.find { it.id.toString() == comment.senderId }?.avatar ?: me.rerere.rikkahub.core.data.model.Avatar.Dummy

    // 解析回复目标：通过 replyToId 找到被回复人的 senderId，再解析成显示名
    val replyToName = comment.replyToId?.let { targetId ->
        val targetComment = allComments.firstOrNull { it.id == targetId }
        val targetSenderId = targetComment?.senderId ?: return@let null
        if (targetSenderId == "USER") {
            if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname
        } else {
            settings.assistants.find { it.id.toString() == targetSenderId }?.name ?: "Unknown"
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.diary_comment_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Row(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
        UIAvatar(name = name, value = avatar, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (replyToName != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diary_comment_reply_prefix),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "@$replyToName",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text(comment.content, style = MaterialTheme.typography.bodyMedium)
            // 回复按钮
            TextButton(
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onReply()
                },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.offset(y = 2.dp)
            ) {
                Text(
                    stringResource(R.string.diary_comment_reply),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 仅允许用户删除自己的评论
        if (comment.senderId == "USER") {
            IconButton(
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    showDeleteConfirm = true
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun CommentInputAreaDetailed(
    diary: AgentDiaryEntity,
    replyingTo: DiaryCommentEntity?,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onCancelReply: () -> Unit,
    onSend: (String, String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var selectedSenderId by remember { mutableStateOf("USER") }
    var expanded by remember { mutableStateOf(false) }

    val isUserDiary = diary.assistantId == "USER"
    val defaultUserStr = stringResource(R.string.diary_filter_user)

    // 解析回复目标的显示名
    val replyToName = replyingTo?.let { target ->
        if (target.senderId == "USER") {
            if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname
        } else {
            settings.assistants.find { it.id.toString() == target.senderId }?.name ?: "Unknown"
        }
    }

    // 当日记主人是 USER 时，默认选中第一个智能体
    LaunchedEffect(isUserDiary) {
        if (isUserDiary && selectedSenderId == "USER") {
            selectedSenderId = settings.assistants.firstOrNull()?.id?.toString() ?: "USER"
        }
    }

    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(16.dp).navigationBarsPadding()) {
            // 回复模式提示条
            if (replyingTo != null && replyToName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.diary_comment_reply_prefix) + " @$replyToName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onCancelReply,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 日记主人是 USER 时：显示智能体选择 + 可选的附加说明
            if (isUserDiary && replyingTo == null) {
                if (settings.assistants.isEmpty()) {
                    Text(
                        text = stringResource(R.string.diary_no_assistants),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.diary_select_agent_to_comment),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    AssistChip(
                        onClick = { expanded = true },
                        label = {
                            Text(
                                settings.assistants.find { it.id.toString() == selectedSenderId }?.name
                                    ?: stringResource(R.string.diary_select_agent_to_comment),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        settings.assistants.forEach { assistant ->
                            DropdownMenuItem(
                                text = { Text(assistant.name) },
                                onClick = { selectedSenderId = assistant.id.toString(); expanded = false }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(R.string.diary_comment_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        trailingIcon = {
                            IconButton(onClick = {
                                if (selectedSenderId != "USER") {
                                    onSend(selectedSenderId, text)
                                    text = ""
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Rounded.Send, null)
                            }
                        }
                    )

                    // 提示文字
                    Text(
                        text = stringResource(R.string.diary_comment_send_as),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                // 日记主人不是 USER 或回复模式：显示普通评论输入
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.diary_comment_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    trailingIcon = {
                        IconButton(onClick = {
                            if (text.isNotBlank()) {
                                onSend("USER", text)
                                text = ""
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.Send, null)
                        }
                    }
                )

                // 回复模式下隐藏发送身份选择（固定为 USER）
                if (replyingTo == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.diary_comment_send_as), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.diary_personnel_user), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    }
}
