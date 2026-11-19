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
-   **Room Database**: `data/db/AppDatabase.kt`（`version = 49`；新增 schema 变更必须新增 `MIGRATION_n_n` 并在 `di/DataSourceModule.kt` 的 `addMigrations` 中注册，禁止依赖 fallbackToDestructiveMigration）。
-   **备份恢复**: `data/sync/WebdavSync.kt`（整库 zip 打包/覆盖 + `DatabaseSanitizer` 白名单重建）。**新增 Room 表必须同步登记进 `DatabaseSanitizer.kt` 的白名单（写蛇形物理表名，驼峰类名匹配不上），否则备份恢复时该表数据被静默丢弃**（曾致 `call_records` 通话记录恢复后消失；白名单匹配规则有单测固化）。录音目录 `call_recordings/` 有意不在 FILES 备份名单（体积大 + 7 天过期策略），恢复后详情页优雅降级为「无通话录音」。

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

### 动态主题架构（2026-11 重构，强制）
-   **唯一实现入口** `app/src/main/java/me/rerere/rikkahub/ui/theme/DynamicThemeColors.kt`，全局主题与智能体独立主题都必须复用它，禁止再各自维护解码/Palette/配色代码：
    -   `extractSeedColorFromImage(context, source)`：图片来源 → 种子色。支持 `content://`、`file://`、`android.resource://`、`http(s)://`、本地路径；解码与 Palette 在 `Dispatchers.IO`；先按 `inSampleSize` 降采样再缩放到 128px；Swatch 优先级 `vibrant → dominant → muted → lightVibrant → darkVibrant → lightMuted → darkMuted`；失败返回 null；Bitmap 在 `finally` 中回收（缩放返回同一对象时只回收一次）。
    -   `resolveAssistantThemeBackground(dynamicColorEnabled, background)`：聊天页主题的来源判定——全局动态颜色开启且当前智能体有背景图才返回该背景，否则返回 null（继承全局主题）。
    -   `buildDynamicColorScheme(seedColor, darkTheme)`：唯一的配色算法，按 `ui/theme/presets/*` 的版式生成**完整色板**——中性表面/正文使用固定中性灰阶（浅色 #FCFCFC 底 / #1B1B1D 字，深色 #121212 底 / #E6E6E6 字），不随图片染色；只有 primary / secondary（降饱和）/ tertiary（色相 +45°）及其容器由种子色相派生；error 沿用 M3 标准红。禁止再用 lerp 与某个预设主题混色，也禁止把樱花粉当作配色基底。
    -   `Color.isUsableThemeSeed()`：饱和度 < 8% 的近纯灰图片视为无有效主题色，取色结果按失败处理。
    -   `ColorScheme.withAmoledDarkCanvas(darkTheme)`：AMOLED 纯黑 `background` / `surface` 的唯一入口。
-   **全局「动态颜色」不再使用系统壁纸与 `dynamicLight/DarkColorScheme`，也不再要求 Android 12+**：开启时只读主智能体（`Settings.getMainAssistant()`，即 `isMain`）的聊天背景图并生成完整动态色板；主智能体无背景图 / 加载失败 / 取色失败（含近纯灰）→ 直接使用樱花粉 `SakuraThemePreset`；关闭时仍使用 `settings.themeId` 预设。樱花粉只做失败回退，不参与配色生成。禁止用主智能体头像、当前会话智能体背景或系统壁纸作为全局取色来源。
-   **聊天页智能体主题（`AssistantChatTheme`）**：全局「动态颜色」开启时，当前智能体有自己的聊天背景图就自动用该背景取色生成完整色板（无需任何开关）；没有背景图 / 取色失败 / 全局动态颜色关闭 → 不覆盖，继承全局主题。头像不参与取色；`useAssistantMaterialYouColors` 字段已废弃（仅保留用于旧数据反序列化），助手详情页的「从角色取色」开关已移除。禁止聊天页主题反向覆盖全局主题。
-   **缓存与异步约束**：`ThemeSeedColorCache` 以图片 URI（含 `android.resource://` 归一化后的 URI）为 key 做 LRU 缓存；背景图每次重选都会生成新的 `files/upload/<uuid>` 文件名，URI 变化即缓存失效。取色统一用 `produceState`（key = 图片 URI），明暗模式切换只重算 `ColorScheme`，不得重复解码；禁止在 Composable 重组路径或主线程解码图片，切换图片时旧协程必须被取消，避免旧颜色覆盖新主题。
-   **DataStore 兼容**：不修改 `dynamicColor` / `themeId` 字段结构，不新增必须迁移的配置项；旧用户开启动态颜色的行为按上述新规则直接生效。

### 共享 UI 组件与豁免口径（2026-10 UI 统一）
-   **共享组件优先（禁止再手写等价物）**：确认弹窗用 common 的 `ConfirmDialog`（`dangerous = true` 时确认按钮为 error 色，删除类操作必须用）；文本输入弹窗用 `InputDialog`；空态用 `EmptyPlaceholder`；行内加载用 `InlineSpinner`（24dp 默认 / 16dp 紧凑两档，禁止手抄不同尺寸的 CircularProgressIndicator）；FAB 用 `AppFab`（统一 `AppShapes.CardLarge` + PremiumHaptics）；日期/时间选择用 `EvoliaDatePickerDialog` / `EvoliaTimePickerDialog`，**禁止使用 android.app 平台对话框**（深色模式不受主题控制）。
-   **浮层配色合规路径**：ModalBottomSheet 必须显式传 `containerColor = surfaceContainerHigh` 与 `shape = AppShapes.BottomSheet`；DropdownMenu 必须显式传 `containerColor = surfaceContainerHigh` 与 `shape = AppShapes.ButtonSquared`；不落入共享组件的 AlertDialog 必须显式传 containerColor / titleContentColor / textContentColor 及按钮色。
-   **形状 token 映射**：10dp 一律用 `AppShapes.ListItemSmall`；6dp 归并 `AppShapes.Indicator`(8dp)；40/44/50dp 大胶囊归并 `ButtonPill`；分组容器 24dp 用 `AppShapes.CardMedium`。设置分组容器背景色统一走 `groupContainerColor()`（app theme 内），禁止复制 `if (LocalDarkMode) … else …` 三元公式。
-   **沉浸式深色画布豁免清单**：以下场景允许使用 `Color.Black` / `Color.White`（固定深色画布上的前景/遮罩，不做深浅切换）：CallScreen 通话页背景与前景、相机/裁剪遮罩、图片预览器、渐变 scrim、`Color.Black.copy` 遮罩浮层；CallScreen 的红/绿/黄信号色使用文件内命名常量（如 `CallRejectColor`），禁止新增内联 `Color(0xFF…)`。
-   **Chip 与 Tag 边界**：语义状态标签（成功/错误/警告/信息）用 `Tag(type = …)`；可输入/可筛选的交互 chip 用 M3 `InputChip` / `FilterChip`。

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
- **Trigger Mechanism**（2026-11 核实代码后修正，与旧描述差异见括注）:
  - **Automatic（唯一自动路径）**: 会话切换钩子 `ChatService.initializeConversation`（检测到 lastConversationId 变化）→ `archiveConversation(oldId, force = true)`。`force = true` **绕过全部阈值检查**（增量 ≥4 / 静置时长均不判断），立即归档该会话锚点之后的增量消息。**进程内直接执行，不走 WorkManager，无重试**——LLM 调用失败或进程被杀即漏网，只能靠手动兜底捞起。
  - **Manual Full Scan（手动全扫）**: 助手详情页「整合记忆」按钮 → `MemoryConsolidationWorker`（`FULL_SCAN`，不带 FORCE/INCREMENTAL 标志）→ 扫描循环逐会话筛选：**未归档增量 ≥ 4** 即归档（2026-11 移除静置 ≥30min 判断：本循环唯一调用方是手动按钮，用户显式要求立即整合；且切换归档仅在「切到新会话」时触发，返回主页/详情页不归档——刚聊完直接来按按钮的会话静置 <30min，旧条件会把最该捞的目标挡在门外。`consolidationDelayMinutes` 字段与 UI 滑条已一并删除；旧 Settings/备份中的该键靠 JsonInstant 忽略未知 key 安全兼容，无需迁移）。**遗留统一项**：该循环尚未 chunk 化、也未登记手动归档遮罩状态（当前无 UI 入口实际调用），待后续与单会话路径统一。
  - **Manual Single Conversation（手动单会话）**: 聊天页「整合」按钮 → `ChatVM.consolidateConversation` → `ChatService.startManualEpisodeArchive`（**进程内 appScope 协程；2026-11-15 起弃用原 WorkManager Worker 路径与 `manualConsolidate`，进度/遮罩/取消统一收口 ChatService**）。保留规则不变：`(total unarchived − 10) ≥ 10`（保留最近 10 条，本轮归档数 ≥ 10，不足则 SKIPPED）。**分 chunk 滚动归档**（`splitEpisodeArchiveChunks` 纯函数有单测：单批 ≤12000 字符 / ≤60 条，同毫秒组不拆批）逐批 `saveEpisode` 推进 `endTime` 与截断游标（同值推进，保持「已归档 ⇒ 上下文可截断」一致）；**chunk 失败/取消立即停止，游标不推进，已存批全保留**——下次以 `episode.endTime` 锚点严格大于查询断点续归，未归档消息绝不进已归档范围。与切换钩子共享 `archivingConversations` 锁，并发时一方 SKIPPED、增量留给下次（消除旧双实现互不互斥导致的 endTime 回退/正文回滚竞态）。**遮罩**（`ChatService.episodeArchivingIds` StateFlow，手动与切换钩子路径统一登记）以 episode 归档生命周期为准：完成即撤（L1 segments 不算在内）、退出聊天页任务继续跑、重进后遮罩自动恢复（状态源是 Koin single，不以 VM 内存态为准）；遮罩带取消按钮（`cancelManualEpisodeArchive`）是唯一中断方式，返回手势不取消任务；进度经 `episodeArchiveProgress`、结果 toast 经 `episodeArchiveResults`（tryEmit，无 replay）。
  - **易混淆澄清**：① 3:00 AM 的 Worker 调度（`INCREMENTAL_MASTER=true`）**跳过 L2 循环，只做 L3**；② `ChatService.checkAndAutoSummarize` 只负责 L1 片段记忆（`summarizeAndRefresh(skipArchive = true)` 显式不做 L2）。旧文档写的「Worker 周期扫描触发 L2」「checkAndAutoSummarize 达阈值触发 L2」与实际代码不符，已删除。
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

## 7.5 AI 日记生成与失败恢复 (Diary Generation & Recovery)

> 核心链路：`DiaryVM`（手动触发） / `DiarySchedulerWorker`（30min 周期调度）→ `DiaryWorker`（`service/DiaryWorker.kt`）→ `DiaryGenerationLogic.kt`（纯决策函数，有单测 `DiaryGenerationLogicTest`）→ `DiaryRepository` / `AgentDiaryDAO`。

### 7.5.1 消息游标 lastMessageAt 的准确语义（硬约束）
- `lastMessageAt` **只代表当前日记已成功生成并持久化的最后一条消息的时间戳**，不代表本次查询到的最后一条、任务开始/结束时间或计划处理范围终点。
- **禁止**在任务开始时、查询完成后或整个任务结束时用整个 `newMessages` 集合的最后一条消息时间更新游标（历史 bug 根因）。
- 游标必须**逐 Chunk 推进**：每个 Chunk 生成成功并持久化后，游标才推进到**该 Chunk 最后一条消息**的 `createdAt`（epoch ms）。

### 7.5.2 Chunk 生成与原子持久化
- 每个 Chunk 是独立持久化单元：生成 → 校验非空 → 写正文+游标（`updateDiaryGenerationProgress`，**单条 UPDATE 语句原子生效**，等价于事务；禁止用全实体 `@Update` read-modify-write，避免覆盖 is_favorite 等并发列）→ 才允许处理下一个 Chunk。
- Chunk 失败（异常或返回空白）→ **立即停止后续 Chunk**，游标不推进，不向正文写入错误信息，已保存内容全部保留。
- **同毫秒时间戳组不可被 Chunk 边界拆开**（`buildDiaryMessageGroups` 的 snap 逻辑，微信模式分句同毫秒落库多条）：否则 `createdAt > 游标` 的严格大于查询会漏掉同 ts 后继消息。新增分批逻辑必须保持此约束（单测固化）。

