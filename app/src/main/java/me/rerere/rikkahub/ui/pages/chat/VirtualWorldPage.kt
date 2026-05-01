package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.data.datastore.ChatInputStyle
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.MinimalChatInput
import me.rerere.rikkahub.ui.components.chat.NewChatContent
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.rememberChatInputState
import me.rerere.rikkahub.ui.theme.AssistantChatTheme
import me.rerere.rikkahub.utils.navigateToChatPage
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
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val currentSearchMode by vm.currentSearchMode.collectAsStateWithLifecycle()
    val newChatStats by vm.newChatStats.collectAsStateWithLifecycle()
    val currentAssistant = setting.getCurrentAssistant()

    val inputState = rememberChatInputState(message = emptyList())

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
                        loading = loadingJob != null,
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
                            // 虚拟模式下需要跨物理会话查找节点
                            vm.updateMessageNodeInAnyConversation(newNode)
                        },
                        onForkMessage = { scope.launch { vm.forkMessage(it) } }
                    )

                    val hasUserSentMessages = remember(conversation.messageNodes) {
                        conversation.messageNodes.any { it.role == me.rerere.ai.core.MessageRole.USER }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isConversationLoaded && !hasUserSentMessages && currentAssistant.presetMessages.isEmpty(),
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(),
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
                        onCancelClick = { loadingJob?.cancel() },
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
                        onUpdateAssistant = { /* Virtual mode doesn't allow switching assistant inside */ },
                        onUpdateSearchService = { index -> vm.updateAssistantSearchMode(me.rerere.rikkahub.core.data.model.AssistantSearchMode.Provider(index)) },
                        onUpdateConversation = { updatedConversation -> vm.updateConversation(updatedConversation); vm.saveConversationAsync() },
                        onNavigateToLorebook = { lorebookId -> navController.navigate(Screen.SettingLorebookDetail(lorebookId)) },
                        onRefreshContext = { vm.refreshContext() },
                        onDeleteFile = { vm.deleteFile(it) },
                        onClearContext = { vm.startNewTopic() } // 对接新话题逻辑
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
        Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp).background(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.95f), Color.Transparent)
            )
        ))

        Row(
            modifier = Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onBack,
                shape = buttonShape,
                color = containerColor,
                border = border
            ) {
                Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = assistantName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            )

            Spacer(Modifier.weight(1f))

            Surface(
                onClick = onNewTopic,
                shape = buttonShape,
                color = containerColor,
                border = border
            ) {
                Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, "New Topic")
                }
            }
        }
    }
}
