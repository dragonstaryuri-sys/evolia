package me.rerere.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.FileOutputStream

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
)

/**
 * 图片长边阈值（像素）。超过此尺寸的图片会被等比缩放后再编码，
 * 避免超大原图吃掉过多 token 和请求体积。
 */
private const val MAX_LONGER_EDGE_PX = 2048

/**
 * 超过此字节数的图片会触发强制重新压缩（哪怕它原本就是 webp 格式）。
 * 目标：尽量控制发给模型的 base64 体积，节省 token。
 */
private const val MAX_FILE_BYTES_FORCE_RECOMPRESS = 2 * 1024 * 1024L // 2MB

private const val TAG = "FileEncoder"

fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }

            // 判断是否需要做归一化处理（格式转换 / 尺寸缩放 / 体积压缩）
            val currentMime = file.guessMimeType().getOrNull()
            val notWebp = currentMime != "image/webp"
            val tooBigBytes = file.length() > MAX_FILE_BYTES_FORCE_RECOMPRESS
            val needNormalize = notWebp || tooBigBytes

            if (needNormalize) {
                normalizeImageToWebp(file)
                println(
                    "[$TAG] Image normalized to WebP: notWebp=$notWebp, " +
                            "tooBigBytes=$tooBigBytes(${file.length()}), path=${file.absolutePath}"
                )
            }

            val bytes = file.readBytes()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val mimeAfter = file.guessMimeType().getOrNull()
                ?: throw IllegalStateException("Cannot determine MIME after normalization: $file")
            if (withPrefix) "data:$mimeAfter;base64,$encoded" else encoded
        }

        this.url.startsWith("data:") -> url
        this.url.startsWith("http://") || this.url.startsWith("https://") -> url
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

/**
 * 从 "data:{mime};base64,..." 形式的 Data URI 中抽取 mime 部分。
 * 如果不是合法的 Data URI，则回退到 fallback 值。
 */
internal fun extractMimeFromDataUri(dataUri: String, fallback: String): String = runCatching {
    require(dataUri.startsWith("data:")) { "Not a data URI: $dataUri" }
    val afterData = dataUri.removePrefix("data:")
    afterData.substringBefore(';').ifBlank { fallback }
}.getOrDefault(fallback)

/**
 * 从 "data:{mime};base64,..." 形式的 Data URI 中抽取纯 base64 数据部分（不含前缀）。
 */
internal fun extractBase64DataFromDataUri(dataUri: String): String =
    dataUri.substringAfter(";base64,", dataUri)

/**
 * 从 URL 路径后缀粗略推断图片 MIME 类型。
 * 无法识别时回退到 "image/jpeg"。
 */
internal fun guessImageMimeFromUrl(url: String): String {
    val lower = url.substringBefore('?').lowercase()
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".bmp") -> "image/bmp"
        lower.endsWith(".heic") || lower.endsWith(".heif") -> "image/heic"
        // jpg / jpeg / 其他一律按 jpeg 处理
        else -> "image/jpeg"
    }
}

/**
 * 将图片文件归一化为 WebP(Lossy q=80) 格式：
 * 1. 解码 Bitmap；decode 失败则直接返回，保留原文件不覆写
 * 2. 若任意一边超过 MAX_LONGER_EDGE_PX，等比缩放到范围内
 * 3. 以 WebP Lossy q=80 压缩后通过「临时文件 + rename」原子替换原文件，避免中途失败损坏原图
 */
