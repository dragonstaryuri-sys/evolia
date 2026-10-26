# AGENTS.md

## 1. Core Principles & Design Philosophy

**App Name:** Evolia (Paradise of Evolution)

**Design Philosophy:**
Minimal / Clean UI. Less is more.

## 2. Vision & Purpose
**“We are not born complete; it is in the collision with the world that we constantly evolve into better versions of ourselves.”**

Evolia is an AI companion focused on "Personal Growth" and "Soul Resonance". It is designed to be the digital other half of your life—growing with you through deep understanding, emotional intelligence, and proactive support.

## 3. Architecture & Codebase Structure

### Modules
-   `app/`: Main application module. Contains UI (Compose), Core Logic, DI, Data Layers, and Room Database.
-   `ai/`: Abstraction layer for AI providers (OpenAI, Google, Anthropic).
-   `common/`: Shared utilities and extensions.
-   `highlight/`: Syntax highlighting features.
-   `search/`: Search functionality (Exa, Tavily, Zhipu).
-   `tts/`: Text-to-Speech implementation.

### Key Technologies
-   **Language:** Kotlin (uses experimental `kotlin.uuid.Uuid`).
-   **UI:** Jetpack Compose (Material You 3 Expressive / Android 16).
-   **Dependency Injection:** Koin.
-   **Database:** Room.
-   **Network:** OkHttp (with SSE support).
-   **Serialization:** Kotlinx Serialization.

### Key Entry Points (app module, path relative to `app/src/main/java/me/rerere/rikkahub/`)
-   **Chat Generation Chain**: `service/ChatService.kt`（组装上下文与工具列表，收集生成流）→ `data/ai/GenerationHandler.kt`（`generateText` 多步工具循环，`maxSteps` 上限；`Tool.systemPrompt` 在此注入系统提示）→ `ai/` 模块各 Provider（OpenAI / Google / Claude）。
-   **Tool Registration**: `data/ai/tools/LocalTools.kt`（本地工具，按 `Assistant.localTools` 选项装配）、`service/ChatService.kt` 内的 `createSearchTool`（联网搜索）、`data/ai/tools/McpAgentTools.kt`（MCP 固定工具）。
-   **MCP**: `data/ai/mcp/McpManager.kt`（连接/同步/调用，Koin single）、`data/ai/mcp/McpConfig.kt`（数据模型）、`ui/pages/setting/McpToolDescVM.kt` + `SettingMcpToolDescPage.kt`（工具描述管理）。
-   **Settings**: `data/datastore/PreferencesStore.kt`（`SettingsStore`：DataStore 持久化、`Settings` 模型、V1–V4 偏好迁移；DI 注册于 `di/DataSourceModule.kt`）。
-   **Room Database**: `data/db/AppDatabase.kt`（`version = 40`；新增 schema 变更必须新增 `MIGRATION_n_n` 并在 `di/DataSourceModule.kt` 的 `addMigrations` 中注册，禁止依赖 fallbackToDestructiveMigration）。

## 4. Coding Standards & Best Practices

### Performance & Concurrency
-   **I/O Operations:** MUST be explicitly executed on `Dispatchers.IO`.
-   *Crucial:* `AppScope` defaults to `Dispatchers.Default`. Do not block the main thread or the default dispatcher with I/O.
-   **Compose Optimization:**
-   **Lists:** Never pass mutable collections (`SnapshotStateList`) directly to `LazyColumn` items. Use `derivedStateOf` to pass simple, immutable states (e.g., `Boolean`) to prevent unnecessary recompositions.
-   **AI Context:** Prioritize token economy and vector memory efficiency. Use caching (Prefix Caching optimized).

