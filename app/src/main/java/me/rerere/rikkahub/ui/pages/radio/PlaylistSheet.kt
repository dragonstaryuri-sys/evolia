package me.rerere.rikkahub.ui.pages.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.common.ui.components.EmptyPlaceholder
import me.rerere.rikkahub.service.media.MediaPlaybackController
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.media.PlaybackItem
import me.rerere.rikkahub.service.media.PlaybackMode
import me.rerere.rikkahub.ui.theme.AppShapes
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 播放列表抽屉（节目详情/首页/栏目详情共用）：
 * - 右上角：播放模式切换（顺序停止 / 列表循环(默认) / 单集循环 / 随机）；
 * - 条目左侧拖拽把手，长按拖动自由排序；
 * - 点击条目切播（抽屉保持打开）；移除正在播放的条目会自动前进到下一首。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(
    controller: MediaPlaybackController,
    onDismiss: () -> Unit,
) {
    val playerState by controller.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = AppShapes.BottomSheet
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "播放列表",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // 播放模式切换
                Box {
                    var modeMenuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { modeMenuOpen = true }) {
                        Icon(
                            imageVector = when (playerState.playMode) {
                                PlaybackMode.LIST_LOOP -> Icons.Rounded.Repeat
                                PlaybackMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
                                PlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
                                PlaybackMode.SEQUENTIAL -> Icons.Rounded.SwipeVertical
                            },
                            contentDescription = "播放模式"
                        )
                    }
                    DropdownMenu(
                        expanded = modeMenuOpen,
                        onDismissRequest = { modeMenuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = AppShapes.ButtonSquared
                    ) {
                        PlaybackMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = when (mode) {
                                            PlaybackMode.LIST_LOOP -> "列表循环"
                                            PlaybackMode.REPEAT_ONE -> "单集循环"
                                            PlaybackMode.SHUFFLE -> "随机播放"
                                            PlaybackMode.SEQUENTIAL -> "顺序停止"
                                        } + if (playerState.playMode == mode) " ✓" else ""
                                    )
                                },
                                onClick = {
                                    modeMenuOpen = false
                                    controller.setPlayMode(mode)
                                }
                            )
                        }
                    }
                }
            }
            Text(
                text = "当前模式：" + when (playerState.playMode) {
                    PlaybackMode.LIST_LOOP -> "列表循环（播完最后一集回到第一集）"
                    PlaybackMode.REPEAT_ONE -> "单集循环"
                    PlaybackMode.SHUFFLE -> "随机播放"
                    PlaybackMode.SEQUENTIAL -> "顺序播放（播完停止）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val queue = playerState.queue
            if (queue.isEmpty()) {
                EmptyPlaceholder(
                    text = stringResource(R.string.radio_queue_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                )
            } else {
                val listState = rememberLazyListState()
                val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                    controller.moveQueueItem(from.index, to.index)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                        val isCurrent = item.id == playerState.currentItem?.id
                        ReorderableItem(
                            state = reorderState,
                            key = item.id,
                            animateItemModifier = Modifier
                        ) { isDragging ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 拖拽把手（长按拖动排序）
                                Icon(
                                    imageVector = Icons.Rounded.DragIndicator,
                                    contentDescription = "拖动排序",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .longPressDraggableHandle(
                                            onDragStarted = {},
                                            onDragStopped = {}
                                        )
                                )
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(24.dp)
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1
                                    )
                                    if (item.subtitle.isNotBlank()) {
                                        Text(
                                            text = item.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                                IconButton(onClick = { controller.seekToQueueItem(index) }) {
                                    Icon(
                                        imageVector = if (isCurrent && playerState.isPlaying) {
                                            Icons.Rounded.Pause
                                        } else {
                                            Icons.Rounded.PlayArrow
                                        },
                                        contentDescription = "播放该集"
                                    )
                                }
                                IconButton(onClick = { controller.removeItem(item.sourceType, item.id) }) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "从队列移除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
