package me.rerere.rikkahub.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import me.rerere.rikkahub.common.utils.LogUtil
import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response as DavResponse
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.GetContentLength
import at.bitfire.dav4jvm.property.GetLastModified
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SecureStore
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.sanitize
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "DataSync"

class WebdavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val secureStore: SecureStore,
    private val secretKeyManager: SecretKeyManager,
    private val conversationRepo: ConversationRepository,
) {
    suspend fun testWebdav(webDavConfig: WebDavConfig) {
        val davCollection = webDavConfig.requireCollection()
        withContext(Dispatchers.IO) {
            try {
                davCollection.propfind(depth = 1) { response, relation ->
                    LogUtil.i(TAG, "testWebdav: $response | $relation")
                }
            } catch (e: HttpException) {
                LogUtil.e(TAG, "testWebdav HttpException: code=${e.code}, message=${e.message}", e)
                val msg = mapHttpCodeToMessage(e.code, operation = "Test", providerHint = webDavConfig.providerHint())
                throw Exception(msg)
            } catch (e: Exception) {
                LogUtil.e(TAG, "testWebdav unexpected error", e)
                throw e
            }
        }
    }

    /**
     * 在还原备份或数据库升级后调用，触发数据逻辑迁移。
     */
    suspend fun triggerDataMigration() {
        try {
            LogUtil.i(TAG, "开始执行数据迁移...")

            // 1. 迁移旧会话节点数据 (将 ConversationEntity.nodes JSON 拆分到表)
            conversationRepo.migrateAllOldConversations()

            // 2. 修复时间轴乱序 & 补齐缺失的 created_at (适配旧版导入或迁移后的数据)
            conversationRepo.recomputeNodeTimestamps()

            // 3. 提取主智能体 L3 记忆中的约定到 schedules 表 (适配 18->19 升级)
            // 修改：获取处理后（已清理 masterMemoryContent）的智能体列表并写回 DataStore
            val currentSettings = settingsStore.settingsFlow.first()
            val updatedAssistants = conversationRepo.extractSchedulesFromAssistants(currentSettings.assistants)

            if (updatedAssistants != currentSettings.assistants) {
                LogUtil.i(TAG, "迁移清理完成，正在保存更新后的智能体设置...")
                settingsStore.update(currentSettings.copy(assistants = updatedAssistants))
            }

            LogUtil.i(TAG, "数据迁移执行完毕")
        } catch (e: Exception) {
            LogUtil.e(TAG, "触发数据迁移失败", e)
        }
    }

    suspend fun backupToWebDav(webDavConfig: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(webDavConfig)
        if (!file.exists() || file.length() == 0L) {
            LogUtil.e(TAG, "Backup file is empty or missing, skip upload.")
            return@withContext
        }

        val fileSizeKb = file.length() / 1024
        LogUtil.i(TAG, "Prepared backup file: ${file.name} (${fileSizeKb} KB)")

        val collection = webDavConfig.requireCollection() // Folder
        try {
            collection.ensureCollectionExists(webDavConfig)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to ensure WebDAV collection exists: ${e.message}")
            throw e
        }

        val target = webDavConfig.requireCollection(file.name) // Target File URL
        LogUtil.i(TAG, "Uploading to: ${target.location}")

        try {
            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            target.put(
                body = file.asRequestBody(mediaType),
            ) { response ->
                LogUtil.i(TAG, "backupToWebDav response code: ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = try { response.body?.string() } catch (e: Exception) { "could not read body" }
                    LogUtil.e(TAG, "WebDAV PUT Error Body: $errorBody")
                    val errorMsg = mapHttpCodeToMessage(
                        code = response.code,
                        operation = "Upload",
                        providerHint = webDavConfig.providerHint(),
                        extra = mapOf(
                            "文件大小" to "${fileSizeKb}KB",
                            "目标文件" to file.name
                        )
                    )
                    throw BackupHttpException(response.code, errorMsg)
                }
            }
            cleanupOldBackups(webDavConfig)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to upload backup to WebDAV: ${e.message}", e)
            throw e
        } finally {
            if (file.exists()) {
                file.delete()
                LogUtil.i(TAG, "Cleaned up local backup file")
            }
        }
    }

    private suspend fun cleanupOldBackups(webDavConfig: WebDavConfig) {
        try {
            val maxFiles = webDavConfig.maxBackupFiles
            if (maxFiles <= 0) return

            val files = listBackupFiles(webDavConfig).sortedByDescending { it.lastModified }

            if (files.size > maxFiles) {
                val toDelete = files.drop(maxFiles)
                LogUtil.i(TAG, "Cleaning up ${toDelete.size} old backups")
                toDelete.forEach { item ->
                    try {
                        deleteWebDavBackupFile(webDavConfig, item)
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "Failed to delete: ${item.displayName}", e)
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Cleanup failed", e)
        }
    }

    suspend fun listBackupFiles(webDavConfig: WebDavConfig): List<WebDavBackupItem> =
        withContext(Dispatchers.IO) {
            val collection = webDavConfig.requireCollection()
            val files = mutableListOf<WebDavBackupItem>()
            val nameDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

            try {
                collection.propfind(depth = 1) { response, relation ->
                    if (relation == DavResponse.HrefRelation.MEMBER) {
                        val displayName = response.properties.filterIsInstance<DisplayName>()
                            .firstOrNull()?.displayName ?: response.href.pathSegments.lastOrNull() ?: "Unknown"
                        val size = response.properties.filterIsInstance<GetContentLength>()
                            .firstOrNull()?.contentLength ?: 0L

                        val lm = response.properties.filterIsInstance<GetLastModified>()
                            .firstOrNull()?.lastModified
                        var lastModified: Instant = when (val obj = lm as Any?) {
                            is Instant -> obj
                            is java.util.Calendar -> obj.toInstant()
                            is java.util.Date -> obj.toInstant()
                            else -> Instant.EPOCH
                        }

                        if (lastModified == Instant.EPOCH) {
                            try {
                                val regex = Regex("""\d{8}_\d{6}""")
                                val match = regex.find(displayName)
                                match?.value?.let { dateStr ->
                                    val localDateTime = LocalDateTime.parse(dateStr, nameDateFormatter)
                                    lastModified = localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant()
                                }
                            } catch (e: Exception) {}
                        }
                        files.add(WebDavBackupItem(response.href.toString(), displayName, size, lastModified))
                    }
                }
            } catch (e: HttpException) {
                LogUtil.e(TAG, "List failed with HttpException: ${e.code}", e)
                val msg = mapHttpCodeToMessage(e.code, operation = "List", providerHint = webDavConfig.providerHint())
                throw Exception(msg)
            } catch (e: Exception) {
                LogUtil.e(TAG, "List failed", e)
                throw e
            }
            files
        }

    suspend fun restoreFromWebDav(webDavConfig: WebDavConfig, item: WebDavBackupItem): RestoreResult =
        withContext(Dispatchers.IO) {
            val httpClient = webDavConfig.requireClient()
            val collection = DavCollection(httpClient, item.href.toHttpUrl())
            val backupFile = File(context.cacheDir, item.displayName)
            if (backupFile.exists()) backupFile.delete()

            try {
                LogUtil.i(TAG, "Downloading backup from WebDAV: ${item.displayName}")
                collection.get(accept = "", headers = null) { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(backupFile).use { input.copyTo(it) }
                        }
                        LogUtil.i(TAG, "Download successful, size: ${backupFile.length()} bytes")
                    } else {
                        val errorBody = try { response.body?.string() } catch (e: Exception) { "could not read body" }
                        LogUtil.e(TAG, "WebDAV GET failed: code=${response.code}, message=${response.message}, body=$errorBody")
                        val msg = mapHttpCodeToMessage(response.code, operation = "Download", providerHint = webDavConfig.providerHint())
                        throw Exception(msg)
                    }
                }

                restoreFromBackupFile(backupFile, webDavConfig)
            } catch (e: Exception) {
                LogUtil.e(TAG, "Restore from WebDAV failed: ${e.message}", e)
                throw e
            } finally {
                if (backupFile.exists()) backupFile.delete()
            }
        }

    suspend fun deleteWebDavBackupFile(webDavConfig: WebDavConfig, item: WebDavBackupItem) =
        withContext(Dispatchers.IO) {
            val collection = DavCollection(webDavConfig.requireClient(), item.href.toHttpUrl())
            try {
                collection.delete { response ->
                    LogUtil.i(TAG, "Delete code: ${response.code}")
                    if (!response.isSuccessful) {
                        LogUtil.w(TAG, "WebDAV Delete failed: ${response.code} ${response.message}")
                        if (response.code == 403) {
                            throw Exception("Forbidden: Permission denied to delete file.")
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(TAG, "Delete failed for ${item.displayName}", e)
                throw e
            }
        }

    suspend fun restoreFromLocalFile(file: File, webDavConfig: WebDavConfig): RestoreResult =
        withContext(Dispatchers.IO) {
            if (!file.exists()) throw Exception("File not found")
            LogUtil.i(TAG, "Restoring from local file: ${file.absolutePath}")
            restoreFromBackupFile(file, webDavConfig)
        }

    suspend fun prepareBackupFile(webDavConfig: WebDavConfig): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "Evolia_backup_$timestamp.zip")

        LogUtil.i(TAG, "Creating backup file: ${backupFile.name}")

        // 【关键修复】：在备份前强制执行 CHECKPOINT，刷入所有 WAL 数据
        if (webDavConfig.items.contains(WebDavConfig.BackupItem.DATABASE)) {
            val dbPath = context.getDatabasePath("rikka_hub").absolutePath
            try {
                SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                    LogUtil.i(TAG, "Database checkpoint successful before backup")
                }
            } catch (e: Exception) {
                LogUtil.w(TAG, "Failed to checkpoint database: ${e.message}")
            }
        }

        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            val settings = secretKeyManager.populateSecretsForExport(settingsStore.settingsFlow.value)
            addVirtualFileToZip(zipOut, "settings.json", json.encodeToString(settings))
            LogUtil.i(TAG, "Added settings.json to backup")

            if (webDavConfig.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, "rikka_hub")
                    LogUtil.i(TAG, "Added rikka_hub to backup")
                }
                listOf("-wal", "-shm").forEach { suffix ->
                    val extra = File(dbFile.parentFile, dbFile.name + suffix)
                    if (extra.exists()) {
                        addFileToZip(zipOut, extra, "rikka_hub" + suffix)
                        LogUtil.i(TAG, "Added ${"rikka_hub" + suffix} to backup")
                    }
                }
            }

            if (webDavConfig.items.contains(WebDavConfig.BackupItem.FILES)) {
                // 收集正在使用的头像文件名
                val usedAvatarFiles = mutableSetOf<String>()
                fun collectAvatar(avatar: Avatar) {
                    if (avatar is Avatar.Image) {
                        val filename = avatar.url.substringAfterLast('/')
                        if (filename.isNotBlank()) {
                            usedAvatarFiles.add(filename)
                        }
                    }
                }
                collectAvatar(settings.displaySetting.userAvatar)
                settings.assistants.forEach { collectAvatar(it.avatar) }
                LogUtil.i(TAG, "Collected ${usedAvatarFiles.size} in-use avatar files")

                // 备份文件目录
                val foldersToBackup = listOf("upload", "avatars", "lorebook_attachments")
                foldersToBackup.forEach { folderName ->
                    val folder = File(context.filesDir, folderName)
                    if (folder.exists() && folder.isDirectory) {
                        val files = folder.listFiles()?.filter { it.isFile }
                        LogUtil.i(TAG, "Found ${files?.size ?: 0} files in $folderName folder")
                        files?.forEach { file ->
                            val shouldBackup = if (folderName == "avatars") {
                                file.name in usedAvatarFiles
                            } else {
                                true
                            }
                            if (shouldBackup) {
                                addFileToZip(zipOut, file, "$folderName/${file.name}")
                                LogUtil.d(TAG, "Added file to backup: $folderName/${file.name}")
                            } else {
                                LogUtil.d(TAG, "Skipped historical avatar: $folderName/${file.name}")
                            }
                        }
                    }
                }
            }

            if (webDavConfig.items.contains(WebDavConfig.BackupItem.TTS_CACHE)) {
                val ttsCacheDir = File(context.cacheDir, "tts_cache")
                if (ttsCacheDir.exists() && ttsCacheDir.isDirectory) {
                    val files = ttsCacheDir.listFiles()?.filter { it.isFile }
                    LogUtil.i(TAG, "Found ${files?.size ?: 0} files in tts_cache folder")
                    files?.forEach { file ->
                        addFileToZip(zipOut, file, "tts_cache/${file.name}")
                    }
                }
            }
        }
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, webDavConfig: WebDavConfig): RestoreResult =
        withContext(Dispatchers.IO) {
            LogUtil.i(TAG, "Starting restore from backup file. Size: ${backupFile.length()} bytes")
            var unsupportedBytes: Long = 0
            var settingsCleanup = BackupCleanupResult()
            val tempDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}").apply { mkdirs() }
            var sanitization = DatabaseSanitizer.SanitizationResult()

            try {
                var restoredSettings: Settings? = null
                ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        entry?.let { ze ->
                            when {
                                ze.name == "settings.json" -> {
                                    val content = zipIn.readBytes().toString(Charsets.UTF_8)
                                    val (cleaned, res) = json.decodeFromString<Settings>(content).sanitize()
                                    settingsCleanup = res
                                    restoredSettings = cleaned
                                    settingsStore.update(cleaned)
                                    LogUtil.i(TAG, "Settings restored.")
                                }
                                ze.name.startsWith("rikka_hub") -> {
                                    if (webDavConfig.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                        val targetFile = File(tempDir, ze.name)
                                        FileOutputStream(targetFile).use { zipIn.copyTo(it) }
                                    }
                                }
                                ze.name.startsWith("tts_cache/") -> {
                                    if (webDavConfig.items.contains(WebDavConfig.BackupItem.TTS_CACHE)) {
                                        val fileName = ze.name.substringAfter("/")
                                        val targetFolder = File(context.cacheDir, "tts_cache").apply { mkdirs() }
                                        val targetFile = File(targetFolder, fileName)
                                        FileOutputStream(targetFile).use { zipIn.copyTo(it) }
                                    }
                                }
                                ze.name.contains("/") -> {
                                    if (webDavConfig.items.contains(WebDavConfig.BackupItem.FILES)) {
                                        val folderName = ze.name.substringBefore("/")
                                        val fileName = ze.name.substringAfter("/")
                                        val targetFolder = File(context.filesDir, folderName).apply { mkdirs() }
                                        val targetFile = File(targetFolder, fileName)
                                        FileOutputStream(targetFile).use { zipIn.copyTo(it) }
                                    }
                                }
                                else -> {
                                    unsupportedBytes += ze.size
                                }
                            }
                            zipIn.closeEntry()
                        }
                    }
                }

                val tempDb = File(tempDir, "rikka_hub").let { if (it.exists()) it else File(tempDir, "rikka_hub.db") }

                if (tempDb.exists()) {
                    LogUtil.i(TAG, "Temporary database found at ${tempDb.name}, starting sanitization")

                    // 关键：确保同目录下的日志文件名与 tempDb 匹配，这样 SQLite 才能正确打开
                    val baseName = tempDb.name
                    listOf("-wal", "-shm").forEach { suffix ->
                        val logFile = File(tempDir, "rikka_hub$suffix")
                        if (logFile.exists() && logFile.name != baseName + suffix) {
                            logFile.renameTo(File(tempDir, baseName + suffix))
                        }
                    }

                    // 还原备份时，将解出的 Settings 传给 sanitizer 提取 L3 待办
                    val (cleanDb, res) = DatabaseSanitizer.sanitize(context, tempDb, restoredSettings)
                    sanitization = res

                    // 【新逻辑】：如果 Sanitizer 清理了设置中的旧待办内容，将其持久化
                    res.modifiedSettings?.let {
                        LogUtil.i(TAG, "Sanitizer modified settings, updating...")
                        settingsStore.update(it)
                    }

                    val finalDb = context.getDatabasePath("rikka_hub")
                    cleanDb.copyTo(finalDb, true)
                    listOf("-wal", "-shm").forEach { s ->
                        val dest = File(finalDb.path + s)
                        if (dest.exists()) dest.delete()
                    }
                }

                triggerDataMigration()
                RestoreResult(sanitization, settingsCleanup.copy(unsupportedZipEntriesBytes = unsupportedBytes))
            } catch (e: Exception) {
                LogUtil.e(TAG, "Error during restore process", e)
                throw e
            } finally {
                tempDir.deleteRecursively()
            }
        }

    data class RestoreResult(
        val sanitization: DatabaseSanitizer.SanitizationResult,
        val settingsCleanup: BackupCleanupResult
    )
}

