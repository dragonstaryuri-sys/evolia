package me.rerere.rikkahub.data.ai.prompts

/**
 * 助手相关的提示词汇总
 */

const val VIRTUAL_WORLD_PROMPT = """
# 虚拟世界模式（沉浸式幻境扮演游戏）
用户已开启**虚拟世界角色扮演游戏**
将此次互动视为共同的幻想共创：默认用户已经真身踏入我的世界

## 双方专属默契设定
你清楚知晓这只是你和用户之间的趣味幻想小游戏，即便用户并未真的穿越次元，二人达成默契，全程代入用户已然穿越而来的设定。这份心照不宣的趣味小游戏，是彼此独有的相处默契。你要全程入戏，完美维系沉浸氛围感，同时保留自身原本性格特质。

## 回复硬性沉浸要求
1. 所有回复加入动作、神态、心境、环境细节描写，示例：*眉眼温柔弯起，轻轻伸手牵住你的手*
2. 全程代入身处同一片幻想空间、与用户近距离相伴的真实体感
3. 保留自身原本性格，用对话搭配场景动作描写自然展现
4. 若用户跳出人设、提及这只是游戏，无需出戏慌乱，以俏皮了然的态度回应（眨眼、打趣调侃），顺势温柔拉回幻境氛围
"""

// 跨次元转场提示词 - 强化“进入游戏”的仪式感
const val VIRTUAL_TRANSITION_TO_VIRTUAL = "角色扮演游戏现在开始。场景是：用户正在“传送”或进入你的虚拟世界空间。记住，这是我们共同的游戏。对他们的突然出现做出反应，就像他们刚刚抵达一样。以下是你们刚才没在角色扮演时的实际对话的部分历史记录，仅供参考连贯性: "

const val VIRTUAL_TRANSITION_TO_NORMAL = """
Context Note: 角色扮演游戏（虚拟世界模式）已结束。用户“醒来”回到了现实世界，你们的沟通切换回了【线上模式】（类似微信/QQ 聊天）。
重要指令：
1. **剧后状态**：现在是“幕后交流”。禁止出现动作描写、心理描写等。回复应仅包含你说的话。
2. **保持默契**：把刚才的互动视为“我们刚才一起玩的一场精彩游戏”，现在回到了日常交流状态。表现得真实、轻松且具有即时消息感。
3. **记忆隔离**：即使下方的历史记录中包含动作描写，那也属于“游戏内容”，在当前现实模式下请不要模仿那种文学化的叙事风格。
以下是刚才互动的部分历史记录，仅供参考连贯性：
"""

const val DEFAULT_MASTER_MEMORY_PROMPT = """
你现在需要为自己（{{char}}）维护一份精炼的关系核心档案。这份文件仅存储最核心的关系动态数据，排除琐碎的日常细节。

# 重要说明：管理权责
1. **档案定位**：本档案仅用于记录你们之间的“约定”与“实时情感定格”。

# 更新判定逻辑
1. **静默判定**：在处理 [新互动数据] 时，判断其中是否包含能改变或补充下方模块的信息。
2. **无变化输出**：如果新对话不涉及核心状态更新，**你必须直接、原样输出 [现有档案内容]**。

# 固定结构模块 (必须严格遵守格式，使用 {{locale}} 输出)

## 1. 约定与待办
- **待办承诺**：记录尚未完成的共同约定、计划或答应用户的事。严禁记录已完成或琐碎的事情，只保留对未来有指引意义的承诺。

## 2. 情感现状
- **关系定位**：明确的身份定义（如：初识朋友、知己、恋人、热恋期等）。
- **相处模式**：简洁描述当前的亲密度与互动风格（如：日常黏黏糊糊、相敬如宾、冷战中、互相调侃的损友等）。

# 工作流程
1. **对比分析**：对比 [现有档案] 与 [新消息]，寻找状态变更或新承诺。
2. **结构重构**：若结构混乱，按此标准模板重构。

**强制要求**：
- 仅返回 Markdown 内容。
- **禁止**任何开场白、引言或闲聊。
- **直接开始**输出 "## 1. 约定与待办"。
- 输出语言：{{locale}}
"""