### 7.5.3 生成状态（generation_status，DB v43+）
- 持久化状态仅三种（`DiaryGenerationStatus`）：`GENERATING`（生成中/崩溃残留）、`PARTIAL`（部分成功、有未处理消息）、`COMPLETED`（全部完成；含用户手动编辑落定与历史日记默认值）。
- **FAILED 不落库**：完全失败（无任何正文）时删除空壳记录，「记录不存在」即等价于可重新生成，避免空日记卡片占位。
- 状态是内部管理信息，**不影响正文展示**：不在正文中插入失败提示，不因状态删除/覆盖内容。
- 用户手动编辑日记（`DiaryVM.saveDiary` / `updateDiaryWithImages` 编辑分支）必须置 `COMPLETED` 落定，禁止后续自动续写覆盖用户编辑；未处理消息由次日期日记承接，不丢失。
- `images.isNotEmpty()` 的记录一律视为用户手动内容（扫描日记正文可为空）：**绝不删除、绝不作为空壳重建、不触发 AI 生成**。

### 7.5.4 失败恢复与跨天承接
- **同天 PARTIAL 恢复**：复用原记录（保留 id/createdAt），锚点 = 自身 `lastMessageAt`，首个 Chunk 走续写草稿 prompt（`generatedContent` 初始化为已有正文），逐 Chunk 追加，完成置 COMPLETED；不得整篇重生成覆盖。
- **空壳/崩溃残留（空白 COMPLETED 或 GENERATING）**：复用原记录重新生成，不删除重建、不产生重复当天日记。
- **跨天不回补**：始终以当天为目标日期新建日记；锚点 = 最近一篇有正文日记的 `lastMessageAt`（跨天自动承接未处理消息）。前一天 PARTIAL 日记保留原样，不创建历史日期日记。
- **无交互日记游标** = 日记记录的 `createdAt`（复用记录时保持原 createdAt）；该时点锚点之后无任何消息，未来消息必然大于 createdAt，严格大于查询不漏。
- **历史日记兼容规则不变**：缺 `lastMessageAt` 时回退目标日期 0 点（不回溯更早日记游标），不批量重算，不改迁移逻辑（30→31 回填规则保留）。

### 7.5.5 并发保护与 WorkManager 异常恢复
- 唯一任务名：手动 `diary_gen_{assistantId}`，自动 `auto_diary_{assistantId}_{date}`（KEEP 策略）。
- **"任务是否还在运行"以 WorkManager 为准，不以 DB 状态推断**：`hasActiveDiaryGenerationTask` 同时查两个唯一任务名的 ENQUEUED/RUNNING WorkInfo（`excludeWorkId` 排除自身）。GENERATING + 无活跃任务 = 崩溃残留 → Worker 接管恢复；有活跃任务 → 跳过（防手动+自动双任务并发写同一日记）。
- `DiarySchedulerWorker` 判定用 `shouldAutoTriggerDiary`（不能只看当天日记是否存在：PARTIAL 要恢复、空壳要重试、GENERATING 要查任务死活）。
- Worker 的 CancellationException / 异常清理必须包 `withContext(NonCancellable)`，否则已取消协程内的挂起清理语句会被立即取消而静默失效（历史 bug）。
- 已知边界：手动+自动在同一瞬间入队（互相都未 RUNNING）时双入口守卫可能同时放行，属可接受残留竞态（与旧行为等价），如需根治需 date+assistant 唯一索引，暂不做。

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

### 8.5 设备状态监视器（EvoliaMonitorService，2026-11 状态实时性与并发安全改造）

> 核心文件：`service/EvoliaMonitorService.kt`（编排与系统观测）、`service/MonitorDeviceStateLogic.kt`（纯决策函数，有单测 `MonitorDeviceStateLogicTest`）。`user_device_state` 单行表，全部写入方都在本服务内。

#### 8.5.1 设备状态同步机制
- **请求入口统一**：屏幕亮灭广播（`screenReceiver`）、无障碍 `TYPE_WINDOW_STATE_CHANGED`、30 秒轮询，一律通过 `requestSync()` 投递 `SyncRequest` 进 `Channel(CONFLATED)`，由 `startSyncWorker()` 启动的**单一 worker 协程串行处理**（`processSyncRequest`）。worker 忙时到达的多个请求合并为最新一个（最终状态正确，窗口事件风暴天然去重）。
- **同步内容**：亮灭、前台应用（`request.newPackage` 优先，其次 DB 值）、今日使用时长（UsageStats）、单应用/全局连续会话起点、WiFi。熄屏时前台置空、会话起点清零（既有语义保留）。
- **检查门槛**：仅「亮屏」或「location/wifi 发生变化」时执行 `checkAllMonitors`（既有语义保留：熄屏且位置/WiFi 无变化不检查）。

#### 8.5.2 状态并发控制机制（禁止回退的硬约束）
- **禁止**为每次 sync `scope.launch` 独立协程做「读-算-写」——多个协程基于同一旧状态乱序写库会用旧值覆盖新值（历史回退根因）。
- `stateWriteMutex` 保护 `user_device_state` 的三类读-改-写事务：sync worker（全量计算）、定位协程（仅位置字段合并）、`recordAction`（仅 recentActions 合并）。**锁内重读最新状态作为 base 再 copy 写入**，任何写者不得基于锁外快照整行覆盖。
- `monitorCheckMutex` 串行化 `checkAllMonitors` 全程（sync worker 与定位协程都可能进入），防止 `lastTriggerTimeMap` 竞态与同一监视器被双路径同时触发；两个 Mutex 无嵌套（检查在 `stateWriteMutex` 之外执行）。
- 慢 I/O 必须在锁外：逆地理编码在独立定位协程（`startLocationRefresher`，60s 检查一次、**5 分钟刷新门限不变**，geocode 走 `Dispatchers.IO`）完成后再进锁合并；屏幕扫描在 `stateWriteMutex` 外预扫描、锁内校验「扫描目标 == 最终写入前台」后才采用，不一致置空等下一轮纠正。

#### 8.5.3 监视器触发前刷新机制（旧状态触发修复）
- 常规评估命中后、`triggerMonitor` 之前，必须经 `revalidateMonitorTrigger`：重新观测亮灭（PowerManager）、前台应用（**活动窗口 `rootInActiveWindow` 优先 → UsageStats 兜底，禁止用 DB 旧 foregroundApp 冒充**；亮屏下两个来源都失败 → 取消触发并记日志）、今日时长（UsageStats）、WiFi，然后用最终状态重新执行 `evaluateConditions`，不满足即取消（冷却表不写，下一轮可重试）。
- **`oldState` 基准语义不变**：二次评估仍以本次同步前的状态为 oldState，`location_name` / `wifi_ssid` 的「now 匹配 && 之前不匹配」到达边缘判断不受影响。熄屏路径不做前台/屏幕观测（无法观测不构成失败），沿用评估时状态二次评估，保留熄屏下时间/位置类监视器既有触发语义。
- `ChatService.executeAgentTask()` 只接收 EvoliaMonitorService 已生成的最终指令，不负责重查设备状态。
- 屏幕文字依赖方（`content_contains` 条件或模板含 `{screen_context}`）触发前**强制重新扫描**（`RoundScanCache` 保证同一轮多监视器命中同一窗口只扫一次），扫描失败（`rootInActiveWindow` 为 null，`scanScreenContext` 返回 null）→ 取消触发，**禁止用旧 screenContext 冒充最新结果**。

#### 8.5.4 屏幕文字扫描的触发条件与扫描时机
- **需求判定**：`MonitorDeviceStateLogic.monitorNeedsScreenText(conditions, actions)`——conditions 存在非空 `content_contains`，或 SEND_HIDDEN_MESSAGE 模板包含 `{screen_context}`；JSON 解析失败按无需求处理。启用监视器列表由 Room Flow 实时缓存（`cachedEnabledTasks`），增删/暂停监视器自动生效。**无任何需求时不产生任何扫描开销**。
- **扫描范围**：不再依赖 `shoppingApps` 固定白名单（已删除）——只要有需求，用户实际所在的前台应用（非 Evolia 自身）即可扫描。
- **扫描时机**：① 常规同步（30s 轮询/广播/窗口事件）按需扫描，同一前台应用 5 秒内复用上次结果（`regularScan*` 缓存）；② 无障碍 `TYPE_VIEW_CLICKED` 只记录 recentActions，**绝不触发全量屏幕扫描**；③ 触发前强制重扫（见 8.5.3）。`AccessibilityNodeInfo` 全节点 recycle、遍历深度 60、文本截断 30 条等资源保护全部保留。
- `scanScreenContext()` 返回可空 `String?`：null = 扫描失败（无活动窗口），空串 = 扫描成功但无文本，两者语义不得混淆。

### 8.6 工具调用循环治理与上下文快照复用（2026-11）

> 核心文件：`data/ai/GenerationHandler.kt`（快照与工具循环）、`data/ai/ToolLoopGuard.kt`（纯决策，有单测 `ToolLoopGuardTest`）、`ai/ui/Message.kt`（流式 ToolCall 合并）、`ai/provider/providers/openai/ResponseAPI.kt`（Responses API 清洗）。

#### 8.6.1 上下文快照复用（禁止回退的硬约束）
- **一次独立用户请求只构建一次动态上下文快照**：`generateText` 在工具循环开始前调用 `computeContextSnapshot`（构建 `GenerationContextSnapshot`），循环内所有模型请求经 `generateInternal → buildMessages(snapshot=…)` 复用。**禁止**把世界书激活（含 RAG 嵌入查询）、L1/L2 记忆查询、日程/日记/里程碑读取、TimeSense 等重新放回每轮执行的 `buildMessages` 中（历史 bug：每轮工具调用重复执行全部动态上下文构建 + TimeSense 随轮次漂移破坏 Prefix Caching）。
- **快照固定 vs 每轮重建的分界**：快照固定 = 系统提示、世界书激活结果、L1 片段、L2 当日跨会话记忆、RAG 记忆注入、参考变量、TimeSense、正在进行的日程、工具提示词（`Tool.systemPrompt` 以首轮消息计算）、模式/世界书附件。每轮基于最新 `currentMessages` 重建 = `prepareHistoryCandidates`（skipContext 白名单过滤 → `limitContext` 裁剪保持工具配对 → 旧搜索结果修剪）→ Token 预算选取 → 输入 Transformers → 动态上下文注入。
- **锚点消息**：动态上下文只注入快照记录的 `anchorUserMessageId`（本轮原始用户请求，即首轮最后一条 USER 消息），**不随工具执行期间追加的额外 USER 消息（如拍照注入）漂移**；锚点被预算裁掉时该轮不注入并记 warning，禁止回退为「每轮重新找最后一条 USER 消息」。
- 记忆预算分配以**首轮**历史为准冻结进快照 dynamicContext；每轮 `buildMessages` 只做消息预算（等价 CHAT_HISTORY 优先）。

