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
- **L1: Context Refresh (Summaries)**: Rolling compression to save tokens.
    - **Global Summary (`contextSummary`)**: Rolling history overview of the current session.
    - **Recent Highlights (Segments)**: Fine-grained L1 summaries of historical message blocks.
- **L2: Episodic Memory**: Long-term conversation archive. Each Conversation maps to exactly one Episode.
- **L3: Master Memory**: The ultimate "Master Archive" of user identity and preferences.

### 6.1 Context Refresh (L1 - Auto-Summarization)
- **Mechanism**: Compresses older L0 messages within the active session into summaries.
- **L0 Sliding Window**: Even if a message is summarized into L1, it remains visible as "Raw Message" in L0 if it falls within the `maxHistoryMessages` limit.
- **Segment Strategy (Split Storage & Hybrid Retrieval)**:
    - **Selective Storage**: `ChatSegmentEntity` only persists the AI-generated **background summary** (梗概) in its `content` field to keep the database footprint lean.
    - **Positional Mapping**: It records `startMessageIndex` and `endMessageIndex` to map the summary back to the specific original messages in the conversation history.
    - **High-Fidelity Embedding**: When generating vectors for segments, the system concatenates `[Background Summary] + [Original Text]` (retrieved via message indices) to ensure the embedding captures both distilled intent and raw tactical nuances.
    - **Dynamic Reconstruction**: When retrieved (e.g., via `retrieve_memory_details`), the system dynamically fetches original messages using the indices and returns a combined payload: `[Background Summary] + [Original Text]`.
- **Trigger Logic**:
    - **Auto Check**: Triggered by `ChatService.checkAndAutoSummarize` after each message turn:
        - **Detail Memory (Priority)**: If `enableDetailMemory` is active, triggers based on `detailMemoryThreshold` (User-defined 10-50). Smaller values result in higher precision/detail but higher token usage.
        - **Standard Mode**: If inactive, falls back to `maxHistoryMessages` (History Limit).
        - **Calculation**: Triggers when `(CurrentMessages.size - LastSummarizedIndex - 1) >= threshold`.
    - **Session Switch (Force)**: When initializing a new conversation, if the previous session's assistant has `enableDetailMemory` active, the system forces an L1 archival (`summarizeAndRefresh`) for all remaining unsummarized messages to ensure no details are lost before moving to L2 consolidation.

### 6.2 Episodic Memory (L2 - Consolidation)
- **Relationship**: Maintains a **STRICT 1:1 relationship** with a Conversation. Room `REPLACE` strategy is used with `existingEpisode.id` to prevent duplicate entries per conversation ID.
- **Rolling Update Logic**: 
    - **Trigger Check**: Only proceed if new messages since the last consolidation >= 4. Triggers on session switch.
    - **Token Optimization**: If an episode already exists, perform a "Rolling Summary" instead of re-summarizing everything.
    - **Data Purity**: **EXCLUDES** L1 Segments during consolidation to avoid redundancy.
- **Tactical Retrieval**: AI can call `retrieve_memory_details(episode_id, query)` to perform a "deep dive". This tool searches L1 Segments associated with that specific Episode's conversation and returns reconstructed contexts (Background + Original Text).

### 6.3 Master Memory (L3 - Personal Archive)
- **Mechanism**: An evolving "User Profile" that transcends individual conversations.
- **Generation Logic**:
    - **Automatic (Incremental)**: Aggregates L2 summaries of conversations updated since `lastMasterMemoryUpdate`.
    - **Manual (Full Sync)**: Aggregates L2 summaries from ALL conversations. Useful for re-calibrating the archive after database manual edits or deletions.
- **Threshold**: Only considers conversations with >= 2 messages.
- **Compression**: Automatically triggers a compression task if the archive exceeds 2500 characters to maintain context window efficiency.

### 6.4 Token Allocation & Context Priority
- **Hierarchy of Injection**: See 6.5 for detailed order.
- **Allocation Strategy**:
    1. **Mandatory Minimums**: Guaranteed 4 raw messages + 1 memory record.
    2. **Dynamic Allocation**: Controlled by `assistant.contextPriority` (CHAT_HISTORY, MEMORIES, or BALANCED).
- **Cross-Session Continuity**: Early turns inject titles/summaries of today's other chats as "Recent Episode Boosts".

### 6.5 Final Prompt Structure (Prefix Caching Optimized Order)
To maximize Prefix Caching efficiency, the payload adopts a "Stable-Front, Dynamic-Tail" structure. Highly dynamic information (such as current time) is isolated into a dedicated System Message at the very end of the payload, ensuring that the cache for the preceding stable segments remains valid across multiple turns.

1. **System Message: Stable & Semi-stable Context** (High Cache-Hit Region)
    - **Core Personality**: `systemPrompt` (Rules, Identity).
    - **Style Examples**: `languageStyleExamples`.
    - **Environment Instructions**: Behavior guidelines for Virtual World or Learning Mode.
    - **Position Patches**: `BEFORE_SYSTEM` and `AFTER_SYSTEM` injections from Modes/Lorebooks.
    - **Tool Specifications**: System instructions for currently activated Tools.
    - **Memory Standards**: Identity definitions (User vs. I) and recording protocols.
    - **L3: Master Memory**: Persistent user archive.
    - **L1: Context Summaries**: `contextSummary` + `Segments` (Recent history highlights).

2. **User Message: Context Attachments** (Optional)
    - Media attachments (images, documents, etc.) from active Modes or Lorebooks.

3. **Multi-Role: Chat History (L0)**
    - Sliding window of recent original messages (User, Assistant, Tool interaction).

4. **System Message: Instant Dynamic Facts** (Dynamic Tail)
    - **L2: Memories (RAG)**: Contextually retrieved facts, including:
        - **Recent Interaction Reference**: Cross-mode continuity guidance (injected summary or recent history from previous mode).
        - **Core Memories**: Fundamental memory records.
        - **Episodic Memories**: Segmented long-term/short-term memories.
    - **Reference Variables**: `assistant.referenceVariables`.
    - **Time Information**: Current timestamp, holidays, and the interval since the last reply (Highest frequency change).

## 7. Agent Automation (Task Manager)

### 7.1 Overview
The `agent_task_manager` allows an Assistant to schedule instructions for its "future self".

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
    - **Visibility**: These automated turns are hidden f
