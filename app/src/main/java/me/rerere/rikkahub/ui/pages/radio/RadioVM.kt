package me.rerere.rikkahub.ui.pages.radio

import android.app.Application
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity
import me.rerere.rikkahub.core.data.db.entity.RadioAudioStatus
import me.rerere.rikkahub.core.data.db.entity.RadioChannelEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.radio.EpisodeQuery
import me.rerere.rikkahub.data.radio.EpisodeSort
import me.rerere.rikkahub.data.radio.RadioAudioExporter
import me.rerere.rikkahub.data.radio.RadioAudioGenerator
import me.rerere.rikkahub.data.radio.RadioService
import me.rerere.rikkahub.data.radio.RadioServiceException
import me.rerere.rikkahub.service.media.MediaPlaybackController
import me.rerere.rikkahub.service.media.PlaybackSourceType

private const val TAG = "RadioVM"

/** 一次性操作提示（保存/删除/生成音频等），UI 用 Toaster 展示。 */
data class RadioMessage(val text: String, val isError: Boolean = false)

/**
 * 电台首页 VM：栏目列表 / 播放列表 / 栏目管理（UI 与 Agent 工具共用 RadioService）。
 */
class RadioHomeVM(
    private val context: Application,
    private val radioService: RadioService,
    private val mediaPlaybackController: MediaPlaybackController,
    private val settingsStore: SettingsStore
) : ViewModel() {

    val channels: StateFlow<List<RadioChannelEntity>> = radioService.observeChannels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<RadioMessage?>(null)
    val message: StateFlow<RadioMessage?> = _message.asStateFlow()

    /** 首页底部胶囊 tab。 */
    enum class RadioHomeTab { CHANNELS, LIKED, DRAFTS }

    private val _tab = MutableStateFlow(RadioHomeTab.CHANNELS)
    val tab: StateFlow<RadioHomeTab> = _tab.asStateFlow()

    fun switchTab(tab: RadioHomeTab) {
        _tab.value = tab
    }

    /** 助手 id → Assistant（节目卡片主播显示）。 */
    val assistantMap: StateFlow<Map<String, Assistant>> = settingsStore.settingsFlow
        .map { settings -> settings.assistants.associateBy { it.id.toString() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ===== 我的喜欢 =====
    val likedAscending = MutableStateFlow(false)

    val likedEpisodes: StateFlow<List<RadioEpisodeEntity>> =
        kotlinx.coroutines.flow.combine(radioService.observeLiked(), likedAscending) { list, ascending ->
            if (ascending) list.reversed() else list
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleLikedOrder() {
        likedAscending.value = !likedAscending.value
    }

    // ===== 草稿箱 =====
    val draftEpisodes: StateFlow<List<RadioEpisodeEntity>> = radioService.observeDrafts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ===== 全部节目（栏目 tab 下方，分页加载）=====
    private val allPageSize = 20
    private var allPage = 0
    private var allExhausted = false
    private val _allEpisodes = MutableStateFlow<List<RadioEpisodeEntity>>(emptyList())

    /** 全部节目（按创建时间从新到旧，分页累积）。 */
    val allEpisodes: StateFlow<List<RadioEpisodeEntity>> = _allEpisodes.asStateFlow()

    /** 是否还有更多（用于滚动加载触发）。 */
    val allEpisodesExhausted = MutableStateFlow(false)

    /** 全部节目排序方向（false = 新→旧，true = 旧→新）。 */
    val allAscending = MutableStateFlow(false)

    /** 全部节目（含排序方向）。 */
    val allEpisodesSorted: StateFlow<List<RadioEpisodeEntity>> =
        kotlinx.coroutines.flow.combine(_allEpisodes, allAscending) { list, ascending ->
            if (ascending) list.reversed() else list
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleAllOrder() {
        allAscending.value = !allAscending.value
    }

    /** 按 id 响应式查询单个节目（「更多」抽屉用，喜欢状态即时刷新）。 */
    fun observeEpisodeById(id: String) = radioService.observeEpisode(id)

    /** 「下一集播放」：插入队列当前节目之后（不跳播）。 */
    fun playEpisodeNext(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val asset = radioService.getCurrentAudioAsset(episode.id)
                    ?: throw RadioServiceException("该节目还没有音频，请先生成音频")
                val file = radioService.resolveAssetFile(asset)
                if (!file.exists()) throw RadioServiceException("音频文件不存在，请重新生成音频")
                MediaPlaybackController.radioEpisode(
                    id = episode.id,
                    filePath = file.absolutePath,
                    title = episode.title,
                    channelName = ""
                )
            }.onSuccess { item ->
                mediaPlaybackController.playNext(item)
                _message.value = RadioMessage(context.getString(R.string.radio_toast_queued_next))
            }.onFailure { showError(it) }
        }
    }

    /** 导出节目音频到公共目录。 */
    fun exportEpisodeAudio(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val asset = radioService.getCurrentAudioAsset(episode.id)
                    ?: throw RadioServiceException("该节目还没有音频")
                val file = radioService.resolveAssetFile(asset)
                if (!file.exists()) throw RadioServiceException("音频文件不存在")
                me.rerere.rikkahub.data.radio.RadioAudioExporter.exportToPublicDownloads(
                    context = context,
                    source = file,
                    displayNameBase = episode.title
                )
            }.onSuccess {
                _message.value = RadioMessage(it)
            }.onFailure { showError(it) }
        }
    }

    /** 喜欢 / 取消喜欢节目。 */
    fun toggleEpisodeLiked(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { radioService.setEpisodeLiked(episode.id, episode.liked != 1) }
                .onSuccess {
                    _message.value = RadioMessage(context.getString(if (episode.liked == 1) R.string.radio_toast_unliked else R.string.radio_toast_liked))
                }
                .onFailure { showError(it) }
        }
    }

    /** 「立即播放」：加入队列当前节目之后并立即跳播。 */
    fun playEpisodeNow(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val asset = radioService.getCurrentAudioAsset(episode.id)
                    ?: throw RadioServiceException("该节目还没有音频，请先生成音频")
                val file = radioService.resolveAssetFile(asset)
                if (!file.exists()) throw RadioServiceException("音频文件不存在，请重新生成音频")
                MediaPlaybackController.radioEpisode(
                    id = episode.id,
                    filePath = file.absolutePath,
                    title = episode.title,
                    channelName = ""
                )
            }.onSuccess { item ->
                mediaPlaybackController.playNextNow(item)
            }.onFailure { showError(it) }
        }
    }

    init {
        loadMoreAllEpisodes()
    }

    /** 加载下一页全部节目（滚动到底触发；幂等）。 */
    fun loadMoreAllEpisodes() {
        if (allExhausted) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                radioService.queryEpisodes(
                    EpisodeQuery(
                        sortBy = EpisodeSort.CREATED_DESC,
                        status = "PUBLISHED",
                        page = allPage,
                        pageSize = allPageSize
                    )
                )
            }.onSuccess { pageItems ->
                val fresh = pageItems.filter { page -> _allEpisodes.value.none { it.id == page.id } }
                _allEpisodes.value = _allEpisodes.value + fresh
                allExhausted = pageItems.size < allPageSize
                allEpisodesExhausted.value = allExhausted
                if (pageItems.isNotEmpty()) allPage++
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun createChannel(name: String, description: String, systemPrompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                radioService.createChannel(
                    name = name,
                    description = description,
                    systemPrompt = systemPrompt,
                    creatorId = "user"
                )
            }.onSuccess {
                _message.value = RadioMessage(context.getString(R.string.radio_toast_channel_created, it.name))
            }.onFailure { showError(it) }
        }
    }

    fun updateChannel(channelId: String, name: String, description: String, systemPrompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                radioService.updateChannel(
                    channelId = channelId,
                    name = name,
                    description = description,
                    systemPrompt = systemPrompt
                )
            }.onSuccess {
                _message.value = RadioMessage(context.getString(R.string.radio_toast_channel_updated))
            }.onFailure { showError(it) }
        }
    }

    /**
     * 删除栏目。栏目下有节目时需要用户在 UI 确认级联删除（deleteEpisodes=true），
     * 业务校验在 RadioService（级联删除逐期写审计）。
     */
    fun deleteChannel(channelId: String, deleteEpisodes: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                radioService.deleteChannel(
                    channelId = channelId,
                    operatorId = "user",
                    operatorType = "user",
                    deleteEpisodes = deleteEpisodes
                )
            }.onSuccess { deletedEpisodes ->
                _message.value = RadioMessage(
                    if (deletedEpisodes > 0) "栏目已删除（连同 $deletedEpisodes 期节目）" else "栏目已删除"
                )
            }.onFailure { showError(it) }
        }
    }

    private fun showError(e: Throwable) {
        val text = (e as? RadioServiceException)?.message ?: "操作失败：${e.message ?: e.javaClass.simpleName}"
        _message.value = RadioMessage(text, isError = true)
    }
}

