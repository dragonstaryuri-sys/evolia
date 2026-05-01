package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.openUrl
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"

sealed class ChatListDisplayItem {
    data class TurnGroup(val group: MessageTurnGroup) : ChatListDisplayItem()
    data class Separator(val text: String) : ChatListDisplayItem()
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
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onJumpToMessage: (MessageNode) -> Unit = {},
    onGetFullMemoryContent: suspend (Int, Int) -> String? = { _, _ -> null },
) {
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
                    onJumpToMessage = onJumpToMessage,
                    animatedVisibilityScope = this@AnimatedContent,
                    initialSearchQuery = initialSearchQuery,
                )
            } else {
                ChatListNormal(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    uiItems = uiItems,
                    state = state,
                    loading = loading,
                    settings = settings,
                    recentlyRestoredNodeIds = recentlyRestoredNodeIds,
                    onRegenerate = onRegenerate,
                    onEdit = onEdit,
                    onForkMessage = onForkMessage,
                    onDelete = onDelete,
                    onUpdateMessage = onUpdateMessage,
                    onGetFullMemoryContent = onGetFullMemoryContent,
                    animatedVisibilityScope = this@AnimatedContent,
                )
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
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onGetFullMemoryContent: suspend (Int, Int) -> String?,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    var isRecentScroll by remember { mutableStateOf(false) }
    var userScrolledUp by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
                                    context.openUrl(url)
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
        val lastItem = lastOrNull() ?: return false
        if (lastItem.key == LoadingIndicatorKey || lastItem.key == ScrollBottomKey) {
            return true
        }
        val hasScrollBottom = any { it.key == ScrollBottomKey }
        if (hasScrollBottom) return true
        return !state.canScrollForward || (lastItem.offset + lastItem.size <= state.layoutInfo.viewportEndOffset + lastItem.size * 0.15 + 32)
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(lazyListState = state)

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // Detect user scrolling up to suppress auto-scroll
        LaunchedEffect(state) {
            var previousFirstIndex = state.firstVisibleItemIndex
            var previousFirstOffset = state.firstVisibleItemScrollOffset
            snapshotFlow {
                Triple(state.isScrollInProgress, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }.collect { (isScrolling, firstIndex, firstOffset) ->
                if (isScrolling && loadingState) {
                    val scrolledUp = firstIndex < previousFirstIndex ||
                        (firstIndex == previousFirstIndex && firstOffset < previousFirstOffset)
                    if (scrolledUp) {
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
            if (!loading) {
                userScrolledUp = false
            }
        }

        LaunchedEffect(state) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
                if (!state.isScrollInProgress && loadingState && !userScrolledUp) {
                    val targetIndex = state.layoutInfo.totalItemsCount - 1
                    if (targetIndex >= 0) {
                        state.animateScrollToItem(targetIndex)
                    }
                }
            }
        }

        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        val needsPhantomLoadingTurn = loading && (
            uiItems.isEmpty() ||
            (uiItems.lastOrNull() as? ChatVM.ChatUIItem.Message)?.node?.currentMessage?.role == me.rerere.ai.core.MessageRole.USER
        )

        val displayItems = remember(uiItems, needsPhantomLoadingTurn) {
            val result = mutableListOf<ChatListDisplayItem>()
            val currentNodes = mutableListOf<MessageNode>()

            fun flush() {
                if (currentNodes.isNotEmpty()) {
                    result.addAll(currentNodes.groupIntoTurns().map { ChatListDisplayItem.TurnGroup(it) })
                    currentNodes.clear()
                }
            }

            uiItems.forEach { item ->
                when (item) {
                    is ChatVM.ChatUIItem.Message -> {
                        if (!item.node.currentMessage.skipContext) {
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
                currentNodes.add(MessageNode.of(UIMessage.assistant("")))
            }

            flush()
            result
        }

        LazyColumn(
            state = state,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp) + PaddingValues(bottom = 32.dp) + innerPadding + androidx.compose.foundation.layout.WindowInsets.ime.asPaddingValues(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "conversation_list"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .fillMaxSize(),
        ) {
            itemsIndexed(
                items = displayItems,
                key = { index, item ->
                    when (item) {
                        is ChatListDisplayItem.TurnGroup -> {
                            if (needsPhantomLoadingTurn && index == displayItems.lastIndex) "pending_assistant"
                            else item.group.firstNode.id
                        }
                        is ChatListDisplayItem.Separator -> "sep_$index"
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
                                    if (checked) {
                                        group.nodes.forEach { selectedItems.add(it.id) }
                                    } else {
                                        group.nodes.forEach { selectedItems.remove(it.id) }
                                    }
                                },
                                enabled = selecting,
                            ) {
                                val isLastTurn = index == displayItems.lastIndex
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
                                    onRegenerate = { node ->
                                        onRegenerate(node.currentMessage)
                                    },
                                    onEdit = { node ->
                                        onEdit(node.currentMessage)
                                    },
                                    onFork = { node ->
                                        onForkMessage(node.currentMessage)
                                    },
                                    onDelete = { node ->
                                        onDelete(node.currentMessage)
                                    },
                                    onShare = { node ->
                                        selecting = true
                                        selectedItems.clear()
                                        // Find index in aggregated messages if possible, but fallback to conversation nodes
                                        val nodeIndex = conversation.messageNodes.indexOf(node)
                                        if (nodeIndex >= 0) {
                                            selectedItems.addAll(conversation.messageNodes
                                                .subList(0, nodeIndex + 1)
                                                .map { it.id })
                                        }
                                    },
                                    onUpdate = {
                                        onUpdateMessage(it)
                                    },
                                    onEditLorebookEntry = { entry ->
                                        navController.navigate(Screen.SettingLorebookDetail(entry.lorebookId, entry.entryId))
                                    },
                                    onModeClick = { mode ->
                                        navController.navigate(Screen.SettingModes(scrollToModeId = mode.modeId))
                                    },
                                    onMemoryClick = { memory ->
                                        scope.launch {
                                            isMemoryLoading = true
                                            previewingMemory = memory
                                            if (memory.memoryType == 2) {
                                                isMemoryLoading = false
                                                return@launch
                                            }
                                            // 2. 异步从数据库查完整的
                                            val fullContent = onGetFullMemoryContent(memory.memoryId, memory.memoryType)
                                            // 3. 如果查到了，更新弹窗显示完整内容
                                            if (fullContent != null) {
                                                previewingMemory = memory.copy(memoryContent = fullContent)
                                            }
                                            else {
                                                previewingMemory = memory.copy(memoryContent = "未找到完整内容")
                                            }
                                            isMemoryLoading = false
                                        }
                                    },
                                    showRegenerate = showRegenerate,
                                )
                            }
                            val truncateNode = group.nodes.find { node ->
                                conversation.messageNodes.indexOf(node) == conversation.truncateIndex - 1
                            }
                            if (truncateNode != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .padding(vertical = 8.dp)
                                        .fillMaxWidth()
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(Icons.Rounded.Close, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.TouchApp, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Check, null)
                        }
                    }
                }
            }

            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            previewingMemory?.let { memory ->
                MemoryPreviewDialog(
                    memory = memory,
                    isLoading = isMemoryLoading,
                    onDismissRequest = {
                        previewingMemory = null
                        isMemoryLoading = false
                    }
                )
            }

            val captureProgress = LocalScrollCaptureInProgress.current
            val effectiveDisplay = settings.getEffectiveDisplaySetting()

            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && effectiveDisplay.showMessageJumper && !captureProgress,
                onLeft = effectiveDisplay.messageJumperOnLeft,
                scope = scope,
                state = state
            )
        }
    }
}

