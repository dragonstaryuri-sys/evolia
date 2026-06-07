package me.rerere.rikkahub.ui.pages.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.toLocalString
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.HapticPattern

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesPage() {
    val vm: FavoritesVM = koinViewModel()
    val favorites = vm.favorites.collectAsLazyPagingItems()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_page_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        if (favorites.itemCount == 0) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.favorites_empty), color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    count = favorites.itemCount,
                    key = favorites.itemKey { it.id }
                ) { index ->
                    val favorite = favorites[index]
                    if (favorite != null) {
                        FavoriteCard(
                            favorite = favorite,
                            onClick = {
                                navController.navigate(Screen.FavoriteDetail(id = favorite.id))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(favorite: FavoriteEntity, onClick: () -> Unit) {
    val haptics = rememberPremiumHaptics()
    val messages = remember(favorite.content) {
        try {
            JsonInstant.decodeFromString<List<UIMessage>>(favorite.content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    Surface(
        onClick = {
            haptics.perform(HapticPattern.Pop)
            onClick()
        },
        shape = AppShapes.CardLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (favorite.type == 0) {
                // 单条消息
                val rawText = messages.firstOrNull()?.toText() ?: ""
                val displayText = if (rawText.length > 50) {
                    rawText.take(50) + "..."
                } else {
                    rawText
                }

                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = favorite.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formatFavoriteDate(favorite.messageTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                // 多条消息 (合并)
                Text(
                    text = stringResource(R.string.favorites_chat_history_title, favorite.userNickname, favorite.agentName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = favorite.agentName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = formatFavoriteDate(favorite.messageTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun formatFavoriteDate(timestamp: Long): String {
    val localDate = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return localDate.toLocalString(includeYear = true)
}
