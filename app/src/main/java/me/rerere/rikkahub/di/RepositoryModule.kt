package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.core.data.ai.EmbeddingService as IEmbeddingService
import me.rerere.rikkahub.core.data.repository.*
import me.rerere.rikkahub.core.data.db.repository.GenMediaRepository
import me.rerere.rikkahub.discover.repo.ScheduleRepository
import me.rerere.rikkahub.discover.repo.BookRepository
import org.koin.dsl.module

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        EmbeddingService(get(), get())
    }

    single<IEmbeddingService> {
        get<EmbeddingService>()
    }

    single {
        MemoryRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        DiaryRepository(get())
    }

    single {
        ScheduleRepository(scheduleDAO = get())
    }

    single {
        AgentTaskRepository(agentTaskDAO = get())
    }

    single {
        BookRepository(bookDAO = get())
    }

    single {
        AssistantExtendedStateRepository(assistantExtendedStateDAO = get())
    }

    single {
        MilestoneRepository(milestoneDAO = get())
    }

    single {
        UserDeviceStateRepository(userDeviceStateDAO = get())
    }

    single {
        AgentMonitorTaskRepository(agentMonitorTaskDAO = get())
    }

    single {
        FavoriteRepository(favoriteDao = get())
    }
}
