package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPermissionCheckPage(vm: PermissionVM = koinViewModel()) {
    val navController = LocalNavController.current
    val permissionStates by vm.permissionStates.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 当页面重新回到前台时自动刷新权限状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.checkAllPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_check_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Button(
                    onClick = { vm.checkAllPermissions() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(stringResource(R.string.permission_check_btn_check))
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(PermissionType.entries) { type ->
                val isGranted = permissionStates[type] ?: false
                PermissionItem(
                    type = type,
                    isGranted = isGranted,
                    onClick = {
                        // 自启动、使用情况统计、无障碍总是允许点击跳转，因为状态无法或无需在此页精确同步
                        val alwaysClickable = type == PermissionType.AUTO_START ||
                                            type == PermissionType.USAGE_STATS ||
                                            type == PermissionType.ACCESSIBILITY
                        if (!isGranted || alwaysClickable) {
                            vm.requestPermission(type)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionItem(
    type: PermissionType,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (type) {
                PermissionType.BATTERY_OPTIMIZATION -> Icons.Rounded.BatteryChargingFull
                PermissionType.LOCATION -> Icons.Rounded.LocationOn
                PermissionType.CAMERA -> Icons.Rounded.CameraAlt
                PermissionType.NOTIFICATION -> Icons.Rounded.Notifications
                PermissionType.AUTO_START -> Icons.Rounded.RocketLaunch
                PermissionType.USAGE_STATS -> Icons.Rounded.Analytics
                PermissionType.ACCESSIBILITY -> Icons.Rounded.AccessibilityNew
            }

            val title = when (type) {
                PermissionType.BATTERY_OPTIMIZATION -> stringResource(R.string.permission_check_battery_optimization)
                PermissionType.LOCATION -> stringResource(R.string.permission_check_location)
                PermissionType.CAMERA -> stringResource(R.string.permission_check_camera)
                PermissionType.NOTIFICATION -> stringResource(R.string.permission_check_notification)
                PermissionType.AUTO_START -> stringResource(R.string.permission_check_auto_start)
                PermissionType.USAGE_STATS -> stringResource(R.string.permission_check_usage_stats)
                PermissionType.ACCESSIBILITY -> stringResource(R.string.permission_check_accessibility)
            }

            val desc = when (type) {
                PermissionType.BATTERY_OPTIMIZATION -> stringResource(R.string.permission_check_battery_optimization_desc)
                PermissionType.LOCATION -> stringResource(R.string.permission_check_location_desc)
                PermissionType.CAMERA -> stringResource(R.string.permission_check_camera_desc)
                PermissionType.NOTIFICATION -> stringResource(R.string.permission_check_notification_desc)
                PermissionType.AUTO_START -> stringResource(R.string.permission_check_auto_start_desc)
                PermissionType.USAGE_STATS -> stringResource(R.string.permission_check_usage_stats_desc)
                PermissionType.ACCESSIBILITY -> stringResource(R.string.permission_check_accessibility_desc)
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 状态图标处理
            val isJumpOnly = type == PermissionType.AUTO_START ||
                           type == PermissionType.USAGE_STATS ||
                           type == PermissionType.ACCESSIBILITY

            when {
                isJumpOnly -> {
                    // 这些权限显示跳转箭头，不显示红叉/绿勾，避免误导
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.permission_go_to_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                isGranted -> {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Rounded.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
