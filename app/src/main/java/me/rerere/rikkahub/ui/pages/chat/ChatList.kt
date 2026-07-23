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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import kotlinx.coroutines.CoroutineScope
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
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
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
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import kotlin.time.Instant

private const val ScrollBottomKey = "ScrollBottomKey"

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    activeMessages: List<ChatVM.ChatUIItem>,
    isInternalLoadingMore: Boolean = false,
    onLoadMoreActiveMessages: () -> Unit = {},
    uiItems: LazyPagingItems<ChatVM.ChatUIItem>,
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
) {
    val previewState = rememberLazyListState()
    var scrollToNodeId by remember { mutableStateOf<Uuid?>(null) }
    var instantScroll by remember { mutableStateOf(false) }

    var isWaitingForJump by remember(targetMessageId) { mutableStateOf(!targetMessageId.isNullOrBlank()) }

    LaunchedEffect(initialSearchQuery, targetMessageId, uiItems.itemSnapshotList, activeMessages) {
        if (!targetMessageId.isNullOrBlank()) {
            // ✨ 关键修复：支持在 Turn 类型中查找节点
            val activeNode = activeMessages.asSequence()
                .mapNotNull {
                    when(it) {
                        is ChatVM.ChatUIItem.Message -> it.node
                        is ChatVM.ChatUIItem.Turn -> it.group.nodes.find { n -> n.messages.any { m -> m.id.toString() == targetMessageId } }
                        else -> null
                    }
                }
                .find { node -> node.messages.any { msg -> msg.id.toString() == targetMessageId } }

            if (activeNode != null) {
                instantScroll = true
                scrollToNodeId = activeNode.id
                return@LaunchedEffect
            }

            val pagingNode = uiItems.itemSnapshotList.items.asSequence()
                .mapNotNull {
                    when(it) {
                        is ChatVM.ChatUIItem.Message -> it.node
                        is ChatVM.ChatUIItem.Turn -> it.group.nodes.find { n -> n.messages.any { m -> m.id.toString() == targetMessageId } }
                        else -> null
                    }
                }
                .find { node -> node.messages.any { msg -> msg.id.toString() == targetMessageId } }

            if (pagingNode != null) {
                instantScroll = true
                scrollToNodeId = pagingNode.id
            }
        } else {
            isWaitingForJump = false
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
                Box(modifier = Modifier.fillMaxSize().alpha(if (isWaitingForJump) 0f else 1f)) {
                    val isInitialLoading = uiItems.loadState.refresh is LoadState.Loading && uiItems.itemCount == 0 && activeMessages.isEmpty() && !isSyncing

                    if (isInitialLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        ChatListNormal(
                            innerPadding = innerPadding,
                            conversation = conversation,
                            activeMessages = activeMessages,
                            isInternalLoadingMore = isInternalLoadingMore,
                            onLoadMoreActiveMessages = onLoadMoreActiveMessages,
                            uiItems = uiItems,
                            isHistoryLoading = isHistoryLoading,
                            state = state,
                            scrollToNodeId = scrollToNodeId,
                            instantScroll = instantScroll,
                            onScrolledToNode = {
                                scrollToNodeId = null
                                instantScroll = false
                                isWaitingForJump = false
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
    activeMessages: List<ChatVM.ChatUIItem>,
    isInternalLoadingMore: Boolean,
    onLoadMoreActiveMessages: () -> Unit,
    uiItems: LazyPagingItems<ChatVM.ChatUIItem>,
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
    animatedVisibilityScope: AnimatedVisibilityScope,
    onDeleteMessages: (List<UIMessage>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current

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

    ImeLazyListAutoScroller(lazyListState = state)

    val needsPhantomLoadingTurn = loading && (
        (activeMessages.isEmpty() && uiItems.itemCount == 0 && uiItems.loadState.refresh !is LoadState.Loading) ||
            run {
                when (val firstItem = activeMessages.firstOrNull()) {
                    is ChatVM.ChatUIItem.Message -> firstItem.node.currentMessage.role == MessageRole.USER
                    is ChatVM.ChatUIItem.Turn -> firstItem.group.role == MessageRole.USER
                    else -> false
                }
            }
        )

    LaunchedEffect(scrollToNodeId, activeMessages, uiItems.itemCount){
        val targetId = scrollToNodeId ?: return@LaunchedEffect

        // ✨ 关键修复：支持查找 Turn 类型中的索引
        val activeIndex = activeMessages.indexOfFirst { item ->
            when(item) {
                is ChatVM.ChatUIItem.Message -> item.node.id == targetId
                is ChatVM.ChatUIItem.Turn -> item.group.nodes.any { it.id == targetId }
                else -> false
            }
        }

        if (activeIndex >= 0) {
            state.scrollToItem(activeIndex + 1)
            onScrolledToNode()
            return@LaunchedEffect
        }

        val pagingIndex = uiItems.itemSnapshotList.indexOfFirst { item ->
            when(item) {
                is ChatVM.ChatUIItem.Message -> item.node.id == targetId
                is ChatVM.ChatUIItem.Turn -> item.group.nodes.any { it.id == targetId }
                else -> false
            }
        }

        if (pagingIndex >= 0) {
            val totalIndex = activeMessages.size + pagingIndex + 1
            if (instantScroll) state.scrollToItem(totalIndex) else state.animateScrollToItem(totalIndex)
            onScrolledToNode()
        }
    }

    // ✨ 核心修复：收集 activeMessages 中已经包含的所有节点 ID，用于分页去重
    val activeNodeIds = remember(activeMessages) {
        activeMessages.flatMap { item ->
            when (item) {
                is ChatVM.ChatUIItem.Message -> listOf(item.node.id)
                is ChatVM.ChatUIItem.Turn -> item.group.nodes.map { it.id }
                else -> emptyList()
            }
        }.toSet()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp) +
                PaddingValues(top = 32.dp) + innerPadding + WindowInsets.ime.asPaddingValues(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(ScrollBottomKey) { Spacer(Modifier.fillMaxWidth().height(5.dp)) }

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
                items = activeMessages,
                key = { _, item ->
                    when(item) {
                        is ChatVM.ChatUIItem.Turn -> "active_turn_${item.group.firstNode.id}"
                        is ChatVM.ChatUIItem.Message -> "active_${item.node.id}"
                        is ChatVM.ChatUIItem.Separator -> "active_separator_${item.text.hashCode()}"
                    }
                }
            ) { index, item ->
                when (item) {
                    is ChatVM.ChatUIItem.Turn -> {
                        val isGenerating = index == 0 && loading

                        val nextItem = activeMessages.getOrNull(index + 1)
                        val shouldShowTime = nextItem == null || nextItem is ChatVM.ChatUIItem.Separator || run {
                            val olderTime = when (nextItem) {
                                is ChatVM.ChatUIItem.Turn -> nextItem.group.nodes.last().currentMessage.createdAt
                                is ChatVM.ChatUIItem.Message -> nextItem.node.currentMessage.createdAt
                                else -> null
                            }
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
                                        Text(text = formatTime(item.group.nodes.first().currentMessage.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                                    }
                                }
                                ChatMessageTurn(
                                    group = item.group,
                                    isLastTurn = index == 0,
                                    assistant = settings.getAssistantById(conversation.assistantId),
                                    loading = loading && item.isGenerating,
                                    model = settings.getCurrentChatModel(),
                                    showRegenerate = true,
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    is ChatVM.ChatUIItem.Message -> {
                        val node = item.node
                        val isLastTurn = index == 0
                        val nextItem = activeMessages.getOrNull(index + 1)
                        val shouldShowTime = nextItem == null || nextItem is ChatVM.ChatUIItem.Separator || run {
                            val olderTime = when (nextItem) {
                                is ChatVM.ChatUIItem.Turn -> nextItem.group.nodes.last().currentMessage.createdAt
                                is ChatVM.ChatUIItem.Message -> nextItem.node.currentMessage.createdAt
                                else -> null
                            }
                            olderTime == null || (node.currentMessage.createdAt.toInstant(TimeZone.currentSystemDefault()) -
                                olderTime.toInstant(TimeZone.currentSystemDefault())) > 5.minutes
                        }

                        MessageItemBox(
                            node = node, isLastTurn = isLastTurn, shouldShowTime = shouldShowTime, loading = loading && isLastTurn,
                            settings = settings, conversation = conversation, selecting = selecting, selectedItems = selectedItems,
                            onCitationClick = onCitationClick, onRegenerate = onRegenerate, onEdit = onEdit, onDelete = onDelete,
                            onUpdateMessage = onUpdateMessage, onGetFullMemoryContent = onGetFullMemoryContent, onAddFavorite = onAddFavorite,
                            onTypingStateChange = onTypingStateChange, navController = navController, scope = scope,
                            onMemoryLoading = { isMemoryLoading = it }, onPreviewMemory = { previewingMemory = it },
                            onStartSelecting = { id -> selecting = true; selectedItems.clear(); selectedItems.add(id) }
                        )
                    }
                    is ChatVM.ChatUIItem.Separator -> {
                        LaunchedEffect(Unit) {
                            onLoadMoreActiveMessages()
                        }
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            if (isInternalLoadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Text(text = item.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }

            items(
                count = uiItems.itemCount,
                key = { index ->
                    when (val item = uiItems.peek(index)) {
                        is ChatVM.ChatUIItem.Message -> "paging_${item.node.id}"
                        is ChatVM.ChatUIItem.Turn -> "paging_turn_${item.group.firstNode.id}"
                        is ChatVM.ChatUIItem.Separator -> "sep_${item.text}"
                        else -> "placeholder_$index"
                    }
                }
            ) { index ->
                val item = uiItems[index] ?: return@items
                if (item is ChatVM.ChatUIItem.Message && item.node.currentMessage.skipContext) return@items

                // ✨ 核心修复：更彻底的分页去重检查
                val isDuplicate = when (item) {
                    is ChatVM.ChatUIItem.Message -> activeNodeIds.contains(item.node.id)
                    is ChatVM.ChatUIItem.Turn -> item.group.nodes.any { activeNodeIds.contains(it.id) }
                    else -> false
                }
                if (isDuplicate) return@items

                when (item) {
                    is ChatVM.ChatUIItem.Turn -> {
                        val nextItem = if (index + 1 < uiItems.itemCount) uiItems.peek(index + 1) else null
                        val shouldShowTime = nextItem == null || nextItem is ChatVM.ChatUIItem.Separator || run {
                            val olderTime = when (nextItem) {
                                is ChatVM.ChatUIItem.Turn -> nextItem.group.nodes.last().currentMessage.createdAt
                                is ChatVM.ChatUIItem.Message -> nextItem.node.currentMessage.createdAt
                                else -> null
                            }
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
                                        Text(text = formatTime(item.group.nodes.first().currentMessage.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                                    }
                                }
                                ChatMessageTurn(
                                    group = item.group,
                                    isLastTurn = false,
                                    assistant = settings.getAssistantById(conversation.assistantId),
                                    loading = false,
                                    model = settings.getCurrentChatModel(),
                                    showRegenerate = item.group.role == MessageRole.ASSISTANT,
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    is ChatVM.ChatUIItem.Message -> {
                        val node = item.node
                        val nextItem = if (index + 1 < uiItems.itemCount) uiItems.peek(index + 1) else null
                        val shouldShowTime = nextItem == null || nextItem is ChatVM.ChatUIItem.Separator || run {
                            val olderTime = when (nextItem) {
                                is ChatVM.ChatUIItem.Turn -> nextItem.group.nodes.last().currentMessage.createdAt
                                is ChatVM.ChatUIItem.Message -> nextItem.node.currentMessage.createdAt
                                else -> null
                            }
                            olderTime == null || (node.currentMessage.createdAt.toInstant(TimeZone.currentSystemDefault()) -
                                olderTime.toInstant(TimeZone.currentSystemDefault())) > 5.minutes
                        }

                        MessageItemBox(
                            node = node, isLastTurn = false, shouldShowTime = shouldShowTime, loading = false,
                            settings = settings, conversation = conversation, selecting = selecting, selectedItems = selectedItems,
                            onCitationClick = onCitationClick, onRegenerate = onRegenerate, onEdit = onEdit, onDelete = onDelete,
                            onUpdateMessage = onUpdateMessage, onGetFullMemoryContent = onGetFullMemoryContent, onAddFavorite = onAddFavorite,
                            onTypingStateChange = onTypingStateChange, navController = navController, scope = scope,
                            onMemoryLoading = { isMemoryLoading = it }, onPreviewMemory = { previewingMemory = it },
                            onStartSelecting = { id -> selecting = true; selectedItems.clear(); selectedItems.add(id) }
                        )
                    }
                    is ChatVM.ChatUIItem.Separator -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Text(text = item.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            if ((isHistoryLoading || uiItems.loadState.append is LoadState.Loading) && uiItems.itemCount > 0) {
                item("paging_loading_indicator") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
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
                                val allMsgNodes = mutableListOf<MessageNode>()
                                activeMessages.forEach {
                                    when(it) {
                                        is ChatVM.ChatUIItem.Message -> allMsgNodes.add(it.node)
                                        is ChatVM.ChatUIItem.Turn -> allMsgNodes.addAll(it.group.nodes)
                                        else -> {}
                                    }
                                }
                                for (i in 0 until uiItems.itemCount) {
                                    when(val it = uiItems.peek(i)) {
                                        is ChatVM.ChatUIItem.Message -> allMsgNodes.add(it.node)
                                        is ChatVM.ChatUIItem.Turn -> allMsgNodes.addAll(it.group.nodes)
                                        else -> {}
                                    }
                                }

                                val toDelete = allMsgNodes.filter { it.id in selectedItems }.map { it.currentMessage }.distinctBy { it.id }
                                onDeleteMessages(toDelete); selecting = false; selectedItems.clear()
                            }
                        ) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    }
                    Tooltip(tooltip = { Text(stringResource(R.string.action_favorite)) }) {
                        IconButton(enabled = selectedItems.isNotEmpty(), onClick = {
                            val messages = conversation.messageNodes.filter { it.id in selectedItems }.map { it.currentMessage }
                            onAddFavorite(messages); selecting = false; selectedItems.clear()
                        }) { Icon(Icons.Rounded.Favorite, null) }
                    }
                    Tooltip(tooltip = { Text(stringResource(R.string.action_share)) }) {
                        FilledIconButton(enabled = selectedItems.isNotEmpty(), onClick = { selecting = false; showExportSheet = true }) { Icon(Icons.Rounded.Share, null) }
                    }
                }
            }
            ChatExportSheet(visible = showExportSheet, onDismissRequest = { showExportSheet = false; selectedItems.clear() }, conversation = conversation, selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }.map { it.currentMessage })
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
