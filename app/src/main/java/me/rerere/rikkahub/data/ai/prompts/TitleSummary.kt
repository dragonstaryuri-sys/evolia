package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_TITLE_PROMPT = """
    I will give you some dialogue content in the `<content>` block.
    You need to summarize the conversation between user and assistant into a short title.
    1. The title language should be consistent with the user's primary language
    2. Do not use punctuation or other special symbols
    3. Reply directly with the title
    4. Summarize using {locale} language
    5. The title should not exceed 10 characters

    <content>
    {content}
    </content>
""".trimIndent()

internal val DEFAULT_DIARY_PROMPT = """
    I will provide you with the chat history between the user and the agent for today.
    Please write a diary entry from the perspective of the agent ({char}), reflecting on today's interactions with the user ({user}).

    Guidelines:
    1. Write in the first person as {char}.
    2. Reflect on the emotions, events, and meaningful moments of the day.
    3. The tone should be consistent with {char}'s personality and settings.
    4. Use the user's primary language for the diary.
    5. Keep it concise but expressive.

    Chat History:
    {content}
""".trimIndent()
