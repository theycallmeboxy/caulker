package com.theycallmeboxy.caulker.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.theycallmeboxy.caulker.data.sync.LibrarySyncState
import com.theycallmeboxy.caulker.data.util.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onLibraryClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onSaveSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSyncDatabaseClick: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val serverUrl by viewModel.serverUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Caulker") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            ConnectionCard(
                serverUrl = serverUrl,
                username = username,
                lastSyncTime = lastSyncTime,
                isSyncing = syncState is LibrarySyncState.Syncing,
                syncError = (syncState as? LibrarySyncState.Error)?.message
            )

            HorizontalDivider()

            DashboardMenuItem(
                icon = Icons.Default.VideogameAsset,
                label = "Platforms",
                onClick = onLibraryClick
            )
            DashboardMenuItem(
                icon = Icons.Default.CollectionsBookmark,
                label = "Collections",
                onClick = onCollectionsClick
            )
            DashboardMenuItem(
                icon = Icons.Default.CloudSync,
                label = "Save sync",
                onClick = onSaveSyncClick
            )
            DashboardMenuItem(
                icon = Icons.Default.Settings,
                label = "Settings",
                onClick = onSettingsClick
            )
            DashboardMenuItem(
                icon = Icons.Default.Storage,
                label = "Sync database now",
                onClick = onSyncDatabaseClick,
                trailing = {
                    if (syncState is LibrarySyncState.Syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    serverUrl: String?,
    username: String?,
    lastSyncTime: Long,
    isSyncing: Boolean,
    syncError: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val dotColor = when {
                syncError != null -> MaterialTheme.colorScheme.error
                serverUrl.isNullOrBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                serverUrl?.takeIf { it.isNotBlank() }?.removeSuffix("/") ?: "No server configured",
                style = MaterialTheme.typography.titleMedium
            )
        }
        username?.takeIf { it.isNotBlank() }?.let {
            Text(
                "Signed in as $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val statusLine = when {
            isSyncing -> "Syncing now…"
            syncError != null -> "Last sync failed: $syncError"
            lastSyncTime == 0L -> "Never synced — tap \"Sync database now\""
            else -> "Last sync: ${formatTimestamp(lastSyncTime)}"
        }
        Text(
            statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = if (syncError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DashboardMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(label) },
        trailingContent = {
            if (trailing != null) trailing()
            else Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}
