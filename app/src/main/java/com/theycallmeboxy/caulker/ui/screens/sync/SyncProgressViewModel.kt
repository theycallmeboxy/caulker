package com.theycallmeboxy.caulker.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.sync.LibrarySyncManager
import com.theycallmeboxy.caulker.data.sync.LibrarySyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SyncProgressViewModel @Inject constructor(
    private val syncManager: LibrarySyncManager
) : ViewModel() {

    val state: StateFlow<LibrarySyncState> = syncManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibrarySyncState.Idle)

    init {
        // If a sweep isn't already in flight, kick one off when the screen opens.
        // refreshAll() is a no-op when one is already running.
        if (!syncManager.isRunning()) syncManager.refreshAll()
    }

    fun retry() {
        syncManager.refreshAll()
    }
}