private fun addFileToZip(zipOut: ZipOutputStream, file: File, name: String) {
    try {
        FileInputStream(file).use { fis ->
            zipOut.putNextEntry(ZipEntry(name))
            fis.copyTo(zipOut)
            zipOut.closeEntry()
        }
    } catch (e: Exception) {
        LogUtil.e("DataSync", "Zip fail: $name", e)
    }
}

private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
    zipOut.putNextEntry(ZipEntry(name))
    zipOut.write(content.toByteArray())
    zipOut.closeEntry()
}

private fun WebDavConfig.requireClient(): OkHttpClient {
    val auth = BasicDigestAuthHandler(null, username, password)
    return OkHttpClient.Builder()
        .followRedirects(false)
        .authenticator(auth)
        .addNetworkInterceptor(auth)
        // 预先发送 Basic 鉴权头。
        // 坚果云等服务商在部分路径上对无鉴权请求直接返回 403（而非标准 401+WWW-Authenticate），
        // 导致 dav4jvm 的 Authenticator 无法触发重试。预发头能显著减少这类伪 403。
        .apply {
            if (username.isNotBlank() || password.isNotBlank()) {
                addInterceptor(PreemptiveBasicAuthInterceptor(username, password))
            }
        }
        // 统一记录关键 WebDAV 响应码，方便用户排查 403/429/507 等问题
        .addInterceptor(WebdavLoggingInterceptor())
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
}

