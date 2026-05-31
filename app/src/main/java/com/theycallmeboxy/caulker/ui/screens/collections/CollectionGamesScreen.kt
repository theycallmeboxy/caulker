package com.theycallmeboxy.caulker.ui.screens.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theycallmeboxy.caulker.ui.screens.games.InstallFilter
import com.theycallmeboxy.caulker.ui.util.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionGamesScreen(
    onGameClick: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: CollectionGamesViewModel = hiltViewModel()
) {
    val roms by viewModel.roms.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val collectionName by viewModel.collectionName.collectAsState()
    val installedRomIds by viewModel.installedRomIds.collectAsState()
    val enrolledRomIds by viewModel.enrolledRomIds.collectAsState()
    val installFilter by viewModel.installFilter.collectAsState()
    val downloadedCount by viewModel.downloadedCount.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()

    var searchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus()
    }

    // Re-scan installed status when returning from game detail.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshInstalledStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            if (searchActive) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = searchQuery,
                            onQueryChange = { viewModel.searchQuery.value = it },
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text("Search games…") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    viewModel.searchQuery.value = ""
                                    searchActive = false
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
                    title = {
                        Column {
                            Text(collectionName ?: "Collection")
                            if (totalCount > 0) {
                                Text(
                                    "$downloadedCount / $totalCount downloaded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = installFilter == InstallFilter.ALL,
                    onClick = { viewModel.installFilter.value = InstallFilter.ALL },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = installFilter == InstallFilter.INSTALLED,
                    onClick = { viewModel.installFilter.value = InstallFilter.INSTALLED },
                    label = { Text("Installed") },
                    leadingIcon = if (installFilter == InstallFilter.INSTALLED) {
                        { Icon(Icons.Default.DownloadDone, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = installFilter == InstallFilter.NOT_INSTALLED,
                    onClick = { viewModel.installFilter.value = InstallFilter.NOT_INSTALLED },
                    label = { Text("Not installed") }
                )
            }

            if (roms.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when (installFilter) {
                            InstallFilter.INSTALLED -> "No games installed"
                            InstallFilter.NOT_INSTALLED -> "All games are installed"
                            InstallFilter.ALL -> if (searchQuery.isNotBlank()) "No results" else "No games in this collection"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(roms, key = { it.id }) { rom ->
                        val isInstalled = rom.id in installedRomIds
                        val isEnrolled = rom.id in enrolledRomIds
                        ListItem(
                            headlineContent = { Text(rom.name) },
                            supportingContent = { Text(formatFileSize(rom.fileSize)) },
                            trailingContent = if (isInstalled || isEnrolled) {
                                {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isEnrolled) {
                                            Icon(
                                                Icons.Default.Sync,
                                                contentDescription = "Save sync enrolled",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        if (isInstalled) {
                                            Icon(
                                                Icons.Default.DownloadDone,
                                                contentDescription = "Installed",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            } else null,
                            modifier = Modifier.clickable { onGameClick(rom.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
