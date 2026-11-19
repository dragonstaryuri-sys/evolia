package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import me.rerere.rikkahub.core.data.db.entity.RadioOperatorType
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.data.radio.EpisodeQuery
import me.rerere.rikkahub.data.radio.EpisodeSort
import me.rerere.rikkahub.data.radio.RadioEpisodeRules
import me.rerere.rikkahub.data.radio.RadioService
import me.rerere.rikkahub.data.radio.RadioServiceException
import kotlin.uuid.Uuid

/**
 * Radio LocalTools 按需加载（复刻 MCP 双固定工具模式，见 AGENTS.md 12.4）。
 *
 * 上下文只常驻「工具目录」（13 个 Radio 工具名 + 描述，见 [getRadioCatalogSystemPrompt]），
 * 完整参数 Schema 不常驻；Agent 先通过 [TOOL_GET_RADIO_TOOL_SCHEMA] 查询参数定义，
 * 再通过 [TOOL_CALL_RADIO_TOOL] 执行（执行前按真实 Schema 校验参数）。
 */
const val TOOL_GET_RADIO_TOOL_SCHEMA = "get_radio_tool_schema"
const val TOOL_CALL_RADIO_TOOL = "call_radio_tool"

/**
 * 一次工具调用的上下文：调用 Agent（作为 creator / operator）与其语音配置。
 */
data class RadioToolContext(
    val assistantId: Uuid,
    val assistantProviderId: Uuid?,
    /** Agent 声音配置副本 JSON（TtsVoiceResolver 合并全局 Provider 使用）。 */
    val assistantVoiceSettingJson: String?,
    /** share_episode 自动播放用；null 时不支持自动播放。 */
    val mediaPlaybackController: me.rerere.rikkahub.service.media.MediaPlaybackController? = null,
    /** 当前会话 ID（后台任务回传关联；null 表示无会话上下文）。 */
    val conversationId: Uuid? = null,
    /** 来源轮次锚点消息 ID（仅作任务来源关联）。 */
    val sourceTurnId: String? = null,
)

/**
 * Radio 工具定义：schema 用于目录/查询返回与执行前校验，execute 内部只调 RadioService。
 */
data class RadioToolDef(
    val name: String,
    val description: String,
    val schema: InputSchema.Obj,
    val execute: suspend (args: JsonObject, ctx: RadioToolContext) -> JsonObject
)

/** 目录条目（纯数据，供 buildRadioCatalogPrompt 单测）。 */
data class RadioCatalogEntry(val name: String, val description: String)

/**
 * 构建固定 Radio 工具（目录纯函数部分见 [buildRadioCatalogPrompt]）。
 */
fun buildRadioAgentTools(
    radioService: RadioService,
    assistant: Assistant,
    mediaPlaybackController: me.rerere.rikkahub.service.media.MediaPlaybackController? = null,
    conversationId: Uuid? = null,
    sourceTurnId: String? = null,
    backgroundTaskManager: me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager? = null,
): List<Tool> {
    val ctx = RadioToolContext(
        assistantId = assistant.id,
        assistantProviderId = assistant.voiceProviderId,
        assistantVoiceSettingJson = assistant.voiceProviderId?.let { pid -> assistant.voiceSettings[pid.toString()] },
        mediaPlaybackController = mediaPlaybackController,
        conversationId = conversationId,
        sourceTurnId = sourceTurnId,
    )
    val defs = buildRadioToolDefs(radioService, backgroundTaskManager, ctx)
    if (defs.isEmpty()) return emptyList()
    return listOf(
        buildGetRadioToolSchemaTool(defs),
        buildCallRadioToolTool(defs, ctx)
    )
}

/** 目录注入系统提示（由 Tool.systemPrompt 调用）。 */
fun getRadioCatalogSystemPrompt(radioService: RadioService): String {
    // 仅提取 name/description 构建目录，不执行工具；使用中性上下文占位
    val neutralCtx = RadioToolContext(
        assistantId = Uuid.random(),
        assistantProviderId = null,
        assistantVoiceSettingJson = null
    )
    return buildRadioCatalogPrompt(buildRadioToolDefs(radioService, backgroundTaskManager = null, ctx = neutralCtx).map {
        RadioCatalogEntry(it.name, it.description)
    })
}

/**
 * 目录构建纯函数（单测固化）：列出全部 Radio 工具名 + 简短用途，
 * 并说明两段式调用流程（先查 Schema 再执行）。
 */
internal fun buildRadioCatalogPrompt(entries: List<RadioCatalogEntry>): String {
    if (entries.isEmpty()) return ""
    val builder = StringBuilder()
    builder.appendLine("## 可用工具 · 小机电台")
    builder.appendLine(
        "以下工具的完整参数定义(Schema)不会随请求常驻。调用前必须先执行 " +
            "`get_radio_tool_schema`(传入 tool_name) 获取参数定义，再通过 `call_radio_tool` 执行。"
    )
    entries.forEach { entry ->
        builder.appendLine("- `${entry.name}`: ${entry.description.replace("\n", " ")}")
    }
    return builder.toString().trimEnd()
}

// ====================================================================
// 固定工具一：get_radio_tool_schema
// ====================================================================