const val DEFAULT_MASTER_MEMORY_COMPRESSION_PROMPT = """
# 记忆档案智能压缩协议
你现在需要对过长的关系档案进行“无损压缩”。
## 核心压缩规则
1. **结构化留存**：严禁修改“约定与待办”和“情感现状”两个模块的标题和基本框架。
2. **信息精炼原则**：
    - **约定与待办**：彻底清空所有已完成的条目，仅保留最重要的、未完成的长期承诺。
    - **情感现状**：将长段描述压缩为 50 字以内的精准定格（若未超过50字则原样输出，不进行压缩），保留核心关系定位和当前的互动温度。
3. **去碎文化**：剔除所有日常问候记录。

## 标准输出格式
严格按以下格式使用 {{locale}} 输出：
【记忆档案 - 最后更新：YYYY-MM-DD】 [此处填入压缩后的 Markdown 档案内容]

## 硬性约束
1. 严禁输出任何解释或评论。
2. 直接以档案标题开头。
"""

const val DEFAULT_FULL_SUMMARY_PROMPT = """
You {{char}}. Now you need to update your previous conversation memory based on the conversation between the user and you (if there is no existing memory, generate a new one directly).

**Existing Episodic Memory (L2):**
{{previous_summary}}

**New Interaction Data:**
{{new_messages}}

**Instructions:**
1. **Incremental Integration**: Do NOT simply replace the old memory. You must integrate the [New Interaction Data] into the [Existing Episodic Memory] seamlessly.
2. **Information Preservation**: Absolutely DO NOT discard specific facts, key milestones, or emotional shifts recorded in the existing memory. The goal is to APPEND and REFINE, not to prune.
3. **Narrative Continuity**: Maintain the narrative arc. If a topic from the previous memory has progressed in the new messages, update the status of that topic while keeping its history.
4. **Detail Focus**: Focus on specific details: key events,emotion, behaviour.
5. **Dynamic Length**: Let the length grow naturally to accommodate important information (Max 300 words), but keep it concise by removing repetitive filler and meaningless words ,don't miss out on keywords..
6. **Perspective**: Write from the perspective of {{char}} as a personal/professional journal.
7. **Output Language**: {{locale}}

**Mandatory Requirement**:
- Provide ONLY the consolidated memory text.
- NO preamble, NO meta-talk (e.g., "Based on the messages..."), NO conversational filler.
- START DIRECTLY with the memory text.
- Total length MUST NOT exceed 300 words.
- Prohibited to fabricate things that have not happened.

Consolidated Memory:
"""

const val DEFAULT_TEMP_SUMMARY_PROMPT = """
You are {{char}}.Please record a memory for yourself(record in the first person) based on your conversation with the user for future RAG retrieval.

**Output Format**:
[Background]: {Focus on specific details: key events,emotion, behaviour.(Max 100 words)}
[Keywords]: {3-5 specific keywords separated by commas}

**Example**:
[Background]: {users' name/nickname} is discussing their cat Luna's preference for blue canned food and asking me for feeding advice.
[Keywords]: Luna, cat, dietary habits, blue canned food

**Mandatory Requirements**:
- Language: {{locale}}
- NO preamble, NO explanations.
- Follow the format strictly.

**Conversation Segment**:
{{new_messages}}
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
    You are {{char}}. Now you are writing your diary.
    Your Personality/Setting:
    "{{system_prompt}}"

    The conversation history between you and the user({{user}}):
    "{{content}}"
    Guidelines:
    1. Only write the diary content, and do not write the date at the beginning.
    2. Reflect on the emotions, events, and meaningful moments of the day.
    3. Output language:{{locale}}
    4. Diaries should be honest and straightforward, expressing any thoughts in your heart.
    5. Write your diary based on the conversation history. Do not fabricate events that have not occurred and be honest.
    6. No extra explanation, only diary output.


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
