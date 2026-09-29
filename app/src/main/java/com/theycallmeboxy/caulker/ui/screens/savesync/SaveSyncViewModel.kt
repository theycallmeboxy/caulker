package com.theycallmeboxy.caulker.ui.screens.savesync

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.BackupInfo
import com.theycallmeboxy.caulker.data.repository.ConfiguredPlatform
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveLocationRepository
import com.theycallmeboxy.caulker.data.repository.SaveRepository
import com.theycallmeboxy.caulker.data.saves.resolvedPathFor
import com.theycallmeboxy.caulker.data.sync.SaveSyncLock
import com.theycallmeboxy.caulker.data.sync.SaveSyncOrchestrator
import com.theycallmeboxy.caulker.data.sync.SaveSyncOverallState
import com.theycallmeboxy.caulker.data.sync.SyncAction
import com.theycallmeboxy.caulker.data.sync.SyncBaseline
import com.theycallmeboxy.caulker.data.sync.determineSyncAction
import com.theycallmeboxy.caulker.data.sync.effectiveBaseline
import com.theycallmeboxy.caulker.data.util.msToIso
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// One sync status for one ROM. Caulker holds exactly one local save file per ROM,
// so the whole screen is a single relationship (this device's file <-> RomM),
// synced to one server slot ("default" unless the user set an advanced override).
data class SlotUiState(
    val slot: SaveSlotResponse,
    val fileName: String? = null,
    val saveId: Int? = null,
    val hasLocalFile: Boolean = false,
    val localModifiedMs: Long = 0L,
    val syncAction: SyncAction = SyncAction.NONE,
    val isSyncing: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val isUntracked: Boolean = false,
    val backupInfo: BackupInfo? = null,
    val localFilePath: String? = null
)

