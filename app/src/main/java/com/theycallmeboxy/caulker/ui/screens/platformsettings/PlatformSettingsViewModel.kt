package com.theycallmeboxy.caulker.ui.screens.platformsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.PlatformEntity
import com.theycallmeboxy.caulker.data.prefs.PlatformOverride
import com.theycallmeboxy.caulker.data.prefs.PlatformOverrideMode
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlatformSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val platformRepository: PlatformRepository,
    private val prefsStore: PrefsStore
) : ViewModel() {

    private val platformId: Int = checkNotNull(savedStateHandle["platformId"])

    private val _platform = MutableStateFlow<PlatformEntity?>(null)
    val platform = _platform.asStateFlow()

    val romBasePath = prefsStore.romBasePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val saveBasePath = prefsStore.saveBasePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val biosBasePath = prefsStore.biosBasePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val savedOverride: StateFlow<PlatformOverride?> = combine(
        _platform, prefsStore.platformOverrides
    ) { platform, overrides ->
        val key = platform?.fsSlug ?: platform?.slug ?: return@combine null
        overrides[key]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            _platform.value = platformRepository.getById(platformId)
        }
    }

    fun saveOverride(override: PlatformOverride) = viewModelScope.launch {
        val slug = _platform.value?.fsSlug ?: _platform.value?.slug ?: return@launch
        prefsStore.setPlatformOverride(slug, override)
    }
}
