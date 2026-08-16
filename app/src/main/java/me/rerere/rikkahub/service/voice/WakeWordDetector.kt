package me.rerere.rikkahub.service.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * 唤醒词检测器（基于 sherpa-onnx KeywordSpotter）。
 *
 * 从文件系统加载 KWS 模型（不使用 AssetManager），
 * 支持用户自定义唤醒词。音频格式：16kHz 单声道 16-bit PCM。
 *
 * 使用流程：
 * 1. [isModelReady] 检查模型是否存在
 * 2. [start] 初始化 KeywordSpotter 并创建检测流
 * 3. 循环调用 [acceptWaveform] 送入音频 → [detect] 查询结果
 * 4. [reset] 重置流状态（检测到唤醒词后调用）
 * 5. [release] 释放资源
 */
class WakeWordDetector(
    private val modelManager: WakeWordModelManager,
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val NUM_THREADS = 1
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    /** 检查模型文件是否就绪 */
    fun isModelReady(): Boolean = modelManager.isModelReady()

    /**
     * 初始化检测器并创建检测流。
     *
     * @param keywords 唤醒词文本，格式：每行一个 "拼音 tokens @ 显示文本"
     *                 例如 "n i h a o a i f u @ 你好艾芙"
     * @param sensitivity 灵敏度 0~1，映射到 keywordsThreshold（1.0→0.1 宽松，0.0→1.0 严格）
     */
    fun start(keywords: String, sensitivity: Float) {
        if (!isModelReady()) {
            throw IllegalStateException("KWS 模型未就绪，请先下载模型")
        }

        release()

        // 灵敏度映射：sensitivity 越高 → threshold 越低 → 越容易唤醒
        // sensitivity=0.5 → threshold=0.5（默认）
        // sensitivity=1.0 → threshold=0.1（最宽松）
        // sensitivity=0.0 → threshold=1.0（最严格）
        val threshold = 1.0f - sensitivity.coerceIn(0f, 1f)
        val keywordsScore = 1.0f + sensitivity.coerceIn(0f, 1f)

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = modelManager.encoderFile.absolutePath,
                    decoder = modelManager.decoderFile.absolutePath,
                    joiner = modelManager.joinerFile.absolutePath
                ),
                tokens = modelManager.tokensFile.absolutePath,
                numThreads = NUM_THREADS,
                provider = "cpu"
            ),
            keywordsThreshold = threshold,
            keywordsScore = keywordsScore,
            numTrailingBlanks = 3
        )

        // assetManager = null → 使用 newFromFile 从文件系统加载
        spotter = KeywordSpotter(assetManager = null, config = config)
        stream = spotter?.createStream(keywords)

        Log.i(TAG, "KWS 启动: threshold=$threshold, score=$keywordsScore, keywords=$keywords")
    }

    /**
     * 送入一帧音频数据。
     * @param samples FloatArray，取值范围 [-1.0, 1.0]
     */
    fun acceptWaveform(samples: FloatArray) {
        val s = stream ?: return
        s.acceptWaveform(samples, SAMPLE_RATE)
    }

    /**
     * 检测是否命中唤醒词。
     * @return 命中时返回唤醒词文本，未命中返回 null
     */
    fun detect(): String? {
        val sp = spotter ?: return null
        val s = stream ?: return null

        if (!sp.isReady(s)) return null
        sp.decode(s)

        val result = sp.getResult(s)
        val keyword = result.keyword
        return keyword?.takeIf { it.isNotBlank() }
    }

    /** 重置检测流状态（检测到唤醒词后调用，避免连续触发） */
    fun reset() {
        val sp = spotter ?: return
        val s = stream ?: return
        sp.reset(s)
    }

    /** 释放所有 native 资源 */
    fun release() {
        stream?.release()
        stream = null
        spotter?.release()
        spotter = null
    }
}
