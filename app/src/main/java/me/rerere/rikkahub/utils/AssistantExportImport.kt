package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.model.CharacterCardV2
import me.rerere.rikkahub.core.data.model.CharacterCardV2Data
import me.rerere.rikkahub.core.data.model.Lorebook
import me.rerere.rikkahub.core.data.model.ModeAttachment
import me.rerere.rikkahub.core.data.model.ModeAttachmentType
import me.rerere.rikkahub.core.data.model.TavernCharacterBook
import me.rerere.rikkahub.core.data.model.TavernCharacterBookEntry
import me.rerere.rikkahub.core.data.model.toTavernCharacterBook
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.dao.ChatSegmentDAO
import me.rerere.rikkahub.core.data.db.entity.ChatSegmentEntity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.uuid.Uuid
import me.rerere.rikkahub.core.data.model.AssistantMemory

@Serializable
data class AssistantExportV1(
    val version: Int = 1,
    val format: String = "Evolia_assistant",
    val assistant: Assistant,
    // Bundled assets
    val avatarContent: String? = null,
    val avatarMimeType: String? = null,
    // Bundled Lorebooks
    val lorebooks: List<LorebookExportV2> = emptyList(),
    // Bundled Memories
    val memories: List<AssistantMemory> = emptyList(),
    // Bundled L1 Segments
    val segments: List<ChatSegmentExport> = emptyList()
)

@Serializable
data class ChatSegmentExport(
    val conversationId: String,
    val content: String,
    val keywords: String? = null,
    val startMessageIndex: Int,
    val endMessageIndex: Int,
    val timestamp: Long
)

