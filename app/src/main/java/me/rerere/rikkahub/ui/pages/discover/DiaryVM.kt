package me.rerere.rikkahub.ui.pages.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.service.DiaryWorker
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.AppToasterState
import java.time.LocalDate
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageSource
import me.rerere.rikkahub.core.data.ai.prompts.DIARY_AGENT_COMMENT_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DIARY_AGENT_REPLY_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DIARY_AGENT_REPLY_TO_COMMENT_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.applyPlaceholders
import me.rerere.rikkahub.core.data.db.dao.ScheduleDAO
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryCommentEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryImage
import me.rerere.rikkahub.core.data.db.entity.OcrStatus
import me.rerere.rikkahub.core.data.model.toMessageNode
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.DiaryOcrService
import me.rerere.rikkahub.utils.DiaryImageUtil
import android.graphics.Bitmap
import java.time.ZoneId
import java.util.Locale
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

/**
 * 评论发送前的图片上下文判断结果。
 *
 * 用于手写日记（含图片）的评论流程：
 * - [SendImages]：目标模型支持图片输入，直接发送日记图片文件路径
 * - [UseOcrText]：目标模型不支持图片，但所有图片已成功 OCR，使用 diary.content（合并 OCR 文本）
 * - [Blocked]：存在未解析成功的图片，不能发送评论请求，UI 应弹窗提示并提供解析入口
 * - [NoImages]：日记无图片，正常发送文本
 */
sealed class CommentImageContext {
    data class SendImages(val imagePaths: List<String>) : CommentImageContext()
    data object UseOcrText : CommentImageContext()
    data class Blocked(val unparsedCount: Int) : CommentImageContext()
    data object NoImages : CommentImageContext()
}

