package com.theycallmeboxy.caulker.ui.screens.savesync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveRepository
import com.theycallmeboxy.caulker.data.sync.SaveSyncLock
import com.theycallmeboxy.caulker.data.sync.SaveSyncOrchestrator
import com.theycallmeboxy.caulker.data.sync.SaveSyncOverallState
import com.theycallmeboxy.caulker.data.sync.SyncAction
import com.theycallmeboxy.caulker.data.sync.determineSyncAction
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val saveSyncLock: SaveSyncLock
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
                val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)
                val newGroups = coroutineScope {
                    enrolled.map { romId ->
                        async {
                            semaphore.withPermit { buildGroupForRom(romId, deviceId) }
                        }
                    }.awaitAll().filterNotNull()
                        .sortedWith(compareBy(
                            { it.platformName?.lowercase() ?: "" },
                            { it.romName.lowercase() }
                        ))
                }
                _groups.value = newGroups
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun buildGroupForRom(romId: Int, deviceId: String): RomSyncGroup? {
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
            val localFileName = saveRepository.resolveLocalSaveFileName(
                save?.fileName, romFileName, platformFsSlug
            ) ?: save?.fileName
            val stat = localFileName?.let { saveRepository.localSaveStat(it, platformFsSlug) }
            val hasLocal = stat != null
            val localMs = stat?.modifiedMs ?: 0L
            val slotResponse = SaveSlotResponse(
                slot = save?.slot ?: target.takeIf { it != "default" },
                emulator = save?.emulator,
                hasRemote = save != null,
                remoteUpdatedAt = save?.updatedAt
            )
            val deviceSync = save?.deviceSyncs?.find { it.deviceId == deviceId }
            val status = SlotUiState(
                slot = slotResponse,
                fileName = localFileName,
                saveId = save?.id,
                hasLocalFile = hasLocal,
                localModifiedMs = localMs,
                syncAction = determineSyncAction(
                    slotResponse, hasLocal, localMs, deviceSync,
                    localHash = stat?.contentHash, remoteHash = save?.contentHash
                ),
                isUntracked = deviceSync?.isUntracked ?: false
            )
            RomSyncGroup(romId, rom.name, platformName, platformFsSlug, romFileName, status)
        } catch (_: Exception) {
            null
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
            try {
                _groups.value = _groups.value.map { group ->
                    if (group.status.syncAction == SyncAction.UPLOAD)
                        group.copy(status = group.status.copy(isSyncing = true))
                    else group
                }
                for (group in _groups.value.filter { it.status.syncAction == SyncAction.UPLOAD }) {
                    revertOne(group)
                }
            } finally {
                saveSyncLock.mutex.unlock()
            }
            refresh()
        }
    }

    private suspend fun revertOne(group: RomSyncGroup) {
        try {
            val slotKey = group.status.slot.slotKey
            val save = saveRepository.syncSavesForRom(group.romId)
                .filter { it.slot == slotKey }
                .maxByOrNull { it.updatedAt ?: "" }
                ?: return
            val remoteMs = parseIsoToMs(save.updatedAt)
            val localFileName = saveRepository.resolveLocalSaveFileName(
                save.fileName, group.romFileName, group.platformFsSlug
            ) ?: save.fileName
            saveRepository.downloadSave(save.id, localFileName, group.platformFsSlug, remoteMs)
        } catch (_: Exception) {
            // best-effort revert; row repaints on the refresh() after the loop
        }
    }
}
