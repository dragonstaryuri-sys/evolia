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
        Log.i(TAG, "DiarySchedulerWorker.doWork() started at ${LocalTime.now()}")
        return try {
            val settings = settingsStore.settingsFlow.first { !it.init }
            val now = LocalTime.now()

            val today = LocalDate.now()
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val yesterdayStr = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

            Log.d(TAG, "Checking ${settings.assistants.size} assistants for auto-diary...")

            settings.assistants.forEach { assistant ->
                if (assistant.enableAutoDiary) {
                    try {
                        val timeStr = assistant.autoDiaryTime.takeIf { it.isNotBlank() } ?: "06:00"
                        val scheduledTime = LocalTime.parse(timeStr)
                        Log.d(TAG, "Assistant: ${assistant.name}, Scheduled: $timeStr, Current: ${now}")

                        // 检查今天是否已经有日记了
                        val todayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), todayStr)
                        if (todayDiary == null) {

                            val isPastScheduledTime = now.isAfter(scheduledTime)

                            // 补偿逻辑：检查昨天是否漏写了
                            val yesterdayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), yesterdayStr)
                            val isMissedPrevious = yesterdayDiary == null

                            if (isPastScheduledTime || isMissedPrevious) {
                                // —— 命中的目标日期：漏写优先补昨天，否则今天 ——
                                val dateToGenerate = when {
                                    isMissedPrevious && !isPastScheduledTime -> {
                                        Log.i(TAG, "Gap detected: Triggering YESTERDAY diary for ${assistant.name} (yesterdayStr=$yesterdayStr)")
                                        yesterdayStr
                                    }
                                    isMissedPrevious -> {
                                        // 昨天漏了 + 今天到点：先补昨天，下一轮调度再处理今天
                                        Log.i(TAG, "Gap + time reached: Triggering YESTERDAY diary first for ${assistant.name} (yesterdayStr=$yesterdayStr)")
                                        yesterdayStr
                                    }
                                    else -> {
                                        Log.i(TAG, "Time reached: Triggering TODAY diary for ${assistant.name}")
                                        todayStr
                                    }
                                }
                                enqueueDiaryWork(assistant.id.toString(), dateToGenerate)
                            } else {
                                Log.d(TAG, "Today's diary for ${assistant.name} is not yet due (Scheduled at $timeStr)")
                            }
                        } else {
                            // 今天已有日记，但如果昨天漏了也要补昨天（保证历史不缺口）
                            val yesterdayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), yesterdayStr)
                            if (yesterdayDiary == null) {
                                Log.i(TAG, "Today's diary for ${assistant.name} exists, but yesterday's is missing: Triggering YESTERDAY diary catch-up")
                                enqueueDiaryWork(assistant.id.toString(), yesterdayStr)
                            } else {
                                Log.d(TAG, "Today's diary for ${assistant.name} already exists.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to check diary for assistant ${assistant.name}", e)
                    }
                } else {
                    Log.d(TAG, "Auto-diary is disabled for assistant: ${assistant.name}")
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
        Log.i(TAG, "Enqueuing DiaryWorker: $workName")
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
