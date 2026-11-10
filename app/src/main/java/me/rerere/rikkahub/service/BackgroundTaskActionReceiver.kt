package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 后台任务前台通知的「取消任务」action 接收器。
 *
 * 不使用 WorkManager 自带的 createCancelPendingIntent：仅取消 Work 不会更新任务状态，
 * 排队中任务会残留为 queued。此处经 Manager.cancelTask 原子更新状态 + 取消 Work + 清理产物。
 */
class BackgroundTaskActionReceiver : BroadcastReceiver(), KoinComponent {

    private val backgroundTaskManager: BackgroundTaskManager by inject()
    private val appScope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_TASK) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        appScope.launch(Dispatchers.IO) {
            try {
                val outcome = backgroundTaskManager.cancelTask(taskId)
                Log.i(TAG, "notification cancel: task=$taskId outcome=$outcome")
            } catch (e: Exception) {
                Log.e(TAG, "notification cancel failed: task=$taskId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BackgroundTaskAction"
        const val ACTION_CANCEL_TASK = "me.rerere.rikkahub.action.CANCEL_BACKGROUND_TASK"
        const val EXTRA_TASK_ID = "taskId"
    }
}
