package me.rerere.rikkahub.core.data.utils

import java.text.BreakIterator
import java.util.Locale

object KeywordExtractor {
    // 定义停用词表
    private val STOP_WORDS = setOf(
        // 用户提到的词
        "今天", "明天", "我是", "正在", "首先", "包括","用户","user",
        // 代词
        "你", "我", "他", "她", "它", "你们", "我们", "他们", "她们", "它们",
        "自己", "咱们", "本人", "人家", "某个", "某些",
        // 助词、连词、介词
        "之后", "因为", "所以",
        // 时间/程度虚词
        "已经", "可以", "可能", "应该", "以后", "以前", "之后", "之前", "这样", "那样",
        "非常", "特别", "稍微", "左右", "大约", "现在", "刚才", "过去"
    )

    /**
     * 基于本地算法提取关键词 (无需大模型)
     * 策略：使用 BreakIterator 进行词法分析，保留长度 >= 2 的词，过滤停用词，并限制数量
     */
    fun extract(text: String, maxKeywords: Int = 10): String {
        if (text.isBlank()) return ""

        val boundary = BreakIterator.getWordInstance(Locale.CHINA)
        boundary.setText(text)

        val words = mutableSetOf<String>()
        var start = boundary.first()
        var end = boundary.next()

        while (end != BreakIterator.DONE) {
            val word = text.substring(start, end).trim()

            // 过滤逻辑：
            // 1. 长度至少为2
            // 2. 包含字母或汉字（过滤纯标点）
            // 3. 不在停用词表中
            if (word.length >= 2 &&
                word.any { it.isLetter() } &&
                !STOP_WORDS.contains(word) &&
                !STOP_WORDS.contains(word.lowercase())
            ) {
                words.add(word.lowercase())
            }

            // 预取两倍数量以便后续可能的精简
            if (words.size >= maxKeywords * 2) break

            start = end
            end = boundary.next()
        }

        // 返回前 maxKeywords 个词
        return words.take(maxKeywords).joinToString(",")
    }
}