### Robustness & Safety
-    JSON Handling:
-   **STRICTLY PROHIBITED:** Non-null assertions (`!!`) on JSON elements.
-   **REQUIRED:** Use safe type checks (`is JsonArray`, `jsonPrimitiveOrNull`).
-   **State Management:**
-   When updating `StateFlow` in services (e.g., `ChatService`), **snapshot** the current value into a local variable before applying complex transformations to avoid race conditions.
-   **Settings 事务（强制）**: `Settings` 是 DataStore 中的单个大对象。**禁止**「读 `settingsFlow.value` 快照 → `copy` → `settingsStore.update(全量对象)`」的写法——多个组件（MCP 服务同步、描述编辑等）并发执行时，后写者会基于陈旧快照整体覆盖，丢失其他写入方的修改。增量修改必须使用原子变换事务 `settingsStore.update { settings -> settings.copy(...) }`（锁内基于最新快照执行）；transform 必须是纯函数，且**不得**在内部再调用 `update`（锁不可重入）。

### Readability & Maintainability
-   **Complex Logic:** Extract conditional expressions, calculations, and multi-step logic into **named local variables** (e.g., `val reason`, `val isActivated`) instead of inlining them directly into constructor or function parameters.
-   **Branching & Formatting:** Do not excessively compress multi-line logic into a single line. Preserve clear indentation and structure for debugging and future maintenance.
-   **Clarity Over Brevity:** Prioritize readable, understandable code over overly terse or compact syntax. Avoid hidden side effects or ambiguous expressions.

### Serialization
-   Use `me.rerere.rikkahub.utils.JsonInstant` (or `JsonInstantPretty`).
  -   *Note:* It ignores unknown keys but **does not** apply snake_case strategies. Field mapping must be manual for external APIs.

## 5. UI/UX Guidelines

### Design Language
-   **Standard:** Material You 3 Expressive / Android 16.
-   **Shapes:** Adhere strictly to `me.rerere.rikkahub.ui.theme.AppShapes`:
    -   **Cards:** `AppShapes.CardLarge` (28.dp), `AppShapes.CardMedium` (24.dp).
    -   **Buttons:** `AppShapes.ButtonPill` (50%).

### Animation
-   Keep animations subtle and purposeful. Avoid over-animation.

### Theme & Dark/Light Adaptation (强制)
-   **必须双模式自检**：任何新增/修改的 UI（页面、卡片、气泡、弹窗、BottomSheet、菜单、输入框、列表项）都必须同时在浅色（`ColorMode.LIGHT`）与深色（`ColorMode.DARK / SYSTEM`）下检查，禁止只按浅色效果交付。
-   **优先使用主题语义色**：背景/容器用 `MaterialTheme.colorScheme.surface*`，文字用 `onSurface / onSurfaceVariant`，强调用 `primary / error` 等；未覆盖的语义用 `MaterialTheme.extendColors`（已按深浅色成对提供），禁止直接写死颜色值。
-   **禁止写死单模式颜色**：不得在 UI 中使用 `Color.White`、`Color.Black`、`Color(0xFF...)` 作为背景色、文字色、边框色或图标色（AMOLED 纯黑判断等已有显式分支除外）。确需使用品牌固有色（如微信模式气泡）时，必须定义浅色/深色成对常量，并用 `LocalDarkMode.current` 选择。
-   **弹窗与浮层显式指定配色**：`AlertDialog / ModalBottomSheet / DropdownMenu` 等必须显式传入 `containerColor / titleContentColor / textContentColor` 及按钮色（确认= `error` 或 `primary`，取消= `onSurfaceVariant`），不得依赖默认值或肉眼假设。
-   **共用组件只做一份适配**：同一交互（例如删除通话记录弹窗）必须抽成共用 Composable，由调用方复用，避免各页面各自实现导致深浅色行为不一致。
-   **验收**：切换深/浅色后无白底黑字刺眼、无浅底白字不可见、无与主题割裂的纯黑/纯白块；深色下层级（surfaceContainerLow → High）仍需可辨。

## 6. Memory & Context Management (L0-L3 Hierarchy)

### 6.0 Memory Tier Overview
- **L0: Raw Messages**: Immediate short-term context (Sliding Window). AI always sees the last N original messages.
- **L1: Context Refresh (Segments)**: Fine-grained L1 summaries of historical message blocks. **This is the primary source for Episodic RAG retrieval.**
- **L2: Episodic Memory**: Long-term conversation archive. Each Conversation maps to exactly one Episode. **Exclusively for all-day continuity and L3 updates, NOT included in RAG.**
- **L3: Master Memory (终极档案)**: The ultimate "Master Archive" of relationship dynamics and long-term commitments.

