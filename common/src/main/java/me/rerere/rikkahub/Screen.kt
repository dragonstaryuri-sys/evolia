package me.rerere.rikkahub

import kotlinx.serialization.Serializable

@Serializable
sealed interface Screen {
    @Serializable
    data object Home : Screen

    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val searchQuery: String? = null,
        val targetMessageId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data object AssistantSearch : Screen

    @Serializable
    data class AssistantDetail(
        val id: String,
        val startRoute: String? = null,
        val initialMemoryTab: Int? = null,
        val scrollToMemoryId: Int? = null
    ) : Screen

    @Serializable
    data object AssistantImportDoubao : Screen

    @Serializable
    data class AssistantImportMemory(
        val assistantId: String,
        val isMain: Boolean,
        val sessionCount: Int
    ) : Screen

    @Serializable
    data class ChatHistorySearch(val assistantId: String) : Screen

    @Serializable
    data object Menu : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val content: String = "", val title: String? = null) : Screen

    @Serializable
    data class MarkdownViewer(val title: String, val content: String = "", val uri: String? = null) : Screen

    @Serializable
    data object SettingDisplay : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data object SettingTTS : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingPermissionCheck : Screen

    @Serializable
    data object SettingRpOptimizations : Screen

    @Serializable
    data object SettingPromptInjections : Screen

    @Serializable
    data class SettingModes(val scrollToModeId: String? = null) : Screen

    @Serializable
    data object SettingLorebooks : Screen

    @Serializable
    data class SettingLorebookDetail(val id: String, val scrollToEntryId: String? = null) : Screen

    @Serializable
    data object Developer : Screen

    @Serializable
    data object SettingUICustomization : Screen

    @Serializable
    data object SettingFonts : Screen

    @Serializable
    data object SettingEmail : Screen

    @Serializable
    data object SettingUserProfile : Screen

    @Serializable
    data object Discover : Screen

    @Serializable
    data class DiaryList(val assistantId: String? = null) : Screen

    @Serializable
    data class DiaryEditor(
        val diaryId: String,
        /**
         * 进入模式：
         * - "text"：直接记录（默认，正常文本编辑）
         * - "scan"：文档扫描模式（新建用户日记时，一进入就启动相册/拍照）
         */
        val entryType: String = "text"
    ) : Screen

    // ✨ 新增：日记详情页
    @Serializable
    data class DiaryDetail(val diaryId: String) : Screen

    // ✨ 新增：日记搜索页
    @Serializable
    data object DiarySearch : Screen

    // ✨ 新增：小时光（朋友圈）
    @Serializable
    data object HourLight : Screen

    @Serializable
    data object Favorites : Screen

    @Serializable
    data class FavoriteDetail(val id: Long) : Screen

    @Serializable
    data object Schedule : Screen

    @Serializable
    data object TokenReport : Screen

    @Serializable
    data object BookShelf : Screen

    @Serializable
    data class BookReader(val bookId: Int) : Screen
}
