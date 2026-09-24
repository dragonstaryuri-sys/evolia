package me.rerere.rikkahub.applock

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import java.io.File

/**
 * 应用锁定全屏遮罩 Activity。
 *
 * 当用户打开被锁定的应用时，由 EvoliaMonitorService 拉起此 Activity，
 * 覆盖在目标应用之上，使用户看不到应用内容。
 *
 * 返回键不会关闭遮罩，而是跳回桌面，防止用户绕过锁定查看应用。
 */
class AppLockOverlayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏、隐藏状态栏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(0xFFB5C9B0.toInt())
        }

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        if (!imagePath.isNullOrBlank()) {
            val file = File(imagePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                imageView.setImageBitmap(bitmap)
            }
        }

        setContentView(imageView)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 更新遮罩图片（快速切换多个被锁应用时）：重建内容视图
        val newImage = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(0xFFB5C9B0.toInt())
            if (!newImage.isNullOrBlank()) {
                val file = File(newImage)
                if (file.exists()) {
                    setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                }
            }
        }
        setContentView(imageView)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 不关闭遮罩，跳回桌面，防止用户绕过锁定
        goHome()
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
        finish()
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"

        fun createIntent(context: android.content.Context, imagePath: String?): Intent {
            return Intent(context, AppLockOverlayActivity::class.java).apply {
                // 仅用 NEW_TASK：让遮罩 Activity 在独立任务中启动并覆盖在前台应用之上
                // 不要用 CLEAR_TOP，否则会切回 Evolia 的任务栈
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_IMAGE_PATH, imagePath)
            }
        }
    }
}