private fun buildGetRadioToolSchemaTool(defs: List<RadioToolDef>): Tool {
    return Tool(
        name = TOOL_GET_RADIO_TOOL_SCHEMA,
        description = "查询指定小机电台工具的完整定义(用途 + 参数 Schema)。在调用任何电台工具之前，" +
            "先用本工具获取目标工具的参数定义，再按 Schema 通过 call_radio_tool 执行。不执行真实电台操作。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tool_name", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("目标电台工具名称，来自工具目录"))
                    })
                },
                required = listOf("tool_name")
            )
        },
        systemPrompt = { _, _ -> buildRadioCatalogPrompt(defs.map { RadioCatalogEntry(it.name, it.description) }) },
        execute = { args ->
            val argsObj = args as? JsonObject ?: JsonObject(emptyMap())
            val toolName = argsObj.readString("tool_name")
            if (toolName.isNullOrBlank()) {
                buildJsonObject { put("error", JsonPrimitive("tool_name is required")) }
            } else {
                val def = defs.find { it.name == toolName }
                if (def == null) {
                    buildJsonObject {
                        put(
                            "error",
                            JsonPrimitive("Radio tool not found: tool_name=$toolName. Check the tool catalog for valid names.")
                        )
                    }
                } else {
                    buildJsonObject {
                        put("tool_name", JsonPrimitive(def.name))
                        put("description", JsonPrimitive(def.description))
                        put("input_schema", def.schema.toJsonElement())
                        put("usage_notes", JsonPrimitive(buildRadioToolUsageNotes(def.name)))
                    }
                }
            }
        }
    )
}

// ====================================================================
// 固定工具二：call_radio_tool
// ====================================================================

private fun buildCallRadioToolTool(defs: List<RadioToolDef>, ctx: RadioToolContext): Tool {
    return Tool(
        name = TOOL_CALL_RADIO_TOOL,
        description = "执行指定的小机电台工具。必须先通过 get_radio_tool_schema 获取参数定义，" +
            "然后按 Schema 组织 arguments 传入；参数会按工具真实 Schema 校验，非法参数会被拒绝。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tool_name", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("要执行的电台工具名称"))
                    })
                    put("arguments", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("description", JsonPrimitive("目标工具的参数对象，结构以 get_radio_tool_schema 返回的 input_schema 为准"))
                    })
                },
                required = listOf("tool_name")
            )
        },
        execute = { args ->
            val argsObj = args as? JsonObject ?: JsonObject(emptyMap())
            val toolName = argsObj.readString("tool_name")
            if (toolName.isNullOrBlank()) {
                return@Tool buildJsonObject { put("error", JsonPrimitive("tool_name is required")) }
            }
            val def = defs.find { it.name == toolName }
            if (def == null) {
                return@Tool buildJsonObject {
                    put("error", JsonPrimitive("Radio tool not found: tool_name=$toolName"))
                }
            }
            val toolArguments = parseRadioCallArguments(argsObj["arguments"])
            if (toolArguments.containsKey("error")) {
                return@Tool toolArguments
            }
            val validationErrors = validateAgainstSchema(toolArguments, def.schema)
            if (validationErrors.isNotEmpty()) {
                return@Tool buildJsonObject {
                    put(
                        "error",
                        JsonPrimitive("参数校验失败: ${validationErrors.joinToString("; ")}。请先调用 get_radio_tool_schema 查看参数定义。")
                    )
                }
            }
            try {
                def.execute(toolArguments, ctx).truncateLargeJsonText()
            } catch (e: RadioServiceException) {
                buildJsonObject { put("error", JsonPrimitive(e.message ?: "电台操作失败")) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                buildJsonObject { put("error", JsonPrimitive("电台操作执行失败: ${e.message ?: e.javaClass.simpleName}")) }
            }
        }
    )
}

/**
 * 解析 call_radio_tool 的 arguments 参数（与 MCP 同约定：标准 object / 字符串化 JSON / 缺失）。
 */
internal fun parseRadioCallArguments(raw: JsonElement?): JsonObject {
    return when (raw) {
        null -> JsonObject(emptyMap())
        is JsonObject -> raw
        is JsonPrimitive -> {
            val argumentsText = raw.content.takeIf { it.isNotBlank() } ?: "{}"
            runCatching {
                me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(argumentsText).jsonObject
            }.getOrElse {
                buildJsonObject { put("error", JsonPrimitive("arguments must be a JSON object, got invalid input")) }
            }
        }

        else -> buildJsonObject { put("error", JsonPrimitive("arguments must be a JSON object")) }
    }
}

/**
 * 按工具真实 Schema 做基础校验（纯函数，单测固化）：
 * - required 参数必须存在；
 * - 已声明类型的参数按基础 JSON 类型核对（string/number/integer/boolean/object/array）；
 * - 未声明的属性不拒绝（与 MCP 行为一致，宽松透传）。
 *
 * @return 错误列表；为空表示校验通过
 */
internal fun validateAgainstSchema(args: JsonObject, schema: InputSchema.Obj): List<String> {
    val errors = mutableListOf<String>()
    schema.required?.forEach { key ->
        if (args[key] == null) {
            errors.add("缺少必填参数 `$key`")
        }
    }
    schema.properties.forEach { (key, propSchema) ->
        val value = args[key] ?: return@forEach
        val declaredType = (propSchema as? JsonObject)?.get("type") as? JsonPrimitive ?: return@forEach
        val typeError = when (declaredType.content) {
            "string" -> if (value is JsonPrimitive && value.isString) null else "应为字符串"
            "number", "integer" -> if (value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null) {
                null
            } else "应为数字"

            "boolean" -> if (value is JsonPrimitive && !value.isString &&
                (value.content == "true" || value.content == "false")
            ) null else "应为布尔值"
            "object" -> if (value is JsonObject) null else "应为对象"
            "array" -> if (value is JsonArray) null else "应为数组"
            else -> null
        }
        if (typeError != null) {
            errors.add("参数 `$key` $typeError")
        }
    }
    return errors
}

