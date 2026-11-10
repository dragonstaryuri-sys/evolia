package me.rerere.rikkahub.data.backgroundtask

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskEntity
import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity
import me.rerere.rikkahub.data.ai.TtsVoiceResolution
import me.rerere.rikkahub.data.ai.TtsVoiceResolver
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.radio.RadioAudioGenerator
import me.rerere.rikkahub.data.radio.RadioEpisodeContentChangedException
import me.rerere.rikkahub.data.radio.RadioService
import me.rerere.rikkahub.data.radio.RadioServiceException
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 电台音频生成执行器（generate_episode_audio 后台化的业务实现）。
 *
 * 执行流程（对应方案 §6/§7 的幂等与提交原子边界）：
 * 1. 幂等检查：按 task_id+content_hash 查已提交资产 → 命中（崩溃在提交后）直接走提交边界落终态，不重复合成；
 * 2. deadline 检查 → 文字稿哈希守卫（getEpisodeContentChecked，短暂持锁）→ 解析音色（Agent 当前配置，删除回退全局默认）；
 * 3. generateChunked 断点续传生成（chunk 保留续跑，单句 1 次重试）；
 * 4. toCommitting（提交原子边界开启，取消自此被拒绝）→ commitEpisodeAudio（锁内 re-check hash + 换备）；
 * 5. 以 committing 状态返回 Success，由 Worker finalizeSucceeded。
 *
 * 失败映射：文字稿变化/参数损坏/超时/音色不可用 = Permanent；TTS 网络/IO 类失败 = Retryable（保留 chunk 续跑）。
 */
