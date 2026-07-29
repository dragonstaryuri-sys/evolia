package me.rerere.rikkahub.ui.pages.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.service.DiaryWorker
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.AppToasterState
import java.time.LocalDate
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.ai.prompts.DIARY_COMMENT_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.applyPlaceholders
import me.rerere.rikkahub.core.data.db.dao.ScheduleDAO
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryCommentEntity
import me.rerere.rikkahub.core.data.model.toMessageNode
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.time.ZoneId
import java.util.Locale
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

class DiaryVM(
    val app: Application,
    private val settingsStore: SettingsStore,
    private val diaryRepo: DiaryRepository,
    private val chatService: ChatService,
    private val scheduleDao: ScheduleDAO,
    private val conversationRepo: ConversationRepository
) : AndroidViewModel(app) {

    val settings = settingsStore.settingsFlow

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
     */
    fun addComment(diary: AgentDiaryEntity, senderId: String, content: String, toaster: AppToasterState? = null) {
        if (_isCommenting.value) return
        viewModelScope.launch {
            _isCommenting.value = true
            try {
                if (senderId == "USER") {
                    // 1. 用户评论：先创建 Entity（拿到稳定 ID），再入库
                    val userComment = DiaryCommentEntity(diaryId = diary.id, senderId = "USER", content = content)
                    diaryRepo.insertComment(userComment)
                    toaster?.show(app.getString(R.string.diary_comment_success))

                    // 2. 联动逻辑：如果日记不是用户写的，通知"日记主人"查看并决定是否回复。
                    //    将 userComment.id 作为 replyToCommentId 传入，让 AI 回复带上"回复给谁"的关联。
                    if (diary.assistantId != "USER") {
                        triggerAgentReplyFlow(diary, content, replyToCommentId = userComment.id, toaster)
                    }
                } else {
                    // 3. 智能体评价：发送指令到"日记主人"的会话，请求评价。
                    //    评价针对的是日记本身，不回复具体评论，replyToCommentId = null。
                    triggerAgentEvaluationFlow(diary, senderId, replyToCommentId = null, toaster)
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
        val truncatedContent = diary.content.take(150).let { if (diary.content.length > 150) "$it..." else it }
        val prompt = """
            【系统指令】
            角色设定：你是 ${diaryOwner.name}。
            背景：用户 $nickname 刚刚阅读了你在 ${diary.date} 写的日记，并留下了评论：“$userComment”。
            日记内容如下：
            $truncatedContent

            任务：请决定是否回复该评论。
            要求：
            1. 以 JSON 格式返回结果，包含 "reply" (整数，1表示回复，0表示不回复) 和 "content" (字符串，回复的具体内容)。
            2. 如果觉得没必要回复，请将 "reply" 设为 0。
            3. 你的回复应当符合你的性格设定。
            4. 仅输出 JSON 字符串，不要有其他解释。

            语言：${Locale.getDefault().displayName}
        """.trimIndent()

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
     * 场景：指定智能体对某篇日记进行评价
     * @param replyToCommentId 针对具体评论的回复 ID；评价针对日记本身时传 null
     */
    private suspend fun triggerAgentEvaluationFlow(
        diary: AgentDiaryEntity,
        selectedSenderId: String,
        replyToCommentId: String?,
        toaster: AppToasterState?
    ) {
        val s = settingsStore.settingsFlow.value

        // 目标会话逻辑修正：
        // 如果是智能体的日记，发给该智能体本人；如果是用户的日记，发给当前选中的评价人。
        val targetId = if (diary.assistantId != "USER") diary.assistantId else selectedSenderId
        val targetAssistant = s.assistants.find { it.id.toString() == targetId } ?: return
        val convId = targetAssistant.lastConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }

        if (convId == null) {
            toaster?.show(app.getString(R.string.assistant_no_conversation), type = ToastType.Error)
            return
        }

        // 构造评价 Prompt
        val senderAssistant = s.assistants.find { it.id.toString() == selectedSenderId }
        val prompt = DIARY_COMMENT_PROMPT.applyPlaceholders(
            "char" to (senderAssistant?.name ?: "Someone"),
            "user" to (s.displaySetting.userNickname.ifBlank { "User" }),
            "diary_content" to diary.content.take(150).let { if (diary.content.length > 150) "$it..." else it },
            "locale" to Locale.getDefault().displayName
        )

        val systemPrompt = "【系统指令】\n$prompt"

        toaster?.show(app.getString(R.string.diary_generating_comment))
        sendHiddenCommandAndListen(
            convId = convId,
            prompt = systemPrompt,
            senderIdToSave = selectedSenderId,
            diaryId = diary.id,
            isJsonDecision = false,
            replyToCommentId = replyToCommentId,
            toaster = toaster
        )
    }

    /**
     * 核心隐形通讯逻辑
     * @param replyToCommentId 关联的目标评论 ID；非回复评论时传 null
     */
    private suspend fun sendHiddenCommandAndListen(
        convId: Uuid,
        prompt: String,
        senderIdToSave: String,
        diaryId: String,
        isJsonDecision: Boolean,
        replyToCommentId: String?,
        toaster: AppToasterState?
    ) {
        val userNode = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt)),
            skipContext = true // 标记指令消息不在列表显示
        ).toMessageNode(convId)

        // 1. 预热会话缓存：确保 getConversationFlow 拿到的是 DB 真实数据，
        //    避免 fallback 用 currentAssistant.id 创建错误占位会话导致归属被改写。
        //    若 DB 中已无该会话（lastConversationId 失效），直接报错返回，不进入 sendMessage 流程，
        //    防止 saveConversation 走 insert 分支把错误归属的新会话写入 DB。
        if (!chatService.ensureConversationLoaded(convId)) {
            toaster?.show(app.getString(R.string.assistant_no_conversation), type = ToastType.Error)
            return
        }

        // 2. 开启回复监听：先等到生成完成信号 generationDoneFlow，再取最终完整消息。
        //    之前用 .first() 会在流式生成的第一个非空 token 到达就截走，得到半截 JSON 解析失败显示"不想评论"。
        //    现在必须等 generationDoneFlow 确认当次生成 fully finished，此时会话中的 skipContext AI 回复是完整稳定的。
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
            includeSkipContextMessages = true // ✨ 让构造 AI 输入上下文时包含 skipContext=true 的消息（即这条评论指令），否则会被 GenerationHandler 过滤掉
        )

        responseJob.join()
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

    fun saveDiary(id: String? = null, assistantId: String, content: String, date: String) {
        viewModelScope.launch {
            val entity = if (id != null && id != "new") {
                AgentDiaryEntity(id = id, assistantId = assistantId, content = content, date = date)
            } else {
                AgentDiaryEntity(assistantId = assistantId, content = content, date = date)
            }
            diaryRepo.insertDiary(entity)
        }
    }

    fun getComments(diaryId: String) = diaryRepo.getCommentsForDiary(diaryId)

    fun deleteComment(commentId: String) {
        viewModelScope.launch {
            diaryRepo.deleteComment(commentId)
        }
    }

    fun getDiaryById(id: String) = flow { emit(diaryRepo.getDiaryById(id)) }

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

    fun deleteDiary(id: String) { viewModelScope.launch { diaryRepo.deleteDiaryById(id) } }

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
                        toaster.show(app.getString(R.string.discover_page_diary_generate_success), type = ToastType.Success)
                        notifiedTaskIds.add(info.id)
                    } else if (info.state.isFinished) {
                        notifiedTaskIds.add(info.id)
                    }
                }
            }
        }
    }
}
