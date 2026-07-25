package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.db.entity.ChatMessageEntity
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.common.JsonInstant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatHistorySearchVM(
    val assistantId: String,
    private val conversationRepository: ConversationRepository
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    @OptIn(FlowPreview::class)
    val searchResults = _query
        .debounce(300L)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList<ChatMessageEntity>())
            else conversationRepository.chatMessageDAO.searchMessagesOfAssistant(assistantId, q)
                .map { entities ->
                    // 1. 核心过滤逻辑：仅检索回复中的正式内容 (Text)，排除思考内容 (Reasoning/Thinking)
                    entities.filter { entity ->
                        val message = JsonInstant.decodeFromString<UIMessage>(entity.contentJson)
                        // ✨ 增加过滤：排除掉 skipContext 的系统消息
                        !message.skipContext && message.toContentText().contains(q, ignoreCase = true)
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistorySearchPage(assistantId: String) {
    val vm: ChatHistorySearchVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val focusRequester = remember { FocusRequester() }

    val settingsStore: SettingsStore = org.koin.compose.koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val assistant = remember(settings.assistants, assistantId) {
        settings.assistants.find { it.id.toString() == assistantId }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = vm::updateQuery,
                        placeholder = {
                            Text(stringResource(R.string.chat_history_search_hint, assistant?.name ?: ""))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.updateQuery("") }) {
                                    Icon(Icons.Rounded.Close, null)
                                }
                            }
                        },
                        singleLine = true
                    )
                },
                navigationIcon = { BackButton() }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { it.id }) { entity ->
                SearchItem(
                    entity = entity,
                    assistantName = assistant?.name ?: stringResource(R.string.assistant_page_default_assistant),
                    onClick = {
                        // 2. 传递 targetMessageId 以实现秒开精准定位
                        navController.navigate(
                            Screen.Chat(
                                id = entity.conversationId,
                                searchQuery = query,
                                targetMessageId = entity.id
                            )
                        )
                    }
                )
            }

            if (results.isEmpty() && query.isNotBlank()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.chat_history_search_empty), color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchItem(entity: ChatMessageEntity, assistantName: String, onClick: () -> Unit) {
    val message = remember(entity.contentJson) {
        JsonInstant.decodeFromString<UIMessage>(entity.contentJson)
    }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (message.role == me.rerere.ai.core.MessageRole.USER) "我" else assistantName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entity.createdAt.let {
                        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                            .format(Date(it))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.toContentText(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