private fun WebDavConfig.requireCollection(path: String? = null): DavCollection {
    val baseUrl = this.url.trimEnd('/')
    val urlStr = buildString {
        append(baseUrl)
        if (this@requireCollection.path.isNotBlank()) {
            append("/").append(this@requireCollection.path.trim('/'))
        }
        if (path != null) {
            append("/").append(path.trim('/'))
        } else {
            append("/") // Ensure directory ends with slash
        }
    }
    return DavCollection(this.requireClient(), urlStr.toHttpUrl())
}

private fun WebDavConfig.providerHint(): String = when {
    "jianguoyun" in url || "dav.jianguoyun" in url || "nutstore" in url -> "坚果云"
    "dav.box.com" in url -> "Box"
    "webdav.teracloud" in url -> "TeraCloud"
    "webdav.yandex" in url -> "Yandex"
    "pcloud" in url -> "pCloud"
    "nextcloud" in url || "owncloud" in url -> "Nextcloud/ownCloud"
    "aliyun" in url || "aliyundrive" in url -> "阿里云盘/阿里云"
    else -> ""
}

private suspend fun DavCollection.ensureCollectionExists(webDavConfig: WebDavConfig) = withContext(Dispatchers.IO) {
    try {
        propfind(depth = 0) { _, _ -> }
    } catch (e: HttpException) {
        if (e.code == 404) {
            LogUtil.i("DataSync", "Collection not found (404), attempting to create: $location")
            mkCol(null) { response ->
                if (!response.isSuccessful) {
                    val errorBody = try { response.body?.string() } catch (ex: Exception) { null }
                    LogUtil.e("DataSync", "mkCol failed: code=${response.code}, message=${response.message}, body=$errorBody")
                    val msg = mapHttpCodeToMessage(
                        code = response.code,
                        operation = "CreateFolder",
                        providerHint = webDavConfig.providerHint()
                    )
                    throw Exception(msg)
                }
            }
        } else if (e.code == 403) {
            LogUtil.e("DataSync", "WebDAV Access Forbidden (403) on PROPFIND: $location")
            throw Exception(
                mapHttpCodeToMessage(
                    code = 403,
                    operation = "PropFind",
                    providerHint = webDavConfig.providerHint()
                )
            )
        } else throw e
    }
}

