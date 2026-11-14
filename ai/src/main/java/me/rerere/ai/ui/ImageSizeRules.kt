package me.rerere.ai.ui

import kotlin.math.abs

/**
 * 显式宽高（width/height）到各后端实际尺寸参数的映射规则。
 *
 * 纯函数、不依赖 Android API，供 Provider 层调用并有单测固化：
 * - DALL·E 3 只接受固定尺寸档位，必须就近映射而不是透传（否则必 400）；
 * - Google predict API 只接受 aspectRatio 枚举，宽高只能转成最近的纵横比。
 *
 * 距离按「纵横比的绝对差」用整数交叉相乘精确比较（不用浮点 ln 距离——
 * 数学上平手的场景浮点会差一个 ULP 导致结果不确定）。
 */
object ImageSizeRules {
    /**
     * DALL·E 3 官方支持的三档尺寸（与既有 aspectRatio 三档映射一致）。
     */
    private val DALLE3_ALLOWED_SIZES = listOf("1024x1024", "1792x1024", "1024x1792")

    /**
     * Google predict API 支持的纵横比档位（声明顺序即平手优先级：SQUARE 最优先）。
     */
    private val GOOGLE_RATIOS = linkedMapOf(
        ImageAspectRatio.SQUARE to (1L to 1L),
        ImageAspectRatio.LANDSCAPE to (16L to 9L),
        ImageAspectRatio.PORTRAIT to (9L to 16L),
    )

    /**
     * 把任意宽高吸附到 DALL·E 3 最近的合法尺寸档位（按纵横比）。
     * 宽高非正数时兜底为 1024x1024。
     */
    fun snapToDalle3Size(width: Int, height: Int): String {
        val validWidth = width.takeIf { it > 0 } ?: return "1024x1024"
        val validHeight = height.takeIf { it > 0 } ?: return "1024x1024"

        val candidates = DALLE3_ALLOWED_SIZES.map { size ->
            val (candidateWidth, candidateHeight) = size.split("x").map { it.toLong() }
            size to (candidateWidth to candidateHeight)
        }
        val bestSize = minByRatioDistance(validWidth.toLong(), validHeight.toLong(), candidates) { it.second }
        return bestSize?.first ?: "1024x1024"
    }

    /**
     * 把任意宽高映射到 Google predict API 支持的最近纵横比枚举（1:1 / 16:9 / 9:16）。
     * 宽高非正数时兜底为 SQUARE。
     */
    fun deriveGoogleAspectRatio(width: Int, height: Int): ImageAspectRatio {
        val validWidth = width.takeIf { it > 0 } ?: return ImageAspectRatio.SQUARE
        val validHeight = height.takeIf { it > 0 } ?: return ImageAspectRatio.SQUARE

        val bestRatio = minByRatioDistance(validWidth.toLong(), validHeight.toLong(), GOOGLE_RATIOS.entries) { it.value }
        return bestRatio?.key ?: ImageAspectRatio.SQUARE
    }

    /**
     * 在候选中取与目标纵横比 w/h 距离最近者（距离 = |w/h - n/d|）。
     * 距离 = |w*d - n*h| / (h*d)，h 对所有候选相同，比较时交叉相乘消去分母：
     * |w*d1 - n1*h| * d2 vs |w*d2 - n2*h| * d1，精确无浮点误差；
     * 距离相等时取遍历顺序靠前者（minWithOrNull 平手保留首个）。
     */
    private fun <T> minByRatioDistance(
        width: Long,
        height: Long,
        candidates: Collection<T>,
        ratioOf: (T) -> Pair<Long, Long>,
    ): T? {
        return candidates.minWithOrNull { a, b ->
            val (numA, denA) = ratioOf(a)
            val (numB, denB) = ratioOf(b)
            val distA = abs(width * denA - numA * height) * denB
            val distB = abs(width * denB - numB * height) * denA
            distA.compareTo(distB)
        }
    }
}