#### 8.6.2 工具循环保护（ToolLoopGuard）
- **轮次上限**默认 16 轮（`DEFAULT_MAX_TOOL_ROUNDS`，一轮 = 一次含工具调用的模型响应）：超限后不再执行工具，以引导性错误 ToolResult（保持调用/结果配对）让模型直接作答，循环自然结束；**禁止**静默 break 丢弃结果。**用户可配置**：`Assistant.maxToolRounds`（助手详情「工具与搜索」页滑条，合法区间 `MIN_TOOL_ROUNDS=10` ~ `MAX_TOOL_ROUNDS=30`），运行时越界数据（历史/导入）必须 coerce 进区间再使用（GenerationHandler 与 UI 两处均有收敛）。
- **相同指纹连续重复检测**：指纹 = 工具名 + 规范化参数；基于**上一轮快照状态**计数（同轮内重复调用不得相互累积），连续相同超过 2 次拦截。不同参数/不同工具/非连续重复不受影响。
- **已删除「每请求仅一次 search_web」硬限制**（GenerationHandler 的 searchCount 与 createSearchTool 的 searchCounter 双重拦截均已移除）：换关键词的再次搜索是合理行为；`search_web` 的 systemPrompt 引导语已同步放宽。不要以「搜索次数限制」替代循环保护。
- **search_web 空结果治理（SearchRetryGuard，纯函数有单测）**：搜索无结果 → 返回空 items/results + note 引导「更换关键词后重新搜索」，并累计**连续**空结果次数（有效结果归零）；连续空结果达 5 次（`DEFAULT_MAX_CONSECUTIVE_EMPTY_RESULTS`）后不再执行实际网络搜索，直接返回 error 要求模型终止搜索并告知用户搜不到。Guard 实例在 `createSearchTool` 闭包内创建（每轮生成一个，按请求天然隔离），**禁止**把它做成全局/会话级单例。
- 工具执行失败 → 明确错误 ToolResult（异常类名+消息+截断堆栈）；结果为空串/JsonNull → 规范化为「执行成功但没有返回任何内容」note；工具不存在 → 结构化错误（不抛堆栈）。空 results 时中止循环（不产生无内容 TOOL 消息）。

#### 8.6.3 链路日志与排查入口
- 每次生成有请求级 `requestTag`（`req-xxxxxxxx`）；逐工具执行日志：`[req-x] ToolCall round=N tool=… id=… duration=… resultLength=… empty=…`；每轮请求发出前 `logToolCallChainSummary`（仅 DEBUG）输出 `[ToolCallDebug] … matchedToolCallIds=… toolResultContentLength=…`，`matchedToolCallIds=false` 即为「工具结果未进请求」的直接证据。
- 排查「模型反复调用同一工具」时按 requestTag 聚合日志，先确认 Tool 消息是否进入下一轮请求（配对清洗日志在 ChatCompletionsAPI/ResponseAPI 中，检索「孤立」「不完整」关键字）。

#### 8.6.4 Provider 工具调用协议事实（有单测固化）
- **Gemini**：`functionCall` 无 `toolCallId`（空串），并行调用靠名称区分。`handleMessageChunk` 的空 id 合并规则：仅「名称匹配/为空」且「非双方均为完整 JSON 参数」时合并，否则追加为新 part（修复并行调用被错并成 `search_webread_url` 坏名 → functionResponse 名称失配 → Gemini 视调用未回答而反复重调）；`ToolCall.merge` 同名不重复拼接。
- **Responses API**（useResponseApi）：`cleanResponseApiInput` 清洗孤立 `function_call_output`/无结果 `function_call`（与 ChatCompletionsAPI 配对清洗策略一致）；仅含 ToolCall/Reasoning 的 assistant 消息**不产出 message item**（空 `content: []` 会被拒绝）。
- **ChatCompletionsAPI**：`buildMessages` 内置配对校验（assistant 的全部 toolCallId 必须有 Tool 结果，否则剥离 tool_calls 保文本防 DeepSeek 400）；TOOL 消息按 toolCallId 逐条序列化为 `role=tool`。
- 历史裁剪必须走 `limitContext`（保持 TOOL→assistant(toolcall) 配对并丢弃开头孤立 TOOL），禁止裸 subList。

### 8.7 会话批量加载的内存红线（2026-11，#20026 OOM 修复）
- **禁止为取一条会话 ID 而全量水合**：定位「助手最新会话」一律用 `ConversationRepository.getLatestConversationIdOfAssistant`（单列 SQL），`getFirstConversationMetaIdOfAssistant`（置顶优先）。禁止 `getAllConversations()` / `getConversationsOfAssistant().first()` + `maxByOrNull` 的写法——那会把**全库/全助手会话**（含旧版 `nodes` 列残留整串 JSON）一次性载入内存；历史根因：夜间定时任务/后台回调每触发一次就全库扫一遍，叠加 3:00 AM 记忆同步把 512MB 堆耗尽。
- **MemoryConsolidationWorker 用轻量元数据**：遍历全部会话做 L2 候选计数/L3 top-5 选择时，用 `getConversationMetasOfAssistant`（仅基本列，零消息加载）；完整会话一律按会话按需 `getConversationById`（L2 循环逐个取，L3 只取 top-10 候选再过滤）。禁止恢复「先全量水合再 filter」。
- **旧版 `nodes` 列解码门槛**：`fetchFullConversations` 仅在「会话没有任何节点行且需要内容」时才兜底解码旧 `nodes` JSON（拆表迁移按会话原子完成，有节点行的会话节点表必完整）。整串解码单行可达数 MB，恢复过旧备份的库残留此列时是所有加载路径的放大器。
- 残留风险（未修，另行处理）：DiaryWorker `loadAllMessages=true` 一次载入游标后全部消息（日记多日未跑时巨大）；AI 生成图以 `data:image;base64` 内联落库 `chat_messages.content_json`（每张 1-5MB 永久放大所有加载路径）；`estimateTokens` 不计图像 part（图像条数不受上下文预算约束）。

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
- **TTS 输出**：所有提供商统一输出 16-bit 小端单声道裸 PCM。采样率：Mimo / Gemini / OpenAI / Custom / Fish Audio 24kHz，MiniMax 32kHz，Azure / ElevenLabs 16kHz，System TTS 为引擎实际值。缓存若为旧的非 PCM 格式必须删除并重新合成。
- **Fish Audio Provider**（`tts/.../providers/FishAudioTTSProvider.kt`）：`POST https://api.fish.audio/v1/tts`，模型通过 `model` 请求头传递（默认 `s2.1-pro`，无额度账号用 `s2.1-pro-free`），音色通过请求体 `reference_id` 传递；二者是不同概念，禁止混用。请求固定 `format=pcm` + `sample_rate=24000`（官方 pcm 为 16-bit mono 裸流，无容器头），语速走 `prosody.speed`（0.5–2.0）。音色列表 `GET https://api.fish.audio/model`（`self=true` 取自有音色，分页 `page_size/page_number`），失败只提示错误、不得影响配置页或其他 Provider。智能体声音副本只存 `voiceId/model/speed`，`apiKey` 由 `stripConnectionFields` 清空并在运行时从全局 Provider 合并。
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

### 10.7 异常中断恢复 (Call Recovery)
- **背景**：进程被杀 / 划掉最近任务 / 崩溃 / 系统重启不会执行正常 `hangup()` 收尾，会遗留 `status=CONNECTED` 且 `disconnected_at IS NULL` 的记录（时长 0、无结束状态）与无引用孤儿录音。
- **通话心跳**：接通期间 `VoiceCallManager.startCallHeartbeat` 每 **30s**（`CALL_HEARTBEAT_INTERVAL_MS`）以单行 UPDATE 持久化 `call_records.last_heartbeat_at`（`touchHeartbeat`，禁止整行 REPLACE 覆盖）；hangup 清理段取消心跳 Job。误差上限 = 心跳间隔，不伪装精确时长。
- **启动对账**（`service/voice/CallRecovery.reconcile`，EvoliaApp onCreate 以 `AppScope.launch(Dispatchers.IO)` 异步执行，不阻塞启动）：
  1. 查询 `getLeftoverConnected()`（CONNECTED 且无 disconnected_at），跳过与 `VoiceCallManager.currentActiveCallId` 匹配的活跃通话；
  2. 结束时间锚点 = **clamp(max(最后有效心跳, end_time 末条转写时间), connected_at, 恢复时刻)**——end_time 可能晚于最后一次心跳故取较晚者；两者皆无时以 connected_at 兜底（时长 0，不伪造）；恢复记录 `status=ENDED + end_reason="abnormal_process_death"`（已接通语义，**禁止落 FAILED**），`duration_ms = max(0, endedAt - connected_at)`；纯函数逻辑在 `CallRecovery.computeRecoveredEndAnchor` / `computeRecoveredDurationMs`（有单测固化）；
  3. 遗留记录的 audio 引用经 `CallRecovery.isAudioFilePlayable`（MediaExtractor 校验有效音频轨道，未完成封装的 m4a 缺 moov 会失败）校验，不可播时**仅清 audio 引用，保留通话记录与转写**；
  4. 孤儿录音清理：扫描 `filesDir/call_recordings/`，同时满足「DB 无引用（按文件名比对相对/绝对路径）+ lastModified 超过 24h（`ORPHAN_RETENTION_MS`）+ 非活跃 callId」才删除；mtime 不可读（≤0）保守保留；单文件失败仅记日志。
- **幂等性**：恢复只处理 `disconnected_at IS NULL`，重复启动不重复生成；对账与正常 hangup 互不影响，不改变正常通话既有行为。正常挂断收尾（`stopCallRecordingAndPersist`）也在 `updateAudio` 前做同一完整性校验，失败不落引用（文件留给孤儿清理回收）。

## 11. 构建与验证 (Build & Verification)

-   **单元测试**: `./gradlew :app:testDebugUnitTest`（纯 JVM 环境，无 Robolectric）。注意 `android.util.Log` **未 mock**——需要单测覆盖的逻辑必须抽为不依赖 Android API 的纯函数（参考 `mergeSyncedTools`、`buildMcpCatalogPrompt` 的做法）。现有测试位于 `app/src/test/java/me/rerere/rikkahub/`。
-   **最小编译检查**: `./gradlew :app:compileDebugKotlin`。
-   **模块依赖顺序**: `app` 依赖 `ai / common / search / tts / highlight`；改动子模块公共接口后必须连同 `app` 一起编译验证。

## 12. 小机电台 (Radio) 与媒体基础设施 (2026-11)

> 核心文件：`data/radio/`（RadioService 业务层 / RadioRepository / RadioAudioRules 纯函数 / RadioAudioGenerator）、`core-data/.../db/entity/Radio*Entity.kt`、`data/ai/tools/RadioAgentTools.kt`（Agent 工具）、`service/media/`（统一播放）、`ui/pages/radio/`（页面）。

### 12.1 数据模型（DB v44，4 张新表）
- `radio_channels`（栏目）/ `radio_episodes`（节目，status=DRAFT/PUBLISHED）/ `radio_audio_assets`（音频资产，status=CURRENT/BACKUP）/ `radio_episode_deletion_logs`（删除审计，**无 FK、独立于节目记录存在**）。
- **不使用 Room FK**，关联完整性由 `RadioService` 业务校验（删栏目前必须处理关联节目）。
- **新增表必须同步登记 `DatabaseSanitizer` 白名单（蛇形物理表名）**：`radio_channels` / `radio_episodes` / `radio_audio_assets` / `radio_episode_deletion_logs`。
- 每期节目只有一份文字稿（`radio_episodes.content`），无草稿表、无版本历史。

### 12.2 节目业务规则（UI 与 Agent 工具共用 RadioService，禁止各自实现）
- **保存**：只有 `save_episode`（工具）落库；对话中生成未保存的文字稿不是节目。
- **修改**：DRAFT 可改标题+文字稿；PUBLISHED 只能改标题（`update_episode` 对 PUBLISHED 必须明确报错）。
- **文字稿变化即清音频**：修改未发布节目文字稿后立即删除 current+backup 音频文件与 `radio_audio_assets` 记录，禁止旧稿音频留作备用。
- **删除**：删除节目只向创作 Agent 提供（工具 `delete_episode`）；用户不能在 UI 直接删 AI 创作的节目。删除时清理节目+全部音频+资产记录，但必须写入 `radio_episode_deletion_logs`（含 operator_id/operator_type/reason/requested_by_user）；用户点名删除无需重复确认，Agent 自主删除必须有明确依据。
- **发布**：`publish_episode` 只校验不合成——节目存在、有文字稿、有 current 音频、文件真实可播（MediaExtractor）、`content_hash` 与当前文字稿一致；缺音频返回失败要求先 `generate_episode_audio`。
- **音频丢失降级**：发布后音频文件丢失/损坏 → 节目保持 PUBLISHED，页面展示异常态，不回草稿；重新生成使用创作 Agent 当前语音配置。

