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
-   **JSON Handling:**
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
- **L1: Context Refresh (Segments)**: Fine-grained L1 summaries of historical message blocks, providing recent context highlights.
- **L2: Episodic Memory**: Long-term conversation archive. Each Conversation maps to exactly one Episode.
- **L3: Master Memory (终极档案)**: The ultimate "Master Archive" of relationship dynamics and long-term commitments.

### 6.1 Context Refresh (L1 - Auto-Summarization)
- **Mechanism**: Compresses older L0 messages within the active session into segments.
- **L0 Sliding Window**: Even if a message is summarized into L1, it remains visible as "Raw Message" in L0 if it falls within the `maxHistoryMessages` limit.
- **Segment Strategy (Split Storage & Hybrid Retrieval)**:
    - **Selective Storage**: `ChatSegmentEntity` only persists the AI-generated **background summary** (梗梗) in its `content` field to keep the database footprint lean.
    - **Positional Mapping**: It records `startMessageIndex` and `endMessageIndex` to map the summary back to the specific original messages.
    - **High-Fidelity Embedding**: For vector search, the system concatenates `[Background Summary] + [Original Text]` to capture both distilled intent and raw nuances.
- **Temporal Grouping**: In the prompt, L1 segments are grouped by "Today", "Yesterday", "This Week", and "Older" to provide clear chronological context.

### 6.2 Episodic Memory (L2 - Consolidation)
- **Relationship**: Maintains a **STRICT 1:1 relationship** with a Conversation.
- **Cross-Window Continuity (All-Day L2 Injection)**: 
    - **Dynamic Tail Injection**: System automatically fetches **all L2 summaries produced today** (excluding the current session) and injects them into every turn.
    - **Non-RAG Resource**: This injection does not consume the RAG retrieval limit, ensuring consistent awareness of all daily interactions across different windows.
    - **High-Precision Time**: Injected L2 items are prefixed with "Today:" and include `HH:mm` timestamps to help AI sequence daily events.

### 6.3 Master Memory (L3 - Master Archive)
- **Mechanism**: A structured relationship record that transcends individual conversations, injected into the Stable System Prompt.
- **Sync Logic**: 
    - **Scheduled Daily Sync**: Executed at **3:00 AM** daily via `master_memory_daily_sync`.
    - **Incremental Update**: Only processes L2 episodes generated since the last sync.
- **Core Content Modules**:
    - **1. Agreement & TODOs (约定与待办)**: Pending promises, plans, and unresolved commitments.
    - **2. Emotional Status (情感现状)**: Relationship positioning (e.g., friends, lovers) and current interaction temperature.
- **Maintenance Protocols**:
    - **Stability Rule**: If new dialogue doesn't involve core state changes, the AI must output the existing archive **verbatim** to prevent hallucinations or loss of detail.
    - **Auto-Compression**: Triggers a "Lossless Compression" protocol when the archive content becomes too large, pruning completed items and refining descriptions into precise snapshots.

## 7. Agent Automation (Task Manager)

### 7.1 Overview
The `agent_task_manager` allows an Assistant to schedule instructions for its "future self".

### 7.2 Core Logic
- **Scheduling**: Reliable execution via `WorkManager`. Tasks are persisted in `AgentTaskEntity` (Room).
- **Smart Session Routing**: Automatic detection of the most relevant conversation for execution, prioritizing active or recently updated sessions.

### 7.3 Execution Modes & Visibility
- **Type: EMAIL / AGENT_TASK**: 
    - **Trigger**: System sends a "Virtual Instruction" message to the AI.
    - **Visibility**: The trigger instruction uses `skipContext = true` and is invisible to the user in the chat UI.
- **Type: NOTIFICATION**: Directly pushes a system notification using the specified title and content data.
- **Type: DIARY**: Automatically records an entry into the Agent's internal diary database.

## 8. Tool System & Local Capabilities

### 8.1 Local Execution Engines
- **Python Engine (`eval_python`)**: Powered by Chaquopy (Python 3.11). Includes `numpy`, `pandas`, `matplotlib`, and `Pillow`. Supports image/chart generation and file sandbox. AI can use Markdown syntax to render generated files directly in chat.
- **JavaScript Engine (`eval_javascript`)**: QuickJS-based lightweight execution for math and logic.

### 8.2 Productivity & Device Control
- **Schedule Manager (`schedule_manager`)**: Manages internal tasks with priority/urgency. Automatically syncs with the **Android System Calendar** for persistent reminders.
- **Device Control (`device_control`)**: 
    - **System Commands**: Perform global actions (LOCK_SCREEN, GO_HOME, BACK, SHOW_RECENTS, SHOW_NOTIFICATIONS) to actively guide user behavior.
    - **Alarm & Timer**: Manage system alarms and countdown timers via Intents.
- **Email Service**: Full SMTP/IMAP support via `qq_email_service` for autonomous email handling.

### 8.3 Relationship & Dynamic Profile
- **Profile Updater (`update_profile`)**: Allows AI to dynamically update User/Assistant Profile fields (diet, appearance, occupation, preferences, health, taboos, etc.) in real-time.
- **Milestone Manager (`milestone_manager`)**: Records core relationship events (Relationship, Perception, Commitment, Emotion, Identity) to shape the long-term bond and personality evolution.

### 8.4 User Observation & Monitoring
- **User Observer (`peek_user`)**: Sets up persistent high-precision monitors for phone status.
    - **Data Dimensions**: Foreground App, Today Usage, Session Duration (App/Device), Screen Context (OCR), and Recent Actions.
    - **Continuous Duration**: Supports triggering based on continuous usage of a specific app (`continuous_usage_minutes`) or continuous screen-on time (`total_continuous_minutes`).
    - **Implementation**: Powered by `EvoliaMonitorService` (Accessibility Service) with 60s active polling and BroadcastReceiver-based screen state tracking.
