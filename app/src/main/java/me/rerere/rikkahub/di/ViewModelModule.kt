package me.rerere.rikkahub.di

import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkahub.ui.pages.assistant.AssistantImportVM
import me.rerere.rikkahub.ui.pages.assistant.AssistantImportMemoryVM
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.ui.pages.chat.ChatListVM
import me.rerere.rikkahub.ui.pages.chat.ChatHistorySearchVM
import me.rerere.rikkahub.ui.pages.developer.DeveloperVM
import me.rerere.rikkahub.ui.pages.imggen.ImgGenVM
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.pages.setting.PermissionVM
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkahub.ui.pages.menu.MenuVM
import me.rerere.rikkahub.ui.pages.discover.DiaryVM
import me.rerere.rikkahub.ui.pages.favorites.FavoritesVM
import me.rerere.rikkahub.ui.pages.favorites.FavoriteDetailVM
import me.rerere.rikkahub.discover.ui.BookViewModel
import me.rerere.rikkahub.discover.ui.ScheduleViewModel
import me.rerere.rikkahub.discover.ui.TokenReportVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        val id: String = params.get()
        val targetMessageId: String? = params.getOrNull()

        ChatVM(
            id = id,
            targetMessageId = targetMessageId,
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            updateChecker = get(),
            appScope = get(),
            memoryRepo = get(),
            favoriteRepo = get()
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
    viewModelOf(::AssistantImportVM)

    viewModel<AssistantImportMemoryVM> { params ->
        AssistantImportMemoryVM(
            assistantId = params.get(),
            isMain = params.get(),
            totalSessions = params.get(),
            settingsStore = get(),
            memoryRepo = get(),
            milestoneRepo = get(),
            conversationRepo = get(),
            chatService = get()
        )
    }

    viewModel<AssistantDetailVM> { params ->
        AssistantDetailVM(
            id = params.get(),
            settingsStore = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            context = get(),
            chatEpisodeDAO = get(),
            providerManager = get(),
            agentTaskRepository = get(),
            extendedStateRepository = get(),
            agentMonitorTaskRepository = get(),
            embeddingService = get()
        )
    }
    viewModel<ChatHistorySearchVM> { params ->
        ChatHistorySearchVM(
            assistantId = params.get(),
            conversationRepository = get()
        )
    }
    viewModel<ShareHandlerVM> { params ->
        ShareHandlerVM(
            text = params.get(),
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

    // 升级后的 DiaryVM 注入
    viewModel<DiaryVM> {
        DiaryVM(
            app = get(),
            settingsStore = get(),
            diaryRepo = get(),
            chatService = get(),
            scheduleDao = get(),
            conversationRepo = get()
        )
    }

    viewModelOf(::ScheduleViewModel)
    viewModelOf(::TokenReportVM)
    viewModelOf(::PermissionVM)
    viewModelOf(::FavoritesVM)
    viewModel<FavoriteDetailVM> { params ->
        FavoriteDetailVM(
            id = params.get(),
            favoriteRepository = get()
        )
    }
    viewModel {
        BookViewModel(
            bookRepository = get(),
            assistants = get()
        )
    }
}