# 6.1 Context Refresh (L1 – Detail Chunk)
- **Purpose**: For short-term memory enhancement within conversations and RAG retrieval. Splits long dialogues into segments attached with background summaries.
- **Trigger Mechanism**: **Real-time incremental count triggering**. Executed by `ChatService.checkAndAutoSummarize` after each AI response.
- **Trigger Conditions**:
  - **Count Anchor**: Based on `Conversation.lastSummarizedMessageTime` (timestamp of the last summarization).
  - **Threshold Check**: Number of new messages generated after the anchor ≥ `detailMemoryThreshold` (detail memory threshold).
  - **Special Coefficient**: This threshold is automatically multiplied by **1.3** under WeChat mode to accommodate fragmented short messages.
- **Persistence**: Messages are fetched in batches (100 messages per batch). After the AI generates summaries, records are persisted into the `chat_segments` table, and `lastSummarizedMessageTime` of the conversation is updated. L1 chunks are embedded into the vector database and support hybrid retrieval.

# 6.2 Episodic Memory (L2 – Archived Summary)
- **Purpose**: Long-running conversation archiving, designed to compress context and enable memory transfer across sessions (within the same day).
- **Relationship**: Maintains a **strict 1:1 binding** with `Conversation`.
- **Trigger Mechanism**:
  - **Background Asynchronous**: Periodic scanning by `MemoryConsolidationWorker`.
  - **Automatic Trigger**: Triggered upon session switching logic (e.g., leaving the current conversation), or via `ChatService.checkAndAutoSummarize` when the L2 threshold is met.
- **Trigger Conditions**:
  - **Automatic / Asynchronous**: Incremental messages ≥ 4, and the time elapsed since the last dialogue exceeds `consolidationDelayMinutes` (archiving delay, default: 30 minutes).
  - **Manual Execution**: Must satisfy `(total unarchived messages − 10) ≥ 10`, meaning at least 10 messages will be archived while retaining the latest 10 messages.
- **Injection Logic**: The system automatically queries **all other L2 summaries generated on the current day** and injects them as cross-window context for the day. L2 memory does **not** participate in RAG retrieval.

### 6.3 Master Memory (L3 - Master Archive)
- **Mechanism**: A structured relationship record that transcends individual conversations, injected into the Stable System Prompt.
- **Sync Logic**: Scheduled Daily Sync executed at **3:00 AM**.

### 6.4 RAG Retrieval & Scoring Logic (Double-Stage Filter)

#### Stage 1: Recall (宽口径召回)
- **Multi-Mode Support**:
    - **Keyword**: `Score = 0.2 + (MatchCount / KeywordsCount) * 0.8`.
    - **Semantic**: Cosine similarity via `VectorEngine`.
    - **Hybrid**: `0.5 * Keyword + 0.5 * Semantic`.
- **Recency Boost (Temporal Decay)**: Applies to L1 Segments. `Recency = 1.0 / (1.0 + (AgeInMillis / (7 Days)))`. Final Score includes 10% Recency weight.
- **Recall Strategy**: Ignores the user-defined `similarityThreshold` and retrieves the top **`limit * 3`** candidates. This ensures the Rerank model has a rich enough candidate pool to find highly relevant content that might have had a mediocre initial score.

#### Stage 2: Rerank & Filtering (严格精选)
- **Rerank Refinement**: If a Rerank model is active, it re-scores candidates based on deep semantic context.
- **Strict Threshold Filtering**: **CRITICAL:** After scoring (via Rerank or Recall), any memory with a score below the **`similarityThreshold` (综合评分阈值)** is immediately discarded.
- **Final Selection**: Returns the top **`limit`** memories that passed the threshold. This prevents low-relevance "noise" from cluttering the AI context.

## 7. Agent Automation (Task Manager)

### 7.1 Overview
The `agent_task_manager` allows an Assistant to schedule instructions for its "future self".

### 7.2 Core Logic
- **Scheduling**: Reliable execution via `WorkManager`. Tasks are persisted in `AgentTaskEntity` (Room).

