package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.common.ui.components.ConfirmDialog
import me.rerere.rikkahub.common.ui.components.InputDialog
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.core.data.model.AssistantSearchMode
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.data.datastore.ChatInputStyle
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.service.voice.VoiceRecorderController
import me.rerere.rikkahub.service.voice.VoiceRecorderResult
import me.rerere.rikkahub.service.voice.VoiceRecorderState
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.MinimalChatInput
import me.rerere.rikkahub.ui.components.chat.groupIntoTurns
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.rememberChatInputState
import me.rerere.rikkahub.ui.hooks.QuotedMessage
import me.rerere.rikkahub.ui.theme.AssistantChatTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * 临时会话独立页面（独立 Navigation Destination，非正式聊天页的模式开关）。
 *
 * - 消息列表/输入状态与正式聊天完全独立，不读取正式消息分页
 * - 顶部仅提供返回 + 临时会话标识；无新建话题、无角色切换
 * - 退出（系统返回/手势/左上角返回）统一走确认弹窗；确认后销毁全部临时数据
 * - 空会话（无用户消息）退出不弹确认
 *
 * 主题仍按当前助手 AssistantChatTheme 渲染，深浅色跟随 MaterialTheme 语义色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemporaryChatPage(assistantId: Uuid) {
    val vm: TemporaryChatVM = koinViewModel(
        parameters = { parametersOf(assistantId.toString()) }
    )
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val tts = LocalTTSState.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val isAiTyping by vm.isAiTyping.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val voiceTranscribing by vm.voiceTranscribing.collectAsStateWithLifecycle()
    val currentAssistant = vm.assistant.collectAsStateWithLifecycle().value
    val overrideModelId by vm.overrideChatModelId.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        launch {
            vm.toastFlow.collect { message ->
                toaster.show(message, type = ToastType.Error)
            }
        }
        launch {
            vm.errorFlow.collect { error ->
                toaster.show(error.message ?: "Error", type = ToastType.Error)
            }
        }
    }

    val inputState = rememberChatInputState()
    val chatListState = rememberLazyListState()
    LaunchedEffect(Unit) {
        vm.scrollToBottomEvent.collect {
            // 倒序列表：index=0 即为最新消息（底部）
            chatListState.animateScrollToItem(0)
        }
    }

    // ===== 未读角标联动 =====
    // 临时会话自身不产生未读记录（消息不落库），但必须登记/撤销「正在前台查看」：
    // 停留在临时会话期间，其他助手正式会话的回复应正确计入未读。
    // ON_RESUME 重新登记、ON_PAUSE 与离开组合撤销；登记/撤销均幂等。
    LaunchedEffect(conversation.id) {
        vm.notifyTemporaryConversationViewed()
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val unreadLifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    vm.notifyTemporaryConversationViewed()

                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    vm.notifyTemporaryConversationViewStopped()

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(unreadLifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(unreadLifecycleObserver)
            vm.notifyTemporaryConversationViewStopped()
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

    fun fillTranscribedToInput(text: String, tempFile: java.io.File?) {
        inputState.setMessageTextAndFocus(text, scope)
        // 转文字模式不保留录音文件，避免堆积临时文件
        tempFile?.let { file ->
            scope.launch {
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }

    fun handleVoiceMessageReady(result: VoiceRecorderResult?, showTranscription: Boolean) {
        if (result == null) {
            val isCancelled = voiceRecorderController.state.value == VoiceRecorderState.Cancelled
            if (!isCancelled) {
                toaster.show(context.getString(R.string.chat_voice_too_short), ToastType.Info)
            }
            return
        }
        if (showTranscription) {
            // 右上滑"转文字"：不发送语音消息，把录音转成文本填入输入框供用户编辑
            vm.transcribeVoiceToInput(result.uri) { text ->
                if (text.isNullOrBlank()) {
                    toaster.show(context.getString(R.string.chat_voice_transcribe_failed), ToastType.Error)
                } else {
                    fillTranscribedToInput(text, result.file)
                }
            }
            return
        }
        vm.sendVoiceMessage(result.uri, result.durationMs, quotedMessages = inputState.quotedMessages)
        inputState.clearQuotedMessages()
    }

    // 录音期间按下（进入 Recording）时的钩子保留给并行流式 ASR 的扩展位（临时页未启用）
    LaunchedEffect(voiceRecorderController) {
        voiceRecorderController.autoStopEvents.collect {
            val result = voiceRecorderController.stop()
            handleVoiceMessageReady(result, showTranscription = false)
        }
    }

    // --- 退出逻辑：统一确认 ---
    var showExitConfirm by remember { mutableStateOf(false) }

    // --- 刷新回复（重新生成）：与正式聊天页相同的两段式确认 ---
    var pendingRegenerateMessage by remember { mutableStateOf<me.rerere.ai.ui.UIMessage?>(null) }
    var showRegenerateRequirementDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirmDialog by remember { mutableStateOf(false) }
    var pendingRegenerateRequirement by remember { mutableStateOf<String?>(null) }

    fun exitTemporaryChat(destroy: Boolean) {
        if (destroy) {
            // 停止临时消息的音频播放，销毁会话数据后返回正式聊天
            tts.stop()
            vm.requestDestroy()
        }
        navController.popBackStack()
    }
    val handleBack: () -> Unit = {
        if (vm.hasUserContent()) {
            showExitConfirm = true
        } else {
            // 空会话直接退出，不必强制弹窗
            exitTemporaryChat(destroy = true)
        }
    }
    BackHandler(enabled = true, onBack = handleBack)

    // 消息列表项组装：单会话时间线，无跨会话 Separator、无通话记录
    val items by remember(conversation.messageNodes, isAiTyping) {
        derivedStateOf {
            conversation.messageNodes.groupIntoTurns().reversed().mapIndexed { index, turn ->
                ChatUIItem.Turn(
                    group = turn.copy(nodes = turn.nodes.reversed()),
                    isGenerating = index == 0 && isAiTyping
                )
            }
        }
    }

    val assistantForTheme = currentAssistant
    if (assistantForTheme == null) {
        // 助手信息未就绪：保持空 Surface，避免闪现默认主题
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {}
        return
    }

    // 临时会话内 ChatInput/ChatList 必须以进入时固定的助手为当前角色：
    // 覆盖 settings.assistantId 指针，并把会话内覆盖的助手配置拼接进助手列表
    // （世界书/推理/UI模式/搜索的会话内修改实时反映到 UI，但不写回正式配置）
    val settingsForInput = remember(setting, assistantForTheme) {
        setting.copy(
            assistantId = assistantForTheme.id,
            assistants = setting.assistants.map {
                if (it.id == assistantForTheme.id) assistantForTheme else it
            }
        )
    }
    // 输入区布局跟随用户的极简/完整设置（助手级 uiSettings.chatInputStyle 优先，与正式聊天页一致）
    val effectiveDisplaySetting = remember(setting, assistantForTheme) {
        setting.getEffectiveDisplaySetting(assistantForTheme)
    }
    // 会话内联网搜索状态：跟随覆盖后的助手配置（与正式页 enableWebSearch 判定一致）
    val enableWebSearch = when (assistantForTheme.searchMode) {
        is AssistantSearchMode.Off -> false
        else -> true
    }
    var lastSearchProviderIndex by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(assistantForTheme.searchMode) {
        val mode = assistantForTheme.searchMode
        if (mode is AssistantSearchMode.Provider) lastSearchProviderIndex = mode.index
    }

    AssistantChatTheme(assistant = assistantForTheme) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                // 微信模式下 AI 思考/输出过程中，标题临时替换为「正在输入中...」
                                //（与正式聊天页微信模式 typing 提示同文案）
                                Text(
                                    text = if (isAiTyping && effectiveDisplaySetting.wechatMode) {
                                        stringResource(R.string.chat_status_typing)
                                    } else {
                                        stringResource(R.string.temporary_chat_title)
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = assistantForTheme.name.ifBlank { "Character" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            // 返回键 + 临时会话标识（标识移到左侧，紧跟返回键）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = handleBack) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                                }
                                Icon(
                                    imageVector = Icons.Rounded.HistoryToggleOff,
                                    contentDescription = stringResource(R.string.temporary_chat_title),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .size(20.dp)
                                )
                            }
                        },
                        actions = {
                            // 只读角色头像（不可点击切换）
                            UIAvatar(
                                name = assistantForTheme.name.ifBlank { "Character" },
                                value = assistantForTheme.avatar,
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .size(30.dp)
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
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
                        innerPadding = PaddingValues(top = 8.dp, bottom = 84.dp),
                        conversation = conversation,
                        items = items,
                        paginationState = null,
                        isHistoryLoading = false,
                        state = chatListState,
                        loading = loadingJob != null,
                        previewMode = false,
                        settings = settingsForInput,
                        recentlyRestoredNodeIds = emptySet(),
                        shownTranscriptions = vm.shownTranscriptions,
                        voiceMessagePlayer = vm.voiceMessagePlayer,
                        onDelete = { message ->
                            vm.deleteMessage(message.id)
                        },
                        onRegenerate = { message ->
                            // 刷新回复：与正式聊天页一致，先弹「重新生成要求」输入弹窗
                            pendingRegenerateMessage = message
                            showRegenerateRequirementDialog = true
                        },
                        onQuote = { message ->
                            // 引用消息：与正式聊天页一致（语音等空内容消息用占位描述）
                            val rawContent = message.toContentText()
                            val quoteContent = if (rawContent.isBlank()) {
                                when {
                                    message.parts.any { it is me.rerere.ai.ui.UIMessagePart.Image } -> "[图片]"
                                    message.parts.any { it is me.rerere.ai.ui.UIMessagePart.Video } -> "[视频]"
                                    message.parts.any { it is me.rerere.ai.ui.UIMessagePart.Audio } -> "[语音]"
                                    message.parts.any { it is me.rerere.ai.ui.UIMessagePart.Document } -> "[文件]"
                                    else -> "[消息]"
                                }
                            } else {
                                if (rawContent.length > 200) rawContent.take(200) + "…" else rawContent
                            }
                            val rawPreview = message.toPreviewText()
                            val displayContent = if (rawPreview.isBlank()) {
                                quoteContent
                            } else {
                                if (rawPreview.length > 200) rawPreview.take(200) + "…" else rawPreview
                            }
                            val senderName = if (message.role == MessageRole.USER) {
                                setting.displaySetting.userNickname.ifBlank { "User" }
                            } else {
                                assistantForTheme.name.ifBlank { "Assistant" }
                            }
                            inputState.addQuotedMessage(
                                QuotedMessage(
                                    senderName = senderName,
                                    content = quoteContent,
                                    isUser = message.role == MessageRole.USER,
                                    displayContent = displayContent
                                )
                            )
                            // 引用与编辑互斥（与正式页一致）
                            inputState.editingMessage = null
                            inputState.setMessageTextAndFocus(inputState.textContent.text.toString(), scope)
                        },
                        onGetFullMemoryContent = { memoryId, memoryType ->
                            // 临时会话允许查看记忆详情（只读）
                            vm.getFullMemoryContent(memoryId, memoryType)
                        },
                    )

                    // 空会话占位提示
                    AnimatedVisibility(
                        visible = conversation.currentMessages.isEmpty(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center)
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
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        when (effectiveDisplaySetting.chatInputStyle) {
                            ChatInputStyle.MINIMAL -> {
                                MinimalChatInput(
                                    modifier = Modifier.fillMaxWidth(),
                                    state = inputState,
                                    settings = settingsForInput,
                                    conversation = conversation,
                                    mcpManager = vm.mcpManager,
                                    isAiTyping = isAiTyping,
                                    chatSuggestions = conversation.chatSuggestions,
                                    onClickSuggestion = { suggestion ->
                                        vm.handleMessageSend(listOf(me.rerere.ai.ui.UIMessagePart.Text(suggestion)))
                                    },
                                    onCancelClick = { vm.stopGeneration() },
                                    enableSearch = enableWebSearch,
                                    onToggleSearch = { enabled ->
                                        if (enabled) {
                                            if (setting.searchServices.isNotEmpty()) {
                                                val validIndex = lastSearchProviderIndex.coerceIn(0, setting.searchServices.lastIndex)
                                                vm.updateSessionSearchMode(AssistantSearchMode.Provider(validIndex))
                                            }
                                        } else {
                                            vm.updateSessionSearchMode(AssistantSearchMode.Off)
                                        }
                                    },
                                    onSendClick = {
                                        vm.handleMessageSend(inputState.getSendContents())
                                        inputState.clearInput()
                                    },
                                    onLongSendClick = {
                                        vm.handleMessageSend(
                                            content = inputState.getSendContents(),
                                            answer = false
                                        )
                                        inputState.clearInput()
                                    },
                                    onUpdateChatModel = { vm.setChatModel(it) },
                                    onUpdateAssistant = { vm.updateSessionAssistant(it) },
                                    onUpdateConversation = { vm.updateConversationModes(it) },
                                    onUpdateSearchService = { index ->
                                        lastSearchProviderIndex = index
                                        vm.updateSessionSearchMode(AssistantSearchMode.Provider(index))
                                    },
                                    onClearContext = {},
                                    onDeleteFile = { vm.deleteFile(it) },
                                    onConsolidate = {},
                                    voiceRecorderController = voiceRecorderController,
                                    voiceTranscribing = voiceTranscribing,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    onVoiceMessageReady = { result, showTranscription ->
                                        handleVoiceMessageReady(result, showTranscription)
                                    },
                                    temporaryMode = true,
                                    overrideChatModelId = overrideModelId,
                                )
                            }

                            else -> {
                                ChatInput(
                                    modifier = Modifier.fillMaxWidth(),
                                    state = inputState,
                                    settings = settingsForInput,
                                    conversation = conversation,
                                    mcpManager = vm.mcpManager,
                                    isAiTyping = isAiTyping,
                                    chatSuggestions = conversation.chatSuggestions,
                                    onClickSuggestion = { suggestion ->
                                        vm.handleMessageSend(listOf(me.rerere.ai.ui.UIMessagePart.Text(suggestion)))
                                    },
                                    onCancelClick = { vm.stopGeneration() },
                                    enableSearch = enableWebSearch,
                                    onToggleSearch = { enabled ->
                                        if (enabled) {
                                            if (setting.searchServices.isNotEmpty()) {
                                                val validIndex = lastSearchProviderIndex.coerceIn(0, setting.searchServices.lastIndex)
                                                vm.updateSessionSearchMode(AssistantSearchMode.Provider(validIndex))
                                            }
                                        } else {
                                            vm.updateSessionSearchMode(AssistantSearchMode.Off)
                                        }
                                    },
                                    onSendClick = {
                                        vm.handleMessageSend(inputState.getSendContents())
                                        inputState.clearInput()
                                    },
                                    onLongSendClick = {
                                        vm.handleMessageSend(
                                            content = inputState.getSendContents(),
                                            answer = false
                                        )
                                        inputState.clearInput()
                                    },
                                    onUpdateChatModel = { vm.setChatModel(it) },
                                    onUpdateAssistant = { vm.updateSessionAssistant(it) },
                                    onUpdateConversation = { vm.updateConversationModes(it) },
                                    onUpdateSearchService = { index ->
                                        lastSearchProviderIndex = index
                                        vm.updateSessionSearchMode(AssistantSearchMode.Provider(index))
                                    },
                                    onClearContext = {},
                                    onDeleteFile = { vm.deleteFile(it) },
                                    onConsolidate = {},
                                    voiceRecorderController = voiceRecorderController,
                                    voiceTranscribing = voiceTranscribing,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    onVoiceMessageReady = { result, showTranscription ->
                                        handleVoiceMessageReady(result, showTranscription)
                                    },
                                    temporaryMode = true,
                                    overrideChatModelId = overrideModelId,
                                )
                            }
                        }
                    }
                }
            }

            if (showExitConfirm) {
                ConfirmDialog(
                    onDismissRequest = { showExitConfirm = false },
                    onConfirm = {
                        showExitConfirm = false
                        exitTemporaryChat(destroy = true)
                    },
                    title = stringResource(R.string.temporary_chat_exit_title),
                    text = stringResource(R.string.temporary_chat_exit_message),
                    confirmText = stringResource(R.string.temporary_chat_exit_confirm),
                    dismissText = stringResource(R.string.temporary_chat_exit_cancel),
                    dangerous = true
                )
            }

            // 刷新回复：先输入要求（可留空），turn 结构不支持保留版本历史时再确认整轮删除
            if (showRegenerateRequirementDialog && pendingRegenerateMessage != null) {
                var requirement by remember { mutableStateOf("") }
                InputDialog(
                    onDismissRequest = {
                        showRegenerateRequirementDialog = false
                        pendingRegenerateMessage = null
                    },
                    onConfirm = {
                        pendingRegenerateMessage?.let { message ->
                            val req = requirement.ifBlank { null }
                            if (vm.canPreserveVersionHistory(message)) {
                                vm.regenerateAtMessage(message, forceWipe = false, requirement = req)
                            } else {
                                pendingRegenerateRequirement = req
                                showRegenerateConfirmDialog = true
                            }
                        }
                        showRegenerateRequirementDialog = false
                        pendingRegenerateMessage = null
                    },
                    title = stringResource(R.string.chat_page_regenerate_requirement_title),
                    value = requirement,
                    onValueChange = { requirement = it },
                    confirmText = stringResource(R.string.regenerate_confirm),
                    dismissText = stringResource(R.string.cancel),
                    placeholder = stringResource(R.string.chat_page_regenerate_requirement_placeholder),
                    label = stringResource(R.string.chat_page_regenerate_requirement_label),
                )
            }

            if (showRegenerateConfirmDialog && pendingRegenerateMessage != null) {
                ConfirmDialog(
                    onDismissRequest = {
                        showRegenerateConfirmDialog = false
                        pendingRegenerateMessage = null
                        pendingRegenerateRequirement = null
                    },
                    onConfirm = {
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
                    },
                    title = stringResource(R.string.regenerate_title),
                    text = stringResource(R.string.regenerate_description),
                    confirmText = stringResource(R.string.regenerate_confirm),
                    dismissText = stringResource(R.string.cancel)
                )
            }
        }
    }
}
