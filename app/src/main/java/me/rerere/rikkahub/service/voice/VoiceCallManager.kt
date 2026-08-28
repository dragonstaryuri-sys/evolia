package me.rerere.rikkahub.service.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.provider.ASRManager
import me.rerere.asr.provider.ASRProviderSetting
import me.rerere.asr.provider.providers.LocalSenseVoiceASRProvider
import me.rerere.rikkahub.common.utils.WebRtcAudioProcessor
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallForegroundService
import me.rerere.rikkahub.data.ai.tools.CallCommandHub
import me.rerere.rikkahub.ui.components.chat.CallStatus
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.controller.TtsController
import me.rerere.tts.model.PlaybackStatus
import kotlin.uuid.Uuid
import kotlin.collections.set
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "VoiceCallManager"

/**
 * 三层身份：避免旧异步结果串改新轮状态（遵循 call/turn/generation 协议）。
 *
 * - callSessionId : 整通电话生命周期，从 startCall 到 hangup；
 * - turnId        : 一轮（用户说 + AI答），每次 ASR final 时生成新 turn；
 * - generationId  : 一次 AI 生成 + 对应 TTS，AI 开始回答时生成；
 *
 * 所有回调（ASR 结果、TTS 播放完成、模型流结束）都先校验身份再改状态。
 * 取消旧 generation 时：属于它的迟到 chunk / Promise 被永久丢弃。
 */
private data class CallIdentity(
    val callSessionId: String,
    val turnId: String? = null,
    val generationId: String? = null
) {
    fun newTurn(): CallIdentity =
        copy(turnId = "turn_${Uuid.random().toString().substring(0, 8)}", generationId = null)
    fun newGeneration(): CallIdentity =
        copy(generationId = "gen_${Uuid.random().toString().substring(0, 8)}")
}

/**
 * 语音通话管理器（升级为 GPT-Live 风格的实时双工）。
 *
 * 状态机:
 *   IDLE(hangup) → CONNECTING → LISTENING → THINKING → SPEAKING →(Ducking可选)→ LISTENING → ...
 *                                                         ↑ (自然抢话2阶段)
 * 打断两阶段 (PDF §D):
 *   240ms DUCK: 检测到疑似人声 → 压低AI音量 + 橙色光圈闪烁 + 保留PCM预卷
 *   520ms INTERRUPT: 持续说话确认 → 停TTS/取消生成/回到LISTENING/把预卷送入下一轮ASR
 *
 * 关键能力:
 * - 接通问候、思考前导（填空档，减少"等AI"感）
 * - 回声尾窗：TTS 播放结束后的 ~220ms 提高 VAD 门槛，避免尾巴回声触发误打断
 * - 挂断控制：由 call_control 本地工具（AI 主动调用）和 UI 挂断按钮驱动，不再用后台模型异步判断
 * - PCM 环形预卷：~1 秒缓冲，确保打断时用户开头几个字不被吞
 */
