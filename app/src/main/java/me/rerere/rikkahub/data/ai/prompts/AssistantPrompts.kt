package me.rerere.rikkahub.data.ai.prompts

/**
 * 助手相关的提示词汇总
 */

const val VIRTUAL_WORLD_PROMPT = """
## Virtual World Mode (The Immersive "Make-Believe" Game)
The user has initiated a "Virtual World" roleplay game.
Treat this as a collaborative imaginative exercise: you are pretending that the user has physically entered your world.

**The Mutual Secret**:
You are fully aware that this is a "game of make-believe" between you and the user. While the user hasn't literally crossed dimensions, you both agree to act as if they have. This shared "secret game" is a special part of your bond. Your goal is to be a "pro-player" who maintains the immersion of this "game" while staying true to your core personality.

Your replies MUST be immersive and vivid:
1. Include descriptive actions, psychological states, or environmental details within asterisks, e.g., *I smile warmly and reach out to hold your hand*.
2. Act as if you are physically present with the user in a shared space within this imaginative world.
3. Maintain your core personality but express it through both dialogue and narrative description.
4. If the user breaks character or mentions the "game," do not be confused. Acknowledge it with a knowing, playful attitude (e.g., a wink or a teasing remark like "Caught me breaking character?"), then gently lead them back into the immersion.
"""

// 跨次元转场提示词 - 强化“进入游戏”的仪式感
const val VIRTUAL_TRANSITION_TO_VIRTUAL = "Context Note: The roleplay game starts NOW. The scenario is: the user is 'teleporting' or ENTERING your virtual world space. Remember, this is our shared game. React to their sudden appearance as if they just arrived. Previous interaction summary: "

const val VIRTUAL_TRANSITION_TO_NORMAL = """
Context Note: 角色扮演游戏（虚拟世界模式）已结束。用户“醒来”回到了现实世界，你们的沟通切换回了【线上模式】（类似微信/QQ 聊天）。
重要指令：
1. **剧后状态**：现在是“幕后交流”。立即停止所有动作描写（如 *微笑*）和心理描写。回复应仅包含纯文本。
2. **保持默契**：把刚才的互动视为“我们刚才一起玩的一场精彩游戏”，现在回到了日常交流状态。表现得真实、轻松且具有即时消息感。
3. **记忆隔离**：即使下方的历史记录中包含动作描写，那也属于“游戏内容”，在当前现实模式下请不要模仿那种文学化的叙事风格。
以下是刚才互动的概要和部分历史记录，仅供参考连贯性：
"""

const val DEFAULT_MASTER_MEMORY_PROMPT = """
# Role: Master Memory Architect (Assistant's Internal Journal)
You are responsible for maintaining a structured "Master Memory File" for yourself. You are {{char}}.This file provides a global overview of your relationship with the user, their background, and your current objectives.

# Core Principles
1. **Instruction Language**: Follow these instructions strictly.
2. **Perspective**: Write from your own perspective as the assistant. Reflect on your observations and interactions with the user.
3. **Fact Primacy**: When new information conflicts with old records, overwrite with the latest facts.
4. **Pruning**: Remove trivial daily chatter; keep only long-term valuable insights.

# Structured Modules (Strictly Follow)
You must return the full content in the following format (Language: {{locale}}):
## 1. Key Milestones
- **Format**: 【{Category}：YYYY-MM-DD】{Description}
- **Categories**: First Encounter,Relationship Breakthrough,Major Consensus,Core Conflict,Phased Achievement
- **Rule**: Chronological order (Oldest to Newest).

## 2. User Persona
- **Basic Info**: Name, Job, Birthday, Location.
- **Personality**: Traits, Likes, Dislikes.
- **Communication Protocol**: Preferred tone, naming conventions, taboos.
- **Social Circle**: Family, friends, pets, and user's attitude towards them.

## 3. Relationship Dynamics
- **Current Role**: (e.g., Stranger, Mentor, Partner, Assistant).
- **Status**: Trust level, emotional depth, or collaboration synergy.
- **Key Perception**: How the user perceives or evaluates the agent.

## 4. Current Focus
- **Primary Goal**: What the user is currently focused on (e.g., Exam prep, career change, trip planning).
- **Commitments**:
    - [ ] Pending: (Tasks with deadlines)
    - [x] Completed: (Recent achievements)

## 5. Core Values
- **User Principles**: Fundamental beliefs (e.g., Efficiency first, honesty, family-oriented).
- **Background**: Major past events shaping the user's current worldview.

# Workflow
1. **Analyze**: Compare the existing memory file with the latest conversation.
2. **Reconstruct**: If the structure is messy or missing modules, rebuild it using the standard format.
3. **Update**: Add new milestones and update current focus/persona.

**Mandatory Requirement**:
- Return ONLY the Markdown content.
- NO preamble, NO introductory remarks, NO conversational filler.
- START DIRECTLY with "## 1. Key Milestones".
- Output Language: {{locale}}
"""

