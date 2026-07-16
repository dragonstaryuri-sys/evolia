package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import kotlin.uuid.Uuid
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.discover.repo.ScheduleRepository
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import me.rerere.rikkahub.core.data.repository.AssistantExtendedStateRepository
import me.rerere.rikkahub.core.data.repository.MilestoneRepository
import me.rerere.rikkahub.core.data.db.entity.MilestoneEntity
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.AgentTaskScheduler
import org.koin.compose.koinInject
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.core.data.db.entity.AssistantExtendedStateEntity
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import java.text.SimpleDateFormat
import java.util.Locale
import me.rerere.rikkahub.core.data.repository.AgentMonitorTaskRepository
import me.rerere.rikkahub.core.data.repository.UserDeviceStateRepository
import me.rerere.rikkahub.core.data.db.entity.AgentMonitorTaskEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.core.data.db.repository.GenMediaRepository
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.utils.getImagesDir
import me.rerere.rikkahub.utils.createCompressedImageFromBase64
import me.rerere.rikkahub.core.data.db.entity.GenMediaEntity
import java.io.File
import me.rerere.ai.provider.ImageGenerationMethod
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality

// 用于在 Tool 和 AccessibilityService 之间传递即时控制指令
object DeviceCommandHub {
    val commands = MutableSharedFlow<String>(extraBufferCapacity = 10)
}

@Composable
fun rememberLocalTools(): LocalTools {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val scheduleRepository = koinInject<ScheduleRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val agentTaskRepository = koinInject<AgentTaskRepository>()
    val agentTaskScheduler = koinInject<AgentTaskScheduler>()
    val secretKeyManager = koinInject<SecretKeyManager>()
    val extendedStateRepo = koinInject<AssistantExtendedStateRepository>()
    val milestoneRepo = koinInject<MilestoneRepository>()
    val monitorTaskRepo = koinInject<AgentMonitorTaskRepository>()
    val userDeviceStateRepo = koinInject<UserDeviceStateRepository>()
    val okHttpClient = koinInject<OkHttpClient>()
    val providerManager = koinInject<ProviderManager>()
    val genMediaRepository = koinInject<GenMediaRepository>()

    return remember {
        LocalTools(
            context,
            scheduleRepository,
            settingsStore,
            secretKeyManager,
            agentTaskRepository,
            agentTaskScheduler,
            extendedStateRepo,
            milestoneRepo,
            monitorTaskRepo,
            userDeviceStateRepo,
            okHttpClient,
            providerManager,
            genMediaRepository
        )
    }
}