// ====================================================================
// 工具定义注册表（13 个，全部经 RadioService 执行）
// ====================================================================

fun buildRadioToolDefs(
    service: RadioService,
    backgroundTaskManager: me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager?,
    ctx: RadioToolContext
): List<RadioToolDef> {
    return listOf(
        defGetChannels(service),
        defManageChannel(service),
        defSaveEpisode(service),
        defUpdateEpisodeTitle(service),
        defQueryEpisodes(service),
        defGetEpisodeContent(service),
        defDeleteEpisode(service),
        defGenerateEpisodeAudio(service, backgroundTaskManager, ctx),
        defRestoreEpisodeAudio(service),
        defPublishEpisode(service),
        defUpdateEpisodeVisibility(service),
        defShareEpisode(service)
    )
}

/** 特定工具的调用约束说明（get_radio_tool_schema 随 Schema 一并返回）。 */
private fun buildRadioToolUsageNotes(toolName: String): String = when (toolName) {
    "manage_channel" -> "创建栏目需要用户明确授权；删除栏目时若栏目下仍有节目，必须传 delete_episodes=true 或先处理节目。"
    "save_episode" -> "创建新节目时不传 episode_id；更新未发布节目文字稿时传 episode_id。保存前应先在对话中生成文字稿并与用户确认；保存成功后才能生成音频。"
    "update_episode" -> "只允许修改未发布节目；文字稿变化会立即删除该节目的当前音频与备用音频。"
    "delete_episode" -> "用户明确要求删除时传 initiated_by_user=true，无需反复确认；你自主删除时必须给出明确的删除依据(reason)，系统会记录删除审计。"
    "generate_episode_audio" -> "必须基于数据库中已保存的文字稿生成，不能使用对话中尚未保存的内容。" +
        "本工具为异步后台任务：立即返回 task_id 后继续对话，不要等待或轮询；进度可用 get_background_task 查询。"
    "publish_episode" -> "发布前必须有有效音频；visibility 必填（public/private，草稿不能单独改可见性，发布时一并指定）。"
    "update_episode_visibility" -> "仅已发布节目可修改可见性；发布时未指定可见性的旧节目默认 public。"
    else -> ""
}

private fun defGetChannels(service: RadioService): RadioToolDef = RadioToolDef(
    name = "get_channels",
    description = "查询用户的电台栏目列表（栏目 ID、名称、介绍、创作提示词等）。",
    schema = schemaOf(),
    execute = { _, _ ->
        val channels = service.getAllChannelsWithCounts()
        buildJsonObject {
            put("count", JsonPrimitive(channels.size))
            put("channels", JsonArray(channels.map { (channel, episodeCount) ->
                buildJsonObject {
                    put("channel_id", JsonPrimitive(channel.id))
                    put("name", JsonPrimitive(channel.name))
                    put("description", JsonPrimitive(channel.description))
                    put("system_prompt", JsonPrimitive(channel.systemPrompt))
                    put("episode_count", JsonPrimitive(episodeCount))
                    put(
                        "creator_name",
                        JsonPrimitive(service.resolveCreatorName(channel.creatorId))
                    )
                }
            }))
        }
    }
)

private fun defManageChannel(service: RadioService): RadioToolDef = RadioToolDef(
    name = "manage_channel",
    description = "电台栏目管理：创建 / 修改(名称、介绍、创作提示词) / 删除栏目。" +
        "创建栏目需要用户明确授权；删除栏目前必须处理关联节目。",
    schema = schemaOf(
        "action" to prop("string", "操作：create / update / delete"),
        "channel_id" to prop("string", "update/delete 必填：栏目 ID"),
        "name" to prop("string", "create 必填 / update 可选：栏目名称"),
        "description" to prop("string", "栏目介绍"),
        "system_prompt" to prop("string", "栏目创作提示词（指导该栏目下的节目创作风格）"),
        "delete_episodes" to prop("boolean", "delete 时可选：true 表示连同栏目下全部节目一并删除（逐期记录删除审计）")
    ),
    execute = { args, ctx ->
        val action = args.readString("action") ?: ""
        when (action) {
            "create" -> {
                val name = args.readString("name")
                if (name.isNullOrBlank()) {
                    buildJsonObject { put("error", JsonPrimitive("创建栏目必须提供 name")) }
                } else {
                    val channel = service.createChannel(
                        name = name,
                        description = args.readString("description").orEmpty(),
                        systemPrompt = args.readString("system_prompt").orEmpty(),
                        creatorId = ctx.assistantId.toString()
                    )
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("action", JsonPrimitive("created"))
                        put("channel_id", JsonPrimitive(channel.id))
                        put("name", JsonPrimitive(channel.name))
                    }
                }
            }

            "update" -> {
                val channelId = args.readString("channel_id")
                if (channelId.isNullOrBlank()) {
                    buildJsonObject { put("error", JsonPrimitive("更新栏目必须提供 channel_id")) }
                } else {
                    val channel = service.updateChannel(
                        channelId = channelId,
                        name = args.readString("name"),
                        description = args.readString("description"),
                        systemPrompt = args.readString("system_prompt")
                    )
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("action", JsonPrimitive("updated"))
                        put("channel_id", JsonPrimitive(channel.id))
                        put("name", JsonPrimitive(channel.name))
                    }
                }
            }

            "delete" -> {
                val channelId = args.readString("channel_id")
                if (channelId.isNullOrBlank()) {
                    buildJsonObject { put("error", JsonPrimitive("删除栏目必须提供 channel_id")) }
                } else {
                    val deletedEpisodes = service.deleteChannel(
                        channelId = channelId,
                        operatorId = ctx.assistantId.toString(),
                        operatorType = RadioOperatorType.AGENT,
                        deleteEpisodes = args.readBoolean("delete_episodes") ?: false
                    )
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("action", JsonPrimitive("deleted"))
                        put("channel_id", JsonPrimitive(channelId))
                        put("deleted_episodes", JsonPrimitive(deletedEpisodes))
                    }
                }
            }

            else -> buildJsonObject { put("error", JsonPrimitive("action 必须是 create / update / delete")) }
        }
    }
)

