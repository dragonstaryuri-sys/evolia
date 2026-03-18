package me.rerere.rikkahub

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import me.rerere.rikkahub.ui.components.ui.AppToasterHost
import me.rerere.rikkahub.ui.components.ui.rememberAppToasterState
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.TTSController
import me.rerere.rikkahub.ui.context.LocalAnimatedVisibilityScope
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.pages.assistant.AssistantPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import me.rerere.rikkahub.ui.pages.backup.BackupPage
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.developer.DeveloperPage
import me.rerere.rikkahub.ui.pages.imggen.ImageGenPage
import me.rerere.rikkahub.ui.pages.menu.MenuPage
import me.rerere.rikkahub.ui.pages.setting.SettingAboutPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayPage
import me.rerere.rikkahub.ui.pages.setting.SettingMcpPage
import me.rerere.rikkahub.ui.pages.setting.SettingModelPage
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingTTSPage
import me.rerere.rikkahub.ui.pages.setting.SettingRpOptimizationsPage
import me.rerere.rikkahub.ui.pages.setting.SettingPromptInjectionsPage
import me.rerere.rikkahub.ui.pages.setting.SettingModesPage
import me.rerere.rikkahub.ui.pages.setting.SettingLorebooksPage
import me.rerere.rikkahub.ui.pages.setting.SettingLorebookDetailPage
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.pages.setting.SettingAndroidIntegrationPage
import me.rerere.rikkahub.ui.pages.setting.SettingUICustomizationPage
import me.rerere.rikkahub.ui.pages.setting.SettingFontsPage
import me.rerere.rikkahub.ui.pages.home.HomePage
import me.rerere.rikkahub.ui.pages.discover.DiscoverPage
import me.rerere.rikkahub.ui.pages.discover.DiaryListPage
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import me.rerere.rikkahub.utils.fileSizeToString
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"

data class TextSelectionData(
    val navigateTo: String?,
    val selectedText: String?,
    val aiResponse: String?,
    val userPrompt: String?,
    val selectionAssistantId: String?
)

