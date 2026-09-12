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

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

            Log.d(TAG, "Checking ${settings.assistants.size} assistants for auto-diary...")

            settings.assistants.forEach { assistant ->
                if (assistant.enableAutoDiary) {
                    try {
                        val timeStr = assistant.autoDiaryTime.takeIf { it.isNotBlank() } ?: "06:00"
                        val scheduledTime = LocalTime.parse(timeStr)
                        Log.d(TAG, "Assistant: ${assistant.name}, Scheduled: $timeStr, Current: ${now}")

                        // 简化逻辑：只看今天是否有日记。
                        // 不再补昨天：DiaryWorker 会从上一篇日记的 lastMessageAt 起取到当前的所有消息，
                        // 即使中间几天没生成日记，下次新建时会一次性覆盖那段空档。
                        val todayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), todayStr)
                        if (todayDiary == null) {
                            val isPastScheduledTime = now.isAfter(scheduledTime)
                            if (isPastScheduledTime) {
                                Log.i(TAG, "Time reached: Triggering TODAY diary for ${assistant.name}")
                                enqueueDiaryWork(assistant.id.toString(), todayStr)
                            } else {
                                Log.d(TAG, "Today's diary for ${assistant.name} is not yet due (Scheduled at $timeStr)")
                            }
                        } else {
                            Log.d(TAG, "Today's diary for ${assistant.name} already exists.")
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
