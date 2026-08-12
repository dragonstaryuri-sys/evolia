package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.hypot

/**
 * 手写日记图片处理工具。
 *
 * 提供：
 * 1. 四角透视校正（基于 OpenCV，将倾斜拍摄的纸张校正为矩形）
 * 2. webp 压缩保存（控制文件大小 ≤ 500KB）
 * 3. 日记图片持久化（filesDir/diary_images/<diaryId>/<imageId>.webp）
 */
object DiaryImageUtil {

    private const val TAG = "DiaryImageUtil"
    private const val MAX_FILE_SIZE_KB = 500
    private const val IMAGE_DIR = "diary_images"

    /** OpenCV 是否已成功初始化 */
    private var openCvInitialized = false

    /**
     * 确保 OpenCV native 库已加载。应在首次使用透视校正前调用。
     * @return true 表示初始化成功（或已初始化）
     */
    fun ensureOpenCVLoaded(): Boolean {
        if (openCvInitialized) return true
        openCvInitialized = OpenCVLoader.initLocal()
        if (openCvInitialized) {
            Log.i(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV initialization failed")
        }
        return openCvInitialized
    }

    /**
     * 四角透视校正：将倾斜拍摄的纸张校正为正面矩形。
     *
     * @param bitmap 原始图片
     * @param corners 四个角点（左上、右上、右下、左下），坐标基于原图像素
     * @return 校正后的矩形 Bitmap
     */
    fun perspectiveCorrect(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        check(corners.size == 4) { "需要恰好 4 个角点，当前 ${corners.size}" }
        check(ensureOpenCVLoaded()) { "OpenCV 未初始化，无法进行透视校正" }

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        // 计算目标矩形的宽高：取对边的最大距离，避免内容被压缩
        val topLeft = corners[0]
        val topRight = corners[1]
        val bottomRight = corners[2]
        val bottomLeft = corners[3]

        val widthTop = distance(topLeft, topRight)
        val widthBottom = distance(bottomLeft, bottomRight)
        val heightLeft = distance(topLeft, bottomLeft)
        val heightRight = distance(topRight, bottomRight)

        val targetWidth = maxOf(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val targetHeight = maxOf(heightLeft, heightRight).toInt().coerceAtLeast(1)

        val srcPoints = MatOfPoint2f(
            CvPoint(topLeft.x.toDouble(), topLeft.y.toDouble()),
            CvPoint(topRight.x.toDouble(), topRight.y.toDouble()),
            CvPoint(bottomRight.x.toDouble(), bottomRight.y.toDouble()),
            CvPoint(bottomLeft.x.toDouble(), bottomLeft.y.toDouble())
        )
        val dstPoints = MatOfPoint2f(
            CvPoint(0.0, 0.0),
            CvPoint(targetWidth.toDouble(), 0.0),
            CvPoint(targetWidth.toDouble(), targetHeight.toDouble()),
            CvPoint(0.0, targetHeight.toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dstMat = Mat()
        Imgproc.warpPerspective(
            srcMat,
            dstMat,
            transform,
            CvSize(targetWidth.toDouble(), targetHeight.toDouble()),
            Imgproc.INTER_LINEAR
        )

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, result)

        srcMat.release()
        dstMat.release()
        srcPoints.release()
        dstPoints.release()
        transform.release()

        return result
    }

    /**
     * 将 Bitmap 压缩为 webp 格式，控制文件大小不超过 [maxSizeKb]。
     * 通过逐步降低质量来实现目标大小。
     */
    fun compressToWebp(bitmap: Bitmap, maxSizeKb: Int = MAX_FILE_SIZE_KB): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        var quality = 90
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bitmap.compress(format, quality, baos)
            bytes = baos.toByteArray()
            quality -= 10
        } while (bytes.size / 1024 > maxSizeKb && quality > 10)

        return bytes
    }

    /**
     * 保存日记图片到内部存储。
     *
     * 路径：filesDir/diary_images/<diaryId>/<imageId>.webp
     *
     * @param context Android 上下文
     * @param diaryId 日记 ID
     * @param imageId 图片 ID
     * @param bitmap 要保存的图片
     * @return 保存后的文件绝对路径
     */
    suspend fun saveDiaryImage(
        context: Context,
        diaryId: String,
        imageId: String,
        bitmap: Bitmap
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "$IMAGE_DIR/$diaryId")
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "$imageId.webp")
        val bytes = compressToWebp(bitmap)
        file.writeBytes(bytes)
        file.absolutePath
    }

    /**
     * 从 URI 加载图片为 Bitmap，用于后续裁剪/校正处理。
     * 会修正 EXIF 方向。
     */
    suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            bitmap?.let { ImageUtils.correctImageOrientation(context, uri, it) }
        }.getOrNull()
    }

    /**
     * 删除某篇日记的所有图片文件（日记删除时调用）。
     */
    suspend fun deleteDiaryImages(context: Context, diaryId: String) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "$IMAGE_DIR/$diaryId")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}
