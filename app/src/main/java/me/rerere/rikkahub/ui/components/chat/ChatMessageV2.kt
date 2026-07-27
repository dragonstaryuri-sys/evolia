package me.rerere.rikkahub.ui.components.chat

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.minutes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantAffectScope
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.core.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.message.ChatMessageActionButtons
import me.rerere.rikkahub.ui.components.message.ChatMessageActionsSheet
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.common.jsonPrimitiveOrNull
import me.rerere.rikkahub.ui.components.message.ChatMessageCopySheet
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.BuildConfig

// WeChat Colors
private val WeChatUserGreen = Color(0xFF95EC69)
private val WeChatAiWhite = Color(0xFFFFFFFF)
private val WeChatTextBlack = Color(0xFF191919)

/**
 * Represents a group of consecutive messages from the same role.
 * For assistant messages, this groups all consecutive assistant nodes together.
 */
data class MessageTurnGroup(
    val nodes: List<MessageNode>,
    val role: MessageRole
) {
    val firstNode get() = nodes.first()
    val lastNode get() = nodes.last()

    /** Node with the most message versions - used for version switching controls */
    val nodeWithMostVersions get() = nodes.maxByOrNull { it.messages.size } ?: lastNode

    /** The active versionTag from the first node's current message */
    val activeVersionTag: String? get() = firstNode.currentMessage.versionTag

    /**
     * Nodes filtered to only include those with a message matching the active versionTag.
     */
    val filteredNodes: List<MessageNode>
        get() {
            val tag = activeVersionTag
            return nodes.mapNotNull { node ->
                if (node.currentMessage.versionTag == tag) {
                    return@mapNotNull node
                }
                val index = node.messages.indexOfLast { it.versionTag == tag }
                if (index != -1) {
                    node.copy(selectIndex = index)
                } else {
                    val lastUntaggedIndex = node.messages.indexOfLast { it.versionTag == null }
                    if (lastUntaggedIndex != -1) {
                        node.copy(selectIndex = lastUntaggedIndex)
                    } else null
                }
            }
        }

    /** All message parts from filtered nodes in the group */
    val allParts: List<UIMessagePart> get() = filteredNodes.flatMap { it.currentMessage.parts }

    /** Combined token usage for filtered messages in the group */
    val combinedUsage: TokenUsage?
        get() {
            val usages = filteredNodes.mapNotNull { it.currentMessage.usage }
            if (usages.isEmpty()) return null
            return TokenUsage(
                promptTokens = usages.sumOf { it.promptTokens },
                completionTokens = usages.sumOf { it.completionTokens },
                totalTokens = usages.sumOf { it.totalTokens },
                cachedTokens = usages.sumOf { it.cachedTokens }
            )
        }

    /** Combined generation duration for filtered messages */
    val combinedGenerationDurationMs: Long?
        get() {
            val durations = filteredNodes.mapNotNull { it.currentMessage.generationDurationMs }
            return if (durations.isNotEmpty()) durations.sum() else null
        }
}

/**
 * Group consecutive messages by role into MessageTurnGroups.
 * TOOL messages are treated as part of the ASSISTANT turn (they're tool results).
 */
fun List<MessageNode>.groupIntoTurns(): List<MessageTurnGroup> {
    if (isEmpty()) return emptyList()
    val visibleNodes = this.filter { !it.currentMessage.skipContext }
    if (visibleNodes.isEmpty()) return emptyList()
    val chronologicalNodes = visibleNodes.reversed()
    val groups = mutableListOf<MessageTurnGroup>()
    var currentGroup = mutableListOf<MessageNode>()
    var currentGroupRole: MessageRole? = null

    fun getGroupingRole(role: MessageRole): MessageRole = when (role) {
        MessageRole.TOOL -> MessageRole.ASSISTANT
        else -> role
    }

    chronologicalNodes.forEach { node ->
        val nodeRole = node.currentMessage.role
        val logicalRole = getGroupingRole(nodeRole)
        val isTimeBreak = if (currentGroup.isNotEmpty()) {
            val lastNodeTime = currentGroup.last().currentMessage.createdAt
            val currentNodeTime = node.currentMessage.createdAt
            (currentNodeTime.toInstant(TimeZone.currentSystemDefault()) -
                lastNodeTime.toInstant(TimeZone.currentSystemDefault())) > 5.minutes
        } else false

        if ((logicalRole != currentGroupRole || isTimeBreak) && currentGroup.isNotEmpty()) {
            groups.add(MessageTurnGroup(currentGroup.toList(), currentGroupRole!!))
            currentGroup = mutableListOf()
        }
        currentGroup.add(node)
        currentGroupRole = logicalRole
    }


    if (currentGroup.isNotEmpty() && currentGroupRole != null) {
        groups.add(MessageTurnGroup(currentGroup.toList(), currentGroupRole!!))
    }

    return groups.asReversed()
}

