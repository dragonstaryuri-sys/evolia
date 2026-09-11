# 新增 camera_capture 本地工具：让 AI 调用摄像头拍照

## Context

用户希望 AI agent 能够调用用户设备的摄像头进行拍照，并区分前置/后置摄像头。典型场景：AI 想看看用户周围的环境、确认某样物品、理解用户的自拍视角等，从而做出更贴切的回应。

经确认的设计诉求：
- 拍照交互：AI 触发后**自动倒计时拍摄**（不需要用户手动按快门）
- 照片去向：**作为工具结果返回**，让 AI 能"看到"照片
- 摄像头选择：**AI 通过参数指定** front/back（界面不再额外提供切换按钮）

项目已具备的基础：`camera-core 1.5.1` 依赖已存在；`AndroidManifest.xml` 已声明 `CAMERA` 权限；local tools 架构（`LocalToolOption` 枚举 + `Tool` 数据结构 + `getTools` 分发）成熟；`CallCommandHub` 已验证"工具侧 emit + 业务侧 collect"的双向通信解耦模式可行。

关键技术约束：OpenAI provider 的 `role=tool` 消息 content 只输出纯字符串（`ChatCompletionsAPI.kt#L626-L635`），工具结果本身无法携带图片。因此拍完的照片需由 `GenerationHandler` 在工具执行后，作为一条额外的 user 图片消息注入到 `currentMessages`，AI 下一轮生成时即可"看到"。

## 实现步骤

### 1. 补充 CameraX 依赖
- `gradle/libs.versions.toml`：复用 `cameraCore = "1.5.1"`，新增 `androidx-camera-camera2` / `androidx-camera-lifecycle` / `androidx-camera-view` 三个库定义
- `app/build.gradle.kts`：在 `libs.androidx.camera.core` 旁补充这三条 `implementation`
  - 预览需要 `PreviewView`（camera-view）、Camera2 实现（camera-camera2）、生命周期绑定（camera-lifecycle）

### 2. 新增 LocalToolOption
`core-data/src/main/java/me/rerere/rikkahub/core/data/model/LocalToolOption.kt`
- 新增 `@SerialName("camera_capture") data object CameraCapture : LocalToolOption()`

### 3. 新增 CameraCaptureHub 双向通信桥梁
`app/src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt`（紧邻 `CallCommandHub` 定义处）
- `data class CaptureRequest(requestId: String, facing: String)`
- `data class CaptureResult(requestId: String, success: Boolean, imagePath: String?, error: String?)`
- `MutableSharedFlow<CaptureRequest>` 暴露给 UI 侧 collect
- `ConcurrentHashMap<String, CompletableDeferred<CaptureResult>>` 暂存 pending 请求
- `suspend fun requestCapture(facing: String): CaptureResult`：生成 requestId，emit 请求，`withTimeoutOrNull(60_000)` 等待结果，超时返回失败
- `fun deliverResult(result: CaptureResult)`：从 map 移除并 `complete` 对应 deferred
- 参考 `CallCommandHub`（同文件 L83-L106）的解耦注释风格

### 4. 新增 camera_capture 工具
`LocalTools.kt`，新增 `fun getCameraCaptureTool(): List<Tool>`：
- `name = "camera_capture"`
- `description`：调用用户摄像头自动倒计时拍摄一张照片；`facing=back` 适合拍景物/文档，`facing=front` 适合自拍/用户视角；照片会作为结果返回并供 AI 查看
- `parameters`：`facing` (string, enum: ["back","front"], required)
- `execute`：读 `facing`（默认 back），`withContext(Dispatchers.IO)` 调 `CameraCaptureHub.requestCapture(facing)`，返回 JSON
  - 成功：`{success: true, image_path, facing, note: "已拍摄，照片已附给 AI 查看"}`
  - 失败：`{success: false, error}`

### 5. 在 getTools 注册
`LocalTools.kt` `getTools`（L2315-L2339）末尾追加：
```kotlin
if (options.contains(LocalToolOption.CameraCapture)) tools.addAll(getCameraCaptureTool())
```
注：不加入 `ChatService.kt` L320-L330 的 Release 核心工具强制列表，保持用户手动开启。

### 6. 让 AI 看到照片（GenerationHandler 注入图片消息）
`app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`，在 L547 `results.forEach {...}` 之后、L550 构建 TOOL UIMessage 之前：
- 扫描 `results`，若 `toolName == "camera_capture"` 且 content 含 `success=true` 与 `image_path`：
  - 把 `image_path` 转成 `UIMessagePart.Image`（参考 `createCompressedImageFromBase64` / `getImagesDir` 的本地图片处理）
  - 追加一条 `UIMessage(role=USER, parts=[Text("（这是刚刚用$facing摄像头拍摄的照片）"), Image(...)])` 到 `currentMessages`（在 TOOL 消息之后）
