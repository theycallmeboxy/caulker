package com.theycallmeboxy.caulker.ui.screens.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.DownloadProgress
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.ui.util.buildCoverUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DownloadState {
    object Idle : DownloadState()
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
    private val prefsStore: PrefsStore
) : ViewModel() {

    private val romId: Int = checkNotNull(savedStateHandle["romId"])

    private val _rom = MutableStateFlow<RomEntity?>(null)
    val rom = _rom.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    private val _localFileState = MutableStateFlow<LocalFileState>(LocalFileState.Missing)
    val localFileState = _localFileState.asStateFlow()

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

    fun download() {
        val rom = _rom.value ?: return
        viewModelScope.launch {
            try {
                repository.downloadRom(rom).collect { progress ->
                    when (progress) {
                        is DownloadProgress.InProgress ->
                            _downloadState.value = DownloadState.Downloading(progress.fraction)
                        is DownloadProgress.Done -> {
                            checkLocalFile()
                            _downloadState.value = DownloadState.Idle
                        }
                        is DownloadProgress.Failed ->
                            _downloadState.value = DownloadState.Error(progress.message)
                    }
                }
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun deleteLocalFile() {
        val rom = _rom.value ?: return
        viewModelScope.launch {
            repository.deleteLocalRom(rom)
            _localFileState.value = LocalFileState.Missing
        }
    }
}
