package me.rerere.tts.model

import kotlinx.serialization.Serializable

@Serializable
data class TTSVoice(
    val id: String,
    val name: String,
    val locale: String? = null,
    val gender: String? = null,
    val description: String? = null
)
