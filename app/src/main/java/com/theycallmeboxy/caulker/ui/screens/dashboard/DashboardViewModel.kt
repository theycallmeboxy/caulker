package com.theycallmeboxy.caulker.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.sync.LibrarySyncManager
import com.theycallmeboxy.caulker.data.sync.LibrarySyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    prefsStore: PrefsStore,
    syncManager: LibrarySyncManager
) : ViewModel() {

    val serverUrl: StateFlow<String?> = prefsStore.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val username: StateFlow<String?> = prefsStore.username
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Drives the "Last sync" line; updates live when LibrarySyncManager writes a
    // new cursor at the end of a successful sweep.
    val lastSyncTime: StateFlow<Long> = prefsStore.globalRomSyncTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val syncState: StateFlow<LibrarySyncState> = syncManager.state
}
