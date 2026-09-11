package me.rerere.rikkahub.ui.components.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.ai.tools.CameraCaptureHub
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "CameraCaptureOverlay"

/**
 * AI 调用摄像头拍照的全屏 Overlay。
 *
 * 状态机：Idle → Countdown(3,2,1) → Capturing → Done
 * 拍完：Bitmap → 压缩为 WebP（质量 80，max 长边 1280）→ 存入 camera_captures 目录 → 回传结果
 * 用户可点取消中断。
 *
 * 注意：Preview 与 ImageCapture 在初始绑定时一起绑定到 lifecycle，避免在拍照瞬间
 * 重新 unbindAll + 绑定导致预览画面闪烁。
 */
@Composable
fun CameraCaptureOverlay(
    request: CameraCaptureHub.CaptureRequest,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var countdown by remember { mutableIntStateOf(3) }
    var phase by remember { mutableStateOf(CapturePhase.Countdown) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val previewView = remember { PreviewView(context) }
    // ImageCapture 需要在 DisposableEffect 中绑定后才能使用，故用 remember 暂存引用
    val imageCaptureHolder = remember { mutableStateOf<ImageCapture?>(null) }

    val facingText = if (request.facing == "front") "前置" else "后置"

    // 绑定 CameraX：Preview + ImageCapture 一起绑定
    DisposableEffect(request.facing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val selector = if (request.facing == "front") {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCaptureHolder.value = imageCapture

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed", e)
                errorMessage = "无法启动摄像头：${e.message ?: "未知错误"}"
                phase = CapturePhase.Failed
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {}
        }
    }

    // 倒计时驱动
    LaunchedEffect(request.requestId) {
        phase = CapturePhase.Countdown
        countdown = 3
        while (countdown > 0 && phase == CapturePhase.Countdown) {
            delay(1000)
            countdown--
        }
        if (phase == CapturePhase.Countdown) {
            phase = CapturePhase.Capturing
            val capture = imageCaptureHolder.value
            if (capture == null) {
                errorMessage = "摄像头未就绪"
                phase = CapturePhase.Failed
                CameraCaptureHub.deliverResult(
                    CameraCaptureHub.CaptureResult(
                        requestId = request.requestId,
                        success = false,
                        error = "摄像头未就绪"
                    )
                )
            } else {
                captureAndDeliver(capture, context, request) { result ->
                    // 关键：把拍照结果回传给 CameraCaptureHub，否则工具侧会一直挂起直到超时
                    CameraCaptureHub.deliverResult(result)
                    if (result.success) {
                        phase = CapturePhase.Done
                    } else {
                        errorMessage = result.error
                        phase = CapturePhase.Failed
                    }
                }
            }
        }
    }

    // 完成或失败后自动关闭
    LaunchedEffect(phase) {
        if (phase == CapturePhase.Done || phase == CapturePhase.Failed) {
            delay(800)
            onDismiss()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 相机预览
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // 顶部提示
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AI 正在用${facingText}摄像头拍照",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 取消按钮
            TextButton(
                onClick = {
                    phase = CapturePhase.Failed
                    CameraCaptureHub.deliverResult(
                        CameraCaptureHub.CaptureResult(
                            requestId = request.requestId,
                            success = false,
                            error = "用户取消了拍照"
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 24.dp, start = 16.dp)
            ) {
                Text("取消", color = Color.White)
            }

            // 倒计时数字
            AnimatedVisibility(
                visible = phase == CapturePhase.Countdown,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .background(Color(0x66000000), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (countdown > 0) countdown.toString() else "开始",
                        color = Color.White,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 拍摄中 / 完成 / 失败提示
            AnimatedVisibility(
                visible = phase != CapturePhase.Countdown,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                val statusText = when (phase) {
                    CapturePhase.Capturing -> "正在拍摄…"
                    CapturePhase.Done -> "拍摄完成"
                    CapturePhase.Failed -> errorMessage ?: "拍照失败"
                    CapturePhase.Countdown -> ""
                }
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .background(Color(0x66000000), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

private enum class CapturePhase {
    Countdown, Capturing, Done, Failed
}

/**
 * 触发一次 CameraX ImageCapture，拍完压缩为 WebP 并回传结果。
 * 这里不再重新绑定，直接用已绑定的 imageCapture 实例。
 */
private fun captureAndDeliver(
    imageCapture: ImageCapture,
    context: Context,
    request: CameraCaptureHub.CaptureRequest,
    onResult: (CameraCaptureHub.CaptureResult) -> Unit
) {
    try {
        // 准备输出文件（先用临时 jpeg，拍完压缩为 webp）
        val outputDir = File(context.filesDir, "images/camera_captures").apply { mkdirs() }
        val tempFile = File(outputDir, "capture_${UUID.randomUUID()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // 压缩为 WebP（保证 <500kb）
                    val compressed = compressToWebp(tempFile, maxLongEdge = 1280, quality = 80)
                    val finalPath = compressed.absolutePath
                    Log.i(TAG, "camera_capture: saved $finalPath (${compressed.length()} bytes)")
                    onResult(
                        CameraCaptureHub.CaptureResult(
                            requestId = request.requestId,
                            success = true,
                            imagePath = finalPath
                        )
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "camera_capture: takePicture failed", exception)
                    onResult(
                        CameraCaptureHub.CaptureResult(
                            requestId = request.requestId,
                            success = false,
                            error = "拍照失败：${exception.message ?: "未知错误"}"
                        )
                    )
                }
            }
        )
    } catch (e: Exception) {
        Log.e(TAG, "camera_capture: takePicture exception", e)
        onResult(
            CameraCaptureHub.CaptureResult(
                requestId = request.requestId,
                success = false,
                error = "拍照失败：${e.message ?: "未知错误"}"
            )
        )
    }
}

/**
 * 把拍下来的图压缩为 WebP，保证长边不超过 maxLongEdge 且文件体积合理。
 * 原始 JPEG 先解码再重编码为 WebP（质量 quality）。
 */
private fun compressToWebp(sourceFile: File, maxLongEdge: Int, quality: Int): File {
    val originalBytes = sourceFile.readBytes()
    val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
        ?: return sourceFile // 解码失败则原样返回

    // 等比缩放
    val srcW = bitmap.width
    val srcH = bitmap.height
    val longEdge = maxOf(srcW, srcH)
    val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge else 1f
    val scaled = if (scale < 1f) {
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(bitmap, newW, newH, true).also { bitmap.recycle() }
    } else bitmap

    // 写入 WebP
    val webpFile = File(sourceFile.parentFile, sourceFile.nameWithoutExtension + ".webp")
    FileOutputStream(webpFile).use { out ->
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(format, quality, out)
    }
    scaled.recycle()

    // 删除原始 JPEG
    if (webpFile.exists() && webpFile.length() > 0) {
        sourceFile.delete()
        return webpFile
    }
    return sourceFile
}
