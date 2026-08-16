package me.rerere.rikkahub.service.voice

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
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
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

/**
 * 语音唤醒前台服务。
 *
 * 持续监听麦克风，检测到唤醒词后自动拨通语音通话。
 * 使用 [FOREGROUND_SERVICE_TYPE_MICROPHONE] 保活，配合 [PARTIAL_WAKE_LOCK] 防止 CPU 休眠。
 *
 * 检测流程：
 * 1. AudioRecord 采集 16kHz PCM
 * 2. WakeWordDetector 进行关键词检测
 * 3. 命中后 → 创建新会话 → 启动通话 → 拉起 RouteActivity 显示通话界面
 */
class WakeWordService : Service(), KoinComponent {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1004
        private const val CHANNEL_ID = "wake_word_foreground"
        private const val WAKE_LOCK_TAG = "Evolia::WakeWordWakeLock"

        /** 唤醒触发后的冷却时间（ms），避免连续触发 */
        private const val COOLDOWN_MS = 5000L

        /** 音频读取缓冲区大小（样本数） */
        private const val READ_BUFFER_SIZE = 1600 // 100ms @ 16kHz

        private var wakeLock: PowerManager.WakeLock? = null
        private var isRunning = false

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
            val intent = Intent(context, WakeWordService::class.java)
            context.stopService(intent)
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
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败: ${e.message}")
        }

        acquireWakeLock()
        isRunning = true

        // 启动检测循环
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
        detector.release()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 核心检测循环：
     * 初始化检测器 → 持续读取麦克风 → 送入 KWS → 命中则触发通话
     */
    private suspend fun startDetectionLoop() {
        // 1. 确保模型就绪：优先从 assets 拷贝 → 失败 fallback 到网络下载
        if (!detector.isModelReady()) {
            Log.i(TAG, "模型未就绪，自动准备（assets 拷贝优先）...")
            val result = modelManager.ensureReady()
            if (result.isFailure || !detector.isModelReady()) {
                Log.w(TAG, "KWS 模型准备失败，唤醒服务进入待机: ${result.exceptionOrNull()?.message}")
                return
            }
        }

        // 2. 读取当前设置
        val settings = settingsStore.settingsFlow.value
        val keywords = settings.customWakeWords
        val sensitivity = settings.wakeWordSensitivity

        // 3. 初始化检测器
        try {
            detector.start(keywords = keywords, sensitivity = sensitivity)
            Log.i(TAG, "唤醒检测已启动: keywords=$keywords, sensitivity=$sensitivity")
        } catch (e: Exception) {
            Log.e(TAG, "检测器初始化失败: ${e.message}", e)
            return
        }

        // 4. 启动音频采集
        if (!startAudioRecord()) {
            Log.e(TAG, "音频采集启动失败")
            return
        }

        // 5. 检测循环
        val buffer = ShortArray(READ_BUFFER_SIZE)
        val floatBuffer = FloatArray(READ_BUFFER_SIZE)

        Log.i(TAG, "开始监听唤醒词...")

        while (scope.isActive && isRunning) {
            val ar = audioRecord ?: break
            val readCount = ar.read(buffer, 0, READ_BUFFER_SIZE)
            if (readCount <= 0) continue

            // short → float 归一化到 [-1.0, 1.0]
            for (i in 0 until readCount) {
                floatBuffer[i] = buffer[i] / 32768.0f
            }

            // 送入检测器
            detector.acceptWaveform(floatBuffer.copyOf(readCount))

            // 检测是否命中
            val keyword = detector.detect()
            if (keyword != null) {
                val now = System.currentTimeMillis()
                if (now - lastTriggerTime > COOLDOWN_MS) {
                    lastTriggerTime = now
                    Log.i(TAG, "唤醒词命中: $keyword")
                    detector.reset()
                    handleWakeWordTrigger(keyword)
                } else {
                    Log.d(TAG, "唤醒词命中但处于冷却期，忽略")
                    detector.reset()
                }
            }
        }

        Log.i(TAG, "检测循环结束")
    }

    /** 唤醒词命中后的处理：创建会话 → 启动通话 → 拉起界面 */
    private fun handleWakeWordTrigger(keyword: String) {
        scope.launch {
            try {
                // 如果通话已在进行中，不重复触发
                if (voiceCallManager.isActive.value) {
                    Log.d(TAG, "通话进行中，忽略唤醒触发")
                    return@launch
                }

                // 创建新会话
                val conversationId = Uuid.random()
                val assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id
                chatService.initializeConversation(
                    conversationId = conversationId,
                    targetAssistantId = assistantId,
                    skipAutoArchive = true
                )

                // 启动通话
                voiceCallManager.startCall(conversationId)
                Log.i(TAG, "通话已启动: convId=$conversationId")

                // 拉起 RouteActivity 显示通话界面
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

    /** 初始化 AudioRecord */
    private fun startAudioRecord(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                WakeWordDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 2, READ_BUFFER_SIZE * 2)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WakeWordDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            Log.i(TAG, "AudioRecord 已启动")
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
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop 异常: ${e.message}")
        }
        audioRecord?.release()
        audioRecord = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12 小时兜底
        }
        Log.i(TAG, "WakeLock 已获取")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock 已释放")
            }
        }
        wakeLock = null
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
        val notificationManager = NotificationManagerCompat.from(this)
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        ).setName(getString(R.string.notification_channel_wake_word)).build()
        notificationManager.createNotificationChannel(channel)
    }
}
