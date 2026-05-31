package com.theycallmeboxy.caulker.ui.screens.firmware

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.RommApiService
import com.theycallmeboxy.caulker.data.api.model.FirmwareResponse
import com.theycallmeboxy.caulker.data.repository.DownloadProgress
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class FirmwareLocalState {
    object Missing : FirmwareLocalState()
    data class Present(val sizeMatch: Boolean) : FirmwareLocalState()
}

sealed class FirmwareDownloadState {
    object Idle : FirmwareDownloadState()
    data class Downloading(val progress: Float) : FirmwareDownloadState()
    data class Error(val message: String) : FirmwareDownloadState()
}

data class FirmwareUiState(
    val firmware: FirmwareResponse,
    val localState: FirmwareLocalState = FirmwareLocalState.Missing,
    val downloadState: FirmwareDownloadState = FirmwareDownloadState.Idle
)

@HiltViewModel
class FirmwareViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: RommApiService,
    private val repository: RomRepository,
    private val platformRepository: PlatformRepository
) : ViewModel() {

    private val platformId: Int = checkNotNull(savedStateHandle["platformId"])
    private var platformFsSlug: String? = null

    private val _items = MutableStateFlow<List<FirmwareUiState>>(emptyList())
    val items = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch {
            platformFsSlug = platformRepository.getById(platformId)?.fsSlug
            load()
        }
    }

    private suspend fun checkLocalState(firmware: FirmwareResponse): FirmwareLocalState {
        val file = withContext(Dispatchers.IO) {
            repository.localFirmwareFile(firmware.fileName, platformFsSlug)
        } ?: return FirmwareLocalState.Missing
        val sizeMatch = firmware.fileSize == 0L || file.length() == firmware.fileSize
        return FirmwareLocalState.Present(sizeMatch)
    }

    private fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val firmware = api.getFirmware(platformId)
                _items.value = firmware.map { fw ->
                    FirmwareUiState(fw, localState = checkLocalState(fw))
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load firmware"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun download(firmware: FirmwareResponse) {
        viewModelScope.launch {
            updateItem(firmware.id) { it.copy(downloadState = FirmwareDownloadState.Downloading(0f)) }
            repository.downloadFirmware(firmware.id, firmware.fileName, platformFsSlug).collect { progress ->
                when (progress) {
                    is DownloadProgress.InProgress ->
                        updateItem(firmware.id) {
                            it.copy(downloadState = FirmwareDownloadState.Downloading(progress.fraction))
                        }
                    is DownloadProgress.Done -> {
                        val localState = checkLocalState(firmware)
                        updateItem(firmware.id) {
                            it.copy(downloadState = FirmwareDownloadState.Idle, localState = localState)
                        }
                    }
                    is DownloadProgress.Failed ->
                        updateItem(firmware.id) {
                            it.copy(downloadState = FirmwareDownloadState.Error(progress.message))
                        }
                }
            }
        }
    }

    fun deleteFirmware(firmware: FirmwareResponse) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteLocalFirmware(firmware.fileName, platformFsSlug)
            }
            updateItem(firmware.id) {
                it.copy(localState = FirmwareLocalState.Missing, downloadState = FirmwareDownloadState.Idle)
            }
        }
    }

    private fun updateItem(id: Int, transform: (FirmwareUiState) -> FirmwareUiState) {
        _items.value = _items.value.map { if (it.firmware.id == id) transform(it) else it }
    }
}
