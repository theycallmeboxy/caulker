package com.theycallmeboxy.caulker.ui.screens.gamedetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.theycallmeboxy.caulker.ui.util.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    romId: Int,
    onSyncSavesClick: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: GameDetailViewModel = hiltViewModel()
) {
    val rom by viewModel.rom.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val localFileState by viewModel.localFileState.collectAsState()
    val coverUrl by viewModel.coverUrl.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rom?.name ?: "Game") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (rom == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val game = rom!!
        val isDownloading = downloadState is DownloadState.Downloading

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ───────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(game.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    formatFileSize(game.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val statusText = when (localFileState) {
                    is LocalFileState.Present -> {
                        val sizeMatch = (localFileState as LocalFileState.Present).sizeMatch
                        if (sizeMatch) "On device" else "On device (size mismatch)"
                    }
                    LocalFileState.Missing -> "Not downloaded"
                }
                val statusColor = when {
                    localFileState is LocalFileState.Present &&
                        !(localFileState as LocalFileState.Present).sizeMatch ->
                        MaterialTheme.colorScheme.error
                    localFileState is LocalFileState.Present ->
                        MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }

            // ── Action buttons ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (localFileState) {
                    is LocalFileState.Present -> {
                        OutlinedButton(
                            onClick = { viewModel.download() },
                            enabled = !isDownloading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Re-download")
                        }
                        OutlinedButton(
                            onClick = { viewModel.deleteLocalFile() },
                            enabled = !isDownloading,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }
                    LocalFileState.Missing -> {
                        Button(
                            onClick = { viewModel.download() },
                            enabled = !isDownloading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isDownloading) "Downloading…" else "Download")
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onSyncSavesClick(romId) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saves")
                }
            }

            // Download progress / error
            when (val ds = downloadState) {
                is DownloadState.Downloading -> LinearProgressIndicator(
                    progress = { ds.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                is DownloadState.Error -> Text(
                    ds.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                else -> {}
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            // ── Details ──────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                game.summary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
                game.fileName?.let { InfoRow("File", it) }
                game.rating?.let { InfoRow("Rating", "%.1f / 10".format(it)) }
                if (game.regions.isNotBlank()) InfoRow("Regions", game.regions)
                if (game.genres.isNotBlank()) InfoRow("Genres", game.genres)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
