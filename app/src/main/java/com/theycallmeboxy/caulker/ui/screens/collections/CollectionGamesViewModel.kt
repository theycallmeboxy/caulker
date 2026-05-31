package com.theycallmeboxy.caulker.ui.screens.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.CollectionRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.ui.screens.games.InstallFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectionGamesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val romRepository: RomRepository,
    private val collectionRepository: CollectionRepository,
    prefsStore: PrefsStore
) : ViewModel() {

    private val collectionId: Int = savedStateHandle.get<Int>("collectionId") ?: -1

    val searchQuery = MutableStateFlow("")
    val installFilter = MutableStateFlow(InstallFilter.ALL)

    private val _collectionName = MutableStateFlow<String?>(null)
    val collectionName = _collectionName.asStateFlow()

    private val _romIds = MutableStateFlow<List<Int>>(emptyList())

    private val allRoms: StateFlow<List<RomEntity>> = _romIds
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else romRepository.observeByIds(ids)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedRomIds = MutableStateFlow<Set<Int>>(emptySet())
    val installedRomIds = _installedRomIds.asStateFlow()

    val enrolledRomIds: StateFlow<Set<Int>> = prefsStore.saveSyncEnrolled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val serverUrl: StateFlow<String?> = prefsStore.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val roms: StateFlow<List<RomEntity>> = combine(
        allRoms, searchQuery, installFilter, _installedRomIds
    ) { roms, query, filter, installed ->
        roms.filter { rom ->
            val matchesQuery = query.isBlank() || rom.name.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                InstallFilter.ALL -> true
                InstallFilter.INSTALLED -> rom.id in installed
                InstallFilter.NOT_INSTALLED -> rom.id !in installed
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedCount: StateFlow<Int> = combine(allRoms, _installedRomIds) { roms, installed ->
        roms.count { it.id in installed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalCount: StateFlow<Int> = allRoms
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        viewModelScope.launch {
            val collection = collectionRepository.getById(collectionId)
            _collectionName.value = collection?.name
            _romIds.value = parseRomIds(collection?.romIdsJson)
        }
        viewModelScope.launch {
            // Debounce so a bulk upsert during a library sync doesn't trigger a
            // filesystem scan per change.
            allRoms.debounce(300).collect {
                _installedRomIds.value = romRepository.getInstalledRomIds(it)
            }
        }
    }

    fun refreshInstalledStatus() {
        viewModelScope.launch {
            _installedRomIds.value = romRepository.getInstalledRomIds(allRoms.value)
        }
    }

    private fun parseRomIds(json: String?): List<Int> {
        json ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getInt(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