class DiaryVM(
    val app: Application,
    private val settingsStore: SettingsStore,
    private val diaryRepo: DiaryRepository,
    private val chatService: ChatService,
    private val scheduleDao: ScheduleDAO,
    private val conversationRepo: ConversationRepository,
    private val diaryOcrService: DiaryOcrService
) : AndroidViewModel(app) {

    val settings = settingsStore.settingsFlow

    // OCR 进度状态：imageId -> OcrStatus（用于 UI 实时展示每张图片的解析状态）
    private val _ocrProgress = MutableStateFlow<Map<String, OcrStatus>>(emptyMap())
    val ocrProgress: StateFlow<Map<String, OcrStatus>> = _ocrProgress.asStateFlow()

    // OCR 错误信息：imageId -> 错误信息
    private val _ocrErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val ocrErrors: StateFlow<Map<String, String>> = _ocrErrors.asStateFlow()

    // OCR 失败一次性事件（转发自 DiaryOcrService，全局跨页面共享）
    val ocrFailureEvents = diaryOcrService.ocrFailureEvents

    // 是否配置了 OCR 模型
    val isOcrModelConfigured: StateFlow<Boolean> = settings.map {
        diaryOcrService.isOcrModelConfigured()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /**
     * 评论发送前的图片上下文判断。
     *
     * 关键：日记有图片就一律 [CommentImageContext.SendImages]，**不要在此处判断模型能力**。
     * 原因是：ChatService 在发送消息前会执行 Transformer pipeline（包括 [OcrTransformer]）：
     * - 如果目标对话模型本身支持图片 → Image parts 原样送模型直接视觉理解。
     * - 如果目标对话模型不支持图片 → OcrTransformer 会用 `settings.ocrModelId`（即用户配置的图片模型）
     *   先把图片 OCR/视觉描述成 `<image_file_ocr>...</image_file_ocr>` 文本块，再替换原 Image parts
     *   送给对话模型——这和"普通对话中发图片、模型不支持图片时的自动 fallback"完全一致。
     *
     * 之前此处判断模型能力并在不支持图片时返回 NoImages，会导致图片根本送不到 Transformer 链路，
     * 结果不支持图片的模型只能看到空的 diary.content，相当于"没发图片"。
     */
    fun checkCommentImageContext(diary: AgentDiaryEntity, targetAssistantId: String): CommentImageContext {
        if (diary.images.isEmpty()) return CommentImageContext.NoImages
        return CommentImageContext.SendImages(diary.images.map { it.imagePath })
    }

    private val _selectedAssistantIds = MutableStateFlow(setOf("ALL"))
    val selectedAssistantIds = _selectedAssistantIds.asStateFlow()

    val isCalendarMode = MutableStateFlow(true)
    val selectedDate = MutableStateFlow(LocalDate.now())

    // 搜索逻辑
    val searchQuery = MutableStateFlow("")
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults: StateFlow<List<AgentDiaryEntity>> = searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList())
            else diaryRepo.searchDiaries(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 评论发送状态 (控制 UI 上的发送按钮显示 Loading 图标)
    private val _isCommenting = MutableStateFlow(false)
    val isCommenting = _isCommenting.asStateFlow()

    val personnelList: StateFlow<List<String>> = settings.map { s ->
        val user = "USER"
        val assistants = s.assistants.sortedByDescending { it.isMain }
        listOf(user) + assistants.map { it.id.toString() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val effectiveFilterIds: Flow<List<String>> = combine(selectedAssistantIds, personnelList) { selected, all ->
        if (selected.contains("ALL")) all else selected.toList()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredDiaries: StateFlow<List<AgentDiaryEntity>> = effectiveFilterIds.flatMapLatest { ids ->
        diaryRepo.getDiariesByAssistants(ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- 分页状态 ---
    private val _pageSize = 10
    private val _currentPage = MutableStateFlow(0)
    private val _hasMore = MutableStateFlow(true)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _diaryList = MutableStateFlow<List<AgentDiaryEntity>>(emptyList())
    val diaryList: StateFlow<List<AgentDiaryEntity>> = _diaryList.asStateFlow()
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private var paginationJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            effectiveFilterIds.collect { ids ->
                // 取消任何进行中的加载任务，避免过滤切换时的竞态
                paginationJob?.cancel()
                _currentPage.value = 0
                _hasMore.value = true
                _isLoadingMore.value = false
                _diaryList.value = emptyList()
                loadFirstPage(ids)
            }
        }
    }

    private suspend fun loadFirstPage(ids: List<String>) {
        val page = diaryRepo.getDiariesByAssistantsPaged(ids, _pageSize, 0)
        _diaryList.value = page
        _hasMore.value = page.size >= _pageSize
    }

    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return
        paginationJob?.cancel()
        paginationJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            try {
                val ids = effectiveFilterIds.first()
                val nextPage = _currentPage.value + 1
                val offset = nextPage * _pageSize
                val newItems = diaryRepo.getDiariesByAssistantsPaged(ids, _pageSize, offset)
                _currentPage.value = nextPage
                _diaryList.value = _diaryList.value + newItems
                _hasMore.value = newItems.size >= _pageSize
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 过滤切换等导致的取消，静默处理
                throw e
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val datesWithDiaries: StateFlow<List<String>> = effectiveFilterIds.flatMapLatest { ids ->
        diaryRepo.getDatesWithDiaries(ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val diariesAtSelectedDate: StateFlow<List<AgentDiaryEntity>> = combine(selectedDate, effectiveFilterIds) { date, ids ->
        date to ids
    }.flatMapLatest { (date, ids) ->
        diaryRepo.getDiariesByDateAndAssistants(date.toString(), ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun togglePersonnelFilter(id: String) {
        _selectedAssistantIds.update { current ->
            if (id == "ALL") {
                setOf("ALL")
            } else {
                val next = current.toMutableSet().apply {
                    remove("ALL")
                    if (contains(id)) remove(id) else add(id)
                }
                if (next.isEmpty()) setOf("ALL") else next
            }
        }
    }

    /**
     * 添加评论：处理 UI 交互后的核心逻辑
     *
     * 两种场景：
     * 1. 日记主人是 USER：用户选择智能体来评论 → 触发 triggerAgentCommentFlow
     * 2. 日记主人不是 USER：用户直接评论 → 保存评论后通知日记主人
     *
     * @param imagePaths 手写日记图片路径列表。当目标模型支持图片输入时，随评论指令一并发送；
     *                   为 null 或空时不携带图片（纯文本日记或模型不支持图片走 OCR 文本）。
     */
    fun addComment(
        diary: AgentDiaryEntity,
        senderId: String,
        content: String,
        toaster: AppToasterState? = null,
        imagePaths: List<String>? = null
    ) {
        if (_isCommenting.value) return
        viewModelScope.launch {
            _isCommenting.value = true
            try {
                if (diary.assistantId == "USER") {
                    // 场景1：日记主人是 USER，用户选择智能体来评论
                    // senderId 为选中的智能体 ID，content 为用户可选的附加说明
                    triggerAgentCommentFlow(diary, senderId, content, imagePaths, toaster)
                } else {
                    // 场景2：日记主人不是 USER，用户直接评论（senderId 固定为 "USER"）
                    val userComment = DiaryCommentEntity(diaryId = diary.id, senderId = "USER", content = content)
                    diaryRepo.insertComment(userComment)
                    toaster?.show(app.getString(R.string.diary_comment_success))

                    // 通知日记主人（AI）查看并决定是否回复
                    triggerAgentReplyFlow(diary, content, replyToCommentId = userComment.id, toaster)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toaster?.show(app.getString(R.string.diary_comment_failed), type = ToastType.Error)
            } finally {
                _isCommenting.value = false
            }
        }
    }

    /**
     * 场景：日记主人是 USER，用户选择一个智能体来对日记发表评论
     * 流程：将完整日记内容 + 用户可选附加说明 + 评论指令发送到选中智能体的最新会话，
     *       要求 AI 返回 reply(0/1) 和 content，决定是否入库。
     *
     * @param imagePaths 手写日记图片路径列表。非空时随指令一并发送给支持图片输入的模型。
     */
    private suspend fun triggerAgentCommentFlow(
        diary: AgentDiaryEntity,
        selectedAssistantId: String,
        userNote: String,
        imagePaths: List<String>?,
        toaster: AppToasterState?
    ) {
        val s = settingsStore.settingsFlow.value
        val targetAssistant = s.assistants.find { it.id.toString() == selectedAssistantId } ?: run {
            toaster?.show(app.getString(R.string.assistant_no_conversation), type = ToastType.Error)
            return
        }
        val convId = targetAssistant.lastConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }

        if (convId == null) {
            toaster?.show(app.getString(R.string.assistant_no_conversation), type = ToastType.Error)
            return
        }

        val userNickname = s.displaySetting.userNickname.ifBlank { "User" }
        // 日记主人是 USER：带入完整日记内容
        val diaryContentForPrompt = diary.content

        // 用户可选附加说明
        val userNoteSection = if (userNote.isNotBlank()) {
            "\n\n用户 $userNickname 补充说明：\n\"$userNote\"\n"
        } else ""

        val prompt = DIARY_AGENT_COMMENT_PROMPT.applyPlaceholders(
            "char" to targetAssistant.name,
            "user" to userNickname,
            "diary_content" to diaryContentForPrompt,
            "user_note" to userNoteSection,
            "locale" to Locale.getDefault().displayName
        )

        toaster?.show(app.getString(R.string.diary_agent_commenting_to, targetAssistant.name))

        sendHiddenCommandAndListen(
            convId = convId,
            prompt = prompt,
            senderIdToSave = selectedAssistantId,
            diaryId = diary.id,
            isJsonDecision = true,
            replyToCommentId = null,
            imagePaths = imagePaths,
            toaster = toaster
        )
    }

    /**
     * 回复评论：用户点击某条评论的"回复"后调用。
     * - 用户回复先入库（replyToId 指向被回复的评论）
     * - 如果被回复的是智能体，通知该智能体决定是否回复用户
     * - 如果被回复的是 USER，不触发 AI（用户自行查看决定是否回复）
     *
     * @param targetComment 被回复的评论
     * @param content 用户的回复内容
     * @param imagePaths 手写日记图片路径列表。当被回复的是智能体且其模型支持图片输入时随指令发送。
     */
    fun replyToComment(
        diary: AgentDiaryEntity,
        targetComment: DiaryCommentEntity,
        content: String,
        toaster: AppToasterState? = null,
        imagePaths: List<String>? = null
    ) {
        if (_isCommenting.value) return
        viewModelScope.launch {
            _isCommenting.value = true
            try {
                // 1. 先保存用户的回复评论（replyToId 指向被回复的评论）
                val userReply = DiaryCommentEntity(
                    diaryId = diary.id,
                    senderId = "USER",
                    replyToId = targetComment.id,
                    content = content
                )
                diaryRepo.insertComment(userReply)
                toaster?.show(app.getString(R.string.diary_comment_success))

                // 2. 如果被回复的是智能体，通知该智能体决定是否回复
                if (targetComment.senderId != "USER") {
                    triggerAgentReplyToCommentFlow(diary, targetComment, userReply, content, imagePaths, toaster)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toaster?.show(app.getString(R.string.diary_comment_failed), type = ToastType.Error)
            } finally {
                _isCommenting.value = false
            }
        }
    }

    /**
     * 场景：用户评论后，通知日记主人（AI）决定是否回复
     */
    private suspend fun triggerAgentReplyFlow(
        diary: AgentDiaryEntity,
        userComment: String,
        replyToCommentId: String,
        toaster: AppToasterState?
    ) {
        val s = settingsStore.settingsFlow.value
        // 目标：找到“日记主人”的会话
        val diaryOwner = s.assistants.find { it.id.toString() == diary.assistantId } ?: return
        val convId = diaryOwner.lastConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }

        if (convId == null) {
            toaster?.show(app.getString(R.string.assistant_no_conversation), type = ToastType.Error)
            return
        }

        val nickname = s.displaySetting.userNickname.ifBlank { "User" }
        // 日记主人是智能体：带入前 200 字
        val truncatedContent = diary.content.take(200).let { if (diary.content.length > 200) "$it..." else it }
        val prompt = DIARY_AGENT_REPLY_PROMPT.applyPlaceholders(
            "char" to diaryOwner.name,
            "user" to nickname,
            "diary_date" to diary.date,
            "user_comment" to userComment,
            "diary_content" to truncatedContent,
            "locale" to Locale.getDefault().displayName
        )

        // 执行隐身对话逻辑
        sendHiddenCommandAndListen(
            convId = convId,
            prompt = prompt,
            senderIdToSave = diaryOwner.id.toString(),
            diaryId = diary.id,
            isJsonDecision = true,
            replyToCommentId = replyToCommentId,
            toaster = toaster
        )
    }

    /**
     * 场景：用户回复了某条智能体评论，通知该智能体决定是否回复用户。
     * 消息发给被回复评论的 senderId 对应的智能体（而非日记主人）。
     *
     * @param targetComment 被回复的评论（senderId 是智能体）
     * @param userReply 用户刚发的回复评论 Entity（AI 回复的 replyToId 指向它）
     * @param userReplyContent 用户回复的内容文本
     * @param imagePaths 手写日记图片路径列表。非空时随指令一并发送给支持图片输入的模型。
     */
    private suspend fun triggerAgentReplyToCommentFlow(
        diary: AgentDiaryEntity,
        targetComment: DiaryCommentEntity,
        userReply: DiaryCommentEntity,
        userReplyContent: String,
        imagePaths: List<String>?,
        toaster: AppToasterState?
    ) {
        val s = settingsStore.settingsFlow.value
        val targetAssistant = s.assistants.find { it.id.toString() == targetComment.senderId } ?: return
        val convId = targetAssistant.lastConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }

        if (convId == null) {
            toaster?.show(app.getString(R.string.assistant_no_conversation), type = ToastType.Error)
            return
        }

        // 获取评论上下文：
        // 1. 排除用户刚发的那条回复（prompt 中已单独引用 userReplyContent）
        // 2. 只保留 USER 和被回复智能体的评论（智能体之间看不到彼此评论）
        // 3. 取最近 5 条
        val recentComments = diaryRepo.getCommentsForDiary(diary.id).first()
            .filter { it.id != userReply.id }
            .filter { it.senderId == "USER" || it.senderId == targetComment.senderId }
            .takeLast(5)
        val nickname = s.displaySetting.userNickname.ifBlank { "User" }
        val commentsContext = recentComments.joinToString("\n") { comment ->
            val senderName = if (comment.senderId == "USER") {
                nickname
            } else {
                s.assistants.find { it.id.toString() == comment.senderId }?.name ?: "Unknown"
            }
            "$senderName: ${comment.content}"
        }

        // 日记内容截断：日记主人是 USER 带完整内容，智能体日记带前 200 字
        val diaryContentForPrompt = if (diary.assistantId == "USER") {
            diary.content
        } else {
            diary.content.take(200).let { if (diary.content.length > 200) "$it..." else it }
        }
        val prompt = DIARY_AGENT_REPLY_TO_COMMENT_PROMPT.applyPlaceholders(
            "char" to targetAssistant.name,
            "user" to nickname,
            "diary_content" to diaryContentForPrompt,
            "comments_context" to commentsContext,
            "user_reply" to userReplyContent,
            "locale" to Locale.getDefault().displayName
        )

        toaster?.show(app.getString(R.string.diary_generating_comment))
        // AI 回复的 replyToId 指向用户刚发的回复评论
        sendHiddenCommandAndListen(
            convId = convId,
            prompt = prompt,
            senderIdToSave = targetComment.senderId,
            diaryId = diary.id,
            isJsonDecision = true,
            replyToCommentId = userReply.id,
            imagePaths = imagePaths,
            toaster = toaster
        )
    }

    /**
     * 核心隐形通讯逻辑
     *
     * @param replyToCommentId 关联的目标评论 ID；非回复评论时传 null
     * @param imagePaths 手写日记图片路径列表。非空时在指令消息中追加 Image 部件，
     *                   使支持图片输入的模型能直接"看到"日记图片。
     */
    private suspend fun sendHiddenCommandAndListen(
        convId: Uuid,
        prompt: String,
        senderIdToSave: String,
        diaryId: String,
        isJsonDecision: Boolean,
        replyToCommentId: String?,
        imagePaths: List<String>? = null,
        toaster: AppToasterState?
    ) {
        // 0. 确保目标 agent 处于普通 UI 模式：微信模式会按标点分句存储 AI 回复，
        //    导致日记评论的 JSON 决策被截断解析失败。若检测到微信模式，自动切换为普通模式。
        ensureNormalUiMode(senderIdToSave, toaster)

        // 构建指令消息部件：文本指令 + 可选的日记图片（支持图片输入的模型可直接"看到"手写日记）
        val messageParts = buildList {
            add(UIMessagePart.Text(prompt))
            if (!imagePaths.isNullOrEmpty()) {
                imagePaths.forEach { path ->
                    add(UIMessagePart.Image("file://$path"))
                }
            }
        }

        val userNode = UIMessage(
            role = MessageRole.USER,
            parts = messageParts,
            skipContext = true, // 标记指令消息不在列表显示
            messageSource = MessageSource.DIARY_COMMENT // 日记评论来源：历史消息截断到100字
        ).toMessageNode(convId)

        // 1. 预热会话缓存：确保 getConversationFlow 拿到的是 DB 真实数据，
        //    避免 fallback 用 currentAssistant.id 创建错误占位会话导致归属被改写。
        //    若 DB 中已无该会话（lastConversationId 失效），直接报错返回，不进入 sendMessage 流程，
        //    防止 saveConversation 走 insert 分支把错误归属的新会话写入 DB。
        if (!chatService.ensureConversationLoaded(convId)) {
            toaster?.show(app.getString(R.string.assistant_no_conversation), type = ToastType.Error)
            return
        }

        // 2. 开启回复监听：使用 viewModelScope，当用户离开日记详情页时立即取消此监听。
        //    发送评论请求后请用户停留在此页等待（UI 会显示 loading 提示）。
        //    应用切到后台不算"退出详情页"——viewModelScope 不会因后台而 cancel，生成可继续。
        val responseJob = viewModelScope.launch {
            // 2a. 等待生成完成（generationDoneFlow 会在 handleMessageComplete onSuccess/onFailure 后 emit）
            chatService.generationDoneFlow.first { it == convId }

            // 2b. 生成完成后再从最新会话中找到当次回复（最后一条 skipContext ASSISTANT 消息）
            val finalConv = chatService.getConversationFlow(convId).value
            val lastMsg = finalConv.currentMessages.lastOrNull {
                it.role == MessageRole.ASSISTANT && it.skipContext && it.toContentText().isNotBlank()
            } ?: return@launch

            val rawText = lastMsg.toContentText()

            if (isJsonDecision) {
                // 逻辑：解析 JSON 并执行条件入库（带 replyToCommentId 关联）
                handleJsonReplyAndInsert(rawText, senderIdToSave, diaryId, replyToCommentId, toaster)
            } else {
                // 逻辑：直接评价，全文入库
                diaryRepo.insertComment(DiaryCommentEntity(
                    diaryId = diaryId,
                    senderId = senderIdToSave,
                    replyToId = replyToCommentId,
                    content = rawText
                ))
                toaster?.show(app.getString(R.string.diary_comment_success))
            }
        }

        // 3. 发送指令
        chatService.sendMessage(
            conversationId = convId,
            content = emptyList(),
            answer = true,
            predefinedUserNode = userNode,
            skipContextForResponse = true,   // 标记 AI 的回复也不显示在列表
            includeSkipContextMessages = true, // ✨ 让构造 AI 输入上下文时包含 skipContext=true 的消息（即这条评论指令），否则会被 GenerationHandler 过滤掉
            responseMessageSource = MessageSource.DIARY_COMMENT // AI 回复也标记为日记评论来源
        )

        responseJob.join()
    }

    /**
     * 确保目标智能体处于普通 UI 模式。
     *
     * 微信模式下 ChatService 会按标点分句存储 AI 回复，导致日记评论的 JSON 决策被截断、
     * 解析失败（表现为"TA不想评论"）。此处检测到微信模式时自动切换为普通模式并持久化，
     * 保证评论回复能被完整接收。
     *
     * @param assistantId 目标智能体 ID 字符串
     */
    private suspend fun ensureNormalUiMode(assistantId: String, toaster: AppToasterState?) {
        val s = settingsStore.settingsFlow.value
        val targetAssistant = s.assistants.find { it.id.toString() == assistantId } ?: return
        val isWechatMode = s.getEffectiveDisplaySetting(targetAssistant).wechatMode
        if (!isWechatMode) return

        val updatedAssistant = targetAssistant.copy(
            uiSettings = targetAssistant.uiSettings.copy(wechatMode = false)
        )
        settingsStore.update(
            s.copy(
                assistants = s.assistants.map { if (it.id == updatedAssistant.id) updatedAssistant else it }
            )
        )
        toaster?.show(app.getString(R.string.diary_comment_switched_to_normal_mode))
    }

    /**
     * 解析 AI 的决策回复并存入数据库
     * @param replyToCommentId 回复目标的评论 ID；非回复时传 null
     */
    private suspend fun handleJsonReplyAndInsert(
        rawText: String,
        senderId: String,
        diaryId: String,
        replyToCommentId: String?,
        toaster: AppToasterState?
    ) {
        try {
            // 提取 JSON，增强对 Markdown 回复的容错
            val jsonStr = if (rawText.contains("{")) {
                rawText.substringAfter("{").substringBeforeLast("}") .let { "{$it}" }
            } else rawText

            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val shouldReply = json["reply"]?.jsonPrimitive?.intOrNull == 1
            val replyContent = json["content"]?.jsonPrimitive?.contentOrNull ?: ""

            if (shouldReply && replyContent.isNotBlank()) {
                // 核心插入逻辑：带上 replyToId 关联目标评论，UI 可据此显示"回复 @XXX"
                diaryRepo.insertComment(DiaryCommentEntity(
                    diaryId = diaryId,
                    senderId = senderId,
                    replyToId = replyToCommentId,
                    content = replyContent
                ))
                toaster?.show(app.getString(R.string.diary_comment_success))
            } else {
                toaster?.show(app.getString(R.string.diary_agent_no_reply))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toaster?.show(app.getString(R.string.diary_agent_no_reply))
        }
    }

    // --- 日记基础维护逻辑 ---

    fun getSchedulesForDate(date: LocalDate) = flow {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        emitAll(scheduleDao.getSchedulesForDay(start, end))
    }

    /**
     * 保存日记（新建或编辑）。
     *
     * @param overrideImages 编辑手写日记时传入：用此列表覆盖数据库中该日记的原有 images。
     *   传 null 表示保留原有 images（直接记录模式 / 不需要修改图片列表时）。
     */
    fun saveDiary(
        id: String? = null,
        assistantId: String,
        content: String,
        date: String,
        overrideImages: List<DiaryImage>? = null
    ) {
        viewModelScope.launch {
            if (id != null && id != "new") {
                // 修改现有日记：保留原始 createdAt 和 assistantId，使用 @Update 而非 insert(REPLACE)。
                // insert(REPLACE) 底层是 DELETE+INSERT，DELETE 会触发 DiaryCommentEntity 外键的
                // CASCADE 删除，导致已有评论全部丢失。
                val existing = diaryRepo.getDiaryById(id)
                val entity = AgentDiaryEntity(
                    id = id,
                    assistantId = existing?.assistantId ?: assistantId,
                    content = content,
                    images = overrideImages ?: (existing?.images ?: emptyList()),
                    date = date,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                diaryRepo.updateDiary(entity)
            } else {
                val entity = AgentDiaryEntity(assistantId = assistantId, content = content, date = date)
                diaryRepo.insertDiary(entity)
            }
        }
    }

    /**
     * 保存手写日记（带图片）。
     *
     * 流程：
     * 1. 生成 diaryId
     * 2. 将每个 Bitmap 保存为 webp 文件（≤500KB）
     * 3. 创建 DiaryImage 列表并插入数据库
     * 4. 后台触发逐张 OCR 解析
     *
     * @param assistantId 作者 ID（"USER" 或智能体 ID）
     * @param date 日期字符串 yyyy-MM-dd
     * @param imageBitmaps 用户导入/拍摄裁剪后的图片 Bitmap 列表
     * @param textContent 可选的手动文字补充（与 OCR 结果合并）
     * @return 新创建的日记 ID（用于页面跳转后继续观察 OCR 进度）
     */
    fun saveDiaryWithImages(
        assistantId: String,
        date: String,
        imageBitmaps: List<Bitmap>,
        textContent: String = "",
        onSaved: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            // 图片压缩+写文件是 IO 操作，必须切到 Dispatchers.IO，否则会阻塞主线程导致 UI 卡顿
            val diaryId = Uuid.random().toString()
            val images = withContext(Dispatchers.IO) {
                imageBitmaps.map { bitmap ->
                    val imageId = Uuid.random().toString()
                    val imagePath = DiaryImageUtil.saveDiaryImage(app, diaryId, imageId, bitmap)
                    DiaryImage(id = imageId, imagePath = imagePath)
                }
            }

            val entity = AgentDiaryEntity(
                id = diaryId,
                assistantId = assistantId,
                content = textContent,
                images = images,
                date = date
            )
            diaryRepo.insertDiary(entity)
            onSaved(diaryId)
        }
    }

    /**
     * 编辑手写日记时追加新图片。
     *
     * 将新图片保存为文件后追加到 diary.images 列表。不再触发本地 OCR ——
     * 图片的理解交给对话模型在评论时按需处理。
     */
    fun appendImagesToDiary(
        diaryId: String,
        newImageBitmaps: List<Bitmap>,
        onSaved: () -> Unit = {}
    ) {
        if (newImageBitmaps.isEmpty()) {
            onSaved()
            return
        }
        viewModelScope.launch {
            val diary = diaryRepo.getDiaryById(diaryId) ?: return@launch
            val newImages = withContext(Dispatchers.IO) {
                newImageBitmaps.map { bitmap ->
                    val imageId = Uuid.random().toString()
                    val imagePath = DiaryImageUtil.saveDiaryImage(app, diaryId, imageId, bitmap)
                    DiaryImage(id = imageId, imagePath = imagePath)
                }
            }
            val updatedDiary = diary.copy(images = diary.images + newImages)
            diaryRepo.updateDiary(updatedDiary)
            onSaved()
        }
    }

    /**
     * 重试单张日记图片的 OCR 解析。
     * OCR 服务在 AppScope 中执行，不受 VM 生命周期影响。
     */
    fun retryOcrImage(diaryId: String, imageId: String) {
        diaryOcrService.retryOcrImage(diaryId, imageId) { id, status, _, error ->
            _ocrProgress.update { it + (id to status) }
            if (error != null) {
                _ocrErrors.update { it + (id to error) }
            } else {
                _ocrErrors.update { it - id }
            }
        }
    }

    /**
     * 获取单张图片的 OCR 状态（优先用 _ocrProgress 中的实时状态，回退到数据库状态）。
     */
    fun getOcrStatusForImage(image: DiaryImage): OcrStatus {
        return _ocrProgress.value[image.id] ?: image.ocrStatus
    }

    /**
     * 获取单张图片的 OCR 错误信息。
     */
    fun getOcrErrorForImage(image: DiaryImage): String? {
        return _ocrErrors.value[image.id] ?: image.ocrError
    }

    /**
     * 触发日记图片的 OCR 解析，并跟踪进度。
     * OCR 服务在 AppScope 中执行，不受 VM 生命周期影响。
     *
     * 失败事件由 DiaryOcrService.ocrFailureEvents 全局发送，任何页面都可收集。
     */
    private fun triggerOcrForDiary(diary: AgentDiaryEntity) {
        // 初始化进度状态
        val initialProgress = diary.images.associate { it.id to it.ocrStatus }
        _ocrProgress.update { it + initialProgress }

        diaryOcrService.ocrDiaryImages(diary) { imageId, status, _, error ->
            _ocrProgress.update { it + (imageId to status) }
            if (error != null) {
                _ocrErrors.update { it + (imageId to error) }
            } else {
                _ocrErrors.update { it - imageId }
            }
        }
    }

    /**
     * 对外暴露的 OCR 触发入口（用于评论弹窗中的"解析"按钮）。
     *
     * 重新读取最新 diary 实体后触发未解析图片的 OCR，实时更新 [_ocrProgress]。
     * OCR 在 AppScope 中执行，不受 VM 生命周期影响。
     */
    fun triggerOcrForDiaryById(diaryId: String) {
        viewModelScope.launch {
            val diary = diaryRepo.getDiaryById(diaryId) ?: return@launch
            triggerOcrForDiary(diary)
        }
    }

    fun getComments(diaryId: String) = diaryRepo.getCommentsForDiary(diaryId)

    fun deleteComment(commentId: String) {
        viewModelScope.launch {
            diaryRepo.deleteComment(commentId)
        }
    }

    fun getDiaryById(id: String) = flow { emit(diaryRepo.getDiaryById(id)) }

    /**
     * 观察单篇日记（响应式）：直接委托 Room Flow，当 DB 更新（如 OCR 状态写入、content 合并）时，
     * 下游 collector 会立即收到新值，无需退出页面重进。
     */
    fun observeDiaryById(id: String): Flow<AgentDiaryEntity?> = diaryRepo.observeDiaryById(id)

    val isGenerating = WorkManager.getInstance(app)
        .getWorkInfosByTagFlow("diary_gen")
        .combine(WorkManager.getInstance(app).getWorkInfosByTagFlow("auto_diary")) { manual, auto ->
            (manual + auto).any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun generateTodayDiary(assistantId: String?, toaster: AppToasterState? = null) {
        val currentSettings = settings.value
        val assistant = if (assistantId != null && assistantId != "USER") {
            currentSettings.assistants.find { it.id.toString() == assistantId }
        } else {
            currentSettings.assistants.find { it.isMain }
        } ?: return

        val workRequest = OneTimeWorkRequestBuilder<DiaryWorker>()
            .setInputData(workDataOf("assistantId" to assistant.id.toString(), "isManual" to true))
            .addTag("diary_gen")
            .build()

        WorkManager.getInstance(app).enqueueUniqueWork("diary_gen_${assistant.id}", ExistingWorkPolicy.REPLACE, workRequest)
        toaster?.show(app.getString(R.string.discover_page_diary_generating), type = ToastType.Info)
    }

    fun deleteDiary(id: String) {
        viewModelScope.launch {
            // 删除日记关联的图片文件
            DiaryImageUtil.deleteDiaryImages(app, id)
            diaryRepo.deleteDiaryById(id)
            // 同步更新列表 UI：_diaryList 基于 suspend 分页查询填充，不会自动响应 DB 变化，
            // 需手动移除被删除项，否则要退出页面重进才能看到删除效果。
            _diaryList.update { list -> list.filterNot { it.id == id } }
        }
    }

    fun toggleAutoDiary(assistantId: String, enabled: Boolean) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            val updatedAssistant = currentSettings.assistants.find { it.id.toString() == assistantId } ?: return@launch
            settingsStore.update(
                currentSettings.copy(
                    assistants = currentSettings.assistants.map {
                        if (it.id == updatedAssistant.id) it.copy(enableAutoDiary = enabled) else it
                    }
                )
            )
        }
    }

    private val notifiedTaskIds = mutableSetOf<java.util.UUID>()
    private var observationJob: kotlinx.coroutines.Job? = null
    private var isTaskObservationInitialized = false

    fun observeTaskResults(toaster: AppToasterState) {
        if (observationJob?.isActive == true) return
        observationJob = viewModelScope.launch {
            val workManager = WorkManager.getInstance(app)
            if (!isTaskObservationInitialized) {
                workManager.getWorkInfosByTagFlow("diary_gen").firstOrNull()?.forEach {
                    if (it.state.isFinished) notifiedTaskIds.add(it.id)
                }
                isTaskObservationInitialized = true
            }
            workManager.getWorkInfosByTagFlow("diary_gen").collect { infos ->
                infos.forEach { info ->
                    if (info.state == WorkInfo.State.SUCCEEDED && !notifiedTaskIds.contains(info.id)) {
                        val isSkipped = info.outputData.getBoolean("skipped", false)
                        val reason = info.outputData.getString("reason")
                        if (isSkipped && reason == "already_exists") {
                            toaster.show(app.getString(R.string.diary_no_new_messages), type = ToastType.Info)
                        } else {
                            toaster.show(app.getString(R.string.discover_page_diary_generate_success), type = ToastType.Success)
                        }
                        notifiedTaskIds.add(info.id)
                    } else if (info.state.isFinished) {
                        notifiedTaskIds.add(info.id)
                    }
                }
            }
        }
    }
}
