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
import me.rerere.rikkahub.data.datastore.isMuteTime
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.QuotedMessage
import me.rerere.rikkahub.ui.hooks.rememberChatInputState
import me.rerere.rikkahub.ui.theme.AssistantChatTheme
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.base64UrlDecode
import me.rerere.rikkahub.utils.createChatFilesByContents
import me.rerere.rikkahub.utils.getFileMimeType
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.ui.components.chat.groupIntoTurns
import me.rerere.rikkahub.data.ai.tools.CameraCaptureHub
import me.rerere.rikkahub.ui.components.camera.CameraCaptureOverlay

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
            text?.base64UrlDecode() ?: ""
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

    // 通话期间禁止向当前通话 AI 发送普通消息
    val activeCallConversationId by vm.activeCallConversationId.collectAsStateWithLifecycle()
    val isCurrentAssistantInCall = activeCallConversationId != null && activeCallConversationId == conversation.id
    val inCallAssistantName = vm.getActiveCallAssistantName() ?: "AI"

    // 通话前动态申请录音权限（Manifest 声明不足以触发 SystemASR 的 SpeechRecognizer）
    var pendingCallConvId by remember { mutableStateOf<Uuid?>(null) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val convId = pendingCallConvId
        pendingCallConvId = null
        if (granted && convId != null) {
            // 权限弹窗期间可能已有通话进入，再次校验单通话限制
            if (!vm.canStartCall()) {
                val name = vm.getActiveCallAssistantName() ?: "AI"
                toaster.show("你正在与「$name」通话，请结束当前通话后再发起新的通话。", ToastType.Error)
            } else {
                vm.startCall(convId)
            }
        } else if (convId != null) {
            toaster.show("需要录音权限才能进行语音通话", ToastType.Error)
        }
    }

    fun startCallWithPermission(convId: Uuid) {
        // 全局单通话限制：通话中/来电等待/结束中均禁止发起新通话
        if (!vm.canStartCall()) {
            val name = vm.getActiveCallAssistantName() ?: "AI"
            toaster.show("你正在与「$name」通话，请结束当前通话后再发起新的通话。", ToastType.Error)
            return
        }
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
    // 语音/键盘输入模式：由 ChatPage 统一管理，转文字完成后需切回文字模式
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.voiceEvents.collect { msg -> toaster.show(msg, ToastType.Error) }
    }

    // --- AI 摄像头拍照工具：collect 请求 + 权限 + overlay ---
    var pendingCapture by remember { mutableStateOf<CameraCaptureHub.CaptureRequest?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val req = pendingCapture
        if (granted && req != null) {
            // 权限已授予，overlay 自然会渲染（pendingCapture 未变）
        } else if (req != null) {
            CameraCaptureHub.deliverResult(
                CameraCaptureHub.CaptureResult(
                    requestId = req.requestId,
                    success = false,
                    error = "缺少摄像头权限，无法拍照"
                )
            )
            pendingCapture = null
            toaster.show("需要摄像头权限才能拍照", ToastType.Error)
        }
    }
    LaunchedEffect(Unit) {
        CameraCaptureHub.requests.collect { req ->
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                pendingCapture = req
            } else {
                pendingCapture = req // 先设值，让 launcher 回调能找到 req
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // 监听录音状态：按下录音（进入 Recording）时启动并行流式 SystemASR 实时识别
    LaunchedEffect(voiceRecorderController) {
        var lastState: me.rerere.rikkahub.service.voice.VoiceRecorderState? = null
        voiceRecorderController.state.collect { newState ->
            if (newState == me.rerere.rikkahub.service.voice.VoiceRecorderState.Recording &&
                lastState != me.rerere.rikkahub.service.voice.VoiceRecorderState.Recording) {
                // Idle → Recording：用户刚按下录音键
                vm.startRecordingASRSession()
            }
            lastState = newState
        }
    }

    /**
     * 把转写文本填入输入框并切回文字模式，供用户编辑后手动发送。
     * 同时删除临时录音文件（不再作为语音消息保存）。
     */
    fun fillTranscribedToInput(text: String, tempFile: java.io.File?) {
        inputState.setMessageTextAndFocus(text, scope)
        voiceMode = false
        // 转文字模式不保留录音文件，避免堆积临时文件
        tempFile?.let { file ->
            scope.launch {
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }

    fun handleVoiceMessageReady(result: me.rerere.rikkahub.service.voice.VoiceRecorderResult?, showTranscription: Boolean) {
        // 无论录音是否有效（发送/取消/过短）都必须收尾 ASR 会话，
        // 释放 SpeechRecognizer 并拿到（若有）并行流式识别结果
        val streamedText = vm.finishRecordingASRSession()

        // null 可能是"太短"或"上滑取消"：通过 controller 状态区分
        if (result == null) {
            val isCancelled = voiceRecorderController.state.value ==
                me.rerere.rikkahub.service.voice.VoiceRecorderState.Cancelled
            if (!isCancelled) {
                toaster.show(context.getString(R.string.chat_voice_too_short), ToastType.Info)
            }
            return
        }

        if (showTranscription) {
            // 右上滑"转文字"：不发送语音消息，把录音转成文本填入输入框供用户编辑
            // 优先使用录音期间并行流式 ASR 已拿到的文本（省去一次网络请求）
            val preText = streamedText?.takeIf { it.isNotBlank() }
            if (preText != null) {
                fillTranscribedToInput(preText, result.file)
            } else {
                vm.transcribeVoiceToInput(result.uri) { text ->
                    if (text.isNullOrBlank()) {
                        // 转文字失败或为空，提示用户重试
                        toaster.show(context.getString(R.string.chat_voice_transcribe_failed), ToastType.Error)
                    } else {
                        fillTranscribedToInput(text, result.file)
                    }
                }
            }
            return
        }

        // 不再要求 currentChatModel 非空：语音消息入列不需要 chat model，
        // ASR 转写用的是 ASR provider，AI 回复在 onASRNodeCompleted 里按需触发。
        // 达到 59s 自动停录时也能正常发送（类似微信效果）。
        vm.sendVoiceMessage(result.uri, result.durationMs, preTranscribedText = streamedText, quotedMessages = inputState.quotedMessages)
        inputState.clearQuotedMessages()
    }

    // 达到录音最长时长（59s）时，自动停录并发送
    LaunchedEffect(voiceRecorderController) {
        voiceRecorderController.autoStopEvents.collect {
            val result = voiceRecorderController.stop()
            handleVoiceMessageReady(result, showTranscription = false)
        }
    }

    val pagingState by vm.chatPaginationState.collectAsStateWithLifecycle()
    val successState = pagingState as? ConversationRepository.ChatPaginationState.Success
    val isHistoryLoading = successState?.loadingDirection == ConversationRepository.PageLoadDirection.OLDER
    val isNewerLoading = successState?.loadingDirection == ConversationRepository.PageLoadDirection.NEWER
    val isAiTyping by vm.isAiTyping.collectAsStateWithLifecycle()
    val pageNodes = successState?.nodes.orEmpty()
    val hasOlder = successState?.hasOlder == true
    // 通话记录：与消息回合按时间倒序合并进时间线，渲染独立通话卡片
    val callRecords by vm.callRecords.collectAsStateWithLifecycle()
    val assembledItems by remember(pageNodes, hasOlder, isAiTyping, callRecords) {
        derivedStateOf {
            if (pageNodes.isEmpty() && callRecords.isEmpty()) return@derivedStateOf emptyList<ChatUIItem>()

            val zone = TimeZone.currentSystemDefault()
            val reversedTurns = pageNodes.groupIntoTurns().reversed()

            // 回合与通话记录统一按时间倒序合并（index 越小越新）
            val merged = mutableListOf<Pair<Long, ChatUIItem>>()
            // 已插入到某个回合内部的通话记录，避免后续重复添加
            val placedRecordIds = mutableSetOf<String>()

            reversedTurns.forEachIndexed { index, turn ->
                // group.nodes 为 新→旧，反转为 旧→新（与下方渲染约定一致）
                val orderedNodes = turn.nodes.reversed()
                val turnStartMs = orderedNodes.first().currentMessage.createdAt
                    .toInstant(zone).toEpochMilliseconds()
                val turnEndMs = orderedNodes.last().currentMessage.createdAt
                    .toInstant(zone).toEpochMilliseconds()
                val turnConvId = orderedNodes.first().conversationId.toString()

                // 与用户诉求对应：通话气泡需按创建时间排入回合「内部」，
                // 而不是固定在回合最下方。命中该回合时间范围且同会话的记录会切分回合。
                val innerRecords = callRecords
                    .filter { record ->
                        record.conversationId == turnConvId &&
                            record.createTime in turnStartMs..turnEndMs
                    }
                    .sortedBy { it.createTime }

                if (innerRecords.isEmpty()) {
                    merged.add(
                        turnStartMs to ChatUIItem.Turn(
                            turn.copy(nodes = orderedNodes),
                            isGenerating = index == 0 && isAiTyping
                        )
                    )
                    return@forEachIndexed
                }

                // 通话记录把回合切分为若干消息段，记录按其 createTime 插在段与段之间
                var remaining = orderedNodes
                var segmentStartMs = turnStartMs
                innerRecords.forEach { record ->
                    val (olderNodes, newerNodes) = remaining.partition {
                        it.currentMessage.createdAt.toInstant(zone).toEpochMilliseconds() <= record.createTime
                    }
                    if (olderNodes.isNotEmpty()) {
                        merged.add(
                            segmentStartMs to ChatUIItem.Turn(
                                turn.copy(nodes = olderNodes),
                                isGenerating = false
                            )
                        )
                    }
                    merged.add(record.createTime to ChatUIItem.CallRecord(record))
                    placedRecordIds.add(record.id)
                    remaining = newerNodes
                    segmentStartMs = newerNodes.firstOrNull()?.currentMessage?.createdAt
                        ?.toInstant(zone)?.toEpochMilliseconds() ?: record.createTime
                }
                if (remaining.isNotEmpty()) {
                    merged.add(
                        segmentStartMs to ChatUIItem.Turn(
                            turn.copy(nodes = remaining),
                            isGenerating = index == 0 && isAiTyping
                        )
                    )
                }
            }

            // 未落在任何回合内部的通话记录（例如未接来电、跨会话记录）单独参与排序
            callRecords.forEach { record ->
                if (record.id !in placedRecordIds) {
                    merged.add(record.createTime to ChatUIItem.CallRecord(record))
                }
            }
            merged.sortByDescending { it.first }

            val items = mutableListOf<ChatUIItem>()
            var lastConvId: Uuid? = null
            merged.forEach { (_, item) ->
                if (item is ChatUIItem.Turn) {
                    // nodes 已反转为新→旧，最后一个即该回合最早（时间顺序）的节点
                    val oldestNode = item.group.nodes.lastOrNull() ?: return@forEach
                    if (lastConvId != null && oldestNode.conversationId != lastConvId) {
                        items.add(ChatUIItem.Separator(
                            text = context.getString(R.string.chat_topic_started),
                            uid = "topic_${oldestNode.conversationId}"
                        ))
                    }
                    lastConvId = oldestNode.conversationId
                }
                items.add(item)
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

    // 通话激活状态：仅用于「返回键优先挂断通话」；通话 UI 已上移到 RouteActivity 全局遮罩
    val isCallActive by vm.isCallActive.collectAsStateWithLifecycle()

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
    var pendingDeleteCallRecord by remember { mutableStateOf<me.rerere.rikkahub.core.data.db.entity.CallRecordEntity?>(null) }

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
    // 使用 SideEffect 而非 LaunchedEffect：确保每次 recomposition 都同步 loading，
    // 不依赖 key 变化触发；避免 inputState 重建后 loading 未同步导致终止按钮消失
    SideEffect {
        if (inputState.loading != isAiTyping) {
            inputState.loading = isAiTyping
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
                            // 编辑与引用互斥：进入编辑模式时清除所有引用状态
                            inputState.clearQuotedMessages()
                        },
                        onQuote = { message ->
                            // 发送给 AI 的引用内容：语音消息使用转写文本
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
                                // 截断过长的引用内容，避免上下文过于冗长
                                if (rawContent.length > 200) {
                                    rawContent.take(200) + "…"
                                } else {
                                    rawContent
                                }
                            }
                            // 预览卡片显示内容：语音消息显示 [语音]X'' 而非转写文本
                            val rawPreview = message.toPreviewText()
                            val displayContent = if (rawPreview.isBlank()) {
                                quoteContent
                            } else {
                                if (rawPreview.length > 200) rawPreview.take(200) + "…" else rawPreview
                            }
                            // 发送者名称：用户消息用昵称，AI 消息用 assistant 名字
                            val senderName = if (message.role == MessageRole.USER) {
                                setting.displaySetting.userNickname.ifBlank { "User" }
                            } else {
                                currentAssistant.name.ifBlank { "Assistant" }
                            }
                            // 追加引用（同一轮可引用多条），重复引用会被忽略
                            inputState.addQuotedMessage(
                                QuotedMessage(
                                    senderName = senderName,
                                    content = quoteContent,
                                    isUser = message.role == MessageRole.USER,
                                    displayContent = displayContent
                                )
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
                        onCallRecordClick = { record ->
                            navController.navigate(Screen.CallDetail(callId = record.id))
                        },
                        onRedialCall = { startCallWithPermission(conversation.id) },
                        onLongPressCallRecord = { record ->
                            pendingDeleteCallRecord = record
                        },
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

                    if (pendingDeleteCallRecord != null) {
                        val record = pendingDeleteCallRecord!!
                        AlertDialog(
                            onDismissRequest = { pendingDeleteCallRecord = null },
                            title = { Text("删除通话记录") },
                            text = {
                                Text("确定要删除这条通话记录及其关联的聊天消息吗？此操作不可撤销。")
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        vm.deleteCallRecord(record)
                                        pendingDeleteCallRecord = null
                                    }
                                ) { Text("删除") }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingDeleteCallRecord = null }) {
                                    Text(stringResource(R.string.cancel))
                                }
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

                    // 通话期间：在输入区上方显示提示条，告知用户结束通话后才能发消息
                    if (isCurrentAssistantInCall) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            shape = AppShapes.CardMedium,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = "正在与「$inCallAssistantName」通话，请结束当前通话后再发送文字消息。",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
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
                                    voiceMode = voiceMode,
                                    onVoiceModeChange = { voiceMode = it },
                                    onVoiceMessageReady = { result, showTranscription -> handleVoiceMessageReady(result, showTranscription) }
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
                                    voiceMode = voiceMode,
                                    onVoiceModeChange = { voiceMode = it },
                                    onVoiceMessageReady = { result, showTranscription -> handleVoiceMessageReady(result, showTranscription) }
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
                }
            }

            // 通话 UI（来电响铃 / 拨号等待 / 已接通）已上移到 RouteActivity 的全局遮罩

            // AI 摄像头拍照 Overlay：盖在 Scaffold 之上，全屏相机预览 + 倒计时
            val activeCapture = pendingCapture
            if (activeCapture != null &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                CameraCaptureOverlay(
                    request = activeCapture,
                    onDismiss = { pendingCapture = null }
                )
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
    val toaster = LocalToaster.current

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

        // 标题：叠层居中，相对于整个屏幕宽度居中显示
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
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(vertical = 8.dp)
                .height(topPillSize)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 80.dp)
                .clickable {
                    navController.navigate(Screen.AssistantDetail(id = currentAssistant.id.toString()))
                }
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

            // 微信模式下隐藏自动朗读按钮
            if (!wechatMode) {
                Spacer(Modifier.width(8.dp))

                Surface(
                    onClick = {
                        // 这里的 logic 也要改一下：
                        // 如果点的时候是静音时间，弹个提醒，不给切开关
                        if (settings.isMuteTime()) {
                            toaster.show("当前处于静音时段，自动朗读已禁用")
                        } else {
                            onUpdateSettings(settings.copy(autoPlayTts = !settings.autoPlayTts))
                        }
                    },
                    shape = buttonShape,
                    color = topContainerColor,
                    border = topContainerBorder
                ) {
                    Box(modifier = Modifier.size(topPillSize), contentAlignment = Alignment.Center) {
                        // 逻辑：如果 (手动关了) 或者 (正处于静音时段)，图标就显示“关闭”
                        val isEffectivelyOff = !settings.autoPlayTts || settings.isMuteTime()
                        Icon(
                            imageVector = if (isEffectivelyOff) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = "Auto Play TTS",
                            // 可选：静音时段让图标变淡一点（0.5f 透明度）
                            tint = if (settings.isMuteTime()) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            } else {
                                LocalContentColor.current
                            }
                        )
                    }
                }
            }

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