@OptIn(ExperimentalUuidApi::class)
class RadioAudioGenerationExecutor(
    private val context: Context,
    private val radioService: RadioService,
    private val audioGenerator: RadioAudioGenerator,
    private val settingsStore: SettingsStore,
    private val backgroundTaskRepository: me.rerere.rikkahub.core.data.repository.BackgroundTaskRepository,
) : BackgroundTaskExecutor {

    override val taskType: String = BackgroundTaskTypes.RADIO_AUDIO_GENERATION

    override suspend fun execute(
        task: BackgroundTaskEntity,
        executionId: String,
        onProgress: suspend (progressJson: String) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val payload = RadioAudioTaskCodec.decodePayload(task.payloadJson)
            ?: return@withContext ExecutionResult.PermanentFailure("任务参数损坏（payload 解析失败）")

        // 0. 幂等检查：崩溃发生在「资产已提交、任务未 finalize」窗口时，直接复用已提交资产
        val committedAsset = radioService.findCommittedAudioForTask(task.id, payload.contentHash)
        if (committedAsset != null) {
            Log.i(TAG, "task=${task.id} 音频已提交（崩溃恢复），跳过重复合成")
            val entered = backgroundTaskRepository.toCommitting(
                task.id, executionId, System.currentTimeMillis() + BackgroundTaskRules.EXECUTION_LEASE_MS
            )
            if (entered == 0) throw TaskLeaseLostException()
            return@withContext ExecutionResult.Success(buildResultJson(committedAsset, fallbackNotice = null))
        }

        // 1. deadline 兜底（创建时间基准，含排队）
        if (BackgroundTaskRules.isDeadlineExceeded(task.deadlineAt, System.currentTimeMillis())) {
            return@withContext ExecutionResult.PermanentFailure(TaskDeadlineExceededException().message ?: "任务执行超时")
        }

        // 2. 文字稿哈希守卫（短暂持锁；生成阶段不持锁，提交时 commitEpisodeAudio 再校验兜底）
        val content = try {
            radioService.getEpisodeContentChecked(payload.episodeId, payload.contentHash)
        } catch (e: RadioEpisodeContentChangedException) {
            return@withContext ExecutionResult.PermanentFailure(e.message ?: "节目文字稿已更新")
        } catch (e: RadioServiceException) {
            return@withContext ExecutionResult.PermanentFailure(e.message ?: "节目文字稿读取失败")
        }

        // 3. 音色解析：按发起 Agent 当前配置（执行时取最新）；Agent 已删除回退全局默认
        val resolution = resolveVoice(task.agentId)
        val providerSetting = resolution.providerSetting
            ?: return@withContext ExecutionResult.PermanentFailure("没有可用的语音服务 Provider，请先在语音服务设置中配置")

        // 4. 断点续传生成
        val audioId = Uuid.random().toString()
        val targetFile = File(RadioAudioGenerator.audioDir(context), "$audioId.m4a")
        val workDir = RadioAudioGenerator.taskWorkDir(context, task.id)
        val progress = RadioAudioTaskProgress(audioId = audioId, total = 0)
        onProgress(JsonInstant.encodeToString(progress))

        val generationResult = try {
            // 阶段进度追踪：逐句合成（N/M）→ 合并 PCM → 编码；阶段切换也走 onProgress
            // （既刷新卡片/通知的阶段文案，也续执行租约——消除"最后一句完成后编码期间租约 60s 不续"的空窗）
            var lastCompleted = 0
            var lastTotal = 0
            var lastSampleRate = 0
            audioGenerator.generateChunked(
                content = content,
                providerSetting = providerSetting,
                providerId = providerSetting.id.toString(),
                workDir = workDir,
                outFile = targetFile,
                onSentenceCompleted = { completed, total, rate ->
                    lastCompleted = completed
                    lastTotal = total
                    lastSampleRate = rate
                    onProgress(
                        JsonInstant.encodeToString(
                            RadioAudioTaskProgress(
                                audioId = audioId,
                                completed = completed,
                                total = total,
                                sampleRate = rate,
                                phase = RadioAudioTaskProgress.PHASE_SYNTHESIZING
                            )
                        )
                    )
                },
                onPhaseChange = { phase ->
                    onProgress(
                        JsonInstant.encodeToString(
                            RadioAudioTaskProgress(
                                audioId = audioId,
                                completed = lastCompleted,
                                total = lastTotal,
                                sampleRate = lastSampleRate,
                                phase = phase
                            )
                        )
                    )
                }
            )
        } catch (e: TaskDeadlineExceededException) {
            throw e
        } catch (e: TaskLeaseLostException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "task=${task.id} chunk 生成异常", e)
            return@withContext ExecutionResult.RetryableFailure("音频生成异常：${e.message ?: e.javaClass.simpleName}")
        }

        when (generationResult) {
            is RadioAudioGenerator.Result.Failure -> {
                // chunk 保留供任务级重试续跑；仅清理半成品最终文件
                return@withContext ExecutionResult.RetryableFailure(generationResult.reason)
            }

            is RadioAudioGenerator.Result.Success -> {
                val generated = generationResult
                // 5. 提交原子边界：running→committing（此后取消被拒绝）→ 锁内 re-check + 换备
                val entered = backgroundTaskRepository.toCommitting(
                    task.id, executionId, System.currentTimeMillis() + BackgroundTaskRules.EXECUTION_LEASE_MS
                )
                if (entered == 0) throw TaskLeaseLostException()

                val asset = try {
                    radioService.commitEpisodeAudio(
                        episodeId = payload.episodeId,
                        audioFile = generated.file,
                        audioId = audioId,
                        contentHash = payload.contentHash,
                        taskId = task.id,
                        providerId = providerSetting.id.toString(),
                        voiceId = radioService.extractVoiceId(providerSetting),
                        durationMs = generated.durationMs
                    )
                } catch (e: RadioEpisodeContentChangedException) {
                    return@withContext ExecutionResult.PermanentFailure(e.message ?: "节目文字稿已更新，结果已丢弃")
                } catch (e: RadioServiceException) {
                    return@withContext ExecutionResult.PermanentFailure(e.message ?: "音频提交失败")
                }
                ExecutionResult.Success(buildResultJson(asset, resolution.fallbackNotice))
            }
        }
    }

    override suspend fun cleanup(task: BackgroundTaskEntity) {
        // 删除最终产物（按 progressJson 记录的 audioId）+ chunk 工作目录；
        // 已提交换备的旧资产不在清理范围（失败不得误删原有有效音频）
        deleteTransientOutput(task)
        audioGenerator.cleanupWorkDir(RadioAudioGenerator.taskWorkDir(context, task.id))
    }

    override suspend fun cleanupTransientOutput(task: BackgroundTaskEntity) {
        deleteTransientOutput(task)
    }

    private fun deleteTransientOutput(task: BackgroundTaskEntity) {
        val progress = task.progressJson?.let {
            runCatching { JsonInstant.decodeFromString(RadioAudioTaskProgress.serializer(), it) }.getOrNull()
        }
        val audioId = progress?.audioId ?: return
        RadioAudioGenerator.audioDir(context).let { dir ->
            listOf("$audioId.m4a", "$audioId.wav").forEach { name ->
                runCatching { File(dir, name).delete() }
            }
        }
    }

    /** 音色解析：Agent 专属（providerId+voiceSettings 副本）→ 全局默认 → legacy；Agent 已删除回退全局。 */
    private suspend fun resolveVoice(agentId: String?): TtsVoiceResolution {
        val settings = settingsStore.settingsFlow.value
        val assistant = agentId?.let { id ->
            runCatching { Uuid.parse(id) }.getOrNull()?.let { settings.getAssistantById(it) }
        }
        return TtsVoiceResolver.resolve(
            settings = settings,
            assistantProviderId = assistant?.voiceProviderId,
            assistantVoiceSettings = assistant?.voiceSettings
        )
    }

    private fun buildResultJson(asset: RadioAudioAssetEntity, fallbackNotice: String?): String {
        val result = RadioAudioTaskResult(
            audioId = asset.id,
            durationMs = asset.durationMs,
            voiceFallbackNotice = fallbackNotice
        )
        return RadioAudioTaskCodec.encodeResult(result)
    }

    companion object {
        private const val TAG = "RadioAudioTaskExecutor"
    }
}
