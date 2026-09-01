package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore

class PreferenceStoreV3Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 3
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        // 迁移逻辑：将旧的 notificationFrequencyHours (小时) 转换为
        // 新的 notificationFrequencyMinutes (分钟)，最低 15 分钟
        prefs[SettingsStore.ASSISTANTS] = prefs[SettingsStore.ASSISTANTS]?.let { json ->
            try {
                val assistants: List<JsonElement> =
                    JsonInstant.parseToJsonElement(json).jsonArray.map { element: JsonElement ->
                        val jsonObj = element.jsonObject.toMutableMap()

                        val oldHours: Int? = jsonObj["notificationFrequencyHours"]
                            ?.jsonPrimitive
                            ?.intOrNull

                        val newMinutesExists = jsonObj.containsKey("notificationFrequencyMinutes")

                        if (oldHours != null && !newMinutesExists) {
                            val minutes = (oldHours * 60).coerceAtLeast(15)
                            jsonObj["notificationFrequencyMinutes"] = JsonPrimitive(minutes)
                            // 移除旧字段，避免冗余
                            jsonObj.remove("notificationFrequencyHours")
                        }

                        JsonObject(jsonObj)
                    }
                JsonInstant.encodeToString(assistants)
            } catch (e: Exception) {
                json // 如果解析失败保持原样
            }
        } ?: "[]"

        // 更新版本到 3
        prefs[SettingsStore.VERSION] = 3

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