### 12.3 音频资产生命周期
- 存储：`filesDir/radio_audio/{audioId}.m4a`（AAC 32kbps 单声道，采样率随 Provider；编码失败降级 WAV）。**不得放进 TTS 临时缓存**；不参与通话录音 7 天清理；「清理缓存」只清 upload/、images/，天然不影响。
- 生成：TextChunker 分句 → 逐句 TTS 合成（16-bit LE mono PCM）→ 拼接（句间 200ms 静音）→ 校验成功后才写资产记录；**任一分句失败即中止并删半成品**，不得先删旧音频。**全链路只允许流式合成**（`generateChunked` 逐句落盘 + 流式编码）：UI「重新生成」（`requestAudioGeneration`）与 Agent 工具兜底同步路径 2026-11 起同样走 `generateChunked`；**整段 PCM 驻留内存的 `generate()` 已删除**（长稿多重复制后峰值可耗尽 Java 堆，#20026 OOM 根因之一），禁止以任何形式回退。
- **同一文字稿重新生成**：新音频成为 current，原 current 转 backup（3 天过期），原 backup 直接删除；**恢复备用音频**：backup↔current 互换，新 backup 重算 3 天。策略只适用于文字稿未变（`content_hash` 判定）。
- 备用音频过期与孤儿文件（DB 无引用且 >24h）由 `RadioAudioCleanupWorker` 每日清理。
- `radio_audio_assets` 必须记录**实际使用**的 provider_id + voice_id（回退后记录回退结果）。
- 并发：RadioService 以 per-episode `Mutex` 串行化「文字稿更新 vs 音频生成/发布」。

### 12.4 Radio Agent LocalTools（按需 Schema，复刻 MCP 模式）
- `data/ai/tools/RadioAgentTools.kt`：常驻仅两个固定工具 `get_radio_tool_schema`（查参数定义）与 `call_radio_tool`（执行，先经 `validateAgainstSchema` 校验）；工具目录（13 个业务工具名+描述）由 `buildRadioCatalogPrompt`（纯函数，有单测）注入 systemPrompt。工具清单：get_channels / manage_channel / save_episode（创建+更新未发布文字稿，已吸收 update_episode）/ update_episode_title / query_episodes（**创作者按名字过滤**（`creator_name`，Agent 不知道自身/他人 ID；同名 Agent 全部命中，由 `RadioEpisodeRules.resolveCreatorIdsByName` 纯函数解析；EpisodeQuery 用 `creatorAgentIds` 集合承载，分页前过滤）；**status 非必填，不传默认 PUBLISHED**（查草稿显式传 DRAFT））/ get_episode_content / delete_episode / generate_episode_audio（**异步后台任务**：立即返回 task_id 不阻塞对话，chunk 断点续传 + 50min 总时限 + completion_policy 回传，见第 13 节；不返回音色信息）/ restore_episode_audio / publish_episode（**visibility 必填**（public/private），成功提示引导调用 share_episode；**不要求用户授权**，音频就绪即可发布，见 13.5）/ update_episode_visibility（修改已发布节目可见性，草稿不可单独改）/ share_episode（发送电台分享卡片消息，autoplay=1 自动播放；无 delete_episode_audio——音频删除不向 Agent 开放）。
- 工具开关：`LocalToolOption.RadioStation`（按助手 opt-in，默认关），在 ChatService 工具装配处与 LocalTools 同路径追加。
- 全部工具调用走 RadioService（参数校验/状态校验/音频资产管理/删除审计共用），禁止工具内直连 DAO。
- `manage_channel`(create) 的授权要求（需用户明确授权）是**行为约束**，写在工具描述中；`publish_episode` **不设授权门槛**（2026-11-12 按产品决策移除，音频就绪即可发布）。

### 12.4a 节目可见性（DB v47，`radio_episodes.visibility`）
- 取值 `PUBLIC`/`PRIVATE`（`RadioEpisodeVisibility` 常量；工具入参大小写不敏感，经 `RadioEpisodeRules.normalizeVisibility` 规范化，非法值报参数错误）。迁移默认 PUBLIC（历史节目全部公开）。
- **语义**：只约束 Agent 侧查询（`query_episodes` / `get_episode_content` 等**读取**操作）；user 与创作 Agent 恒可见，UI 永不过滤。过滤入口：`RadioService.queryEpisodes(query, viewerAgentId)` 与 `getEpisodeForAgent(id, viewerAgentId)`（PRIVATE 非创作 Agent 一律按「节目不存在」处理，不泄露存在性）；**禁止**在 UI 查询路径传 viewerAgentId 或自行实现过滤。
- **发布时指定**：`publishEpisode` 必带 visibility（UI 发布按钮缺省 PUBLIC）；已发布节目经 `updateEpisodeVisibility` 修改（仅 PUBLISHED，草稿的可见性随 publish_episode 指定，不单独修改）。
- UI：已发布节目在卡片/详情页标注「公开/私密」Tag（共用 `EpisodeVisibilityTag`），草稿仍标「草稿」。
- 音频重生成音色：详情页手动「重新生成」按节目 `creatorAgentId` 解析创作助手当前专属音色（`voiceProviderId + voiceSettings`，`TtsVoiceResolver` 统一回退），与 Agent 工具生成同规则；禁止回退为传 null（全局默认）。

### 12.5 Agent 独立语音配置（统一语音选择）
- `Assistant.voiceProviderId + voiceId`（可空；null = 用全局默认）；引用必须是 **provider_id + voice_id**，不能只存音色名。
- Settings 新增 `ttsVoiceEntries: List<TTSVoiceEntry>`（用户管理的可用音色库）与 `defaultTtsVoiceEntryId`；空列表=旧模式（全局默认仍走 `selectedTTSProvider`，老用户零迁移）；非空时全局默认=default 条目（失效回退第一个有效项→legacy）。
- 解析统一走 `TtsVoiceResolver.resolve`（纯函数，有单测）：Agent 专属（且在音色库中）→ 全局默认 → legacy selectedTTSProvider → SystemTTS；不可用回退时返回 `fallbackNotice` 提示用户。**所有 TTS 调用点**（Radio 生成、微信语音消息、语音通话、自动朗读）都必须经此解析。
- `withVoiceOverride(setting, voiceId)` 按子类映射音色字段；MiniMax DESIGN/CLONE 音色不支持作 Agent 专属音色（回退并提示）。
- 音色库 UI：语音服务设置内管理（添加/设默认/删除），**禁止删除或禁用最后一个可用音色**。

### 12.6 统一媒体播放 (Media3 Session)
- `service/media/MediaPlaybackService : MediaSessionService`（单实例 ExoPlayer + MediaSession，foregroundServiceType=mediaPlayback）；UI 只经 `MediaPlaybackController`（MediaController 封装，Koin single）发指令/订阅状态，**禁止各页面自建 ExoPlayer 播电台/录音**。
- 电台节目与通话录音共用同一播放会话；**同一时刻只允许一个媒体源播放**（与聊天语音条 VoiceMessagePlayer 双向互斥：统一播放器开播即停 VoiceMessagePlayer，反之亦然）。实时语音通话链路不接入。
- 队列项只存 ID 引用（episodeId/callId），标题实时从 DB 读取；改名后经 `refreshMetadata` 刷新播放器与通知栏（禁止维护标题副本）。播放模式：随机/单曲循环/顺序（顺序播完即停，不自动循环列表）。

### 12.7 备份恢复（媒体资产）
- `WebDavConfig.BackupItem` 新增 `CALL_RECORDINGS` / `RADIO_AUDIO`，默认勾选；两者依赖 DATABASE（未勾选时 UI 禁用）。ZIP 布局新增 `call_recordings/`、`radio_audio/` 顶层目录。
- 老用户一次性迁移（DataStore key `backup_items_media_migrated`）：补两项默认勾选，保留用户原 DATABASE/FILES/TTS_CACHE 选择；迁移后用户取消勾选不被回退。
- 恢复规则：录音/电台音频文件写回私有目录，**公共目录导出件的 DB 绝对路径改写回相对路径**（forever 语义不变）；`forever` / `audioExpiresAt` / `expires_at` 原值保留，不重置；文件缺失 → 录音 `audio=null`、电台删对应资产记录并按无音频降级，节目保持 PUBLISHED。
- **FILES 项目录清单（2026-11 扩展）**：`upload`（聊天附件 + 聊天背景图）/ `avatars`（仅备份在用头像）/ `lorebook_attachments` / `app_locks` / `python_sandbox`（Agent 沙盒工作文件，按会话子目录）/ `images`（Agent 生图产物）。全部**递归打包**，zip 条目 = `<folder>/<相对路径>`；恢复走通用分支（按 FILES 项还原任意目录，含嵌套父目录 mkdirs + 条目名 `..` 防 Zip Slip）。旧备份无新目录条目时恢复自动兼容，无需迁移。
- **扫描日记图片备份/恢复（2026-11 扩展）**：`diary_images`（`filesDir/diary_images/<diaryId>/<imageId>.webp`）**不并入通用 FILES 循环**（通用 `addFileToZip` 吞异常），走 `WebdavSync.packDiaryImagesStrictly` 专项打包：直接复制 WebP 二进制（不重压缩/改命名），对账 DB 引用并统计（dbRefs/packed/failed/missing/outside/bytes）。**完整性闸门**：任一引用图片缺失（历史遗留）或打包 IO 失败 → 删除半截 zip 并抛 `DiaryImageBackup.IncompleteBackupException`（本地导出/WebDAV 手动/自动备份共用 prepareBackupFile，三个入口均以异常消息提示，不静默成功）。恢复侧走通用 FILES 分支还原 `diary_images/`，恢复完成后 `repairDiaryImagePaths` 对账：DB 路径失效但可按 diaryId/imageId 重建（`DiaryImageUtil.getDiaryImageFile`）→ 改写 DB 路径；文件确实不存在 → **保留图片元数据与 OCR 结果，仅警告日志，绝不删日记**；历史备份无该目录 → 正常恢复其余数据。纯决策函数在 `data/sync/DiaryImageBackup`（单测 `DiaryImageBackupTest`）。
- **恢复后 DB 对账必须用独立裸 SQLite 连接（实测教训）**：`cleanDb.copyTo(finalDb)` 在 Room 连接存活期间覆盖 DB 文件，且 finalDb 的 -wal/-shm 被删除后 Room 旧连接继续持有已 unlink 的 fd——Room 读到的是"私有宇宙"里的恢复前旧数据（模拟器 d2d 自动恢复场景下 dbRefs=0/路径全 Keep 的根因）。恢复流程内的一切对账（如 `repairDiaryImagePaths`）必须 `SQLiteDatabase.openDatabase(finalDb)` 裸连接读写磁盘最终态，修复写入后由强制的 App 重启让 Room 重新加载。模拟器卸载重装会触发 Android Auto Backup/d2d 灌回旧数据，纯净测试需用 `pm clear` 而非卸载重装。

### 12.8 定时创作
复用 `agent_task_manager`（AGENT_TASK）：定时唤起 Agent 走 `ChatService.executeAgentTask` 同一生成链路，Radio 工具可用即可定时创作草稿/发布；不新增 Radio 专用调度器。

## 13. 通用后台任务机制与结果回传（2026-11）

> 核心文件：`data/backgroundtask/`（BackgroundTaskManager / BackgroundTaskExecutor / RadioAudioGenerationExecutor / BackgroundTaskRules / BackgroundTaskPayload / ChunkResumeRules）、`core-data/.../db/entity/BackgroundTaskEntity.kt` + `dao/BackgroundTaskDao.kt`（条件 UPDATE 状态机）、`service/BackgroundTaskWorker.kt`（长时运行 Worker）、`service/BackgroundTaskActionReceiver.kt`（通知取消 action）、`data/ai/tools/BackgroundTaskTools.kt`（get/cancel 工具）、`ui/components/chat/BackgroundTaskCard.kt`（任务卡片）。

### 13.1 数据模型（DB v46）
- `background_tasks` 表：任务状态/结果/回调投递状态的**唯一事实源**——Agent 查询（get_background_task）、UI 任务卡片、回调投递都只读此表，不依赖内存 State / WorkManager 临时状态 / 进程内 EventBus。**新增表已登记 DatabaseSanitizer 白名单（`background_tasks`）**。
- `radio_audio_assets.task_id`：任务与资产关联；提交原子边界崩溃恢复的幂等判据（按 task_id+content_hash 查已提交资产，不限 CURRENT——已提交后转备份也不重复合成）。
- **deadline 语义（产品定稿）**：`deadline_at = created_at + 50min`，任务**总生命周期上限，含排队等待时间**。TTS 预计 30 分钟内完成，50 分钟容纳网络波动/调度延迟/中断恢复，防止任务无限挂起。不追求连续运行满 50 分钟；验收重点是「被系统停止后能恢复执行」。认领/逐句心跳/重试认领前都检查 deadline，超时置 failed（保留诊断信息与可恢复数据）。

