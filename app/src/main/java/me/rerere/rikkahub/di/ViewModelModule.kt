package me.rerere.rikkahub.di

import me.rerere.rikkahub.ui.activity.TextSelectionVM
import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.ui.pages.chat.ChatListVM
import me.rerere.rikkahub.ui.pages.developer.DeveloperVM
import me.rerere.rikkahub.ui.pages.imggen.ImgGenVM
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkahub.ui.pages.menu.MenuVM
import me.rerere.rikkahub.ui.pages.discover.DiaryVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            updateChecker = get(),
            appScope = get(),
            memoryRepo = get()
        )
    }
    viewModel<SettingVM> {
        SettingVM(
            settingsStore = get(),
            mcpManager = get(),
            context = get(),
            okHttpClient = get()
        )
    }
    viewModelOf(::ChatListVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get<MemoryRepository>(),
            conversationRepository = get<ConversationRepository>(),
            context = get(),
            chatEpisodeDAO = get<ChatEpisodeDAO>(),
            providerManager = get(),
            agentTaskRepository = get<AgentTaskRepository>()
        )
    }
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModel<BackupVM> {
        BackupVM(
            settingsStore = get(),
            webdavSync = get(),
            context = get()
        )
    }
    viewModelOf(::ImgGenVM)
    viewModelOf(::DeveloperVM)
    viewModelOf(::MenuVM)
    viewModelOf(::TextSelectionVM)
    viewModelOf(::DiaryVM)
}
