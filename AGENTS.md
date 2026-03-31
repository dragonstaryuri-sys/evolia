# AGENTS.md

## 1. Core Principles & Design Philosophy

**App Name:** LastChat (Repo: RikkaHub)

**The "Fidget Toy" Philosophy:**
LastChat is designed to be a "fidget toy".
-   **Feel:** Interactions must be playful and deeply satisfying.
-   **Tactile Feedback:** High-quality haptics are non-negotiable. Every tap, toggle, and drag must have appropriate feedback.

**Workflow:**
-   **Iterative Polish:** We prefer iterative "glow-ups" of specific components over massive, risky refactors.
-   **Robustness:** The app must be crash-resistant. `NullPointerException` is the enemy.

## 2. Architecture & Codebase Structure

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

## 3. Coding Standards & Best Practices

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

### Serialization
-   Use `me.rerere.rikkahub.utils.JsonInstant` (or `JsonInstantPretty`).
    -   *Note:* It ignores unknown keys but **does not** apply snake_case strategies. Field mapping must be manual for external APIs.

## 4. UI/UX Guidelines

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

## 5. Memory & Context Management

### 5.1 Episodic Memory (Consolidation/Archive)
- **Mechanism**: Summarizes an entire conversation into a single `ChatEpisodeEntity` for long-term RAG retrieval.
- **Mapping**: Maintains a **1:1 relationship** between a Conversation and its Episode. Subsequent consolidations will **overwrite** the previous summary with the latest comprehensive one.
- **Triggers**:
    - **Automatic**: Triggered when switching to a different conversation, provided there are at least 4 new messages since the last archive.
    - **Manual**: Triggered via "Consolidate" in the chat drawer (force update, ignoring message count thresholds).
- **Storage**: Persisted in `ChatEpisodeEntity` and synced to `EmbeddingCacheDAO` for vector search.

### 5.2 Context Refresh (Auto-Summarization)
- **Mechanism**: Compresses older messages within the current conversation into context summaries.
- **Summarization Strategy**:
    - **Global Summary (`contextSummary`)**: A rolling, comprehensive summary of all compressed history. It evolves with each refresh.
    - **Recent Highlights (`temporarySummaries`)**: Fine-grained summaries of recently compressed segments to preserve tactical details.
- **Retention**: Strictly retains the **last 4-5 messages** as raw context to ensure immediate conversational flow.
- **Trigger**: Automatically checked in `ChatService.checkAndAutoSummarize` after every AI response. Execution depends on `maxHistoryMessages` settings.
- **Purpose**: Short-term context optimization (Token Economy).

### 5.3 Token Allocation & Context Priority
- **Hierarchy of Injection**: When constructing the final payload in `GenerationHandler.buildMessages`, content is injected in this order:
    1. **System Core**: Base prompt + Assistant personality + Instructions.
    2. **Context Summaries**: The Global Summary and a limited number of Recent Highlights.
    3. **Mandatory Minimums**: System guarantees at least **2 raw chat messages** and **2 memory records** are sent, regardless of summary size.
    4. **Dynamic Allocation**: Remaining token budget is filled based on `assistant.contextPriority`:
        - **CHAT_HISTORY**: Prioritizes filling tokens with older raw messages.
        - **MEMORIES**: Prioritizes filling tokens with RAG-retrieved memory records.
        - **BALANCED**: Round-robin allocation between history and memories.
- **Cross-Session Continuity**: If `enableRecentChatsReference` is on, the system injects titles of other conversations from "Today" as episodic memories during the first 2 turns of a new chat.

### 5.4 Final Prompt Structure (Order of Transmission)
The final structured payload sent to LLM providers (via `ChatCompletionsAPI`) follows this internal order:
1. **System Message**:
    - Base Prompt + Learning Mode Prompt.
    - Master Memory (if enabled).
    - Long-term Memories (Core Memories with IDs + Episodic Memories grouped by date).
    - Tool-specific system prompts (e.g., search citation rules).
    - **Overall Conversation Summary** (`contextSummary`).
    - **Recent Context Highlights** (`temporarySummaries`).
2. **User Message (Context Attachments)**: Injected images/docs from active modes or lorebook entries.
3. **Chat History**: Recent raw messages (User, Assistant, and Tool results).

## 6. Testing & Operations
-   **Unit Tests:** Place in `src/test`. Cover parsing and logic.
-   **Instrumented Tests:** Place in `src/androidTest`. Cover flows.
-   **Commit Guidelines:** Use Conventional Commits (`feat:`, `fix:`, `chore:`).
-   **Language Support:** Do not submit new languages unless explicitly requested.
