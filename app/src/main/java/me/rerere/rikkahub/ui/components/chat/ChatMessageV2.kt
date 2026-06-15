package me.rerere.rikkahub.ui.components.chat

import android.util.Log
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
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

    val groups = mutableListOf<MessageTurnGroup>()
    var currentGroup = mutableListOf<MessageNode>()
    var currentGroupRole: MessageRole? = null

    fun getGroupingRole(role: MessageRole): MessageRole = when (role) {
        MessageRole.TOOL -> MessageRole.ASSISTANT
        else -> role
    }

    forEach { node ->
        val nodeRole = node.currentMessage.role
        val logicalRole = getGroupingRole(nodeRole)

        if (logicalRole != currentGroupRole && currentGroup.isNotEmpty()) {
            groups.add(MessageTurnGroup(currentGroup.toList(), currentGroupRole!!))
            currentGroup = mutableListOf()
        }
        currentGroup.add(node)
        currentGroupRole = logicalRole
    }

    if (currentGroup.isNotEmpty() && currentGroupRole != null) {
        groups.add(MessageTurnGroup(currentGroup.toList(), currentGroupRole!!))
    }

    return groups
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
    loading: Boolean
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

        val hasReasoning = totalReasoningMs > 0
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
    onFork: (MessageNode) -> Unit = {},
    onRegenerate: (MessageNode) -> Unit = {},
    onEdit: (MessageNode) -> Unit = {},
    onShare: (MessageNode) -> Unit = {},
    onDelete: (MessageNode) -> Unit = {},
    onUpdate: (MessageNode) -> Unit = {},
    showRegenerate: Boolean,
    onEditLorebookEntry: ((me.rerere.ai.ui.UsedLorebookEntry) -> Unit)? = null,
    onModeClick: ((me.rerere.ai.ui.UsedMode) -> Unit)? = null,
    onMemoryClick: ((me.rerere.ai.ui.UsedMemory) -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val navController = LocalNavController.current
    val effectiveDisplay = settings.getEffectiveDisplaySetting(assistant)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * effectiveDisplay.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * effectiveDisplay.fontSizeRatio
    )
    val configuration = LocalConfiguration.current
    val maxBubbleWidth = (configuration.screenWidthDp * 0.85f).dp
    val wechatMode = effectiveDisplay.wechatMode

    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    var showTimelineSheet by remember { mutableStateOf(false) }
    var initialTimelineExpandedType by remember { mutableStateOf<ActivityType?>(null) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var showUserToolbar by remember { mutableStateOf(false) }

    val activityState = deriveActivityState(group.allParts, loading && isLastTurn)
    val timelineEntries = buildTimelineEntries(group.allParts)

    val actionTargetNode = remember(group) {
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
                    maxWidth = maxBubbleWidth,
                    showToolbar = showUserToolbar,
                    onToggleToolbar = { showUserToolbar = !showUserToolbar },
                    onCopy = { context.copyMessageToClipboard(group.lastNode.currentMessage) },
                    onRegenerate = { onRegenerate(group.lastNode) },
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
                    onBubbleClick = {
                        if (isLastTurn) {
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
            onFork = { onFork(actionTargetNode) },
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

/**
 * User message turn - right-aligned stacked bubbles.
 */
@Composable
private fun UserMessageTurn(
    group: MessageTurnGroup,
    assistant: Assistant?,
    maxWidth: androidx.compose.ui.unit.Dp,
    showToolbar: Boolean,
    onToggleToolbar: () -> Unit,
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
                    val isLast = nodeIndex == group.filteredNodes.lastIndex && partIndex == textParts.lastIndex
                    val totalBubbles = group.filteredNodes.sumOf { n ->
                        n.currentMessage.parts.filterIsInstance<UIMessagePart.Text>().size
                    }
                    val position = if (wechatMode) BubblePosition.SINGLE else when {
                        totalBubbles == 1 -> BubblePosition.SINGLE
                        isFirst -> BubblePosition.FIRST
                        isLast -> BubblePosition.LAST
                        else -> BubblePosition.MIDDLE
                    }

                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.End
                    ) {
                        GroupedMessageBubble(
                            position = position,
                            role = BubbleRole.USER,
                            modifier = Modifier.widthIn(max = maxWidth),
                            containerColor = if (wechatMode) WeChatUserGreen else null,
                            contentColor = if (wechatMode) WeChatTextBlack else null,
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                onToggleToolbar()
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
                                modifier = Modifier.size(40.dp)
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
    onBubbleClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onRegenerate: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onOpenActionSheet: () -> Unit,
    showRegenerate: Boolean,
    onEditLorebookEntry: ((me.rerere.ai.ui.UsedLorebookEntry) -> Unit)?,
    onModeClick: ((me.rerere.ai.ui.UsedMode) -> Unit)?,
    onMemoryClick: ((me.rerere.ai.ui.UsedMemory) -> Unit)?,
    wechatMode: Boolean,
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
    val handleBubbleClick = {
        haptics.perform(HapticPattern.Pop)
        onBubbleClick()
    }

    val avatarName = assistant?.name?.ifEmpty { null } ?: model?.displayName ?: "Assistant"
    val avatarValue = assistant?.avatar ?: Avatar.Dummy
    val hasInterestingActivity = activityState !is ActivityState.Hidden && !wechatMode

    val allTextBubbles = mutableListOf<Pair<MessageNode, UIMessagePart.Text>>()
    group.filteredNodes.forEach { node ->
        node.currentMessage.parts.filterIsInstance<UIMessagePart.Text>().forEach { part ->
            if (part.text.isNotBlank()) {
                allTextBubbles.add(node to part)
            }
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

            allTextBubbles.forEachIndexed { index, (node, part) ->
                val position = if (wechatMode) BubblePosition.SINGLE else when {
                    allTextBubbles.size == 1 -> BubblePosition.SINGLE
                    index == 0 -> BubblePosition.FIRST
                    index == allTextBubbles.lastIndex -> BubblePosition.LAST
                    else -> BubblePosition.MIDDLE
                }

                Row(
                    verticalAlignment = Alignment.Top,
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

                    GroupedMessageBubble(
                        position = position,
                        role = BubbleRole.ASSISTANT,
                        modifier = Modifier.widthIn(max = maxWidth),
                        containerColor = if (wechatMode) WeChatAiWhite else null,
                        contentColor = if (wechatMode) WeChatTextBlack else null,
                        onClick = handleBubbleClick
                    ) {
                        MarkdownBlock(
                            content = part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.ASSISTANT,
                                visual = true
                            ),
                            onClickCitation = { onCitationClick(it) }
                        )
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

            allTextBubbles.forEach { (_, part) ->
                Row(verticalAlignment = Alignment.Top) {
                    if (wechatMode) {
                        UIAvatar(
                            name = avatarName,
                            modifier = Modifier.size(40.dp),
                            value = avatarValue,
                            onClick = onAvatarClick
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    MarkdownBlock(
                        content = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.ASSISTANT,
                            visual = true
                        ),
                        onClickCitation = { id -> onCitationClick(id) },
                        modifier = Modifier.clickable { handleBubbleClick() }
                    )
                }
            }
        }

        if (showTokenUsage && group.combinedUsage != null && !loading && !wechatMode) {
            TokenStatisticsInline(
                usage = group.combinedUsage!!,
                generationDurationMs = group.combinedGenerationDurationMs
            )
        }

        val showActions = !loading && (isLastTurn || actionsExpanded)
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
                    dampingRatio = 0.8f,
                    stiffness = 500f
                )
            ) { -it } + fadeOut()) {
            ChatMessageActionButtons(
                message = group.lastNode.currentMessage,
                onRegenerate = onRegenerate,
                node = group.nodeWithMostVersions,
                onUpdate = onUpdate,
                showRegenerate = showRegenerate,
                onOpenActionSheet = onOpenActionSheet,
                onEditLorebookEntry = onEditLorebookEntry,
                onModeClick = onModeClick,
                onMemoryClick = onMemoryClick
            )
        }
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
