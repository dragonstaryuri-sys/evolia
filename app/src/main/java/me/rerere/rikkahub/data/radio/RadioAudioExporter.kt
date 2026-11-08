package me.rerere.rikkahub.data.radio

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 电台音频导出（MediaStore → Download/Evolia/radio/，独立外部副本，删除互不影响）。
 * UI（节目详情/栏目卡片抽屉）共用。
 */
object RadioAudioExporter {

    /**
     * 导出到公共目录。
     *
     * @param displayNameBase 用于生成文件名的前缀（如节目标题），非法字符会被替换
     * @return 面向用户的成功提示（含导出路径）；失败抛异常
     */
    fun exportToPublicDownloads(context: Context, source: File, displayNameBase: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 及以下降级：无 MediaStore RELATIVE_PATH 支持
            val legacyDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = File(File(legacyDir, "Evolia"), source.name)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            return "已导出到 ${target.absolutePath}"
        }
        val safeTitle = displayNameBase.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(50).ifBlank { "episode" }
        val displayName = "${safeTitle}_${source.name}"
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/Evolia/radio"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                if (source.name.endsWith(".wav", true)) "audio/wav" else "audio/mp4"
            )
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("无法在公共目录创建文件")
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("无法写入公共目录文件")
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, published, null, null)
            // 通知媒体库扫描，文件管理器立即可见
            MediaScannerConnection.scanFile(context, arrayOf(displayName), null, null)
            return "已导出到 Download/Evolia/radio/$displayName"
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }
    }
}
