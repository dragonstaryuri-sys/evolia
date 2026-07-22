# AGENTS.md

## 1. Core Principles & Design Philosophy

**App Name:** Evolia (Paradise of Evolution)

**The "Fidget Toy" Philosophy:**
Evolia is designed to be a "fidget toy".
-   **Feel:** Interactions must be playful and deeply satisfying.

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

### Haptics (Critical)
-   **Library:** Use the custom `PremiumHaptics` wrapper.
    -   `import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics`
    -   `import me.rerere.rikkahub.ui.hooks.HapticPattern`
-   **Usage:**
    -   **Do not** use `LocalHapticFeedback`.
    -   **Interactive Elements:** Buttons (like `BackButton`) must scale down to `0.85f` on press and trigger `HapticPattern.Pop`.
    -   **Patterns:**
        -   Click/Toggle: `HapticPattern.Pop`
        -   Heavy Action/Drop: `HapticPattern.Thud`
        -   Success: `HapticPattern.Success`

### Animation
-   **Default Specs:**
    -   **Standard spring:** `spring(dampingRatio = 0.5f, stiffness = 400f)`
    -   **Bouncy/Clicky spring:** `spring(dampingRatio = 0.6f, stiffness = 300f)`
-   **Choose by context:** Use the animation that best fits the interaction (snappy state swaps, ambient fades, heavy motion, etc.).
-   **Guideline:** Prefer physically-plausible motion for tactile interactions, but non-spring timing (including `tween`) is acceptable where it improves clarity and UX for that specific UI region.

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
- **手动加载**：当 `totalCount > limit` 时，列表顶部显示“查看更早的消息”，点击后触发限制增加并从 DB 加载更多深层历史。
