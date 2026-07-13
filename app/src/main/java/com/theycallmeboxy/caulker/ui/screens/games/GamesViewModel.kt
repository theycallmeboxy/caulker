package com.theycallmeboxy.caulker.ui.screens.games

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.download.BulkDownloadState
import com.theycallmeboxy.caulker.data.download.DownloadOrchestrator
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.sync.LibrarySyncManager
import com.theycallmeboxy.caulker.service.DownloadForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class InstallFilter { ALL, INSTALLED, NOT_INSTALLED }

// Result of estimating a bulk install: how many selected games aren't on-device
// yet and their combined size, shown in the confirm dialog before downloading.
data class BulkInstallEstimate(val missingCount: Int, val totalBytes: Long, val alreadyPresent: Int)

@OptIn(FlowPreview::class)
@HiltViewModel
class GamesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RomRepository,
    private val platformRepository: PlatformRepository,
    private val prefsStore: PrefsStore,
    private val syncManager: LibrarySyncManager,
    private val downloadOrchestrator: DownloadOrchestrator,
    @ApplicationContext private val context: Context
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

    // --- Multi-select bulk actions ---

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedIds = _selectedIds.asStateFlow()

    // App-scoped bulk download progress (shared with collections), surfaced so
    // the games screen can show a progress bar after a bulk install starts.
    val downloadState: StateFlow<BulkDownloadState> = downloadOrchestrator.state

    fun enterSelection(romId: Int) {
        _selectionMode.value = true
        _selectedIds.value = setOf(romId)
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelection(romId: Int) {
        val cur = _selectedIds.value
        _selectedIds.value = if (romId in cur) cur - romId else cur + romId
    }

    // "All" = every ROM on this platform; "Visible" = the current filtered/searched
    // list; "None" = clear but stay in selection mode.
    fun selectAll() { _selectedIds.value = allRoms.value.map { it.id }.toSet() }
    fun selectVisible() { _selectedIds.value = roms.value.map { it.id }.toSet() }
    fun selectNone() { _selectedIds.value = emptySet() }

    // How much a bulk install of the current selection would actually fetch.
    suspend fun estimateInstall(): BulkInstallEstimate {
        val roms = _selectedIds.value.mapNotNull { repository.getById(it) }
        val installed = repository.getInstalledRomIds(roms)
        val missing = roms.filter { it.id !in installed }
        return BulkInstallEstimate(
            missingCount = missing.size,
            totalBytes = missing.sumOf { it.fileSize },
            alreadyPresent = roms.size - missing.size
        )
    }

    fun installSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty() || downloadOrchestrator.isRunning()) return
        downloadOrchestrator.download("Selected games", ids)
        DownloadForegroundService.start(context)
        exitSelection()
    }

    fun uninstallSelected() {
        val ids = _selectedIds.value.toList()
        exitSelection()
        viewModelScope.launch {
            ids.forEach { id -> repository.getById(id)?.let { repository.deleteLocalRom(it) } }
            refreshInstalledStatus()
        }
    }

    fun enrollSelected() {
        val ids = _selectedIds.value.toList()
        exitSelection()
        viewModelScope.launch { ids.forEach { prefsStore.enrollInSaveSync(it) } }
    }

    fun unenrollSelected() {
        val ids = _selectedIds.value.toList()
        exitSelection()
        viewModelScope.launch { ids.forEach { prefsStore.unenrollFromSaveSync(it) } }
    }

    fun cancelDownload() = downloadOrchestrator.cancel()
}
