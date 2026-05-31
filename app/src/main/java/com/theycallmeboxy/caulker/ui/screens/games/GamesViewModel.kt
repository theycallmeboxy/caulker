package com.theycallmeboxy.caulker.ui.screens.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.sync.LibrarySyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class InstallFilter { ALL, INSTALLED, NOT_INSTALLED }

@OptIn(FlowPreview::class)
@HiltViewModel
class GamesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RomRepository,
    private val platformRepository: PlatformRepository,
    private val prefsStore: PrefsStore,
    private val syncManager: LibrarySyncManager
) : ViewModel() {

    private val platformId: Int = savedStateHandle.get<Int>("platformId") ?: -1

    val searchQuery = MutableStateFlow("")
    val installFilter = MutableStateFlow(InstallFilter.ALL)

    val serverUrl: StateFlow<String?> = prefsStore.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _platformName = MutableStateFlow<String?>(null)
    val platformName = _platformName.asStateFlow()

    private val allRoms: StateFlow<List<RomEntity>> =
        repository.observeByPlatform(platformId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedRomIds = MutableStateFlow<Set<Int>?>(null)
    val installedRomIds: StateFlow<Set<Int>> = _installedRomIds
        .map { it ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Null means the scan hasn't run yet — UI suppresses the count line until ready.
    val installedCountReady: StateFlow<Boolean> = _installedRomIds
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val enrolledRomIds: StateFlow<Set<Int>> = prefsStore.saveSyncEnrolled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val roms: StateFlow<List<RomEntity>> = combine(
        allRoms, searchQuery, installFilter, _installedRomIds
    ) { roms, query, filter, installed ->
        val ids = installed ?: emptySet()
        roms.filter { rom ->
            val matchesQuery = query.isBlank() || rom.name.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                InstallFilter.ALL -> true
                InstallFilter.INSTALLED -> rom.id in ids
                InstallFilter.NOT_INSTALLED -> rom.id !in ids
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedCount: StateFlow<Int> = combine(allRoms, _installedRomIds) { roms, installed ->
        roms.count { it.id in (installed ?: emptySet()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalCount: StateFlow<Int> = allRoms
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Pull-to-refresh gesture flag for this platform's per-platform refresh.
    private val _userRefreshing = MutableStateFlow(false)
    val userRefreshing = _userRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch {
            _platformName.value = platformRepository.getById(platformId)?.name
        }
        viewModelScope.launch {
            // Re-scan installed status whenever the cached rom list changes (the
            // global sweep may stream rows in while this screen is open, or a
            // per-platform pull-to-refresh writes a batch). Debounced so bursts
            // collapse into one filesystem scan.
            allRoms.debounce(300).collect { _installedRomIds.value = repository.getInstalledRomIds(it) }
        }
        // No automatic library sync from this screen — that's a dashboard action.
        // Pull-to-refresh on this screen still re-syncs just this platform.
    }

    fun refresh() {
        viewModelScope.launch {
            _userRefreshing.value = true
            _error.value = null
            try {
                syncManager.refreshPlatform(platformId)
                refreshInstalledStatus()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _userRefreshing.value = false
            }
        }
    }

    fun refreshInstalledStatus() {
        viewModelScope.launch {
            _installedRomIds.value = repository.getInstalledRomIds(allRoms.value)
        }
    }

    fun refreshInstalledStatusIfLoaded() {
        if (allRoms.value.isNotEmpty()) refreshInstalledStatus()
    }
}
