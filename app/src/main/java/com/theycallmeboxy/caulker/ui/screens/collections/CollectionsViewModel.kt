package com.theycallmeboxy.caulker.ui.screens.collections

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.model.VirtualCollectionResponse
import com.theycallmeboxy.caulker.data.db.entity.CollectionEntity
import com.theycallmeboxy.caulker.data.download.CollectionDownloadOrchestrator
import com.theycallmeboxy.caulker.data.download.CollectionDownloadState
import com.theycallmeboxy.caulker.data.repository.CollectionRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.service.CollectionDownloadForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

// What a "Download collection" action will do, computed before it starts so the
// user sees the cost: how many members aren't on-device yet and their total size.
data class DownloadEstimate(
    val missingCount: Int,
    val totalBytes: Long,
    val alreadyPresent: Int
)

// Lists collections from the local cache. Sync runs as part of the library
// sweep from the dashboard's "Sync database now" — this screen is cache-only.
// Virtual collections (auto-generated groupings) are fetched live since they
// aren't cached to Room. Collections are action targets, not browsable: the one
// action is "download everything in this collection that isn't already here."
@HiltViewModel
class CollectionsViewModel @Inject constructor(
    repository: CollectionRepository,
    private val romRepository: RomRepository,
    private val downloadOrchestrator: CollectionDownloadOrchestrator,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val collections: StateFlow<List<CollectionEntity>> = combine(
        repository.observeAll(),
        _searchQuery
    ) { all, query ->
        all.filter { c -> query.isBlank() || c.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _virtualCollections = MutableStateFlow<List<VirtualCollectionResponse>>(emptyList())
    val virtualCollections: StateFlow<List<VirtualCollectionResponse>> = combine(
        _virtualCollections,
        _searchQuery
    ) { all, query ->
        all.filter { vc -> query.isBlank() || vc.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadState: StateFlow<CollectionDownloadState> = downloadOrchestrator.state

    init {
        viewModelScope.launch {
            _virtualCollections.value = repository.getVirtualCollections()
                .sortedBy { it.name.lowercase() }
        }
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    // Member ROM ids for a regular/smart collection come from the cached rom_ids
    // JSON; virtual collections carry theirs on the listing response.
    fun memberIds(collection: CollectionEntity): List<Int> = parseRomIds(collection.romIdsJson)

    // How much a download of these members would actually fetch: skips members
    // already installed on-device (and members not present in the local library
    // cache, which can't be downloaded). Suspends — call from the confirm dialog.
    suspend fun estimate(romIds: List<Int>): DownloadEstimate {
        val roms = romIds.mapNotNull { romRepository.getById(it) }
        val installed = romRepository.getInstalledRomIds(roms)
        val missing = roms.filter { it.id !in installed }
        return DownloadEstimate(
            missingCount = missing.size,
            totalBytes = missing.sumOf { it.fileSize },
            alreadyPresent = roms.size - missing.size
        )
    }

    fun downloadCollection(name: String, romIds: List<Int>) {
        if (downloadOrchestrator.isRunning()) return
        downloadOrchestrator.download(name, romIds)
        // Foreground service keeps the process alive and shows a progress
        // notification with a cancel action while the app-scoped orchestrator runs.
        CollectionDownloadForegroundService.start(context)
    }

    fun cancelDownload() {
        downloadOrchestrator.cancel()
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
