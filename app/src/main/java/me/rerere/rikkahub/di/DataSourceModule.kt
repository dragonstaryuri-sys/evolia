package me.rerere.rikkahub.di

import androidx.room.Room
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.EvoliaAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.QuickSettingsCache
import me.rerere.rikkahub.data.datastore.SecureStore
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.sync.WebdavSync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    single {
        QuickSettingsCache(context = get())
    }

    single {
        SecureStore(context = get())
    }

    single {
        SecretKeyManager(secureStore = get())
    }

    single {
        SettingsStore(context = get(), scope = get(), quickCache = get(), secretKeyManager = get())
    }

    // 注入供 :discover 模块使用的助手列表流，实现解耦
    single<StateFlow<List<Assistant>>> {
        get<SettingsStore>().settingsFlow
            .map { it.assistants }
            .stateIn(
                scope = get<AppScope>(),
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )
    }

    single {
        Room.databaseBuilder(get(), AppDatabase::class.java, "rikka_hub")
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().chatEpisodeDao()
    }

    single {
        get<AppDatabase>().embeddingCacheDao()
    }

    single {
        get<AppDatabase>().dailyActivityDao()
    }

    single {
        get<AppDatabase>().agentDiaryDao()
    }

    single {
        get<AppDatabase>().scheduleDao()
    }

    single {
        get<AppDatabase>().agentTaskDao()
    }

    single {
        get<AppDatabase>().chatSegmentDao()
    }

    single {
        get<AppDatabase>().tokenUsageDao()
    }

    single {
        get<AppDatabase>().bookDao()
    }

    single { McpManager(settingsStore = get(), appScope = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            conversationRepo = get(),
            aiLoggingManager = get(),
            embeddingService = get(),
            chatSegmentDAO = get(),
            appScope = get()
        )
    }

    single<OkHttpClient> {
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)
                    .addHeader(HttpHeaders.UserAgent, "Evolia-Android/${BuildConfig.VERSION_NAME}")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(AIRequestInterceptor(remoteConfig = get()))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        ProviderManager(client = get())
    }

    single {
        WebdavSync(settingsStore = get(), json = get(), context = get(), secureStore = get(), secretKeyManager = get())
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<EvoliaAPI> {
        get<Retrofit>().create(EvoliaAPI::class.java)
    }
}