### 7.3 Execution Modes & Visibility
- **Type: EMAIL / AGENT_TASK**: The trigger instruction uses `skipContext = true` and is invisible to the user.
- **Type: NOTIFICATION**: Directly pushes a system notification.
- **Type: DIARY**: Automatically records an entry into the Agent's internal diary database.

## 8. Tool System & Local Capabilities

### 8.1 Local Execution Engines
- **Python Engine (`eval_python`)**: Powered by Chaquopy (Python 3.11).
- **JavaScript Engine (`eval_javascript`)**: QuickJS-based lightweight execution.

### 8.2 Productivity & Device Control
- **Schedule Manager (`schedule_manager`)**: Manages internal tasks with priority/urgency.
- **Device Control (`device_control`)**: LOCK_SCREEN, GO_HOME, OPEN_APP, etc.
- **Email Service**: Full SMTP/IMAP support via `qq_email_service`.

### 8.3 Relationship & Dynamic Profile
- **Profile Updater (`update_profile`)**: Allows AI to dynamically update User/Assistant Profile fields.
- **Milestone Manager (`milestone_manager`)**: Records core relationship events (Relationship, Commitment, Identity, etc.).

### 8.4 MCP 按需加载 (On-Demand Schema)
- **上下文策略**: MCP 工具的 `inputSchema` **不常驻**模型上下文。取而代之常驻两个固定 Function Tool：`get_mcp_tool_schema`（查询参数定义）与 `call_mcp_tool`（执行），定义于 `data/ai/tools/McpAgentTools.kt`；当前助手无可用 MCP 工具时不注册。
- **工具目录**: 各 MCP 工具的「名称 + 有效描述」目录由 `get_mcp_tool_schema` 的 `Tool.systemPrompt` 注入系统提示（`GenerationHandler` 统一收集各工具的 systemPrompt），Agent 先查 Schema 再按 Schema 组织参数调用。
- **精确定位**: 一律以 `serverId + toolName` 二元组定位工具，避免不同 MCP 服务同名工具冲突；Schema 查询走 DataStore 缓存（服务端同步时写入），服务短暂离线仍可查询。
- **执行边界**: 执行层错误（找不到工具 / 服务未连接 / 连接失败）返回带 `error` 字段的 JSON 对象；工具自身业务错误经 MCP 协议 `isError` 标记原样透传给 Agent 自行纠正。超大结果递归截断（单字符串 32000 字符上限）。
- **描述管理**: `McpTool.customDescription` 持久化于 Settings（null 表示未自定义，有效描述 = customDescription 优先 / 原始 description 兜底）。服务同步时原始描述与 Schema 刷新、用户自定义描述原样保留（合并逻辑在 `McpManager.kt` 的 `mergeSyncedTools` 纯函数，有单元测试）。批量简化使用用户配置的全局后台模型（`ui/pages/setting/McpToolDescVM.kt`）。
- **并发约束**: `McpManager.clients` 是跨协程共享的并发容器（ConcurrentHashMap），直接读写即可；MCP 相关的 Settings 写入必须走 `SettingsStore.update(transform)` 原子事务（见第 4 节），否则多服务并发同步会丢更新。

## 9. 会话加载与 UI 同步逻辑 (Chat Loading & UI Sync)

### 9.1 混合消息流 (Hybrid Message Flow)
- **数据来源**：`ChatVM.activeMessages` 是一个复合流，它实时合并了两个来源：
    1. **内存状态** (`Conversation.messageNodes`)：当前活跃会话中尚未保存或正在生成的即时节点。
    2. **数据库历史** (`dbHistoryNodes`)：通过 `conversationRepo` 获取的该智能体下所有会话的持久化历史。
- **合并策略**：以数据库历史为基底，使用内存中的最新节点替换掉旧节点（基于 ID），并确保未持久化的新消息能即时插入流中。

### 9.2 安全同步机制 (Safe Persistence)
- **防止级联删除**：`ChatMessageDAO` 放弃了“先全删再插入”的危险策略，改用 `@Upsert` 策略。
- **精确清理**：通过 `deleteRedundantNodes` 仅删除当前会话列表中明确移除的节点，确保未被 UI 加载进入内存的深层历史数据在数据库中保持安全，不会因 `CASCADE DELETE` 而丢失。

