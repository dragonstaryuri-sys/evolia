package me.rerere.rikkahub.ui.pages.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.ui.components.chat.ChatMessageTurn
import me.rerere.rikkahub.ui.components.chat.groupIntoTurns
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.HapticPattern
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlinx.serialization.decodeFromString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteDetailPage(id: Long) {
    val vm: FavoriteDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val favoritesVM: FavoritesVM = koinViewModel() // 复用删除逻辑
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_detail_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Rounded.Delete, null)
                    }
                }
            )
        }
    ) { padding ->
        favorite?.let { fav ->
            val messages = remember(fav.content) {
                try {
                    JsonInstant.decodeFromString<List<UIMessage>>(fav.content)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val groups = remember(messages) {
                messages.map { MessageNode.of(it, kotlin.uuid.Uuid.NIL) }.groupIntoTurns()
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(groups) { group ->
                    ChatMessageTurn(
                        group = group,
                        isLastTurn = false, // 详情页无需动态效果
                        onCitationClick = { /* 详情暂不支持引用 */ },
                        assistant = settings.assistants.find { it.name == fav.agentName },
                        showRegenerate = false // 详情页不可重生成
                    )
                }

                // 底部留白，防止内容被遮挡
                item { Spacer(Modifier.height(32.dp)) }
            }
        } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.chat_page_delete)) }, // 借用现有字符串
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        favoritesVM.deleteFavorite(id)
                        navController.navigateUp()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
