@file:Suppress("unused")

package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.png.PngChunkType
import com.drew.metadata.png.PngDirectory
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 图片处理工具类
 * 提供图片压缩、旋转修正、二维码解析等功能
 */
object ImageUtils {

    /**
     * 优化的图片加载方法，避免OOM
     * 1. 先获取图片尺寸
     * 2. 计算合适的采样率
     * 3. 加载压缩后的图片
     * 4. 处理图片旋转
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸限制，默认1024px
     * @return 压缩后的Bitmap，失败返回null
     */
    fun loadOptimizedBitmap(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024
    ): Bitmap? {
        return runCatching {
            // 第一步：获取图片的原始尺寸，不加载到内存
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // 计算合适的采样率
            val sampleSize = calculateInSampleSize(options, maxSize, maxSize)

            // 第二步：使用采样率加载压缩后的图片
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // 使用RGB_565减少内存占用
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, loadOptions)
            }

            // 第三步：处理图片旋转（如果需要）
            bitmap?.let { correctImageOrientation(context, uri, it) }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 降采样加载图片（用于裁剪页等需要直接绘制 Bitmap 的场景）
     *
     * 高分辨率原图（如相机 40/50MP 模式照片）全尺寸解码后内存可达 100MB 以上，
     * 超过 RecordingCanvas 单张 bitmap 100MB 的绘制上限会直接抛
     * "Canvas: trying to draw too large bitmap" 崩溃。本方法先量边界，
     * 再按 2 的幂采样使最长边不超过 maxDimension，最后修正 EXIF 方向。
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxDimension 最长边上限（px），默认 2048（ARGB_8888 下最多约 16MB 内存）
     * @return 降采样后的Bitmap，失败返回null
     */
    fun loadDownsampledBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = 2048
    ): Bitmap? {
        return runCatching {
            // 第一步：仅读取图片尺寸，不加载像素到内存
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, bounds)
            }

            // 第二步：采样解码（保留 ARGB_8888，保证裁剪输出质量）
            val sampleSize = calculateDownSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            val bitmap = if (sampleSize != null) {
                val loadOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, loadOptions)
                }
            } else {
                null
            }

            // 第三步：修正 EXIF 方向
            if (bitmap != null) correctImageOrientation(context, uri, bitmap) else null
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 从字节数组降采样解码 Bitmap（用于相机拍照等数据已在内存中的场景）。
     *
     * 200MP 级相机原图全尺寸解码需要数百 MB 内存，会直接 OOM；
     * 先量边界再按 2 的幂采样使最长边不超过 maxDimension。
     *
     * @param data 图片文件字节
     * @param maxDimension 最长边上限（px）
     * @return 降采样后的Bitmap，失败返回null
     */
    fun decodeDownsampledBitmap(data: ByteArray, maxDimension: Int = 2048): Bitmap? {
        return runCatching {
            // 第一步：仅读取图片尺寸，不加载像素到内存
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)

            // 第二步：采样解码（保留 ARGB_8888）
            val sampleSize = calculateDownSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            if (sampleSize == null) {
                null
            } else {
                val loadOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                BitmapFactory.decodeByteArray(data, 0, data.size, loadOptions)
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 计算 inSampleSize（2 的幂），使最长边降到 maxDimension 以内。
     *
     * @return 采样率；尺寸无效（解码失败或非图片）时返回 null
     */
    private fun calculateDownSampleSize(outWidth: Int, outHeight: Int, maxDimension: Int): Int? {
        val hasValidSize = outWidth > 0 && outHeight > 0
        if (!hasValidSize) return null

        val longestEdge = maxOf(outWidth, outHeight)
        var sampleSize = 1
        while (longestEdge / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 计算合适的采样率
     *
     * @param options BitmapFactory.Options包含原始图片尺寸信息
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 采样率（2的幂）
     */
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // 计算最大的inSampleSize值，该值是2的幂，并且保持高度和宽度都大于请求的高度和宽度
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 修正图片旋转
     * 根据EXIF信息自动旋转图片到正确方向
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param bitmap 原始bitmap
     * @return 旋转后的bitmap
     */
    fun correctImageOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return runCatching {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = inputStream?.let { ExifInterface(it) }
            inputStream?.close()

            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap // 不需要旋转
            }

            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle() // 回收原始bitmap
            }
            rotatedBitmap
        }.onFailure {
            it.printStackTrace()
        }.getOrDefault(bitmap)
    }

    /**
     * 从图片中解析二维码
     *
     * @param bitmap 要解析的图片
     * @return 二维码内容，解析失败返回null
     */
    fun decodeQRCodeFromBitmap(bitmap: Bitmap): String? {
        return runCatching {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)

            result.text
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 从URI加载图片并解析二维码（组合方法）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸限制，默认1024px
     * @return 二维码内容，解析失败返回null
     */
    fun decodeQRCodeFromUri(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024
    ): String? {
        val bitmap = loadOptimizedBitmap(context, uri, maxSize) ?: return null
        return try {
            decodeQRCodeFromBitmap(bitmap)
        } finally {
            bitmap.recycle() // 确保释放内存
        }
    }

    /**
     * 安全地回收Bitmap内存
     *
     * @param bitmap 要回收的bitmap
     */
    fun recycleBitmapSafely(bitmap: Bitmap?) {
        bitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    /**
     * Copy an image to app's internal storage for persistence
     * 
     * @param context Android context
     * @param sourceUri Source image URI
     * @param fileName Name for the copied file
     * @return URI of the copied file, or null if failed
     */
    fun copyImageToInternalStorage(
        context: Context,
        sourceUri: Uri,
        fileName: String
    ): Uri? {
        return runCatching {
            val iconDir = java.io.File(context.filesDir, "custom_icons")
            if (!iconDir.exists()) {
                iconDir.mkdirs()
            }
            
            val destFile = java.io.File(iconDir, fileName)
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Uri.fromFile(destFile)
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 获取图片的基本信息（不加载到内存）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @return ImageInfo包含宽度、高度、MIME类型等信息
     */
    fun getImageInfo(context: Context, uri: Uri): ImageInfo? {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            if (options.outWidth > 0 && options.outHeight > 0) {
                ImageInfo(
                    width = options.outWidth,
                    height = options.outHeight,
                    mimeType = options.outMimeType
                )
            } else null
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 获取酒馆角色卡中的角色元数据（如果存在）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @return Result<String> 包含角色元数据的Result对象
     */
    fun getTavernCharacterMeta(context: Context, uri: Uri): Result<String> = runCatching {
        val metadata = context.contentResolver.openInputStream(uri)?.use { ImageMetadataReader.readMetadata(it) }
        if (metadata == null) error("Metadata is null, please check if the image is a character card")
        if (!metadata.containsDirectoryOfType(PngDirectory::class.java)) error("No PNG directory found, please check if the image is a character card")

        val pngDirectory = metadata.getDirectoriesOfType(PngDirectory::class.java)
            .firstOrNull { directory ->
                directory.pngChunkType == PngChunkType.tEXt
                    && directory.getString(PngDirectory.TAG_TEXTUAL_DATA).startsWith("[chara:")
            } ?: error("No tEXt chunk found, please check if the image is a character card")

        val value = pngDirectory.getString(PngDirectory.TAG_TEXTUAL_DATA)

        val regex = Regex("""\[chara:\s*(.+?)]""")
        return Result.success(regex.find(value)?.groupValues?.get(1) ?: error("No character data found"))
    }

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val mimeType: String?
    )
}
