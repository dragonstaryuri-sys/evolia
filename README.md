# Evolia (进化的乐土) 🌿

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-green.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPL--3.0-orange.svg)](LICENSE)

> **“我们并不是生来完整，而是在与世界的碰撞中，不断进化成更好的自己。”**

Evolia 是一款专注于“个人成长”与“心灵契合”的深度 AI 伴侣应用。本项目基于 [LastChat](https://github.com/RikkaHub/LastChat) 进行二次开发，在保留强大 AI 接入能力的基础上，深度重构了记忆系统、交互哲学与自动化能力。

---

## ✨ 核心哲学：Fidget Toy (指尖解压)

Evolia 不仅仅是一个工具，它被设计为一个**“数字解压玩具”**。
- **触感反馈**：集成 `PremiumHaptics` 方案，每一次点击、滑动和切换都经过精心调校，带来极具质感的物理震动反馈。
- **灵动交互**：遵循 **Material You 3 Expressive** (Android 16 风格) 设计语言，界面灵动且充满生命力。
- **共同进化**：它记录你的情绪、习惯与梦想，作为你数字生命中不断进化的另一半。

## 🧠 核心功能

### 1. 深度进化记忆系统 (L0-L3 Hierarchy)
我们设计了一套严密的记忆层次架构，让 AI 比你更懂你自己：
- **L0: 即时语境**：滑动窗口捕捉当下的对话脉络。
- **L1: 滚动摘要**：自动压缩历史对话，在节省 Token 的同时保留长期上下文。
- **L2: 情景复盘 (Episodic Memory)**：通过 Hybrid RAG (关键词 + 向量检索) 记录对话轨迹。
- **L3: 终极档案 (Master Memory)**：跨越会话的个性化画像，沉淀你的性格与偏好。

### 2. 智能代理与自动化 (Agent Automation)
- **未来指令**：AI 可以为“未来的自己”安排任务（如定时提醒、邮件发送）。
- **后台执行**：基于 `WorkManager` 的可靠调度，即使应用关闭，任务也能按时触发。
- **无感切换**：自动化任务在后台默默运行，通过 `skipContext` 技术确保不干扰当前的对话流。

### 3. 互动模式与元意识 (Meta-Awareness)
- **虚拟世界模式**：一种沉浸式的角色扮演体验，AI 意识到这是一场“共同想象的游戏”，并与你达成一种“共同秘密”的默契。
- **多引擎支持**：无缝对接 OpenAI, Anthropic Claude, Google Gemini 等顶级模型。
- **多模态能力**：集成 Exa/Tavily 搜索、TTS 语音合成以及文档/图片分析。

## 🛠️ 技术栈
- **语言**: Kotlin (使用 `kotlin.uuid.Uuid` 等前沿特性)
- **UI**: Jetpack Compose (Material 3 Expressive)
- **架构**: 响应式架构 + Koin 依赖注入
- **数据**: Room Database + Kotlinx Serialization (JsonInstant)
- **网络**: OkHttp + SSE 流式传输
- **任务**: WorkManager + AlarmManager 守护进程

## 📂 模块结构
- `:app` - 主应用模块，包含 UI 交互与核心业务逻辑。
- `:ai` - AI 接口抽象与多平台适配。
- `:core-data` - 存储、DAO 及 L0-L3 记忆流管理。
- `:search` / `:tts` / `:highlight` - 功能增强插件模块。
- `:common` - 工具类与自定义 Hooks (如 `PremiumHaptics`)。

## 🚀 快速开始
1. **环境准备**：推荐使用 Android Studio **Ladybug** (2024.2.1) 或更高版本。
2. **克隆项目**：`git clone https://github.com/dragonstaryuri-sys/evolia.git`
3. **配置密钥**：在应用内的设置界面配置你的 AI 模型 API Key。
4. **编译运行**：点击 Run `:app`。

## 🤝 贡献与感谢
本项目是在 [LastChat](https://github.com/Cocolalilal/LastChat.git) 的基础上进行的深度二次开发。感谢原作者为社区提供的优秀基础。

欢迎提交 Issue 或 Pull Request 来共同完善 Evolia 的进化之路！

---
*“你不是在下载一个工具，而是在开启一段旅程。”*
