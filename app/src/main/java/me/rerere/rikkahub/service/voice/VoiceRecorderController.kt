package me.rerere.rikkahub.service.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.uuid.Uuid

private const val TAG = "VoiceRecorderController"

// 录音参数：16kHz / 16-bit / 单声道 PCM，与通话 ASR 路径完全一致
private const val SAMPLE_RATE = 16000
private const val CHANNEL_COUNT = 1
private const val BITS_PER_SAMPLE = 16

/**
 * 语音消息录音结果。
 *
 * @param uri 录音文件 Uri（file:// 形式，可直接被 ExoPlayer / Whisper API 读取）
 * @param durationMs 录音时长（毫秒）
 * @param file 录音文件
 */
data class VoiceRecorderResult(
    val uri: Uri,
    val durationMs: Long,
    val file: File
)

/**
 * 录音状态。Idle 未开始；Recording 录音中；Stopped 已停止；Cancelled 已取消（文件已删）。
 */
enum class VoiceRecorderState { Idle, Recording, Stopped, Cancelled }

/**
 * 按住说话式语音消息录音控制器。
 *
 * 使用 AudioRecord 录制 PCM 16kHz/16-bit/mono，直接写入 WAV 文件，
 * 与通话 ASR 路径完全一致（兼容 SiliconCloud / OpenAI Whisper）。
 * 文件落在 `filesDir/voice_messages/`。
 *
 * 线程模型：start/stop/cancel 由调用方线程同步调用（通常在主线程手势回调里），
 * 录音读取在独立线程上进行，文件 header 更新在 stop() 里同步完成。
 */