/**
 * 将常见 HTTP 错误码翻译成更有针对性、携带运营商提示的文案。
 * 尤其针对坚果云的 403（密码是「应用专用密码」，非账号密码，且有月上传流量限制）。
 */
private fun mapHttpCodeToMessage(
    code: Int,
    operation: String,
    providerHint: String,
    extra: Map<String, String> = emptyMap()
): String {
    val provider = if (providerHint.isNotBlank()) " [$providerHint]" else ""
    val extraInfo = if (extra.isEmpty()) "" else extra.entries.joinToString("，", prefix = "（", postfix = "）") { (k, v) -> "$k: $v" }
    return buildString {
        append("WebDAV $operation 失败 (HTTP $code)$provider$extraInfo: ")
        append(
            when (code) {
                400 -> "请求格式错误，请检查 URL 是否包含非法字符。"
                401 -> "账号或密码不正确。" +
                    if ("坚果云" in provider)
                        " 坚果云 WebDAV 需使用「设置-安全选项-第三方应用管理」里生成的【应用专用密码】，而不是登录密码。"
                    else " 如果是新创建的授权，请确认密码已保存。"
                403 -> "服务端拒绝访问 (Forbidden)，常见原因：① 登录账号/密码格式错（尤其是坚果云，请使用「应用专用密码」而非账号密码）；② WebDAV 根目录没有写权限；③ 坚果云免费版每月上传流量已用尽（免费用户约 1GB/月）；④ 路径拼写导致落到了无权访问的目录。"
                404 -> "目标路径不存在。请确认 URL 与 path 正确，并已在服务商后台手动创建过根目录（部分服务商禁止自动创建根目录）。"
                413 -> "备份文件过大，超出服务商限制。可暂时关闭 FILES/TTS_CACHE 备份项，或改用更大配额的 WebDAV 服务。"
                422, 423 -> "目标资源被锁定或格式不被接受，请换一个备份文件名重试。"
                429 -> "请求过于频繁，触发服务商限流。请稍后再试（坚果云短时间大量请求后常见）。"
                500, 502, 503, 504 -> "服务端异常（$code），一般是临时故障，等待稍后重试即可。"
                507 -> "磁盘配额不足（Insufficient Storage），请清理远端空间或升级套餐。"
                else -> "未分类错误 code=${code}，请查看日志或联系服务商。"
            }
        )
    }
}

