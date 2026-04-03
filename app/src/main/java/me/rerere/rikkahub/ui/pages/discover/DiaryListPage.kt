package me.rerere.rikkahub.ui.pages.discover

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
fun DiaryListPage(
    assistantId: String? = null,
    vm: DiaryVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val assistants by vm.assistants.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var diaryToDelete by remember { mutableStateOf<AgentDiaryEntity?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val diaries by remember(assistantId) { vm.getDiaries(assistantId) }.collectAsStateWithLifecycle(emptyList())
    val currentAssistant = remember(assistantId, assistants) {
        assistants.find { it.id.toString() == assistantId }
    }

    val copyAllText = stringResource(R.string.copy)

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = if (assistantId == null) {
                    stringResource(R.string.discover_page_diary_title)
                } else {
                    stringResource(R.string.diary_assistant_title_format, currentAssistant?.name ?: "")
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    if (assistantId != null) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { vm.generateTodayDiary(assistantId, toaster) }) {
                                Icon(Icons.Rounded.Add, null)
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Settings, null)
                        }
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        if (assistantId == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(assistants, key = { it.id }) { assistant ->
                    AgentDiaryCard(
                        name = assistant.name,
                        avatar = assistant.avatar,
                        onClick = {
                            navController.navigate(Screen.DiaryList(assistantId = assistant.id.toString()))
                        }
                    )
                }
            }
        } else {
            if (diaries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.discover_page_diary_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding + PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(diaries, key = { it.id }) { diary ->
                        DiaryItem(
                            diary = diary,
                            onDelete = { diaryToDelete = diary },
                            onCopy = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("diary", diary.content)))
                                    toaster.show(copyAllText, type = ToastType.Success)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (diaryToDelete != null) {
        AlertDialog(
            onDismissRequest = { diaryToDelete = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.discover_page_diary_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteDiary(diaryToDelete!!.id)
                        diaryToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { diaryToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showSettings && assistantId != null && currentAssistant != null) {
        DiarySettingsDialog(
            enableAuto = currentAssistant.enableAutoDiary,
            onDismiss = { showSettings = false },
            onSave = { enable ->
                vm.updateAssistantDiarySettings(assistantId, enable)
                showSettings = false
            }
        )
    }
}

@Composable
private fun AgentDiaryCard(
    name: String,
    avatar: me.rerere.rikkahub.core.data.model.Avatar,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UIAvatar(name = name, value = avatar, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.diary_assistant_title_format, name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DiaryItem(
    diary: AgentDiaryEntity,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = diary.date,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Rounded.Delete,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = diary.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun DiarySettingsDialog(
    enableAuto: Boolean,
    onDismiss: () -> Unit,
    onSave: (Boolean) -> Unit
) {
    var enable by remember { mutableStateOf(enableAuto) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diary_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.diary_auto_generate))
                    Switch(checked = enable, onCheckedChange = { enable = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(enable) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private operator fun PaddingValues.plus(other: PaddingValues): PaddingValues = PaddingValues(
    start = this.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + other.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
    top = this.calculateTopPadding() + other.calculateTopPadding(),
    end = this.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + other.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
    bottom = this.calculateBottomPadding() + other.calculateBottomPadding()
)