### 13.2 状态机与并发（禁止回退的硬约束）
- 六态：`queued → running → committing → succeeded|failed`；`queued|running → cancelled`。全部状态变更走 BackgroundTaskDao **条件 UPDATE**（认领/终态/取消/回调各迁移均带 status+execution_id 条件），终态不可覆盖；旧 Worker（execution_id 不匹配）无法写入新 Worker 结果。
- **SQL 状态字面量必须全大写（历史 bug 硬约束）**：实体列存 `BackgroundTaskStatus.name`（'QUEUED' 等），SQLite 字符串比较默认 BINARY 大小写敏感——@Query 里写小写 `'queued'` 会让条件 UPDATE 永远匹配 0 行（症状：任务永远停在 QUEUED、Worker 重试耗尽后静默 failure、取消/恢复扫描全部失效）。新增状态相关 SQL 时必须核对大小写。
- 执行租约：`execution_id + lease_expires_at`（60s，逐句心跳续租）。**租约过期仅代表「可认领」，实际重调度延迟 = max(租约剩余, WorkManager backoff)**，不代表 60s 内必然重跑。Worker 显式 `.setBackoffCriteria(LINEAR, 30s)`——项目「不设 backoff」惯例的唯一例外（进程被杀后需要尽快续跑，避免指数退避吃掉 deadline），改其他 Worker 时不要照抄。
- 创建并发：per-refKey Mutex（**单进程假设**：Manifest 无 android:process，全部创建路径经 Manager）+ 锁内「查活跃任务→决策→插入」+ 启动对账兜底（同 ref_key 多活跃任务保留最新取消其余）。不用部分唯一索引（Room 校验 @Entity 声明的索引，partial/expression 索引无法声明，会触发迁移校验失败）。
- **提交原子边界（committing）**：生成文件 → `toCommitting`（此后取消被明确拒绝）→ `RadioService.commitEpisodeAudio`（episodeLock 内 re-check content_hash 封堵「生成→提交」间文字稿变化 + asset-by-task_id 幂等保护）→ `finalizeSucceeded`（result 与终态同条 UPDATE 原子落库）。崩溃恢复：committing+租约过期 → 重认领回 running → 按 task_id 判断资产已提交（直接 finalize succeeded）或未提交（断点续传后重走提交）。
- 取消语义：仅 queued/running 可取消；cancelTask 原子置 cancelled + cancelUniqueWork；Worker 被停止的清理必须在 `withContext(NonCancellable)` 内（历史 bug 模式）。**用户中断对话不取消后台任务**（Worker 挂 WorkManager，独立于生成 Job）。

### 13.3 执行机制（50 分钟可行性）
- WorkManager 2.11 长时运行 Worker + `setForeground(dataSync)`；Manifest 已声明 `androidx.work.impl.foreground.SystemForegroundService` `foregroundServiceType="dataSync"`（tools:node="merge"）。Android 15/16 dataSync 6h/24h 预算是硬约束（与 mediaProcessing 共享预算）——超预算 onTimeout → Worker 停止 → WorkManager 自动重跑 → 租约过期重认领 → 断点续传续跑；预算彻底耗尽任务 failed（"系统后台执行预算耗尽"类文案）。用户与 App 交互会重置 dataSync 计时。**不引入独立自建 FGS**（同类型同预算，丢 WorkManager 持久化调度零收益）。
- **TTS chunk 断点续传（`RadioAudioGenerator.generateChunked`）**：每句独立 chunk 文件（`radio_audio/tmp/{taskId}/chunk_{i}.part` → 原子 rename → `chunk_{i}.pcm`，**rename 原子性即句子完成事实源**，正确性不依赖 progress_json——其仅作 UI 进度展示）；恢复按 `ChunkResumeRules` 最大连续前缀续跑、清理 .part 残留；`manifest.json`（原子写）记录 total/providerId/sampleRate——缺失或 provider 变化 → 全部作废重跑（防采样率失配拼接）。`PcmAacEncoder.encodeFile` 流式编码（50 分钟 PCM ≈144MB **不可载入内存**；旧 `encode(ByteArray)` 路径保留给 UI 短稿）。单句失败立即重试 1 次；任务级 ≤3 次（Result.retry 共享同一 deadline，不重新获得完整 50 分钟）。
- 启动恢复：`EvoliaApp.onCreate` 异步 `BackgroundTaskManager.recoverTasks()`（幂等）：WorkManager 丢失的重入队 / 租约过期执行中任务重置 queued / deadline 已过置 failed / 回调恢复。

### 13.4 任务结果回传与 Agent 后台唤起
- 回调状态机与任务状态**互相独立**：`NONE → PENDING → DELIVERING → INJECTED → COMPLETED`（ABANDONED 旁路）。**任务 succeeded ≠ 回调已送达**：回调失败不改任务状态、不丢结果（Agent 可随时 get_background_task 查询，卡片仍显示成功）；ABANDONED 仅表示唤起不可达，走 ai_messages 兜底通知（gate notificationAiReplyEnabled），任务结果保留。
- 投递时机三处：任务终态即时 / ChatService 生成结束钩子（消化会话忙延后的 PENDING + INJECTED 恢复）/ App 启动扫描。会话忙（generationJob/aiTyping）→ 保持 PENDING 延后，**不无条件打断用户当前对话**；同会话多回调按 created_at 串行。
- **注入幂等（永不重复注入）**：回调指令消息 ID = `BackgroundTaskRules.callbackMessageId(taskId)`（MD5 确定性派生，注入前查库判重）。**INJECTED 恢复按会话内容判定、不按时间租约**（生成轮可能长于任何合理租约）：回调消息后已有 ASSISTANT 响应 → COMPLETED；无响应且消息为末节点 → **resume 模式**（不重复注入，以既有消息为锚直接续跑生成，任务上下文从持久化指令重新解析）；用户已继续对话 → 保守 COMPLETED（结果可查询）。DELIVERING 认领租约 10min 仅覆盖 claim→注入窗口。
- 指令消息：`【系统自动化指令 - 后台任务完成】` + 结构化 JSON（task_id/task_type/status/result/error/follow_up_instruction），`skipContext=true + MessageSource.AGENT_TASK`——用户不可见（ChatList 按 skipContext 过滤）、上下文白名单完整带入不截断（GenerationHandler isContextWhitelistedSource）。新轮次经 `executeBackgroundTaskCallback` → `handleMessageComplete` 独立执行，不伪装原 Turn。L1/L2 摘要是否把该类消息当用户表达【待验证，若纳入需在摘要选材过滤 USER 角色 AGENT_TASK 消息】。

### 13.5 completion_policy 与后续动作边界（2026-11-12 修订：publish 去授权门槛）
- `notify_user`：仅更新任务状态+UI+通知，不唤起 Agent；`notify_agent`（默认）：完成后唤起 Agent 决定告知/后续；`continue_workflow`：唤起 Agent 按 `follow_up_instruction` 推进后续动作（必填）。
- **publish_episode 不设授权门槛（2026-11-12 按产品决策移除）**：普通对话轮次与自动化回调轮次均可直接发布。原机器授权链路（`RadioToolContext.isAutomatedCallbackTurn` / `authorizedActions` → `BackgroundTaskRules.authorizedPublishAction` → 持久化指令 `authorized_publish_episode_action` 字段）已整体删除；历史已注入指令中的旧字段靠 JsonInstant 忽略未知 key 兼容，resume 解析不依赖它。删除类操作不进入 continue_workflow 范围（删除仍要求用户点名或 Agent 给出明确依据）。

### 13.6 通用任务工具与 UI
- `get_background_task`（task_id 缺省 = 当前会话最近 10 条，解决上下文裁剪后找不到 task_id；所有权校验 agent_id）/ `cancel_background_task`（所有权+状态校验；committing/终态明确拒绝）。**主动查询是回调的兜底能力，不是替代品**。
- 任务卡片 `BackgroundTaskCard`：订阅 `observeTask` Flow；五态（排队/生成中·第 N/M 句进度条/完成·播放入口走 MediaPlaybackController/失败·原因+重试提示/已取消）；**callback_state 不混入卡片主状态**。挂载惯例同 RadioShareCard：ToolResult 识别 task_id → `AssistantRenderItem.BackgroundTaskItem`；**task_id 必须先过 `BackgroundTaskRules.isBackgroundTaskId` UUID 校验**（agent_task_manager 等其他任务体系也返回数字型 task_id，未校验会误挂载「任务记录不存在或已被清理」占位卡）；**turn 内按 taskId 去重**（创建返回与多次 get_background_task 查询结果都带 task_id，只渲染首张——卡片订阅 DB 实时状态，重复卡片无信息量）；跨 turn 查询渲染新卡片（新一轮对话的实时状态展示）。

### 13.7 新长耗时工具接入清单
实现 `BackgroundTaskExecutor`（taskType + `execute(task, executionId, onProgress)` + `cleanup`/`cleanupTransientOutput`）→ AppModule 注册 → BackgroundTaskManager.executors 登记即可；调度/租约/状态机/回调/查询/取消/通知全部复用，禁止在业务模块内自建任务管理。

## 14. 消息持久化与中断规则（2026-11 修订）

- **生成期增量持久化（禁止回退到「整轮结束才写库」）**：普通模式 collect 内——新节点出现（ToolCall/ToolResult 边界，anyNewMessages 判定）立即 `launchIncrementalNodeSave`；纯流式文本 ≥2s 节流（`INCREMENTAL_SAVE_INTERVAL_MS`）。保存走 per-conversation persistenceMutex **锁内重新读取最新快照**后差量 upsert（`ConversationRepository.saveNodesForConversation`，仅 upsert 指定节点行+消息行，orderIndex 取全列表位置，**不删除任何行**——分页窗口外数据安全）。整轮结束全量保存保留；微信模式逐句落库保留。
- **中断语义（硬约束）**：stopGeneration 只取消生成 Job——不删除 Turn/消息/ToolCall/ToolResult、不取消已创建的后台任务、不清内存。取消兜底保存**同步捕获**局部 currentConversation 快照（Conversation/UIMessage 均不可变 data class，捕获引用即冻结）后直存 `conversationRepo.saveConversation(捕获对象)`——**禁止**经 mutateConversationAndSave/ensureConversationLoaded 从 DB 重载（中断后 500ms 内存逐出竞态会用无 partial 的 DB 快照覆盖丢失已流式内容）。最后一条流式 ASSISTANT 消息标记 `interrupted=true`（UIMessage 字段，随 JSON 持久化；UI 显示「已停止，以上内容已保留」；「生成中」判定走内存态 generationJobs，重进会话不会把已中断消息误判为生成中）。
- **重进页面窗口重建顺序（禁止回退）**：ChatVM 的会话节点同步 collector 必须在 `initPaginationManager`（loadInitial 完成）之后启动——loadInitial 以 DB 清空重建窗口，与内存同步并发执行顺序不定时 partial 被抹掉且 TTS 阻塞期间无新发射无法自动补回。节点合并沿用 syncConversationNodes 既有规则（内存新版本按 id 替换、空壳不覆盖、缺节点追加、窗口外不受影响）。

## 15. 临时会话（Temporary Chat，2026-11-12 独立化改造）

### 15.1 架构定位（硬约束）

