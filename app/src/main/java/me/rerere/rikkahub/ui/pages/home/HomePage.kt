package me.rerere.rikkahub.ui.pages.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.ui.components.ui.ItemPosition
import me.rerere.rikkahub.ui.components.ui.PhysicsSwipeToDelete
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.ui.pages.assistant.AssistantCreationSheet
import me.rerere.rikkahub.ui.pages.chat.ChatListVM
import me.rerere.rikkahub.ui.pages.discover.DiscoverPage
import me.rerere.rikkahub.ui.pages.setting.ProviderConfigWarningCard
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid
import com.airbnb.lottie.compose.*
import com.airbnb.lottie.LottieProperty
import androidx.compose.ui.graphics.toArgb

enum class HomeTab {
    CHATS, DISCOVER, ME
}

@Composable
fun HomePage() {
    var currentTab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
    val navController = LocalNavController.current

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == HomeTab.CHATS,
                    onClick = { currentTab = HomeTab.CHATS },
                    icon = { Icon(Icons.Rounded.ChatBubble, null) },
                    label = { Text(stringResource(R.string.chat_page_title)) }
                )
                NavigationBarItem(
                    selected = currentTab == HomeTab.DISCOVER,
                    onClick = { currentTab = HomeTab.DISCOVER },
                    icon = { Icon(Icons.Rounded.Explore, null) },
                    label = { Text(stringResource(R.string.discover_page_title)) }
                )
                NavigationBarItem(
                    selected = currentTab == HomeTab.ME,
                    onClick = { currentTab = HomeTab.ME },
                    icon = { Icon(Icons.Rounded.Person, null) },
                    label = { Text(stringResource(R.string.settings)) }
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(bottom = innerPadding.calculateBottomPadding())
                .fillMaxSize()
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "home_tab_content"
            ) { tab ->
                when (tab) {
                    HomeTab.CHATS -> AgentListPage()
                    HomeTab.DISCOVER -> DiscoverPage()
                    HomeTab.ME -> SettingPage()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListPage() {
    val chatVm: ChatListVM = koinViewModel()
    val assistantVm: AssistantVM = koinViewModel()
    val navController = LocalNavController.current
    val settings by assistantVm.settings.collectAsStateWithLifecycle()
    val lastMessages by chatVm.assistantsLastMessages.collectAsStateWithLifecycle()

    // 获取转场加载状态
    val isSwitchingMode by chatVm.isSwitchingMode.collectAsStateWithLifecycle()

    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val repo = org.koin.compose.koinInject<me.rerere.rikkahub.core.data.repository.ConversationRepository>()
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)

    val createState = useEditState<Assistant> {
        assistantVm.addAssistant(it)
    }
    val context = LocalContext.current
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val res = me.rerere.rikkahub.utils.AssistantExportImport.parseImport(uri, context)
                    when (res) {
                        is me.rerere.rikkahub.utils.AssistantExportImport.ImportResult.Success -> {
                            assistantVm.addAssistant(res.assistant)
                            toaster.show("Character Imported")
                        }

                        is me.rerere.rikkahub.utils.AssistantExportImport.ImportResult.Configurable -> {
                            assistantVm.addAssistant(res.assistant)
                            toaster.show("Character Imported (${res.assistant.name})")
                        }

                        is me.rerere.rikkahub.utils.AssistantExportImport.ImportResult.Error -> {
                            toaster.show("Import Failed: ${res.message}")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    toaster.show("Error: ${e.localizedMessage}")
                }
            }
        }
    }

    val mainAgents = remember(settings.assistants) {
        settings.assistants.filter { it.isMain }.distinctBy { it.id }
    }
    val otherAgents = remember(settings.assistants, mainAgents) {
        val mainIds = mainAgents.map { it.id }.toSet()
        settings.assistants.filter { !it.isMain && it.id !in mainIds }.distinctBy { it.id }
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val offset = if (mainAgents.isNotEmpty()) mainAgents.size + 1 else 0
        val fromIndex = from.index - offset
        val toIndex = to.index - offset

        if (fromIndex >= 0 && toIndex >= 0 && fromIndex < otherAgents.size && toIndex < otherAgents.size) {
            val newOthers = otherAgents.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            assistantVm.updateSettings(settings.copy(assistants = mainAgents + newOthers))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.chat_page_title),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Screen.AssistantSearch) }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = {
                            createState.open(
                                Assistant(
                                    chatModelId = settings.chatModelId,
                                    embeddingModelId = settings.embeddingModelId,
                                    memoryModelId = settings.memoryModelId,
                                    diaryModelId = settings.diaryModelId,
                                )
                            )
                        }) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.95f)
                    )
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (settings.isNotConfigured()) {
                    item {
                        ProviderConfigWarningCard(navController)
                    }
                }

                itemsIndexed(mainAgents, key = { _, assistant -> assistant.id }) { index, assistant ->
                    PhysicsSwipeToDelete(
                        onDelete = {},
                        position = if (mainAgents.size == 1) ItemPosition.ONLY else if (index == 0) ItemPosition.FIRST else if (index == mainAgents.lastIndex) ItemPosition.LAST else ItemPosition.MIDDLE,
                        deleteEnabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        groupCornerRadius = 24.dp
                    ) { _ ->
                        AgentItem(
                            assistant = assistant,
                            lastMessage = lastMessages[assistant.id] ?: "",
                            onClick = {
                                scope.launch {
                                    chatVm.selectAssistant(assistant.id)
                                    val lastConv = repo.getConversationsOfAssistant(
                                        assistant.id,
                                        isVirtual = assistant.isVirtualWorldMode
                                    )
                                        .firstOrNull()
                                        ?.firstOrNull()
                                    val chatId = lastConv?.id ?: Uuid.random()
                                    if (assistant.isVirtualWorldMode) {
                                        navController.navigate(Screen.VirtualWorld(id = chatId.toString()))
                                    } else {
                                        navController.navigate(Screen.Chat(id = chatId.toString()))
                                    }
                                }
                            },
                            onModeToggle = {
                                chatVm.toggleVirtualMode(assistant)
                            }
                        )
                    }
                }

                if (mainAgents.isNotEmpty() && otherAgents.isNotEmpty()) {
                    item { Spacer(Modifier.height(12.dp)) }
                }

                itemsIndexed(otherAgents, key = { _, assistant -> assistant.id }) { index, assistant ->
                    val position = when {
                        otherAgents.size == 1 -> ItemPosition.ONLY
                        index == 0 -> ItemPosition.FIRST
                        index == otherAgents.lastIndex -> ItemPosition.LAST
                        else -> ItemPosition.MIDDLE
                    }

                    ReorderableItem(
                        state = reorderableState,
                        key = assistant.id
                    ) { isDragging ->
                        PhysicsSwipeToDelete(
                            position = position,
                            deleteEnabled = true,
                            onDelete = {
                                assistantVm.removeAssistant(assistant)
                                toaster.show(
                                    message = context.getString(R.string.assistant_deleted, assistant.name),
                                    action = me.rerere.rikkahub.ui.components.ui.ToastAction(
                                        label = context.getString(R.string.undo),
                                        onClick = { assistantVm.undoRemoveAssistant(assistant) }
                                    )
                                )
                            },
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .fillMaxWidth(),
                            groupCornerRadius = 24.dp
                        ) { _ ->
                            AgentItem(
                                assistant = assistant,
                                lastMessage = lastMessages[assistant.id] ?: "",
                                onClick = {
                                    scope.launch {
                                        chatVm.selectAssistant(assistant.id)
                                        val lastConv = repo.getConversationsOfAssistant(
                                            assistant.id,
                                            isVirtual = assistant.isVirtualWorldMode
                                        )
                                            .firstOrNull()
                                            ?.firstOrNull()
                                        val chatId = lastConv?.id ?: Uuid.random()
                                        if (assistant.isVirtualWorldMode) {
                                            navController.navigate(Screen.VirtualWorld(id = chatId.toString()))
                                        } else {
                                            navController.navigate(Screen.Chat(id = chatId.toString()))
                                        }
                                    }
                                },
                                onCopy = { assistantVm.copyAssistant(assistant) },
                                dragHandle = {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.longPressDraggableHandle(
                                            onDragStarted = { haptics.perform(HapticPattern.Pop) },
                                            onDragStopped = { haptics.perform(HapticPattern.Thud) }
                                        )
                                    ) {
                                        Icon(Icons.Rounded.DragIndicator, null)
                                    }
                                }
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp, bottom = 48.dp)
                            .padding(horizontal = 32.dp)
                            .alpha(0.35f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.app_slogan),
                            style = MaterialTheme.typography.bodySmall.copy(
                                lineHeight = 22.sp,
                                letterSpacing = 0.8.sp,
                                fontSize = 11.sp
                            ),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        TransitionOverlay(
            visible = isSwitchingMode,
            assistant = mainAgents.firstOrNull()
        )
    }

    AssistantCreationSheet(
        state = createState,
        onImportClick = {
            createState.dismiss()
            importLauncher.launch(arrayOf("*/*"))
        }
    )
}