/**
 * Build timeline entries from message parts.
 */
private fun buildTimelineEntries(parts: List<UIMessagePart>): List<TimelineEntry> {
    val entries = mutableListOf<TimelineEntry>()
    val memoryTools = setOf("create_memory", "edit_memory", "delete_memory")

    val toolResults = parts.filterIsInstance<UIMessagePart.ToolResult>()
        .associateBy { it.toolCallId }

    parts.forEach { part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                val durationMs = if (part.finishedAt != null) {
                    (part.finishedAt!! - part.createdAt).inWholeMilliseconds
                } else 0L

                entries.add(
                    TimelineEntry.Reasoning(
                        id = "reasoning_${entries.size}",
                        content = part.reasoning,
                        durationMs = durationMs,
                        title = null
                    )
                )
            }

            is UIMessagePart.ToolCall -> {
                val result = toolResults[part.toolCallId]
                if (part.toolName in memoryTools) {
                    entries.add(buildMemoryTimelineEntry(part, result))
                } else {
                    val argumentsJson = result?.arguments ?: parseJsonObjectOrNull(part.arguments)
                    val resultJson = result?.content
                    entries.add(
                        TimelineEntry.ToolCall(
                            id = "tool_${part.toolCallId}",
                            toolName = part.toolName,
                            displayName = getToolDisplayName(part.toolName),
                            argumentsText = part.arguments.take(200),
                            resultText = result?.content?.toString()?.take(500),
                            argumentsJson = argumentsJson,
                            resultJson = resultJson,
                            isLoading = result == null
                        )
                    )
                }
            }

            else -> {}
        }
    }

    return entries
}

private fun buildMemoryTimelineEntry(
    call: UIMessagePart.ToolCall,
    result: UIMessagePart.ToolResult?
): TimelineEntry.MemoryAction {
    val operation = when (call.toolName) {
        "create_memory" -> MemoryOperation.CREATE
        "edit_memory" -> MemoryOperation.EDIT
        "delete_memory" -> MemoryOperation.DELETE
        else -> MemoryOperation.CREATE
    }
    val resultObj = result?.content as? JsonObject
    val argsObj = (result?.arguments as? JsonObject) ?: parseJsonObjectOrNull(call.arguments)

    val memoryId = resultObj?.get("id")?.jsonPrimitiveOrNull?.intOrNull
        ?: argsObj?.get("id")?.jsonPrimitiveOrNull?.intOrNull
    val content = resultObj?.get("content")?.jsonPrimitiveOrNull?.contentOrNull
        ?: argsObj?.get("content")?.jsonPrimitiveOrNull?.contentOrNull
    val previousContent = resultObj?.get("before_content")?.jsonPrimitiveOrNull?.contentOrNull
    val memoryType = resultObj?.get("type")?.jsonPrimitiveOrNull?.intOrNull
    val timestamp = resultObj?.get("timestamp")?.jsonPrimitiveOrNull?.longOrNull

    return TimelineEntry.MemoryAction(
        id = "memory_${call.toolCallId}",
        toolName = call.toolName,
        operation = operation,
        memoryId = memoryId,
        content = content,
        previousContent = previousContent,
        memoryType = memoryType,
        timestamp = timestamp,
        isLoading = result == null
    )
}

private fun parseJsonObjectOrNull(raw: String): JsonObject? {
    return runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
}

/**
 * Get display name for a tool.
 */
private fun getToolDisplayName(toolName: String): String {
    return when (toolName) {
        "search_web" -> "Searching web"
        "scrape_web" -> "Reading page"
        "eval_python" -> "Running Python"
        "pip_install" -> "Installing packages"
        "write_sandbox_file" -> "Writing file"
        "read_sandbox_file" -> "Reading file"
        "list_sandbox_files" -> "Listing files"
        "delete_sandbox_file" -> "Deleting file"
        "create_memory" -> "Creating memory"
        "edit_memory" -> "Editing memory"
        "delete_memory" -> "Deleting memory"
        else -> toolName.replace("_", " ").replaceFirstChar { it.uppercase() }
    }
}

/**
 * Determine the current activity state from message parts.
 */
