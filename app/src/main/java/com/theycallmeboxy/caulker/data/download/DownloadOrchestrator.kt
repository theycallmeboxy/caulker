package com.theycallmeboxy.caulker.data.download

import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.repository.DownloadProgress
import com.theycallmeboxy.caulker.data.repository.RomRepository
import kotlinx.coroutines.CancellationException
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
        val total: Int,             // ROMs that need downloading (missing only, unless force)
        val currentRomId: Int?,     // id of the ROM currently transferring, so a single-ROM
                                     // caller (the detail screen) can tell if IT is the one running
        val currentRomName: String?,
        val currentFraction: Float  // 0f..1f progress of the current ROM
    ) : BulkDownloadState
    data class Done(
        val label: String,
        val downloaded: Int,
        val skipped: Int,           // already installed + not in local library cache
        val failed: Int,
        // romId -> DownloadProgress.Failed.message, for a single-ROM caller that
        // wants to know whether ITS rom failed and why. Bulk callers (the
        // notification) only need the aggregate counts above.
        val failures: Map<Int, String> = emptyMap(),
        // True when another queued request is about to start (see the queue
        // comment on `download()`) -- the state flow will immediately move back
        // to Downloading. The service uses this to avoid tearing itself down
        // between queued jobs.
        val hasMore: Boolean = false
    ) : BulkDownloadState
    data class Error(val message: String) : BulkDownloadState
}

// A download request that arrived while another one was already running. See
// the queue comment on DownloadOrchestrator.download().
internal data class PendingDownload(val label: String, val romIds: List<Int>, val force: Boolean)

// A tiny FIFO for PendingDownload. Pulled out of DownloadOrchestrator so its
// ordering and clear() behavior can be unit-tested without coroutines or a
// RomRepository in the loop.
internal class PendingDownloadQueue {
    private val items = ArrayDeque<PendingDownload>()
    fun enqueue(item: PendingDownload) { items.addLast(item) }
    fun dequeue(): PendingDownload? = items.removeFirstOrNull()
    fun clear() { items.clear() }
}

// Pure: which of `resolvedRoms` still need downloading, and how many of the
// originally-requested ids are being skipped (already installed, or not in
// the local library cache at all). Split out from `runOne` so the
// force/already-installed filtering rule is unit-testable on its own.
internal data class DownloadPlan(val toDownload: List<RomEntity>, val skipped: Int)

internal fun planDownload(
    requestedIdCount: Int,
    resolvedRoms: List<RomEntity>,
    installedIds: Set<Int>,
    force: Boolean
): DownloadPlan {
    val missingFromCache = requestedIdCount - resolvedRoms.size
    // force=true (re-download) skips the "already installed" filter entirely --
    // everything resolved gets re-fetched. Ids that never resolved to a cached
    // RomEntity still can't be downloaded regardless of force.
    val toDownload = if (force) resolvedRoms else resolvedRoms.filter { it.id !in installedIds }
    val alreadyInstalled = if (force) 0 else resolvedRoms.size - toDownload.size
    return DownloadPlan(toDownload, skipped = alreadyInstalled + missingFromCache)
}

