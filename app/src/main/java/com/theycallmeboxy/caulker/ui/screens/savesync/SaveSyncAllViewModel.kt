package com.theycallmeboxy.caulker.ui.screens.savesync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
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
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

private const val MAX_CONCURRENT_FETCHES = 8

data class RomSyncGroup(
    val romId: Int,
    val romName: String,
    val platformName: String?,
    val platformFsSlug: String?,
    val romFileName: String?,
    // One status per ROM — its single local save vs. the targeted server slot.
    val status: SlotUiState
)

@HiltViewModel
class SaveSyncAllViewModel @Inject constructor(
    private val prefsStore: PrefsStore,
    private val romRepository: RomRepository,
    private val platformRepository: PlatformRepository,
    private val saveRepository: SaveRepository,
    private val orchestrator: SaveSyncOrchestrator,
    private val saveSyncLock: SaveSyncLock,
    private val saveLocationRepository: SaveLocationRepository
) : ViewModel() {

    private val _groups = MutableStateFlow<List<RomSyncGroup>>(emptyList())
    val groups = _groups.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val hasPendingChanges: StateFlow<Boolean> = _groups.map { groups ->
        groups.any { it.status.syncAction == SyncAction.UPLOAD || it.status.syncAction == SyncAction.DOWNLOAD }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Bridge the orchestrator's current-rom signal into the per-row UI: when the
    // orchestrator is processing rom X, the X group shows a spinner.
    private val orchestratorState: StateFlow<SaveSyncOverallState> = orchestrator.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SaveSyncOverallState.Idle)

    val syncingRomIds: StateFlow<Set<Int>> = orchestratorState
        .map { s -> if (s is SaveSyncOverallState.Syncing && s.currentRomId != null) setOf(s.currentRomId) else emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isSyncingAll: StateFlow<Boolean> = orchestratorState
        .map { it is SaveSyncOverallState.Syncing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val syncProgressLabel: StateFlow<String?> = orchestratorState
        .map { s ->
            when (s) {
                is SaveSyncOverallState.Syncing ->
                    if (s.total > 0)
                        "Syncing ${s.done + 1} of ${s.total}${s.currentRomName?.let { " — $it" } ?: ""}"
                    else
                        "Preparing…${s.currentRomName?.let { " — $it" } ?: ""}"
                is SaveSyncOverallState.Done ->
                    "Synced — uploaded ${s.uploaded}, downloaded ${s.downloaded}" +
                        (if (s.errors > 0) " (${s.errors} failed)" else "")
                is SaveSyncOverallState.Error -> "Sync failed: ${s.message}"
                else -> null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        refresh()
        // After the orchestrator finishes, repaint the rows so sync states reflect
        // post-sync reality (mtime, hasRemote, etc.). orchestratorState is a fresh
        // StateFlow that replays its current value to every new subscriber — track
        // the previous state so re-entering this screen right after a sync doesn't
        // see a stale "Done" and trigger a redundant refresh.
        viewModelScope.launch {
            var previous: SaveSyncOverallState = orchestratorState.value
            orchestratorState.collect { s ->
                val prev = previous
                previous = s
                if (s is SaveSyncOverallState.Done && prev !is SaveSyncOverallState.Done) refresh()
            }
        }
    }

    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val enrolled = prefsStore.saveSyncEnrolled.first()
                val deviceId = saveRepository.getOrRegisterDeviceId()

                // One scan per distinct configured platform, covering every
                // enrolled ROM on it, BEFORE the concurrent per-ROM fetch below
                // -- otherwise several ROMs on the same platform would each
                // trigger their own concurrent scan of the same folder
                // (independent review, item 7: "must not run a full folder
                // scan per ROM concurrently"). A batch-scan failure here isn't
                // fatal to the whole refresh -- buildGroupForRom falls back to
                // scanning its own ROM individually, which surfaces as that
                // ROM's own error row instead of losing the whole screen.
                val enrolledRoms = enrolled.mapNotNull { romRepository.getById(it) }
                val preScannedUnits = try {
                    saveLocationRepository.unitsFor(enrolledRoms)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyMap()
                }

                val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)
                val newGroups = coroutineScope {
                    enrolled.map { romId ->
                        async {
                            semaphore.withPermit { buildGroupForRom(romId, deviceId, preScannedUnits[romId]) }
                        }
                    }.awaitAll().filterNotNull()
                        .sortedWith(compareBy(
                            { it.platformName?.lowercase() ?: "" },
                            { it.romName.lowercase() }
                        ))
                }
                _groups.value = newGroups
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    // A scan/config failure for one ROM's configured platform must surface as
    // a visible error row, never silently drop the ROM out of the list --
    // returning null here used to mean exactly that (independent review,
    // item 6: "a scan failure must be an error, never 'no local save'").
    private suspend fun buildGroupForRom(romId: Int, deviceId: String, preScannedUnit: com.theycallmeboxy.caulker.data.saves.LocalSaveUnit?): RomSyncGroup? {
        val rom = romRepository.getById(romId) ?: return null
        val platformName = rom.platformId.let { platformRepository.getById(it)?.name }
        val platformFsSlug = rom.platformFsSlug
        val romFileName = rom.fileName
        return try {
            // Match the orchestrator: sync the ROM's one local file against its
            // targeted server slot ("default" unless overridden).
            val target = prefsStore.saveSyncSlotPref(romId).first()
            val save = saveRepository.syncSavesForRom(romId)
                .filter { it.slot == target }
                .maxByOrNull { it.updatedAt ?: "" }

            // v1 save-location wiring (§12 phase 3): a configured platform
            // resolves its local save via the folder/preset scan; an
            // unconfigured one (configured == null) keeps the exact legacy
            // behavior below (§6 hard constraint).
            val configured = saveLocationRepository.configuredPlatform(platformFsSlug)
            val localFileName: String?
            val hasLocal: Boolean
            val localMs: Long
            val localHash: String?
            val resolvedPath: String?
            if (configured != null) {
                val unit = preScannedUnit ?: saveLocationRepository.unitFor(rom)?.second
                localFileName = unit?.matchingKeyName
                hasLocal = unit != null
                localMs = unit?.modifiedMs ?: 0L
                localHash = unit?.contentHash
                resolvedPath = unit?.resolvedPath
            } else {
                localFileName = saveRepository.resolveLocalSaveFileName(
                    save?.fileName, romFileName, platformFsSlug
                ) ?: save?.fileName
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
            val baseline = effectiveBaseline(
                prefsStore.getSyncBaseline(romId, target), resolvedPath, requirePathRecorded = configured != null
            )
            val status = SlotUiState(
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
                isUntracked = deviceSync?.isUntracked ?: false
            )
            RomSyncGroup(romId, rom.name, platformName, platformFsSlug, romFileName, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Real failure (e.g. a configured platform's folder couldn't be
            // scanned) -- shown as an error row rather than vanishing.
            RomSyncGroup(
                romId, rom.name, platformName, platformFsSlug, romFileName,
                SlotUiState(
                    slot = SaveSlotResponse(hasRemote = false),
                    message = e.message ?: "Failed to load save status",
                    isError = true
                )
            )
        }
    }

    fun syncAll() {
        // Hand off to the app-scoped orchestrator. The same code path runs from
        // the QS tile and the foreground service.
        orchestrator.syncAllEnrolled()
    }

    fun cancelSync() {
        orchestrator.cancel()
    }

    fun revertAll() {
        // "Revert" means: for slots that would currently upload (local newer than
        // server), download instead — discard local changes for whatever's
        // currently desynced. Kept local to the ViewModel since it's a different
        // intent than syncAll's "do the right thing per slot."
        //
        // This mutates local save files directly (bypassing the orchestrator),
        // so it must hold the same app-wide lock the orchestrator and per-ROM
        // screen use — otherwise a bulk sync in progress could race these
        // downloads against its own negotiated ops.
        viewModelScope.launch {
            if (!saveSyncLock.mutex.tryLock()) {
                _error.value = "Save sync in progress — try again when it finishes"
                return@launch
            }
            val failures = mutableListOf<String>()
            try {
                _groups.value = _groups.value.map { group ->
                    if (group.status.syncAction == SyncAction.UPLOAD)
                        group.copy(status = group.status.copy(isSyncing = true))
                    else group
                }
                for (group in _groups.value.filter { it.status.syncAction == SyncAction.UPLOAD }) {
                    val reason = revertOne(group)
                    if (reason != null) failures += "${group.romName}: $reason"
                }
            } finally {
                saveSyncLock.mutex.unlock()
            }
            // The revert path must keep and show a refusal reason, not swallow
            // it (independent review, nits) -- surfaced as a combined error
            // rather than per-row, since revertAll() is a single bulk action.
            if (failures.isNotEmpty()) {
                _error.value = "Some reverts failed:\n" + failures.joinToString("\n")
            }
            refresh()
        }
    }

    // Returns null on success, or a human-readable failure reason (surfaced
    // by revertAll() -- independent review nit: "the revert path must keep
    // and show the refusal reason").
    private suspend fun revertOne(group: RomSyncGroup): String? {
        try {
            val slotKey = group.status.slot.slotKey
            val save = saveRepository.syncSavesForRom(group.romId)
                .filter { it.slot == slotKey }
                .maxByOrNull { it.updatedAt ?: "" }
                ?: return "no save found on the server for slot \"$slotKey\""

            val configured = saveLocationRepository.configuredPlatform(group.platformFsSlug)
            if (configured != null) {
                val rom = romRepository.getById(group.romId) ?: return "ROM no longer exists locally"
                // preset-aware: SAVE_TARGET-matched presets (Dreamcast, PSP)
                // key on RomEntity.saveTarget, not the ROM filename.
                val matchingKeyName = saveLocationRepository.matchingKeyNameFor(configured.preset, rom)
                    ?: return "cannot resolve a matching-key name for this ROM"
                val previousUnit = saveLocationRepository.unitFor(rom)?.second
                val outcome = saveLocationRepository.download(
                    configured, matchingKeyName, save.id, save.fileName,
                    incomingEmulatorId = save.emulator, previousUnit = previousUnit
                )
                if (!outcome.success) return outcome.reason ?: "download refused"
                // §5 path (item 1) and hash fallback (item 2) both come from
                // what was ACTUALLY just written, never previousUnit's stale
                // pre-download hash/path.
                val resolvedPath = resolvedPathFor(configured.folderPath, configured.preset.shape, outcome.writtenRelativePaths)
                val baselineHash = save.contentHash ?: outcome.writtenContentHash
                if (baselineHash != null) {
                    prefsStore.setSyncBaseline(
                        group.romId, slotKey, SyncBaseline(baselineHash, save.id, save.updatedAt, resolvedPath = resolvedPath)
                    )
                }
                return null
            }

            val remoteMs = parseIsoToMs(save.updatedAt)
            val localFileName = saveRepository.resolveLocalSaveFileName(
                save.fileName, group.romFileName, group.platformFsSlug
            ) ?: save.fileName
            saveRepository.downloadSave(save.id, localFileName, group.platformFsSlug, remoteMs)
            // The local file now matches what the server had — record that as the
            // new common-ancestor baseline for future decisions.
            val baselineHash = save.contentHash
                ?: saveRepository.localSaveStat(localFileName, group.platformFsSlug)?.contentHash
            if (baselineHash != null) {
                prefsStore.setSyncBaseline(group.romId, slotKey, SyncBaseline(baselineHash, save.id, save.updatedAt))
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "unknown error"
        }
    }
}
