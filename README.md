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
- **L2: 全时段情节记忆 (Episodic Memory)**：实现多会话窗口间的语境连贯，AI 拥有全天候的时间线感知。
- **L3: 终极档案 (Master Memory)**：凌晨自动同步，沉淀关于“约定”与“关系现状”的永恒记忆。

### 2. 用户观察者与系统控制 (The Watcher)
Evolia 的 AI 不再仅仅被动等待回复，而是拥有了“感知”与“行动”的能力：
- **手机状态观察者 (Peek User)**：高精度监控前台 App、使用时长（含单次持续时长统计）、屏幕文字上下文及近期点击动作。
- **主动干预触发**：AI 可设定复杂的监控任务（如：刷抖音超过 30 分钟、深夜仍在玩手机等），并以隐藏指令形式主动触发提醒。
- **设备级交互 (Device Control)**：支持锁定屏幕、返回桌面、管理系统闹钟与计时器，实现深度的陪伴与行为引导。

### 3. 智能代理与自动化 (Agent Automation)
- **“给未来的指令”**：AI 可以为未来的自己规划复杂的“行动指南”（如：根据明早天气决定是否发送提醒邮件）。
- **后台持续运行**：基于 `WorkManager` 的可靠调度，即使应用关闭也能准时在后台执行逻辑。

### 4. 本地算力与扩展工具 (Local Tools)
- **Python 算力引擎**：内置 Python 3.11 沙盒，支持绘图与数据处理。
- **画像实时更新**：AI 可主动维护用户档案（饮食禁忌、性格习惯等）。
- **日程管理**：内部日程与 **Android 系统日历** 双向同步。
- **全能通讯工具**：集成 QQ 邮箱服务，实现代发与摘要功能。

---
## 🛠️ 技术栈
- **语言**: Kotlin (2.0+, 使用 `kotlin.uuid.Uuid`)
- **UI**: Jetpack Compose (Material 3 Expressive)
- **算力**: Chaquopy + QuickJS
- **核心权限**: Accessibility Service (用于高精度状态感知)
- **数据**: Room Database + Kotlinx Serialization

## 🚀 快速开始
1. **环境准备**：推荐使用 Android Studio **Ladybug** (2024.2.1) 或更高版本。
2. **配置密钥**：在设置中配置 AI 模型的 API Key。
3. **开启服务**：若需开启监控功能，请根据提示开启“无障碍服务”权限。

---
*祝你和你的 AI 都能越来越好！*