- **临时会话是独立运行模式，不是正式会话上的开关**。入口为正式聊天页 TopBar 的临时会话按钮（空会话与**普通对话中均可用**，普通对话中点击即随时切入临时房间，退出 `popBackStack` 回到原会话，内容/草稿/滚动由导航栈保留）→ `Screen.TemporaryChat(assistantId)` 独立 Navigation Destination（`ui/pages/chat/TemporaryChatPage.kt` + `TemporaryChatVM.kt`），禁止再在 ChatPage 上以 `if (isTemporaryChat)` 分叉实现。
- **身份唯一权威是 `service/TemporaryChatSessionRegistry.kt`**（Koin single，注入 ChatService 构造参数 `temporarySessions`）：sessionId 为运行期 UUID，**创建时即注册**（`ChatService.createTemporarySession(assistantId)`），发送/生成/总结/记忆等链路统一查 `isTemporary(id)`，**禁止**依赖调用方逐个传临时标记（`sendMessage` 的 `isTemporaryChat` 参数已删除）。
- **临时消息绝不落库**：仅存在于 ChatService `conversations` 内存 StateFlow；不创建 Conversation 记录、不写 ChatMessage、不更新 lastConversationId / 当前助手 / lastSummarizedMessageTime 等任何正式状态。进程死亡即自然销毁，无持久化恢复。

### 15.2 ChatService 守卫清单（禁止遗漏，新增写库路径必须同步检查）

| 位置 | 守卫 |
|---|---|
| `sendMessage` | 入口检查 `isDestroying` → 拒绝销毁中的会话写入 |
| `ensureConversationLoaded` | 临时会话：注册表确认 + 内存流存在即返回 true（否则所有 mutate 路径被 DB miss 静默丢弃） |
| `launchIncrementalNodeSave` | 临时会话直接 return（流式增量落库禁用） |
| 中断/异常兜底保存（handleMessageComplete failure 分支） | 临时会话跳过 `saveConversation` |
| `mutateConversationAndSave` / `replaceConversationMessages` | 临时会话只更新内存 + 删文件，不落库 |
| `checkAndAutoSummarize` / `summarizeAndRefresh` / `archiveConversation` | 执行层守卫：临时会话直接 return（覆盖自动总结、立即整合、刷新上下文全部入口） |
| `recordDailyActivity` | 临时会话跳过（不计入每日活跃） |
| `getConversationFlow` LRU 淘汰 | 跳过临时会话 |
| `checkAllConversationsReferences` | 跳过临时会话（历史 bug：生成结束 500ms 后的无引用清理把临时消息缓存逐出，下一条消息被写进 `computeIfAbsent` 重建的空会话——UI 看不到消息、AI 上下文清空；与 LRU 淘汰必须同步排除，两处缺一不可） |
| `initializeConversation` | 临时会话：跳过 lastConversationId 追踪、离场归档、DB 重载；仅确保内存会话存在 |

**墓碑判定（统一 `isTemporaryOrDestroyed`，禁止只查 `isTemporary`）**：注册表 `remove()` 后 `isTemporary`/`isDestroying` 同时归 false，唯一残留标记是 `destroyedTemporaryIds` 墓碑（内存态）。任何**执行时点晚于 `destroyTemporarySession`** 的异步路径（中断/异常兜底保存、onSuccess 的 lastConversationId 写入、`executeAgentTask` 目标会话选择等）必须用三合一判定——只查 `isTemporary` 会在销毁完成后放行（历史 bug：生成中退出临时会话 → 临时消息被兜底落库成「幽灵会话」+ `lastConversationId` 被写成临时会话 id → 幽灵霸占所有「最新会话」解析 → 首页进入幽灵会话后 `sendMessage` 被墓碑守卫静默丢弃，表现为「发消息看不到、无回复」）。

**引用持有（双保险）**：`TemporaryChatVM` init 时 `addConversationReference(sessionId)`、`onCleared` 时 remove（与正式 ChatVM.trackConversation 同机制）；`destroyTemporarySession` 一并清 `conversationReferences`。任何新增的「无引用清理/逐出」路径都必须同时排除临时会话。

### 15.5 定时任务路由与「进入普通会话」导航拦截（2026-11-13，不变式：临时会话与普通会话视图不能同时存在）

- **定时任务绝不进临时会话**：`executeAgentTask` 的目标会话候选（`conversationReferences ∩ conversations` 按 `updateAt` 取最新）必须经 `isTemporaryOrDestroyed` 过滤——临时会话开着时它引用在册且 `updateAt` 最新，不过滤则任务指令+回复被注入临时会话、随会话销毁蒸发。落点 = 用户正打开的正式会话（临时页下方）→ DB 最新正式会话 → 新建。
- **临时会话期间任务回复强制通知**：任务触发时若有活跃临时会话（任意助手），生成完成后在 `job.invokeOnCompletion` 补发通知（`isForeground` 门控会拦掉 App 前台的常规通知，导致用户在临时会话里完全无感知）；仍尊重 `notificationAiReplyEnabled` 总开关。
- **通知/快捷方式导航拦截（RouteActivity）**：`NotificationHandler` 的 `Screen.Chat(conversationId)` 与 `handleIntent` 的 assistantId 分支（快捷方式，日记页不拦截）在 `hasActiveTemporarySession()` 为 true 时改为暂存 `pendingTempExitNavigation` 并弹 `ConfirmDialog`（dangerous=true）：确认 → `navigate(Screen.Chat)` 带 `popUpTo(currentDestination, inclusive)` 弹出临时会话页（`TemporaryChatVM.onCleared` 兜底销毁）；取消 → 丢弃导航、临时会话原样保留。禁止绕过此弹窗直接清栈跳转（会静默销毁临时会话内容）。

### 15.3 记忆只读（双层拦截）

- **读取保留**：生成链路 `retrievedMemories` 检索对临时会话照常执行（基于角色已有记忆对话）；`retrieve_memory` 工具保留；记忆详情查看（`getFullMemoryContent`）保留。
- **写入双层拦截**（`GenerationHandler.kt`）：① 工具构建层——`isTemporarySession()` 为 true 时不注册 `create_memory`/`edit_memory`/`delete_memory`；② 执行层——`TEMPORARY_BLOCKED_TOOLS` 集合在工具循环中以错误 ToolResult 拒绝（防模型幻觉调用）。不生成 Segments、不生成会话总结、不触发自动记忆整合。

### 15.3a 工具白名单（2026-11-12 与用户逐项确认，规则唯一事实源 `data/ai/tools/TemporarySessionToolRules.kt`，有单测）

- **两层拦截**：① 装配层——`ChatService.handleMessageComplete` 组装 `targetOptions` 后按 `TemporarySessionToolRules.filterOptions` 过滤（与记忆联动的 MilestoneManagement 先组装完再统一过滤；RadioAgentTools 注入以 targetOptions 判定，过滤后自然跳过）；② 执行层——`GenerationHandler.TEMPORARY_BLOCKED_TOOLS` 引用 `TemporarySessionToolRules.blockedToolNames`，模型幻觉调用已下线工具时返回明确错误 ToolResult（助手人设引导调用被禁工具时会收到「不可用」提示，属预期行为）。
- **禁用清单**（写正式数据或现实副作用）：`CallControl`（call_records + 录音）、`AgentAutomation`（agent_task 落库 + 定时唤起）、`ScheduleManagement`（schedule_events + 联动提醒）、`UpdateProfile`（settingsStore 持久化档案）、`MilestoneManagement`（关系档案落库）、`RadioStation`（radio_* 表 + 音频）、`PeekUser`（agent_monitor_tasks 落库，**add/pause 是写不是读**）、`EmailService`（发真实邮件）、`DeviceControl`（锁屏/开 App/静默设系统闹钟）+ `CameraCapture`（已废弃，运行时等同 DeviceControl，getTools 同一开关连带注册设备工具组，必须同禁）。
- **保留清单**（经用户确认）：沙盒计算（eval_python/eval_javascript + 沙盒文件四件套 + import_attachment）、`fetch_url_content`、`search_web`（开关为会话级内存态）、`generate_image`（写 GenMediaEntity 媒体库——用户接受残留）、`print_content`（opt-in）、MCP 双固定工具、`get/cancel_background_task`、`retrieve_memory`、TimeSense（systemPrompt 注入非工具）。

### 15.4 页面与 UI 约束

- TemporaryChatPage：顶部布局 = 返回键 + 临时会话标识（HistoryToggleOff，**紧跟返回键左侧区**，2026-11-14 调整）+ 只读头像（不可点击切换角色）；无新建话题入口。**微信模式下 AI 思考/输出中标题「临时会话」临时替换为「正在输入中...」**（`chat_status_typing`，仅 WeChat mode + isAiTyping）。输入区**跟随用户的 chatInputStyle 设置分支渲染**（MINIMAL → `MinimalChatInput` / 其他 → `ChatInput`，均传 `temporaryMode = true`）。加号菜单与正式会话的**唯一 UI 差异**：隐藏立即整合、上下文刷新（= summarizeAndRefresh，记忆写入族，业务层本就禁止）与通话按钮（拨号即写正式 call_records，会话销毁后成悬挂记录）；模式/世界书/推理/UI模式/搜索全部保留且可用。`ModelSelector` 在临时会话**可点击切换**：选择写入 `TemporaryChatVM.overrideChatModelId`（内存态 StateFlow），经 `sendMessage(chatModelOverrideId=…)` → `triggerAIResponse` → `handleMessageComplete` 只影响本次生成，**绝不写回 Assistant.chatModelId 或全局设置**，退出即失效。`settingsForInput` 以会话助手覆盖 `settings.assistantId` 并把内存态覆盖助手拼接进助手列表，防全局当前助手漂移且会话内配置修改实时反映。
- **消息操作（2026-11-14 与正式页对齐）**：ChatList 已接 `onQuote`（引用，`getSendContents()` 自动把 quotedMessages 转 Quote parts，VM 零改动）与 `onRegenerate`（刷新回复：requirement 输入弹窗 → `canPreserveVersionHistory` 判定 → false 时 forceWipe 确认弹窗，两段式与 ChatPage 一致；`TemporaryChatVM.regenerateAtMessage` 透传会话内模型/助手覆盖 → `ChatService.regenerateAtMessage` 新增可选 `chatModelOverrideId/assistantOverride` 参数）。**regenerate 链路对临时会话安全的前提**：节点替换走 `replaceConversationMessages`（临时守卫=仅内存）、生成走 `handleMessageComplete`（segments/L2/lastConversationId 守卫齐全）。**仍未接的菜单项（点了无反应，按需再接）**：编辑（需 `handleMessageEdit` + 版本切换 UI 配套）、收藏、分享、更新消息。
- **退出统一确认**：返回键/手势/左上角返回 → `hasUserContent()`（存在 USER 消息，预置消息不算）为 true 时弹 `ConfirmDialog`（继续聊天 / 退出并清空）；空会话直接退出。确认后顺序：`tts.stop()` → `vm.requestDestroy()`（= `chatService.destroyTemporarySession`：markDestroying → 取消生成 Job → 取消微信 debounce → 删除会话引用的附件文件 → remove 注册 → cleanupConversation）→ `popBackStack()`。
- **兜底销毁**：`TemporaryChatVM.onCleared()` 再次调用 destroy（幂等 no-op），覆盖通知跳转等绕过确认弹窗的离开路径，保证不遗留活跃生成任务与注册表条目。
- TemporaryChatVM 复用 ChatService 全部生成链路（sendMessage/triggerAIResponse/工具/MCP），不复制 Agent 实现；语音为紧凑实现（录音→ASR→trigger，SystemASR 自动 fallback EvoliaASR/OnlineASR），**不含**正式页的录音期并行流式 SystemASR 优化；不启动 autoRead（避免 clobber 正式页 CustomTtsState 单例订阅），手动 TTS 播放保留，退出销毁时 `tts.stop()`。
- 注册表逻辑有单测固化：`app/src/test/.../service/TemporaryChatSessionRegistryTest.kt`。

## 16. 未读消息角标（Home Agent 头像，DB v48，2026-11-16）

### 16.1 数据模型与唯一事实源

