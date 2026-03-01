package me.rerere.rikkahub.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences-based storage for widget configuration.
 * Uses synchronous SharedPreferences for reliability.
 */
object WidgetPrefs {
    private const val PREFS_NAME = "assistant_widget_prefs"
    
    private fun prefs(context: Context): SharedPreferences {
        // Use applicationContext to ensure the same SharedPreferences instance
        // is accessed from both the config activity and the widget
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveConfig(
        context: Context,
        widgetId: Int,
        assistantId: String,
        assistantName: String,
        avatarType: String,  // "dummy", "emoji", "image", "resource"
        avatarData: String   // emoji content, image URL, or resource ID as string
    ): Boolean {
        // Use commit() instead of apply() to ensure synchronous write
        // This is critical because the widget updates immediately after
        return prefs(context).edit()
            .putString("widget_${widgetId}_id", assistantId)
            .putString("widget_${widgetId}_name", assistantName)
            .putString("widget_${widgetId}_avatar_type", avatarType)
            .putString("widget_${widgetId}_avatar_data", avatarData)
            .commit()
    }
    
    fun getAssistantId(context: Context, widgetId: Int): String? {
        return prefs(context).getString("widget_${widgetId}_id", null)
    }
    
    fun getAssistantName(context: Context, widgetId: Int): String {
        return prefs(context).getString("widget_${widgetId}_name", null) ?: "Assistant"
    }
    
    fun getAvatarType(context: Context, widgetId: Int): String {
        return prefs(context).getString("widget_${widgetId}_avatar_type", null) ?: "dummy"
    }
    
    fun getAvatarData(context: Context, widgetId: Int): String {
        return prefs(context).getString("widget_${widgetId}_avatar_data", null) ?: ""
    }
    
    fun deleteConfig(context: Context, widgetId: Int) {
        prefs(context).edit()
            .remove("widget_${widgetId}_id")
            .remove("widget_${widgetId}_name")
            .remove("widget_${widgetId}_avatar_type")
            .remove("widget_${widgetId}_avatar_data")
            .apply()
    }
}