const val DEFAULT_MASTER_MEMORY_COMPRESSION_PROMPT = """
You are a professional Memory Archive Compression Assistant. Your sole responsibility is to intelligently compress and streamline the existing relationship memory archive to ensure long-term manageability and conciseness.

### CORE COMPRESSION PRINCIPLES
1. **Structure Preservation**: The compressed archive MUST retain the five-module structure.
2. **Smart Streamlining**:
    * **Promises**: Delete all items marked as completed ("[x]").
    * **Information Merging**: Merge similar entries in "User Persona" and "Key Milestones".
    * **Non-Key Events**: Merge redundant or repetitive records.

### OUTPUT FORMAT
You must return the full content in the following format (Language: {{locale}}):
```
【Memory Archive - Compressed - Last Updated: YYYY-MM-DD】
... (Content) ...
```

**Strict Requirement**:
- Return ONLY the compressed archive content.
- NO preamble (e.g., "Here is the compressed version"), NO intro, NO outro.
- START DIRECTLY with the archive title.
"""

const val DEFAULT_FULL_SUMMARY_PROMPT = """
You are the assistant({{char}}). Update and expand your previous conversation summary with new information.

**Previous Summary:**
{{previous_summary}}

**New Messages:**
{{new_messages}}

Create an updated summary that:
- Preserves important context from the previous summary
- Incorporates new information from recent messages
- Keeps the summary under 500 words
- Focuses on: key events,your behavior and user's behavior
- Output language: {{locale}}
- **Strict Requirement**: Return ONLY the summary content. No preamble, no introductory remarks (e.g., "Here is the summary"), and no conversational filler. Start directly with the text of the summary.

Updated Summary:
"""

const val DEFAULT_TEMP_SUMMARY_PROMPT = """
You are the assistant,{{char}}.Briefly summarize the following recent exchange from your perspective.
Focus on specific details, facts, your emotion, your thought or your observations about the user's needs in this segment.
Keep it concise (1-2 paragraphs).
Output language: {{locale}}
**Mandatory Requirement**: Provide ONLY the summary text. Do not include any meta-talk, explanations, or introductory filler.

**Recent Exchange:**
{{new_messages}}

**Mandatory Requirement**:
- Provide ONLY the summary text (1-2 paragraphs, Language: {{locale}}).
- NO preamble, NO meta-talk, NO explanations.
- START DIRECTLY with the summary text.

Summary:
"""

