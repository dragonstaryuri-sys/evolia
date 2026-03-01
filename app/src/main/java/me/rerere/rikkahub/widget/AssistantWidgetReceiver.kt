package me.rerere.rikkahub.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AssistantWidgetReceiver"

/**
 * Receiver for the Assistant Widget.
 * This is the entry point for the widget system.
 */
class AssistantWidgetReceiver : GlanceAppWidgetReceiver() {
    
    override val glanceAppWidget: GlanceAppWidget = AssistantWidget()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for widgets: ${appWidgetIds.joinToString()}")
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        
        // Explicitly update each widget
        scope.launch {
            val glanceManager = GlanceAppWidgetManager(context)
            appWidgetIds.forEach { appWidgetId ->
                try {
                    Log.d(TAG, "Updating widget $appWidgetId")
                    val glanceId = glanceManager.getGlanceIdBy(appWidgetId)
                    glanceAppWidget.update(context, glanceId)
                    Log.d(TAG, "Widget $appWidgetId updated")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating widget $appWidgetId", e)
                }
            }
        }
    }
    
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        Log.d(TAG, "onDeleted called for widgets: ${appWidgetIds.joinToString()}")
        // Clean up widget data when widgets are deleted
        scope.launch {
            appWidgetIds.forEach { widgetId ->
                WidgetPrefs.deleteConfig(context, widgetId)
            }
        }
    }
}
