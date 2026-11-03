package me.rerere.rikkahub.service.voice

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import me.rerere.rikkahub.core.data.db.entity.CallRecordStatus
import me.rerere.rikkahub.core.data.repository.CallRecordRepository
import java.io.File

/**
 * 通话异常恢复与对账。
 *
 * 进程被系统回收 / 用户划掉最近任务 / 崩溃 / 系统重启等异常退出不会执行正常
 * [VoiceCallManager.hangup] 收尾，数据库会遗留 status=CONNECTED 且无 disconnected_at
 * 的通话记录（表现为通话时长 0、无结束状态），私有目录也可能残留无引用的孤儿录音。
 *
 * 本对象在 App 启动后异步执行一次对账（[reconcile]），职责：
 * 1. 恢复遗留 CONNECTED 记录：置为 ENDED + abnormal_process_death，
 *    结束时间按「max(最后心跳, 末条转写时间)」估算，并 clamp 到 [connectedAt, 恢复时刻]；
 * 2. 校验遗留记录的录音引用：文件不可播时仅清 audio 引用（保留通话记录与转写）；
 * 3. 清理孤儿录音文件：DB 无引用 + 最后修改超 24h + 非当前活跃通话。
 *
 * 幂等性：恢复只处理 disconnected_at IS NULL 的记录，孤儿清理只删不满足保留条件的文件，
 * 重复执行无副作用；单条失败仅记日志，不中断整体对账，也不阻塞 App 启动。
 */
object CallRecovery {

    private const val TAG = "CallRecovery"

    /** 异常结束原因：进程死亡 / 服务异常终止后由启动对账恢复的记录。 */
    const val END_REASON_ABNORMAL_PROCESS_DEATH = "abnormal_process_death"

    /** 孤儿录音安全保留期：最后修改时间超过 24h 才允许删除。 */
    const val ORPHAN_RETENTION_MS = 24L * 60L * 60L * 1000L

    // ========================================================================
    //  纯函数（无 Android API 依赖，可单元测试）
    // ========================================================================

    /**
     * 计算异常中断通话的结束时间锚点（估算值，误差 ≤ 心跳间隔）。
     *
     * - 优先取 [lastHeartbeatAt] 与 [endTime]（末条真实转写时间，可能晚于最后一次心跳）
     *   中较晚的有效值；
     * - 两者都无效时以 [connectedAt] 兜底（时长记 0，不伪造结束时间）；
     * - 结果 clamp 到 [connectedAt, recoveryNow] 区间（防御脏数据与时钟回拨）。
     *
     * @return 结束时间锚点；[connectedAt] 无效（null / ≤ 0）时返回 null。
     */
    fun computeRecoveredEndAnchor(
        connectedAt: Long?,
        lastHeartbeatAt: Long?,
        endTime: Long?,
        recoveryNow: Long
    ): Long? {
        if (connectedAt == null || connectedAt <= 0L) return null
        val heartbeat = lastHeartbeatAt?.takeIf { it > 0L }
        val transcriptEnd = endTime?.takeIf { it > 0L }
        val anchor = when {
            heartbeat != null && transcriptEnd != null -> maxOf(heartbeat, transcriptEnd)
            heartbeat != null -> heartbeat
            transcriptEnd != null -> transcriptEnd
            else -> connectedAt
        }
        val lowerBound = connectedAt
        val upperBound = recoveryNow.coerceAtLeast(lowerBound) // 时钟回拨防御
        return anchor.coerceIn(lowerBound, upperBound)
    }

    /** 由结束锚点计算通话时长（毫秒）。任一时间无效或为负（时钟回拨）时记 0。 */
    fun computeRecoveredDurationMs(connectedAt: Long?, endedAt: Long?): Long {
        if (connectedAt == null || endedAt == null) return 0L
        return (endedAt - connectedAt).coerceAtLeast(0L)
    }

    /**
     * 孤儿录音文件判定：同时满足 DB 无引用、最后修改超过保留期、非当前活跃通话的录音。
     * 最后修改时间不可读（≤ 0）时保守保留（返回 false）。
     */
    fun isOrphanRecordingFile(
        fileName: String,
        referencedFileNames: Set<String>,
        activeCallId: String?,
        fileLastModifiedMs: Long,
        nowMs: Long,
        safeRetentionMs: Long = ORPHAN_RETENTION_MS
    ): Boolean {
        if (fileName in referencedFileNames) return false
        if (activeCallId != null && fileName == "$activeCallId.m4a") return false
        if (fileLastModifiedMs <= 0L) return false
        return nowMs - fileLastModifiedMs > safeRetentionMs
    }

    // ========================================================================
    //  录音文件完整性
    // ========================================================================

