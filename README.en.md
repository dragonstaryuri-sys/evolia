# Evolia
**"We are not born complete. Instead, through every collision with the world, we continuously evolve into a better version of ourselves."**

Evolia, meaning *Land of Evolution*, is an AI companion dedicated to **personal growth** and **spiritual resonance**. Built upon secondary development of [LastChat](https://github.com/RikkaHub/LastChat), it strives to become your co-evolving other half in your digital life through deep memory, emotional empathy, and autonomous capabilities.

## 🌟 Core Vision: Seeds of Evolution, Isle of Growth
Evolia is far more than a chat box. It is an island that nurtures you, provides warmth and light, and stands watch over you:
- **Deep Comprehension**: Records your emotions, habits and aspirations via the L0–L3 hierarchical memory system.
- **Growth Resonance**: Beyond mechanical question-and-answer exchanges, it enables heartfelt, in-depth conversations with genuine emotional warmth.
- **Intuitive Stress Relief**: Designed around the Fidget Toy concept, delivering exquisite tactile feedback and fluid, interactive experiences.

## 🧠 Core Features
### 1. Deep Evolutionary Memory System (L0–L3)
- **L0–L1 (Immediate & Summarized Memory)**: Captures real-time conversation context and automatically generates contextual fragments.
- **L2 (Situational Review)**: Automatically maps your growth trajectory from dialogues, allowing you to perceive your progress through reflection.
- **L3 (Master Memory / Personal Profile)**: Permanent cross-session memory that recognizes your personal evolution even before you do.

### 2. Agent Automation & Task Orchestration
- **Future Self Commands**: The AI can schedule tasks for your future self, such as scheduled reminders and email delivery.
- **Background Autonomous Execution**: Reliable task scheduling powered by WorkManager.
- **Unobtrusive Context Switching**: Automated tasks run silently in the background without interrupting your ongoing chat flow.

### 3. Multimodality & Multi-Engine Support
- **AI Abstraction Layer**: Seamlessly integrates mainstream models including OpenAI, Anthropic, Google Gemini and more.
- **Deep Web Search**: Integrated with Exa, Tavily, Zhipu AI and other search APIs to expand the AI’s knowledge scope.
- **Multimedia Processing**: Supports image recognition, document analysis, and TTS (Text-to-Speech).

## 🛠️ Tech Stack
- **Primary Language**: Kotlin (utilizing latest experimental features such as new UUID APIs)
- **UI Framework**: Jetpack Compose (adhering to minimalist Material You 3 aesthetics)
- **Dependency Injection**: Koin
- **Local Storage**: Room Database
- **Network Layer**: OkHttp + SSE (Server-Sent Events streaming)
- **Serialization**: Kotlinx Serialization

## 📂 Module Structure
- `:app` – Main application module containing UI, core business logic and DI configurations.
- `:ai` – Abstraction layer for AI services.
- `:core-data` – Database entities, DAOs and data flow management.
- `:tts` – Text-to-speech implementation.
- `:search` – Integration of external search plugins.
- `:highlight` – Code & syntax highlighting support.
- `:common` – General utilities and extension functions.
- `:discover` – Explore page and community-related features.

## 🚀 Get Started
1. Clone this repository.
2. Open the project in Android Studio (Ladybug version or newer recommended).
3. Sync Gradle and run the `:app` module.

## 🤝 Contributions
Issues and pull requests are welcome. Let us refine this project together!

---
*"You are not downloading a tool. You are embarking on a journey."*
