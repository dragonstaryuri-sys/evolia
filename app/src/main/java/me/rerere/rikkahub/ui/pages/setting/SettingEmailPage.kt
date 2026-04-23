package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Properties
import javax.mail.Session

@Composable
fun SettingEmailPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var account by remember(settings.emailConfig.account) { mutableStateOf(settings.emailConfig.account) }
    var password by remember(settings.emailConfig.password) { mutableStateOf(settings.emailConfig.password) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<Unit>?>(null) }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_email_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding
        ) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = account,
                        onValueChange = {
                            account = it
                            vm.updateSettings(settings.copy(
                                emailConfig = settings.emailConfig.copy(account = it)
                            ))
                        },
                        label = { Text(stringResource(R.string.setting_email_page_account)) },
                        placeholder = { Text("example@qq.com") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            vm.updateSettings(settings.copy(
                                emailConfig = settings.emailConfig.copy(password = it)
                            ))
                        },
                        label = { Text(stringResource(R.string.setting_email_page_auth_code)) },
                        placeholder = { Text(stringResource(R.string.setting_email_page_auth_code_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val result = testEmailConnection(account, password)
                                testing = false
                                testResult = result
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !testing && account.isNotBlank() && password.isNotBlank()
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.setting_email_page_testing))
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Send, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.setting_email_page_test_button))
                        }
                    }

                    testResult?.let { result ->
                        Spacer(modifier = Modifier.height(16.dp))
                        val color = if (result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        Text(
                            text = if (result.isSuccess) {
                                stringResource(R.string.setting_email_page_test_success)
                            } else {
                                stringResource(R.string.setting_email_page_test_failed, result.exceptionOrNull()?.message ?: "Unknown error")
                            },
                            color = color,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.setting_email_page_auth_code_help),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun testEmailConnection(account: String, authCode: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        // Test IMAP
        val imapProps = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.qq.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
        }
        val session = Session.getInstance(imapProps)
        val store = session.getStore("imaps")
        store.connect(account, authCode)
        store.close()

        // Test SMTP
        val smtpProps = Properties().apply {
            put("mail.smtp.host", "smtp.qq.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
        }
        val smtpSession = Session.getInstance(smtpProps)
        val transport = smtpSession.getTransport("smtp")
        transport.connect(account, authCode)
        transport.close()
    }
}
