package com.theycallmeboxy.caulker.ui.screens.platformsettings

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.theycallmeboxy.caulker.data.prefs.PlatformOverride
import com.theycallmeboxy.caulker.data.prefs.PlatformOverrideMode
import com.theycallmeboxy.caulker.data.saves.PresetKey
import com.theycallmeboxy.caulker.data.saves.RetroArchPresetInfo
import com.theycallmeboxy.caulker.data.saves.SaveSetupWarnings
import com.theycallmeboxy.caulker.ui.util.OnResumeEffect
import com.theycallmeboxy.caulker.ui.util.ellipsizeStart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingsScreen(
    onBack: () -> Unit,
    onFirmwareClick: () -> Unit,
    // v1 save-location UI (§12 phase 3B, item 2): "N files not matched"
    // links here.
    onUnassignedFilesClick: () -> Unit = {},
    viewModel: PlatformSettingsViewModel = hiltViewModel()
) {
    val platform by viewModel.platform.collectAsState()
    val romBasePath by viewModel.romBasePath.collectAsState()
    val saveBasePath by viewModel.saveBasePath.collectAsState()
    val biosBasePath by viewModel.biosBasePath.collectAsState()
    val savedOverride by viewModel.savedOverride.collectAsState()
    val availablePresets by viewModel.availablePresets.collectAsState()
    val savedSaveConfig by viewModel.savedSaveConfig.collectAsState()
    val saveSetupWarnings by viewModel.saveSetupWarnings.collectAsState()
    val isCheckingSaveSetup by viewModel.isCheckingSaveSetup.collectAsState()
    val saveConfigError by viewModel.saveConfigError.collectAsState()
    val isBulkSyncing by viewModel.isBulkSyncing.collectAsState()

    // Item 6: re-check setup warnings whenever this screen becomes visible
    // again -- most importantly, returning from the Unassigned-files screen
    // after assigning/unassigning a file there.
    OnResumeEffect { viewModel.refreshSaveSetupWarnings() }

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

    // ── Save sync setup local editing state (item 1) ─────────────────────────
    // null selectedPreset == "Default (current behavior)" (§6). Reset from
    // the saved config the same way the override state above resets from
    // savedOverride.
    var selectedPresetKey by remember(savedSaveConfig) { mutableStateOf(savedSaveConfig?.presetKey) }
    var saveFolderText by remember(savedSaveConfig) { mutableStateOf(savedSaveConfig?.folderPath ?: "") }
    var showPresetPicker by remember { mutableStateOf(false) }
    var showSaveConfigConfirm by remember { mutableStateOf(false) }
    var showClearConfigConfirm by remember { mutableStateOf(false) }

    val selectedPresetInfo = selectedPresetKey?.let { key -> availablePresets.firstOrNull { it.preset.key == key } }
    val saveConfigDirty = selectedPresetKey != savedSaveConfig?.presetKey ||
        (selectedPresetKey != null && saveFolderText.trim() != (savedSaveConfig?.folderPath ?: ""))
    val canSaveSaveConfig = saveConfigDirty && selectedPresetKey != null && saveFolderText.isNotBlank()

    val saveFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { saveFolderText = treeUriToPath(it) }
    }

    if (showPresetPicker) {
        PresetPickerDialog(
            presets = availablePresets,
            selected = selectedPresetKey,
            onSelect = { key ->
                selectedPresetKey = key
                showPresetPicker = false
            },
            onDismiss = { showPresetPicker = false }
        )
    }

    // item 1: "Changing preset or folder must be explicit (confirm)" --
    // and the confirmation states the §5 baseline behavior, and (Phase 3B
    // fixes nit) that any existing Unassigned-file assignments for this
    // platform are cleared, rather than silently applying either.
    if (showSaveConfigConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfigConfirm = false },
            title = { Text("Change save sync setup?") },
            text = {
                Text(
                    "Caulker will read/write saves for \"${platform?.name ?: fsSlug}\" from this folder using " +
                        "the \"${selectedPresetInfo?.coreDisplayName ?: "selected"}\" preset from now on. " +
                        "The first sync after this change will ask before overwriting anything, so nothing is " +
                        "silently replaced. Any files you've manually assigned to a game on this platform will " +
                        "be un-assigned -- you'll need to assign them again if they still apply."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showSaveConfigConfirm = false
                    val key = selectedPresetKey
                    if (key != null) viewModel.saveSaveLocationConfig(key, saveFolderText.trim())
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfigConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearConfigConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfigConfirm = false },
            title = { Text("Reset to Default?") },
            text = {
                // Round-2 fixes, should-fix 3: this must NOT promise "ask
                // before overwriting" -- that's the §5 baseline-path
                // guarantee, which only applies to a CONFIGURED platform.
                // After Reset, this platform is back on the legacy path
                // (§6), which can upload or download automatically based on
                // timestamps/server history without asking -- say that
                // plainly instead.
                Text(
                    "Save sync for \"${platform?.name ?: fsSlug}\" will go back to the app's default " +
                        "save folder and filename behavior. That's the ORDINARY sync behavior Caulker uses " +
                        "everywhere else -- it can upload or download automatically based on timestamps and " +
                        "server history, without asking first. Any files you've manually assigned to a game on " +
                        "this platform will be un-assigned."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showClearConfigConfirm = false
                    viewModel.clearSaveLocationConfig()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfigConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Item 7: the folder validation error saveSaveLocationConfig can
    // produce -- shown instead of ever silently saving an unreadable path.
    if (saveConfigError != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSaveConfigError,
            title = { Text("Can't use this folder") },
            text = { Text(saveConfigError!!) },
            confirmButton = {
                Button(onClick = viewModel::dismissSaveConfigError) { Text("OK") }
            }
        )
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

            HorizontalDivider()

            // ── Save sync setup (item 1) ─────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Save Sync Setup")

                if (availablePresets.isEmpty()) {
                    Text(
                        "No save sync presets are available for this platform yet. It will keep using the " +
                            "app's default save folder and filename behavior.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedButton(onClick = { showPresetPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedPresetInfo?.let { presetLabel(it) } ?: "Default (current behavior)")
                    }

                    if (selectedPresetInfo != null) {
                        PathPickerField(
                            label = "Save folder",
                            value = saveFolderText,
                            placeholder = "Choose the folder this preset reads/writes",
                            onValueChange = { saveFolderText = it },
                            onBrowse = { saveFolderPicker.launch(null) }
                        )

                        if (selectedPresetInfo.setupNotes.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.shapes.small
                                    )
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Required emulator setting${if (selectedPresetInfo.setupNotes.size != 1) "s" else ""}:",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                selectedPresetInfo.setupNotes.forEach { note ->
                                    Text(
                                        "• $note",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Text(
                            "The first sync after you save this will ask before overwriting anything, " +
                                "whether that's here or on the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Round-2 fixes, should-fix 2: disabled while a bulk
                    // sync is running -- a config change mid-sync could mix
                    // the OLD folder/preset with the NEW one inside the
                    // orchestrator's still-in-flight operations.
                    if (isBulkSyncing) {
                        Text(
                            "Save sync is running -- try again when it finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (savedSaveConfig != null) {
                            OutlinedButton(
                                onClick = { showClearConfigConfirm = true },
                                enabled = !isBulkSyncing,
                                modifier = Modifier.weight(1f)
                            ) { Text("Clear") }
                        }
                        Button(
                            onClick = { showSaveConfigConfirm = true },
                            enabled = canSaveSaveConfig && !isBulkSyncing,
                            modifier = Modifier.weight(1f)
                        ) { Text("Save") }
                    }
                }

                // ── Setup warnings (item 2) ──────────────────────────────────
                if (savedSaveConfig != null) {
                    when {
                        isCheckingSaveSetup && saveSetupWarnings == null ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    "Checking save folder…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        saveSetupWarnings != null && !saveSetupWarnings!!.isEmpty ->
                            SaveSetupWarningsCard(saveSetupWarnings!!, onUnassignedFilesClick)
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

// "RetroArch -- <core>" for the only PresetKey shape v1 ships (§9/§10.x);
// falls back to the bare display name for a future Standalone preset, whose
// label is already just the emulator's own name.
private fun presetLabel(info: RetroArchPresetInfo): String = when (info.preset.key) {
    is PresetKey.RetroArchCore -> "RetroArch — ${info.coreDisplayName}"
    is PresetKey.Standalone -> info.coreDisplayName
}

// D-pad friendly preset picker (item 1: "choose ... a preset from
// presetsForPlatform(slug) (show system + core name clearly)"). A plain
// scrollable Column of selectable rows inside a Dialog, rather than a
// dropdown, so every row is reachable by moving focus straight down/up --
// same reasoning as the Unassigned-files ROM picker (UnassignedFilesScreen.kt).
@Composable
private fun PresetPickerDialog(
    presets: List<RetroArchPresetInfo>,
    selected: PresetKey?,
    onSelect: (PresetKey?) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Save sync preset",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                PresetRow("Default (current behavior)", selected == null) { onSelect(null) }
                presets.forEach { info ->
                    PresetRow(presetLabel(info), selected == info.preset.key) { onSelect(info.preset.key) }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Nit: onClick = null -- the enclosing Row's own .selectable is the
        // one focus/click target for this row; a second clickable target on
        // the RadioButton itself would make D-pad focus stop at each row
        // twice.
        RadioButton(selected = isSelected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// item 2: setup warnings for a configured platform. Each is stated plainly
// rather than as a raw exception message where avoidable.
@Composable
private fun SaveSetupWarningsCard(warnings: SaveSetupWarnings, onUnassignedFilesClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            warnings.folderUnreadable?.let { reason ->
                WarningRow("Folder isn't readable: $reason")
            }
            if (warnings.folderEmptyOrNoSaves) {
                WarningRow("This folder is empty, or nothing in it looks like a save for this platform yet.")
            }
            if (warnings.unassignedCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WarningRow(
                        "${warnings.unassignedCount} file${if (warnings.unassignedCount != 1) "s" else ""} " +
                            "in this folder ${if (warnings.unassignedCount != 1) "aren't" else "isn't"} matched to any game.",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onUnassignedFilesClick) { Text("Review") }
                }
            }
        }
    }
}

@Composable
private fun WarningRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
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
