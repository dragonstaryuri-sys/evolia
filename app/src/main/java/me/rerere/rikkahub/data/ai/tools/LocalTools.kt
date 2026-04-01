package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.animation.core.copy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import kotlin.uuid.Uuid
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.discover.repo.ScheduleRepository
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
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
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity

@Composable
fun rememberLocalTools(): LocalTools {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val scheduleRepository = koinInject<ScheduleRepository>() // 新增这一行
    val settingsStore = koinInject<SettingsStore>()
    val agentTaskRepository = koinInject<AgentTaskRepository>()
    val agentTaskScheduler = koinInject<AgentTaskScheduler>()
    val secretKeyManager = koinInject<SecretKeyManager>()

    // 这里的参数顺序要和 class LocalTools 构造函数一致
    return remember {
        LocalTools(
            context,
            scheduleRepository, // 传入这个参数
            settingsStore,
            secretKeyManager,
            agentTaskRepository,
            agentTaskScheduler
        )
    }
}

class LocalTools(
    private val context: Context,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val secretKeyManager: SecretKeyManager,
    private val agentTaskRepository: AgentTaskRepository,
    private val agentTaskScheduler: AgentTaskScheduler
) {
    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = "Execute JavaScript code with QuickJS. If use this tool to calculate math, better to add `toFixed` to the code.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "The JavaScript code to execute")
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
            " User attachments are pre-loaded in sandbox as: ${preloadedFiles.joinToString { it }}. Access them with Image.open(\"${'$'}{filename}\")."
        } else ""

        return listOf(
            Tool(
                name = "eval_python",
                description = "Execute Python code. Has access to numpy, pandas, matplotlib and Pillow. Use for calculations, data processing and chart/image generation.$preloadedInfo After execution, check `generated_files` and include any `markdown_link` in your reply (for images prefer Markdown image syntax like `![chart](content://...)`).",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("code", buildJsonObject {
                                put("type", "string")
                                put("description", "The Python code to execute")
                            })
                        },
                        required = listOf("code")
                    )
                },
                execute = {
                    val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull ?: ""
                    try {
                        val filesBefore = pythonSandbox.listFiles(conversationId)
                        val beforeNames = filesBefore.map { file -> file.name }.toSet()

                        val python = com.chaquo.python.Python.getInstance()
                        val executor = python.getModule("executor")
                        val resultJson = executor.callAttr("execute", code, workingDir).toString()
                        val baseResultObj = kotlinx.serialization.json.Json.parseToJsonElement(resultJson).jsonObject

                        val filesAfter = pythonSandbox.listFiles(conversationId)
                        val generatedFiles = filesAfter
                            .filter { file -> !beforeNames.contains(file.name) }
                            .map { file ->
                                val uri = pythonSandbox.getFileUri(conversationId, file.name)
                                buildJsonObject {
                                    put("name", file.name)
                                    put("size", file.size)
                                    put("is_image", file.isImage)
                                    put("mime", file.mimeType)
                                    put("uri", uri.toString())
                                    put("markdown_link", if (file.isImage) "![${file.name}]($uri)" else "[${file.name}]($uri)")
                                }
                            }

                        val finalResultObj = buildJsonObject {
                            baseResultObj.forEach { (k, v) -> put(k, v) }
                            if (preloadedFiles.isNotEmpty()) {
                                put("preloaded_attachments", JsonArray(preloadedFiles.map { JsonPrimitive(it) }))
                            }
                            if (generatedFiles.isNotEmpty()) {
                                put("generated_files", JsonArray(generatedFiles))
                                put("note", "Use generated_files[].markdown_link in your reply so users can open/download outputs directly in chat.")
                            }
                        }

                        val output = finalResultObj.toString()
                        if (output.length > 2000) {
                            buildJsonObject {
                                put("output", output.take(2000) + "... (truncated)")
                                put("note", "Output truncated to save context window. Use print() sparingly or save to file, and use list_sandbox_files to inspect files.")
                            }
                        } else {
                            finalResultObj
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Unknown error") }
                    }
                }
            ),
            Tool(
                name = "list_sandbox_files",
                description = "List all files in the Python sandbox for this conversation. Returns file names, sizes, whether they are images, and direct markdown links you can include in your response.",
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
                            put("files", kotlinx.serialization.json.JsonArray(
                                files.map { file ->
                                    buildJsonObject {
                                        val uri = pythonSandbox.getFileUri(conversationId, file.name)
                                        put("name", file.name)
                                        put("size", file.size)
                                        put("is_image", file.isImage)
                                        put("mime", file.mimeType)
                                        put("uri", uri.toString())
                                        put("markdown_link", if (file.isImage) "![${file.name}]($uri)" else "[${file.name}]($uri)")
                                    }
                                }
                            ))
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Failed to list files") }
                    }
                }
            ),
            Tool(
                name = "read_sandbox_file",
                description = "Read a text file from the Python sandbox.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("path", buildJsonObject {
                                put("type", "string")
                                put("description", "Relative path to the file in the sandbox")
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
                        buildJsonObject { put("error", e.message ?: "Failed to read file") }
                    }
                }
            ),
            Tool(
                name = "write_sandbox_file",
                description = "Write content to a file in the Python sandbox. Returns `markdown_link` which you MUST include in your response to let the user download the file.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("path", buildJsonObject {
                                put("type", "string")
                                put("description", "Relative path for the file")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Content to write to the file")
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
                            val uri = pythonSandbox.getFileUri(conversationId, path)
                            kotlinx.serialization.json.buildJsonObject {
                                resultObj.forEach { (k, v) -> put(k, v) }
                                put("uri", uri.toString())
                                put("markdown_link", "[$path]($uri)")
                            }
                        } else {
                            resultObj
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Failed to write file") }
                    }
                }
            ),
            Tool(
                name = "delete_sandbox_file",
                description = "Delete a file from the Python sandbox.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("path", buildJsonObject {
                                put("type", "string")
                                put("description", "Relative path to the file to delete")
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
                        buildJsonObject { put("error", e.message ?: "Failed to delete file") }
                    }
                }
            ),
            Tool(
                name = "import_attachment",
                description = "Import an attached file from the user's message into the Python sandbox.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("url", buildJsonObject {
                                put("type", "string")
                                put("description", "File URL")
                            })
                            put("filename", buildJsonObject {
                                put("type", "string")
                                put("description", "Filename to save as in the sandbox")
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
                        val savedPath = pythonSandbox.importFile(conversationId, uriArg, filename)
                        val fileUri = pythonSandbox.getFileUri(conversationId, filename)
                        buildJsonObject {
                            put("success", true)
                            put("path", savedPath)
                            put("filename", filename)
                            put("uri", fileUri.toString())
                            put("markdown_link", "[$filename]($fileUri)")
                        }
                    } catch (e: Exception) {
                        buildJsonObject {
                            put("success", false)
                            put("error", e.message ?: "Failed to import file")
                        }
                    }
                }
            )
        )
    }

    fun getDeviceControlTools(assistantId: Uuid, conversationId: Uuid): List<Tool> {
        return listOf(
            Tool(
                name = "device_alarm_timer_manager",
                description = "Manage alarms and timers on the device. Action 'set_alarm' requires 'hour' and 'minutes'. Action 'set_timer' requires 'seconds'.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("description", "Action to perform: set_alarm or set_timer")
                                put("enum", JsonArray(listOf(JsonPrimitive("set_alarm"), JsonPrimitive("set_timer"))))
                            })
                            put("hour", buildJsonObject {
                                put("type", "integer")
                                put("description", "Alarm hour (0-23)")
                            })
                            put("minutes", buildJsonObject {
                                put("type", "integer")
                                put("description", "Alarm minutes (0-59)")
                            })
                            put("seconds", buildJsonObject {
                                put("type", "integer")
                                put("description", "Timer duration in seconds")
                            })
                            put("label", buildJsonObject {
                                put("type", "string")
                                put("description", "Label for the alarm or timer")
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
                                val label = json["label"]?.jsonPrimitive?.contentOrNull ?: "Assistant Alarm"
                                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                                    putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                buildJsonObject { put("success", true); put("message", "Alarm set for $hour:$minutes") }
                            }
                            "set_timer" -> {
                                val seconds = json["seconds"]?.jsonPrimitive?.intOrNull ?: 0
                                val label = json["label"]?.jsonPrimitive?.contentOrNull ?: "Assistant Timer"
                                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                buildJsonObject { put("success", true); put("message", "Timer set for $seconds seconds") }
                            }
                            else -> buildJsonObject { put("error", "Unknown action") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Operation failed") }
                    }
                }
            )
        )
    }

    fun getScheduleTools(): List<Tool> {
        return listOf(
            Tool(
                name = "schedule_manager",
                description = "Manage user's schedules/tasks. Supported actions: add, list, edit, toggle, delete.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("description", "Action to perform: add (new task), list (get all), edit (modify existing), toggle (complete status), delete (remove)")
                                put("enum", JsonArray(listOf(
                                    JsonPrimitive("add"),
                                    JsonPrimitive("list"),
                                    JsonPrimitive("edit"),
                                    JsonPrimitive("toggle"),
                                    JsonPrimitive("delete")
                                )))
                            })
                            put("id", buildJsonObject {
                                put("type", "integer")
                                put("description", "Schedule ID, required for edit, toggle, and delete")
                            })
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "The title of the schedule, required for 'add'")
                            })
                            put("priority", buildJsonObject {
                                put("type", "integer")
                                put("description", "Priority (0: Not Important, 1: Normal, 2: Important)")
                            })
                            put("urgency", buildJsonObject {
                                put("type", "integer")
                                put("description", "Urgency (0: Not Urgent, 1: Normal, 2: Very Urgent)")
                            })
                            put("difficulty", buildJsonObject {
                                put("type", "integer")
                                put("description", "Difficulty (0: Simple, 1: Normal, 2: Not Simple)")
                            })
                            put("end_time", buildJsonObject {
                                put("type", "string")
                                put("description", "Deadline, please use ISO 8601 format (e.g., 2023-10-27T10:00:00).Fill in only if provided by the user")
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
                                val endTimeStr = json["end_time"]?.jsonPrimitive?.contentOrNull
                                val endTime = endTimeStr?.let {
                                    runCatching {
                                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(it)?.time
                                    }.getOrNull()
                                }
                                scheduleRepository.addSchedule(
                                    ScheduleEntity(
                                        title = title,
                                        content = "",
                                        priority = priority,
                                        urgency = urgency,
                                        difficulty = difficulty,
                                        startTime = System.currentTimeMillis(),
                                        endTime = endTime
                                    )
                                )
                                buildJsonObject { put("success", true) }
                            }
                            "list" -> {
                                val schedules = scheduleRepository.getPendingAndTodayCompleted().first()
                                val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
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
                                            put("is_completed", s.isCompleted)
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
                                    val newDifficulty = json["difficulty"]?.jsonPrimitive?.intOrNull ?: schedule.difficulty
                                    val newEndTimeStr = json["end_time"]?.jsonPrimitive?.contentOrNull
                                    val newEndTime = if (newEndTimeStr != null) {
                                        runCatching {
                                            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(newEndTimeStr)?.time
                                        }.getOrNull() ?: schedule.endTime
                                    } else {
                                        schedule.endTime
                                    }

                                    scheduleRepository.updateSchedule(schedule.copy(
                                        title = newTitle,
                                        priority = newPriority,
                                        urgency = newUrgency,
                                        difficulty = newDifficulty,
                                        endTime = newEndTime,
                                        updatedAt = System.currentTimeMillis()
                                    ))
                                    buildJsonObject { put("success", true) }
                                } else {
                                    buildJsonObject { put("error", "Schedule not found") }
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
                                    buildJsonObject { put("error", "Schedule not found") }
                                }
                            }
                            "delete" -> {
                                val id = json["id"]?.jsonPrimitive?.longOrNull ?: -1L
                                scheduleRepository.deleteSchedule(id)
                                buildJsonObject { put("success", true) }
                            }
                            else -> buildJsonObject { put("error", "Unknown action: $action") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Schedule operation failed") }
                    }
                }
            )
        )
    }

    fun getEmailTools(): List<Tool> {
        return listOf(
            Tool(
                name = "qq_email_service",
                description = "Send or fetch emails using QQ mailbox.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("description", "Operation type: send to send email, fetch to receive emails")
                                put("enum", JsonArray(listOf(JsonPrimitive("send"), JsonPrimitive("fetch"))))
                            })
                            put("to", buildJsonObject {
                                put("type", "string")
                                put("description", "Recipient email address, required when action=send")
                            })
                            put("subject", buildJsonObject {
                                put("type", "string")
                                put("description", "Email title, required when action=send")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Email body content, required when action=send")
                            })
                            put("limit", buildJsonObject {
                                put("type", "integer")
                                put("description", "Number of emails to fetch, min 1, max 3, default 1, used when action=fetch")
                            })
                        },
                        required = listOf("action")
                    )
                },
                execute = {
                    val settings = settingsStore.settingsFlow.value
                    val emailAccount = settings.emailConfig.account
                    val authCode = secretKeyManager.getEmailPassword("")

                    if (!settings.emailConfig.enabled || emailAccount.isBlank() || authCode.isBlank()) {
                        return@Tool buildJsonObject { put("error", "Email service not configured.") }
                    }

                    val json = it.jsonObject
                    val action = json["action"]?.jsonPrimitive?.contentOrNull ?: ""

                    withContext(Dispatchers.IO) {
                        try {
                            when (action) {
                                "send" -> {
                                    val to = json["to"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val subject = json["subject"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val content = json["content"]?.jsonPrimitive?.contentOrNull ?: ""

                                    sendQQEmail(emailAccount, authCode, to, subject, content)

                                    buildJsonObject { put("success", true); put("message", "Email sent to $to") }
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
                                else -> buildJsonObject { put("error", "Unknown action") }
                            }
                        } catch (e: Exception) {
                            buildJsonObject { put("error", e.message ?: "Email operation failed") }
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
                    subject = msg.subject ?: "(No Subject)",
                    from = msg.from?.joinToString { it.toString() } ?: "Unknown",
                    date = msg.sentDate?.toString() ?: "Unknown",
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

    data class MailData(val subject: String, val from: String, val date: String, val content: String)

    fun getAgentTaskTools(assistantId: Uuid): List<Tool> {
        return listOf(
            Tool(
                name = "agent_task_manager",
                description = "Manage automation tasks. Write an 'instruction' for your future self. At the scheduled time, you will receive this instruction as a virtual message and you can then use ANY available tool to fulfill it. This is far more flexible than static tasks.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "string")
                                put("description", "Action to perform: add, list, delete")
                                put("enum", JsonArray(listOf(JsonPrimitive("add"), JsonPrimitive("list"), JsonPrimitive("delete"))))
                            })
                            put("instruction", buildJsonObject {
                                put("type", "string")
                                put("description", "Instruction for your future self. e.g., 'Check if it will rain tomorrow in Tokyo, and if so, send an email to boss@example.com'.")
                            })
                            put("scheduled_time", buildJsonObject {
                                put("type", "integer")
                                put("description", "Timestamp (ms) when you want to receive this instruction.")
                            })
                            put("repeat_interval", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional repeat interval in ms (e.g. 86400000 for daily). 0 means once.")
                            })
                            put("task_id", buildJsonObject {
                                put("type", "integer")
                                put("description", "Required for 'delete'.")
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
                                val instruction = json["instruction"]?.jsonPrimitive?.contentOrNull ?: ""
                                val time = json["scheduled_time"]?.jsonPrimitive?.longOrNull ?: 0L
                                val repeat = json["repeat_interval"]?.jsonPrimitive?.longOrNull ?: 0L

                                val taskDataStr = buildJsonObject {
                                    put("instruction", instruction)
                                }.toString()

                                // 将变量名 entity 改为 newTaskEntity 以避免潜在冲突
                                val newTaskEntity = AgentTaskEntity(
                                    assistantId = assistantId.toString(),
                                    taskType = "AGENT_TASK",
                                    taskData = taskDataStr,
                                    scheduledTime = time,
                                    repeatInterval = repeat
                                )
                                val newId = agentTaskRepository.addTask(newTaskEntity)
                                // 确保这里传入的是 entity 副本
                                agentTaskScheduler.scheduleTask(newTaskEntity.copy(id = newId))

                                buildJsonObject { put("success", true); put("task_id", newId) }
                            }
                            "list" -> {
                                val tasks = agentTaskRepository.getTasksByAssistant(assistantId.toString()).first()
                                buildJsonObject {
                                    put("tasks", JsonArray(tasks.map { t ->
                                        buildJsonObject {
                                            put("id", t.id); put("type", t.taskType); put("scheduled_time", t.scheduledTime); put("is_executed", t.isExecuted); put("data", t.taskData)
                                        }
                                    }))
                                }
                            }
                            "delete" -> {
                                val id = json["task_id"]?.jsonPrimitive?.longOrNull ?: -1L
                                val task = agentTaskRepository.getTaskById(id)
                                if (task != null) {
                                    agentTaskRepository.deleteTask(task)
                                    agentTaskScheduler.cancelTask(id)
                                    buildJsonObject { put("success", true) }
                                } else buildJsonObject { put("error", "Task not found") }
                            }
                            else -> buildJsonObject { put("error", "Unsupported action") }
                        }
                    } catch (e: Exception) { buildJsonObject { put("error", e.message ?: "Failed") } }
                }
            )
        )
    }

    fun getTools(options: List<LocalToolOption>, assistantId: Uuid, conversationId: Uuid, userImageUrls: List<String> = emptyList()): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) tools.add(javascriptTool)
        if (options.contains(LocalToolOption.DeviceControl)) tools.addAll(getDeviceControlTools(assistantId, conversationId))
        if (options.contains(LocalToolOption.PythonEngine)) tools.addAll(getPythonTools(conversationId, userImageUrls))
        if (options.contains(LocalToolOption.ScheduleManagement)) tools.addAll(getScheduleTools())
        if (options.contains(LocalToolOption.AgentAutomation)) tools.addAll(getAgentTaskTools(assistantId))
        if (options.contains(LocalToolOption.EmailService)) tools.addAll(getEmailTools())
        return tools
    }
}
