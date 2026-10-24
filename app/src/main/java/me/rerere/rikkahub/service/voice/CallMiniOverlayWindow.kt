package me.rerere.rikkahub.service.voice

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.imageLoader
import coil3.request.ImageRequest
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Avatar

/**
 * 通话缩小后的系统悬浮迷你窗（Telegram 式顶部窄条）。
 *
 * 通话缩小（isCallMinimized）且已授予「显示在其他应用上层」权限时显示：
 * App 退到桌面 / 其他应用时依然悬浮在屏幕最上方，点击条身拉回应用并展开通话页。
 * 权限未授予时不显示（由应用内迷你窗兜底，见 RouteActivity）。
 *
 * 使用传统 View 而非 ComposeView：悬浮窗挂在 WindowManager 上没有 Compose 生命周期宿主。
 * 视觉与应用内 MiniCallWindow 对齐；深浅色按系统 night mode 成对取色（悬浮窗在 App 外，
 * 无法使用 MaterialTheme，故读取系统暗色模式而非写死单模式颜色）。
 */
class CallMiniOverlayWindow(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var attachedView: View? = null
    private var durationText: TextView? = null
    private var avatarImageView: ImageView? = null
    private var avatarTextView: TextView? = null

    fun isShowing(): Boolean = attachedView != null

    /**
     * 显示悬浮窄条。重复调用时先移除旧视图再重建（显示/隐藏由状态流驱动，频率极低）。
     * @param name AI 名称
     * @param avatar AI 头像
     * @param onExpand 点击条身回调（展开通话页）
     * @param onHangup 点击挂断按钮回调
     */
    fun show(
        name: String,
        avatar: Avatar,
        onExpand: () -> Unit,
        onHangup: () -> Unit
    ) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "show: overlay permission not granted, skip")
                return@post
            }
            removeInternal()
            try {
                val wm = windowManager
                    ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm == null) {
                    Log.w(TAG, "show: windowManager unavailable")
                    return@post
                }
                windowManager = wm
                val view = buildOverlayView(name, avatar, onExpand, onHangup)
                wm.addView(view, buildLayoutParams())
                attachedView = view
                Log.i(TAG, "show: overlay attached, name=$name")
            } catch (e: Exception) {
                // 权限刚被撤销 / view 已添加等异常路径：静默降级，不影响通话本身
                Log.w(TAG, "show: addView failed", e)
                attachedView = null
            }
        }
    }

    /** 刷新通话时长文本（每秒由 VoiceCallManager 的时长流驱动）。 */
    fun updateDuration(durationMs: Long) {
        mainHandler.post {
            durationText?.text = formatDuration(durationMs)
        }
    }

    /** 移除悬浮窗（幂等，未显示时调用为空操作）。 */
    fun hide() {
        mainHandler.post {
            removeInternal()
        }
    }

    private fun removeInternal() {
        val view = attachedView ?: return
        attachedView = null
        durationText = null
        avatarImageView = null
        avatarTextView = null
        try {
            windowManager?.removeView(view)
            Log.i(TAG, "hide: overlay removed")
        } catch (e: Exception) {
            // view 未附加 / window 已失效：忽略
            Log.w(TAG, "hide: removeView failed", e)
        }
    }

    // ===== 视图构建 =====

    private fun buildOverlayView(
        name: String,
        avatar: Avatar,
        onExpand: () -> Unit,
        onHangup: () -> Unit
    ): View {
        val palette = OverlayPalette.of(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(palette.container, radiusDp = 20f)
            elevation = dp(4).toFloat()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                }
            }
            setOnClickListener { onExpand() }
        }

        // 头像：Image/Resource 走 Coil，Emoji/Dummy 用文字（对齐 UIAvatar 的分发逻辑）
        when (avatar) {
            is Avatar.Image -> {
                val iv = circularImageView(palette.avatarBg)
                loadAvatarImage(iv, avatar.url)
                root.addView(iv)
                avatarImageView = iv
            }
            is Avatar.Resource -> {
                val iv = circularImageView(palette.avatarBg)
                loadAvatarImage(iv, avatar.id)
                root.addView(iv)
                avatarImageView = iv
            }
            is Avatar.Emoji -> {
                root.addView(circularTextView(palette, avatar.content, bold = false))
            }
            is Avatar.Dummy -> {
                val initial = name.trim().ifEmpty { "A" }.firstOrNull()?.uppercase() ?: "A"
                root.addView(circularTextView(palette, initial, bold = true))
            }
        }

        // 名称 + 时长列
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        }
        val nameView = TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(palette.title)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            maxWidth = dp(140)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val durationRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            val callIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_overlay_call)
                setColorFilter(palette.callGreen)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            }
            val durationView = TextView(context).apply {
                text = formatDuration(0L)
                textSize = 13f
                setTextColor(palette.subtitle)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(4) }
            }
            durationText = durationView
            addView(callIcon)
            addView(durationView)
        }
        textColumn.addView(nameView)
        textColumn.addView(durationRow)
        root.addView(textColumn)

        // 挂断按钮
        val hangupButton = FrameLayout(context).apply {
            val size = dp(40)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.hangupBg)
            }
            isClickable = true
            setOnClickListener { onHangup() }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_overlay_call_end)
                    setColorFilter(Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                }
            )
        }
        root.addView(hangupButton)
        return root
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx() + dp(4)
        }
    }

    private fun circularImageView(bgColor: Int): ImageView {
        return ImageView(context).apply {
            val size = dp(44)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
        }
    }

    private fun circularTextView(palette: OverlayPalette, text: String, bold: Boolean): TextView {
        return TextView(context).apply {
            val size = dp(44)
            layoutParams = LinearLayout.LayoutParams(size, size)
            gravity = Gravity.CENTER
            textSize = if (bold) 18f else 24f
            setTextColor(palette.title)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            this.text = text
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.avatarBg)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun loadAvatarImage(view: ImageView, data: Any) {
        val request = ImageRequest.Builder(context)
            .data(data)
            .target(coil3.target.ImageViewTarget(view))
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun roundedRect(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp,
                context.resources.displayMetrics
            )
            setColor(color)
        }
    }

    private fun statusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    /**
     * 悬浮窗配色（深浅色成对，按系统 night mode 选择）。
     * 语义：container=条身底色，title/subtitle=名称/时长文字，avatarBg=文字头像底，callGreen/hangupBg=状态色。
     */
    private data class OverlayPalette(
        val container: Int,
        val title: Int,
        val subtitle: Int,
        val avatarBg: Int,
        val callGreen: Int,
        val hangupBg: Int
    ) {
        companion object {
            // 状态色（通话绿 / 挂断红）与应用内 MiniCallWindow 保持一致，不随模式变化
            private const val CALL_GREEN = 0xFF4CAF50.toInt()
            private const val HANGUP_RED = 0xFFE53935.toInt()

            private val Dark = OverlayPalette(
                container = 0xE61E1E20.toInt(),
                title = 0xFFFFFFFF.toInt(),
                subtitle = 0xB3FFFFFF.toInt(),
                avatarBg = 0xFF3A3A3C.toInt(),
                callGreen = CALL_GREEN,
                hangupBg = HANGUP_RED
            )
            private val Light = OverlayPalette(
                container = 0xF2FFFFFF.toInt(),
                title = 0xFF1C1C1E.toInt(),
                subtitle = 0xFF6D6D72.toInt(),
                avatarBg = 0xFFE5E5EA.toInt(),
                callGreen = CALL_GREEN,
                hangupBg = HANGUP_RED
            )

            fun of(context: Context): OverlayPalette {
                val isNight = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                return if (isNight) Dark else Light
            }
        }
    }

    companion object {
        private const val TAG = "CallMiniOverlay"
    }
}

/**
 * 跳转到「显示在其他应用上层」授权页（定位到本应用）。
 * 部分 ROM 不支持带 package uri 的目标页，回退到通用悬浮窗设置列表。
 */
fun Context.launchOverlayPermissionSettings() {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(flags)
        )
    } catch (e: Exception) {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(flags))
        } catch (fallback: Exception) {
            Log.w("CallMiniOverlay", "launchOverlayPermissionSettings: no overlay settings activity", fallback)
        }
    }
}
