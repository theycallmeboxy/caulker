package com.theycallmeboxy.caulker.data.sync

import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveRepository
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SaveSyncOverallState {
    data object Idle : SaveSyncOverallState
    data class Syncing(
        val done: Int,
        val total: Int,
        val currentRomId: Int? = null,
        val currentRomName: String? = null
    ) : SaveSyncOverallState
    data class Done(val uploaded: Int, val downloaded: Int, val skipped: Int, val errors: Int) : SaveSyncOverallState
    data class Error(val message: String) : SaveSyncOverallState
}

// App-scoped driver for "sync all save-enrolled ROMs" — used by the in-app
// SaveSyncAll button, the foreground service, and the QS tile. Runs in an
// app-lifetime scope so it survives ViewModel destruction; observers just
// collect `state`.
@Singleton
class SaveSyncOrchestrator @Inject constructor(
    private val prefsStore: PrefsStore,
    private val saveRepository: SaveRepository,
    private val romRepository: RomRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SaveSyncOverallState>(SaveSyncOverallState.Idle)
    val state: StateFlow<SaveSyncOverallState> = _state.asStateFlow()

    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    // Fire-and-forget. No-op if a sync is already running. Resets to Idle
    // first so observers see a clean transition rather than Done→Syncing.
    fun syncAllEnrolled() {
        if (job?.isActive == true) return
        _state.value = SaveSyncOverallState.Idle
        job = scope.launch { run() }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun run() {
        try {
            val enrolled = prefsStore.saveSyncEnrolled.first().toList()
            if (enrolled.isEmpty()) {
                _state.value = SaveSyncOverallState.Done(0, 0, 0, 0)
                return
            }

            val total = enrolled.size
            var uploaded = 0
            var downloaded = 0
            var skipped = 0
            var errors = 0
            val deviceId = saveRepository.getOrRegisterDeviceId()

            _state.value = SaveSyncOverallState.Syncing(done = 0, total = total)
            enrolled.forEachIndexed { index, romId ->
                val rom = romRepository.getById(romId)
                _state.value = SaveSyncOverallState.Syncing(
                    done = index,
                    total = total,
                    currentRomId = romId,
                    currentRomName = rom?.name
                )
                if (rom == null) {
                    skipped++
                    return@forEachIndexed
                }
                when (syncOne(rom, deviceId)) {
                    PerRomResult.Uploaded -> uploaded++
                    PerRomResult.Downloaded -> downloaded++
                    PerRomResult.Skipped -> skipped++
                    PerRomResult.Conflict -> skipped++
                    is PerRomResult.Failed -> errors++
                }
            }
            _state.value = SaveSyncOverallState.Done(uploaded, downloaded, skipped, errors)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = SaveSyncOverallState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = SaveSyncOverallState.Error(e.message ?: "Save sync failed")
        }
    }

    private suspend fun syncOne(rom: RomEntity, deviceId: String): PerRomResult {
        return try {
            // Pick the user's preferred slot for this ROM and find its server save.
            val preferredSlot = prefsStore.saveSyncSlotPref(rom.id).first()
            val saves = saveRepository.syncSavesForRom(rom.id)
            val save = saves
                .filter { (it.slot?.takeIf { s -> s.isNotBlank() } ?: "default") == preferredSlot }
                .maxByOrNull { it.updatedAt ?: "" }

            val localFileName = saveRepository.resolveLocalSaveFileName(
                save?.fileName, rom.fileName, rom.platformFsSlug
            ) ?: save?.fileName ?: return PerRomResult.Skipped

            val hasLocal = saveRepository.hasLocalSave(localFileName, rom.platformFsSlug)
            val localMs = if (hasLocal)
                saveRepository.localSaveModifiedMs(localFileName, rom.platformFsSlug)
            else 0L

            val slotResponse = SaveSlotResponse(
                slot = save?.slot,
                emulator = save?.emulator,
                hasRemote = save != null,
                remoteUpdatedAt = save?.updatedAt
            )
            val deviceSync = save?.deviceSyncs?.find { it.deviceId == deviceId }
            val action = determineSyncAction(slotResponse, hasLocal, localMs, deviceSync)

            when (action) {
                SyncAction.UPLOAD -> {
                    saveRepository.uploadSaveFromDisk(
                        rom.id, preferredSlot, localFileName, rom.platformFsSlug
                    )
                    PerRomResult.Uploaded
                }
                SyncAction.DOWNLOAD -> {
                    val remoteSave = save ?: return PerRomResult.Skipped
                    val remoteMs = parseIsoToMs(remoteSave.updatedAt)
                    saveRepository.downloadSave(remoteSave.id, localFileName, rom.platformFsSlug, remoteMs)
                    PerRomResult.Downloaded
                }
                SyncAction.UP_TO_DATE, SyncAction.NONE -> PerRomResult.Skipped
                SyncAction.CONFLICT -> PerRomResult.Conflict
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't swallow cancellation — let it bubble to run() so the loop stops.
            throw e
        } catch (e: Exception) {
            PerRomResult.Failed(e.message ?: "unknown")
        }
    }

    private sealed interface PerRomResult {
        data object Uploaded : PerRomResult
        data object Downloaded : PerRomResult
        data object Skipped : PerRomResult
        data object Conflict : PerRomResult
        data class Failed(val message: String) : PerRomResult
    }
}