@Composable
private fun deriveActivityState(
    parts: List<UIMessagePart>,
    loading: Boolean,
    autoCollapseReasoning: Boolean
): ActivityState {
    val toolResults = parts.filterIsInstance<UIMessagePart.ToolResult>()
        .associateBy { it.toolCallId }

    val reasoningParts = parts.filterIsInstance<UIMessagePart.Reasoning>()
    val toolCalls = parts.filterIsInstance<UIMessagePart.ToolCall>()

    val lastToolIndex = parts.indexOfLast { it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult }
    val hasRecentText = parts.drop(lastToolIndex + 1)
        .filterIsInstance<UIMessagePart.Text>()
        .any { it.text.isNotBlank() }

    if (!loading) {
        val totalReasoningMs = reasoningParts.sumOf { r ->
            if (r.finishedAt != null) {
                (r.finishedAt!! - r.createdAt).inWholeMilliseconds
            } else 0L
        }

        val toolCategories = toolCalls.map { categorizeToolName(it.toolName) }.distinct()

        // ✨ Reasoning only contributes to activity pill if auto-collapse is enabled
        // If auto-collapse is OFF, we hide the finished pill because it's shown in message flow
        val hasReasoning = totalReasoningMs > 0 && autoCollapseReasoning
        val hasTools = toolCategories.isNotEmpty()

        val activityCount = (if (hasReasoning) 1 else 0) + toolCategories.size

        return when {
            activityCount == 0 -> ActivityState.Hidden
            activityCount == 1 && hasReasoning -> ActivityState.CompletedSingle(
                type = ActivityType.REASONING,
                durationMs = totalReasoningMs
            )

            activityCount == 1 && hasTools -> ActivityState.CompletedSingle(
                type = toolCategories.first(),
                toolName = toolCalls.first().toolName,
                displayName = getToolDisplayName(toolCalls.first().toolName),
                count = toolCalls.size
            )

            else -> ActivityState.CompletedMultiple(
                reasoningDurationMs = if (hasReasoning) totalReasoningMs else null,
                toolsUsed = toolCalls.map { it.toolName }.distinct()
            )
        }
    }

    val activeReasoning = reasoningParts.lastOrNull { it.finishedAt == null }
    if (activeReasoning != null) {
        return ActivityState.Reasoning(startTimeMs = activeReasoning.createdAt.toEpochMilliseconds())
    }

    val activeTool = toolCalls.lastOrNull { toolResults[it.toolCallId] == null }
    if (activeTool != null) {
        return ActivityState.ToolUse(
            toolName = activeTool.toolName,
            displayName = getToolDisplayName(activeTool.toolName),
            startTimeMs = System.currentTimeMillis()
        )
    }

    if (hasRecentText) {
        return ActivityState.Replying
    }

    return ActivityState.Waiting
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageTurn(
    group: MessageTurnGroup,
    isLastTurn: Boolean,
    onCitationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    onRegenerate: (MessageNode) -> Unit = {},
    onEdit: (MessageNode) -> Unit = {},
    onShare: (MessageNode) -> Unit = {},
    onDelete: (MessageNode) -> Unit = {},
    onUpdate: (MessageNode) -> Unit = {},
    showRegenerate: Boolean,
    onEditLorebookEntry: ((me.rerere.ai.ui.UsedLorebookEntry) -> Unit)? = null,
    onModeClick: ((me.rerere.ai.ui.UsedMode) -> Unit)? = null,
    onMemoryClick: ((me.rerere.ai.ui.UsedMemory) -> Unit)? = null,
    onTypingStateChange: (Boolean) -> Unit = {},
) {
    val settings = LocalSettings.current
    val navController = LocalNavController.current
    val effectiveDisplay = settings.getEffectiveDisplaySetting(assistant)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * effectiveDisplay.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * effectiveDisplay.fontSizeRatio
    )
    val configuration = LocalConfiguration.current
    val wechatMode = effectiveDisplay.wechatMode

    // WeChat mode: max width is screen width minus avatar(40) and margins
    val maxBubbleWidth = if (wechatMode) {
        (configuration.screenWidthDp - 88).dp
    } else {
        (configuration.screenWidthDp * 0.85f).dp
    }

    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    var showTimelineSheet by remember { mutableStateOf(false) }
    var initialTimelineExpandedType by remember { mutableStateOf<ActivityType?>(null) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var showUserToolbar by remember { mutableStateOf(false) }

    // ✨ Pass autoCloseThinking to activity state derivation
    val activityState = deriveActivityState(
        group.allParts,
        loading && isLastTurn,
        effectiveDisplay.autoCloseThinking
    )
    val timelineEntries = buildTimelineEntries(group.allParts)

    // Current focused/clicked node for actions
    var selectedNode by remember(group) { mutableStateOf<MessageNode?>(null) }

    val actionTargetNode = selectedNode ?: remember(group) {
        group.filteredNodes
            .asReversed()
            .firstOrNull { node ->
                node.currentMessage.parts.any { part ->
                    part is UIMessagePart.Text || part is UIMessagePart.Reasoning || part is UIMessagePart.Thinking
                }
            }
            ?: group.filteredNodes.lastOrNull()
            ?: group.lastNode
    }

    val onAvatarClick = {
        assistant?.id?.let { id ->
            navController.navigate(Screen.AssistantDetail(id = id.toString()))
        }
        Unit
    }

    ProvideTextStyle(textStyle) {
        when (group.role) {
            MessageRole.USER -> {
                val context = androidx.compose.ui.platform.LocalContext.current
                UserMessageTurn(
                    group = group,
                    assistant = assistant,
                    isLastTurn = isLastTurn,
                    maxWidth = maxBubbleWidth,
                    showToolbar = showUserToolbar,
                    onToggleToolbar = {
                        selectedNode = it
                        showUserToolbar = !showUserToolbar
                    },
                    onBubbleClick = { node ->
                        selectedNode = node
                        if (wechatMode) {
                            showActionsSheet = true
                        } else {
                            showUserToolbar = !showUserToolbar
                        }
                    },
                    onCopy = {
                        val node = selectedNode ?: group.lastNode
                        context.copyMessageToClipboard(node.currentMessage)
                    },
                    onRegenerate = {
                        val node = selectedNode ?: group.lastNode
                        onRegenerate(node)
                    },
                    onOpenMenu = { showActionsSheet = true },
                    showRegenerate = showRegenerate,
                    enableHaptics = effectiveDisplay.enableUIHaptics,
                    wechatMode = wechatMode,
                    userAvatar = effectiveDisplay.userAvatar,
                    userNickname = effectiveDisplay.userNickname,
                    modifier = modifier
                )
            }

            MessageRole.ASSISTANT -> {
                AssistantMessageTurn(
                    group = group,
                    assistant = assistant,
                    model = model,
                    activityState = activityState,
                    loading = loading && isLastTurn,
                    isLastTurn = isLastTurn,
                    actionsExpanded = actionsExpanded,
                    maxWidth = maxBubbleWidth,
                    showTokenUsage = effectiveDisplay.showTokenUsage,
                    showAssistantBubbles = effectiveDisplay.showAssistantBubbles,
                    enableHaptics = effectiveDisplay.enableUIHaptics,
                    onCitationClick = onCitationClick,
                    onActivityPillClick = { type ->
                        initialTimelineExpandedType = type
                        showTimelineSheet = true
                    },
                    onBubbleClick = { node ->
                        selectedNode = node
                        if (wechatMode || isLastTurn) {
                            showActionsSheet = true
                        } else {
                            actionsExpanded = !actionsExpanded
                        }
                    },
                    onAvatarClick = onAvatarClick,
                    onRegenerate = { onRegenerate(group.lastNode) },
                    onUpdate = onUpdate,
                    onOpenActionSheet = { showActionsSheet = true },
                    showRegenerate = showRegenerate,
                    onEditLorebookEntry = onEditLorebookEntry,
                    onModeClick = onModeClick,
                    onMemoryClick = onMemoryClick,
                    wechatMode = wechatMode,
                    onTypingStateChange = onTypingStateChange,
                    modifier = modifier
                )
            }

            else -> { /* System messages not rendered */
            }
        }
    }

    // Sheets
    if (showTimelineSheet) {
        ActivityTimelineSheet(
            entries = timelineEntries,
            onDismissRequest = { showTimelineSheet = false },
            initialExpandedType = initialTimelineExpandedType,
            assistantId = assistant?.id?.toString()
        )
    }

    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = actionTargetNode.currentMessage,
            onEdit = { onEdit(actionTargetNode) },
            onDelete = { onDelete(actionTargetNode) },
            onShare = { onShare(actionTargetNode) },
            model = model,
            onSelectAndCopy = { showSelectCopySheet = true },
            onWebViewPreview = { },
            onDismissRequest = { showActionsSheet = false }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = actionTargetNode.currentMessage,
            onDismissRequest = { showSelectCopySheet = false }
        )
    }
}