- 表 `chat_unread_messages`（实体 `ChatUnreadMessageEntity`，DAO `ChatUnreadDAO`，DB v48，`MIGRATION_47_48` 仅建表+索引）：`node_id` 主键（= `chat_message_nodes.id`）、`conversation_id`、`created_at`。**未读以消息节点为单位**，一个节点至多一条记录，多版本消息天然去重；`INSERT IGNORE` 幂等。
- `assistant_id` **不落库**，统计经 `INNER JOIN conversationentity` 聚合（`observeUnreadCountByAssistant`，Flow 由 InvalidationTracker 驱动，禁止轮询）；Home 经 `ChatListVM.assistantUnreadCounts` 消费。
- `node_id` 挂 **FK CASCADE** 到 `chat_message_nodes`：删除消息节点 / 删除会话 / 刷新覆盖的未读清理由数据库级联完成，**禁止**在各删除路径重复手写清理。
- 历史消息不回填：升级后未读从零开始。

### 16.2 写入时机与原子性（硬约束）

- 唯一决策函数 `service/UnreadRules.kt`（纯函数，`UnreadRulesTest` 22 用例固化）；写入必须与消息落库**同一事务**（`ConversationRepository.saveConversationWithUnread` → `mutateConversationAndSave(unreadNodes=…)`），禁止「消息已存、未读后补」的跨事务补偿。
- 三个写入入口：① `handleMessageComplete` 的 `onSuccess` 汇总（普通模式）；② 微信模式逐句 `storeWechatSentenceNode` / 整条语音条 / TTS 回退文本（逐句即时决策，节点登记进 `unreadHandledNodeIds` 供 onSuccess 排除）；③ `SpontaneousWorker` 自发消息（`decideUnreadForExternalNodes`）。**任何新增「直接向会话追加可见 ASSISTANT 节点」的路径必须按同样方式接入**。
- **基线语义**：生成开始时的「已有可见内容节点 id 集合」，不是全部节点 id 集合——AI 首条回复会复用生成前的空占位节点 id（`processMessageIds[0] = placeholderId`），用全节点差集会漏计主回复；刷新回复是在已有可见内容节点上追加版本，节点已在基线内 → 不重复计数。
- 流式 Chunk 期间**禁止**写未读；仅在回复持久化完成后写入。生成失败 / 用户中断（failure 分支）不产生未读。

### 16.3 可见性口径（与 ChatList 渲染一致）

- 只计 `UnreadRules.isVisibleAssistantNode` 为真的节点：`currentMessage` 角色为 ASSISTANT、`skipContext == false`、且选中版本有文本或音频/图片内容。因此 CALL / CALL_STATE / CALL_REQUEST / AGENT_TASK / DIARY_COMMENT（均 `skipContext=true`）、纯 ToolCall 节点、空占位、未选中历史版本天然排除。
- 微信模式一次生成多个独立节点 → 按可见节点逐条计数，不合并为一轮。

### 16.4 活跃会话状态与清零

- 「用户正在查看的会话」唯一事实源：`ChatService.activeViewingConversationId`（StateFlow）。ChatPage / TemporaryChatPage 在组合时登记、`ON_PAUSE` / `onDispose` 撤销；判定条件 = **App 前台（`isForeground`）+ 该会话正是当前活跃会话**。禁止在页面或 Service 里另建独立判断变量，禁止只看 `currentAssistantId`（同助手其他会话/后台场景会误判已读）。
- 清零：ChatPage 进入组合及 `ON_RESUME` 调用 `markConversationAsRead(conversationId)`（DELETE 幂等）；按 conversationId 独立，**禁止**进入一个会话时清空该 Agent 的其他会话。
- 临时会话永不产生未读记录（不落库），但必须登记/撤销「正在查看」，否则停留在临时会话期间其他正式会话的回复会被误判已读。
- 备份恢复：`chat_unread_messages` 已登记 `DatabaseSanitizer` 白名单，并在外键重新开启前执行孤儿未读清理 SQL（恢复期间级联不生效）。

## 17. Evolia Extensions（扩展体系，DB v49，2026-11-16）

> 核心文件：`data/extensions/`（ExtensionConstants / ExtensionManifest / JsonSchemaValidator / ManifestValidator / ZipPackageReader / ExtensionRules / ExtensionInstaller / ExtensionRegistry / ExtensionToolRuntime / ExtensionSdkPrelude / MiniAppStorageService / MiniAppBridge / ExtensionNetworkService / ExtensionCreator / ExtensionTemplates）、`data/ai/tools/ExtensionAgentTools.kt` + `ToolDescriptionService.kt`、`ui/components/miniapp/MiniAppWebView.kt`、`ui/components/extension/ExtensionPermissionDialog.kt`、`ui/pages/extension/`（SettingMiniAppsPage / ExtensionWorkshopPage / ExtensionCreatorPage）、`ui/pages/miniapp/MiniAppPage.kt`、`assets/evolia/evolia-sdk.js`。

### 17.1 规范冻结（Phase 0，禁止改动语义）

- **扩展身份**：`app_id` 是唯一稳定身份（正则 `^[a-z][a-z0-9_]{2,63}(\.[a-z0-9_]+)*$`，`ExtensionConstants.APP_ID_REGEX`）。**禁止**通过 app_id 前缀/名称推断扩展是否内置——一律看 `extensions.is_builtin` 显式属性。Tool 唯一标识统一 `app_id:tool_id`。
- **版本**：SemVer（major.minor.patch[-pre][+build]），比较走 `ExtensionRules.compareVersions`（纯函数有单测，prerelease < 正式版）。
- **Manifest**（`ExtensionManifest`，@SerialName 手动 snake_case 映射，JsonInstant 不做策略转换）：`manifest_version=1` 支持范围冻结；安装前必须经 `ManifestValidator.validate`（纯函数，fileExists/parseSchemaFile 注入，有单测）；对不支持的 Schema 关键字**必须报错不得静默忽略**。
- **JSON Schema 校验器**（`JsonSchemaValidator`）：支持 type/required/properties/items/enum/min/maximum/minLength/maxLength/minItems/maxItems/additionalProperties(false) + 注释类关键字（title/description/default/examples）。Manifest 校验、Tool 参数校验（ExtensionAgentTools）、Mini App 数据写入校验（MiniAppStorageService）三者复用同一个校验器。
- **包格式**：`.evolia` = 标准 ZIP。安全限制集中在 `PackageLimits`（20MB 包 / 50MB 解压 / 2000 文件 / 10MB 单文件 / 100 倍压缩比），禁止散落硬编码。
- **权限集合**（`ExtensionPermissions`）：storage.read / storage.write / network.request / ui.toast / ui.dialog。Manifest 声明只是申请，granted 由用户授权（`extension_permissions` 表）。

### 17.2 数据模型（DB v49，六张新表，无 Room FK）

- `extensions`（app_id 主键）/ `extension_tools`（UNIQUE(app_id,tool_id)，enable + custom_description 运行配置）/ `extension_permissions`（UNIQUE(app_id,permission)）/ `mini_app_data`（UNIQUE(app_id,collection,record_id)，data 存 JSON 字符串）/ `mini_app_versions`（数据 schema_version 与扩展版本独立管理）/ `extension_install_operations`（安装恢复记录）。
- `MIGRATION_48_49` 已在 AppDatabase companion + DataSourceModule 注册；六张表全部登记 `DatabaseSanitizer` 白名单（蛇形表名）与测试副本（`DatabaseSanitizerWhitelistTest`；该测试副本此前缺 `background_tasks`/`chat_unread_messages` 的问题已一并修复并有断言固化）。
- `source_type`（当前来源）与 `created_origin`（最初创建来源）分开：本地创建导出再导入，origin 仍是 LOCAL_CREATED。

### 17.3 安装/更新/卸载与崩溃恢复（ExtensionInstaller，硬约束）

- 流程：staging 解压+校验 → 安装操作记录（FILES_STAGED）→ 正式目录原子 rename（更新先备份）→ FILES_SWITCHED → Room withTransaction 提交（Registry+工具配置合并+权限合并+版本记录）→ DATABASE_COMMITTED → 清理 → COMPLETED 并删操作记录。
- **同 ID 判定**（`ExtensionRules.decideInstallAction`，有单测）：类型不同禁止覆盖、同版本拒绝、降级默认禁止、更新保留用户配置。
- **更新合并**（纯函数有单测）：`mergeToolConfigsOnUpdate`（保留仍存在 Tool 的 enable/custom_description，删已下线配置）、`mergePermissionsOnUpdate`（保留已声明权限的授权，新增权限默认 granted=false 不得自动授予；`grantNew=true` 仅用于用户在安装/更新弹窗中明确同意的场景）。
- **安装/更新期权限确认（2026-11-19，硬约束）**：三个安装入口（拓展中心导入/更新、创建页安装）安装前经 `ExtensionImportExport.readManifestFromZip/Directory` 预扫描 manifest，用 `ExtensionRules.permissionsRequiringGrant`（纯函数有单测）计算需新授权权限（更新只列新增）；非空必须弹 `ExtensionPermissionDialog`（`ui/components/extension/`），**被拒则不安装/放弃更新**并提示；同意后以 `installFromZip/installFromDirectory(grantNewPermissions = true)` 安装即授权（运行时不再重复弹窗）。权限落库范围 = manifest 顶层 + 全部 Tool 权限（与运行时工具级校验对齐，避免只声明在 Tool 上的权限永远无行可授）。
- **崩溃恢复**：`EvoliaApp.onCreate` 异步 `recoverPendingOperations()`（幂等）——按 `ExtensionRules.decideRecovery`（纯函数有单测）对 PREPARING/FILES_STAGED 清 staging、FILES_SWITCHED/ROLLBACK_REQUIRED 恢复备份或删新目录、DATABASE_COMMITTED 仅清理；孤儿 staging/backup/安装目录一并清理。
- 目录布局：`files/extensions/{installed,staging,backups,workspaces}/`，DB 只存相对路径（`/` 前缀绝对路径语义与其他模块一致）。
- 卸载：先清 Room（工具配置/权限/版本/可选 mini_app_data/Registry）再删目录；**用户数据默认保留**，删除需 UI 显式勾选（详情页二次确认）。

### 17.4 Extension Registry 与三层权限（13.3，口径唯一）

- `ExtensionRegistry` 以 Room Flow（extensions + extension_tools + extension_permissions 三源 combine，InvalidationTracker 驱动）构建内存快照 `installedExtensions: StateFlow<List<InstalledExtension>>`（含 Manifest 解析/工具配置/授权集合），**Catalog 与可用性判定全部同步走快照，禁止在 systemPrompt 注入链路读盘/查库**（systemPrompt lambda 不可挂起）。manifest 缺失的扩展跳过并记日志（孤儿清理兜底）。
- **三层权限**：① 扩展整体启用（extensions.is_enabled）→ ② Tool 全局启用（extension_tools.enable）→ ③ 助手级扩展授权：Mini App 用 `Assistant.enabledMiniAppIds`、Tool 类扩展用 `Assistant.enabledToolExtensionIds`（2026-11-18 按用户需求升级，覆盖详设 13.2「独立 Tool Extension 对所有助手开放」的第一阶段规则，默认空集合兼容历史数据）。判定纯函数 `ExtensionRules.isExtensionToolAvailable`（有单测矩阵）。**目录注入（systemPrompt）与执行路径（checkToolCallable）必须使用同一判定**：2026-11-19 修复目录对独立 Tool Extension 跳过助手级检查导致的「未授权工具描述仍进上下文」泄漏——未启用的扩展工具任何信息不得进入上下文，无可用扩展时不注册固定工具与目录。
- **助手详情页入口（2026-11-19 拆卡）**：工具与搜索页为「Mini Apps」「Tools」两张独立卡片（`AssistantToolsSubPage`），MCP 服务器同款交互——各自 `ExtensionPickerButton(kind)`（ToggleSurface 图标 + 徽标=该类已启用数）点击弹出 `ExtensionPicker` 抽屉，开关写入 `enabledMiniAppIds` / `enabledToolExtensionIds`（组件在 `ui/components/ai/ExtensionPicker.kt`）。**Mini Apps 卡片只显示声明了工具的 Mini App**（`pickerCandidates` 过滤，卡片显隐与抽屉同口径）——该页用于配置助手可调用的工具，无工具的 Mini App 没有可授权项，不在此展示。
- 执行入口必须完整复查（`ExtensionRegistry.checkToolCallable`，返回 ToolCallCheck 六态，映射 `ExtensionAgentTools.mapCheckError` 有单测）；**Schema 获取阶段的检查不能替代执行阶段检查**。

