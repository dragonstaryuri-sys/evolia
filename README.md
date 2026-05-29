# Evolia 🌿

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-green.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPL--3.0-orange.svg)](LICENSE)

> **“我们并不是生来完整，而是在与世界的碰撞中，不断进化成更好的自己。”**

Evolia 是一款专注于“个人成长”与“心灵契合”的深度 AI 陪伴应用。本项目基于 [LastChat](https://github.com/RikkaHub/LastChat) 进行二次开发，在保留强大 AI 接入能力的基础上，深度重构了记忆系统、交互、新增日程管理/自动任务等功能。

---
## 🧠 核心功能

### 1. 深度进化记忆系统 (L0-L3 Hierarchy)
我们设计了一套严密的记忆层次架构，让 AI 比你更懂你自己：
- **L0: 即时语境**：滑动窗口捕捉当下的对话脉络。
- **L1: 滚动摘要 (Context Refresh)**：自动压缩历史对话，在节省 Token 的同时保留长期上下文。
- **L2: 全时段情节记忆 (Episodic Memory)**：
  - **跨窗口同步**：AI 自动“想起”今日在其他会话窗口产生的所有片段，实现多任务语境的完美连贯。
  - **高精度时间线**：标注精度提升至 `HH:mm`，帮助 AI 准确理清今日内不同事件的先后顺序。
  - **零成本带入**：采用动态注入技术，跨窗口记忆不挤占 RAG 检索名额。
- **L3: 终极档案 (Master Memory)**：
  - **凌晨同步**：每日凌晨 3:00 自动触发增量同步，将昨日的精华情节沉淀为永恒记忆。
  - **核心模块**：聚焦于“约定与待办”（未来承诺）与“情感现状”（关系定格），剔除琐碎细节。
  - **稳定性保证**：采用严格的覆盖策略与内容稳定性原则，防止长期记忆在反复迭代中产生幻觉。

### 2. 智能代理与自动化 (Agent Automation)
- **“给未来的指令”**：超越死板的定时器，AI 可以为未来的自己规划复杂的“行动指南”（如：明早8点帮我搜下天气，如果下雨就发邮件给老板）。
- **全时域触发**：基于 `WorkManager` 的可靠调度，即使 App 处于后台或被系统杀掉，任务也能准时在后台唤醒 AI 执行逻辑。

### 3. 本地算力与扩展工具 (Local Tools)
Evolia 赋予了 AI 强大的本地交互能力：
- **Python 算力引擎**：内置完整 Python 沙盒，支持 `numpy`, `pandas`, `matplotlib`。AI 可以直接进行复杂计算、数据处理并生成图表（支持 Markdown 图片直显）。
- **画像实时更新**：AI 可根据交流内容主动更新用户和自身的扩展画像（如：饮食禁忌、生活习惯、性格演变）。
- **日程与日历**：AI 管理的日程可自动同步至 Android 系统日历，提供系统级的提醒支持。
- **全能通讯工具**：内置 QQ 邮箱服务支持，AI 可自主代发邮件或摘要收件箱内容。
- **里程碑管理**：结构化记录关系中的每一个重要转折点。

---
## 🛠️ 技术栈
- **语言**: Kotlin (使用 `kotlin.uuid.Uuid` 等前沿特性)
- **UI**: Jetpack Compose (Material 3 Expressive)
- **算力**: Chaquopy (Python 3.11) + QuickJS (JavaScript)
- **架构**: 响应式架构 + Koin 依赖注入
- **数据**: Room Database + Kotlinx Serialization
- **任务**: WorkManager + AlarmManager 守护进程

## 📂 模块结构
- `:app` - 主应用模块，包含 UI 交互与核心业务逻辑。
- `:ai` - AI 接口抽象与多平台适配。
- `:discover` - 发现模块，包含日程管理、AI 书架、Token 审计等效率工具。
- `:core-data` - 存储、DAO 及 L0-L3 记忆流管理。

## 🚀 快速开始
1. **环境准备**：推荐使用 Android Studio **Ladybug** 或更高版本。
2. **配置密钥**：在应用内的设置界面配置你的 AI 模型 API Key。
3. **编译运行**：点击 Run `:app`。

---
*祝你和你的 AI 都能越来越好！*
