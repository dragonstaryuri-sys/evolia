package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通话记录实体。
 *
 * 每次通话尝试（包含接通/拒绝/超时/权限失败/初始化失败/取消）都会写入一条记录。
 * 主键 [id] 同时作为该次通话的唯一 `callId`，用于关联通话转写消息（UIMessage.callId）。
 *
 * 字段语义：
 * - [startTime] / [endTime]：本次通话第一条 / 最后一条**真实转写消息**的时间戳，
 *   尚无转写消息时为 null（未接通的通话始终为 null）。
 * - [audio]：App 私有目录中录音文件的**相对路径**（例如 `call_recordings/{callId}.m4a`），
 *   无录音或录音已被删除时为 null。禁止把音频二进制直接存入 Room。
 * - [createTime]：本次通话尝试记录创建时间，不因接听/挂断而改变。
 * - [forever]：0 表示按 7 天规则自动清理；1 表示不自动清理（用户主动保存）。
 * - [durationMs]：仅由 [disconnectedAt] - [connectedAt] 计算得出，不用消息首尾时间代替。
 * - [audioExpiresAt]：音频自动过期时间戳；接通结束后 = disconnectedAt + 7 天。
 */
@Entity(
    tableName = "call_records",
    indices = [
        Index(value = ["assistant_id", "create_time"]),
        Index(value = ["conversation_id"]),
        Index(value = ["status"])
    ]
)
data class CallRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "assistant_id")
    val assistantId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    /** 第一条真实转写消息时间戳（毫秒），无转写时为 null。 */
    @ColumnInfo(name = "start_time")
    val startTime: Long? = null,

    /** 最后一条真实转写消息时间戳（毫秒），无转写时为 null。 */
    @ColumnInfo(name = "end_time")
    val endTime: Long? = null,

    /** 录音文件的相对路径，无录音/已删除为 null。 */
    @ColumnInfo(name = "audio")
    val audio: String? = null,

    /** 记录创建时间，不因接听/挂断改变。 */
    @ColumnInfo(name = "create_time")
    val createTime: Long,

    /** 0 = 按 7 天规则自动清理；1 = 不自动清理。 */
    @ColumnInfo(name = "forever")
    val forever: Int = 0,

    /** 生命周期状态：RINGING / CONNECTED / REJECTED / MISSED / FAILED / ENDED / CANCELLED。 */
    @ColumnInfo(name = "status")
    val status: String,

    /** 实际接通时间戳（毫秒），未接通为 null。 */
    @ColumnInfo(name = "connected_at")
    val connectedAt: Long? = null,

    /** 实际挂断/结束时间戳（毫秒），未接通为 null。 */
    @ColumnInfo(name = "disconnected_at")
    val disconnectedAt: Long? = null,

    /** 通话时长（毫秒），默认 0，仅按 connected_at → disconnected_at 计算。 */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    /** 结束原因：rejected / missed / permission_denied / init_failed / user_hangup / agent_hangup / timeout / error 等。 */
    @ColumnInfo(name = "end_reason")
    val endReason: String? = null,

    /** 录音自动过期时间戳（毫秒），永久保存时清理任务会跳过。 */
    @ColumnInfo(name = "audio_expires_at")
    val audioExpiresAt: Long? = null,

    /** 通话发起方：user = 用户主动拨打；agent = 智能体主动来电。 */
    @ColumnInfo(name = "initiator")
    val initiator: String = CallInitiator.USER
)

/** 通话记录生命周期状态常量。 */
object CallRecordStatus {
    const val RINGING = "RINGING"
    const val CONNECTED = "CONNECTED"
    const val REJECTED = "REJECTED"
    const val MISSED = "MISSED"
    const val FAILED = "FAILED"
    const val ENDED = "ENDED"
    const val CANCELLED = "CANCELLED"
}

/** 通话发起方常量。 */
object CallInitiator {
    const val USER = "user"
    const val AGENT = "agent"
}
