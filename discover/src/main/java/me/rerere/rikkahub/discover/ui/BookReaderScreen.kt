package me.rerere.rikkahub.discover.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.discover.R
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(
    bookId: Int,
    onBack: () -> Unit,
    viewModel: BookViewModel = koinViewModel()
) {
    val bookWithProgress by viewModel.getBookWithProgress(bookId).collectAsState(null)
    val scrollState = rememberScrollState()

    // 模拟 AI 想法
    var aiThought by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bookWithProgress?.book?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { /* 呼叫 AI 深度讨论 */ }) {
                        Icon(Icons.Rounded.ChatBubbleOutline, null)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 阅读正文
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "这里是书籍内容占位符...\n\n" +
                           "为了节省 Token，我们将仅发送当前屏幕内的文字给 AI 助手。\n" +
                           "系统会自动维护一个 L1 摘要记录在数据库中，确保 AI 记得前面的剧情。\n" +
                           "知识类书籍将开启 RAG 模式，从文中自动提取术语。",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 28.sp,
                        letterSpacing = 0.5.sp
                    )
                )

                // 模拟翻页后的 AI 主动交互
                LaunchedEffect(scrollState.value) {
                    if (scrollState.value > 500 && aiThought == null) {
                        aiThought = "这段描写很有张力，你觉得主角在这里的选择正确吗？"
                    }
                }
            }

            // AI 陪伴气泡 (节省 Token 的秘密：低频、异步、轻量)
            aiThought?.let { thought ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
                    shape = AppShapes.CardLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = thought,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { aiThought = null }) {
                            Text("懂了")
                        }
                    }
                }
            }
        }
    }
}
