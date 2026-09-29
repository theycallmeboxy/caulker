package com.theycallmeboxy.caulker.ui.screens.gamedetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.download.BulkDownloadState
import com.theycallmeboxy.caulker.data.download.DownloadOrchestrator
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.service.DownloadForegroundService
import com.theycallmeboxy.caulker.ui.util.buildCoverUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DownloadState {
    object Idle : DownloadState()
    // Our download request is sitting behind another job in the orchestrator's
    // queue (see DownloadOrchestrator.download). Local to this ViewModel --
    // reopening the screen mid-*download* still recovers state from the
    // singleton below, but a purely-queued request (not yet started) doesn't
    // need to survive that, since it isn't doing any work yet.
    object Queued : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

sealed class LocalFileState {
    object Missing : LocalFileState()
    data class Present(val sizeMatch: Boolean) : LocalFileState()
}

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RomRepository,
    private val prefsStore: PrefsStore,
    private val downloadOrchestrator: DownloadOrchestrator,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val romId: Int = checkNotNull(savedStateHandle["romId"])

    private val _rom = MutableStateFlow<RomEntity?>(null)
    val rom = _rom.asStateFlow()

    private val _localFileState = MutableStateFlow<LocalFileState>(LocalFileState.Missing)
    val localFileState = _localFileState.asStateFlow()

    // Set true when our own download() call gets queued instead of started
    // immediately; cleared as soon as the orchestrator's state shows our rom
    // as the one actually transferring, or the queue/job we were waiting on
    // goes away (cancelled, or the orchestrator hit an error).
    private val _queuedLocally = MutableStateFlow(false)

    // Downloads for this screen go through the app-lifetime DownloadOrchestrator
    // + DownloadForegroundService (see GamesViewModel.installSelected), not
    // viewModelScope -- collecting RomRepository.downloadRom directly in
    // viewModelScope used to cancel the download the instant this screen was
    // popped. Deriving downloadState from the orchestrator's singleton `state`
    // (rather than a ViewModel-owned mutable flow written to by a collector)
    // is also what makes reopening this screen mid-download show the
    // in-progress state for free: a freshly created ViewModel just reads
    // whatever the orchestrator is already doing.
    val downloadState: StateFlow<DownloadState> = combine(
        downloadOrchestrator.state, _queuedLocally
    ) { state, queued ->
        when {
            state is BulkDownloadState.Downloading && state.currentRomId == romId ->
                DownloadState.Downloading(state.currentFraction)
            state is BulkDownloadState.Done && state.failures.containsKey(romId) ->
                DownloadState.Error(state.failures.getValue(romId))
            // The whole queue (including our still-pending request) was wiped
            // out by an orchestrator-level failure, not a per-ROM one.
            state is BulkDownloadState.Error && queued ->
                DownloadState.Error(state.message)
            queued -> DownloadState.Queued
            else -> DownloadState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadState.Idle)

    val coverUrl: StateFlow<String?> = combine(_rom, prefsStore.serverUrl) { rom, serverUrl ->
        val path = rom?.coverPath
        if (path != null && serverUrl != null) buildCoverUrl(serverUrl, path) else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            val rom = repository.getById(romId)
            _rom.value = rom
            if (rom != null) checkLocalFile()
        }
        // Side effects driven by the orchestrator's state: drop the "queued"
        // flag once our rom actually starts (or the job we were waiting on
        // disappears), and re-check the local file once our rom stops being
        // the current download -- whether it finished, failed, or the job was
        // cancelled out from under it.
        viewModelScope.launch {
            var wasOurTurn = false
            downloadOrchestrator.state.collect { state ->
                val isOurTurn = state is BulkDownloadState.Downloading && state.currentRomId == romId
                if (isOurTurn) _queuedLocally.value = false
                if (wasOurTurn && !isOurTurn) checkLocalFile()
                wasOurTurn = isOurTurn
                if (state is BulkDownloadState.Idle || state is BulkDownloadState.Error) {
                    _queuedLocally.value = false
                }
            }
        }
    }

    private suspend fun checkLocalFile() {
        val rom = _rom.value ?: return
        val file = repository.localFile(rom)
        _localFileState.value = if (file == null) {
            LocalFileState.Missing
        } else if (rom.hasMultipleFiles) {
            // Multi-file ROMs land an m3u playlist; size-matching against the
            // playlist isn't meaningful, so just report present.
            LocalFileState.Present(sizeMatch = true)
        } else {
            val sizeMatch = rom.fileSize == 0L || file.length() == rom.fileSize
            LocalFileState.Present(sizeMatch)
        }
    }

    fun download() = startDownload(force = false)

    fun reDownload() = startDownload(force = true)

    private fun startDownload(force: Boolean) {
        val rom = _rom.value ?: return
        val startedImmediately = downloadOrchestrator.download(rom.name, listOf(rom.id), force = force)
        _queuedLocally.value = !startedImmediately
        DownloadForegroundService.start(context)
    }

    fun deleteLocalFile() {
        val rom = _rom.value ?: return
        viewModelScope.launch {
            repository.deleteLocalRom(rom)
            _localFileState.value = LocalFileState.Missing
        }
    }
}
