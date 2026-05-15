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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.CubicBezierEasing
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.ui.components.chat.NewChatContent
import me.rerere.rikkahub.ui.components.ui.ToastType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.data.datastore.ChatInputStyle
import me.rerere.rikkahub.data.datastore.TtsTextFilterRule
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
import me.rerere.rikkahub.ui.components.chat.groupIntoTurns

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, searchQuery: String? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

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
            snapshotFlow { chatListState.layoutInfo.totalItemsCount }
                .filter { it > 0 }
                .first()

            delay(150)

            chatListState.scrollToItem(chatListState.layoutInfo.totalItemsCount - 1)
            vm.chatListInitialized = true
        }
    }

    if (isBigScreen) {
        PermanentNavigationDrawer(
            drawerContent = {
                ChatDrawerContent(
                    navController = navController,
                    vm = vm,
                    settings = setting,
                    conversationId = conversation.id,
                    drawerState = drawerState
                )
            }
        ) {
            ChatPageContent(
                inputState = inputState,
                loadingJob = loadingJob,
                setting = setting,
                conversation = conversation,
                isConversationLoaded = isConversationLoaded,
                drawerState = drawerState,
                navController = navController,
                vm = vm,
                chatListState = chatListState,
                enableWebSearch = enableWebSearch,
                currentSearchMode = currentSearchMode,
                currentChatModel = currentChatModel,
                bigScreen = true,
                initialSearchQuery = searchQuery,
                newChatStats = newChatStats
            )
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ChatDrawerContent(
                    navController = navController,
                    vm = vm,
                    settings = setting,
                    conversationId = conversation.id,
                    drawerState = drawerState
                )
            }
        ) {
            ChatPageContent(
                inputState = inputState,
                loadingJob = loadingJob,
                setting = setting,
                conversation = conversation,
                isConversationLoaded = isConversationLoaded,
                drawerState = drawerState,
                navController = navController,
                vm = vm,
                chatListState = chatListState,
                enableWebSearch = enableWebSearch,
                currentSearchMode = currentSearchMode,
                currentChatModel = currentChatModel,
                bigScreen = false,
                initialSearchQuery = searchQuery,
                newChatStats = newChatStats
            )
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    isConversationLoaded: Boolean,
    drawerState: DrawerState,
    navController: NavHostController,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentSearchMode: me.rerere.rikkahub.core.data.model.AssistantSearchMode,
    currentChatModel: Model?,
    initialSearchQuery: String? = null,
    newChatStats: me.rerere.rikkahub.ui.components.chat.NewChatStats,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var isTemporaryChat by rememberSaveable { mutableStateOf(false) }

    var showRegenerateConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRegenerateMessage by rememberSaveable { mutableStateOf<me.rerere.ai.ui.UIMessage?>(null) }
    val currentAssistant = setting.getCurrentAssistant()
    val topMessagePadding = 72.dp

    val uiMessages by vm.uiMessages.collectAsStateWithLifecycle()
    val isSyncingContext by vm.isSyncingContext.collectAsStateWithLifecycle()

    // --- 核心优化：流式自动朗读逻辑 ---
    val tts = LocalTTSState.current
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
                        tts.speak(sentence, flushCalled = false)
                    }
                    lastProcessedIndex = i + 1
                }
                i++
            }

            if (loadingJob == null && lastProcessedIndex < rawContent.length) {
                val remaining = rawContent.substring(lastProcessedIndex).trim()
                if (remaining.isNotEmpty()) {
                    tts.speak(remaining, flushCalled = false)
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
                            conversation.messageNodes.any { it.role == me.rerere.ai.core.MessageRole.USER }
                        },
                        bigScreen = bigScreen,
                        drawerState = drawerState,
                        previewMode = previewMode,
                        isTemporaryChat = isTemporaryChat,
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
                        }
                    )
                },
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp)
            ) { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    ChatList(
                        innerPadding = PaddingValues(top = topMessagePadding, bottom = 140.dp),
                        conversation = conversation,
                        uiItems = uiMessages,
                        state = chatListState,
                        loading = loadingJob != null,
                        previewMode = previewMode,
                        settings = setting,
                        recentlyRestoredNodeIds = vm.recentlyRestoredNodeIds.collectAsStateWithLifecycle().value,
                        initialSearchQuery = initialSearchQuery,
                        onJumpToMessage = { targetNode ->
                            previewMode = false
                            // 跳转逻辑已交由 ChatList 内部统一处理，避免手动计算索引带来的偏差
                        },
                        onRegenerate = { message ->
                            if (vm.canPreserveVersionHistory(message)) {
                                vm.regenerateAtMessage(message, forceWipe = false)
                            } else {
                                pendingRegenerateMessage = message
                                showRegenerateConfirmDialog = true
                            }
                        },
                        onEdit = {
                            inputState.editingMessage = it.id
                            inputState.setContents(it.parts)
                        },
                        onDelete = {
                            val backup = conversation
                            val deletedNodeIds = conversation.messageNodes.map { it.id }.toSet()
                            vm.deleteMessage(it)
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
                        onForkMessage = {
                            scope.launch { vm.forkMessage(it) }
                        },
                        onGetFullMemoryContent = { id, type -> vm.getFullMemoryContent(id, type) }
                    )

                val hasUserSentMessages = remember(conversation.messageNodes) {
                    conversation.messageNodes.any { it.role == me.rerere.ai.core.MessageRole.USER }
                }
                val hasAnyPresetMessages = currentAssistant.presetMessages.isNotEmpty()
                val effectiveDisplaySetting = setting.getEffectiveDisplaySetting(currentAssistant)

                androidx.compose.animation.AnimatedVisibility(
                    visible = isTemporaryChat && !hasUserSentMessages && !hasAnyPresetMessages,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
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
                val hasTextInput = inputState.textContent.text.isNotEmpty() || inputState.messageContent.isNotEmpty()
                val isFirstVirtualChat by vm.isFirstVirtualChat.collectAsStateWithLifecycle()

                // 仅在首次进入虚拟模式时显示 NewChatContent (因为目前 NewChatContent 内部已处理 VirtualWorldWelcome)
                val shouldShowNewChatContent = isConversationLoaded && !isTemporaryChat && !hasUserSentMessages && !hasAnyPresetMessages && !hasTextInput && !isKeyboardOpen && currentAssistant.isVirtualWorldMode && isFirstVirtualChat
                val errorSelectModelText = stringResource(R.string.error_select_model_first)
                androidx.compose.animation.AnimatedVisibility(
                    visible = shouldShowNewChatContent,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.Center).offset(y = 28.dp)
                ) {
                    NewChatContent(
                        assistant = currentAssistant,
                        headerStyle = effectiveDisplaySetting.newChatHeaderStyle,
                        contentStyle = effectiveDisplaySetting.newChatContentStyle,
                        showAvatarInHeader = effectiveDisplaySetting.newChatShowAvatar,
                        stats = newChatStats,
                        hasBackgroundImage = currentAssistant.background != null,
                        onTemplateClick = { prompt -> inputState.setMessageTextAndFocus(prompt, scope) },
                        onNavigateToImageGen = { navController.navigate(Screen.ImageGen) },
                        onAvatarClick = {
                            navController.navigate(Screen.AssistantDetail(id = currentAssistant.id.toString()))
                        }
                    )
                }

                if (showRegenerateConfirmDialog && pendingRegenerateMessage != null) {
                    AlertDialog(
                        onDismissRequest = {
                            showRegenerateConfirmDialog = false
                            pendingRegenerateMessage = null
                        },
                        title = { Text(stringResource(R.string.regenerate_title))},
                        text = {
                            Text(stringResource(R.string.regenerate_description))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingRegenerateMessage?.let { message ->
                                        vm.regenerateAtMessage(message, forceWipe = true)
                                    }
                                    showRegenerateConfirmDialog = false
                                    pendingRegenerateMessage = null
                                }
                            ) { Text(stringResource(R.string.regenerate_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showRegenerateConfirmDialog = false
                                pendingRegenerateMessage = null
                            }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = hasUserSentMessages || hasAnyPresetMessages || isTemporaryChat || !shouldShowNewChatContent,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp).background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
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
                                    vm.handleMessageSend(listOf(me.rerere.ai.ui.UIMessagePart.Text(suggestion)), isTemporaryChat = isTemporaryChat)
                                } else { toaster.show(errorSelectModelText, type = ToastType.Error) }
                            },
                            onCancelClick = { loadingJob?.cancel() },
                            enableSearch = enableWebSearch,
                            onToggleSearch = {
                                if (enableWebSearch) vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Off)
                                else if (setting.searchServices.isNotEmpty()) {
                                    val validIndex = lastProviderIndex.coerceIn(0, setting.searchServices.lastIndex)
                                    vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(validIndex))
                                }
                            },
                            onSendClick = {
                                if (inputState.isEditing()) vm.handleMessageEdit(parts = inputState.getContents(), messageId = inputState.editingMessage!!)
                                else {
                                    if (currentChatModel == null) { toaster.show(errorSelectModelText, type = ToastType.Error); return@MinimalChatInput }
                                    vm.handleMessageSend(inputState.getContents(), isTemporaryChat = isTemporaryChat)
                                }
                                inputState.clearInput()
                            },
                            onLongSendClick = {
                                if (inputState.isEditing()) vm.handleMessageEdit(parts = inputState.getContents(), messageId = inputState.editingMessage!!)
                                else {
                                    if (currentChatModel == null) { toaster.show(errorSelectModelText, type = ToastType.Error); return@MinimalChatInput }
                                    vm.handleMessageSend(content = inputState.getContents(), answer = false, isTemporaryChat = isTemporaryChat)
                                }
                                inputState.clearInput()
                            },
                            onUpdateChatModel = { vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it) },
                            onUpdateAssistant = { updatedAssistant ->
                                vm.updateSettings(setting.copy(assistants = setting.assistants.map { if (it.id == updatedAssistant.id) updatedAssistant else it }))
                            },
                            onUpdateSearchService = { index -> vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(index)) },
                            onClearContext = { vm.startNewTopic() },
                            onUpdateConversation = { updatedConversation -> vm.updateConversation(updatedConversation); vm.saveConversationAsync() },
                            onNavigateToLorebook = { lorebookId -> navController.navigate(Screen.SettingLorebookDetail(lorebookId)) },
                            onRefreshContext = { vm.refreshContext() },
                            onDeleteFile = { vm.deleteFile(it) },
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
                                    vm.handleMessageSend(listOf(me.rerere.ai.ui.UIMessagePart.Text(suggestion)), isTemporaryChat = isTemporaryChat)
                                } else { toaster.show(errorSelectModelText, type = ToastType.Error) }
                            },
                            onCancelClick = { loadingJob?.cancel() },
                            enableSearch = enableWebSearch,
                            onToggleSearch = {
                                if (enableWebSearch) vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Off)
                                else if (setting.searchServices.isNotEmpty()) {
                                    val validIndex = lastProviderIndex.coerceIn(0, setting.searchServices.lastIndex)
                                    vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(validIndex))
                                }
                            },
                            onSendClick = {
                                if (inputState.isEditing()) vm.handleMessageEdit(parts = inputState.getContents(), messageId = inputState.editingMessage!!)
                                else {
                                    if (currentChatModel == null) { toaster.show(errorSelectModelText, type = ToastType.Error); return@ChatInput }
                                    vm.handleMessageSend(inputState.getContents(), isTemporaryChat = isTemporaryChat)
                                }
                                inputState.clearInput()
                            },
                            onLongSendClick = {
                                if (inputState.isEditing()) vm.handleMessageEdit(parts = inputState.getContents(), messageId = inputState.editingMessage!!)
                                else {
                                    if (currentChatModel == null) { toaster.show(errorSelectModelText, type = ToastType.Error); return@ChatInput }
                                    vm.handleMessageSend(content = inputState.getContents(), answer = false, isTemporaryChat = isTemporaryChat)
                                }
                                inputState.clearInput()
                            },
                            onUpdateChatModel = { vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it) },
                            onUpdateAssistant = { updatedAssistant ->
                                vm.updateSettings(setting.copy(assistants = setting.assistants.map { if (it.id == updatedAssistant.id) updatedAssistant else it }))
                            },
                            onUpdateSearchService = { index -> vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(index)) },
                            onClearContext = { vm.startNewTopic() },
                            onUpdateConversation = { updatedConversation -> vm.updateConversation(updatedConversation); vm.saveConversationAsync() },
                            onNavigateToLorebook = { lorebookId -> navController.navigate(Screen.SettingLorebookDetail(lorebookId)) },
                            onRefreshContext = { vm.refreshContext() },
                            onDeleteFile = { vm.deleteFile(it) },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isSyncingContext,
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
                            RunningCircleAnimation(modifier = Modifier.padding(bottom = 24.dp))
                            Text(
                                text = stringResource(R.string.syncing_context_animation_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun RunningCircleAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "running_circle")

    val bounce by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    val horizontalOffset by infiniteTransition.animateFloat(
        initialValue = -50f,
        targetValue = 50f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "horizontal"
    )

    Box(modifier = modifier.height(100.dp).width(150.dp), contentAlignment = Alignment.Center) {
        // Shadow
        val shadowScale by animateFloatAsState(
            targetValue = 0.5f + (0.5f * (1f - bounce)),
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
            label = "shadow_scale"
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = horizontalOffset.dp, y = (-10).dp)
                .size(width = 40.dp, height = 10.dp)
                .graphicsLayer {
                    scaleX = shadowScale
                    alpha = 0.3f * (1f - bounce)
                }
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
        )

        // Circle "TA"
        val verticalOffset = -60f * bounce
        val squeeze = if (bounce < 0.15f) 1f - (0.3f * (1f - bounce / 0.15f)) else 1f
        val stretch = if (bounce > 0.15f) 1f + (0.1f * bounce) else 1f

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = horizontalOffset.dp, y = (verticalOffset - 15).dp)
                .size(40.dp)
                .graphicsLayer {
                    scaleX = stretch
                    scaleY = squeeze
                    rotationZ = horizontalOffset * 2
                }
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primaryContainer
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
                Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
            }
        }
    }
}

