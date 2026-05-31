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
    val isPreferred: Boolean = false,
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

    private val _allSlots = MutableStateFlow<List<SlotUiState>>(emptyList())

    private val _romName = MutableStateFlow("")
    val romName = _romName.asStateFlow()

    val preferredSlotKey: StateFlow<String> = prefsStore.saveSyncSlotPref(romId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    val isSaveSyncEnrolled: StateFlow<Boolean> = prefsStore.saveSyncEnrolled
        .map { it.contains(romId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val visibleSlotKeys: StateFlow<Set<String>> = prefsStore.visibleSaveSlots(romId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Only expose slots the user has made visible, plus always the preferred slot.
    val slots: StateFlow<List<SlotUiState>> = combine(
        _allSlots, visibleSlotKeys, preferredSlotKey
    ) { all, visible, preferred ->
        all.filter { it.slot.slotKey == preferred || it.slot.slotKey in visible }
            .sortedByDescending { it.isPreferred }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _serverSlots = MutableStateFlow<List<String>>(emptyList())
    val serverSlots: StateFlow<List<String>> = _serverSlots.asStateFlow()

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
            loadSummary()
        }
        viewModelScope.launch {
            prefsStore.saveSyncSlotPref(romId).collect { pref ->
                _allSlots.value = _allSlots.value
                    .map { it.copy(isPreferred = it.slot.slotKey == pref) }
            }
        }
    }

    fun loadSummary() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val deviceId = saveRepository.getOrRegisterDeviceId()
                val preferredSlot = prefsStore.saveSyncSlotPref(romId).first()
                val visibleSlots = prefsStore.visibleSaveSlots(romId).first()

                val saves = saveRepository.syncSavesForRom(romId)
                    .groupBy { it.slot?.takeIf { s -> s.isNotBlank() } ?: "default" }
                    .values
                    .map { group -> group.maxByOrNull { it.updatedAt ?: "" }!! }

                val serverSlotKeys = saves.map { it.slot?.takeIf { s -> s.isNotBlank() } ?: "default" }.toSet()

                val serverStates = saves.map { save ->
                    val slotKey = save.slot?.takeIf { it.isNotBlank() } ?: "default"
                    val localFileName = saveRepository.resolveLocalSaveFileName(
                        save.fileName, romFileName, platformFsSlug
                    ) ?: save.fileName
                    val hasLocal = saveRepository.hasLocalSave(localFileName, platformFsSlug)
                    val localMs = if (hasLocal)
                        saveRepository.localSaveModifiedMs(localFileName, platformFsSlug)
                    else 0L
                    val slotResponse = SaveSlotResponse(
                        slot = save.slot,
                        emulator = save.emulator,
                        hasRemote = true,
                        remoteUpdatedAt = save.updatedAt
                    )
                    val deviceSync = save.deviceSyncs.find { it.deviceId == deviceId }
                    SlotUiState(
                        slot = slotResponse,
                        fileName = localFileName,
                        saveId = save.id,
                        hasLocalFile = hasLocal,
                        localModifiedMs = localMs,
                        syncAction = determineSyncAction(slotResponse, hasLocal, localMs, deviceSync),
                        isPreferred = slotKey == preferredSlot,
                        backupInfo = saveRepository.getBackupInfo(localFileName, platformFsSlug),
                        localFilePath = saveRepository.getLocalFilePath(localFileName, platformFsSlug)
                    )
                }

                // Synthesize states for visible + preferred slots not yet on the server.
                // All slots on this ROM share the same physical local file, so reuse a
                // resolved server filename when available; otherwise scan the save dir for
                // a file matching the ROM base (handles fresh enrollment with no server saves).
                val refFileName = serverStates.firstOrNull { it.hasLocalFile }?.fileName
                    ?: serverStates.firstOrNull()?.fileName
                    ?: saveRepository.resolveLocalSaveFileName(null, romFileName, platformFsSlug)
                val toSynthesize = (visibleSlots + preferredSlot) - serverSlotKeys
                val synthStates = toSynthesize.map { slotKey ->
                    buildSynthesizedSlot(slotKey, preferredSlot, refFileName)
                }

                _allSlots.value = serverStates + synthStates
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setPreferredSlot(state: SlotUiState) {
        viewModelScope.launch {
            prefsStore.setSaveSyncSlotPref(romId, state.slot.slotKey)
            _allSlots.value = _allSlots.value.map { it.copy(isPreferred = it.slot.slotKey == state.slot.slotKey) }
        }
    }

    fun loadServerSlots() {
        viewModelScope.launch {
            try {
                val saves = saveRepository.syncSavesForRom(romId)
                _serverSlots.value = saves
                    .map { it.slot?.takeIf { s -> s.isNotBlank() } ?: "default" }
                    .distinct()
                    .sorted()
            } catch (_: Exception) {
                _serverSlots.value = emptyList()
            }
        }
    }

    fun enrollInSaveSync(slotKey: String) {
        viewModelScope.launch {
            val key = slotKey.trim().ifBlank { "default" }
            prefsStore.setSaveSyncSlotPref(romId, key)
            prefsStore.addVisibleSaveSlot(romId, key)
            prefsStore.enrollInSaveSync(romId)
            loadSummary()
        }
    }

    fun addSlot(slotKey: String) {
        val key = slotKey.trim().ifBlank { "default" }
        viewModelScope.launch {
            prefsStore.addVisibleSaveSlot(romId, key)
            // Synthesize a local state if this slot isn't already loaded from the server.
            if (_allSlots.value.none { it.slot.slotKey == key }) {
                val refFileName = _allSlots.value.firstOrNull { it.hasLocalFile }?.fileName
                    ?: _allSlots.value.firstOrNull()?.fileName
                    ?: saveRepository.resolveLocalSaveFileName(null, romFileName, platformFsSlug)
                _allSlots.value = _allSlots.value + buildSynthesizedSlot(key, preferredSlotKey.value, refFileName)
            }
        }
    }

    private suspend fun buildSynthesizedSlot(
        slotKey: String,
        preferredSlot: String,
        refFileName: String?
    ): SlotUiState {
        val hasLocal = refFileName?.let { saveRepository.hasLocalSave(it, platformFsSlug) } ?: false
        val localMs = refFileName
            ?.takeIf { hasLocal }
            ?.let { saveRepository.localSaveModifiedMs(it, platformFsSlug) }
            ?: 0L
        val synthSlot = SaveSlotResponse(slot = slotKey, hasRemote = false)
        return SlotUiState(
            slot = synthSlot,
            fileName = refFileName,
            hasLocalFile = hasLocal,
            localModifiedMs = localMs,
            syncAction = determineSyncAction(synthSlot, hasLocal, localMs, null),
            isPreferred = slotKey == preferredSlot,
            backupInfo = refFileName?.let { saveRepository.getBackupInfo(it, platformFsSlug) },
            localFilePath = refFileName?.let { saveRepository.getLocalFilePath(it, platformFsSlug) }
        )
    }

    fun removeSlot(slotKey: String) {
        if (slotKey == preferredSlotKey.value) return
        viewModelScope.launch {
            prefsStore.removeVisibleSaveSlot(romId, slotKey)
        }
    }

    fun unenrollFromSaveSync() {
        viewModelScope.launch {
            prefsStore.unenrollFromSaveSync(romId)
            prefsStore.clearVisibleSaveSlots(romId)
            _allSlots.value = emptyList()
            _serverSlots.value = emptyList()
            _error.value = null
        }
    }

    fun smartSync(state: SlotUiState) {
        when (state.syncAction) {
            SyncAction.DOWNLOAD -> downloadSlot(state)
            SyncAction.UPLOAD -> uploadFromDisk(state)
            else -> {}
        }
    }

    fun keepLocal(state: SlotUiState) = uploadFromDisk(state)
    fun keepRemote(state: SlotUiState) = downloadSlot(state)

    fun downloadSlot(state: SlotUiState) {
        val slotKey = state.slot.slotKey
        viewModelScope.launch {
            updateSlot(slotKey) { it.copy(isSyncing = true, message = null, isError = false) }
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
                updateSlot(slotKey) {
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
                updateSlot(slotKey) { it.copy(isSyncing = false, message = e.message, isError = true) }
            }
        }
    }

    fun uploadFromDisk(state: SlotUiState) {
        val slotKey = state.slot.slotKey
        val fileName = state.fileName ?: run {
            updateSlot(slotKey) { it.copy(message = "No save file found — make sure your save folder is configured correctly", isError = true) }
            return
        }
        viewModelScope.launch {
            updateSlot(slotKey) { it.copy(isSyncing = true, message = null, isError = false) }
            try {
                val serverMs = saveRepository.uploadSaveFromDisk(romId, slotKey, fileName, platformFsSlug)
                updateSlot(slotKey) {
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
                updateSlot(slotKey) { it.copy(isSyncing = false, message = e.message, isError = true) }
            }
        }
    }

    private fun updateSlot(slotKey: String, transform: (SlotUiState) -> SlotUiState) {
        _allSlots.value = _allSlots.value.map { if (it.slot.slotKey == slotKey) transform(it) else it }
    }
}