@Composable
private fun WeChatRegenerateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = "Regenerate",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * User message turn - right-aligned stacked bubbles.
 */
@Composable
private fun UserMessageTurn(
    group: MessageTurnGroup,
    assistant: Assistant?,
    isLastTurn: Boolean,
    maxWidth: androidx.compose.ui.unit.Dp,
    showToolbar: Boolean,
    onToggleToolbar: (MessageNode) -> Unit,
    onBubbleClick: (MessageNode) -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenMenu: () -> Unit,
    showRegenerate: Boolean,
    enableHaptics: Boolean,
    wechatMode: Boolean,
    userAvatar: Avatar,
    userNickname: String,
    modifier: Modifier = Modifier
) {
    val haptics = rememberPremiumHaptics(enabled = enableHaptics)
    val navController = LocalNavController.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(if (wechatMode) 12.dp else 2.dp)
        ) {
            val allImages = group.nodes.flatMap { node ->
                node.currentMessage.parts.filterIsInstance<UIMessagePart.Image>()
            }

            if (allImages.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    allImages.fastForEach { image ->
                        ZoomableAsyncImage(
                            model = image.url,
                            contentDescription = null,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .height(72.dp)
                        )
                    }
                }
            }

            group.filteredNodes.forEachIndexed { nodeIndex, node ->
                val textParts = node.currentMessage.parts.filterIsInstance<UIMessagePart.Text>()
                textParts.forEachIndexed { partIndex, part ->
                    val isFirst = nodeIndex == 0 && partIndex == 0
                    val isLastPart = nodeIndex == group.filteredNodes.lastIndex && partIndex == textParts.lastIndex
                    val totalBubbles = group.filteredNodes.sumOf { n ->
                        n.currentMessage.parts.filterIsInstance<UIMessagePart.Text>().size
                    }
                    val position = if (wechatMode) BubblePosition.SINGLE else when {
                        totalBubbles == 1 -> BubblePosition.SINGLE
                        isFirst -> BubblePosition.FIRST
                        isLastPart -> BubblePosition.LAST
                        else -> BubblePosition.MIDDLE
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (wechatMode && isLastTurn && isLastPart && showRegenerate) {
                            WeChatRegenerateButton(onClick = onRegenerate)
                        }

                        GroupedMessageBubble(
                            position = position,
                            role = BubbleRole.USER,
                            modifier = Modifier.widthIn(max = maxWidth),
                            containerColor = if (wechatMode) WeChatUserGreen else null,
                            contentColor = if (wechatMode) WeChatTextBlack else null,
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                onBubbleClick(node)
                            }
                        ) {
                            MarkdownBlock(
                                content = part.text.replaceRegexes(
                                    assistant = assistant,
                                    scope = AssistantAffectScope.USER,
                                    visual = true,
                                ),
                                onClickCitation = {}
                            )
                        }

                        if (wechatMode) {
                            Spacer(Modifier.width(8.dp))
                            UIAvatar(
                                name = userNickname,
                                value = userAvatar,
                                modifier = Modifier.size(40.dp),
                                onClick = {
                                    navController.navigate(Screen.SettingUserProfile)
                                }
                            )
                        }
                    }
                }
            }

            // Toolbar
            AnimatedVisibility(
                visible = showToolbar,
                enter = expandVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeOut()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { haptics.perform(HapticPattern.Pop); onCopy() }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (showRegenerate) {
                        Box(
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .clickable { haptics.perform(HapticPattern.Pop); onRegenerate() }
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Regenerate",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { haptics.perform(HapticPattern.Pop); onOpenMenu() }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = "More Options",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Assistant message turn - name + avatar at top, activity pill, stacked bubbles.
 */
@Composable
private fun AssistantMessageTurn(
    group: MessageTurnGroup,
    assistant: Assistant?,
    model: Model?,
    activityState: ActivityState,
    loading: Boolean,
    isLastTurn: Boolean,
    actionsExpanded: Boolean,
    maxWidth: androidx.compose.ui.unit.Dp,
    showTokenUsage: Boolean,
    showAssistantBubbles: Boolean,
    enableHaptics: Boolean,
    onCitationClick: (String) -> Unit,
    onActivityPillClick: (ActivityType?) -> Unit,
    onBubbleClick: (MessageNode) -> Unit,
    onAvatarClick: () -> Unit,
    onRegenerate: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onOpenActionSheet: () -> Unit,
    showRegenerate: Boolean,
    onEditLorebookEntry: ((me.rerere.ai.ui.UsedLorebookEntry) -> Unit)?,
    onModeClick: ((me.rerere.ai.ui.UsedMode) -> Unit)?,
    onMemoryClick: ((me.rerere.ai.ui.UsedMemory) -> Unit)?,
    wechatMode: Boolean,
    onTypingStateChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settings = LocalSettings.current
    val effectiveDisplay = settings.getEffectiveDisplaySetting(assistant)
    val showIcon = effectiveDisplay.showModelIcon
    val showModelName = effectiveDisplay.showModelName
    val haptics = rememberPremiumHaptics(enabled = enableHaptics)
    val showName = showModelName && (!isLastTurn || !loading) && !wechatMode
    val nameAlpha by animateFloatAsState(
        targetValue = if (showName) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f),
        label = "assistant_name_alpha"
    )

    val avatarName = assistant?.name?.ifEmpty { null } ?: model?.displayName ?: "Assistant"
    val avatarValue = assistant?.avatar ?: Avatar.Dummy
    val hasInterestingActivity = activityState !is ActivityState.Hidden && !wechatMode

    // ✨ Modified: Never include Reasoning parts in WeChat mode
    val allTextBubbles = remember(group.filteredNodes, wechatMode, effectiveDisplay.autoCloseThinking) {
        mutableListOf<Pair<MessageNode, UIMessagePart>>().apply {
            group.filteredNodes.forEach { node ->
                node.currentMessage.parts.forEach { part ->
                    if (part is UIMessagePart.Text && part.text.isNotBlank()) {
                        if (wechatMode) {
                            // 微信模式：使用正则动态切分
                            val regex = Regex("[，。！？~\\n]|[,!?~\\n]")
                            var lastIndex = 0
                            // 查找所有的分隔符位置
                            val matches = regex.findAll(part.text).toList()

                            matches.forEach { match ->
                                // 截取分隔符之前的文本
                                val sentence = part.text.substring(lastIndex, match.range.first).trim()
                                if (sentence.isNotBlank()) {
                                    add(node to UIMessagePart.Text(sentence))
                                }
                                lastIndex = match.range.last + 1
                            }

                            // 处理最后剩下的尾巴
                            val remainder = part.text.substring(lastIndex).trim()
                            if (remainder.isNotBlank()) {
                                add(node to UIMessagePart.Text(remainder))
                            }
                        } else {
                            // 普通模式：直接添加
                            add(node to part)
                        }
                    } else if (part is UIMessagePart.Reasoning && !effectiveDisplay.autoCloseThinking && !wechatMode) {
                        // ✨ Reasoning is only added if auto-collapse is OFF AND NOT in WeChat mode
                        if (part.reasoning.isNotBlank()) {
                            add(node to part)
                        }
                    }
                }
            }
        }
    }

    // --- WeChat Mode Dynamics ---
    val currentBubbles by rememberUpdatedState(allTextBubbles)
    val isAiLoading by rememberUpdatedState(loading)
    // ✨ Fix: Add loading to the remember keys to reset displayedCount when regeneration starts
    var displayedCount by remember(group.firstNode.id, wechatMode, loading) {
        // 修改为：如果是最后一轮或者正在加载，我们倾向于从 0 开始跑一遍动画
        val shouldAnimate = wechatMode && (isLastTurn || loading)
        mutableIntStateOf(if (shouldAnimate) 0 else allTextBubbles.size)
    }

    // ✨ Fix: Add loading to the LaunchedEffect keys to ensure it restarts on regeneration
    LaunchedEffect(group.firstNode.id, wechatMode, loading) {
        if (!wechatMode) {
            onTypingStateChange(false)
            return@LaunchedEffect
        }

        try {
            if (displayedCount == 0) {
                // 等待条件：直到 AI 真正开始产生内容，或者 activityState 进入了非空闲状态
                while (currentBubbles.isEmpty() && isAiLoading) {
                    delay(100)
                }

                // 当跳出上面的循环，说明 AI 已经开始响应了
                if (currentBubbles.isNotEmpty()) {
                    displayedCount = 1
                }
            }
            // Initial typing notification
            onTypingStateChange(isAiLoading || displayedCount < currentBubbles.size)

            while (true) {
                val latest = currentBubbles
                val totalAvailable = latest.size

                onTypingStateChange(displayedCount < totalAvailable || isAiLoading)

                if (displayedCount < totalAvailable) {
                    // ✨ Extract text correctly based on part type
                    val prevPart = latest.getOrNull(displayedCount - 1)?.second
                    val prevText = when (prevPart) {
                        is UIMessagePart.Text -> prevPart.text
                        is UIMessagePart.Reasoning -> prevPart.reasoning
                        else -> ""
                    }
                    // 打字速度建议：每个字 100ms 基础延迟
                    val delayTime = (prevText.length * 200L + 100L).coerceIn(500L, 3000L)
                    delay(delayTime)
                    displayedCount++
                } else {
                    // ✨ 核心修复：只有当“不再加载”且“所有气泡都显示完了”才退出
                    // 这样即使 loading 信号短暂断开，动画也会把剩下的气泡跑完
                    if (!isAiLoading) break
                    delay(200)
                }
            }
        } finally {
            onTypingStateChange(false)
        }
    }

    val elementSpacing = 4.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(dampingRatio = 0.7f, stiffness = 320f)),
        verticalArrangement = Arrangement.spacedBy(if (wechatMode) 12.dp else if (showAssistantBubbles) elementSpacing else 3.dp)
    ) {
        if (showAssistantBubbles) {
            if (hasInterestingActivity) {
                if (showModelName) {
                    Text(
                        text = avatarName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .graphicsLayer { alpha = nameAlpha }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onAvatarClick
                            )
                    )
                }
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(elementSpacing)) {
                    if (showIcon) {
                        UIAvatar(
                            name = avatarName,
                            modifier = Modifier.size(36.dp),
                            value = avatarValue,
                            loading = loading,
                            onClick = onAvatarClick
                        )
                    }
                    ActivityPillRow(
                        state = activityState,
                        onClick = { haptics.perform(HapticPattern.Pop); onActivityPillClick(it) },
                        connectsToBubbleBelow = false,
                        modifier = Modifier.height(36.dp)
                    )
                }
            } else if (!wechatMode) {
                if (showIcon || showModelName) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (showIcon) {
                            UIAvatar(
                                name = avatarName,
                                modifier = Modifier.size(36.dp),
                                value = avatarValue,
                                loading = loading,
                                onClick = onAvatarClick
                            )
                        }
                        if (showModelName) {
                            Text(
                                text = avatarName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .graphicsLayer { alpha = nameAlpha }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onAvatarClick
                                    )
                            )
                        }
                    }
                }
            }

            val bubblesToShow = if (wechatMode) allTextBubbles.take(displayedCount) else allTextBubbles
            bubblesToShow.forEachIndexed { index, (node, part) ->
                val isLastBubble = index == allTextBubbles.lastIndex
                val position = if (wechatMode) BubblePosition.SINGLE else when {
                    allTextBubbles.size == 1 -> BubblePosition.SINGLE
                    index == 0 -> BubblePosition.FIRST
                    isLastBubble -> BubblePosition.LAST
                    else -> BubblePosition.MIDDLE
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    if (wechatMode) {
                        UIAvatar(
                            name = avatarName,
                            modifier = Modifier.size(40.dp),
                            value = avatarValue,
                            onClick = onAvatarClick
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    if (part is UIMessagePart.Reasoning) {
                        // ✨ Render reasoning as a flow box when not collapsed (already excluded in WeChat mode)
                        ReasoningFlowBlock(
                            content = part.reasoning,
                            modifier = Modifier
                                .widthIn(max = maxWidth)
                                .padding(vertical = 4.dp),
                            onClick = { onBubbleClick(node) }
                        )
                    } else {
                        GroupedMessageBubble(
                            position = position,
                            role = BubbleRole.ASSISTANT,
                            modifier = Modifier.widthIn(max = maxWidth),
                            containerColor = if (wechatMode) WeChatAiWhite else null,
                            contentColor = if (wechatMode) WeChatTextBlack else null,
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                onBubbleClick(node)
                            }
                        ) {
                            val contentText = if (part is UIMessagePart.Text) part.text else ""
                            MarkdownBlock(
                                content = contentText.replaceRegexes(
                                    assistant = assistant,
                                    scope = AssistantAffectScope.ASSISTANT,
                                    visual = true
                                ),
                                onClickCitation = { onCitationClick(it) }
                            )
                        }
                    }

                    if (wechatMode && isLastTurn && isLastBubble && showRegenerate && !loading) {
                        WeChatRegenerateButton(onClick = onRegenerate)
                    }
                }
            }
        } else {
            // Non-bubble mode
            if (!wechatMode) {
                if (showIcon || showModelName) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (showIcon) {
                            UIAvatar(
                                name = avatarName,
                                modifier = Modifier.size(36.dp),
                                value = avatarValue,
                                loading = loading,
                                onClick = onAvatarClick
                            )
                        }
                        if (showModelName) {
                            Text(
                                text = avatarName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .graphicsLayer { alpha = nameAlpha }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onAvatarClick
                                    )
                            )
                        }
                    }
                }
                if (activityState !is ActivityState.Hidden) {
                    ActivityPillRow(
                        state = activityState,
                        onClick = { haptics.perform(HapticPattern.Pop); onActivityPillClick(it) },
                        connectsToBubbleBelow = false,
                        modifier = Modifier.height(36.dp)
                    )
                }
            }

            val bubblesToShow = if (wechatMode) allTextBubbles.take(displayedCount) else allTextBubbles
            bubblesToShow.forEachIndexed { index, (node, part) ->
                val isLastBubble = index == allTextBubbles.lastIndex

                // ✨ Determine what to render based on part type
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (wechatMode) {
                        UIAvatar(
                            name = avatarName,
                            modifier = Modifier.size(40.dp),
                            value = avatarValue,
                            onClick = onAvatarClick
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    if (part is UIMessagePart.Reasoning) {
                        ReasoningFlowBlock(
                            content = part.reasoning,
                            modifier = Modifier.padding(vertical = 4.dp),
                            onClick = { onBubbleClick(node) }
                        )
                    } else {
                        val contentText = if (part is UIMessagePart.Text) part.text else ""
                        MarkdownBlock(
                            content = contentText.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.ASSISTANT,
                                visual = true
                            ),
                            onClickCitation = { id -> onCitationClick(id) },
                            modifier = Modifier.clickable {
                                haptics.perform(HapticPattern.Pop)
                                onBubbleClick(node)
                            }
                        )
                    }

                    if (wechatMode && isLastTurn && isLastBubble && showRegenerate && !loading) {
                        WeChatRegenerateButton(onClick = onRegenerate)
                    }
                }
            }
        }

        if (showTokenUsage && group.combinedUsage != null && !loading && (!wechatMode || BuildConfig.DEBUG)) {
            TokenStatisticsInline(
                usage = group.combinedUsage!!,
                generationDurationMs = group.combinedGenerationDurationMs
            )
        }

        val allBubblesShown = !wechatMode || (displayedCount >= allTextBubbles.size)
        val showActions = !loading && allBubblesShown && (isLastTurn || actionsExpanded || (wechatMode && BuildConfig.DEBUG))
        AnimatedVisibility(
            visible = showActions,
            enter = expandVertically(spring(dampingRatio = 0.7f, stiffness = 300f)) + slideInVertically(
                spring(
                    dampingRatio = 0.6f,
                    stiffness = 300f
                )
            ) { -it } + fadeIn(spring(dampingRatio = 0.8f, stiffness = 400f)),
            exit = shrinkVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + slideOutVertically(
                spring(
                    dampingRatio = 0.8f, stiffness = 500f
                )
            ) { -it } + fadeOut()) {
            ChatMessageActionButtons(
                message = group.lastNode.currentMessage,
                onRegenerate = onRegenerate,
                node = group.nodeWithMostVersions,
                onUpdate = onUpdate,
                showRegenerate = showRegenerate,
                onOpenActionSheet = onOpenActionSheet,
                onEditLorebookEntry = if (wechatMode && !BuildConfig.DEBUG) null else onEditLorebookEntry,
                onModeClick = if (wechatMode && !BuildConfig.DEBUG) null else onModeClick,
                onMemoryClick = if (wechatMode && !BuildConfig.DEBUG) null else onMemoryClick
            )
        }
    }
}

