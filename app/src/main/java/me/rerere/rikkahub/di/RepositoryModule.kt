package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.core.data.ai.EmbeddingService as IEmbeddingService
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.db.repository.GenMediaRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import org.koin.dsl.module

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get())
    }

    single {
        EmbeddingService(get(), get())
    }

    single<IEmbeddingService> {
        get<EmbeddingService>()
    }

    single {
        MemoryRepository(get(), get(), get(), get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        DiaryRepository(get())
    }
}
