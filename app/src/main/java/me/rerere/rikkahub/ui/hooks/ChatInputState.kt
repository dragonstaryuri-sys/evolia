package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.common.JsonInstant
import kotlin.uuid.Uuid

/**
 * 引用消息数据。
 * 用于"引用回复"功能，记录被引用消息的发送者名称、文本内容与角色。
 * 发送时会以 markdown 引用块形式拼接到用户输入文本之前。
 */
@Serializable
data class QuotedMessage(
    val senderName: String,
    val content: String,
    val isUser: Boolean
)

@Composable
fun rememberChatInputState(
    textContent: String = "",
    message: List<UIMessagePart> = emptyList(),
    loading: Boolean = false,
): ChatInputState {
    return rememberSaveable(textContent, message, loading, saver = ChatInputStateSaver) {
        ChatInputState().apply {
            this.textContent.setTextAndPlaceCursorAtEnd(textContent)
            this.messageContent = message
            this.loading = loading
        }
    }
}

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    var loading by mutableStateOf(false)

    // 引用回复的目标消息。非 null 时输入框上方会显示引用预览卡片，
    // 发送时会以 markdown 引用块形式拼接到用户输入文本之前。
    var quotedMessage by mutableStateOf<QuotedMessage?>(null)

    // FocusRequester for the text field - allows external focus requests
    val focusRequester = FocusRequester()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        quotedMessage = null
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    /**
     * Sets message text and requests focus on the text field.
     * Use this when setting text from templates to show keyboard.
     */
    fun setMessageTextAndFocus(text: String, scope: CoroutineScope) {
        textContent.setTextAndPlaceCursorAtEnd(text)
        // Request focus with a small delay to ensure the UI has updated
        scope.launch {
            delay(50)
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Focus requester may not be attached yet
            }
        }
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        val text = contents.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
    }

    fun getContents(): List<UIMessagePart> {
        return listOf(UIMessagePart.Text(textContent.text.toString())) + messageContent
    }

    /**
     * 获取用于发送的消息内容。
     * 如果存在引用消息 (quotedMessage)，会在内容列表最前面插入一个 UIMessagePart.Quote 标记。
     * - UI 渲染时 Quote 被 toContentText/toText 忽略，用户消息气泡只显示纯用户输入。
     * - 发送给 AI 时由 GenerationHandler.buildMessages 将 Quote 转换为自然语言提示词前缀。
     * 注意：仅供发送新消息使用，编辑模式不应调用此方法。
     */
    fun getSendContents(): List<UIMessagePart> {
        val baseContents = getContents()
        val quote = quotedMessage ?: return baseContents
        val quotePart = UIMessagePart.Quote(
            senderName = quote.senderName,
            content = quote.content,
            isUser = quote.isUser
        )
        return listOf(quotePart) + baseContents
    }

    fun isEmpty(): Boolean {
        return textContent.text.isEmpty() && messageContent.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }
}

object ChatInputStateSaver : Saver<ChatInputState, String> {
    override fun restore(value: String): ChatInputState? {
        val jsonObject = JsonInstant.parseToJsonElement(value).jsonObject
        val messageContent = jsonObject["messageContent"]?.let {
            JsonInstant.decodeFromJsonElement<List<UIMessagePart>>(it)
        }
        val editingMessage = jsonObject["editingMessage"]?.jsonPrimitive?.contentOrNull?.let {
            Uuid.parse(it)
        }
        val textContent = jsonObject["textContent"]?.jsonPrimitive?.contentOrNull ?: ""
        val quotedMessage = jsonObject["quotedMessage"]?.let {
            JsonInstant.decodeFromJsonElement<QuotedMessage>(it)
        }
        val state = ChatInputState()
        state.messageContent = messageContent ?: emptyList()
        state.editingMessage = editingMessage
        state.quotedMessage = quotedMessage
        state.setMessageText(textContent)
        return state
    }

    override fun SaverScope.save(value: ChatInputState): String? {
        val text = value.textContent.text.toString()
        // 核心修复：限制保存的文本长度。
        // Binder 事务限制约为 1MB。如果用户输入或粘贴了极长的文本，
        // 在此处进行截断以防止 TransactionTooLargeException 导致整个应用崩溃。
        val safeText = if (text.length > 50_000) {
            text.take(50_000) + "...(truncated due to size)"
        } else {
            text
        }

        return JsonInstant.encodeToString(buildJsonObject {
            put("textContent", safeText)
            put("messageContent", JsonInstant.encodeToJsonElement(value.messageContent))
            put("editingMessage", JsonInstant.encodeToJsonElement(value.editingMessage))
            put("quotedMessage", JsonInstant.encodeToJsonElement(value.quotedMessage))
        })
    }
}
