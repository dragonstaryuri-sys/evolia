package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val TAG = "DiarySchedulerWorker"

class DiarySchedulerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val diaryRepo: DiaryRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            val settings = settingsStore.settingsFlow.first { !it.init }
            val now = LocalTime.now()
            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

            settings.assistants.forEach { assistant ->
                if (assistant.enableAutoDiary) {
                    try {
                        val scheduledTime = LocalTime.parse(assistant.autoDiaryTime)

                        // 1. 检查当前时间是否已超过助手设定的 autoDiaryTime
                        if (now.isAfter(scheduledTime)) {
                            // 2. 检查数据库中今天是否已经有日记
                            val todayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), todayStr)

                            if (todayDiary == null) {
                                Log.i(TAG, "Triggering auto diary for assistant: ${assistant.name} because no diary exists today.")
                                val workRequest = OneTimeWorkRequestBuilder<DiaryWorker>()
                                    .setInputData(workDataOf(
                                        "assistantId" to assistant.id.toString(),
                                        "isManual" to false
                                    ))
                                    .addTag("auto_diary")
                                    .build()

                                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                                    "auto_diary_${assistant.id}",
                                    ExistingWorkPolicy.KEEP,
                                    workRequest
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse or schedule diary for assistant ${assistant.name}", e)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "DiarySchedulerWorker failed", e)
            Result.failure()
        }
    }
}
