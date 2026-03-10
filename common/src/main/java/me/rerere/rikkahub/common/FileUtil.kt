@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)
package me.rerere.rikkahub.common

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

private const val TAG = "FileUtil"

/**
 * Delete chat files from URIs
 */
suspend fun Context.deleteChatFiles(uris: List<Uri>) = withContext(Dispatchers.IO) {
    uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
        runCatching {
            val file = uri.toFile()
            if (file.exists()) {
                file.delete()
            }
        }
    }
}

/**
 * Delete all uploaded chat files
 */
fun Context.deleteAllChatFiles() {
    val dir = this.filesDir.resolve("upload")
    if (dir.exists()) {
        dir.deleteRecursively()
    }
}

/**
 * Count chat files and their total size
 */
suspend fun Context.countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
    val dir = filesDir.resolve("upload")
    if (!dir.exists()) {
        return@withContext Pair(0, 0)
    }
    val files = dir.listFiles() ?: return@withContext Pair(0, 0)
    val count = files.size
    val size = files.sumOf { it.length() }
    Pair(count, size)
}

/**
 * Create local chat files by copying content from URIs
 */
fun Context.createChatFilesByContents(uris: List<Uri>): List<Uri> {
    val newUris = mutableListOf<Uri>()
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    uris.forEach { uri ->
        val fileName = Uuid.random()
        val file = dir.resolve("$fileName")
        if (!file.exists()) {
            file.createNewFile()
        }
        val newUri = file.toUri()
        runCatching {
            this.contentResolver.openInputStream(uri)?.use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            newUris.add(newUri)
        }.onFailure {
            it.printStackTrace()
            Log.e(TAG, "createChatFilesByContents: Failed to save image from $uri", it)
        }
    }
    return newUris
}

/**
 * Create local chat files from byte arrays
 */
fun Context.createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
    val newUris = mutableListOf<Uri>()
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    byteArrays.forEach { byteArray ->
        val fileName = Uuid.random()
        val file = dir.resolve("$fileName")
        if (!file.exists()) {
            file.createNewFile()
        }
        val newUri = file.toUri()
        file.outputStream().use { outputStream ->
            outputStream.write(byteArray)
        }
        newUris.add(newUri)
    }
    return newUris
}

/**
 * Get file name from URI
 */
fun Context.getFileNameFromUri(uri: Uri): String? {
    var fileName: String? = null
    val projection = arrayOf(
        OpenableColumns.DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    )
    runCatching {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val documentDisplayNameIndex =
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (documentDisplayNameIndex != -1) {
                    fileName = cursor.getString(documentDisplayNameIndex)
                } else {
                    val openableDisplayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (openableDisplayNameIndex != -1) {
                        fileName = cursor.getString(openableDisplayNameIndex)
                    }
                }
            }
        }
    }
    return fileName
}

/**
 * Get file MIME type from URI
 */
fun Context.getFileMimeType(uri: Uri): String? {
    return when (uri.scheme) {
        "content" -> contentResolver.getType(uri)
        else -> null
    }
}

/**
 * Get images directory
 */
fun Context.getImagesDir(): File {
    val dir = this.filesDir.resolve("images")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

/**
 * Create image file from base64 data
 */
fun Context.createImageFileFromBase64(base64Data: String, filePath: String): File {
    val data = if (base64Data.startsWith("data:image")) {
        base64Data.substringAfter("base64,")
    } else {
        base64Data
    }

    val byteArray = Base64.decode(data.toByteArray())
    val file = File(filePath)
    file.parentFile?.mkdirs()
    file.writeBytes(byteArray)
    return file
}

/**
 * List image files in the images directory
 */
fun Context.listImageFiles(): List<File> {
    val imagesDir = getImagesDir()
    return imagesDir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }?.toList()
        ?: emptyList()
}
