package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingUserProfilePage() {
    val vm: SettingVM = koinViewModel()
    val settings by vm.settings.collectAsState()

    // 使用本地状态暂存修改，实现“退出自动保存”
    var localSettings by remember(settings.init) { mutableStateOf(settings) }

    val profile = localSettings.userProfile
    val displaySetting = localSettings.displaySetting

    var showDatePicker by remember { mutableStateOf(false) }

    // 关键逻辑：退出页面（Composable 销毁）时自动保存
    val currentLocalSettings by rememberUpdatedState(localSettings)
    DisposableEffect(vm) {
        onDispose {
            if (!currentLocalSettings.init) {
                vm.updateSettings(currentLocalSettings)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_user_profile)) },
                navigationIcon = { BackButton() }
            )
        },
        // 关键点 1：禁用默认 Insets 处理，避免冲突
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // 关键点 2：imePadding 应用于滚动容器外层，确保键盘弹出时高度正确缩减
                // 同时加上 navigationBarsPadding 确保底部不被导航栏遮挡
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UIAvatar(
                    name = displaySetting.userNickname,
                    value = displaySetting.userAvatar,
                    onUpdate = { newAvatar ->
                        localSettings = localSettings.copy(
                            displaySetting = displaySetting.copy(userAvatar = newAvatar)
                        )
                    },
                    modifier = Modifier.size(96.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.avatar_change_avatar),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = stringResource(R.string.user_profile_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingsGroup(title = stringResource(R.string.setting_user_profile)) {
                // 1. 昵称
                ProfileItem(
                    label = stringResource(R.string.user_profile_nickname),
                    value = displaySetting.userNickname,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(displaySetting = displaySetting.copy(userNickname = newValue))
                    }
                )

                // 2. 生日 (使用日期选择器)
                BirthdayItem(
                    label = stringResource(R.string.user_profile_birthday),
                    value = profile.birthday,
                    onClick = { showDatePicker = true }
                )

                // 3. 邮箱
                ProfileItem(
                    label = stringResource(R.string.user_profile_email),
                    value = displaySetting.userEmail,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(displaySetting = displaySetting.copy(userEmail = newValue))
                    },
                    supportingText = stringResource(R.string.user_profile_email_hint)
                )

                // 4. 外貌
                ProfileItem(
                    label = stringResource(R.string.user_profile_appearance),
                    value = profile.appearance,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(userProfile = profile.copy(appearance = newValue))
                    }
                )

                // 其他项...
                ProfileItem(
                    label = stringResource(R.string.user_profile_occupation),
                    value = profile.occupation,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(userProfile = profile.copy(occupation = newValue))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_preferences),
                    value = profile.preferences,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(userProfile = profile.copy(preferences = newValue))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_diet),
                    value = profile.diet,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(userProfile = profile.copy(diet = newValue))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_health),
                    value = profile.health,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(userProfile = profile.copy(health = newValue))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_taboos),
                    value = profile.taboos,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(userProfile = profile.copy(taboos = newValue))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_interaction_preferences),
                    value = profile.interactionPreferences,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(userProfile = profile.copy(interactionPreferences = newValue))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_important_relationships),
                    value = profile.importantRelationships,
                    onValueChange = { newValue ->
                        localSettings = localSettings.copy(userProfile = profile.copy(importantRelationships = newValue))
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showDatePicker) {
        val initialDate = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(profile.birthday)?.time
        } catch (e: Exception) {
            null
        } ?: System.currentTimeMillis()

        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialDate
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
                        localSettings = localSettings.copy(userProfile = profile.copy(birthday = date))
                    }
                    showDatePicker = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun BirthdayItem(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.user_profile_birthday_placeholder)) },
                readOnly = true,
                shape = MaterialTheme.shapes.medium,
                trailingIcon = {
                    Icon(Icons.Rounded.CalendarToday, contentDescription = null)
                }
            )
            // 覆盖一层透明层捕获点击
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onClick)
            )
        }
    }
}

@Composable
private fun ProfileItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supportingText: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("请输入${label}") },
            minLines = 1,
            maxLines = 5,
            shape = MaterialTheme.shapes.medium,
            supportingText = supportingText?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}
