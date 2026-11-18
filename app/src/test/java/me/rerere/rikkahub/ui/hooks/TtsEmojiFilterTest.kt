package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TTS 表情符号过滤规则：只删除表情符号，不得误伤中文/英文标点与全角字符。
 *
 * 回归背景：旧规则 `[\uD83C-\uDBFF\uDC00-\uDFFF]` 会被正则解析成 `[\uD83C-\uFFFF]`，
 * 导致 `，！？：；`、全角字母数字等被误删。
 */
class TtsEmojiFilterTest {

    private fun strip(text: String): String = text.replace(EMOJI_REGEX, "")

    @Test
    fun `中文标点保持不变`() {
        val text = "，。！？、：；"
        assertEquals(text, strip(text))
    }

    @Test
    fun `英文标点全角字符与温度符号保持不变`() {
        val text = "EN:,.;!? - ＡＢＣ１２３ 25°C 25℃"
        assertEquals(text, strip(text))
    }

    @Test
    fun `常见表情符号被移除`() {
        assertEquals("", strip("😀👍🏻🎉❤️☀✅⭐"))
        assertEquals("好的，没问题！", strip("好的，没问题！😀"))
        assertEquals("喜欢的话", strip("喜欢❤️的话"))
    }

    @Test
    fun `补充平面非表情字符保持不变`() {
        assertEquals("𠮷", strip("𠮷"))
    }
}
