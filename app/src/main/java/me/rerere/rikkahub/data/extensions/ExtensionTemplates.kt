package me.rerere.rikkahub.data.extensions

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 扩展导入导出（详设第十五章 + 2026-11-20 补充需求第五章）。
 *
 * 导出 = 安装目录内容打包为 .evolia（ZIP）；**绝不包含**用户业务数据
 * （mini_app_data 在 Room 中，不在安装目录）与本地凭证——程序目录与用户
 * 数据严格分离的包规范天然保证这一点。
 * 导入统一走 ExtensionInstaller。
 *
 * 二次开发权限随包携带：导出时用户选择 allowModification 写入包内 manifest.json
 * 的 development.allow_modification 字段；**磁盘安装目录不改写**（同一拓展可
 * 以不同权限重复导出）。导入后该字段随包持久化，重新导入/重装不丢失。
 */
object ExtensionImportExport {

    const val PACKAGE_EXTENSION = "evolia"
    const val MIME_TYPE = "application/zip"

    /**
     * 按导出选项改写 Manifest 的二次开发权限（纯函数，有单测）。
     * 仅设置 development.allow_modification，其余字段原样保留。
     */
    fun manifestWithDevelopmentFlag(manifest: ExtensionManifest, allowModification: Boolean): ExtensionManifest {
        return manifest.copy(development = ManifestDevelopment(allowModification = allowModification))
    }

    /**
     * 将已安装扩展打包为 .evolia 文件。
     *
     * @param allowModification 二次开发权限（用户在导出弹窗中选择），写入包内 manifest
     */
    fun exportToZip(
        context: android.content.Context,
        appId: String,
        targetFile: java.io.File,
        allowModification: Boolean = true,
    ): Boolean {
        val registry = org.koin.java.KoinJavaComponent.getKoin().get<ExtensionRegistry>()
        val installed = registry.getInstalled(appId) ?: return false
        val dir = java.io.File(context.filesDir, installed.installPath)
        if (!dir.isDirectory) return false

        val exportManifest = manifestWithDevelopmentFlag(installed.manifest, allowModification)
        val manifestContent = me.rerere.rikkahub.common.JsonInstantPretty.encodeToString(
            ExtensionManifest.serializer(),
            exportManifest,
        )

        ZipOutputStream(targetFile.outputStream().buffered()).use { zip ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach { file ->
                val entryName = file.relativeTo(dir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(entryName))
                if (entryName == "manifest.json") {
                    zip.write(manifestContent.encodeToByteArray())
                } else {
                    file.inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
        }
        return true
    }

    /**
     * 从 SAF Uri 复制到临时 .evolia 文件（不安装）。调用方负责在安装
     * （ExtensionInstaller.installFromZip）或放弃后删除临时文件。
     */
    suspend fun copyToTempFile(context: android.content.Context, uri: android.net.Uri): java.io.File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val tempFile = java.io.File.createTempFile("evolia_import_", ".evolia", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("无法读取所选文件")
                tempFile
            } catch (e: Throwable) {
                tempFile.delete()
                throw e
            }
        }

    /**
     * 安装前预扫描：从 .evolia 包宽松解析 manifest（用于安装期权限确认弹窗）。
     * 解析失败返回 null——调用方应直接走安装流程，由 Installer 给出准确错误。
     */
    suspend fun readManifestFromZip(zipFile: java.io.File): ExtensionManifest? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                java.util.zip.ZipFile(zipFile).use { zip ->
                    val entry = zip.getEntry("manifest.json") ?: return@runCatching null
                    zip.getInputStream(entry).use { input ->
                        me.rerere.rikkahub.common.JsonInstant.decodeFromString<ExtensionManifest>(
                            input.readBytes().decodeToString()
                        )
                    }
                }
            }.getOrNull()
        }

    /** 目录（本地创建工作区）版预扫描，语义同 [readManifestFromZip]。 */
    suspend fun readManifestFromDirectory(dir: java.io.File): ExtensionManifest? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ZipPackageReader().parseManifestFromDirectory(dir) }.getOrNull()
        }

    /**
     * 计算安装/更新该包前需要向用户新申请的权限：全新安装 = 全部声明权限
     * （manifest 顶层 + 全部 Tool）；更新 = 相对已装版本新增的权限。
     */
    fun permissionsNeedingGrant(manifest: ExtensionManifest, installed: List<InstalledExtension>): List<String> {
        val previouslyDeclared = installed.find { it.appId == manifest.appId }
            ?.let { it.manifest.permissions + it.manifest.tools.flatMap { tool -> tool.permissions } }
            ?.toSet()
            ?: emptySet()
        return ExtensionRules.permissionsRequiringGrant(
            previouslyDeclared = previouslyDeclared,
            manifestPermissions = manifest.permissions,
            toolPermissions = manifest.tools.flatMap { it.permissions },
        )
    }
}

