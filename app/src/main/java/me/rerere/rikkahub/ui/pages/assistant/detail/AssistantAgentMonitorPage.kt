package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.AgentMonitorTaskEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantAgentMonitorPage(assistantId: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val monitors by vm.monitorTasks.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_monitor_manager)) },
                navigationIcon = { BackButton() }
            )
        }
    ) { padding ->
        if (monitors.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.agent_monitor_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(monitors, key = { it.id }) { monitor ->
                    AgentMonitorItem(
                        monitor = monitor,
                        onDelete = { vm.deleteMonitorTask(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentMonitorItem(
    monitor: AgentMonitorTaskEntity,
    onDelete: (AgentMonitorTaskEntity) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.MonitorHeart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = monitor.monitorName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        R.string.agent_monitor_created_at,
                        dateFormat.format(Date(monitor.createdAt))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.agent_monitor_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(monitor)
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
