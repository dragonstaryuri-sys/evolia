package me.rerere.rikkahub.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import me.rerere.rikkahub.utils.LogUtil
import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response as DavResponse
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.GetContentLength
import at.bitfire.dav4jvm.property.GetLastModified
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SecureStore
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.sanitize
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
                val msg = when (e.code) {
                    401 -> "Unauthorized: Check your WebDAV username and password."
                    403 -> "Forbidden: Check your permissions or path. Your WebDAV provider might require specific settings for this directory."
                    404 -> "Not Found: Check your WebDAV URL and path. Ensure the root folder exists."
                    else -> "WebDAV Test failed with code ${e.code}: ${e.message}"
                }
                throw Exception(msg)
            } catch (e: Exception) {
                LogUtil.e(TAG, "testWebdav unexpected error", e)
                throw e
            }
        }
    }

    suspend fun backupToWebDav(webDavConfig: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(webDavConfig)
        if (!file.exists() || file.length() == 0L) {
            LogUtil.e(TAG, "Backup file is empty or missing, skip upload.")
            return@withContext
        }

        LogUtil.i(TAG, "Prepared backup file: ${file.name} (${file.length() / 1024} KB)")

        val collection = webDavConfig.requireCollection() // Folder
        try {
            collection.ensureCollectionExists()
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
                    val errorMsg = when (response.code) {
                        401 -> "Unauthorized: Invalid WebDAV credentials."
                        403 -> "Forbidden: Permission denied. Check if your WebDAV provider allows file uploads to this path or if your storage quota is full."
                        413 -> "Payload Too Large: The backup file might be too big for your WebDAV provider."
                        else -> "WebDAV PUT failed with code ${response.code}: ${response.message}"
                    }
                    throw Exception(errorMsg)
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
                val msg = when (e.code) {
                    401 -> "Unauthorized: Check your WebDAV username and password."
                    403 -> "Forbidden: Check your permissions or path. Your WebDAV provider might require specific settings for this directory."
                    404 -> "Not Found: Check your WebDAV URL and path. Ensure the root folder exists."
                    else -> "WebDAV List failed with code ${e.code}: ${e.message}"
                }
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
                        val msg = when (response.code) {
                            401 -> "Unauthorized: Invalid WebDAV credentials."
                            403 -> "Forbidden: You don't have permission to download this file."
                            else -> "Download failed with code ${response.code}: ${response.message}"
                        }
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
                    // 统一命名为 rikka_hub 开头，不带 .db 后缀以免混淆
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
                // 备份文件目录
                val foldersToBackup = listOf("upload", "avatars", "lorebook_attachments")
                foldersToBackup.forEach { folderName ->
                    val folder = File(context.filesDir, folderName)
                    if (folder.exists() && folder.isDirectory) {
                        val files = folder.listFiles()?.filter { it.isFile }
                        LogUtil.i(TAG, "Found ${files?.size ?: 0} files in $folderName folder")
                        files?.forEach {
                            addFileToZip(zipOut, it, "$folderName/${it.name}")
                            LogUtil.d(TAG, "Added file to backup: $folderName/${it.name}")
                        }
                    }
                }
            }
        }
        backupFile
    }

    data class RestoreResult(
        val sanitization: DatabaseSanitizer.SanitizationResult,
        val settingsCleanup: BackupCleanupResult
    )

    private suspend fun restoreFromBackupFile(backupFile: File, webDavConfig: WebDavConfig): RestoreResult =
        withContext(Dispatchers.IO) {
            LogUtil.i(TAG, "Starting restore from backup file. Size: ${backupFile.length()} bytes")
            var unsupportedBytes: Long = 0
            var settingsCleanup = BackupCleanupResult()
            val tempDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}").apply { mkdirs() }
            var sanitization = DatabaseSanitizer.SanitizationResult()

            try {
                ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        entry?.let { ze ->
                            LogUtil.i(TAG, "Extracting zip entry: ${ze.name}")
                            when {
                                ze.name == "settings.json" -> {
                                    val content = zipIn.readBytes().toString(Charsets.UTF_8)
                                    val (cleaned, res) = json.decodeFromString<Settings>(content).sanitize()
                                    settingsCleanup = res
                                    settingsStore.update(cleaned)
                                    LogUtil.i(TAG, "Settings restored. Cleanup results: $res")
                                }
                                ze.name.startsWith("rikka_hub") -> { // 只要以 rikka_hub 开头都提取，包括带有 .db 后缀的旧版本
                                    if (webDavConfig.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                        val targetFile = File(tempDir, ze.name)
                                        FileOutputStream(targetFile).use { zipIn.copyTo(it) }
                                        LogUtil.i(TAG, "Database component extracted: ${ze.name}")
                                    }
                                }
                                ze.name.contains("/") -> {
                                    // 处理子目录文件 (upload/, avatars/, lorebook_attachments/)
                                    if (webDavConfig.items.contains(WebDavConfig.BackupItem.FILES)) {
                                        val folderName = ze.name.substringBefore("/")
                                        val fileName = ze.name.substringAfter("/")
                                        val targetFolder = File(context.filesDir, folderName).apply { mkdirs() }
                                        val targetFile = File(targetFolder, fileName)
                                        FileOutputStream(targetFile).use { zipIn.copyTo(it) }
                                        LogUtil.d(TAG, "File extracted: ${ze.name}")
                                    }
                                }
                                else -> {
                                    LogUtil.w(TAG, "Unsupported entry in backup: ${ze.name}")
                                    unsupportedBytes += ze.size
                                }
                            }
                            zipIn.closeEntry()
                        }
                    }
                }

                // 尝试查找主数据库文件，可能是 rikka_hub 或 rikka_hub.db
                val tempDb = File(tempDir, "rikka_hub").let { if (it.exists()) it else File(tempDir, "rikka_hub.db") }

                if (tempDb.exists()) {
                    LogUtil.i(TAG, "Temporary database found at ${tempDb.name}, starting sanitization")

                    // 关键：确保同目录下的日志文件名与 tempDb 匹配，这样 SQLite 才能正确打开
                    val baseName = tempDb.name
                    listOf("-wal", "-shm").forEach { suffix ->
                        val logFile = File(tempDir, "rikka_hub$suffix") // 之前版本可能存的名字
                        if (logFile.exists() && logFile.name != baseName + suffix) {
                            logFile.renameTo(File(tempDir, baseName + suffix))
                        }
                    }

                    val (cleanDb, res) = DatabaseSanitizer.sanitize(context, tempDb)
                    sanitization = res

                    val finalDb = context.getDatabasePath("rikka_hub")
                    cleanDb.copyTo(finalDb, true)
                    LogUtil.i(TAG, "Final database moved to: ${finalDb.absolutePath}")

                    // 恢复后清理掉 finalDb 可能存在的旧日志文件
                    listOf("-wal", "-shm").forEach { s ->
                        val dest = File(finalDb.path + s)
                        if (dest.exists()) dest.delete()
                    }
                } else {
                    LogUtil.w(TAG, "No rikka_hub database found in the backup ZIP entries")
                }
                LogUtil.i(TAG, "Restore process completed successfully.")
                RestoreResult(sanitization, settingsCleanup.copy(unsupportedZipEntriesBytes = unsupportedBytes))
            } catch (e: Exception) {
                LogUtil.e(TAG, "Error during restore process", e)
                throw e
            } finally {
                tempDir.deleteRecursively()
            }
        }
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

private suspend fun DavCollection.ensureCollectionExists() = withContext(Dispatchers.IO) {
    try {
        propfind(depth = 0) { _, _ -> }
    } catch (e: HttpException) {
        if (e.code == 404) {
            LogUtil.i("DataSync", "Collection not found (404), attempting to create: $location")
            mkCol(null) { response ->
                if (!response.isSuccessful) {
                    val errorBody = try { response.body?.string() } catch (ex: Exception) { null }
                    LogUtil.e("DataSync", "mkCol failed: code=${response.code}, message=${response.message}, body=$errorBody")
                    if (response.code == 403) {
                        throw Exception("Forbidden (403): Failed to create directory. Please check if your account has permissions or if the path is valid.")
                    }
                    throw Exception("Failed to create WebDAV collection: ${response.code} ${response.message}")
                }
            }
        } else if (e.code == 403) {
            LogUtil.e("DataSync", "WebDAV Access Forbidden (403) on PROPFIND: $location")
            throw Exception("Forbidden (403): Access denied. Check your credentials and permissions. Some providers require manual folder creation.")
        } else throw e
    }
}

data class WebDavBackupItem(val href: String, val displayName: String, val size: Long, val lastModified: Instant)