- 这样下一轮 AI 生成时既能看到 tool result JSON，又能直接看到照片本身
- 只针对 `camera_capture` 工具名处理，不影响其他工具

### 7. 新增相机拍照 Overlay Composable
新建 `app/src/main/java/me/rerere/rikkahub/ui/components/camera/CameraCaptureOverlay.kt`：
- CameraX `PreviewView`（AndroidView + LocalLifecycleOwner）
- `ImageCapture` use case，按 `facing` 选择 `CameraSelector.DEFAULT_FRONT/back`
- 状态机：`Idle → Countdown(3,2,1) → Capturing → Done`
- 倒计时 UI：大号数字 + "AI 正在用${facing=='front'?'前置':'后置'}摄像头拍照"
- 拍完：Bitmap → 压缩为 WebP（质量 80，max 长边 1280，参考日记图片 <500kb 约束）→ 存入 `getImagesDir` 子目录 `camera_captures/`
- 取消按钮：用户可中断 → `deliverResult(success=false, error="用户取消了拍照")`
- 完成后：`deliverResult(success=true, imagePath=绝对路径)` 并隐藏 overlay
- 预加载/权限缺失时显示友好提示

### 8. ChatPage 集成收集器 + Overlay
`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`：
- 新增 `rememberLauncherForActivityResult` / 权限检查（参考 L246-L256 的 `RECORD_AUDIO` 模式）请求 `CAMERA`
- `LaunchedEffect` collect `CameraCaptureHub.requests`：收到请求 → 检查 CAMERA 权限 → 不足则请求 launcher → 足则把请求塞入 overlay state
- overlay state（`var pendingCapture by remember { mutableStateOf<CaptureRequest?>(null) }`）非空时渲染 `CameraCaptureOverlay` 全屏覆盖在聊天页之上
- 若不在前台/会话已退出，`deliverResult(success=false, error="当前无法拍照")`

### 9. AssistantToolsSubPage 开关
`app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantToolsSubPage.kt`：
- 在 LOCAL TOOLS GROUP（DeviceControl 旁）新增 `SettingGroupItem` 开关
- 开启时请求 `Manifest.permission.CAMERA`（参考 L186-L195 DeviceControl 的 `deviceControlPermissionLauncher` 模式）
- 关闭时从 `localTools` 移除 `CameraCapture`

### 10. 字符串资源
`app/src/main/res/values/strings.xml` 与 `values-zh` (如有)：
- `assistant_tools_camera_capture_title` "摄像头拍照"
- `assistant_tools_camera_capture_desc` "允许 AI 调用设备摄像头拍照（可指定前后摄像头）"
- overlay 内文案：倒计时、取消、权限提示等

## 关键复用点
- `CallCommandHub`（`LocalTools.kt#L83-L106`）：双向通信解耦模式
- `VoiceCallManager#L142-L156`：UI 侧 collect 工具请求的范例
- `DiaryEditorPage.kt#L144-L154`：相机 launcher + 图片处理模式（系统 Intent，本次改用 CameraX）
- `ChatPage.kt#L246-L256`：运行时权限请求范例
- `AssistantToolsSubPage.kt#L186-L195`：DeviceControl 开关 + 权限请求范例
- `getImagesDir` / `createCompressedImageFromBase64`（`utils`）：本地图片存储与压缩
- `UIMessagePart.Image`：多模态图片 part（`ChatCompletionsAPI.kt#L695-L707` 已支持 base64 编码送出）

## 验证条目（用户自测）
1. 助手详情页 → 工具子页，能看到"摄像头拍照"开关，开启时弹出 CAMERA 权限请求
2. AI 在对话中决定拍照时（如用户说"看看我桌上有什么"），弹出全屏相机 overlay
3. overlay 按 AI 指定的摄像头（前/后）启动预览，显示 3-2-1 倒计时
4. 倒计时结束自动拍摄，照片保存到本地，overlay 消失
5. AI 下一轮回复能基于照片内容回答（说明图片成功注入 AI 上下文）
6. 测试 `facing=front` 与 `facing=back` 分别使用正确摄像头
7. 倒计时期间点取消，工具返回失败，AI 收到"用户取消"反馈
8. 未授予 CAMERA 权限时，工具返回友好错误而非崩溃
9. 拍照期间退出聊天页/会话，工具返回失败，不留悬挂请求
10. 照片文件大小合理（<500kb，WebP 格式），不占用过多存储
11. 多次连续拍照正常工作（requestId 不混淆）
12. 60 秒超时保护生效（如 overlay 未响应）
