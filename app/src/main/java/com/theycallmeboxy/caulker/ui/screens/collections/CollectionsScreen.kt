package com.theycallmeboxy.caulker.ui.screens.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.theycallmeboxy.caulker.data.download.BulkDownloadState
import com.theycallmeboxy.caulker.ui.util.formatFileSize

// A collection the user asked to download, held while the confirm dialog resolves
// its estimate. Name is shown; ids drive the download.
private data class PendingDownload(val name: String, val ids: List<Int>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    viewModel: CollectionsViewModel = hiltViewModel()
) {
    val collections by viewModel.collections.collectAsState()
    val virtualCollections by viewModel.virtualCollections.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()

    var searchActive by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingDownload?>(null) }
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
        },
        bottomBar = {
            val state = downloadState
            if (state is BulkDownloadState.Downloading) {
                DownloadProgressBar(state, onCancel = viewModel::cancelDownload)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (collections.isEmpty() && virtualCollections.isEmpty()) {
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
                    items(collections, key = { "c:${it.id}" }) { collection ->
                        CollectionRow(
                            name = collection.name,
                            subtitle = "${collection.romCount} games",
                            icon = if (collection.isSmart) Icons.Default.AutoAwesome else Icons.Default.Folder,
                            onDownload = { pending = PendingDownload(collection.name, viewModel.memberIds(collection)) }
                        )
                        HorizontalDivider()
                    }

                    if (virtualCollections.isNotEmpty()) {
                        item(key = "vc-header") {
                            Text(
                                "Browse",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                        items(virtualCollections, key = { "v:${it.id}" }) { vc ->
                            val type = vc.type?.takeIf { it.isNotBlank() }
                            CollectionRow(
                                name = vc.name,
                                subtitle = if (type != null) "$type · ${vc.romCount} games" else "${vc.romCount} games",
                                icon = Icons.Default.Category,
                                onDownload = { pending = PendingDownload(vc.name, vc.romIds) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        DownloadConfirmDialog(
            pending = p,
            estimateProvider = { viewModel.estimate(p.ids) },
            onConfirm = {
                viewModel.downloadCollection(p.name, p.ids)
                pending = null
            },
            onDismiss = { pending = null }
        )
    }
}

@Composable
private fun CollectionRow(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onDownload: () -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(name) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Download collection")
            }
        },
        modifier = Modifier.clickable { onDownload() }
    )
}

@Composable
private fun DownloadConfirmDialog(
    pending: PendingDownload,
    estimateProvider: suspend () -> DownloadEstimate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var estimate by remember(pending) { mutableStateOf<DownloadEstimate?>(null) }
    LaunchedEffect(pending) { estimate = estimateProvider() }

    val est = estimate
    val hasWork = est != null && est.missingCount > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pending.name) },
        text = {
            Text(
                when {
                    est == null -> "Checking what's already downloaded…"
                    est.missingCount == 0 -> "Everything in this collection is already on this device."
                    else -> "${est.missingCount} game${if (est.missingCount == 1) "" else "s"} not yet " +
                        "downloaded (${formatFileSize(est.totalBytes)})." +
                        if (est.alreadyPresent > 0) " ${est.alreadyPresent} already here." else ""
                }
            )
        },
        confirmButton = {
            if (hasWork) {
                TextButton(onClick = onConfirm) { Text("Download") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
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
