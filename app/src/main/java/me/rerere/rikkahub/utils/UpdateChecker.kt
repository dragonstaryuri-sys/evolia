package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

private const val GITEE_API_URL = "https://gitee.com/api/v5/repos/s0416/evolia_apk/releases/latest"

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    val response = client.newCall(
                        Request.Builder()
                            .url(GITEE_API_URL)
                            .get()
                            .addHeader(
                                "User-Agent",
                                "Evolia ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                            )
                            .build()
                    ).await()
                    if (response.isSuccessful) {
                        val release = json.decodeFromString<GiteeRelease>(response.body.string())

                        // Convert Gitee release to UpdateInfo
                        val arch = getDeviceArchitecture()
                        val downloads = release.assets
                            .filter { it.name.endsWith(".apk") }
                            .map { asset ->
                                UpdateDownload(
                                    name = asset.name,
                                    url = asset.browserDownloadUrl,
                                    size = formatFileSize(asset.size)
                                )
                            }

                        // Sort downloads to prioritize architecture match
                        val sortedDownloads = downloads.sortedByDescending { download ->
                            when {
                                download.name.contains(arch, ignoreCase = true) -> 2
                                download.name.contains("universal", ignoreCase = true) -> 1
                                else -> 0
                            }
                        }

                        UpdateInfo(
                            version = release.tagName.removePrefix("v"),
                            publishedAt = release.createdAt,
                            changelog = release.body ?: "",
                            downloads = sortedDownloads
                        )
                    } else {
                        throw Exception("Failed to fetch update info: ${response.code}")
                    }
                } catch (e: Exception) {
                    throw Exception("Failed to fetch update info", e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    private fun getDeviceArchitecture(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.any { it.contains("arm64") } -> "arm64-v8a"
            abis.any { it.contains("armeabi") } -> "armeabi-v7a"
            abis.any { it.contains("x86_64") } -> "x86_64"
            abis.any { it.contains("x86") } -> "x86"
            else -> "universal"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                setTitle("Evolia Update")
                setDescription("Downloading ${download.name}...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }.onFailure {
            Toast.makeText(context, "Failed to download update", Toast.LENGTH_SHORT).show()
            context.openUrl(download.url)
        }
    }
}

@Serializable
data class GiteeRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String,
    @SerialName("body") val body: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("assets") val assets: List<GiteeAsset> = emptyList()
)

@Serializable
data class GiteeAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val size: Long
)

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    /**
     * 将版本号分解为数字数组
     */
    private fun parseVersion(): List<Int> {
        return value.split(".")
            .map { it.toIntOrNull() ?: 0 }
    }

    /**
     * 实现 Comparable 接口的比较方法
     */
    override fun compareTo(other: Version): Int {
        val thisParts = this.parseVersion()
        val otherParts = other.parseVersion()

        val maxLength = maxOf(thisParts.size, otherParts.size)

        for (i in 0 until maxLength) {
            val thisPart = if (i < thisParts.size) thisParts[i] else 0
            val otherPart = if (i < otherParts.size) otherParts[i] else 0

            when {
                thisPart > otherPart -> return 1
                thisPart < otherPart -> return -1
            }
        }

        return 0
    }

    companion object {
        /**
         * 比较两个版本号字符串
         */
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }
    }
}

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
