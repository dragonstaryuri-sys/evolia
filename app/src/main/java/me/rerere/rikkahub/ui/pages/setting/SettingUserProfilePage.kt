package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingUserProfilePage() {
    val vm: SettingVM = koinViewModel()
    val settings by vm.settings.collectAsState()
    val profile = settings.userProfile

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_user_profile)) },
                navigationIcon = { BackButton() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.user_profile_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingsGroup(title = stringResource(R.string.setting_user_profile)) {
                ProfileItem(
                    label = stringResource(R.string.user_profile_appearance),
                    value = profile.appearance,
                    onValueChange = { newValue ->
                        vm.updateSettings(settings.copy(userProfile = profile.copy(appearance = newValue)))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_occupation),
                    value = profile.occupation,
                    onValueChange = { newValue ->
                        vm.updateSettings(settings.copy(userProfile = profile.copy(occupation = newValue)))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_preferences),
                    value = profile.preferences,
                    onValueChange = { newValue ->
                        vm.updateSettings(settings.copy(userProfile = profile.copy(preferences = newValue)))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_diet),
                    value = profile.diet,
                    onValueChange = { newValue ->
                        vm.updateSettings(settings.copy(userProfile = profile.copy(diet = newValue)))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_health),
                    value = profile.health,
                    onValueChange = { newValue ->
                        vm.updateSettings(settings.copy(userProfile = profile.copy(health = newValue)))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_taboos),
                    value = profile.taboos,
                    onValueChange = { newValue ->
                        vm.updateSettings(settings.copy(userProfile = profile.copy(taboos = newValue)))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_interaction_preferences),
                    value = profile.interactionPreferences,
                    onValueChange = { newValue ->
                        vm.updateSettings(settings.copy(userProfile = profile.copy(interactionPreferences = newValue)))
                    }
                )
                ProfileItem(
                    label = stringResource(R.string.user_profile_important_relationships),
                    value = profile.importantRelationships,
                    onValueChange = { newValue ->
                        vm.updateSettings(settings.copy(userProfile = profile.copy(importantRelationships = newValue)))
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProfileItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
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
            shape = MaterialTheme.shapes.medium
        )
    }
}
