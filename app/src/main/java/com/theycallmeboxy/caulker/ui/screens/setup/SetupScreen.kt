package com.theycallmeboxy.caulker.ui.screens.setup

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Build.MODEL as DeviceModel
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val romPath by viewModel.romPath.collectAsState()
    val savePath by viewModel.savePath.collectAsState()
    val biosPath by viewModel.biosPath.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()

    var romPathText by remember(romPath) { mutableStateOf(romPath ?: "") }
    var savePathText by remember(savePath) { mutableStateOf(savePath ?: "") }
    var biosPathText by remember(biosPath) { mutableStateOf(biosPath ?: "") }
    var deviceNameText by remember(deviceName) { mutableStateOf(deviceName ?: "") }

    val hasPermission = remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission.value = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val romDirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val path = treeUriToPath(it)
            romPathText = path
            viewModel.setRomPath(path)
        }
    }

    val saveDirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val path = treeUriToPath(it)
            savePathText = path
            viewModel.setSavePath(path)
        }
    }

    val biosDirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val path = treeUriToPath(it)
            biosPathText = path
            viewModel.setBiosPath(path)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Setup") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Configure storage before accessing your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasPermission.value) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "All Files Access Required",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Caulker needs permission to read and write ROM and save files on your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Grant Access")
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Device", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = deviceNameText,
                    onValueChange = { deviceNameText = it },
                    label = { Text("Device Name") },
                    placeholder = { Text(DeviceModel, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (deviceNameText.isNotBlank()) {
                            TextButton(onClick = { viewModel.setDeviceName(deviceNameText) }) { Text("Save") }
                        }
                    }
                )
                Text(
                    "How this device appears in RomM. Defaults to the hardware model if left blank.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Directories", style = MaterialTheme.typography.titleSmall)

                PathPickerField(
                    label = "ROM Folder *",
                    value = romPathText,
                    placeholder = "/storage/emulated/0/ROMs",
                    onValueChange = { romPathText = it },
                    onSave = { viewModel.setRomPath(it) },
                    onBrowse = { romDirPicker.launch(null) },
                    enabled = hasPermission.value
                )

                PathPickerField(
                    label = "Save Folder",
                    value = savePathText,
                    placeholder = "/storage/emulated/0/RetroArch/saves",
                    onValueChange = { savePathText = it },
                    onSave = { viewModel.setSavePath(it) },
                    onBrowse = { saveDirPicker.launch(null) },
                    enabled = hasPermission.value
                )

                PathPickerField(
                    label = "BIOS Folder",
                    value = biosPathText,
                    placeholder = "${romPathText.ifBlank { "/storage/emulated/0/ROMs" }}/bios",
                    onValueChange = { biosPathText = it },
                    onSave = { viewModel.setBiosPath(it) },
                    onBrowse = { biosDirPicker.launch(null) },
                    enabled = hasPermission.value
                )

                Text(
                    "* Required to access the library. BIOS folder defaults to ROM folder/bios if not set.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onComplete,
                enabled = romPath != null && romPath!!.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue to Library")
            }
        }
    }
}

@Composable
private fun PathPickerField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onBrowse: () -> Unit,
    enabled: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = {
                if (value.isNotBlank()) {
                    TextButton(onClick = { onSave(value) }) { Text("Save") }
                }
            }
        )
        IconButton(onClick = onBrowse, enabled = enabled) {
            Icon(Icons.Default.FolderOpen, contentDescription = "Browse")
        }
    }
}

private fun treeUriToPath(uri: Uri): String {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":")
        val storageType = parts[0]
        val relative = if (parts.size > 1) parts[1] else ""
        val base = if (storageType.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$storageType"
        }
        if (relative.isBlank()) base else "$base/$relative"
    } catch (e: Exception) {
        uri.path ?: uri.toString()
    }
}
