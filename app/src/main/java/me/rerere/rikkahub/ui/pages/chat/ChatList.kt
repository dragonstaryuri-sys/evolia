package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
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
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.ui.components.chat.ChatMessageTurn
import me.rerere.rikkahub.ui.components.chat.MessageTurnGroup
import me.rerere.rikkahub.ui.components.chat.groupIntoTurns
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private const val TAG = "ChatList"
private const val ScrollBottomKey = "ScrollBottomKey"

sealed class ChatListDisplayItem {
    data class TurnGroup(val group: MessageTurnGroup) : ChatListDisplayItem()
    data class Separator(val text: String) : ChatListDisplayItem()
    data class Time(val timeText: String) : ChatListDisplayItem()
}

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    uiItems: List<ChatVM.ChatUIItem>,
    state: LazyListState,
    loading: Boolean,
    previewMode: Boolean,
    settings: Settings,
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

    // 使用 alpha 遮罩实现“微信效果”：在跳转定位完成前不显示列表，防止看到滑动过程
    var isWaitingForJump by remember(targetMessageId) { mutableStateOf(!targetMessageId.isNullOrBlank()) }

    LaunchedEffect(initialSearchQuery, targetMessageId, uiItems) {
        if (!targetMessageId.isNullOrBlank()) {
            val node = uiItems.filterIsInstance<ChatVM.ChatUIItem.Message>()
                .find { it.node.messages.any { msg -> msg.id.toString() == targetMessageId } }
                ?.node
            if (node != null) {
                instantScroll = true
                scrollToNodeId = node.id
            } else {
                // 数据可能还在加载中
            }
        } else if (!initialSearchQuery.isNullOrBlank()) {
            val node = uiItems.filterIsInstance<ChatVM.ChatUIItem.Message>()
                .map { it.node }
                .findLast { it.currentMessage.toContentText().contains(initialSearchQuery, ignoreCase = true) }

            if (node != null) {
                instantScroll = false
                scrollToNodeId = node.id
            }
            isWaitingForJump = false
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
                    loading = loading,
                    onJumpToMessage = { node ->
                        instantScroll = true
                        scrollToNodeId = node.id
                        onJumpToMessage(node)
                    },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().alpha(if (isWaitingForJump) 0f else 1f)) {
                    ChatListNormal(
                        innerPadding = innerPadding,
                        conversation = conversation,
                        uiItems = uiItems,
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

@Composable
private fun SharedTransitionScope.ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    uiItems: List<ChatVM.ChatUIItem>,
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
    val loadingState by rememberUpdatedState(loading)
    var isRecentScroll by remember { mutableStateOf(false) }
    var userScrolledUp by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val density = LocalDensity.current

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
    var previewingMemory by remember { mutableStateOf<me.rerere.ai.ui.UsedMemory?>(null) }
    var isMemoryLoading by remember { mutableStateOf(false) }

    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        return state.firstVisibleItemIndex == 0
    }

    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    ImeLazyListAutoScroller(lazyListState = state)

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        LaunchedEffect(state) {
            var previousFirstIndex = state.firstVisibleItemIndex
            var previousFirstOffset = state.firstVisibleItemScrollOffset
            snapshotFlow {
                Triple(state.isScrollInProgress, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }.collect { (isScrolling, firstIndex, firstOffset) ->
                if (isScrolling && loadingState) {
                    val scrolledUp = firstIndex > previousFirstIndex ||
                        (firstIndex == previousFirstIndex && firstOffset > previousFirstOffset)
                    if (scrolledUp && !userScrolledUp) {
                        userScrolledUp = true
                    }
                    if (state.layoutInfo.visibleItemsInfo.isAtBottom()) {
                        userScrolledUp = false
                    }
                }
                previousFirstIndex = firstIndex
                previousFirstOffset = firstOffset
            }
        }

        LaunchedEffect(loading) {
            if (!loading) userScrolledUp = false
        }

        LaunchedEffect(state) {
            snapshotFlow {
                state.isScrollInProgress || !state.layoutInfo.visibleItemsInfo.isAtBottom()
            }.collect { shouldShow ->
                isRecentScroll = shouldShow
            }
        }

        val needsPhantomLoadingTurn = loading && (
            uiItems.isEmpty() ||
                (uiItems.lastOrNull() as? ChatVM.ChatUIItem.Message)?.node?.currentMessage?.role == me.rerere.ai.core.MessageRole.USER
            )

        val displayItems = remember(uiItems, needsPhantomLoadingTurn, selecting) {
            val result = mutableListOf<ChatListDisplayItem>()
            val currentNodes = mutableListOf<MessageNode>()
            var lastShownTime: LocalDateTime? = null
            var lastMessageTime: LocalDateTime? = null

            fun flush() {
                if (currentNodes.isNotEmpty()) {
                    if (selecting) {
                        // 在多选模式下，不进行 Turn 合并，让每一条消息都拥有独立的选框
                        currentNodes.forEach { node ->
                            result.add(
                                ChatListDisplayItem.TurnGroup(
                                    MessageTurnGroup(
                                        nodes = listOf(node),
                                        role = node.currentMessage.role
                                    )
                                )
                            )
                        }
                    } else {
                        val turns = currentNodes.groupIntoTurns()
                        turns.forEach { turn ->
                            result.add(ChatListDisplayItem.TurnGroup(turn))
                        }
                    }
                    currentNodes.clear()
                }
            }

            uiItems.forEach { item ->
                when (item) {
                    is ChatVM.ChatUIItem.Message -> {
                        val msg = item.node.currentMessage
                        val msgTime = msg.createdAt

                        val shouldShowTime = lastShownTime == null ||
                            (msgTime.toInstant(TimeZone.currentSystemDefault()) -
                             (lastMessageTime ?: msgTime).toInstant(TimeZone.currentSystemDefault())) > 10.minutes

                        if (shouldShowTime) {
                            flush()
                            result.add(ChatListDisplayItem.Time(formatTime(msgTime)))
                            lastShownTime = msgTime
                        }
                        lastMessageTime = msgTime

                        if (!msg.skipContext) {
                            currentNodes.add(item.node)
                        }
                    }
                    is ChatVM.ChatUIItem.Separator -> {
                        flush()
                        result.add(ChatListDisplayItem.Separator(item.text))
                    }
                }
            }

            if (needsPhantomLoadingTurn) {
                val phantomNode = MessageNode.of(UIMessage.assistant(""))
                val msgTime = phantomNode.currentMessage.createdAt
                val shouldShowTime = lastShownTime == null ||
                        (msgTime.toInstant(TimeZone.currentSystemDefault()) -
                         (lastMessageTime ?: msgTime).toInstant(TimeZone.currentSystemDefault())) > 10.minutes

                if (shouldShowTime) {
                    flush()
                    result.add(ChatListDisplayItem.Time(formatTime(msgTime)))
                }
                currentNodes.add(phantomNode)
            }

            flush()
            result.asReversed()
        }

        // 精准跳转核心逻辑
        LaunchedEffect(displayItems, scrollToNodeId) {
            val targetId = scrollToNodeId ?: return@LaunchedEffect
            val realIndex = displayItems.indexOfFirst { item ->
                item is ChatListDisplayItem.TurnGroup && item.group.nodes.any { it.id == targetId }
            }
            if (realIndex >= 0) {
                if (instantScroll) {
                    // 反向布局优化：增加一个正向 offset 补偿。
                    // 这样系统会把该 item 往屏幕上方推，让它出现在视野中上部，接近微信体验。
                    val offsetPx = with(density) { 350.dp.toPx().toInt() }
                    state.scrollToItem(realIndex, scrollOffset = offsetPx)
                    // 给系统一帧时间完成布局测量
                    kotlinx.coroutines.yield()
                } else {
                    delay(200)
                    state.animateScrollToItem(realIndex)
                }
                onScrolledToNode()
            }
        }

        LazyColumn(
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp) +
                PaddingValues(top = 32.dp) +
                innerPadding +
                androidx.compose.foundation.layout.WindowInsets.ime.asPaddingValues(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "conversation_list"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .fillMaxSize(),
        ) {
            item(ScrollBottomKey) {
                Spacer(Modifier.fillMaxWidth().height(5.dp))
            }

            itemsIndexed(
                items = displayItems,
                key = { index, item ->
                    when (item) {
                        is ChatListDisplayItem.TurnGroup -> {
                            if (needsPhantomLoadingTurn && index == 0) "pending_assistant"
                            else item.group.firstNode.id
                        }
                        is ChatListDisplayItem.Separator -> "sep_${index}_${item.text.hashCode()}"
                        is ChatListDisplayItem.Time -> "time_${index}_${item.timeText.hashCode()}"
                    }
                },
            ) { index, item ->
                when (item) {
                    is ChatListDisplayItem.TurnGroup -> {
                        val group = item.group
                        Column {
                            val isSelected by remember(group.nodes.map { it.id }) {
                                derivedStateOf { group.nodes.any { selectedItems.contains(it.id) } }
                            }
                            ListSelectableItem(
                                isSelected = isSelected,
                                onSelectChange = { checked ->
                                    if (checked) group.nodes.forEach { if (!selectedItems.contains(it.id)) selectedItems.add(it.id) }
                                    else group.nodes.forEach { selectedItems.remove(it.id) }
                                },
                                enabled = selecting,
                            ) {
                                val isLastTurn = index == 0 || (needsPhantomLoadingTurn && index == 1)
                                val showRegenerate by remember(group.role, isLastTurn) {
                                    derivedStateOf {
                                        when (group.role) {
                                            me.rerere.ai.core.MessageRole.USER -> true
                                            else -> isLastTurn
                                        }
                                    }
                                }
                                ChatMessageTurn(
                                    group = group,
                                    isLastTurn = isLastTurn,
                                    onCitationClick = onCitationClick,
                                    model = group.lastNode.currentMessage.modelId?.let { settings.findModelById(it) },
                                    assistant = settings.getAssistantById(conversation.assistantId),
                                    loading = loading && isLastTurn,
                                    onRegenerate = { node -> onRegenerate(node.currentMessage) },
                                    onEdit = { node -> onEdit(node.currentMessage) },
                                    onDelete = { node -> onDelete(node.currentMessage) },
                                    onShare = { node ->
                                        selecting = true
                                        selectedItems.clear()
                                        selectedItems.add(node.id)
                                    },
                                    onUpdate = { onUpdateMessage(it) },
                                    onEditLorebookEntry = { entry ->
                                        navController.navigate(Screen.SettingLorebookDetail(entry.lorebookId, entry.entryId))
                                    },
                                    onMemoryClick = { memory ->
                                        scope.launch {
                                            isMemoryLoading = true
                                            previewingMemory = memory
                                            if (memory.memoryType == 2) {
                                                isMemoryLoading = false
                                                return@launch
                                            }
                                            val fullContent = onGetFullMemoryContent(memory.memoryId, memory.memoryType)
                                            previewingMemory = memory.copy(memoryContent = fullContent ?: "未找到完整内容")
                                            isMemoryLoading = false
                                        }
                                    },
                                    showRegenerate = showRegenerate,
                                    onTypingStateChange = { onTypingStateChange(group.firstNode.id, it) },
                                )
                            }

                            val truncateNode = group.nodes.find { node ->
                                conversation.messageNodes.indexOf(node) == conversation.truncateIndex - 1
                            }
                            if (truncateNode != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
                                ) {
                                    HorizontalDivider(modifier = Modifier.weight(1f))
                                    Text(
                                        text = stringResource(R.string.chat_page_clear_context),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    HorizontalDivider(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    is ChatListDisplayItem.Separator -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Text(text = item.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    is ChatListDisplayItem.Time -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = item.timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
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
                                val messages = uiItems.filterIsInstance<ChatVM.ChatUIItem.Message>()
                                    .map { it.node }
                                    .filter { it.id in selectedItems }
                                    .map { it.currentMessage }
                                onDeleteMessages(messages)
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Tooltip(tooltip = { Text(stringResource(R.string.action_favorite)) }) {
                        IconButton(
                            enabled = selectedItems.isNotEmpty(),
                            onClick = {
                                val messages = conversation.messageNodes
                                    .filter { it.id in selectedItems }
                                    .map { it.currentMessage }
                                onAddFavorite(messages)
                                selecting = false
                                selectedItems.clear()
                            }
                        ) { Icon(Icons.Rounded.Favorite, null) }
                    }

                    Tooltip(tooltip = { Text(stringResource(R.string.action_share)) }) {
                        FilledIconButton(
                            enabled = selectedItems.isNotEmpty(),
                            onClick = {
                                selecting = false
                                showExportSheet = true
                            }
                        ) { Icon(Icons.Rounded.Share, null) }
                    }
                }
            }

            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = { showExportSheet = false; selectedItems.clear() },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }.map { it.currentMessage }
            )

            previewingMemory?.let { memory ->
                MemoryPreviewDialog(memory = memory, isLoading = isMemoryLoading, onDismissRequest = { previewingMemory = null; isMemoryLoading = false })
            }

            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.getEffectiveDisplaySetting().showMessageJumper && !LocalScrollCaptureInProgress.current,
                onLeft = settings.getEffectiveDisplaySetting().messageJumperOnLeft,
                scope = scope,
                state = state
            )
        }
    }
}

private fun formatTime(dateTime: LocalDateTime): String {
    val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        .toLocalDateTime(TimeZone.currentSystemDefault())
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


private fun extractMatchingSnippet(text: String, query: String): String {
    if (query.isBlank()) return text
    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) return text
    val snippet = text.substring(matchIndex)
    return if (matchIndex > 0) "...$snippet" else snippet
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

@Composable
private fun SharedTransitionScope.ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (MessageNode) -> Unit,
    initialSearchQuery: String? = null,
    state: LazyListState,
    loading: Boolean,
) {
    var searchQuery by remember { mutableStateOf(initialSearchQuery ?: "") }
    val previewTopPadding = 20.dp
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer

    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        val visibleNodes = conversation.messageNodes.filter { !it.currentMessage.skipContext }
        if (searchQuery.isBlank()) conversation.messageNodes
        else visibleNodes.filter { it.currentMessage.toContentText().contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.padding(innerPadding).padding(top = previewTopPadding).fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.chat_page_search_placeholder)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Rounded.Close, null, modifier = Modifier.size(20.dp)) }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        LazyColumn(
            state = state,
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.sharedBounds(rememberSharedContentState(key = "conversation_list_preview"), animatedVisibilityScope).fillMaxWidth().weight(1f),
        ) {
            itemsIndexed(items = filteredMessages, key = { _, item -> item.id }) { _, node ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
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

@Composable
private fun BoxScope.MessageJumper(show: Boolean, onLeft: Boolean, scope: CoroutineScope, state: LazyListState) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(initialOffsetX = { if (onLeft) -it * 2 else it * 2 }),
        exit = slideOutHorizontally(targetOffsetX = { if (onLeft) -it * 2 else it * 2 })
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(onClick = { scope.launch { state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1) } }, shape = CircleShape, tonalElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(alpha = 0.65f)) { Icon(Icons.Rounded.KeyboardDoubleArrowUp, null, modifier = Modifier.padding(4.dp)) }
            Surface(onClick = { scope.launch { state.animateScrollToItem(state.firstVisibleItemIndex + 1) } }, shape = CircleShape, tonalElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(alpha = 0.65f)) { Icon(Icons.Rounded.KeyboardArrowUp, null, modifier = Modifier.padding(4.dp)) }
            Surface(onClick = { scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - 1).fastCoerceAtLeast(0)) } }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(alpha = 0.65f)) { Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.padding(4.dp)) }
            Surface(onClick = { scope.launch { state.animateScrollToItem(0) } }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp).copy(alpha = 0.65f)) { Icon(Icons.Rounded.KeyboardDoubleArrowDown, stringResource(R.string.chat_page_scroll_to_bottom), modifier = Modifier.padding(4.dp)) }
        }
    }
}

@Composable
fun MemoryPreviewDialog(memory: me.rerere.ai.ui.UsedMemory, isLoading: Boolean, onDismissRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(id = android.R.string.ok)) } },
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(imageVector = when(memory.memoryType) { 0 -> Icons.Rounded.Memory; 2 -> Icons.Rounded.Bolt; else -> Icons.Rounded.History }, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Text(text = if (memory.memoryType == 0) stringResource(R.string.context_sources_core_memory) else stringResource(R.string.context_sources_episodic_memory)) } },
        text = { Box(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(stiffness = 300f)).heightIn(min = 120.dp), contentAlignment = Alignment.Center) { if (isLoading) CircularProgressIndicator(modifier = Modifier.size(36.dp)) else Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) { Text(text = memory.memoryContent, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } } } }
    )
}