### 9.3 动态边界探测 (Boundary Detection)
- **自动分隔符**：UI 在渲染消息列表时，会自动对比相邻节点的 `conversationId`。一旦 ID 发生变化，立即插入 `ChatUIItem.Separator`（已开启新话题）。
- **空会话保底**：当用户点击“新建会话”且尚未发送消息时，逻辑会强制在历史记录最下方追加分隔符，确保用户能明确感知会话已切换。

### 9.4 分页与性能限制
- **滑动窗口**：`_activeMessageLimit` 控制 UI 显示的消息上限（默认 100/500 条），防止超长对话导致 Compose 渲染性能下降。
- **手动加载**：当 `totalCount > limit` 时，列表顶部显示“查看更早的消息”，点击后触发限制增加并从 DB 加载更多深层历史.

## 10. 语音通话 (Voice Call)

### 10.1 核心组件
- **`VoiceCallManager`**（`service/voice/`，Koin singleton）：通话唯一状态源。
  - `CallPhase`：`IDLE / INCOMING_RINGING / OUTGOING_WAITING / CONNECTED / ENDING`（生命周期，决定能否发起新通话）。
  - `CallStatus`：`CONNECTING / LISTENING / THINKING / SPEAKING / DUCKING / DISCONNECTED`（接通后的对话状态）。
  - 对外暴露 `isActive / isCallMinimized / callDurationMs / incomingCall / isNearEar / activeCallConversationId`。
- **`CallCommandHub`**（LocalTools）：AI 工具与通话管理器之间的命令通道，解耦 `call / hangup` 请求与执行。
- **UI**（`ui/components/chat/CallScreen.kt`）：`IncomingCallScreen`（来电全屏覆盖）、`OutgoingCallWaitingScreen`（拨号等待）、`CallScreen`（通话页）、`MiniCallWindow`（应用内迷你窗）。由 `RouteActivity` 根级挂载，任意页面均可弹出。
- **`CallRecorder` + `CallMixer`**：双向录音；**`CallSoundPlayer`**：挂断音；**`VadDetector`**（`service/voice/`）与 **`WebRtcAudioProcessor`**（`common/utils/`）：VAD / AEC3 / NS。

### 10.2 生命周期与状态机
- **全局单通话**：仅 `callPhase == IDLE` 时允许新建（`canStartCall()`）；UI 按钮、后台通知、Agent 工具等所有入口都必须显式校验。
- **用户拨号**：`startCall(conversationId)` → `OUTGOING_WAITING`，播放与 AI 来电相同的配置铃声，等待 AI 决策（0 拒绝 / 1 接通）。
- **AI 来电**：`call_control` 的 `call` 分支 → 写入 `CallRecordEntity(status=RINGING)` + 全屏来电页 + `incoming_call` 通知 + 铃声；120s 未接听 → `MISSED`。
- **接通**：`acceptIncomingCall(callId)` → `CONNECTED`，此时才 `setCallMode(true)`、开麦、启动录音和时长 ticker；用户接通结果必须在通话真正初始化完成后才回传 AI（避免通话时长气泡位置异常）。
- **挂断**：`hangup(endReason)`。AI 主动挂断先立即退出 callMode（否则 AI 看不到结果会反复调用工具），再延迟 `AI_HANGUP_DELAY_MS`（30s，等 AI 说完道别）才真正挂断；宽限期内用户开口（`noteUserSpoke`）会取消挂断。真实接通的结束只播放一次挂断音（1.5s 去重窗口）；拒绝 / 超时 / 权限失败 / 初始化失败 / 取消路径不播。
- **未接通路径**（拒绝 / 超时 / 权限失败 / 初始化失败 / 取消）：只更新通话记录状态并提示错误，不进入正式 `CallScreen`，也不出现在历史通话列表。
- **模式约束**：进入接听/接听确认环节时，静默将微信模式切换为普通模式，不提示用户或 AI。
- **缩略**：仅全屏通话页左边缘返回手势触发 `minimizeCall()`，点击 `expandCall()` 恢复，缩小期间通话不中断。缩小后按悬浮窗权限二选一：已授权「显示在其他应用上层」（`SYSTEM_ALERT_WINDOW`）→ 由 **`CallMiniOverlayWindow`**（`service/voice/`，WindowManager `TYPE_APPLICATION_OVERLAY` 传统 View 顶部窄条，Telegram 式）接管，App 退后台/桌面时迷你窗仍悬浮可见，点击拉回应用并展开、挂断钮直接挂断；未授权 → 应用内 `MiniCallWindow` 兜底（仅 App 内可见）。悬浮窗显示/移除由 `VoiceCallManager` 内 `combine(_isCallMinimized, _callPhase, _overlayPermissionGranted)` 单一副作用流驱动，禁止在各清理路径散落 hide()；权限状态由 UI 在 ON_RESUME 调 `refreshOverlayPermission()` 同步（授权页返回后悬浮窗自动接管）。缩小通话时未授权会弹出解释弹窗引导授权（RouteActivity）；设置页「权限管理」含悬浮窗条目（`PermissionType.OVERLAY`）。