/**
 * 内置示例模板（详设 Phase 5 交付项：天气 Tool Extension 示例 + 个人论坛
 * Mini App 示例）。
 */
object ExtensionTemplates {
    const val TEMPLATE_EMPTY_TOOL = "empty_tool"
    const val TEMPLATE_EMPTY_MINIAPP = "empty_miniapp"
    const val TEMPLATE_WEATHER_TOOL = "weather_tool"
    const val TEMPLATE_POMODORO_MINIAPP = "pomodoro_miniapp"

    data class TemplateDef(val id: String, val name: String, val type: String)

    val TEMPLATES = listOf(
        TemplateDef(TEMPLATE_EMPTY_TOOL, "空白 Tool Extension", ExtensionType.TOOL),
        TemplateDef(TEMPLATE_EMPTY_MINIAPP, "空白 Mini App", ExtensionType.MINI_APP),
        TemplateDef(TEMPLATE_WEATHER_TOOL, "示例：天气查询工具", ExtensionType.TOOL),
        TemplateDef(TEMPLATE_POMODORO_MINIAPP, "示例：番茄钟", ExtensionType.MINI_APP),
    )

    fun scaffold(name: String, appId: String, extensionType: String, templateId: String): Map<String, String> {
        return when (templateId) {
            TEMPLATE_WEATHER_TOOL -> weatherTool(name, appId)
            TEMPLATE_POMODORO_MINIAPP -> pomodoroMiniApp(name, appId)
            TEMPLATE_EMPTY_MINIAPP -> emptyMiniApp(name, appId)
            else -> emptyTool(name, appId)
        }
    }

    // ---------- 空白模板 ----------

    private fun emptyTool(name: String, appId: String): Map<String, String> = mapOf(
        "manifest.json" to """
{
  "manifest_version": 1,
  "app_id": "$appId",
  "name": "$name",
  "description": "自定义工具扩展",
  "extension_type": "tool",
  "version": "1.0.0",
  "tools": [
    {
      "tool_id": "my_tool",
      "name": "我的工具",
      "description": "在此填写工具用途说明",
      "entry": "tools/my_tool.js",
      "permissions": []
    }
  ],
  "permissions": []
}
""".trimIndent(),
        "tools/my_tool.js" to """
// 工具执行入口：最后一个表达式作为返回值（JSON 值）。
// 可用 SDK（同步 API，不支持 Promise）：
//   evolia.network.request({url, method, headers, body}) → {status, body}
//   evolia.storage.get/list/insert/update/delete（需对应权限）
//   evolia.ui.toast(message)（需 ui.toast 权限）
function run(args) {
  return {
    received: args,
    message: "Hello from Evolia Extension!"
  };
}

// arguments 为 Agent 传入的参数对象
run(typeof arguments === "object" ? arguments : {});
""".trimIndent(),
        "README.md" to "# $name\n\n本地创建的 Tool Extension。\n\n## 工具\n\n- my_tool：在此补充说明。\n",
    )