private fun defSaveEpisode(service: RadioService): RadioToolDef = RadioToolDef(
    name = "save_episode",
    description = "保存节目文字稿到数据库（保存后才能生成音频）。必须提供栏目与标题；" +
        "传 episode_id 时为更新已有节目（已发布节目只允许改标题，改文字稿会被拒绝）。",
    schema = schemaOf(
        "channel_id" to prop("string", "所属栏目 ID"),
        "title" to prop("string", "节目标题（必填）"),
        "content" to prop("string", "完整节目文字稿"),
        "episode_id" to prop("string", "可选：更新已有节目时传入")
    ),
    execute = { args, ctx ->
        val channelId = args.readString("channel_id")
        val title = args.readString("title")
        if (channelId.isNullOrBlank() || title.isNullOrBlank()) {
            buildJsonObject { put("error", JsonPrimitive("channel_id 与 title 为必填参数")) }
        } else {
            val episode = service.saveEpisode(
                channelId = channelId,
                title = title,
                content = args.readString("content").orEmpty(),
                creatorAgentId = ctx.assistantId.toString(),
                episodeId = args.readString("episode_id")
            )
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("episode_id", JsonPrimitive(episode.id))
                put("title", JsonPrimitive(episode.title))
                put("status", JsonPrimitive(episode.status))
                put("visibility", JsonPrimitive(episode.visibility.lowercase()))
                put(
                    "message",
                    JsonPrimitive(
                        "草稿已成功保存（${episode.status}）。请和用户确认是否需要修改，需要为文稿生成音频成功后才能调用 publish_episode 发布节目。"
                    )
                )
            }
        }
    }
)

private fun defUpdateEpisodeTitle(service: RadioService): RadioToolDef = RadioToolDef(
    name = "update_episode_title",
    description = "修改节目标题（草稿与已发布节目均允许）；播放器与通知栏元数据会同步刷新。",
    schema = schemaOf(
        "episode_id" to prop("string", "节目 ID"),
        "title" to prop("string", "新标题")
    ),
    execute = { args, _ ->
        val episodeId = args.readString("episode_id")
        val title = args.readString("title")
        if (episodeId.isNullOrBlank() || title.isNullOrBlank()) {
            buildJsonObject { put("error", JsonPrimitive("episode_id 与 title 为必填参数")) }
        } else {
            val episode = service.updateEpisodeTitle(episodeId, title)
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("episode_id", JsonPrimitive(episode.id))
                put("title", JsonPrimitive(episode.title))
                put("status", JsonPrimitive(episode.status))
            }
        }
    }
)

