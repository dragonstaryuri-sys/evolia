package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.common.JsonInstant

class PreferenceStoreV2Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 2
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        // 迁移逻辑：为主智能体默认补全一次 PeekUser 和 UpdateProfile 工具
        prefs[SettingsStore.ASSISTANTS] = prefs[SettingsStore.ASSISTANTS]?.let { json ->
            try {
                val assistants = JsonInstant.parseToJsonElement(json).jsonArray.map { element ->
                    val jsonObj = element.jsonObject.toMutableMap()
                    val isMain = jsonObj["isMain"]?.jsonPrimitive?.booleanOrNull ?: false

                    if (isMain) {
                        val localTools = jsonObj["localTools"]?.jsonArray?.toMutableList() ?: mutableListOf()

                        // 检查并添加 update_profile
                        val hasUpdateProfile = localTools.any {
                            it is JsonObject && it["type"]?.jsonPrimitive?.content == "update_profile" ||
                            it is JsonPrimitive && it.content == "update_profile"
                        }
                        if (!hasUpdateProfile) {
                            localTools.add(buildJsonObject { put("type", "update_profile") })
                        }

                        // 检查并添加 peek_user
                        val hasPeekUser = localTools.any {
                            it is JsonObject && it["type"]?.jsonPrimitive?.content == "peek_user" ||
                            it is JsonPrimitive && it.content == "peek_user"
                        }
                        if (!hasPeekUser) {
                            localTools.add(buildJsonObject { put("type", "peek_user") })
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

        // 更新版本到 2
        prefs[SettingsStore.VERSION] = 2

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