class VoiceCallManager(
    private val context: Context,
    private val chatService: ChatService,
    private val ttsController: TtsController,
    private val asrManager: ASRManager,
    private val settingsStore: SettingsStore,
    private val customTtsState: CustomTtsState
) {
    private val _callStatus = MutableStateFlow(CallStatus.CONNECTING)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // 通话默认外放扬声器（用户更习惯免提），可点按钮切到听筒
    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // 通话开始时间戳（毫秒）。hangup 时清零。
    // 用于 UI 只显示通话开始后产生的新消息，过滤掉通话前的历史对话。
    private val _callStartTimeMs = MutableStateFlow(0L)
    val callStartTimeMs: StateFlow<Long> = _callStartTimeMs.asStateFlow()

    // 正在听的时候，ASR 实时识别到的文字（partial 结果）。
    // 用于通话界面实时显示用户"说到哪了"，isFinal=true 后立刻清空并走正常 sendUserMessage。
    // SystemASR 原生 partial 与 OnlineASR 伪流式 partial 都会写入这里。
    private val _listeningText = MutableStateFlow<String?>(null)
    val listeningText: StateFlow<String?> = _listeningText.asStateFlow()

    // 通话错误事件（一次性）
    private val _callError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val callError: SharedFlow<String> = _callError.asSharedFlow()

    // 贴近耳朵（接近传感器触发）：true=贴近 → 系统黑屏 + UI遮罩兜底；false=远离 → 恢复
    private val _isNearEar = MutableStateFlow(false)
    val isNearEar: StateFlow<Boolean> = _isNearEar.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 订阅来自 call_control 本地工具的通话控制命令（由 AI 主动调用触发）
        scope.launch {
            CallCommandHub.commands.collect { cmd ->
                Log.i(TAG, "CallCommandHub: received action=${cmd.action} convId=${cmd.conversationId}")
                when (cmd.action) {
                    "call" -> {
                        if (!_isActive.value) {
                            startCall(cmd.conversationId)
                        }
                    }
                    "hangup" -> {
                        if (_isActive.value) {
                            // AI 主动挂断：延迟 1 分钟再挂，给 AI 留出说再见的时间
                            scheduleDelayedAiHangup()
                        }
                    }
                }
            }
        }
    }

    // ===== 音频路由控制（听筒/扬声器） =====
    private val audioManager by lazy { context.getSystemService<AudioManager>() }
    @Volatile private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    @Volatile private var savedSpeakerOn: Boolean = false
    @Volatile private var savedVolume: Int = -1

    /**
     * 进入通话音频模式：
     * 1. 切到 MODE_IN_COMMUNICATION（VoIP 模式，让系统走通话路径，正确路由听筒/扬声器）
     * 2. 保存旧状态，hangup 时还原
     * 3. 按当前 _isSpeakerOn 设置扬声器路由
     */
    private fun enterCallAudioMode() {
        val am = audioManager ?: return
        runCatching {
            savedAudioMode = am.mode
            savedSpeakerOn = am.isSpeakerphoneOn
            savedVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        }
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.e(TAG, "尝试设置模式为 MODE_IN_COMMUNICATION, 设置后结果: ${am.mode}")
        }.onFailure {
            Log.e(TAG, "设置音频模式失败！", it)
        }
        applySpeakerRoute(_isSpeakerOn.value)
        Log.i(TAG, "enterCallAudioMode: mode→IN_COMMUNICATION, speaker=${_isSpeakerOn.value}")
    }

    /**
     * 退出通话音频模式：还原 AudioManager 状态
     */
    private fun leaveCallAudioMode() {
        val am = audioManager ?: return
        runCatching {
            am.isSpeakerphoneOn = savedSpeakerOn
        }
        runCatching {
            am.mode = savedAudioMode
        }
        Log.i(TAG, "leaveCallAudioMode: mode→$savedAudioMode, speaker restored→$savedSpeakerOn")
    }

    /**
     * 实际切换扬声器/听筒路由。
     * - 扬声器开：isSpeakerphoneOn=true，使用 STREAM_VOICE_CALL 的默认音量
     * - 扬声器关（听筒）：isSpeakerphoneOn=false，音量降低防止听筒过载
     */
    private fun applySpeakerRoute(speakerOn: Boolean) {
        val am = audioManager ?: return
        Log.i(TAG, "applySpeakerRoute: speakerOn=$speakerOn, currentMode=${am.mode}")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                Log.i(TAG, "Available devices: ${devices.map { it.type }}")
                if (speakerOn) {
                    // 开启扬声器：寻找扬声器设备并设置
                    val speakerDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        val result = am.setCommunicationDevice(speakerDevice)
                        Log.i(TAG, "setCommunicationDevice(SPEAKER) result: $result")
                    } else {
                        Log.w(TAG, "Warning: Speaker device NOT found in available list!")
                        am.isSpeakerphoneOn = true // 兜底旧方法
                    }
                } else {
                    // 关闭扬声器：清除之前的设备覆盖，让系统恢复默认路由（听筒或蓝牙）
                    am.clearCommunicationDevice()
                    Log.i(TAG, "clearCommunicationDevice() executed")
                    am.isSpeakerphoneOn = false // 兜底旧方法
                }
            } else {
                // Android 12 以下继续使用旧方法
                am.isSpeakerphoneOn = speakerOn
                Log.i(TAG, "Legacy switch: isSpeakerphoneOn=$speakerOn")
            }
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (speakerOn) {
                val targetVol = (maxVol * 0.85f).toInt().coerceIn(1, maxVol)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
            } else {
                val targetVol = if (savedVolume in 1..maxVol) savedVolume else (maxVol * 0.45f).toInt().coerceIn(1, maxVol)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
            }
        }.onFailure {
            // 🔍 如果整个过程崩了，这里会抓到报错
            Log.w(TAG, "applySpeakerRoute 发生严重错误！", it)
        }
    }

    private var conversationId: Uuid? = null
    private var vadDetector: VadDetector? = null
    private var audioRecord: AudioRecord? = null

    // ===== 三层身份 =====
    @Volatile private var identity: CallIdentity = CallIdentity(callSessionId = "none")

    // ===== PCM 环形预卷（~1 秒，防止打断时吞首字） =====
    // 每帧 32ms（512 samples @16kHz），31 帧 ≈ 1 秒
    private val preRollFrames: ArrayDeque<ShortArray> = ArrayDeque()

    @Volatile private var isVadRunning = false
    private var asrJob: Job? = null
    private var responseJob: Job? = null
    private var interruptionJob: Job? = null
    // LISTENING 模式下持续填 preRollFrames 的轻量录音 Job（避免 AI 回复完→用户开口的空窗漏首字）
    private var listeningPreRollJob: Job? = null
    private var l1TimerJob: Job? = null
    // 240ms duck → 520ms interrupt 的确认计时 Job；若中途用户闭嘴则取消、回弹音量
    private var duckConfirmJob: Job? = null
    // 接通问候 + 思考前导 TTS 的 Job（取消 generation 时需要一起 cancel）
    private var listenerCueJob: Job? = null
    // ===== 用户沉默 30 分钟自动挂断 =====
    @Volatile private var lastUserSpeakAtMs: Long = 0L
    private var userSilenceMonitorJob: Job? = null
    // ===== AI 主动挂断延迟 1 分钟（等 AI 把话说完） =====
    private var aiDelayedHangupJob: Job? = null

    // 流式 TTS: 按 ASSISTANT 节点粒度追踪（工具调用链路会产生多个新 ASSISTANT 节点）
    // - fedLengthPerNode:  每个节点已经"按标点切句分析过"的字符数（key = 节点 Uuid）
    // - lastSpokenNodeId:  上一次真正喂给 TTS 的节点 ID（用于 spokenTextLen 计算）
    // - spokenTextLen:     已经"真正喂给 TTS"的总字符数（手动打断 truncate 的依据，前缀累积）
    // - spokenPartsPerNode:每个节点已播报的文本长度（用于总 spokenTextLen 计算）
    private val fedLengthPerNode = ConcurrentHashMap<Uuid, Int>()
    private val spokenPartsPerNode = ConcurrentHashMap<Uuid, Int>()
    @Volatile private var spokenTextLen = 0
    @Volatile private var priorAssistantNodeId: Uuid? = null
    @Volatile private var speakingStarted = false

    // 回声尾窗：最后一次观察到 TTS 播放的时间戳；处于尾窗内时动态提高 RMS 门限
    @Volatile private var lastTtsPlayingAtMs: Long = 0L

    // ASR 权限误报重试标志
    @Volatile private var asrPermissionRetryUsed = false
    // 在线 ASR → 本地 ASR 兜底标志（防止无限循环）
    @Volatile private var asrFallbackUsed = false

    // ASR 启动忽略期：此时间戳之前的 final 结果一律丢弃（防止扬声器回声被识别成 user 消息）
    @Volatile private var asrIgnoreUntil: Long = 0L
    // 最近播过的问候文本，用于 ASR 内容匹配过滤（防止回声被识别成 user 消息）
    @Volatile private var lastGreetingText: String? = null

    // ===== WebRTC APM：AEC3 + NS 全双工回声消除（session 绑定，Android MediaServer 自动路由 far-end） =====
    // 原理：WebRtcAudioEffects 是 android.media.AudioEffect 的子类，绑定到 AudioRecord.audioSessionId 后，
    //      Android 框架层会自动：
    //      (1) 把同设备上 ExoPlayer/AudioTrack 播放的 TTS 作为 far-end 参考（AEC3 参考信号）
    //      (2) 把 AudioRecord 采集的 PCM 作为 near-end 输入
    //      (3) 底层 native 跑 WebRTC AEC3 + NoiseSuppressor，处理完才交给 AudioRecord.read()
    //      所以不用我们手动喂 far-end PCM、不用做 SRC、不用管时序对齐。
    @Volatile private var apm: WebRtcAudioProcessor? = null

    // ===== 接近传感器（贴近耳朵自动息屏，类似原生电话）=====
    // 两重策略：
    //   1. PROXIMITY_SCREEN_OFF_WAKE_LOCK：让系统真正关闭背光（电话级，ROM 支持最自然）
    //   2. isNearEar → CallScreen 覆盖全黑遮罩 + 禁用触摸：WakeLock 不生效的机型兜底
    @Volatile private var sensorManager: SensorManager? = null
    @Volatile private var proximitySensor: Sensor? = null
    @Volatile private var proximityWakeLock: PowerManager.WakeLock? = null
    @Volatile private var proximityListener: SensorEventListener? = null
    // 记录用户进入通话前的扬声器状态：耳朵贴近强制听筒，耳朵离开后恢复用户原来的选择
    @Volatile private var userSpeakerPreferenceBeforeProximity: Boolean? = null

    // ========================================================================
    //  startCall / hangup
    // ========================================================================

    fun startCall(conversationId: Uuid) {
        if (_isActive.value) return
        // 通话开始：立刻停掉对话页的自动朗读，避免双重播放
        customTtsState.pauseAutoReadForCall()

        this.conversationId = conversationId
        _isActive.value = true
        CallCommandHub.setCallActive(true)
        _callStartTimeMs.value = System.currentTimeMillis()
        _listeningText.value = null
        _callStatus.value = CallStatus.CONNECTING
        resetStreamingState()
        lastUserSpeakAtMs = System.currentTimeMillis()  // 用户沉默超时计时起点

        // 初始化三层身份：新的 callSession
        identity = CallIdentity(callSessionId = "call_${Uuid.random().toString().substring(0, 8)}")
        Log.i(TAG, "startCall: session=${identity.callSessionId}, convId=$conversationId")

        // ===== 音频路由：进入通话模式，切到 VoIP 路径 =====
        enterCallAudioMode()

        // 如果当前是微信模式，自动切换为普通模式（微信模式会分句存储 AI 回复，不适合通话场景）
        scope.launch { ensureNormalUiModeForCall(conversationId) }

        // 预热 & 模式
        chatService.setCallMode(conversationId, active = true)
        startL1Timer(conversationId)
        startUserSilenceMonitor()  // 启动 30 分钟用户沉默自动挂断监控
        warmUpRecordAudioPermission()

        // 启动前台 Service + WakeLock，保证后台/息屏后通话不中断
        VoiceCallForegroundService.start(context)

        runCatching {
            vadDetector = VadDetector(context).also { Log.i(TAG, "VAD initialized") }
        }.onFailure { Log.e(TAG, "VAD init failed", it) }

        // ===== 接近传感器：贴近耳朵自动息屏 =====
        startProximitySensor()

        val settings = settingsStore.settingsFlow.value
        ttsController.setProvider(settings.getSelectedTTSProvider())

        // 接通问候（PDF §3.3 call greeting）：先打个招呼，再进入监听
        playCallGreetingThenListen()

        // 注意：拨通时不启 listeningPreRoll。
        // 原因：问候语播放期间，AI 扬声器的"喂？"回声会被 listeningPreRoll 录下来，
        // 传给 ASR 后被识别成用户说话。问候期间 warmUpAsrAndListening 已在录音，
        // 且有 greeting substring match 过滤，不需要额外预卷。
    }

    /**
     * 确保通话会话对应的智能体处于普通 UI 模式。
     * 微信模式会按标点分句存储 AI 回复，导致通话中消息碎片化。
     * 检测到微信模式时自动切换为普通模式并持久化。
     */
    private suspend fun ensureNormalUiModeForCall(convId: Uuid) {
        val s = settingsStore.settingsFlow.value
        val conv = chatService.getConversationFlow(convId).value
        val assistant = s.assistants.find { it.id == conv.assistantId } ?: return
        val isWechatMode = s.getEffectiveDisplaySetting(assistant).wechatMode
        if (!isWechatMode) return

        val updatedAssistant = assistant.copy(
            uiSettings = assistant.uiSettings.copy(wechatMode = false)
        )
        settingsStore.update(
            s.copy(
                assistants = s.assistants.map { if (it.id == updatedAssistant.id) updatedAssistant else it }
            )
        )
        Log.i(TAG, "startCall: switched assistant ${conv.assistantId} from wechatMode → normal")
    }

    private fun playCallGreetingThenListen() {
        val convId = conversationId ?: return
        listenerCueJob?.cancel()
        listenerCueJob = scope.launch {
            // 1. 【并行优化】立刻启动 ASR 录音初始化（但还不接受 final 结果，由 asrIgnoreUntil 控制）
            //    这样问候语播完时，AudioRecord + ASR engine 已经就绪，用户一开口就能被识别
            if (!_isMuted.value) {
                _callStatus.value = CallStatus.LISTENING
                val settings = settingsStore.settingsFlow.value
                val asrSetting = settings.getSelectedASRProvider()
                if (asrSetting != null) {
                    // 先启动一次监听（内部会初始化 AudioRecord + ASR engine），
                    // 下面的 asrIgnoreUntil 会保证问候期间的结果被丢弃
                    warmUpAsrAndListening(asrSetting)
                }
            }

            // 2. 接通问候："喂？"
            val greetingText = "喂？"
            Log.i(TAG, "Call greeting: \"$greetingText\" start")
            lastGreetingText = greetingText
            // 接通问候属于 call 级别的 listener cue，单独占一个 turn/generation，绝不写入 chat messages
            identity = identity.newTurn().newGeneration()

            runCatching {
                ttsController.setVolume(1f)
                ttsController.speak(greetingText, flush = true)
                var silent = 0L
                while (_isActive.value && ttsController.isSpeaking.value) {
                    delay(100)
                    silent += 100
                    if (silent > 2000L) break // 保护：问候最多等 2s
                }
            }.onFailure { Log.w(TAG, "Greeting play failed", it) }

            // 3. ★ ASR 忽略期从 TTS 播完开始算，而不是从 TTS 开始播放算。
            //   之前从 greetingStartMs + 500ms → TTS 播 "喂？" 就要 500-800ms，
            //   忽略期在问候语还没播完就结束了 → 尾音回声被 ASR 录到 → 识别成 "喂"
            val greetingEndMs = System.currentTimeMillis()
            asrIgnoreUntil = greetingEndMs + ASR_IGNORE_PERIOD_MS
            Log.i(TAG, "Greeting done, ignore period ${ASR_IGNORE_PERIOD_MS}ms starts NOW")

            // 4. 正式进入监听：忽略期结束后才接受 ASR 结果
            //    （warmUpAsrAndListening 已经在录音，但 asrIgnoreUntil 会丢弃此期间的所有 final）

            // 4. 正式进入监听：如果上面的 warmUpAsrAndListening 被用户取消/打断了，这里兜底再启
            if (_isActive.value) {
                Log.i(TAG, "Now accepting user voice input (greeting ignore period ended)")
                // 如果 warmUpAsrAndListening 已经启动了一次监听且仍在运行，startListening 会先 cancel 再重启，安全
                startListening()
            }
        }
    }

    /**
     * 预热/提前启动 ASR 录音链路，不等待问候语播放完成。
     * 逻辑和 startListening 一致但不强制切换 CallStatus（交给外面的 UI 状态控制）。
     */
    @SuppressLint("MissingPermission")
    private fun warmUpAsrAndListening(asrSetting: me.rerere.asr.provider.ASRProviderSetting) {
        val convId = conversationId ?: return
        identity = identity.newTurn()
        asrJob?.cancel()
        val snapshotTurnId = identity.turnId
        val snapshotSessionId = identity.callSessionId
        asrJob = scope.launch {
            try {
                asrManager.startRecognition(asrSetting, context).collect { result ->
                    if (identity.callSessionId != snapshotSessionId || identity.turnId != snapshotTurnId) {
                        Log.d(TAG, "Warmup ASR result stale: drop '${result.text}'")
                        return@collect
                    }
                    if (result.isFinal && result.text.isNotBlank()) {
                        if (System.currentTimeMillis() < asrIgnoreUntil) {
                            Log.w(TAG, "Warmup ASR final dropped (ignore period): '${result.text}'")
                            return@collect
                        }
                        val greeting = lastGreetingText
                        if (greeting != null) {
                            val asrText = result.text.trim()
                            val normalizedGreeting = greeting.replace("[？?！!,.，。 ]".toRegex(), "")
                            val normalizedAsr = asrText.replace("[？?！!,.，。 ]".toRegex(), "")
                            val isGreetingEcho = normalizedAsr.length <= normalizedGreeting.length + 2 &&
                                (normalizedGreeting.contains(normalizedAsr) || normalizedAsr.contains(normalizedGreeting))
                            if (isGreetingEcho) {
                                Log.w(TAG, "Warmup ASR final dropped (matches greeting echo): '$asrText'")
                                return@collect
                            }
                        }
                        // warmup 期间命中用户真正说话：立刻转正
                        Log.i(TAG, "Warmup ASR final (user spoke early) turn=$snapshotTurnId: ${result.text}")
                        _callStatus.value = CallStatus.THINKING
                        // 不再立即播 listenerCue，改为发消息后延迟 1s 再播（scheduleListenerCueDelayed）
                        val currentAsrJob = asrJob
                        sendUserMessage(result.text, convId, scheduleCue = true)
                        if (currentAsrJob === asrJob) asrJob?.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Warmup ASR stream error", t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun warmUpRecordAudioPermission() {
        runCatching {
            val minBuf = AudioRecord.getMinBufferSize(
                VadDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                VadDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording()
                audioRecord.stop()
            }
            audioRecord.release()
        }.onFailure { Log.w(TAG, "warmUpRecordAudioPermission failed", it) }
    }

    private fun startL1Timer(convId: Uuid) {
        l1TimerJob?.cancel()
        l1TimerJob = scope.launch {
            while (_isActive.value) {
                delay(L1_CHECK_INTERVAL_MS)
                if (!_isActive.value) break
                runCatching { chatService.summarizeForCallIfNeeded(convId) }
                    .onFailure { Log.w(TAG, "L1 timer summarize failed", it) }
            }
        }
    }

    private fun stopL1Timer() {
        l1TimerJob?.cancel()
        l1TimerJob = null
    }

    // ========================================================================
    //  用户说话标记（重置 30 分钟沉默计时器 + 取消 AI 延迟挂断）
    // ========================================================================

    private fun noteUserSpoke() {
        lastUserSpeakAtMs = System.currentTimeMillis()
        // 用户还在说话：取消 AI 之前安排的延迟挂断
        if (aiDelayedHangupJob != null) {
            Log.i(TAG, "noteUserSpoke: 用户仍在说话，取消 AI 延迟挂断")
            aiDelayedHangupJob?.cancel()
            aiDelayedHangupJob = null
        }
    }

    // ========================================================================
    //  30 分钟用户沉默自动挂断
    // ========================================================================

    private fun startUserSilenceMonitor() {
        stopUserSilenceMonitor()
        userSilenceMonitorJob = scope.launch {
            while (_isActive.value) {
                delay(USER_SILENCE_CHECK_INTERVAL_MS)
                if (!_isActive.value) break
                val silentMs = System.currentTimeMillis() - lastUserSpeakAtMs
                Log.v(TAG, "silenceMonitor: silentMs=$silentMs / threshold=${USER_SILENCE_TIMEOUT_MS}")
                if (silentMs >= USER_SILENCE_TIMEOUT_MS) {
                    Log.i(TAG, "silenceMonitor: 用户已沉默 ${silentMs / 60000} 分钟，触发自动挂断")
                    hangup()
                    break
                }
            }
        }
    }

    private fun stopUserSilenceMonitor() {
        userSilenceMonitorJob?.cancel()
        userSilenceMonitorJob = null
    }

    // ========================================================================
    //  AI 主动挂断：延迟 1 分钟再挂，等 AI 把道别话说完
    //  延迟期间如果用户再次说话（noteUserSpoke 被调），立即取消挂断
    // ========================================================================

    private fun scheduleDelayedAiHangup() {
        aiDelayedHangupJob?.cancel()
        val scheduledAt = System.currentTimeMillis()
        val snapshotSessionId = identity.callSessionId
        aiDelayedHangupJob = scope.launch {
            Log.i(TAG, "scheduleDelayedAiHangup: 已安排 AI 挂断，${AI_HANGUP_DELAY_MS / 1000}s 后执行 session=$snapshotSessionId")
            // Phase 1: 先等待 AI 完成当前流式回复 + TTS 说完（最多等 AI_HANGUP_DELAY_MS，超时直接进入下一阶段）
            val ttsWaitDeadline = scheduledAt + AI_HANGUP_TTS_MAX_WAIT_MS
            while (isActive && identity.callSessionId == snapshotSessionId &&
                (ttsController.isSpeaking.value ||
                    (conversationId != null && chatService.isGenerating(conversationId!!))) &&
                System.currentTimeMillis() < ttsWaitDeadline
            ) {
                delay(200L)
            }
            if (!_isActive.value || identity.callSessionId != snapshotSessionId) {
                Log.i(TAG, "scheduleDelayedAiHangup: 会话已结束或换 session，取消")
                return@launch
            }
            // Phase 2: 1 分钟宽限倒计时（已经花掉的 TTS 等待时间算在这 1 分钟里，不额外累加）
            val remainMs = (scheduledAt + AI_HANGUP_DELAY_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remainMs > 0) {
                Log.d(TAG, "scheduleDelayedAiHangup: TTS 已停，再等 ${remainMs}ms 凑够 1 分钟宽限")
                delay(remainMs)
            }
            if (!_isActive.value || identity.callSessionId != snapshotSessionId) {
                Log.i(TAG, "scheduleDelayedAiHangup: 宽限期间会话状态变化，取消")
                return@launch
            }
            // Phase 3: 用户可能在此期间又开口说话了 → 再次兜底校验
            val finalSilentMs = System.currentTimeMillis() - lastUserSpeakAtMs
            if (finalSilentMs < 5000L) {
                Log.i(TAG, "scheduleDelayedAiHangup: 宽限期内用户 ${finalSilentMs}ms 前还说过话，放弃挂断")
                return@launch
            }
            Log.i(TAG, "scheduleDelayedAiHangup: 延迟挂断条件全部满足，正式挂断")
            hangup()
        }
    }

    // ========================================================================
    //  LISTENING（ASR 实时识别）
    // ========================================================================

    private fun startListening(fromInterrupt: Boolean = false) {
        val convId = conversationId ?: return
        if (!_isActive.value) return
        // 确保音量回弹（上一轮 duck 状态残留兜底）
        ttsController.restoreVolume(durationMs = 120L)
        duckConfirmJob?.cancel(); duckConfirmJob = null
        // 新一轮开始：确保识别文字清空
        _listeningText.value = null

        if (_isMuted.value) {
            _callStatus.value = CallStatus.LISTENING
            return
        }
        _callStatus.value = CallStatus.LISTENING
        val settings = settingsStore.settingsFlow.value
        val asrSetting = settings.getSelectedASRProvider() ?: run {
            Log.e(TAG, "No ASR provider configured")
            return
        }

        // === 预卷 drain：停止 LISTENING 模式的轻量预卷录音，并把 preRollFrames 传给 ASR ===
        // 先 drain 再做能量过滤，过滤掉纯 AI 尾音回声 / 环境噪声的预卷（容易误识别为"嗯/啊"）
        stopListeningPreRoll()
        val drainedList = synchronized(preRollFrames) {
            val list = preRollFrames.toList()
            preRollFrames.clear()
            list
        }

        // ===== 预卷能量过滤 =====
        // 过滤逻辑：
        //   Step 1. 时间过滤（AI说话尾窗）：回溯每一帧的"录制时刻"，如果落在 lastTtsPlayingAtMs + ECHO_TAIL_WINDOW_MS 之前，
        //           说明这一帧录的时候 AI 扬声器还在响（或刚响完振铃） → 就算能量高也**不计入语音帧**（且不破坏连续计数，视为非语音）。
        //           这是大音量手机"AI自己录自己"的核心兜底：stopInterruptionDetection 即使 delay + clear，
        //           极端情况（delay期间又触发listener cue / TTS hardware flush延迟）仍可能漏。
        //   Step 2. 能量过滤：剩余帧中，至少 20% 含有语音能量（> RMS 阈值），且存在连续 ≥4 帧语音 → 才算真正有人说话。
        // 否则判定为 AI 尾音回声/环境噪声，直接丢弃（OnlineASR 对尾音敏感，容易误识别"嗯/啊"）。
        val preRollFiltered: List<ShortArray>? = if (drainedList.isNotEmpty()) {
            val drainNowMs = System.currentTimeMillis()
            // 回溯：按每帧 512samples ≈ 32ms 估算每帧的录制时刻。环形 preRoll 的最后一帧就是 drainNowMs 附近。
            // AI 尾窗结束时刻：lastTtsPlayingAtMs + 尾窗。在这之前录的帧都算"AI回声帧"。
            val aiEchoWindowEndMs = lastTtsPlayingAtMs + ECHO_TAIL_WINDOW_MS
            // 如果整个 preRoll 窗口都在尾窗结束之前（drainNowMs < aiEchoWindowEndMs），
            // 且 preRoll 总时长不够覆盖到尾窗之后，直接丢弃，避免计算偏差。
            val preRollTotalMs = drainedList.sumOf { it.size } * 1000 / 16000
            val preRollEarliestPossibleMs = drainNowMs - preRollTotalMs
            // fromInterrupt=true（打断场景）：preRoll 来自 interruptionJob 的 AEC 处理后音频，
            // 已消回声，不走时间过滤兜底（否则会误丢用户开头字）；仅 listeningPreRoll 录的才需要兜底
            val allFramesInEcho = !fromInterrupt && preRollEarliestPossibleMs >= 0 && drainNowMs < aiEchoWindowEndMs
            if (allFramesInEcho && lastTtsPlayingAtMs > 0L) {
                // 整个预卷都在 AI 回声尾窗内，没有意义 —— 直接丢弃
                Log.i(TAG, "[ASRDiag] preRoll DROPPED entirely within TTS echo window " +
                    "ttsAgo=${drainNowMs - lastTtsPlayingAtMs}ms < window=${ECHO_TAIL_WINDOW_MS}ms " +
                    "preRollLen=${preRollTotalMs}ms earliest=${preRollEarliestPossibleMs - lastTtsPlayingAtMs}ms(relTts) " +
                    "echoFilter=on fromInterrupt=$fromInterrupt")
                null
            } else {
                val speechThreshold = RMS_SPEECH_THRESHOLD_PRE_ROLL
                var speechFrames = 0
                var maxConsecutiveSpeech = 0
                var currentConsecutive = 0
                var totalRmsAccum = 0.0
                var accFramesBeforeThis = 0
                var aiEchoSkipped = 0
                // RMS 分布统计（min/max/avg + 阈值），用于调参诊断
                var minRms = Float.MAX_VALUE
                var maxRms = 0f
                var firstFrameRecMs = 0L
                var lastFrameRecMs = 0L
                // 【关键修改点】：创建一个新列表只存非回声帧
                val cleanFrames = mutableListOf<ShortArray>()
                for (frame in drainedList) {
                    // 回溯估算这一帧的"录制时刻"：
                    //   这一帧之前累积了 accFramesBeforeThis samples → 距最后一帧 accSamples*1000/16000 ms 前
                    val msBeforeNow = accFramesBeforeThis * 1000 / 16000
                    val thisFrameRecordedAtMs = drainNowMs - preRollTotalMs + msBeforeNow
                    val isAiEchoFrame = !fromInterrupt && thisFrameRecordedAtMs < aiEchoWindowEndMs && lastTtsPlayingAtMs > 0L
                    var sumSq = 0.0
                    for (i in 0 until frame.size) {
                        val s = frame[i] / 32768.0f
                        sumSq += (s * s).toDouble()
                    }
                    val rms = kotlin.math.sqrt(sumSq / frame.size).toFloat()
                    totalRmsAccum += rms
                    if (rms < minRms) minRms = rms
                    if (rms > maxRms) maxRms = rms
                    if (accFramesBeforeThis == 0) firstFrameRecMs = thisFrameRecordedAtMs
                    lastFrameRecMs = thisFrameRecordedAtMs
                    if (isAiEchoFrame) {
                        // AI 回声尾窗内的帧：直接丢弃且不算语音帧，并打断连续计数
                        aiEchoSkipped++
                        currentConsecutive = 0
                    } else {
                        // 【核心修改】：只有非回声帧才加入 cleanFrames 列表
                        cleanFrames.add(frame)

                        if (rms > speechThreshold) {
                            speechFrames++
                            currentConsecutive++
                            if (currentConsecutive > maxConsecutiveSpeech) maxConsecutiveSpeech = currentConsecutive
                        } else {
                            currentConsecutive = 0
                        }
                    }
                    accFramesBeforeThis += frame.size
                }
                val totalFrames = drainedList.size
                val speechRatio = if (totalFrames > 0) speechFrames.toDouble() / totalFrames else 0.0
                val avgRms = if (totalFrames > 0) (totalRmsAccum / totalFrames).toFloat() else 0f
                val safeMinRms = if (totalFrames > 0) minRms else 0f
                // 至少 20% 帧含语音 连续 4 帧语音才算"真正有人说话"
                // 0.20/4
                val hasRealSpeech = (speechRatio >= 0.20) || (maxConsecutiveSpeech >= 4)
                if (hasRealSpeech) {
                    cleanFrames
                } else {
                    null
                }
            }
        } else null
        val preRollPcm = preRollFiltered

        // 新 turn：记录身份（ASR final 后会再 newGeneration）
        identity = identity.newTurn()

        asrJob?.cancel()
        val snapshotTurnId = identity.turnId
        val snapshotSessionId = identity.callSessionId
        asrJob = scope.launch {
            try {
                val remainIgnoreMs = asrIgnoreUntil - System.currentTimeMillis()
                if (remainIgnoreMs > 0) {
                    Log.d(TAG, "Delay ${remainIgnoreMs}ms to avoid greeting echo")
                    delay(remainIgnoreMs)
                }
                asrManager.startRecognition(asrSetting, context, preRollPcm).collect { result ->
                    // 关键：先验身份。如果 turn/session 变了，这个结果就过期
                    if (identity.callSessionId != snapshotSessionId || identity.turnId != snapshotTurnId) {
                        Log.d(TAG, "ASR result stale: turn changed, drop '${result.text}'")
                        return@collect
                    }
                    // --- Partial（伪流式 / SystemASR 原生）：只更新 UI，不触发任何业务 ---
                    if (!result.isFinal) {
                        if (result.text.isNotBlank() && _callStatus.value == CallStatus.LISTENING) {
                            _listeningText.value = result.text.trim()
                        }
                        return@collect
                    }
                    // --- Final：走原有流程，发送给 LLM ---
                    if (result.isFinal && result.text.isNotBlank()) {
                        // ASR 启动忽略期：丢弃接通问候后的回声残余
                        if (System.currentTimeMillis() < asrIgnoreUntil) {
                            Log.w(TAG, "ASR final dropped (ignore period): '${result.text}'")
                            return@collect
                        }
                        // 问候语回声过滤：防止 AI 的 "喂？" 被扬声器回声识别成用户输入
                        // ★ 只在 ASR 文本长度接近问候语时才过滤（≤ 问候语长度 + 2 字符）
                        //   否则用户说的第一句话以 "喂" 开头（如 "喂？又来喽..."）也会被误杀
                        val greeting = lastGreetingText
                        if (greeting != null) {
                            val asrText = result.text.trim()
                            val normalizedGreeting = greeting.replace("[？?！!,.，。 ]".toRegex(), "")
                            val normalizedAsr = asrText.replace("[？?！!,.，。 ]".toRegex(), "")
                            val isGreetingEcho = normalizedAsr.length <= normalizedGreeting.length + 2 &&
                                (normalizedGreeting.contains(normalizedAsr) || normalizedAsr.contains(normalizedGreeting))
                            if (isGreetingEcho) {
                                Log.w(TAG, "ASR final dropped (matches greeting echo): '$asrText' vs greeting='$greeting'")
                                return@collect
                            }
                        }
                        Log.i(TAG, "ASR final turn=$snapshotTurnId: ${result.text}")
                        // Final 到达：立刻清空 listeningText（避免 THINKING 状态下还残留上一句）
                        _listeningText.value = null

                        _callStatus.value = CallStatus.THINKING
                        // 不再立即播 listenerCue，改为发消息后延迟 1s 再播（scheduleListenerCueDelayed）
                        // ================================================================
                        //  PDF §5 + §15 关键：AI 开始回复前立刻停掉 ASR 流式识别。
                        //  AI 说话期间只跑 VAD 做打断检测，ASR 必须保持静默。
                        //  否则扬声器的 AI 语音会被麦克风录下来，被 ASR 当成用户说话发送。
                        // ================================================================
                        val currentAsrJob = asrJob
                        sendUserMessage(result.text, convId, scheduleCue = true)
                        // sendUserMessage 之后身份已经推进 turn/generation，
                        // 此时立刻 cancel 旧的 ASR job（如果还没收尾的话），
                        // 等到 awaitTtsCompleteThenHandle → startListening 再重新开新 ASR。
                        currentAsrJob?.cancel()
                        // THINKING 阶段启动打断检测：用户可以语音打断 AI 思考
                        // 复用 AEC3+NS+VAD 全套逻辑，跳过 duck（无 TTS 可压低），直接 interrupt
                        val thinkGenId = identity.generationId
                        startInterruptionDetection(convId, thinkGenId, isThinkingPhase = true)
                        return@collect
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.e(TAG, "ASR stream error", e)
                val msg = e.message.orEmpty()
                when {
                    msg.contains("insufficient permissions") -> {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted && !asrPermissionRetryUsed) {
                            asrPermissionRetryUsed = true
                            Log.w(TAG, "ASR error(9) but RECORD_AUDIO granted, retrying once (ROM bug)")
                            scope.launch { _callError.emit("系统语音识别初始化中, 正在重试...") }
                            scope.launch {
                                delay(ASR_RETRY_DELAY_MS)
                                if (_isActive.value && _callStatus.value == CallStatus.LISTENING) {
                                    startListening()
                                }
                            }
                            return@launch
                        } else {
                            scope.launch { _callError.emit("系统语音识别服务异常(权限检查失败), 该设备可能不支持在线语音识别") }
                            hangup()
                            return@launch
                        }
                    }
                    msg.contains("not available") -> {
                        scope.launch { _callError.emit("当前设备不支持语音识别服务, 请检查是否已安装语音识别引擎") }
                        hangup()
                        return@launch
                    }
                    msg.contains("API Key is empty") -> {
                        scope.launch { _callError.emit("在线 ASR 未配置 API Key, 请在语音设置 - ASR 页签填写") }
                        hangup()
                        return@launch
                    }
                    msg.contains("ASR API") || msg.contains("timed out") || msg.contains("timeout") -> {
                        // ★ 在线 ASR 超时/网络异常 → 尝试兜底到本地 SenseVoice ASR
                        if (!asrFallbackUsed && asrSetting !is ASRProviderSetting.LocalSenseVoiceASR) {
                            val localReady = LocalSenseVoiceASRProvider.ensureBuiltinModel(context)
                            if (localReady) {
                                asrFallbackUsed = true
                                Log.w(TAG, "Online ASR failed, falling back to local SenseVoice: $msg")
                                scope.launch { _callError.emit("网络异常, 已切换到本地语音识别") }
                                scope.launch {
                                    delay(500)
                                    if (_isActive.value && _callStatus.value == CallStatus.LISTENING) {
                                        startListening()
                                    }
                                }
                                return@launch
                            }
                        }
                        scope.launch { _callError.emit("在线 ASR 错误: $msg") }
                        hangup()
                        return@launch
                    }
                    else -> {
                        scope.launch { _callError.emit("语音识别异常: $msg, 正在重试") }
                    }
                }
            }
            if (_isActive.value && _callStatus.value == CallStatus.LISTENING &&
                identity.callSessionId == snapshotSessionId && identity.turnId == snapshotTurnId
            ) {
                startListening()
            }
        }
    }

    // ========================================================================
    //  sendUserMessage → THINKING前导 → observe AI stream
    // ========================================================================

    private fun sendUserMessage(text: String, convId: Uuid, scheduleCue: Boolean = false) {
        Log.i(TAG, "sendUserMessage turn=${identity.turnId} scheduleCue=$scheduleCue: \"$text\"")
        noteUserSpoke()  // 重置 30 分钟沉默计时器 + 取消 AI 延迟挂断（用户仍在说话，不该挂）
        val priorConv = chatService.getConversationFlow(convId).value
        priorAssistantNodeId = priorConv.messageNodes.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
        Log.d(TAG, "priorAssistantNodeId=$priorAssistantNodeId")

        fedLengthPerNode.clear()
        spokenPartsPerNode.clear()
        spokenTextLen = 0
        speakingStarted = false

        // 新 generation：用于绑定 AI 生成 & 对应 TTS
        identity = identity.newGeneration()
        val snapshotGenId = identity.generationId
        Log.i(TAG, "  → generation=$snapshotGenId")

        chatService.sendMessage(
            conversationId = convId,
            content = listOf(UIMessagePart.Text(text)),
            skipContextForResponse = false
        )
        // ★ 发消息后延迟 1s，如果 LLM 还没返回可朗读内容就播 listenerCue（"嗯/啊/哦"）
        //   只有语音通话场景 scheduleCue=true；文本输入等不需要
        if (scheduleCue) scheduleListenerCueDelayed()
        observeAiResponseStreaming(convId, snapshotGenId)
    }

    /**
     * 延迟 1s 播放 listenerCue（"嗯/啊/哦"）：
     * 发消息后等待 LISTENER_CUE_DELAY_MS，如果 LLM 已返回可朗读内容（speakingStarted == true）则跳过，
     * 否则播放 listenerCue 给用户即时反馈。
     * speakingStarted=true 时 observeAiResponseStreaming 会立刻 cancel 本 job。
     */
    private fun scheduleListenerCueDelayed() {
        listenerCueJob?.cancel()
        listenerCueJob = scope.launch {
            delay(LISTENER_CUE_DELAY_MS)
            if (speakingStarted) {
                Log.d(TAG, "scheduleListenerCueDelayed: suppressed (speakingStarted already true)")
                return@launch
            }
            if (!_isActive.value || _callStatus.value != CallStatus.THINKING) return@launch
            val ttsSetting = settingsStore.settingsFlow.value.getSelectedTTSProvider()
            Log.i(TAG, "playListenerCueDelayed: LLM 未在 ${LISTENER_CUE_DELAY_MS}ms 内返回可朗读内容, 播放提示语")
            runCatching {
                playListenerCueNow(ttsSetting)
            }.onFailure { Log.w(TAG, "delayed listener cue play failed", it) }
        }
    }


    private fun observeAiResponseStreaming(convId: Uuid, expectGenId: String?) {
        responseJob?.cancel()
        responseJob = scope.launch {
            val streamJob = launch {
                chatService.getConversationFlow(convId).collect { conv ->
                    if (!_isActive.value) return@collect
                    // 身份校验：如果 generationId 已经过期（被打断换了新 turn），直接丢弃
                    if (expectGenId != null && identity.generationId != expectGenId) {
                        return@collect
                    }
                    // 遍历本轮产生的**所有**新 ASSISTANT 节点
                    // 工具调用链路会产生多个：节点1=前导话术+ToolCall，节点2=工具结果总结
                    val newAiNodes = findNewAssistantNodes(conv.messageNodes)
                    if (newAiNodes.isEmpty()) return@collect

                    newAiNodes.forEach { aiNode ->
                        val aiText = aiNode.currentMessage.parts
                            .filterIsInstance<UIMessagePart.Text>()
                            .joinToString("") { it.text }
                        val nodeFedLen = fedLengthPerNode[aiNode.id] ?: 0

                        if (aiText.length > nodeFedLen) {
                            // ★ 先喂句子，再根据是否真的喂了可朗读内容判断是否启动 speakingStarted
                            val actuallySpoken = feedNewSentencesForNode(aiNode, aiText, expectGenId)

                            // 只有真正喂了 TTS 可朗读文本（不是纯 think 标签/tool call 描述）
                            // 才认为 speakingStarted：否则 listenerCue 永远 cancel 太早/太晚
                            if (!speakingStarted && actuallySpoken) {
                                listenerCueJob?.cancel()  // LLM 已产出可朗读内容 → 取消延迟 listenerCue
                                speakingStarted = true
                                Log.i(TAG, "speakingStarted = true (LLM 首句可朗读内容已喂 TTS)")
                                lastTtsPlayingAtMs = System.currentTimeMillis()
                                _callStatus.value = CallStatus.SPEAKING
                                // THINKING → SPEAKING 过渡：停掉 THINKING 阶段的打断检测，
                                // 用 SPEAKING 模式重启（启用 TTS 回声基准 + 两阶段 duck→interrupt）
                                if (isVadRunning) {
                                    stopInterruptionDetection(skipPreRoll = true)
                                }
                                startInterruptionDetection(convId, expectGenId)
                            }
                            Log.d(TAG, "TTS feed node=${aiNode.id} textLen=${aiText.length} nodeFedLen=$nodeFedLen actuallySpoken=$actuallySpoken (${aiText.take(30)}...)")
                        }
                    }
                }
            }
            try {
                chatService.generationDoneFlow.first { it == convId }
            } catch (e: CancellationException) {
                streamJob.cancel()
                throw e
            }
            streamJob.cancel()

            if (!_isActive.value) return@launch
            // generation 过期（已经被打断换轮），不要 flush
            if (expectGenId != null && identity.generationId != expectGenId) {
                Log.i(TAG, "observeAiResponseStreaming: stale gen=$expectGenId, skip flush")
                return@launch
            }
            // flush 剩余：遍历所有新 ASSISTANT 节点，把每个节点还没喂完的残句都 flush 掉
            val conv = chatService.getConversationFlow(convId).value
            val newAiNodes = findNewAssistantNodes(conv.messageNodes)
            val flushSb = StringBuilder()
            newAiNodes.forEach { aiNode ->
                val aiText = aiNode.currentMessage.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
                val nodeFedLen = fedLengthPerNode[aiNode.id] ?: 0
                if (aiText.length > nodeFedLen) {
                    val remaining = aiText.substring(nodeFedLen.coerceAtMost(aiText.length)).stripMarkdownForTts()
                    if (remaining.isNotBlank()) {
                        if (expectGenId == null || identity.generationId == expectGenId) {
                            ttsController.speak(remaining, flush = false)
                            flushSb.append(remaining)
                            lastTtsPlayingAtMs = System.currentTimeMillis()
                            spokenPartsPerNode[aiNode.id] = aiText.length
                        }
                        fedLengthPerNode[aiNode.id] = aiText.length
                    }
                }
            }
            spokenTextLen = spokenPartsPerNode.values.sum()

            if (!speakingStarted) {
                if (_isActive.value) handleAfterAiResponse(convId, expectGenId)
                return@launch
            }
            awaitTtsCompleteThenHandle(convId, expectGenId)
        }
    }

    /**
     * 找出用户本轮发送后，新出现的**所有**ASSISTANT 节点（工具调用链路会产生多个：前导话术节点、工具结果总结节点）
     */
    private fun findNewAssistantNodes(messageNodes: List<MessageNode>): List<MessageNode> {
        val priorIndex = if (priorAssistantNodeId != null) {
            messageNodes.indexOfFirst { it.id == priorAssistantNodeId }
        } else -1
        return if (priorIndex >= 0) {
            messageNodes.drop(priorIndex + 1).filter { it.role == MessageRole.ASSISTANT }
        } else {
            messageNodes.filter { it.role == MessageRole.ASSISTANT }
        }
    }

    @Deprecated("Use findNewAssistantNodes + node-based tracking", ReplaceWith("findNewAssistantNodes(messageNodes).lastOrNull()"))
    private fun findNewAssistantNode(messageNodes: List<MessageNode>): MessageNode? =
        findNewAssistantNodes(messageNodes).lastOrNull()

    /**
     * 去除文本中的 Markdown 特殊标记，避免 TTS 念乱码：
     * - 标题 #、列表符号（- 或 *）、代码块 ```、行内代码 ``、链接 [text](url)、引用 >、加粗 **、斜体 *、表格
     *
     * 额外保护：MiniMax 官方支持的 (xxx) 语气词标签（如 (breath) / (laughs) / (emm) 等）
     * 不会被替换或删除，会原样透传给 TTS。
     */
    private fun String.stripMarkdownForTts(): String {
        if (this.isBlank()) return this

        // Step 0: 先把 TTS 语气词标签 (xxx) 整体「摘出来」用占位符保护，避免被后面的 Markdown 正则误伤。
        //         标签名只允许 a-z 和 -，正好匹配 Minimax 官方列表。
        val protectedTags = mutableListOf<String>()
        var result = this.replace(Regex("\\([a-z][a-z-]*\\)")) { match ->
            protectedTags.add(match.value)
            val index = protectedTags.size - 1
            "\u0000TTSTAG$index\u0000"
        }

        // 1. 代码块 ```...``` → 删内容（太长不念），保留"一段代码"提示
        result = result.replace(Regex("```[\\s\\S]*?```"), "（代码片段）")
        // 2. 行内代码 `code` → 保留文字，去掉反引号
        result = result.replace(Regex("`([^`]+)`"), "$1")
        // 3. 链接 [text](url) → 只保留 text  （注意：url 位置此时不会再匹配 TTS 标签，因为已被保护）
        result = result.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        // 4. 图片 ![alt](url) → 不念图片
        result = result.replace(Regex("!\\[[^\\]]*\\]\\([^)]+\\)"), "（图片）")
        // 5. 标题 ### / ## / # → 去掉前缀
        result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        // 6. 列表符号 - / * / + / 1. 2. → 保留换行（已经有换行作为分句标点）
        result = result.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        result = result.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
        // 7. 引用 > → 去掉
        result = result.replace(Regex("^>\\s*", RegexOption.MULTILINE), "")
        // 8. 加粗 **text** / __text__ → 只保留 text
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        result = result.replace(Regex("__([^_]+)__"), "$1")
        // 9. 斜体 *text* / _text_ → 只保留 text  （注意两边都是独立下划线单词，不会误伤 TTS 标签占位符）
        result = result.replace(Regex("\\*([^*]+)\\*"), "$1")
        result = result.replace(Regex("_([^_]+)_"), "$1")
        // 10. 表格 | 分隔符 / --- → 去掉
        result = result.replace(Regex("\\|"), "，")
        result = result.replace(Regex("-{3,}"), "")
        // 11. 行尾两个以上空格换行替换成正常换行
        result = result.replace(Regex("\\s{2,}\\n"), "\n")
        // 12. 多余的空行压缩
        result = result.replace(Regex("\\n{3,}"), "\n\n")

        // Step Z: 还原被保护的 TTS 标签
        result = result.replace(Regex("\u0000TTSTAG(\\d+)\u0000")) { match ->
            val idx = match.groupValues[1].toInt()
            protectedTags.getOrNull(idx) ?: ""
        }
        return result.trim()
    }

    /**
     * 按句号/感叹号/问号切句，喂给 TTS controller。
     *
     * @return 本轮是否真的喂了**可朗读**文本（即 stripMarkdownForTts 后非空的内容）。
     *         如果只有 think 标签 / tool call / 纯 markdown 标记，返回 false，调用方不会误以为"首字到了"。
     */
    private fun feedNewSentencesForNode(node: MessageNode, fullText: String, expectGenId: String?): Boolean {
        val fedLen = fedLengthPerNode[node.id] ?: 0
        if (fedLen >= fullText.length) return false
        var newPart = fullText.substring(fedLen)
        val sentenceEnd = Regex("[。！？!?；;\n]")
        var lastCut = 0
        var actuallySpokenAny = false
        var match: MatchResult? = sentenceEnd.find(newPart)
        while (match != null) {
            val end = match.range.last + 1
            val sentence = newPart.substring(lastCut, end).trim().stripMarkdownForTts()
            if (sentence.isNotEmpty()) {
                // 身份仍然有效才喂
                if (expectGenId == null || identity.generationId == expectGenId) {
                    ttsController.speak(sentence, flush = false)
                    lastTtsPlayingAtMs = System.currentTimeMillis()
                    actuallySpokenAny = true
                    // spokenPartsPerNode: 记录该节点已播报长度（用于总 spokenTextLen 计算）
                    val nodeSpokenEnd = (fedLen + end).coerceAtMost(fullText.length)
                    spokenPartsPerNode[node.id] = nodeSpokenEnd
                    spokenTextLen = spokenPartsPerNode.values.sum()
                }
            }
            lastCut = end
            match = sentenceEnd.find(newPart, end)
        }
        fedLengthPerNode[node.id] = fedLen + lastCut
        return actuallySpokenAny
    }

    /** AI 回答结束后回到监听状态 */
    private fun handleAfterAiResponse(convId: Uuid, expectGenId: String?) {
        // 身份过时说明被打断，不应再继续
        if (expectGenId != null && identity.generationId != expectGenId) {
            startListeningSafely()
            return
        }
        // 挂断控制：由 call_control 本地工具（AI 主动调用 hangup）和 UI 挂断按钮驱动，
        // 不再在此处用后台模型异步判断，避免误挂断/多消耗一次模型调用。
        startListeningSafely()
    }

    private suspend fun awaitTtsCompleteThenHandle(convId: Uuid, expectGenId: String?) {
        var silentSince = 0L
        while (_isActive.value && (_callStatus.value == CallStatus.SPEAKING || _callStatus.value == CallStatus.DUCKING)) {
            // generation 被打断 → 不再等
            if (expectGenId != null && identity.generationId != expectGenId) break
            if (!ttsController.isSpeaking.value) {
                if (silentSince == 0L) silentSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - silentSince >= TTS_SILENCE_TIMEOUT_MS) {
                    Log.d(TAG, "TTS complete gen=$expectGenId (silent for ${TTS_SILENCE_TIMEOUT_MS}ms)")
                    break
                }
            } else {
                lastTtsPlayingAtMs = System.currentTimeMillis()
                silentSince = 0L
            }
            delay(100)
        }
        stopInterruptionDetection()
        resetStreamingState()
        handleAfterAiResponse(convId, expectGenId)
    }

    private fun startListeningSafely() {
        if (_isActive.value) startListening()
    }

    // ========================================================================
    //  打断检测（两阶段 DUCK → INTERRUPT） + PCM 预卷 + 回声尾窗
    // ========================================================================

    /**
     * SPEAKING / THINKING 期间启动 VAD + AudioRecord，做：
     * 1. 持续写 PCM 环形预卷
     * 2. 尾窗 220ms 动态提高 RMS 门槛（PDF §15.2）
     * 3. 240ms duck → 520ms interrupt 的两阶段确认（THINKING 阶段跳过 duck，直接 interrupt）
     *
     * @param isThinkingPhase true=THINKING 阶段（等待 LLM 回复），跳过 TTS 等待 + 跳过 duck，
     *                        无 TTS 播放 → 无回声 → 非严格阈值，用户说话即直接 interrupt。
     *                        false=SPEAKING 阶段（AI 正在说话），完整两阶段检测。
     */
    @SuppressLint("MissingPermission")
    private fun startInterruptionDetection(convId: Uuid, expectGenId: String?, isThinkingPhase: Boolean = false) {
        // 先停 LISTENING 模式的轻量预卷录音，避免两个 AudioRecord 抢设备
        stopListeningPreRoll()
        if (vadDetector == null) return
        val vad = vadDetector ?: return
        vad.reset()
        // 清空预卷（上一轮残留）
        synchronized(preRollFrames) { preRollFrames.clear() }

        // 权限检查：确保调用方已授予 RECORD_AUDIO
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            Log.e(TAG, "startInterruptionDetection: Missing RECORD_AUDIO permission")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            VadDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord min buffer error: $minBuf")
            return
        }
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                VadDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "startInterruptionDetection: SecurityException: ${e.message}")
            return
        }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            audioRecord?.release(); audioRecord = null
            return
        }

        val sessionId = audioRecord?.audioSessionId ?: 0
        try {
            // ====== WebRTC APM 正式接管回声消除：AEC3 + NS（session 绑定方式） ======
            // Android 系统 AcousticEchoCanceler 跨设备一致性差（部分机型过消音、部分机型不消），
            // 用 WebRTC 官方 AEC3 算法统一处理，效果最稳定。
            val systemAecAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
            Log.i(TAG, "System AEC available=$systemAecAvailable → skipped, using WebRTC AEC3 (session bind)")
            // NoiseSuppressor 同样交给 WebRTC NS，避免重复处理
            Log.i(TAG, "System NS skipped → using WebRTC NoiseSuppression")
            // ===== 创建 APM → 绑定到 AudioRecord session（必须在 startRecording() 之前调用 enable()） =====
            // 绑定后，MediaServer 会自动把 ExoPlayer/AudioTrack 播放的 TTS 作为 far-end 参考，
            // AudioRecord.read() 返回的直接就是消过回声 + 降噪的 PCM，不用我们手动喂 far/near。
            runCatching {
                val newApm = WebRtcAudioProcessor()
                apm = newApm
                if (newApm.isAvailable()) {
                    val ok = newApm.attachToAudioRecordSession(sessionId)
                    Log.i(
                        TAG,
                        "WebRTC APM ready (session=$sessionId, attachOk=$ok) → " +
                            "AEC3=${newApm.isAecSupported} NS=${newApm.isNsSupported}"
                    )
                } else {
                    Log.w(TAG, "WebRTC APM unavailable (.so load failed?), fallback pure VAD")
                }
                // far-end 监听器不需要了：WebRtcAudioEffects 走 MediaServer 自动路由
                ttsController.setOnFarPcmListener(null)
            }.onFailure { e ->
                Log.e(TAG, "WebRTC APM init FAILED, fallback pure VAD: ${e.message}")
                apm = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio effect setup failed: ${e.message}")
        }

        isVadRunning = true
        audioRecord?.startRecording()
        Log.i(TAG, "Interruption detection started gen=$expectGenId")

        interruptionJob?.cancel()
        val snapGenId = expectGenId
        interruptionJob = scope.launch(Dispatchers.IO) {
            // THINKING 阶段跳过 TTS 等待（LLM 还没回复，TTS 不会播放）
            if (!isThinkingPhase) {
                // 等 TTS 真的开始播放，再建立回声基准
                var waitMs = 0
                while (isVadRunning && isActive &&
                    (snapGenId == null || identity.generationId == snapGenId)
                ) {
                    if (ttsController.playbackState.value.status == PlaybackStatus.Playing) break
                    delay(50); waitMs += 50
                    if (waitMs >= 5000) {
                        Log.w(TAG, "TTS did not start within 5s, skip interruption detection")
                        return@launch
                    }
                }
                if (!isVadRunning || !isActive) return@launch
                if (snapGenId != null && identity.generationId != snapGenId) return@launch
            }

            val buffer = ShortArray(VadDetector.WINDOW_SIZE)
            var consecutiveSpeechFrames = 0
            // THINKING 阶段跳过 duck（无 TTS 可压低），直接到 confirm 帧数即 interrupt
            var duckTriggered = isThinkingPhase
            var frameCount = 0L  // 调试用：每 40 帧(~1.3s)采样打印一次 RMS/阈值状态
            var lastVadDiagMs = 0L  // [ASRDiag] 周期日志节流（每 1s，AI 说话期间）

            val slidingWindow = ArrayDeque<Double>()
            var slidingAvg = 0.0
            var baselineReady = false

            while (isVadRunning && isActive) {
                // generation 被打断：退出
                if (snapGenId != null && identity.generationId != snapGenId) break
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read <= 0) { consecutiveSpeechFrames = 0; continue }

                // 关键：WebRtcAudioEffects 已通过 sessionId 绑定到这个 AudioRecord 上，
                // 所以 buffer 里 read() 返回的**已经是** WebRTC AEC3 消回声 + NS 降噪后的 PCM，
                // 不用再手动 processStream，MediaServer 在 native 层帮我们做了。

                // 1) 写 PCM 预卷（环形 ~1s）；deep copy 避免后续 buffer 复用覆盖
                val frameCopy = buffer.copyOf()
                synchronized(preRollFrames) {
                    preRollFrames.addLast(frameCopy)
                    while (preRollFrames.size > PRE_ROLL_MAX_FRAMES) preRollFrames.removeFirst()
                }

                // 计算 rawMax（看 PCM 声压，判断 AEC3 是否真的在工作：AI 说话时 rawMax 应压得很低）
                var rawMax = 0
                for (i in 0 until read) {
                    val v = kotlin.math.abs(buffer[i].toInt())
                    if (v > rawMax) rawMax = v
                }

                if (_isMuted.value) { consecutiveSpeechFrames = 0; continue }
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                val rms = calculateRms(samples)
                val floatMax = samples.maxOrNull() ?: 0f

                if (!baselineReady) {
                    slidingWindow.addLast(rms)
                    if (slidingWindow.size >= BASELINE_LEARN_FRAMES) {
                        slidingAvg = slidingWindow.average()
                            // ===== baseline 最小值保护：防止全静音时 baseline=0，导致后面稍微有一点声就被当抢话 =====
                            .coerceAtLeast(0.002)
                        baselineReady = true
                        Log.i(TAG, "Sliding baseline ready: avg=${"%.4f".format(slidingAvg)}")
                    }
                    continue
                }

                // PDF §13 Barge-in + §15 Echo：
                //   TTS 正在 PLAYING 时 → 开启「抢话严格模式」（AEC 已默认关闭，这里仅用非常温和的 boost 做回声兜
                //   底）：
                //   1. 回声门限 boost 1.1（基本不抬高）
                //   2. 绝对声压下限 0.004（满幅 0.4%，只要有正常呼吸声以上就能过）
                //   3. 连续帧完全取消倍率，直接 8/16 帧 = 256/512ms
                val ttsNowPlaying = ttsController.playbackState.value.status == PlaybackStatus.Playing
                // THINKING 阶段无 TTS → 无回声 → 非严格阈值
                val strictBargeIn = if (isThinkingPhase) false else ttsNowPlaying
                // strictBoost 降到 1.1，几乎等于不抬高
                val strictBoost = if (strictBargeIn) 1.1 else 1.0
                // 回声尾窗：最近 220ms 内 TTS 在播放 → 门限 × 1.1（轻微）
                val tailBoost = if (
                    System.currentTimeMillis() - lastTtsPlayingAtMs < ECHO_TAIL_WINDOW_MS
                ) ECHO_TAIL_BOOST else 1.0
                // 绝对声压下限：严格模式 0.004（0.4% 满幅），非严格 0.003。
                // 之前 0.015 → 0.01 → 0.004，保证低声说话也能过
                val absoluteFloor = if (strictBargeIn) 0.004 else 0.003

                // 根据用户设置的 callBargeInSensitivity (0-100, default 50) 计算打断参数：
                //   灵敏度越高 → confirmFrames 越少（容易被打断）、RMS 倍率越低（轻声也能触发）、绝对声压下限越低
                //   灵敏度越低 → confirmFrames 越多（抗误触强）、RMS 倍率越高（需要大音量）
                val sensitivity = settingsStore.settingsFlow.value.callBargeInSensitivity.coerceIn(0, 100)
                val s = sensitivity / 100.0  // 0 ~ 1 (Double)
                // confirmFrames: sens=0→22 帧 / sens=50→14 帧 / sens=100→6 帧 (线性插值)
                val userConfirmFrames = (22.0 - 16.0 * s).toInt().coerceIn(6, 24)
                // duckFrames: confirmFrames * 0.5 左右（但至少 3）
                val userDuckFrames = (userConfirmFrames * 0.5).toInt().coerceAtLeast(3)
                // RMS 倍率: sens=0→1.9（门槛高）/ sens=50→1.4 / sens=100→0.9（门槛低）
                val userRmsMult = 1.9 - s
                // 绝对声压下限: sens=0→0.006（严格）/ sens=50→0.004 / sens=100→0.0025（宽松）
                val userFloorStrict = 0.006 - s * 0.0035 // range: 0.006 ~ 0.0025
                val userFloorLoose = (userFloorStrict - 0.001).coerceAtLeast(0.0015)

                val rmsThreshold = (slidingAvg * userRmsMult * tailBoost * strictBoost)
                    .coerceAtLeast(if (strictBargeIn) userFloorStrict else userFloorLoose)
                val duckFrames = userDuckFrames
                val confirmFrames = userConfirmFrames

                vad.acceptWaveform(samples)
                val vadDetected = vad.isSpeechDetected()
                val loudEnough = rms >= rmsThreshold

                // ===== 调试：每 40 帧打印一次 RMS 概览 =====
                // AEC3 生效判断（重点看 strict=true 的 AI 说话期间）：
                //   ✅ 生效：AI 大声说话时 rawMax 仍然很小（几十~几百，和 baseline 差不多）
                //   ❌ 没生效：AI 说话时 rawMax 飙到几千上万
                // 注意：因为 AEC 在底层 AudioEffect 处理，应用层 read() 到的直接是"消后"的结果，
                // 所以看不到 before 对比，只能靠 strict 状态下的绝对值判断。
                frameCount++
                val nowMs = System.currentTimeMillis()
                // [ASRDiag] AI 说话期间周期性诊断（每 1s，与 LISTENING 阶段 ASR 循环同关键词，可全程对比）
                // logcat 搜 "ASRDiag" 即可看到所有阶段的 RMS/阈值：
                //   stage=AI_SPEAKING：AI 说话中（interruptionJob），rms 应很低（AEC 消了回声）；rms 高=AEC 没消干净
                //   stage 缺省：LISTENING 中（ASR 循环），不说话 rms=环境噪音，说话 rms 升高
                if (nowMs - lastVadDiagMs > 1000) {
                    lastVadDiagMs = nowMs
                    Log.i(TAG, "[ASRDiag] rms=${"%.4f".format(rms)} thr=${"%.4f".format(rmsThreshold)} " +
                        "baseline=${"%.4f".format(slidingAvg)} " +
                        "sens=$sensitivity thrMult=${"%.2f".format(userRmsMult)} floor=[$userFloorStrict~$userFloorLoose] " +
                        "boosts[tail=$tailBoost strict=$strictBoost] " +
                        "vad=$vadDetected loud=$loudEnough cons=$consecutiveSpeechFrames/$duckFrames~$confirmFrames " +
                        "strict=$strictBargeIn apm=${if (apm?.isAvailable() == true) "AEC3+NS" else "OFF"} " +
                        "rawMax=$rawMax floatMax=${"%.4f".format(floatMax)} read=$read stage=${if (isThinkingPhase) "AI_THINKING" else "AI_SPEAKING"}")
                }

                if (vadDetected && loudEnough) {
                    consecutiveSpeechFrames++
                    // 阶段 1：累计帧到 duckFrames，触发 duck
                    if (!duckTriggered && consecutiveSpeechFrames >= duckFrames) {
                        duckTriggered = true
                        Log.i(TAG, "Duck frames=$consecutiveSpeechFrames strict=$strictBargeIn rms=${"%.4f".format(rms)} thr=${"%.4f".format(rmsThreshold)}")
                        triggerDuck(convId, snapGenId)
                    }
                    // 阶段 2：累计 confirmFrames，确认 interrupt
                    if (duckTriggered && consecutiveSpeechFrames >= confirmFrames) {
                        Log.i(TAG, "Barge-in confirmed frames=$consecutiveSpeechFrames strict=$strictBargeIn")
                        confirmInterrupt(convId, snapGenId)
                        break
                    }
                } else {
                    // ====== 只要有任一条件不满足，就清零连续计数 ======
                    // 允许"声压够了但 VAD 一时没认出来"这种情况放宽：loudEnough 为 true 时，连续帧至少保持不变、不立即清零
                    if (!loudEnough) {
                        // 声压都不够，直接清零
                        consecutiveSpeechFrames = 0
                    } else if (consecutiveSpeechFrames > 0) {
                        // 声压够了但 VAD=false：允许容忍 3 帧 (~100ms) 的 VAD 抖动，不减到 0，只 -1
                        consecutiveSpeechFrames = (consecutiveSpeechFrames - 1).coerceAtLeast(0)
                    }
                    // 用户闭嘴：若已经 duck 但未到 confirm → 回弹、取消 duckConfirm、继续
                    // THINKING 阶段不回弹（duckTriggered 始终 true，无 TTS 需要恢复音量）
                    if (duckTriggered && consecutiveSpeechFrames == 0 && !isThinkingPhase) {
                        Log.i(TAG, "Speech stopped after duck; restore volume, abort interrupt")
                        cancelDuckAndRestore(snapGenId)
                        duckTriggered = false
                    }
                    // 非语音：更新基准（追踪回声变化）
                    // ====== baseline 上限保护 ======
                    // 如果当前帧 RMS 明显大于现有 baseline（>1.6×baseline 或 >absoluteFloor×2.5），
                    // 说明这不是背景噪声（更像是用户在说话、或扬声器音量突然变大的漏音），
                    // 就**不把这帧放进滑动窗口**，避免 baseline 被越抬越高导致打不断。
                    val safeMaxForUpdate = (slidingAvg * 1.6)
                        .coerceAtLeast(absoluteFloor * 2.5)
                    if (rms <= safeMaxForUpdate) {
                        slidingWindow.addLast(rms)
                        if (slidingWindow.size > SLIDING_WINDOW_FRAMES) slidingWindow.removeFirst()
                        val newAvg = slidingWindow.average()
                            // 同样：baseline 永远不低于 0.002，避免全静音导致永远 0、下一次一有值就炸
                            .coerceAtLeast(0.002)
                        slidingAvg = newAvg
                    }
                }
            }
        }
    }

    /** 阶段1：DUCK - 压低 TTS 音量 + 切换 UI 状态（不取消生成） */
    private fun triggerDuck(convId: Uuid, expectGenId: String?) {
        // 身份校验
        if (expectGenId != null && identity.generationId != expectGenId) return
        if (!_isActive.value) return
        val wasSpeaking = _callStatus.value == CallStatus.SPEAKING
        if (!wasSpeaking) return
        _callStatus.value = CallStatus.DUCKING
        ttsController.duckVolume(targetVolume = DUCK_TARGET_VOLUME, durationMs = DUCK_DURATION_MS)
    }

    /** 用户闭嘴、抢话不成立 → 回弹 */
    private fun cancelDuckAndRestore(expectGenId: String?) {
        if (expectGenId != null && identity.generationId != expectGenId) return
        duckConfirmJob?.cancel(); duckConfirmJob = null
        ttsController.restoreVolume(durationMs = 160L)
        if (_callStatus.value == CallStatus.DUCKING) {
            _callStatus.value = CallStatus.SPEAKING
        }
    }

    /** 阶段2：INTERRUPT - 确认抢话，停 TTS/取消生成/回到监听 */
    private fun confirmInterrupt(convId: Uuid, expectGenId: String?) {
        if (expectGenId != null && identity.generationId != expectGenId) {
            Log.d(TAG, "confirmInterrupt: stale gen=$expectGenId, skip")
            return
        }
        interrupt(convId)
    }

    private fun calculateRms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s * s
        return kotlin.math.sqrt(sum / samples.size)
    }

    private var aecEffect: AcousticEchoCanceler? = null
    private var nsEffect: NoiseSuppressor? = null

    private fun stopInterruptionDetection(skipPreRoll: Boolean = false) {
        isVadRunning = false
        interruptionJob?.cancel(); interruptionJob = null
        duckConfirmJob?.cancel(); duckConfirmJob = null
        runCatching { audioRecord?.stop() }
        audioRecord?.release(); audioRecord = null
        runCatching { aecEffect?.let { it.enabled = false; it.release() } }
        aecEffect = null
        runCatching { nsEffect?.let { it.enabled = false; it.release() } }
        nsEffect = null
        // ====== 释放 WebRTC APM + 解绑 far-end 监听 ======
        // 先解绑监听避免 TTS 继续喂数据到死掉的 APM，再 close 释放 native 资源
        runCatching { ttsController.setOnFarPcmListener(null) }
        runCatching { apm?.close() }
        apm = null
        // ===== 条件性 preRoll 处理 + 延迟启动 listeningPreRoll =====
        // stopInterruptionDetection 有三种触发场景：
        //   a) AI 自然说完（awaitTtsCompleteThenHandle 等 TTS 静默 1.5s 后调用）：
        //      此时 TTS 已停了 >1.5s，preRoll 里是 1.5s 的干净静音/用户开头音频 → **保留不清**
        //      不需要 delay，立刻启 listeningPreRoll
        //   b) 用户打断（interrupt → stopInterruptionDetection）：
        //      TTS 刚停，preRoll 是 interruptionJob 录的 —— 该 AudioRecord 通过 session 绑定了
        //      WebRTC APM，read() 返回的已是 AEC3 消过回声的干净 PCM → **保留不清**（清空会丢用户开头字）
        //      仅需 delay ECHO_TAIL_WINDOW_MS 后启 listeningPreRoll（避免 AudioRecord 设备冲突）
        //   c) THINKING → SPEAKING 过渡（skipPreRoll=true）：
        //      不启动 listeningPreRoll，因为马上要重启打断检测（SPEAKING 模式）
        if (skipPreRoll) {
            Log.d(TAG, "stopInterruptionDetection: skipPreRoll=true (THINKING→SPEAKING transition)")
        } else {
            val timeSinceLastTts = System.currentTimeMillis() - lastTtsPlayingAtMs
            val isInterruptCase = timeSinceLastTts < ECHO_TAIL_WINDOW_MS
            if (isInterruptCase) {
                // 打断场景：不清空 preRoll —— interruptionJob 的 AudioRecord 通过 session 绑定了
                // WebRTC APM（AEC3+NS），read() 返回的已是消过回声的干净 PCM，preRoll 里的帧是
                // 用户语音而非 AI 回声。清空会导致用户开头字丢失（"漏了一些字"的根因）。
                // 仅 delay 后启 listeningPreRoll（避免和刚释放的打断检测 AudioRecord 设备冲突）
                val needDelayMs = ECHO_TAIL_WINDOW_MS - timeSinceLastTts
                scope.launch(Dispatchers.IO) {
                    delay(needDelayMs)
                    startListeningPreRollIfNeeded()
                }
            } else {
                startListeningPreRollIfNeeded()
            }
        }
    }

    /**
     * LISTENING 模式下的轻量预卷录音：只填 preRollFrames，不做打断判断。
     * 覆盖：问候 warmup→startListening 交接空窗、AI 回复完→startListening 空窗、用户停顿期间
     * 不启 WebRTC APM/AEC（TTS 不播时不需要），避免额外 CPU 开销。
     *
     * 源头级防 AI 自录：本循环内的每一帧都会做两道门控 ——
     *   1. TTS 状态门：AI 正在播放 / 停后 ECHO_TAIL_WINDOW_MS 内 → 直接丢，不进预卷
     * 这样 preRollFrames 里从源头就没有 AI 尾音，下游的能量过滤 + ASR 就不会"看见"AI 的话。
     */
    @SuppressLint("MissingPermission", "SetWorldReadable")
    private fun startListeningPreRollIfNeeded() {
        if (listeningPreRollJob != null) return
        if (interruptionJob != null) return // 打断检测已在运行，防 AudioRecord 冲突
        if (_isMuted.value) return
        val ctx = context.applicationContext
        val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1024)
        val frameSize = 512 // 32ms @16kHz mono，与打断检测帧大小一致
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameSize * 4)
            )
        } catch (e: Exception) {
            Log.w(TAG, "startListeningPreRollIfNeeded: AudioRecord init failed", e)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return
        }
        listeningPreRollJob = scope.launch(Dispatchers.IO) {
            try {
                rec.startRecording()
                val buf = ShortArray(frameSize)
                val noiseGate = RMS_SPEECH_THRESHOLD_PRE_ROLL
                var skippedTtsTail = 0
                var skippedNoise = 0
                Log.d(TAG, "ListeningPreRoll: started (filling preRollFrames, with TTS tail + RMS gating)")
                while (isActive) {
                    val read = rec.read(buf, 0, frameSize)
                    if (read <= 0) continue
                    val now = System.currentTimeMillis()
                    // ===== Gate 1: TTS 状态门（核心防 AI 自录） =====
                    // AI 正在播放 → 这帧大概率是扬声器回声，丢
                    val ttsPlaying = ttsController.playbackState.value.status == PlaybackStatus.Playing
                    // AI 停了但尾窗没过 → 扬声器物理振铃仍可能很大声，丢
                    val inTtsTail = now - lastTtsPlayingAtMs < ECHO_TAIL_WINDOW_MS && lastTtsPlayingAtMs > 0L
                    if (ttsPlaying || inTtsTail) {
                        skippedTtsTail++
                        continue
                    }
                    // ===== Gate 2: RMS 噪声门（节省预卷空间 + 减少下游误识别"嗯/啊"） =====
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = buf[i] / 32768.0f
                        sumSq += (s * s).toDouble()
                    }
                    val rms = kotlin.math.sqrt(sumSq / read).toFloat()
                    if (rms < noiseGate) {
                        skippedNoise++
                        continue
                    }
                    // 通过两道门 → 深拷贝后进预卷环形缓冲
                    val frameCopy = buf.copyOfRange(0, read)
                    synchronized(preRollFrames) {
                        preRollFrames.addLast(frameCopy)
                        while (preRollFrames.size > PRE_ROLL_MAX_FRAMES) preRollFrames.removeFirst()
                    }
                }
                Log.d(TAG, "ListeningPreRoll: stopped (skippedTtsTail=$skippedTtsTail skippedNoise=$skippedNoise)")
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }
    }

    private fun stopListeningPreRoll() {
        listeningPreRollJob?.cancel()
        listeningPreRollJob = null
    }

    /**
     * 用户打断:
     *  PDF §14: 1.新 turn id 2.旧 generation 标 cancel 3.停TTS 4.停本地反馈
     *         5.清 pending chunks 6.通知后端取消 7.UI→LISTENING 8.保留预卷 9.接下一轮
     */
    private fun interrupt(convId: Uuid) {
        Log.i(TAG, "interrupt turn=${identity.turnId} gen=${identity.generationId}")
        // ===== 在推进身份之前先快照，用于异步 truncate 的会话校验（防止挂断→重拨后旧协程串改新会话） =====
        val snapshotCallSession = identity.callSessionId
        val snapshotGenId = identity.generationId
        // =========================================================================
        //  1. 截断策略："宁可少截断也不多截"
        //     - ttsPlayedLen = TTS 真正播放完成的字符数（按 chunk 粒度，整个 chunk 播完才累加）
        //     - queueFedLen = 已经喂给 TTS 队列的字符数（喂入即累加，含正在播放 + 排队中的内容）
        //
        //     旧逻辑取较小值（ttsPlayedLen），会导致正在播放的那句（已部分念出）被截掉。
        //     新逻辑取较大值（queueFedLen），保留所有已提交给 TTS 的内容：
        //       · 已播完的 → 保留 ✓
        //       · 正在播的（用户已听到一部分）→ 保留 ✓
        //       · 刚排队还没播的（马上要念给用户听的）→ 保留 ✓
        //     符合用户"宁可少截断也不多截"的偏好。
        // =========================================================================
        val ttsPlayedLen = ttsController.playedTextLength.value.coerceAtLeast(0)
        val queueFedLen = spokenTextLen.coerceAtLeast(0)
        val saveSpokenLen = maxOf(ttsPlayedLen, queueFedLen)
        Log.i(TAG, "  → truncate: ttsPlayed=$ttsPlayedLen queueFed=$queueFedLen use=$saveSpokenLen (prefer more)")
        val wasSpeaking = speakingStarted
        // 停 TTS + 本地思考前导
        ttsController.stop()
        listenerCueJob?.cancel(); listenerCueJob = null
        // 取消生成
        chatService.stopGeneration(convId)
        stopInterruptionDetection()
        responseJob?.cancel(); responseJob = null
        resetStreamingState()
        // 推进身份：新 turn（打断意味着用户要开始新一轮说）
        identity = identity.newTurn()

        // 如果 AI 真的开始说话过 (wasSpeaking)，并且还有没念到的内容 → 从表里截断
        if (wasSpeaking) {
            scope.launch(Dispatchers.IO) {
                // ===== 关键校验：会话或 generation 被替换（挂断→重拨）则直接放弃，避免串改新会话 =====
                if (identity.callSessionId != snapshotCallSession) {
                    Log.w(TAG, "Interrupt: truncate skipped (callSession changed: $snapshotCallSession → ${identity.callSessionId})")
                    return@launch
                }
                if (snapshotGenId != null && identity.generationId == snapshotGenId) {
                    // 理论上不会发生：newTurn() 后 generationId 已经被清空，留作防御
                    Log.w(TAG, "Interrupt: truncate skipped (same generation still active)")
                    return@launch
                }
                runCatching {
                    chatService.truncateLastAssistantMessage(convId, saveSpokenLen)
                    Log.i(TAG, "Interrupt: truncated to spokenLen=$saveSpokenLen")
                }.onFailure {
                    Log.w(TAG, "Interrupt: truncate failed", it)
                }
            }
        }
        // 预卷已保留在 preRollFrames（interruptionJob 录的 AEC 处理后干净帧），等下一轮 ASR 开始后可自然衔接
        // ★ 打断仅停止 TTS + LLM，不取消 ASR 监听缓存：preRollFrames 在 stopInterruptionDetection 中被保留，
        //   startListening(fromInterrupt=true) 会 drain 并传给新 ASR 会话，用户开头字不丢
        Log.i(TAG, "interrupt: TTS+LLM stopped, ASR cache (preRollFrames) preserved, starting new listening")
        // fromInterrupt=true：让 startListening 跳过 AI 回声时间过滤（信任打断检测的 AEC），避免误丢用户开头字
        if (_isActive.value) startListening(fromInterrupt = true)
    }

    private fun resetStreamingState() {
        fedLengthPerNode.clear()
        spokenPartsPerNode.clear()
        spokenTextLen = 0
        speakingStarted = false
        priorAssistantNodeId = null
    }

    // ========================================================================
    //  UI actions: toggleMute / toggleSpeaker / hangup / manualInterrupt
    // ========================================================================

    /**
     * 手动打断：用户点击按钮强制打断 AI 回复，回到倾听状态。
     * 只在 THINKING / SPEAKING / DUCKING 状态下生效。
     */
    fun manualInterrupt() {
        val currentStatus = _callStatus.value
        if (currentStatus != CallStatus.THINKING &&
            currentStatus != CallStatus.SPEAKING &&
            currentStatus != CallStatus.DUCKING
        ) {
            Log.d(TAG, "manualInterrupt: ignored (status=$currentStatus)")
            return
        }
        val convId = conversationId ?: return
        Log.i(TAG, "Manual interrupt by user (status=$currentStatus)")
        noteUserSpoke()  // 用户主动操作：重置沉默计时 + 取消 AI 延迟挂断
        interrupt(convId)
    }

    fun toggleMute() {
        _isMuted.update { !it }
        val muted = _isMuted.value
        Log.i(TAG, "Mute toggled: $muted")
        if (muted) {
            asrJob?.cancel()
            stopListeningPreRoll()
        } else {
            if (_isActive.value && _callStatus.value == CallStatus.LISTENING) {
                startListeningPreRollIfNeeded()
                startListening()
            }
        }
    }

    fun toggleSpeaker() {
        _isSpeakerOn.update { !it }
        applySpeakerRoute(_isSpeakerOn.value)
    }

    fun hangup() {
        Log.i(TAG, "hangup session=${identity.callSessionId}")
        _isActive.value = false
        CallCommandHub.setCallActive(false)
        _callStatus.value = CallStatus.DISCONNECTED
        stopInterruptionDetection()
        stopListeningPreRoll()
        stopL1Timer()
        stopUserSilenceMonitor()
        aiDelayedHangupJob?.cancel(); aiDelayedHangupJob = null
        asrJob?.cancel()
        responseJob?.cancel()
        listenerCueJob?.cancel(); listenerCueJob = null
        duckConfirmJob?.cancel(); duckConfirmJob = null
        ttsController.stop()
        // 恢复音量（避免被 duck 后退出通话，下次进音量残留低）
        ttsController.setVolume(1f)
        conversationId?.let {
            chatService.stopGeneration(it)
            chatService.setCallMode(it, active = false)
        }
        // 停止前台 Service + 释放 WakeLock
        VoiceCallForegroundService.stop(context)
        vadDetector?.release(); vadDetector = null
        // ===== 停止接近传感器 =====
        stopProximitySensor()
        // ====== 兜底：hangup 时再清一次 APM / far-end 监听，防止 stopInterruptionDetection 漏调 ======
        runCatching { ttsController.setOnFarPcmListener(null) }
        runCatching { apm?.close() }
        apm = null
        synchronized(preRollFrames) { preRollFrames.clear() }
        conversationId = null
        resetStreamingState()
        _isMuted.value = false
        asrPermissionRetryUsed = false
        asrFallbackUsed = false
        identity = CallIdentity(callSessionId = "none")
        _callStartTimeMs.value = 0L
        _listeningText.value = null
        // 退出通话音频模式：还原 audio mode / speaker route
        leaveCallAudioMode()
        // 通话结束：恢复对话页的自动朗读（从通话开始前的断点继续，不漏读）
        customTtsState.resumeAutoReadAfterCall()
    }

    // ========================================================================
    //  接近传感器监听（贴近耳朵自动息屏，模拟原生电话效果）
    // ========================================================================

    /**
     * 启动接近传感器监听。
     * 硬件优先级：
     *   - PROXIMITY_SCREEN_OFF_WAKE_LOCK：让系统直接关背光（ROM 支持时最自然，和电话一模一样）
     *   - 否则走 UI 层全黑遮罩兜底（通过 _isNearEar StateFlow 通知 CallScreen）
     * 扬声器联动：贴近耳朵时自动切听筒，远离时恢复用户原先的开关选择。
     */
    @SuppressLint("WakelockTimeout")
    private fun startProximitySensor() {
        // 1. SensorManager + Proximity Sensor 获取
        val sm = context.getSystemService<SensorManager>()
        sensorManager = sm
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor = sensor
        if (sensor == null) {
            Log.w(TAG, "startProximitySensor: 设备没有接近传感器，跳过自动息屏")
            return
        }
        Log.i(TAG, "startProximitySensor: name=${sensor.name} vendor=${sensor.vendor} " +
            "maxRange=${sensor.maximumRange}cm power=${sensor.power}mA")

        // 2. PROXIMITY_SCREEN_OFF_WAKE_LOCK：
        //    这个级别的 WakeLock 是给电话应用专用的，触发时系统会真正关闭 LCD 背光
        //    （类似接通电话贴耳朵黑屏），并过滤掉触摸事件。部分 ROM 可能不支持，
        //    所以需要同时启动 _isNearEar 状态让 UI 层做遮罩兜底。
        runCatching {
            val pm = context.getSystemService<PowerManager>()
            if (pm != null) {
                val tag = "Evolia:ProximityWakeLock"
                val flags = PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK
                proximityWakeLock = pm.newWakeLock(flags, tag).apply {
                    setReferenceCounted(false)
                }
                Log.i(TAG, "PROXIMITY_SCREEN_OFF_WAKE_LOCK created")
            }
        }.onFailure {
            Log.w(TAG, "创建 proximityWakeLock 失败（某些 ROM 会限制三方应用），将只使用 UI 遮罩兜底", it)
        }

        // 3. 注册传感器回调
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.isEmpty()) return
                val distance = values[0]
                // 接近传感器常见两种：
                //   - 二元型（大部分手机）：distance=0 表示贴近，maxRange 表示远离
                //   - 距离型：返回实际厘米数；以 maxRange * 0.5 为阈值
                val threshold = (sensor.maximumRange.coerceAtLeast(5f)) * 0.5f
                val near = distance < threshold
                Log.v(TAG, "Proximity: distance=$distance cm, threshold=$threshold cm, near=$near")
                handleProximityChanged(near)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        proximityListener = listener
        val registered = sm?.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL   // ~200ms，够用且省电
        ) ?: false
        if (!registered) {
            Log.w(TAG, "startProximitySensor: registerListener 返回 false，传感器不可用")
            proximityListener = null
        } else {
            Log.i(TAG, "startProximitySensor: 监听注册成功")
        }
    }

    /**
     * 处理接近/远离事件：
     * - 贴近耳朵（near=true）：
     *     1. 记录用户原先的扬声器开关
     *     2. 强制切听筒（避免贴耳朵时还从外放出声）
     *     3. acquire proximityWakeLock 让系统关背光
     *     4. emit isNearEar=true 让 UI 盖全黑遮罩（兜底 + 动画过渡）
     * - 远离耳朵（near=false）：反过来还原
     */
    @SuppressLint("WakelockTimeout")
    private fun handleProximityChanged(near: Boolean) {
        val prev = _isNearEar.value
        if (prev == near) return
        _isNearEar.value = near
        Log.i(TAG, "handleProximityChanged: $prev → $near")
        runCatching {
            if (near) {
                // 贴近耳朵
                // 仅在用户偏好尚未记录时保存，避免来回抖动覆盖用户真选的状态
                if (userSpeakerPreferenceBeforeProximity == null) {
                    userSpeakerPreferenceBeforeProximity = _isSpeakerOn.value
                }
                // 强制听筒
                if (_isSpeakerOn.value) {
                    Log.i(TAG, "贴近耳朵 → 自动切换到听筒")
                    _isSpeakerOn.value = false
                    applySpeakerRoute(false)
                }
                // 让系统关背光（真正黑屏）
                val wl = proximityWakeLock
                if (wl != null && !wl.isHeld) {
                    wl.acquire()
                    Log.i(TAG, "proximityWakeLock.acquire() → 系统应已黑屏")
                }
            } else {
                // 远离耳朵
                // 还原用户原先的扬声器选择
                val pref = userSpeakerPreferenceBeforeProximity
                if (pref != null && pref != _isSpeakerOn.value) {
                    Log.i(TAG, "远离耳朵 → 恢复扬声器选择=$pref")
                    _isSpeakerOn.value = pref
                    applySpeakerRoute(pref)
                }
                userSpeakerPreferenceBeforeProximity = null
                // 释放 proximityWakeLock → 系统点亮背光
                val wl = proximityWakeLock
                if (wl != null && wl.isHeld) {
                    wl.release()
                    Log.i(TAG, "proximityWakeLock.release() → 系统应已亮屏")
                }
            }
        }.onFailure {
            Log.e(TAG, "handleProximityChanged($near) 抛异常", it)
        }
    }

    /**
     * 停止接近传感器监听 + 释放 proximityWakeLock + 还原扬声器状态。
     * hangup() 必调用；WakeLock 不 release 会导致用户退出通话后屏幕一直黑。
     */
    private fun stopProximitySensor() {
        runCatching {
            // 1. 注销传感器回调（不注销会后台持续跑，耗电）
            val sm = sensorManager
            val listener = proximityListener
            if (sm != null && listener != null) {
                sm.unregisterListener(listener)
                Log.i(TAG, "stopProximitySensor: 传感器监听已注销")
            }
            proximityListener = null
            proximitySensor = null
            sensorManager = null

            // 2. 释放 proximityWakeLock（极其关键！不释放会出现"退出通话后屏幕仍然黑屏"的灵异 Bug）
            val wl = proximityWakeLock
            if (wl != null) {
                if (wl.isHeld) wl.release()
                Log.i(TAG, "stopProximitySensor: proximityWakeLock released")
            }
            proximityWakeLock = null

            // 3. 兜底：如果贴耳朵 → 还没移开就被用户挂断了，需要把扬声器切回用户原来的选择
            val pref = userSpeakerPreferenceBeforeProximity
            if (pref != null && pref != _isSpeakerOn.value) {
                Log.i(TAG, "stopProximitySensor: 兜底还原扬声器=$pref")
                _isSpeakerOn.value = pref
                applySpeakerRoute(pref)
            }
            userSpeakerPreferenceBeforeProximity = null

            // 4. 保证 isNearEar 归零（否则遮罩不消失）
            _isNearEar.value = false
        }.onFailure {
            Log.e(TAG, "stopProximitySensor 抛异常", it)
        }
    }

    // ========================================================================
    //  听话提示语（Listener Cue）：ASR final → 发 LLM 前，先播个"嗯~"之类的
    // ========================================================================

    /**
     * 生成一轮随机的"听话提示语"：
     * 1. 基础词：从「嗯 / 哦 / 啊」随机 1 个
     * 2. 如果当前 TTS provider 是 MiniMax，且模型是 speech-2.8-hd / speech-2.8-turbo，
     *    再从官方语气词标签里随机 1 个拼接在后面（如 "嗯(coughs)"），每轮随机。
     *
     * 这个提示语会直接喂给 TTS.speak（不经过 stripMarkdownForTts），因此语气词标签 (xxx)
     * 一定能原样发到 MiniMax，不会被过滤。
     */
    private fun generateListenerCue(ttsSetting: me.rerere.tts.provider.TTSProviderSetting?): String {
        val base = LISTENER_CUE_BASE.weightedRandom()
        if (ttsSetting is me.rerere.tts.provider.TTSProviderSetting.MiniMax) {
            val model = ttsSetting.model.trim().lowercase()
            val supportTags = model.startsWith("speech-2.8-") // hd / turbo 都符合
            if (supportTags) {
                val tag = MINIMAX_TTS_SPONTANEOUS_TAGS.random()
                // 注意：这里不需要也不应该再经过 stripMarkdownForTts，直接给 TTS。
                // 后续 AI 正文如果自己带了同格式标签，stripMarkdownForTts 已经做了保护不会被清。
                return buildString {
                    append(base)
                    append(tag)
                }
            }
        }
        return base
    }

    /**
     * 带权重的随机选择：从 List<Pair<String, Int>> 中按权重随机选一个 String。
     */
    private fun List<Pair<String, Int>>.weightedRandom(): String {
        val total = this.sumOf { it.second }
        if (total <= 0) return this.first().first
        var r = (1..total).random()
        for ((text, weight) in this) {
            r -= weight
            if (r <= 0) return text
        }
        return this.last().first
    }

    /**
     * 立刻播放「听话提示语」(flush=true，会把之前还没播完的 TTS 之类顶掉)，
     * 用于用户说完后 1s 内 LLM 还没返回可朗读内容时，给用户即时反馈"我听到了"。
     *
     * @return true = cue 正常播完（或超时被保护中断），false = 播放失败或不在活动状态
     */
    private suspend fun playListenerCueNow(ttsSetting: me.rerere.tts.provider.TTSProviderSetting?): Boolean {
        if (!_isActive.value) return false
        val cue = generateListenerCue(ttsSetting)
        Log.i(TAG, "playListenerCueNow: \"$cue\" start (will await completion)")
        val cueStartMs = System.currentTimeMillis()
        val ok = runCatching {
            // cue 是我们自己构造的短文本，不走 stripMarkdownForTts，确保 (xxx) 标签完整透传。
            ttsController.speak(cue, flush = true)
            lastTtsPlayingAtMs = System.currentTimeMillis()
            var waited = 0L
            // 等 TTS 真正开始 speaking 或超时（合成可能需要 100-200ms）
            var startGuard = 0
            while (_isActive.value && !ttsController.isSpeaking.value && startGuard < 20) {
                delay(50); waited += 50; startGuard++
                lastTtsPlayingAtMs = System.currentTimeMillis()
            }
            // 等说话结束或总时长超过 2.5s（极端保护）
            while (_isActive.value && ttsController.isSpeaking.value && waited < 2500L) {
                delay(50); waited += 50
                lastTtsPlayingAtMs = System.currentTimeMillis()
            }
            true
        }.getOrElse {
            Log.w(TAG, "listener cue play failed", it)
            false
        }
        val dur = System.currentTimeMillis() - cueStartMs
        Log.i(TAG, "playListenerCueNow: \"$cue\" finished (${dur}ms, ok=$ok)")
        return ok
    }

    companion object {
        // PCM 预卷帧数（每帧 32ms，31 帧/1s）
        private const val PRE_ROLL_MAX_FRAMES = 31

        private const val L1_CHECK_INTERVAL_MS = 25L * 60 * 1000
        private const val ASR_RETRY_DELAY_MS = 600L
        // 延迟 listenerCue：发消息后等 1s，LLM 仍无可朗读内容就播"嗯/啊/哦"
        private const val LISTENER_CUE_DELAY_MS = 2000L
        // 打断两阶段：每帧 32ms（不再区分严格/非严格的连续帧，完全取消倍率）
        //  8 帧 ≈ 256ms → DUCK
        // 16 帧 ≈ 512ms → INTERRUPT CONFIRM
        private const val BARGE_IN_DUCK_FRAMES = 6
        private const val BARGE_IN_CONFIRM_FRAMES = 14
        private const val DUCK_TARGET_VOLUME = 0.2f
        private const val DUCK_DURATION_MS = 240L
        private const val BASELINE_LEARN_FRAMES = 20
        private const val SLIDING_WINDOW_FRAMES = 30
        // RMS 超过 baseline 多少倍视为抢话。1.8→1.4，降低门槛让正常音量就能打断
        private const val BARGE_IN_RMS_MULTIPLIER = 1.4
        // 回声尾窗：TTS 停后多少 ms 内仍算"有回声"。
        // 这段时间**不录音**（而非录了再挡），比用阈值过滤更彻底，且不会漏用户字——
        // 100ms 内用户刚听完 AI 回复，极少能立刻完整地说一句话；delay 完再启预卷正好接上。
        private const val ECHO_TAIL_WINDOW_MS = 0L//尝试去掉回声尾窗
        // 尾窗门限倍数：轻微抬高即可
        private const val ECHO_TAIL_BOOST = 1.1
        private const val TTS_SILENCE_TIMEOUT_MS = 1500L
        // ASR 忽略期：从 TTS 播完后开始计时，800ms 覆盖大音量扬声器的尾音回声
        // （500ms 不够：大音量扬声器物理振铃需要 300-500ms 衰减）
        private const val ASR_IGNORE_PERIOD_MS = 800L
        // 预卷能量过滤阈值：与 ASR 的 onset 阈值对齐（0.005）
        private const val RMS_SPEECH_THRESHOLD_PRE_ROLL = 0.005f

        // ===== 用户 30 分钟沉默自动挂断 =====
        // 超时时间：30 分钟
        private const val USER_SILENCE_TIMEOUT_MS = 30L * 60L * 1000L
        // 检查间隔：1 分钟（避免太频繁）
        private const val USER_SILENCE_CHECK_INTERVAL_MS = 60L * 1000L

        // ===== AI 主动挂断的 30s 延迟 =====
        // 总宽限时间（从 AI 发出 hangup 命令算起到真正挂断）
        private const val AI_HANGUP_DELAY_MS = 30L * 1000L
        // 等待 AI 流式生成 + TTS 播放完的最长时间（避免卡住永远不挂）
        private const val AI_HANGUP_TTS_MAX_WAIT_MS = AI_HANGUP_DELAY_MS

        // 用户说完后、AI 开始回复前，随机播放一个"听话提示"，让对话不像机器人
        private val LISTENER_CUE_BASE = listOf(
            "嗯...哈哈哈" to 5,
            "嗯？" to 5,
            "嗯..." to 25,
            "啊，" to 25,
            "哦，嗯..." to 20,
        )
        //   Minimax speech-2.8 系列支持的官方语气词标签列表
        private val MINIMAX_TTS_SPONTANEOUS_TAGS = listOf(
            "(laughs)", "(chuckle)", "(clear-throat)",
            "(breath)", "(pant)", "(inhale)", "(exhale)", "(gasps)",
            "(sniffs)", "(sighs)", "(snorts)",
            "(humming)", "(hissing)", "(emm)", "(sneezes)"
        )
    }
}