private fun defQueryEpisodes(service: RadioService): RadioToolDef = RadioToolDef(
    name = "query_episodes",
    description = "查询你可见的节目列表（默认只返回元数据，不含文字稿正文；需要正文时调用 get_episode_content）。" +
        "支持栏目 / 创作者名字 / 状态 / 标题搜索 / 排序 / 分页。创作者按名字（creator_name）筛选，" +
        "你不知道任何 Agent 的 ID，请直接传名字；同名 Agent 的节目会全部返回。" +
        "可见性为 private 的节目只有创作该节目的 Agent 能查到，public 节目所有 Agent 都能查询。",
    schema = schemaOf(
        "channel_id" to prop("string", "按栏目筛选"),
        "creator_name" to prop("string", "按创作 Agent 名字筛选（如查询你自己创建的节目就传你的名字；同名 Agent 全部命中）"),
        "status" to prop("string", "按状态筛选：DRAFT / PUBLISHED；不传时默认 PUBLISHED（查询草稿请显式传 DRAFT）"),
        "title_query" to prop("string", "标题关键词搜索"),
        "sort_by" to prop("string", "排序：created_desc(默认) / created_asc / published_desc / title_asc"),
        "page" to prop("integer", "页码，从 0 开始"),
        "page_size" to prop("integer", "每页数量，默认 20，最大 100")
    ),
    execute = { args, ctx ->
        val statusRaw = args.readString("status")
        val statusInvalid = statusRaw != null &&
            statusRaw != RadioEpisodeStatus.DRAFT && statusRaw != RadioEpisodeStatus.PUBLISHED
        if (statusInvalid) {
            buildJsonObject { put("error", JsonPrimitive("status 只支持 DRAFT / PUBLISHED")) }
        } else {
            val sortBy = when (args.readString("sort_by")) {
                "created_asc" -> EpisodeSort.CREATED_ASC
                "published_desc" -> EpisodeSort.PUBLISHED_DESC
                "title_asc" -> EpisodeSort.TITLE_ASC
                else -> EpisodeSort.CREATED_DESC
            }
            val creatorName = args.readString("creator_name")
            // 未传 status 时默认只查已发布节目（查草稿需显式传 DRAFT）
            val effectiveStatus = statusRaw ?: RadioEpisodeStatus.PUBLISHED
            val query = EpisodeQuery(
                channelId = args.readString("channel_id"),
                status = effectiveStatus,
                titleQuery = args.readString("title_query"),
                sortBy = sortBy,
                page = args.readInt("page") ?: 0,
                pageSize = args.readInt("page_size") ?: 20
            )
            val viewerAgentId = ctx.assistantId.toString()
            val episodes = service.queryEpisodesByCreatorName(query, creatorName, viewerAgentId)
            buildJsonObject {
                put("count", JsonPrimitive(episodes.size))
                val noNameMatch = episodes.isEmpty() && !creatorName.isNullOrBlank()
                if (noNameMatch) {
                    put(
                        "message",
                        JsonPrimitive("没有名字匹配「$creatorName」的创作助手，或其节目均不可见（private 仅创作 Agent 本人可查询）。")
                    )
                }
                put("episodes", JsonArray(episodes.map { episode ->
                    val hasAudio = service.getCurrentAudioAsset(episode.id) != null
                    val creatorDisplayName = service.resolveCreatorName(episode.creatorAgentId)
                    buildJsonObject {
                        put("episode_id", JsonPrimitive(episode.id))
                        put("title", JsonPrimitive(episode.title))
                        put("channel_id", JsonPrimitive(episode.channelId))
                        put("status", JsonPrimitive(episode.status))
                        put("visibility", JsonPrimitive(episode.visibility.lowercase()))
                        put("creator_name", JsonPrimitive(creatorDisplayName))
                        put("has_audio", JsonPrimitive(hasAudio))
                        put("created_at", JsonPrimitive(episode.createdAt))
                        put("published_at", JsonPrimitive(episode.publishedAt))
                    }
                }))
            }
        }
    }
)

private fun defGetEpisodeContent(service: RadioService): RadioToolDef = RadioToolDef(
    name = "get_episode_content",
    description = "获取节目完整文字稿正文。可见性为 private 的节目该节目创作者能读取。",
    schema = schemaOf(
        "episode_id" to prop("string", "节目 ID")
    ),
    execute = { args, ctx ->
        val episodeId = args.readString("episode_id")
        if (episodeId.isNullOrBlank()) {
            buildJsonObject { put("error", JsonPrimitive("episode_id 为必填参数")) }
        } else {
            val episode = service.getEpisodeForAgent(episodeId, ctx.assistantId.toString())
            buildJsonObject {
                put("episode_id", JsonPrimitive(episode.id))
                put("title", JsonPrimitive(episode.title))
                put("status", JsonPrimitive(episode.status))
                put("visibility", JsonPrimitive(episode.visibility.lowercase()))
                put("content", JsonPrimitive(episode.content))
            }
        }
    }
)

private fun defDeleteEpisode(service: RadioService): RadioToolDef = RadioToolDef(
    name = "delete_episode",
    description = "删除节目（连同其文字稿与全部音频），并写入删除审计。用户明确要求删除时直接执行；" +
        "你自主删除时必须提供明确的删除依据。",
    schema = schemaOf(
        "episode_id" to prop("string", "节目 ID"),
        "initiated_by_user" to prop("boolean", "true = 用户明确提出删除该节目；false = 你自主决定删除"),
        "reason" to prop("string", "删除依据；initiated_by_user=false 时必填")
    ),
    execute = { args, ctx ->
        val episodeId = args.readString("episode_id")
        if (episodeId.isNullOrBlank()) {
            buildJsonObject { put("error", JsonPrimitive("episode_id 为必填参数")) }
        } else {
            val initiatedByUser = args.readBoolean("initiated_by_user") ?: false
            val reason = args.readString("reason").orEmpty()
            if (!initiatedByUser && reason.isBlank()) {
                buildJsonObject {
                    put(
                        "error",
                        JsonPrimitive("自主删除节目必须提供明确的删除依据(reason)。若用户已明确要求删除，请传 initiated_by_user=true。")
                    )
                }
            } else {
                service.deleteEpisode(
                    episodeId = episodeId,
                    operatorId = ctx.assistantId.toString(),
                    operatorType = RadioOperatorType.AGENT,
                    reason = reason,
                    requestedByUser = initiatedByUser
                )
                buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("episode_id", JsonPrimitive(episodeId))
                    put("message", JsonPrimitive("节目已删除，删除事件已记录审计。"))
                }
            }
        }
    }
)