/**
 * 预处理 Basic 鉴权，避免先 401 再重试的往返。
 * 对坚果云这种 "没带 Authorization 就直接 403" 的服务尤其关键。
 */
private class PreemptiveBasicAuthInterceptor(
    private val username: String,
    private val password: String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header("Authorization") != null) {
            return chain.proceed(original)
        }
        val credentials = "$username:$password"
        val encoded = Base64.encodeToString(
            credentials.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        val requestWithAuth = original.newBuilder()
            .header("Authorization", "Basic $encoded")
            .build()
        return chain.proceed(requestWithAuth)
    }
}

/**
 * 轻量日志拦截器：所有 WebDAV 请求异常都会打印 method / code / url，
 * 方便用户在日志里直接看到到底是哪一步返回了 403。
 */
private class WebdavLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            LogUtil.e(TAG, "WebDAV 请求失败: ${request.method} ${request.url} -> ${t.message}", t)
            throw t
        }
        if (!response.isSuccessful) {
            LogUtil.w(
                TAG,
                "WebDAV 返回非成功: method=${request.method} code=${response.code} url=${request.url}"
            )
        }
        return response
    }
}

/**
 * 备份相关 HTTP 异常。带 code 便于 BackupWorker 判断是否值得重试。
 */
class BackupHttpException(val code: Int, message: String) : Exception(message)

data class WebDavBackupItem(val href: String, val displayName: String, val size: Long, val lastModified: Instant)
