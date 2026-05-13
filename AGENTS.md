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
- **L3: Master Memory**: The ultimate "Master Archive" of user identity and preferences (User Profile).

### 6.1 Context Refresh (L1 - Auto-Summarization)
- **Mechanism**: Compresses older L0 messages within the active session into segments.
- **L0 Sliding Window**: Even if a message is summarized into L1, it remains visible as "Raw Message" in L0 if it falls within the `maxHistoryMessages` limit.
- **Segment Strategy (Split Storage & Hybrid Retrieval)**:
    - **Selective Storage**: `ChatSegmentEntity` only persists the AI-generated **background summary** (梗概) in its `content` field to keep the database footprint lean.
    - **Positional Mapping**: It records `startMessageIndex` and `endMessageIndex` to map the summary back to the specific original messages.
    - **High-Fidelity Embedding**: For vector search, the system concatenates `[Background Summary] + [Original Text]` to capture both distilled intent and raw nuances.
    - **Dynamic Reconstruction**: Tools like `retrieve_memory_details` fetch original messages using indices to return a combined payload: `[Background Summary] + [Original Text]`.
- **Trigger Logic**:
    - **Auto Check**: Triggered by `ChatService.checkAndAutoSummarize` after each message turn.
    - **Session Switch (Force)**: Forces an L1 archival for unsummarized messages before moving to L2.

### 6.2 Episodic Memory (L2 - Consolidation)
- **Relationship**: Maintains a **STRICT 1:1 relationship** with a Conversation.
- **Tactical Retrieval**: AI can call `retrieve_memory_details(episode_id, query)` for a "deep dive" into L1 Segments associated with that Episode.

### 6.3 Master Memory (L3 - Personal Archive)
- **Mechanism**: An evolving "User Profile" that transcends individual conversations.
- **Generation Logic**: Aggregates L2 summaries into a persistent archive injected into the Stable System Prompt.

### 6.4 Token Allocation & Context Priority
- **Hierarchy of Injection**: See 6.5 for detailed order.
- **Allocation Strategy**: Controlled by `assistant.contextPriority` (CHAT_HISTORY, MEMORIES, or BALANCED).
- **Cross-Session Continuity**: Early turns inject summaries of today's other chats as "Recent Episode Boosts" in the dynamic tail.

### 6.5 Final Prompt Structure (Prefix Caching Optimized Order)
To maximize Prefix Caching efficiency, the payload adopts a **"Stable-Front, Dynamic-Tail"** structure. Highly dynamic information is injected into the **last User message** in the sequence, ensuring that the cache for all preceding messages (System + Attachments + History) remains valid across multiple turns.

1. **System Message: Stable Preset** (Highest Cache-Hit Region)
    - **Core Personality**: Rules, Identity, and Instructions.
    - **Master Memory (L3)**: Persistent User Archive.
    - **Tool Specifications**: System instructions for activated tools and Memory Recording protocols.
    - **Static Injections**: `BEFORE_SYSTEM` and `AFTER_SYSTEM` prompts from Modes/Lorebooks.

2. **User Message: Context Attachments**
    - Media attachments (images, documents, etc.) provided by active Modes or Lorebooks.

3. **Multi-Role: Chat History (L0)**
    - Stable sliding window of recent original messages. This prefix is preserved across turns.

4. **User Message: Augmented Tail** (The Last Message)
    - The final User message is intercepted and injected with a `dynamicContext` header containing:
        - **L1: Context Highlights**: Recent history Segments (Summaries).
        - **L2: Memories (RAG)**: Contextually retrieved facts and **Recent Interaction Boosts** (continuity guidance from previous sessions).
        - **Lorebook Entries**: `AFTER_SYSTEM` lorebook entries activated by the current context.
        - **Reference Variables**: Global variables applied with placeholders.
        - **Instant Dynamic Facts**: Current timestamp, holidays, and the interval since the last assistant reply.
        - **The Original User Question**: Appended after the injected context header.

## 7. Agent Automation (Task Manager)

### 7.1 Overview
The `agent_task_manager` allows an Assistant to schedule instructions for its "future self".

### 7.2 Core Logic
- **Strict Creation Constraints**: Tasks are persisted and scheduled via `WorkManager`.
- **Smart Session Routing**: Automatic detection of the most relevant conversation for execution.
