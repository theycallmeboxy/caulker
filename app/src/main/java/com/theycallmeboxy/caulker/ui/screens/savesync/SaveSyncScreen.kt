package com.theycallmeboxy.caulker.ui.screens.savesync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.theycallmeboxy.caulker.data.sync.SyncAction
import com.theycallmeboxy.caulker.data.util.formatTimestamp
import com.theycallmeboxy.caulker.data.util.parseIsoToMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSyncScreen(
    onBack: () -> Unit,
    viewModel: SaveSyncViewModel = hiltViewModel()
) {
    val status by viewModel.status.collectAsState()
    val romName by viewModel.romName.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val targetSlot by viewModel.targetSlot.collectAsState()
    val isSaveSyncEnrolled by viewModel.isSaveSyncEnrolled.collectAsState()
    val serverSlots by viewModel.serverSlots.collectAsState()

    var showSlotDialog by remember { mutableStateOf(false) }

    if (showSlotDialog) {
        SlotDialog(
            title = "Sync target slot",
            confirmText = "Use slot",
            existingSlots = serverSlots,
            onConfirm = { slotKey ->
                showSlotDialog = false
                viewModel.setTargetSlot(slotKey)
            },
            onDismiss = { showSlotDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Save Sync")
                        if (romName.isNotBlank()) {
                            Text(
                                romName,
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
                    if (isSaveSyncEnrolled) {
                        IconButton(onClick = { viewModel.unenrollFromSaveSync() }) {
                            Icon(Icons.Default.SyncDisabled, contentDescription = "Disable Save Sync")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            !isSaveSyncEnrolled -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.Save, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Save sync isn't enabled for this game.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { viewModel.enrollInSaveSync() }) {
                        Text("Enable Save Sync")
                    }
                }
            }

            isLoading && status == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null && status == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            status == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No saves found for this game", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                SaveStatusSection(
                    state = status!!,
                    onSmartSync = viewModel::smartSync,
                    onKeepLocal = viewModel::keepLocal,
                    onKeepRemote = viewModel::keepRemote,
                    onToggleTrack = viewModel::toggleTrack
                )

                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Sync target slot") },
                    supportingContent = {
                        Text(
                            if (targetSlot == "default") "Default slot" else targetSlot,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { showSlotDialog = true }) { Text("Change") }
                    }
                )
            }
        }
    }
}

@Composable
private fun SaveStatusSection(
    state: SlotUiState,
    onSmartSync: () -> Unit,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onToggleTrack: () -> Unit
) {
    val context = LocalContext.current
    val slot = state.slot

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "This device ↔ RomM",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (state.isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                SyncStatusIcon(state.syncAction)
            }
        }

        state.fileName?.let { name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val path = state.localFilePath ?: name
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("save path", path))
                        Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy path",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.hasLocalFile || slot.hasRemote) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (state.hasLocalFile) {
                    TimestampLabel("On device", formatTimestamp(state.localModifiedMs))
                }
                if (slot.hasRemote) {
                    val remoteMs = parseIsoToMs(slot.remoteUpdatedAt) ?: 0L
                    TimestampLabel("On server", formatTimestamp(remoteMs))
                } else {
                    TimestampLabel("On server", "Not yet uploaded")
                }
            }
        }

        state.backupInfo?.let { backup ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "Backups",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (backup.count == 0) "None yet"
                        else "${backup.count} ${if (backup.count == 1) "backup" else "backups"} — latest ${formatTimestamp(backup.latestMs)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (state.message != null) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }

        when (state.syncAction) {
            SyncAction.NONE -> {
                Text(
                    "No save found — play the game and come back to sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SyncAction.DOWNLOAD -> {
                Button(
                    onClick = onSmartSync,
                    enabled = !state.isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isSyncing) "Downloading…" else "Download from server")
                }
            }

            SyncAction.UPLOAD -> {
                Button(
                    onClick = onSmartSync,
                    enabled = !state.isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isSyncing) "Uploading…" else "Upload to server")
                }
            }

            SyncAction.UP_TO_DATE -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Up to date", color = MaterialTheme.colorScheme.primary)
                }
            }

            SyncAction.CONFLICT -> {
                Text(
                    "Both your device and the server have newer versions. Choose which to keep:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onKeepLocal,
                        enabled = !state.isSyncing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Keep Local")
                    }
                    OutlinedButton(
                        onClick = onKeepRemote,
                        enabled = !state.isSyncing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Keep Remote")
                    }
                }
            }
        }

        // RomM 4.9: pause/resume sync for this save on this device. Only meaningful
        // once the save exists on the server.
        if (state.saveId != null) {
            if (state.isUntracked) {
                Text(
                    "Sync is paused for this game on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onToggleTrack,
                enabled = !state.isSyncing
            ) {
                Icon(
                    if (state.isUntracked) Icons.Default.Sync else Icons.Default.SyncDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (state.isUntracked) "Resume sync on this device" else "Pause sync on this device")
            }
        }
    }
}

@Composable
private fun SyncStatusIcon(action: SyncAction) {
    when (action) {
        SyncAction.DOWNLOAD -> Icon(
            Icons.Default.CloudDownload, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        SyncAction.UPLOAD -> Icon(
            Icons.Default.CloudUpload, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        SyncAction.UP_TO_DATE -> Icon(
            Icons.Default.CheckCircle, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        SyncAction.CONFLICT -> Icon(
            Icons.Default.Warning, null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        SyncAction.NONE -> {}
    }
}

@Composable
private fun TimestampLabel(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private const val NEW_SLOT_SENTINEL = "__new__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotDialog(
    title: String,
    confirmText: String,
    existingSlots: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(existingSlots) {
        mutableStateOf(existingSlots.firstOrNull() ?: NEW_SLOT_SENTINEL)
    }
    var newSlotName by remember { mutableStateOf("") }

    val effectiveSlot = if (selected == NEW_SLOT_SENTINEL)
        newSlotName.trim().ifBlank { "default" }
    else
        selected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Your local save file will sync with this server slot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (existingSlots.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = if (selected == NEW_SLOT_SENTINEL) "New slot…" else selected,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Slot") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            existingSlots.forEach { slot ->
                                DropdownMenuItem(
                                    text = { Text(slot) },
                                    onClick = { selected = slot; expanded = false }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("New slot…") },
                                onClick = { selected = NEW_SLOT_SENTINEL; expanded = false }
                            )
                        }
                    }
                }
                if (selected == NEW_SLOT_SENTINEL) {
                    OutlinedTextField(
                        value = newSlotName,
                        onValueChange = { newSlotName = it },
                        label = { Text("Slot name") },
                        placeholder = { Text("default") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConfirm(effectiveSlot) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(effectiveSlot) }) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
