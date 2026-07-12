package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.ui.graphics.StrokeCap
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.ui.components.chat.NewChatContent
import me.rerere.rikkahub.ui.components.ui.ToastType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.data.datastore.ChatInputStyle
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.MinimalChatInput
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
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
            parametersOf(id.toString())
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
    val newChatStats by vm.newChatStats.collectAsStateWithLifecycle()

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
        newChatStats = newChatStats
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
    newChatStats: me.rerere.rikkahub.ui.components.chat.NewChatStats,
) {
    val isAiTyping by vm.isAiTyping.collectAsStateWithLifecycle()

    // Track messages that are visually animating (bubbles popping up in WeChat mode)
    val animatingMessages = remember(conversation.id) { mutableStateMapOf<Uuid, Boolean>() }
    val isAnyMessageAnimating = animatingMessages.values.any { it }

    // Combined typing status
    val effectiveTypingIndicator = isAiTyping || isAnyMessageAnimating

    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var isTemporaryChat by rememberSaveable { mutableStateOf(false) }

    // Voice Call States
    var isCallActive by rememberSaveable { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(true) }
    var callStatus by remember { mutableStateOf(CallStatus.CONNECTING) }

    // --- 统一返回出口逻辑 ---
    val handleBack: () -> Unit = {
        when {
            isCallActive -> {
                isCallActive = false
            }

            previewMode -> {
                previewMode = false
            }

            else -> {
                // 尝试正常回退，如果回退失败（栈中只有当前页）则导航回主页并清空栈
                if (!navController.popBackStack()) {
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

    val uiMessages by vm.uiMessages.collectAsStateWithLifecycle()
    val isSyncingContext by vm.isSyncingContext.collectAsStateWithLifecycle()
    val isConsolidating by vm.isConsolidating.collectAsStateWithLifecycle()

    val tts = LocalTTSState.current
    val ttsMutex = remember { Mutex() }
    val ttsScope = rememberCoroutineScope()
    var lastProcessedMessageId by remember { mutableStateOf<Uuid?>(null) }
    var lastProcessedIndex by remember { mutableStateOf(0) }

    LaunchedEffect(conversation, loadingJob, setting.autoPlayTts) {
        val lastMsg = conversation.currentMessages.lastOrNull()

        if (!setting.autoPlayTts) {
            tts.stop()
            if (lastMsg?.role == MessageRole.ASSISTANT) {
                val rawContent = lastMsg.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                lastProcessedMessageId = lastMsg.id
                lastProcessedIndex = rawContent.length
            } else {
                lastProcessedMessageId = null
                lastProcessedIndex = 0
            }
            return@LaunchedEffect
        }

        if (lastMsg?.role == MessageRole.ASSISTANT) {
            val rawContent = lastMsg.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }

            if (lastProcessedMessageId != lastMsg.id) {
                if (lastProcessedMessageId == null && loadingJob == null) {
                    lastProcessedMessageId = lastMsg.id
                    lastProcessedIndex = rawContent.length
                } else {
                    lastProcessedMessageId = lastMsg.id
                    lastProcessedIndex = 0
                }
            }
            val terminators = charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';')
            var i = lastProcessedIndex
            while (i < rawContent.length) {
                if (rawContent[i] in terminators) {
                    val sentence = rawContent.substring(lastProcessedIndex, i + 1).trim()
                    if (sentence.isNotEmpty()) {
                        ttsScope.launch {
                            ttsMutex.withLock {
                                tts.speak(sentence, flushCalled = false)
                            }
                        }
                    }
                    lastProcessedIndex = i + 1
                }
                i++
            }

            if (loadingJob == null && lastProcessedIndex < rawContent.length) {
                val remaining = rawContent.substring(lastProcessedIndex).trim()
                if (remaining.isNotEmpty()) {
                    ttsScope.launch {
                        ttsMutex.withLock {
                            tts.speak(remaining, flushCalled = false)
                        }
                    }
                }
                lastProcessedIndex = rawContent.length
            }
        } else {
            lastProcessedMessageId = null
            lastProcessedIndex = 0
        }
    }

    var lastProviderIndex by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(currentSearchMode) {
        if (currentSearchMode is me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider) {
            lastProviderIndex = currentSearchMode.index
        }
    }

    LaunchedEffect(loadingJob) {
        inputState.loading = loadingJob != null
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
                        isLoading = effectiveTypingIndicator
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
                        innerPadding = PaddingValues(top = topMessagePadding, bottom = 100.dp),
                        conversation = conversation,
                        uiItems = uiMessages,
                        state = chatListState,
                        loading = loadingJob != null,
                        previewMode = previewMode,
                        settings = setting,
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
                                        vm.updateConversation(backup)
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
                            val backup = conversation // 用于撤销
                            vm.deleteMessages(messages)

                            toaster.show(
                                message = context.getString(R.string.message_deleted),
                                action = me.rerere.rikkahub.ui.components.ui.ToastAction(
                                    label = context.getString(R.string.undo),
                                    onClick = {
                                        vm.updateConversation(backup)
                                    }
                                )
                            )
                        },
                        onTypingStateChange = { nodeId, isTyping ->
                            if (isTyping) animatingMessages[nodeId] = true
                            else animatingMessages.remove(nodeId)
                        }
                    )

                    val hasUserSentMessages = remember(conversation.messageNodes) {
                        conversation.messageNodes.any { it.role == MessageRole.USER }
                    }
                    val hasAnyPresetMessages = currentAssistant.presetMessages.isNotEmpty()
                    val effectiveDisplaySetting = setting.getEffectiveDisplaySetting(currentAssistant)

                    AnimatedVisibility(
                        visible = isTemporaryChat && !hasUserSentMessages && !hasAnyPresetMessages,
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
                        visible = hasUserSentMessages || hasAnyPresetMessages || isTemporaryChat || !shouldShowNewChatContent,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
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

                    when (effectiveDisplaySetting.chatInputStyle) {
                        ChatInputStyle.MINIMAL -> {
                            MinimalChatInput(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                state = inputState,
                                settings = setting,
                                conversation = conversation,
                                mcpManager = vm.mcpManager,
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
                                onCancelClick = { loadingJob?.cancel() },
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
                                            inputState.getContents(),
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
                                            content = inputState.getContents(),
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
                                    vm.updateConversation(
                                        updatedConversation
                                    ); vm.saveConversationAsync()
                                },
                                onNavigateToLorebook = { lorebookId ->
                                    navController.navigate(
                                        Screen.SettingLorebookDetail(
                                            lorebookId
                                        )
                                    )
                                },
                                onStartCall = { isCallActive = true },
                                onRefreshContext = { vm.refreshContext() },
                                onDeleteFile = { vm.deleteFile(it) },
                                onConsolidate = { vm.consolidateConversation(conversation) }
                            )
                        }

                        ChatInputStyle.FLOATING -> {
                            ChatInput(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                state = inputState,
                                settings = setting,
                                conversation = conversation,
                                mcpManager = vm.mcpManager,
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
                                onCancelClick = { loadingJob?.cancel() },
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
                                            inputState.getContents(),
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
                                            content = inputState.getContents(),
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
                                    vm.updateConversation(
                                        updatedConversation
                                    ); vm.saveConversationAsync()
                                },
                                onNavigateToLorebook = { lorebookId ->
                                    navController.navigate(
                                        Screen.SettingLorebookDetail(
                                            lorebookId
                                        )
                                    )
                                },
                                onStartCall = { isCallActive = true },
                                onRefreshContext = { vm.refreshContext() },
                                onDeleteFile = { vm.deleteFile(it) },
                                onConsolidate = { vm.consolidateConversation(conversation) }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isSyncingContext || isConsolidating,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "sync_background_breathing")
                        val breathingAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.6f,
                            targetValue = 0.85f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 2000, easing = LinearOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "breathing_alpha"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = breathingAlpha))
                                .clickable(enabled = true, onClick = {}),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                DocumentLoadingAnimation(modifier = Modifier.padding(bottom = 24.dp))
                                Text(
                                    text = if (isConsolidating) stringResource(R.string.consolidating_in_progress) else stringResource(
                                        R.string.syncing_context_animation_hint
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }

                    // Voice Call Overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isCallActive,
                        enter = fadeIn() + expandIn(expandFrom = Alignment.BottomCenter),
                        exit = fadeOut() + shrinkOut(shrinkTowards = Alignment.BottomCenter)
                    ) {
                        CallScreen(
                            assistant = currentAssistant,
                            status = callStatus,
                            isMuted = isMuted,
                            isSpeakerOn = isSpeakerOn,
                            onMuteToggle = { isMuted = !isMuted },
                            onSpeakerToggle = { isSpeakerOn = !isSpeakerOn },
                            onHangup = { isCallActive = false },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentLoadingAnimation(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(120.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            strokeCap = StrokeCap.Round
        )
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            modifier = Modifier.size(54.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private data class TopBarActionState(
    val isEmpty: Boolean,
    val isTemporaryChat: Boolean,
    val shouldUseCompactTemporaryToggle: Boolean,
    val assistantId: Uuid,
    val conversationId: Uuid
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
    isLoading: Boolean
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
            val titleText = if (isLoading && wechatMode) {
                stringResource(R.string.chat_status_typing)
            } else {
                currentAssistant.name
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
                        conversationId = conversationId
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
