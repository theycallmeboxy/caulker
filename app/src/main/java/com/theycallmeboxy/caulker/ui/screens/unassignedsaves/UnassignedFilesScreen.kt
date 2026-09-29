package com.theycallmeboxy.caulker.ui.screens.unassignedsaves

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.saves.LocalSaveEntry

// v1 save-location UI (save-sync design doc, Part 2 §3 rule 3 / §12 phase 3B,
// item 3). See UnassignedFilesViewModel's doc comment for the two modes this
// one screen serves. Every list here is a plain LazyColumn of clickable
// ListItems -- same D-pad-friendly pattern as GamesScreen/SaveSyncAllScreen
// (Modifier.clickable rows are focusable and D-pad "A"/center fires onClick
// by default; no custom key handling needed).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnassignedFilesScreen(
    onBack: () -> Unit,
    viewModel: UnassignedFilesViewModel = hiltViewModel()
) {
    val platformName by viewModel.platformName.collectAsState()
    val unassigned by viewModel.unassigned.collectAsState()
    val platformRoms by viewModel.platformRoms.collectAsState()
    val ruleMatchedRomIds by viewModel.ruleMatchedRomIds.collectAsState()
    val isExchangeMode by viewModel.isExchangeMode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isBulkSyncing by viewModel.isBulkSyncing.collectAsState()
    val error by viewModel.error.collectAsState()
    val entryPendingAssignment by viewModel.entryPendingAssignment.collectAsState()
    val pickerError by viewModel.pickerError.collectAsState()
    val assignedForTarget by viewModel.assignedForTarget.collectAsState()
    val forRomId = viewModel.forRomId
    // Item 4: assign/unassign is disabled while a bulk sync is running,
    // same as every other configured-platform mutation. Owner decision
    // (2026-09-29): also disabled outright for a DIRECT platform -- manual
    // assignment is EXCHANGE-only, so a DIRECT platform's list is read-only
    // diagnostics.
    val actionsEnabled = !isBulkSyncing && isExchangeMode

    // ASSIGN-FOR-ROM mode: a successful direct assignment pops this screen
    // straight back to SaveSyncScreen -- which refreshes itself on resume
    // (OnResumeEffect, item 6) and then shows the newly-assigned unit; this
    // screen doesn't reload anything on the destination's behalf itself.
    LaunchedEffect(assignedForTarget) {
        if (assignedForTarget) onBack()
    }

    // Owner decision (2026-09-29): the ROM picker only ever makes sense for
    // an EXCHANGE platform -- SaveSyncViewModel/Screen also never navigate
    // here with forRomId set for a DIRECT one, so entryPendingAssignment
    // (BROWSE mode's picker trigger) is gated the same way defensively.
    if (isExchangeMode) {
        entryPendingAssignment?.let { entry ->
            RomPickerDialog(
                entryLabel = entry.relativePath,
                roms = platformRoms,
                ruleMatchedRomIds = ruleMatchedRomIds,
                // Should-fix 5: rendered INSIDE this still-open dialog, not
                // behind it.
                error = pickerError,
                onSelect = { rom -> viewModel.assignToRom(entry, rom.id) },
                onDismiss = viewModel::cancelAssign
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (forRomId != null) "Choose save file" else "Unassigned files")
                        platformName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading && unassigned.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null && unassigned.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
            }

            unassigned.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No unassigned files here. Everything in the configured folder is matched to a game.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }

            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // An assignment rejection (blocker 1/should-fix item 3
                // validation, or a lock/exception -- nit: wrap assign calls
                // in try/catch and surface errors) shown inline, without
                // hiding the still-populated list behind a full-screen error.
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (!isExchangeMode) {
                    // Owner decision (2026-09-29): read-only diagnostics for
                    // a DIRECT platform -- explains WHY these files sit
                    // unmatched and what to do about it, since there's no
                    // assign action to offer instead.
                    Text(
                        "These files don't match any game. Automatic sync matches saves by the ROM's file " +
                            "name (or RomM's game ID for some systems). This usually means the emulator saved " +
                            "under a different name than the ROM on RomM -- rename the file or re-download the " +
                            "ROM through Caulker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                } else if (forRomId != null) {
                    Text(
                        "Pick which file is this game's save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (isExchangeMode && !actionsEnabled) {
                    Text(
                        "Save sync is running -- try again when it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                // Item 5: `unassigned` is already narrowed to entries
                // eligible for this preset's shape by resolveSaveLocations
                // (SaveLocationResolver.kt) -- no client-side filtering
                // needed here.
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(unassigned, key = { it.relativePath }) { entry ->
                        ListItem(
                            leadingContent = {
                                Icon(
                                    if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null
                                )
                            },
                            headlineContent = { Text(entry.relativePath) },
                            supportingContent = if (entry.isDirectory) {
                                { Text("Folder", style = MaterialTheme.typography.bodySmall) }
                            } else null,
                            // DIRECT: read-only -- no click target at all
                            // (not just disabled-and-inert), matching this
                            // list's "diagnostics only" role there.
                            modifier = if (isExchangeMode) {
                                Modifier.clickable(enabled = actionsEnabled) {
                                    if (forRomId != null) viewModel.assignForTarget(entry) else viewModel.beginAssign(entry)
                                }
                            } else {
                                Modifier
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// D-pad friendly, searchable ROM picker (item 3: "pick from that platform's
// ROMs, D-pad friendly searchable/scrollable list"). The search field is an
// ordinary focusable OutlinedTextField; the result list below it is a plain
// scrollable LazyColumn of clickable rows, so moving focus down from the
// search field reaches the list the same way every other screen's list does.
@Composable
private fun RomPickerDialog(
    entryLabel: String,
    roms: List<RomEntity>,
    // Item 5: "the ROM picker hides, or disables with a reason, ROMs that
    // already have a rule-matched unit" -- disabled (not hidden) with a
    // reason, so it's clear WHY a game the user might expect to see is
    // greyed out, rather than making it look missing.
    ruleMatchedRomIds: Set<Int>,
    // Round-2 fixes, should-fix 5: an assignment rejection, shown inside
    // this dialog (which stays open so the user can immediately try a
    // different game) rather than on the screen behind it.
    error: String?,
    onSelect: (RomEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(roms, query) {
        if (query.isBlank()) roms else roms.filter { it.name.contains(query, ignoreCase = true) }
    }

    // usePlatformDefaultWidth = false: this dialog needs real room for its
    // search field + scrollable list on a small square screen (720x720
    // handhelds), not the platform's narrower default dialog width.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Nit: ellipsize (not just clip) a long entry name so it's
                // obvious there's more, not just abruptly cut off.
                Text(
                    "Assign \"$entryLabel\" to…",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search games") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No games match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filtered, key = { it.id }) { rom ->
                            val alreadyMatched = rom.id in ruleMatchedRomIds
                            ListItem(
                                headlineContent = { Text(rom.name) },
                                supportingContent = if (alreadyMatched) {
                                    { Text("Already has a matched save", style = MaterialTheme.typography.bodySmall) }
                                } else null,
                                colors = if (alreadyMatched) {
                                    ListItemDefaults.colors(
                                        headlineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                } else ListItemDefaults.colors(),
                                modifier = Modifier.clickable(enabled = !alreadyMatched) { onSelect(rom) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}
