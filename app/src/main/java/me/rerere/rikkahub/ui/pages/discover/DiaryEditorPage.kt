package me.rerere.rikkahub.ui.pages.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryEditorPage(
    diaryId: String,
    vm: DiaryVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val diary by vm.getDiaryById(diaryId).collectAsStateWithLifecycle()

    // OneUI 风格标题栏必须配套使用 scrollBehavior
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 局部编辑状态
    var content by remember { mutableStateOf("") }
    var initialContent by remember { mutableStateOf<String?>(null) }

    // 当当日记内容加载完成后初始化
    LaunchedEffect(diary) {
        diary?.let {
            if (initialContent == null) {
                content = it.content
                initialContent = it.content
            }
        }
    }

    // 保存并返回逻辑
    val saveAndBack = {
        if (content != initialContent && initialContent != null) {
            vm.updateDiaryContent(diaryId, content)
        }
        navController.popBackStack()
    }

    // 拦截物理返回键或手势返回（左滑返回）实现自动保存
    BackHandler {
        saveAndBack()
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.diary_edit),
                scrollBehavior = scrollBehavior, // 修复：传入必填的 scrollBehavior
                navigationIcon = {
                    // BackButton 已经支持自定义 onClick 了
                    BackButton(onClick = { saveAndBack() })
                },
                actions = {
                    IconButton(onClick = { saveAndBack() }) {
                        Icon(Icons.Rounded.Check, null)
                    }
                }
            )
        },
        // 连接嵌套滚动，使标题栏能随滑动折叠
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.diary_edit_save_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            BasicTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (content.isEmpty()) {
                        Text(
                            text = "...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}
