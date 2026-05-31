package com.theycallmeboxy.caulker.ui.screens.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theycallmeboxy.caulker.ui.util.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    platformId: Int = -1,
    onGameClick: (Int) -> Unit,
    onBack: () -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    viewModel: GamesViewModel = hiltViewModel()
) {
    val roms by viewModel.roms.collectAsState()
    val userRefreshing by viewModel.userRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val installedCountReady by viewModel.installedCountReady.collectAsState()
    var showRefreshConfirm by remember { mutableStateOf(false) }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val platformName by viewModel.platformName.collectAsState()
    val installedRomIds by viewModel.installedRomIds.collectAsState()
    val enrolledRomIds by viewModel.enrolledRomIds.collectAsState()
    val installFilter by viewModel.installFilter.collectAsState()
    val downloadedCount by viewModel.downloadedCount.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()

    var searchActive by remember { mutableStateOf(false) }

    if (showRefreshConfirm) {
        AlertDialog(
            onDismissRequest = { showRefreshConfirm = false },
            title = { Text("Refresh ${platformName ?: "platform"}?") },
            text = { Text("Re-downloads the full game list for this platform from RomM.") },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshConfirm = false
                    viewModel.refresh()
                }) { Text("Refresh") }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Re-scan installed status when returning from game detail (e.g. after a
    // download). Guard against empty allRoms — the scan is a no-op until the
    // Room Flow has delivered its first batch of rows.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshInstalledStatusIfLoaded()
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
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    viewModel.searchQuery.value = ""
                                    searchActive = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            }
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth()
                ) {}
            } else {
                TopAppBar(
                    title = {
                        Text(platformName ?: "Games")
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
                        if (userRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).padding(end = 4.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { showRefreshConfirm = true }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh platform")
                            }
                        }
                        if (onSettingsClick != null) {
                            IconButton(onClick = onSettingsClick) {
                                Icon(Icons.Default.Settings, contentDescription = "Platform settings")
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val installedLabel = if (installedCountReady) "$downloadedCount" else "…"
                val notInstalledLabel = if (installedCountReady) "${totalCount - downloadedCount}" else "…"
                FilterChip(
                    selected = installFilter == InstallFilter.ALL,
                    onClick = { viewModel.installFilter.value = InstallFilter.ALL },
                    label = { Text("All ($totalCount)") }
                )
                FilterChip(
                    selected = installFilter == InstallFilter.INSTALLED,
                    onClick = { viewModel.installFilter.value = InstallFilter.INSTALLED },
                    label = { Text("Installed ($installedLabel)") },
                    leadingIcon = if (installFilter == InstallFilter.INSTALLED) {
                        { Icon(Icons.Default.DownloadDone, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = installFilter == InstallFilter.NOT_INSTALLED,
                    onClick = { viewModel.installFilter.value = InstallFilter.NOT_INSTALLED },
                    label = { Text("Not installed ($notInstalledLabel)") }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (error != null && roms.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error ?: "Unknown error", color = MaterialTheme.colorScheme.error)
                    }
                } else if (roms.isEmpty() && userRefreshing) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Refreshing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (roms.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when (installFilter) {
                                InstallFilter.INSTALLED -> "No games installed"
                                InstallFilter.NOT_INSTALLED -> "All games are installed"
                                InstallFilter.ALL -> if (searchQuery.isNotBlank()) "No results" else "No games"
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
}
