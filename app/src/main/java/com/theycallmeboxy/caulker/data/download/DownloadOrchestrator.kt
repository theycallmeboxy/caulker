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

sealed interface BulkDownloadState {
    data object Idle : BulkDownloadState
    data class Downloading(
        val label: String,
        val done: Int,              // ROMs finished so far
        val total: Int,             // ROMs that need downloading (missing only)
        val currentRomName: String?,
        val currentFraction: Float  // 0f..1f progress of the current ROM
    ) : BulkDownloadState
    data class Done(
        val label: String,
        val downloaded: Int,
        val skipped: Int,           // already installed + not in local library cache
        val failed: Int
    ) : BulkDownloadState
    data class Error(val message: String) : BulkDownloadState
}

// Downloads a set of ROMs (by id) that aren't already on-device, one at a time,
// reusing RomRepository.downloadRom (which handles multi-disc .m3u and path
// overrides). Modeled on SaveSyncOrchestrator: an app-lifetime scope so a long
// download survives ViewModel destruction; a foreground service owns the
// notification and observes `state`. Membership is passed in by the caller —
// a collection's cached rom_ids, or a user's multi-selection — so this stays a
// pure download engine keyed by a label + rom ids.
@Singleton
class DownloadOrchestrator @Inject constructor(
    private val romRepository: RomRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BulkDownloadState>(BulkDownloadState.Idle)
    val state = _state.asStateFlow()

    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun download(label: String, romIds: List<Int>) {
        if (isRunning()) return
        job = scope.launch { run(label, romIds) }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(label: String, romIds: List<Int>) {
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
                _state.value = BulkDownloadState.Done(
                    label, downloaded = 0, skipped = baseSkipped, failed = 0
                )
                return
            }

            var downloaded = 0
            var failed = 0
            _state.value = BulkDownloadState.Downloading(
                label, done = 0, total = toDownload.size, currentRomName = null, currentFraction = 0f
            )

            toDownload.forEachIndexed { index, rom ->
                _state.value = BulkDownloadState.Downloading(
                    label, done = index, total = toDownload.size,
                    currentRomName = rom.name, currentFraction = 0f
                )
                var ok = false
                romRepository.downloadRom(rom).collect { p ->
                    when (p) {
                        is DownloadProgress.InProgress ->
                            _state.value = BulkDownloadState.Downloading(
                                label, done = index, total = toDownload.size,
                                currentRomName = rom.name, currentFraction = p.fraction
                            )
                        is DownloadProgress.Done -> ok = true
                        is DownloadProgress.Failed -> ok = false
                    }
                }
                if (ok) downloaded++ else failed++
            }

            _state.value = BulkDownloadState.Done(
                label, downloaded = downloaded, skipped = baseSkipped, failed = failed
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = BulkDownloadState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = BulkDownloadState.Error(e.message ?: "Download failed")
        }
    }
}