/**
 * 栏目详情 VM：节目列表 + 按栏目顺序播放。
 */
class RadioChannelVM(
    private val channelId: String,
    private val context: Application,
    private val radioService: RadioService,
    private val mediaPlaybackController: MediaPlaybackController,
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore
) : ViewModel() {

    val channel: StateFlow<RadioChannelEntity?> = radioService.observeChannel(channelId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 助手 id → Assistant（节目卡片主播显示）。 */
    val assistantMap: StateFlow<Map<String, Assistant>> = settingsStore.settingsFlow
        .map { settings -> settings.assistants.associateBy { it.id.toString() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val episodes: StateFlow<List<RadioEpisodeEntity>> = radioService.observeChannelEpisodes(channelId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<RadioMessage?>(null)
    val message: StateFlow<RadioMessage?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /** 「下一集播放」：把该节目插入当前队列的当前节目之后（无队列时直接开播）。 */
    fun playEpisodeNext(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val asset = radioService.getCurrentAudioAsset(episode.id)
                    ?: throw RadioServiceException("该节目还没有音频，请先生成音频")
                val file = radioService.resolveAssetFile(asset)
                if (!file.exists()) throw RadioServiceException("音频文件不存在，请重新生成音频")
                MediaPlaybackController.radioEpisode(
                    id = episode.id,
                    filePath = file.absolutePath,
                    title = episode.title,
                    channelName = channel.value?.name.orEmpty()
                )
            }.onSuccess { item ->
                mediaPlaybackController.playNext(item)
                _message.value = RadioMessage(context.getString(R.string.radio_toast_queued_next))
            }.onFailure { showError(it) }
        }
    }

    /** 喜欢 / 取消喜欢节目（同步 DB，列表响应式刷新）。 */
    fun toggleEpisodeLiked(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { radioService.setEpisodeLiked(episode.id, episode.liked != 1) }
                .onSuccess {
                    _message.value = RadioMessage(context.getString(if (episode.liked == 1) R.string.radio_toast_unliked else R.string.radio_toast_liked))
                }
                .onFailure { showError(it) }
        }
    }

    /** 「立即播放」：加入队列当前节目之后并立即跳播该节目。 */
    fun playEpisodeNow(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val asset = radioService.getCurrentAudioAsset(episode.id)
                    ?: throw RadioServiceException("该节目还没有音频，请先生成音频")
                val file = radioService.resolveAssetFile(asset)
                if (!file.exists()) throw RadioServiceException("音频文件不存在，请重新生成音频")
                MediaPlaybackController.radioEpisode(
                    id = episode.id,
                    filePath = file.absolutePath,
                    title = episode.title,
                    channelName = channel.value?.name.orEmpty()
                )
            }.onSuccess { item ->
                mediaPlaybackController.playNextNow(item)
            }.onFailure { showError(it) }
        }
    }

    /** 从栏目页直接导出该节目音频到公共目录。 */
    fun exportEpisodeAudio(episode: RadioEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val asset = radioService.getCurrentAudioAsset(episode.id)
                    ?: throw RadioServiceException("该节目还没有音频")
                val file = radioService.resolveAssetFile(asset)
                if (!file.exists()) throw RadioServiceException("音频文件不存在")
                me.rerere.rikkahub.data.radio.RadioAudioExporter.exportToPublicDownloads(
                    context = context,
                    source = file,
                    displayNameBase = episode.title
                )
            }.onSuccess {
                _message.value = RadioMessage(it)
            }.onFailure { showError(it) }
        }
    }

    /**
     * 播放本栏目全部已发布节目（队列顺序 = 节目创建顺序，老→新）。
     * 无音频的节目跳过；已有播放队列会被替换为本栏目队列。
     */
    fun playAllPublished() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val published = episodes.value
                    .filter { it.status == RadioEpisodeStatus.PUBLISHED }
                    .sortedBy { it.createdAt }
                val items = published.mapNotNull { episode ->
                    val asset = radioService.getCurrentAudioAsset(episode.id) ?: return@mapNotNull null
                    val file = radioService.resolveAssetFile(asset)
                    if (!file.exists()) return@mapNotNull null
                    MediaPlaybackController.radioEpisode(
                        id = episode.id,
                        filePath = file.absolutePath,
                        title = episode.title,
                        channelName = channel.value?.name.orEmpty()
                    )
                }
                if (items.isEmpty()) null else items
            }.onSuccess { items ->
                if (items == null) {
                    _message.value = RadioMessage(context.getString(R.string.radio_toast_no_playable))
                } else {
                    mediaPlaybackController.playEpisodes(items)
                }
            }.onFailure { showError(it) }
        }
    }

    private fun showError(e: Throwable) {
        val text = (e as? RadioServiceException)?.message ?: "操作失败：${e.message ?: e.javaClass.simpleName}"
        _message.value = RadioMessage(text, isError = true)
    }
}

