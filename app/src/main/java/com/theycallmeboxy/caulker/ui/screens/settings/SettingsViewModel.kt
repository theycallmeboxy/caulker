package com.theycallmeboxy.caulker.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.RommApiService
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsStore: PrefsStore,
    private val api: RommApiService
) : ViewModel() {

    val serverUrl = prefsStore.serverUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val romPath = prefsStore.romBasePath.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val savePath = prefsStore.saveBasePath.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val biosPath = prefsStore.biosBasePath.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion = _serverVersion.asStateFlow()

    init {
        viewModelScope.launch {
            _serverVersion.value = try { api.heartbeat().version } catch (_: Exception) { null }
        }
    }

    fun setRomPath(path: String) = viewModelScope.launch { prefsStore.setRomBasePath(path) }
    fun setSavePath(path: String) = viewModelScope.launch { prefsStore.setSaveBasePath(path) }
    fun setBiosPath(path: String) = viewModelScope.launch { prefsStore.setBiosBasePath(path) }

    fun logout() = viewModelScope.launch { prefsStore.clearAuth() }

    // Resets the incremental-sync cursor so the next library sync is a full
    // re-fetch of every non-empty platform. Doesn't wipe local rows — the
    // sweep will upsert over them and reconcile any orphans. `onCleared` runs
    // before the caller navigates to SyncProgress so the sweep starts fresh.
    fun clearSyncCursor(onCleared: () -> Unit) {
        viewModelScope.launch {
            prefsStore.setGlobalRomSyncTime(0L)
            onCleared()
        }
    }
}
