package com.theycallmeboxy.caulker.ui.screens.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.theycallmeboxy.caulker.data.sync.LibrarySyncState
import com.theycallmeboxy.caulker.data.sync.SyncPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncProgressScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: SyncProgressViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is LibrarySyncState.Syncing -> SyncingBody(s)
                is LibrarySyncState.Idle -> DoneBody(onDone = onDone)
                is LibrarySyncState.Error -> ErrorBody(message = s.message, onRetry = viewModel::retry, onBack = onBack)
            }
        }
    }
}

@Composable
private fun SyncingBody(state: LibrarySyncState.Syncing) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp), strokeWidth = 5.dp)

        val phaseLabel = when (state.phase) {
            SyncPhase.Platforms -> "Syncing platforms…"
            SyncPhase.Roms -> state.currentPlatform ?: "Syncing games…"
        }
        Text(phaseLabel, style = MaterialTheme.typography.titleMedium)

        // Row count climbs as each page of the current platform lands. Makes
        // it visible that the app is still working on huge systems (e.g.,
        // MAME/arcade dumps with tens of thousands of ROMs) where the bar
        // would otherwise sit on the same platform name for minutes.
        if (state.phase == SyncPhase.Roms && state.rowsInCurrent > 0) {
            Text(
                "${state.rowsInCurrent} games fetched",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.phase == SyncPhase.Roms && state.total > 0) {
            // Determinate bar — done/total are platforms processed, since RomM's
            // /api/roms requires a platform filter so we sync per-platform.
            LinearProgressIndicator(
                progress = { state.done.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${state.done} / ${state.total} systems",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Indeterminate bar for platform sync, reconcile, and the brief
            // window before the first platform completes.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                when (state.phase) {
                    SyncPhase.Platforms -> "Pulling platform list from RomM"
                    SyncPhase.Roms -> "Starting…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            "This may take a minute on a slow network.\nYou can leave this screen — sync continues in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DoneBody(onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text("Library synced", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Text("Sync failed", style = MaterialTheme.typography.titleLarge)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Button(onClick = onRetry) { Text("Retry") }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
