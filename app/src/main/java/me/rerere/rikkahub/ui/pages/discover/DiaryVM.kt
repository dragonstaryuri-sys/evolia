package me.rerere.rikkahub.ui.pages.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.service.DiaryWorker
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.AppToasterState

class DiaryVM(
    private val app: Application,
    private val settingsStore: SettingsStore,
    private val diaryRepo: DiaryRepository,
) : AndroidViewModel(app) {
    val settings = settingsStore.settingsFlow

    val assistants = settings.map { it.assistants }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun getDiaries(assistantId: String?) = if (assistantId == null) {
        diaryRepo.getAllDiaries()
    } else {
        diaryRepo.getDiariesByAssistant(assistantId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 由于生成逻辑移到了 WorkManager，UI 上的加载状态可以根据 WorkInfo 来观察（可选）
    // 这里暂时保持精简，直接通过通知告知结果
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    fun generateTodayDiary(assistantId: String?, toaster: AppToasterState? = null) {
        val currentSettings = settings.value
        val assistant = if (assistantId != null) {
            currentSettings.assistants.find { it.id.toString() == assistantId }
        } else {
            currentSettings.getCurrentAssistant()
        } ?: return

        // 提交后台任务，带上 isManual=true
        val workRequest = OneTimeWorkRequestBuilder<DiaryWorker>()
            .setInputData(workDataOf(
                "assistantId" to assistant.id.toString(),
                "isManual" to true
            ))
            .addTag("diary_gen")
            .build()

        WorkManager.getInstance(app).enqueueUniqueWork(
            "diary_gen_${assistant.id}",
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        toaster?.show(app.getString(R.string.discover_page_diary_generating), type = ToastType.Info)
    }

    fun deleteDiary(id: String) {
        viewModelScope.launch {
            diaryRepo.deleteDiaryById(id)
        }
    }

    fun updateAssistantDiarySettings(assistantId: String, enableAuto: Boolean) {
        viewModelScope.launch {
            val currentSettings = settings.value
            val updatedAssistants = currentSettings.assistants.map {
                if (it.id.toString() == assistantId) {
                    it.copy(enableAutoDiary = enableAuto)
                } else {
                    it
                }
            }
            settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
        }
    }
}
