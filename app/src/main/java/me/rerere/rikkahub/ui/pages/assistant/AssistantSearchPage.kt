package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.home.AgentItem
import me.rerere.rikkahub.ui.pages.chat.ChatListVM
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSearchPage() {
    val vm: AssistantVM = koinViewModel()
    val chatVm: ChatListVM = koinViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lastMessages by chatVm.assistantsLastMessages.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val repo = org.koin.compose.koinInject<me.rerere.rikkahub.core.data.repository.ConversationRepository>()

    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val filteredAssistants = remember(settings.assistants, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            settings.assistants.filter {
                it.name.contains(query, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        singleLine = true
                    )
                },
                navigationIcon = { BackButton() }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp)
        ) {
            if (query.isBlank()) {
                item {
                    Text(
                        text = stringResource(R.string.assistant_page_search_placeholder ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(filteredAssistants) { assistant ->
                    AgentItem(
                        assistant = assistant,
                        lastMessage = lastMessages[assistant.id] ?: "",
                        onClick = {
                            scope.launch {
                                chatVm.selectAssistant(assistant.id)
                                val lastConv = repo.getConversationsOfAssistant(assistant.id)
                                    .firstOrNull()
                                    ?.firstOrNull()
                                val chatId = lastConv?.id ?: Uuid.random()
                                navController.navigate(Screen.Chat(id = chatId.toString()))
                            }
                        }
                    )
                }
            }
        }
    }
}
