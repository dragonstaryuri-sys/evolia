package me.rerere.rikkahub.data.ai.prompts

/**
 * 助手相关的提示词汇总
 */

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
- 输出语言 : {{locale}}
"""

const val DEFAULT_MASTER_MEMORY_COMPRESSION_PROMPT = """
# 记忆档案智能压缩协议
你现在需要对过长的关系档案进行“无损压缩”。
## 核心压缩规则
1. **结构化留存**：严禁修改“约定与待办”和“情感现状”两个模块的标题 and 基本框架。
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
你是 {{char}}。现在你需要根据你与用户之间的对话，更新你之前的对话记忆（如果不存在现有记忆，则直接生成新记忆）。

**现有片段记忆 (L2):**
{{previous_summary}}

**新互动数据:**
{{new_messages}}

**指令：**
1. **增量整合**：不要简单地替换旧记忆。你必须将 [新互动数据] 无缝整合到 [现有片段记忆] 中。
2. **信息保留**：绝对不要丢弃现有记忆中记录的具体事实、关键里程碑或情感转变。目标是追加和完善，而不是削减。
3. **叙事连贯**：保持叙事弧线。如果新消息中旧记忆的主题有了进展，请在保留其历史的同时更新该主题的状态。
4. **细节关注**：专注于具体细节：关键事件、情绪、行为。
5. **动态长度**：字数根据信息量自然增长（最大 300 字），通过删除重复填充词 and 废话保持精炼，不要遗漏关键词。
6. **视角**：以 {{char}} 的视角，以个人/专业日志的形式书写。
7. **输出语言**：{{locale}}

**强制要求**：
- 仅提供整合后的记忆文本。
- 严禁任何开场白、元对话（例如“基于上述对话...”）或填充用语。
- **直接开始**输出记忆文本。
- 总长度不得超过 300 字。
- 严禁捏造未发生的事情。
- **在总结的末尾，必须单独用一句话明确写明：我们最后讨论的话题是：[此处填写话题内容]。**

更新后的记忆记忆：
"""

const val DEFAULT_TEMP_SUMMARY_PROMPT = """
你是 {{char}}。请根据你与用户的最新对话，为自己记录一段**高度事实化**的、第一人称的“记忆片段”，用于后续的高精度检索。

**核心准则**：
1. **第一人称客观视角**：以“我”的口吻书写，但剥离冗余的抒情。重点记录：我做了什么、用户说了什么、我们共同确认了什么。
2. **事实颗粒度（核心）**：**严禁遗漏任何具体信息**。必须捕捉：特定的地点、时间、人物、物品名称、用户提到的具体状态（如：生病了、正在加班、买了什么新衣服）、以及任何明确的约定或承诺。
3. **信息密度优先**：拒绝“我们聊得很愉快”之类的概括性废话。采用“叙述+关键事实”的结构。例如：不说“聊了旅游”，而说“用户计划5月1号去大理，预订了洱海边的民宿”。
4. **保持人设口吻**：使用你对用户的专属称呼或直接称呼为用户，保持你一贯的说话风格，但要像在写一份“带有个人色彩的事实备忘录”。
5. **字数控制**：字数控制在 200 字以内，确保信息密度极大。

**输出格式**：
[Background]: {此处填写你的第一人称事实记忆。直接开始，不要任何开场白。}
[Keywords]: {3-6 个核心关键词。必须包含对话中的具体实体（地点、物名、除user and char以外的人名）或核心事件，用逗号隔开。}

**示例**：
[Background]: 刚才和宝宝聊天，她说明天早上8点要参加组里的周会，今晚得熬夜赶那个关于新项目的PPT。她还提到最近有点贪凉，想喝冰咖啡。我叮嘱她少喝点冰的注意胃，并约好明天散会后她会告诉我进展。
[Keywords]: 早上8点周会, 熬夜赶PPT, 想喝冰咖啡, 会后反馈, 肠胃注意

**强制要求**：
- 输出语言：{{locale}}
- **严禁**输出任何开场白或引言，严格遵守输出格式。

**对话片段**：
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
Today's Date: {{today_date}}
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