    /**
     * 录音完整性检查：文件存在且能被 MediaExtractor 解析出有效音频轨道。
     * 未完成封装（缺 moov）的 m4a 会在此返回 false，不能冒充完整录音。
     */
    fun isAudioFilePlayable(file: File?): Boolean {
        if (file == null || !file.exists() || file.length() <= 0L) return false
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var hasAudioTrack = false
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        hasAudioTrack = true
                        break
                    }
                }
                hasAudioTrack
            } finally {
                runCatching { extractor.release() }
            }
        }.getOrDefault(false)
    }

    // ========================================================================
    //  启动对账
    // ========================================================================

    /**
     * 启动对账入口：恢复遗留通话 + 清理孤儿录音。必须在 IO 协程调用，幂等可重复执行。
     *
     * @param activeCallId 当前活跃通话 id（App 启动时刻快照）；匹配的记录保留不动。
     */
    suspend fun reconcile(
        context: Context,
        repository: CallRecordRepository,
        activeCallId: String?,
        nowMs: Long = System.currentTimeMillis()
    ) {
        recoverLeftoverConnected(context, repository, activeCallId, nowMs)
        cleanupOrphanRecordings(context, repository, activeCallId, nowMs)
    }

    /** 恢复遗留的 CONNECTED 记录（status=CONNECTED 且无 disconnected_at）。 */
    private suspend fun recoverLeftoverConnected(
        context: Context,
        repository: CallRecordRepository,
        activeCallId: String?,
        nowMs: Long
    ) {
        val leftovers = runCatching { repository.getLeftoverConnectedRecords() }
            .getOrElse {
                Log.e(TAG, "对账失败：查询遗留 CONNECTED 记录出错", it)
                return
            }
        val staleRecords = leftovers.filter { it.id != activeCallId }
        if (staleRecords.isEmpty()) return
        Log.i(TAG, "对账：发现 ${staleRecords.size} 条遗留 CONNECTED 通话记录，开始恢复")
        for (record in staleRecords) {
            runCatching {
                val connectedAt = record.connectedAt
                if (connectedAt == null) {
                    // 脏数据：状态 CONNECTED 但无接通时间。不伪造时间，仅补结束状态。
                    repository.updateEnded(
                        callId = record.id,
                        status = CallRecordStatus.ENDED,
                        connectedAt = null,
                        disconnectedAt = null,
                        durationMs = 0L,
                        endReason = END_REASON_ABNORMAL_PROCESS_DEATH
                    )
                    Log.i(TAG, "对账：遗留记录无 connected_at，仅补结束状态 callId=${record.id}")
                } else {
                    val endedAt = computeRecoveredEndAnchor(
                        connectedAt = connectedAt,
                        lastHeartbeatAt = record.lastHeartbeatAt,
                        endTime = record.endTime,
                        recoveryNow = nowMs
                    ) ?: connectedAt // 理论不可达（connectedAt 已校验非空），防御兜底
                    val durationMs = computeRecoveredDurationMs(connectedAt, endedAt)
                    repository.updateEnded(
                        callId = record.id,
                        status = CallRecordStatus.ENDED,
                        connectedAt = connectedAt,
                        disconnectedAt = endedAt,
                        durationMs = durationMs,
                        endReason = END_REASON_ABNORMAL_PROCESS_DEATH
                    )
                    Log.i(
                        TAG,
                        "对账：恢复异常中断通话 callId=${record.id} " +
                            "connectedAt=$connectedAt heartbeat=${record.lastHeartbeatAt} " +
                            "endTime=${record.endTime} -> endedAt=$endedAt durationMs=$durationMs"
                    )
                }
            }.onFailure { Log.w(TAG, "对账：恢复遗留通话失败 callId=${record.id}", it) }
            // 录音引用完整性：有 audio 引用但文件不可播 → 仅清引用，保留通话记录与转写
            val storedAudio = record.audio
            if (storedAudio != null) {
                val audioFile = CallRecorder.resolveAudioFile(context, storedAudio)
                if (!isAudioFilePlayable(audioFile)) {
                    runCatching { repository.updateAudio(record.id, null, null) }
                        .onFailure { Log.w(TAG, "对账：清理无效录音引用失败 callId=${record.id}", it) }
                    Log.i(TAG, "对账：遗留通话录音不可播，已清 audio 引用 callId=${record.id}")
                }
            }
        }
    }

    /** 清理孤儿录音文件（DB 无引用 + 超保留期 + 非活跃通话），单文件失败不影响其他文件。 */
    private suspend fun cleanupOrphanRecordings(
        context: Context,
        repository: CallRecordRepository,
        activeCallId: String?,
        nowMs: Long
    ) {
        val recordingsDir = File(context.filesDir, CallRecorder.RELATIVE_DIR)
        if (!recordingsDir.isDirectory) return
        val referencedFileNames = runCatching { repository.getReferencedAudioPaths() }
            .getOrElse {
                Log.e(TAG, "对账失败：查询音频引用出错，跳过孤儿清理", it)
                return
            }
            .map { File(it).name } // 相对路径与绝对路径统一按文件名比对
            .toSet()
        val recordingFiles = runCatching { recordingsDir.listFiles()?.toList().orEmpty() }
            .getOrElse {
                Log.e(TAG, "对账失败：扫描录音目录出错", it)
                return
            }
        var removedCount = 0
        for (file in recordingFiles) {
            runCatching {
                val isOrphan = isOrphanRecordingFile(
                    fileName = file.name,
                    referencedFileNames = referencedFileNames,
                    activeCallId = activeCallId,
                    fileLastModifiedMs = file.lastModified(),
                    nowMs = nowMs,
                    safeRetentionMs = ORPHAN_RETENTION_MS
                )
                if (isOrphan && file.delete()) {
                    removedCount++
                    Log.i(TAG, "对账：删除孤儿录音 ${file.name}")
                }
            }.onFailure { Log.w(TAG, "对账：孤儿录音处理失败 file=${file.name}", it) }
        }
        if (removedCount > 0) {
            Log.i(TAG, "对账：孤儿录音清理完成，共删除 $removedCount 个文件")
        }
    }
}