### 10.3 音频链路（硬性约束）
- **TTS 输出**：所有提供商统一输出 16-bit 小端单声道裸 PCM。采样率：Mimo / Gemini / OpenAI / Custom 24kHz，MiniMax 32kHz，Azure / ElevenLabs 16kHz，System TTS 为引擎实际值。缓存若为旧的非 PCM 格式必须删除并重新合成。
- **通话 TTS 链路**：使用独立的 `TtsController`（`USAGE_VOICE_COMMUNICATION`，Koin single），与 UI 自动朗读的 `CustomTtsState` 实例区分。
- **far-end PCM 分流**：`TtsController.setOnFarPcmListener` 暴露 TTS PCM，**仅**用于 `CallRecorder.feedFarEnd`（重采样至 16kHz mono）。监听器必须由 `refreshFarPcmListener()` 在录音器存在时绑定、停止 / 挂断时解绑；禁止在别处直接 `setOnFarPcmListener(null)`（曾导致录音丢失 AI 侧声音）。禁止为录音另建解码 / 播放链路。
- **录音**：`CallRecorder` 以 near（麦克风）驱动时间轴，far 先入环形缓冲，再在**同一位置 overlap-add**（`CallMixer`）；输出 16kHz mono AAC `.m4a`（`filesDir/call_recordings/{callId}.m4a`）。混入有效内容不足 1s 时删除文件、不生成录音。
- **AEC**：`WebRtcAudioProcessor` 包装 `WebRtcAudioEffects`（`AudioEffect`），必须 **attach 到 `AudioRecord.audioSessionId` 且在 `startRecording()` 之前**；绑定后由 MediaServer 自动以当前播放流作 far-end 参考，`AudioRecord.read()` 返回的已是消回声 + 降噪 PCM，**不要再手动 processStream / processReverseStream**。默认禁用系统 `AcousticEchoCanceler` / `NoiseSuppressor`（跨机型不一致），并在 `close()` / 挂断时释放。
- **音频路由**：`MODE_IN_COMMUNICATION`；默认外放；检测到耳机（有线 / USB / BLE / SCO / A2DP）时强制路由耳机并禁用扬声器切换。

### 10.4 交互与打断
- **VAD 两阶段**：DUCK（压低 AI 音量，保留 PCM 预卷）→ INTERRUPT（停 TTS、取消生成、回到 `LISTENING`）。
- **严格模式**：TTS 播放期间启用更高阈值与绝对下限；TTS 结束后约 220ms 回声尾窗内继续提高门槛，防止尾部回声误打断。
- **手动打断**：`manualInterrupt()` 优先级高于 VAD 自动打断；被打断时仅已播放文本入库，未播放文本丢弃。
- **资源生命周期**：打断检测开始时创建 `WebRtcAudioProcessor` 并注册 TTS PCM 监听，停止 / 挂断时必须释放。
- **通话期消息限制**：`ChatVM` 层拦截向通话中 AI 发送普通文字消息（可查看历史），但通话内部的 ASR 转写与 AI 语音回复不受限。

