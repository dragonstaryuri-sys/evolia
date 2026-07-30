package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.ui.components.chat.ChatMessageTurn
import me.rerere.rikkahub.ui.components.chat.MessageTurnGroup
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.utils.plus
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.context.LocalNavController
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UsedMemory
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import kotlin.time.Instant

private const val ScrollBottomKey = "ScrollBottomKey"
sealed class ChatUIItem {
    data class Turn(
        val group: me.rerere.rikkahub.ui.components.chat.MessageTurnGroup,
        val isGenerating: Boolean = false
    ) : ChatUIItem()
    data class Separator(val text: String, val uid: String) : ChatUIItem()
}
@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    items: List<ChatUIItem>,
    paginationState: ConversationRepository.ChatPaginationState?,
    isHistoryLoading: Boolean,
    state: LazyListState,
    loading: Boolean,
    previewMode: Boolean,
    settings: Settings,
    isSyncing: Boolean = false,
    recentlyRestoredNodeIds: Set<Uuid> = emptySet(),
    initialSearchQuery: String? = null,
    targetMessageId: String? = null,
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onJumpToMessage: (MessageNode) -> Unit = {},
    onGetFullMemoryContent: suspend (Int, Int) -> String? = { _, _ -> null },
    onAddFavorite: (List<UIMessage>) -> Unit = {},
    onDeleteMessages: (List<UIMessage>) -> Unit = {},
    onTypingStateChange: (Uuid, Boolean) -> Unit = { _, _ -> },
    onUserScroll: () -> Boolean = { false },
    onRetryPagination: () -> Unit = {},
) {
    val previewState = rememberLazyListState()
    var scrollToNodeId by remember { mutableStateOf<Uuid?>(null) }
    var instantScroll by remember { mutableStateOf(false) }

    LaunchedEffect(targetMessageId, items) {
        if (!targetMessageId.isNullOrBlank()) {
            val targetNode = items.asSequence()
                .mapNotNull { item ->
                    when (item) {
                        is ChatUIItem.Turn -> item.group.nodes.find { n ->
                            n.messages.any { m -> m.id.toString() == targetMessageId }
                        }
                        else -> null
                    }
                }
                .firstOrNull()

            if (targetNode != null) {
                instantScroll = true
                scrollToNodeId = targetNode.id
            }
        }
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = previewMode,
            label = "ChatListMode",
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
            }
        ) { target ->
            if (target) {
                ChatListPreview(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    settings = settings,
                    animatedVisibilityScope = this@AnimatedContent,
                    initialSearchQuery = initialSearchQuery,
                    state = previewState,
                    onJumpToMessage = { node ->
                        instantScroll = true
                        scrollToNodeId = node.id
                        onJumpToMessage(node)
                    },
                )
            } else {
                // 搜索跳转期间仍保持当前窗口可见。目标消息加载完成后再执行定位，
                // 避免目标不存在/分页失败时整个详情页永久透明而显示为空白。
                Box(modifier = Modifier.fillMaxSize()) {
                    // ✨ 更新：使用 paginationState 判断初始加载
                    val isInitialLoading = paginationState is ConversationRepository.ChatPaginationState.Loading && items.isEmpty()

                    val initialError = paginationState as? ConversationRepository.ChatPaginationState.Error
                    if (isInitialLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (initialError != null && items.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(text = initialError.cause.message ?: "加载消息失败")
                            TextButton(onClick = onRetryPagination) { Text("重试") }
                        }
                    } else {
                        this@SharedTransitionLayout.ChatListNormal(
                            innerPadding = innerPadding,
                            conversation = conversation,
                            items = items, // 传递新列表
                            paginationState = paginationState, // 传递状态
                            isHistoryLoading = isHistoryLoading,
                            state = state,
                            scrollToNodeId = scrollToNodeId,
                            instantScroll = instantScroll,
                            onScrolledToNode = {
                                scrollToNodeId = null
                                instantScroll = false
                            },
                            loading = loading,
                            settings = settings,
                            recentlyRestoredNodeIds = recentlyRestoredNodeIds,
                            onRegenerate = onRegenerate,
                            onEdit = onEdit,
                            onDelete = onDelete,
                            onUpdateMessage = onUpdateMessage,
                            onGetFullMemoryContent = onGetFullMemoryContent,
                            onAddFavorite = onAddFavorite,
                            onDeleteMessages = onDeleteMessages,
                            onTypingStateChange = onTypingStateChange,
                            onUserScroll = onUserScroll,
                            onRetryPagination = onRetryPagination,
                            animatedVisibilityScope = this@AnimatedContent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedTransitionScope.ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    items: List<ChatUIItem>,
    paginationState: ConversationRepository.ChatPaginationState?,
    isHistoryLoading: Boolean,
    state: LazyListState,
    loading: Boolean,
    settings: Settings,
    recentlyRestoredNodeIds: Set<Uuid> = emptySet(),
    scrollToNodeId: Uuid? = null,
    instantScroll: Boolean = false,
    onScrolledToNode: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onGetFullMemoryContent: suspend (Int, Int) -> String?,
    onAddFavorite: (List<UIMessage>) -> Unit,
    onTypingStateChange: (Uuid, Boolean) -> Unit,
    onUserScroll: () -> Boolean,
    onRetryPagination: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onDeleteMessages: (List<UIMessage>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val latestOnUserScroll = rememberUpdatedState(onUserScroll)
    val userScrollConnection = remember(scope) {
        object : NestedScrollConnection {
            var paginationRequestedForGesture = false
            var gestureResetJob: Job? = null
            var boundaryCheckJob: Job? = null

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput &&
                    (consumed.y != 0f || available.y != 0f)
                ) {
                    gestureResetJob?.cancel()
                    gestureResetJob = scope.launch {
                        delay(300)
                        paginationRequestedForGesture = false
                    }
                    if (!paginationRequestedForGesture) {
                        boundaryCheckJob?.cancel()
                        boundaryCheckJob = scope.launch {
                            withFrameNanos { }
                            val paginationStarted = latestOnUserScroll.value()
                            if (paginationStarted) {
                                paginationRequestedForGesture = true
                            }
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }

    val currentConversationState = rememberUpdatedState(conversation)
    val onCitationClick = remember {
        { citationId: String ->
            run findCitation@{
                currentConversationState.value.currentMessages.forEach { message ->
                    message.parts.forEach { part ->
                        if (part is UIMessagePart.ToolResult && part.toolName == "search_web") {
                            val items = part.content.jsonObject["items"]?.jsonArray ?: return@forEach
                            items.forEach { item ->
                                val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                                val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                                if (citationId == id) {
                                    navController.navigate(Screen.WebView(url = url))
                                    return@findCitation
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    var previewingMemory by remember { mutableStateOf<UsedMemory?>(null) }
    var isMemoryLoading by remember { mutableStateOf(false) }

    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    val needsPhantomLoadingTurn = loading && (
        items.isEmpty() || run {
            val firstItem = items.firstOrNull()
            firstItem is ChatUIItem.Turn && firstItem.group.role == MessageRole.USER
        }
    )
    // ✨ 更新：跳转滚动逻辑
    LaunchedEffect(scrollToNodeId, items) {
        val targetId = scrollToNodeId ?: return@LaunchedEffect
        val index = items.indexOfFirst { item ->
            item is ChatUIItem.Turn && item.group.nodes.any { it.id == targetId }
        }
        if (index >= 0) {
            if (instantScroll) state.scrollToItem(index + 1)
            else state.animateScrollToItem(index + 1)
            onScrolledToNode()
        }
    }

    // 计算最新一组 user 和 assistant 消息的索引（reverseLayout，index 0 为最新）
    // 只有这两组消息才显示刷新按钮，避免用户刷新历史消息导致上下文错乱
    val latestUserTurnIndex = remember(items) {
        items.indexOfFirst { it is ChatUIItem.Turn && it.group.role == MessageRole.USER }
    }
    val latestAssistantTurnIndex = remember(items) {
        items.indexOfFirst { it is ChatUIItem.Turn && it.group.role == MessageRole.ASSISTANT }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp) +
                PaddingValues(top = 32.dp) + innerPadding + WindowInsets.ime.asPaddingValues(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(userScrollConnection),
        ) {
            item(ScrollBottomKey) { Spacer(Modifier.fillMaxWidth().height(5.dp)) }

            val pageState = paginationState as? ConversationRepository.ChatPaginationState.Success
            if (pageState?.loadingDirection == ConversationRepository.PageLoadDirection.NEWER) {
                item("newer_loading_indicator") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (needsPhantomLoadingTurn) {
                item("phantom_loading") {
                    ChatMessageTurn(
                        group = MessageTurnGroup(listOf(MessageNode.of(UIMessage.assistant(""), conversation.id)), MessageRole.ASSISTANT),
                        isLastTurn = true,
                        assistant = settings.getAssistantById(conversation.assistantId),
                        loading = true,
                        showRegenerate = false,
                        onCitationClick = onCitationClick,
                    )
                }
            }

            itemsIndexed(
                items = items,
                key = { _, item ->
                    when (item) {
                        is ChatUIItem.Turn -> "turn_${item.group.firstNode.id}"
                        is ChatUIItem.Separator -> "sep_${item.uid}"
                    }
                }
            )  { index, item ->
                when (item) {
                    is ChatUIItem.Turn -> {
                        // ✨ 直接从 item 中读取组装好的状态
                        val isGenerating = item.isGenerating

                        // 话题间的时间显示逻辑
                        val nextItem = items.getOrNull(index + 1)
                        val shouldShowTime = nextItem == null || nextItem is ChatUIItem.Separator || run {
                            // 由于现在只有 Turn，提取时间逻辑变简单了
                            val olderTime = (nextItem as? ChatUIItem.Turn)?.group?.nodes?.last()?.currentMessage?.createdAt
                            olderTime == null || (item.group.nodes.first().currentMessage.createdAt.toInstant(TimeZone.currentSystemDefault()) -
                                olderTime.toInstant(TimeZone.currentSystemDefault())) > 5.minutes
                        }

                        val isTurnSelected = remember(selectedItems.size) {
                            item.group.nodes.any { it.id in selectedItems }
                        }

                        ListSelectableItem(
                            isSelected = isTurnSelected,
                            onSelectChange = { selected ->
                                item.group.nodes.forEach { node ->
                                    if (selected) {
                                        if (node.id !in selectedItems) selectedItems.add(node.id)
                                    } else {
                                        selectedItems.remove(node.id)
                                    }
                                }
                            },
                            enabled = selecting
                        ) {
                            Column {
                                if (shouldShowTime) {
                                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = formatTime(item.group.nodes.first().currentMessage.createdAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                                ChatMessageTurn(
                                    group = item.group,
                                    isLastTurn = index == 0,
                                    assistant = settings.getAssistantById(conversation.assistantId),
                                    loading = loading && item.isGenerating,
                                    model = settings.getCurrentChatModel(),
                                    showRegenerate = index == latestUserTurnIndex || index == latestAssistantTurnIndex,
                                    onCitationClick = onCitationClick,
                                    onRegenerate = { node -> onRegenerate(node.currentMessage) },
                                    onEdit = { node -> onEdit(node.currentMessage) },
                                    onDelete = { node -> onDelete(node.currentMessage) },
                                    onShare = { node ->
                                        selecting = true
                                        selectedItems.clear()
                                        selectedItems.add(node.id)
                                    },
                                    onUpdate = onUpdateMessage,
                                    onEditLorebookEntry = { entry -> navController.navigate(Screen.SettingLorebookDetail(entry.lorebookId, entry.entryId)) },
                                    onMemoryClick = { memory ->
                                        scope.launch {
                                            isMemoryLoading = true
                                            previewingMemory = memory
                                            val full = onGetFullMemoryContent(memory.memoryId, memory.memoryType)
                                            previewingMemory = memory.copy(memoryContent = full ?: "未找到内容")
                                            isMemoryLoading = false
                                        }
                                    },
                                    onTypingStateChange = { isTyping ->
                                        onTypingStateChange(item.group.firstNode.id, isTyping)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    is ChatUIItem.Separator -> {
                        // ✨ 这里的逻辑变简单了，不需要主动调用 onLoadMore，由 ChatPage 的滚动监听负责
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // ✨ 在列表最顶部（历史端）显示加载圈
            if (isHistoryLoading || paginationState is ConversationRepository.ChatPaginationState.Loading) {
                item("loading_indicator") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            }

            pageState?.error?.let { error ->
                item("pagination_error_${pageState.errorDirection}") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error.message ?: "加载消息失败",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(onClick = onRetryPagination) { Text("重试") }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = -(48).dp),
                enter = slideInVertically(initialOffsetY = { it * 2 }),
                exit = slideOutVertically(targetOffsetY = { it * 2 }),
            ) {
                HorizontalFloatingToolbar(expanded = true) {
                    Tooltip(tooltip = { Text(stringResource(R.string.cancel)) }) {
                        IconButton(onClick = { selecting = false; selectedItems.clear() }) { Icon(Icons.Rounded.Close, null) }
                    }
                    Tooltip(tooltip = { Text(stringResource(R.string.delete)) }) {
                        IconButton(
                            enabled = selectedItems.isNotEmpty(),
                            onClick = {
                                // ✨ 核心修改：直接从现在的 items 列表中提取所有 Node
                                val allMsgNodes = items.filterIsInstance<ChatUIItem.Turn>()
                                    .flatMap { it.group.nodes }

                                val toDelete = allMsgNodes
                                    .filter { it.id in selectedItems }
                                    .map { it.currentMessage }
                                    .distinctBy { it.id }

                                onDeleteMessages(toDelete)
                                selecting = false
                                selectedItems.clear()
                            }
                        ) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    }
                    // 下面的 Favorite 和 Share 建议也同步修改，保持数据源一致
                    Tooltip(tooltip = { Text(stringResource(R.string.action_favorite)) }) {
                        IconButton(enabled = selectedItems.isNotEmpty(), onClick = {
                            val allMsgNodes = items.filterIsInstance<ChatUIItem.Turn>()
                                .flatMap { it.group.nodes }

                            val messages = allMsgNodes
                                .filter { it.id in selectedItems }
                                .map { it.currentMessage }

                            onAddFavorite(messages)
                            selecting = false
                            selectedItems.clear()
                        }) { Icon(Icons.Rounded.Favorite, null) }
                    }
                    Tooltip(tooltip = { Text(stringResource(R.string.action_share)) }) {
                        FilledIconButton(enabled = selectedItems.isNotEmpty(), onClick = {
                            // 注意：ExportSheet 内部也需要 List<UIMessage>，逻辑同上
                            selecting = false
                            showExportSheet = true
                        }) { Icon(Icons.Rounded.Share, null) }
                    }
                }
            }

            // ExportSheet 的 selectedMessages 也建议从 items 中提取
            val selectedMessagesForExport = remember(selectedItems.size, items) {
                items.filterIsInstance<ChatUIItem.Turn>()
                    .flatMap { it.group.nodes }
                    .filter { it.id in selectedItems }
                    .map { it.currentMessage }
            }

            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = { showExportSheet = false; selectedItems.clear() },
                conversation = conversation,
                selectedMessages = selectedMessagesForExport
            )

            previewingMemory?.let { MemoryPreviewDialog(memory = it, isLoading = isMemoryLoading, onDismissRequest = { previewingMemory = null; isMemoryLoading = false }) }
        }
    }
}

@Composable
private fun MessageItemBox(
    node: MessageNode, isLastTurn: Boolean, shouldShowTime: Boolean, loading: Boolean,
    settings: Settings, conversation: Conversation, selecting: Boolean, selectedItems: MutableList<Uuid>,
    onCitationClick: (String) -> Unit, onRegenerate: (UIMessage) -> Unit, onEdit: (UIMessage) -> Unit, onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit, onGetFullMemoryContent: suspend (Int, Int) -> String?,
    onAddFavorite: (List<UIMessage>) -> Unit, onTypingStateChange: (Uuid, Boolean) -> Unit,
    navController: androidx.navigation.NavController, scope: CoroutineScope,
    onMemoryLoading: (Boolean) -> Unit, onPreviewMemory: (UsedMemory) -> Unit,
    onStartSelecting: (Uuid) -> Unit
) {
    Column {
        if (shouldShowTime) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(text = formatTime(node.currentMessage.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
            }
        }
        val isSelected by remember(node.id) { derivedStateOf { selectedItems.contains(node.id) } }
        ListSelectableItem(isSelected = isSelected, onSelectChange = { if (it) selectedItems.add(node.id) else selectedItems.remove(node.id) }, enabled = selecting) {
            val showRegenerate by remember(node.currentMessage.role, isLastTurn) { derivedStateOf { node.currentMessage.role == MessageRole.USER || isLastTurn } }
            ChatMessageTurn(
                group = MessageTurnGroup(listOf(node), node.currentMessage.role), isLastTurn = isLastTurn, onCitationClick = onCitationClick,
                model = node.currentMessage.modelId?.let { settings.findModelById(it) }, assistant = settings.getAssistantById(conversation.assistantId),
                loading = loading, onRegenerate = { onRegenerate(it.currentMessage) }, onEdit = { onEdit(it.currentMessage) }, onDelete = { onDelete(it.currentMessage) },
                onShare = { onStartSelecting(node.id) },
                onUpdate = { onUpdateMessage(it) }, onEditLorebookEntry = { navController.navigate(Screen.SettingLorebookDetail(it.lorebookId, it.entryId)) },
                onMemoryClick = { memory ->
                    scope.launch { onMemoryLoading(true); onPreviewMemory(memory); val full = onGetFullMemoryContent(memory.memoryId, memory.memoryType); onPreviewMemory(memory.copy(memoryContent = full ?: "未找到内容")); onMemoryLoading(false) }
                }, showRegenerate = showRegenerate, onTypingStateChange = { onTypingStateChange(node.id, it) }
            )
        }
        val isTruncatePoint = conversation.truncateIndex > 0 && conversation.messageNodes.getOrNull(conversation.truncateIndex - 1)?.id == node.id
        if (isTruncatePoint) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.weight(1f)); Text(text = stringResource(R.string.chat_page_clear_context), style = MaterialTheme.typography.bodySmall); HorizontalDivider(modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun formatTime(dateTime: LocalDateTime): String {
    val now = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toLocalDateTime(TimeZone.currentSystemDefault())
    val date = dateTime.date
    val nowDate = now.date
    val timeStr = "${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}"
    return when {
        date == nowDate -> timeStr
        date == nowDate.minus(1, DateTimeUnit.DAY) -> "昨天 $timeStr"
        date.year == nowDate.year -> "${date.month.number}月${date.day}日 $timeStr"
        else -> "${date.year}年${date.month.number}月${date.day}日 $timeStr"
    }
}

@Composable
private fun SharedTransitionScope.ChatListPreview(
    innerPadding: PaddingValues, conversation: Conversation, settings: Settings, animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (MessageNode) -> Unit, initialSearchQuery: String? = null, state: LazyListState,
) {
    var searchQuery by remember { mutableStateOf(initialSearchQuery ?: "") }
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        val visibleNodes = conversation.messageNodes.filter { !it.currentMessage.skipContext }
        if (searchQuery.isBlank()) conversation.messageNodes
        else visibleNodes.filter { it.currentMessage.toContentText().contains(searchQuery, ignoreCase = true) }
    }
    Column(modifier = Modifier.padding(innerPadding).padding(top = 20.dp).fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.chat_page_search_placeholder)) }, leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(20.dp)) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Rounded.Close, null, modifier = Modifier.size(20.dp)) } },
            singleLine = true, shape = CircleShape, maxLines = 1,
        )
        LazyColumn(state = state, contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(items = filteredMessages, key = { _, item -> item.id }) { _, node ->
                val message = node.currentMessage
                val isUser = message.role == MessageRole.USER
                Column(modifier = Modifier.fillMaxWidth().then(if (!isUser) Modifier.padding(end = 24.dp) else Modifier), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    Surface(shape = MaterialTheme.shapes.medium, color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
                        val highlightedText = remember(searchQuery, message, highlightColor) { buildHighlightedText(extractMatchingSnippet(message.toContentText().trim().ifBlank { "[...]" }, searchQuery), searchQuery, highlightColor) }
                        Text(text = highlightedText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onJumpToMessage(node) }.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

private fun buildHighlightedText(text: String, query: String, highlightColor: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)
        while (index >= 0) {
            append(text.substring(startIndex, index))
            withStyle(style = SpanStyle(background = highlightColor, color = Color.Black)) { append(text.substring(index, index + query.length)) }
            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }
        if (startIndex < text.length) append(text.substring(startIndex))
    }
}

private fun extractMatchingSnippet(text: String, query: String): String {
    if (query.isBlank()) return text
    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) return text
    val snippet = text.substring(matchIndex)
    return if (matchIndex > 0) "...$snippet" else snippet
}

@Composable
fun MemoryPreviewDialog(memory: UsedMemory, isLoading: Boolean, onDismissRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(id = android.R.string.ok)) } },
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(imageVector = when(memory.memoryType) { 0 -> Icons.Rounded.Memory; 2 -> Icons.Rounded.Bolt; else -> Icons.Rounded.History }, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Text(text = if (memory.memoryType == 0) stringResource(R.string.context_sources_core_memory) else stringResource(R.string.context_sources_episodic_memory)) } },
        text = { Box(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(stiffness = 300f)).heightIn(min = 120.dp), contentAlignment = Alignment.Center) { if (isLoading) CircularProgressIndicator(modifier = Modifier.size(36.dp)) else Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) { Text(text = memory.memoryContent, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } } } }
    )
}
