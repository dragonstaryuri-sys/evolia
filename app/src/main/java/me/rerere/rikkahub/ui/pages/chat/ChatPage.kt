package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.StrokeCap
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.service.voice.VoiceRecorderController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.data.datastore.ChatInputStyle
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.MinimalChatInput
import me.rerere.rikkahub.common.ui.components.FullscreenLoadingOverlay
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.QuotedMessage
import me.rerere.rikkahub.ui.hooks.rememberChatInputState
import me.rerere.rikkahub.ui.theme.AssistantChatTheme
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.createChatFilesByContents
import me.rerere.rikkahub.utils.getFileMimeType
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid
import me.rerere.rikkahub.ui.components.chat.CallScreen
import me.rerere.rikkahub.ui.components.chat.CallStatus
import me.rerere.rikkahub.ui.pages.chat.ChatUIItem
import me.rerere.rikkahub.ui.components.chat.groupIntoTurns

private enum class PaginationBoundary {
    Older,
    Newer
}

@Composable
fun ChatPage(
    id: Uuid,
    text: String?,
    files: List<Uri>,
    searchQuery: String? = null,
    targetMessageId: String? = null
) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString(), targetMessageId)
        }
    )
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current


    LaunchedEffect(Unit) {
        launch {
            vm.toastFlow.collect { message ->
                if (message.startsWith("NAVIGATE_NEW_CHAT:")) {
                    val newId = message.substringAfter("NAVIGATE_NEW_CHAT:")
                    navController.navigate(Screen.Chat(id = newId)) {
                        popUpTo(Screen.Chat(id = id.toString())) { inclusive = true }
                    }
                } else {
                    toaster.show(message)
                }
            }
        }
        launch {
            vm.errorFlow.collect { error ->
                toaster.show(error.message ?: "Error", type = ToastType.Error)
            }
        }
        launch {
            vm.conversationDeletedFlow.collect { deletedConv ->
                toaster.show(
                    message = context.getString(R.string.conversation_deleted),
                    action = me.rerere.rikkahub.ui.components.ui.ToastAction(
                        label = context.getString(R.string.undo),
                        onClick = {
                            vm.undoDeleteConversation(deletedConv.id)
                        }
                    )
                )
                if (deletedConv.id == id) {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navigateToChatPage(navController, Uuid.random())
                    }
                }
            }
        }
    }

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val isConversationLoaded by vm.isConversationLoaded.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val currentSearchMode by vm.currentSearchMode.collectAsStateWithLifecycle()

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    val inputState = rememberChatInputState(
        message = emptyList(),
        textContent = remember(text) {
            text?.base64Decode() ?: ""
        }
    )

    LaunchedEffect(files) {
        if (files.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val localFiles = context.createChatFilesByContents(files)
                val contentTypes = files.mapNotNull { file ->
                    context.getFileMimeType(file)
                }
                val parts = localFiles.mapIndexedNotNull { index, file ->
                    val type = contentTypes.getOrNull(index)
                    when {
                        type?.startsWith("image/") == true -> UIMessagePart.Image(url = file.toString())
                        type?.startsWith("video/") == true -> UIMessagePart.Video(url = file.toString())
                        type?.startsWith("audio/") == true -> UIMessagePart.Audio(url = file.toString())
                        else -> null
                    }
                }
                inputState.setContents(parts)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(isConversationLoaded) {
        if (isConversationLoaded && !vm.chatListInitialized) {
            vm.chatListInitialized = true
        }
    }

    // 监听滚动到底部事件（发送新消息后触发）
    LaunchedEffect(Unit) {
        vm.scrollToBottomEvent.collect {
            // 倒序列表：index=0 即为最新消息（底部）
            chatListState.animateScrollToItem(0)
        }
    }

    ChatPageContent(
        inputState = inputState,
        loadingJob = loadingJob,
        setting = setting,
        bigScreen = isBigScreen,
        conversation = conversation,
        isConversationLoaded = isConversationLoaded,
        navController = navController,
        vm = vm,
        chatListState = chatListState,
        enableWebSearch = enableWebSearch,
        currentSearchMode = currentSearchMode,
        currentChatModel = currentChatModel,
        initialSearchQuery = searchQuery,
        targetMessageId = targetMessageId,
    )
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    isConversationLoaded: Boolean,
    navController: NavHostController,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentSearchMode: me.rerere.rikkahub.core.data.model.AssistantSearchMode,
    currentChatModel: Model?,
    initialSearchQuery: String? = null,
    targetMessageId: String? = null,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    // 通话前动态申请录音权限（Manifest 声明不足以触发 SystemASR 的 SpeechRecognizer）
    var pendingCallConvId by remember { mutableStateOf<Uuid?>(null) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val convId = pendingCallConvId
        pendingCallConvId = null
        if (granted && convId != null) {
            vm.startCall(convId)
        } else if (convId != null) {
            toaster.show("需要录音权限才能进行语音通话", ToastType.Error)
        }
    }

    fun startCallWithPermission(convId: Uuid) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.startCall(convId)
        } else {
            pendingCallConvId = convId
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 收集通话错误事件, toast 提示用户（权限缺失/ASR 不可用等）
    LaunchedEffect(Unit) {
        vm.callError.collect { msg ->
            toaster.show(msg, ToastType.Error)
        }
    }

    // --- 语音消息：录音控制器 + 麦克风权限 ---
    val voiceRecorderController = remember { VoiceRecorderController(context) }
    DisposableEffect(voiceRecorderController) {
        onDispose { voiceRecorderController.release() }
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            toaster.show(context.getString(R.string.chat_voice_permission_required), ToastType.Error)
        }
    }
    val voiceTranscribing by vm.voiceTranscribing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.voiceEvents.collect { msg -> toaster.show(msg, ToastType.Error) }
    }

    fun handleVoiceMessageReady(result: me.rerere.rikkahub.service.voice.VoiceRecorderResult?) {
        // null 可能是"太短"或"上滑取消"：通过 controller 状态区分
        if (result == null) {
            val isCancelled = voiceRecorderController.state.value ==
                me.rerere.rikkahub.service.voice.VoiceRecorderState.Cancelled
            if (!isCancelled) {
                toaster.show(context.getString(R.string.chat_voice_too_short), ToastType.Info)
            }
            return
        }
        if (currentChatModel == null) {
            toaster.show(context.getString(R.string.error_select_model_first), ToastType.Error)
            return
        }
        vm.sendVoiceMessage(result.uri, result.durationMs)
    }

    // 达到录音最长时长（59s）时，自动停录并发送
    LaunchedEffect(voiceRecorderController) {
        voiceRecorderController.autoStopEvents.collect {
            val result = voiceRecorderController.stop()
            handleVoiceMessageReady(result)
        }
    }

    val pagingState by vm.chatPaginationState.collectAsStateWithLifecycle()
    val successState = pagingState as? ConversationRepository.ChatPaginationState.Success
    val isHistoryLoading = successState?.loadingDirection == ConversationRepository.PageLoadDirection.OLDER
    val isNewerLoading = successState?.loadingDirection == ConversationRepository.PageLoadDirection.NEWER
    val isAiTyping by vm.isAiTyping.collectAsStateWithLifecycle()
    val pageNodes = successState?.nodes.orEmpty()
    val hasOlder = successState?.hasOlder == true
    val assembledItems by remember(pageNodes, hasOlder, isAiTyping) {
        derivedStateOf {
            if (pageNodes.isEmpty()) return@derivedStateOf emptyList<ChatUIItem>()

            val items = mutableListOf<ChatUIItem>()
            val turns = pageNodes.groupIntoTurns()
            val reversedTurns = turns.reversed()

            var lastConvId: Uuid? = null
            reversedTurns.forEachIndexed { index, turn ->
                val firstNode = turn.firstNode
                // 话题线逻辑：如果会话 ID 变了，插一根分隔线
                if (lastConvId != null && firstNode.conversationId != lastConvId) {
                    items.add(ChatUIItem.Separator(
                        text = context.getString(R.string.chat_topic_started),
                        uid = "topic_${firstNode.conversationId}"
                    ))
                }
                val reversedTurn = turn.copy(
                    nodes = turn.nodes.reversed()
                )
                // 组装成 UI 条目，index == 0 是最新的消息
                items.add(ChatUIItem.Turn(
                    reversedTurn,
                    isGenerating = index == 0 && isAiTyping
                ))
                lastConvId = firstNode.conversationId
            }

            // 顶部加载提示
            if (hasOlder) {
                items.add(ChatUIItem.Separator(
                    text = context.getString(R.string.chat_load_more),
                    uid = "load_more"
                ))
            } else {
                items.add(ChatUIItem.Separator(
                    text = context.getString(R.string.chat_topic_started_all),
                    uid = "history_start"
                ))
            }
            items
        }
    }

    val requestPaginationForUserScroll: () -> Boolean = {
        var paginationStarted = false
        val state = pagingState as? ConversationRepository.ChatPaginationState.Success
        val visibleItems = chatListState.layoutInfo.visibleItemsInfo
        if (state != null && visibleItems.isNotEmpty()) {
            val totalItems = chatListState.layoutInfo.totalItemsCount
            val firstVisibleIndex = visibleItems.minOf { it.index }
            val lastVisibleIndex = visibleItems.maxOf { it.index }
            val boundary = when {
                totalItems > 5 && lastVisibleIndex >= totalItems - 5 && state.hasOlder -> PaginationBoundary.Older
                firstVisibleIndex <= 1 && state.hasNewer -> PaginationBoundary.Newer
                else -> null
            }

            if (boundary != null && state.loadingDirection == null) {
                when (boundary) {
                    PaginationBoundary.Older -> vm.triggerLoadOlder()
                    PaginationBoundary.Newer -> vm.triggerLoadNewer()
                }
                paginationStarted = true
            }
        }
        paginationStarted
    }

    // Track messages that are visually animating (bubbles popping up in WeChat mode)
    val animatingMessages = remember(conversation.id) { mutableStateMapOf<Uuid, Boolean>() }
    val isAnyMessageAnimating = animatingMessages.values.any { it }

    // Combined typing status
    val effectiveTypingIndicator = isAiTyping || isAnyMessageAnimating
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var isTemporaryChat by rememberSaveable { mutableStateOf(false) }

    // Voice Call States - 由 ChatVM 转发的 VoiceCallManager StateFlow 驱动
    val isCallActive by vm.isCallActive.collectAsStateWithLifecycle()
    val isMuted by vm.callIsMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by vm.callIsSpeakerOn.collectAsStateWithLifecycle()
    val callStatus by vm.callStatus.collectAsStateWithLifecycle()

    // --- 统一返回出口逻辑 ---
    val handleBack: () -> Unit = {
        when {
            isCallActive -> {
                vm.hangupCall()
            }

            previewMode -> {
                previewMode = false
            }

            else -> {
                // 如果是从搜索跳转来的，直接返回上一页
                if (!targetMessageId.isNullOrBlank()) {
                    navController.popBackStack()
                } else {
                    // 强制返回主页并清空所有中间栈（如检索页），实现“一键回主页”
                    navController.navigate(Screen.Home) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }
    }

    // 系统返回手势（左滑）统一拦截
    BackHandler(enabled = true, onBack = handleBack)

    var showRegenerateConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showRegenerateRequirementDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRegenerateMessage by remember { mutableStateOf<me.rerere.ai.ui.UIMessage?>(null) }
    var pendingRegenerateRequirement by remember { mutableStateOf<String?>(null) }

    val currentAssistant = setting.getCurrentAssistant()
    val topMessagePadding = 72.dp

    val isSyncingContext by vm.isSyncingContext.collectAsStateWithLifecycle()
    val isConsolidating by vm.isConsolidating.collectAsStateWithLifecycle()

    val tts = LocalTTSState.current

    var lastProviderIndex by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(currentSearchMode) {
        if (currentSearchMode is me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider) {
            lastProviderIndex = currentSearchMode.index
        }
    }

    // 终止按钮完全以 isAiTyping 为准：
    //   - finally 块中立即清除 → AI 停后按钮立即消失，不依赖 invokeOnCompletion 的异步
    //   - 微信模式 5 秒 debounce 期间 isAiTyping=false → 前 5 秒不显示终止按钮
    LaunchedEffect(isAiTyping) {
        inputState.loading = isAiTyping
    }

    AssistantChatTheme(assistant = currentAssistant) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            AssistantBackground(setting = setting)
            Scaffold(
                topBar = {
                    TopBar(
                        settings = setting,
                        conversationId = conversation.id,
                        hasUserMessages = remember(conversation.messageNodes) {
                            conversation.messageNodes.any { it.role == MessageRole.USER }
                        },
                        bigScreen = bigScreen,
                        previewMode = previewMode,
                        isTemporaryChat = isTemporaryChat,
                        onBack = handleBack,
                        onNewChat = {
                            vm.startNewTopic()
                        },
                        onClickMenu = {
                            previewMode = !previewMode
                        },
                        onUpdateSettings = { newSettings ->
                            vm.updateSettings(newSettings)
                        },
                        onToggleTemporaryChat = {
                            isTemporaryChat = !isTemporaryChat
                        },
                        isLoading = effectiveTypingIndicator,
                        isReadOnly = !targetMessageId.isNullOrBlank()
                    )
                },
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp)
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    ChatList(
                        innerPadding = PaddingValues(
                            top = topMessagePadding,
                            bottom = if (targetMessageId.isNullOrBlank()) 84.dp else 16.dp
                        ),
                        conversation = conversation,
                        items = assembledItems,
                        paginationState = pagingState,
                        isHistoryLoading = isHistoryLoading,
                        state = chatListState,
                        loading = loadingJob != null,
                        previewMode = previewMode,
                        settings = setting,
                        isSyncing = isSyncingContext || isConsolidating,
                        recentlyRestoredNodeIds = vm.recentlyRestoredNodeIds.collectAsStateWithLifecycle().value,
                        initialSearchQuery = initialSearchQuery,
                        targetMessageId = targetMessageId,
                        onJumpToMessage = { targetNode ->
                            previewMode = false
                        },
                        onRegenerate = { message ->
                            pendingRegenerateMessage = message
                            showRegenerateRequirementDialog = true
                        },
                        onEdit = {
                            inputState.editingMessage = it.id
                            inputState.setContents(it.parts)
                            // 编辑与引用互斥：进入编辑模式时清除引用状态
                            inputState.quotedMessage = null
                        },
                        onQuote = { message ->
                            // 提取被引用消息的文本内容；若为空（纯图片/文件等），使用占位符
                            val rawContent = message.toContentText()
                            val quoteContent = if (rawContent.isBlank()) {
                                when {
                                    message.parts.any { it is UIMessagePart.Image } -> "[图片]"
                                    message.parts.any { it is UIMessagePart.Video } -> "[视频]"
                                    message.parts.any { it is UIMessagePart.Audio } -> "[语音]"
                                    message.parts.any { it is UIMessagePart.Document } -> "[文件]"
                                    else -> "[消息]"
                                }
                            } else {
                                // 截断过长的引用内容，避免预览卡片和上下文过于冗长
                                if (rawContent.length > 200) {
                                    rawContent.take(200) + "…"
                                } else {
                                    rawContent
                                }
                            }
                            // 发送者名称：用户消息用昵称，AI 消息用 assistant 名字
                            val senderName = if (message.role == MessageRole.USER) {
                                setting.displaySetting.userNickname.ifBlank { "User" }
                            } else {
                                currentAssistant.name.ifBlank { "Assistant" }
                            }
                            inputState.quotedMessage = QuotedMessage(
                                senderName = senderName,
                                content = quoteContent,
                                isUser = message.role == MessageRole.USER
                            )
                            // 清除编辑模式（引用与编辑互斥）
                            inputState.editingMessage = null
                            // 请求焦点以唤起键盘
                            inputState.setMessageTextAndFocus(inputState.textContent.text.toString(), scope)
                        },
                        onDelete = { message ->
                            val backup = conversation
                            val deletedNodeIds = conversation.messageNodes.map { it.id }.toSet()
                            vm.deleteMessage(message)
                            val newNodeIds = vm.conversation.value.messageNodes.map { it.id }.toSet()
                            val removedIds = deletedNodeIds - newNodeIds
                            toaster.show(
                                message = context.getString(R.string.message_deleted),
                                action = me.rerere.rikkahub.ui.components.ui.ToastAction(
                                    label = context.getString(R.string.undo),
                                    onClick = {
                                        vm.restoreConversation(backup)
                                        vm.markNodesAsRestored(removedIds)
                                    }
                                )
                            )
                        },
                        onUpdateMessage = { newNode ->
                            vm.updateMessageNodeInAnyConversation(newNode)
                        },
                        onGetFullMemoryContent = { id, type -> vm.getFullMemoryContent(id, type) },
                        onAddFavorite = { messages ->
                            vm.addFavorite(messages, currentAssistant, setting.displaySetting.userNickname)
                        },
                        onDeleteMessages = { messages ->
                            val backup = conversation
                            vm.deleteMessages(messages)

                            toaster.show(
                                message = context.getString(R.string.message_deleted),
                                action = me.rerere.rikkahub.ui.components.ui.ToastAction(
                                    label = context.getString(R.string.undo),
                                    onClick = {
                                        vm.restoreConversation(backup)
                                    }
                                )
                            )
                        },
                        onTypingStateChange = { nodeId, isTyping ->
                            if (isTyping) animatingMessages[nodeId] = true
                            else animatingMessages.remove(nodeId)
                        },
                        onUserScroll = requestPaginationForUserScroll,
                        onRetryPagination = vm::retryPagination,
                        voiceMessagePlayer = vm.voiceMessagePlayer,
                        shownTranscriptions = vm.shownTranscriptions,
                        onManualTranscribe = { nodeId, audioUrl -> vm.manualTranscribeAudio(nodeId, audioUrl) },
                    )

                    val hasUserSentMessages = remember(conversation.messageNodes) {
                        conversation.messageNodes.any { it.role == MessageRole.USER }
                    }
                    val hasAnyPresetMessages = currentAssistant.presetMessages.isNotEmpty()
                    val effectiveDisplaySetting = setting.getEffectiveDisplaySetting(currentAssistant)

                    AnimatedVisibility(
                        visible = isTemporaryChat && !hasUserSentMessages && !hasAnyPresetMessages && targetMessageId.isNullOrBlank(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.HistoryToggleOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = stringResource(R.string.temporary_chat_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    val isKeyboardOpen = WindowInsets.isImeVisible
                    val hasTextInput =
                        inputState.textContent.text.isNotEmpty() || inputState.messageContent.isNotEmpty()

                    val shouldShowNewChatContent =
                        isConversationLoaded && !isTemporaryChat && !hasUserSentMessages && !hasAnyPresetMessages && !hasTextInput && !isKeyboardOpen
                    val errorSelectModelText = stringResource(R.string.error_select_model_first)

                    if (showRegenerateConfirmDialog && pendingRegenerateMessage != null) {
                        AlertDialog(
                            onDismissRequest = {
                                showRegenerateConfirmDialog = false
                                pendingRegenerateMessage = null
                                pendingRegenerateRequirement = null
                            },
                            title = { Text(stringResource(R.string.regenerate_title)) },
                            text = {
                                Text(stringResource(R.string.regenerate_description))
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        pendingRegenerateMessage?.let { message ->
                                            vm.regenerateAtMessage(
                                                message,
                                                forceWipe = true,
                                                requirement = pendingRegenerateRequirement
                                            )
                                        }
                                        showRegenerateConfirmDialog = false
                                        pendingRegenerateMessage = null
                                        pendingRegenerateRequirement = null
                                    }
                                ) { Text(stringResource(R.string.regenerate_confirm)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showRegenerateConfirmDialog = false
                                    pendingRegenerateMessage = null
                                    pendingRegenerateRequirement = null
                                }) { Text(stringResource(R.string.cancel)) }
                            }
                        )
                    }

                    if (showRegenerateRequirementDialog && pendingRegenerateMessage != null) {
                        var requirement by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = {
                                showRegenerateRequirementDialog = false
                                pendingRegenerateMessage = null
                            },
                            title = { Text(stringResource(R.string.chat_page_regenerate_requirement_title)) },
                            text = {
                                OutlinedTextField(
                                    value = requirement,
                                    onValueChange = { requirement = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(stringResource(R.string.chat_page_regenerate_requirement_placeholder)) },
                                    label = { Text(stringResource(R.string.chat_page_regenerate_requirement_label)) }
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        pendingRegenerateMessage?.let { message ->
                                            val req = requirement.ifBlank { null }
                                            if (vm.canPreserveVersionHistory(message)) {
                                                vm.regenerateAtMessage(message, forceWipe = false, requirement = req)
                                                showRegenerateRequirementDialog = false
                                                pendingRegenerateMessage = null
                                            } else {
                                                pendingRegenerateRequirement = req
                                                showRegenerateRequirementDialog = false
                                                showRegenerateConfirmDialog = true
                                            }
                                        }
                                    }
                                ) { Text(stringResource(R.string.regenerate_confirm)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showRegenerateRequirementDialog = false
                                    pendingRegenerateMessage = null
                                }) { Text(stringResource(R.string.cancel)) }
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = (hasUserSentMessages || hasAnyPresetMessages || isTemporaryChat || !shouldShowNewChatContent) && targetMessageId.isNullOrBlank(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
                                        )
                                    )
                                )
                        )
                    }

                    if (targetMessageId.isNullOrBlank()) {
                        when (effectiveDisplaySetting.chatInputStyle) {
                            ChatInputStyle.MINIMAL -> {
                                MinimalChatInput(
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    state = inputState,
                                    settings = setting,
                                    conversation = conversation,
                                    mcpManager = vm.mcpManager,
                                    isAiTyping = isAiTyping,
                                    chatSuggestions = conversation.chatSuggestions,
                                    onClickSuggestion = { suggestion ->
                                        if (currentChatModel != null) {
                                            vm.handleMessageSend(
                                                listOf(UIMessagePart.Text(suggestion)),
                                                isTemporaryChat = isTemporaryChat
                                            )
                                        } else {
                                            toaster.show(errorSelectModelText, type = ToastType.Error)
                                        }
                                    },
                                    onCancelClick = { vm.stopGeneration() },
                                    enableSearch = enableWebSearch,
                                    onToggleSearch = {
                                        if (enableWebSearch) vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Off)
                                        else if (setting.searchServices.isNotEmpty()) {
                                            val validIndex = lastProviderIndex.coerceIn(0, setting.searchServices.lastIndex)
                                            vm.updateAssistantSearchMode(
                                                me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(
                                                    validIndex
                                                )
                                            )
                                        }
                                    },
                                    onSendClick = {
                                        if (inputState.isEditing()) vm.handleMessageEdit(
                                            parts = inputState.getContents(),
                                            messageId = inputState.editingMessage!!
                                        )
                                        else {
                                            if (currentChatModel == null) {
                                                toaster.show(
                                                    errorSelectModelText,
                                                    type = ToastType.Error
                                                ); return@MinimalChatInput
                                            }
                                            vm.handleMessageSend(
                                                inputState.getSendContents(),
                                                isTemporaryChat = isTemporaryChat
                                            )
                                        }
                                        inputState.clearInput()
                                    },
                                    onLongSendClick = {
                                        if (inputState.isEditing()) vm.handleMessageEdit(
                                            parts = inputState.getContents(),
                                            messageId = inputState.editingMessage!!
                                        )
                                        else {
                                            if (currentChatModel == null) {
                                                toaster.show(
                                                    errorSelectModelText,
                                                    type = ToastType.Error
                                                ); return@MinimalChatInput
                                            }
                                            vm.handleMessageSend(
                                                content = inputState.getSendContents(),
                                                answer = false,
                                                isTemporaryChat = isTemporaryChat
                                            )
                                        }
                                        inputState.clearInput()
                                    },
                                    onUpdateChatModel = {
                                        vm.setChatModel(
                                            assistant = setting.getCurrentAssistant(),
                                            model = it
                                        )
                                    },
                                    onUpdateAssistant = { updatedAssistant ->
                                        vm.updateAssistant(updatedAssistant)
                                    },
                                    onUpdateSearchService = { index ->
                                        vm.updateAssistantSearchMode(
                                            me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(
                                                index
                                            )
                                        )
                                    },
                                    onClearContext = { vm.startNewTopic() },
                                    onUpdateConversation = { updatedConversation ->
                                        vm.updateConversation(updatedConversation)
                                    },
                                    onNavigateToLorebook = { lorebookId ->
                                        navController.navigate(
                                            Screen.SettingLorebookDetail(
                                                lorebookId
                                            )
                                        )
                                    },
                                    onStartCall = { startCallWithPermission(conversation.id) },
                                    onRefreshContext = { vm.refreshContext() },
                                    onDeleteFile = { vm.deleteFile(it) },
                                    onConsolidate = { vm.consolidateConversation(conversation) },
                                    voiceRecorderController = voiceRecorderController,
                                    voiceTranscribing = voiceTranscribing,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    onVoiceMessageReady = { handleVoiceMessageReady(it) }
                                )
                            }

                            ChatInputStyle.FLOATING -> {
                                ChatInput(
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    state = inputState,
                                    settings = setting,
                                    conversation = conversation,
                                    mcpManager = vm.mcpManager,
                                    isAiTyping = isAiTyping,
                                    chatSuggestions = conversation.chatSuggestions,
                                    onClickSuggestion = { suggestion ->
                                        if (currentChatModel != null) {
                                            vm.handleMessageSend(
                                                listOf(UIMessagePart.Text(suggestion)),
                                                isTemporaryChat = isTemporaryChat
                                            )
                                        } else {
                                            toaster.show(errorSelectModelText, type = ToastType.Error)
                                        }
                                    },
                                    onCancelClick = { vm.stopGeneration() },
                                    enableSearch = enableWebSearch,
                                    onToggleSearch = {
                                        if (enableWebSearch) vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Off)
                                        else if (setting.searchServices.isNotEmpty()) {
                                            val validIndex = lastProviderIndex.coerceIn(0, setting.searchServices.lastIndex)
                                            vm.updateAssistantSearchMode(
                                                me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(
                                                    validIndex
                                                )
                                            )
                                        }
                                    },
                                    onSendClick = {
                                        if (inputState.isEditing()) vm.handleMessageEdit(
                                            parts = inputState.getContents(),
                                            messageId = inputState.editingMessage!!
                                        )
                                        else {
                                            if (currentChatModel == null) {
                                                toaster.show(errorSelectModelText, type = ToastType.Error); return@ChatInput
                                            }
                                            vm.handleMessageSend(
                                                inputState.getSendContents(),
                                                isTemporaryChat = isTemporaryChat
                                            )
                                        }
                                        inputState.clearInput()
                                    },
                                    onLongSendClick = {
                                        if (inputState.isEditing()) vm.handleMessageEdit(
                                            parts = inputState.getContents(),
                                            messageId = inputState.editingMessage!!
                                        )
                                        else {
                                            if (currentChatModel == null) {
                                                toaster.show(errorSelectModelText, type = ToastType.Error); return@ChatInput
                                            }
                                            vm.handleMessageSend(
                                                content = inputState.getSendContents(),
                                                answer = false,
                                                isTemporaryChat = isTemporaryChat
                                            )
                                        }
                                        inputState.clearInput()
                                    },
                                    onUpdateChatModel = {
                                        vm.setChatModel(
                                            assistant = setting.getCurrentAssistant(),
                                            model = it
                                        )
                                    },
                                    onUpdateAssistant = { updatedAssistant ->
                                        vm.updateAssistant(updatedAssistant)
                                    },
                                    onUpdateSearchService = { index ->
                                        vm.updateAssistantSearchMode(
                                            me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(
                                                index
                                            )
                                        )
                                    },
                                    onClearContext = { vm.startNewTopic() },
                                    onUpdateConversation = { updatedConversation ->
                                        vm.updateConversation(updatedConversation)
                                    },
                                    onNavigateToLorebook = { lorebookId ->
                                        navController.navigate(
                                            Screen.SettingLorebookDetail(
                                                lorebookId
                                            )
                                        )
                                    },
                                    onStartCall = { startCallWithPermission(conversation.id) },
                                    onRefreshContext = { vm.refreshContext() },
                                    onDeleteFile = { vm.deleteFile(it) },
                                    onConsolidate = { vm.consolidateConversation(conversation) },
                                    voiceRecorderController = voiceRecorderController,
                                    voiceTranscribing = voiceTranscribing,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    onVoiceMessageReady = { handleVoiceMessageReady(it) }
                                )
                            }
                        }
                    }

                    FullscreenLoadingOverlay(
                        visible = isSyncingContext || isConsolidating,
                        hint = if (isConsolidating) stringResource(R.string.consolidating_in_progress) else stringResource(
                            R.string.syncing_context_animation_hint
                        )
                    )

                    // Voice Call Overlay
                    AnimatedVisibility(
                        visible = isCallActive,
                        enter = fadeIn() + expandIn(expandFrom = Alignment.BottomCenter),
                        exit = fadeOut() + shrinkOut(shrinkTowards = Alignment.BottomCenter)
                    ) {
                        CallScreen(
                            assistant = currentAssistant,
                            status = callStatus,
                            isMuted = isMuted,
                            isSpeakerOn = isSpeakerOn,
                            onMuteToggle = { vm.toggleCallMute() },
                            onSpeakerToggle = { vm.toggleCallSpeaker() },
                            onHangup = { vm.hangupCall() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

private data class TopBarActionState(
    val isEmpty: Boolean,
    val isTemporaryChat: Boolean,
    val shouldUseCompactTemporaryToggle: Boolean,
    val assistantId: Uuid,
    val conversationId: Uuid,
    val isReadOnly: Boolean = false
)

@Composable
private fun TopBar(
    settings: Settings,
    conversationId: Uuid,
    hasUserMessages: Boolean,
    bigScreen: Boolean,
    previewMode: Boolean,
    isTemporaryChat: Boolean,
    onBack: () -> Unit,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateSettings: (Settings) -> Unit,
    onToggleTemporaryChat: () -> Unit,
    isLoading: Boolean,
    isReadOnly: Boolean = false
) {
    val navController = LocalNavController.current
    val topContainerColor = MaterialTheme.colorScheme.surfaceContainer
    val topContainerBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.background)
    val buttonShape = RoundedCornerShape(999.dp)
    val topPillSize = 48.dp
    var showAssistantPicker by remember { mutableStateOf(false) }
    val currentAssistant = settings.getCurrentAssistant()
    val isEmpty = !hasUserMessages
    var animateTopPillIn by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        animateTopPillIn = false
        delay(16)
        animateTopPillIn = true
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.95f), Color.Transparent)
                    )
                )
        )

        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onBack,
                shape = buttonShape,
                color = topContainerColor,
                border = topContainerBorder
            ) {
                Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
            }

            Spacer(Modifier.width(8.dp))

            Surface(
                onClick = { onUpdateSettings(settings.copy(autoPlayTts = !settings.autoPlayTts)) },
                shape = buttonShape,
                color = topContainerColor,
                border = topContainerBorder
            ) {
                Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                    Icon(
                        if (settings.autoPlayTts) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                        "Auto Play TTS"
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            val wechatMode = settings.getEffectiveDisplaySetting(currentAssistant).wechatMode
            val titleText = when {
                isReadOnly -> stringResource(R.string.chat_page_search_result_title)
                isLoading && wechatMode -> stringResource(R.string.chat_status_typing)
                else -> currentAssistant.name
            }

            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clickable {
                        navController.navigate(Screen.AssistantDetail(id = currentAssistant.id.toString()))
                    }
            )

            Spacer(Modifier.weight(1f))

            val topPillScale by animateFloatAsState(
                targetValue = if (animateTopPillIn) 1f else 0.88f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "top_pill_scale"
            )

            Surface(
                shape = buttonShape,
                color = topContainerColor,
                border = topContainerBorder,
                modifier = Modifier.graphicsLayer { scaleX = topPillScale; scaleY = topPillScale }
            ) {
                AnimatedContent(
                    targetState = TopBarActionState(
                        isEmpty = isEmpty,
                        isTemporaryChat = isTemporaryChat,
                        shouldUseCompactTemporaryToggle = run {
                            val hasPresetMessages = currentAssistant.presetMessages.isNotEmpty()
                            !hasPresetMessages
                        },
                        assistantId = currentAssistant.id,
                        conversationId = conversationId,
                        isReadOnly = isReadOnly
                    ),
                    transitionSpec = {
                        (fadeIn(
                            animationSpec = spring(
                                dampingRatio = 0.6f,
                                stiffness = 300f
                            )
                        ) + scaleIn(
                            initialScale = 0.92f,
                            animationSpec = spring(
                                dampingRatio = 0.6f,
                                stiffness = 300f
                            )
                        )) togetherWith (fadeOut(
                            animationSpec = spring(
                                dampingRatio = 0.75f,
                                stiffness = 400f
                            )
                        ) + scaleOut(
                            targetScale = 0.92f,
                            animationSpec = spring(
                                dampingRatio = 0.75f,
                                stiffness = 400f
                            )
                        )) using SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ ->
                                spring(
                                    dampingRatio = 0.6f,
                                    stiffness = 300f
                                )
                            })
                    },
                    label = "topbar_actions"
                ) { actionState ->
                    val isEmptyState = actionState.isEmpty
                    val isTempChat = actionState.isTemporaryChat
                    val hideTopRightAvatar = actionState.shouldUseCompactTemporaryToggle

                    if (actionState.isReadOnly) {
                        Box(modifier = Modifier.height(topPillSize).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onBack) {
                                Text(stringResource(R.string.chat_page_return_to_chat))
                            }
                        }
                    } else {
                        when {
                            isEmptyState && !isTempChat && hideTopRightAvatar -> {
                                Row(
                                    modifier = Modifier.height(topPillSize),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { onToggleTemporaryChat() },
                                        modifier = Modifier.size(topPillSize)
                                    ) {
                                        Icon(Icons.Rounded.HistoryToggleOff, "Temporary Chat")
                                    }
                                }
                            }

                            else -> Row(
                                modifier = Modifier.height(topPillSize),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when {
                                    isEmptyState && !isTempChat -> {
                                        IconButton(
                                            onClick = { onToggleTemporaryChat() },
                                            modifier = Modifier.size(topPillSize)
                                        ) {
                                            Icon(Icons.Rounded.HistoryToggleOff, "Temporary Chat")
                                        }
                                        Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                                            me.rerere.rikkahub.ui.components.ui.UIAvatar(
                                                name = currentAssistant.name.ifBlank { "Character" },
                                                value = currentAssistant.avatar,
                                                modifier = Modifier.size(30.dp),
                                                onClick = { showAssistantPicker = true })
                                        }
                                    }

                                    isEmptyState && isTempChat -> {
                                        IconButton(
                                            onClick = { onToggleTemporaryChat() },
                                            modifier = Modifier.size(topPillSize)
                                        ) {
                                            Icon(Icons.Rounded.History, "Make Normal Chat")
                                        }
                                        Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                                            me.rerere.rikkahub.ui.components.ui.UIAvatar(
                                                name = currentAssistant.name.ifBlank { "Character" },
                                                value = currentAssistant.avatar,
                                                modifier = Modifier.size(30.dp),
                                                onClick = { showAssistantPicker = true })
                                        }
                                    }

                                    else -> {
                                        IconButton(
                                            onClick = {
                                                navController.navigate(Screen.ChatHistorySearch(assistantId = currentAssistant.id.toString()))
                                            },
                                            modifier = Modifier.size(topPillSize)
                                        ) {
                                            Icon(Icons.Rounded.Search, "Search")
                                        }
                                        IconButton(onClick = { onNewChat() }, modifier = Modifier.size(topPillSize)) {
                                            Icon(Icons.Rounded.Add, "New Message")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAssistantPicker) {
        val assistantState = me.rerere.rikkahub.ui.hooks.rememberAssistantState(settings, onUpdateSettings)
        me.rerere.rikkahub.ui.components.ai.AssistantPickerSheet(
            settings = settings,
            currentAssistant = currentAssistant,
            onAssistantSelected = { selectedAssistant ->
                assistantState.setSelectAssistant(selectedAssistant)
            },
            onDismiss = { showAssistantPicker = false }
        )
    }

}
