package com.theycallmeboxy.caulker.data.download

import com.theycallmeboxy.caulker.data.repository.DownloadProgress
import com.theycallmeboxy.caulker.data.repository.RomRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CollectionDownloadState {
    data object Idle : CollectionDownloadState
    data class Downloading(
        val collectionName: String,
        val done: Int,              // ROMs finished so far
        val total: Int,             // ROMs that need downloading (missing only)
        val currentRomName: String?,
        val currentFraction: Float  // 0f..1f progress of the current ROM
    ) : CollectionDownloadState
    data class Done(
        val collectionName: String,
        val downloaded: Int,
        val skipped: Int,           // already installed + not in local library cache
        val failed: Int
    ) : CollectionDownloadState
    data class Error(val message: String) : CollectionDownloadState
}

// Downloads every ROM in a collection that isn't already on-device, one at a
// time, reusing RomRepository.downloadRom (which handles multi-disc .m3u and
// path overrides). Modeled on SaveSyncOrchestrator: an app-lifetime scope so a
// long download survives ViewModel destruction; a foreground service owns the
// notification and observes `state`. Membership is passed in by the caller
// (regular/smart collections carry cached rom_ids, virtual collections carry
// theirs in the listing response), so this stays a pure download engine.
@Singleton
class CollectionDownloadOrchestrator @Inject constructor(
    private val romRepository: RomRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<CollectionDownloadState>(CollectionDownloadState.Idle)
    val state = _state.asStateFlow()

    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun download(collectionName: String, romIds: List<Int>) {
        if (isRunning()) return
        job = scope.launch { run(collectionName, romIds) }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(collectionName: String, romIds: List<Int>) {
        try {
            // Resolve to cached ROM entities. Ids not in the local library cache
            // (library not synced yet) can't be downloaded, so they're counted as
            // skipped rather than silently dropped.
            val roms = romIds.mapNotNull { romRepository.getById(it) }
            val missingFromCache = romIds.size - roms.size
            val installed = romRepository.getInstalledRomIds(roms)
            val toDownload = roms.filter { it.id !in installed }
            val alreadyInstalled = roms.size - toDownload.size
            val baseSkipped = alreadyInstalled + missingFromCache

            if (toDownload.isEmpty()) {
                _state.value = CollectionDownloadState.Done(
                    collectionName, downloaded = 0, skipped = baseSkipped, failed = 0
                )
                return
            }

            var downloaded = 0
            var failed = 0
            _state.value = CollectionDownloadState.Downloading(
                collectionName, done = 0, total = toDownload.size, currentRomName = null, currentFraction = 0f
            )

            toDownload.forEachIndexed { index, rom ->
                _state.value = CollectionDownloadState.Downloading(
                    collectionName, done = index, total = toDownload.size,
                    currentRomName = rom.name, currentFraction = 0f
                )
                var ok = false
                romRepository.downloadRom(rom).collect { p ->
                    when (p) {
                        is DownloadProgress.InProgress ->
                            _state.value = CollectionDownloadState.Downloading(
                                collectionName, done = index, total = toDownload.size,
                                currentRomName = rom.name, currentFraction = p.fraction
                            )
                        is DownloadProgress.Done -> ok = true
                        is DownloadProgress.Failed -> ok = false
                    }
                }
                if (ok) downloaded++ else failed++
            }

            _state.value = CollectionDownloadState.Done(
                collectionName, downloaded = downloaded, skipped = baseSkipped, failed = failed
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = CollectionDownloadState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = CollectionDownloadState.Error(e.message ?: "Collection download failed")
        }
    }
}
