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

// GitHub API 地址
private const val GITHUB_API_URL = "https://api.github.com/repos/dragonstaryuri-sys/evolia/releases/latest"
// GitHub 下载加速前缀
private const val GH_PROXY = "https://mirror.ghproxy.com/"

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    val response = client.newCall(
                        Request.Builder()
                            .url(GITHUB_API_URL)
                            .get()
                            .addHeader("Accept", "application/vnd.github+json")
                            .addHeader(
                                "User-Agent",
                                "Evolia ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                            )
                            .build()
                    ).await()
                    if (response.isSuccessful) {
                        val release = json.decodeFromString<GitHubRelease>(response.body.string())

                        // 获取架构信息
                        val arch = getDeviceArchitecture()
                        val downloads = release.assets
                            .filter { it.name.endsWith(".apk") }
                            .map { asset ->
                                UpdateDownload(
                                    name = asset.name,
                                    // 拼接加速镜像地址
                                    url = GH_PROXY + asset.browserDownloadUrl,
                                    size = formatFileSize(asset.size)
                                )
                            }

                        // 排序：优先匹配当前架构
                        val sortedDownloads = downloads.sortedByDescending { download ->
                            when {
                                download.name.contains(arch, ignoreCase = true) -> 2
                                download.name.contains("universal", ignoreCase = true) -> 1
                                else -> 0
                            }
                        }

                        UpdateInfo(
                            version = release.tagName.removePrefix("v"),
                            publishedAt = release.publishedAt,
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
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String,
    @SerialName("body") val body: String? = null,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
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
 * 版本号值类
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {
    private fun parseVersion(): List<Int> = value.split(".").map { it.toIntOrNull() ?: 0 }

    override fun compareTo(other: Version): Int {
        val thisParts = this.parseVersion()
        val otherParts = other.parseVersion()
        val maxLength = maxOf(thisParts.size, otherParts.size)
        for (i in 0 until maxLength) {
            val thisPart = if (i < thisParts.size) thisParts[i] else 0
            val otherPart = if (i < otherParts.size) otherParts[i] else 0
            if (thisPart > otherPart) return 1
            if (thisPart < otherPart) return -1
        }
        return 0
    }

    companion object {
        fun compare(version1: String, version2: String): Int = Version(version1).compareTo(Version(version2))
    }
}

operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
