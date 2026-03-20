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

    override suspend fun doWork(): Result {
        return try {
            val settings = settingsStore.settingsFlow.first { !it.init }
            val now = LocalTime.now()
            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

            settings.assistants.forEach { assistant ->
                if (assistant.enableAutoDiary) {
                    try {
                        // 检查今天是否已经自动执行过
                        if (assistant.lastAutoDiaryDate == todayStr) {
                            return@forEach
                        }

                        val scheduledTime = LocalTime.parse(assistant.autoDiaryTime)

                        // 如果当前时间已过预约时间，且今天还没执行过
                        if (now.isAfter(scheduledTime)) {
                            Log.i(TAG, "Triggering auto diary for assistant: ${assistant.name}")
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
