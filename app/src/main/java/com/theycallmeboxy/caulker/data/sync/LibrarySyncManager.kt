package com.theycallmeboxy.caulker.data.sync

import com.theycallmeboxy.caulker.data.repository.CollectionRepository
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncPhase { Platforms, Roms }

sealed interface LibrarySyncState {
    data object Idle : LibrarySyncState
    // For phase == Roms: done/total count platforms processed, currentPlatform
    // is the system being fetched right now, and rowsInCurrent ticks up as
    // each page of the current platform's ROMs lands. Other phases use
    // indeterminate UI (total=0, no current name, rowsInCurrent=0).
    data class Syncing(
        val phase: SyncPhase,
        val done: Int,
        val total: Int,
        val currentPlatform: String? = null,
        val rowsInCurrent: Int = 0
    ) : LibrarySyncState
    data class Error(val message: String) : LibrarySyncState
}

// Owns the whole-library sync as an app-scoped job so it survives screen
// navigation. One global paginated ROM sweep populates every platform's list at
// once (each ROM carries its platform_id), rather than a separate sync per
// platform as you browse.
//
// Triggered explicitly from the Sync Progress screen — there's no auto-sync on
// screen entry. Re-entering screens never hits the network; lists render from
// Room. refreshPlatform() is the explicit single-platform refresh path.
@Singleton
class LibrarySyncManager @Inject constructor(
    private val platformRepository: PlatformRepository,
    private val collectionRepository: CollectionRepository,
    private val romRepository: RomRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<LibrarySyncState>(LibrarySyncState.Idle)
    val state: StateFlow<LibrarySyncState> = _state.asStateFlow()

    private var job: Job? = null

    // Fire-and-forget. No-op if a sweep is already running — callers observe `state`
    // to track progress. Use isRunning() to check if a sweep is in flight.
    fun refreshAll() {
        if (job?.isActive == true) return
        job = scope.launch { runSweep() }
    }

    fun isRunning(): Boolean = job?.isActive == true

    // Explicit single-platform refresh (full re-fetch + local deletion reconcile).
    suspend fun refreshPlatform(platformId: Int) {
        romRepository.syncPlatform(platformId)
    }

    // Sync is always triggered by the user (from the Sync Progress screen), so:
    //   - the ROM sweep uses updated_after (incremental); first run with no cursor
    //     is a full fetch automatically;
    //   - reconciliation runs unconditionally — the user asked for fresh data, so
    //     pay the whole-server id fetch to catch deletions.
    private suspend fun runSweep() {
        _state.value = LibrarySyncState.Syncing(SyncPhase.Platforms, 0, 0)
        try {
            platformRepository.sync()
            // Collections piggyback on the Platforms phase — small payload, same
            // "metadata" tier as platforms; no need for a separate UI step.
            runCatching { collectionRepository.sync() }
            _state.value = LibrarySyncState.Syncing(SyncPhase.Roms, 0, 0)
            romRepository.syncAllRoms { done, total, current, rows ->
                _state.value = LibrarySyncState.Syncing(SyncPhase.Roms, done, total, current, rows)
            }
            _state.value = LibrarySyncState.Idle
        } catch (e: Exception) {
            _state.value = LibrarySyncState.Error(e.message ?: "Library sync failed")
        }
    }
}
