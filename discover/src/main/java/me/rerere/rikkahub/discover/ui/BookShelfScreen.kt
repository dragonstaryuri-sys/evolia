package me.rerere.rikkahub.discover.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.core.data.db.entity.BookCategory
import me.rerere.rikkahub.core.data.db.entity.BookEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.discover.R
import me.rerere.rikkahub.common.ui.components.AppFab
import me.rerere.rikkahub.common.ui.components.EmptyPlaceholder
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookShelfScreen(
    onBack: () -> Unit,
    viewModel: BookViewModel = koinViewModel()
) {
    val books by viewModel.books.collectAsState()
    var showUploadDialog by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.reading_shelf_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton(onClick = onBack) }
            )
        },
        floatingActionButton = {
            AppFab(onClick = { showUploadDialog = true }) {
                Icon(Icons.Rounded.Add, null)
            }
        }
    ) { padding ->
        if (books.isEmpty()) {
            EmptyPlaceholder(
                text = stringResource(R.string.reading_empty_shelf),
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(books) { book ->
                    BookCard(
                        book = book,
                        onClick = {
                            navController.navigate(Screen.BookReader(book.id))
                        },
                        onDelete = { viewModel.deleteBook(book) }
                    )
                }
            }
        }
    }

    if (showUploadDialog) {
        UploadBookDialog(
            onDismiss = { showUploadDialog = false },
            onUpload = { title, author, uri, category, assistantId ->
                viewModel.uploadBook(title, author, uri.toString(), category, assistantId)
                showUploadDialog = false
            },
            viewModel = viewModel
        )
    }
}

@Composable
fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = AppShapes.CardLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().height(200.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Book, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (book.category == BookCategory.KNOWLEDGE)
                        stringResource(R.string.reading_category_knowledge)
                    else
                        stringResource(R.string.reading_category_entertainment),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadBookDialog(
    onDismiss: () -> Unit,
    onUpload: (String, String?, Uri, BookCategory, String?) -> Unit,
    viewModel: BookViewModel
) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var category by remember { mutableStateOf(BookCategory.ENTERTAINMENT) }
    var assistantId by remember { mutableStateOf<String?>(null) }

    val assistants by viewModel.assistants.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUri = uri
        if (title.isBlank()) {
            title = uri?.lastPathSegment?.substringBeforeLast(".") ?: ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(stringResource(R.string.reading_upload_book)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { launcher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedUri?.lastPathSegment ?: "Select File")
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Author (Optional)") }, modifier = Modifier.fillMaxWidth())

                Text("Category", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = category == BookCategory.ENTERTAINMENT,
                        onClick = { category = BookCategory.ENTERTAINMENT },
                        label = { Text(stringResource(R.string.reading_category_entertainment)) }
                    )
                    FilterChip(
                        selected = category == BookCategory.KNOWLEDGE,
                        onClick = { category = BookCategory.KNOWLEDGE },
                        label = { Text(stringResource(R.string.reading_category_knowledge)) }
                    )
                }

                Text("Companion AI", style = MaterialTheme.typography.labelMedium)
                AssistantsSelector(
                    assistants = assistants,
                    selectedId = assistantId,
                    onSelected = { assistantId = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedUri?.let { onUpload(title, author.takeIf { it.isNotBlank() }, it, category, assistantId) } },
                enabled = title.isNotBlank() && selectedUri != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Upload")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantsSelector(
    assistants: List<Assistant>,
    selectedId: String?,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAssistant = assistants.find { it.id.toString() == selectedId }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedAssistant?.name ?: stringResource(R.string.reading_no_assistant),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reading_no_assistant)) },
                onClick = { onSelected(null); expanded = false }
            )
            assistants.forEach { assistant ->
                DropdownMenuItem(
                    text = { Text(assistant.name) },
                    onClick = { onSelected(assistant.id.toString()); expanded = false }
                )
            }
        }
    }
}
