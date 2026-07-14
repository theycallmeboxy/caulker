package com.theycallmeboxy.caulker.ui.screens.savesync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveRepository
import com.theycallmeboxy.caulker.data.sync.SaveSyncOrchestrator
import com.theycallmeboxy.caulker.data.sync.SaveSyncOverallState
import com.theycallmeboxy.caulker.data.sync.SyncAction
import com.theycallmeboxy.caulker.data.sync.determineSyncAction
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val orchestrator: SaveSyncOrchestrator
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
                    "Syncing ${s.done + 1} of ${s.total}${s.currentRomName?.let { " — $it" } ?: ""}"
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
        // post-sync reality (mtime, hasRemote, etc.).
        viewModelScope.launch {
            orchestratorState.collect { s ->
                if (s is SaveSyncOverallState.Done) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
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
                .filter { (it.slot?.takeIf { s -> s.isNotBlank() } ?: "default") == target }
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
        viewModelScope.launch {
            _groups.value = _groups.value.map { group ->
                if (group.status.syncAction == SyncAction.UPLOAD)
                    group.copy(status = group.status.copy(isSyncing = true))
                else group
            }
            for (group in _groups.value.filter { it.status.syncAction == SyncAction.UPLOAD }) {
                revertOne(group)
            }
            refresh()
        }
    }

    private suspend fun revertOne(group: RomSyncGroup) {
        try {
            val slotKey = group.status.slot.slotKey
            val save = saveRepository.syncSavesForRom(group.romId)
                .filter { (it.slot?.takeIf { s -> s.isNotBlank() } ?: "default") == slotKey }
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
