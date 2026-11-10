package me.rerere.rikkahub.data.backgroundtask

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.common.JsonInstant

/**
 * 通用后台任务类型常量。新任务类型实现 BackgroundTaskExecutor 并在此登记 refKey 规则。
 */
object BackgroundTaskTypes {
    /** 电台节目音频生成（TTS），接入 generate_episode_audio 工具。 */
    const val RADIO_AUDIO_GENERATION = "radio_audio_generation"

    fun refKeyForRadioAudio(episodeId: String): String = "radio_audio:$episodeId"

    /** 从 refKey 提取电台节目 ID（radio_audio:{episodeId}）。 */
    fun episodeIdFromRadioAudioRefKey(refKey: String): String? =
        refKey.removePrefix("radio_audio:").takeIf { it != refKey && it.isNotBlank() }
}

/** completion_policy 取值。 */
object CompletionPolicies {
    const val NOTIFY_USER = "notify_user"
    const val NOTIFY_AGENT = "notify_agent"
    const val CONTINUE_WORKFLOW = "continue_workflow"

    val ALL = setOf(NOTIFY_USER, NOTIFY_AGENT, CONTINUE_WORKFLOW)

    fun isValid(value: String?): Boolean = value == null || value in ALL
}

/**
 * 电台音频生成任务参数（payload v1）。
 *
 * 版本化约束：字段只增不删、新字段必须带默认值；JsonInstant ignoreUnknownKeys
 * 保证旧版本 App 读新 payload 不崩溃、新版本读旧 payload 走默认值。
 */
@Serializable
data class RadioAudioTaskPayload(
    val version: Int = 1,
    /** 目标节目 ID */
    val episodeId: String,
    /** 创建任务时的文字稿内容哈希；执行时与节目当前文字稿比对，不一致则丢弃结果 */
    val contentHash: String,
    /** 节目标题快照，仅用于通知文案展示（展示时以 DB 最新标题为准） */
    val episodeTitle: String? = null,
    /** Agent 声明的完成后续动作（不构成授权）；continue_workflow 时必填 */
    val followUpInstruction: String? = null,
)

/** 电台音频生成任务成功结果（result v1）。 */
@Serializable
data class RadioAudioTaskResult(
    val version: Int = 1,
    val audioId: String,
    val durationMs: Long = 0L,
    val voiceFallbackNotice: String? = null,
)

/**
 * 电台音频生成任务执行进度（写入 background_tasks.progress_json）。
 * 仅作 UI 展示与产物定位；chunk 完成的正确性事实源是工作目录文件（ChunkResumeRules）。
 */
@Serializable
data class RadioAudioTaskProgress(
    val version: Int = 1,
    /** 本次执行分配的音频 ID（最终产物 radio_audio/{audioId}.m4a/.wav） */
    val audioId: String,
    val completed: Int = 0,
    val total: Int = 0,
    val sampleRate: Int = 0,
    /** 执行阶段：synthesizing（逐句合成，有 N/M 进度）→ merging（合并 PCM）→ encoding（AAC 编码）。
     *  合成后的阶段无逐句粒度，卡片/通知按阶段文案展示；提交阶段由任务状态 COMMITTING 表达。 */
    val phase: String = PHASE_SYNTHESIZING,
) {
    companion object {
        const val PHASE_SYNTHESIZING = "synthesizing"
        const val PHASE_MERGING = "merging"
        const val PHASE_ENCODING = "encoding"
    }
}

/** payload / result 编解码（失败返回 null，由调用方按损坏参数处理）。 */
object RadioAudioTaskCodec {
    fun encodePayload(payload: RadioAudioTaskPayload): String = JsonInstant.encodeToString(payload)

    fun decodePayload(json: String): RadioAudioTaskPayload? = runCatching {
        JsonInstant.decodeFromString<RadioAudioTaskPayload>(json)
    }.getOrNull()

    fun encodeResult(result: RadioAudioTaskResult): String = JsonInstant.encodeToString(result)

    fun decodeResult(json: String): RadioAudioTaskResult? = runCatching {
        JsonInstant.decodeFromString<RadioAudioTaskResult>(json)
    }.getOrNull()
}
