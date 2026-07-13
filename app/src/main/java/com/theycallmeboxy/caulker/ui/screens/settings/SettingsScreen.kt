package com.theycallmeboxy.caulker.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onForceFullResync: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val serverUrl by viewModel.serverUrl.collectAsState()
    val romPath by viewModel.romPath.collectAsState()
    val savePath by viewModel.savePath.collectAsState()
    val biosPath by viewModel.biosPath.collectAsState()
    val serverVersion by viewModel.serverVersion.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showResyncDialog by remember { mutableStateOf(false) }

    val hasStoragePermission = remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        )
    }

    // Re-check permission when screen becomes active
    LaunchedEffect(Unit) {
        hasStoragePermission.value = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout") },
            text = { Text("Disconnect from ${serverUrl ?: "server"}?") },
            confirmButton = {
                TextButton(onClick = { viewModel.logout(); onLogout() }) { Text("Logout") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showResyncDialog) {
        AlertDialog(
            onDismissRequest = { showResyncDialog = false },
            title = { Text("Rebuild database?") },
            text = {
                Text(
                    "Re-downloads every platform and game from RomM, ignoring " +
                        "the incremental cursor. Use this if your local library " +
                        "looks wrong. May take several minutes on a slow network."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResyncDialog = false
                    viewModel.clearSyncCursor { onForceFullResync() }
                }) { Text("Sync now") }
            },
            dismissButton = {
                TextButton(onClick = { showResyncDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Storage", style = MaterialTheme.typography.titleSmall)

            if (!hasStoragePermission.value) {
                OutlinedCard {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "All Files Access required to read and write ROM and save files.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Grant Access")
                        }
                    }
                }
            } else {
                PathField(
                    label = "ROM Folder",
                    value = romPath ?: "",
                    placeholder = "/storage/emulated/0/ROMs",
                    onSave = { viewModel.setRomPath(it) }
                )
                PathField(
                    label = "Save Folder",
                    value = savePath ?: "",
                    placeholder = "/storage/emulated/0/RetroArch/saves",
                    onSave = { viewModel.setSavePath(it) }
                )
                PathField(
                    label = "BIOS Folder",
                    value = biosPath ?: "",
                    placeholder = "${romPath?.trimEnd('/') ?: "/storage/emulated/0/ROMs"}/bios",
                    onSave = { viewModel.setBiosPath(it) }
                )
            }

            Spacer(Modifier.weight(1f))

            Text("Library", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { showResyncDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Rebuild Database")
            }

            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Logout")
            }

            serverVersion?.let { version ->
                Text(
                    "RomM server v$version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun PathField(
    label: String,
    value: String,
    placeholder: String,
    onSave: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = {
            if (text != value && text.isNotBlank()) {
                TextButton(onClick = { onSave(text) }) { Text("Save") }
            }
        }
    )
}
