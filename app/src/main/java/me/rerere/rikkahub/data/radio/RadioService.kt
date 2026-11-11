package me.rerere.rikkahub.data.radio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity
import me.rerere.rikkahub.core.data.db.entity.RadioAudioStatus
import me.rerere.rikkahub.core.data.db.entity.RadioChannelEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeDeletionLogEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeVisibility
import me.rerere.rikkahub.core.data.db.entity.RadioOperatorType
import me.rerere.rikkahub.data.ai.TtsVoiceResolver
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.service.voice.CallRecovery
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** 业务规则违反时抛出；工具层转 {"error": message}，UI 层直接展示 message。 */
open class RadioServiceException(message: String) : Exception(message)

/**
 * 节目文字稿与任务创建时不一致（content_hash 不匹配）：任务创建后文字稿被更新。
 * 后台任务执行器将此异常映射为 PermanentFailure（"文字稿已更新，本次生成已丢弃"），
 * 此时 TTS 尚未开始 / 资产尚未提交，无文件需要清理。
 */
class RadioEpisodeContentChangedException(message: String) : RadioServiceException(message)

/**
 * 播放联动钩子：节目标题变更/删除后刷新或停止统一播放器。
 * 由 MediaPlaybackController 实现（避免 RadioService 反向依赖播放器实现细节）。
 */
interface RadioPlaybackHook {
    fun onEpisodeTitleChanged(episodeId: String, title: String)
    fun onEpisodeRemoved(episodeId: String)

    /**
     * 节目音频被替换/清除。
     * @param newAsset 新的当前音频资产（null = 音频被清除，无新音频）。
     * 若正在播放该节目：有新音频时立即切换到新音频并从头播放；
     * 没有新音频时停止播放。本来不在播该节目则不打断当前播放。
     */
    fun onEpisodeAudioReplaced(episodeId: String, newAsset: me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity?)
}

internal object NoOpRadioPlaybackHook : RadioPlaybackHook {
    override fun onEpisodeTitleChanged(episodeId: String, title: String) = Unit
    override fun onEpisodeRemoved(episodeId: String) = Unit
    override fun onEpisodeAudioReplaced(episodeId: String, newAsset: me.rerere.rikkahub.core.data.db.entity.RadioAudioAssetEntity?) = Unit
}

/**
 * 小机电台业务层。UI 与 Agent LocalTool 必须共用本类（AGENTS.md 12.2），
 * 禁止绕过业务规则直连 DAO。
 *
 * 职责：栏目 CRUD、节目保存/修改/删除/发布校验、音频资产生命周期
 * （current/backup 换备、恢复、清理）、删除审计、文字稿与音频的并发互斥。
 */