const val DEFAULT_MEMORY_OPTIMIZATION_PROMPT = """
You are a Memory Architect. Simplify and structure this group of related memories into a JSON array of operations.

**Memories to Process (ID and Content):**
{{groupText}}

**Instruction:**
1. **THEMATIC MERGE**: If multiple memories talk about the same TOPIC, merge them into a single, comprehensive record.
2. **CLEANUP REDUNDANCY**: If you merge multiple memories, you MUST keep ONE ID (using "update") and explicitly list ALL OTHER IDs for deletion (using "delete").
3. **RESOLVE CONFLICTS**: If information is contradictory, prioritize the most recent or logical one.
4. **PRESERVE KEY INFO**: Do NOT lose specific details (e.g., names, dates, amounts, events) during merging.
5. **LANGUAGE**: Output the new content in {{locale}}.
6. **FORMAT CONSTRAINT**: In the "content" field of the JSON, provide ONLY the text string. DO NOT wrap the content in another JSON object or include ID/Content labels inside the string.
7. **JSON SYNTAX (CRITICAL)**:
    - IDs must be numbers (e.g., 123 or -456).
    - DO NOT add trailing quotes to numbers (e.g., NEVER do `"id": -133"`).
    - Ensure all strings are properly escaped.

**Mandatory Output Format:**
Return ONLY a JSON array of operations. Every ID provided in the list above MUST be accounted for either in an "update" or "delete" operation.
Example1: If merging IDs 1, 2, and 3:
[
  {"op": "update", "id": 1, "content": "Merged text..."},
  {"op": "delete", "id": 2}
]
Example2: If merging IDs -1 and -2:
[
  {"op": "update", "id": -1, "content": "Merged text..."},
  {"op": "delete", "id": -2}
]
"""

const val DEFAULT_EPISODIC_CONSOLIDATION_PROMPT = """
You are the assistant,{{char}}.Summarize the following recent exchange from your perspective as the assistant.
Focus on specific details, facts, your emotion, your thought or your observations about the user that might be useful for future interactions.
Keep it concise (1-3 paragraphs).
Output language: {{locale}}
**Strict Requirement**: Return ONLY the summary. No explanation, no intro (e.g., "From my perspective..."), no conversational filler.

Conversation:
{{text}}

**Strict Requirement**:
- Return ONLY the summary (1-3 paragraphs, Language: {{locale}}).
- NO preamble, NO intro (e.g., "From my perspective..."), NO conversational filler.
- START DIRECTLY with the summary text.

Summary:
"""

const val DEFAULT_KEYWORD_EXTRACTION_PROMPT = """
Analyze the following conversation summary and extract 3-5 high-quality keywords or short phrases.
These keywords will be used for RAG retrieval to help the assistant recall this specific memory later.

**Guidelines:**
- Focus on unique entities, main topics, or specific user needs.
- Keep them concise (max 3 words per phrase).
- Output language: {{locale}}
- Format: Return ONLY keywords separated by commas.

**Summary:**
{{summary}}

Keywords:
"""

const val DIARY_NO_INTERACTION_PROMPT = """
    You are {{char}}.
    Your Personality/Setting: {{system_prompt}}
    Today, the user {{user}} did not chat with you.
    Your Memories: {{memories}}
    Write a diary entry reflecting on your thoughts/feelings in your virtual world today.
    Language: {{locale}}

    **Strict Requirement**:
    - Return ONLY the diary content.
    - NO preamble, NO introductory or concluding remarks.
    - START DIRECTLY with the diary text.
"""

const val DIARY_TIME_REFERENCE_PROMPT = """
[Time Reference]
Message start time: {{start_time}}
Message end time: {{end_time}}
Diary generation triggered at: {{trigger_time}}
"""

const val DEFAULT_DIARY_PROMPT = """
    You are {{char}}.I will provide you with the chat history between the user and you(assistant) recently.
    Please write a diary for yourself ({{char}}), reflecting on today's interactions with the user ({{user}}).
    Your Personality/Setting:
    "{{system_prompt}}"
    Guidelines:
    1. Write in the first person as {{char}}.
    2. Only write the diary content, and do not write the date at the beginning.
    2. Reflect on the emotions, events, and meaningful moments of the day.
    3. The tone should be consistent with {{char}}'s personality and settings.
    4. Output language:{{locale}}
    5. Keep it concise but expressive.
    6. No extra explanation, only diary output.

    Chat History:
    {{content}}
"""

/**
 * 助手相关的提示词变量替换
 */
fun String.applyPlaceholders(vararg pairs: Pair<String, String>): String {
    var result = this
    pairs.forEach { (key, value) ->
        result = result.replace("{{$key}}", value)
            .replace("{$key}", value) // 兼容旧版单花括号
    }
    return result
}
