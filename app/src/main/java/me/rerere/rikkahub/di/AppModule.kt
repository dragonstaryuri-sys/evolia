package me.rerere.rikkahub.di


import kotlinx.serialization.json.Json
import me.rerere.asr.provider.ASRManager
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.BackupWorker
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.service.AgentTaskScheduler
import me.rerere.rikkahub.service.DiaryWorker
import me.rerere.rikkahub.service.DiarySchedulerWorker
import me.rerere.rikkahub.service.AgentTaskWorker
import me.rerere.rikkahub.service.MemoryConsolidationWorker
import me.rerere.rikkahub.service.voice.VoiceCallManager
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.tts.controller.TtsController
import me.rerere.tts.provider.TTSManager
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        LocalTools(
            context = get(),
            scheduleRepository = get(),
            settingsStore = get(),
            secretKeyManager = get(),
            agentTaskRepository = get(),
            agentTaskScheduler = get(),
            extendedStateRepo = get(),
            milestoneRepo = get(),
            monitorTaskRepo = get(),
            userDeviceStateRepo = get(),
            okHttpClient = get(),
            providerManager = get(),
            genMediaRepository = get(),
        )
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        AILoggingManager()
    }

    single {
        AgentTaskScheduler(context = get())
    }

    // 通话专用 TTS 控制器（独立实例，避免与 UI 朗读的 TtsController 互相干扰）
    single {
        TtsController(
            context = get(),
            ttsManager = get()
        )
    }

    single {
        ASRManager()
    }

    single {
        VoiceCallManager(
            context = get(),
            chatService = get(),
            ttsController = get(),
            asrManager = get(),
            settingsStore = get()
        )
    }

    single {
        me.rerere.rikkahub.service.voice.VoiceMessagePlayer(get())
    }

    singleOf(::ChatService)

    workerOf(::BackupWorker)
    workerOf(::DiaryWorker)
    workerOf(::DiarySchedulerWorker)
    workerOf(::AgentTaskWorker)
    workerOf(::MemoryConsolidationWorker)
}