class RouteActivity : AppCompatActivity() {
    private val highlighter by inject<Highlighter>()
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private val chatService by inject<me.rerere.rikkahub.service.ChatService>()
    private var navStack by mutableStateOf<NavHostController?>(null)
    private var pendingAssistantId by mutableStateOf<String?>(null)
    private var pendingTextSelection by mutableStateOf<TextSelectionData?>(null)
    private var pendingConversationId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)

        val intentAssistantId = intent?.getStringExtra("assistantId")
        val intentConversationId = intent?.getStringExtra("conversationId")

        val continueConversation = intent?.getBooleanExtra("continue_conversation", false) ?: false
        if (continueConversation) {
            pendingTextSelection = TextSelectionData(
                navigateTo = intent?.getStringExtra("navigate_to"),
                selectedText = intent?.getStringExtra("selected_text"),
                aiResponse = intent?.getStringExtra("ai_response"),
                userPrompt = intent?.getStringExtra("user_prompt"),
                selectionAssistantId = intent?.getStringExtra("selection_assistant_id")
            )
        }

        setContent {
            val navStack = rememberNavController()
            this.navStack = navStack
            ShareHandler(navStack)
            TextSelectionHandler(navStack)
            NotificationHandler(navStack)
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .memoryCache {
                            MemoryCache.Builder()
                                .maxSizePercent(context, 0.25)
                                .build()
                        }
                        .diskCache {
                            DiskCache.Builder()
                                .directory(context.filesDir.resolve("icon_cache").toOkioPath())
                                .maxSizeBytes(50 * 1024 * 1024)
                                .build()
                        }
                        .components {
                            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes(navStack)
            }
        }

        if (intentAssistantId != null) {
            lifecycleScope.launch {
                while (navStack == null) {
                    kotlinx.coroutines.delay(50)
                }
                try {
                    val assistantId = Uuid.parse(intentAssistantId)
                    settingsStore.updateAssistant(assistantId)
                    settingsStore.markAssistantUsed(assistantId)
                    navStack?.navigate(Screen.Chat(Uuid.random().toString())) {
                        popUpTo(0) { inclusive = true }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        if (intentConversationId != null) {
            pendingConversationId = intentConversationId
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    @Composable
    private fun ShareHandler(navBackStack: NavHostController) {
        val shareIntent = remember {
            Intent().apply {
                action = intent?.action
                putExtra(Intent.EXTRA_TEXT, intent?.getStringExtra(Intent.EXTRA_TEXT))
                putExtra(Intent.EXTRA_STREAM, intent?.getStringExtra(Intent.EXTRA_STREAM))
            }
        }

        LaunchedEffect(navBackStack) {
            if (shareIntent.action == Intent.ACTION_SEND) {
                val text = shareIntent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val imageUri = shareIntent.getStringExtra(Intent.EXTRA_STREAM)
                navBackStack.navigate(Screen.ShareHandler(text, imageUri))
            }
        }
    }

    @Composable
    private fun NotificationHandler(navBackStack: NavHostController) {
        val conversationIdStr = pendingConversationId
        LaunchedEffect(conversationIdStr) {
            if (conversationIdStr != null) {
                pendingConversationId = null
                navBackStack.navigate(Screen.Chat(conversationIdStr))
            }
        }
    }

    @Composable
    private fun TextSelectionHandler(navBackStack: NavHostController) {
        val data = pendingTextSelection
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

        LaunchedEffect(data) {
            if (data != null) {
                pendingTextSelection = null
                try {
                    val conversationId = Uuid.random()
                    val userContent = buildString {
                        if (!data.selectedText.isNullOrBlank()) {
                            append(data.selectedText)
                        }
                        if (!data.userPrompt.isNullOrBlank()) {
                            append("\n\n")
                            append(data.userPrompt)
                        }
                    }

                    val messages = mutableListOf<me.rerere.rikkahub.core.data.model.MessageNode>()
                    if (userContent.isNotBlank()) {
                        val userMessage = me.rerere.ai.ui.UIMessage.user(userContent.trim())
                        messages.add(me.rerere.rikkahub.core.data.model.MessageNode.of(userMessage))
                    }
                    val aiResponse = data.aiResponse
                    if (!aiResponse.isNullOrBlank()) {
                        val assistantMessage = me.rerere.ai.ui.UIMessage.assistant(aiResponse)
                        messages.add(me.rerere.rikkahub.core.data.model.MessageNode.of(assistantMessage))
                    }

                    if (messages.isNotEmpty()) {
                        val assistantId = data.selectionAssistantId?.takeIf { it.isNotBlank() }?.let {
                            try { Uuid.parse(it) } catch (e: Exception) { null }
                        } ?: settings.assistantId

                        val conversation = me.rerere.rikkahub.core.data.model.Conversation.ofId(
                            id = conversationId,
                            assistantId = assistantId,
                            messages = messages
                        )
                        chatService.saveConversation(conversationId, conversation)
                        navBackStack.navigate(Screen.Chat(id = conversationId.toString()))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("conversationId")?.let { text ->
            navStack?.navigate(Screen.Chat(text))
        }
        intent.getStringExtra("assistantId")?.let { assistantIdStr ->
            lifecycleScope.launch {
                try {
                    val assistantId = Uuid.parse(assistantIdStr)
                    settingsStore.updateAssistant(assistantId)
                    settingsStore.markAssistantUsed(assistantId)
                    navStack?.navigate(Screen.Chat(Uuid.random().toString())) {
                        popUpTo(0) { inclusive = true }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Composable
    fun AppRoutes(navBackStack: NavHostController) {
        val toastState = rememberAppToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides navBackStack,
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalHighlighter provides highlighter,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
            ) {
                LaunchedEffect(Unit) {
                    val prefs = this@RouteActivity.getSharedPreferences("backup_cleanup", MODE_PRIVATE)
                    val unsupportedBytes = prefs.getLong("unsupported_bytes", 0)
                    val issuesFixed = prefs.getInt("issues_fixed", 0)
                    val skippedRows = prefs.getInt("db_skipped_rows", 0)

                    if (unsupportedBytes > 0 || issuesFixed > 0 || skippedRows > 0) {
                        prefs.edit().clear().apply()
                        val parts = mutableListOf<String>()
                        if (unsupportedBytes > 0) parts.add("${unsupportedBytes.fileSizeToString()} of unsupported data")
                        if (issuesFixed > 0) parts.add("$issuesFixed invalid references")
                        if (skippedRows > 0) parts.add("$skippedRows corrupt items removed")
                        toastState.show("Import completed: ${parts.joinToString(", ")}", type = me.rerere.rikkahub.ui.components.ui.ToastType.Info)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                TTSController()
                NavHost(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    startDestination = Screen.Home,
                    navController = navBackStack,
                    enterTransition = { slideInHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(animationSpec = tween(150)) },
                    exitTransition = { slideOutHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(animationSpec = tween(100)) },
                    popEnterTransition = { slideInHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(animationSpec = tween(150)) },
                    popExitTransition = { slideOutHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(animationSpec = tween(100)) }
                ) {
                    composable<Screen.Home> {
                        HomePage()
                    }

                    composable<Screen.Chat>(
                        enterTransition = { fadeIn() },
                        exitTransition = { fadeOut() },
                    ) { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.Chat>()
                        // 增加异常捕获以定位具体的错误 ID
                        val uuid = try {
                            Uuid.parse(route.id)
                        } catch (e: Exception) {
                            android.util.Log.e("RouteActivity", "Invalid UUID: ${route.id}")
                            Uuid.random() // 或者跳转回首页
                        }
                        ChatPage(
                            id = Uuid.parse(route.id),
                            text = route.text,
                            files = route.files.map { it.toUri() },
                            searchQuery = route.searchQuery
                        )
                    }

                    composable<Screen.ShareHandler> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.ShareHandler>()
                        ShareHandlerPage(text = route.text, image = route.streamUri)
                    }

                    composable<Screen.Assistant> {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                            AssistantPage()
                        }
                    }

                    composable<Screen.AssistantDetail> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.AssistantDetail>()
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                            AssistantDetailPage(id = route.id, startRoute = route.startRoute, initialMemoryTab = route.initialMemoryTab, scrollToMemoryId = route.scrollToMemoryId)
                        }
                    }

                    composable<Screen.Menu> { MenuPage() }
                    composable<Screen.Setting> { SettingPage() }
                    composable<Screen.Backup> { BackupPage() }
                    composable<Screen.ImageGen> { ImageGenPage() }
                    composable<Screen.WebView> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.WebView>()
                        WebViewPage(route.url, route.content)
                    }
                    composable<Screen.SettingDisplay> { SettingDisplayPage() }
                    composable<Screen.SettingProvider> { SettingProviderPage() }
                    composable<Screen.SettingProviderDetail> {
                        val route = it.toRoute<Screen.SettingProviderDetail>()
                        SettingProviderDetailPage(id = Uuid.parse(route.providerId))
                    }
                    composable<Screen.SettingModels> { SettingModelPage() }
                    composable<Screen.SettingAbout> { SettingAboutPage() }
                    composable<Screen.SettingSearch> { SettingSearchPage() }
                    composable<Screen.SettingTTS> { SettingTTSPage() }
                    composable<Screen.SettingMcp> { SettingMcpPage() }
                    composable<Screen.SettingRpOptimizations> { SettingRpOptimizationsPage() }
                    composable<Screen.SettingPromptInjections> { SettingPromptInjectionsPage() }
                    composable<Screen.SettingModes> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.SettingModes>()
                        SettingModesPage(scrollToModeId = route.scrollToModeId)
                    }
                    composable<Screen.SettingLorebooks> { SettingLorebooksPage() }
                    composable<Screen.SettingLorebookDetail> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.SettingLorebookDetail>()
                        SettingLorebookDetailPage(id = route.id, scrollToEntryId = route.scrollToEntryId)
                    }
                    composable<Screen.Developer> { DeveloperPage() }
                    composable<Screen.SettingAndroidIntegration> { SettingAndroidIntegrationPage() }
                    composable<Screen.SettingUICustomization> { SettingUICustomizationPage() }
                    composable<Screen.SettingFonts> { SettingFontsPage() }
                    composable<Screen.Discover> { DiscoverPage() }
                    composable<Screen.DiaryList> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.DiaryList>()
                        DiaryListPage(assistantId = route.assistantId)
                    }
                }
                AppToasterHost(state = toastState)
                }
            }
        }
    }
}

sealed interface Screen {
    @Serializable
    data object Home : Screen

    @Serializable
    data class Chat(val id: String, val text: String? = null, val files: List<String> = emptyList(), val searchQuery: String? = null) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(
        val id: String,
        val startRoute: String? = null,
        val initialMemoryTab: Int? = null,
        val scrollToMemoryId: Int? = null
    ) : Screen

    @Serializable
    data object Menu : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val content: String = "") : Screen

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
    data object SettingAndroidIntegration : Screen

    @Serializable
    data object SettingUICustomization : Screen

    @Serializable
    data object SettingFonts : Screen

    @Serializable
    data object Discover : Screen

    @Serializable
    data class DiaryList(val assistantId: String? = null) : Screen
}
