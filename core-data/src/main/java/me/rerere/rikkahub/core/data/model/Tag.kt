package me.rerere.rikkahub.core.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Tag(
    val id: Uuid,
    val name: String,
)