private fun defGenerateEpisodeAudio(
    service: RadioService,
    backgroundTaskManager: me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager?,
    ctx: RadioToolContext
): RadioToolDef = RadioToolDef(
    name = "generate_episode_audio",
    description = "为节目生成音频（异步后台任务）。调用后立即返回 task_id，音频在后台生成，" +
        "期间可以继续与用户对话，不要等待或轮询；完成后按 completion_policy 回传结果或通知用户，" +
        "也可随时用 get_background_task 查询进度。" +
        "仅在用户确认稿子无需修改后才调用。同一文字稿重新生成时，旧音频自动转为备用音频（3 天有效）。",
    schema = schemaOf(
        "episode_id" to prop("string", "节目 ID"),
        "completion_policy" to prop(
            "string",
            "完成策略：notify_user=仅通知用户（不唤起你）；notify_agent=完成后唤起你决定如何告知用户（默认）；" +
                "continue_workflow=完成后唤起你继续已授权的后续动作（必须在 follow_up_instruction 写明，且需要用户原始指令明确授权）"
        ),
        "follow_up_instruction" to prop(
            "string",
            "continue_workflow 时必填：完成后要执行的动作说明（注意：这不构成用户授权，授权以用户原始指令为准）"
        ),
    ),
    execute = { args, ctx ->
        val episodeId = args.readString("episode_id")
        if (episodeId.isNullOrBlank()) {
            buildJsonObject { put("error", JsonPrimitive("episode_id 为必填参数")) }
        } else if (backgroundTaskManager == null) {
            // 后台任务管理器不可用：回退同步生成（保持旧行为，保证工具始终可用）
            val outcome = service.generateEpisodeAudio(
                episodeId = episodeId,
                assistantProviderId = ctx.assistantProviderId,
                assistantVoiceSettingJson = ctx.assistantVoiceSettingJson
            )
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("episode_id", JsonPrimitive(episodeId))
                put("audio_id", JsonPrimitive(outcome.asset.id))
                put("duration_ms", JsonPrimitive(outcome.asset.durationMs))
                outcome.fallbackNotice?.let { put("voice_fallback_notice", JsonPrimitive(it)) }
            }
        } else {
            createRadioAudioGenerationTask(service, backgroundTaskManager, ctx, episodeId, args)
        }
    }
)

/**
 * 创建电台音频生成后台任务（立即返回，不等待 TTS 完成）。
 * 冲突规则：同节目同文字稿 → 幂等复用既有任务；同节目不同文字稿 → 拒绝并返回既有任务 ID。
 */
private suspend fun createRadioAudioGenerationTask(
    service: RadioService,
    manager: me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager,
    ctx: RadioToolContext,
    episodeId: String,
    args: kotlinx.serialization.json.JsonObject
): kotlinx.serialization.json.JsonObject {
    val policyInput = args.readString("completion_policy")
    if (!me.rerere.rikkahub.data.backgroundtask.CompletionPolicies.isValid(policyInput)) {
        return buildJsonObject {
            put("error", JsonPrimitive("completion_policy 仅支持 notify_user / notify_agent / continue_workflow"))
        }
    }
    val completionPolicy = policyInput
        ?: me.rerere.rikkahub.data.backgroundtask.CompletionPolicies.NOTIFY_AGENT
    val followUp = args.readString("follow_up_instruction")
    if (completionPolicy == me.rerere.rikkahub.data.backgroundtask.CompletionPolicies.CONTINUE_WORKFLOW &&
        followUp.isNullOrBlank()
    ) {
        return buildJsonObject {
            put("error", JsonPrimitive("continue_workflow 需要在 follow_up_instruction 写明完成后的动作"))
        }
    }

    // 基于当前已保存文字稿的哈希创建任务（执行时与提交时二次校验）
    val episode = service.getEpisode(episodeId)
    val contentHash = me.rerere.rikkahub.data.radio.RadioEpisodeRules.contentHash(episode.content)
    if (episode.content.isBlank()) {
        return buildJsonObject { put("error", JsonPrimitive("节目文字稿为空，无法生成音频")) }
    }
    val payload = me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskPayload(
        episodeId = episodeId,
        contentHash = contentHash,
        episodeTitle = episode.title,
        followUpInstruction = followUp,
    )

    val outcome = manager.createTask(
        taskType = me.rerere.rikkahub.data.backgroundtask.BackgroundTaskTypes.RADIO_AUDIO_GENERATION,
        refKey = me.rerere.rikkahub.data.backgroundtask.BackgroundTaskTypes.refKeyForRadioAudio(episodeId),
        payloadJson = me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskCodec.encodePayload(payload),
        completionPolicy = completionPolicy,
        agentId = ctx.assistantId.toString(),
        conversationId = ctx.conversationId?.toString(),
        sourceTurnId = ctx.sourceTurnId,
        conflictDecider = { existing ->
            me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.decideConflict(
                activeTasks = existing.mapNotNull { task ->
                    me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskCodec.decodePayload(task.payloadJson)
                        ?.let { payload ->
                            me.rerere.rikkahub.data.backgroundtask.BackgroundTaskRules.ConflictCandidate(
                                taskId = task.id,
                                contentHash = payload.contentHash,
                                createdAt = task.createdAt,
                                isExecuting = task.status != me.rerere.rikkahub.core.data.db.entity.BackgroundTaskStatus.QUEUED.name
                            )
                        }
                },
                contentHash = contentHash
            )
        }
    )
    return when (outcome) {
        is me.rerere.rikkahub.data.backgroundtask.CreateTaskOutcome.Created -> buildJsonObject {
            put("success", JsonPrimitive(true))
            put("task_id", JsonPrimitive(outcome.taskId))
            put("status", JsonPrimitive("queued"))
            put("episode_id", JsonPrimitive(episodeId))
            put("message", JsonPrimitive("音频生成任务已创建，正在后台执行；期间可继续与用户对话，完成后会自动回传结果"))
        }

        is me.rerere.rikkahub.data.backgroundtask.CreateTaskOutcome.Reused -> buildJsonObject {
            put("success", JsonPrimitive(true))
            put("task_id", JsonPrimitive(outcome.taskId))
            put("status", JsonPrimitive("queued"))
            put("episode_id", JsonPrimitive(episodeId))
            put("message", JsonPrimitive("相同文字稿的生成任务已在进行中，无需重复创建"))
        }

        is me.rerere.rikkahub.data.backgroundtask.CreateTaskOutcome.Rejected -> buildJsonObject {
            put("success", JsonPrimitive(false))
            put("error", JsonPrimitive(outcome.message))
            put("existing_task_id", JsonPrimitive(outcome.existingTaskId))
            put("message", JsonPrimitive("该节目已有进行中的生成任务，可用 get_background_task 查询其状态"))
        }
    }
}

