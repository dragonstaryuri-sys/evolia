package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingUserProfilePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val displaySetting = settings.displaySetting
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.placeholder_user),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // User Avatar Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                UIAvatar(
                    name = displaySetting.userNickname,
                    value = displaySetting.userAvatar,
                    onUpdate = { avatar ->
                        vm.updateSettings(settings.copy(
                            displaySetting = displaySetting.copy(userAvatar = avatar)
                        ))
                    },
                    modifier = Modifier.size(96.dp)
                )

                Text(
                    text = "点击头像更换",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsGroup(title = "个人资料") {
                // Nickname
                var tempNickname by remember(displaySetting.userNickname) {
                    mutableStateOf(displaySetting.userNickname)
                }
                ListItem(
                    headlineContent = {
                        OutlinedTextField(
                            value = tempNickname,
                            onValueChange = {
                                tempNickname = it
                                vm.updateSettings(settings.copy(
                                    displaySetting = displaySetting.copy(userNickname = it)
                                ))
                            },
                            label = { Text("用户昵称") },
                            placeholder = { Text(stringResource(R.string.user_default_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                // Default Recipient Email
                var tempEmail by remember(displaySetting.userEmail) {
                    mutableStateOf(displaySetting.userEmail)
                }
                ListItem(
                    headlineContent = {
                        OutlinedTextField(
                            value = tempEmail,
                            onValueChange = {
                                tempEmail = it
                                vm.updateSettings(settings.copy(
                                    displaySetting = displaySetting.copy(userEmail = it)
                                ))
                            },
                            label = { Text("默认收件邮箱") },
                            placeholder = { Text("AI 发邮件时的默认目标") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            leadingIcon = { Icon(Icons.Rounded.Email, null) }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            Surface(
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "在此设置的邮箱将作为邮件工具的默认收件人。当智能体需要发送通知或工作简报给你时，会自动发送到此地址，除非它在调用工具时明确指定了其他地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