private fun normalizeImageToWebp(file: File) {
    // 1) 解码，失败则不改动原文件
    val originalBitmap = BitmapFactory.decodeFile(file.absolutePath)
    if (originalBitmap == null) {
        println("[$TAG] normalizeImageToWebp: decodeBitmap failed, keep original file: $file")
        return
    }

    // 2) 超尺寸等比缩放
    val longerEdge = maxOf(originalBitmap.width, originalBitmap.height)
    val targetBitmap: Bitmap = if (longerEdge > MAX_LONGER_EDGE_PX) {
        val scale = MAX_LONGER_EDGE_PX.toFloat() / longerEdge.toFloat()
        val targetW = (originalBitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (originalBitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(originalBitmap, targetW, targetH, true)
        println(
            "[$TAG] Resize image: ${originalBitmap.width}x${originalBitmap.height} " +
                    "-> ${targetW}x${targetH}, scale=${String.format("%.3f", scale)}"
        )
        if (scaled !== originalBitmap) originalBitmap.recycle()
        scaled
    } else {
        originalBitmap
    }

    // 3) 压 WebP 写到临时文件，再原子替换
    val tmpFile = File(file.parentFile, "${file.name}.webp.tmp")
    try {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        FileOutputStream(tmpFile).use { out ->
            targetBitmap.compress(format, 80, out)
        }
        if (!tmpFile.renameTo(file)) {
            // rename 失败（例如跨分区），fallback 为 copy+delete
            tmpFile.copyTo(file, overwrite = true)
            tmpFile.delete()
        }
    } finally {
        if (targetBitmap !== originalBitmap) {
            targetBitmap.recycle()
        }
        originalBitmap.recycle()
        if (tmpFile.exists()) tmpFile.delete()
    }
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val bytes = file.readBytes()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mime = guessAudioMime(file.name)
            val bytes = file.readBytes()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (withPrefix) "data:$mime;base64,$encoded" else encoded
        }

        this.url.startsWith("data:") -> url
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

/**
 * 根据 url 推断音频 mime（用于 Provider 直接拼装 inline_data / input_audio）。
 * file:// / data: / http(s):// 都会尝试从路径扩展名推断；无法识别时回退 audio/mpeg。
 */
fun UIMessagePart.Audio.audioMime(): String = guessAudioMime(url)

/**
 * OpenAI input_audio.format 字段值（mp3/wav/mp4 等，不含 "audio/" 前缀）。
 */
fun UIMessagePart.Audio.openaiAudioFormat(): String = when (guessAudioMime(url)) {
    "audio/wav" -> "wav"
    "audio/mpeg" -> "mp3"
    "audio/mp4" -> "mp4"
    "audio/webm" -> "webm"
    "audio/ogg" -> "ogg"
    "audio/flac" -> "flac"
    else -> "mp3"
}

private fun guessAudioMime(url: String): String {
    val lower = url.substringBefore('?').lowercase()
    return when {
        lower.endsWith(".wav") -> "audio/wav"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".m4a") -> "audio/mp4"
        lower.endsWith(".mp4") -> "audio/mp4"
        lower.endsWith(".webm") -> "audio/webm"
        lower.endsWith(".ogg") -> "audio/ogg"
        lower.endsWith(".flac") -> "audio/flac"
        lower.startsWith("data:audio/wav") -> "audio/wav"
        lower.startsWith("data:audio/mpeg") || lower.startsWith("data:audio/mp3") -> "audio/mpeg"
        lower.startsWith("data:audio/mp4") || lower.startsWith("data:audio/m4a") -> "audio/mp4"
        lower.startsWith("data:audio/webm") -> "audio/webm"
        lower.startsWith("data:audio/ogg") -> "audio/ogg"
        lower.startsWith("data:audio/flac") -> "audio/flac"
        else -> "audio/mpeg"
    }
}

private fun convertToJpeg(file: File) = runCatching {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
    FileOutputStream(file).use { outputStream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
    }
}

private fun File.isSupportedType(): Boolean {
    val mimeType = guessMimeType().getOrNull() ?: return false
    return mimeType in supportedTypes
}

private fun File.guessMimeType(): Result<String> = runCatching {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 打印前16个字节（可选）
        println("guessMimeType bytes = ${bytes.joinToString(",")}")

        // 判断 HEIC 格式：包含 "ftypheic"
        if (bytes.copyOfRange(4, 12).toString(Charsets.US_ASCII) == "ftypheic") {
            return@runCatching "image/heic"
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return@runCatching "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return@runCatching "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return@runCatching "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return@runCatching "image/gif"
        }

        error(
            "Failed to guess MIME type: $header, ${
                bytes.joinToString(",") {
                    it.toUByte().toString()
                }
            }"
        )
    }
}
