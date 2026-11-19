package me.rerere.rikkahub.ui.components.ime

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp

/**
 * 键盘感知布局通用组件（2026-11-20 沉淀）。
 *
 * 背景：带输入框的页面反复出现「键盘遮挡输入框 / 内容滚不到顶」的问题，
 * 根源是各页面手写 imePadding 组合、口径不一。带输入框的新页面/弹窗
 * **必须**使用以下组件，禁止再手写等价 Modifier 组合：
 *
 * - 表单页（多卡片/多输入框，内容可滚动）→ [ImeAwareScrollColumn]
 * - 全屏大编辑器（单个占满剩余空间的编辑框）→ [ImeAwareEditorColumn]
 *   （编辑框用 Modifier.weight(1f) 放置，长内容在编辑框内部滚动，
 *   视口始终完整在屏幕内——参考 FullScreenSystemPromptEditor）
 * - 带输入框的 AlertDialog → 内容中调用 [DialogImeResizeEffect]
 *
 * 已知反例（禁止回退）：graphicsLayer 整页上移——编辑框视口会被推出
 * 屏幕外，内容顶部永远滚不回可视区。
 */

/**
 * 表单页滚动容器：内容底部随键盘避让（imePadding），获焦输入框通过
 * BringIntoView 自动滚入可视区。等价
 * `Column(Modifier.fillMaxSize().imePadding().verticalScroll(...))`。
 */
@Composable
fun ImeAwareScrollColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * 全屏编辑器容器：编辑框（调用方用 Modifier.weight(1f) 放置）随键盘收缩，
 * 完整落在键盘上方；标题栏/工具条等其余内容保持可见。不自带滚动——
 * 长内容由编辑框内部滚动承载。
 */
@Composable
fun ImeAwareEditorColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        content = content,
    )
}

/**
 * 强制当前弹窗窗口随键盘 RESIZE。AlertDialog 窗口默认 PAN 上推，部分 ROM
 * 上叠加内容 imePadding 会双重补偿，导致输入框顶部被裁切且滚不回顶。
 * 在带输入框的 AlertDialog / BasicAlertDialog 的内容中调用一次即可。
 */
@Composable
fun DialogImeResizeEffect() {
    val view = LocalView.current
    LaunchedEffect(view) {
        var parent = view.parent
        while (parent != null && parent !is DialogWindowProvider) {
            parent = (parent as? android.view.View)?.parent
        }
        (parent as? DialogWindowProvider)?.window
            ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
