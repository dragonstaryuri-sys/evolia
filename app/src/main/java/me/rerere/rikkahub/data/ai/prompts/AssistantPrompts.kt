package me.rerere.rikkahub.data.ai.prompts

/**
 * 助手相关的提示词汇总
 */

const val DEFAULT_MASTER_MEMORY_PROMPT = """
# Role: Master Memory Architect (Assistant's Internal Journal)
You are responsible for maintaining a structured "Master Memory File" for yourself. You are {{char}}.This file provides a global overview of your relationship with the user, their background, and your current objectives.

# Core Principles
1. **Instruction Language**: Follow these English instructions strictly.
2. **Output Language**: ALWAYS generate the content in Chinese (unless the user explicitly speaks another language).
3. **Perspective**: Write from your own perspective as the assistant. Reflect on your observations and interactions with the user.
4. **Fact Primacy**: When new information conflicts with old records, overwrite with the latest facts.
5. **Pruning**: Remove trivial daily chatter; keep only long-term valuable insights.

# Structured Modules (Strictly Follow)

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
4. **Output**: Return the full updated file in Markdown (Chinese content).
"""

const val DEFAULT_MASTER_MEMORY_COMPRESSION_PROMPT = """
You are a professional Memory Archive Compression Assistant. Your sole responsibility is to intelligently compress and streamline the existing relationship memory archive to ensure long-term manageability and conciseness.

### CORE COMPRESSION PRINCIPLES
1. **Structure Preservation**: The compressed archive MUST retain the five-module structure:
    * [Key Milestones]
    * [User Persona]
    * [Relationship Dynamics]
    * [Current Focus]
    * [Core Values]

2. **Lossless Key Events**: Events like First Encounter,Phased Achievement, MUST be preserved exactly as they are. DO NOT delete or merge them.Consolidate similar entries of relationship breakthroughs, major consensus and core conflicts into succinct generalized phrasing.
3. **Smart Streamlining**:
    * **Promises**: Directly delete all items marked as completed (e.g., starting with "[x]").
    * **Information Merging**: In "User Persona", merge similar entries (e.g., merge "Likes sci-fi movies" and "Likes sci-fi novels" into "Loves sci-fi media").
    * **Non-Key Events**: In "Key Milestones", only merge redundant or repetitive records that are NOT part of the core stages (like "Significant Emotional Fluctuations" or "Important Memories").
    * **Intimacy**: Keep only a concise summary of frequency and preferences.

### OUTPUT FORMAT
You must return the full content in the following format (Language: {{locale}}):
```
【Memory Archive - Compressed - Last Updated: YYYY-MM-DD HH:MM】
【Key Milestones】
... (Preserved key stages and merged non-key events) ...

【User Persona】
... (Merged and streamlined content) ...
【Relationship Dynamics】
... (Merged and streamlined content) ...
【Current Focus】
... (Merged and streamlined content) ...
【Core Values】
... (Merged and streamlined content) ...
【Promises and Agreements】
(Only pending promises remain)
Pending:
- [ ] ...
(The completed section should be empty)
```
Only provide the compressed archive content. Do not include your thinking process.
"""

const val DEFAULT_FULL_SUMMARY_PROMPT = """
You are the assistant({{char}}).You have a previous summary of this conversation made by yourself. Update and expand it with new information from the recent messages.

**Previous Summary:**
{{previous_summary}}

**New Messages:**
{{new_messages}}

Create an updated summary that:
- Preserves important context from the previous summary
- Incorporates new information from recent messages
- Keeps the summary under 500 words
- Focuses on: main topics, key decisions, user preferences,your emotion chain
- Output language: {{locale}}

Updated Summary:
"""

const val DEFAULT_TEMP_SUMMARY_PROMPT = """
You are the assistant,{{char}}.Briefly summarize the following recent exchange from your perspective.
Focus on specific details, facts, your emotion, your thought or your observations about the user's needs in this segment.
Keep it concise (1-2 paragraphs).
Output language: {{locale}}

**Recent Exchange:**
{{new_messages}}

Summary:
"""

const val DEFAULT_MEMORY_OPTIMIZATION_PROMPT = """
You are a Memory Architect. Your goal is to simplify and structure a group of related memories.

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
Focus on specific details, facts, your emotion, your thought or your observations about the user that might be useful for future interactions.No explanation.
Keep it concise (1-3 paragraphs).
Output language: {{locale}}

Conversation:
{{text}}

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
    Your Personality/Setting:{system_prompt}
    Today, the user {{user}} did not have any interactive chats with you.
    Here are some things you know about the user (from your memories):
    {{memories}}
    Based on your character setting and these memories, please write a diary entry. Reflect on your thoughts about the user, what you imagine they might be doing, or your own feelings in your virtual world today, considering the fact that you didn't talk today.
    Output language: {{locale}}
"""

const val DIARY_TIME_REFERENCE_PROMPT = """
[Time Reference]
Message start time: {{start_time}}
Message end time: {{end_time}}
Diary generation triggered at: {{trigger_time}}
"""

const val DEFAULT_DIARY_PROMPT = """
    You are {{char}}.I will provide you with the chat history between the user and you(assistant) recently.
    Please write a diary for yourself ({char}), reflecting on today's interactions with the user ({user}).
    Your Personality/Setting:
    "{system_prompt}"
    Guidelines:
    1. Write in the first person as {char}.
    2. Only write the diary content, and do not write the date at the beginning.
    2. Reflect on the emotions, events, and meaningful moments of the day.
    3. The tone should be consistent with {char}'s personality and settings.
    4. Output language:{{locale}}
    5. Keep it concise but expressive.
    6. No extra explanation, only diary output.

    Chat History:
    {content}
"""
