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
                            items(comments) { comment ->
                                CommentItemDetailed(comment, settings)
                            }
                        }
                    }
                }

                // 底部评论输入区
                CommentInputAreaDetailed(
                    onSend = { senderId, text ->
                        vm.addComment(diary, senderId, text, toaster)
                    },
                    settings = settings
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
private fun CommentItemDetailed(comment: DiaryCommentEntity, settings: me.rerere.rikkahub.data.datastore.Settings) {
    val defaultUserStr = stringResource(R.string.diary_filter_user)
    val name = if (comment.senderId == "USER") {
        if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname
    } else settings.assistants.find { it.id.toString() == comment.senderId }?.name ?: "Unknown"

    val avatar = if (comment.senderId == "USER") settings.displaySetting.userAvatar
                 else settings.assistants.find { it.id.toString() == comment.senderId }?.avatar ?: me.rerere.rikkahub.core.data.model.Avatar.Dummy

    Row(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
        UIAvatar(name = name, value = avatar, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CommentInputAreaDetailed(
    onSend: (String, String) -> Unit,
    settings: me.rerere.rikkahub.data.datastore.Settings
) {
    var text by remember { mutableStateOf("") }
    var selectedSenderId by remember { mutableStateOf("USER") }
    var expanded by remember { mutableStateOf(false) }

    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(16.dp).navigationBarsPadding()) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.diary_comment_hint)) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                trailingIcon = {
                    IconButton(onClick = {
                        if (text.isNotBlank()) {
                            onSend(selectedSenderId, text)
                            text = ""
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.Send, null)
                    }
                }
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.diary_comment_send_as), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Box {
                    val currentName = if (selectedSenderId == "USER") stringResource(R.string.diary_personnel_user)
                                    else settings.assistants.find { it.id.toString() == selectedSenderId }?.name ?: ""
                    AssistChip(
                        onClick = { expanded = true },
                        label = { Text(currentName, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.diary_personnel_user)) },
                            onClick = { selectedSenderId = "USER"; expanded = false }
                        )
                        settings.assistants.forEach { assistant ->
                            DropdownMenuItem(
                                text = { Text(assistant.name) },
                                onClick = { selectedSenderId = assistant.id.toString(); expanded = false }
                            )
                        }
                    }
                }
            }
        }
    }
}
