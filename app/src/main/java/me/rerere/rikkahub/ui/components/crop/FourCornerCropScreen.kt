package me.rerere.rikkahub.ui.components.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.DiaryImageUtil
import me.rerere.rikkahub.utils.ImageUtils
import kotlin.math.roundToInt

private const val TAG = "FourCornerCropScreen"

/**
 * 四角透视校正裁剪界面（手写日记专用）。
 *
 * 用户可拖拽四个角点选中纸张区域，确认后系统通过 OpenCV 将倾斜的纸张校正为正面矩形。
 *
 * @param sourceUri 原始图片 URI
 * @param onCropComplete 校正完成，返回校正后图片的 Bitmap
 * @param onCancel 用户取消
 */
@Composable
fun FourCornerCropScreen(
    sourceUri: Uri,
    onCropComplete: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // 容器尺寸（图片显示区域）
    var containerSize by remember { mutableStateOf(Size.Zero) }
    // 图片在容器中的实际显示矩形（nullable：null 表示尚未初始化）
    var imageDisplayRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // 四个角点位置（基于容器坐标系，顺序：左上、右上、右下、左下）
    var corners by remember { mutableStateOf<List<Offset>?>(null) }

    // 加载原图（含 EXIF 方向校正）
    LaunchedEffect(sourceUri) {
        Log.d(TAG, "LaunchedEffect: start loading bitmap, uri=$sourceUri")
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = context.contentResolver.openInputStream(sourceUri)
                val rawBitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
                Log.d(TAG, "decodeStream result: ${if (rawBitmap != null) "${rawBitmap.width}x${rawBitmap.height}" else "null"}")
                rawBitmap?.let { ImageUtils.correctImageOrientation(context, sourceUri, it) }
            }.onSuccess { bitmap ->
                Log.d(TAG, "bitmap load success: ${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "null"}")
                if (bitmap != null) originalBitmap = bitmap
                else {
                    Log.e(TAG, "bitmap load success but null, calling onCancel")
                    onCancel()
                }
            }.onFailure {
                Log.e(TAG, "bitmap load failed", it)
                it.printStackTrace()
                onCancel()
            }
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.diary_crop_adjust_title),
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.diary_crop_adjust_hint),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }

                // 图片 + 四角点
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val bitmap = originalBitmap
                    if (bitmap != null) {
                        val maxWidth = constraints.maxWidth.toFloat()
                        val maxHeight = constraints.maxHeight.toFloat()

                        // 计算 ContentScale.Fit 下图片的实际显示矩形
                        val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                        val containerAspect = maxWidth / maxHeight
                        val displayWidth: Float
                        val displayHeight: Float
                        if (imageAspect > containerAspect) {
                            displayWidth = maxWidth
                            displayHeight = maxWidth / imageAspect
                        } else {
                            displayHeight = maxHeight
                            displayWidth = maxHeight * imageAspect
                        }
                        val offsetX = (maxWidth - displayWidth) / 2f
                        val offsetY = (maxHeight - displayHeight) / 2f

                        val rect = androidx.compose.ui.geometry.Rect(
                            left = offsetX,
                            top = offsetY,
                            right = offsetX + displayWidth,
                            bottom = offsetY + displayHeight
                        )

                        // 初始化角点（仅首次或图片尺寸变化时执行）
                        // 注意：Rect 不是 data class，!= 是引用比较，所以用值比较
                        val prevRect = imageDisplayRect
                        val rectChanged = prevRect == null ||
                                prevRect.left != rect.left ||
                                prevRect.right != rect.right ||
                                prevRect.top != rect.top ||
                                prevRect.bottom != rect.bottom
                        if (corners == null || rectChanged) {
                            Log.d(TAG, "init corners: corners==null=${corners == null}, rectChanged=$rectChanged, " +
                                    "prevRect=$prevRect, newRect=$rect, " +
                                    "displayW=$displayWidth, displayH=$displayHeight, " +
                                    "maxW=$maxWidth, maxH=$maxHeight")
                            imageDisplayRect = rect
                            val insetX = displayWidth * 0.05f
                            val insetY = displayHeight * 0.05f
                            val newCorners = listOf(
                                Offset(rect.left + insetX, rect.top + insetY),       // 左上
                                Offset(rect.right - insetX, rect.top + insetY),      // 右上
                                Offset(rect.right - insetX, rect.bottom - insetY),   // 右下
                                Offset(rect.left + insetX, rect.bottom - insetY)     // 左下
                            )
                            Log.d(TAG, "new corners: $newCorners")
                            corners = newCorners
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )

                            val currentCorners = corners
                            if (currentCorners != null) {
                                // 绘制四边形外部遮罩 + 边框（不用 BlendMode，避免把底层图片也擦掉）
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val path = Path().apply {
                                        moveTo(currentCorners[0].x, currentCorners[0].y)
                                        lineTo(currentCorners[1].x, currentCorners[1].y)
                                        lineTo(currentCorners[2].x, currentCorners[2].y)
                                        lineTo(currentCorners[3].x, currentCorners[3].y)
                                        close()
                                    }
                                    // 只画四边形外部的 4 个半透明遮罩三角形区域，内部保持透明让图片可见
                                    val overlayColor = Color.Black.copy(alpha = 0.55f)
                                    // 外部遮罩通过填充整个画布再"挖空"四边形内部会擦除底层，
                                    // 所以改为直接在 Path 之外画 4 块遮罩：使用 FillType.EvenOdd
                                    val outerPath = Path().apply {
                                        // 外框：整个画布
                                        moveTo(0f, 0f)
                                        lineTo(size.width, 0f)
                                        lineTo(size.width, size.height)
                                        lineTo(0f, size.height)
                                        close()
                                        // 内框：四边形（EvenOdd 会把两个路径之间的区域作为填充区）
                                        moveTo(currentCorners[0].x, currentCorners[0].y)
                                        lineTo(currentCorners[1].x, currentCorners[1].y)
                                        lineTo(currentCorners[2].x, currentCorners[2].y)
                                        lineTo(currentCorners[3].x, currentCorners[3].y)
                                        close()
                                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                                    }
                                    drawPath(path = outerPath, color = overlayColor, style = androidx.compose.ui.graphics.drawscope.Fill)
                                    // 四边形边框线
                                    drawPath(
                                        path = path,
                                        color = Color.White,
                                        style = Stroke(width = 3f)
                                    )
                                }

                                // 4 个可拖拽角点
                                currentCorners.forEachIndexed { index, corner ->
                                    CornerHandle(
                                        position = corner,
                                        onDrag = { delta ->
                                            // 重要：不要使用外层捕获的 currentCorners！
                                            // CornerHandle 内部 pointerInput(Unit) 永不重启，
                                            // lambda 捕获的 currentCorners 永远是首次组合时的初始值。
                                            // 必须直接读取 corners MutableState 的最新值。
                                            val snapshot = corners
                                            Log.d(TAG, "onDrag start: index=$index, delta=$delta, snapshotCorners=$snapshot")
                                            if (snapshot != null) {
                                                corners = snapshot.toMutableList().also {
                                                    val newOffset = it[index] + delta
                                                    val rect = imageDisplayRect
                                                    if (rect != null) {
                                                        val pad = 4f
                                                        val clampedX = newOffset.x.coerceIn(
                                                            rect.left - pad,
                                                            rect.right + pad
                                                        )
                                                        val clampedY = newOffset.y.coerceIn(
                                                            rect.top - pad,
                                                            rect.bottom + pad
                                                        )
                                                        it[index] = Offset(clampedX, clampedY)
                                                    } else {
                                                        it[index] = newOffset
                                                    }
                                                }
                                                Log.d(TAG, "onDrag end: new corners=$corners")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 底部操作栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.ButtonPill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            val bitmap = originalBitmap ?: return@Button
                            val currentCorners = corners ?: return@Button
                            val rect = imageDisplayRect ?: return@Button

                            isProcessing = true
                            scope.launch {
                                withContext(Dispatchers.Default) {
                                    // 将显示坐标转换为原图坐标
                                    val scaleX = bitmap.width.toFloat() / rect.width
                                    val scaleY = bitmap.height.toFloat() / rect.height
                                    val imagePoints = currentCorners.map { corner ->
                                        PointF(
                                            ((corner.x - rect.left) * scaleX).roundToInt().toFloat().coerceIn(0f, bitmap.width.toFloat()),
                                            ((corner.y - rect.top) * scaleY).roundToInt().toFloat().coerceIn(0f, bitmap.height.toFloat())
                                        )
                                    }
                                    val corrected = DiaryImageUtil.perspectiveCorrect(bitmap, imagePoints)
                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                        onCropComplete(corrected)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.ButtonPill,
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }
}

/**
 * 可拖拽的角点手柄。通过 Modifier.offset 定位到 [position]（基于父容器坐标系）。
 */
@Composable
private fun CornerHandle(
    position: Offset,
    onDrag: (Offset) -> Unit
) {
    val handleSize = 28.dp
    val dotSize = 12.dp
    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(
                (position.x - handleSize.toPx() / 2).roundToInt(),
                (position.y - handleSize.toPx() / 2).roundToInt()
            ) }
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(handleSize)
                .background(
                    color = Color.White.copy(alpha = 0.3f),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(dotSize)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                )
        )
    }
}
