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

            val today = LocalDate.now()
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val yesterdayStr = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

            settings.assistants.forEach { assistant ->
                if (assistant.enableAutoDiary) {
                    try {
                        val scheduledTime = LocalTime.parse(assistant.autoDiaryTime)

                        // 检查今天是否已经有日记了，如果有则跳过
                        val todayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), todayStr)
                        if (todayDiary == null) {

                            val isPastScheduledTime = now.isAfter(scheduledTime)

                            // 补偿逻辑：检查昨天是否漏写了
                            val yesterdayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), yesterdayStr)
                            val isMissedPrevious = yesterdayDiary == null

                            // 触发条件：
                            // 1. 到了今天的预定时间
                            // 2. 或者发现昨天（或之前）漏写了，提前触发今天的，把断档补上
                            if (isPastScheduledTime || isMissedPrevious) {
                                if (isMissedPrevious && !isPastScheduledTime) {
                                    Log.i(TAG, "Gap detected: Triggering today's diary early for assistant: ${assistant.name} to cover previous days.")
                                } else {
                                    Log.i(TAG, "Time reached: Triggering today's diary for assistant: ${assistant.name}")
                                }

                                enqueueDiaryWork(assistant.id.toString(), todayStr)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to check diary for assistant ${assistant.name}", e)
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "DiarySchedulerWorker failed", e)
            Result.failure()
        }
    }

    private fun enqueueDiaryWork(assistantId: String, targetDate: String) {
        val workName = "auto_diary_${assistantId}_${targetDate}"
        val workRequest = OneTimeWorkRequestBuilder<DiaryWorker>()
            .setInputData(workDataOf(
                "assistantId" to assistantId,
                "isManual" to false,
                "targetDate" to targetDate
            ))
            .addTag("auto_diary")
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}
