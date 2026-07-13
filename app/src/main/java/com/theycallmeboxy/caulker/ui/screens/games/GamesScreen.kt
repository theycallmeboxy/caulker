package com.theycallmeboxy.caulker.ui.screens.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theycallmeboxy.caulker.data.download.BulkDownloadState
import com.theycallmeboxy.caulker.ui.util.formatFileSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()

    var searchActive by remember { mutableStateOf(false) }
    var showInstallConfirm by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }

    // System back exits selection mode first.
    BackHandler(enabled = selectionMode) { viewModel.exitSelection() }

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

    if (showInstallConfirm) {
        BulkInstallConfirmDialog(
            count = selectedIds.size,
            estimateProvider = { viewModel.estimateInstall() },
            onConfirm = { viewModel.installSelected(); showInstallConfirm = false },
            onDismiss = { showInstallConfirm = false }
        )
    }

    if (showUninstallConfirm) {
        val n = selectedIds.size
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text("Delete downloaded files?") },
            text = { Text("Removes the on-device files for $n selected game${if (n == 1) "" else "s"}. Saves in your save folder are not touched.") },
            confirmButton = {
                TextButton(onClick = { viewModel.uninstallSelected(); showUninstallConfirm = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showUninstallConfirm = false }) { Text("Cancel") } }
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
            when {
                selectionMode -> SelectionTopBar(
                    count = selectedIds.size,
                    onClose = { viewModel.exitSelection() },
                    onSelectAll = { viewModel.selectAll() },
                    onSelectVisible = { viewModel.selectVisible() },
                    onSelectNone = { viewModel.selectNone() }
                )
                searchActive -> SearchBar(
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
                else -> TopAppBar(
                    title = { Text(platformName ?: "Games") },
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
        },
        bottomBar = {
            if (selectionMode) {
                SelectionActionBar(
                    hasSelection = selectedIds.isNotEmpty(),
                    onInstall = { showInstallConfirm = true },
                    onUninstall = { showUninstallConfirm = true },
                    onEnroll = { viewModel.enrollSelected() },
                    onUnenroll = { viewModel.unenrollSelected() }
                )
            } else {
                val ds = downloadState
                if (ds is BulkDownloadState.Downloading) {
                    DownloadProgressBar(ds, onCancel = { viewModel.cancelDownload() })
                }
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
                            val isSelected = rom.id in selectedIds
                            ListItem(
                                leadingContent = if (selectionMode) {
                                    {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { viewModel.toggleSelection(rom.id) }
                                        )
                                    }
                                } else null,
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
                                colors = if (isSelected) {
                                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                } else ListItemDefaults.colors(),
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (selectionMode) viewModel.toggleSelection(rom.id)
                                        else onGameClick(rom.id)
                                    },
                                    onLongClick = { viewModel.enterSelection(rom.id) }
                                )
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectVisible: () -> Unit,
    onSelectNone: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Exit selection")
            }
        },
        actions = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.Checklist, contentDescription = "Select…")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Select all") }, onClick = { onSelectAll(); menuOpen = false })
                DropdownMenuItem(text = { Text("Select visible") }, onClick = { onSelectVisible(); menuOpen = false })
                DropdownMenuItem(text = { Text("Select none") }, onClick = { onSelectNone(); menuOpen = false })
            }
        }
    )
}

@Composable
private fun SelectionActionBar(
    hasSelection: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onEnroll: () -> Unit,
    onUnenroll: () -> Unit
) {
    BottomAppBar {
        Spacer(Modifier.width(4.dp))
        BulkAction(Icons.Default.Download, "Install", hasSelection, onInstall)
        BulkAction(Icons.Default.Delete, "Uninstall", hasSelection, onUninstall)
        BulkAction(Icons.Default.Sync, "Enroll sync", hasSelection, onEnroll)
        BulkAction(Icons.Default.SyncDisabled, "Unenroll sync", hasSelection, onUnenroll)
    }
}

@Composable
private fun RowScope.BulkAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.weight(1f).clickable(enabled = enabled) { onClick() }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun BulkInstallConfirmDialog(
    count: Int,
    estimateProvider: suspend () -> BulkInstallEstimate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var estimate by remember { mutableStateOf<BulkInstallEstimate?>(null) }
    LaunchedEffect(Unit) { estimate = estimateProvider() }
    val est = estimate
    val hasWork = est != null && est.missingCount > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install $count game${if (count == 1) "" else "s"}") },
        text = {
            Text(
                when {
                    est == null -> "Checking what's already downloaded…"
                    est.missingCount == 0 -> "All selected games are already on this device."
                    else -> "${est.missingCount} game${if (est.missingCount == 1) "" else "s"} not yet " +
                        "downloaded (${formatFileSize(est.totalBytes)})." +
                        if (est.alreadyPresent > 0) " ${est.alreadyPresent} already here." else ""
                }
            )
        },
        confirmButton = {
            if (hasWork) TextButton(onClick = onConfirm) { Text("Download") }
            else TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = if (hasWork) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else null
    )
}

@Composable
private fun DownloadProgressBar(
    state: BulkDownloadState.Downloading,
    onCancel: () -> Unit
) {
    val overall = if (state.total > 0) (state.done + state.currentFraction) / state.total else 0f
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Downloading ${state.done + 1} of ${state.total}" +
                            (state.currentRomName?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { overall.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