private fun defRestoreEpisodeAudio(service: RadioService): RadioToolDef = RadioToolDef(
    name = "restore_episode_audio",
    description = "恢复节目的备用音频：备用音频转当前音频，原当前音频转备用（重新计算 3 天有效期）。",
    schema = schemaOf(
        "episode_id" to prop("string", "节目 ID")
    ),
    execute = { args, _ ->
        val episodeId = args.readString("episode_id")
        if (episodeId.isNullOrBlank()) {
            buildJsonObject { put("error", JsonPrimitive("episode_id 为必填参数")) }
        } else {
            val restored = service.restoreBackupAudio(episodeId)
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("episode_id", JsonPrimitive(episodeId))
                put("audio_id", JsonPrimitive(restored.id))
                put("duration_ms", JsonPrimitive(restored.durationMs))
            }
        }
    }
)

private fun defPublishEpisode(service: RadioService): RadioToolDef = RadioToolDef(
    name = "publish_episode",
    description = "发布节目。发布前校验：节目存在、文字稿存在、当前音频有效且对应当前文字稿；" +
        "缺少有效音频时发布失败（本工具不负责自动生成音频，请先调用 generate_episode_audio）。" +
        "必须指定可见性 visibility：public = 所有 Agent 都能查询到该节目；private = 只有你（创作 Agent）与 user 能查询。",
    schema = schemaOf(
        "episode_id" to prop("string", "节目 ID"),
        "visibility" to prop(
            "string",
            "必填：节目可见性。public = 所有人(包含user)均可查询；private = 仅创作者与user可查询（user 始终可见）"
        )
    ),
    execute = { args, _ ->
        val episodeId = args.readString("episode_id")
        val visibilityRaw = args.readString("visibility")
        val visibility = RadioEpisodeRules.normalizeVisibility(visibilityRaw)
        val missingParam = episodeId.isNullOrBlank() || visibility == null
        if (missingParam) {
            buildJsonObject {
                put(
                    "error",
                    JsonPrimitive(
                        when {
                            episodeId.isNullOrBlank() -> "episode_id 为必填参数"
                            else -> "visibility 为必填参数，只支持 public / private"
                        }
                    )
                )
            }
        } else {
            val episode = service.publishEpisode(episodeId, visibility)
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("episode_id", JsonPrimitive(episode.id))
                put("title", JsonPrimitive(episode.title))
                put("status", JsonPrimitive(episode.status))
                put("visibility", JsonPrimitive(episode.visibility.lowercase()))
                put("published_at", JsonPrimitive(episode.publishedAt))
                put(
                    "message",
                    JsonPrimitive(
                        "节目发布成功（可见性 ${episode.visibility.lowercase()}）。" +
                            "可以调用 share_episode 工具把节目分享给 user。"
                    )
                )
            }
        }
    }
)

private fun defUpdateEpisodeVisibility(service: RadioService): RadioToolDef = RadioToolDef(
    name = "update_episode_visibility",
    description = "修改已发布节目的可见性：public = 所有人(包含user)都能查询到该节目；private = 只有你和user 能查询。" +
        "仅已发布节目可修改（草稿的可见性在发布时通过 publish_episode 的 visibility 参数指定）。",
    schema = schemaOf(
        "episode_id" to prop("string", "节目 ID"),
        "visibility" to prop(
            "string",
            "必填：目标可见性。public = 所有人均可查询；private = 仅你和user可查询"
        )
    ),
    execute = { args, _ ->
        val episodeId = args.readString("episode_id")
        val visibility = RadioEpisodeRules.normalizeVisibility(args.readString("visibility"))
        val missingParam = episodeId.isNullOrBlank() || visibility == null
        if (missingParam) {
            buildJsonObject {
                put(
                    "error",
                    JsonPrimitive(
                        when {
                            episodeId.isNullOrBlank() -> "episode_id 为必填参数"
                            else -> "visibility 为必填参数，只支持 public / private"
                        }
                    )
                )
            }
        } else {
            val episode = service.updateEpisodeVisibility(episodeId, visibility)
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("episode_id", JsonPrimitive(episode.id))
                put("title", JsonPrimitive(episode.title))
                put("status", JsonPrimitive(episode.status))
                put("visibility", JsonPrimitive(episode.visibility.lowercase()))
                put(
                    "message",
                    JsonPrimitive(
                        if (episode.visibility == me.rerere.rikkahub.core.data.db.entity.RadioEpisodeVisibility.PRIVATE) {
                            "节目已设为私密：仅你与 user 可查询该节目，其他人无法查询。"
                        } else {
                            "节目已设为公开：所有人均可查询该节目。"
                        }
                    )
                )
            }
        }
    }
)

