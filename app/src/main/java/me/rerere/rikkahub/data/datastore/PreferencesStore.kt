package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.core.data.model.Lorebook
import me.rerere.rikkahub.core.data.model.Mode
import me.rerere.rikkahub.core.data.model.Tag
import me.rerere.rikkahub.core.data.model.TextSelectionConfig
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_DIARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

private const val TAG = "PreferencesStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { _ ->
        listOf(
            PreferenceStoreV1Migration()
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
    private val quickCache: QuickSettingsCache,
    private val secretKeyManager: SecretKeyManager,
) : KoinComponent {
    companion object {
        val VERSION = intPreferencesKey("data_version")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val ENABLE_RAG_LOGGING = booleanPreferencesKey("enable_rag_logging")
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val BACKGROUND_MODEL = stringPreferencesKey("background_model")
        val SUMMARIZER_MODEL = stringPreferencesKey("summarizer_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val LEARNING_MODE_PROMPT = stringPreferencesKey("learning_mode_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")
        val MEMORY_MODEL = stringPreferencesKey("memory_model")
        val DIARY_MODEL = stringPreferencesKey("diary_model")
        val DIARY_PROMPT = stringPreferencesKey("diary_prompt")
        val PROVIDERS = stringPreferencesKey("providers")
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        val PROVIDER_TAGS = stringPreferencesKey("provider_tags")
        val RECENTLY_USED_ASSISTANTS = stringPreferencesKey("recently_used_assistants")
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")
        val EMAIL_CONFIG = stringPreferencesKey("email_config")
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val CONSOLIDATION_WORKER_INTERVAL = intPreferencesKey("consolidation_worker_interval")
        val CONSOLIDATION_REQUIRES_DEVICE_IDLE = booleanPreferencesKey("consolidation_requires_device_idle")
        val MODES = stringPreferencesKey("modes")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val TEXT_SELECTION_CONFIG = stringPreferencesKey("text_selection_config")
        val AUTO_BACKUP_ON_START = booleanPreferencesKey("auto_backup_on_start")
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            Settings(
                enableWebSearch = preferences[ENABLE_WEB_SEARCH] == true,
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                backgroundModelId = preferences[BACKGROUND_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                summarizerModelId = preferences[SUMMARIZER_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                learningModePrompt = preferences[LEARNING_MODE_PROMPT] ?: DEFAULT_LEARNING_MODE_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                embeddingModelId = preferences[EMBEDDING_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                memoryModelId = preferences[MEMORY_MODEL]?.let { Uuid.parse(it) } ?: GEMINI_2_5_FLASH_ID,
                diaryModelId = preferences[DIARY_MODEL]?.let { Uuid.parse(it) } ?: GEMINI_2_5_FLASH_ID,
                diaryPrompt = preferences[DIARY_PROMPT] ?: DEFAULT_DIARY_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providerTags = preferences[PROVIDER_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                recentlyUsedAssistants = preferences[RECENTLY_USED_ASSISTANTS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                developerMode = preferences[DEVELOPER_MODE] == true,
                enableRagLogging = preferences[ENABLE_RAG_LOGGING] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                emailConfig = preferences[EMAIL_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: EmailConfig(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                consolidationWorkerIntervalMinutes = preferences[CONSOLIDATION_WORKER_INTERVAL] ?: 15,
                consolidationRequiresDeviceIdle = preferences[CONSOLIDATION_REQUIRES_DEVICE_IDLE] ?: false,
                modes = preferences[MODES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                textSelectionConfig = preferences[TEXT_SELECTION_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: TextSelectionConfig(),
                autoBackupOnStart = preferences[AUTO_BACKUP_ON_START] ?: false,
            )
        }
        .map {
            var providers = it.providers.ifEmpty { me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS }.toMutableList()
            providers = providers.map { provider ->
                val defaultProvider = me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders
            )
        }
        .map { settings ->
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(models = provider.models.distinctBy { it.id })
                        is ProviderSetting.Google -> provider.copy(models = provider.models.distinctBy { it.id })
                        is ProviderSetting.Claude -> provider.copy(models = provider.models.distinctBy { it.id })
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(mcpServers = assistant.mcpServers.filter { it in validMcpServerIds }.toSet())
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                }
            )
        }
        .onEach { get<PebbleEngine>().templateCache.invalidateAll() }
        .flowOn(Dispatchers.Default)

    private var hasMigratedSecrets = false

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .map { settings ->
            if (!hasMigratedSecrets && !settings.init) {
                hasMigratedSecrets = true
                val migratedSettings = secretKeyManager.migrateSecretsFromSettings(settings)
                if (migratedSettings != settings) {
                    scope.launch { persistMigratedSettings(migratedSettings) }
                }
                migratedSettings
            } else settings
        }
        .map { secretKeyManager.populateSecretsForExport(it) }
        .onEach { quickCache.updateCache(it) }
        .toMutableStateFlow(scope, quickCache.createCachedSettings())

    private suspend fun persistMigratedSettings(settings: Settings) {
        dataStore.edit { preferences ->
            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
        }
    }

    suspend fun update(settings: Settings) {
        if(settings.init) return
        val settingsToSave = if (settings.assistantId != settingsFlow.value.assistantId && !settingsFlow.value.init && settings.assistants.any { it.id == settings.assistantId }) {
            settings.copy(recentlyUsedAssistants = buildList {
                add(settings.assistantId)
                settings.recentlyUsedAssistants.filter { it != settings.assistantId }.take(2).forEach { add(it) }
            })
        } else settings
        secretKeyManager.handleExplicitSecretDeletions(settingsFlow.value, settingsToSave)
        val migratedSettings = secretKeyManager.migrateSecretsFromSettings(settingsToSave)
        settingsFlow.value = secretKeyManager.populateSecretsForExport(migratedSettings)
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settingsToSave.dynamicColor
            preferences[THEME_ID] = settingsToSave.themeId
            preferences[DEVELOPER_MODE] = settingsToSave.developerMode
            preferences[ENABLE_RAG_LOGGING] = settingsToSave.enableRagLogging
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settingsToSave.displaySetting)
            preferences[ENABLE_WEB_SEARCH] = settingsToSave.enableWebSearch
            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settingsToSave.favoriteModels)
            preferences[SELECT_MODEL] = settingsToSave.chatModelId.toString()
            preferences[BACKGROUND_MODEL] = settingsToSave.backgroundModelId.toString()
            preferences[SUMMARIZER_MODEL] = settingsToSave.summarizerModelId.toString()
            preferences[TITLE_MODEL] = settingsToSave.titleModelId.toString()
            preferences[TRANSLATE_MODEL] = settingsToSave.translateModeId.toString()
            preferences[SUGGESTION_MODEL] = settingsToSave.suggestionModelId.toString()
            preferences[IMAGE_GENERATION_MODEL] = settingsToSave.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settingsToSave.titlePrompt
            preferences[TRANSLATION_PROMPT] = settingsToSave.translatePrompt
            preferences[SUGGESTION_PROMPT] = settingsToSave.suggestionPrompt
            preferences[LEARNING_MODE_PROMPT] = settingsToSave.learningModePrompt
            preferences[OCR_MODEL] = settingsToSave.ocrModelId.toString()
            preferences[OCR_PROMPT] = settingsToSave.ocrPrompt
            preferences[EMBEDDING_MODEL] = settingsToSave.embeddingModelId.toString()
            preferences[MEMORY_MODEL] = settingsToSave.memoryModelId.toString()
            preferences[DIARY_MODEL] = settingsToSave.diaryModelId.toString()
            preferences[DIARY_PROMPT] = settingsToSave.diaryPrompt
            preferences[PROVIDERS] = JsonInstant.encodeToString(migratedSettings.providers)
            preferences[ASSISTANTS] = JsonInstant.encodeToString(settingsToSave.assistants)
            preferences[SELECT_ASSISTANT] = settingsToSave.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settingsToSave.assistantTags)
            preferences[PROVIDER_TAGS] = JsonInstant.encodeToString(settingsToSave.providerTags)
            preferences[RECENTLY_USED_ASSISTANTS] = JsonInstant.encodeToString(settingsToSave.recentlyUsedAssistants)
            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settingsToSave.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settingsToSave.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settingsToSave.searchServiceSelected.coerceIn(0, settingsToSave.searchServices.size - 1)
            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settingsToSave.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(migratedSettings.webDavConfig)
            preferences[EMAIL_CONFIG] = JsonInstant.encodeToString(migratedSettings.emailConfig)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(migratedSettings.ttsProviders)
            settingsToSave.selectedTTSProviderId.let { preferences[SELECTED_TTS_PROVIDER] = it.toString() }
            preferences[CONSOLIDATION_WORKER_INTERVAL] = settingsToSave.consolidationWorkerIntervalMinutes
            preferences[CONSOLIDATION_REQUIRES_DEVICE_IDLE] = settingsToSave.consolidationRequiresDeviceIdle
            preferences[MODES] = JsonInstant.encodeToString(settingsToSave.modes)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settingsToSave.lorebooks)
            preferences[TEXT_SELECTION_CONFIG] = JsonInstant.encodeToString(settingsToSave.textSelectionConfig)
            preferences[AUTO_BACKUP_ON_START] = settingsToSave.autoBackupOnStart
        }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        val current = settingsFlow.value
        if (!current.init && current.assistants.any { it.id == assistantId }) {
            settingsFlow.value = current.copy(assistantId = assistantId, recentlyUsedAssistants = buildList {
                add(assistantId)
                current.recentlyUsedAssistants.filter { it != assistantId }.take(2).forEach { add(it) }
            })
        }
        dataStore.edit { it[SELECT_ASSISTANT] = assistantId.toString() }
    }

    suspend fun markAssistantUsed(assistantId: Uuid) {
        val current = settingsFlow.value
        val newList = buildList {
            add(assistantId)
            current.recentlyUsedAssistants.filter { it != assistantId }.take(2).forEach { add(it) }
        }
        dataStore.edit { preferences ->
            preferences[RECENTLY_USED_ASSISTANTS] = JsonInstant.encodeToString(newList)
        }
    }
}

@Serializable
data class Settings(
    @kotlinx.serialization.Transient val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val developerMode: Boolean = false,
    val enableRagLogging: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val backgroundModelId: Uuid = Uuid.random(),
    val summarizerModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val suggestionModelId: Uuid = Uuid.random(),
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val learningModePrompt: String = DEFAULT_LEARNING_MODE_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val embeddingModelId: Uuid = Uuid.random(),
    val memoryModelId: Uuid = Uuid.random(),
    val diaryModelId: Uuid = Uuid.random(),
    val diaryPrompt: String = DEFAULT_DIARY_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = emptyList(),
    val assistants: List<Assistant> = emptyList(),
    val assistantTags: List<Tag> = emptyList(),
    val providerTags: List<Tag> = emptyList(),
    val recentlyUsedAssistants: List<Uuid> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val emailConfig: EmailConfig = EmailConfig(),
    val ttsProviders: List<TTSProviderSetting> = emptyList(),
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val consolidationWorkerIntervalMinutes: Int = 15,
    val consolidationRequiresDeviceIdle: Boolean = false,
    val modes: List<Mode> = emptyList(),
    val lorebooks: List<Lorebook> = emptyList(),
    val textSelectionConfig: TextSelectionConfig = TextSelectionConfig(),
    val autoBackupOnStart: Boolean = false,
) { companion object { fun dummy() = Settings(init = true) } }

@Serializable data class RpStyleRule(val id: String = Uuid.random().toString(), val pattern: String = "*", val colorHex: String = "#808080", val enabled: Boolean = true)
@Serializable data class TtsTextFilterRule(val id: String = Uuid.random().toString(), val pattern: String = "*", val mode: TtsFilterMode = TtsFilterMode.SKIP, val enabled: Boolean = true)
@Serializable enum class TtsFilterMode { SKIP, ONLY_READ }
@Serializable enum class FontSource { System, SystemCode, Custom }
@Serializable data class FontAxis(val tag: String, val name: String, val minValue: Float, val maxValue: Float, val defaultValue: Float, val currentValue: Float = defaultValue)
@Serializable data class FontFeature(val tag: String, val name: String, val enabled: Boolean = true)
@Serializable data class FontConfig(val fontSource: FontSource = FontSource.System, val customFontPath: String? = null, val customFontName: String? = null, val weight: Float = 400f, val width: Float = 100f, val roundness: Float = 100f, val grade: Float = 0f, val slant: Float = 0f, val fontSize: Float = 1.0f, val lineHeight: Float = 1.0f, val letterSpacing: Float = 0f, val customAxes: List<FontAxis> = emptyList(), val features: List<FontFeature> = emptyList()) {
    companion object { val DEFAULT_EXPRESSIVE = FontConfig(fontSource = FontSource.System, roundness = 100f); val DEFAULT_NORMAL = FontConfig(fontSource = FontSource.System, roundness = 0f); val DEFAULT_CODE = FontConfig(fontSource = FontSource.SystemCode, roundness = 0f, weight = 400f) }
}
@Serializable data class FontSettings(val useSameFontForHeadersAndContent: Boolean = false, val headerFont: FontConfig = FontConfig.DEFAULT_EXPRESSIVE, val contentFont: FontConfig = FontConfig.DEFAULT_EXPRESSIVE, val codeFont: FontConfig = FontConfig.DEFAULT_CODE)
@Serializable data class DisplaySetting(val userAvatar: Avatar = Avatar.Dummy, val userNickname: String = "", val chatInputStyle: me.rerere.rikkahub.data.datastore.ChatInputStyle = me.rerere.rikkahub.data.datastore.ChatInputStyle.MINIMAL, val showUserAvatar: Boolean = true, val showModelIcon: Boolean = true, val showModelName: Boolean = true, val showAssistantBubbles: Boolean = true, val showTokenUsage: Boolean = false, val autoCloseThinking: Boolean = true, val showUpdates: Boolean = false, val checkForUpdates: Boolean = true, val showMessageJumper: Boolean = false, val messageJumperOnLeft: Boolean = false, val fontSizeRatio: Float = 1.0f, val fontSettings: FontSettings = FontSettings(), val enableMessageGenerationHapticEffect: Boolean = false, val enableUIHaptics: Boolean = true, val skipCropImage: Boolean = false, val enableNotificationOnMessageGeneration: Boolean = false, val codeBlockAutoWrap: Boolean = false, val codeBlockAutoCollapse: Boolean = true, val rpStyleRules: List<RpStyleRule> = emptyList(), val ttsTextFilterRules: List<TtsTextFilterRule> = emptyList(), val providerViewMode: ProviderViewMode = ProviderViewMode.LIST, val showContextStacks: Boolean = false, val newChatHeaderStyle: me.rerere.rikkahub.data.datastore.NewChatHeaderStyle = me.rerere.rikkahub.data.datastore.NewChatHeaderStyle.GREETING, val newChatContentStyle: me.rerere.rikkahub.data.datastore.NewChatContentStyle = me.rerere.rikkahub.data.datastore.NewChatContentStyle.ACTIONS, val newChatShowAvatar: Boolean = true)
@Serializable enum class NewChatHeaderStyle { NONE, GREETING, BIG_ICON }
@Serializable enum class NewChatContentStyle { NONE, TEMPLATES, STATS, ACTIONS }
@Serializable enum class ProviderViewMode { LIST, GRID }
@Serializable enum class ChatInputStyle { FLOATING, MINIMAL }
@Serializable data class WebDavConfig(val url: String = "", val username: String = "", val password: String = "", val path: String = "evolia_backups", val items: List<BackupItem> = listOf(BackupItem.DATABASE, BackupItem.FILES), val maxBackupFiles: Int = 3) { @Serializable enum class BackupItem { DATABASE, FILES} }
@Serializable data class EmailConfig(val account: String = "", val password: String = "", val enabled: Boolean = false)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }
fun Settings.findModelById(uuid: Uuid): Model? = this.providers.findModelById(uuid)
fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? { forEach { s -> s.models.forEach { if (it.id == uuid) return it } }; return null }
fun Settings.getCurrentChatModel(): Model? = findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
fun Settings.getCurrentAssistant(): Assistant = assistants.find { it.id == assistantId } ?: assistants.firstOrNull() ?: DEFAULT_ASSISTANTS.first()
fun Settings.getAssistantById(id: Uuid): Assistant? = assistants.find { it.id == id }
fun Settings.getEffectiveDisplaySetting(assistant: Assistant? = null): DisplaySetting {
    val ui = (assistant ?: getCurrentAssistant()).uiSettings
    return displaySetting.copy(
        chatInputStyle = ui.chatInputStyle?.let { runCatching { ChatInputStyle.valueOf(it) }.getOrNull() } ?: displaySetting.chatInputStyle,
        showUserAvatar = ui.showUserAvatar ?: displaySetting.showUserAvatar,
        showModelIcon = ui.showAssistantAvatar ?: displaySetting.showModelIcon,
        showAssistantBubbles = ui.showAssistantBubbles ?: displaySetting.showAssistantBubbles,
        showTokenUsage = ui.showTokenUsage ?: displaySetting.showTokenUsage,
        autoCloseThinking = ui.autoCloseThinking ?: displaySetting.autoCloseThinking,
        showMessageJumper = ui.showMessageJumper ?: displaySetting.showMessageJumper,
        messageJumperOnLeft = ui.messageJumperOnLeft ?: displaySetting.messageJumperOnLeft,
        fontSizeRatio = ui.fontSizeRatio ?: displaySetting.fontSizeRatio,
        codeBlockAutoWrap = ui.codeBlockAutoWrap ?: displaySetting.codeBlockAutoWrap,
        codeBlockAutoCollapse = ui.codeBlockAutoCollapse ?: displaySetting.codeBlockAutoCollapse,
        showContextStacks = ui.showContextStacks ?: displaySetting.showContextStacks,
        newChatHeaderStyle = ui.newChatHeaderStyle?.let { runCatching { NewChatHeaderStyle.valueOf(it) }.getOrNull() } ?: displaySetting.newChatHeaderStyle,
        newChatContentStyle = ui.newChatContentStyle?.let { runCatching { NewChatContentStyle.valueOf(it) }.getOrNull() } ?: displaySetting.newChatContentStyle,
        newChatShowAvatar = ui.newChatShowAvatar ?: displaySetting.newChatShowAvatar,
    )
}
fun Settings.getSelectedTTSProvider(): TTSProviderSetting? = ttsProviders.find { it.id == selectedTTSProviderId } ?: ttsProviders.firstOrNull()
fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = providers.find { p -> p.models.any { it.id == this.id } } ?: return null
    if (checkOverwrite && this.providerOverwrite != null) return this.providerOverwrite!!.copyProvider(proxy = provider.proxy, models = emptyList())
    return provider
}

internal val GEMINI_2_5_FLASH_ID = Uuid.parse("cd2cba9a-3f92-4148-b4c6-4d7a86f7b9c2")
internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(Assistant(id = DEFAULT_ASSISTANT_ID, name = "Generical", avatar = Avatar.Resource(me.rerere.rikkahub.R.drawable.default_generical_pfp), temperature = 0.6f, systemPrompt = "You are the best generic assistant."))
val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(TTSProviderSetting.SystemTTS(id = DEFAULT_SYSTEM_TTS_ID, name = ""))
