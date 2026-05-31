package com.theycallmeboxy.caulker.ui.screens.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onCollectionClick: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: CollectionsViewModel = hiltViewModel()
) {
    val collections by viewModel.collections.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var searchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            if (searchActive) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = searchQuery,
                            onQueryChange = viewModel::setSearchQuery,
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text("Search collections...") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    searchActive = false
                                    viewModel.setSearchQuery("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            },
                            modifier = Modifier.focusRequester(focusRequester)
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth()
                ) {}
            } else {
                TopAppBar(
                    title = { Text("Collections") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (collections.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            searchQuery.isNotBlank() -> "No collections match \"$searchQuery\""
                            else -> "No collections cached. Open the dashboard and tap \"Sync database now\"."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(collections, key = { it.id }) { collection ->
                        ListItem(
                            leadingContent = {
                                Icon(
                                    if (collection.isSmart) Icons.Default.AutoAwesome
                                    else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text(collection.name) },
                            supportingContent = { Text("${collection.romCount} games") },
                            modifier = Modifier.clickable { onCollectionClick(collection.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
