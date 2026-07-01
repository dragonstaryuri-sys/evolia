package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.*
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
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateChecker"
private val GITHUB_API_URL = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

// 镜像站列表
private val GITHUB_MIRRORS = listOf(
    "https://ghfile.geekertao.top/",
    "https://ghproxy.com/",
    "https://ghfast.top/",
    "https://gh.ddlc.top/",
    "https://ghproxy.cc/",
    "https://ghproxy.imciel.com/",
    "https://gh.jasonzeng.dev/",
    "https://gh.monlor.com/",
    "https://proxy.gitwarp.com/",
    "https://gh.dpik.top/"
)

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    // 使用 IO 作用域处理耗时测速逻辑
    private val checkerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 检查更新：仅负责拉取 Release 列表
     */
    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)

        Log.d(TAG, "Fetching update info...")
        val response = client.newCall(
            Request.Builder()
                .url(GITHUB_API_URL)
                .get()
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "Evolia/${BuildConfig.VERSION_NAME}")
                .build()
        ).await()

        if (response.isSuccessful) {
            val release = json.decodeFromString<GitHubRelease>(response.body.string())

            val downloads = release.assets
                .filter { it.name.endsWith(".apk") }
                .map { asset ->
                    UpdateDownload(
                        name = asset.name,
                        url = asset.browserDownloadUrl,
                        size = formatFileSize(asset.size)
                    )
                }

            // 根据设备架构自动排序，首位即为最匹配的版本
            val sortedDownloads = downloads.sortedByDescending { download ->
                calculateMatchScore(download.name)
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
            throw Exception("GitHub API Failed: ${response.code}")
        }
    }.catch { e ->
        Log.e(TAG, "Update check failed", e)
        emit(UiState.Error(e))
    }.flowOn(Dispatchers.IO)

    /**
     * 执行更新下载
     */
    fun downloadUpdate(context: Context, download: UpdateDownload) {
        checkerScope.launch {
            Toast.makeText(context, "🚀 正在为您匹配最快下载通道...", Toast.LENGTH_SHORT).show()

            val fastestMirror = withTimeoutOrNull(5000) {
                Log.i(TAG, "Starting real-time mirror speed test...")
                findFastestMirror()
            }

            val finalUrl = fastestMirror?.let { mirror ->
                val prefix = if (mirror.endsWith("/")) mirror else "$mirror/"
                "$prefix${download.url}"
            } ?: download.url

            Log.i(TAG, "Speed test result -> Mirror: ${fastestMirror ?: "NONE"}, Final URL: $finalUrl")

            if (fastestMirror != null) {
                Toast.makeText(context, "✨ 已连接加速节点，正在起飞！", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "⚠️ 镜像连接超时，正在直连 GitHub...", Toast.LENGTH_SHORT).show()
            }

            try {
                val request = DownloadManager.Request(finalUrl.toUri()).apply {
                    setTitle("Evolia 更新下载")
                    setDescription("文件名: ${download.name}")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                    setMimeType("application/vnd.android.package-archive")
                }
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            } catch (e: Exception) {
                Log.e(TAG, "DownloadManager Error", e)
                context.openUrl(finalUrl)
            }
        }
    }

    private suspend fun findFastestMirror(): String? = withContext(Dispatchers.IO) {
        val testClient = client.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .build()

        val tasks = GITHUB_MIRRORS.map { mirror ->
            async {
                val start = System.currentTimeMillis()
                try {
                    val request = Request.Builder()
                        .url(mirror)
                        .get()
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build()

                    testClient.newCall(request).await().use { resp ->
                        val delay = System.currentTimeMillis() - start
                        if (resp.code < 500) {
                            Log.d(TAG, "Mirror available: $mirror ($delay ms)")
                            mirror to delay
                        } else null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
        tasks.awaitAll().filterNotNull().minByOrNull { it.second }?.first
    }

    /**
     * 根据文件名和设备支持的 ABI 计算匹配分数
     * 优化：优先匹配 Native ABI，并防止模拟器环境下被 ARM 包误导
     */
    private fun calculateMatchScore(fileName: String): Int {
        val name = fileName.lowercase()
        val supportedAbis = Build.SUPPORTED_ABIS
        if (supportedAbis.isEmpty()) return 0

        // 1. 优先检查是否完全匹配第一个（原生）ABI
        val primaryAbi = supportedAbis[0].lowercase()
        val isPrimaryMatch = isAbiMatch(name, primaryAbi)
        if (isPrimaryMatch) return 200 // 给一个极高的基础分

        // 2. 备选方案：遍历支持列表
        for ((index, abi) in supportedAbis.withIndex()) {
            if (isAbiMatch(name, abi.lowercase())) {
                // 根据优先级降序给分
                return 100 - index
            }
        }

        // 3. 通用版兜底
        if (name.contains("universal")) return 50

        return 0
    }

    private fun isAbiMatch(fileName: String, abi: String): Boolean {
        return when (abi) {
            "arm64-v8a" -> fileName.contains("arm64") || fileName.contains("v8a")
            "armeabi-v7a" -> fileName.contains("armeabi-v7a") || fileName.contains("v7a") || fileName.contains("armv7")
            "x86_64" -> fileName.contains("x86_64") || fileName.contains("x64")
            "x86" -> fileName.contains("x86") && !fileName.contains("x86_64")
            else -> fileName.contains(abi)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
            else -> "$bytes B"
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
