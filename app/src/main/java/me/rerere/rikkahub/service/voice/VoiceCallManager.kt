package me.rerere.rikkahub.service.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
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
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.chat.CallStatus
import me.rerere.tts.controller.TtsController
import me.rerere.tts.model.PlaybackStatus
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallManager"

/**
 * 语音通话管理器。
 *
 * 状态机: CONNECTING → LISTENING → THINKING → SPEAKING →(打断/完成)→ LISTENING → ... → IDLE(hangup)
 *
 * 录音通道分配（避免麦克风冲突）:
 * - LISTENING: 由 SystemASR 的 SpeechRecognizer 自行录音识别
 * - SPEAKING:  由 [VadDetector] + AudioRecord 持续监听, 检测用户开口即打断
 *
 * 低延迟 TTS: 观察 AI 消息增量, 按句子边界逐句喂给 [TtsController]（首句生成完即播, 不等整段）。
 */
class VoiceCallManager(
    private val context: Context,
    private val chatService: ChatService,
    private val ttsController: TtsController,
    private val asrManager: ASRManager,
    private val settingsStore: SettingsStore
) {
    private val _callStatus = MutableStateFlow(CallStatus.CONNECTING)
    val callStatus: StateFlow<CallStatus> = _callStatus.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // 通话错误事件（一次性）: 权限缺失/ASR 不可用等, 供 UI 层 toast 提示
    private val _callError = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val callError: SharedFlow<String> = _callError.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var conversationId: Uuid? = null
    private var vadDetector: VadDetector? = null
    private var audioRecord: AudioRecord? = null

    @Volatile private var isVadRunning = false
    private var asrJob: Job? = null
    private var responseJob: Job? = null
    private var interruptionJob: Job? = null
    // 通话期间的 L1 定时归档：每 25 分钟检查一次未归档消息数是否达阈值
    private var l1TimerJob: Job? = null

    // 流式 TTS: 跟踪已喂给 TTS 的 AI 文本长度
    @Volatile private var lastFedTextLen = 0
    @Volatile private var priorAssistantNodeId: Uuid? = null
    @Volatile private var speakingStarted = false

    // ASR 权限误报重试标志: 部分 ROM 的 SpeechRecognizer 首次启动会误报 error(9),
    // 即使 RECORD_AUDIO 已授权. 允许重试一次避免误杀.
    @Volatile private var asrPermissionRetryUsed = false

    fun startCall(conversationId: Uuid) {
        if (_isActive.value) return
        this.conversationId = conversationId
        _isActive.value = true
        _callStatus.value = CallStatus.CONNECTING
        resetStreamingState()

        // 标记会话进入通话模式：跳过主路径每轮 AI 响应的 L1 自动摘要, 改由下方定时器驱动
        chatService.setCallMode(conversationId, active = true)
        startL1Timer(conversationId)

        // 权限预热: 某些 ROM 上 SpeechRecognizer 即使 checkSelfPermission=GRANTED,
        // 仍因 AppOps 未记录 RECORD_AUDIO op 而报 error(9). 短暂打开一次 AudioRecord
        // 可让系统记录该 op, 避免后续 ASR 启动时权限检查失败.
        warmUpRecordAudioPermission()

        // 初始化 VAD（用于 SPEAKING 打断）
        runCatching {
            vadDetector = VadDetector(context).also { Log.i(TAG, "VAD initialized") }
        }.onFailure { Log.e(TAG, "VAD init failed", it) }

        // 配置 TTS provider
        val settings = settingsStore.settingsFlow.value
        ttsController.setProvider(settings.getSelectedTTSProvider())

        Log.i(TAG, "startCall: conversationId=$conversationId")
        startListening()
    }

    /**
     * 录音权限预热: 短暂打开 AudioRecord 触发 AppOps 记录 RECORD_AUDIO op, 然后立即释放.
     * 解决 SpeechRecognizer error(9) insufficient permissions（checkSelfPermission 已 GRANTED
     * 但 AppOps 侧无记录导致系统 ASR 服务拒绝录音的场景）.
     */
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

    /**
     * 通话期间 L1 定时归档：每 25 分钟触发一次, 由 ChatService.summarizeForCallIfNeeded 内部判断阈值。
     * - 避免实时通话每轮 AI 响应都触发摘要调用占用并发槽位、增加端到端延迟；
     * - 同时保证长通话不会让未归档消息无限堆积, 影响后续上下文质量。
     */
    private fun startL1Timer(convId: Uuid) {
        l1TimerJob?.cancel()
        l1TimerJob = scope.launch {
            while (_isActive.value) {
                kotlinx.coroutines.delay(L1_CHECK_INTERVAL_MS)
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

    /**
     * 开始监听用户语音（LISTENING）。
     * 使用 SystemASR 的 SpeechRecognizer 实时录音识别。
     */
    private fun startListening() {
        val convId = conversationId ?: return
        if (!_isActive.value) return
        if (_isMuted.value) {
            // 静音状态下不启动识别, 等待取消静音
            _callStatus.value = CallStatus.LISTENING
            return
        }
        _callStatus.value = CallStatus.LISTENING
        val settings = settingsStore.settingsFlow.value
        val asrSetting = settings.getSelectedASRProvider() ?: run {
            Log.e(TAG, "No ASR provider configured")
            return
        }

        asrJob?.cancel()
        asrJob = scope.launch {
            try {
                asrManager.startRecognition(asrSetting, context).collect { result ->
                    if (result.isFinal && result.text.isNotBlank()) {
                        Log.i(TAG, "ASR final: ${result.text}")
                        _callStatus.value = CallStatus.THINKING
                        sendUserMessage(result.text, convId)
                        return@collect
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                // SystemASR 通过 close(exception) 传递错误, 这里兜底避免协程异常导致 App 崩溃.
                Log.e(TAG, "ASR stream error", e)
                val msg = e.message.orEmpty()
                when {
                    msg.contains("insufficient permissions") -> {
                        // 部分 ROM 的 SpeechRecognizer 首次启动误报 error(9), 即使权限已授予.
                        // 用 checkSelfPermission 二次确认: 若确已授权且未重试过, 允许重试一次.
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted && !asrPermissionRetryUsed) {
                            asrPermissionRetryUsed = true
                            Log.w(TAG, "ASR error(9) but RECORD_AUDIO granted, retrying once (ROM bug)")
                            scope.launch { _callError.emit("系统语音识别初始化中, 正在重试...") }
                            // 延迟后重试, 避免立即重试时 SpeechRecognizer 内部状态未恢复仍失败
                            scope.launch {
                                kotlinx.coroutines.delay(ASR_RETRY_DELAY_MS)
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
                    msg.contains("ASR API") -> {
                        // 在线 ASR API 调用错误（401/403/500 等），重试无意义
                        scope.launch { _callError.emit("在线 ASR API 错误: $msg") }
                        hangup()
                        return@launch
                    }
                    else -> {
                        // 其他错误（网络/服务端等）: 短暂提示后回到监听重试
                        scope.launch { _callError.emit("语音识别异常: $msg, 正在重试") }
                    }
                }
            }
            // 流正常结束（沉默或错误）且仍活跃, 继续监听
            if (_isActive.value && _callStatus.value == CallStatus.LISTENING) {
                startListening()
            }
        }
    }

    private fun sendUserMessage(text: String, convId: Uuid) {
        Log.i(TAG, "sendUserMessage: \"$text\" -> convId=$convId")
        // 记录发送前的最后一个 ASSISTANT 节点 ID
        // 用于在 observeAiResponseStreaming 中只观察新创建的 ASSISTANT 节点
        // 否则 lastOrNull { ASSISTANT } 会取到上一轮的旧节点，导致把上一轮的回答重新念一遍
        val priorConv = chatService.getConversationFlow(convId).value
        priorAssistantNodeId = priorConv.messageNodes.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
        Log.d(TAG, "priorAssistantNodeId=$priorAssistantNodeId")

        // 重置流式 TTS 状态：新一轮 AI 响应从头开始喂
        // 否则 lastFedTextLen 保留上一轮的值，新节点文本长度增长到超过旧值时
        // substring(lastFedTextLen) 会跳过新回答的前半段
        lastFedTextLen = 0
        speakingStarted = false

        chatService.sendMessage(
            conversationId = convId,
            content = listOf(UIMessagePart.Text(text)),
            skipContextForResponse = false
        )
        observeAiResponseStreaming(convId)
    }

    /**
     * 观察 AI 流式响应, 按句子边界逐句喂 TTS（低延迟首句即播）。
     */
    private fun observeAiResponseStreaming(convId: Uuid) {
        responseJob?.cancel()
        responseJob = scope.launch {
            // 流式观察 AI 文本增量
            val streamJob = launch {
                chatService.getConversationFlow(convId).collect { conv ->
                    if (!_isActive.value) return@collect
                    // 只观察 priorAssistantNodeId 之后的新 ASSISTANT 节点
                    // 不能用 id != priorAssistantNodeId，否则会取到更早的旧 ASSISTANT 节点
                    val aiNode = findNewAssistantNode(conv.messageNodes) ?: return@collect
                    val aiText = aiNode.currentMessage.parts
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }

                    if (aiText.length > lastFedTextLen) {
                        if (!speakingStarted && aiText.isNotBlank()) {
                            speakingStarted = true
                            _callStatus.value = CallStatus.SPEAKING
                            startInterruptionDetection(convId)
                        }
                        feedNewSentences(aiText)
                    }
                }
            }
            // 等待生成完成
            try {
                chatService.generationDoneFlow.first { it == convId }
            } catch (e: CancellationException) {
                streamJob.cancel()
                throw e
            }
            streamJob.cancel()

            if (!_isActive.value) return@launch
            // flush 生成完成后剩余未播文本
            val conv = chatService.getConversationFlow(convId).value
            val aiNode = findNewAssistantNode(conv.messageNodes)
            val aiText = aiNode?.currentMessage?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("") { it.text } ?: ""
            // 防御性检查：lastFedTextLen 可能被并发的旧 streamJob 修改
            if (aiText.length > lastFedTextLen.coerceAtLeast(0)) {
                val remaining = aiText.substring(lastFedTextLen.coerceAtLeast(0).coerceAtMost(aiText.length))
                if (remaining.isNotBlank()) {
                    ttsController.speak(remaining, flush = false)
                    lastFedTextLen = aiText.length
                }
            }
            // 无任何文本输出, 回到监听
            if (!speakingStarted) {
                if (_isActive.value) startListening()
                return@launch
            }
            // 等待 TTS 播放完成
            awaitTtsCompleteThenListen()
        }
    }

    /**
     * 在 messageNodes 中找到 priorAssistantNodeId 之后的新 ASSISTANT 节点。
     * 如果 priorAssistantNodeId 为 null 或不在列表中，返回最后一个 ASSISTANT 节点。
     */
    private fun findNewAssistantNode(messageNodes: List<MessageNode>): MessageNode? {
        val priorIndex = if (priorAssistantNodeId != null) {
            messageNodes.indexOfFirst { it.id == priorAssistantNodeId }
        } else -1
        return if (priorIndex >= 0) {
            messageNodes.drop(priorIndex + 1).lastOrNull { it.role == MessageRole.ASSISTANT }
        } else {
            messageNodes.lastOrNull { it.role == MessageRole.ASSISTANT }
        }
    }

    /** 提取新增文本中的完整句子喂给 TTS, 保留不完整尾部。 */
    private fun feedNewSentences(fullText: String) {
        // 局部快照：防止并发场景下 lastFedTextLen 被另一个 streamJob 修改
        val fedLen = lastFedTextLen
        if (fedLen >= fullText.length) return
        val newPart = fullText.substring(fedLen)
        // 按句末标点切分（中英文）。保留标点。
        val sentenceEnd = Regex("[。！？!?；;\n]")
        var lastCut = 0
        var match: MatchResult? = sentenceEnd.find(newPart)
        while (match != null) {
            val end = match.range.last + 1
            val sentence = newPart.substring(lastCut, end).trim()
            if (sentence.isNotEmpty()) {
                ttsController.speak(sentence, flush = false)
            }
            lastCut = end
            match = sentenceEnd.find(newPart, end)
        }
        // 已喂到 lastCut, 更新已喂长度（基于快照值，避免并发覆盖）
        lastFedTextLen = fedLen + lastCut
    }

    private suspend fun awaitTtsCompleteThenListen() {
        // 等待 TTS 真正播放完成
        // isSpeaking=false 仅在 TTS worker 退出（queue 为空）时出现
        // 但流式分句喂入时，worker 可能在句间短暂退出（下一句尚未添加到 queue），
        // 导致 isSpeaking 短暂为 false。使用宽限期过滤这种间隙，避免误判为播放结束。
        // 注意：去掉了 sawSpeaking 收集器——若 TTS 在调用前已完成，收集器永远收不到
        // true，会导致死循环。直接轮询当前值即可覆盖所有情况。
        var silentSince = 0L
        while (_isActive.value && _callStatus.value == CallStatus.SPEAKING) {
            if (!ttsController.isSpeaking.value) {
                if (silentSince == 0L) silentSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - silentSince >= TTS_SILENCE_TIMEOUT_MS) {
                    Log.d(TAG, "TTS complete (isSpeaking=false for ${TTS_SILENCE_TIMEOUT_MS}ms)")
                    break
                }
            } else {
                silentSince = 0L
            }
            kotlinx.coroutines.delay(100)
        }
        stopInterruptionDetection()
        resetStreamingState()
        if (_isActive.value) startListening()
    }

    /**
     * 启动打断检测（SPEAKING 期间）。
     *
     * 抗扬声器回声策略：
     * 1. AudioSource.VOICE_COMMUNICATION + 显式 AcousticEchoCanceler + NoiseSuppressor
     * 2. VAD threshold 0.7 + minSpeechDuration 0.6s
     * 3. 滑动平均能量门限：用最近 SLIDING_WINDOW_FRAMES 帧的 RMS 平均值作为动态基准
     *    —— 基准自动跟随 TTS 音量变化，音量大时门槛自动提高
     *    —— 用户说话时冻结基准更新，避免被拉高
     * 4. 连续 BARGE_IN_CONFIRM_FRAMES 帧都满足条件才确认打断
     */
    private fun startInterruptionDetection(convId: Uuid) {
        if (vadDetector == null) return
        val vad = vadDetector ?: return
        vad.reset()

        val minBuf = AudioRecord.getMinBufferSize(
            VadDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord min buffer error: $minBuf")
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            VadDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            audioRecord?.release()
            audioRecord = null
            return
        }

        // 启用系统级 AEC + NS
        val sessionId = audioRecord?.audioSessionId ?: 0
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aecEffect = AcousticEchoCanceler.create(sessionId)
                aecEffect?.let {
                    it.enabled = true
                    Log.i(TAG, "AEC enabled (session=$sessionId)")
                }
            } else {
                Log.w(TAG, "AEC not available on this device")
            }
            if (NoiseSuppressor.isAvailable()) {
                nsEffect = NoiseSuppressor.create(sessionId)
                nsEffect?.let {
                    it.enabled = true
                    Log.i(TAG, "NoiseSuppressor enabled (session=$sessionId)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio effect init failed: ${e.message}")
        }

        isVadRunning = true
        audioRecord?.startRecording()
        Log.i(TAG, "Interruption detection started")

        interruptionJob?.cancel()
        interruptionJob = scope.launch(Dispatchers.IO) {
            // 关键：等待 TTS 实际开始播放后再采样基准
            // 否则基准是安静噪声（0.001），TTS 播放后回声 RMS 远超基准被误判为用户说话
            var waitMs = 0
            while (isVadRunning && isActive && _callStatus.value == CallStatus.SPEAKING) {
                if (ttsController.playbackState.value.status == PlaybackStatus.Playing) break
                delay(50)
                waitMs += 50
                if (waitMs >= 5000) {
                    Log.w(TAG, "TTS did not start playing within 5s, skipping interruption detection")
                    return@launch
                }
            }
            if (!isVadRunning || !isActive || _callStatus.value != CallStatus.SPEAKING) return@launch

            // 额外等 200ms 让回声路径稳定
            delay(200)
            Log.i(TAG, "TTS is playing, starting baseline sampling after ${waitMs}ms wait")

            val buffer = ShortArray(VadDetector.WINDOW_SIZE)
            var consecutiveSpeechFrames = 0

            // 滑动窗口：记录最近 N 帧的 RMS，用于计算动态基准
            val slidingWindow = ArrayDeque<Double>()
            var slidingAvg = 0.0
            var baselineReady = false

            while (isVadRunning && isActive && _callStatus.value == CallStatus.SPEAKING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read <= 0) {
                    consecutiveSpeechFrames = 0
                    continue
                }
                if (_isMuted.value) {
                    consecutiveSpeechFrames = 0
                    continue
                }
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                val rms = calculateRms(samples)

                // 阶段 1: 预热滑动窗口（TTS 播放后采样回声基准）
                if (!baselineReady) {
                    slidingWindow.addLast(rms)
                    if (slidingWindow.size >= BASELINE_LEARN_FRAMES) {
                        slidingAvg = slidingWindow.average()
                        baselineReady = true
                        Log.i(TAG, "Sliding baseline ready: avg=${"%.4f".format(slidingAvg)}, " +
                            "threshold=${"%.4f".format(slidingAvg * BARGE_IN_RMS_MULTIPLIER)}")
                    }
                    continue
                }

                // 阶段 2: 检测用户打断（VAD + 滑动平均能量门限）
                vad.acceptWaveform(samples)
                val rmsThreshold = slidingAvg * BARGE_IN_RMS_MULTIPLIER
                val vadDetected = vad.isSpeechDetected()
                val loudEnough = rms > rmsThreshold

                if (vadDetected && loudEnough) {
                    consecutiveSpeechFrames++
                    // 检测到用户说话时，冻结滑动窗口更新（避免基准被拉高）
                    if (consecutiveSpeechFrames >= BARGE_IN_CONFIRM_FRAMES) {
                        Log.i(TAG, "Barge-in confirmed: frames=$consecutiveSpeechFrames, " +
                            "rms=${"%.4f".format(rms)}, avg=${"%.4f".format(slidingAvg)}, " +
                            "threshold=${"%.4f".format(rmsThreshold)}")
                        interrupt(convId)
                        break
                    }
                } else {
                    // 未检测到打断：更新滑动窗口（追踪回声变化）
                    slidingWindow.addLast(rms)
                    if (slidingWindow.size > SLIDING_WINDOW_FRAMES) slidingWindow.removeFirst()
                    slidingAvg = slidingWindow.average()

                    if (consecutiveSpeechFrames > 0) {
                        Log.d(TAG, "Counter reset: $consecutiveSpeechFrames -> 0 " +
                            "(vad=$vadDetected, rms=${"%.4f".format(rms)}, " +
                            "avg=${"%.4f".format(slidingAvg)}, threshold=${"%.4f".format(rmsThreshold)})")
                    }
                    consecutiveSpeechFrames = 0
                }
            }
        }
    }

    /** 计算 PCM 样本的 RMS（音量能量）。 */
    private fun calculateRms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            sum += s * s
        }
        return kotlin.math.sqrt(sum / samples.size)
    }

    private var aecEffect: AcousticEchoCanceler? = null
    private var nsEffect: NoiseSuppressor? = null

    private fun stopInterruptionDetection() {
        isVadRunning = false
        interruptionJob?.cancel()
        interruptionJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        // 释放音频效果
        try { aecEffect?.let { it.enabled = false; it.release() } } catch (_: Exception) {}
        aecEffect = null
        try { nsEffect?.let { it.enabled = false; it.release() } } catch (_: Exception) {}
        nsEffect = null
        Log.d(TAG, "Interruption detection stopped")
    }

    /** 用户打断: 立即停 TTS + 取消生成 + 回到监听 */
    private fun interrupt(convId: Uuid) {
        ttsController.stop()
        chatService.stopGeneration(convId)
        stopInterruptionDetection()
        responseJob?.cancel()
        resetStreamingState()
        if (_isActive.value) startListening()
    }

    private fun resetStreamingState() {
        lastFedTextLen = 0
        speakingStarted = false
        priorAssistantNodeId = null
    }

    fun toggleMute() {
        _isMuted.update { !it }
        val muted = _isMuted.value
        Log.i(TAG, "Mute toggled: $muted")
        if (muted) {
            // 静音: 取消当前识别, 等待取消静音
            asrJob?.cancel()
        } else {
            // 取消静音: 重新监听（若当前在 LISTENING）
            if (_isActive.value && _callStatus.value == CallStatus.LISTENING) {
                startListening()
            }
        }
    }

    fun toggleSpeaker() {
        _isSpeakerOn.update { !it }
        // TODO: 实际切换音频输出路由到扬声器/听筒（需 AudioPlayer 暴露路由控制）
    }

    fun hangup() {
        Log.i(TAG, "hangup")
        _isActive.value = false
        _callStatus.value = CallStatus.CONNECTING
        stopInterruptionDetection()
        stopL1Timer()
        asrJob?.cancel()
        responseJob?.cancel()
        ttsController.stop()
        conversationId?.let {
            chatService.stopGeneration(it)
            // 解除通话模式: 后续该会话的 AI 响应恢复主路径的 L1 自动摘要
            chatService.setCallMode(it, active = false)
        }
        vadDetector?.release()
        vadDetector = null
        conversationId = null
        resetStreamingState()
        _isMuted.value = false
        asrPermissionRetryUsed = false
    }

    companion object {
        // L1 归档定时检查间隔：25 分钟
        private const val L1_CHECK_INTERVAL_MS = 25L * 60 * 1000
        // ASR 权限误报重试延迟
        private const val ASR_RETRY_DELAY_MS = 600L
        // 连续确认帧数（每帧 32ms，20 帧 ≈ 640ms，要求持续说话才打断）
        private const val BARGE_IN_CONFIRM_FRAMES = 20
        // 预热帧数（前 20 帧 ≈ 640ms 建立初始基准）
        private const val BASELINE_LEARN_FRAMES = 20
        // 滑动窗口大小（30 帧 ≈ 960ms，追踪最近 1 秒的回声变化）
        private const val SLIDING_WINDOW_FRAMES = 30
        // 能量门限倍数：用户说话 RMS 需超过滑动平均 × 此倍数才算打断
        // 2.0 倍：扬声器回声波动一般不超过平均的 1.5 倍，用户说话增量超过 2 倍
        private const val BARGE_IN_RMS_MULTIPLIER = 2.5
        // TTS 播放完成判定：isSpeaking=false 持续超过此时长才认为播放结束
        // 流式分句播放时，worker 在句间可能短暂退出（等待下一句添加到 queue），
        // 1500ms 宽限期可过滤这种间隙，避免 ASR 在 TTS 仍在播放时提前启动
        private const val TTS_SILENCE_TIMEOUT_MS = 1500L
    }
}
