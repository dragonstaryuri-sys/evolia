package me.rerere.rikkahub.core.data.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable(with = AvatarSerializer::class)
sealed class Avatar {
    @Serializable
    @SerialName("Dummy")
    data object Dummy : Avatar()

    @Serializable
    @SerialName("Emoji")
    data class Emoji(val content: String) : Avatar()

    @Serializable
    @SerialName("Image")
    data class Image(val url: String) : Avatar()

    @Serializable
    @SerialName("Resource")
    data class Resource(val id: Int) : Avatar()
}

object AvatarSerializer : JsonContentPolymorphicSerializer<Avatar>(Avatar::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Avatar> {
        val jsonObject = element.jsonObject
        // 尝试从 "type" 字段获取类名标识
        val type = jsonObject["type"]?.jsonPrimitive?.contentOrNull

        return when {
            // 匹配 Dummy
            type == "Dummy" ||
            type == "me.rerere.rikkahub.data.model.Avatar.Dummy" ||
            type == "me.rerere.rikkahub.core.data.model.Avatar.Dummy" -> Avatar.Dummy.serializer()

            // 匹配 Emoji
            type == "Emoji" ||
            type == "me.rerere.rikkahub.data.model.Avatar.Emoji" ||
            type == "me.rerere.rikkahub.core.data.model.Avatar.Emoji" -> Avatar.Emoji.serializer()

            // 匹配 Image
            type == "Image" ||
            type == "me.rerere.rikkahub.data.model.Avatar.Image" ||
            type == "me.rerere.rikkahub.core.data.model.Avatar.Image" -> Avatar.Image.serializer()

            // 匹配 Resource
            type == "Resource" ||
            type == "me.rerere.rikkahub.data.model.Avatar.Resource" ||
            type == "me.rerere.rikkahub.core.data.model.Avatar.Resource" -> Avatar.Resource.serializer()

            // 兜底逻辑：如果 type 匹配不上，但包含某些特征字段，尝试猜测
            jsonObject.containsKey("url") -> Avatar.Image.serializer()
            jsonObject.containsKey("content") -> Avatar.Emoji.serializer()
            jsonObject.containsKey("id") -> Avatar.Resource.serializer()

            // 实在不行返回 Dummy
            else -> Avatar.Dummy.serializer()
        }
    }
}