    private fun emptyMiniApp(name: String, appId: String): Map<String, String> = mapOf(
        "manifest.json" to """
{
  "manifest_version": 1,
  "app_id": "$appId",
  "name": "$name",
  "description": "自定义小应用",
  "extension_type": "mini_app",
  "version": "1.0.0",
  "frontend": { "entry": "web/index.html" },
  "storage": { "schema_version": 1, "collections": [] },
  "permissions": ["storage.read", "storage.write"]
}
""".trimIndent(),
        "web/index.html" to """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>$name</title>
  <style> body { font-family: sans-serif; padding: 16px; } </style>
</head>
<body>
  <h1>$name</h1>
  <p id="status">Evolia SDK 加载中…</p>
  <script>
    if (window.Evolia) {
      document.getElementById('status').textContent = 'Evolia SDK 就绪';
    }
  </script>
</body>
</html>
""".trimIndent(),
        "README.md" to "# $name\n\n本地创建的 Mini App。\n",
    )

    // ---------- 示例：天气查询 Tool Extension ----------

    private fun weatherTool(name: String, appId: String): Map<String, String> = mapOf(
        "manifest.json" to """
{
  "manifest_version": 1,
  "app_id": "$appId",
  "name": "$name",
  "description": "为 Agent 提供天气查询能力（示例扩展，使用 Open-Meteo 免费接口）",
  "extension_type": "tool",
  "version": "1.0.0",
  "tools": [
    {
      "tool_id": "get_weather",
      "name": "查询天气",
      "description": "查询指定城市的当前天气信息",
      "schema": {
        "type": "object",
        "required": ["city"],
        "properties": {
          "city": { "type": "string", "description": "城市名（如 北京 / 上海）" }
        }
      },
      "entry": "tools/get_weather.js",
      "permissions": ["network.request"]
    }
  ],
  "permissions": ["network.request"]
}
""".trimIndent(),
        "tools/get_weather.js" to """
// 查询天气（Open-Meteo 免费天气接口，无需 API Key）
function run(args) {
  var city = (args && args.city) ? args.city : "";
  if (!city) {
    return { error: "缺少 city 参数" };
  }
  // 经纬度简化：示例使用固定城市映射；真实扩展可接入地理编码接口
  var geo = {
    "北京": [39.9042, 116.4074],
    "上海": [31.2304, 121.4737],
    "广州": [23.1291, 113.2644],
    "深圳": [22.5431, 114.0579],
    "杭州": [30.2741, 120.1551],
    "beijing": [39.9042, 116.4074],
    "shanghai": [31.2304, 121.4737],
    "guangzhou": [23.1291, 113.2644],
    "shenzhen": [22.5431, 114.0579],
    "hangzhou": [30.2741, 120.1551]
  };
  var coords = geo[city] || geo[String(city).toLowerCase()];
  if (!coords) {
    return { error: "示例暂只支持：北京/上海/广州/深圳/杭州（或英文名 Beijing 等）" };
  }
  var resp = evolia.network.request({
    url: "https://api.open-meteo.com/v1/forecast?latitude=" + coords[0] +
         "&longitude=" + coords[1] + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
    method: "GET"
  });
  if (resp.status !== 200) {
    return { error: "天气接口请求失败: HTTP " + resp.status };
  }
  var data = JSON.parse(resp.body);
  var current = data.current || {};
  return {
    city: city,
    temperature: current.temperature_2m,
    humidity: current.relative_humidity_2m,
    wind_speed: current.wind_speed_10m,
    weather_code: current.weather_code,
    note: "温度单位 ℃，风速单位 km/h"
  };
}

run(typeof arguments === "object" ? arguments : {});
""".trimIndent(),
        "README.md" to "# $name\n\n示例 Tool Extension：查询天气（Open-Meteo）。\n\n## 使用\n\nAgent 调用 `get_weather`，参数 `{\"city\": \"北京\"}`。\n",
    )

    // ---------- 示例：个人论坛 Mini App ----------

