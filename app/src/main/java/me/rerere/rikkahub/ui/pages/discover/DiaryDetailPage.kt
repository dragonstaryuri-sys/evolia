package me.rerere.rikkahub.ui.pages.discover

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryDetailPage(
    diaryId: String,
    vm: DiaryVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
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
                        navController.navigate(Screen.DiaryEditor(diaryId))
                    }) {
                        Icon(Icons.Rounded.Edit, null)
                    }
                }
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding()
    ) { padding ->
        if (diaryState == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val diary = diaryState!!
            var replyingTo by remember { mutableStateOf<DiaryCommentEntity?>(null) }
            val listState = rememberLazyListState()

            // 回复模式或评论列表变化时，自动滚到底部，确保输入区域与目标评论可见
            LaunchedEffect(replyingTo, comments.size) {
                if (replyingTo != null || comments.isNotEmpty()) {
                    // LazyColumn 目前只有 2 个 item（日记正文 + 评论区），0=正文 1=评论
                    // 滚动到评论区 item，然后 animate 到末尾
                    runCatching { listState.animateScrollToItem(1) }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
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
                                Column {
                                    comments.forEach { comment ->
                                        CommentItemDetailed(
                                            comment = comment,
                                            allComments = comments,
                                            settings = settings,
                                            diaryOwnerId = diary.assistantId,
                                            onDelete = { vm.deleteComment(comment.id) },
                                            onReply = { replyingTo = comment }
                                        )
                                    }
                                }
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
    diaryOwnerId: String,
    onDelete: () -> Unit,
    onReply: () -> Unit
) {
    val defaultUserStr = stringResource(R.string.diary_filter_user)
    val copiedStr = stringResource(R.string.copied)
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val name = if (comment.senderId == "USER") {
        if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname
    } else settings.assistants.find { it.id.toString() == comment.senderId }?.name ?: "Unknown"

    val avatar = if (comment.senderId == "USER") settings.displaySetting.userAvatar
                 else settings.assistants.find { it.id.toString() == comment.senderId }?.avatar ?: me.rerere.rikkahub.core.data.model.Avatar.Dummy

    // 解析回复目标
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

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            showMenu = true
                        },
                        onTap = {
                            onReply()
                        }
                    )
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(modifier = Modifier.padding(12.dp)) {
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
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "回复 @$replyToName",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = comment.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }

        // 长按弹出菜单
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy)) },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(comment.content))
                    showMenu = false
                    toaster.show(copiedStr)
                }
            )
            val canDelete = true // user 可以删除任何人的评论
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showMenu = false
                        showDeleteConfirm = true
                    }
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
            }
        }
    }
}
