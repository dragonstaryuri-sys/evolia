package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.data.datastore.SettingsStore

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        // 迁移逻辑：为主智能体默认补全 TimeSense 工具
        // 让老用户的主智能体也默认开启时间观念，用户可自行在设置中关闭
        prefs[SettingsStore.ASSISTANTS] = prefs[SettingsStore.ASSISTANTS]?.let { json ->
            try {
                val assistants = JsonInstant.parseToJsonElement(json).jsonArray.map { element ->
                    val jsonObj = element.jsonObject.toMutableMap()
                    val isMain = jsonObj["isMain"]?.jsonPrimitive?.booleanOrNull ?: false

                    if (isMain) {
                        val localTools = jsonObj["localTools"]?.jsonArray?.toMutableList() ?: mutableListOf()

                        // 检查是否已存在 time_sense（兼容 JsonObject 与 JsonPrimitive 两种历史格式）
                        val hasTimeSense = localTools.any {
                            (it is JsonObject && it["type"]?.jsonPrimitive?.content == "time_sense") ||
                                (it is JsonPrimitive && it.content == "time_sense")
                        }
                        if (!hasTimeSense) {
                            localTools.add(buildJsonObject { put("type", "time_sense") })
                        }

                        jsonObj["localTools"] = JsonArray(localTools)
                    }
                    JsonObject(jsonObj)
                }
                JsonInstant.encodeToString(assistants)
            } catch (e: Exception) {
                json // 如果解析失败保持原样
            }
        } ?: "[]"

        // 更新版本到 4
        prefs[SettingsStore.VERSION] = 4

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