    private fun pomodoroMiniApp(name: String, appId: String): Map<String, String> = mapOf(
        "manifest.json" to """
{
  "manifest_version": 1,
  "app_id": "$appId",
  "name": "$name",
  "description": "番茄钟专注计时小应用（示例），支持 25/5 循环与完成记录",
  "extension_type": "mini_app",
  "version": "1.0.0",
  "frontend": { "entry": "web/index.html" },
  "storage": {
    "schema_version": 1,
    "collections": [
      {
        "name": "sessions",
        "schema": {
          "type": "object",
          "required": ["date", "minutes"],
          "properties": {
            "date": { "type": "string" },
            "minutes": { "type": "integer", "minimum": 1 }
          }
        }
      }
    ]
  },
  "permissions": ["storage.read", "storage.write", "ui.toast"]
}
""".trimIndent(),
        "web/index.html" to """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>$name</title>
  <style>
    body { font-family: sans-serif; margin: 0; padding: 16px; background: #1e1b2e; color: #eee; text-align: center; }
    .timer { font-size: 56px; font-weight: bold; margin: 24px 0; font-variant-numeric: tabular-nums; }
    .phase { font-size: 14px; color: #aaa; margin-bottom: 8px; }
    button { padding: 10px 18px; margin: 6px; border: none; border-radius: 10px; font-size: 15px; }
    .primary { background: #7c6cf7; color: #fff; }
    .ghost { background: #333; color: #ddd; }
    .stats { margin-top: 24px; font-size: 14px; color: #bbb; }
    h1 { font-size: 18px; }
  </style>
</head>
<body>
  <h1>🍅 $name</h1>
  <div class="phase" id="phase">专注 25 分钟</div>
  <div class="timer" id="timer">25:00</div>
  <button class="primary" id="startBtn">开始</button>
  <button class="ghost" id="resetBtn">重置</button>
  <div class="stats" id="stats">今日完成番茄：加载中…</div>
  <script>
    var WORK = 25 * 60, REST = 5 * 60;
    var remaining = WORK, resting = false, tickId = null;
    var timerEl = document.getElementById('timer');
    var phaseEl = document.getElementById('phase');
    var startBtn = document.getElementById('startBtn');

    function render() {
      var m = Math.floor(remaining / 60), s = remaining % 60;
      timerEl.textContent = (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
      phaseEl.textContent = resting ? '休息 ' + (REST / 60) + ' 分钟' : '专注 ' + (WORK / 60) + ' 分钟';
    }
    function today() { return new Date().toISOString().slice(0, 10); }

    function loadStats() {
      // 注意：局部变量不能命名为 today（会遮蔽 today() 函数，模拟器实测踩坑）
      Evolia.storage.list('sessions', 1, 100).then(function (page) {
        var day = today();
        var count = 0, minutes = 0;
        page.items.forEach(function (it) {
          if (it.date === day) { count++; minutes += (it.minutes || 0); }
        });
        document.getElementById('stats').textContent =
          '今日完成番茄：' + count + ' 个（' + minutes + ' 分钟）';
      }).catch(function (e) {
        document.getElementById('stats').textContent = e.message;
      });
    }

    function stop() { if (tickId) { clearInterval(tickId); tickId = null; } startBtn.textContent = '开始'; }

    startBtn.addEventListener('click', function () {
      if (tickId) { stop(); return; }
      startBtn.textContent = '暂停';
      tickId = setInterval(function () {
        remaining--;
        if (remaining <= 0) {
          stop();
          if (!resting) {
            Evolia.storage.insert('sessions', { date: today(), minutes: WORK / 60 })
              .then(function () { return Evolia.ui.toast('完成一个番茄，休息一下！'); })
              .then(function () {
                resting = true; remaining = REST; render(); loadStats();
                tickId = setInterval(countdown, 1000); startBtn.textContent = '暂停';
              });
            return;
          } else {
            resting = false; remaining = WORK;
          }
        }
        render();
      }, 1000);
    });

    function countdown() {
      remaining--;
      if (remaining <= 0) { stop(); resting = false; remaining = WORK; }
      render();
    }

    document.getElementById('resetBtn').addEventListener('click', function () {
      stop(); resting = false; remaining = WORK; render();
    });

    if (window.Evolia) { render(); loadStats(); }
  </script>
</body>
</html>
""".trimIndent(),
        "README.md" to """
# $name

示例 Mini App：番茄钟（25/5 循环计时 + 今日完成统计，数据存 sessions 集合）。
""".trimIndent(),
    )
}
