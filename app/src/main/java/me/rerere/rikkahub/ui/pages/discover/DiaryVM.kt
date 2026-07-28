package me.rerere.rikkahub.ui.pages.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    fun addComment(diary: AgentDiaryEntity, senderId: String, content: String, toaster: AppToasterState? = null) {
        viewModelScope.launch {
            if (senderId == "USER") {
                diaryRepo.insertComment(DiaryCommentEntity(diaryId = diary.id, senderId = "USER", content = content))
                toaster?.show(app.getString(R.string.diary_comment_success))
            } else {
                val s = settingsStore.settingsFlow.value
                val assistant = s.assistants.find { it.id.toString() == senderId } ?: return@launch

                val prompt = DIARY_COMMENT_PROMPT.applyPlaceholders(
                    "char" to assistant.name,
                    "user" to (s.displaySetting.userNickname.ifBlank { "User" }),
                    "diary_content" to diary.content,
                    "locale" to Locale.getDefault().displayName
                )

                val lastConvIdStr = assistant.lastConversationId
                val convId: Uuid? = lastConvIdStr?.let { runCatching { Uuid.parse(it) }.getOrNull() }

                if (convId == null) {
                    toaster?.show(app.getString(R.string.assistant_no_conversation), type = ToastType.Error)
                    return@launch
                }

                val userNode = UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("[System Instruction]\n$prompt\nUser's context: $content")),
                    skipContext = true
                ).toMessageNode(convId)

                launch {
                    chatService.getConversationFlow(convId)
                        .mapNotNull { conv ->
                            conv.currentMessages.lastOrNull()?.takeIf {
                                it.role == MessageRole.ASSISTANT && it.skipContext && it.toContentText().isNotBlank()
                            }
                        }
                        .first()
                        .let { lastMsg ->
                            diaryRepo.insertComment(DiaryCommentEntity(
                                diaryId = diary.id,
                                senderId = senderId,
                                content = lastMsg.toContentText()
                            ))
                            toaster?.show(app.getString(R.string.diary_comment_success))
                        }
                }

                chatService.sendMessage(
                    conversationId = convId,
                    content = emptyList(),
                    answer = true,
                    predefinedUserNode = userNode,
                    skipContextForResponse = true
                )
                toaster?.show(app.getString(R.string.diary_generating_comment))
            }
        }
    }

    fun getSchedulesForDate(date: LocalDate) = flow {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        emitAll(scheduleDao.getSchedulesForDay(start, end))
    }

    // ✨ 修正：支持传入 ID 以便更新现有日记
    fun saveDiary(id: String? = null, assistantId: String, content: String, date: String) {
        viewModelScope.launch {
            val entity = if (id != null && id != "new") {
                AgentDiaryEntity(
                    id = id,
                    assistantId = assistantId,
                    content = content,
                    date = date
                )
            } else {
                AgentDiaryEntity(
                    assistantId = assistantId,
                    content = content,
                    date = date
                )
            }
            diaryRepo.insertDiary(entity)
        }
    }

    fun getComments(diaryId: String) = diaryRepo.getCommentsForDiary(diaryId)

    fun getDiaryById(id: String) = flow {
        emit(diaryRepo.getDiaryById(id))
    }

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
                workManager.getWorkInfosByTagFlow("diary_gen")
                    .firstOrNull()?.forEach {
                        if (it.state.isFinished) {
                            notifiedTaskIds.add(it.id)
                        }
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
