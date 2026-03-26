package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import kotlin.uuid.Uuid
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.discover.repo.ScheduleRepository
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull

class LocalTools(
    private val context: Context,
    private val scheduleRepository: ScheduleRepository
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
                                put("description", "The file URL")
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
                name = "send_notification",
                description = "Send a notification to the user",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("title", buildJsonObject { put("type", "string") })
                            put("content", buildJsonObject { put("type", "string") })
                        },
                        required = listOf("title", "content")
                    )
                },
                execute = {
                    val title = it.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: "Notification"
                    val content = it.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""

                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val channelId = "assistant_notification"
                    val channel = android.app.NotificationChannel(channelId, "Assistant", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                    notificationManager.createNotificationChannel(channel)

                    val intent = android.content.Intent(context, RouteActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("conversationId", conversationId.toString())
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(context, conversationId.hashCode(), intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

                    val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()

                    notificationManager.notify(System.currentTimeMillis().toInt(), notification)
                    buildJsonObject { put("status", "success") }
                }
            )
        )
    }

    fun getScheduleTools(): List<Tool> {
        return listOf(
            Tool(
                name = "add_schedule",
                description = "Add a new schedule/task/to-do.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "The title of the schedule")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "More details about the schedule")
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
                        },
                        required = listOf("title")
                    )
                },
                execute = {
                    val title = it.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = it.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val priority = it.jsonObject["priority"]?.jsonPrimitive?.intOrNull ?: 1
                    val urgency = it.jsonObject["urgency"]?.jsonPrimitive?.intOrNull ?: 1
                    val difficulty = it.jsonObject["difficulty"]?.jsonPrimitive?.intOrNull ?: 0
                    try {
                        scheduleRepository.addSchedule(
                            ScheduleEntity(
                                title = title,
                                content = content,
                                priority = priority,
                                urgency = urgency,
                                difficulty = difficulty,
                                startTime = System.currentTimeMillis()
                            )
                        )
                        buildJsonObject { put("success", true) }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Failed to add schedule") }
                    }
                }
            ),
            Tool(
                name = "list_schedules",
                description = "List all pending schedules/tasks and those completed today.",
                parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
                execute = {
                    try {
                        val schedules = scheduleRepository.getPendingAndTodayCompleted().first()
                        buildJsonObject {
                            put("schedules", JsonArray(schedules.map { s ->
                                buildJsonObject {
                                    put("id", s.id)
                                    put("title", s.title)
                                    put("content", s.content)
                                    put("priority", s.priority)
                                    put("urgency", s.urgency)
                                    put("difficulty", s.difficulty)
                                    put("is_completed", s.isCompleted)
                                }
                            }))
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Failed to list schedules") }
                    }
                }
            ),
            Tool(
                name = "edit_schedule",
                description = "Edit an existing schedule/task.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "integer")
                                put("description", "The ID of the schedule to edit")
                            })
                            put("title", buildJsonObject {
                                put("type", "string")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                            })
                            put("priority", buildJsonObject {
                                put("type", "integer")
                                put("description", "0: Not Important, 1: Normal, 2: Important")
                            })
                            put("urgency", buildJsonObject {
                                put("type", "integer")
                                put("description", "0: Not Urgent, 1: Normal, 2: Very Urgent")
                            })
                            put("difficulty", buildJsonObject {
                                put("type", "integer")
                                put("description", "0: Simple, 1: Normal, 2: Not Simple")
                            })
                        },
                        required = listOf("id")
                    )
                },
                execute = {
                    val id = it.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: -1L
                    try {
                        val schedule = scheduleRepository.getScheduleById(id)
                        if (schedule != null) {
                            val newTitle = it.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: schedule.title
                            val newContent = it.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: schedule.content
                            val newPriority = it.jsonObject["priority"]?.jsonPrimitive?.intOrNull ?: schedule.priority
                            val newUrgency = it.jsonObject["urgency"]?.jsonPrimitive?.intOrNull ?: schedule.urgency
                            val newDifficulty = it.jsonObject["difficulty"]?.jsonPrimitive?.intOrNull ?: schedule.difficulty

                            scheduleRepository.updateSchedule(schedule.copy(
                                title = newTitle,
                                content = newContent,
                                priority = newPriority,
                                urgency = newUrgency,
                                difficulty = newDifficulty,
                                updatedAt = System.currentTimeMillis()
                            ))
                            buildJsonObject { put("success", true) }
                        } else {
                            buildJsonObject { put("error", "Schedule not found") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Failed to edit schedule") }
                    }
                }
            ),
            Tool(
                name = "update_schedule_status",
                description = "Toggle the completion status of a schedule.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "integer")
                                put("description", "The ID of the schedule to toggle")
                            })
                        },
                        required = listOf("id")
                    )
                },
                execute = {
                    val id = it.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: -1L
                    try {
                        val schedules = scheduleRepository.getAllSchedules().first()
                        val schedule = schedules.find { s -> s.id == id }
                        if (schedule != null) {
                            scheduleRepository.toggleComplete(schedule)
                            buildJsonObject { put("success", true) }
                        } else {
                            buildJsonObject { put("error", "Schedule not found") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Failed to update schedule") }
                    }
                }
            ),
            Tool(
                name = "delete_schedule",
                description = "Delete a schedule by its ID.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "integer")
                                put("description", "The ID of the schedule to delete")
                            })
                        },
                        required = listOf("id")
                    )
                },
                execute = {
                    val id = it.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: -1L
                    try {
                        scheduleRepository.deleteSchedule(id)
                        buildJsonObject { put("success", true) }
                    } catch (e: Exception) {
                        buildJsonObject { put("error", e.message ?: "Failed to delete schedule") }
                    }
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
        return tools
    }
}
