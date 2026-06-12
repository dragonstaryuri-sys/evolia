package me.rerere.rikkahub.ui.pages.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.utils.saveToDownloads
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownViewerPage(title: String, content: String, uri: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var markdownContent by remember { mutableStateOf(content) }
    var isLoading by remember { mutableStateOf(uri != null && content.isEmpty()) }

    LaunchedEffect(uri) {
        if (uri != null && markdownContent.isEmpty()) {
            isLoading = true
            try {
                context.contentResolver.openInputStream(uri.toUri())?.use { inputStream ->
                    markdownContent = InputStreamReader(inputStream).readText()
                }
            } catch (e: Exception) {
                markdownContent = "Error loading file: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (uri != null) {
                        IconButton(onClick = {
                            scope.launch {
                                context.saveToDownloads(uri.toUri(), title)
                            }
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Save to Downloads")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            MarkdownBlock(
                content = markdownContent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}
