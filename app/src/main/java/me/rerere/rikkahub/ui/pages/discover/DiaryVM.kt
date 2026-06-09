package me.rerere.rikkahub.ui.pages.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.service.DiaryWorker
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.AppToasterState
import java.util.UUID

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

    // 观察 WorkManager 状态，包括手动和自动
    val isGenerating = WorkManager.getInstance(app)
        .getWorkInfosByTagFlow("diary_gen")
        .combine(WorkManager.getInstance(app).getWorkInfosByTagFlow("auto_diary")) { manual, auto ->
            (manual + auto).any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val processedTaskIds = mutableSetOf<UUID>()
    private var isObserving = false

    /**
     * 监听任务结果并弹出提示
     */
    fun observeTaskResults(toaster: AppToasterState) {
        if (isObserving) return
        isObserving = true
        viewModelScope.launch {
            var isFirstEmission = true
            WorkManager.getInstance(app)
                .getWorkInfosByTagFlow("diary_gen")
                .combine(WorkManager.getInstance(app).getWorkInfosByTagFlow("auto_diary")) { manual, auto ->
                    manual + auto
                }
                .collect { infos ->
                    if (isFirstEmission) {
                        // 首次加载时，记录所有已结束的任务，防止进入页面时弹出旧提示
                        infos.forEach { if (it.state.isFinished) processedTaskIds.add(it.id) }
                        isFirstEmission = false
                    } else {
                        infos.forEach { info ->
                            // 如果是新完成的任务且成功，弹出 Toast
                            if (info.state == WorkInfo.State.SUCCEEDED && !processedTaskIds.contains(info.id)) {
                                processedTaskIds.add(info.id)

                                // 检查是否是跳过的任务
                                val isSkipped = info.outputData.getBoolean("skipped", false)
                                val reason = info.outputData.getString("reason")

                                if (isSkipped && reason == "already_exists") {
                                    toaster.show(app.getString(R.string.diary_already_generated), type = ToastType.Info)
                                } else if (!isSkipped) {
                                    toaster.show(app.getString(R.string.discover_page_diary_generate_success), type = ToastType.Success)
                                }
                            } else if (info.state.isFinished) {
                                processedTaskIds.add(info.id)
                            }
                        }
                    }
                }
        }
    }

    fun generateTodayDiary(assistantId: String?, toaster: AppToasterState? = null) {
        val currentSettings = settings.value
        val assistant = if (assistantId != null) {
            currentSettings.assistants.find { it.id.toString() == assistantId }
        } else {
            currentSettings.getCurrentAssistant()
        } ?: return

        val workRequest = OneTimeWorkRequestBuilder<DiaryWorker>()
            .setInputData(workDataOf(
                "assistantId" to assistant.id.toString(),
                "isManual" to true
            ))
            .addTag("diary_gen")
            .build()

        WorkManager.getInstance(app).enqueueUniqueWork(
            "diary_gen_${assistant.id}",
            ExistingWorkPolicy.REPLACE,
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
