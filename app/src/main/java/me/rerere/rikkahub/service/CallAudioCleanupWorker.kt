package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.core.data.repository.CallRecordRepository
import me.rerere.rikkahub.service.voice.CallRecorder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "CallAudioCleanupWorker"

/**
 * 通话录音 7 天过期清理。
 *
 * 目标：`forever = 0 AND audio IS NOT NULL AND audio_expires_at <= now`。
 * 流程：先安全删除对应文件，再更新 `audio = NULL`（文件不存在时仍修正数据库）。
 * 文件删除失败时保留记录（下次重试），不得把失败伪装成成功。
 */
class CallAudioCleanupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val callRecordRepository: CallRecordRepository by inject()

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        return try {
            val expired = callRecordRepository.getExpiredAudioRecords(now)
            if (expired.isEmpty()) {
                Log.i(TAG, "no expired call recordings")
                return Result.success()
            }
            var deleted = 0
            var failed = 0
            for (record in expired) {
                val relPath = record.audio
                if (relPath.isNullOrBlank()) {
                    runCatching { callRecordRepository.clearAudio(record.id) }
                    continue
                }
                val file = CallRecorder.resolveAudioFile(applicationContext, relPath)
                val fileDeleted = if (file != null && file.exists()) {
                    runCatching { file.delete() }.getOrDefault(false)
                } else {
                    true // 文件不存在：修正数据库即可
                }
                if (!fileDeleted) {
                    failed++
                    Log.w(TAG, "failed to delete audio file, keep record for retry: $relPath")
                    continue
                }
                runCatching { callRecordRepository.clearAudio(record.id) }
                    .onSuccess { deleted++ }
                    .onFailure { Log.w(TAG, "clearAudio failed callId=${record.id}", it) }
            }
            Log.i(TAG, "call recording cleanup done: deleted=$deleted failed=$failed")
            if (failed > 0) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "call recording cleanup failed", e)
            Result.retry()
        }
    }
}