// Downloads a set of ROMs (by id) that aren't already on-device, one at a time,
// reusing RomRepository.downloadRom (which handles multi-disc .m3u and path
// overrides). Modeled on SaveSyncOrchestrator: an app-lifetime scope so a long
// download survives ViewModel destruction; a foreground service owns the
// notification and observes `state`. Membership is passed in by the caller —
// a collection's cached rom_ids, a user's multi-selection, or a single ROM
// from the detail screen — so this stays a pure download engine keyed by a
// label + rom ids.
@Singleton
class DownloadOrchestrator @Inject constructor(
    private val romRepository: RomRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BulkDownloadState>(BulkDownloadState.Idle)
    val state = _state.asStateFlow()

    // Guards `job` and `pending` together. Both "is something running" (used by
    // download() to decide whether to start or enqueue) and "is there more
    // queued work" (used when a job finishes, to decide whether to keep going
    // or let the coroutine end) are decided under this same lock, so a
    // download() call that arrives at exactly the moment the queue drains can
    // never be silently dropped.
    private val lock = Any()
    private var job: Job? = null
    private val pending = PendingDownloadQueue()

    fun isRunning(): Boolean = synchronized(lock) { job?.isActive == true }

    // Starts downloading `romIds` under `label`. If a download is already
    // running, this request is queued (FIFO) instead of being dropped -- it
    // runs once the current job, and anything already ahead of it in the
    // queue, finishes. This is what lets the detail screen's Download button
    // "just work" even if a collection/selection download is already going,
    // rather than the tap silently doing nothing. cancel() drops the whole
    // queue, not just the running job.
    //
    // force=true skips the "already installed" filter (used by Re-download).
    //
    // Returns true if the download started immediately, false if it was
    // queued -- callers that care about a single ROM (the detail screen) use
    // this to show a "queued" state right away instead of waiting on `state`.
    fun download(label: String, romIds: List<Int>, force: Boolean = false): Boolean {
        val request = PendingDownload(label, romIds, force)
        synchronized(lock) {
            if (job?.isActive == true) {
                pending.enqueue(request)
                return false
            }
            job = scope.launch { runQueue(request) }
            return true
        }
    }

    fun cancel() {
        synchronized(lock) {
            pending.clear()
            job?.cancel()
        }
    }

    // Runs `first`, then keeps draining the queue until it's empty. Each
    // queued request gets its own Downloading -> Done cycle (own label,
    // progress and failures) so the notification updates per-job instead of
    // blending unrelated downloads together.
    private suspend fun runQueue(first: PendingDownload) {
        var current: PendingDownload? = first
        while (current != null) {
            current = runOne(current)
        }
    }

    // Runs one request to completion and returns the next queued request (if
    // any). See `finish` for how that decision is made race-free.
    private suspend fun runOne(req: PendingDownload): PendingDownload? {
        try {
            // Resolve to cached ROM entities. Ids not in the local library cache
            // (library not synced yet) can't be downloaded, so they're counted as
            // skipped rather than silently dropped.
            val roms = req.romIds.mapNotNull { romRepository.getById(it) }
            val installed = romRepository.getInstalledRomIds(roms)
            val plan = planDownload(req.romIds.size, roms, installed, req.force)
            val toDownload = plan.toDownload

            if (toDownload.isEmpty()) {
                return finish(BulkDownloadState.Done(req.label, downloaded = 0, skipped = plan.skipped, failed = 0))
            }

            var downloaded = 0
            var failed = 0
            val failures = mutableMapOf<Int, String>()
            _state.value = BulkDownloadState.Downloading(
                req.label, done = 0, total = toDownload.size,
                currentRomId = null, currentRomName = null, currentFraction = 0f
            )

            toDownload.forEachIndexed { index, rom ->
                _state.value = BulkDownloadState.Downloading(
                    req.label, done = index, total = toDownload.size,
                    currentRomId = rom.id, currentRomName = rom.name, currentFraction = 0f
                )
                var ok = false
                romRepository.downloadRom(rom).collect { p ->
                    when (p) {
                        is DownloadProgress.InProgress ->
                            _state.value = BulkDownloadState.Downloading(
                                req.label, done = index, total = toDownload.size,
                                currentRomId = rom.id, currentRomName = rom.name, currentFraction = p.fraction
                            )
                        is DownloadProgress.Done -> ok = true
                        is DownloadProgress.Failed -> {
                            ok = false
                            failures[rom.id] = p.message
                        }
                    }
                }
                if (ok) downloaded++ else failed++
            }

            return finish(
                BulkDownloadState.Done(req.label, downloaded, plan.skipped, failed, failures)
            )
        } catch (e: CancellationException) {
            synchronized(lock) { pending.clear(); job = null }
            _state.value = BulkDownloadState.Idle
            throw e
        } catch (e: Exception) {
            synchronized(lock) { pending.clear(); job = null }
            _state.value = BulkDownloadState.Error(e.message ?: "Download failed")
            return null
        }
    }

    // Atomically checks for the next queued request and stamps `done.hasMore`
    // with whether one is coming, then publishes it in a single state update.
    // If nothing is queued, `job` is cleared right here -- under the same lock
    // download() checks -- so isRunning() flips false at the exact instant a
    // caller could observe "no more work", not a moment later once this
    // coroutine actually returns.
    private fun finish(done: BulkDownloadState.Done): PendingDownload? {
        val next = synchronized(lock) {
            val n = pending.dequeue()
            if (n == null) job = null
            n
        }
        _state.value = done.copy(hasMore = next != null)
        return next
    }
}
