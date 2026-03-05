package me.rerere.rikkahub.ui.pages.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.chat.ChatListVM
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.discover.DiscoverPage
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

enum class HomeTab {
    CHATS, DISCOVER, ME
}

@Composable
fun HomePage() {
    var currentTab by remember { mutableStateOf(HomeTab.CHATS) }
    val navController = LocalNavController.current

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == HomeTab.CHATS,
                    onClick = { currentTab = HomeTab.CHATS },
                    icon = { Icon(Icons.Rounded.ChatBubble, null) },
                    label = { Text(stringResource(R.string.chat_page_title)) }
                )
                NavigationBarItem(
                    selected = currentTab == HomeTab.DISCOVER,
                    onClick = { currentTab = HomeTab.DISCOVER },
                    icon = { Icon(Icons.Rounded.Explore, null) },
                    label = { Text(stringResource(R.string.discover_page_title)) }
                )
                NavigationBarItem(
                    selected = currentTab == HomeTab.ME,
                    onClick = { currentTab = HomeTab.ME },
                    icon = { Icon(Icons.Rounded.Person, null) },
                    label = { Text(stringResource(R.string.settings)) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "home_tab_content"
            ) { tab ->
                when (tab) {
                    HomeTab.CHATS -> AgentListPage()
                    HomeTab.DISCOVER -> DiscoverPage()
                    HomeTab.ME -> SettingPage()
                }
            }
        }
    }
}

@Composable
fun AgentListPage() {
    val vm: ChatListVM = koinViewModel()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val repo = org.koin.compose.koinInject<me.rerere.rikkahub.data.repository.ConversationRepository>()

    if (settings == null) return

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.chat_page_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(settings!!.assistants) { assistant ->
                AgentItem(
                    assistant = assistant,
                    onClick = {
                        scope.launch {
                            // 切换当前助手
                            vm.selectAssistant(assistant.id)

                            // 查找该助手的最近一次会话
                            val lastConv = repo.getConversationsOfAssistant(assistant.id)
                                .first()
                                .firstOrNull()

                            val chatId = lastConv?.id ?: Uuid.random()
                            navController.navigate(Screen.Chat(id = chatId.toString()))
                        }
                    }
                )
            }

            item {
                OutlinedButton(
                    onClick = { navController.navigate(Screen.Assistant) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.assistant_page_add))
                }
            }
        }
    }
}

@Composable
fun AgentItem(assistant: Assistant, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = assistant.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = assistant.systemPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
