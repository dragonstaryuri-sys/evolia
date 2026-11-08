package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.radio.RadioService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "RadioAudioCleanupWorker"

/**
 * 电台音频清理（每日，AGENTS.md 12.3）：
 * - 备用音频按 3 天有效期（expires_at）清理（文件 + 资产记录）；
 * - 孤儿文件清理：DB 无引用且 lastModified 超过 24h 的 radio_audio 文件
 *   （mtime 不可读时保守保留，与 CallRecovery 口径一致）。
 * 「清理缓存」不触碰 radio_audio 目录；当前音频永不自动清理。
 */
class RadioAudioCleanupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val radioService: RadioService by inject()

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val expired = radioService.cleanupExpiredBackups(now)
            val orphans = radioService.cleanupOrphanFiles(now)
            Log.i(TAG, "radio audio cleanup done: expiredBackups=$expired orphans=$orphans")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "radio audio cleanup failed", e)
            Result.retry()
        }
    }
}
