package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.paging.compose.collectAsLazyPagingItems
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.navigateToChatPage
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: NavHostController,
    vm: ChatVM,
    settings: Settings,
    conversationId: Uuid,
    drawerState: DrawerState? = null,
) {
    val conversations = vm.conversations.collectAsLazyPagingItems()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val recentlyRestoredIds by vm.recentlyRestoredIds.collectAsStateWithLifecycle()
    val currentAssistant = settings.getCurrentAssistant()

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Assistant Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                UIAvatar(
                    name = currentAssistant.name,
                    value = currentAssistant.avatar,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = currentAssistant.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            // New Chat Button for THIS Assistant
            Button(
                onClick = {
                    navigateToChatPage(navController, chatId = Uuid.random())
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_page_new_chat))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Conversation History List
            ConversationList(
                current = remember(conversationId) { Conversation.dummy().copy(id = conversationId) },
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                recentlyRestoredIds = recentlyRestoredIds,
                searchQuery = searchQuery,
                onSearchQueryChange = { vm.updateSearchQuery(it) },
                modifier = Modifier.weight(1f),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onDelete = { vm.deleteConversation(it) },
                onPin = { vm.updatePinnedStatus(it) },
                onEditTitle = { conv, title -> vm.updateConversationTitle(conv, title) },
                onRegenerateTitle = { vm.generateTitle(it) },
                onConsolidate = { vm.consolidateConversation(it) },
                showUnconsolidatedDot = currentAssistant.enableMemory && currentAssistant.enableMemoryConsolidation,
                showConsolidateOption = currentAssistant.enableMemory && currentAssistant.enableMemoryConsolidation
            )
        }
    }
}
