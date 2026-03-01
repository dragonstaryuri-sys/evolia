package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import me.rerere.rikkahub.RouteActivity

private const val TAG = "ShortcutHandlerActivity"

/**
 * Handles shortcut and widget intents.
 * Routes assistant shortcuts to the main app with the assistant ID.
 */
class ShortcutHandlerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "onCreate called")
        Log.d(TAG, "Intent: $intent")
        Log.d(TAG, "Intent action: ${intent?.action}")
        Log.d(TAG, "Intent data: ${intent?.data}")
        
        // Handle assistant shortcut
        val data = intent?.data
        if (data?.scheme == "lastchat" && data.host == "assistant") {
            val assistantId = data.pathSegments.firstOrNull()
            Log.d(TAG, "Parsed assistantId: $assistantId")
            if (!assistantId.isNullOrBlank()) {
                val routeIntent = Intent(this, RouteActivity::class.java).apply {
                    putExtra("assistantId", assistantId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                Log.d(TAG, "Starting RouteActivity with assistantId: $assistantId")
                startActivity(routeIntent)
            } else {
                Log.w(TAG, "assistantId is null or blank")
            }
        } else {
            Log.w(TAG, "Unexpected URI scheme: ${data?.scheme}, host: ${data?.host}")
        }
        
        // Always finish this activity
        Log.d(TAG, "Finishing activity")
        finish()
    }
}
