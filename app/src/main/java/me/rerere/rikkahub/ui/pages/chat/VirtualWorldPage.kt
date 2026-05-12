package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.ui.components.ai.MinimalChatInput
import me.rerere.rikkahub.ui.components.chat.NewChatContent
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberChatInputState
import me.rerere.rikkahub.ui.theme.AssistantChatTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid
import me.rerere.rikkahub.ui.components.chat.groupIntoTurns

@Composable
fun VirtualWorldPage(id: Uuid) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 监听 Toast 信号
    LaunchedEffect(Unit) {
        vm.toastFlow.collect { message ->
            if (message.startsWith("NAVIGATE_NEW_CHAT:")) {
                val newId = message.substringAfter("NAVIGATE_NEW_CHAT:")
                navController.navigate(Screen.Chat(id = newId)) {
                    popUpTo(Screen.VirtualWorld(id = id.toString())) { inclusive = true }
                }
            } else {
                toaster.show(message)
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            toaster.show(error.message ?: "Error", type = ToastType.Error)
        }
    }

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val uiMessages by vm.uiMessages.collectAsStateWithLifecycle()
    val isConversationLoaded by vm.isConversationLoaded.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle()

    val isAnyJobRunning by remember(loadingJob, conversationJobs) {
        derivedStateOf {
            loadingJob?.isActive == true || conversationJobs.values.any { it?.isActive == true }
        }
    }

    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val currentSearchMode by vm.currentSearchMode.collectAsStateWithLifecycle()
    val newChatStats by vm.newChatStats.collectAsStateWithLifecycle()
    val currentAssistant = setting.getCurrentAssistant()

    val inputState = rememberChatInputState(message = emptyList())
    var previewMode by rememberSaveable { mutableStateOf(false) }

    // --- 补全 TTS 自动朗读逻辑 ---
    val tts = LocalTTSState.current
    var lastProcessedMessageId by remember { mutableStateOf<Uuid?>(null) }
    var lastProcessedIndex by remember { mutableStateOf(0) }

    LaunchedEffect(conversation, isAnyJobRunning, setting.autoPlayTts) {
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
                if (lastProcessedMessageId == null && !isAnyJobRunning) {
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

            if (!isAnyJobRunning && lastProcessedIndex < rawContent.length) {
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

    LaunchedEffect(isAnyJobRunning) {
        inputState.loading = isAnyJobRunning
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

    AssistantChatTheme(assistant = currentAssistant) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            AssistantBackground(setting = setting)

            Scaffold(
                topBar = {
                    VirtualTopBar(
                        assistantName = currentAssistant.name,
                        settings = setting,
                        hasMessages = remember(conversation.messageNodes) {
                            conversation.messageNodes.any { it.role == me.rerere.ai.core.MessageRole.USER }
                        },
                        previewMode = previewMode,
                        onBack = { navController.navigateUp() },
                        onTitleClick = {
                            navController.navigate(Screen.AssistantDetail(id = currentAssistant.id.toString()))
                        },
                        onNewTopic = { vm.startNewTopic() },
                        onTogglePreview = { previewMode = !previewMode },
                        onUpdateSettings = { vm.updateSettings(it) }
                    )
                },
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp)
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    ChatList(
                        innerPadding = PaddingValues(top = 72.dp, bottom = 140.dp),
                        conversation = conversation,
                        uiItems = uiMessages,
                        state = chatListState,
                        loading = isAnyJobRunning,
                        previewMode = previewMode,
                        settings = setting,
                        recentlyRestoredNodeIds = vm.recentlyRestoredNodeIds.collectAsStateWithLifecycle().value,
                        onRegenerate = { message -> vm.regenerateAtMessage(message, forceWipe = false) },
                        onEdit = {
                            inputState.editingMessage = it.id
                            inputState.setContents(it.parts)
                        },
                        onDelete = { vm.deleteMessage(it) },
                        onUpdateMessage = { newNode ->
                            vm.updateMessageNodeInAnyConversation(newNode)
                        },
                        onForkMessage = { scope.launch { vm.forkMessage(it) } },
                        onGetFullMemoryContent = { id, type -> vm.getFullMemoryContent(id, type) },
                        onJumpToMessage = { targetNode ->
                            previewMode = false
                            // 跳转逻辑已交由 ChatList 内部处理
                        }
                    )

                    val hasUserSentMessages = remember(conversation.messageNodes) {
                        conversation.messageNodes.any { it.role == me.rerere.ai.core.MessageRole.USER }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isConversationLoaded && !hasUserSentMessages && currentAssistant.presetMessages.isEmpty(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center).offset(y = 28.dp)
                    ) {
                        NewChatContent(
                            assistant = currentAssistant,
                            headerStyle = me.rerere.rikkahub.data.datastore.NewChatHeaderStyle.BIG_ICON,
                            contentStyle = me.rerere.rikkahub.data.datastore.NewChatContentStyle.NONE,
                            stats = newChatStats,
                            onTemplateClick = { prompt -> inputState.setMessageTextAndFocus(prompt, scope) }
                        )
                    }

                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp).background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                            )
                        )
                    )

                    MinimalChatInput(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        state = inputState,
                        settings = setting,
                        conversation = conversation,
                        mcpManager = vm.mcpManager,
                        chatSuggestions = conversation.chatSuggestions,
                        onClickSuggestion = { suggestion ->
                             if (currentChatModel != null) {
                                 vm.handleMessageSend(listOf(me.rerere.ai.ui.UIMessagePart.Text(suggestion)))
                             }
                        },
                        onCancelClick = {
                            loadingJob?.cancel()
                            conversationJobs.values.forEach { it?.cancel() }
                        },
                        enableSearch = enableWebSearch,
                        onToggleSearch = {
                            if (enableWebSearch) vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Off)
                            else if (setting.searchServices.isNotEmpty()) {
                                vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(0))
                            }
                        },
                        onSendClick = {
                            if (inputState.isEditing()) vm.handleMessageEdit(parts = inputState.getContents(), messageId = inputState.editingMessage!!)
                            else {
                                if (currentChatModel != null) {
                                    vm.handleMessageSend(inputState.getContents())
                                }
                            }
                            inputState.clearInput()
                        },
                        onLongSendClick = {
                            if (inputState.isEditing()) vm.handleMessageEdit(parts = inputState.getContents(), messageId = inputState.editingMessage!!)
                            else {
                                if (currentChatModel != null) {
                                    vm.handleMessageSend(content = inputState.getContents(), answer = false)
                                }
                            }
                            inputState.clearInput()
                        },
                        onUpdateChatModel = { vm.setChatModel(assistant = currentAssistant, model = it) },
                        onUpdateAssistant = { updatedAssistant ->
                            vm.updateSettings(setting.copy(assistants = setting.assistants.map { if (it.id == updatedAssistant.id) updatedAssistant else it }))
                        },
                        onUpdateSearchService = { index -> vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(index)) },
                        onUpdateConversation = { updatedConversation -> vm.updateConversation(updatedConversation); vm.saveConversationAsync() },
                        onNavigateToLorebook = { lorebookId -> navController.navigate(Screen.SettingLorebookDetail(lorebookId)) },
                        onRefreshContext = { vm.refreshContext() },
                        onDeleteFile = { vm.deleteFile(it) },
                        onClearContext = { vm.startNewTopic() }
                    )
                }
            }
        }
    }
}