private fun defShareEpisode(service: RadioService): RadioToolDef = RadioToolDef(
    name = "share_episode",
    description = "把已发布的电台节目以分享卡片的形式发送到对话中给 user 收听。" +
        "autoplay=1 时卡片发出后立即自动播放该节目；autoplay=0 时由 user 自己点击播放。",
    schema = schemaOf(
        "episode_id" to prop("string", "节目 ID（须为已发布节目）"),
        "autoplay" to prop("integer", "是否自动播放：0 = 不自动播放（默认）；1 = 发出后立即播放")
    ),
    execute = { args, ctx ->
        val episodeId = args.readString("episode_id")
        if (episodeId.isNullOrBlank()) {
            buildJsonObject { put("error", JsonPrimitive("episode_id 为必填参数")) }
        } else {
            val item = service.buildPlaybackItem(episodeId)
            if (item == null) {
                buildJsonObject {
                    put(
                        "error",
                        JsonPrimitive("分享失败：节目不存在、未发布或音频不可用（可先调用 generate_episode_audio）")
                    )
                }
            } else {
                val episode = service.getEpisode(episodeId)
                val autoplay = (args.readInt("autoplay") ?: 0) == 1
                if (autoplay) {
                    ctx.mediaPlaybackController?.playSingleItem(item)
                }
                buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("type", JsonPrimitive("radio_share"))
                    put("episode_id", JsonPrimitive(episode.id))
                    put("title", JsonPrimitive(episode.title))
                    put("autoplay", JsonPrimitive(if (autoplay) 1 else 0))
                    put("published_at", JsonPrimitive(episode.publishedAt))
                    put(
                        "message",
                        JsonPrimitive(
                            if (autoplay) "电台分享卡片已发送，正在自动播放该节目。"
                            else "电台分享卡片已发送，user 点击卡片上的播放按钮即可收听。"
                        )
                    )
                }
            }
        }
    }
)

/**
 * 从工具调用中提取 share_episode 的 episode_id（纯函数，单测固化；供消息流渲染电台分享卡片）。
 * Radio 工具经 call_radio_tool 二段式执行，消息里的 ToolCall 存的是外层包装，
 * 需同时识别两种挂载形式：
 * - 直接调用：toolName == "share_episode"，episode_id 在顶层参数；
 * - 二段式调用：toolName == call_radio_tool 且内层 tool_name == "share_episode"，
 *   episode_id 在内层 arguments（支持 object 或字符串化 JSON 两种传参形态）。
 * 其余情况（schema 查询、其他工具、解析失败）一律返回 null，不渲染卡片。
 */
internal fun extractRadioShareEpisodeId(toolName: String, argumentsJson: String): String? {
    val argsObj = runCatching {
        me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(argumentsJson).jsonObject
    }.getOrNull() ?: return null
    return when (toolName) {
        "share_episode" -> argsObj.readString("episode_id")
        TOOL_CALL_RADIO_TOOL -> {
            val innerTool = argsObj.readString("tool_name")
            if (innerTool != "share_episode") return null
            when (val raw = argsObj["arguments"]) {
                is JsonObject -> raw.readString("episode_id")
                is JsonPrimitive -> runCatching {
                    me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(raw.content).jsonObject
                        .readString("episode_id")
                }.getOrNull()

                else -> null
            }
        }

        else -> null
    }
}

// ====================================================================
// JSON / Schema 小工具
// ====================================================================

private fun prop(type: String, description: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("description", JsonPrimitive(description))
}

private fun schemaOf(
    vararg properties: Pair<String, JsonObject>
): InputSchema.Obj = InputSchema.Obj(
    properties = buildJsonObject { properties.forEach { (key, value) -> put(key, value) } }
)

private fun JsonObject.readString(key: String): String? {
    return (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}

private fun JsonObject.readInt(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content.toIntOrNull() else primitive.content.toIntOrNull()
}

private fun JsonObject.readBoolean(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.toBooleanStrictOrNull()
}

/** InputSchema.Obj 序列化为标准 JSON Schema 形状（与 provider 请求构建一致）。 */
private fun InputSchema.Obj.toJsonElement(): JsonElement = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", properties)
    required?.let { required ->
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

/** 递归截断过长 JSON 字符串值，防止单个工具结果撑爆上下文（与 MCP 同口径 32000 字符）。 */
private fun JsonElement.truncateLargeJsonText(maxLength: Int = 32000): JsonElement {
    return when (this) {
        is JsonPrimitive -> {
            if (isString && content.length > maxLength) {
                JsonPrimitive(content.take(maxLength) + "...(truncated, ${content.length} chars total)")
            } else {
                this
            }
        }

        is JsonObject -> JsonObject(this.mapValues { it.value.truncateLargeJsonText(maxLength) })
        is JsonArray -> JsonArray(this.map { it.truncateLargeJsonText(maxLength) })
        else -> this
    }
}
