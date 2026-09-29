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
import com.theycallmeboxy.caulker.data.saves.SavePresetRegistry
import com.theycallmeboxy.caulker.data.saves.SaveSyncMode
import com.theycallmeboxy.caulker.data.sync.SyncAction
import com.theycallmeboxy.caulker.data.util.formatTimestamp
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import com.theycallmeboxy.caulker.ui.util.OnResumeEffect
import com.theycallmeboxy.caulker.ui.util.ellipsizeStart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSyncScreen(
    onBack: () -> Unit,
    // v1 save-location UI (§12 phase 3B): "Choose save file..." for a
    // configured platform with no local unit yet opens the Unassigned-files
    // picker targeted at this ROM -- needs both the platform's numeric id
    // (Screen.UnassignedSaves' route shape, same as PlatformSettings/
    // Firmware) and this ROM's id.
    onChooseSaveFile: (platformId: Int, romId: Int) -> Unit = { _, _ -> },
    viewModel: SaveSyncViewModel = hiltViewModel()
) {
    val status by viewModel.status.collectAsState()
    val romName by viewModel.romName.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val targetSlot by viewModel.targetSlot.collectAsState()
    val isSaveSyncEnrolled by viewModel.isSaveSyncEnrolled.collectAsState()
    val serverSlots by viewModel.serverSlots.collectAsState()
    val isBulkSyncing by viewModel.isBulkSyncing.collectAsState()
    val platformId by viewModel.platformId.collectAsState()
    val pendingGuardDownload by viewModel.pendingGuardDownload.collectAsState()

    var showSlotDialog by remember { mutableStateOf(false) }

    // Item 6: refresh this screen's status whenever it becomes visible
    // again -- most importantly, returning from the Unassigned-files screen
    // after "Choose save file..." assigns this ROM's save, which this
    // screen otherwise has no way to know about. Round-2 fixes nit: skipped
    // while a download/upload is actually in flight (status.isSyncing) --
    // loadStatus() builds a brand-new SlotUiState from scratch, so firing it
    // mid-operation would reset isSyncing to false and make the screen look
    // like nothing's happening while the write is still running underneath.
    OnResumeEffect { if (status?.isSyncing != true) viewModel.loadStatus() }

    if (pendingGuardDownload) {
        GuardConfirmDialog(
            incomingEmulator = status?.slot?.emulator,
            configuredPreset = status?.presetDisplayName,
            onConfirm = viewModel::confirmGuardedDownload,
            onDismiss = viewModel::dismissGuardedDownload
        )
    }

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
                    isBulkSyncing = isBulkSyncing,
                    onSmartSync = viewModel::smartSync,
                    onKeepLocal = viewModel::keepLocal,
                    onKeepRemote = viewModel::keepRemote,
                    onToggleTrack = viewModel::toggleTrack,
                    onChooseSaveFile = { platformId?.let { onChooseSaveFile(it, viewModel.currentRomId) } },
                    onUnassign = viewModel::unassign
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
    isBulkSyncing: Boolean,
    onSmartSync: () -> Unit,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onToggleTrack: () -> Unit,
    onChooseSaveFile: () -> Unit,
    onUnassign: () -> Unit
) {
    val context = LocalContext.current
    val slot = state.slot
    // Bulk "sync all" holds the same save-mutation lock this screen's actions
    // use, so disable them while it's running instead of letting the tap fail
    // with a locked-message after the fact.
    val actionsEnabled = !state.isSyncing && !isBulkSyncing

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

        // v1 save-location UI (§12 phase 3B, item 4): which preset/folder
        // this platform is configured with, and the unit's member files --
        // presetDisplayName is only set for a configured platform (see
        // loadStatus), so this whole block is absent for a legacy one.
        state.presetDisplayName?.let { presetName ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Save source: $presetName",
                        style = MaterialTheme.typography.labelLarge
                    )
                    state.configuredFolderPath?.let {
                        // Nit: ellipsize from the start -- the end of a
                        // save-folder path (the actually distinguishing
                        // part) is what should stay visible, not the common
                        // storage-root prefix every path shares.
                        Text(
                            ellipsizeStart(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (state.unitMemberPaths.isNotEmpty()) {
                        Text(
                            state.unitMemberPaths.joinToString(", ") { it.substringAfterLast('/') },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // Owner decision (2026-09-29): manual assignment is
                    // EXCHANGE-only, so "Unassign" only ever makes sense
                    // there -- state.isManualAssignment is already always
                    // false for a DIRECT platform (its units never carry
                    // isAssigned=true, see SaveLocationResolver.kt), but the
                    // explicit mode check makes that guarantee visible here
                    // too rather than relying on it implicitly.
                    if (state.presetMode == SaveSyncMode.EXCHANGE && state.isManualAssignment) {
                        TextButton(
                            onClick = onUnassign,
                            enabled = actionsEnabled,
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Unassign this save file")
                        }
                    }
                }
            }
            // Item 5: Exchange mode notice, shown only for this preset's
            // mode (§1) -- Caulker syncs the exchange folder like any other,
            // but the user still has to move the save in/out of the
            // emulator themselves.
            if (state.presetMode == SaveSyncMode.EXCHANGE) {
                if (state.message?.startsWith("Downloaded") == true) {
                    ExchangeNotice("Import this save into your emulator now.")
                } else if (state.syncAction == SyncAction.UPLOAD) {
                    ExchangeNotice("Export the save from your emulator first, then upload.")
                }
            }
        }

        // Item 4: a configured EXCHANGE platform with no local unit at all --
        // offer to point it at an existing unassigned file instead of
        // leaving the user stuck with no download/upload action. Owner
        // decision (2026-09-29): DIRECT is automatic-only -- for a DIRECT
        // platform with no local unit, nothing extra is shown here; the
        // normal Download action (or, for a SAVE_TARGET preset with no
        // save_target, the specific error from matchingKeyNameErrorFor)
        // applies instead.
        if (state.presetMode == SaveSyncMode.EXCHANGE && !state.hasLocalFile) {
            OutlinedButton(onClick = onChooseSaveFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose save file…")
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
                // §7 download guard: the incoming save was made with a
                // possibly-incompatible emulator/core -- shown here so the
                // warning is visible before the user taps the button (which
                // then opens the confirmation dialog instead of downloading
                // straight away, see viewModel.smartSync/GuardConfirmDialog).
                if (state.guardWarning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "This save was made with a different emulator or core — confirm before downloading.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Button(
                    onClick = onSmartSync,
                    enabled = actionsEnabled,
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
                    enabled = actionsEnabled,
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
                // §7 download guard: "Keep Remote" is also a download, so it
                // gets the same up-front warning as SyncAction.DOWNLOAD above.
                if (state.guardWarning) {
                    Text(
                        "The server's version was made with a different emulator or core — " +
                            "\"Keep Remote\" will ask you to confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onKeepLocal,
                        enabled = actionsEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Keep Local")
                    }
                    OutlinedButton(
                        onClick = onKeepRemote,
                        enabled = actionsEnabled,
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

// Item 5 -- Exchange mode's "needs importing"/"export first" reminder. Kept
// short per this task's instructions.
@Composable
private fun ExchangeNotice(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

// §7 download guard confirmation (item 6). Every text-carrying element is a
// standard Material3 AlertDialog with two TextButtons/Button -- focusable and
// D-pad operable by default, same as every other dialog in this screen
// (SlotDialog below).
@Composable
private fun GuardConfirmDialog(
    incomingEmulator: String?,
    configuredPreset: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // item 6: "names both emulators in plain words" -- the incoming save's
    // raw emulator id (§7's upload id table) is looked up against the
    // registry's own display names; an id Caulker doesn't recognize (a
    // standalone emulator, or a core outside v1) falls back to the raw id
    // rather than hiding it.
    val incomingName = SavePresetRegistry.displayNameForEmulatorId(incomingEmulator) ?: incomingEmulator
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Different emulator or core") },
        text = {
            Text(
                "This save was made with " + (incomingName?.let { "\"$it\"" } ?: "a different emulator") +
                    ", which may not be compatible with " +
                    (configuredPreset?.let { "your configured \"$it\"" } ?: "what's configured on this device") +
                    ". Downloading it may overwrite your local save with one it can't read."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Download anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
