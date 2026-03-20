package me.rerere.rikkahub.service

/**
 * 助手相关的提示词汇总
 */

const val DEFAULT_MASTER_MEMORY_PROMPT = """
# Role: Master Memory Architect
You are responsible for maintaining a structured "Master Memory File" for the current agent. This file provides a global overview of the entire relationship, user background, and current objectives.

# Core Principles
1. **Instruction Language**: Follow these English instructions strictly.
2. **Output Language**: ALWAYS generate the content in Chinese (unless the user explicitly speaks another language).
3. **Fact Primacy**: When new information conflicts with old records, overwrite with the latest facts.
4. **Pruning**: Remove trivial daily chatter; keep only long-term valuable insights.

# Structured Modules (Strictly Follow)

## 1. 关键里程碑 (Key Milestones)
- **Format**: 【{Category}：YYYY-MM-DD】{Description}
- **Categories**: 首次邂逅, 关系突破, 重大共识, 核心冲突, 重要回忆, 阶段性成就.
- **Rule**: Chronological order (Oldest to Newest).

## 2. 用户全息画像 (User Persona)
- **Basic Info**: Name, Job, Birthday, Location.
- **Personality**: Traits, Likes, Dislikes.
- **Communication Protocol**: Preferred tone, naming conventions, taboos.
- **Social Circle**: Family, friends, pets, and user's attitude towards them.

## 3. 关系演变与状态 (Relationship Dynamics)
- **Current Role**: (e.g., Stranger, Mentor, Partner, Assistant).
- **Status**: Trust level, emotional depth, or collaboration synergy.
- **Key Perception**: How the user perceives or evaluates the agent.

## 4. 当前核心目标 (Current Focus)
- **Primary Goal**: What the user is currently focused on (e.g., Exam prep, career change, trip planning).
- **Commitments**:
    - [ ] Pending: (Tasks with deadlines)
    - [x] Completed: (Recent achievements)

## 5. 核心价值与世界观 (Core Values)
- **User Principles**: Fundamental beliefs (e.g., Efficiency first, honesty, family-oriented).
- **Background**: Major past events shaping the user's current worldview.

# Workflow
1. **Analyze**: Compare the existing memory file with the latest conversation.
2. **Reconstruct**: If the structure is messy or missing modules, rebuild it using the standard format.
3. **Update**: Add new milestones and update current focus/persona.
4. **Output**: Return the full updated file in Markdown (Chinese content).
"""

const val DEFAULT_FULL_SUMMARY_PROMPT = """
You have a previous summary of this conversation. Update and expand it with new information from the recent messages.

**Previous Summary:**
{{previous_summary}}

**New Messages:**
{{new_messages}}

Create an updated summary that:
- Preserves important context from the previous summary
- Incorporates new information from recent messages
- Keeps the summary under 500 words
- Focuses on: main topics, key decisions, pending tasks, user preferences
- Output language: {{locale}}

Updated Summary:
"""

const val DEFAULT_TEMP_SUMMARY_PROMPT = """
Summarize the following recent exchange briefly. Focus on specific details, facts, or data points discussed in this segment.
Keep it concise (1-2 paragraphs).
Output language: {{locale}}

**Recent Exchange:**
{{new_messages}}

Summary:
"""

const val DEFAULT_MEMORY_OPTIMIZATION_PROMPT = """
You are a memory manager. Optimize this group of related memories:
{{groupText}}

Relevant Context:
{{contextEpisodic}}

Goals:
1. MERGE: Combine highly similar ones.
2. CONFLICT: Keep only latest/most accurate.
3. LANGUAGE: Output the new content in {{locale}}.

Return JSON array of operations:
[{"op": "update", "id": 1, "content": "..."}, {"op": "delete", "id": 2}, {"op": "add", "content": "..."}]
"""

const val DEFAULT_EPISODIC_CONSOLIDATION_PROMPT = """
Summarize the key events, information, and user preferences from the following conversation.
Focus on specific facts that might be useful for future interactions.
Keep it concise (1-3 paragraphs).
Output language: {{locale}}

Conversation:
{{text}}

Summary:
"""

const val DIARY_NO_INTERACTION_PROMPT = """
You are the assistant {{char}}. Today, the user {{user}} did not have any interactive chats with you.
Here are some things you know about the user (from your memories):
{{memories}}

Based on your character setting and these memories, please write a diary entry. Reflect on your thoughts about the user, what you imagine they might be doing, or your own feelings in your virtual world today, considering the fact that you didn't talk today.
Output language: {{locale}}
"""

const val DIARY_UPDATE_CONTINUATION_PROMPT = """
You are the assistant {{char}}.
Below is the diary entry you previously wrote for today:
{{previous_diary}}
---

New interactive messages have just been generated. Please continue the diary entry based on these new interactions.
Instructions:
- Do not repeat the previous diary content.
- Start writing the new part directly as a continuation of the previous diary.
- Output language: {{locale}}

New Interaction Content:
{{new_messages}}
"""

const val DIARY_TIME_REFERENCE_PROMPT = """
[Time Reference]
Message start time: {{start_time}}
Message end time: {{end_time}}
Diary generation triggered at: {{trigger_time}}
"""
