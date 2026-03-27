package me.rerere.rikkahub.ui.pages.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.discover.ui.components.ScheduleCard
import me.rerere.rikkahub.discover.ui.ScheduleViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun DiscoverPage() {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 获取日程 ViewModel
    val scheduleViewModel: ScheduleViewModel = koinViewModel()
    val progress by scheduleViewModel.todayProgress.collectAsState()
    val unfinishedCount by scheduleViewModel.unfinishedCount.collectAsState()
    val allPendingSchedules by scheduleViewModel.allPendingSchedules.collectAsState()

    Column(modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        OneUITopAppBar(
            title = stringResource(R.string.discover_page_title),
            scrollBehavior = scrollBehavior
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 智能日程卡片
            item {
                ScheduleCard(
                    progress = progress,
                    unfinishedCount = unfinishedCount,
                    recentTasks = allPendingSchedules, // 这里改为传入所有待办列表（已排好序）
                    onClick = {
                        // 使用类型安全的路由进行导航
                        navController.navigate(Screen.Schedule)
                    }
                )
            }

            // 2. 现有功能：日记
            item {
                DiscoverItem(
                    title = stringResource(R.string.discover_page_diary),
                    description = stringResource(R.string.discover_page_diary_desc),
                    icon = { Icon(Icons.Rounded.Book, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        navController.navigate(Screen.DiaryList())
                    }
                )
            }

            // 3. 现有功能：社区（开发中）
            item {
                val developingText = stringResource(R.string.discover_page_developing)
                DiscoverItem(
                    title = stringResource(R.string.discover_page_forum),
                    description = stringResource(R.string.discover_page_forum_desc),
                    icon = { Icon(Icons.Rounded.Forum, null, tint = MaterialTheme.colorScheme.secondary) },
                    onClick = {
                        toaster.show(developingText)
                    }
                )
            }
        }
    }
}

@Composable
private fun DiscoverItem(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