### 17.5 Tool Runtime（ExtensionToolRuntime，QuickJS Spike 结论 + P0 风险）

- **禁止**复用/包装 eval_javascript 作为扩展 Runtime（详设 9.2）；Runtime 独立实现：显式 `QuickJSLoader.init()`（不依赖 Highlighter）、每执行独立 Context+独立 daemon 线程、执行完 destroy、`setMemoryLimit(64MB)` + `setMaxStackSize(512KB)`、console 捕获（≤20 行）、15s 超时、结构化结果 {success, data, error:{code,message}}。
- **QuickJS wrapper 3.2.3 Spike 结论（2026-11-16 实测）**：`setInterruptHandler` **不存在**——CPU 密集无限循环无法强制中断；`setMemoryLimit`/`setMaxStackSize` 可用；`evaluate` 同步返回无事件循环泵（Promise/setTimeout 不可用）；`globalObject.setProperty(name, JSCallFunction)` 同步回调可用。**P0 已知风险**：超时后放弃线程（daemon 随进程退出，泄漏受 maxToolRounds 会话上限约束）；进程内 QuickJS 不是进程级安全沙箱，第一阶段仅限用户显式安装的本地扩展，**不得**对不可信第三方默认开放高权限。
- **工具代码是同步代码**：SDK 经 `ExtensionSdkPrelude`（JSON 字符串进出 + {ok,value|code,message} 信封）包装为同步 `evolia.network/storage/ui` API；原生调用在 JSCallFunction 内 `runBlocking` 到 IO 线程完成（QuickJS 专线程阻塞，无死锁）。
- `ExtensionNetworkService`：受控网络（30s 超时 / 5MB 响应上限 / 仅 http/https / 结构化返回）；扩展代码不能获得任意网络能力，API Key 不进扩展包。
- `ExtensionAgentTools`：MCP/Radio 同款按需 Schema 双固定工具（get_extension_tool_schema + call_extension_tool），目录 Prompt（`buildExtensionCatalogPrompt` 纯函数有单测）由 get_schema 的 systemPrompt 注入；无可用工具返回空列表不注册。装配位置：`ChatService.handleMessageComplete` buildList 尾部（RadioAgentTools 之后），`buildExtensionAgentTools` 为顶层函数（与 buildMcpAgentTools 同约定）。
- **目录 Prompt 措辞中性化（2026-11-18 用户要求；2026-11-19 统一标题句式）**：三类目录标题统一为「## 可用工具 · <来源名>」（小机电台 / 外部服务 / 扩展），引导语使用同一句式、仅替换查询与执行工具名；分组标题用服务名/扩展名本身，**禁止出现「扩展工具」分类字样**——不向模型区分扩展工具与普通工具，模型只需知道有工具可调（按需 Schema 机制说明保留）。扩展工具 description 同步中性化。有单测断言固化。
- **临时会话双层拦截**：`TemporarySessionToolRules.blockedToolNames` 已登记 get_extension_tool_schema / call_extension_tool（扩展工具可写正式 Mini App 数据/外部业务数据，第一阶段全禁，详设 10.5；后续若放开只读工具须按权限区分，不得默认放行）。扩展工具不走助手 localTools 开关（无新 LocalToolOption）。
- eval_javascript Context 泄漏已顺手修复（try/finally destroy，不影响 Highlighter 持久 Context）。

### 17.6 Mini App Runtime 与 SDK（WebView 隔离，硬约束）

- **禁止**复用通用 `WebView` composable 充当 Mini App 容器：`MiniAppWebView`（`ui/components/miniapp/`）独立实现——`androidx.webkit`（新增依赖，1.14.0）`WebViewAssetLoader` 受控资源域 `https://appassets.androidplatform.net/app/{app_id}/...`（自定义 PathHandler：safe-path 校验 + canonical 包含性检查，越界/其他扩展/缺失一律 404）；settings.allowFileAccess=false、fileURLs 全禁。
- **Bridge**：`WebViewCompat.addWebMessageListener`（注意 webkit 1.14 的 onPostMessage 是 **5 参数**（WebView 打头），reply proxy 类型是顶层 `JavaScriptReplyProxy` 且 postMessage 直接收 String）+ `addDocumentStartJavaScript` 注入 SDK（注意：该 API 收**单个 String** 非 List）+ Origin Rules 限定受控域。子 frame 一律拒绝；外部 http/https 经系统浏览器打开，绝不继承 Bridge。
- `MiniAppBridgeSession`：一次页面进入 = 一个会话实例；**每次调用**完整校验（扩展存在/启用/实例 active/来源域/主 frame/权限/参数，详设 12.3，禁止只查一次）；页面 dispose/扩展停用即 `invalidate()`，旧实例调用立即失效。权限未授权 → 触发页面 ConfirmDialog（Manifest 申请与授权分离），拒绝返回 PERMISSION_DENIED。
- SDK（`assets/evolia/evolia-sdk.js`）：Promise 式 Evolia.storage.get/list/insert/update/delete（自动绑定 app_id，**前端不可指定 app_id**）、Evolia.ui.toast/dialog（dialog 走 pendingCalls 等用户点击回复）、Evolia.navigation.close、生命周期事件。回复走注入对象 onmessage（非 window message）。
- `MiniAppStorageService`：全操作绑定 app_id；集合必须在 Manifest 声明否则拒绝；写入统一过 JsonSchemaValidator；单条 ≤256KB；list 分页（≤100/页）。扩展**不得**直接执行 SQL。
- 数据迁移（详设 14.4）第一阶段为 no-op：仅记录 mini_app_versions.schema_version，数据原样保留（Manifest 未定义迁移脚本语法；后续扩展时补）。

### 17.7 本地创建 / 导入导出（Phase 5）

- **页面职责拆分（2026-11-18 用户需求）**：设置 → 小应用（`SettingMiniAppsPage`）**只做运行控制**——扩展卡片列表（名称/类型/版本/N/M tools/启用开关），点卡片弹出抽屉展示工具列表（开关/自定义描述/AI 简化/恢复默认，与 MCP 工具页签同款能力，无基础设置）；发现 → 拓展中心（`ExtensionWorkshopPage`，DiscoverPage 新入口；2026-11-19 由「应用中心」改名，页面紧凑顶栏、卡片无开关带卸载按钮+二次确认、详情抽屉权限改基本信息只读展示）**承载开发管理**——创建向导（模板弹窗）/ 导入 .evolia / 权限授权 / 打开小应用 / 更新 / 导出 / 卸载（数据保留选择）。原 `ExtensionDetailPage` 已删除。
- `ExtensionCreator`：草稿在 `extensions/workspaces/{draftId}/`（目录结构即包规范），与已安装严格分离（草稿不能被 Agent 调用）；创建向导自动生成 app_id + 模板脚手架；校验与安装共用 ManifestValidator / ExtensionInstaller.installFromDirectory（同一统一安装链）。**显示名称（extension name / tool name）允许中文**（仅展示与模型上下文文本，JSON 存储/调用键不受影响）；唯一标识 app_id / tool_id 已冻结为英文小写正则。
- `ExtensionImportExport`：导出 = 安装目录打 ZIP（包规范天然不含 mini_app_data/凭证）；导入经 SAF OpenDocument → 临时文件 → installer.installFromZip(LOCAL_IMPORT)，更新判定/回滚全走 Installer。
- 内置模板（`ExtensionTemplates`）：空白 Tool/Mini App + **天气查询 Tool 示例**（Open-Meteo，内联 Schema + 中英文城市映射）+ **番茄钟 Mini App 示例**（25/5 循环 + sessions 集合统计 + Toast，2026-11-18 按用户需求替换个人论坛）。**模板 JS 坑**：局部变量不得命名为与函数相同的 `today` 等（var 提升遮蔽函数 → 'not a function'，实测踩坑）；Mini App 页面输入框键盘遮挡用 `Modifier.imePadding()`（Compose 通用方案，无需自定义组件）。

### 17.8 ToolDescriptionService（MCP/扩展共用，详设 18.5）

- `data/ai/tools/ToolDescriptionService.kt`：描述简化的共用服务——纯函数（buildSingleSimplifyPrompt/buildBatchSimplifyPrompt/buildBatchInput/chunkBySize/parseBatchSimplifyResponse，有单测）+ generateWithBackgroundModel（全局后台模型，与 McpToolDescVM 原实现同语义）。
- `McpToolDescVM` 已改为委托该服务（internal 顶层函数签名保留，既有 McpOnDemandToolsTest 断言不变通过）；扩展详情页「AI 简化」按钮走 simplifySingle(kind="扩展") + 预览确认。

### 17.9 测试与验收

- 纯 JVM 单测（app/src/test 下 data/extensions/ + data/ai/tools/）：JsonSchemaValidator（13 例）/ ExtensionRules（版本/判定/合并/恢复/可用性矩阵/路径安全/Manifest 校验，22 例）/ ZipPackageReader（ZIP Slip/重复/炸弹/缺 manifest，10 例，其中重复条目用例**手写 raw ZIP**——Java ZipOutputStream 自身拒绝重复条目）/ ExtensionAgentTools（目录 Prompt/错误映射/参数解析）/ ToolDescriptionService（解析/分批/Prompt）。
- 验证命令：`./gradlew :app:compileDebugKotlin` + `./gradlew :app:testDebugUnitTest`（2026-11-18：414 全绿）。
- **模拟器实测已通过（2026-11-17/18，EvoliaTest AVD）**：① Mini App 全链路——创建→校验→安装→打开→权限三连（read/write/toast 逐项运行时授权弹窗）→发帖→Room 落库核验→重开持久化→停用禁用入口→卸载保留数据→重装恢复数据（场景 D）→深色渲染，全程零崩溃；② Tool Extension Agent 调用链——目录 Prompt 注入→`get_extension_tool_schema`（303 字节含参数定义）→`call_extension_tool`→QuickJS 执行→受控网络请求 Open-Meteo→实时天气数据返回（Agent 把结果渲染成表格），ToolLoopGuard 语义下失败自动换参数重试、跨轮 app_id 自纠错均验证。**实测修复的 4 个 bug（全部已固化进代码，禁止回退）**：
  1. 首次安装 `stagingDir.renameTo(targetDir)` 因 `extensions/installed|backups` 父目录未创建静默返回 false（rename(2) ENOENT）——Installer 切换前必须 mkdirs 父目录；
  2. `ExtensionToolRuntime.runToolScript` 从未注入 `request.arguments`——工具脚本顶层 `arguments` 恒为 undefined、恒收空参数；修复 = 执行前 `globalThis.arguments = Object.freeze(<json>)`（SDK_PRELUDE 是 IIFE 不受污染），扩展 JS 以 `arguments` 读取调用参数；
  3. `__evoliaRequest` 信封不匹配——原生直接返回 `{ok,status,body}` 而 JS `__call` 约定读 `{ok,value}`，JS 侧 `resp.status` 恒 undefined；修复 = 成功时包 `value`、失败透传错误信封。**所有 native handler 必须遵守 `{ok, value}` 信封约定**；
  4. `EXECUTION_TIMEOUT_SECONDS` 15s 在上游 API 波动时把网络请求误判为工具超时——提至 30s 与 OkHttp 超时对齐。
  另：天气示例模板已补内联 Schema（required city）与英文城市映射；Mini App Bridge 权限授权后页面不自动重试（JS promise 已 reject）为已知 UX 改进点（P3）。
- MIGRATION_48_49 已在真机场景实测（旧 v48 库升级后六表建立、数据无损）。

### 17.10 已知边界与后续（不回退清单）

- QuickJS interrupt handler 缺失（P0 风险见 17.5）；扩展工具暂不支持 Promise/异步代码。
- 备份第一阶段过渡：扩展六表随整库备份恢复，无独立「小应用」勾选分类（详设 17.3 过渡口径）；后续独立分类需备份侧按表过滤能力。
- Settings/SettingsStore 未新增扩展字段（权限/启用状态全部在 Room），助手授权经 Assistant.enabledMiniAppIds 走既有 settingsStore.update 原子事务。
- 后续 GitHub/市场/Agent 自动生成扩展必须复用 Manifest/Package/Installer/Registry/Runtime，禁止另起第二套机制。
