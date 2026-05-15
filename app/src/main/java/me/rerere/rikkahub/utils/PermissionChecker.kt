package me.rerere.rikkahub.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.R

/**
 * Utility for checking and mapping feature permissions.
 * Used to ensure required permissions are requested after backup import.
 */
object PermissionChecker {

    /**
     * Permission requirements for different features
     */
    enum class FeaturePermission(val permissions: List<String>) {
        DEVICE_CONTROL(
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                add(Manifest.permission.CAMERA)
            }
        ),
        LOCATION(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ),
        CALENDAR(
            listOf(Manifest.permission.READ_CALENDAR)
        ),
        ALARM(
            buildList {
                add("com.android.alarm.permission.SET_ALARM")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.SCHEDULE_EXACT_ALARM)
                }
            }
        )
    }

    /**
     * Check which permissions are missing for the given assistants.
     * @return List of permission strings that need to be requested
     */
    fun getMissingPermissions(context: Context, assistants: List<Assistant>): List<String> {
        val requiredPermissions = mutableSetOf<String>()

        for (assistant in assistants) {
            // Check for Device Control tool
            if (assistant.localTools.contains(LocalToolOption.DeviceControl)) {
                requiredPermissions.addAll(FeaturePermission.DEVICE_CONTROL.permissions)
                requiredPermissions.addAll(FeaturePermission.ALARM.permissions)
            }

            // Check for Schedule Management tool
            if (assistant.localTools.contains(LocalToolOption.ScheduleManagement)) {
                requiredPermissions.addAll(FeaturePermission.CALENDAR.permissions)
            }

            // Check for Agent Automation (needs exact alarm)
            if (assistant.localTools.contains(LocalToolOption.AgentAutomation)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requiredPermissions.add(Manifest.permission.SCHEDULE_EXACT_ALARM)
                }
            }

            // Check for placeholders in all text fields that support placeholders
            val locationPlaceholderPattern = "\\{\\{?location\\}?\\}".toRegex(RegexOption.IGNORE_CASE)
            val calendarPlaceholderPattern = "\\{\\{?calendar\\}?\\}".toRegex(RegexOption.IGNORE_CASE)

            val textsToCheck = buildList {
                add(assistant.systemPrompt)
                add(assistant.messageTemplate)
                add(assistant.spontaneousPrompt)
                addAll(assistant.quickMessages.map { it.content })
                add(assistant.referenceVariables)
            }

            if (textsToCheck.any { locationPlaceholderPattern.containsMatchIn(it) }) {
                requiredPermissions.addAll(FeaturePermission.LOCATION.permissions)
            }

            if (textsToCheck.any { calendarPlaceholderPattern.containsMatchIn(it) }) {
                requiredPermissions.addAll(FeaturePermission.CALENDAR.permissions)
            }
        }

        // Filter to only permissions not yet granted
        return requiredPermissions.filter { permission ->
            !isPermissionGranted(context, permission)
        }
    }

    /**
     * Check if a specific permission is granted.
     */
    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return when (permission) {
            Manifest.permission.SCHEDULE_EXACT_ALARM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    alarmManager.canScheduleExactAlarms()
                } else true
            }
            "com.android.alarm.permission.SET_ALARM" -> true // Normal permission, always granted if declared
            else -> {
                ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * Check if location permission is granted (either coarse or fine).
     */
    fun hasLocationPermission(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
               isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Get human-readable descriptions for a list of permissions.
     */
    fun getPermissionDescriptions(context: Context, permissions: List<String>): List<String> {
        return permissions.mapNotNull { permission ->
            when (permission) {
                Manifest.permission.POST_NOTIFICATIONS -> context.getString(R.string.backup_permission_notification)
                Manifest.permission.ACCESS_COARSE_LOCATION -> context.getString(R.string.backup_permission_location_coarse)
                Manifest.permission.ACCESS_FINE_LOCATION -> context.getString(R.string.backup_permission_location_fine)
                Manifest.permission.CAMERA -> context.getString(R.string.backup_permission_camera)
                Manifest.permission.READ_CALENDAR -> context.getString(R.string.backup_permission_calendar)
                "com.android.alarm.permission.SET_ALARM" -> context.getString(R.string.backup_permission_alarm)
                Manifest.permission.SCHEDULE_EXACT_ALARM -> context.getString(R.string.backup_permission_exact_alarm)
                else -> null
            }
        }.distinct()
    }
}
