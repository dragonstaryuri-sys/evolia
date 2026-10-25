package me.rerere.rikkahub.data.planner

/**
 * 轻量 Markdown 解析：面向 AI 生成的日计划文档。
 *
 * 支持块级：标题 (#~####)、段落、无序/有序列表、任务复选框 (- [ ] / - [x])、
 * 引用 (>)、分隔线 (---)。行内支持 **加粗**。
 * 不支持表格与图片（工具提示已要求 AI 在窄纸张上使用列表结构），
 * 未知语法按普通段落渲染，解析永不失败。
 */
object PlannerMarkdownParser {

    data class MdRun(val text: String, val bold: Boolean)

    sealed class MdBlock {
        data class Heading(val level: Int, val runs: List<MdRun>) : MdBlock()
        data class Paragraph(val runs: List<MdRun>) : MdBlock()
        data class ListItem(
            val ordered: Boolean,
            val marker: String,
            val runs: List<MdRun>,
            val checked: Boolean?,   // null = 普通列表项；true/false = 任务复选框
        ) : MdBlock()

        data class Quote(val runs: List<MdRun>) : MdBlock()
        data object Rule : MdBlock()
    }

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraphLines = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraphLines.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(parseRuns(paragraphLines.joinToString(" "))))
                paragraphLines.clear()
            }
        }

        var inFence = false
        val fenceBuffer = mutableListOf<String>()

        markdown.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()

            // 代码围栏：内容按普通段落合并渲染（避免 AI 输出围栏导致版面破碎）
            if (line.trimStart().startsWith("```")) {
                if (inFence) {
                    inFence = false
                    flushParagraph()
                } else {
                    flushParagraph()
                    inFence = true
                }
                return@forEach
            }
            if (inFence) {
                fenceBuffer.add(line)
                return@forEach
            }

            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flushParagraph()

                isHeading(trimmed) -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                    val text = trimmed.dropWhile { it == '#' }.trim()
                    blocks.add(MdBlock.Heading(level, parseRuns(text)))
                }

                isRule(trimmed) -> {
                    flushParagraph()
                    blocks.add(MdBlock.Rule)
                }

                isQuote(trimmed) -> {
                    flushParagraph()
                    val text = trimmed.removePrefix(">").trim()
                    val last = blocks.lastOrNull()
                    if (last is MdBlock.Quote) {
                        // 连续引用行合并为一段
                        blocks[blocks.size - 1] =
                            MdBlock.Quote(last.runs + MdRun(" ", false) + parseRuns(text))
                    } else {
                        blocks.add(MdBlock.Quote(parseRuns(text)))
                    }
                }

                isTaskListItem(trimmed) -> {
                    flushParagraph()
                    val checked = trimmed.substringAfter("[", missingDelimiterValue = "").startsWith("x")
                    val text = trimmed
                        .removePrefix("-").removePrefix("*")
                        .trim()
                        .removePrefix("[ ]").removePrefix("[x]").removePrefix("[X]")
                        .trim()
                    blocks.add(MdBlock.ListItem(ordered = false, marker = "", parseRuns(text), checked))
                }

                isUnorderedListItem(trimmed) -> {
                    flushParagraph()
                    val marker = trimmed.takeWhile { it == '-' || it == '*' || it == ' ' }.trim()
                    val text = trimmed.dropWhile { it == '-' || it == '*' || it == ' ' }.trim()
                    blocks.add(MdBlock.ListItem(ordered = false, marker = if (marker.isEmpty()) "-" else marker, parseRuns(text), null))
                }

                isOrderedListItem(trimmed) -> {
                    flushParagraph()
                    val marker = trimmed.substringBefore('.').trim() + "."
                    val text = trimmed.substringAfter('.').trim()
                    blocks.add(MdBlock.ListItem(ordered = true, marker = marker, parseRuns(text), null))
                }

                else -> paragraphLines.add(trimmed)
            }
        }
        // 未闭合围栏的内容仍然渲染
        if (inFence && fenceBuffer.isNotEmpty()) {
            paragraphLines.addAll(0, fenceBuffer)
        }
        flushParagraph()
        return blocks
    }

    /** 行内 **加粗** 解析 */
    fun parseRuns(text: String): List<MdRun> {
        val runs = mutableListOf<MdRun>()
        var bold = false
        text.split("**").forEachIndexed { index, part ->
            if (part.isNotEmpty()) {
                runs.add(MdRun(part, bold))
            }
            bold = !bold
        }
        if (runs.isEmpty()) runs.add(MdRun(text, false))
        return runs
    }

    private fun isHeading(line: String) =
        line.startsWith("#") && line.dropWhile { it == '#' }.startsWith(" ")

    private fun isRule(line: String) =
        (line == "---" || line == "***" || line == "___") ||
            (line.length >= 3 && line.all { it == '-' })

    private fun isQuote(line: String) = line.startsWith(">")

    private fun isTaskListItem(line: String) =
        (line.startsWith("- [ ]") || line.startsWith("* [ ]") ||
            line.startsWith("- [x]") || line.startsWith("* [x]") ||
            line.startsWith("- [X]") || line.startsWith("* [X]"))

    private fun isUnorderedListItem(line: String) =
        (line.startsWith("- ") || line.startsWith("* ")) && !isTaskListItem(line)

    private fun isOrderedListItem(line: String) =
        Regex("^\\d{1,2}\\.\\s+\\S").containsMatchIn(line)
}
