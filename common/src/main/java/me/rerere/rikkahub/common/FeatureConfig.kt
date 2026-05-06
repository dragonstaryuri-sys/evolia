package me.rerere.rikkahub.common

/**
 * Evolia 功能开关管理中心
 * 用于控制开发中功能的可见性
 */
object FeatureConfig {

    /**
     * 总开关：是否开启“实验性功能”
     * 默认逻辑：仅在 Debug 模式（本地开发）下开启
     */
    val isExperimentalEnabled: Boolean
        get() = BuildConfig.DEBUG

    /**
     * 具体功能开关示例
     * 这样你可以精确控制每一个功能的上线状态
     */

    // 示例：新的 AI 模型实验室
    val enableNewAiModel: Boolean = isExperimentalEnabled && true

    // 示例：正在开发的高级备份功能
    val enableAdvancedBackup: Boolean = isExperimentalEnabled && false

    // 示例：还在调优的触觉反馈模式
    val enableCustomHaptics: Boolean = isExperimentalEnabled && true

    /**
     * 检查某个功能是否可见
     */
    fun isFeatureVisible(featureName: String): Boolean {
        return when (featureName) {
            "EXPERIMENTAL" -> isExperimentalEnabled
            else -> false
        }
    }
}