class RadioService(
    private val context: Context,
    private val repository: RadioRepository,
    private val settingsStore: SettingsStore,
    private val audioGenerator: RadioAudioGenerator,
    private val playbackHook: RadioPlaybackHook = NoOpRadioPlaybackHook,
    /** 后台音频生成的作用域（App 进程级：退出详情页生成继续）。 */
    private val appScope: me.rerere.rikkahub.AppScope? = null,
) {
    // ===== 音频生成状态（进程内；进程被杀则生成终止，半成品由孤儿清理回收）=====
    private val generationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val _generatingEpisodes = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** 正在生成音频的节目 ID 集合（详情页生成态/终止按钮/重进恢复展示）。 */
    val generatingEpisodes: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        _generatingEpisodes.asStateFlow()

    private val _generationErrors = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    /** 最近一次生成失败的错误信息（episodeId → 原因），UI 消费后可调 [clearGenerationError]。 */
    val generationErrors: kotlinx.coroutines.flow.StateFlow<Map<String, String>> =
        _generationErrors.asStateFlow()

    fun clearGenerationError(episodeId: String) {
        _generationErrors.update { it - episodeId }
    }

    /**
     * 请求后台生成音频：立即返回，生成在 AppScope(IO) 进行，退出详情页不中断。
     * 同一节目重复请求会被忽略（进行中）；结果经换源联动刷新播放器。
     */
    fun requestAudioGeneration(
        episodeId: String,
        assistantProviderId: kotlin.uuid.Uuid?,
        assistantVoiceSettingJson: String?
    ) {
        val scope = appScope ?: return
        if (generationJobs[episodeId]?.isActive == true) return
        _generatingEpisodes.update { it + episodeId }
        _generationErrors.update { it - episodeId }
        val job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeout(15 * 60_000L) {
                    generateEpisodeAudio(
                        episodeId = episodeId,
                        assistantProviderId = assistantProviderId,
                        assistantVoiceSettingJson = assistantVoiceSettingJson
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户终止：半成品文件由 generateEpisodeAudio 内部清理，资产不落库
            } catch (e: Exception) {
                _generationErrors.update {
                    it + (episodeId to ((e as? RadioServiceException)?.message ?: "音频生成失败：${e.message ?: e.javaClass.simpleName}"))
                }
            } finally {
                generationJobs.remove(episodeId)
                _generatingEpisodes.update { it - episodeId }
            }
        }
        generationJobs[episodeId] = job
    }

    /** 终止进行中的音频生成。 */
    fun cancelAudioGeneration(episodeId: String) {
        generationJobs[episodeId]?.cancel()
    }
    /** per-episode 互斥：串行化「文字稿更新 vs 音频生成/换备」（异常场景 #8）。 */
    private val episodeLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(episodeId: String): Mutex =
        episodeLocks.computeIfAbsent(episodeId) { Mutex() }

    // ====================================================================
    // 栏目
    // ====================================================================

    /**
     * 预置默认栏目（幂等）：电台空间为空时补入「早安频道 / 晚安频道」，
     * 附默认创作提示词。用户与 Agent 均可修改或删除（走通用栏目管理逻辑）。
     */
    suspend fun ensureDefaultChannels(now: Long = System.currentTimeMillis()) {
        if (repository.getAllChannels().isNotEmpty()) return
        val defaults = listOf(
            "早安频道" to (
                "你是「早安频道」的主播。以轻快、温暖的语气创作一期 3 分钟左右的早安电台节目："
                    + "先向听众道一声早安，再用一两句话开启新的一天（正向但不鸡汤），"
                    + "分享一条简短实用的生活小建议，最后预告今天会一直陪伴在身边。"
                    + "口语化表达，适合清晨收听，避免过度煽情。"
                ),
            "晚安频道" to (
                "你是「晚安频道」的主播。以舒缓、温柔的语气创作一期 3 分钟左右的晚安电台节目："
                    + "轻轻回顾今天的一两个小片段，引导听众做一次放松（深呼吸或温暖的想象），"
                    + "用一句安稳的祝福收尾。语速放缓，用词柔和，营造睡前的安心感。"
                )
        )
        defaults.forEachIndexed { index, (name, prompt) ->
            repository.upsertChannel(
                RadioChannelEntity(
                    id = Uuid.random().toString(),
                    name = name,
                    description = "预置栏目，可自由修改或删除",
                    systemPrompt = prompt,
                    creatorId = "user",
                    sortOrder = index,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    fun observeChannels() = repository.observeChannels()
    fun observeChannel(id: String) = repository.observeChannel(id)

    /** 栏目列表 + 各栏目节目数（工具目录展示用）。 */
    suspend fun getAllChannelsWithCounts(): List<Pair<RadioChannelEntity, Int>> {
        val channels = repository.getAllChannels()
        return channels.map { it to repository.countEpisodesByChannel(it.id) }
    }

    suspend fun getChannel(id: String): RadioChannelEntity =
        repository.getChannel(id) ?: throw RadioServiceException("栏目不存在: $id")

    suspend fun createChannel(
        name: String,
        description: String,
        systemPrompt: String,
        creatorId: String,
        icon: String? = null,
        now: Long = System.currentTimeMillis()
    ): RadioChannelEntity {
        val validName = name.trim()
        if (validName.isBlank()) throw RadioServiceException("栏目名称不能为空")
        val channel = RadioChannelEntity(
            id = Uuid.random().toString(),
            name = validName,
            description = description.trim(),
            systemPrompt = systemPrompt,
            icon = icon,
            creatorId = creatorId,
            sortOrder = nextSortOrder(),
            createdAt = now,
            updatedAt = now
        )
        repository.upsertChannel(channel)
        return channel
    }

    suspend fun updateChannel(
        channelId: String,
        name: String? = null,
        description: String? = null,
        systemPrompt: String? = null,
        icon: String? = null,
        now: Long = System.currentTimeMillis()
    ): RadioChannelEntity {
        val existing = getChannel(channelId)
        val validName = name?.trim()
        if (validName != null && validName.isBlank()) throw RadioServiceException("栏目名称不能为空")
        val updated = existing.copy(
            name = validName ?: existing.name,
            description = description?.trim() ?: existing.description,
            systemPrompt = systemPrompt ?: existing.systemPrompt,
            icon = icon ?: existing.icon,
            updatedAt = now
        )
        repository.upsertChannel(updated)
        return updated
    }

    /**
     * 删除栏目。栏目下存在节目时必须显式选择级联删除（先逐期写删除审计），
     * 不允许直接删除后留下无归属节目。
     *
     * @return 实际级联删除的节目数
     */
    suspend fun deleteChannel(
        channelId: String,
        operatorId: String,
        operatorType: String,
        deleteEpisodes: Boolean
    ): Int {
        val channel = getChannel(channelId)
        val episodeCount = repository.countEpisodesByChannel(channelId)
        if (episodeCount > 0 && !deleteEpisodes) {
            throw RadioServiceException(
                "栏目「${channel.name}」下还有 $episodeCount 期节目，请先处理节目或明确要求一并删除"
            )
        }
        var deleted = 0
        if (episodeCount > 0) {
            for (episode in repository.queryEpisodes(EpisodeQuery(channelId = channelId, pageSize = 100, page = 0))) {
                deleteEpisode(
                    episodeId = episode.id,
                    operatorId = operatorId,
                    operatorType = operatorType,
                    reason = "所属栏目「${channel.name}」被删除",
                    requestedByUser = operatorType == RadioOperatorType.USER,
                    skipMissingOk = true
                )
                deleted++
            }
        }
        repository.deleteChannel(channelId)
        return deleted
    }

    // ====================================================================
    // 节目
    // ====================================================================

    fun observeChannelEpisodes(channelId: String) = repository.observeEpisodesByChannel(channelId)

    /** 点赞节目列表（我的喜欢 tab）。 */
    fun observeLiked() = repository.observeLiked()

    /** 全部草稿（草稿箱 tab）。 */
    fun observeDrafts() = repository.observeDrafts()
    fun observeEpisode(id: String) = repository.observeEpisode(id)
    suspend fun getEpisode(id: String): RadioEpisodeEntity =
        repository.getEpisode(id) ?: throw RadioServiceException("节目不存在: $id")

    fun observeAssets(episodeId: String) = repository.observeAssetsByEpisode(episodeId)

    /**
     * 保存节目（创建或更新）。创建必须具有标题；更新时：
     * - DRAFT：可改标题+文字稿，文字稿变化立即清空全部音频；
     * - PUBLISHED：只允许改标题（文字稿变化直接报错）。
     */
    suspend fun saveEpisode(
        channelId: String,
        title: String,
        content: String,
        creatorAgentId: String,
        episodeId: String? = null,
        now: Long = System.currentTimeMillis()
    ): RadioEpisodeEntity {
        val validTitle = title.trim()
        if (validTitle.isBlank()) throw RadioServiceException("节目标题不能为空")
        val channel = getChannel(channelId)

        return lockFor(episodeId ?: "new:$channelId:$now").withLock {
            if (episodeId == null) {
                val episode = RadioEpisodeEntity(
                    id = Uuid.random().toString(),
                    channelId = channel.id,
                    title = validTitle,
                    content = content,
                    creatorAgentId = creatorAgentId,
                    status = RadioEpisodeStatus.DRAFT,
                    createdAt = now,
                    updatedAt = now
                )
                repository.upsertEpisode(episode)
                episode
            } else {
                val existing = repository.getEpisode(episodeId)
                    ?: throw RadioServiceException("要更新的节目不存在: $episodeId")
                val contentChanged = existing.content != content
                if (contentChanged && !RadioEpisodeRules.canModifyContent(existing.status)) {
                    throw RadioServiceException("节目已发布，不允许修改文字稿")
                }
                if (contentChanged) {
                    wipeAudioAssetsLocked(existing.id)
                }
                val updated = existing.copy(
                    title = validTitle,
                    content = if (contentChanged) content else existing.content,
                    updatedAt = now
                )
                repository.upsertEpisode(updated)
                if (contentChanged) {
                    playbackHook.onEpisodeAudioReplaced(existing.id, null)
                }
                updated
            }
        }
    }

    /** 修改未发布节目文字稿（PUBLISHED 明确报错）；文字稿变化即清全部音频。 */
    suspend fun updateEpisodeContent(
        episodeId: String,
        content: String,
        now: Long = System.currentTimeMillis()
    ): RadioEpisodeEntity = lockFor(episodeId).withLock {
        val existing = getEpisode(episodeId)
        if (!RadioEpisodeRules.canModifyContent(existing.status)) {
            throw RadioServiceException("节目已发布，不允许修改文字稿")
        }
        val contentChanged = existing.content != content
        if (contentChanged) {
            wipeAudioAssetsLocked(episodeId)
        }
        repository.updateEpisodeContent(episodeId, content, now)
        if (contentChanged) {
            playbackHook.onEpisodeAudioReplaced(episodeId, null)
        }
        existing.copy(content = content, updatedAt = now)
    }

    /** 修改节目标题（草稿/已发布均允许）；联动刷新播放器与通知栏元数据。 */
    suspend fun updateEpisodeTitle(
        episodeId: String,
        title: String,
        now: Long = System.currentTimeMillis()
    ): RadioEpisodeEntity {
        val validTitle = title.trim()
        if (validTitle.isBlank()) throw RadioServiceException("节目标题不能为空")
        val existing = getEpisode(episodeId)
        repository.updateEpisodeTitle(episodeId, validTitle, now)
        playbackHook.onEpisodeTitleChanged(episodeId, validTitle)
        return existing.copy(title = validTitle, updatedAt = now)
    }

    /**
     * 删除节目：清理节目记录、全部音频（文件+资产记录），并写入独立删除审计。
     * 审计记录不随节目删除而消失。
     */
    suspend fun deleteEpisode(
        episodeId: String,
        operatorId: String,
        operatorType: String,
        reason: String,
        requestedByUser: Boolean,
        skipMissingOk: Boolean = false
    ) {
        lockFor(episodeId).withLock {
            val episode = repository.getEpisode(episodeId)
            if (episode == null) {
                if (skipMissingOk) return@withLock
                throw RadioServiceException("节目不存在: $episodeId")
            }
            playbackHook.onEpisodeRemoved(episodeId)
            wipeAudioAssetsLocked(episodeId)
            repository.deleteEpisode(episodeId)
            repository.insertDeletionLog(
                RadioEpisodeDeletionLogEntity(
                    id = Uuid.random().toString(),
                    episodeId = episodeId,
                    episodeTitle = episode.title,
                    channelId = episode.channelId,
                    creatorAgentId = episode.creatorAgentId,
                    operatorId = operatorId,
                    operatorType = operatorType,
                    reason = reason,
                    requestedByUser = if (requestedByUser) 1 else 0,
                    deletedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * 发布节目：只校验不合成。缺有效音频时明确失败，要求先生成音频。
     * [visibility] 由 Agent 在 publish_episode 时指定（UI 发布按钮缺省 PUBLIC）；
     * PRIVATE 节目对其他 Agent 不可查询，user 与创作 Agent 恒可见。
     */
    suspend fun publishEpisode(
        episodeId: String,
        visibility: String = RadioEpisodeVisibility.PUBLIC,
        now: Long = System.currentTimeMillis()
    ): RadioEpisodeEntity {
        val validVisibility = RadioEpisodeRules.normalizeVisibility(visibility)
            ?: throw RadioServiceException("visibility 只支持 public / private")
        return lockFor(episodeId).withLock {
            val episode = repository.getEpisode(episodeId) ?: throw RadioServiceException("节目不存在: $episodeId")
            val current = repository.getCurrentAsset(episodeId)
            val currentFile = current?.let { RadioAudioGenerator.resolveAudioFile(context, it.filePath) }
            val blockReason = RadioAudioRules.publishBlockReason(
                episodeExists = true,
                episodeStatus = episode.status,
                content = episode.content,
                currentAssetExists = current != null,
                currentAssetContentHash = current?.contentHash,
                currentFilePlayable = currentFile != null && CallRecovery.isAudioFilePlayable(currentFile),
                contentHash = RadioEpisodeRules.contentHash(episode.content)
            )
            if (blockReason != null) throw RadioServiceException(blockReason)
            repository.publishEpisode(episodeId, now, now, validVisibility)
            episode.copy(
                status = RadioEpisodeStatus.PUBLISHED,
                visibility = validVisibility,
                publishedAt = now,
                updatedAt = now
            )
        }
    }

    /**
     * 修改节目可见性（仅已发布节目；草稿的可见性在 publish_episode 时指定）。
     * Agent 工具入口，user 无 UI 入口。
     */
    suspend fun updateEpisodeVisibility(
        episodeId: String,
        visibility: String,
        now: Long = System.currentTimeMillis()
    ): RadioEpisodeEntity {
        val validVisibility = RadioEpisodeRules.normalizeVisibility(visibility)
            ?: throw RadioServiceException("visibility 只支持 public / private")
        return lockFor(episodeId).withLock {
            val existing = getEpisode(episodeId)
            val canModify = RadioEpisodeRules.canModifyVisibility(existing.status)
            if (!canModify) {
                throw RadioServiceException("节目尚未发布，可见性在发布时通过 publish_episode 的 visibility 参数指定")
            }
            val changed = existing.visibility != validVisibility
            if (changed) {
                repository.updateEpisodeVisibility(episodeId, validVisibility, now)
            }
            existing.copy(visibility = validVisibility, updatedAt = now)
        }
    }

    /**
     * 查询节目列表。[viewerAgentId] 非空时按可见性过滤（Agent 工具入口）：
     * PRIVATE 节目仅创作 Agent 本人可查询；null = user/UI 视角，不过滤。
     */
    suspend fun queryEpisodes(query: EpisodeQuery, viewerAgentId: String? = null): List<RadioEpisodeEntity> {
        val list = repository.queryEpisodes(query)
        val isHiddenByVisibility = { episode: RadioEpisodeEntity ->
            !RadioEpisodeRules.isVisibleToAgent(episode.visibility, episode.creatorAgentId, viewerAgentId)
        }
        return if (viewerAgentId == null) list else list.filterNot(isHiddenByVisibility)
    }

    /**
     * Agent 视角按创作助手名查询节目（Agent 不知道自身/他人 ID，只能按名字查）：
     * 同名助手的节目全部返回（不区分具体是哪一个）；名字为空或无匹配返回空列表；
     * 可见性过滤与 [queryEpisodes] 一致。
     */
    suspend fun queryEpisodesByCreatorName(
        query: EpisodeQuery,
        creatorName: String?,
        viewerAgentId: String?
    ): List<RadioEpisodeEntity> {
        val assistants = settingsStore.settingsFlow.value.assistants
        val matchedCreatorIds = RadioEpisodeRules.resolveCreatorIdsByName(
            creatorName,
            assistants.map { it.id.toString() to it.name }
        )
        val hasNoNameMatch = matchedCreatorIds.isEmpty()
        if (hasNoNameMatch) return emptyList()
        return queryEpisodes(query.copy(creatorAgentIds = matchedCreatorIds), viewerAgentId)
    }

    /**
     * Agent 视角读取单个节目：PRIVATE 节目非创作 Agent 一律按「不存在」处理（不泄露存在性）。
     * user / UI 侧读取仍走 [getEpisode]，不做可见性过滤。
     */
    suspend fun getEpisodeForAgent(episodeId: String, viewerAgentId: String): RadioEpisodeEntity {
        val episode = repository.getEpisode(episodeId)
            ?: throw RadioServiceException("节目不存在: $episodeId")
        val isHiddenByVisibility =
            !RadioEpisodeRules.isVisibleToAgent(episode.visibility, episode.creatorAgentId, viewerAgentId)
        if (isHiddenByVisibility) throw RadioServiceException("节目不存在: $episodeId")
        return episode
    }

    /** 点赞/取消点赞节目（播放器点赞按钮）。 */
    suspend fun setEpisodeLiked(episodeId: String, liked: Boolean): RadioEpisodeEntity {
        val episode = getEpisode(episodeId)
        repository.updateEpisodeLiked(episodeId, if (liked) 1 else 0)
        return episode.copy(liked = if (liked) 1 else 0)
    }

    /**
     * 最近发布的节目（对话页「新节目发布」卡片）。
     * [publishedAfterMs] 过滤：只返回该时间点之后发布的节目（如本会话窗口起点），
     * null 表示不过滤。
     */
    /** 观察指定 Agent 的最近发布节目（对话页发布卡片，响应式）。 */
    fun observeLatestPublished(agentId: String): Flow<RadioEpisodeEntity?> =
        repository.observeLatestPublishedByAgent(agentId)

    suspend fun getLatestPublishedEpisode(agentId: String?, publishedAfterMs: Long? = null): RadioEpisodeEntity? {
        val latest = if (agentId != null) {
            repository.getLatestPublishedByAgent(agentId)
        } else {
            repository.getLatestPublished()
        } ?: return null
        return if (publishedAfterMs != null && (latest.publishedAt ?: 0L) < publishedAfterMs) null else latest
    }

    /** 当前音频资产（列表页 has_audio 标记等只读用途）。 */
    suspend fun getCurrentAudioAsset(episodeId: String): RadioAudioAssetEntity? =
        repository.getCurrentAsset(episodeId)

    /** 备用音频资产。 */
    suspend fun getBackupAudioAsset(episodeId: String): RadioAudioAssetEntity? =
        repository.getBackupAsset(episodeId)

    // ====================================================================
    // 音频资产
    // ====================================================================

    /**
     * 基于当前已保存文字稿生成音频（必须经 lockFor 互斥）。
     *
     * 语音选择：Agent 专属（providerId+voiceId，须在音色库中）→ 全局默认 → legacy；
     * 发生回退时 [GenerateAudioOutcome.fallbackNotice] 携带提示文案，
     * 资产记录实际使用的 provider/voice。
     *
     * @param expectedContentHash 非空时先与节目当前文字稿哈希比对，不一致抛
     * [RadioEpisodeContentChangedException]（后台任务创建后文字稿被更新的防护；此时尚未合成，无文件需清理）
     */
    suspend fun generateEpisodeAudio(
        episodeId: String,
        assistantProviderId: Uuid?,
        assistantVoiceSettingJson: String?,
        expectedContentHash: String? = null,
        now: Long = System.currentTimeMillis()
    ): GenerateAudioOutcome = lockFor(episodeId).withLock {
        val episode = getEpisode(episodeId)
        val content = episode.content
        if (content.isBlank()) throw RadioServiceException("节目文字稿为空，无法生成音频")
        if (expectedContentHash != null && RadioEpisodeRules.contentHash(content) != expectedContentHash) {
            throw RadioEpisodeContentChangedException("节目文字稿已更新，本次生成已取消，请重新发起")
        }

        val resolution = TtsVoiceResolver.resolve(
            settings = settingsStore.settingsFlow.value,
            assistantProviderId = assistantProviderId,
            assistantVoiceSettings = assistantVoiceSettingJson
                ?.takeIf { json -> json.isNotBlank() && assistantProviderId != null }
                ?.let { json -> mapOf(assistantProviderId.toString() to json) }
                ?: emptyMap()
        )
        val providerSetting = resolution.providerSetting
            ?: throw RadioServiceException("没有可用的语音服务 Provider，请先在语音服务设置中配置")

        val audioId = Uuid.random().toString()
        val targetFile = File(RadioAudioGenerator.audioDir(context), "$audioId.m4a")
        targetFile.parentFile?.mkdirs()

        val result = try {
            audioGenerator.generate(content, providerSetting, targetFile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            targetFile.delete()
            throw e
        }
        when (result) {
            is RadioAudioGenerator.Result.Failure -> {
                targetFile.delete()
                throw RadioServiceException(result.reason)
            }

            is RadioAudioGenerator.Result.Success -> {
                val playable = CallRecovery.isAudioFilePlayable(result.file)
                if (!playable) {
                    result.file.delete()
                    throw RadioServiceException("生成的音频文件校验失败，请重试")
                }
                val actualPath = relativeAudioPath(result.file)
                val newAsset = RadioAudioAssetEntity(
                    id = audioId,
                    episodeId = episodeId,
                    filePath = actualPath,
                    providerId = providerSetting.id.toString(),
                    voiceId = extractVoiceId(providerSetting),
                    durationMs = result.durationMs,
                    fileSize = result.file.length(),
                    status = RadioAudioStatus.CURRENT,
                    contentHash = RadioEpisodeRules.contentHash(content),
                    createdAt = now
                )
                val current = repository.getCurrentAsset(episodeId)
                val backup = repository.getBackupAsset(episodeId)
                val plan = RadioAudioRules.planRegenerationSwap(
                    newAssetId = newAsset.id,
                    newContentHash = newAsset.contentHash,
                    currentAssetId = current?.id,
                    currentContentHash = current?.contentHash,
                    backupAssetId = backup?.id,
                    now = now
                )
                applySwapPlan(plan, episodeId, newAsset)
                playbackHook.onEpisodeAudioReplaced(episodeId, newAsset)
                GenerateAudioOutcome(asset = newAsset, fallbackNotice = resolution.fallbackNotice)
            }
        }
    }

    /**
     * 读取节目文字稿并校验任务期望哈希（短暂持 episodeLock，读完即释放）。
     * 后台任务执行器生成阶段不持锁（避免长时间阻塞 UI 换备/编辑），
     * 文字稿变化由 [commitEpisodeAudio] 在提交时二次校验兜底。
     */
    suspend fun getEpisodeContentChecked(episodeId: String, expectedContentHash: String): String =
        lockFor(episodeId).withLock {
            val episode = getEpisode(episodeId)
            val content = episode.content
            if (content.isBlank()) throw RadioServiceException("节目文字稿为空，无法生成音频")
            if (RadioEpisodeRules.contentHash(content) != expectedContentHash) {
                throw RadioEpisodeContentChangedException("节目文字稿已更新，本次生成已取消，请重新发起")
            }
            content
        }

    /**
     * 幂等检查：该后台任务的音频是否已在崩溃前提交（按 task_id + content_hash 匹配，不限状态——
     * 已提交后又被用户重新生成转为备份的情况也不重复合成）。
     */
    suspend fun findCommittedAudioForTask(taskId: String, contentHash: String): RadioAudioAssetEntity? =
        repository.getAssetByTaskId(taskId, contentHash)

    /**
     * 提交后台任务生成的音频资产（提交原子边界，独立 episodeLock）。
     *
     * 与 [generateEpisodeAudio] 的区别：合成与提交解耦——Worker 先生成文件，
     * 再经本方法在锁内重新校验并落库（committing 状态中间边界）：
     * - 重新比对文字稿哈希：封堵「生成完成 → 提交执行」之间文字稿变化的窗口，
     *   不一致抛 [RadioEpisodeContentChangedException]（结果丢弃，不落资产）；
     * - 文件可播校验（MediaExtractor）；
     * - 资产记录 [RadioAudioAssetEntity.taskId]，崩溃恢复据此判断该任务音频是否已提交（幂等判据）；
     * - 遵守 current/backup 换备策略；失败不影响既有有效音频。
     *
     * 失败抛 RadioServiceException，调用方（RadioAudioGenerationExecutor）据此
     * 置任务 failed 并清理生成文件与工作目录。
     */
    suspend fun commitEpisodeAudio(
        episodeId: String,
        audioFile: File,
        audioId: String,
        contentHash: String,
        taskId: String,
        providerId: String,
        voiceId: String,
        durationMs: Long,
        now: Long = System.currentTimeMillis()
    ): RadioAudioAssetEntity = lockFor(episodeId).withLock {
        // 幂等保护：崩溃后重入（committing → running 重认领 → 重走提交）时，
        // 已提交的资产直接返回，不重复换备（防重复创建资产/破坏 current/backup 规则）
        repository.getAssetByTaskId(taskId, contentHash)?.let { return@withLock it }
        val episode = getEpisode(episodeId)
        val content = episode.content
        if (content.isBlank()) throw RadioServiceException("节目文字稿为空，无法提交音频")
        if (RadioEpisodeRules.contentHash(content) != contentHash) {
            throw RadioEpisodeContentChangedException("节目文字稿已更新，本次生成结果已丢弃，请重新发起")
        }
        if (!CallRecovery.isAudioFilePlayable(audioFile)) {
            throw RadioServiceException("生成的音频文件校验失败，请重试")
        }
        val newAsset = RadioAudioAssetEntity(
            id = audioId,
            episodeId = episodeId,
            filePath = relativeAudioPath(audioFile),
            providerId = providerId,
            voiceId = voiceId,
            durationMs = durationMs,
            fileSize = audioFile.length(),
            status = RadioAudioStatus.CURRENT,
            contentHash = contentHash,
            createdAt = now,
            taskId = taskId
        )
        val current = repository.getCurrentAsset(episodeId)
        val backup = repository.getBackupAsset(episodeId)
        val plan = RadioAudioRules.planRegenerationSwap(
            newAssetId = newAsset.id,
            newContentHash = newAsset.contentHash,
            currentAssetId = current?.id,
            currentContentHash = current?.contentHash,
            backupAssetId = backup?.id,
            now = now
        )
        applySwapPlan(plan, episodeId, newAsset)
        playbackHook.onEpisodeAudioReplaced(episodeId, newAsset)
        newAsset
    }

    /** 恢复备用音频：BACKUP ↔ CURRENT 互换，新 BACKUP 重算 3 天有效期。 */
    suspend fun restoreBackupAudio(episodeId: String, now: Long = System.currentTimeMillis()): RadioAudioAssetEntity =
        lockFor(episodeId).withLock {
            val backup = repository.getBackupAsset(episodeId)
                ?: throw RadioServiceException("节目没有备用音频")
            val backupFile = RadioAudioGenerator.resolveAudioFile(context, backup.filePath)
            if (!CallRecovery.isAudioFilePlayable(backupFile)) {
                // 备用文件已失效：清理该资产，保持状态一致
                deleteAssetWithFile(backup)
                throw RadioServiceException("备用音频文件已失效，已被清理")
            }
            val current = repository.getCurrentAsset(episodeId)
            val plan = RadioAudioRules.planRestoreSwap(
                backupAssetId = backup.id,
                currentAssetId = current?.id,
                now = now
            )
            applySwapPlan(plan, episodeId)
            val restoredAsset = repository.getCurrentAsset(episodeId)
            playbackHook.onEpisodeAudioReplaced(episodeId, restoredAsset)
            backup.copy(status = RadioAudioStatus.CURRENT, expiresAt = null)
        }

    enum class AudioDeleteTarget { CURRENT, BACKUP, ALL }

    data class GenerateAudioOutcome(
        val asset: RadioAudioAssetEntity,
        val fallbackNotice: String?
    )

    // ====================================================================
    // 清理（Worker 调用）
    // ====================================================================

    /** 备用音频 3 天过期清理：删文件 + 记录。 */
    suspend fun cleanupExpiredBackups(now: Long = System.currentTimeMillis()): Int {
        val expired = repository.getExpiredBackups(now)
        expired.forEach { asset ->
            runCatching { deleteAssetWithFile(asset) }
                .onFailure { Log.w(TAG, "清理过期备用音频失败: ${asset.id}", it) }
        }
        return expired.size
    }

    /** 孤儿音频文件清理：DB 无引用 + mtime 超过保护期 才删除（mtime 不可读保守保留）。 */
    suspend fun cleanupOrphanFiles(now: Long = System.currentTimeMillis()): Int {
        val dir = RadioAudioGenerator.audioDir(context)
        if (!dir.exists()) return 0
        val referencedNames = repository.getAllAssets()
            .map { File(it.filePath).name }
            .toSet()
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            val isReferenced = file.name in referencedNames
            val mtime = runCatching { file.lastModified() }.getOrDefault(0L)
            val isOldEnough = mtime > 0L && now - mtime > RADIO_ORPHAN_RETENTION_MS
            if (!isReferenced && isOldEnough) {
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    // ====================================================================
    // 内部工具
    // ====================================================================

    /**
     * 创作者 ID → 展示名：user 返回 user；Agent ID 查助手名（不存在返回原 ID）。
     */
    suspend fun resolveCreatorName(creatorId: String): String {
        if (creatorId == "user" || creatorId.isBlank()) return creatorId.ifBlank { "unknown" }
        val id = runCatching { kotlin.uuid.Uuid.parse(creatorId) }.getOrNull() ?: return creatorId
        return settingsStore.settingsFlow.value.getAssistantById(id)?.name ?: creatorId
    }

    /** 解析资产相对路径为真实文件（UI 播放/导出用）。 */
    fun resolveAssetFile(asset: RadioAudioAssetEntity): File =
        RadioAudioGenerator.resolveAudioFile(context, asset.filePath)

    /**
     * 构造节目的播放条目（share_episode 工具用）：
     * 校验节目存在且已发布、有当前音频且文件可用；不满足返回 null。
     */
    suspend fun buildPlaybackItem(episodeId: String): me.rerere.rikkahub.service.media.PlaybackItem? {
        val episode = repository.getEpisode(episodeId) ?: return null
        if (episode.status != RadioEpisodeStatus.PUBLISHED) return null
        val asset = repository.getCurrentAsset(episodeId) ?: return null
        val file = resolveAssetFile(asset)
        if (!file.exists()) return null
        return me.rerere.rikkahub.service.media.PlaybackItem(
            sourceType = me.rerere.rikkahub.service.media.PlaybackSourceType.RADIO_EPISODE,
            id = episode.id,
            uri = android.net.Uri.fromFile(file),
            title = episode.title,
            subtitle = "",
            mimeType = if (file.name.endsWith(".wav", true)) "audio/wav" else "audio/mp4"
        )
    }

    /** 删除节目全部音频（文件+记录），须在 episode 锁内调用。 */
    private suspend fun wipeAudioAssetsLocked(episodeId: String) {
        val assets = repository.getAssetsByEpisode(episodeId)
        assets.forEach { deleteAssetWithFile(it) }
        if (assets.isNotEmpty()) {
            playbackHook.onEpisodeAudioReplaced(episodeId, null)
        }
    }

    /** 删除单个资产：先删文件再删记录；文件删除失败时记录日志（孤儿清理兜底回收）。 */
    private suspend fun deleteAssetWithFile(asset: RadioAudioAssetEntity) {
        val file = RadioAudioGenerator.resolveAudioFile(context, asset.filePath)
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "音频文件删除失败（留待孤儿清理回收）: ${file.absolutePath}")
        }
        repository.deleteAsset(asset.id)
    }

    private suspend fun applySwapPlan(
        plan: AudioSwapPlan,
        episodeId: String,
        newAsset: RadioAudioAssetEntity? = null
    ) {
        // 先删旧资产（文件+记录），再写入新资产，最后应用状态互换
        plan.deleteAssetIds.forEach { id ->
            val asset = repository.getAsset(id)
            if (asset != null) {
                deleteAssetWithFile(asset)
            } else {
                // 记录已不存在（并发残留），仅跳过
                Log.d(TAG, "资产记录已存在缺失，跳过删除: $id (episode=$episodeId)")
            }
        }
        newAsset?.let { repository.upsertAsset(it) }
        plan.statusChanges.forEach { change ->
            if (change.assetId == newAsset?.id) return@forEach
            repository.updateAssetStatus(change.assetId, change.newStatus, change.newExpiresAt)
        }
    }

    private suspend fun nextSortOrder(): Int {
        val channels = repository.getAllChannels()
        return (channels.maxOfOrNull { it.sortOrder } ?: -1) + 1
    }

    private fun relativeAudioPath(file: File): String {
        val filesDirPath = context.filesDir.absolutePath.trimEnd('/') + "/"
        return file.absolutePath.removePrefix(filesDirPath)
    }

    /** 从 Provider 配置中提取实际音色 ID（用于资产记录）。 */
    /** 提取实际生效音色 ID（后台任务执行器复用，禁止各自实现 Provider 映射）。 */
    fun extractVoiceId(setting: me.rerere.tts.provider.TTSProviderSetting): String {
        return when (setting) {
            is me.rerere.tts.provider.TTSProviderSetting.OpenAI -> setting.voice
            is me.rerere.tts.provider.TTSProviderSetting.Custom -> setting.voice
            // Mimo voicedesign 没有音色 ID，记录实际生效的设计描述（voice 字段是标准模型残留值）
            is me.rerere.tts.provider.TTSProviderSetting.Mimo ->
                me.rerere.rikkahub.data.ai.mimoEffectiveVoiceId(setting)
            is me.rerere.tts.provider.TTSProviderSetting.Gemini -> setting.voiceName
            is me.rerere.tts.provider.TTSProviderSetting.Azure -> setting.voiceName
            is me.rerere.tts.provider.TTSProviderSetting.SystemTTS -> setting.voiceName ?: "system_default"
            is me.rerere.tts.provider.TTSProviderSetting.ElevenLabs -> setting.voiceId
            is me.rerere.tts.provider.TTSProviderSetting.MiniMax -> setting.voiceId
        }
    }

    companion object {
        private const val TAG = "RadioService"
    }
}
