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
import com.theycallmeboxy.caulker.data.saves.SavePresetRegistry
import com.theycallmeboxy.caulker.data.saves.SaveSyncMode
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
    val localFilePath: String? = null,
    // v1 save-location UI (§12 phase 3B) -- all null/default/empty for an
    // unconfigured (legacy) platform, exactly like resolvedPath above.
    // guardWarning: true when the pending DOWNLOAD/CONFLICT action's
    // incoming save is a known-incompatible format for the configured
    // preset (§7) -- computed at load time (not just at download time) so
    // the screen can show the warning before the user even taps anything.
    val guardWarning: Boolean = false,
    val presetMode: SaveSyncMode? = null,
    val presetDisplayName: String? = null,
    val configuredFolderPath: String? = null,
    val unitMemberPaths: List<String> = emptyList(),
    // True when this ROM's local save came from a manual Unassigned-files
    // assignment (§3 rule 3) rather than a rule-based match -- offers an
    // "Unassign" affordance the screen shows instead of pretending it's an
    // ordinary rule match.
    val isManualAssignment: Boolean = false
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
    // Public, read-only re-export of the constructor-captured romId -- the
    // screen needs it (unchanged from the SavedStateHandle-derived value
    // above) to build the Unassigned-files "Choose save file..." nav route,
    // which needs both this and platformId together (see that StateFlow's
    // own doc comment).
    val currentRomId: Int get() = romId
    private var platformFsSlug: String? = null
    private var romFileName: String? = null
    private var romEntity: RomEntity? = null

    private val _romName = MutableStateFlow("")
    val romName = _romName.asStateFlow()

    // This ROM's numeric platform id (Room entity id, not fs slug) -- null
    // until the ROM itself has loaded. Only consumed by the screen to build
    // the "Choose save file..." nav route into the Unassigned-files screen
    // (Screen.UnassignedSaves needs a platformId, same as PlatformSettings/
    // Firmware's routes).
    private val _platformId = MutableStateFlow<Int?>(null)
    val platformId: StateFlow<Int?> = _platformId.asStateFlow()

    // §7 download guard, Phase 3B: true while the user needs to confirm
    // "Download anyway" for the currently pending download/conflict action
    // (status.value.guardWarning was true when they tapped it). The screen
    // shows a confirmation dialog while this is true; confirmGuardedDownload
    // proceeds with confirmedSaveId=state.saveId, dismissGuardedDownload
    // just closes it.
    private val _pendingGuardDownload = MutableStateFlow(false)
    val pendingGuardDownload: StateFlow<Boolean> = _pendingGuardDownload.asStateFlow()

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
            _platformId.value = rom?.platformId
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
                val syncAction = determineSyncAction(
                    slotResponse, hasLocal, localMs, deviceSync,
                    localHash = localHash, remoteHash = save?.contentHash,
                    baseline = baseline, isConfiguredPlatform = configured != null
                )
                // §7 download guard (Phase 3B): only meaningful for the two
                // actions that can actually overwrite the local file with
                // the incoming save (nit: "show the guard icon only on rows
                // that will actually download").
                val guardWarning = configured != null &&
                    (syncAction == SyncAction.DOWNLOAD || syncAction == SyncAction.CONFLICT) &&
                    saveLocationRepository.downloadGuardWarningFor(configured, save?.emulator)
                // Preset display name + Exchange-mode notice (item 4/5) --
                // looked up from the registry's own display metadata, not
                // just the bare SavePreset the resolver works with.
                val presetInfo = configured?.let { c ->
                    SavePresetRegistry.presetsForPlatform(platformFsSlug).firstOrNull { it.preset.key == c.preset.key }
                }
                // Round-2 fixes (blocker-1 follow-on): read straight off the
                // CURRENT unit's own isAssigned flag, not the raw PrefsStore
                // record -- a record can go stale (its target entry gets
                // rule-matched by a different ROM) without being deleted,
                // and this must stop looking "active" the moment that
                // happens, not just after the next failed download attempt
                // clears the record.
                val isManualAssignment = configuredUnit?.second?.isAssigned == true
                _status.value = SlotUiState(
                    slot = slotResponse,
                    fileName = localFileName,
                    saveId = save?.id,
                    hasLocalFile = hasLocal,
                    localModifiedMs = localMs,
                    syncAction = syncAction,
                    isUntracked = deviceSync?.isUntracked ?: false,
                    backupInfo = if (configured == null) {
                        localFileName?.let { saveRepository.getBackupInfo(it, platformFsSlug) }
                    } else null, // TODO(phase-3-UI): per-member backup info for a configured platform
                    localFilePath = if (configured == null) {
                        localFileName?.let { saveRepository.getLocalFilePath(it, platformFsSlug) }
                    } else resolvedPath,
                    guardWarning = guardWarning,
                    presetMode = configured?.preset?.mode,
                    presetDisplayName = presetInfo?.coreDisplayName,
                    configuredFolderPath = configured?.folderPath,
                    unitMemberPaths = configuredUnit?.second?.memberPaths ?: emptyList(),
                    isManualAssignment = isManualAssignment
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
            SyncAction.DOWNLOAD -> downloadOrConfirmGuard(state)
            SyncAction.UPLOAD -> upload(state)
            else -> {}
        }
    }

    // Conflict "Keep Local": the server has a newer save, so force overwrite —
    // otherwise the upload 409s and the conflict just re-appears.
    fun keepLocal() { _status.value?.let { upload(it, overwrite = true) } }
    fun keepRemote() { _status.value?.let { downloadOrConfirmGuard(it) } }

    // §7 download guard (Phase 3B): routes a download through the
    // confirmation dialog when the pending state was already flagged
    // guardWarning at load time (see loadStatus), instead of downloading
    // straight away. Shared by smartSync's DOWNLOAD case and keepRemote,
    // since both can overwrite the local file with a possibly-incompatible
    // incoming save.
    private fun downloadOrConfirmGuard(state: SlotUiState) {
        if (state.guardWarning) _pendingGuardDownload.value = true else download(state)
    }

    // User confirmed "Download anyway" on the guard dialog -- proceed with
    // the same pending state. The confirmation is tied to the specific save
    // id shown in the dialog (state.saveId) -- if a refetch inside
    // download() turns up a DIFFERENT save (someone else uploaded in the
    // meantime), the guard fires again instead of silently trusting a
    // confirmation that was about a different save (Phase 3B fixes nit).
    fun confirmGuardedDownload() {
        _pendingGuardDownload.value = false
        _status.value?.let { download(it, confirmedSaveId = it.saveId) }
    }

    fun dismissGuardedDownload() {
        _pendingGuardDownload.value = false
    }

    // §3 rule 3: reverses a manual Unassigned-file assignment for this ROM.
    // The file itself is untouched -- it just goes back to "unassigned" on
    // this platform's next scan. Item 4: respects the same SaveSyncLock
    // every other mutation does, so it can't race a bulk sync's own
    // negotiate/execute pass reading the assignment map mid-change.
    fun unassign() {
        val slug = platformFsSlug ?: return
        viewModelScope.launch {
            if (!saveSyncLock.mutex.tryLock()) {
                setStatus { it.copy(message = lockedMessage, isError = true) }
                return@launch
            }
            try {
                saveLocationRepository.clearAssignment(slug, romId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus { it.copy(message = e.message ?: "Failed to unassign", isError = true) }
            } finally {
                saveSyncLock.mutex.unlock()
            }
            loadStatus()
        }
    }

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

    private fun download(state: SlotUiState, confirmedSaveId: Int? = null) {
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
                    downloadConfigured(configured, save, slotKey, confirmedSaveId)
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
        slotKey: String,
        confirmedSaveId: Int? = null
    ) {
        val rom = romEntity ?: error("ROM not loaded")
        // Blocker 1 (round 2): resolveDownloadTarget is the single source of
        // truth for where a download lands -- a unit's own matchingKeyName
        // when one exists, a stale-assignment refusal (never a guess) when
        // it doesn't, or the bare preset guess as a last resort. See its own
        // doc comment for why matchingKeyNameFor+unitFor separately (round 1)
        // was unsafe.
        val target = saveLocationRepository.resolveDownloadTarget(configured, rom)
        val (matchingKeyName, previousUnit) = when (target) {
            is SaveLocationRepository.DownloadTarget.Resolved -> target.matchingKeyName to target.previousUnit
            is SaveLocationRepository.DownloadTarget.Refused -> {
                setStatus { it.copy(isSyncing = false, message = target.reason, isError = true) }
                return
            }
        }
        val outcome = saveLocationRepository.download(
            configured, matchingKeyName, save.id, save.fileName,
            incomingEmulatorId = save.emulator, previousUnit = previousUnit, confirmedSaveId = confirmedSaveId
        )
        if (!outcome.success) {
            if (outcome.guardWarning) {
                // Round-2 fixes, should-fix 4: the confirmed save id no
                // longer matches this download's (a refetch returned a
                // DIFFERENT save) -- update status to the NEWLY fetched
                // save's id/emulator before re-prompting, so the NEXT
                // confirm attempt is tied to the save that's actually
                // pending now. Without this the dialog kept re-showing the
                // OLD saveId/emulator forever, and every confirm attempt
                // re-fetched a save whose id never matched the stale
                // confirmedSaveId -- an infinite reprompt loop. Never shows
                // the raw "download guard: ..." string (nit).
                setStatus {
                    it.copy(
                        isSyncing = false,
                        guardWarning = true,
                        saveId = save.id,
                        slot = it.slot.copy(emulator = save.emulator, remoteUpdatedAt = save.updatedAt, hasRemote = true)
                    )
                }
                _pendingGuardDownload.value = true
                return
            }
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
                localFilePath = resolvedPath,
                guardWarning = false,
                unitMemberPaths = outcome.writtenRelativePaths
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
