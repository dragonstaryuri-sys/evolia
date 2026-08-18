package me.rerere.rikkahub.service.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid
import kotlin.math.max

/**
 * 语音唤醒前台服务。
 *
 * 持续监听麦克风，检测到唤醒词后自动拨通语音通话。
 * 使用 [FOREGROUND_SERVICE_TYPE_MICROPHONE] 保活，配合 [PARTIAL_WAKE_LOCK] 防止 CPU 休眠。
 *
 * 检测流程：
 *   AudioRecord(16kHz PCM) → WakeWordDetector(KWS) → 命中 → 新建会话 + 启动通话 → 拉起 RouteActivity
 */
class WakeWordService : Service(), KoinComponent {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1004
        private const val CHANNEL_ID = "wake_word_foreground"
        private const val WAKE_LOCK_TAG = "Evolia::WakeWordWakeLock"

        /** 唤醒触发后的冷却时间（ms），避免连续触发 */
        private const val COOLDOWN_MS = 5000L

        private var wakeLock: PowerManager.WakeLock? = null
        @Volatile private var isRunning = false

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动唤醒服务失败: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, WakeWordService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "停止唤醒服务失败: ${e.message}")
            }
        }

        fun isRunning(): Boolean = isRunning
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var detectionJob: Job? = null

    private val settingsStore: SettingsStore by lazy { get() }
    private val chatService: ChatService by lazy { get() }
    private val voiceCallManager: VoiceCallManager by lazy { get() }
    private val modelManager: WakeWordModelManager by lazy { get() }
    private val detector: WakeWordDetector by lazy { WakeWordDetector(modelManager) }

    private var audioRecord: AudioRecord? = null
    private var lastTriggerTime = 0L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败: ${e.message}")
        }
        acquireWakeLock()
        isRunning = true
        detectionJob?.cancel()
        detectionJob = scope.launch { startDetectionLoop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        detectionJob?.cancel()
        detectionJob = null
        stopAudioRecord()
        try { detector.release() } catch (_: Throwable) {}
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startDetectionLoop() {
        // 1) 确保模型就绪：assets 拷贝优先 → 网络下载兜底
        if (!modelManager.isModelReady()) {
            Log.i(TAG, "模型未就绪，自动准备...")
            val result = try {
                modelManager.ensureReady()
            } catch (t: Throwable) {
                Result.failure(t)
            }
            if (result.isFailure || !modelManager.isModelReady()) {
                val msg = result.exceptionOrNull()?.message ?: "未知错误"
                Log.e(TAG, "KWS 模型准备失败: $msg")
                showToastOnMain(getString(R.string.wake_word_model_prepare_failed))
                stopSelfSafely()
                return
            }
        }

        // 2) 读取设置 & 初始化检测器
        val settings = settingsStore.settingsFlow.value
        val keywords = settings.customWakeWords
        val sensitivity = settings.wakeWordSensitivity

        val started = try {
            detector.start(keywords = keywords, sensitivity = sensitivity)
        } catch (t: Throwable) {
            Log.e(TAG, "检测器 start 抛错: ${t.message}", t)
            false
        }
        if (!started) {
            Log.e(TAG, "检测器初始化失败（唤醒词格式/模型异常），服务自动停止")
            showToastOnMain(getString(R.string.wake_word_detector_init_failed))
            stopSelfSafely()
            return
        }
        Log.i(TAG, "唤醒检测已启动，sensitivity=$sensitivity")

        // 3) 启动音频采集
        if (!startAudioRecord()) {
            Log.e(TAG, "音频采集启动失败，服务自动停止")
            showToastOnMain(getString(R.string.wake_word_audio_init_failed))
            try { detector.release() } catch (_: Throwable) {}
            stopSelfSafely()
            return
        }

        // 4) 检测循环
        val readSize = WakeWordDetector.READ_BUFFER_SIZE
        val buffer = ShortArray(readSize)
        val floatBuffer = FloatArray(readSize)
        Log.i(TAG, "开始监听唤醒词...")

        while (scope.isActive && isRunning) {
            val ar = audioRecord ?: break
            if (!detector.isActive()) {
                Log.w(TAG, "检测器不活跃，重新初始化")
                val ok = try { detector.start(keywords, sensitivity) } catch (_: Throwable) { false }
                if (!ok) break
            }
            val readCount = try {
                ar.read(buffer, 0, readSize)
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord.read 异常: ${t.message}")
                continue
            }
            if (readCount <= 0) continue

            // short → float 归一化
            for (i in 0 until readCount) {
                floatBuffer[i] = buffer[i] / 32768.0f
            }
            try {
                detector.acceptWaveform(floatBuffer.copyOf(readCount))
            } catch (_: Throwable) {}

            val keyword = try { detector.detect() } catch (_: Throwable) { null }
            if (keyword != null) {
                val now = System.currentTimeMillis()
                if (now - lastTriggerTime > COOLDOWN_MS) {
                    lastTriggerTime = now
                    Log.i(TAG, "唤醒词命中: [$keyword]")
                    handleWakeWordTrigger(keyword)
                } else {
                    Log.d(TAG, "冷却期内忽略: $keyword")
                }
            }
        }
        Log.i(TAG, "检测循环结束")
    }

    private fun handleWakeWordTrigger(keyword: String) {
        scope.launch {
            try {
                if (voiceCallManager.isActive.value) {
                    Log.d(TAG, "通话进行中，忽略唤醒触发")
                    return@launch
                }
                val conversationId = Uuid.random()
                val settings = settingsStore.settingsFlow.value
                // 优先使用用户在设置里单独指定的唤醒智能体；否则用当前主智能体
                val configuredAssistantId = settings.wakeWordAssistantId
                val targetAssistant = configuredAssistantId
                    ?.let { settings.getAssistantById(it) }
                    ?: settings.getCurrentAssistant()
                chatService.initializeConversation(
                    conversationId = conversationId,
                    targetAssistantId = targetAssistant.id,
                    skipAutoArchive = true
                )
                voiceCallManager.startCall(conversationId)
                Log.i(TAG, "通话已启动（assistant=${targetAssistant.name}）: convId=$conversationId")
                val intent = Intent(this@WakeWordService, RouteActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("conversationId", conversationId.toString())
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "唤醒触发处理失败: ${e.message}", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecord(): Boolean {
        // 权限检查：确保调用方已授予 RECORD_AUDIO
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            Log.e(TAG, "Missing RECORD_AUDIO permission")
            return false
        }
        return try {
            val sampleRate = WakeWordDetector.SAMPLE_RATE
            val readSize = WakeWordDetector.READ_BUFFER_SIZE
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBuf * 2, readSize * 2)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                ar.release()
                return false
            }
            audioRecord = ar
            ar.startRecording()
            Log.i(TAG, "AudioRecord 已启动 (sampleRate=$sampleRate, bufferSize=$bufferSize)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "录音权限被拒绝: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 启动异常: ${e.message}", e)
            false
        }
    }

    private fun stopAudioRecord() {
        val ar = audioRecord ?: return
        try { ar.stop() } catch (_: Exception) {}
        try { ar.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun stopSelfSafely() {
        try { stopSelf() } catch (_: Throwable) {}
        isRunning = false
    }

    private fun showToastOnMain(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
        Log.i(TAG, "WakeLock 已获取")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        Log.i(TAG, "WakeLock 已释放")
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_wake_word_active))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW
        ).setName(getString(R.string.notification_channel_wake_word)).build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }
}
