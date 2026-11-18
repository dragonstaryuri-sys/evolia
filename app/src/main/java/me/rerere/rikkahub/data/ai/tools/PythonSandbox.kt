package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlin.uuid.Uuid

/**
 * Manages sandboxed directories for Python execution.
 * Each assistant gets its own isolated directory (shared across that assistant's conversations).
 * Legacy layouts may still contain per-conversation directories; they are not migrated.
 */
class PythonSandbox(private val context: Context) {
    private val baseDir = File(context.filesDir, "python_sandbox")

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    /**
     * Get the sandbox working directory for a specific assistant.
     * Creates the directory if it doesn't exist.
     * 目录名即助手 id；同一助手跨会话共享沙箱（历史遗留的会话目录不迁移、不影响）。
     */
    fun getWorkDir(assistantId: Uuid): File {
        val dir = File(baseDir, assistantId.toString())
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Save output from Python to a file and return its content URI.
     */
    fun saveOutputFile(dirId: Uuid, filename: String, data: ByteArray): Uri {
        val dir = getWorkDir(dirId)
        val file = File(dir, filename)
        file.writeBytes(data)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Copy a user-provided file into the sandbox for Python to access.
     * Returns the path within the sandbox (relative to the conversation directory).
     */
    fun importFile(dirId: Uuid, sourceUri: Uri, filename: String): String {
        val dir = getWorkDir(dirId)
        val destFile = File(dir, filename)

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw java.io.FileNotFoundException("Could not open input stream for URI: $sourceUri (File not found or no permission)")

        return filename
    }

    /**
     * Get File object for a path within the sandbox.
     */
    fun getFile(dirId: Uuid, relativePath: String): File {
        val dir = getWorkDir(dirId)
        return File(dir, relativePath)
    }

    /**
     * List all files in a conversation's sandbox.
     */
    fun listFiles(dirId: Uuid): List<FileInfo> {
        val dir = getWorkDir(dirId)
        if (!dir.exists()) return emptyList()

        return dir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                FileInfo(
                    name = file.relativeTo(dir).path,
                    size = file.length(),
                    isImage = file.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp"),
                    mimeType = getMimeType(file.extension),
                    lastModified = file.lastModified()
                )
            }
            .toList()
    }

    /**
     * Delete a specific file from the sandbox.
     * Returns true if the file was deleted, false if it didn't exist.
     */
    fun deleteFile(dirId: Uuid, relativePath: String): Boolean {
        val dir = getWorkDir(dirId)
        val file = File(dir, relativePath)

        // Security: ensure file is within sandbox directory
        val realPath = file.canonicalPath
        val realDir = dir.canonicalPath
        if (!realPath.startsWith(realDir)) {
            throw SecurityException("Access denied: path outside sandbox")
        }

        return if (file.exists() && file.isFile) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Clean up a conversation's sandbox when the chat is deleted.
     */
    fun cleanupConversation(conversationId: Uuid) {
        val dir = File(baseDir, conversationId.toString())
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    /**
     * Get content URI for a file in the sandbox (for sharing/downloading).
     */
    fun getFileUri(dirId: Uuid, relativePath: String): Uri {
        val file = getFile(dirId, relativePath)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "xml" -> "text/xml"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    data class FileInfo(
        val name: String,
        val size: Long,
        val isImage: Boolean,
        val mimeType: String,
        val lastModified: Long
    )
}
