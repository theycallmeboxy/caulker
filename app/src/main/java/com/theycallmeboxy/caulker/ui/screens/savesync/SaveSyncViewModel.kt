package com.theycallmeboxy.caulker.ui.screens.savesync

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.BackupInfo
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveRepository
import com.theycallmeboxy.caulker.data.sync.SyncAction
import com.theycallmeboxy.caulker.data.sync.determineSyncAction
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val prefsStore: PrefsStore
) : ViewModel() {

    private val romId: Int = checkNotNull(savedStateHandle["romId"])
    private var platformFsSlug: String? = null
    private var romFileName: String? = null

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

    init {
        viewModelScope.launch {
            val rom = romRepository.getById(romId)
            platformFsSlug = rom?.platformFsSlug
            romFileName = rom?.fileName
            _romName.value = rom?.name ?: ""
            loadStatus()
        }
    }

    fun loadStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val deviceId = saveRepository.getOrRegisterDeviceId()
                val target = prefsStore.saveSyncSlotPref(romId).first()

                val saves = saveRepository.syncSavesForRom(romId)
                _serverSlots.value = saves
                    .map { it.slot?.takeIf { s -> s.isNotBlank() } ?: "default" }
                    .distinct()
                    .sorted()

                // The server save for the targeted slot (newest wins if duplicated).
                val save = saves
                    .filter { (it.slot?.takeIf { s -> s.isNotBlank() } ?: "default") == target }
                    .maxByOrNull { it.updatedAt ?: "" }

                // The single physical local file for this ROM. Resolve from the
                // server filename when we have one, else scan the save dir by ROM base.
                val localFileName = saveRepository.resolveLocalSaveFileName(
                    save?.fileName, romFileName, platformFsSlug
                ) ?: save?.fileName
                // Read the local file's hash + mtime once so the decision can
                // short-circuit byte-identical content (matching the server).
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
                _status.value = SlotUiState(
                    slot = slotResponse,
                    fileName = localFileName,
                    saveId = save?.id,
                    hasLocalFile = hasLocal,
                    localModifiedMs = localMs,
                    syncAction = determineSyncAction(
                        slotResponse, hasLocal, localMs, deviceSync,
                        localHash = stat?.contentHash, remoteHash = save?.contentHash
                    ),
                    isUntracked = deviceSync?.isUntracked ?: false,
                    backupInfo = localFileName?.let { saveRepository.getBackupInfo(it, platformFsSlug) },
                    localFilePath = localFileName?.let { saveRepository.getLocalFilePath(it, platformFsSlug) }
                )
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

    fun keepLocal() { _status.value?.let { upload(it) } }
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
            } catch (e: Exception) {
                setStatus { it.copy(isSyncing = false, message = e.message, isError = true) }
            }
        }
    }

    private fun download(state: SlotUiState) {
        val slotKey = state.slot.slotKey
        viewModelScope.launch {
            setStatus { it.copy(isSyncing = true, message = null, isError = false) }
            try {
                val save = saveRepository.syncSavesForRom(romId)
                    .filter { (it.slot?.takeIf { s -> s.isNotBlank() } ?: "default") == slotKey }
                    .maxByOrNull { it.updatedAt ?: "" }
                    ?: error("Save not found on server for slot $slotKey")
                val remoteMs = parseIsoToMs(save.updatedAt)
                val localFileName = saveRepository.resolveLocalSaveFileName(
                    save.fileName, romFileName, platformFsSlug
                ) ?: save.fileName
                saveRepository.downloadSave(save.id, localFileName, platformFsSlug, remoteMs)
                val newLocalMs = saveRepository.localSaveModifiedMs(localFileName, platformFsSlug)
                val newLocalPath = saveRepository.getLocalFilePath(localFileName, platformFsSlug)
                setStatus {
                    it.copy(
                        isSyncing = false,
                        hasLocalFile = true,
                        localModifiedMs = newLocalMs,
                        syncAction = SyncAction.UP_TO_DATE,
                        message = "Downloaded $localFileName",
                        isError = false,
                        fileName = localFileName,
                        localFilePath = newLocalPath
                    )
                }
            } catch (e: Exception) {
                setStatus { it.copy(isSyncing = false, message = e.message, isError = true) }
            }
        }
    }

    private fun upload(state: SlotUiState) {
        val slotKey = state.slot.slotKey
        val fileName = state.fileName ?: run {
            setStatus { it.copy(message = "No save file found — make sure your save folder is configured correctly", isError = true) }
            return
        }
        viewModelScope.launch {
            setStatus { it.copy(isSyncing = true, message = null, isError = false) }
            try {
                val serverMs = saveRepository.uploadSaveFromDisk(romId, slotKey, fileName, platformFsSlug)
                setStatus {
                    it.copy(
                        isSyncing = false,
                        hasLocalFile = true,
                        localModifiedMs = serverMs,
                        syncAction = SyncAction.UP_TO_DATE,
                        message = "Uploaded $fileName",
                        isError = false,
                        slot = it.slot.copy(
                            hasRemote = true,
                            remoteUpdatedAt = java.time.Instant.ofEpochMilli(serverMs).toString()
                        )
                    )
                }
            } catch (e: Exception) {
                setStatus { it.copy(isSyncing = false, message = e.message, isError = true) }
            }
        }
    }

    private fun setStatus(transform: (SlotUiState) -> SlotUiState) {
        _status.value = _status.value?.let(transform)
    }
}
