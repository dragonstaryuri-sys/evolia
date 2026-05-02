package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.ui.components.ai.MinimalChatInput
import me.rerere.rikkahub.ui.components.chat.NewChatContent
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberChatInputState
import me.rerere.rikkahub.ui.theme.AssistantChatTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

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

    // 监听 Toast 信号（处理新话题反馈）
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

    // 聚合判断：在虚拟聚合模式下，追踪所有活跃的任务。
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

    // 同步聚合加载状态到输入框
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
                        onBack = { navController.navigateUp() },
                        onNewTopic = { vm.startNewTopic() }
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
                        previewMode = false,
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
                        onGetFullMemoryContent = { id, type -> vm.getFullMemoryContent(id, type) }
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

                    // Bottom Gradient
                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp).background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                            )
                        )
                    )

                    // Unified Virtual Input (Minimal)
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
                                 // 基于聚合列表长度滚动
                                 scope.launch { chatListState.requestScrollToItem(uiMessages.size + 5) }
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
                                    scope.launch { chatListState.requestScrollToItem(uiMessages.size + 5) }
                                }
                            }
                            inputState.clearInput()
                        },
                        onLongSendClick = {
                            if (inputState.isEditing()) vm.handleMessageEdit(parts = inputState.getContents(), messageId = inputState.editingMessage!!)
                            else {
                                if (currentChatModel != null) {
                                    vm.handleMessageSend(content = inputState.getContents(), answer = false)
                                    scope.launch { chatListState.requestScrollToItem(uiMessages.size + 5) }
                                }
                            }
                            inputState.clearInput()
                        },
                        onUpdateChatModel = { vm.setChatModel(assistant = currentAssistant, model = it) },
                        onUpdateAssistant = { },
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
    onBack: () -> Unit,
    onNewTopic: () -> Unit
) {
    val buttonShape = RoundedCornerShape(999.dp)
    val topPillSize = 48.dp
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.background)

    Box(modifier = Modifier.fillMaxWidth()) {
        // 顶部阴影/渐变背景，和普通模式保持一致
        Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp).background(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.95f), Color.Transparent)
            )
        ))

        Row(
            modifier = Modifier
                .statusBarsPadding() // 核心：空出状态栏位置，解决重合问题
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
                modifier = Modifier.align(Alignment.CenterVertically)
            )

            Spacer(Modifier.weight(1f))

            Surface(
                onClick = onNewTopic,
                shape = CircleShape,
                color = containerColor,
                border = border,
                modifier = Modifier.size(topPillSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Add, // 换成和普通模式一致的 Add 图标
                        contentDescription = "New Topic",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
