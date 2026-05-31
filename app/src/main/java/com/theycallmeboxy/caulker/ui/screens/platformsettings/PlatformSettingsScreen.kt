package com.theycallmeboxy.caulker.ui.screens.platformsettings

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.theycallmeboxy.caulker.data.prefs.PlatformOverride
import com.theycallmeboxy.caulker.data.prefs.PlatformOverrideMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingsScreen(
    onBack: () -> Unit,
    onFirmwareClick: () -> Unit,
    viewModel: PlatformSettingsViewModel = hiltViewModel()
) {
    val platform by viewModel.platform.collectAsState()
    val romBasePath by viewModel.romBasePath.collectAsState()
    val saveBasePath by viewModel.saveBasePath.collectAsState()
    val biosBasePath by viewModel.biosBasePath.collectAsState()
    val savedOverride by viewModel.savedOverride.collectAsState()

    val fsSlug = platform?.fsSlug ?: platform?.slug ?: ""
    val baseRom = romBasePath?.trimEnd('/') ?: ""
    val baseSave = saveBasePath?.trimEnd('/') ?: ""
    val globalBiosDir = biosBasePath?.trimEnd('/') ?: "$baseRom/bios"

    // ── Local editing state, reset when saved override changes ──────────────
    val savedMode = savedOverride?.mode ?: PlatformOverrideMode.DEFAULT
    var selectedMode by remember(savedOverride) { mutableStateOf(savedMode) }
    var slugText by remember(savedOverride) { mutableStateOf(savedOverride?.slug ?: "") }
    var romPathText by remember(savedOverride) { mutableStateOf(savedOverride?.romPath ?: "") }
    var savePathText by remember(savedOverride) { mutableStateOf(savedOverride?.savePath ?: "") }
    var biosPathText by remember(savedOverride) { mutableStateOf(savedOverride?.biosPath ?: "") }
    var biosManual by remember(savedOverride) { mutableStateOf(!savedOverride?.biosPath.isNullOrBlank()) }

    // ── Derived effective paths (live preview) ───────────────────────────────
    val effectiveSlug = slugText.trim().ifBlank { fsSlug }
    val effectiveRomDir = when (selectedMode) {
        PlatformOverrideMode.SLUG_OVERRIDE -> "$baseRom/$effectiveSlug"
        PlatformOverrideMode.MANUAL -> romPathText.trim().ifBlank { "$baseRom/$fsSlug" }
        else -> "$baseRom/$fsSlug"
    }
    val effectiveSaveDir = when (selectedMode) {
        PlatformOverrideMode.SLUG_OVERRIDE -> "$baseSave/$effectiveSlug"
        PlatformOverrideMode.MANUAL -> savePathText.trim().ifBlank { "$baseSave/$fsSlug" }
        else -> "$baseSave/$fsSlug"
    }
    val effectiveBiosDir = biosPathText.trim().ifBlank { globalBiosDir }

    // ── Dirty / save-enabled tracking ───────────────────────────────────────
    val isDirty = selectedMode != savedMode ||
        (selectedMode == PlatformOverrideMode.SLUG_OVERRIDE && slugText != (savedOverride?.slug ?: "")) ||
        (selectedMode == PlatformOverrideMode.MANUAL &&
            (romPathText != (savedOverride?.romPath ?: "") || savePathText != (savedOverride?.savePath ?: ""))) ||
        biosPathText != (savedOverride?.biosPath ?: "")

    val canSave = isDirty &&
        when (selectedMode) {
            PlatformOverrideMode.DEFAULT -> true
            PlatformOverrideMode.SLUG_OVERRIDE -> slugText.isNotBlank()
            PlatformOverrideMode.MANUAL -> romPathText.isNotBlank() && savePathText.isNotBlank()
        } &&
        (!biosManual || biosPathText.isNotBlank())

    val romDirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { romPathText = treeUriToPath(it) }
    }
    val saveDirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { savePathText = treeUriToPath(it) }
    }
    val biosDirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { biosPathText = treeUriToPath(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(platform?.name ?: "Platform Settings") },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Live path summary ────────────────────────────────────────────
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Effective Paths")
                    PathSummaryRow("Slug", effectiveSlug.ifBlank { "—" })
                    PathSummaryRow("ROM", effectiveRomDir.ifBlank { "Not configured" })
                    PathSummaryRow("Save", effectiveSaveDir.ifBlank { "Not configured" })
                    PathSummaryRow("BIOS", effectiveBiosDir.ifBlank { "Not configured" })
                }
            }

            // ── ROM & Save override ──────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("ROM & Save Override")

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        PlatformOverrideMode.DEFAULT to "Default",
                        PlatformOverrideMode.SLUG_OVERRIDE to "Slug",
                        PlatformOverrideMode.MANUAL to "Manual"
                    ).forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            label = { Text(label) }
                        )
                    }
                }

                when (selectedMode) {
                    PlatformOverrideMode.DEFAULT ->
                        Text(
                            "Uses the global ROM and save directories with \"$fsSlug\" as the subdirectory.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                    PlatformOverrideMode.SLUG_OVERRIDE -> {
                        Text(
                            "Replaces \"$fsSlug\" with a custom subdirectory name under the global base paths.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = slugText,
                            onValueChange = { slugText = it },
                            label = { Text("Subdirectory name") },
                            placeholder = { Text(fsSlug, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                    }

                    PlatformOverrideMode.MANUAL -> {
                        Text(
                            "Fully custom paths for this platform. Both fields are required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PathPickerField(
                            label = "ROM path",
                            value = romPathText,
                            placeholder = "$baseRom/$fsSlug",
                            onValueChange = { romPathText = it },
                            onBrowse = { romDirPicker.launch(null) }
                        )
                        PathPickerField(
                            label = "Save path",
                            value = savePathText,
                            placeholder = "$baseSave/$fsSlug",
                            onValueChange = { savePathText = it },
                            onBrowse = { saveDirPicker.launch(null) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // ── BIOS directory override ──────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("BIOS Directory")

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !biosManual,
                        onClick = { biosManual = false; biosPathText = "" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text("Default") }
                    )
                    SegmentedButton(
                        selected = biosManual,
                        onClick = { biosManual = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text("Manual") }
                    )
                }

                when {
                    !biosManual ->
                        Text(
                            "Downloads to: $globalBiosDir",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    else ->
                        PathPickerField(
                            label = "BIOS path",
                            value = biosPathText,
                            placeholder = globalBiosDir.ifBlank { "Set ROM folder in Settings" },
                            onValueChange = { biosPathText = it },
                            onBrowse = { biosDirPicker.launch(null) }
                        )
                }

                if ((platform?.firmwareCount ?: 0) > 0) {
                    val count = platform!!.firmwareCount
                    OutlinedButton(
                        onClick = onFirmwareClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Manage BIOS ($count file${if (count != 1) "s" else ""})")
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Save / Revert ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        selectedMode = savedMode
                        slugText = savedOverride?.slug ?: ""
                        romPathText = savedOverride?.romPath ?: ""
                        savePathText = savedOverride?.savePath ?: ""
                        biosPathText = savedOverride?.biosPath ?: ""
                        biosManual = !savedOverride?.biosPath.isNullOrBlank()
                    },
                    enabled = isDirty,
                    modifier = Modifier.weight(1f)
                ) { Text("Revert") }

                Button(
                    onClick = {
                        val biosOverride = biosPathText.trim().ifBlank { null }
                        val override = when (selectedMode) {
                            PlatformOverrideMode.DEFAULT ->
                                PlatformOverride(PlatformOverrideMode.DEFAULT, biosPath = biosOverride)
                            PlatformOverrideMode.SLUG_OVERRIDE ->
                                PlatformOverride(PlatformOverrideMode.SLUG_OVERRIDE,
                                    slug = slugText.trim(), biosPath = biosOverride)
                            PlatformOverrideMode.MANUAL ->
                                PlatformOverride(PlatformOverrideMode.MANUAL,
                                    romPath = romPathText.trim(),
                                    savePath = savePathText.trim(),
                                    biosPath = biosOverride)
                        }
                        viewModel.saveOverride(override)
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun PathSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PathPickerField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        IconButton(onClick = onBrowse) {
            Icon(Icons.Default.FolderOpen, contentDescription = "Browse")
        }
    }
}

private fun treeUriToPath(uri: Uri): String = try {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val parts = docId.split(":")
    val base = if (parts[0].equals("primary", ignoreCase = true))
        Environment.getExternalStorageDirectory().absolutePath
    else "/storage/${parts[0]}"
    val rel = if (parts.size > 1) parts[1] else ""
    if (rel.isBlank()) base else "$base/$rel"
} catch (e: Exception) {
    uri.path ?: uri.toString()
}