private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    val snippet = text.substring(matchIndex)

    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            append(text.substring(startIndex, index))

            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
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
) {
    var searchQuery by remember { mutableStateOf(initialSearchQuery ?: "") }
    val previewTopPadding = 20.dp

    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        val visibleNodes = conversation.messageNodes.filter { !it.currentMessage.skipContext }
        if (searchQuery.isBlank()) {
            conversation.messageNodes
        } else {
            visibleNodes.filter { node ->
                node.currentMessage.toText().contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(innerPadding)
            .padding(top = previewTopPadding)
            .fillMaxSize(),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.chat_page_search_placeholder)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "conversation_list"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.id },
            ) { _, node ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(node)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                    highlightColor = highlightColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardDoubleArrowUp,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardDoubleArrowDown,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}


@Composable
fun MemoryPreviewDialog(    memory: me.rerere.ai.ui.UsedMemory,
                            isLoading: Boolean,
                            onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(id = android.R.string.ok))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = when(memory.memoryType) {
                        0 -> Icons.Rounded.Memory
                        2 -> Icons.Rounded.Bolt // 临时/增强记忆用闪电
                        else -> Icons.Rounded.History
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (memory.memoryType == 0)
                        stringResource(R.string.context_sources_core_memory)
                    else
                        stringResource(R.string.context_sources_episodic_memory)
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = androidx.compose.animation.core.spring(stiffness = 300f))
                    .heightIn(min = 120.dp), // 这里的 heightIn 是正确写法
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = memory.memoryContent,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        memory.activationReason?.let { reason ->
                            Text(
                                text = stringResource(R.string.context_sources_activation_reason, reason),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    )
}
