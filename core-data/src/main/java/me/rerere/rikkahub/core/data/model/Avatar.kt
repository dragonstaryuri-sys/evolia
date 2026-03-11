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
        val type = element.jsonObject["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "Dummy",
            "me.rerere.rikkahub.data.model.Avatar.Dummy",
            "me.rerere.rikkahub.core.data.model.Avatar.Dummy" -> Avatar.Dummy.serializer()

            "Emoji",
            "me.rerere.rikkahub.data.model.Avatar.Emoji",
            "me.rerere.rikkahub.core.data.model.Avatar.Emoji" -> Avatar.Emoji.serializer()

            "Image",
            "me.rerere.rikkahub.data.model.Avatar.Image",
            "me.rerere.rikkahub.core.data.model.Avatar.Image" -> Avatar.Image.serializer()

            "Resource",
            "me.rerere.rikkahub.data.model.Avatar.Resource",
            "me.rerere.rikkahub.core.data.model.Avatar.Resource" -> Avatar.Resource.serializer()

            else -> Avatar.Dummy.serializer()
        }
    }
}
