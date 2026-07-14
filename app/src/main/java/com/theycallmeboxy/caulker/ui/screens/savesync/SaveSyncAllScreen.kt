package com.theycallmeboxy.caulker.ui.screens.savesync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.theycallmeboxy.caulker.data.sync.SyncAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSyncAllScreen(
    onBack: () -> Unit,
    onGameClick: (Int) -> Unit,
    viewModel: SaveSyncAllViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val hasPendingChanges by viewModel.hasPendingChanges.collectAsState()
    val isSyncingAll by viewModel.isSyncingAll.collectAsState()
    val syncingRomIds by viewModel.syncingRomIds.collectAsState()
    val syncProgressLabel by viewModel.syncProgressLabel.collectAsState()

    // Ask once per screen entry for POST_NOTIFICATIONS so the QS-tile / foreground
    // service progress notification can show. If the user denies, sync still
    // works headlessly — they just won't see it in the shade.
    val context = LocalContext.current
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result: ignored — we don't gate any in-screen action on it */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Save Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        bottomBar = {
            if (groups.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column {
                        syncProgressLabel?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = viewModel::revertAll,
                                enabled = hasPendingChanges && !isLoading && !isSyncingAll,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Revert")
                            }
                            if (isSyncingAll) {
                                Button(
                                    onClick = viewModel::cancelSync,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Cancel")
                                }
                            } else {
                                Button(
                                    onClick = viewModel::syncAll,
                                    enabled = hasPendingChanges && !isLoading,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Sync All")
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            isLoading && groups.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null && groups.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Button(onClick = viewModel::refresh) { Text("Retry") }
                }
            }

            groups.isEmpty() && !isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(Icons.Default.SyncAlt, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No games enrolled in save sync",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Open a game's detail page to enable save sync for it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                var lastPlatform: String? = null
                groups.forEach { group ->
                    if (group.platformName != lastPlatform) {
                        lastPlatform = group.platformName
                        item(key = "header_${group.platformName}") {
                            Text(
                                group.platformName ?: "Unknown platform",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    item(key = group.romId) {
                        RomSyncRow(
                            group = group,
                            isSyncing = group.romId in syncingRomIds,
                            onClick = { onGameClick(group.romId) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RomSyncRow(group: RomSyncGroup, isSyncing: Boolean, onClick: () -> Unit) {
    val slotKey = group.status.slot.slotKey

    ListItem(
        headlineContent = {
            Text(group.romName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (slotKey != "default") {
            {
                Text(
                    "Slot: $slotKey",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else null,
        trailingContent = {
            if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                StatusBadge(group.status.syncAction)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun StatusBadge(action: SyncAction) {
    val icon: ImageVector
    val label: String
    val color: Color
    when (action) {
        SyncAction.UP_TO_DATE -> {
            icon = Icons.Default.CheckCircle
            label = "Up to date"
            color = MaterialTheme.colorScheme.primary
        }
        SyncAction.DOWNLOAD -> {
            icon = Icons.Default.CloudDownload
            label = "Download"
            color = MaterialTheme.colorScheme.primary
        }
        SyncAction.UPLOAD -> {
            icon = Icons.Default.CloudUpload
            label = "Upload"
            color = MaterialTheme.colorScheme.primary
        }
        SyncAction.CONFLICT -> {
            icon = Icons.Default.Warning
            label = "Out of sync"
            color = MaterialTheme.colorScheme.error
        }
        SyncAction.NONE -> {
            icon = Icons.Default.Remove
            label = "No saves"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