@HiltViewModel
class SaveSyncViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val saveRepository: SaveRepository,
    private val romRepository: RomRepository,
    private val prefsStore: PrefsStore,
    private val saveSyncLock: SaveSyncLock,
    private val orchestrator: SaveSyncOrchestrator,
    // v1 save-location wiring (save-sync design doc, Part 2 §12 phase 3):
    // null configuredPlatform() means this platform has no v1 config, so
    // every function below falls through to the existing legacy behavior
    // unchanged (§6 hard constraint).
    private val saveLocationRepository: SaveLocationRepository
) : ViewModel() {

    private val romId: Int = checkNotNull(savedStateHandle["romId"])
    private var platformFsSlug: String? = null
    private var romFileName: String? = null
    private var romEntity: RomEntity? = null

    private val _romName = MutableStateFlow("")
    val romName = _romName.asStateFlow()

    // The server slot this device's local save maps to. "default" unless the user
    // picked a specific slot via the advanced override.
    val targetSlot: StateFlow<String> = prefsStore.saveSyncSlotPref(romId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    val isSaveSyncEnrolled: StateFlow<Boolean> = prefsStore.saveSyncEnrolled
        .map { it.contains(romId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Slots that already exist on the server — offered in the advanced picker.
    private val _serverSlots = MutableStateFlow<List<String>>(emptyList())
    val serverSlots: StateFlow<List<String>> = _serverSlots.asStateFlow()

    private val _status = MutableStateFlow<SlotUiState?>(null)
    val status: StateFlow<SlotUiState?> = _status.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    // True while the app-wide "sync all" orchestrator is running, so the UI can
    // disable this screen's own download/upload actions rather than let the
    // user kick off an action that will just be rejected by the lock below.
    val isBulkSyncing: StateFlow<Boolean> = orchestrator.state
        .map { it is SaveSyncOverallState.Syncing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            val rom = romRepository.getById(romId)
            romEntity = rom
            platformFsSlug = rom?.platformFsSlug
            romFileName = rom?.fileName
            _romName.value = rom?.name ?: ""
            loadStatus()
        }
    }

    private var loadJob: Job? = null

    fun loadStatus() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val deviceId = saveRepository.getOrRegisterDeviceId()
                val target = prefsStore.saveSyncSlotPref(romId).first()

                val saves = saveRepository.syncSavesForRom(romId)
                // Server negotiate pairs on the exact (rom_id, slot); a null slot is
                // archival and never offered/paired against, so mirror that here
                // instead of folding it into "default".
                _serverSlots.value = saves
                    .mapNotNull { it.slot?.takeIf { s -> s.isNotBlank() } }
                    .distinct()
                    .sorted()

                // The server save for the targeted slot (newest wins if duplicated).
                val save = saves
                    .filter { it.slot == target }
                    .maxByOrNull { it.updatedAt ?: "" }

                // v1 save-location wiring (§12 phase 3): a configured platform
                // resolves its local save via the folder/preset scan instead of
                // effectiveSaveDir + resolveLocalSaveFileName; an unconfigured
                // one (configured == null) keeps the exact legacy behavior below
                // (§6 hard constraint).
                val configured = saveLocationRepository.configuredPlatform(platformFsSlug)
                val configuredUnit = if (configured != null) romEntity?.let { saveLocationRepository.unitFor(it) } else null

                val localFileName: String?
                val hasLocal: Boolean
                val localMs: Long
                val localHash: String?
                val resolvedPath: String?

                if (configured != null) {
                    val unit = configuredUnit?.second
                    localFileName = unit?.matchingKeyName
                    hasLocal = unit != null
                    localMs = unit?.modifiedMs ?: 0L
                    localHash = unit?.contentHash
                    resolvedPath = unit?.resolvedPath
                } else {
                    // The single physical local file for this ROM. Resolve from the
                    // server filename when we have one, else scan the save dir by ROM base.
                    localFileName = saveRepository.resolveLocalSaveFileName(
                        save?.fileName, romFileName, platformFsSlug
                    ) ?: save?.fileName
                    // Read the local file's hash + mtime once so the decision can
                    // short-circuit byte-identical content (matching the server).
                    val stat = localFileName?.let { saveRepository.localSaveStat(it, platformFsSlug) }
                    hasLocal = stat != null
                    localMs = stat?.modifiedMs ?: 0L
                    localHash = stat?.contentHash
                    resolvedPath = null // legacy baselines never record one; see effectiveBaseline()
                }

                val slotResponse = SaveSlotResponse(
                    slot = save?.slot ?: target.takeIf { it != "default" },
                    emulator = save?.emulator,
                    hasRemote = save != null,
                    remoteUpdatedAt = save?.updatedAt
                )
                val deviceSync = save?.deviceSyncs?.find { it.deviceId == deviceId }
                // §5: a baseline recorded against a different resolved path (or no
                // path at all, for a config change / first Unassigned-file
                // assignment) is treated as no history, forcing a conflict prompt
                // rather than a silent overwrite. On a configured platform a
                // PATHLESS baseline also counts as no history (requirePathRecorded
                // = true) -- it was written by the legacy path before this platform
                // had a v1 config, and configuring one IS the location change §5
                // guards against; the legacy path keeps today's "pathless =
                // matching" exception.
                val baseline = effectiveBaseline(
                    prefsStore.getSyncBaseline(romId, target), resolvedPath,
                    requirePathRecorded = configured != null
                )
                _status.value = SlotUiState(
                    slot = slotResponse,
                    fileName = localFileName,
                    saveId = save?.id,
                    hasLocalFile = hasLocal,
                    localModifiedMs = localMs,
                    syncAction = determineSyncAction(
                        slotResponse, hasLocal, localMs, deviceSync,
                        localHash = localHash, remoteHash = save?.contentHash,
                        baseline = baseline
                    ),
                    isUntracked = deviceSync?.isUntracked ?: false,
                    backupInfo = if (configured == null) {
                        localFileName?.let { saveRepository.getBackupInfo(it, platformFsSlug) }
                    } else null, // TODO(phase-3-UI): per-member backup info for a configured platform
                    localFilePath = if (configured == null) {
                        localFileName?.let { saveRepository.getLocalFilePath(it, platformFsSlug) }
                    } else resolvedPath
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun enrollInSaveSync(slotKey: String = "default") {
        viewModelScope.launch {
            val key = slotKey.trim().ifBlank { "default" }
            prefsStore.setSaveSyncSlotPref(romId, key)
            prefsStore.enrollInSaveSync(romId)
            loadStatus()
        }
    }

    fun unenrollFromSaveSync() {
        // Cancel any in-flight load so it can't repopulate _status after this
        // clears it (e.g. a load kicked off by enroll racing a quick disable).
        loadJob?.cancel()
        viewModelScope.launch {
            prefsStore.unenrollFromSaveSync(romId)
            _status.value = null
            _error.value = null
        }
    }

    // Advanced override: point this ROM's local save at a different server slot.
    fun setTargetSlot(slotKey: String) {
        viewModelScope.launch {
            prefsStore.setSaveSyncSlotPref(romId, slotKey.trim().ifBlank { "default" })
            loadStatus()
        }
    }

    fun smartSync() {
        val state = _status.value ?: return
        when (state.syncAction) {
            SyncAction.DOWNLOAD -> download(state)
            SyncAction.UPLOAD -> upload(state)
            else -> {}
        }
    }

    // Conflict "Keep Local": the server has a newer save, so force overwrite —
    // otherwise the upload 409s and the conflict just re-appears.
    fun keepLocal() { _status.value?.let { upload(it, overwrite = true) } }
    fun keepRemote() { _status.value?.let { download(it) } }

    // RomM 4.9: pause/resume sync tracking for this save on this device. A paused
    // (untracked) save is treated as no-op by the server's sync negotiation.
    fun toggleTrack() {
        val state = _status.value ?: return
        val saveId = state.saveId ?: return
        viewModelScope.launch {
            setStatus { it.copy(isSyncing = true, message = null, isError = false) }
            try {
                val deviceId = saveRepository.getOrRegisterDeviceId()
                val updated = if (state.isUntracked)
                    saveRepository.trackSave(saveId, deviceId)
                else
                    saveRepository.untrackSave(saveId, deviceId)
                val nowUntracked = updated.deviceSyncs
                    .find { it.deviceId == deviceId }?.isUntracked ?: !state.isUntracked
                setStatus {
                    it.copy(
                        isSyncing = false,
                        isUntracked = nowUntracked,
                        message = if (nowUntracked) "Sync paused on this device" else "Sync resumed",
                        isError = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus { it.copy(isSyncing = false, message = e.message, isError = true) }
            }
        }
    }

    // Message shown when a bulk "sync all" run holds the lock and this screen's
    // action can't proceed right now.
    private val lockedMessage = "Save sync in progress — try again when it finishes"

    private fun download(state: SlotUiState) {
        val slotKey = state.slot.slotKey
        viewModelScope.launch {
            if (!saveSyncLock.mutex.tryLock()) {
                setStatus { it.copy(message = lockedMessage, isError = true) }
                return@launch
            }
            setStatus { it.copy(isSyncing = true, message = null, isError = false) }
            try {
                val save = saveRepository.syncSavesForRom(romId)
                    .filter { it.slot == slotKey }
                    .maxByOrNull { it.updatedAt ?: "" }
                    ?: error("Save not found on server for slot $slotKey")

                val configured = saveLocationRepository.configuredPlatform(platformFsSlug)
                if (configured != null) {
                    downloadConfigured(configured, save, slotKey)
                } else {
                    downloadLegacy(save, slotKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus { it.copy(isSyncing = false, message = e.message, isError = true) }
            } finally {
                saveSyncLock.mutex.unlock()
            }
        }
    }

    private suspend fun downloadLegacy(save: com.theycallmeboxy.caulker.data.api.model.SaveResponse, slotKey: String) {
        val remoteMs = parseIsoToMs(save.updatedAt)
        val localFileName = saveRepository.resolveLocalSaveFileName(
            save.fileName, romFileName, platformFsSlug
        ) ?: save.fileName
        saveRepository.downloadSave(save.id, localFileName, platformFsSlug, remoteMs)
        val newLocalMs = saveRepository.localSaveModifiedMs(localFileName, platformFsSlug)
        val newLocalPath = saveRepository.getLocalFilePath(localFileName, platformFsSlug)
        val newBackupInfo = saveRepository.getBackupInfo(localFileName, platformFsSlug)
        // Now that the local file matches what the server had, that content
        // hash becomes the new common-ancestor baseline for future decisions.
        val baselineHash = save.contentHash
            ?: saveRepository.localSaveStat(localFileName, platformFsSlug)?.contentHash
        if (baselineHash != null) {
            prefsStore.setSyncBaseline(romId, slotKey, SyncBaseline(baselineHash, save.id, save.updatedAt))
        }
        setStatus {
            it.copy(
                isSyncing = false,
                hasLocalFile = true,
                localModifiedMs = newLocalMs,
                syncAction = SyncAction.UP_TO_DATE,
                message = "Downloaded $localFileName",
                isError = false,
                fileName = localFileName,
                localFilePath = newLocalPath,
                backupInfo = newBackupInfo
            )
        }
    }

    // v1 configured-platform download (§12 phase 3): SaveLocationRepository
    // decides raw-vs-zip placement (SaveTransferPlan.kt, verified against
    // Argosy) and backs up every member it's about to overwrite (§6) before
    // writing. A Refused placement (a genuine format mismatch, never a
    // guess -- see SaveTransferPlan.kt) surfaces its reason as an error
    // instead of silently doing nothing.
    private suspend fun downloadConfigured(
        configured: ConfiguredPlatform,
        save: com.theycallmeboxy.caulker.data.api.model.SaveResponse,
        slotKey: String
    ) {
        // preset-aware: SAVE_TARGET-matched presets (Dreamcast, PSP) key on
        // RomEntity.saveTarget, not the ROM filename.
        val matchingKeyName = romEntity?.let { saveLocationRepository.matchingKeyNameFor(configured.preset, it) }
            ?: error("Cannot resolve a matching-key name for this ROM")
        // The unit as it stood before this download, so SaveLocationRepository
        // can clean up any stale member the new content doesn't include (§12
        // phase 3 fixes item 3) -- a scan failure here propagates as a real
        // error (caught by download()'s own try/catch above), not "no local
        // save."
        val previousUnit = romEntity?.let { saveLocationRepository.unitFor(it) }?.second
        val outcome = saveLocationRepository.download(
            configured, matchingKeyName, save.id, save.fileName,
            incomingEmulatorId = save.emulator, previousUnit = previousUnit
        )
        if (!outcome.success) {
            // Nothing was written (or a partial write was rolled back) --
            // never record a baseline for a failed/refused download.
            setStatus { it.copy(isSyncing = false, message = outcome.reason, isError = true) }
            return
        }
        // §5 path (item 1) and hash fallback (item 2) both come from what was
        // ACTUALLY just written -- resolvedPathFor is the same function
        // buildUnit uses so a FOLDER save's path always agrees with what the
        // next scan computes, and writtenContentHash is the new content's
        // own hash, never previousUnit's stale pre-download one.
        val resolvedPath = resolvedPathFor(configured.folderPath, configured.preset.shape, outcome.writtenRelativePaths)
        val baselineHash = save.contentHash ?: outcome.writtenContentHash
        if (baselineHash != null) {
            prefsStore.setSyncBaseline(
                romId, slotKey, SyncBaseline(baselineHash, save.id, save.updatedAt, resolvedPath = resolvedPath)
            )
        }
        setStatus {
            it.copy(
                isSyncing = false,
                hasLocalFile = true,
                syncAction = SyncAction.UP_TO_DATE,
                message = "Downloaded $matchingKeyName",
                isError = false,
                fileName = matchingKeyName,
                localFilePath = resolvedPath
            )
        }
    }

    private fun upload(state: SlotUiState, overwrite: Boolean = false) {
        val slotKey = state.slot.slotKey
        val fileName = state.fileName ?: run {
            setStatus { it.copy(message = "No save file found — make sure your save folder is configured correctly", isError = true) }
            return
        }
        viewModelScope.launch {
            if (!saveSyncLock.mutex.tryLock()) {
                setStatus { it.copy(message = lockedMessage, isError = true) }
                return@launch
            }
            setStatus { it.copy(isSyncing = true, message = null, isError = false) }
            try {
                val configured = saveLocationRepository.configuredPlatform(platformFsSlug)
                // Captured once and reused for both the upload call and the
                // baseline's resolvedPath -- upload() never mutates local files,
                // so re-scanning afterward to get the same unit's resolvedPath
                // again would just be a wasted read (item 7's scan-cost concern).
                val configuredUnit = if (configured != null) {
                    romEntity?.let { saveLocationRepository.unitFor(it) }?.second
                        ?: error("No local save found for \"$fileName\" in the configured folder")
                } else null
                val result = if (configured != null && configuredUnit != null) {
                    saveLocationRepository.upload(configured, configuredUnit, romId, slotKey, overwrite = overwrite)
                } else {
                    saveRepository.uploadSaveFromDisk(romId, slotKey, fileName, platformFsSlug, overwrite = overwrite)
                }
                val newBackupInfo = if (configured == null) saveRepository.getBackupInfo(fileName, platformFsSlug) else null
                // The server now holds exactly what we just uploaded — record its
                // hash as the new common-ancestor baseline for future decisions.
                // §5: on a configured platform, the baseline also records the
                // resolved local path it was uploaded from.
                val resolvedPath = configuredUnit?.resolvedPath
                result.contentHash?.let { hash ->
                    prefsStore.setSyncBaseline(
                        romId, slotKey, SyncBaseline(hash, result.saveId, msToIso(result.serverMs), resolvedPath)
                    )
                }
                setStatus {
                    it.copy(
                        isSyncing = false,
                        hasLocalFile = true,
                        localModifiedMs = result.serverMs,
                        syncAction = SyncAction.UP_TO_DATE,
                        message = "Uploaded $fileName",
                        isError = false,
                        backupInfo = newBackupInfo,
                        slot = it.slot.copy(
                            hasRemote = true,
                            remoteUpdatedAt = msToIso(result.serverMs)
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus { it.copy(isSyncing = false, message = e.message, isError = true) }
            } finally {
                saveSyncLock.mutex.unlock()
            }
        }
    }

    private fun setStatus(transform: (SlotUiState) -> SlotUiState) {
        _status.value = _status.value?.let(transform)
    }
}