@Composable
private fun TransitionOverlay(
    visible: Boolean,
    assistant: Assistant?
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(600))
    ) {
        val lottieComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.world_change))
        val lottieProgress by animateLottieCompositionAsState(
            composition = lottieComposition,
            iterations = LottieConstants.IterateForever
        )

        val infiniteTransition = rememberInfiniteTransition(label = "portal")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 使用原色彩渲染 Lottie 动画
                LottieAnimation(
                    composition = lottieComposition,
                    progress = { lottieProgress },
                    modifier = Modifier.size(200.dp)
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (assistant?.isVirtualWorldMode == true) "正在进入虚拟模式..." else "正在返回现实世界...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.alpha(alpha)
                    )
                }

                Spacer(Modifier.height(16.dp))

                LinearProgressIndicator(
                    modifier = Modifier
                        .width(140.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )
            }
        }
    }
}

@Composable
fun AgentItem(
    assistant: Assistant,
    lastMessage: String,
    onClick: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onModeToggle: (() -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null
) {
    val haptics = rememberPremiumHaptics()
    val isVirtual = assistant.isVirtualWorldMode
    val animatedColor by animateColorAsState(
        if (isVirtual) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f) else Color.Transparent,
        label = "bg_color"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = animatedColor,
        shape = RoundedCornerShape(0.dp)
    ) {
        Box {
            if (isVirtual) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                                )
                            )
                        )
                )
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UIAvatar(
                    name = assistant.name,
                    value = assistant.avatar,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = assistant.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isVirtual) MaterialTheme.colorScheme.primary else Color.Unspecified
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (assistant.isMain) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = "Master",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (isVirtual) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "虚拟",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Text(
                        text = lastMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (onModeToggle != null && assistant.isMain) {
                    IconButton(onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onModeToggle()
                    }) {
                        Icon(
                            imageVector = if (isVirtual) Icons.Rounded.Public else Icons.Rounded.PublicOff,
                            contentDescription = "Toggle Virtual Mode",
                            modifier = Modifier.size(22.dp),
                            tint = if (isVirtual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.6f
                            )
                        )
                    }
                }

                if (onCopy != null) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        )
                    }
                }
                dragHandle?.invoke()
            }
        }
    }
}