private data class TopBarActionState(
    val isEmpty: Boolean,
    val isTemporaryChat: Boolean,
    val shouldUseCompactTemporaryToggle: Boolean,
    val assistantId: kotlin.uuid.Uuid,
    val conversationId: kotlin.uuid.Uuid
)

@Composable
private fun TopBar(
    settings: Settings,
    conversationId: kotlin.uuid.Uuid,
    hasUserMessages: Boolean,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    isTemporaryChat: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateSettings: (Settings) -> Unit,
    onToggleTemporaryChat: () -> Unit
) {
    val scope = rememberCoroutineScope()
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
        Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp).background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.95f), Color.Transparent)
                    )
                )
        )

        Row(
            modifier = Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = { navController.navigateUp() },
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
                onClick = { scope.launch { drawerState.open() } },
                shape = buttonShape,
                color = topContainerColor,
                border = topContainerBorder
            ) {
                Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Menu, "History")
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = currentAssistant.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clickable {
                        navController.navigate(Screen.AssistantDetail(id = currentAssistant.id.toString()))
                    }
            )

            Spacer(Modifier.weight(1f))

            val topPillScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (animateTopPillIn) 1f else 0.88f,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "top_pill_scale"
            )

            Surface(
                shape = buttonShape,
                color = topContainerColor,
                border = topContainerBorder,
                modifier = Modifier.graphicsLayer { scaleX = topPillScale; scaleY = topPillScale }
            ) {
                androidx.compose.animation.AnimatedContent(
                    targetState = TopBarActionState(
                        isEmpty = isEmpty,
                        isTemporaryChat = isTemporaryChat,
                        shouldUseCompactTemporaryToggle = run {
                            val hasPresetMessages = currentAssistant.presetMessages.isNotEmpty()
                            val effectiveDisplay = settings.getEffectiveDisplaySetting(currentAssistant)
                            val headerShowsAvatar = effectiveDisplay.newChatShowAvatar && (
                                effectiveDisplay.newChatHeaderStyle == me.rerere.rikkahub.data.datastore.NewChatHeaderStyle.BIG_ICON ||
                                    effectiveDisplay.newChatHeaderStyle == me.rerere.rikkahub.data.datastore.NewChatHeaderStyle.GREETING
                                )
                            !hasPresetMessages && headerShowsAvatar
                        },
                        assistantId = currentAssistant.id,
                        conversationId = conversationId
                    ),
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f)) + androidx.compose.animation.scaleIn(initialScale = 0.92f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f))) togetherWith (androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 400f)) + androidx.compose.animation.scaleOut(targetScale = 0.92f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 400f))) using androidx.compose.animation.SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f) })
                    },
                    label = "topbar_actions"
                ) { actionState ->
                    val isEmptyState = actionState.isEmpty
                    val isTempChat = actionState.isTemporaryChat
                    val hideTopRightAvatar = actionState.shouldUseCompactTemporaryToggle
                    when {
                        isEmptyState && !isTempChat && hideTopRightAvatar -> {
                            Row(modifier = Modifier.height(topPillSize), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onUpdateSettings(settings.copy(autoPlayTts = !settings.autoPlayTts)) }, modifier = Modifier.size(topPillSize)) {
                                    Icon(if (settings.autoPlayTts) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Auto Play TTS")
                                }
                                IconButton(onClick = { onToggleTemporaryChat() }, modifier = Modifier.size(topPillSize)) {
                                    Icon(Icons.Rounded.HistoryToggleOff, "Temporary Chat")
                                }
                            }
                        }
                        else -> Row(modifier = Modifier.height(topPillSize), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onUpdateSettings(settings.copy(autoPlayTts = !settings.autoPlayTts)) }, modifier = Modifier.size(topPillSize)) {
                                Icon(if (settings.autoPlayTts) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Auto Play TTS")
                            }
                            when {
                                isEmptyState && !isTempChat -> {
                                    IconButton(onClick = { onToggleTemporaryChat() }, modifier = Modifier.size(topPillSize)) {
                                        Icon(Icons.Rounded.HistoryToggleOff, "Temporary Chat")
                                    }
                                    Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                                        me.rerere.rikkahub.ui.components.ui.UIAvatar(name = currentAssistant.name.ifBlank { "Character" }, value = currentAssistant.avatar, modifier = Modifier.size(30.dp), onClick = { showAssistantPicker = true })
                                    }
                                }
                                isEmptyState && isTempChat -> {
                                    IconButton(onClick = { onToggleTemporaryChat() }, modifier = Modifier.size(topPillSize)) {
                                        Icon(Icons.Rounded.History, "Make Normal Chat")
                                    }
                                    Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                                        me.rerere.rikkahub.ui.components.ui.UIAvatar(name = currentAssistant.name.ifBlank { "Character" }, value = currentAssistant.avatar, modifier = Modifier.size(30.dp), onClick = { showAssistantPicker = true })
                                    }
                                }
                                else -> {
                                    IconButton(onClick = { onClickMenu() }, modifier = Modifier.size(topPillSize)) {
                                        Icon(if (previewMode) Icons.Rounded.Close else Icons.Rounded.Search, "Chat Options")
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

    if (showAssistantPicker) {
        val assistantState = me.rerere.rikkahub.ui.hooks.rememberAssistantState(settings, onUpdateSettings)
        me.rerere.rikkahub.ui.components.ai.AssistantPickerSheet(
            settings = settings,
            currentAssistant = currentAssistant,
            onAssistantSelected = { selectedAssistant ->
                assistantState.setSelectAssistant(selectedAssistant)
                showAssistantPicker = false
            },
            onDismiss = { showAssistantPicker = false }
        )
    }

}
