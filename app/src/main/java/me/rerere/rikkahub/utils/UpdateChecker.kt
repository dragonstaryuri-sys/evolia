package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

// GitHub API 地址
private const val GITHUB_API_URL = "https://api.github.com/repos/dragonstaryuri-sys/evolia/releases/latest"

// GitHub 镜像站列表
private val GITHUB_MIRRORS = listOf(
    "https://ghfile.geekertao.top/",
    "https://ghproxy.com/",
    "https://ghfast.top/",
    "https://gh.ddlc.top/",
    "https://ghproxy.cc/",
    "https://ghproxy.imciel.com/",
    "https://gh.jasonzeng.dev/",
    "https://gh.monlor.com/",
    "https://proxy.gitwarp.com/"
)

private const val TAG = "UpdateChecker"

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
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

            // 挑选最快的镜像
            val fastestMirror = withTimeoutOrNull(2000) {
                findFastestMirror()
            }
            Log.d(TAG, "Fastest mirror: $fastestMirror")

            val downloads = release.assets
                .filter { it.name.endsWith(".apk") }
                .map { asset ->
                    val originalUrl = asset.browserDownloadUrl
                    val mirrorUrl = fastestMirror?.let { mirror ->
                        if (mirror.endsWith("/")) "$mirror$originalUrl" else "$mirror/$originalUrl"
                    }
                    UpdateDownload(
                        name = asset.name,
                        url = originalUrl,
                        mirrorUrl = mirrorUrl,
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

            emit(
                UiState.Success(
                    UpdateInfo(
                        version = release.tagName.removePrefix("v"),
                        publishedAt = release.publishedAt,
                        changelog = release.body ?: "",
                        downloads = sortedDownloads
                    )
                )
            )
        } else {
            throw Exception("Failed to fetch update info: ${response.code}")
        }
    }.catch {
        it.printStackTrace()
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    /**
     * 测试并寻找最快的镜像站
     */
    private suspend fun findFastestMirror(): String? = withContext(Dispatchers.IO) {
        GITHUB_MIRRORS.map { mirror ->
            async {
                val start = System.currentTimeMillis()
                try {
                    // 使用 HEAD 请求测试延迟
                    val request = Request.Builder()
                        .url(mirror)
                        .head()
                        .build()
                    client.newCall(request).await().use { resp ->
                        if (resp.isSuccessful) {
                            mirror to (System.currentTimeMillis() - start)
                        } else {
                            null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull().minByOrNull { it.second }?.first
    }

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
            // 优先使用镜像地址，如果没有则使用原始地址
            val downloadUrl = download.mirrorUrl ?: download.url
            Log.d(TAG, "Downloading update from: $downloadUrl")

            val request = DownloadManager.Request(downloadUrl.toUri()).apply {
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
    val mirrorUrl: String? = null,
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
