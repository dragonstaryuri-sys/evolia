package me.rerere.rikkahub.service.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig

/**
 * 唤醒词检测器（基于 sherpa-onnx KeywordSpotter 1.13.4）。
 *
 * 参考官方 Android demo：
 *   - KeywordSpotter(assetManager=null | context.assets, config)
 *   - keywords = rawText.replace('\n', '/').trim()    // 多行用 / 分隔，不是换行
 *   - stream = spotter.createStream(keywords)
 *   - if (stream.ptr == 0L) throw  // 格式不合法时返回 ptr=0，一定要先判断
 *   - stream.acceptWaveform(samples, SAMPLE_RATE)    // 两参数重载
 *   - while (spotter.isReady(stream)) spotter.decode(stream)
 *   - val result = spotter.getResult(stream).keyword  // 非空即命中
 *
 * 调用顺序：init → start(keywords, sensitivity) → acceptWaveform 循环喂入 → detect() → stop → release
 */
class WakeWordDetector(
    private val modelManager: WakeWordModelManager,
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val NUM_THREADS = 1
        const val READ_BUFFER_SIZE = 1600 // 100ms 一帧，贴近官方 demo 节奏
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    /** 是否已经通过 start 初始化并可喂入音频 */
    fun isActive(): Boolean = spotter != null && stream != null && (stream?.ptr ?: 0L) != 0L

    /**
     * 初始化 KWS 引擎并加载唤醒词。
     *
     * @param keywords 唤醒词文本，格式：每行一个，"拼音 tokens @ 显示文本"；
     *                 内部会把换行符统一替换为 "/" 并 trim 后传入 createStream。
     * @param sensitivity 灵敏度 ∈ [0,1]；越接近 1 越灵敏（阈值越低）
     */
    fun start(keywords: String, sensitivity: Float): Boolean {
        stop()
        if (!modelManager.isModelReady()) {
            Log.e(TAG, "start() 失败：模型未就绪，encoder=${modelManager.encoderFile}")
            return false
        }

        // ======== 参数映射（与官方 demo 保持一致的调参方向）========
        val s = sensitivity.coerceIn(0f, 1f)
        // keywordsThreshold ∈ [0,1]：越大越不容易触发；s=高灵敏度→阈值低
        val keywordsThreshold = 1.0f - s
        // keywordsScore：加到每个 token 上的 bonus，越高越灵敏
        val keywordsScore = 1.0f + s * 1.5f

        val encoder = modelManager.encoderFile.absolutePath
        val decoder = modelManager.decoderFile.absolutePath
        val joiner = modelManager.joinerFile.absolutePath
        val tokens = modelManager.tokensFile.absolutePath

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(SAMPLE_RATE, FEATURE_DIM, 0f),
            modelConfig = OnlineModelConfig(
                OnlineTransducerModelConfig(encoder, decoder, joiner),
                OnlineParaformerModelConfig(),
                OnlineZipformer2CtcModelConfig(),
                OnlineNeMoCtcModelConfig(),
                OnlineToneCtcModelConfig(),
                tokens,
                NUM_THREADS,
                false,
                "cpu",
                "",     // modelType
                "",     // modelingUnit
                "",     // bpeVocab
            ),
            4,              // maxActivePaths
            "",             // keywordsFile
            keywordsScore,
            keywordsThreshold,
            3               // numTrailingBlanks
        )

        val spt = try {
            KeywordSpotter(null, config)
        } catch (t: Throwable) {
            Log.e(TAG, "创建 KeywordSpotter 异常: ${t.message}", t)
            return false
        }
        spotter = spt

        // ======== keywords 规范化：换行 → "/" + trim ========
        val normalized = keywords
            .replace("\r\n", "/")
            .replace("\n", "/")
            .replace("\r", "/")
            .trim()
        Log.i(TAG, "创建 KWS Stream，keywords(raw)=[$keywords]，normalized=[$normalized]，threshold=$keywordsThreshold，score=$keywordsScore")

        val st = try {
            spt.createStream(normalized)
        } catch (t: Throwable) {
            Log.e(TAG, "createStream 抛异常: ${t.message}", t)
            try { spt.release() } catch (_: Throwable) {}
            spotter = null
            return false
        }

        if (st == null || st.ptr == 0L) {
            Log.e(TAG, "createStream 返回 null 或 ptr=0！唤醒词格式或 tokens.txt 不匹配，keywords=[$normalized]")
            try { st?.release() } catch (_: Throwable) {}
            try { spt.release() } catch (_: Throwable) {}
            spotter = null
            return false
        }
        stream = st
        Log.i(TAG, "KWS 初始化成功，stream.ptr=${st.ptr}")
        return true
    }

    /** 喂入一帧 16kHz 归一化 float PCM */
    fun acceptWaveform(samples: FloatArray) {
        val s = stream ?: return
        try {
            s.acceptWaveform(samples, SAMPLE_RATE)
        } catch (t: Throwable) {
            Log.w(TAG, "acceptWaveform 异常: ${t.message}")
        }
    }

    /**
     * 检测是否命中唤醒词。
     * @return 用户可读的唤醒词文本（@后面的部分，如 "你好艾芙"），未命中返回 null
     */
    fun detect(): String? {
        val sp = spotter ?: return null
        val s = stream ?: return null
        return try {
            var decoded = 0
            // 官方 demo：while isReady → decode（可能多轮）
            while (sp.isReady(s)) {
                sp.decode(s)
                decoded++
                if (decoded > 1024) {
                    Log.w(TAG, "decode 循环超过上限，强行跳出")
                    break
                }
            }
            val keyword = sp.getResult(s).keyword
            if (keyword.isNullOrBlank()) null else keyword.trim()
        } catch (t: Throwable) {
            Log.w(TAG, "detect 异常: ${t.message}")
            null
        }
    }

    /** 停止本轮检测（释放 stream，保留 spotter 以便下次 start 复用） */
    fun stop() {
        try {
            stream?.release()
        } catch (_: Throwable) {}
        stream = null
    }

    /** 完全释放（spotter + stream 全部释放） */
    fun release() {
        try {
            stream?.release()
        } catch (_: Throwable) {}
        try {
            spotter?.release()
        } catch (_: Throwable) {}
        stream = null
        spotter = null
    }
}