/**
 * 节目详情 VM：正文 + 音频管理 + 播放 + 导出 + 播放列表。
 */
class RadioEpisodeVM(
    private val episodeId: String,
    private val context: Application,
    private val radioService: RadioService,
    private val mediaPlaybackController: MediaPlaybackController,
    private val settingsStore: SettingsStore
) : ViewModel() {

    val episode: StateFlow<RadioEpisodeEntity?> = radioService.observeEpisode(episodeId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 全部音频资产（CURRENT/BACKUP）。 */
    val assets: StateFlow<List<RadioAudioAssetEntity>> = radioService.observeAssets(episodeId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentAsset: StateFlow<RadioAudioAssetEntity?> = assets
        .map { list -> list.firstOrNull { it.status == RadioAudioStatus.CURRENT } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val backupAsset: StateFlow<RadioAudioAssetEntity?> = assets
        .map { list -> list.firstOrNull { it.status == RadioAudioStatus.BACKUP } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 统一播放器状态（仅当在播本节目时有意义）。 */
    val playback: StateFlow<MediaPlaybackStateUi?> = mediaPlaybackController.state
        .map { st ->
            val current = st.currentItem
            if (current?.sourceType == PlaybackSourceType.RADIO_EPISODE && current.id == episodeId) {
                MediaPlaybackStateUi(
                    isPlaying = st.isPlaying,
                    positionMs = st.positionMs,
                    durationMs = st.durationMs
                )
            } else {
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    data class MediaPlaybackStateUi(
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private val _message = MutableStateFlow<RadioMessage?>(null)
    val message: StateFlow<RadioMessage?> = _message.asStateFlow()

    private val _generating = MutableStateFlow(false)

    /** 音频生成中（来自 RadioService 进程级状态：退出页面重进仍为 true）。 */
    val generating: StateFlow<Boolean> = radioService.generatingEpisodes
        .map { episodeId in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 本次生成的失败原因（一次性，UI 展示后 clearGenerationError）。 */
    val generationError: StateFlow<String?> = radioService.generationErrors
        .map { it[episodeId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun clearMessage() {
        _message.value = null
    }

    /** 播放/暂停当前音频。 */
    fun togglePlay() {
        val asset = currentAsset.value ?: return
        val file = RadioAudioGenerator.resolveAudioFile(context, asset.filePath)
        if (!file.exists()) {
            _message.value = RadioMessage(context.getString(R.string.radio_toast_audio_missing_regen), isError = true)
            return
        }
        val current = mediaPlaybackController.state.value.currentItem
        val isCurrent = current?.sourceType == PlaybackSourceType.RADIO_EPISODE && current.id == episodeId
        if (isCurrent) {
            mediaPlaybackController.togglePlayPause()
        } else {
            val ep = episode.value ?: return
            mediaPlaybackController.playSingleItem(
                MediaPlaybackController.radioEpisode(
                    id = ep.id,
                    filePath = file.absolutePath,
                    title = ep.title,
                    channelName = ""
                )
            )
        }
    }

    fun seekTo(positionMs: Long) {
        val current = mediaPlaybackController.state.value.currentItem
        if (current?.sourceType == PlaybackSourceType.RADIO_EPISODE && current.id == episodeId) {
            mediaPlaybackController.seekTo(positionMs)
        }
    }

    /**
     * 请求后台生成音频（立即返回）：生成在 AppScope 进行，退出详情页不中断；
     * 生成态经 [generating] 观察，失败经 [generationError] 观察。
     */
    fun generateAudio() {
        if (generating.value) return
        radioService.requestAudioGeneration(
            episodeId = episodeId,
            assistantProviderId = null,
            assistantVoiceSettingJson = null
        )
    }

    /** 终止进行中的音频生成。 */
    fun cancelAudioGeneration() {
        radioService.cancelAudioGeneration(episodeId)
    }

    /** UI 已展示生成失败提示后清除。 */
    fun clearGenerationError() {
        radioService.clearGenerationError(episodeId)
    }

    /** 恢复备用音频。 */
    fun restoreBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { radioService.restoreBackupAudio(episodeId) }
                .onSuccess { _message.value = RadioMessage(context.getString(R.string.radio_toast_backup_restored)) }
                .onFailure { showError(it) }
        }
    }

    /** 发布节目（需有效音频；校验失败直接提示原因）。 */
    fun publish() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { radioService.publishEpisode(episodeId) }
                .onSuccess { _message.value = RadioMessage(context.getString(R.string.radio_toast_published)) }
                .onFailure { showError(it) }
        }
    }

    /** 修改标题（草稿/已发布均允许）。 */
    fun updateTitle(title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { radioService.updateEpisodeTitle(episodeId, title) }
                .onSuccess { _message.value = RadioMessage(context.getString(R.string.radio_toast_title_updated)) }
                .onFailure { showError(it) }
        }
    }

    /**
     * 导出音频到公共目录 Download/Evolia/radio/（独立外部副本，删除互不影响）。
     */
    fun exportAudio() {
        val asset = currentAsset.value ?: run {
            _message.value = RadioMessage(context.getString(R.string.radio_toast_no_audio_export), isError = true)
            return
        }
        val file = radioService.resolveAssetFile(asset)
        if (!file.exists()) {
            _message.value = RadioMessage(context.getString(R.string.radio_toast_audio_missing), isError = true)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                RadioAudioExporter.exportToPublicDownloads(
                    context = context,
                    source = file,
                    displayNameBase = episode.value?.title ?: "episode"
                )
            }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
            _message.value = RadioMessage(result)
        }
    }

    /** 点赞 / 取消点赞节目。 */
    fun toggleLiked() {
        val current = episode.value ?: return
        val target = current.liked != 1
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { radioService.setEpisodeLiked(episodeId, target) }
                .onFailure { showError(it) }
        }
    }

    private fun showError(e: Throwable) {
        val text = (e as? RadioServiceException)?.message ?: "操作失败：${e.message ?: e.javaClass.simpleName}"
        _message.value = RadioMessage(text, isError = true)
    }
}