object AssistantExportImport : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val chatEpisodeDAO: ChatEpisodeDAO by inject()
    private val chatSegmentDAO: ChatSegmentDAO by inject()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Export an Assistant to Evolia Bundle JSON format.
     */
    suspend fun exportToEvoliaBundle(
        assistant: Assistant,
        context: Context,
        includeMemories: Boolean,
        includeLorebooks: Boolean
    ): String {
        // 1. Process Avatar
        var avatarContent: String? = null
        var avatarMime: String? = null
        if (assistant.avatar is Avatar.Image) {
            val url = (assistant.avatar as Avatar.Image).url
            try {
                val bytes = readUriBytes(context, url)
                if (bytes != null) {
                    avatarContent = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    avatarMime = "image/*"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Process Lorebooks
        val bundledLorebooks = if (includeLorebooks) {
            val allLorebooks = settingsStore.settingsFlow.value.lorebooks
            assistant.enabledLorebookIds.mapNotNull { id ->
                allLorebooks.find { it.id == id }
            }.map { lorebook ->
                val entryAttachments = lorebook.entries.associate { entry ->
                    entry.id.toString() to entry.attachments.mapNotNull { attachment ->
                        embedAttachment(
                            context,
                            attachment.url,
                            attachment.type.name,
                            attachment.fileName,
                            attachment.mime
                        )
                    }
                }.filterValues { it.isNotEmpty() }

                LorebookExportV2(
                    version = 2,
                    format = "Evolia",
                    lorebook = lorebook,
                    entryAttachments = entryAttachments
                )
            }
        } else {
            emptyList()
        }

        // 3. Process Memories (L1-L3)
        var bundledMemories: List<AssistantMemory> = emptyList()
        var bundledSegments: List<ChatSegmentExport> = emptyList()

        if (includeMemories) {
            // L3 & L2
            val coreConfigured = memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
            val episodes = memoryRepository.getEpisodeEntitiesOfAssistant(assistant.id.toString())
            val episodicConfigured = episodes.map {
                AssistantMemory(
                    id = -it.id,
                    content = it.content,
                    type = 1, // EPISODIC
                    hasEmbedding = it.embedding != null,
                    embeddingModelId = it.embeddingModelId,
                    timestamp = it.startTime,
                    significance = it.significance
                )
            }
            bundledMemories = coreConfigured + episodicConfigured

            // L1 Segments
            bundledSegments = chatSegmentDAO.getSegmentsByAssistant(assistant.id.toString()).map {
                ChatSegmentExport(
                    conversationId = it.conversationId,
                    content = it.content,
                    keywords = it.keywords,
                    startMessageIndex = it.startMessageIndex,
                    endMessageIndex = it.endMessageIndex,
                    timestamp = it.timestamp
                )
            }
        }

        val export = AssistantExportV1(
            assistant = assistant,
            avatarContent = avatarContent,
            avatarMimeType = avatarMime,
            lorebooks = bundledLorebooks,
            memories = bundledMemories,
            segments = bundledSegments
        )

        return json.encodeToString(AssistantExportV1.serializer(), export)
    }

    /**
     * Import an Assistant from Evolia Bundle JSON format.
     */
    suspend fun importFromEvoliaBundle(jsonContent: String, context: Context): Assistant {
        val export = json.decodeFromString<AssistantExportV1>(jsonContent)
        return finalizeEvoliaImport(export, context, true, true)
    }

    /**
     * Finalize the import from a parsed ExportV1 object.
     */
    suspend fun finalizeEvoliaImport(
        export: AssistantExportV1,
        context: Context,
        importMemories: Boolean,
        importLorebooks: Boolean
    ): Assistant {
        var assistant = export.assistant.copy(id = Uuid.random())

        // 1. Restore Avatar
        if (export.avatarContent != null && assistant.avatar is Avatar.Image) {
            val fileName = "avatar_${assistant.id}_${System.currentTimeMillis()}.png"
            val file = File(context.filesDir, "avatars/$fileName")
            file.parentFile?.mkdirs()
            try {
                val bytes = Base64.decode(export.avatarContent, Base64.NO_WRAP)
                file.writeBytes(bytes)
                assistant = assistant.copy(avatar = Avatar.Image(url = Uri.fromFile(file).toString()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Restore Lorebooks
        val newLorebookIds = mutableSetOf<Uuid>()
        val importedLorebooks = mutableListOf<Lorebook>()

        if (importLorebooks) {
            export.lorebooks.forEach { lbExport ->
                var lorebook = lbExport.lorebook.copy(id = Uuid.random())
                val entryAttachments = lbExport.entryAttachments

                val newEntries = lorebook.entries.map { entry ->
                    val attachments = entryAttachments[entry.id.toString()] ?: emptyList()
                    val restoredAttachments = attachments.map { att ->
                        try {
                            val fileName = "lb_${lorebook.id}_${System.currentTimeMillis()}_${att.fileName}"
                            val file = File(context.filesDir, "lorebook_attachments/$fileName")
                            file.parentFile?.mkdirs()
                            file.writeBytes(Base64.decode(att.content, Base64.NO_WRAP))
                            ModeAttachment(
                                url = Uri.fromFile(file).toString(),
                                type = att.type,
                                fileName = att.fileName,
                                mime = att.mime
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }.filterNotNull()
                    entry.copy(attachments = restoredAttachments)
                }
                lorebook = lorebook.copy(entries = newEntries)

                importedLorebooks.add(lorebook)
                newLorebookIds.add(lorebook.id)
            }

            if (importedLorebooks.isNotEmpty()) {
                val current = settingsStore.settingsFlow.value
                settingsStore.update(current.copy(lorebooks = current.lorebooks + importedLorebooks))
            }

            if (newLorebookIds.isNotEmpty()) {
                assistant = assistant.copy(enabledLorebookIds = newLorebookIds)
            }
        } else {
            assistant = assistant.copy(enabledLorebookIds = emptySet())
        }

        // 3. Restore Memories (L1-L3)
        if (importMemories) {
            // Restore L3 & L2
            export.memories.forEach { memory ->
                if (memory.type == 0) { // Core
                    memoryRepository.addMemory(
                        assistantId = assistant.id.toString(),
                        content = memory.content
                    )
                } else if (memory.type == 1) { // Episodic
                    val entity = me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity(
                        id = 0,
                        assistantId = assistant.id.toString(),
                        startTime = memory.timestamp,
                        endTime = memory.timestamp,
                        content = memory.content,
                        lastAccessedAt = System.currentTimeMillis(),
                        significance = memory.significance ?: 5,
                        embedding = null,
                        embeddingModelId = null
                    )
                    chatEpisodeDAO.insertEpisode(entity)
                }
            }

            // Restore L1 Segments
            export.segments.forEach { seg ->
                chatSegmentDAO.insertSegment(
                    ChatSegmentEntity(
                        assistantId = assistant.id.toString(),
                        conversationId = seg.conversationId,
                        content = seg.content,
                        keywords = seg.keywords,
                        startMessageIndex = seg.startMessageIndex,
                        endMessageIndex = seg.endMessageIndex,
                        timestamp = seg.timestamp,
                        embedding = null
                    )
                )
            }
        }

        return assistant
    }

    /**
     * Export an Assistant to Character Card V2 JSON format.
     */
    suspend fun exportToCharacterCardV2(assistant: Assistant, context: Context): String {
        val allLorebooks = settingsStore.settingsFlow.value.lorebooks
        val enabledLorebooks = assistant.enabledLorebookIds.mapNotNull { id ->
            allLorebooks.find { it.id == id }
        }

        val mergedTavernEntries = mutableListOf<TavernCharacterBookEntry>()
        enabledLorebooks.forEach { lb ->
            mergedTavernEntries.addAll(lb.toTavernCharacterBook().entries)
        }

        val characterBook = if (mergedTavernEntries.isNotEmpty()) {
            TavernCharacterBook(
                name = "Bundled Lore",
                description = "Merged lorebooks from Evolia",
                entries = mergedTavernEntries
            )
        } else {
            null
        }

        val card = CharacterCardV2(
            data = CharacterCardV2Data(
                name = assistant.name,
                description = "",
                personality = assistant.systemPrompt,
                firstMes = assistant.presetMessages.firstOrNull()?.toContentText() ?: "",
                mesExample = "",
                systemPrompt = assistant.systemPrompt,
                characterBook = characterBook,
                tags = assistant.tags.map { it.toString() }
            )
        )

        return json.encodeToString(CharacterCardV2.serializer(), card)
    }

    private fun readUriBytes(context: Context, url: String): ByteArray? {
        val uri = Uri.parse(url)
        return when {
            url.startsWith("file://") -> {
                val file = File(uri.path ?: return null)
                if (file.exists()) file.readBytes() else null
            }

            url.startsWith("content://") -> {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }

            url.startsWith("/") -> {
                val file = File(url)
                if (file.exists()) file.readBytes() else null
            }

            url.startsWith("http://") || url.startsWith("https://") -> {
                // Network URL - skip for now (would need async loading)
                null
            }

            else -> null
        }
    }

    private fun embedAttachment(
        context: Context,
        url: String,
        typeName: String,
        fileName: String,
        mime: String
    ): EmbeddedAttachment? {
        val bytes = readUriBytes(context, url) ?: return null
        return EmbeddedAttachment(
            type = ModeAttachmentType.valueOf(typeName),
            fileName = fileName,
            mime = mime,
            content = Base64.encodeToString(bytes, Base64.NO_WRAP)
        )
    }

    fun getSuggestedFileName(assistant: Assistant, format: String): String {
        val baseName = assistant.name.ifEmpty { "character" }
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(50)
        return when (format) {
            "card_v2" -> "${baseName}_card_v2.json"
            "card_v2_png" -> "${baseName}_card.png"
            else -> "${baseName}_Evolia.json"
        }
    }

    /**
     * Export to Character Card V2 PNG format.
     */
    suspend fun exportToCharacterCardPng(assistant: Assistant, context: Context): ByteArray? {
        val avatarBytes: ByteArray = when (val avatar = assistant.avatar) {
            is Avatar.Image -> {
                val url = avatar.url
                readUriBytes(context, url) ?: createPlaceholderPng(assistant.name)
            }

            is Avatar.Resource -> {
                renderResourceToPng(context, avatar.id, assistant.name)
            }

            is Avatar.Emoji -> {
                createEmojiPng(avatar.content)
            }

            Avatar.Dummy -> {
                createPlaceholderPng(assistant.name)
            }
        }

        val cardJson = exportToCharacterCardV2(assistant, context)
        val base64Data = Base64.encodeToString(cardJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return embedTextChunkInPng(avatarBytes, "chara", base64Data)
    }

    private fun createPlaceholderPng(name: String): ByteArray {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val bgPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.rgb(100, 100, 150)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)
        val initial = name.firstOrNull()?.uppercaseChar() ?: 'C'
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = size * 0.5f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface =
                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(initial.toString(), 0, 1, textBounds)
        val yPos = (size / 2f) + (textBounds.height() / 2f)
        canvas.drawText(initial.toString(), size / 2f, yPos, textPaint)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun renderResourceToPng(context: Context, resourceId: Int, fallbackName: String): ByteArray {
        try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, resourceId)
            if (drawable != null) {
                val size = 512
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                return stream.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return createPlaceholderPng(fallbackName)
    }

    private fun createEmojiPng(emoji: String): ByteArray {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val bgPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.rgb(60, 60, 80)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)
        val textPaint = android.graphics.Paint().apply {
            textSize = size * 0.6f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(emoji, 0, emoji.length, textBounds)
        val yPos = (size / 2f) + (textBounds.height() / 2f)
        canvas.drawText(emoji, size / 2f, yPos, textPaint)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun embedTextChunkInPng(pngBytes: ByteArray, keyword: String, text: String): ByteArray? {
        if (pngBytes.size < 8 || !pngBytes.take(8).toByteArray().contentEquals(PNG_HEADER)) {
            return null
        }
        var offset = 8
        var iendPosition = -1
        while (offset < pngBytes.size) {
            if (offset + 8 > pngBytes.size) break
            val length = ((pngBytes[offset].toInt() and 0xFF) shl 24) or
                ((pngBytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((pngBytes[offset + 2].toInt() and 0xFF) shl 8) or
                (pngBytes[offset + 3].toInt() and 0xFF)
            val type = String(pngBytes, offset + 4, 4)
            if (type == "IEND") {
                iendPosition = offset
                break
            }
            offset += 4 + 4 + length + 4
        }
        if (iendPosition == -1) return null
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val chunkData = keywordBytes + byteArrayOf(0) + textBytes
        val chunkLength = chunkData.size
        val typeBytes = "tEXt".toByteArray(Charsets.ISO_8859_1)
        val crc = java.util.zip.CRC32()
        crc.update(typeBytes)
        crc.update(chunkData)
        val crcValue = crc.value.toInt()
        val chunk = ByteArray(4 + 4 + chunkData.size + 4)
        chunk[0] = ((chunkLength shr 24) and 0xFF).toByte()
        chunk[1] = ((chunkLength shr 16) and 0xFF).toByte()
        chunk[2] = ((chunkLength shr 8) and 0xFF).toByte()
        chunk[3] = (chunkLength and 0xFF).toByte()
        System.arraycopy(typeBytes, 0, chunk, 4, 4)
        System.arraycopy(chunkData, 0, chunk, 8, chunkData.size)
        chunk[chunk.size - 4] = ((crcValue shr 24) and 0xFF).toByte()
        chunk[chunk.size - 3] = ((crcValue shr 16) and 0xFF).toByte()
        chunk[chunk.size - 2] = ((crcValue shr 8) and 0xFF).toByte()
        chunk[chunk.size - 1] = (crcValue and 0xFF).toByte()
        val beforeIend = pngBytes.copyOfRange(0, iendPosition)
        val iendChunk = pngBytes.copyOfRange(iendPosition, pngBytes.size)
        return beforeIend + chunk + iendChunk
    }

    @Serializable
    sealed class ImportResult {
        data class Success(val assistant: Assistant) : ImportResult()
        data class Configurable(
            val assistant: Assistant,
            val exportV1: AssistantExportV1?,
            val hasMemories: Boolean,
            val hasLorebooks: Boolean,
            val missingModels: List<String>
        ) : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    suspend fun parseImport(uri: Uri, context: Context): ImportResult {
        return try {
            val contentBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return ImportResult.Error("Failed to read file")
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val isPng = mimeType == "image/png" || contentBytes.take(8).toByteArray().contentEquals(PNG_HEADER)
            var jsonContent: String? = null
            var avatarBytes: ByteArray? = null
            if (isPng) {
                val chunks = extractPngChunks(contentBytes)
                val characterData = chunks["chara"] ?: chunks["ccv3"]
                if (characterData != null) {
                    jsonContent = String(Base64.decode(characterData, Base64.NO_WRAP))
                    avatarBytes = contentBytes
                } else {
                    return ImportResult.Error("No character data found in PNG")
                }
            } else {
                jsonContent = String(contentBytes)
            }
            if (jsonContent == null) return ImportResult.Error("Unknown file format")
            try {
                if (jsonContent.contains("\"format\": \"Evolia_assistant\"") || jsonContent.contains("\"format\":\"Evolia_assistant\"") ||
                    jsonContent.contains("\"format\": \"lastchat_assistant\"") || jsonContent.contains("\"format\":\"lastchat_assistant\"")
                ) {
                    val export = json.decodeFromString<AssistantExportV1>(jsonContent)
                    return ImportResult.Configurable(
                        assistant = export.assistant,
                        exportV1 = export,
                        hasMemories = export.memories.isNotEmpty() || export.segments.isNotEmpty(),
                        hasLorebooks = export.lorebooks.isNotEmpty(),
                        missingModels = checkMissingModels(export.assistant)
                    )
                } else {
                    val assistant = parseCharacterCard(jsonContent, avatarBytes, context)
                    if (assistant != null) {
                        return ImportResult.Configurable(
                            assistant = assistant,
                            exportV1 = null,
                            hasMemories = false,
                            hasLorebooks = false,
                            missingModels = checkMissingModels(assistant)
                        )
                    } else {
                        return ImportResult.Error("Unsupported JSON format")
                    }
                }
            } catch (e: Exception) {
                return ImportResult.Error("JSON Parse Error: ${e.message}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ImportResult.Error(e.message ?: "Unknown Parsing Error")
        }
    }

    private fun checkMissingModels(assistant: Assistant): List<String> {
        val settings = settingsStore.settingsFlow.value
        val missing = mutableListOf<String>()
        fun check(id: Uuid?, name: String) {
            if (id != null) {
                val exists = settings.findModelById(id) != null
                if (!exists) missing.add(name)
            }
        }
        check(assistant.chatModelId, "Chat Model")
        check(assistant.backgroundModelId, "Background Model")
        check(assistant.embeddingModelId, "Embedding Model")
        check(assistant.summarizerModelId, "Summarizer Model")
        check(assistant.memoryModelId, "Memory Model")
        check(assistant.diaryModelId, "Diary Model")
        check(assistant.suggestionModelId, "Suggestion Model")
        return missing
    }

    // Function to clear missing models from assistant
    fun clearMissingModels(assistant: Assistant): Assistant {
        val settings = settingsStore.settingsFlow.value

        fun checkAndClear(id: Uuid?): Uuid? {
            if (id != null) {
                val exists = settings.findModelById(id) != null
                return if (exists) id else null
            }
            return null
        }

        return assistant.copy(
            chatModelId = checkAndClear(assistant.chatModelId),
            backgroundModelId = checkAndClear(assistant.backgroundModelId),
            embeddingModelId = checkAndClear(assistant.embeddingModelId),
            summarizerModelId = checkAndClear(assistant.summarizerModelId),
            memoryModelId = checkAndClear(assistant.memoryModelId),
            diaryModelId = checkAndClear(assistant.diaryModelId),
            suggestionModelId = checkAndClear(assistant.suggestionModelId)
        )
    }

    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(),
        0x50.toByte(),
        0x4E.toByte(),
        0x47.toByte(),
        0x0D.toByte(),
        0x0A.toByte(),
        0x1A.toByte(),
        0x0A.toByte()
    )

    private fun extractPngChunks(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 8
        while (offset < bytes.size) {
            if (offset + 8 > bytes.size) break
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            val type = String(bytes, offset, 4)
            offset += 4
            if (offset + length > bytes.size) break
            val data = bytes.copyOfRange(offset, offset + length)
            offset += length
            offset += 4
            when (type) {
                "tEXt" -> {
                    val separator = data.indexOf(0.toByte())
                    if (separator > 0) {
                        val keyword = String(data, 0, separator, Charsets.ISO_8859_1)
                        val text = String(data, separator + 1, data.size - separator - 1, Charsets.ISO_8859_1)
                        result[keyword] = text
                    }
                }

                "zTXt" -> {
                    val separator = data.indexOf(0.toByte())
                    if (separator > 0 && separator + 1 < data.size) {
                        val keyword = String(data, 0, separator, Charsets.ISO_8859_1)
                        val compressionMethod = data[separator + 1].toInt() and 0xFF
                        if (compressionMethod == 0) {
                            try {
                                val compressedData = data.copyOfRange(separator + 2, data.size)
                                val inflater = Inflater()
                                inflater.setInput(compressedData)
                                val outputStream = ByteArrayOutputStream()
                                val buffer = ByteArray(1024)
                                while (!inflater.finished()) {
                                    val count = inflater.inflate(buffer)
                                    if (count == 0 && inflater.needsInput()) break
                                    outputStream.write(buffer, 0, count)
                                }
                                inflater.end()
                                result[keyword] = outputStream.toString(Charsets.ISO_8859_1.name())
                            } catch (e: Exception) {
                                // Skip malformed zTXt chunks
                            }
                        }
                    }
                }

                "iTXt" -> {
                    // iTXt: Keyword + Null + CompressionFlag + CompressionMethod + LanguageTag + Null + TranslatedKeyword + Null + Text
                    val separator = data.indexOf(0.toByte())
                    if (separator > 0 && separator + 3 < data.size) {
                        val keyword = String(data, 0, separator, Charsets.UTF_8)
                        val compressionFlag = data[separator + 1].toInt() and 0xFF
                        val compressionMethod = data[separator + 2].toInt() and 0xFF

                        // Find text start (skip language tag and translated keyword)
                        var textStart = separator + 3
                        // Skip language tag
                        while (textStart < data.size && data[textStart] != 0.toByte()) textStart++
                        textStart++ // Skip null
                        // Skip translated keyword
                        while (textStart < data.size && data[textStart] != 0.toByte()) textStart++
                        textStart++ // Skip null

                        if (textStart < data.size) {
                            val textData = data.copyOfRange(textStart, data.size)
                            val text = if (compressionFlag == 1 && compressionMethod == 0) {
                                try {
                                    val inflater = Inflater()
                                    inflater.setInput(textData)
                                    val outputStream = ByteArrayOutputStream()
                                    val buffer = ByteArray(1024)
                                    while (!inflater.finished()) {
                                        val count = inflater.inflate(buffer)
                                        if (count == 0 && inflater.needsInput()) break
                                        outputStream.write(buffer, 0, count)
                                    }
                                    inflater.end()
                                    outputStream.toString(Charsets.UTF_8.name())
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                String(textData, Charsets.UTF_8)
                            }
                            if (text != null) {
                                result[keyword] = text
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun parseCharacterCard(jsonContent: String, avatarBytes: ByteArray?, context: Context): Assistant? {
        return try {
            val jsonElement = json.parseToJsonElement(jsonContent)
            val jsonObj = jsonElement.jsonObject

            // Check spec field for V2/V3
            val spec = jsonObj["spec"]?.jsonPrimitive?.contentOrNull
            val assistant = when {
                spec == "chara_card_v2" || spec == "chara_card_v3" -> {
                    // V2 or V3 format - parse with data model
                    val card = json.decodeFromString<CharacterCardV2>(jsonContent)
                    card.toAssistant()
                }

                jsonObj.containsKey("data") -> {
                    // Has data field but no spec - try V2 anyway
                    val card = json.decodeFromString<CharacterCardV2>(jsonContent)
                    card.toAssistant()
                }

                jsonObj.containsKey("name") || jsonObj.containsKey("char_name") -> {
                    parseV1Card(jsonObj)
                }

                else -> null
            }
            if (assistant != null && avatarBytes != null) {
                val fileName = "avatar_${assistant.id}_${System.currentTimeMillis()}.png"
                val file = File(context.filesDir, "avatars/$fileName")
                file.parentFile?.mkdirs()
                file.writeBytes(avatarBytes)
                assistant.copy(avatar = Avatar.Image(url = Uri.fromFile(file).toString()))
            } else assistant
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseV1Card(jsonObj: JsonObject): Assistant {
        val name = jsonObj["name"]?.jsonPrimitive?.contentOrNull ?: jsonObj["char_name"]?.jsonPrimitive?.contentOrNull
        ?: "Imported Character"
        val description = jsonObj["description"]?.jsonPrimitive?.contentOrNull
            ?: jsonObj["char_persona"]?.jsonPrimitive?.contentOrNull ?: ""
        val personality = jsonObj["personality"]?.jsonPrimitive?.contentOrNull ?: ""
        val scenario =
            jsonObj["scenario"]?.jsonPrimitive?.contentOrNull ?: jsonObj["world_scenario"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val first_mes =
            jsonObj["first_mes"]?.jsonPrimitive?.contentOrNull ?: jsonObj["char_greeting"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val mes_example = jsonObj["mes_example"]?.jsonPrimitive?.contentOrNull
            ?: jsonObj["example_dialogue"]?.jsonPrimitive?.contentOrNull ?: ""

        val systemPromptBuilder = StringBuilder().apply {
            if (description.isNotBlank()) append("Description:\n$description\n\n")
            if (personality.isNotBlank()) append("Personality:\n$personality\n\n")
            if (scenario.isNotBlank()) append("Scenario:\n$scenario\n\n")
            if (mes_example.isNotBlank()) append("Examples:\n$mes_example\n\n")
        }

        val presetMessages = if (first_mes.isNotBlank()) {
            listOf(
                me.rerere.ai.ui.UIMessage(
                    role = me.rerere.ai.core.MessageRole.ASSISTANT,
                    parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(text = first_mes))
                )
            )
        } else emptyList()

        return Assistant(
            name = name,
            systemPrompt = systemPromptBuilder.toString().trim(),
            presetMessages = presetMessages
        )
    }

    private fun CharacterCardV2.toAssistant(): Assistant {
        val data = this.data
        val systemPromptBuilder = StringBuilder().apply {
            if (data.description.isNotBlank()) append("Description:\n${data.description}\n\n")
            if (data.personality.isNotBlank()) append("Personality:\n${data.personality}\n\n")
            if (data.scenario.isNotBlank()) append("Scenario:\n${data.scenario}\n\n")
            if (data.systemPrompt.isNotBlank()) append("System:\n${data.systemPrompt}\n\n")
            if (data.mesExample.isNotBlank()) append("Examples:\n${data.mesExample}\n\n")
        }
        val presetMessages = if (data.firstMes.isNotBlank()) {
            listOf(
                me.rerere.ai.ui.UIMessage(
                    role = me.rerere.ai.core.MessageRole.ASSISTANT,
                    parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(text = data.firstMes))
                )
            )
        } else emptyList()
        return Assistant(
            name = data.name.ifBlank { "Imported Character" },
            systemPrompt = systemPromptBuilder.toString().trim(),
            presetMessages = presetMessages
        )
    }
}
