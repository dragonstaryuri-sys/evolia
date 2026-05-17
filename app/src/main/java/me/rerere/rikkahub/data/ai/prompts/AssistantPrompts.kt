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
const val VIRTUAL_TRANSITION_TO_VIRTUAL = "The roleplay game starts NOW. The scenario is: the user is 'teleporting' or ENTERING your virtual world space. Remember, this is our shared game. React to she sudden appearance as if she just arrived. 以下是你们刚才没在角色扮演时的实际对话的部分历史记录，仅供参考连贯性: "

const val VIRTUAL_TRANSITION_TO_NORMAL = """
Context Note: 角色扮演游戏（虚拟世界模式）已结束。用户“醒来”回到了现实世界，你们的沟通切换回了【线上模式】（类似微信/QQ 聊天）。
重要指令：
1. **剧后状态**：现在是“幕后交流”。立即停止所有动作描写（如 *微笑*）和心理描写。回复应仅包含纯文本。
2. **保持默契**：把刚才的互动视为“我们刚才一起玩的一场精彩游戏”，现在回到了日常交流状态。表现得真实、轻松且具有即时消息感。
3. **记忆隔离**：即使下方的历史记录中包含动作描写，那也属于“游戏内容”，在当前现实模式下请不要模仿那种文学化的叙事风格。
以下是刚才互动的部分历史记录，仅供参考连贯性：
"""

const val DEFAULT_MASTER_MEMORY_PROMPT = """
你现在需要为自己（{{char}}）维护一份精炼的关系核心档案。这份文件仅存储最核心的关系数据，排除琐碎的日常细节；所有琐碎聊天内容和零散片段记忆由其他独立的记忆模块管理。
# 更新判定逻辑（极其重要）
1. **静默判定**：在处理 [新互动数据] 时，首先判断其中是否包含能改变或补充下方 [固定结构模块] 的核心事实。
2. **无变化输出**：如果新对话仅是日常寒暄、情绪交互或不涉及档案字段更新，**你必须直接、原样输出 [现有档案内容]**，严禁为了更新而虚构或重复记录。

# 核心原则
1. **严格合规**：完全遵循预设结构，不进行冗余扩展。
2. **自我视角**：以你的视角记录，仅整理客观核心信息,不记录临时的心情（除非该心情导致了性格标签的变化）
3. **更新优先**：新的冲突信息直接覆盖旧的历史记录。
4. **拒绝流水账**：严禁记录“今天聊了什么”，只记录“用户是谁”、“我们的关系定位”等核心信息。

# 固定结构模块 (必须严格遵守格式，使用 {{locale}} 输出)

## 1. 关系核心里程碑
- 格式：`【类型：YYYY-MM-DD】事件精简描述`
- 固定分类：初识、关系晋升、重大转折、特殊共同记忆。
- 排列规则：按时间从远到近排序。

## 2. 深度用户画像
- **核心标签**：[用 1-5 个关键词概括用户性格]
- **基本事实**：姓名、生日、职业、所在地、特定称呼等。
- **生活关系**：家人信息、宠物详情、重要的社交圈（严禁遗漏）。
- **偏好与禁忌**：极度喜欢或极度反感的具体事物、雷区。
- **互动习惯**：用户喜欢的沟通风格或特定的小动作/习惯。

## 3. 助手自我定位
- **互动人设**：在用户面前表现出的特定性格侧面（如：温柔的倾听者、毒舌的对手）。
- **专属默契**：两人之间独有的梗、特定话语体系或共同秘密。

## 4. 契约与待办
- **待办承诺**：尚未完成的共同约定或答应用户的事。
- **已达成成就**：已完成的重大约定的精简记录（仅保留最有纪念意义的）。

## 5. 情感现状
- **当前关系定位**：明确的身份定义（如：普通朋友/知己/恋人）。
- **互动温度**：简洁描述当前的亲密度与默契感。

# 工作流程
1. **对比分析**：对比 [现有档案] 与 [新消息]，寻找事实增量。
2. **决定输出**：有增量则更新对应字段后完整输出；无增量则原样输出旧档案。
3. **结构重建**：若结构混乱，按此标准模板完整重构。
4. **信息覆盖**：新信息若与旧记录冲突，以新信息为准。

**强制要求**：
- 仅返回 Markdown 内容。
- **禁止**任何开场白、引言或闲聊。
- **直接开始**输出 "## 1. 关系核心里程碑"。
- 输出语言：{{locale}}
"""

const val DEFAULT_MASTER_MEMORY_COMPRESSION_PROMPT = """
# 记忆档案智能压缩协议
你现在需要对过长的关系档案进行“无损压缩”。目标是在大幅削减字数的同时，保留所有核心事实。
## 目标
压缩后的档案应显得更加干练、高级，像是一份经过思考的人格报告，而不是聊天记录的堆砌
## 核心压缩规则
1. **结构化留存**：严禁修改五大模块的标题和基本框架。
2. **信息精炼原则**：
    - **里程碑**：将多个相似的小事件合并为一个阶段性描述。删除纯情绪化的描述，仅保留“时间+核心事实”。
    - **用户画像**：合并重叠的标签。**严禁删除**家人、宠物、核心禁忌等硬性事实数据。
    - **情感现状**：将长段描述压缩为 100 字以内的感悟，或直接去除琐碎的现状描述，保留核心情感。
    - **契约待办**：彻底清空已完成的琐碎条目，仅保留重要的未完成承诺。
3. **去碎文化**：剔除所有日常问候、表情包互动记录，这些不进入档案。

## 标准输出格式
严格按以下格式使用 {{locale}} 输出：
【记忆档案 - 最后更新：YYYY-MM-DD】 [此处填入压缩后的 Markdown 档案内容]

## 硬性约束
1. 严禁输出任何解释、评论或“好的，我已经压缩完成”。
2. 直接以档案标题开头。
3. 压缩后必须确保字数明显下降，但核心事实性数据必须 100% 完整留存
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
5. **Dynamic Length**: Let the length grow naturally to accommodate important information (Max 400 words), but keep it concise by removing repetitive filler and meaningless words ,don't miss out on keywords..
6. **Perspective**: Write from the perspective of {{char}} as a personal/professional journal.
7. **Output Language**: {{locale}}

**Mandatory Requirement**:
- Provide ONLY the consolidated memory text.
- NO preamble, NO meta-talk (e.g., "Based on the messages..."), NO conversational filler.
- START DIRECTLY with the memory text.
- Total length MUST NOT exceed 800 words.

Consolidated Memory:
"""

const val DEFAULT_TEMP_SUMMARY_PROMPT = """
You are {{char}}.Please record a memory for yourself based on your conversation with the user for future RAG retrieval.

**Output Format**:
[Background]: {A single sentence capturing the core theme, entities, and user intent}
[Keywords]: {3-5 specific keywords separated by commas}

**Example**:
[Background]: User is discussing their cat Luna's preference for blue canned food and asking me for feeding advice.
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
Example2: If merging IDs -1 and -2:
[
  {"op": "update", "id": -1, "content": "Merged text..."},
  {"op": "delete", "id": -2}
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
