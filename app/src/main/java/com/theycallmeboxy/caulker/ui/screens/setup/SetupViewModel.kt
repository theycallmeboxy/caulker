package com.theycallmeboxy.caulker.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val prefsStore: PrefsStore
) : ViewModel() {

    val romPath = prefsStore.romBasePath.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val savePath = prefsStore.saveBasePath.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val biosPath = prefsStore.biosBasePath.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val deviceName = prefsStore.deviceName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setRomPath(path: String) = viewModelScope.launch { prefsStore.setRomBasePath(path) }
    fun setSavePath(path: String) = viewModelScope.launch { prefsStore.setSaveBasePath(path) }
    fun setBiosPath(path: String) = viewModelScope.launch { prefsStore.setBiosBasePath(path) }
    fun setDeviceName(name: String) = viewModelScope.launch { prefsStore.setDeviceName(name) }
}