@Composable
private fun VirtualTopBar(
    assistantName: String,
    settings: Settings,
    hasMessages: Boolean,
    previewMode: Boolean,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onNewTopic: () -> Unit,
    onTogglePreview: () -> Unit,
    onUpdateSettings: (Settings) -> Unit
) {
    val buttonShape = RoundedCornerShape(999.dp)
    val topPillSize = 48.dp
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.background)

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp).background(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.95f), Color.Transparent)
            )
        ))

        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = containerColor,
                border = border,
                modifier = Modifier.size(topPillSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = assistantName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = onTitleClick
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(Modifier.weight(1f))

            Surface(
                shape = buttonShape,
                color = containerColor,
                border = border,
                modifier = Modifier.height(topPillSize)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onUpdateSettings(settings.copy(autoPlayTts = !settings.autoPlayTts)) },
                        modifier = Modifier.size(topPillSize)
                    ) {
                        Icon(
                            imageVector = if (settings.autoPlayTts) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                            contentDescription = "TTS",
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    if (hasMessages) {
                        IconButton(onClick = onTogglePreview, modifier = Modifier.size(topPillSize)) {
                            Icon(
                                imageVector = if (previewMode) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = "Search",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    IconButton(onClick = onNewTopic, modifier = Modifier.size(topPillSize)) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "New Topic",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