/**
 * ✨ A styled block to show reasoning inline when auto-collapse is disabled.
 */
@Composable
private fun ReasoningFlowBlock(
    content: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = stringResource(R.string.chat_activity_thinking), // ✨ Internationalized
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(Modifier.height(8.dp))
        MarkdownBlock(
            content = content,
            style = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = MaterialTheme.typography.bodyMedium.fontSize
            )
        )
    }
}

/**
 * Inline token statistics display.
 */
@Composable
private fun TokenStatisticsInline(
    usage: TokenUsage,
    generationDurationMs: Long?,
    modifier: Modifier = Modifier
) {
    val grayColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val tokensPerSecond: Float? =
        generationDurationMs?.let { if (it > 0) (usage.completionTokens / (it / 1000.0)).toFloat() else null }

    Row(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = "Sent",
                modifier = Modifier.size(14.dp),
                tint = grayColor
            )
            Text(text = buildString {
                append("${usage.promptTokens.formatNumber()} tokens"); if (usage.cachedTokens > 0) append(
                " (${usage.cachedTokens.formatNumber()} cached)"
            )
            }, style = MaterialTheme.typography.labelSmall, color = grayColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = "Received",
                modifier = Modifier.size(14.dp),
                tint = grayColor
            )
            Text(
                text = "${usage.completionTokens.formatNumber()} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = grayColor
            )
        }
        if (tokensPerSecond != null && tokensPerSecond > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = "Speed",
                    modifier = Modifier.size(14.dp),
                    tint = grayColor
                )
                Text(
                    text = "%.1f tok/s".format(tokensPerSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = grayColor
                )
            }
        }
    }
}
