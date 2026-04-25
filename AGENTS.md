# AGENTS.md

## 1. Core Principles & Design Philosophy

**App Name:** Evolia (Paradise of Evolution)

**The "Fidget Toy" Philosophy:**
Evolia is designed to be a "fidget toy".
-   **Feel:** Interactions must be playful and deeply satisfying.
-   **Tactile Feedback:** High-quality haptics are non-negotiable. Every tap, toggle, and drag must have appropriate feedback.

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
-   **AI Context:** Prioritize token economy and vector memory efficiency. Use caching.

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
- **L0: Raw Messages**: Immediate short-term context.
- **L1: Context Refresh (Segments)**: In-session compression to save tokens.
- **L2: Episodic Memory**: Long-term conversation archive with keyword indexing.
- **L3: Master Memory**: The ultimate "Master Archive" of user identity and preferences.

### 6.1 Context Refresh (L1 - Auto-Summarization)
- **Mechanism**: Compresses older L0 messages within the active session.
- **Strategy**:
    - **Global Summary (`contextSummary`)**: A rolling summary of all compressed history.
    - **Recent Highlights (`temporarySummaries`)**: Referred to as **Segments**. These are fine-grained summaries of recently compressed message blocks to preserve "Tactical Details".
- **Trigger**: `ChatService.checkAndAutoSummarize` based on `maxHistoryMessages`.

### 6.2 Episodic Memory (L2 - Consolidation)
- **Relationship**: Maintains a **STRICT 1:1 relationship** with a Conversation. Room `REPLACE` strategy is used with `existingEpisode.id` to prevent duplicate entries per conversation ID.
- **Rolling Update Logic**: 
    - **Trigger Check**: Only proceed if new messages since the last consolidation >= 4.
    - **Token Optimization**: If an episode already exists, use `DEFAULT_FULL_SUMMARY_PROMPT` to perform a "Rolling Summary" (Old Summary + New Messages) instead of re-summarizing the entire chat history.
    - **Manual vs Auto**: Both manual UI consolidation and background workers follow the same rolling logic to ensure consistency and economy.
- **Retrieval**: AI extracts keywords from L2 for hybrid RAG search (Keywords have higher matching priority than Embeddings).
- **Triggers**: `MemoryConsolidationWorker` (Automatic/Manual).

### 6.3 Master Memory (L3 - Personal Archive)
- **Mechanism**: An evolving "User Profile" that transcends individual conversations.
- **Generation Logic**:
    - **Automatic (Incremental)**: Aggregates L2 summaries of only those conversations updated since `lastMasterMemoryUpdate`. 
    - **Manual (Full Sync)**: Aggregates L2 summaries from ALL conversations. Useful for re-calibrating the archive after database manual edits or deletions.
- **Threshold**: Only considers conversations with >= 2 messages.
- **Compression**: Automatically triggers a compression task if the archive exceeds 2500 characters to maintain context window efficiency.

### 6.4 Token Allocation & Context Priority
- **Hierarchy of Injection**: In `GenerationHandler.buildMessages`:
    1. **System Core**: Base prompt + Assistant personality.
    2. **Context Summaries**: The Global Summary (L1) and Recent Highlights (Segments).
    3. **Mandatory Minimums**: Guaranteed 2 raw messages + 2 memory records.
    4. **Dynamic Allocation**: Based on `assistant.contextPriority` (CHAT_HISTORY, MEMORIES, or BALANCED).
- **Cross-Session Continuity**: `enableRecentChatsReference` injects titles of today's other chats as episodic memories during early turns.

### 6.5 Final Prompt Structure (Detailed Order)
The payload sent to LLMs follows this strict code-defined order:
1. **System Message (Combined)**:
    - **Core Personality**: Assistant's `systemPrompt` (Highest Priority).
    - **Learning Mode**: Behavioral guidance.
    - **BEFORE_SYSTEM**: Pre-patches from Modes and Lorebook entries.
    - **L3: Master Memory**: Permanent archive of user identity.
    - **AFTER_SYSTEM**: Post-patches from Modes and Lorebook entries.
    - **Tool Instructions**: System prompts for active tools.
    - **L1: Global Context Summary**: `contextSummary`.
    - **L1: Recent Context Highlights**: `temporarySummaries` (Segments).
    - **L2 & Core Memories**: RAG-retrieved records (Core + Episodic with Keywords).
    - **Memory Management Guidelines**: Proactive recording and Person Specification rules.
2. **User Message (Context Attachments)**: 
    - Images, Docs, and media from active Modes or Lorebooks.
3. **Chat History**: 
    - Recent **L0 Raw Messages** (User, Assistant, and Tool results).

## 7. Agent Automation (Task Manager)

### 7.1 Overview
The `agent_task_manager` allows an Assistant to schedule instructions for its "future self". It's an asynchronous task system driven by AI decision-making rather than static scripts.

### 7.2 Core Logic
- **Strict Creation Constraints**:
    - For `EMAIL` tasks, the AI must provide `target` (recipient), `subject`, and `instruction` (body/command).
    - Tasks are persisted in the `agent_tasks` table and scheduled via `WorkManager` with a `CONNECTED` network constraint.
- **Smart Session Routing**:
    - When a task triggers, the system automatically detects the most active/relevant conversation for that Assistant.
    - **Conflict Handling**: If the user is currently chatting in that session, the system waits (3s retry). If still busy, it forcibly cancels the current job and takes over to ensure automation reliability.
- **System Message Protocol**:
    - Trigger instructions are sent with a `【System Automation Instruction】` prefix, allowing the AI to execute logic immediately without requiring further user input.
- **Ephemeral Context (skipContext)**:
    - **Visibility**: These automated turns are hidden from the UI by filtering messages where `skipContext == true` in `ChatList`.
    - **Memory Isolation**: Both the system instruction and AI execution response are marked with `skipContext = true`. They are excluded from future context windows in `GenerationHandler`, ensuring the AI "forgets" the automation task during subsequent normal conversations to prevent hallucinations.

### 7.3 Reliability & Monitoring
- **Heartbeat Guard**: A 30-minute `AlarmManager` heartbeat, combined with `Application.onCreate` checks, ensures overdue tasks are rescheduled after app restarts or network recovery.

## 8. Testing & Operations
-   **Unit Tests:** Place in `src/test`. Cover parsing and logic.
-   **Instrumented Tests:** Place in `src/androidTest`. Cover flows.
-   **Commit Guidelines:** Use Conventional Commits (`feat:`, `fix:`, `chore:`).
-   **Language Support:** Do not submit new languages unless explicitly requested.
