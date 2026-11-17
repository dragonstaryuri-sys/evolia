package me.rerere.rikkahub.data.extensions

import me.rerere.rikkahub.core.data.db.entity.ExtensionEntity
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 扩展导入导出（详设第十五章）。
 *
 * 导出 = 安装目录内容打包为 .evolia（ZIP）；**绝不包含**用户业务数据
 * （mini_app_data 在 Room 中，不在安装目录）与本地凭证——程序目录与用户
 * 数据严格分离的包规范天然保证这一点。
 * 导入统一走 ExtensionInstaller。
 */
object ExtensionImportExport {

    const val PACKAGE_EXTENSION = "evolia"
    const val MIME_TYPE = "application/zip"

    /**
     * 将已安装扩展打包为 .evolia 文件。
     */
    fun exportToZip(context: android.content.Context, appId: String, targetFile: java.io.File): Boolean {
        val registry = org.koin.java.KoinJavaComponent.getKoin().get<ExtensionRegistry>()
        val installed = registry.getInstalled(appId) ?: return false
        val dir = java.io.File(context.filesDir, installed.installPath)
        if (!dir.isDirectory) return false

        ZipOutputStream(targetFile.outputStream().buffered()).use { zip ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach { file ->
                val entryName = file.relativeTo(dir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return true
    }

    /**
     * 从 SAF Uri 导入：复制到临时文件后进入统一 Installer（staging/校验/回滚）。
     */
    suspend fun importFromUri(
        context: android.content.Context,
        uri: android.net.Uri,
    ): ExtensionEntity {
        val installer = org.koin.java.KoinJavaComponent.getKoin().get<ExtensionInstaller>()
        val tempFile = java.io.File.createTempFile("evolia_import_", ".evolia", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("无法读取所选文件")
            return installer.installFromZip(tempFile, ExtensionSourceType.LOCAL_IMPORT)
        } finally {
            tempFile.delete()
        }
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
    const val TEMPLATE_FORUM_MINIAPP = "forum_miniapp"

    data class TemplateDef(val id: String, val name: String, val type: String)

    val TEMPLATES = listOf(
        TemplateDef(TEMPLATE_EMPTY_TOOL, "空白 Tool Extension", ExtensionType.TOOL),
        TemplateDef(TEMPLATE_EMPTY_MINIAPP, "空白 Mini App", ExtensionType.MINI_APP),
        TemplateDef(TEMPLATE_WEATHER_TOOL, "示例：天气查询工具", ExtensionType.TOOL),
        TemplateDef(TEMPLATE_FORUM_MINIAPP, "示例：个人论坛", ExtensionType.MINI_APP),
    )

    fun scaffold(name: String, appId: String, extensionType: String, templateId: String): Map<String, String> {
        return when (templateId) {
            TEMPLATE_WEATHER_TOOL -> weatherTool(name, appId)
            TEMPLATE_FORUM_MINIAPP -> forumMiniApp(name, appId)
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

    private fun forumMiniApp(name: String, appId: String): Map<String, String> = mapOf(
        "manifest.json" to """
{
  "manifest_version": 1,
  "app_id": "$appId",
  "name": "$name",
  "description": "用于管理个人帖子和讨论内容（示例小应用）",
  "extension_type": "mini_app",
  "version": "1.0.0",
  "frontend": { "entry": "web/index.html" },
  "storage": {
    "schema_version": 1,
    "collections": [
      { "name": "posts", "schema": "schemas/posts.schema.json" }
    ]
  },
  "permissions": ["storage.read", "storage.write", "ui.toast"]
}
""".trimIndent(),
        "schemas/posts.schema.json" to """
{
  "type": "object",
  "required": ["title", "content"],
  "properties": {
    "title": { "type": "string", "minLength": 1, "maxLength": 100 },
    "content": { "type": "string" },
    "author": { "type": "string" }
  }
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
    body { font-family: sans-serif; margin: 0; padding: 16px; background: #f5f5f5; }
    h1 { font-size: 20px; }
    .card { background: #fff; border-radius: 12px; padding: 12px 16px; margin-bottom: 10px; box-shadow: 0 1px 3px rgba(0,0,0,.08); }
    .card h3 { margin: 0 0 6px; font-size: 16px; }
    .card p { margin: 0; color: #555; font-size: 14px; white-space: pre-wrap; }
    .meta { color: #999; font-size: 12px; margin-top: 6px; }
    form { background: #fff; border-radius: 12px; padding: 12px 16px; margin-bottom: 16px; }
    input, textarea { width: 100%; box-sizing: border-box; padding: 8px; margin: 6px 0; border: 1px solid #ddd; border-radius: 8px; font-size: 14px; }
    button { width: 100%; padding: 10px; background: #4f6ef7; color: #fff; border: none; border-radius: 8px; font-size: 15px; }
    .empty { text-align: center; color: #999; padding: 32px 0; }
  </style>
</head>
<body>
  <h1>$name</h1>
  <form id="postForm">
    <input id="title" placeholder="帖子标题" required>
    <textarea id="content" placeholder="帖子内容" rows="3" required></textarea>
    <button type="submit">发布帖子</button>
  </form>
  <div id="list"><div class="empty">加载中…</div></div>
  <script>
    var listEl = document.getElementById('list');

    function render(posts) {
      if (!posts.length) {
        listEl.innerHTML = '<div class="empty">还没有帖子，发第一篇吧</div>';
        return;
      }
      listEl.innerHTML = '';
      posts.forEach(function (p) {
        var card = document.createElement('div');
        card.className = 'card';
        var h = document.createElement('h3');
        h.textContent = p.title;
        var body = document.createElement('p');
        body.textContent = p.content;
        var meta = document.createElement('div');
        meta.className = 'meta';
        meta.textContent = (p.author || '匿名') + ' · ' + new Date(p.created_at || Date.now()).toLocaleString();
        var del = document.createElement('button');
        del.textContent = '删除';
        del.style.background = '#eee';
        del.style.color = '#c00';
        del.style.marginTop = '8px';
        del.onclick = function () { remove(p.id); };
        card.appendChild(h); card.appendChild(body); card.appendChild(meta); card.appendChild(del);
        listEl.appendChild(card);
      });
    }

    function load() {
      Evolia.storage.list('posts', 1, 50).then(function (page) {
        render(page.items);
      }).catch(function (e) {
        listEl.innerHTML = '<div class="empty">' + e.message + '</div>';
      });
    }

    function remove(id) {
      Evolia.storage.delete('posts', id).then(load);
    }

    document.getElementById('postForm').addEventListener('submit', function (ev) {
      ev.preventDefault();
      var title = document.getElementById('title').value.trim();
      var content = document.getElementById('content').value.trim();
      if (!title || !content) return;
      Evolia.storage.insert('posts', { title: title, content: content, author: '我', created_at: Date.now() })
        .then(function () {
          document.getElementById('title').value = '';
          document.getElementById('content').value = '';
          Evolia.ui.toast('发布成功');
          load();
        })
        .catch(function (e) { Evolia.ui.toast('发布失败：' + e.message); });
    });

    if (window.Evolia) { load(); }
    else { listEl.innerHTML = '<div class="empty">Evolia SDK 不可用</div>'; }
  </script>
</body>
</html>
""".trimIndent(),
        "README.md" to "# $name\n\n示例 Mini App：个人论坛（posts 集合 CRUD + 原生 Toast）。\n",
    )
}