class VoiceRecorderController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VoiceRecorderState.Idle)
    val state: StateFlow<VoiceRecorderState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /**
     * 达到最长录制时长时会发送一次事件（auto-stop 触发一次）。
     * UI 层 collect 后应调用 stop() 并把结果发送出去。
     */
    private val _autoStopEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autoStopEvents: SharedFlow<Unit> = _autoStopEvents.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var outputFile: File? = null
    private var raf: RandomAccessFile? = null
    private var recordThread: Thread? = null
    @Volatile private var isRecording = false
    private var startTimeMs: Long = 0L
    private var dataLength: Long = 0L

    // durationMs 定时刷新（供 UI 显示录音时长）
    private var durationJob: kotlinx.coroutines.Job? = null

    /**
     * 开始录音。若当前已在录音则忽略。
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (_state.value == VoiceRecorderState.Recording) {
            Log.w(TAG, "start: already recording, ignore")
            return
        }

        // 权限检查：确保调用方已授予 RECORD_AUDIO
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            _state.value = VoiceRecorderState.Idle
            throw SecurityException("Missing RECORD_AUDIO permission. Please grant microphone permission first.")
        }

        val dir = File(context.filesDir, "voice_messages").apply {
            if (!exists()) mkdirs()
        }
        val file = File(dir, "vm_${Uuid.random()}.wav")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "start: AudioRecord.getMinBufferSize failed: $minBuf")
            _state.value = VoiceRecorderState.Idle
            throw RuntimeException("无法初始化录音（采样率/声道不支持）")
        }

        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: SecurityException) {
            _state.value = VoiceRecorderState.Idle
            throw e
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "start: AudioRecord not initialized")
            try { ar.release() } catch (_: Exception) {}
            _state.value = VoiceRecorderState.Idle
            throw RuntimeException("AudioRecord 初始化失败")
        }

        val raf = RandomAccessFile(file, "rw")
        writeWavHeader(raf, 0L) // 占位 header，dataLength=0

        outputFile = file
        this.raf = raf
        audioRecord = ar
        dataLength = 0L
        isRecording = true

        ar.startRecording()
        startTimeMs = SystemClock.elapsedRealtime()
        _durationMs.value = 0L
        _amplitude.value = 0
        _state.value = VoiceRecorderState.Recording

        // 录音线程：循环读取 PCM 写入文件
        recordThread = Thread({ recordingLoop() }, "VoiceRecorderThread").apply {
            isDaemon = true
            start()
        }

        // 时长刷新
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive && isRecording) {
                _durationMs.value = SystemClock.elapsedRealtime() - startTimeMs
                delay(60)
            }
        }

        Log.i(TAG, "start: recording WAV to ${file.absolutePath}")
    }

    private fun recordingLoop() {
        val ar = audioRecord ?: return
        val raf = raf ?: return
        val bufferSize = 1024 // 每次读 1024 short = 2048 bytes
        val buffer = ShortArray(bufferSize)

        try {
            while (isRecording) {
                val read = ar.read(buffer, 0, bufferSize)
                if (read <= 0) continue

                // 计算 max amplitude（供 UI 波形动画）
                var max = 0
                for (i in 0 until read) {
                    val v = if (buffer[i] < 0) -buffer[i].toInt() else buffer[i].toInt()
                    if (v > max) max = v
                }
                _amplitude.value = max

                // 写入 PCM 数据（little-endian short）
                val byteBuf = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) byteBuf.putShort(buffer[i])
                raf.write(byteBuf.array())
                dataLength += read.toLong() * 2

                // 达到最长录制时长，自动停录，并通过 autoStopEvents 通知 UI 完成 stop()/发送
                val elapsed = SystemClock.elapsedRealtime() - startTimeMs
                if (elapsed >= MAX_DURATION_MS) {
                    Log.i(TAG, "recordingLoop: reached max duration ${MAX_DURATION_MS}ms, emit auto-stop")
                    isRecording = false
                    _autoStopEvents.tryEmit(Unit)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordingLoop: error", e)
        }
    }

    /**
     * 停止录音并返回结果。若时长过短（< 500ms）则视为取消（删除文件）。
     * 若未在录音返回 null。
     */
    fun stop(): VoiceRecorderResult? {
        if (_state.value != VoiceRecorderState.Recording) {
            Log.w(TAG, "stop: not recording, ignore")
            return null
        }
        val duration = SystemClock.elapsedRealtime() - startTimeMs
        durationJob?.cancel()

        // 1. 停止录音线程
        isRecording = false
        try { audioRecord?.stop() } catch (e: Exception) {
            Log.w(TAG, "stop: AudioRecord.stop failed", e)
        }
        try { recordThread?.join(500) } catch (_: Exception) {}

        // 2. 释放 AudioRecord
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        recordThread = null

        // 3. 更新 WAV header（写入真实 dataLength）
        val raf = this.raf
        val file = outputFile
        this.raf = null
        outputFile = null

        try {
            if (raf != null && file != null) {
                raf.seek(0)
                writeWavHeader(raf, dataLength)
                raf.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop: update WAV header failed", e)
        }

        _state.value = VoiceRecorderState.Stopped

        // 过短录音直接清理，返回 null 让上层提示"录音太短"
        if (duration < MIN_DURATION_MS || file == null || !file.exists() || file.length() <= 44L) {
            Log.i(TAG, "stop: too short ($duration ms) or empty file, discarding")
            scope.launch { file?.deleteSafely() }
            _state.value = VoiceRecorderState.Cancelled
            _amplitude.value = 0
            return null
        }
        _durationMs.value = duration
        _amplitude.value = 0
        Log.i(TAG, "stop: ok, duration=${duration}ms, file=${file.absolutePath}, size=${file.length()}")
        return VoiceRecorderResult(
            uri = Uri.fromFile(file),
            durationMs = duration,
            file = file
        )
    }

    /**
     * 取消录音，删除录音文件。可在录音中或录音后调用。
     */
    fun cancel() {
        if (_state.value == VoiceRecorderState.Idle) return
        durationJob?.cancel()
        isRecording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { recordThread?.join(300) } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        recordThread = null

        val raf = this.raf
        val file = outputFile
        this.raf = null
        outputFile = null
        try { raf?.close() } catch (_: Exception) {}
        scope.launch { file?.deleteSafely() }

        _state.value = VoiceRecorderState.Cancelled
        _amplitude.value = 0
        _durationMs.value = 0L
        Log.i(TAG, "cancel: discarded recording")
    }

    /**
     * 释放资源。Composable 卸载时调用。
     */
    fun release() {
        cancel()
    }

    /**
     * 写入 44 字节标准 WAV header。
     * @param dataLength PCM 数据字节数（若用于占位，传 0）
     */
    private fun writeWavHeader(raf: RandomAccessFile, dataLength: Long) {
        val numChannels = CHANNEL_COUNT
        val bitsPerSample = BITS_PER_SAMPLE
        val byteRate = SAMPLE_RATE * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val chunkSize = 36 + dataLength

        raf.seek(0)
        // RIFF header
        raf.writeBytes("RIFF")
        raf.write(intToLittleEndian(chunkSize.toInt()))
        raf.writeBytes("WAVE")
        // fmt chunk
        raf.writeBytes("fmt ")
        raf.write(intToLittleEndian(16))       // subchunk1 size
        raf.write(shortToLittleEndian(1))      // audioFormat = PCM
        raf.write(shortToLittleEndian(numChannels))
        raf.write(intToLittleEndian(SAMPLE_RATE))
        raf.write(intToLittleEndian(byteRate))
        raf.write(shortToLittleEndian(blockAlign))
        raf.write(shortToLittleEndian(bitsPerSample))
        // data chunk
        raf.writeBytes("data")
        raf.write(intToLittleEndian(dataLength.toInt()))
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    private suspend fun File.deleteSafely() {
        try {
            if (exists()) delete()
        } catch (e: Exception) {
            Log.w(TAG, "deleteSafely: failed", e)
        }
    }

    companion object {
        /** 最短有效录音时长，低于此值视为误触。 */
        const val MIN_DURATION_MS = 500L
        /** 最长录音时长（毫秒），达到后自动停止并发送。 */
        const val MAX_DURATION_MS = 59_000L
    }
}
