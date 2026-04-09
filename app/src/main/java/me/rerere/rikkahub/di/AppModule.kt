package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.BackupWorker
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.service.AgentTaskScheduler
import me.rerere.rikkahub.service.DiaryWorker
import me.rerere.rikkahub.service.DiarySchedulerWorker
import me.rerere.rikkahub.service.AgentTaskWorker
import me.rerere.rikkahub.utils.UpdateChecker
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
            agentTaskScheduler = get()
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
        Firebase.crashlytics
    }

    single {
        Firebase.remoteConfig
    }

    single {
        AILoggingManager()
    }

    single {
        AgentTaskScheduler(context = get())
    }

    singleOf(::ChatService)

    workerOf(::BackupWorker)
    workerOf(::DiaryWorker)
    workerOf(::DiarySchedulerWorker)
    workerOf(::AgentTaskWorker) // 新增：注册自动化任务 Worker


}