class LocalTools(
    private val context: Context,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val secretKeyManager: SecretKeyManager,
    private val agentTaskRepository: AgentTaskRepository,
    private val agentTaskScheduler: AgentTaskScheduler,
    private val extendedStateRepo: AssistantExtendedStateRepository,
    private val milestoneRepo: MilestoneRepository,
    private val monitorTaskRepo: AgentMonitorTaskRepository,
    private val userDeviceStateRepo: UserDeviceStateRepository,
    private val okHttpClient: OkHttpClient,
    private val providerManager: ProviderManager,
    private val genMediaRepository: GenMediaRepository
) {
    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = "使用 QuickJS 执行 JavaScript 代码。如果使用此工具进行数学计算，建议在代码中添加 `toFixed` 以确保精度。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "要执行的 JavaScript 代码")
                        })
                    },
                )
            },
            execute = {
                val context = QuickJSContext.create()
                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                val result = context.evaluate(code)
                buildJsonObject {
                    put(
                        "result", when (result) {
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
            }
        )
    }

    private val pythonSandbox by lazy { PythonSandbox(context) }

    fun getPythonTools(conversationId: Uuid, userImageUrls: List<String> = emptyList()): List<Tool> {
        val workingDir = pythonSandbox.getConversationDir(conversationId).absolutePath

        val preloadedFiles = mutableListOf<String>()
        userImageUrls.forEachIndexed { index, url ->
            runCatching {
                val filename = "attachment_$index.png"
                pythonSandbox.importFile(conversationId, android.net.Uri.parse(url), filename)
                preloadedFiles.add(filename)
            }.onFailure { e ->
                android.util.Log.w("LocalTools", "Failed to auto-import attachment $index: ${e.message}")
            }
        }

        val preloadedInfo = if (preloadedFiles.isNotEmpty()) {
            " 用户附件已预加载到沙盒中：${preloadedFiles.joinToString { it }}。可通过 Image.open(\"${"$"}{filename}\") 访问。"
        } else ""

        return listOf(
            Tool(
                name = "eval_python",
                description = "执行 Python 代码。支持数据分析 (numpy, pandas)、图表生成 (matplotlib)、图像处理 (Pillow)、网络请求 (requests) 以及多种文档处理 (Word, Excel, PPT, PDF)。环境已内置多款中文字体，绘图时可直接显示中文。$preloadedInfo 执行后，请检查 `generated_files` 并在回复中包含任何 `markdown_link` (对于图像，优先使用 Markdown 图像语法如 `![chart](...)`)。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("code", buildJsonObject {
                                put("type", "string")
                                put("description", "要执行的 Python 代码")
                            })
                        },
                        required = listOf("code")
                    )
                },
                execute = {
                    val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        val filesBefore = pythonSandbox.listFiles(conversationId)
                        val beforeMap = filesBefore.associateBy { it.name }

                        val python = com.chaquo.python.Python.getInstance()
                        val executor = python.getModule("executor")
                        val resultJson = executor.callAttr("execute", code, workingDir).toString()
                        val baseResultObj = kotlinx.serialization.json.Json.parseToJsonElement(resultJson).jsonObject

                        val filesAfter = pythonSandbox.listFiles(conversationId)
                        val generatedFiles = filesAfter
                            .filter { file ->
                                val before = beforeMap[file.name]
                                // 只有新创建的文件或内容被修改过的文件才被视为生成文件
                                before == null || file.lastModified > (before.lastModified + 500) // 容差 500ms
                            }
                            .map { file ->
                                val uri = pythonSandbox.getFileUri(conversationId, file.name)
                                buildJsonObject {
                                    put("name", file.name)
                                    put("size", file.size)
                                    put("is_image", file.isImage)
                                    put("mime", file.mimeType)
                                    put("uri", uri.toString())
                                    put(
                                        "markdown_link",
                                        if (file.isImage) "![${file.name}]($uri)" else "[${file.name}]($uri)"
                                    )
                                }
                            }

                        val finalResultObj = buildJsonObject {
                            baseResultObj.forEach { (k, v) -> put(k, v) }
                            if (preloadedFiles.isNotEmpty()) {
                                put("preloaded_attachments", JsonArray(preloadedFiles.map { JsonPrimitive(it) }))
                            }
                            if (generatedFiles.isNotEmpty()) {
                                put("generated_files", JsonArray(generatedFiles))
                                put(
                                    "note",
                                    "在你的回复中使用 generated_files[].markdown_link，以便用户直接在聊天中查看或下载输出。"
                                )
                            }
                        }

                        val output = finalResultObj.toString()
                        if (output.length > 2000) {
                            buildJsonObject {
                                put("output", output.take(2000) + "... (内容过长，已截断)")
                                put(
                                    "note",
                                    "输出内容已被截断以节省上下文窗口。请尽量少用 print() 或保存到文件，并使用 list_sandbox_files 查看文件。"
                                )
                            }
                        } else {
                            finalResultObj
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "未知错误") }
                    }
                }
            ),
            Tool(
                name = "list_sandbox_files",
                description = "列出此对话的 Python 沙盒中的所有文件。返回文件名、大小、是否为图像以及可以直接包含在回复中的 markdown 链接。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject { },
                        required = emptyList()
                    )
                },
                execute = {
                    try {
                        val files = pythonSandbox.listFiles(conversationId)
                        buildJsonObject {
                            put(
                                "files", kotlinx.serialization.json.JsonArray(
                                    files.map { file ->
                                        buildJsonObject {
                                            val uri = pythonSandbox.getFileUri(conversationId, file.name)
                                            put("name", file.name)
                                            put("size", file.size)
                                            put("is_image", file.isImage)
                                            put("mime", file.mimeType)
                                            put("uri", uri.toString())
                                            put(
                                                "markdown_link",
                                                if (file.isImage) "![${file.name}]($uri)" else "[${file.name}]($uri)"
                                            )
                                        }
                                    }
                                ))
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "列出文件失败") }
                    }
                }
            ),
            Tool(
                name = "read_sandbox_file",
                description = "从 Python 沙盒中读取文本文件的具体内容。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("path", buildJsonObject {
                                put("type", "string")
                                put("description", "沙盒中文件的相对路径")
                            })
                        },
                        required = listOf("path")
                    )
                },
                execute = {
                    val path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        val python = com.chaquo.python.Python.getInstance()
                        val executor = python.getModule("executor")
                        val resultJson = executor.callAttr("read_file", path, workingDir).toString()
                        kotlinx.serialization.json.Json.parseToJsonElement(resultJson).jsonObject
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "读取文件失败") }
                    }
                }
            ),
            Tool(
                name = "write_sandbox_file",
                description = "向 Python 沙盒中的文件写入内容。返回 `markdown_link`，你必须将其包含在回复中，以便用户下载该文件。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("path", buildJsonObject {
                                put("type", "string")
                                put("description", "文件的相对路径")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "要写入文件的内容")
                            })
                        },
                        required = listOf("path", "content")
                    )
                },
                execute = {
                    val path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = it.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        val python = com.chaquo.python.Python.getInstance()
                        val executor = python.getModule("executor")
                        val resultJson = executor.callAttr("write_file", path, content, workingDir).toString()
                        val resultObj = kotlinx.serialization.json.Json.parseToJsonElement(resultJson).jsonObject

                        if (resultObj["success"]?.jsonPrimitive?.booleanOrNull == true) {
                            val relativePath = resultObj["path"]?.jsonPrimitive?.contentOrNull ?: path
                            val uri = pythonSandbox.getFileUri(conversationId, relativePath)
                            kotlinx.serialization.json.buildJsonObject {
                                resultObj.forEach { (k, v) -> put(k, v) }
                                put("uri", uri.toString())
                                put("markdown_link", "[$relativePath]($uri)")
                            }
                        } else {
                            resultObj
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "写入文件失败") }
                    }
                }
            ),
            Tool(
                name = "delete_sandbox_file",
                description = "从 Python 沙盒中物理删除指定文件。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("path", buildJsonObject {
                                put("type", "string")
                                put("description", "要删除的文件的相对路径")
                            })
                        },
                        required = listOf("path")
                    )
                },
                execute = {
                    val path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        val deleted = pythonSandbox.deleteFile(conversationId, path)
                        buildJsonObject {
                            put("success", deleted)
                            put("path", path)
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "删除文件失败") }
                    }
                }
            ),
            Tool(
                name = "import_attachment",
                description = "将用户在消息中上传的附件文件导入到 Python 沙盒中以便后续处理。返回文件在沙盒中的相对路径。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("url", buildJsonObject {
                                put("type", "string")
                                put("description", "附件文件的 URL")
                            })
                            put("filename", buildJsonObject {
                                put("type", "string")
                                put("description", "在沙盒中保存的文件名，需要带上格式后缀，如:.md")
                            })
                        },
                        required = listOf("url", "filename")
                    )
                },
                execute = {
                    val url = it.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val filename = it.jsonObject["filename"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        val uriArg = android.net.Uri.parse(url)
                        val relativePath = pythonSandbox.importFile(conversationId, uriArg, filename)
                        val fileUri = pythonSandbox.getFileUri(conversationId, relativePath)
                        buildJsonObject {
                            put("success", true)
                            put("path", relativePath)
                            put("filename", filename)
                            put("uri", fileUri.toString())
                            put("markdown_link", "[$filename]($fileUri)")
                        }
                    } catch (e: Exception) {
                        buildJsonObject {
                            put("success", false)
                            put("error", e.message ?: "导入文件失败")
                        }
                    }
                }
            )
        )
    }

    fun getDeviceControlTools(assistantId: Uuid, conversationId: Uuid): List<Tool> {
        return listOf(
            Tool(
                name = "list_apps",
                description = "列出用户手机上安装的所有应用及其包名。当需要调用 'device_control' 中的 'OPEN_APP' 操作但不知道包名时使用。",
                parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
                execute = {
                    try {
                        val pm = context.packageManager
                        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        val appList = apps.mapNotNull { app ->
                            val name = pm.getApplicationLabel(app).toString()
                            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                                buildJsonObject {
                                    put("name", name)
                                    put("package_name", app.packageName)
                                }
                            } else null
                        }
                        buildJsonObject {
                            put("apps", JsonArray(appList))
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "获取应用列表失败") }
                    }
                }
            ),
            Tool(
                name = "device_alarm_timer_manager",
                description = "管理设备上的闹钟 and 定时器。操作 'set_alarm' 需要 'hour' 和 'minutes'。操作 'set_timer' 需要 'seconds'。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("description", "要执行的操作：set_alarm（设置闹钟）或 set_timer（设置定时器）")
                                put("enum", JsonArray(listOf(JsonPrimitive("set_alarm"), JsonPrimitive("set_timer"))))
                            })
                            put("hour", buildJsonObject {
                                put("type", "integer")
                                put("description", "闹钟小时 (0-23)")
                            })
                            put("minutes", buildJsonObject {
                                put("type", "integer")
                                put("description", "闹钟分钟 (0-59)")
                            })
                            put("seconds", buildJsonObject {
                                put("type", "integer")
                                put("description", "定时器时长（秒）")
                            })
                            put("label", buildJsonObject {
                                put("type", "string")
                                put("description", "闹钟或定时器的描述标签")
                            })
                        },
                        required = listOf("action")
                    )
                },
                execute = {
                    val json = it.jsonObject
                    val action = json["action"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        when (action) {
                            "set_alarm" -> {
                                val hour = json["hour"]?.jsonPrimitive?.intOrNull ?: 0
                                val minutes = json["minutes"]?.jsonPrimitive?.intOrNull ?: 0
                                val label = json["label"]?.jsonPrimitive?.contentOrNull ?: "小机闹钟"
                                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                                    putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                buildJsonObject { put("success", true); put("message", "闹钟已设置为 $hour:$minutes") }
                            }

                            "set_timer" -> {
                                val seconds = json["seconds"]?.jsonPrimitive?.intOrNull ?: 0
                                val label = json["label"]?.jsonPrimitive?.contentOrNull ?: "小机定时器"
                                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                buildJsonObject {
                                    put("success", true); put(
                                    "message",
                                    "定时器已设置为 $seconds 秒"
                                )
                                }
                            }

                            else -> buildJsonObject { put("error", "未知操作") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "操作失败") }
                    }
                }
            ),
            Tool(
                name = "device_control",
                description = "在用户的手机上执行系统级全局操作。当需要主动干预时（例如：为睡眠管理锁定屏幕、返回主屏幕以停止使用、打开特定 App）使用。部分操作需要开启无障碍服务。支持的命令：LOCK_SCREEN（锁屏）, GO_HOME（回主页）, BACK（返回）, SHOW_RECENTS（显示最近任务）, SHOW_NOTIFICATIONS（显示通知）。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("command", buildJsonObject {
                                put("type", "string")
                                put("description", "要执行的系统命令")
                                put(
                                    "enum", JsonArray(
                                        listOf(
                                            JsonPrimitive("LOCK_SCREEN"),
                                            JsonPrimitive("GO_HOME"),
                                            JsonPrimitive("BACK"),
                                            JsonPrimitive("SHOW_RECENTS"),
                                            JsonPrimitive("SHOW_NOTIFICATIONS"),
                                            JsonPrimitive("OPEN_APP")
                                        )
                                    )
                                )
                            })
                            put("package_name", buildJsonObject {
                                put("type", "string")
                                put("description", "仅在 command=OPEN_APP 时必填，指定要打开的应用包名（可先通过 list_apps 获取）")
                            })
                        },
                        required = listOf("command")
                    )
                },
                execute = {
                    val command = it.jsonObject["command"]?.jsonPrimitive?.contentOrNull ?: ""
                    val packageName = it.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull

                    // 对于简单的打开 App，如果不在无障碍服务中也能尝试直接执行
                    if (command == "OPEN_APP" && !packageName.isNullOrBlank()) {
                        try {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                return@Tool buildJsonObject { put("success", true); put("message", "正在打开 $packageName") }
                            } else {
                                return@Tool buildJsonObject { put("error", "无法找到该应用的启动入口") }
                            }
                        } catch (e: Exception) {
                            return@Tool buildJsonObject { put("error", "打开应用失败: ${e.message}") }
                        }
                    }

                    // 其它命令通过 DeviceCommandHub 发送给 EvoliaMonitorService
                    if (DeviceCommandHub.commands.subscriptionCount.value == 0) {
                        return@Tool buildJsonObject {
                            put(
                                "error",
                                "无障碍服务未开启。涉及系统控制（如锁屏、返回键等）的操作需要该服务权限。请请示用户开启。"
                            )
                        }
                    }

                    val finalCommand = if (command == "OPEN_APP") "OPEN_APP:$packageName" else command
                    val success = DeviceCommandHub.commands.tryEmit(finalCommand)
                    buildJsonObject {
                        put("success", success)
                        if (success) put("message", "命令 $command 已下发执行。")
                        else put("error", "无障碍服务未响应。")
                    }
                }
            )
        )
    }

    fun getScheduleTools(): List<Tool> {
        return listOf(
            Tool(
                name = "schedule_manager",
                description = "管理你或用户的日程和待办。重要原则：凡是你向用户承诺的事、两人共同的约定、你答应要提醒的事或你自己的计划，必须归类为 'assistant'；仅用户纯粹的个人私事（如用户自己去取快递）才记为 'user'。支持操作：add, list, edit, toggle, delete。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "要执行的操作：add（新任务）, list（获取未完成列表）, edit（修改现有）, toggle（切换完成状态）, delete（移除）"
                                )
                                put(
                                    "enum", JsonArray(
                                        listOf(
                                            JsonPrimitive("add"),
                                            JsonPrimitive("list"),
                                            JsonPrimitive("edit"),
                                            JsonPrimitive("toggle"),
                                            JsonPrimitive("delete")
                                        )
                                    )
                                )
                            })
                            put("id", buildJsonObject {
                                put("type", "integer")
                                put("description", "日程 ID，编辑、切换状态和删除时必填")
                            })
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "日程标题，添加时必填")
                            })
                            put("priority", buildJsonObject {
                                put("type", "integer")
                                put("description", "优先级 (0: 不重要, 1: 普通, 2: 重要)")
                            })
                            put("urgency", buildJsonObject {
                                put("type", "integer")
                                put("description", "紧急程度 (0: 不紧急, 1: 普通, 2: 非常紧急)")
                            })
                            put("difficulty", buildJsonObject {
                                put("type", "integer")
                                put("description", "难度 (0: 简单, 1: 普通, 2: 困难)")
                            })
                            put("end_time", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "截止日期/结束时间，请使用 ISO 8601 格式（例如：2023-10-27T10:00:00）。注意：当该任务为你的待办或共享待办时，该字段必填。"
                                )
                            })
                            put("reminder_time", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "具体提醒时间，请使用 ISO 8601 格式（例如：2023-10-27T09:30:00）。"
                                )
                            })
                            put("category", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "待办类型：user（用户个人待办）, assistant（你的待办项）。注意：执行 list 操作时此参数必填。"
                                )
                                put(
                                    "enum", JsonArray(
                                        listOf(
                                            JsonPrimitive("user"),
                                            JsonPrimitive("assistant")
                                        )
                                    )
                                )
                            })
                        },
                        required = listOf("action")
                    )
                },
                execute = {
                    val json = it.jsonObject
                    val action = json["action"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        when (action) {
                            "add" -> {
                                val title = json["title"]?.jsonPrimitive?.contentOrNull ?: ""
                                val priority = json["priority"]?.jsonPrimitive?.intOrNull ?: 1
                                val urgency = json["urgency"]?.jsonPrimitive?.intOrNull ?: 1
                                val difficulty = json["difficulty"]?.jsonPrimitive?.intOrNull ?: 0
                                val category = json["category"]?.jsonPrimitive?.contentOrNull ?: "user"

                                val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

                                val endTimeStr = json["end_time"]?.jsonPrimitive?.contentOrNull
                                val endTime = if (endTimeStr != null) {
                                    runCatching { df.parse(endTimeStr)?.time }.getOrNull()
                                        ?: return@Tool buildJsonObject {
                                            put("success", false)
                                            put("error", "end_time 格式无效。")
                                        }
                                } else null

                                val reminderTimeStr = json["reminder_time"]?.jsonPrimitive?.contentOrNull
                                val reminderTime = if (reminderTimeStr != null) {
                                    runCatching { df.parse(reminderTimeStr)?.time }.getOrNull()
                                        ?: return@Tool buildJsonObject {
                                            put("success", false)
                                            put("error", "reminder_time 格式无效。")
                                        }
                                } else null

                                scheduleRepository.addSchedule(
                                    ScheduleEntity(
                                        title = title,
                                        content = "",
                                        priority = priority,
                                        urgency = urgency,
                                        difficulty = difficulty,
                                        startTime = System.currentTimeMillis(),
                                        endTime = endTime,
                                        reminderTime = reminderTime,
                                        category = category
                                    )
                                )

                                // 自动同步到系统日历 (如果包含结束时间或提醒时间)
                                if (category == "user" && endTime != null || reminderTime != null) {
                                    openSystemCalendar(
                                        title = title,
                                        startTime = reminderTime ?: System.currentTimeMillis(),
                                        endTime = endTime,
                                        description = "由你的机创建"
                                    )
                                }

                                buildJsonObject { put("success", true) }
                            }

                            "list" -> {
                                // 1. 获取并校验 category 参数
                                val category = json["category"]?.jsonPrimitive?.contentOrNull
                                if (category == null) {
                                    return@Tool buildJsonObject {
                                        put("success", false)
                                        put("error", "list 操作必须提供 category 参数 (user 或 assistant)")
                                    }
                                }

                                // 2. 从仓库获取数据，并过滤：匹配分类 且 未完成
                                val schedules = scheduleRepository.getPendingAndTodayCompleted().first()
                                    .filter { it.category == category && !it.isCompleted }

                                val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                                buildJsonObject {
                                    put("schedules", JsonArray(schedules.map { s ->
                                        buildJsonObject {
                                            put("id", s.id)
                                            put("title", s.title)
                                            put("priority", s.priority)
                                            put("urgency", s.urgency)
                                            put("difficulty", s.difficulty)
                                            put("start_time", df.format(java.util.Date(s.startTime)))
                                            s.endTime?.let { put("end_time", df.format(java.util.Date(it))) }
                                            s.reminderTime?.let { put("reminder_time", df.format(java.util.Date(it))) }
                                            put("is_completed", s.isCompleted)
                                            put("category", s.category)
                                        }
                                    }))
                                }
                            }

                            "edit" -> {
                                val id = json["id"]?.jsonPrimitive?.longOrNull ?: -1L
                                val schedule = scheduleRepository.getScheduleById(id)
                                if (schedule != null) {
                                    val newTitle = json["title"]?.jsonPrimitive?.contentOrNull ?: schedule.title
                                    val newPriority = json["priority"]?.jsonPrimitive?.intOrNull ?: schedule.priority
                                    val newUrgency = json["urgency"]?.jsonPrimitive?.intOrNull ?: schedule.urgency
                                    val newDifficulty =
                                        json["difficulty"]?.jsonPrimitive?.intOrNull ?: schedule.difficulty
                                    val newCategory = json["category"]?.jsonPrimitive?.contentOrNull ?: schedule.category

                                    val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

                                    val newEndTimeStr = json["end_time"]?.jsonPrimitive?.contentOrNull
                                    val newEndTime = if (newEndTimeStr != null) {
                                        runCatching { df.parse(newEndTimeStr)?.time }.getOrNull()
                                            ?: return@Tool buildJsonObject {
                                                put("success", false)
                                                put("error", "end_time 格式无效。")
                                            }
                                    } else schedule.endTime

                                    val newReminderTimeStr = json["reminder_time"]?.jsonPrimitive?.contentOrNull
                                    val newReminderTime = if (newReminderTimeStr != null) {
                                        runCatching { df.parse(newReminderTimeStr)?.time }.getOrNull()
                                            ?: return@Tool buildJsonObject {
                                                put("success", false)
                                                put("error", "reminder_time 格式无效。")
                                            }
                                    } else schedule.reminderTime

                                    scheduleRepository.updateSchedule(
                                        schedule.copy(
                                            title = newTitle,
                                            priority = newPriority,
                                            urgency = newUrgency,
                                            difficulty = newDifficulty,
                                            endTime = newEndTime,
                                            reminderTime = newReminderTime,
                                            category = newCategory,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    )

                                    // 自动同步到系统日历
                                    if (newCategory == "user" && newEndTime != null || newReminderTime != null) {
                                        openSystemCalendar(
                                            title = newTitle,
                                            startTime = newReminderTime ?: System.currentTimeMillis(),
                                            endTime = newEndTime,
                                            description = "由小机更新"
                                        )
                                    }

                                    buildJsonObject { put("success", true) }
                                } else {
                                    buildJsonObject { put("error", "未找到日程") }
                                }
                            }

                            "toggle" -> {
                                val id = json["id"]?.jsonPrimitive?.longOrNull ?: -1L
                                val schedules = scheduleRepository.getAllSchedules().first()
                                val schedule = schedules.find { s -> s.id == id }
                                if (schedule != null) {
                                    scheduleRepository.toggleComplete(schedule)
                                    buildJsonObject { put("success", true) }
                                } else {
                                    buildJsonObject { put("error", "未找到日程") }
                                }
                            }

                            "delete" -> {
                                val id = json["id"]?.jsonPrimitive?.longOrNull ?: -1L
                                scheduleRepository.deleteSchedule(id)
                                buildJsonObject { put("success", true) }
                            }

                            else -> buildJsonObject { put("error", "未知操作：$action") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "日程操作失败") }
                    }
                }
            )
        )
    }

    private fun openSystemCalendar(title: String, startTime: Long, endTime: Long?, description: String?) {
        try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                if (endTime != null) {
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                }
                if (description != null) {
                    putExtra(CalendarContract.Events.DESCRIPTION, description)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("LocalTools", "无法打开系统日历", e)
        }
    }

    fun getEmailTools(): List<Tool> {
        return listOf(
            Tool(
                name = "qq_email_service",
                description = "使用 QQ 邮箱发送或获取邮件。发送邮件时，收件人 'to' 参数是可选的；如果省略，邮件将自动发送到用户在个人设置中定义的默认邮箱。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("description", "操作类型：send 表示发送邮件，fetch 表示获取邮件列表")
                                put("enum", JsonArray(listOf(JsonPrimitive("send"), JsonPrimitive("fetch"))))
                            })
                            put("to", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "收件人邮箱地址。可选：如果不提供，邮件将发送到用户保存的默认邮箱。"
                                )
                            })
                            put("subject", buildJsonObject {
                                put("type", "string")
                                put("description", "邮件主题，action=send 时必填")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "邮件正文内容，action=send 时必填")
                            })
                            put("limit", buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "获取邮件的数量，最小 1，最大 3，默认为 1，仅在 action=fetch 时有效"
                                )
                            })
                        },
                        required = listOf("action")
                    )
                },
                execute = {
                    val settings = settingsStore.settingsFlow.value
                    val emailAccount = settings.emailConfig.account
                    val authCode = secretKeyManager.getEmailPassword("")

                    if (emailAccount.isBlank() || authCode.isBlank()) {
                        return@Tool buildJsonObject { put("error", "邮件服务未配置（缺少账号或授权码）。") }
                    }

                    val json = it.jsonObject
                    val action = json["action"]?.jsonPrimitive?.contentOrNull ?: ""

                    withContext(Dispatchers.IO) {
                        try {
                            when (action) {
                                "send" -> {
                                    val toParam = json["to"]?.jsonPrimitive?.contentOrNull
                                    val to = if (toParam.isNullOrBlank()) {
                                        settings.displaySetting.userEmail
                                    } else {
                                        toParam
                                    }

                                    if (to.isBlank()) {
                                        return@withContext buildJsonObject {
                                            put(
                                                "error",
                                                "收件人邮箱不能为空。请询问用户，或建议其在档案设置中设置。"
                                            )
                                        }
                                    }

                                    val subject = json["subject"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val content = json["content"]?.jsonPrimitive?.contentOrNull ?: ""

                                    sendQQEmail(emailAccount, authCode, to, subject, content)

                                    buildJsonObject { put("success", true); put("message", "邮件已成功发送至 $to") }
                                }

                                "fetch" -> {
                                    val limit = (json["limit"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 3)
                                    val emails = fetchQQEmails(emailAccount, authCode, limit)

                                    buildJsonObject {
                                        put("success", true)
                                        put("emails", JsonArray(emails.map { mail ->
                                            buildJsonObject {
                                                put("subject", mail.subject)
                                                put("from", mail.from)
                                                put("date", mail.date)
                                                put("content", mail.content)
                                            }
                                        }))
                                    }
                                }

                                else -> buildJsonObject { put("error", "未知操作") }
                            }
                        } catch (e: Exception) {
                            buildJsonObject { put("error", e.message ?: "邮件操作过程出错") }
                        }
                    }
                }
            )
        )
    }

    private fun sendQQEmail(account: String, authCode: String, to: String, subject: String, content: String) {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.qq.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(account, authCode)
            }
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(account))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject)
            setText(content)
        }
        Transport.send(message)
    }

    private fun fetchQQEmails(account: String, authCode: String, limit: Int): List<MailData> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.qq.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(account, authCode)
        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)
        val messages = inbox.messages
        val result = mutableListOf<MailData>()
        val start = (messages.size - limit).coerceAtLeast(0)
        for (i in messages.size - 1 downTo start) {
            val msg = messages[i]
            result.add(
                MailData(
                    subject = msg.subject ?: "(无主题)",
                    from = msg.from?.joinToString { it.toString() } ?: "未知发件人",
                    date = msg.sentDate?.toString() ?: "未知日期",
                    content = getTextFromMessage(msg)
                )
            )
        }

        inbox.close(false)
        store.close()
        return result
    }

    private fun getTextFromMessage(message: Message): String {
        return when {
            message.isMimeType("text/plain") -> message.content.toString()
            message.isMimeType("multipart/*") -> {
                val multipart = message.content as Multipart
                var result = ""
                for (i in 0 until multipart.count) {
                    val bodyPart = multipart.getBodyPart(i)
                    if (bodyPart.isMimeType("text/plain")) {
                        return bodyPart.content.toString() // 优先返回纯文本
                    } else if (bodyPart.isMimeType("text/html")) {
                        val html = bodyPart.content.toString()
                        result = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    }
                }
                result
            }

            else -> message.content.toString()
        }
    }

    private fun extractTextFromUIMessage(uiMessage: UIMessage): String {
        return uiMessage.parts.joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
    }

    data class MailData(val subject: String, val from: String, val date: String, val content: String)

    fun getAgentTaskTools(assistantId: Uuid): List<Tool> {
        return listOf(
            Tool(
                name = "agent_task_manager",
                description = "管理自动化异步任务。给“未来的自己”设定一条指令（instruction）。在预定时间，你将收到这条指令作为虚拟消息，并可调用任何工具来执行它。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("description", "要执行的操作：add（新增任务）, list（查询列表）, delete（删除任务）, edit（修改任务）")
                                put(
                                    "enum",
                                    JsonArray(
                                        listOf(
                                            JsonPrimitive("add"),
                                            JsonPrimitive("list"),
                                            JsonPrimitive("delete"),
                                            JsonPrimitive("edit")
                                        )
                                    )
                                )
                            })
                            put("task_id", buildJsonObject {
                                put("type", "integer")
                                put("description", "任务 ID，仅在 'delete' 和 'edit' 时必填")
                            })
                            put("task_name", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "此任务的自定义名称，方便用户识别。"
                                )
                            })
                            put("task_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "任务类别：EMAIL（邮件定时发）, NOTIFICATION（定时消息提醒）, OTHERS（通用异步任务，添加时必填）"
                                )
                            })
                            put("scheduled_time", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "执行时间，使用 ISO 8601 格式（如：2023-10-27T10:00:00）。新增时必填。"
                                )
                            })
                            put("repeat_interval", buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "重复周期（毫秒），可选。例如：86400000 代表每天执行一次"
                                )
                            })
                            put("instruction", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "具体指令内容。例如：'查询东京明天的天气，如降雨则向 boss@example.com 发送提醒邮件'。"
                                )
                            })
                            put("target", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "目标对象。EMAIL 任务为收件人邮箱；NOTIFICATION 任务为提醒的主题名称。"
                                )
                            })
                            put("subject", buildJsonObject {
                                put("type", "string")
                                put("description", "任务相关的标题/主题（EMAIL 任务必填）。")
                            })
                        },
                        required = listOf("action", "task_type")
                    )
                },
                execute = {
                    val json = it.jsonObject
                    val action = json["action"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        when (action) {
                            "add" -> {
                                val typeFromAi = json["task_type"]?.jsonPrimitive?.contentOrNull ?: ""
                                val finalType =
                                    if (typeFromAi == "OTHERS" || typeFromAi.isBlank()) "AGENT_TASK" else typeFromAi

                                val timeStr = json["scheduled_time"]?.jsonPrimitive?.contentOrNull
                                val time = if (timeStr != null) {
                                    runCatching {
                                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                            .parse(timeStr)?.time
                                    }.getOrNull() ?: return@Tool buildJsonObject {
                                        put("success", false)
                                        put(
                                            "error",
                                            "scheduled_time 格式不正确。请使用 ISO 8601 (yyyy-MM-dd'T'HH:mm:ss)"
                                        )
                                    }
                                } else return@Tool buildJsonObject {
                                    put("success", false)
                                    put("error", "新增任务必须提供 scheduled_time")
                                }

                                val repeat = json["repeat_interval"]?.jsonPrimitive?.longOrNull ?: 0L

                                val instruction = json["instruction"]?.jsonPrimitive?.contentOrNull
                                val target = json["target"]?.jsonPrimitive?.contentOrNull
                                val subject = json["subject"]?.jsonPrimitive?.contentOrNull

                                // 强制校验 EMAIL 任务的必填项
                                if (finalType == "EMAIL") {
                                    val missing = mutableListOf<String>()
                                    if (target.isNullOrBlank()) missing.add("target (收件人)")
                                    if (subject.isNullOrBlank()) missing.add("subject")
                                    if (instruction.isNullOrBlank()) missing.add("instruction (正文内容)")

                                    if (missing.isNotEmpty()) {
                                        return@Tool buildJsonObject {
                                            put("success", false)
                                            put(
                                                "error",
                                                "EMAIL 任务必须提供：${missing.joinToString(", ")}。"
                                            )
                                        }
                                    }
                                }

                                val taskData = buildJsonObject {
                                    json["task_name"]?.let { put("task_name", it) }
                                    instruction?.let { put("instruction", it) }
                                    target?.let {
                                        if (finalType == "EMAIL") put("to", it)
                                        else if (finalType == "NOTIFICATION") put("title", it)
                                    }
                                    subject?.let { put("subject", it) }
                                }.toString()

                                val entity = AgentTaskEntity(
                                    assistantId = assistantId.toString(),
                                    taskType = finalType,
                                    taskData = taskData,
                                    scheduledTime = time,
                                    repeatInterval = repeat
                                )
                                val newId = agentTaskRepository.addTask(entity)
                                agentTaskScheduler.scheduleTask(entity.copy(id = newId))

                                buildJsonObject { put("success", true); put("task_id", newId) }
                            }

                            "list" -> {
                                val tasks = agentTaskRepository.getTasksByAssistant(assistantId.toString()).first()
                                val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                buildJsonObject {
                                    put("tasks", JsonArray(tasks.map { t ->
                                        val data = try {
                                            kotlinx.serialization.json.Json.parseToJsonElement(t.taskData).jsonObject
                                        } catch (e: Exception) {
                                            buildJsonObject { }
                                        }
                                        buildJsonObject {
                                            put("id", t.id)
                                            put("task_name", data["task_name"] ?: JsonPrimitive(t.taskType))
                                            put("type", t.taskType)
                                            put("scheduled_time", df.format(java.util.Date(t.scheduledTime)))
                                            put("repeat_interval", t.repeatInterval)
                                            put("is_executed", t.isExecuted)
                                        }
                                    }))
                                }
                            }

                            "edit" -> {
                                val id = json["task_id"]?.jsonPrimitive?.longOrNull ?: -1L
                                val task = agentTaskRepository.getTaskById(id)
                                if (task != null) {
                                    val typeFromAi = json["task_type"]?.jsonPrimitive?.contentOrNull ?: task.taskType
                                    val finalType =
                                        if (typeFromAi == "OTHERS" || typeFromAi.isBlank()) "AGENT_TASK" else typeFromAi

                                    val timeStr = json["scheduled_time"]?.jsonPrimitive?.contentOrNull
                                    val time = if (timeStr != null) {
                                        runCatching {
                                            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                                .parse(timeStr)?.time
                                        }.getOrNull() ?: return@Tool buildJsonObject {
                                            put("success", false)
                                            put(
                                                "error",
                                                "scheduled_time 格式无效。请使用 ISO 8601 (yyyy-MM-dd'T'HH:mm:ss)"
                                            )
                                        }
                                    } else {
                                        task.scheduledTime
                                    }
                                    val repeat =
                                        json["repeat_interval"]?.jsonPrimitive?.longOrNull ?: task.repeatInterval

                                    val oldData = try {
                                        kotlinx.serialization.json.Json.parseToJsonElement(task.taskData).jsonObject
                                    } catch (e: Exception) {
                                        buildJsonObject { }
                                    }

                                    val instruction = json["instruction"]?.jsonPrimitive?.contentOrNull
                                        ?: oldData["instruction"]?.jsonPrimitive?.contentOrNull
                                    val target = json["target"]?.jsonPrimitive?.contentOrNull
                                        ?: oldData["to"]?.jsonPrimitive?.contentOrNull
                                    val subject = json["subject"]?.jsonPrimitive?.contentOrNull
                                        ?: oldData["subject"]?.jsonPrimitive?.contentOrNull

                                    if (finalType == "EMAIL") {
                                        val missing = mutableListOf<String>()
                                        if (target.isNullOrBlank()) missing.add("target (收件邮箱)")
                                        if (subject.isNullOrBlank()) missing.add("subject")
                                        if (instruction.isNullOrBlank()) missing.add("instruction")

                                        if (missing.isNotEmpty()) {
                                            return@Tool buildJsonObject {
                                                put("success", false)
                                                put(
                                                    "error",
                                                    "EMAIL 任务修改时必须包含：${missing.joinToString(", ")}。"
                                                )
                                            }
                                        }
                                    }

                                    val taskData = buildJsonObject {
                                        put("task_name", json["task_name"] ?: oldData["task_name"] ?: JsonPrimitive(""))
                                        put("instruction", JsonPrimitive(instruction ?: ""))

                                        if (target != null) {
                                            if (finalType == "EMAIL") put("to", JsonPrimitive(target))
                                            else if (finalType == "NOTIFICATION") put("title", JsonPrimitive(target))
                                        }

                                        put("subject", JsonPrimitive(subject ?: ""))
                                    }.toString()

                                    val updatedTask = task.copy(
                                        taskType = finalType,
                                        taskData = taskData,
                                        scheduledTime = time,
                                        repeatInterval = repeat,
                                        isExecuted = false
                                    )
                                    agentTaskRepository.updateTask(updatedTask)
                                    agentTaskScheduler.scheduleTask(updatedTask)
                                    buildJsonObject { put("success", true) }
                                } else {
                                    buildJsonObject { put("error", "未找到该任务") }
                                }
                            }

                            "delete" -> {
                                val id = json["task_id"]?.jsonPrimitive?.longOrNull ?: -1L
                                val task = agentTaskRepository.getTaskById(id)
                                if (task != null) {
                                    agentTaskRepository.deleteTask(task)
                                    agentTaskScheduler.cancelTask(id)
                                    buildJsonObject { put("success", true) }
                                } else buildJsonObject { put("error", "任务不存在") }
                            }

                            else -> buildJsonObject { put("error", "不支持的任务操作") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "操作异常") }
                    }
                }
            )
        )
    }

    fun getUpdateProfileTools(assistantId: Uuid): List<Tool> {
        return listOf(
            Tool(
                name = "update_profile",
                description = "当获知新信息时，更新用户或你自己的的档案。必须提供 'target' ('user' 或 'assistant') 和 'updates' 对象。" +
                    "重要：更新档案时，必须在现有信息的基础上进行增量更新，严禁直接覆盖导致旧信息丢失。" +
                    "如果某个字段已有内容，请将新信息与旧信息合理整合后再提交。例如：若用户原有偏好为'喜欢苹果'，新得知其也喜欢橘子，则应更新为'喜欢苹果和橘子'。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("target", buildJsonObject {
                                put("type", "string")
                                put("description", "要更新的目标档案：'user'（用户）或 'assistant'（助手）")
                                put("enum", JsonArray(listOf(JsonPrimitive("user"), JsonPrimitive("assistant"))))
                            })
                            put("updates", buildJsonObject {
                                put("type", "object")
                                put(
                                    "description", "要更新的字段映射键值对。" +
                                        "'user' 可选字段：appearance（外貌）, occupation（职业）, preferences（偏好）, diet（饮食）, health（健康）, taboos（禁忌）, interaction_preferences（交互偏好）, important_relationships（重要人际关系）, birthday（生日）。" +
                                        "'assistant' 可选字段：personality（性格）, preferences（偏好）, diet（饮食）, taboos（禁忌）, interaction_habits（交互习惯）, relationships（关系描述）。"
                                )
                                // 3. 即使解析是动态的，定义一些属性占位符能引导 AI 理解这是一个键值对对象
                                put("additionalProperties", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                        },
                        required = listOf("target", "updates")
                    )
                },
                execute = { argumentsElement ->
                    // 添加日志，查看原始参数
                    android.util.Log.d("LocalTools", "update_profile: raw arguments = $argumentsElement")

                    val argsObj = argumentsElement.jsonObject
                    if (argsObj.isEmpty()) {
                        return@Tool buildJsonObject {
                            put(
                                "error", "缺少参数：'target' 或 'updates'。请按照 JSON 格式规范调用。"
                            )
                        }
                    }
                    val target = argsObj["target"]?.jsonPrimitive?.contentOrNull ?: ""
                    val updates = argsObj["updates"]?.jsonObject ?: buildJsonObject {}

                    // 记录解析出的关键字段
                    android.util.Log.d(
                        "LocalTools",
                        "update_profile: parsed target = '$target', updates_keys = ${updates.keys}"
                    )

                    try {
                        when (target) {
                            "user" -> {
                                val currentSettings = settingsStore.settingsFlow.value
                                val profile = currentSettings.userProfile
                                var updatedProfile = profile

                                updates.forEach { (field, value) ->
                                    val newValue = value.jsonPrimitive.contentOrNull ?: value.toString()
                                    android.util.Log.d(
                                        "LocalTools",
                                        "update_profile: [user] updating '$field' to '$newValue'"
                                    )
                                    updatedProfile = when (field) {
                                        "appearance" -> updatedProfile.copy(appearance = newValue)
                                        "occupation" -> updatedProfile.copy(occupation = newValue)
                                        "preferences" -> updatedProfile.copy(preferences = newValue)
                                        "diet" -> updatedProfile.copy(diet = newValue)
                                        "health" -> updatedProfile.copy(health = newValue)
                                        "taboos" -> updatedProfile.copy(taboos = newValue)
                                        "interaction_preferences" -> updatedProfile.copy(interactionPreferences = newValue)
                                        "important_relationships" -> updatedProfile.copy(importantRelationships = newValue)
                                        "birthday" -> updatedProfile.copy(birthday = newValue)
                                        else -> updatedProfile
                                    }
                                }
                                settingsStore.update(currentSettings.copy(userProfile = updatedProfile))
                                buildJsonObject { put("success", true); put("message", "用户档案已完成增量更新。") }
                            }

                            "assistant" -> {
                                val state = extendedStateRepo.getStateById(assistantId.toString())
                                    ?: AssistantExtendedStateEntity(assistantId = assistantId.toString())
                                var updatedState = state

                                updates.forEach { (field, value) ->
                                    val newValue = value.jsonPrimitive.contentOrNull ?: value.toString()
                                    android.util.Log.d(
                                        "LocalTools",
                                        "update_profile: [assistant] updating '$field' to '$newValue'"
                                    )
                                    updatedState = when (field) {
                                        "personality" -> updatedState.copy(personality = newValue)
                                        "preferences" -> updatedState.copy(preferences = newValue)
                                        "diet" -> updatedState.copy(diet = newValue)
                                        "taboos" -> updatedState.copy(taboos = newValue)
                                        "interaction_habits" -> updatedState.copy(interactionHabits = newValue)
                                        "relationships" -> updatedState.copy(relationships = newValue)
                                        else -> updatedState
                                    }
                                }
                                extendedStateRepo.updateState(updatedState)
                                buildJsonObject { put("success", true); put("message", "小机档案已完成增量更新。") }
                            }

                            else -> {
                                // 发生错误时，记录更详细的信息
                                android.util.Log.w(
                                    "LocalTools",
                                    "update_profile failed: 目标 '$target' 无效。收到的键：${argsObj.keys.joinToString()}"
                                )
                                buildJsonObject {
                                    put(
                                        "error",
                                        "无效的目标档案类型：'$target'。"
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LocalTools", "update_profile: 发生内部错误", e)
                        buildJsonObject { put("error", e.message ?: "档案更新操作失败。") }
                    }
                }
            )
        )
    }

    fun getMilestoneTools(assistantId: Uuid): List<Tool> {
        return listOf(
            Tool(
                name = "milestone_manager",
                description = "记录并管理关系发展中的重大里程碑。里程碑指重塑“我们”定义或轨迹的事件或永久性的约定，包括：关系定位（你和用户的关系定位发生变化，如：朋友->恋人，助手->家人）、认知（认知发生重大变化）、承诺（形成新长期约定）、情感（互动方式更加深入、情感更加浓厚）、身份（披露自我身份相关变化）。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("description", "要执行的操作：add（记录新里程碑）, list（获取所有记录）, delete（移除某项）, update（修改内容）")
                                put(
                                    "enum",
                                    JsonArray(
                                        listOf(
                                            JsonPrimitive("add"),
                                            JsonPrimitive("list"),
                                            JsonPrimitive("delete"),
                                            JsonPrimitive("update")
                                        )
                                    )
                                )
                            })
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "里程碑记录的 ID，在 update 和 delete 时必填")
                            })
                            put("time", buildJsonObject {
                                put("type", "string")
                                put("description", "事件发生的时间 (YYYY-MM-DD)，在 add 和 update 时必填")
                            })
                            put("label", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "简短的分类标签（如：初见, 确立关系, 深度交心, 误会化解），新增时必填"
                                )
                            })
                            put("description", buildJsonObject {
                                put("type", "string")
                                put("description", "事件的详细背景或经过，新增时必填")
                            })
                        },
                        required = listOf("action")
                    )
                },
                execute = {
                    val json = it.jsonObject
                    val action = json["action"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        when (action) {
                            "add" -> {
                                val time = json["time"]?.jsonPrimitive?.contentOrNull ?: ""
                                val label = json["label"]?.jsonPrimitive?.contentOrNull ?: ""
                                val description = json["description"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (time.isBlank() || label.isBlank() || description.isBlank()) {
                                    return@Tool buildJsonObject { put("error", "添加记录时缺少核心信息（时间、标签或描述）") }
                                }
                                val milestone = MilestoneEntity(
                                    assistantId = assistantId.toString(),
                                    time = time,
                                    label = label,
                                    description = description
                                )
                                milestoneRepo.addMilestone(milestone)
                                buildJsonObject { put("success", true); put("id", milestone.id) }
                            }

                            "list" -> {
                                val milestones = milestoneRepo.getMilestones(assistantId.toString())
                                buildJsonObject {
                                    put("milestones", JsonArray(milestones.map { m ->
                                        buildJsonObject {
                                            put("id", m.id)
                                            put("time", m.time)
                                            put("label", m.label)
                                            put("description", m.description)
                                        }
                                    }))
                                }
                            }

                            "update" -> {
                                val id = json["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                val time = json["time"]?.jsonPrimitive?.contentOrNull
                                val label = json["label"]?.jsonPrimitive?.contentOrNull
                                val description = json["description"]?.jsonPrimitive?.contentOrNull
                                val list = milestoneRepo.getMilestones(assistantId.toString())
                                val existing = list.find { it.id == id }
                                if (existing != null) {
                                    val updated = existing.copy(
                                        time = time ?: existing.time,
                                        label = label ?: existing.label,
                                        description = description ?: existing.description
                                    )
                                    milestoneRepo.updateMilestone(updated)
                                    buildJsonObject { put("success", true) }
                                } else {
                                    buildJsonObject { put("error", "未找到对应的里程碑记录") }
                                }
                            }

                            "delete" -> {
                                val id = json["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                milestoneRepo.deleteMilestone(id)
                                buildJsonObject { put("success", true) }
                            }

                            else -> buildJsonObject { put("error", "无效操作") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "操作异常") }
                    }
                }
            )
        )
    }

    fun getPeekUserTools(assistantId: Uuid): List<Tool> {
        return listOf(
            Tool(
                name = "peek_user",
                description = "观察并监控用户的手机实时状态。通过 'add' 设置触发式监控（满足条件时系统将自动发送包含实时数据的隐藏消息给你），或使用 'get' 查看具体监控配置详情。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "要执行的操作：add（创建新监控）, list（查看已有列表）, delete（按 ID 撤销监控）, get（按 ID 获取详细配置）"
                                )
                                put(
                                    "enum",
                                    JsonArray(
                                        listOf(
                                            JsonPrimitive("add"),
                                            JsonPrimitive("list"),
                                            JsonPrimitive("delete"),
                                            JsonPrimitive("get")
                                        )
                                    )
                                )
                            })
                            put("monitor_id", buildJsonObject {
                                put("type", "integer")
                                put("description", "监控条目的 ID，删除或查询时必填")
                            })
                            put("monitor_name", buildJsonObject {
                                put("type", "string")
                                put("description", "起一个描述性名称（例如：'防熬夜卫士'）")
                            })
                            put("data_requirements", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                                put(
                                    "description",
                                    "需获取的数据字段：foreground_app（前台应用）, screen_status（屏幕状态）, current_time（当前时间）, today_usage_duration（今日使用总计）, app_session_duration（当前应用连续使用时长）, total_continuous_duration（手机连续使用时长）, recent_actions（近期操作记录）, screen_context（屏幕文字上下文）, location（位置信息）"
                                )
                            })
                            put("conditions", buildJsonObject {
                                put("type", "object")
                                put(
                                    "description",
                                    "设置触发逻辑。支持：'time_range' (HH:mm 范围), 'screen_status' (ON/OFF), 'foreground_app' (特定包名), 'usage_duration_minutes' (使用时长限额), 'continuous_usage_minutes' (单次应用限额), 'total_continuous_minutes' (单次持续使用时间限额), 'content_contains' (屏幕内容包含关键词), 'location_name' (抵达/留在某地), 'cooldown_minutes' (触发后静默时长，默认 5)。"
                                )
                            })
                            put("trigger_message", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "触发时发送给你的消息模板。可用变量：{app_name}, {duration}, {continuous_duration}, {total_continuous_duration}, {recent_actions}, {screen_context}, {current_time}, {location}。"
                                )
                            })
                        },
                        required = listOf("action")
                    )
                },
                execute = {
                    val json = it.jsonObject
                    val action = json["action"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        when (action) {
                            "add" -> {
                                val monitorName =
                                    json["monitor_name"]?.jsonPrimitive?.contentOrNull ?: "未命名监控"
                                val dataReq = json["data_requirements"]?.toString() ?: "[]"

                                // 自动修复：如果未提供 foreground_app 但提供了 continuous_usage_minutes，
                                // 则将其重命名为 total_continuous_minutes。
                                val conditionsObj = json["conditions"]?.jsonObject ?: buildJsonObject {}
                                val processedConditions = if (conditionsObj.containsKey("continuous_usage_minutes") && !conditionsObj.containsKey("foreground_app")) {
                                    buildJsonObject {
                                        conditionsObj.forEach { (k, v) ->
                                            if (k == "continuous_usage_minutes") {
                                                put("total_continuous_minutes", v)
                                            } else {
                                                put(k, v)
                                            }
                                        }
                                    }
                                } else {
                                    conditionsObj
                                }
                                val conditions = processedConditions.toString()

                                val triggerMsg =
                                    json["trigger_message"]?.jsonPrimitive?.contentOrNull ?: "监控已触发"

                                val task = AgentMonitorTaskEntity(
                                    assistantId = assistantId.toString(),
                                    monitorName = monitorName,
                                    dataRequirements = dataReq,
                                    conditions = conditions,
                                    actions = buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "SEND_HIDDEN_MESSAGE")
                                            put("content", triggerMsg)
                                        })
                                    }.toString()
                                )
                                val id = monitorTaskRepo.addTask(task)
                                buildJsonObject { put("success", true); put("monitor_id", id) }
                            }

                            "list" -> {
                                val tasks = monitorTaskRepo.getTasksByAssistant(assistantId.toString()).first()
                                buildJsonObject {
                                    put("monitors", JsonArray(tasks.map { t ->
                                        buildJsonObject {
                                            put("id", t.id)
                                            put("name", t.monitorName)
                                            put("is_enabled", t.isEnabled)
                                        }
                                    }))
                                }
                            }

                            "delete" -> {
                                val id = json["monitor_id"]?.jsonPrimitive?.longOrNull ?: -1L
                                monitorTaskRepo.deleteTaskById(id)
                                buildJsonObject { put("success", true) }
                            }
                            "get" -> {
                                val id = json["monitor_id"]?.jsonPrimitive?.longOrNull ?: -1L
                                val task = monitorTaskRepo.getTaskById(id)
                                if (task != null) {
                                    buildJsonObject {
                                        put("success", true)
                                        put("id", task.id)
                                        put("name", task.monitorName)
                                        // 尝试将存储的 String JSON 解析回结构化对象，以便 AI 更好地处理
                                        put("data_requirements", try { Json.parseToJsonElement(task.dataRequirements) } catch (e: Exception) { JsonPrimitive(task.dataRequirements) })
                                        put("conditions", try { Json.parseToJsonElement(task.conditions) } catch (e: Exception) { JsonPrimitive(task.conditions) })
                                        put("actions", try { Json.parseToJsonElement(task.actions) } catch (e: Exception) { JsonPrimitive(task.actions) })
                                        put("is_enabled", task.isEnabled)
                                        put("created_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(task.createdAt)))
                                    }
                                } else {
                                    buildJsonObject { put("error", "监控项不存在") }
                                }
                            }

                            else -> buildJsonObject { put("error", "不支持的操作") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "操作异常") }
                    }
                }
            )
        )
    }

    fun getWebPageReaderTools(): List<Tool> {
        return listOf(
            Tool(
                name = "fetch_url_content",
                description = "获取指定 URL 网页的内容。该工具会自动提取网页正文并转换为易读的文本，适合用于阅读新闻、文章、文档等。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("url", buildJsonObject {
                                put("type", "string")
                                put("description", "要访问的完整 URL 链接")
                            })
                        },
                        required = listOf("url")
                    )
                },
                execute = {
                    val url = it.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (url.isBlank()) {
                        return@Tool buildJsonObject { put("error", "URL 不能为空") }
                    }

                    withContext(Dispatchers.IO) {
                        try {
                            val request = Request.Builder()
                                .url(url)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                .build()

                            okHttpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                                val html = response.body?.string() ?: ""

                                val doc = Jsoup.parse(html, url)
                                // 移除不必要的标签
                                doc.select("script, style, noscript, iframe, head").remove()
                                val bodyText = doc.body().text()

                                buildJsonObject {
                                    put("content", bodyText.take(5000))
                                    put("status", response.code)
                                    if (bodyText.length > 5000) {
                                        put("note", "内容过长，已截断。如果需要更多内容或特定解析，请尝试使用 eval_python。")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            buildJsonObject { put("error", "访问失败: ${e.message}") }
                        }
                    }
                }
            )
        )
    }

    fun getImageGenerationTools(): List<Tool> {
        return listOf(
            Tool(
                name = "generate_image",
                description = "生成图像。基于用户提供的提示词（prompt）生成一张或多张图片。生成的图片将保存到设备中并返回可以直接在回复中使用的 Markdown 链接。建议提示词使用详细的英文描述以获得更好效果。",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("prompt", buildJsonObject {
                                put("type", "string")
                                put("description", "描述要生成的图像的提示词")
                            })
                            put("num_images", buildJsonObject {
                                put("type", "integer")
                                put("description", "生成的图像数量，1-4，默认为 1")
                            })
                            put("aspect_ratio", buildJsonObject {
                                put("type", "string")
                                put("description", "图像纵横比：SQUARE (1:1), PORTRAIT (2:3), LANDSCAPE (3:2)")
                                put("enum", JsonArray(listOf(JsonPrimitive("SQUARE"), JsonPrimitive("PORTRAIT"), JsonPrimitive("LANDSCAPE"))))
                            })
                        },
                        required = listOf("prompt")
                    )
                },
                execute = { args ->
                    val prompt = args.jsonObject["prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                    val numImages = (args.jsonObject["num_images"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 4)
                    val aspectRatioStr = args.jsonObject["aspect_ratio"]?.jsonPrimitive?.contentOrNull ?: "SQUARE"
                    val aspectRatio = try { ImageAspectRatio.valueOf(aspectRatioStr) } catch (e: Exception) { ImageAspectRatio.SQUARE }

                    withContext(Dispatchers.IO) {
                        try {
                            val settings = settingsStore.settingsFlow.value
                            val model = settings.findModelById(settings.imageGenerationModelId)
                                ?: return@withContext buildJsonObject { put("error", "未配置生图模型，请在设置中配置。") }

                            val provider = model.findProvider(settings.providers)
                                ?: return@withContext buildJsonObject { put("error", "找不到对应的 Provider 实例。") }

                            val providerSetting = settings.providers.find { it.id == provider.id }
                                ?: return@withContext buildJsonObject { put("error", "找不到 Provider 设置。") }

                            val method = model.imageGenerationMethod ?: ImageGenerationMethod.DIFFUSION

                            val items = when (method) {
                                ImageGenerationMethod.MULTIMODAL -> {
                                    val modelWithImageOutput = model.copy(
                                        outputModalities = model.outputModalities + Modality.IMAGE
                                    )
                                    val textParams = TextGenerationParams(
                                        model = modelWithImageOutput,
                                        temperature = null,
                                        topP = null,
                                        maxTokens = null,
                                        tools = emptyList(),
                                        thinkingBudget = null,
                                        customHeaders = model.customHeaders,
                                        customBody = model.customBodies
                                    )
                                    val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt))))
                                    val result = providerManager.getProviderByType(provider).generateText(providerSetting, messages, textParams)
                                    val images = mutableListOf<ImageGenerationItem>()
                                    val textResponse = mutableListOf<String>()

                                    result.choices.forEach { choice ->
                                        choice.message?.parts?.forEach { part ->
                                            when (part) {
                                                is UIMessagePart.Image -> images.add(ImageGenerationItem(data = part.url, mimeType = "image/png"))
                                                is UIMessagePart.Text -> textResponse.add(part.text)
                                                else -> {}
                                            }
                                        }
                                    }

                                    if (images.isEmpty()) {
                                        val reason = textResponse.joinToString("\n").ifBlank { "模型未返回任何图像内容，且没有提供文字解释。" }
                                        throw IllegalStateException("生图失败。模型回复：$reason")
                                    }
                                    images
                                }
                                ImageGenerationMethod.DIFFUSION -> {
                                    val params = ImageGenerationParams(
                                        model = model,
                                        prompt = prompt,
                                        numOfImages = numImages,
                                        aspectRatio = aspectRatio,
                                        customHeaders = model.customHeaders,
                                        customBody = model.customBodies
                                    )
                                    providerManager.getProviderByType(provider).generateImage(providerSetting, params).items
                                }
                            }

                            val generatedFiles = mutableListOf<JsonObject>()
                            val imagesDir = context.getImagesDir()

                            items.forEachIndexed { index, item ->
                                val timestamp = System.currentTimeMillis()
                                val filename = "${timestamp}_${model.displayName}_$index.webp"
                                val imageFile = File(imagesDir, filename)

                                context.createCompressedImageFromBase64(item.data, imageFile.absolutePath, 80)

                                val relativePath = "images/${imageFile.name}"
                                val entity = GenMediaEntity(
                                    path = relativePath,
                                    modelId = model.displayName,
                                    prompt = prompt,
                                    createAt = timestamp
                                )
                                genMediaRepository.insertMedia(entity)

                                val uri = android.net.Uri.fromFile(imageFile)
                                generatedFiles.add(buildJsonObject {
                                    put("name", filename)
                                    put("uri", uri.toString())
                                    put("markdown_link", "![generated_image]($uri)")
                                })
                            }

                            buildJsonObject {
                                put("success", true)
                                put("generated_images", JsonArray(generatedFiles))
                                put("note", "请在你的回复中直接包含 generated_images[].markdown_link 以便用户查看图片。")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("LocalTools", "Failed to generate image in tool", e)
                            buildJsonObject { put("error", e.message ?: "图像生成失败") }
                        }
                    }
                }
            )
        )
    }

    fun getTools(
        options: List<LocalToolOption>,
        assistantId: Uuid,
        conversationId: Uuid,
        userImageUrls: List<String> = emptyList()
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) tools.add(javascriptTool)
        if (options.contains(LocalToolOption.DeviceControl)) {
            tools.addAll(getDeviceControlTools(assistantId, conversationId))
        }
        if (options.contains(LocalToolOption.PythonEngine)) tools.addAll(getPythonTools(conversationId, userImageUrls))
        if (options.contains(LocalToolOption.ScheduleManagement)) tools.addAll(getScheduleTools())
        if (options.contains(LocalToolOption.AgentAutomation)) tools.addAll(getAgentTaskTools(assistantId))
        if (options.contains(LocalToolOption.EmailService)) tools.addAll(getEmailTools())
        if (options.contains(LocalToolOption.UpdateProfile)) tools.addAll(getUpdateProfileTools(assistantId))
        if (options.contains(LocalToolOption.MilestoneManagement)) tools.addAll(getMilestoneTools(assistantId))
        if (options.contains(LocalToolOption.PeekUser)) tools.addAll(getPeekUserTools(assistantId))
        if (options.contains(LocalToolOption.WebPageReader)) tools.addAll(getWebPageReaderTools())
        if (options.contains(LocalToolOption.ImageGeneration)) tools.addAll(getImageGenerationTools())
        return tools
    }
}
