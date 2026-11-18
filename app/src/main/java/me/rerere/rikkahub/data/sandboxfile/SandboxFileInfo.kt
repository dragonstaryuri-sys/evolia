package me.rerere.rikkahub.data.sandboxfile

/**
 * Agent 创建文件的来源目录。
 *
 * 两个来源都由 Agent 在执行任务时产生，统一纳入「Agent 文件」管理页：
 * - [PYTHON_SANDBOX]：Python 沙箱工作目录（eval_python 及沙盒文件工具产物）。
 *   2026-11-18 起新文件按助手子目录组织（一个助手一个目录，跨会话共享）；
 *   历史遗留按会话子目录组织的文件不迁移、原地保留。
 * - [GENERATED_IMAGE]：Agent 生图产物（generate_image / 生图页），平铺存放于 files/images/
 */
enum class SandboxFileSource {
    PYTHON_SANDBOX,

    GENERATED_IMAGE
}

/**
 * Agent 文件分类。
 */
enum class SandboxFileCategory {
    /** 图片文件（通过文件头嗅探 + MIME/扩展名综合判定） */
    IMAGE,

    /** 其他文件（无法确认是图片的一切文件，包括类型未知的文件） */
    OTHER
}

/**
 * Agent 文件信息模型（仅用于运行时展示与操作，不落库）。
 *
 * 文件系统本身是文件清单的唯一数据来源，每次进入页面重新扫描。
 *
 * @param id 稳定唯一标识 = 来源前缀 + 规范化后的相对路径（不做大小写归一，避免大小写敏感文件系统上撞号；
 *           来源前缀保证两个目录的同名相对路径不冲突）
 * @param name 展示用文件名
 * @param relativePath 相对来源根目录的规范化路径（'/' 分隔），仅在应用内部使用，不展示给用户
 * @param topLevelDir 来源根目录下第一级子目录名：
 *        python_sandbox 新布局为助手 id、旧布局为会话 id；根目录直属文件与生图平铺为 null。
 *        分组展示时据此解析为助手名/会话标题
 * @param source 来源目录，决定扫描根与实际读写路径
 * @param category 分类结果
 */
data class SandboxFileInfo(
    val id: String,
    val name: String,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
    val extension: String?,
    val topLevelDir: String?,
    val source: SandboxFileSource,
    val category: SandboxFileCategory
)

/**
 * Agent 文件分组（按来源根目录下的第一级子目录聚簇，用于页面分组展示）。
 *
 * @param key 分组键（见 [SandboxFileRules.groupKeyOf]），跨来源/目录唯一
 * @param label 展示名（助手名 / 会话标题 / 生图 / 回退短名），由 VM 解析注入
 * @param files 组内文件，已按最后修改时间倒序
 */
data class SandboxFileGroup(
    val key: String,
    val label: String,
    val files: List<SandboxFileInfo>
)
