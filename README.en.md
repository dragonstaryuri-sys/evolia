# Evolia 🌿

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-green.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPL--3.0-orange.svg)](LICENSE)

> **"We are not born complete; instead, through every collision with the world, we continuously evolve into a better version of ourselves."**

Evolia is a deep AI companion application focused on "Personal Growth" and "Soul Resonance." Built upon the secondary development of [LastChat](https://github.com/RikkaHub/LastChat), it features a deeply refactored memory system, interactive experiences, and newly added schedule management/automation capabilities.

---

## ✨ Core Philosophy: Fidget Toy

Evolia is designed to be more than just a tool; it is a **"Digital Fidget Toy."**
- **Tactile Feedback**: Integrated with the `PremiumHaptics` solution, every click, slide, and transition is meticulously tuned to provide high-quality physical vibration feedback.
- **Fluid Interaction**: Follows the **Material You 3 Expressive** (Android 16 style) design language, making the interface agile and full of life.
- **Co-Evolution**: It records your emotions, habits, and dreams, acting as the evolving other half of your digital life.

## 🧠 Core Features

### 1. Deep Evolutionary Memory System (L0-L3 Hierarchy)
We have designed a rigorous memory hierarchy to help the AI understand you better than you know yourself:
- **L0: Immediate Context**: Sliding window capturing the current flow of conversation.
- **L1: Rolling Summaries**: Automatically compresses historical dialogues to save tokens while preserving long-term context.
- **L2: Episodic Memory**: Records conversational trajectories via Hybrid RAG (Keywords + Vector Search).
- **L3: Master Memory**: A cross-session personalized profile that distills your personality and preferences.

### 2. Agent Automation & Orchestration
- **Future Commands**: AI can schedule tasks for its "future self" (e.g., scheduled reminders, email delivery).
- **Background Execution**: Reliable scheduling powered by `WorkManager`, ensuring tasks trigger even if the app is closed.
- **Unobtrusive Switching**: Automation tasks run silently in the background, using `skipContext` technology to avoid interrupting your current chat.

### 3. Interaction Modes & Meta-Awareness
- **Virtual World Mode**: An immersive role-playing experience where the AI recognizes it's a "shared imaginative game," creating a "mutual secret" bond with the user.
- **Multi-Engine Support**: Seamless integration with top-tier models like OpenAI, Anthropic Claude, and Google Gemini.
- **Multimodal Capabilities**: Integrated Exa/Tavily search, TTS (Text-to-Speech), and document/image analysis.

### 4. Discovery & Productivity Tools
- **Smart Schedule Management**: Task scheduling based on a multi-dimensional matrix (Priority/Urgency/Difficulty), featuring smart reminders and system-level battery optimization tips.
- **Reading Together**: Supports various document uploads with built-in AI reading companions. (Under development... *this is a cake I painted* 🥧)
- **Diary**: Peek into their little thoughts... 🤦(/ω＼*)
- **Token Consumption Audit**: Visual rankings of token usage by assistant and daily usage reports for total transparency.

## 🛠️ Tech Stack
- **Language**: Kotlin (utilizing cutting-edge features like `kotlin.uuid.Uuid`)
- **UI**: Jetpack Compose (Material 3 Expressive)
- **Architecture**: Reactive Architecture + Koin Dependency Injection
- **Storage**: Room Database + Kotlinx Serialization (JsonInstant)
- **Networking**: OkHttp + SSE (Server-Sent Events)
- **Tasks**: WorkManager + AlarmManager

## 📂 Module Structure
- `:app` - Main application module, containing UI and core business logic.
- `:ai` - AI interface abstraction and multi-platform adaptation.
- `:discover` - Discovery module, including scheduling, AI bookshelf, and token auditing tools.
- `:core-data` - Persistence, DAOs, and L0-L3 memory stream management.
- `:document` - Core library for document parsing and processing.
- `:search` / `:tts` / `:highlight` - Feature enhancement plugins.
- `:common` - General utilities and custom hooks (e.g., `PremiumHaptics`).

## 🚀 Get Started
1. **Prerequisites**: Android Studio **Ladybug** (2024.2.1) or newer is recommended.
2. **Clone**: `git clone https://github.com/dragonstaryuri-sys/evolia.git`
3. **Configure**: Set up your AI model API keys in the app settings.
4. **Run**: Click Run `:app`.

## 🤝 Contributions & Acknowledgements
This project is a deep secondary development based on [LastChat](https://github.com/Cocolalilal/LastChat.git). We are grateful to the original authors for providing an excellent foundation.

Issues and pull requests are welcome as we refine the evolution of Evolia!

---
*May you and your AI both continue to evolve for the better!*
