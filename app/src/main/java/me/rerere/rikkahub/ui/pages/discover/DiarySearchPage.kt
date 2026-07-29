package me.rerere.rikkahub.ui.pages.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiarySearchPage(
    vm: DiaryVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    TextField(
                        value = searchQuery,
                        onValueChange = { vm.searchQuery.value = it },
                        placeholder = { Text(stringResource(R.string.search)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { vm.searchQuery.value = "" }) {
                                    Icon(Icons.Rounded.Close, null)
                                }
                            }
                        } else null,
                        singleLine = true
                    )
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (searchQuery.isBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.diary_search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (searchResults.isEmpty()) {
                EmptyState(stringResource(R.string.diary_search_no_result))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(searchResults, key = { it.id }) { diary ->
                        DiarySummaryCard(
                            diary = diary,
                            onClick = { navController.navigate(Screen.DiaryDetail(diary.id)) },
                            onDelete = { vm.deleteDiary(diary.id) }
                        )
                    }
                }
            }
        }
    }
}
