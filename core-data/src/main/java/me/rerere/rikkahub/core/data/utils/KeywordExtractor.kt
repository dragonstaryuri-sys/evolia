package me.rerere.rikkahub.core.data.utils

import java.text.BreakIterator
import java.util.Locale

object KeywordExtractor {
    /**
     * 基于本地算法提取关键词 (无需大模型)
     * 策略：使用 BreakIterator 进行词法分析，保留长度 >= 2 的词，并限制数量
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
            // 过滤：长度至少为2，且不是纯数字/符号
            if (word.length >= 2 && word.any { it.isLetter() }) {
                words.add(word.lowercase())
            }
            if (words.size >= maxKeywords * 2) break // 预取多一点用于后续过滤
            start = end
            end = boundary.next()
        }

        // 简单频率/长度排序（可选），这里直接取前 maxKeywords 个
        return words.take(maxKeywords).joinToString(",")
    }
}