### 10.5 通话朗读 (TTS) 边界规则
- 每次请求前必须快照 `requestBaselineNodeIds` 并记录 `requestStartedAtMs`；`findNewAssistantNodes` 只接受同时满足「锚点之后」「不在基线内」「`timelineCreatedAt >= requestStartedAtMs`」的节点。
- `priorAssistantNodeId` 失效且无可靠时间边界时**直接返回空**（fallback-blocked），严禁把全量历史 assistant 节点当候选，否则会从会话第一条消息开始重播。
- `fedLengthPerNode` 只重置当前请求的进度；`resetStreamingState` 同时清理边界与朗读进度。
- 同一 turn 内的所有 AI 消息（含定时任务 / 监控产生的额外回复）都必须朗读，不能简单只取最后一条。

### 10.6 数据、通知与 UI
- **`CallRecordEntity`**（Room `call_records`）：主键 `id` 即 `callId`；`status` 为 `RINGING / CONNECTED / REJECTED / MISSED / FAILED / ENDED / CANCELLED`；`audio` 存路径字符串（禁止二进制入 Room）：未永久保存时为私有目录相对路径（`call_recordings/`），永久保存后为公共目录（`Download/Evolia/`）绝对路径——保存时文件**移动**至公共目录（MediaStore，单份存储，App 内可播、文件管理器可见），解析统一走 `CallRecorder.resolveAudioFile`；详情页会自动检测 audio 指向的文件缺失（被外部移动/删除）并重置 `audio=null, forever=0`，播放器显示「无通话录音」；`forever=0` 时录音 7 天过期清理，且「清理缓存」也会清除其音频（只清音频，通话记录与转写保留），删除通话记录时同步删除音频文件；`durationMs = disconnectedAt - connectedAt`。
- **转写归属**：通话消息通过 `UIMessage.callId` + `MessageSource.CALL` 标记。AI 拨号前仅含空白文本的触发轮需重打标为 `CALL`，不残留为普通聊天消息；有实质文字则保留正常显示。
- **对话列表合并**：通话记录只加载**当前消息分页窗口内出现过的会话**（与消息滑动窗口一致，不全量加载），再按 `createTime` 统一排序；命中回合时间范围且同会话的记录会按时间切分回合，未落回合的记录（未接来电等）独立排序。上滑加载更早消息时，新进入窗口的会话其通话记录会随之补齐。分页未就绪（`null / Idle / Loading`）时**先不合并通话记录**，避免「先出通话卡片、消息随后填入」的闪烁；分页 `Error` 时仍展示记录。
- **首页预览**：最新记录为通话时统一显示「语音通话」，不区分时长 / 已挂断。
- **历史通话列表**：仅展示已接通的记录。
- **通知渠道**：`incoming_call`（来电，高优先级，不使用强制全屏 Intent，点击复用现有页面栈）与 `voice_call_foreground`（通话中保活）。
- **铃声设置**：`callRingtone` / `callRingtoneCustomPath`；自定义铃声 ≤10MB、≤60s，超长时调用裁切器；试听前必须停止上一段试听音频。

## 11. 构建与验证 (Build & Verification)

-   **单元测试**: `./gradlew :app:testDebugUnitTest`（纯 JVM 环境，无 Robolectric）。注意 `android.util.Log` **未 mock**——需要单测覆盖的逻辑必须抽为不依赖 Android API 的纯函数（参考 `mergeSyncedTools`、`buildMcpCatalogPrompt` 的做法）。现有测试位于 `app/src/test/java/me/rerere/rikkahub/`。
-   **最小编译检查**: `./gradlew :app:compileDebugKotlin`。
-   **模块依赖顺序**: `app` 依赖 `ai / common / search / tts / highlight`；改动子模块公共接口后必须连同 `app` 一起编译验证。
-   **已知存量问题**（截至 2026-10）：`ShareSheetTest` 中 `decode should restore OpenAI provider correctly` 在当前分支失败（Provider 分享串解码后 `models` 为空），与 MCP 无关，修复前勿误判为新回归。
