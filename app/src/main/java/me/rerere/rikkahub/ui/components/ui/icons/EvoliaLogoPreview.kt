package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF) // 白色背景
@Composable
fun EvoliaLogoExportPreview() {
    Box(
        modifier = Modifier.size(512.dp), // 设置你想要的分辨率大小
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_launcher_evolia_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = Color.Unspecified // 关键：保持原有的渐变色
        )
    }
}
